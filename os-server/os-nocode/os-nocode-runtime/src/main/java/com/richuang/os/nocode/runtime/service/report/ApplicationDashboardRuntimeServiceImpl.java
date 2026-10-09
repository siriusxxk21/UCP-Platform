package com.richuang.os.nocode.runtime.service.report;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;
import static com.richuang.os.nocode.api.NocodeErrorCodes.versionChanged;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.report.dal.mapper.ReportDatasetMapper;
import com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService.ApplicationGate;
import com.richuang.os.nocode.report.service.authorization.ReportPrincipals;
import com.richuang.os.nocode.report.service.dashboard.ReportDashboardService;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.report.ReportDashboardQueryService.BoundInput;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** 应用只提供受控固定看板入口；服务器 pin、实时授权与必需输入共同约束每次取数。 */
@Service
public class ApplicationDashboardRuntimeServiceImpl implements ApplicationDashboardRuntimeService {
    @Resource private ApplicationService applications;
    @Resource private DataObjectApi objects;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private ApplicationAuthorizationService authorization;
    @Resource private ObjectSharingService sharing;
    @Resource private ApplicationResourceValidator resources;
    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private ReportDatasetMapper datasets;
    @Resource private ReportDashboardService dashboards;
    @Resource private ReportDashboardQueryService queries;
    @Resource private ReportDatasetQueryService datasetQueries;
    @Resource private RecordService records;
    @Resource private ReportPrincipals principals;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;

    private record AccessStamp(int revision, Map<String, Integer> ceilings) {}

    private record ContextStamp(String id, String revision, Map<String, String> values) {}

    private record Prepared(
            String applicationId,
            String resourceId,
            String stamp,
            ApplicationDashboards.Config config,
            AccessStamp access,
            ContextStamp context,
            List<BoundInput> inputs) {}

    @Override
    public ApplicationDashboards.Model model(String applicationId, String resourceId, long actor) {
        ReportPrincipals.Snapshot identity = principals.snapshot(actor);
        ApplicationDashboards.Model result =
                transaction()
                        .execute(
                                tx -> {
                                    catalog.lock(false);
                                    datasets.designReadLock();
                                    Prepared prepared =
                                            load(applicationId, resourceId, null, actor);
                                    ApplicationDashboards.Reference ref =
                                            prepared.config().dashboard();
                                    ReportDashboards.Release release =
                                            dashboards.publishedFixed(
                                                    ref.id(),
                                                    ref.versionNo(),
                                                    ref.checksum(),
                                                    actor);
                                    return new ApplicationDashboards.Model(
                                            applicationId,
                                            resourceId,
                                            prepared.stamp(),
                                            release,
                                            prepared.config().inputBindings().stream()
                                                    .map(
                                                            ApplicationDashboards.InputBinding
                                                                    ::filterId)
                                                    .toList(),
                                            prepared.config());
                                });
        principals.unchanged(identity);
        transaction()
                .executeWithoutResult(
                        tx -> {
                            catalog.lock(false);
                            datasets.designReadLock();
                            Prepared current =
                                    load(applicationId, resourceId, result.stamp(), actor);
                            ApplicationDashboards.Reference ref = current.config().dashboard();
                            dashboards.publishedFixed(
                                    ref.id(), ref.versionNo(), ref.checksum(), actor);
                        });
        return result;
    }

    /** 元数据模型可先加载；入口可见性探测不代替查询的参数或记录输入校验。 */
    @Override
    public boolean available(String applicationId, String resourceId, long actor) {
        try {
            ApplicationDashboards.Model model = model(applicationId, resourceId, actor);
            Prepared prepared =
                    transaction()
                            .execute(
                                    tx -> {
                                        catalog.lock(false);
                                        datasets.designReadLock();
                                        return load(
                                                applicationId, resourceId, model.stamp(), actor);
                                    });
            for (ReportDashboards.Chart chart : model.dashboard().content().charts()) {
                try {
                    ApplicationGate gate = new Gate(prepared, null, actor);
                    ReportDashboards.Resolved resolved =
                            dashboards.resolveFixed(
                                    new ReportDashboards.Query(
                                            model.dashboard().id(),
                                            chart.id(),
                                            false,
                                            model.dashboard().versionNo(),
                                            model.dashboard().checksum()),
                                    actor,
                                    false);
                    datasetQueries.checkChartAccess(resolved, gate, actor);
                    return true;
                } catch (AccessDeniedException | ServiceException denied) {
                    // 单个组件缺少数据授权时继续检查其他组件，不在取数事务里吞掉拒绝。
                }
            }
            return false;
        } catch (AccessDeniedException | ServiceException denied) {
            return false;
        }
    }

