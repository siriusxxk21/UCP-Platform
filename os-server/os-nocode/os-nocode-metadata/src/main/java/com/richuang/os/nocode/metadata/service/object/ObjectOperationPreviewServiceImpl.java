package com.richuang.os.nocode.metadata.service.object;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.ObjectOperationPreview.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 预检使用一致只读快照；不保存草稿、不改变业务数据，也不自动修改或发布应用。 */
@Service
public class ObjectOperationPreviewServiceImpl implements ObjectOperationPreviewService {
    @Resource private ObjectDesignReader reader;
    @Resource private DraftValidator validator;
    @Resource private ObjectLifecycleChecks lifecycle;
    @Resource private ObjectFieldOperationChecks fields;
    @Resource private PlatformTransactionManager manager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Result preview(Request request) {
        if (request == null || request.expectedLockVersion() == null) throw invalid("对象及修订号必填");
        ObjectOperationEnum operation = ObjectOperationEnum.fromCode(request.operation());
        validator.id(request.objectId(), "对象 ID");
        if (operation.fieldOperation()) validator.id(request.fieldId(), "字段 ID");
        if (request.detailId() != null) validator.id(request.detailId(), "明细 ID");
        return tx.execute(status -> inspect(request, operation));
    }

    private Result inspect(Request request, ObjectOperationEnum operation) {
        ObjectDraftHeadDO head = reader.head(request.objectId(), false);
        if (!Objects.equals(request.expectedLockVersion(), head.getLockVersion()))
            throw new ServiceException(CONFLICT, "对象已变化，请刷新后重试；原操作不会自动使用新修订");
        List<Impact> impacts;
        List<DataScope> scopes;
        if (operation.fieldOperation()) {
            ObjectFieldOperationChecks.Evaluation evaluation =
                    fields.inspect(head, request, operation);
            impacts = evaluation.impacts();
            scopes = evaluation.scopes();
        } else {
            ObjectLifecycleChecks.Evaluation evaluation =
                    lifecycle.inspect(
                            head, LifecycleActionEnum.fromCode(operation.getCode()), true);
            impacts = evaluation.impacts();
            scopes = evaluation.scopes();
        }
        boolean allowed = impacts.stream().noneMatch(Impact::blocking);
        boolean restorationConflicts =
                operation == ObjectOperationEnum.RESTORE_FIELD
                        && impacts.stream()
                                .anyMatch(
                                        impact ->
                                                ObjectOperationCheckEnum.FIELD_CONSTRAINT.matches(
                                                        impact.code()));
        String summary =
                !allowed
                        ? "存在必须先处理的影响，请按具体位置处理后重新检查"
                        : restorationConflicts
                                ? "可恢复到草稿；发布前必须解决下列数据约束冲突，当前不会修改原列数据"
                                : "当前检查通过；请确认保留范围及执行步骤";
        List<String> steps =
                operation == ObjectOperationEnum.RESTORE_FIELD
                        ? List.of(
                                "确认后仅按原身份恢复到当前本地草稿，原列及历史记录保留",
                                "在恢复后的字段配置中处理发布前待处理的约束；业务数据修正仍走已有授权业务入口",
                                "保存草稿及发布仍重新检查；全部发布阻断解决后才能生效",
                                "应用配置和对象版本同步仍在应用中心显式处理并单独发布")
                        : operation.fieldOperation()
                                ? List.of(
                                        "确认后仅修改当前本地草稿，取消不会更改配置",
                                        "保存草稿时重新检查字段身份、标题、关系、索引和规则",
                                        "发布预览再次核对物理结构及历史约束，通过后发布生效",
                                        "应用配置和对象版本同步仍在应用中心显式处理并单独发布")
                                : List.of(
                                        "确认保留范围并填写本次操作原因",
                                        "执行原管理动作时重新检查权限、修订号及依赖；删除还会重新检查业务数据",
                                        "仅改变对象生命周期状态，物理表和历史记录保留；应用不会自动修改或发布");
        return new Result(
                request.objectId(),
                head.getLockVersion(),
                operation.getCode(),
                allowed,
                summary,
                impacts,
                scopes,
                steps);
    }
}
