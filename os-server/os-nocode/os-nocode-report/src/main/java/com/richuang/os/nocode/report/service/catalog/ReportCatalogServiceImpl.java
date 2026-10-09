package com.richuang.os.nocode.report.service.catalog;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.report.dal.dataobject.*;
import com.richuang.os.nocode.report.dal.mapper.ReportCatalogMapper;
import com.richuang.os.nocode.report.dal.mapper.ReportDatasetMapper;
import com.richuang.os.nocode.report.service.authorization.ReportJson;
import com.richuang.os.nocode.report.service.authorization.ReportPrincipals;
import com.richuang.os.nocode.report.service.authorization.ReportResourceAccess;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceService;
import com.richuang.os.nocode.report.service.dataset.ReportReadCompatibility;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 目录只读精确发布快照；应用写事务维护索引，不读取业务记录或修改任何授权。 */
@Service
public class ReportCatalogServiceImpl implements ReportCatalogService {
    @Resource private ReportCatalogMapper store;
    @Resource private ReportDatasetMapper datasets;
    @Resource private ReportDatasetSourceService sources;
    @Resource private DataObjectApi objects;
    @Resource private ReportResourceAccess access;
    @Resource private ReportJson json;
    @Resource private PlatformTransactionManager manager;
    @Resource private ReportPrincipals principals;

    @Override
    public com.richuang.os.framework.common.pojo.PageResult<ApplicationDashboards.Candidate> page(
            int page, int size, String search, long actor) {
        if (page < 1 || size < 1 || size > 100) throw invalid("目录分页参数无效");
        String term = search == null ? "" : search.trim();
        if (term.length() > 80) throw invalid("搜索名称最多80字符");
        ReportPrincipals.Snapshot identity = principals.snapshot(actor);
        com.richuang.os.framework.common.pojo.PageResult<ApplicationDashboards.Candidate> result =
                new TransactionTemplate(manager)
                        .execute(
                                tx -> {
                                    datasets.designReadLock();
                                    com.baomidou.mybatisplus.core.metadata.IPage<ReportDashboardDO>
                                            found =
                                                    store.page(
                                                            new com.baomidou.mybatisplus.extension
                                                                    .plugins.pagination.Page<>(
                                                                    page, size),
                                                            actor,
                                                            identity.principals(),
                                                            "%"
                                                                    + term.replace("!", "!!")
                                                                            .replace("%", "!%")
                                                                            .replace("_", "!_")
                                                                    + "%");
                                    List<ApplicationDashboards.Candidate> items =
                                            found.getRecords().stream()
                                                    .map(
                                                            row -> {
                                                                ReportDashboardVersionDO version =
                                                                        store.version(
                                                                                row.getId(),
                                                                                row
                                                                                        .getPublishedVersion());
                                                                ReportDashboards.Content content =
                                                                        json.read(
                                                                                version
                                                                                        .getDefinitionJson(),
                                                                                new TypeReference<
                                                                                        ReportDashboards
                                                                                                .Content>() {});
                                                                return new ApplicationDashboards
                                                                        .Candidate(
                                                                        row.getId().toString(),
                                                                        content.name(),
                                                                        version.getVersionNo(),
                                                                        version.getChecksum(),
                                                                        content.charts().size());
                                                            })
                                                    .toList();
                                    return new com.richuang.os.framework.common.pojo.PageResult<>(
                                            items, found.getTotal());
                                });
        principals.unchanged(identity);
        return result;
    }