    @Override
    public ApplicationReports.Result query(ApplicationDashboards.Query request, long actor) {
        return result(request, actor, false);
    }

    @Override
    public ApplicationReports.Result export(ApplicationDashboards.Query request, long actor) {
        return result(request, actor, true);
    }

    private ApplicationReports.Result result(
            ApplicationDashboards.Query request, long actor, boolean exporting) {
        Prepared prepared = prepare(request, actor);
        ApplicationGate gate = new Gate(prepared, request, actor);
        return queries.fixed(
                resolve(prepared, request, actor, exporting),
                prepared.inputs(),
                gate,
                exporting,
                actor);
    }

    @Override
    public ReportDatasetQueries.OptionPage options(
            ApplicationDashboards.Options request, long actor) {
        if (request == null) throw invalid("缺少应用看板候选查询参数");
        Prepared prepared = prepare(request.query(), actor);
        ReportDashboards.Resolved resolved = resolve(prepared, request.query(), actor, false);
        ReportDashboards.Options options =
                new ReportDashboards.Options(
                        resolved.request(),
                        request.filterId(),
                        request.pageNo(),
                        request.pageSize(),
                        request.search());
        return queries.fixedOptions(
                resolved,
                options,
                prepared.inputs(),
                new Gate(prepared, request.query(), actor),
                actor);
    }

    @Override
    public ReportDashboards.DetailPage details(ApplicationDashboards.Details request, long actor) {
        if (request == null) throw invalid("缺少应用看板明细查询参数");
        Prepared prepared = prepare(request.query(), actor);
        ReportDashboards.Resolved resolved = resolve(prepared, request.query(), actor, false);
        ReportDashboards.Details details =
                new ReportDashboards.Details(
                        resolved.request(),
                        request.group(),
                        request.columnGroup(),
                        request.metricId(),
                        request.pageNo(),
                        request.pageSize());
        return queries.fixedDetails(
                resolved,
                details,
                prepared.inputs(),
                new Gate(prepared, request.query(), actor),
                actor);
    }

    /** 目标只取应用发布绑定，不能用业务视图参数跳到同应用的其他资源。 */
    @Override
    public <T> T withBusinessDetails(
            ApplicationRecords.Query request, long actor, Function<ReportStatement, T> action) {
        if (request == null
                || request.dashboardDrill() == null
                || request.dashboardDrill().query() == null
                || request.viewId() == null
                || request.reportDrill() != null) throw invalid("缺少应用看板业务明细参数或混用了统计下钻");
        ApplicationDashboards.Drill drill = request.dashboardDrill();
        if (!Objects.equals(request.applicationId(), drill.query().applicationId()))
            throw invalid("看板业务明细与目标视图不属于同一应用");
        Prepared prepared = prepare(drill.query(), actor);
        List<ApplicationDashboards.DetailView> bindings =
                prepared.config().detailViews() == null
                        ? List.of()
                        : prepared.config().detailViews();
        String target =
                bindings.stream()
                        .filter(binding -> binding.chartId().equals(drill.query().chartId()))
                        .map(ApplicationDashboards.DetailView::viewId)
                        .findFirst()
                        .orElseThrow(() -> invalid("当前图表未绑定业务明细视图"));
        if (!target.equals(request.viewId())) throw invalid("业务明细目标不属于当前图表发布绑定");
        ReportDashboards.Resolved resolved = resolve(prepared, drill.query(), actor, false);
        ReportDashboards.Details details =
                new ReportDashboards.Details(
                        resolved.request(),
                        drill.group(),
                        drill.columnGroup(),
                        drill.metricId(),
                        request.pageNo(),
                        request.pageSize());
        return queries.withFixedBusinessDetails(
                resolved,
                details,
                prepared.inputs(),
                new Gate(prepared, drill.query(), actor),
                request.objectId(),
                actor,
                action);
    }

    private ReportDashboards.Resolved resolve(
            Prepared prepared, ApplicationDashboards.Query request, long actor, boolean exporting) {
        ApplicationDashboards.Reference ref = prepared.config().dashboard();
        return dashboards.resolveFixed(
                new ReportDashboards.Query(
                        ref.id(),
                        request.chartId(),
                        false,
                        ref.versionNo(),
                        ref.checksum(),
                        request.filterValues(),
                        request.selections(),
                        request.drillPath()),
                actor,
                exporting);
    }

