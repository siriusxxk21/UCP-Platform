package com.richuang.os.module.bpm.framework.flowable.core.interceptor;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.TASK_BUSINESS_SKIP_NOT_ALLOWED;

import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;

import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.interceptor.CreateUserTaskAfterContext;
import org.flowable.engine.interceptor.CreateUserTaskBeforeContext;
import org.flowable.engine.interceptor.CreateUserTaskInterceptor;

/**
 * 业务节点创建前的跳过保护，覆盖已经发布的模型与引擎动态属性。
 *
 * <p>Flowable 的 skip 分支不触发 TASK_COMPLETED，不能仅依赖完成 Guard。此钩子在 skip
 * 表达式求值之前执行，异常沿当前引擎事务回滚，不清空表达式或将节点静默降为普通审批。
 */
public final class BpmBusinessTaskCreateInterceptor implements CreateUserTaskInterceptor {
    private final CreateUserTaskInterceptor delegate;

    /** 包装引擎已配置的拦截器，保留其他模块的前置及后置行为。 */
    public BpmBusinessTaskCreateInterceptor(CreateUserTaskInterceptor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void beforeCreateUserTask(CreateUserTaskBeforeContext context) {
        boolean business = hasBusinessBinding(context.getUserTask());
        validate(context, business);
        if (delegate != null) delegate.beforeCreateUserTask(context);
        validate(context, business);
    }

    private void validate(CreateUserTaskBeforeContext context, boolean business) {
        if ((business || hasBusinessBinding(context.getUserTask()))
                && (hasExpression(context.getSkipExpression())
                        || hasExpression(context.getUserTask().getSkipExpression()))) {
            throw exception(TASK_BUSINESS_SKIP_NOT_ALLOWED, context.getUserTask().getName());
        }
    }

    @Override
    public void afterCreateUserTask(CreateUserTaskAfterContext context) {
        if (delegate != null) delegate.afterCreateUserTask(context);
    }

    private boolean hasBusinessBinding(UserTask node) {
        return node.getAttributeValue(
                                BpmBusinessTaskBinding.NAMESPACE,
                                BpmBusinessTaskBinding.HANDLER_ATTRIBUTE)
                        != null
                || node.getAttributeValue(
                                BpmBusinessTaskBinding.NAMESPACE,
                                BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE)
                        != null;
    }

    private boolean hasExpression(String value) {
        return value != null && !value.isEmpty();
    }
}
