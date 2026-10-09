package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 运行端只读请求内的重复查询去重：同一次请求里对同一应用头、同一对象版本、同一授权上限、同一张物理表的读取只执行一次， 结果与去重前完全一致；作用域外（写入路径）逐次读取；每个事务仍至少取一次锁。
 *
 * <p>夹具仿资金流水：带引用（列表补标签时再次解析来源对象）和读取时计算的累计余额（逐行再次读取授权上限与表结构）。
 */
class ReadRequestDedupIntegrationTest {
    private static final long OWNER = 10001;
    private static final long MEMBER = 20002;

    /** 被去重的语句：同一参数在一次只读请求的同一事务里只应出现一次。 */
    private static final Set<String> DEDUPED =
            Set.of(
                    "ApplicationMapper.lock",
                    "ApplicationMapper.automationCatalogLock",
                    "ApplicationMapper.version",
                    "ApplicationMapper.automationSnapshots",
                    "ObjectDraftMapper.selectHead",
                    "DataCenterMapper.versions",
                    "DataCenterMapper.versionSchema",
                    "ObjectApplicationGrantMapper.find",
                    "ApplicationAccessMapper.policy",
                    "PostgreSqlDatabaseMetadataMapper.selectRelation",
                    "PostgreSqlDatabaseMetadataMapper.selectColumns",
                    "PostgreSqlDatabaseMetadataMapper.selectConstraints",
                    "PostgreSqlDatabaseMetadataMapper.selectIndexes",
                    "PostgreSqlDatabaseMetadataMapper.selectTriggers",
                    "PostgreSqlDatabaseMetadataMapper.selectStatistics");

    private FieldRuleFixture f;
    private DataCenter.Definition company;
    private DataCenter.Definition bank;
    private DataCenter.Definition fund;
    private String app;
    private List<Row> funds;