    @Override
    public ApplicationDashboards.Catalog fixed(
            ApplicationDashboards.Reference reference,
            List<ApplicationCenter.ObjectReference> applicationObjects,
            long actor) {
        validate(reference);
        if (actor <= 0) throw new AccessDeniedException("请先登录");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            // 与应用写入同序；外层已有应用/设计锁时加入该事务，不开启第二个连接。
                            datasets.designReadLock();
                            ReportDashboardDO dashboard = store.dashboard(id(reference.id()));
                            if (dashboard == null) throw invalid("应用引用的仪表板不存在");
                            access.require(
                                    ReportResourceKindEnum.DASHBOARD,
                                    dashboard.getId(),
                                    dashboard.getOwnerId(),
                                    actor,
                                    ReportResourceActionEnum.VIEW);
                            if (!ReportResourceStatusEnum.ACTIVE.matches(dashboard.getStatus()))
                                throw invalid("应用引用的仪表板已停用");
                            ReportDashboardVersionDO version =
                                    store.version(dashboard.getId(), reference.versionNo());
                            if (version == null
                                    || !reference.checksum().equals(version.getChecksum()))
                                throw invalid("应用固定仪表板版本或校验和失效");
                            ReportDashboards.Content content =
                                    json.read(
                                            version.getDefinitionJson(),
                                            new TypeReference<ReportDashboards.Content>() {});
                            List<ReportDashboards.Dataset> refs =
                                    content.charts().stream()
                                            .map(ReportDashboards.Chart::dataset)
                                            .distinct()
                                            .sorted(
                                                    Comparator.comparingLong(
                                                                    (ReportDashboards.Dataset
                                                                                    ref) ->
                                                                            id(ref.id()))
                                                            .thenComparingInt(
                                                                    ReportDashboards.Dataset
                                                                            ::versionNo))
                                            .toList();
                            Map<String, DataCenter.Definition> definitions =
                                    applicationDefinitions(applicationObjects);
                            List<ApplicationDashboards.DatasetContract> contracts =
                                    new ArrayList<>();
                            for (ReportDashboards.Dataset ref : refs) {
                                ReportDatasetDO dataset = datasets.lock(id(ref.id()), false);
                                if (dataset == null
                                        || !ReportResourceStatusEnum.ACTIVE.matches(
                                                dataset.getStatus()))
                                    throw invalid("应用固定看板的数据集已失效或停用");
                                ReportDatasetVersionDO fixed =
                                        datasets.version(dataset.getId(), ref.versionNo());
                                if (fixed == null || !fixed.getChecksum().equals(ref.checksum()))
                                    throw invalid("应用固定看板的数据集版本校验失败");
                                ReportDatasets.Content definition =
                                        json.read(
                                                fixed.getDefinitionJson(),
                                                new TypeReference<ReportDatasets.Content>() {});
                                ReportDatasets.ResolvedSource source =
                                        sources.resolve(definition.source());
                                compatible(source, definitions);
                                contracts.add(
                                        new ApplicationDashboards.DatasetContract(
                                                ref, definition, source));
                            }
                            return new ApplicationDashboards.Catalog(
                                    reference, content, List.copyOf(contracts));
                        });
    }

    @Override
    public void registerApplicationDraft(
            String applicationId,
            List<ApplicationDashboards.Reference> references,
            List<ApplicationCenter.ObjectReference> applicationObjects,
            long actor) {
        register(applicationId, 0, references, applicationObjects, actor);
    }

    @Override
    public void registerApplicationVersion(
            String applicationId,
            int versionNo,
            List<ApplicationDashboards.Reference> references,
            List<ApplicationCenter.ObjectReference> applicationObjects,
            long actor) {
        if (versionNo < 1) throw invalid("应用依赖须指定发布版本");
        register(applicationId, versionNo, references, applicationObjects, actor);
    }

    private void register(
            String applicationId,
            int number,
            List<ApplicationDashboards.Reference> references,
            List<ApplicationCenter.ObjectReference> applicationObjects,
            long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("应用报表依赖必须与应用写入处于同一事务");
        if (actor <= 0 || references == null) throw invalid("缺少应用报表依赖或受信操作者");
        long application = id(applicationId);
        // 调用方已持目录和设计写锁；复用同一设计锁保证引用登记和目标删除互斥。
        datasets.designLock();
        List<ApplicationDashboards.Catalog> fixed =
                references.stream()
                        .distinct()
                        .sorted(
                                Comparator.comparingLong(
                                                (ApplicationDashboards.Reference ref) ->
                                                        id(ref.id()))
                                        .thenComparingInt(
                                                ApplicationDashboards.Reference::versionNo))
                        .map(ref -> fixed(ref, applicationObjects, actor))
                        .toList();
        if (number == 0) store.clearDraft(application, Long.toString(actor));
        for (ApplicationDashboards.Catalog catalog : fixed) {
            List<String> identities = new ArrayList<>();
            catalog.content().charts().forEach(chart -> identities.add("chart:" + chart.id()));
            if (catalog.content().filters() != null)
                catalog.content()
                        .filters()
                        .forEach(filter -> identities.add("filter:" + filter.id()));
            store.dependency(
                    application,
                    number,
                    id(catalog.reference().id()),
                    catalog.reference().versionNo(),
                    json.write(identities),
                    Long.toString(actor));
        }
    }

    private Map<String, DataCenter.Definition> applicationDefinitions(
            List<ApplicationCenter.ObjectReference> refs) {
        if (refs == null) throw invalid("缺少应用固定对象引用");
        Map<String, DataCenter.Definition> result = new LinkedHashMap<>();
        for (ApplicationCenter.ObjectReference ref : refs) {
            if (ref == null || ref.versionNo() < 1 || ref.checksum() == null)
                throw invalid("应用固定对象引用无效");
            DataObjectApi.PublishedObject fixed =
                    objects.getVersion(ref.objectId(), ref.versionNo());
            if (!ref.checksum().equals(fixed.checksum())) throw invalid("应用固定对象校验和失效");
            if (result.putIfAbsent(ref.objectId(), fixed.definition()) != null)
                throw invalid("应用不能重复引用同一对象");
        }
        return result;
    }

    /** 两边版本可以不同，只比较真正参与来源的稳定字段、关系及表绑定，不以最新定义替代任一边。 */
    private void compatible(
            ReportDatasets.ResolvedSource source,
            Map<String, DataCenter.Definition> applicationDefinitions) {
        Map<String, DataCenter.Definition> reportDefinitions = new LinkedHashMap<>();
        for (ReportDatasets.ObjectReference ref : source.objects()) {
            DataCenter.Definition app = applicationDefinitions.get(ref.objectId());
            if (app == null) throw invalid("看板来源对象尚未加入应用：" + ref.objectId());
            DataCenter.Definition report =
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition();
            if (!ReportReadCompatibility.table(report, app))
                throw invalid("应用与看板的固定对象表绑定不兼容：" + ref.objectId());
            reportDefinitions.put(ref.objectId(), report);
        }
        for (ReportDatasets.ResolvedField field : source.fields())
            compatibleField(
                    reportDefinitions.get(field.objectId()),
                    applicationDefinitions.get(field.objectId()),
                    field.sourceFieldId());
        Map<String, String> paths = new HashMap<>();
        paths.put("", source.source().root().objectId());
        for (ReportDatasets.Relation ref :
                source.source().relations().stream()
                        .sorted(Comparator.comparingInt(relation -> relation.parentPath().size()))
                        .toList()) {
            String owner = paths.get(String.join("/", ref.parentPath()));
            DataCenter.Definition report = reportDefinitions.get(owner);
            DataCenter.Definition app = applicationDefinitions.get(owner);
            DataCenter.Relation before = relation(report, ref.relationId());
            DataCenter.Relation after = relation(app, ref.relationId());
            if (before == null
                    || after == null
                    || !Objects.equals(before.kind(), after.kind())
                    || !Objects.equals(before.fieldId(), after.fieldId())
                    || !Objects.equals(before.targetObjectId(), after.targetObjectId())
                    || !Objects.equals(before.targetFieldId(), after.targetFieldId())
                    || !Objects.equals(before.sourceDetailId(), after.sourceDetailId()))
                throw invalid("应用与看板的固定关联不兼容：" + ref.relationId());
            compatibleField(report, app, before.fieldId());
            if (before.targetFieldId() != null && !before.targetFieldId().isBlank())
                compatibleField(
                        reportDefinitions.get(ref.target().objectId()),
                        applicationDefinitions.get(ref.target().objectId()),
                        before.targetFieldId());
            List<String> path = new ArrayList<>(ref.parentPath());
            path.add(ref.id());
            paths.put(String.join("/", path), ref.target().objectId());
        }
    }

    private DataCenter.Relation relation(DataCenter.Definition definition, String id) {
        return definition.relations().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private void compatibleField(
            DataCenter.Definition report, DataCenter.Definition app, String id) {
        if (!ReportReadCompatibility.field(
                FieldConversionCompatibility.fields(report).get(id),
                FieldConversionCompatibility.fields(app).get(id)))
            throw invalid("应用与看板的固定字段不兼容：" + id);
    }

    private void validate(ApplicationDashboards.Reference reference) {
        if (reference == null
                || reference.versionNo() < 1
                || reference.checksum() == null
                || !reference.checksum().matches("[a-f0-9]{64}")) throw invalid("请选择仪表板固定发布版本和校验和");
        id(reference.id());
    }

    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException();
            return Long.parseLong(value);
        } catch (RuntimeException error) {
            throw invalid("报表引用身份无效");
        }
    }
}
