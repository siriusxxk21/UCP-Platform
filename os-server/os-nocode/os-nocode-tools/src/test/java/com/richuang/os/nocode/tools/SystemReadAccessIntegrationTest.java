package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.application.service.sharing.SystemReadAccess;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 计算取数不再让人勾（契约第 4 章）：应用对来源对象的授权满足「全部记录、含查看、查看无记录条件」且所需字段可查看 ⇒ 可以计算；否则拦，
 * 报错整段逐字说清是哪个对象的哪个字段、从哪个对象取数、去哪改。
 *
 * <p>夹具：「乙」的计算字段「合计」对「甲」里编码相同的记录求金额之和；应用引用两者，共享上限是首次引用时系统写入的默认值（六个清单都是「全部」、没有计算取数）。
 */
class SystemReadAccessIntegrationTest {
    private static final Set<String> ALL = Set.of("*");
    private FieldRuleFixture f;
    private ObjectSharingService sharing;
    private SystemReadAccess systemRead;
    private DataCenter.Definition jia, yi;
    private String app;
    private String applicationName;
    private Row target;
    private final long owner = 10001L;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        f = new FieldRuleFixture();
        sharing = servicesContext.getBean(ObjectSharingService.class);
        systemRead = servicesContext.getBean(SystemReadAccess.class);
        jia =
                f.object(
                        "jia",
                        List.of(
                                field("code", "编码", "TEXT"),
                                field("amount", "金额", "DECIMAL", 20, 4)),
                        Map.of(),
                        List.of(),
                        List.of());
        yi =
                f.object(
                        "yi",
                        List.of(field("code", "编码", "TEXT"), field("total", "合计", "FORMULA")),
                        Map.of(
                                "total",
                                new DataCenter.FieldOptions(
                                        null,
                                        "NORMAL",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "ACTIVE",
                                        List.of(),
                                        null,
                                        "DECIMAL",
                                        "NONE",
                                        null,
                                        false,
                                        false,
                                        null,
                                        new CalculationOptions(
                                                "LOOKUP",
                                                "LIVE",
                                                jia.objectId(),
                                                null,
                                                "amount",
                                                "SUM",
                                                "AND",
                                                List.of(
                                                        new CalculationOptions.Match(
                                                                "code", "eq", "code", null)),
                                                false,
                                                List.of(),
                                                null))),
                        List.of(),
                        List.of());
        // 不走夹具的显式全量授权：就用首次引用时系统写入的默认上限。
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        List<ApplicationCenter.ObjectReference> references = new ArrayList<>();
        for (DataCenter.Definition d : List.of(jia, yi)) {
            DataObjectApi.PublishedObject version = objects.getVersion(d.objectId(), null);
            references.add(
                    new ApplicationCenter.ObjectReference(
                            version.objectId(), version.versionNo(), version.checksum()));
        }
        applicationName = "取数验证";
        ApplicationCenter.Detail saved =
                f.applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                f.prefix() + "sysread",
                                applicationName,
                                null,
                                null,
                                new ApplicationCenter.Definition(references, List.of())),
                        owner);
        app = saved.application().id();
        f.applications.publish(
                new ApplicationCenter.Revision(app, saved.application().revision(), "取数验证"), owner);
        f.save(
                app,
                jia,
                values(id(jia, "name"), "一", id(jia, "code"), "A", id(jia, "amount"), "3"));
        f.save(
                app,
                jia,
                values(id(jia, "name"), "二", id(jia, "code"), "A", id(jia, "amount"), "4"));
        f.save(
                app,
                jia,
                values(id(jia, "name"), "三", id(jia, "code"), "B", id(jia, "amount"), "100"));
        target = f.save(app, yi, values(id(yi, "name"), "订单", id(yi, "code"), "A"));
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private void share(ObjectGrant permission) {
        int revision =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(jia.objectId()))
                        .findFirst()
                        .map(ObjectSharing.Grant::revision)
                        .orElse(0);
        sharing.save(
                new ObjectSharing.Save(jia.objectId(), app, revision, permission, "取数验证"), owner);
    }

    private Object total() {
        return f.runtime
                .get(app, yi.objectId(), target.id(), owner)
                .record()
                .values()
                .get(id(yi, "total"));
    }

    private String message(String reason, String action) {
        return "「对象yi」的「合计」要从「对象jia」取数，但现在取不了："
                + reason
                + "。\n去哪改：应用中心 → "
                + applicationName
                + " → 已引用对象 →「对象jia」这一行 → 配置数据权限 → "
                + action
                + "。\n没有数据权限管理权限时，请联系数据管理员处理。";
    }

    /** 默认上限没有任何计算取数的内容，查找取值照常出值；全程没有人勾过。 */
    @Test
    void lookupWorksWithoutAnyComputeFields() {
        ObjectGrant stored = sharing.storedPermission(jia.objectId(), app);
        assertThat(stored.computeFields()).isEmpty();
        assertThat(stored.readFields()).containsExactly("*");
        assertThat(target.values()).containsEntry(id(yi, "total"), "7.0000000000");
        assertThat(total()).isEqualTo("7.0000000000");
        assertThat(systemRead.check(app, jia, Set.of(id(jia, "amount"), id(jia, "code")))).isNull();
    }

    /** 上限是显式清单且包含所需字段、没有计算取数：同样可以计算。 */
    @Test
    void explicitListCoveringTheSourceFieldsIsEnough() {
        share(
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(id(jia, "amount"), id(jia, "code")),
                        Set.of(),
                        Set.of(),
                        Set.of()));
        assertThat(total()).isEqualTo("7.0000000000");
    }

    /** 所需字段为空时不做字段判定：前三条满足就通过，返回的只读授权是展开后的。 */
    @Test
    void emptyRequiredFieldsPassWhenScopeAllows() {
        assertThat(systemRead.check(app, jia, Set.of())).isNull();
        assertThat(systemRead.check(app, jia, null)).isNull();
        ObjectGrant grant = systemRead.require(app, jia, Set.of(), "测试");
        assertThat(grant.actions()).containsExactly("READ");
        assertThat(grant.scope()).isEqualTo("ALL");
        assertThat(grant.readFields())
                .containsExactlyInAnyOrder(id(jia, "name"), id(jia, "code"), id(jia, "amount"));
        assertThat(grant.writeFields()).isEmpty();
        assertThat(grant.actionScopes()).isEmpty();
        share(
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of()));
        assertThat(systemRead.check(app, jia, Set.of())).as("可查看字段为空，但不需要任何字段").isNull();
    }

    @Test
    void notGrantedWhenTheGrantIsRevoked() {
        share(null);
        SystemReadAccess.Denial denial = systemRead.check(app, jia, Set.of(id(jia, "amount")));
        assertThat(denial.code()).isEqualTo("NOT_GRANTED");
        assertThatThrownBy(this::total)
                .hasMessage(
                        message("「对象jia」还没有授权给应用「" + applicationName + "」（或授权已撤销）", "新增应用授权并保存"));
    }

    @Test
    void scopeDeniedWhenTheCeilingIsOwnRecordsOnly() {
        share(new ObjectGrant(jia.objectId(), Set.of("READ"), "OWN", ALL, Set.of(), ALL, Set.of()));
        assertThat(systemRead.check(app, jia, Set.of(id(jia, "amount"))).code()).isEqualTo("SCOPE");
        assertThatThrownBy(this::total)
                .hasMessage(
                        message(
                                "「对象jia」授给应用「"
                                        + applicationName
                                        + "」的记录范围是“当前操作者创建的记录”，系统计算必须能读全部记录",
                                "记录范围选“全部记录”"));
    }

    @Test
    void conditionDeniedWhenReadCarriesARecordCondition() {
        share(
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ"),
                        "ALL",
                        ALL,
                        Set.of(),
                        ALL,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "READ",
                                new DataScope(
                                        "AND",
                                        List.of(
                                                new DataScope.Condition(
                                                        id(jia, "code"), "eq", "A")),
                                        List.of())),
                        Set.of()));
        assertThat(systemRead.check(app, jia, Set.of(id(jia, "amount"))).code())
                .isEqualTo("CONDITION");
        assertThatThrownBy(this::total)
                .hasMessage(
                        message(
                                "「对象jia」授给应用「" + applicationName + "」的“查看”设了记录条件，系统计算不能带条件",
                                "在“高级设置”里取消“查看的记录条件”"));
    }

    /** 可查看字段是清单且缺所需字段：点名缺的每个字段（按对象里的字段顺序）。 */
    @Test
    void fieldDeniedNamesEveryMissingSourceField() {
        share(
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(id(jia, "name")),
                        Set.of(),
                        Set.of(),
                        Set.of()));
        assertThat(systemRead.check(app, jia, Set.of(id(jia, "amount"))).code()).isEqualTo("FIELD");
        assertThatThrownBy(this::total)
                .hasMessage(
                        message(
                                "「对象jia」没有把字段「编码、金额」授给应用「" + applicationName + "」",
                                "“可查看字段”选“全部”，或加上这些字段"));
        // 只缺一个：只点那一个。
        share(
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(id(jia, "name"), id(jia, "code")),
                        Set.of(),
                        Set.of(),
                        Set.of()));
        assertThatThrownBy(this::total)
                .hasMessage(
                        message(
                                "「对象jia」没有把字段「金额」授给应用「" + applicationName + "」",
                                "“可查看字段”选“全部”，或加上这些字段"));
    }

    /** 判定顺序：范围先于条件、条件先于字段。 */
    @Test
    void denialsAreReportedInOrder() {
        share(
                new ObjectGrant(
                        jia.objectId(),
                        Set.of("READ"),
                        "OWN",
                        Set.of(id(jia, "name")),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "READ",
                                new DataScope(
                                        "AND",
                                        List.of(
                                                new DataScope.Condition(
                                                        id(jia, "name"), "eq", "一")),
                                        List.of())),
                        Set.of()));
        assertThat(systemRead.check(app, jia, Set.of(id(jia, "amount"))).code()).isEqualTo("SCOPE");
    }
}
