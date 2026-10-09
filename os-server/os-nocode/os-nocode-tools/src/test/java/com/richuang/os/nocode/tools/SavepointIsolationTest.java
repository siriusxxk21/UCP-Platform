package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.api.SaveObjectDraft;
import com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.richuang.os.nocode.application.dal.mapper.ApplicationMapper;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 底座行为回归：对象发布事务里用保存点逐个替应用「同步 + 发布」时，单个应用的失败能否被隔离。
 *
 * <p>自动跟随（应用跟随对象最新版本）建立在这几条行为之上：内层参与事务抛错后把外层标成只能回滚的标记，会在回滚保存点时被清掉； 保存点回滚不丢外层已持有的事务级咨询锁；MyBatis
 * 一级缓存在回滚保存点后要等下一条写语句（或带 flushCache 的查询）才清。 底座（Spring 事务管理器、多数据源 starter、MyBatis）升级后这些用例必须仍然通过。
 */
class SavepointIsolationTest extends NocodeIntegrationSupport {
    private String scratch;
    private final List<Long> applications = new ArrayList<>();

    @BeforeEach
    void scratchTable() {
        scratch = prefix + "_sp";
        jdbc.execute(
                "CREATE TABLE public.\""
                        + scratch
                        + "\" (id integer PRIMARY KEY, note varchar(40))");
    }

    @AfterEach
    void dropScratch() {
        jdbc.execute("DROP TABLE IF EXISTS public.\"" + scratch + "\"");
        for (Long id : applications)
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        applications.clear();
    }

    private TransactionTemplate outer() {
        return new TransactionTemplate(manager);
    }

    private TransactionTemplate nested() {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return template;
    }

    private void insert(int id, String note) {
        jdbc.update("INSERT INTO public.\"" + scratch + "\" (id,note) VALUES (?,?)", id, note);
    }

    private List<Integer> rows() {
        return jdbc.queryForList(
                "SELECT id FROM public.\"" + scratch + "\" ORDER BY id", Integer.class);
    }

    /** P1-①：内层调了一个自己又包了一层事务并抛业务异常的服务方法。 */
    @Test
    void nestedTemplateIsolatesBusinessFailureRaisedInsideParticipatingTransaction() {
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        List<Throwable> caught = new ArrayList<>();
        assertThatCode(
                        () ->
                                outer().executeWithoutResult(
                                                status -> {
                                                    insert(1, "outer-before");
                                                    try {
                                                        nested().executeWithoutResult(
                                                                        inner -> {
                                                                            insert(2, "inner");
                                                                            // 自带
                                                                            // transaction.execute，在其中抛业务异常
                                                                            objects.getVersion(
                                                                                    "999999999999999",
                                                                                    null);
                                                                        });
                                                    } catch (ServiceException failure) {
                                                        caught.add(failure);
                                                    }
                                                    assertThat(status.isRollbackOnly())
                                                            .as("回滚保存点后外层不应被标成只能回滚")
                                                            .isFalse();
                                                    insert(3, "outer-after");
                                                }))
                .as("外层提交不应报「事务已被标记为只能回滚」")
                .doesNotThrowAnyException();
        assertThat(caught).hasSize(1);
        assertThat(rows()).as("外层两行都在、内层那一行不在").containsExactly(1, 3);
    }

    /** P1-②：内层制造数据库层面的错误（违反主键唯一）。 */
    @Test
    void nestedTemplateIsolatesDatabaseError() {
        List<Throwable> caught = new ArrayList<>();
        assertThatCode(
                        () ->
                                outer().executeWithoutResult(
                                                status -> {
                                                    insert(1, "outer-before");
                                                    try {
                                                        nested().executeWithoutResult(
                                                                        inner -> {
                                                                            insert(2, "inner");
                                                                            insert(1, "duplicate");
                                                                        });
                                                    } catch (DataAccessException failure) {
                                                        caught.add(failure);
                                                    }
                                                    assertThat(status.isRollbackOnly()).isFalse();
                                                    insert(3, "outer-after");
                                                }))
                .doesNotThrowAnyException();
        assertThat(caught).hasSize(1);
        assertThat(rows()).containsExactly(1, 3);
    }

