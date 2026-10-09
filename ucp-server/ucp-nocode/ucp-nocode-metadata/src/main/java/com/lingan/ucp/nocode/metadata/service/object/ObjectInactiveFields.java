package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 停用字段沿用原成员身份；仅在完整设计保存事务内恢复，不开放独立写入口。 */
@Component
public class ObjectInactiveFields {
    @Resource private DataCenterMapper store;
    @Resource private ObjectDesignReader reader;
    @Resource private DraftValidator validator;

    List<InactiveField> list(ObjectDraftHeadDO head, String detailId) {
        long tableId = head.getTableId();
        boolean adopted = ObjectSourceEnum.ADOPTED.matches(head.getSourceType());
        if (detailId != null && !detailId.isBlank()) {
            validator.id(detailId, "明细 ID");
            var detail =
                    reader.load(head).details().stream()
                            .filter(d -> detailId.equals(d.id()))
                            .findFirst()
                            .orElseThrow(() -> invalid("明细不属于当前对象"));
            tableId =
                    store.details(head.getVersionId()).stream()
                            .filter(t -> detailId.equals(t.getStableTableId().toString()))
                            .findFirst()
                            .orElseThrow()
                            .getId();
            adopted = detail.binding().adopted();
        }
        return candidates(head, tableId, adopted, reader.options(head.getVersionId()));
    }

    private List<InactiveField> candidates(
            ObjectDraftHeadDO head,
            long tableId,
            boolean adopted,
            Map<String, FieldOptions> options) {
        var result = new ArrayList<InactiveField>();
        for (var field : store.inactiveFields(head.getVersionId(), tableId)) {
            var option = options.getOrDefault(field.id(), FieldOptions.defaults());
            String reason =
                    Boolean.TRUE.equals(option.generated())
                            ? "关系生成字段请通过关系配置维护"
                            : adopted ? "纳管字段请通过物理差异同步维护映射" : null;
            result.add(new InactiveField(field, option, reason == null, reason));
        }
        return result;
    }

    /** 对象锁和预期修订先行，全部校验通过后才更新当前草稿中的停用行。 */
    void prepare(ObjectDraftHeadDO head, Design previous, SaveDesign request, long actor) {
        var requested =
                request.restoredFieldIds() == null ? List.<String>of() : request.restoredFieldIds();
        if (head == null) {
            if (!requested.isEmpty()) throw invalid("新对象没有可恢复字段");
            return;
        }
        if (!Objects.equals(head.getLockVersion(), request.draft().expectedLockVersion()))
            throw new ServiceException(CONFLICT, "草稿已被其他人修改，请刷新后合并");
        if (!VersionStateEnum.DRAFT.matches(head.getVersionState())
                || Objects.equals(head.getLatestVersionNo(), head.getCurrentPublishedVersionNo())
                || !Set.of(ObjectStatusEnum.ACTIVE.getCode(), ObjectStatusEnum.DRAFT.getCode())
                        .contains(head.getStatus()))
            throw new ServiceException(UNSUPPORTED_STATE, "请先为有效对象创建可编辑草稿");
        var restoreIds = new HashSet<String>();
        for (var id : requested) {
            validator.id(id, "恢复字段 ID");
            if (!restoreIds.add(id)) throw invalid("恢复字段 ID 重复");
        }
        var accepted = new HashSet<String>();
        var options = reader.options(head.getVersionId());
        validateTable(
                candidates(
                        head,
                        head.getTableId(),
                        ObjectSourceEnum.ADOPTED.matches(head.getSourceType()),
                        options),
                previous.draft().fields(),
                request.draft().fields(),
                request.draft().removedFieldIds(),
                request.fieldOptions(),
                restoreIds,
                accepted);
        if (request.details() != null) {
            var tableIds = new HashMap<String, Long>();
            store.details(head.getVersionId())
                    .forEach(t -> tableIds.put(t.getStableTableId().toString(), t.getId()));
            for (var detail : request.details()) {
                if (detail == null) throw invalid("内部明细不能为空");
                if (detail.id() == null) continue;
                if (detail.fields() == null || detail.fields().stream().anyMatch(Objects::isNull))
                    throw invalid("明细字段不能为空");
                var old =
                        previous.details().stream()
                                .filter(d -> detail.id().equals(d.id()))
                                .findFirst()
                                .orElseThrow(() -> invalid("明细 ID 不属于当前对象"));
                var incomingIds =
                        detail.fields() == null
                                ? Set.<String>of()
                                : detail.fields().stream()
                                        .map(FieldDefinition::id)
                                        .filter(Objects::nonNull)
                                        .collect(java.util.stream.Collectors.toSet());
                var removed =
                        old.fields().stream()
                                .map(FieldDefinition::id)
                                .filter(id -> !incomingIds.contains(id))
                                .toList();
                if (incomingIds.stream().anyMatch(restoreIds::contains)
                        && !MemberStateEnum.ACTIVE.matches(
                                Objects.toString(detail.state(), MemberStateEnum.ACTIVE.getCode())))
                    throw invalid("请先启用内部明细，再恢复其中的字段");
                validateTable(
                        candidates(
                                head, tableIds.get(detail.id()), old.binding().adopted(), options),
                        old.fields(),
                        detail.fields(),
                        removed,
                        detail.fieldOptions(),
                        restoreIds,
                        accepted);
            }
        }
        if (!accepted.equals(restoreIds))
            throw new ServiceException(FOREIGN_FIELD, "恢复字段不属于当前对象或未放回原字段配置");
        for (var id : restoreIds) {
            if (store.restoreField(head.getVersionId(), Long.parseLong(id), actor) != 1)
                throw new ServiceException(CONFLICT, "停用字段状态已变化，请刷新后重试");
        }
    }

