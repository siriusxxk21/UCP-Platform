package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.ObjectOperationPreview.*;
import com.lingan.ucp.nocode.api.ObjectOperationPreview.DataScope;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.*;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/** 字段操作只读模拟，物理数据范围始终由保存的身份定位，拟议草稿只能影响规则分析。 */
@Component
public class ObjectFieldOperationChecks {
    @Resource private ObjectDesignReader reader;
    @Resource private ObjectInactiveFields inactive;
    @Resource private ObjectDesignReferences references;
    @Resource private DataCenterMapper store;
    @Resource private DatabaseMetadataReader database;
    @Resource private ObjectOperationDataQueries data;
    @Resource private FieldConstraintCheckMapper constraints;
    @Resource private ObjectProvider<FieldConversionDependencyInspector> inspectors;

    record Evaluation(List<Impact> impacts, List<DataScope> scopes) {}

    private record Located(
            FieldDefinition field,
            FieldOptions options,
            TableBinding binding,
            String table,
            String location,
            boolean activeDetail) {}

    Evaluation inspect(ObjectDraftHeadDO head, Request request, ObjectOperationEnum operation) {
        Definition current = reader.definition(request.objectId());
        boolean restore = operation == ObjectOperationEnum.RESTORE_FIELD;
        Located original = locate(head, current, request, restore);
        List<Impact> impacts = new ArrayList<>();
        List<DataScope> scopes = new ArrayList<>();
        if (!VersionStateEnum.DRAFT.matches(head.getVersionState())
                || Objects.equals(head.getLatestVersionNo(), head.getCurrentPublishedVersionNo())
                || !Set.of(ObjectStatusEnum.ACTIVE.getCode(), ObjectStatusEnum.DRAFT.getCode())
                        .contains(head.getStatus()))
            impacts.add(
                    own(
                            ObjectOperationCheckEnum.EDITABLE_DRAFT,
                            current,
                            original,
                            "请先为有效对象创建可编辑草稿",
                            "停用对象先启用；已发布版本先创建编辑草稿"));
        String protection = protection(original);
        if (protection != null)
            impacts.add(
                    own(
                            ObjectOperationCheckEnum.PROTECTED_FIELD,
                            current,
                            original,
                            protection,
                            original.binding().adopted()
                                    ? "在原表维护后使用物理差异同步入口"
                                    : "关系生成字段从对象关系维护；主键和公共列不支持此操作"));
        Definition before =
                ObjectOperationDrafts.proposed(current, request.proposed(), head.getLockVersion());
        if (restore && !activeDetail(before, request.detailId()))
            impacts.add(
                    own(
                            ObjectOperationCheckEnum.DETAIL_INACTIVE,
                            current,
                            original,
                            "请先启用内部明细，再恢复其中的字段",
                            "在内部明细页启用该明细后重新检查"));
        FieldDefinition supplied = field(before, request.detailId(), request.fieldId());
        if (restore
                && supplied != null
                && !ObjectFieldOperationRules.restorationIdentityMatches(
                        original.field(), supplied))
            impacts.add(
                    own(
                            ObjectOperationCheckEnum.FIELD_IDENTITY,
                            current,
                            original,
                            "恢复时必须保留原字段编码、类型和存储长度，请先恢复再修改",
                            "取消对原字段身份及存储属性的修改，再恢复原字段"));
        Definition proposed =
                ObjectOperationDrafts.apply(
                        before,
                        request.detailId(),
                        original.field(),
                        active(original.options()),
                        restore);
        impacts.addAll(
                ObjectFieldOperationRules.references(proposed, store.dependencies(head.getId())));
        configuration(proposed, original, impacts);
        if (!restore) dependencies(before, proposed, original, impacts);
        physical(head, current, original, restore, impacts, scopes);
        return new Evaluation(List.copyOf(new LinkedHashSet<>(impacts)), List.copyOf(scopes));
    }