    /** P1-③：连续两个保存点，第一个失败、第二个成功；第二个的写入要留下。 */
    @Test
    void laterSavepointSurvivesEarlierFailure() {
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        outer().executeWithoutResult(
                        status -> {
                            insert(1, "outer-before");
                            try {
                                nested().executeWithoutResult(
                                                inner -> {
                                                    insert(2, "first-inner");
                                                    objects.getVersion("999999999999999", null);
                                                });
                            } catch (ServiceException expected) {
                                // 第一个应用失败
                            }
                            nested().executeWithoutResult(inner -> insert(4, "second-inner"));
                            insert(3, "outer-after");
                        });
        assertThat(rows()).containsExactly(1, 3, 4);
    }

    private long application(String suffix) {
        ApplicationMapper store = session.getMapper(ApplicationMapper.class);
        NocodeApplicationDO app = new NocodeApplicationDO();
        app.setAppCode(prefix + suffix);
        app.setAppName("保存点夹具" + suffix);
        app.setCategory("");
        app.setStatus("ACTIVE");
        app.setDesignJson("{\"objects\":[],\"resources\":[]}");
        store.create(app, "10001");
        applications.add(app.getId());
        return app.getId();
    }

    /**
     * P2：MyBatis 一级缓存在保存点回滚后的表现。内层对应用头做 UPDATE，再用不带 flushCache 的查询读它；回滚保存点后外层用同一条查询再读。
     *
     * <p>结论钉在断言里：回滚后不先执行写语句 ⇒ 读到的是保存点里的旧缓存（脏）；先执行一条写语句 ⇒ 干净。
     */
    @Test
    void firstLevelCacheIsStaleAfterSavepointRollbackUntilNextWrite() {
        ApplicationMapper store = session.getMapper(ApplicationMapper.class);
        long first = application("c1"), second = application("c2");
        outer().executeWithoutResult(
                        status -> {
                            assertThat(store.selectById(first).getStatus()).isEqualTo("ACTIVE");
                            try {
                                nested().executeWithoutResult(
                                                inner -> {
                                                    store.status(first, "DISABLED", "10001");
                                                    assertThat(store.selectById(first).getStatus())
                                                            .isEqualTo("DISABLED");
                                                    throw new IllegalStateException("回滚保存点");
                                                });
                            } catch (IllegalStateException expected) {
                                // 保存点已回滚
                            }
                            String database =
                                    jdbc.queryForObject(
                                            "SELECT status FROM public.nocode_application WHERE"
                                                    + " id=?",
                                            String.class,
                                            first);
                            assertThat(database).as("数据库里已回到保存点之前").isEqualTo("ACTIVE");
                            // 第一种：回滚后没有先执行任何写语句
                            assertThat(store.selectById(first).getStatus())
                                    .as("没有写语句清缓存时，同一条查询读到保存点里的旧缓存")
                                    .isEqualTo("DISABLED");
                            // 第二种：回滚后先执行一条写语句（改的是另一行）
                            store.status(second, "DISABLED", "10001");
                            assertThat(store.selectById(first).getStatus())
                                    .as("任意一条写语句之后缓存被清，读到真实值")
                                    .isEqualTo("ACTIVE");
                            // 带 flushCache 的查询（应用头行锁）本身也读真实值
                            assertThat(store.lock(first, true).getStatus()).isEqualTo("ACTIVE");
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM public.nocode_application WHERE id=?",
                                String.class,
                                first))
                .isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM public.nocode_application WHERE id=?",
                                String.class,
                                second))
                .isEqualTo("DISABLED");
    }

    /** 另开一个连接「尝试加锁」：拿得到返回 true（语句结束即释放），拿不到返回 false。 */
    private boolean tryLock(String name) {
        try (Connection other = ds.getConnection();
                Statement statement = other.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT pg_try_advisory_xact_lock(hashtextextended('"
                                        + name
                                        + "',0))")) {
            result.next();
            return result.getBoolean(1);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** P3：事务管理器允许嵌套事务；保存点回滚后，外层已拿到的目录独占锁与设计写锁仍然持有。 */
    @Test
    void advisoryLocksTakenBeforeSavepointSurviveItsRollback() {
        System.out.println("[P3] 事务管理器 = " + manager.getClass().getName());
        assertThat(manager).isInstanceOf(AbstractPlatformTransactionManager.class);
        assertThat(((AbstractPlatformTransactionManager) manager).isNestedTransactionAllowed())
                .isTrue();
        ApplicationMapper store = session.getMapper(ApplicationMapper.class);
        String catalog = "nocode-automation-catalog", design = "nocode-design-write";
        assertThat(tryLock(catalog)).as("对照组：事务外锁是空闲的").isTrue();
        assertThat(tryLock(design)).isTrue();
        outer().executeWithoutResult(
                        status -> {
                            store.automationCatalogLock(true);
                            draftMapper.lockTableName(design);
                            assertThat(tryLock(catalog)).as("外层持有目录独占锁").isFalse();
                            assertThat(tryLock(design)).as("外层持有设计写锁").isFalse();
                            try {
                                nested().executeWithoutResult(
                                                inner -> {
                                                    insert(1, "inner");
                                                    insert(1, "duplicate");
                                                });
                            } catch (DataAccessException expected) {
                                // 保存点已回滚
                            }
                            assertThat(tryLock(catalog)).as("保存点回滚后目录独占锁仍在").isFalse();
                            assertThat(tryLock(design)).as("保存点回滚后设计写锁仍在").isFalse();
                        });
        assertThat(tryLock(catalog)).as("外层提交后锁释放").isTrue();
        assertThat(tryLock(design)).isTrue();
    }

    /** P5：同一事务内，对象头移动到新版本之后，DataObjectApi.getVersion(对象, null) 取到的是新版本号与新校验和。 */
    @Test
    void latestVersionIsVisibleInsidePublishingTransactionAfterHeadMoves() {
        SaveObjectDraft request = createRequest("sp");
        Design design =
                designs.save(
                        new SaveDesign(
                                request,
                                Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        String id = design.draft().id();
        publisher.execute(
                new ExecutePlan(
                        publisher
                                .plan(new Revision(id, design.draft().lockVersion(), null), 10001)
                                .id(),
                        "保存点夹具初始发布"),
                10001);
        Design published = designs.get(id);
        designs.editPublished(new Revision(id, published.draft().lockVersion(), null), 10001);
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        DataObjectApi.PublishedObject before = objects.getVersion(id, null);
        assertThat(before.versionNo()).isEqualTo(1);
        outer().executeWithoutResult(
                        status -> {
                            ObjectDraftHeadDO head = designs.head(id, true);
                            assertThat(head.getLatestVersionNo()).isEqualTo(2);
                            String checksum = centerMapper.versionChecksum(head.getVersionId());
                            // 先读一次旧版本，让本会话的一级缓存里有旧值
                            assertThat(objects.getVersion(id, null).versionNo()).isEqualTo(1);
                            assertThat(
                                            centerMapper.publishVersion(
                                                    head.getId(), head.getVersionId(), 10001))
                                    .isEqualTo(1);
                            centerMapper.publishHead(head.getId(), 10001);
                            DataObjectApi.PublishedObject after = objects.getVersion(id, null);
                            assertThat(after.versionNo()).as("同一事务内取到新版本号").isEqualTo(2);
                            assertThat(after.checksum()).as("同一事务内取到新校验和").isEqualTo(checksum);
                            status.setRollbackOnly();
                        });
        assertThat(objects.getVersion(id, null).versionNo()).as("回滚后仍是旧版本").isEqualTo(1);
    }
}
