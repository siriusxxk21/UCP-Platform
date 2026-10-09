package com.lingan.ucp.nocode.schema.service.publish;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;
import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.BusinessFilePublishInspection;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldConversions;
import com.lingan.ucp.nocode.api.ObjectApplicationUpgrade;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.DataCenterRows;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.FieldRuleValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;
import com.lingan.ucp.nocode.schema.service.compile.FieldConversionPlanner;
import com.lingan.ucp.nocode.schema.service.compile.SchemaCompiler;
import com.lingan.ucp.nocode.schema.service.selection.SelectionMigrationService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 发布计划绑定草稿修订、Schema 摘要、实际结构和关系目标。 PostgreSQL 事务保证 DDL、发布版本及基线同时提交；失败记录在回滚后独立保存。 */
@Service
public class SchemaPublishService {
    @Resource private SchemaPublishContext publishContext;

    @Resource
    private com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService
            orderedStates;

    @Resource private ObjectProvider<ObjectApplicationUpgrade> applicationUpgradeProvider;
    @Resource private ObjectProvider<BusinessFilePublishInspection> businessFileInspection;
    @Resource private SelectionMigrationService selectionMigrations;
    @Resource private FieldConversionPlanner conversions;
    @Resource private PlatformTransactionManager manager;
    @Resource private ObjectDesignService designs;
    @Resource private DataTableService tables;
    @Resource private SchemaCompiler compiler;
    @Resource private DataCenterMapper store;
    @Resource private ObjectDraftMapper objects;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper commands;
    private TransactionTemplate tx;
    private TransactionTemplate failureTx;