    @BeforeAll
    static void open() throws Exception {
        connect();
        session.getSqlSessionFactory().getConfiguration().addInterceptor(new StatementLedger());
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        f = new FieldRuleFixture();
        company =
                f.object(
                        "company",
                        List.of(field("acct", "默认账号", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        bank =
                f.object(
                        "bank",
                        List.of(
                                field("no", "账号", "TEXT"),
                                field("owner", "户名", "TEXT"),
                                field("copy", "默认账号", "TEXT")),
                        Map.of(),
                        List.of(reference("company", company)),
                        List.of());
        fund =
                f.object(
                        "fund",
                        List.of(
                                field("account", "账户编码", "TEXT"),
                                field("seq", "序号", "INTEGER"),
                                field("income", "收入", "DECIMAL", 18, 2),
                                field("balance", "余额", "FORMULA")),
                        Map.of("balance", runningTotal()),
                        List.of(reference("bank", bank)),
                        List.of());
        app = application();
        Row first =
                f.save(
                        app,
                        company,
                        values(id(company, "name"), "甲公司", id(company, "acct"), "A-1"));
        Row second =
                f.save(
                        app,
                        company,
                        values(id(company, "name"), "乙公司", id(company, "acct"), "A-2"));
        List<Row> banks = new ArrayList<>();
        for (Row owner : List.of(first, second))
            banks.add(
                    f.save(
                            app,
                            bank,
                            values(
                                    id(bank, "name"),
                                    "账户" + owner.id(),
                                    id(bank, "no"),
                                    "N-" + owner.id(),
                                    relationField(bank, "company"),
                                    owner.id())));
        funds = new ArrayList<>();
        for (int index = 1; index <= 5; index++)
            funds.add(
                    f.save(
                            app,
                            fund,
                            values(
                                    id(fund, "name"),
                                    "流水" + index,
                                    id(fund, "account"),
                                    index % 2 == 0 ? "A" : "B",
                                    id(fund, "seq"),
                                    index,
                                    id(fund, "income"),
                                    Integer.toString(index * 100),
                                    relationField(fund, "bank"),
                                    banks.get(index % 2).id())));
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private static DataCenter.FieldOptions runningTotal() {
        return new DataCenter.FieldOptions(
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
                        "RUNNING_TOTAL",
                        "LIVE",
                        null,
                        null,
                        "income",
                        "SUM",
                        "AND",
                        List.of(),
                        false,
                        List.of("account"),
                        new CalculationOptions.RunningTotal("seq", null, null, "0", null),
                        null));
    }

    /** 应用带一个数据视图和一个统计；创建人之外另有一名只读成员，覆盖成员授权的读取路径。 */
    private String application() {
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        List<ApplicationCenter.ObjectReference> references = new ArrayList<>();
        for (DataCenter.Definition d : List.of(company, bank, fund)) {
            DataObjectApi.PublishedObject version = api.getVersion(d.objectId(), null);
            references.add(
                    new ApplicationCenter.ObjectReference(
                            version.objectId(), version.versionNo(), version.checksum()));
        }
        ApplicationUi.View view =
                new ApplicationUi.View(
                        fund.objectId(),
                        fund.fields().stream().map(FieldDefinition::id).toList(),
                        Map.of(),
                        id(fund, "seq"),
                        false,
                        20,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        null);
        ApplicationReports.Config report =
                new ApplicationReports.Config(
                        fund.objectId(),
                        List.of(
                                new ApplicationReports.Dimension(
                                        id(fund, "account"), null, "VALUE")),
                        List.of(new ApplicationReports.Metric("count", "笔数", "COUNT", null)),
                        Map.of(),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "TABLE",
                        null,
                        false,
                        30,
                        null);
        ApplicationCenter.Detail saved =
                f.applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                f.prefix() + "dedup",
                                "只读去重验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        references,
                                        List.of(
                                                resource("fund_view", "VIEW", view),
                                                resource("fund_report", "REPORT", report)))),
                        OWNER);
        String id = saved.application().id();
        grantApplicationObjects(id);
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        // 累计余额在本对象内取数：授予应用全部字段的计算取数权限。
        ObjectSharing.Grant previous =
                sharing.forApplication(id).stream()
                        .filter(item -> item.objectId().equals(fund.objectId()))
                        .findFirst()
                        .orElseThrow();
        ApplicationAuthorization.ObjectGrant ceiling =
                sharing.storedPermission(fund.objectId(), id);
        sharing.save(
                new ObjectSharing.Save(
                        fund.objectId(),
                        id,
                        previous.revision(),
                        new ApplicationAuthorization.ObjectGrant(
                                ceiling.objectId(),
                                ceiling.actions(),
                                ceiling.scope(),
                                ceiling.readFields(),
                                ceiling.writeFields(),
                                ceiling.readDetails(),
                                ceiling.writeDetails(),
                                ceiling.readRelations(),
                                ceiling.writeRelations(),
                                Map.of(),
                                fund.fields().stream()
                                        .map(FieldDefinition::id)
                                        .collect(Collectors.toSet())),
                        "累计余额计算取数"),
                OWNER);
        f.applications.publish(new ApplicationCenter.Revision(id, 0, "只读去重验证"), OWNER);
        ApplicationAuthorizationService authorization =
                servicesContext.getBean(ApplicationAuthorizationService.class);
        List<ApplicationAuthorization.ObjectGrant> grants = new ArrayList<>();
        for (DataCenter.Definition d : List.of(company, bank, fund))
            grants.add(
                    new ApplicationAuthorization.ObjectGrant(
                            d.objectId(),
                            Set.of("READ"),
                            "ALL",
                            d.fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(Collectors.toSet()),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of()));
        authorization.save(
                new ApplicationAuthorization.Save(
                        id,
                        authorization.get(id).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER", Long.toString(MEMBER), grants))),
                OWNER);
        return id;
    }

    private static ApplicationCenter.Resource resource(String id, String kind, Object config) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = mapper.convertValue(config, Map.class);
        return new ApplicationCenter.Resource(id, kind, id, id, values);
    }

    private Query viewPage() {
        return new Query(
                app, fund.objectId(), 1, 20, null, Map.of(), null, false, "fund_view", null, null);
    }