    private void validateTable(
            List<InactiveField> inactive,
            List<FieldDefinition> active,
            List<FieldDefinition> incoming,
            List<String> removed,
            Map<String, FieldOptions> supplied,
            Set<String> restoreIds,
            Set<String> accepted) {
        if (incoming == null || incoming.stream().anyMatch(Objects::isNull))
            throw invalid("字段列表及字段不能为空");
        var removedIds = removed == null ? List.<String>of() : removed;
        for (var candidate : inactive) {
            var original = candidate.field();
            var changed =
                    incoming.stream()
                            .filter(f -> original.id().equals(f.id()))
                            .findFirst()
                            .orElse(null);
            if (restoreIds.contains(original.id()) && changed != null) {
                if (!candidate.restorable()) throw invalid(candidate.blockedReason());
                if (removedIds.contains(original.id())) throw invalid("同一字段不能同时停用和恢复");
                if (!ObjectFieldOperationRules.restorationIdentityMatches(original, changed))
                    throw invalid("恢复时必须保留原字段编码、类型和存储长度，请先恢复再修改");
                var option = supplied == null ? null : supplied.get(original.id());
                if (option != null && !MemberStateEnum.ACTIVE.matches(option.state()))
                    throw invalid("恢复字段应处于启用状态");
                accepted.add(original.id());
            } else if (changed != null) {
                throw new ServiceException(FOREIGN_FIELD, "停用字段须通过已停用字段入口显式恢复");
            }
            for (var field : incoming) {
                if (!original.id().equals(field.id())
                        && (original.code().equals(field.code())
                                || Objects.equals(candidate.options().columnName(), field.code())))
                    throw occupied(original);
            }
        }
        // 同一保存里先停用再新建，也不能接管原字段编码或物理列。
        for (var original : active) {
            if (!removedIds.contains(original.id())) continue;
            for (var field : incoming)
                if (!original.id().equals(field.id()) && original.code().equals(field.code()))
                    throw occupied(original);
        }
    }

    private ServiceException occupied(FieldDefinition field) {
        return new ServiceException(
                DUPLICATE, "编码已被停用字段“" + field.name() + "”（" + field.code() + "）占用，请恢复原字段或更换编码");
    }
}