    /** 发布与失败留痕使用不同事务：结构变更整体回滚后，失败记录以 REQUIRES_NEW 独立保存。 */
    @PostConstruct
    void initialize() {
        this.tx = new TransactionTemplate(manager);
        this.failureTx = new TransactionTemplate(manager);
        failureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Contents(
            List<Step> changes,
            List<Check> checks,
            List<Dependency> dependencies,
            List<FieldConversions.Change> conversions,
            List<ObjectApplicationUpgrade.Impact> applicationUpgrades) {
        public Contents {
            applicationUpgrades =
                    applicationUpgrades == null ? List.of() : List.copyOf(applicationUpgrades);
            conversions = conversions == null ? List.of() : List.copyOf(conversions);
        }
    }

    public PublishPlan plan(Revision request, long actor) {
        return tx.execute(
                status -> {
                    ObjectApplicationUpgrade upgrades = applicationUpgrades();
                    upgrades.lock();
                    ObjectDraftHeadDO h = designs.requireRevision(request);
                    publishContext.requireDraft(h);
                    designs.snapshot(request.id());
                    Definition definition = designs.definition(request.id());
                    boolean reconciliation = publishContext.reconciliation(h, definition);
                    commands.execute(statementTimeout());
                    List<FieldConversionPlanner.Candidate> candidates =
                            reconciliation
                                    ? List.of()
                                    : conversions.preview(
                                            definition, designs.published(request.id()));
                    SchemaCompiler.Compilation compilation =
                            reconciliation
                                    ? publishContext.reconciliationCompilation()
                                    : compiler.compile(
                                            definition,
                                            designs.published(request.id()),
                                            conversions.ids(candidates));
                    List<Check> checks = new ArrayList<Check>();
                    try {
                        orderedStates.validatePublish(definition);
                    } catch (ServiceException error) {
                        checks.add(
                                new Check(
                                        PublishCheckEnum.GENERATED_CHANGE.getCode(),
                                        error.getMessage(),
                                        true));
                    }
                    if (!reconciliation) checks.addAll(tables.drift(request.id()));
                    checks.addAll(
                            compilation.checks().stream()
                                    .filter(
                                            check ->
                                                    check.blocking()
                                                            || !PublishCheckEnum
                                                                    .APPLICATION_CONTRACT
                                                                    .matches(check.code()))
                                    .toList());
                    checks.addAll(conversions.checks(candidates));
                    List<ObjectApplicationUpgrade.Impact> appImpacts =
                            upgrades.inspect(
                                    designs.published(request.id()),
                                    definition,
                                    clearedFields(candidates),
                                    actor);
                    for (ObjectApplicationUpgrade.Impact impact : appImpacts)
                        for (String blocker : impact.blockers())
                            checks.add(
                                    new Check(
                                            PublishCheckEnum.APPLICATION_CONTRACT.getCode(),
                                            impact.applicationName() + "：" + blocker,
                                            true));
                    // 自动跟随的提示（不阻断）：哪些应用会自动跟上、哪些暂时跟不上、新增字段会对谁可见。
                    checks.addAll(
                            upgrades.followChecks(
                                    designs.published(request.id()),
                                    definition,
                                    appImpacts,
                                    actor));
                    // 同一字段只能由一处写入：配了数据联动或公式默认值的字段不能同时被已发布应用的自动更新、留存动作写入。
                    for (String conflict : upgrades.ruleWriteConflicts(definition))
                        checks.add(
                                new Check(
                                        PublishCheckEnum.APPLICATION_CONTRACT.getCode(),
                                        conflict,
                                        true));
                    // 业务文件预检由运行时模块按需提供：文件默认值阻断、在途上传与规则影响范围提示
                    BusinessFilePublishInspection businessFiles =
                            businessFileInspection.getIfAvailable();
                    if (businessFiles != null)
                        checks.addAll(
                                businessFiles.inspect(
                                        h.getId().toString(),
                                        definition,
                                        designs.published(request.id())));
                    if (ObjectSourceEnum.ADOPTED.matches(h.getSourceType())
                            && h.getCurrentPublishedVersionNo() == null) {
                        DatabaseMetadata.Table actual =
                                database.readTable(h.getSchemaName(), h.getTableName())
                                        .orElse(null);
                        if (actual == null
                                || !tables.fingerprint(actual).equals(h.getAdoptionHash()))
                            checks.add(
                                    new Check(
                                            PublishCheckEnum.ADOPTION_CHANGED.getCode(),
                                            "原表结构已变化，请重新同步映射后发布",
                                            true));
                    }
                    List<Step> changes = new ArrayList<>(compilation.changes());
                    candidates.stream()
                            .filter(
                                    candidate ->
                                            !com.lingan.ucp.nocode.enums.FieldConversionActionEnum
                                                    .KEEP_COLUMN
                                                    .matches(candidate.change().action()))
                            .forEach(
                                    candidate ->
                                            changes.add(
                                                    new Step(
                                                            SchemaChangeEnum.ALTER_COLUMN_TYPE
                                                                    .getCode(),
                                                            "转换字段："
                                                                    + candidate
                                                                            .change()
                                                                            .sourceName()
                                                                    + "."
                                                                    + candidate.change().fieldName()
                                                                    + (com.lingan.ucp.nocode.enums
                                                                                    .FieldConversionActionEnum
                                                                                    .PRESERVE_VALUES
                                                                                    .matches(
                                                                                            candidate
                                                                                                    .change()
                                                                                                    .action())
                                                                            ? "，按规则保留本列 "
                                                                                    + candidate
                                                                                            .change()
                                                                                            .affectedRows()
                                                                                    + " 条值："
                                                                                    + candidate
                                                                                            .change()
                                                                                            .conversionRule()
                                                                            : "，清空本列 "
                                                                                    + candidate
                                                                                            .change()
                                                                                            .affectedRows()
                                                                                    + " 条值；保留记录及其他业务列"))));
                    Contents contents =
                            new Contents(
                                    changes,
                                    checks,
                                    store.dependencies(h.getId()),
                                    candidates.stream()
                                            .map(FieldConversionPlanner.Candidate::change)
                                            .toList(),
                                    appImpacts);
                    DataCenterRows.Plan row = new DataCenterRows.Plan();
                    row.setId(UUID.randomUUID().toString());
                    row.setObjectId(h.getId());
                    row.setObjectVersionId(h.getVersionId());
                    row.setVersionNo(h.getLatestVersionNo());
                    row.setRevision(h.getLockVersion());
                    row.setSchemaChecksum(store.versionChecksum(h.getVersionId()));
                    row.setBaselineHash(publishContext.contextHash(definition));
                    row.setPlanJson(designs.write(contents));
                    row.setState(
                            checks.stream().anyMatch(Check::blocking)
                                    ? PublishStateEnum.BLOCKED.getCode()
                                    : PublishStateEnum.PENDING.getCode());
                    row.setCreator(Long.toString(actor));
                    store.insertPlan(row);
                    designs.audit(
                            h.getId(),
                            actor,
                            AuditOperationEnum.OBJECT_PUBLISH_PLAN.getCode(),
                            Map.of(
                                    "planId",
                                    row.getId(),
                                    "state",
                                    row.getState(),
                                    "changes",
                                    contents.changes(),
                                    "checks",
                                    contents.checks()));
                    return view(store.plan(row.getId(), false));
                });
    }

    public PublishPlan get(String planId) {
        return view(publishContext.requirePlan(planId, false));
    }

    public List<Execution> history(String id) {
        ObjectDraftHeadDO h = designs.head(id, false);
        return store.plans(h.getId()).stream().map(this::execution).toList();
    }

    public Execution execute(ExecutePlan request, long actor) {
        if (request == null
                || request.reason() == null
                || request.reason().isBlank()
                || request.reason().length() > 1000) throw invalid("发布原因必填且最多 1000 字符");
        UUID.fromString(request.planId());
        try {
            return tx.execute(
                    status -> {
                        // 与业务写入和应用发布保持 catalog → design 的锁顺序，暂停与 DDL 原子提交。
                        ObjectApplicationUpgrade upgrades = applicationUpgrades();
                        upgrades.lock();
                        objects.lockTableName("nocode-design-write");
                        DataCenterRows.Plan plan =
                                publishContext.requirePlan(request.planId(), true);
                        if (PublishStateEnum.SUCCEEDED.matches(plan.getState()))
                            return execution(plan);
                        if (!PublishStateEnum.PENDING.matches(plan.getState()))
                            throw invalid("该计划不可执行，请刷新设计后重新生成计划");
                        ObjectDraftHeadDO h = designs.head(plan.getObjectId().toString(), true);
                        publishContext.requireDraft(h);
                        if (!Objects.equals(h.getVersionId(), plan.getObjectVersionId())
                                || !Objects.equals(h.getLockVersion(), plan.getRevision())
                                || !store.versionChecksum(h.getVersionId())
                                        .equals(plan.getSchemaChecksum()))
                            throw invalid("草稿已变化，旧计划已失效");
                        Definition definition = designs.definition(h.getId().toString());
                        // 发布之前的已发布定义：对象头移动后就取不到了，自动跟随要用它判断「之前有没有发布过」。
                        Definition previousPublished =
                                h.getCurrentPublishedVersionNo() == null
                                        ? null
                                        : designs.published(h.getId().toString());
                        orderedStates.validatePublish(definition);
                        commands.execute(lockTimeout());
                        commands.execute(statementTimeout());
                        publishContext.lockTables(definition);
                        if (!plan.getBaselineHash().equals(publishContext.contextHash(definition)))
                            throw invalid("物理结构、目标对象或依赖发生变化，旧计划已失效");
                        boolean reconciliation = publishContext.reconciliation(h, definition);
                        if (!reconciliation && !tables.drift(h.getId().toString()).isEmpty())
                            throw invalid("检测到结构漂移，请先处理差异");
                        Contents expected = designs.read(plan.getPlanJson(), Contents.class);
                        List<FieldConversionPlanner.Candidate> candidates =
                                reconciliation
                                        ? List.of()
                                        : conversions.preview(
                                                definition,
                                                designs.published(h.getId().toString()));
                        conversions.verify(
                                expected.conversions(), candidates, request.clearFieldIds());
                        List<ObjectApplicationUpgrade.Impact> appImpacts =
                                upgrades.inspect(
                                        designs.published(h.getId().toString()),
                                        definition,
                                        clearedFields(candidates),
                                        actor);
                        verifyApplications(
                                expected.applicationUpgrades(),
                                appImpacts,
                                request.suspendApplicationIds());
                        // 生成计划之后可能有应用新发布了自动更新或留存动作：执行前在同一把目录锁内再判一次。
                        List<String> ruleConflicts = upgrades.ruleWriteConflicts(definition);
                        if (!ruleConflicts.isEmpty()) throw invalid(ruleConflicts.getFirst());
                        SchemaCompiler.Compilation compilation =
                                reconciliation
                                        ? publishContext.reconciliationCompilation()
                                        : compiler.compile(
                                                definition,
                                                designs.published(h.getId().toString()),
                                                conversions.ids(candidates));
                        if (compilation.checks().stream().anyMatch(Check::blocking))
                            throw invalid(
                                    compilation.checks().stream()
                                            .filter(Check::blocking)
                                            .findFirst()
                                            .orElseThrow()
                                            .message());
                        upgrades.suspend(appImpacts, actor, request.reason());
                        conversions.apply(candidates, actor);
                        if (!reconciliation)
                            selectionMigrations.apply(
                                    selectionMigrations.preview(
                                            definition,
                                            designs.published(h.getId().toString()),
                                            conversions.ids(candidates)),
                                    actor);
                        for (PostgreSqlCommands.Command command : compilation.commands())
                            commands.execute(command);
                        orderedStates.publish(definition, actor);
                        if (store.publishVersion(h.getId(), h.getVersionId(), actor) != 1)
                            throw invalid("版本状态发生变化");
                        // 先采集完整结果，再移动对象头；保留上一版本中停用表的结构基线。
                        String structure = tables.capture(definition);
                        store.insertDeployment(
                                h.getId(),
                                h.getLatestVersionNo(),
                                h.getSchemaName(),
                                h.getTableName(),
                                DigestUtil.sha256Hex(structure),
                                structure,
                                actor);
                        store.publishHead(h.getId(), actor);
                        registerRuleDependencies(definition, actor);
                        // 对象新版本已生效：替开着自动跟随的应用做「同步 + 发布」。逐个应用用保存点隔离，不影响对象发布。
                        upgrades.follow(
                                previousPublished,
                                definition,
                                plan.getId(),
                                actor,
                                request.reason());
                        store.finishPlan(
                                plan.getId(),
                                PublishStateEnum.SUCCEEDED.getCode(),
                                actor,
                                request.reason(),
                                null);
                        designs.audit(
                                h.getId(),
                                actor,
                                AuditOperationEnum.OBJECT_PUBLISH.getCode(),
                                Map.of(
                                        "planId",
                                        plan.getId(),
                                        "version",
                                        h.getLatestVersionNo(),
                                        "reason",
                                        request.reason(),
                                        "suspendedApplications",
                                        appImpacts,
                                        "clearedColumns",
                                        expected.conversions().stream()
                                                .filter(
                                                        change ->
                                                                FieldConversionActionEnum
                                                                        .CLEAR_COLUMN
                                                                        .matches(change.action()))
                                                .toList(),
                                        "preservedColumns",
                                        expected.conversions().stream()
                                                .filter(
                                                        change ->
                                                                FieldConversionActionEnum
                                                                        .PRESERVE_VALUES
                                                                        .matches(change.action()))
                                                .toList()));
                        return execution(store.plan(plan.getId(), false));
                    });
        } catch (RuntimeException exception) {
            String message =
                    exception instanceof ServiceException
                            ? exception.getMessage()
                            : "数据库拒绝结构变更，类型或约束校验未通过；本次结构修改已全部回滚";
            failureTx.executeWithoutResult(
                    status -> {
                        objects.lockTableName("nocode-design-write");
                        DataCenterRows.Plan plan = store.plan(request.planId(), true);
                        if (plan != null && PublishStateEnum.PENDING.matches(plan.getState())) {
                            store.finishPlan(
                                    plan.getId(),
                                    PublishStateEnum.FAILED.getCode(),
                                    actor,
                                    request.reason(),
                                    message.substring(0, Math.min(message.length(), 1000)));
                            designs.audit(
                                    plan.getObjectId(),
                                    actor,
                                    AuditOperationEnum.OBJECT_PUBLISH_FAILED.getCode(),
                                    Map.of(
                                            "planId",
                                            plan.getId(),
                                            "reason",
                                            request.reason(),
                                            "error",
                                            message));
                        }
                    });
            ServiceException error = invalid(message);
            error.initCause(exception);
            throw error;
        }
    }

    private ObjectApplicationUpgrade applicationUpgrades() {
        ObjectApplicationUpgrade service = applicationUpgradeProvider.getIfAvailable();
        if (service == null) throw invalid("应用兼容检查服务不可用，请稍后重新检查");
        return service;
    }

    private static Set<String> clearedFields(List<FieldConversionPlanner.Candidate> candidates) {
        Set<String> result = new TreeSet<>();
        for (FieldConversionPlanner.Candidate candidate : candidates)
            if (FieldConversionActionEnum.CLEAR_COLUMN.matches(candidate.change().action())
                    && candidate.change().affectedRows() > 0)
                result.add(candidate.change().fieldId());
        return result;
    }

    /** 必须与执行时的应用版本、修订和影响完全一致，旧客户端不能隐式暂停应用。 */
    private static void verifyApplications(
            List<ObjectApplicationUpgrade.Impact> expected,
            List<ObjectApplicationUpgrade.Impact> actual,
            List<String> confirmed) {
        if (!expected.equals(actual)) throw invalid("受影响应用或权限已变化，请重新检查发布影响");
        if (actual.stream().anyMatch(impact -> !impact.blockers().isEmpty()))
            throw invalid("应用还有未处理的依赖，请重新检查发布影响");
        Set<String> required = new TreeSet<>();
        actual.forEach(impact -> required.add(impact.applicationId()));
        if (new HashSet<>(confirmed).size() != confirmed.size()
                || !required.equals(new TreeSet<>(confirmed)))
            throw invalid("请确认暂停列表中的全部受影响应用；对象发布后可分别适配并启用");
    }

    private PublishPlan view(DataCenterRows.Plan row) {
        Contents c = designs.read(row.getPlanJson(), Contents.class);
        return new PublishPlan(
                row.getId(),
                row.getObjectId().toString(),
                row.getRevision(),
                row.getVersionNo(),
                row.getState(),
                c.changes(),
                c.checks(),
                c.dependencies(),
                offset(row.getCreateTime()),
                c.conversions().stream()
                        .map(
                                value ->
                                        new FieldConversions.Change(
                                                value.fieldId(),
                                                value.detailId(),
                                                value.fieldName(),
                                                value.sourceName(),
                                                value.fromType(),
                                                value.toType(),
                                                value.fromFieldType(),
                                                value.toFieldType(),
                                                value.affectedRows(),
                                                value.deletedRows(),
                                                value.masked(),
                                                "",
                                                value.clearAllowed(),
                                                value.impacts(),
                                                value.action(),
                                                value.conversionRule(),
                                                value.failedRows(),
                                                value.conflicts()))
                        .toList(),
                c.applicationUpgrades());
    }

    /** 行预览从持久化计划重新定位列，草稿或数据已变化时要求重建计划。 */
    public FieldConversions.Page conversionRows(
            String planId, String fieldId, int pageNo, int pageSize) {
        return tx.execute(
                status -> {
                    DataCenterRows.Plan plan = publishContext.requirePlan(planId, false);
                    if (!PublishStateEnum.PENDING.matches(plan.getState())
                            && !PublishStateEnum.BLOCKED.matches(plan.getState()))
                        throw invalid("该计划已执行或失效，请重新检查字段转换");
                    com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO head =
                            designs.head(plan.getObjectId().toString(), false);
                    if (!Objects.equals(head.getVersionId(), plan.getObjectVersionId())
                            || !Objects.equals(head.getLockVersion(), plan.getRevision()))
                        throw invalid("草稿已变化，请重新生成发布计划");
                    commands.execute(statementTimeout());
                    Contents contents = designs.read(plan.getPlanJson(), Contents.class);
                    FieldConversions.Change expected =
                            contents.conversions().stream()
                                    .filter(c -> c.fieldId().equals(fieldId))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("字段不属于本次转换计划"));
                    FieldConversionPlanner.Candidate candidate =
                            conversions
                                    .preview(
                                            designs.definition(plan.getObjectId().toString()),
                                            designs.published(plan.getObjectId().toString()))
                                    .stream()
                                    .filter(c -> c.change().fieldId().equals(fieldId))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("字段转换配置已变化，请重新检查"));
                    if (!expected.equals(candidate.change())) throw invalid("待转换数据或依赖已变化，请重新预览确认");
                    return conversions.rows(candidate, pageNo, pageSize);
                });
    }

    private Execution execution(DataCenterRows.Plan row) {
        return new Execution(
                row.getId(),
                row.getObjectId().toString(),
                row.getVersionNo(),
                row.getState(),
                row.getReason(),
                row.getErrorMessage(),
                offset(row.getCreateTime()),
                row.getExecutedAt());
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
    }

    /** 对象规则引用其它对象字段时登记 OBJECT_RULE 依赖，与发布同一事务；先撤销本对象上一版的登记，规则删除后不再阻止对方停用字段。 */
    private void registerRuleDependencies(Definition definition, long actor) {
        String owner = Long.toString(actor);
        store.deleteDependencies(
                DependencyKindEnum.OBJECT_RULE.getCode(), definition.objectId(), owner);
        FieldRuleValidator.dependencies(definition)
                .forEach(
                        (target, fields) ->
                                store.upsertDependency(
                                        new Dependency(
                                                DependencyKindEnum.OBJECT_RULE.getCode(),
                                                definition.objectId(),
                                                definition.objectName(),
                                                target,
                                                List.copyOf(fields)),
                                        designs.write(List.copyOf(fields)),
                                        owner));
    }
}