    private Located locate(
            ObjectDraftHeadDO head, Definition current, Request request, boolean restore) {
        Detail detail =
                request.detailId() == null
                        ? null
                        : current.details().stream()
                                .filter(item -> request.detailId().equals(item.id()))
                                .findFirst()
                                .orElseThrow(() -> invalid("明细不属于当前对象"));
        FieldDefinition field;
        FieldOptions option;
        if (restore) {
            InactiveField candidate =
                    inactive.list(head, request.detailId()).stream()
                            .filter(item -> request.fieldId().equals(item.field().id()))
                            .findFirst()
                            .orElseThrow(() -> invalid("恢复字段不属于当前对象或已不处于停用状态"));
            field = candidate.field();
            option = candidate.options();
        } else {
            field = field(current, request.detailId(), request.fieldId());
            if (field == null) throw invalid("停用字段不属于当前对象的有效字段");
            option =
                    (detail == null ? current.fieldOptions() : detail.fieldOptions())
                            .getOrDefault(field.id(), FieldOptions.defaults());
        }
        return new Located(
                field,
                option,
                detail == null ? ObjectTables.main(current) : ObjectTables.detail(current, detail),
                detail == null ? current.tableName() : detail.tableName(),
                (detail == null ? "主表字段" : "内部明细 / " + detail.name()) + " / " + field.name(),
                detail == null || MemberStateEnum.ACTIVE.matches(detail.state()));
    }

    private static String protection(Located field) {
        if (Boolean.TRUE.equals(field.options().generated())) return "关系生成字段请通过关系配置维护";
        if (field.binding().adopted()) return "纳管字段请通过物理差异同步维护映射";
        String column = column(field);
        if (Boolean.TRUE.equals(field.options().primaryKey())
                || column.equals(field.binding().keyColumn())
                || Set.of(
                                "id",
                                "creator",
                                "create_time",
                                "updater",
                                "update_time",
                                "deleted",
                                "parent_id")
                        .contains(column)) return "主键、公共列及明细归属列不能通过普通字段停用或恢复";
        return null;
    }

    private void dependencies(
            Definition before, Definition proposed, Located field, List<Impact> impacts) {
        for (FieldConversionDependencyInspector inspector : inspectors.orderedStream().toList())
            for (FieldConversionDependencyInspector.Impact found :
                    inspector.inspect(before, proposed, Set.of(field.field().id()))) {
                boolean app =
                        FieldConversionDependencyInspector.SourceKind.APPLICATION
                                .name()
                                .equals(found.sourceKind());
                impacts.add(
                        new Impact(
                                ObjectOperationCheckEnum.DEPENDENCY.getCode(),
                                app ? false : found.blocking(),
                                found.sourceKind(),
                                found.sourceId(),
                                found.sourceName(),
                                found.fieldId(),
                                found.location(),
                                found.message(),
                                app ? "可先保存草稿；发布时检查是否需要暂停此应用，适配后分别发布启用" : "前往该位置解除或调整依赖，再重新检查",
                                found.route()));
            }
    }

    private void configuration(Definition definition, Located field, List<Impact> impacts) {
        checkConfiguration(
                definition, field, "整单规则与状态", impacts, () -> DocumentPolicies.validate(definition));
        checkConfiguration(
                definition,
                field,
                "字段计算",
                impacts,
                () ->
                        Calculations.validate(
                                definition,
                                id ->
                                        id.equals(definition.objectId())
                                                ? definition
                                                : reader.published(id)));
        checkConfiguration(
                definition,
                field,
                "主表字段 / 公式与汇总",
                impacts,
                () ->
                        references.validateExpressions(
                                definition.fields(),
                                definition.fieldOptions(),
                                definition.details(),
                                true));
        for (Detail detail : definition.details())
            if (MemberStateEnum.ACTIVE.matches(detail.state()))
                checkConfiguration(
                        definition,
                        field,
                        "内部明细 / " + detail.name() + " / 公式",
                        impacts,
                        () ->
                                references.validateExpressions(
                                        detail.fields(), detail.fieldOptions(), List.of(), false));
    }

