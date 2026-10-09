package com.richuang.os.nocode.report.service.dataset;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.formula.Calculations;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;
import com.richuang.os.nocode.metadata.service.object.ObjectDesignService;
import com.richuang.os.nocode.report.dal.dataobject.ReportDatasetDO;
import com.richuang.os.nocode.report.dal.mapper.ReportAuthorizationMapper;
import com.richuang.os.nocode.report.dal.mapper.ReportDatasetMapper;
import com.richuang.os.nocode.report.service.authorization.ReportJson;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 默认按报表查询权限发现当前租户的可用对象；精确版本与来源有效性仍由原 API 校验。 */
@Service
public class ReportDatasetCatalogServiceImpl implements ReportDatasetCatalogService {
    @Resource private ObjectDesignService designs;

    @Resource
    private com.richuang.os.nocode.report.service.authorization.ReportSourcePermissions
            sourcePermissions;

    @Resource private DataObjectApi objects;
    @Resource private PermissionCommonApi permissions;
    @Resource private ReportDatasetMapper datasets;
    @Resource private ReportJson json;
    @Resource private ReportAuthorizationMapper grants;
    @Resource private DraftValidator validator;
    @Resource private PlatformTransactionManager manager;

    @Override
    public PageResult<ReportDatasetCatalog.ObjectItem> objects(
            int pageNo, int pageSize, String search, long actor) {
        discover(actor);
        bounds(pageNo, pageSize, search);
        PageResult<DataCenter.ObjectRow> page =
                designs.page(
                        pageNo,
                        pageSize,
                        search,
                        null,
                        ObjectStatusEnum.ACTIVE.getCode(),
                        null,
                        null);
        return new PageResult<>(
                page.getList().stream()
                        .map(
                                row ->
                                        new ReportDatasetCatalog.ObjectItem(
                                                row.id(),
                                                row.objectCode(),
                                                row.objectName(),
                                                row.publishedVersion()))
                        .toList(),
                page.getTotal());
    }

    @Override
    public ReportDatasetCatalog.ObjectVersion object(String id, Integer versionNo, long actor) {
        discover(actor);
        if (versionNo != null && versionNo < 1) throw invalid("对象发布版本无效");
        return objectProjection(id, versionNo);
    }

    private ReportDatasetCatalog.ObjectVersion objectProjection(String id, Integer versionNo) {
        DataObjectApi.PublishedObject published = objects.getVersion(id, versionNo);
        DataCenter.Definition definition = published.definition();
        List<ReportDatasetCatalog.Field> fields = new ArrayList<>();
        for (FieldDefinition original : definition.fields()) {
            DataCenter.FieldOptions options =
                    definition
                            .fieldOptions()
                            .getOrDefault(original.id(), DataCenter.FieldOptions.defaults());
            if (MemberStateEnum.INACTIVE.matches(options.state()) || Calculations.live(options))
                continue;
            FieldDefinition field = OrderedCalculations.queryField(original, options);
            FieldTypeEnum type = FieldTypeEnum.fromCode(field.type());
            if (!type.supportsReportGrouping()) continue;
            boolean relationKey =
                    definition.relations().stream()
                            .anyMatch(relation -> Objects.equals(relation.fieldId(), field.id()));
            fields.add(
                    new ReportDatasetCatalog.Field(
                            field.id(),
                            field.code(),
                            field.name(),
                            field.type(),
                            type.isNumeric() && !relationKey));
        }
        List<ReportDatasetCatalog.Relation> relations =
                definition.relations().stream()
                        .filter(
                                relation ->
                                        relation.sourceDetailId() == null
                                                && !RelationTypeEnum.MANY_TO_MANY.matches(
                                                        relation.kind()))
                        .map(
                                relation ->
                                        new ReportDatasetCatalog.Relation(
                                                relation.id(),
                                                relation.name(),
                                                relation.fieldId(),
                                                relation.targetObjectId()))
                        .toList();
        return new ReportDatasetCatalog.ObjectVersion(
                new ReportDatasets.ObjectReference(id, published.versionNo(), published.checksum()),
                definition.objectName(),
                List.copyOf(fields),
                relations);
    }

    @Override
    public PageResult<ReportDatasetCatalog.AuthorizationTarget> authorizationTargets(
            int pageNo, int pageSize, String search, long actor) {
        permission(actor, "nocode:object:share");
        bounds(pageNo, pageSize, search);
        String pattern =
                "%"
                        + Objects.toString(search, "")
                                .trim()
                                .replace("!", "!!")
                                .replace("%", "!%")
                                .replace("_", "!_")
                        + "%";
        IPage<ReportDatasetDO> page =
                datasets.authorizationTargets(new Page<>(pageNo, pageSize), pattern);
        return new PageResult<>(
                page.getRecords().stream().map(this::target).toList(), page.getTotal());
    }

    private ReportDatasetCatalog.AuthorizationTarget target(ReportDatasetDO row) {
        ReportDatasets.Content content =
                json.read(row.getDraftJson(), new TypeReference<ReportDatasets.Content>() {});
        Set<ReportDatasets.ObjectReference> references = new LinkedHashSet<>();
        if (content.source() != null) {
            references.add(content.source().root());
            content.source().relations().forEach(relation -> references.add(relation.target()));
        }
        return new ReportDatasetCatalog.AuthorizationTarget(
                row.getId().toString(), row.getName(), row.getStatus(), List.copyOf(references));
    }

    @Override
    public List<ReportDatasetCatalog.AuthorizationObject> authorizationObjects(
            String id, long actor) {
        permission(actor, "nocode:object:share");
        long datasetId = validator.id(id, "数据集");
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            datasets.designReadLock();
                            if (datasets.lock(datasetId, false) == null)
                                throw new AccessDeniedException("数据集不存在或无权访问");
                            List<ReportDatasetCatalog.AuthorizationObject> result =
                                    new ArrayList<>();
                            for (String objectId : grants.authorizationObjectIds(datasetId)) {
                                try {
                                    ReportDatasetCatalog.ObjectVersion projection =
                                            objectProjection(objectId, null);
                                    result.add(
                                            new ReportDatasetCatalog.AuthorizationObject(
                                                    objectId, projection.name(), projection, null));
                                } catch (ServiceException exception) {
                                    // 无法继续授权时仍须提供撤销入口；不放开对象状态或版本校验。
                                    result.add(
                                            new ReportDatasetCatalog.AuthorizationObject(
                                                    objectId,
                                                    "对象 " + objectId,
                                                    null,
                                                    "来源对象已停用或不可用，仅可撤销已有上限"));
                                }
                            }
                            return List.copyOf(result);
                        });
    }

    private void discover(long actor) {
        permission(actor, "nocode:report:query");
        if (sourcePermissions.enabled()) permission(actor, "nocode:object:query");
    }

    private void permission(long actor, String code) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, code))
            throw new AccessDeniedException("没有数据集目录操作权限");
    }

    private void bounds(int pageNo, int pageSize, String search) {
        if (pageNo < 1
                || pageNo > 10000
                || pageSize < 1
                || pageSize > 100
                || search != null && search.length() > 80) throw invalid("目录分页或搜索词超出范围");
    }
}
