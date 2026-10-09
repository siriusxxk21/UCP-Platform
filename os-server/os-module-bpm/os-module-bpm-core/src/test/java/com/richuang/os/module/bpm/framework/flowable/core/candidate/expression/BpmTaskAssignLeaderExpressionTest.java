package com.richuang.os.module.bpm.framework.flowable.core.candidate.expression;

import static com.richuang.os.framework.common.util.collection.SetUtils.asSet;
import static com.richuang.os.module.bpm.support.BpmTestFixtures.bean;
import static com.richuang.os.module.bpm.support.BpmTestFixtures.uniqueId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.richuang.os.module.bpm.service.task.BpmProcessInstanceService;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.dto.DeptRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

@ExtendWith(MockitoExtension.class)
public class BpmTaskAssignLeaderExpressionTest {

    @InjectMocks private BpmTaskAssignLeaderExpression expression;

    @Mock private AdminUserApi adminUserApi;
    @Mock private DeptApi deptApi;

    @Mock private BpmProcessInstanceService processInstanceService;

    @Test
    public void testCalculateUsers_noDept() {
        // 准备参数
        DelegateExecution execution = mockDelegateExecution(1L);
        // mock 方法(startUser)
        AdminUserRespDTO startUser = bean(AdminUserRespDTO.class, o -> o.setDeptId(10L));
        when(adminUserApi.getUser(eq(1L))).thenReturn(startUser);
        // mock 方法(getStartUserDept)没有部门
        when(deptApi.getDept(eq(10L))).thenReturn(null);

        // 调用
        Set<Long> result = expression.calculateUsers(execution, 1);
        // 断言
        assertEquals(0, result.size());
    }

    @Test
    public void testCalculateUsers_noParentDept() {
        // 准备参数
        DelegateExecution execution = mockDelegateExecution(1L);
        // mock 方法(startUser)
        AdminUserRespDTO startUser = bean(AdminUserRespDTO.class, o -> o.setDeptId(10L));
        when(adminUserApi.getUser(eq(1L))).thenReturn(startUser);
        DeptRespDTO startUserDept =
                bean(DeptRespDTO.class, o -> o.setId(10L).setParentId(100L).setLeaderUserId(20L));
        // mock 方法（getDept）
        when(deptApi.getDept(eq(10L))).thenReturn(startUserDept);
        when(deptApi.getDept(eq(100L))).thenReturn(null);

        // 调用
        Set<Long> result = expression.calculateUsers(execution, 2);
        // 断言
        assertEquals(asSet(20L), result);
    }

    @Test
    public void testCalculateUsers_existParentDept() {
        // 准备参数
        DelegateExecution execution = mockDelegateExecution(1L);
        // mock 方法(startUser)
        AdminUserRespDTO startUser = bean(AdminUserRespDTO.class, o -> o.setDeptId(10L));
        when(adminUserApi.getUser(eq(1L))).thenReturn(startUser);
        DeptRespDTO startUserDept =
                bean(DeptRespDTO.class, o -> o.setId(10L).setParentId(100L).setLeaderUserId(20L));
        when(deptApi.getDept(eq(10L))).thenReturn(startUserDept);
        // mock 方法（父 dept）
        DeptRespDTO parentDept =
                bean(
                        DeptRespDTO.class,
                        o -> o.setId(100L).setParentId(1000L).setLeaderUserId(200L));
        when(deptApi.getDept(eq(100L))).thenReturn(parentDept);

        // 调用
        Set<Long> result = expression.calculateUsers(execution, 2);
        // 断言
        assertEquals(asSet(200L), result);
    }

    @SuppressWarnings("SameParameterValue")
    private DelegateExecution mockDelegateExecution(Long startUserId) {
        ExecutionEntityImpl execution = new ExecutionEntityImpl();
        execution.setProcessInstanceId(uniqueId());
        // mock 返回 startUserId
        ExecutionEntityImpl processInstance = new ExecutionEntityImpl();
        processInstance.setStartUserId(String.valueOf(startUserId));
        when(processInstanceService.getProcessInstance(eq(execution.getProcessInstanceId())))
                .thenReturn(processInstance);
        return execution;
    }
}