    private void checkConfiguration(
            Definition definition,
            Located field,
            String location,
            List<Impact> impacts,
            Runnable check) {
        try {
            check.run();
        } catch (ServiceException | IllegalArgumentException exception) {
            impacts.add(
                    ObjectOperationImpacts.own(
                            ObjectOperationCheckEnum.CONFIGURATION,
                            definition,
                            field.field().id(),
                            location,
                            exception.getMessage(),
                            "在此配置位置修正字段引用或规则后重新检查"));
        }
    }

    private void physical(
            ObjectDraftHeadDO head,
            Definition definition,
            Located field,
            boolean restore,
            List<Impact> impacts,
            List<DataScope> scopes) {
        if (FieldTypeEnum.SUMMARY.matches(field.field().type())) {
            scopes.add(
                    new DataScope(
                            ObjectOperationScopeEnum.FIELD.getCode(),
                            field.location(),
                            field.binding().schemaName(),
                            field.table(),
                            null,
                            null,
                            null,
                            true,
                            "虚拟汇总没有独立物理列；原配置身份保留"));
            return;
        }
        String schema = field.binding().schemaName(), column = column(field);
        DatabaseMetadata.Table table = database.readTable(schema, field.table()).orElse(null);
        DatabaseMetadata.Column actual =
                table == null
                        ? null
                        : table.columns().stream()
                                .filter(item -> column.equals(item.name()))
                                .findFirst()
                                .orElse(null);
        boolean deployed =
                store.lastPublishedFieldSchema(head.getId(), Long.parseLong(field.field().id()))
                        != null;
        if (actual == null) {
            if (restore && deployed)
                impacts.add(
                        own(
                                ObjectOperationCheckEnum.MISSING_COLUMN,
                                definition,
                                field,
                                "原字段物理列不存在，无法恢复历史列",
                                "先核对并修复结构漂移，再通过原字段恢复；不能新建同名列冒充恢复"));
            scopes.add(
                    new DataScope(
                            ObjectOperationScopeEnum.FIELD.getCode(),
                            field.location(),
                            schema,
                            field.table(),
                            column,
                            null,
                            null,
                            true,
                            deployed ? "原物理列缺失，无法核实历史数据" : "此字段尚未部署物理列，无已部署列数据可清空"));
            return;
        }
        ObjectOperationDataMapper.Counts counts = data.column(schema, field.table(), column);
        scopes.add(
                new DataScope(
                        ObjectOperationScopeEnum.FIELD.getCode(),
                        field.location(),
                        schema,
                        field.table(),
                        column,
                        counts == null ? null : counts.rows(),
                        counts == null ? null : counts.nonNulls(),
                        true,
                        "原列、全部记录及其他列保留；统计包含逻辑删除行，本操作不清空数据"));
        if (counts == null) {
            impacts.add(unknown(definition, field));
            return;
        }
        if (!restore) return;
        if (!FieldStorage.accepts(
                FieldStorage.sqlType(field.field(), field.options()), actual.nativeType())) {
            impacts.add(
                    own(
                            ObjectOperationCheckEnum.COLUMN_TYPE,
                            definition,
                            field,
                            "原物理列类型与待恢复字段不兼容：" + actual.nativeType(),
                            "先核对物理差异并修复类型，再恢复原字段"));
            return;
        }
        if (Boolean.TRUE.equals(field.field().required()) && counts.rows() > counts.nonNulls())
            impacts.add(
                    restorationConstraint(
                            definition,
                            field,
                            "恢复必填字段存在 " + (counts.rows() - counts.nonNulls()) + " 条空值记录（包含逻辑删除）",
                            "先恢复到草稿，再在字段配置调整必填；也可通过已有授权业务入口补合法值，发布前重新检查"));
        if (Boolean.TRUE.equals(field.field().unique())) {
            Long duplicates = data.duplicates(schema, field.table(), column);
            if (duplicates == null) impacts.add(unknown(definition, field));
            else if (duplicates > 0)
                impacts.add(
                        restorationConstraint(
                                definition,
                                field,
                                "恢复唯一字段存在 " + duplicates + " 条重复值记录（包含逻辑删除）",
                                "先恢复到草稿，再在字段配置调整唯一约束；也可通过已有授权业务入口处理重复，发布前重新检查"));
        }
        constraintValues(definition, field, impacts);
    }