    private Prepared prepare(ApplicationDashboards.Query request, long actor) {
        if (request == null
                || request.chartId() == null
                || request.chartId().isBlank()
                || request.stamp() == null
                || !request.stamp().matches("[a-f0-9]{64}")) throw invalid("缺少应用看板组件身份或模型标记");
        return transaction()
                .execute(
                        tx -> {
                            catalog.lock(false);
                            datasets.designReadLock();
                            Prepared loaded =
                                    load(
                                            request.applicationId(),
                                            request.resourceId(),
                                            request.stamp(),
                                            actor);
                            ApplicationDashboards.Reference ref = loaded.config().dashboard();
                            ReportDashboards.Release release =
                                    dashboards.publishedFixed(
                                            ref.id(), ref.versionNo(), ref.checksum(), actor);
                            ContextStamp context = context(loaded.config(), request, actor);
                            List<BoundInput> inputs =
                                    bindings(loaded.config(), release.content(), request, context);
                            return new Prepared(
                                    loaded.applicationId(),
                                    loaded.resourceId(),
                                    loaded.stamp(),
                                    loaded.config(),
                                    loaded.access(),
                                    context,
                                    inputs);
                        });
    }

    /** 目录/设计锁由调用方先取，published 再取得应用头共享锁；stamp 不包含实时授权。 */
    private Prepared load(String applicationId, String resourceId, String expected, long actor) {
        if (actor <= 0) throw invalid("请先登录");
        if (resourceId == null || resourceId.isBlank()) throw invalid("缺少应用看板资源身份");
        policy.requireEntry(applicationId, actor);
        ApplicationCenter.Published published = applications.published(applicationId, null);
        ApplicationCenter.Resource resource =
                published.definition().resources().stream()
                        .filter(
                                r ->
                                        resourceId.equals(r.id())
                                                && ApplicationResourceKindEnum.REPORT_DASHBOARD
                                                        .matches(r.kind()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        expected == null
                                                ? invalid("应用当前发布版本没有此看板资源")
                                                : versionChanged("应用看板资源已移除，请重新加载应用"));
        ApplicationDashboards.Config config =
                resources.decode(resource.config(), ApplicationDashboards.Config.class);
        String stamp;
        try {
            stamp =
                    DigestUtil.sha256Hex(
                            applicationId
                                    + "\n"
                                    + published.versionNo()
                                    + "\n"
                                    + published.checksum()
                                    + "\n"
                                    + resourceId
                                    + "\n"
                                    + json.writeValueAsString(config));
        } catch (JsonProcessingException error) {
            throw invalid("应用看板发布配置无法读取");
        }
        if (expected != null && !expected.equals(stamp))
            throw versionChanged("应用看板发布配置已变化，请重新加载模型");
        Map<String, Integer> ceilings = new TreeMap<>();
        sharing.forApplication(applicationId)
                .forEach(grant -> ceilings.put(grant.objectId(), grant.revision()));
        AccessStamp access =
                new AccessStamp(authorization.revision(applicationId), Map.copyOf(ceilings));
        return new Prepared(applicationId, resourceId, stamp, config, access, null, List.of());
    }

    private ContextStamp context(
            ApplicationDashboards.Config config, ApplicationDashboards.Query request, long actor) {
        boolean required =
                config.inputBindings().stream()
                        .anyMatch(
                                binding ->
                                        !ApplicationDashboardInputSourceEnum.PARAMETER.matches(
                                                binding.source()));
        String id = request.recordId();
        if (id == null) {
            if (required) throw invalid("看板绑定需要当前记录");
            return null;
        }
        if (config.contextObjectId() == null || id.isBlank() || id.length() > 500)
            throw invalid("看板记录上下文无效");
        ApplicationRecords.Row row =
                records.get(request.applicationId(), config.contextObjectId(), id, actor).record();
        Map<String, String> fields = new TreeMap<>();
        for (ApplicationDashboards.InputBinding binding : config.inputBindings()) {
            if (!ApplicationDashboardInputSourceEnum.RECORD_FIELD.matches(binding.source()))
                continue;
            if (row.permissions() == null
                    || !row.permissions().readFields().contains(binding.fieldId()))
                throw new AccessDeniedException("没有看板上下文绑定字段的查看权限");
            Object value = row.values().get(binding.fieldId());
            if (value instanceof Collection<?> || value instanceof Map<?, ?>)
                throw invalid("看板上下文输入必须是标量原值");
            fields.put(binding.fieldId(), value == null ? null : value.toString());
        }
        return new ContextStamp(row.id(), row.revision(), Collections.unmodifiableMap(fields));
    }

    private List<BoundInput> bindings(
            ApplicationDashboards.Config config,
            ReportDashboards.Content content,
            ApplicationDashboards.Query request,
            ContextStamp context) {
        Map<String, ApplicationDashboards.InputValue> parameters =
                request.parameters() == null ? Map.of() : request.parameters();
        Set<String> declared = new HashSet<>();
        config.inputBindings().stream()
                .filter(b -> ApplicationDashboardInputSourceEnum.PARAMETER.matches(b.source()))
                .forEach(b -> declared.add(b.parameter()));
        if (!declared.equals(parameters.keySet())) throw invalid("必须提供全部已声明看板参数，不能提交其他参数");
        Set<String> bound = new HashSet<>();
        config.inputBindings().forEach(b -> bound.add(b.filterId()));
        if (request.filterValues() != null
                && request.filterValues().stream()
                        .anyMatch(v -> v != null && bound.contains(v.filterId())))
            throw invalid("用户筛选不能覆盖固定绑定输入");
        Map<String, ReportDashboards.Filter> filters = new HashMap<>();
        if (content.filters() != null) content.filters().forEach(f -> filters.put(f.id(), f));
        List<BoundInput> result = new ArrayList<>();
        for (ApplicationDashboards.InputBinding binding : config.inputBindings()) {
            ReportDashboards.Filter filter = filters.get(binding.filterId());
            if (filter == null) throw invalid("看板绑定筛选已失效");
            ApplicationDashboardInputSourceEnum source =
                    ApplicationDashboardInputSourceEnum.fromCode(binding.source());
            ReportDashboards.FilterValue value;
            if (source == ApplicationDashboardInputSourceEnum.PARAMETER) {
                ApplicationDashboards.InputValue input = parameters.get(binding.parameter());
                requireInput(input, ReportDashboardFilterKindEnum.fromCode(filter.kind()));
                value =
                        new ReportDashboards.FilterValue(
                                binding.filterId(), copy(input.values()), input.from(), input.to());
            } else {
                if (context == null) throw invalid("缺少已授权的记录上下文");
                String raw =
                        source == ApplicationDashboardInputSourceEnum.RECORD_ID
                                ? context.id()
                                : context.values().get(binding.fieldId());
                value =
                        new ReportDashboards.FilterValue(
                                binding.filterId(), Collections.singletonList(raw), null, null);
            }
            result.add(
                    new BoundInput(value, source != ApplicationDashboardInputSourceEnum.PARAMETER));
        }
        return List.copyOf(result);
    }

    /** 固定输入必须产生真实条件；显式 NULL 单选保留 IS_NULL，而空输入不能取消绑定。 */
    private void requireInput(
            ApplicationDashboards.InputValue input, ReportDashboardFilterKindEnum kind) {
        if (input == null) throw invalid("看板绑定参数不能为空");
        List<String> values = input.values() == null ? List.of() : input.values();
        boolean range =
                kind == ReportDashboardFilterKindEnum.NUMBER_RANGE
                        || kind == ReportDashboardFilterKindEnum.DATE_RANGE;
        if (range) {
            if (!values.isEmpty() || input.from() == null && input.to() == null)
                throw invalid("看板绑定范围参数至少填写一端");
            try {
                if (input.from() != null) key(input.from());
                if (input.to() != null) key(input.to());
                if (kind == ReportDashboardFilterKindEnum.NUMBER_RANGE) {
                    BigDecimal from = input.from() == null ? null : new BigDecimal(input.from());
                    BigDecimal to = input.to() == null ? null : new BigDecimal(input.to());
                    if (from != null && to != null && from.compareTo(to) > 0)
                        throw invalid("范围起点不能晚于终点");
                } else {
                    java.time.Instant from = input.from() == null ? null : date(input.from());
                    java.time.Instant to = input.to() == null ? null : date(input.to());
                    if (from != null && to != null && from.compareTo(to) > 0)
                        throw invalid("范围起点不能晚于终点");
                }
            } catch (RuntimeException error) {
                throw invalid("看板绑定范围参数格式无效");
            }
        } else {
            if (input.from() != null
                    || input.to() != null
                    || values.isEmpty()
                    || values.size() > (kind == ReportDashboardFilterKindEnum.MULTISELECT ? 100 : 1)
                    || new HashSet<>(values).size() != values.size())
                throw invalid("看板绑定选择参数数量或格式无效");
            for (String value : values) if (value != null) key(value);
            if (kind == ReportDashboardFilterKindEnum.TEXT
                    && (values.getFirst() == null || values.getFirst().isBlank()))
                throw invalid("看板绑定文本参数不能为空");
        }
    }

    private void key(String value) {
        if (value.isBlank() || value.length() > 2000 || value.indexOf(0) >= 0)
            throw invalid("看板绑定参数原值无效");
    }

    private java.time.Instant date(String value) {
        if (value.length() == 10)
            return java.time.LocalDate.parse(value)
                    .atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC);
        String normalized = value.replace(" ", "T");
        try {
            return java.time.OffsetDateTime.parse(normalized).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(normalized).toInstant(java.time.ZoneOffset.UTC);
        }
    }