    /** 同一只读调用分别在作用域外、作用域内执行：结果必须一致，作用域内被去重的语句同一参数只出现一次。 */
    private <T> Map<String, Integer> measured(Supplier<T> call) {
        T plain = call.get();
        Map<String, Integer> unscoped = StatementLedger.record(call::get);
        List<T> scoped = new ArrayList<>();
        Map<String, Integer> ledger =
                StatementLedger.record(() -> scoped.add(ReadRequestMemo.within(call)));
        assertThat(ReadRequestMemo.active()).isFalse();
        assertThat(scoped.getFirst()).usingRecursiveComparison().isEqualTo(plain);
        // 去重只省略重复读取：其余语句（业务数据、逐行检查、计数）的执行次数与去重前逐一相同。
        Map<String, Integer> before = StatementLedger.totals(unscoped);
        Map<String, Integer> after = StatementLedger.totals(ledger);
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            if (DEDUPED.contains(entry.getKey()))
                assertThat(after.get(entry.getKey()))
                        .as(entry.getKey())
                        .isLessThanOrEqualTo(entry.getValue());
            else
                assertThat(after.get(entry.getKey()))
                        .as(entry.getKey())
                        .isEqualTo(entry.getValue());
        }
        // 开启作用域的入口必须是纯读取：记忆的前提是请求内没有任何写入会让已读到的定义、授权或表结构过期。
        assertThat(StatementLedger.total(unscoped, StatementLedger.WRITE)).isZero();
        assertThat(StatementLedger.total(ledger, StatementLedger.WRITE)).isZero();
        // 夹具确实存在重复读取，否则下面的「只出现一次」没有检出力。
        assertThat(repeated(unscoped)).isNotEmpty();
        return ledger;
    }

    /** 被去重语句里同一参数执行超过一次的条目。 */
    private static Map<String, Integer> repeated(Map<String, Integer> ledger) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ledger.forEach(
                (key, count) -> {
                    if (count > 1 && DEDUPED.contains(StatementLedger.statementOf(key)))
                        result.put(key, count);
                });
        return result;
    }

    private static void assertSingleTransactionRead(Map<String, Integer> ledger) {
        assertThat(repeated(ledger)).isEmpty();
        // 整个请求在一个事务里：目录共享锁、应用头共享锁、发布版本各只执行一次，且确实执行过。
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.automationCatalogLock"))
                .isEqualTo(1);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.lock")).isEqualTo(1);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.version")).isEqualTo(1);
    }

    @Test
    void viewPageReadsEachDefinitionGrantAndTableOnce() {
        for (long actor : List.of(OWNER, MEMBER)) {
            Map<String, Integer> ledger = measured(() -> f.runtime.page(viewPage(), actor));
            assertSingleTransactionRead(ledger);
            // 列表本身、引用标签的来源对象各一个固定版本；累计余额逐行取数不再重复读取授权上限和表结构。
            assertThat(StatementLedger.total(ledger, "DataCenterMapper.versionSchema"))
                    .isEqualTo(2);
            assertThat(StatementLedger.total(ledger, "ObjectApplicationGrantMapper.find"))
                    .isEqualTo(2);
            assertThat(
                            StatementLedger.total(
                                    ledger, "PostgreSqlDatabaseMetadataMapper.selectColumns"))
                    .isEqualTo(2);
            assertThat(StatementLedger.total(ledger, "RecordMapper.runningTotal")).isEqualTo(5);
        }
        PageResult<Row> page = ReadRequestMemo.within(() -> f.runtime.page(viewPage(), MEMBER));
        assertThat(page.getList())
                .extracting(row -> row.values().get(id(fund, "balance")).toString())
                .containsExactly(
                        "100.0000000000",
                        "200.0000000000",
                        "400.0000000000",
                        "600.0000000000",
                        "900.0000000000");
    }

    @Test
    void memberAuthorizationPolicyIsReadOnce() {
        Map<String, Integer> unscoped =
                StatementLedger.record(() -> f.runtime.page(viewPage(), MEMBER));
        Map<String, Integer> ledger =
                StatementLedger.record(
                        () -> ReadRequestMemo.within(() -> f.runtime.page(viewPage(), MEMBER)));
        assertThat(StatementLedger.total(unscoped, "ApplicationAccessMapper.policy"))
                .isGreaterThan(2);
        // 成员授权配置按应用读取一次；入口检查与各对象的授权解析共用这一份。
        assertThat(StatementLedger.total(ledger, "ApplicationAccessMapper.policy")).isEqualTo(1);
    }

    @Test
    void modelGetSelectionAndReportAreDedupedWithIdenticalResults() {
        for (long actor : List.of(OWNER, MEMBER)) {
            assertSingleTransactionRead(
                    measured(() -> f.runtime.model(app, fund.objectId(), actor)));
            assertSingleTransactionRead(
                    measured(
                            () ->
                                    f.runtime.get(
                                            app, fund.objectId(), funds.getFirst().id(), actor)));
            assertSingleTransactionRead(
                    measured(
                            () ->
                                    f.runtime.selection(
                                            new SelectionFields.Query(
                                                    app,
                                                    fund.objectId(),
                                                    null,
                                                    relationField(fund, "bank"),
                                                    null,
                                                    1,
                                                    100,
                                                    List.of(),
                                                    null,
                                                    null,
                                                    Map.of(),
                                                    true,
                                                    null),
                                            actor)));
            ApplicationReportService reports =
                    servicesContext.getBean(ApplicationReportService.class);
            // 正式环境里统计查询由 @Transactional 包在一个事务里；测试装配不处理该注解，这里显式开同样的事务。
            assertSingleTransactionRead(
                    measured(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    status ->
                                                            reports.query(
                                                                    new ApplicationReports.Query(
                                                                            app,
                                                                            "fund_report",
                                                                            null,
                                                                            null,
                                                                            null,
                                                                            null,
                                                                            null,
                                                                            1,
                                                                            20),
                                                                    actor))));
        }
    }

    /** 规则求值（数据联动）每次改字段都会调用：来源对象的定义、授权与表结构同样只读一次，求值结果不变。 */
    @Test
    void fieldRuleEvaluationIsDedupedWithIdenticalResults() {
        bank =
                rules(
                        bank,
                        id(bank, "copy"),
                        linkage(
                                company,
                                id(company, "acct"),
                                null,
                                List.of(formField(id(company, "name"), "eq", id(bank, "owner")))));
        for (long actor : List.of(OWNER, MEMBER)) {
            Supplier<FieldRules.Evaluation> evaluation =
                    () ->
                            f.evaluate(
                                    app,
                                    bank,
                                    values(id(bank, "owner"), "甲公司"),
                                    List.of(),
                                    List.of(),
                                    null,
                                    actor);
            assertSingleTransactionRead(measured(evaluation));
            FieldRules.Result copied = result(ReadRequestMemo.within(evaluation), id(bank, "copy"));
            assertThat(copied.state()).isEqualTo("APPLIED");
            assertThat(copied.value()).isEqualTo("A-1");
        }
    }

    /** 应用入口没有外层事务：对象版本、授权上限、成员授权按请求只读一次；应用头在入口检查和发布读取各自的短事务里各取一次锁。 */
    @Test
    void applicationEntryReadsEachObjectVersionOnce() {
        ApplicationRuntimeService entry = servicesContext.getBean(ApplicationRuntimeService.class);
        for (long actor : List.of(OWNER, MEMBER)) {
            Map<String, Integer> ledger = measured(() -> entry.application(app, actor));
            Map<String, Integer> again = new LinkedHashMap<>(repeated(ledger));
            again.keySet()
                    .removeIf(
                            key ->
                                    StatementLedger.statementOf(key)
                                            .equals("ApplicationMapper.lock"));
            assertThat(again).isEmpty();
            // 三个对象的固定版本都是当前发布版本：「固定版本」与「最新版本」两种问法共用一次读取。
            assertThat(StatementLedger.total(ledger, "ObjectDraftMapper.selectHead")).isEqualTo(3);
            assertThat(StatementLedger.total(ledger, "DataCenterMapper.versions")).isEqualTo(3);
            assertThat(StatementLedger.total(ledger, "DataCenterMapper.versionSchema"))
                    .isEqualTo(3);
            assertThat(StatementLedger.total(ledger, "ObjectApplicationGrantMapper.find"))
                    .isEqualTo(3);
            assertThat(StatementLedger.total(ledger, "ApplicationMapper.version")).isEqualTo(1);
            assertThat(StatementLedger.total(ledger, "ApplicationMapper.lock")).isBetween(1, 2);
        }
    }

    /** 固定版本落后于当前发布版本时，两种问法是两份不同的定义，各读一次，不能混用。 */
    @Test
    void pinnedAndLatestVersionsStayDistinctWhenTheyDiffer() {
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        int pinned = api.getVersion(company.objectId(), null).versionNo();
        f.republish(company, Map.of());
        assertThat(api.getVersion(company.objectId(), null).versionNo()).isEqualTo(pinned + 1);
        Map<String, Integer> ledger =
                StatementLedger.record(
                        () ->
                                ReadRequestMemo.within(
                                        () -> {
                                            assertThat(
                                                            api.getVersion(
                                                                            company.objectId(),
                                                                            pinned)
                                                                    .versionNo())
                                                    .isEqualTo(pinned);
                                            assertThat(
                                                            api.getVersion(company.objectId(), null)
                                                                    .versionNo())
                                                    .isEqualTo(pinned + 1);
                                            assertThat(
                                                            api.getVersion(
                                                                            company.objectId(),
                                                                            pinned)
                                                                    .versionNo())
                                                    .isEqualTo(pinned);
                                            assertThat(
                                                            api.getVersion(
                                                                            company.objectId(),
                                                                            pinned + 1)
                                                                    .versionNo())
                                                    .isEqualTo(pinned + 1);
                                            return null;
                                        }));
        // 两个版本各读取、解析一次；同一问法的重复调用不再读取。
        assertThat(StatementLedger.total(ledger, "ObjectDraftMapper.selectHead")).isEqualTo(2);
        assertThat(StatementLedger.total(ledger, "DataCenterMapper.versions")).isEqualTo(2);
        assertThat(StatementLedger.total(ledger, "DataCenterMapper.versionSchema")).isEqualTo(2);
    }

    /** 记忆键包含全部入参：同一请求里换操作者、换对象、换应用得到各自的结果，不会串用。 */
    @Test
    void rememberedValuesAreKeyedByEveryArgument() {
        ObjectSharingService sharing = servicesContext.getBean(ObjectSharingService.class);
        ApplicationAuthorizationService authorization =
                servicesContext.getBean(ApplicationAuthorizationService.class);
        String other = f.app(company);
        ReadRequestMemo.within(
                () -> {
                    for (int round = 0; round < 2; round++) {
                        assertThat(f.applications.isOwner(app, OWNER)).isTrue();
                        assertThat(f.applications.isOwner(app, MEMBER)).isFalse();
                        assertThat(sharing.storedPermission(fund.objectId(), app).objectId())
                                .isEqualTo(fund.objectId());
                        assertThat(sharing.storedPermission(bank.objectId(), app).objectId())
                                .isEqualTo(bank.objectId());
                        // 另一个应用没有引用资金流水：没有授权上限，空结果同样按键记住。
                        assertThat(sharing.storedPermission(fund.objectId(), other)).isNull();
                        assertThat(authorization.grants(app, fund.objectId(), MEMBER))
                                .extracting(ApplicationAuthorization.ObjectGrant::objectId)
                                .containsExactly(fund.objectId());
                        assertThat(authorization.grants(app, bank.objectId(), MEMBER))
                                .extracting(ApplicationAuthorization.ObjectGrant::objectId)
                                .containsExactly(bank.objectId());
                        assertThat(authorization.grants(app, null, MEMBER)).hasSize(3);
                        assertThat(authorization.grants(app, fund.objectId(), OWNER)).isEmpty();
                        assertThat(authorization.grants(other, null, MEMBER)).isEmpty();
                        assertThat(f.applications.published(app).application().id()).isEqualTo(app);
                        assertThat(f.applications.published(other).application().id())
                                .isEqualTo(other);
                    }
                    return null;
                });
    }

    /** 写入路径不开启作用域：发布读取、对象版本读取照旧逐次执行。 */
    @Test
    void outsideScopeEveryReadStillExecutes() {
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        Map<String, Integer> ledger =
                StatementLedger.record(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                status -> {
                                                    f.applications.published(app);
                                                    f.applications.published(app);
                                                    f.applications.isOwner(app, OWNER);
                                                    api.getVersion(fund.objectId(), null);
                                                    api.getVersion(fund.objectId(), null);
                                                }));
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.automationCatalogLock"))
                .isEqualTo(2);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.lock")).isEqualTo(3);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.version")).isEqualTo(2);
        assertThat(StatementLedger.total(ledger, "ObjectDraftMapper.selectHead")).isEqualTo(2);
        assertThat(StatementLedger.total(ledger, "DataCenterMapper.versionSchema")).isEqualTo(2);
    }

    /** 每个事务至少取一次锁：前一个事务或无事务段读到的值不能让后一个事务跳过取锁。 */
    @Test
    void everyTransactionTakesItsOwnLocks() {
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        Runnable reads =
                () -> {
                    f.applications.published(app);
                    f.applications.isOwner(app, OWNER);
                    f.applications.published(app);
                    api.getVersion(fund.objectId(), null);
                    api.getVersion(fund.objectId(), null);
                };
        Map<String, Integer> ledger =
                StatementLedger.record(
                        () ->
                                ReadRequestMemo.within(
                                        () -> {
                                            reads.run();
                                            for (int round = 0; round < 2; round++)
                                                new TransactionTemplate(manager)
                                                        .executeWithoutResult(
                                                                status -> reads.run());
                                            return null;
                                        }));
        // 无事务段：发布读取与归属检查各自的短事务各取一次；两个事务各取一次。
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.lock")).isEqualTo(4);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.automationCatalogLock"))
                .isEqualTo(3);
        assertThat(StatementLedger.total(ledger, "ObjectDraftMapper.selectHead")).isEqualTo(3);
    }

    /** 目录锁是事务级锁：没有事务时锁不会保留，作用域内每次调用都照常执行；独占锁在任何情况下都不省略。 */
    @Test
    void catalogLockIsOnlyRememberedAsSharedLockInsideATransaction() {
        com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog catalog =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.application.service.resource
                                .ApplicationAutomationCatalog.class);
        Map<String, Integer> ledger =
                StatementLedger.record(
                        () ->
                                ReadRequestMemo.within(
                                        () -> {
                                            catalog.lock(false);
                                            catalog.lock(false);
                                            new TransactionTemplate(manager)
                                                    .executeWithoutResult(
                                                            status -> {
                                                                catalog.lock(false);
                                                                catalog.lock(false);
                                                                catalog.lock(true);
                                                                catalog.lock(true);
                                                            });
                                            catalog.lock(false);
                                            return null;
                                        }));
        Map<String, Integer> byMode = new LinkedHashMap<>();
        ledger.forEach(
                (key, count) -> {
                    if (StatementLedger.statementOf(key)
                            .equals("ApplicationMapper.automationCatalogLock"))
                        byMode.merge(
                                key.contains("exclusive=true") ? "exclusive" : "shared",
                                count,
                                Integer::sum);
                });
        // 无事务的三次各执行一次，事务内的共享锁两次调用只执行一次；独占锁两次调用执行两次。
        assertThat(byMode).containsOnly(entry("shared", 4), entry("exclusive", 2));
    }

    /** 真实数据源事务管理器上的分段：回滚到保存点后重新取锁、读取；挂起后的新事务自己取锁；外层恢复后沿用原值。 */
    @Test
    void realTransactionBoundariesSegmentTheMemo() {
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        TransactionTemplate nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        TransactionTemplate fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Map<String, Integer> ledger =
                StatementLedger.record(
                        () ->
                                ReadRequestMemo.within(
                                        () -> {
                                            new TransactionTemplate(manager)
                                                    .executeWithoutResult(
                                                            outer -> {
                                                                f.applications.published(app);
                                                                nested.executeWithoutResult(
                                                                        inner -> {
                                                                            f.applications
                                                                                    .published(app);
                                                                            api.getVersion(
                                                                                    fund.objectId(),
                                                                                    null);
                                                                            inner.setRollbackOnly();
                                                                        });
                                                                // 保存点之后取得的对象头锁已随回滚释放：必须重新取。
                                                                api.getVersion(
                                                                        fund.objectId(), null);
                                                                f.applications.published(app);
                                                                fresh.executeWithoutResult(
                                                                        inner -> {
                                                                            f.applications
                                                                                    .published(app);
                                                                            f.applications
                                                                                    .published(app);
                                                                        });
                                                                f.applications.published(app);
                                                            });
                                            return null;
                                        }));
        assertThat(StatementLedger.total(ledger, "ObjectDraftMapper.selectHead")).isEqualTo(2);
        // 外层首次、保存点回滚之后、挂起后的新事务各一次；嵌套段内与恢复后的重复读取都被省略。
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.lock")).isEqualTo(3);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.automationCatalogLock"))
                .isEqualTo(3);
        assertThat(StatementLedger.total(ledger, "ApplicationMapper.version")).isEqualTo(3);
    }

    /** 并发：记忆命中后锁仍然由当前事务持有，发布方必须等事务结束；事务结束即释放。 */
    @Test
    void locksAreStillHeldWhileRepeatedReadsAreSkipped() throws Exception {
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        String applicationRow =
                "SELECT id FROM public.nocode_application WHERE id=" + app + " FOR UPDATE NOWAIT";
        String objectRow =
                "SELECT id FROM public.nocode_object WHERE id="
                        + fund.objectId()
                        + " FOR UPDATE NOWAIT";
        String catalog =
                "SELECT pg_try_advisory_xact_lock(hashtextextended('nocode-automation-catalog',0))";
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            ReadRequestMemo.within(
                    () -> {
                        new TransactionTemplate(manager)
                                .executeWithoutResult(
                                        status -> {
                                            for (int round = 0; round < 3; round++) {
                                                f.applications.published(app);
                                                api.getVersion(fund.objectId(), null);
                                            }
                                            assertThat(probe(other, applicationRow))
                                                    .isEqualTo("55P03");
                                            assertThat(probe(other, objectRow)).isEqualTo("55P03");
                                            assertThat(probe(other, catalog)).isEqualTo("f");
                                        });
                        return null;
                    });
            assertThat(probe(other, applicationRow)).isEqualTo(app);
            assertThat(probe(other, objectRow)).isEqualTo(fund.objectId());
            assertThat(probe(other, catalog)).isEqualTo("t");
        } finally {
            other.shutdownNow();
        }
    }

    /** 在另一条连接、另一个线程上试取独占锁；返回首列文本，被锁挡住时返回 SQLSTATE。 */
    private static String probe(ExecutorService other, String sql) {
        try {
            return other.submit(
                            () -> {
                                try (Connection connection = ds.getConnection()) {
                                    connection.setAutoCommit(false);
                                    try (Statement statement = connection.createStatement();
                                            ResultSet rows = statement.executeQuery(sql)) {
                                        return rows.next() ? rows.getString(1) : null;
                                    } catch (SQLException blocked) {
                                        return blocked.getSQLState();
                                    } finally {
                                        connection.rollback();
                                    }
                                }
                            })
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** 并发发布：事务内的只读请求挡住发布直到事务结束；无事务的请求以首次读取为准，下一个请求读到新版本。 */
    @Test
    void concurrentPublishWaitsForTransactionAndNextRequestSeesIt() throws Exception {
        int before = f.applications.published(app).versionNo();
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> published =
                    ReadRequestMemo.within(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    status -> {
                                                        assertThat(
                                                                        f.applications
                                                                                .published(app)
                                                                                .versionNo())
                                                                .isEqualTo(before);
                                                        Future<Integer> pending =
                                                                publisher.submit(this::republish);
                                                        assertThatThrownBy(
                                                                        () ->
                                                                                pending.get(
                                                                                        1500,
                                                                                        TimeUnit
                                                                                                .MILLISECONDS))
                                                                .isInstanceOf(
                                                                        TimeoutException.class);
                                                        // 发布被挡住期间，重复读取仍是同一份发布版本。
                                                        assertThat(
                                                                        f.applications
                                                                                .published(app)
                                                                                .versionNo())
                                                                .isEqualTo(before);
                                                        return pending;
                                                    }));
            assertThat(published.get(30, TimeUnit.SECONDS)).isEqualTo(before + 1);
            ReadRequestMemo.within(
                    () -> {
                        assertThat(f.applications.published(app).versionNo()).isEqualTo(before + 1);
                        try {
                            assertThat(publisher.submit(this::republish).get(30, TimeUnit.SECONDS))
                                    .isEqualTo(before + 2);
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                        // 无事务的同一请求以首次读取为准。
                        assertThat(f.applications.published(app).versionNo()).isEqualTo(before + 1);
                        return null;
                    });
            assertThat(
                            ReadRequestMemo.<Integer>within(
                                    () -> f.applications.published(app).versionNo()))
                    .isEqualTo(before + 2);
        } finally {
            publisher.shutdownNow();
        }
    }

    private int republish() {
        ApplicationCenter.Detail current = f.applications.get(app);
        return f.applications
                .publish(
                        new ApplicationCenter.Revision(
                                app, current.application().revision(), "并发发布"),
                        OWNER)
                .application()
                .publishedVersion();
    }

    /** 两个并发请求各有自己的作用域，互不读取对方的值。 */
    @Test
    void concurrentRequestsDoNotShareWhatTheyRead() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier together = new CyclicBarrier(2);
        try {
            List<Future<Map<String, Integer>>> results = new ArrayList<>();
            for (long actor : List.of(OWNER, MEMBER))
                results.add(
                        pool.submit(
                                () -> {
                                    together.await(10, TimeUnit.SECONDS);
                                    return StatementLedger.record(
                                            () ->
                                                    ReadRequestMemo.within(
                                                            () ->
                                                                    f.runtime.page(
                                                                            viewPage(), actor)));
                                }));
            for (Future<Map<String, Integer>> result : results)
                assertSingleTransactionRead(result.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