    private void constraintValues(Definition definition, Located field, List<Impact> impacts) {
        FieldOptions option = field.options();
        if (option.minimum() != null)
            conflicts(
                    definition,
                    field,
                    "最小值 " + option.minimum(),
                    impacts,
                    data.read(() -> constraints.belowMinimum(statement(field, option.minimum()))));
        if (option.maximum() != null)
            conflicts(
                    definition,
                    field,
                    "最大值 " + option.maximum(),
                    impacts,
                    data.read(() -> constraints.aboveMaximum(statement(field, option.maximum()))));
        if (option.pattern() != null && !option.pattern().isBlank())
            conflicts(
                    definition,
                    field,
                    "正则规则",
                    impacts,
                    data.read(
                            () -> constraints.patternMismatch(statement(field, option.pattern()))));
    }

    private void conflicts(
            Definition definition,
            Located field,
            String rule,
            List<Impact> impacts,
            List<FieldConstraintCheckMapper.Conflict> conflicts) {
        if (conflicts == null) impacts.add(unknown(definition, field));
        else if (!conflicts.isEmpty())
            impacts.add(
                    restorationConstraint(
                            definition,
                            field,
                            "恢复字段不满足"
                                    + rule
                                    + "的记录共 "
                                    + conflicts.getFirst().total()
                                    + " 条（包含逻辑删除）",
                            "先恢复到草稿，再在字段配置调整此约束；也可通过已有授权业务入口修正记录，发布前重新检查"));
    }

    /** 恢复只进入草稿；历史值冲突留给后续配置及发布检查，避免停用字段无处编辑的死锁。 */
    private static Impact restorationConstraint(
            Definition definition, Located field, String message, String resolution) {
        Impact impact =
                own(
                        ObjectOperationCheckEnum.FIELD_CONSTRAINT,
                        definition,
                        field,
                        "【发布前待处理】" + message,
                        resolution);
        return new Impact(
                impact.code(),
                false,
                impact.sourceKind(),
                impact.sourceId(),
                impact.sourceName(),
                impact.fieldId(),
                impact.location(),
                impact.message(),
                impact.resolution(),
                impact.route());
    }

    private static FieldConstraintCheckMapper.Statement statement(Located field, String value) {
        return new FieldConstraintCheckMapper.Statement(
                field.binding().schemaName(),
                field.table(),
                column(field),
                field.binding().keyColumn(),
                value);
    }

    private static Impact unknown(Definition definition, Located field) {
        return own(
                ObjectOperationCheckEnum.UNKNOWN_DATA,
                definition,
                field,
                "无法核实原列数据或约束，结果未知",
                "检查物理列与数据库读取权限，再重新预检；不会把未知数量当作零");
    }

    private static Impact own(
            ObjectOperationCheckEnum code,
            Definition definition,
            Located field,
            String message,
            String resolution) {
        return ObjectOperationImpacts.own(
                code, definition, field.field().id(), field.location(), message, resolution);
    }

    private static String column(Located field) {
        return field.options().columnName() == null
                ? field.field().code()
                : field.options().columnName();
    }

    private static boolean activeDetail(Definition definition, String detailId) {
        return detailId == null
                || definition.details().stream()
                        .anyMatch(
                                detail ->
                                        detailId.equals(detail.id())
                                                && MemberStateEnum.ACTIVE.matches(detail.state()));
    }

    private static FieldDefinition field(Definition definition, String detailId, String fieldId) {
        List<FieldDefinition> fields =
                detailId == null
                        ? definition.fields()
                        : definition.details().stream()
                                .filter(detail -> detailId.equals(detail.id()))
                                .findFirst()
                                .map(Detail::fields)
                                .orElse(List.of());
        return fields.stream().filter(field -> fieldId.equals(field.id())).findFirst().orElse(null);
    }

    private static FieldOptions active(FieldOptions option) {
        // 只改状态，其余配置（含对象规则）原样保留。
        return FieldOptions.copyOf(option).state(MemberStateEnum.ACTIVE.getCode()).build();
    }
}