    private List<String> copy(List<String> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setTimeout(25);
        return transaction;
    }

    /** 门卫不缓存授权结论：主事务复核服务器 pin，来源锁之后取权限，交付前再开短事务复核。 */
    private final class Gate implements ApplicationGate {
        private final Prepared prepared;
        private final ApplicationDashboards.Query request;
        private final long actor;
        private Set<String> objectIds = Set.of();
        private Map<String, List<ObjectGrant>> effective;
        private final Map<String, DataCenter.Definition> sourceDefinitions = new LinkedHashMap<>();

        private Gate(Prepared prepared, ApplicationDashboards.Query request, long actor) {
            this.prepared = prepared;
            this.request = request;
            this.actor = actor;
        }

        @Override
        public void lockCatalog() {
            catalog.lock(false);
        }

        @Override
        public void lockApplication(ReportDashboards.Resolved board, long actor) {
            if (this.actor != actor) throw invalid("看板运行身份不匹配");
            Prepared current =
                    load(prepared.applicationId(), prepared.resourceId(), prepared.stamp(), actor);
            if (!current.access().equals(prepared.access()))
                throw new AccessDeniedException("应用授权已变化，请重新查询");
            ApplicationDashboards.Reference ref = current.config().dashboard();
            if (!ref.id().equals(board.request().id())
                    || board.request().preview()
                    || !Integer.valueOf(ref.versionNo()).equals(board.request().versionNo())
                    || !ref.checksum().equals(board.request().checksum()))
                throw invalid("看板固定引用不属于当前应用发布资源");
            Set<String> ids = new HashSet<>();
            applications
                    .published(prepared.applicationId(), null)
                    .definition()
                    .objects()
                    .forEach(refObject -> ids.add(refObject.objectId()));
            objectIds = Set.copyOf(ids);
        }

        @Override
        public Map<String, List<ObjectGrant>> grants(
                ReportDatasets.ResolvedSource source, long actor) {
            if (this.actor != actor) throw invalid("看板运行身份不匹配");
            Map<String, List<ObjectGrant>> granted = new LinkedHashMap<>();
            for (ReportDatasets.ObjectReference ref : source.objects()) {
                if (!objectIds.contains(ref.objectId())) throw invalid("看板来源对象不属于当前应用");
                DataCenter.Definition definition =
                        objects.getVersion(ref.objectId(), ref.versionNo()).definition();
                sourceDefinitions.put(ref.objectId(), definition);
                List<ObjectGrant> grants =
                        policy.effectiveGrants(prepared.applicationId(), definition, actor);
                if (grants.isEmpty()) throw new AccessDeniedException("没有指定应用来源对象的查看权限");
                granted.put(ref.objectId(), List.copyOf(grants));
            }
            if (request != null
                    && !Objects.equals(
                            prepared.context(), context(prepared.config(), request, actor)))
                throw invalid("看板记录上下文已变化，请重新查询");
            effective = Map.copyOf(granted);
            return effective;
        }

        @Override
        public void recheck(long actor) {
            if (this.actor != actor) throw invalid("看板运行身份不匹配");
            transaction()
                    .executeWithoutResult(
                            tx -> {
                                catalog.lock(false);
                                datasets.designReadLock();
                                Prepared current =
                                        load(
                                                prepared.applicationId(),
                                                prepared.resourceId(),
                                                prepared.stamp(),
                                                actor);
                                if (!prepared.access().equals(current.access()))
                                    throw new AccessDeniedException("应用授权已变化，请重新查询");
                                if (effective != null)
                                    for (Map.Entry<String, List<ObjectGrant>> entry :
                                            effective.entrySet()) {
                                        if (!entry.getValue()
                                                .equals(
                                                        policy.effectiveGrants(
                                                                prepared.applicationId(),
                                                                sourceDefinitions.get(
                                                                        entry.getKey()),
                                                                actor)))
                                            throw new AccessDeniedException("应用成员有效授权已变化，请重新查询");
                                    }
                                if (request != null
                                        && !Objects.equals(
                                                prepared.context(),
                                                context(current.config(), request, actor)))
                                    throw invalid("看板记录上下文已变化，请重新查询");
                            });
        }
    }
}
