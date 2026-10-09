package com.richuang.os.module.bpm.framework.flowable.core.candidate.strategy.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.richuang.os.module.bpm.service.task.BpmProcessInstanceService;

import org.assertj.core.util.Sets;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

@ExtendWith(MockitoExtension.class)
public class BpmTaskCandidateStartUserStrategyTest {

    @InjectMocks private BpmTaskCandidateStartUserStrategy strategy;

    @Mock private BpmProcessInstanceService processInstanceService;

    @Test
    public void testCalculateUsersByTask() {
        // 准备参数
        String param = "2";
        // mock 方法（获得流程发起人）
        Long startUserId = 1L;
        ProcessInstance processInstance = mock(ProcessInstance.class);
        DelegateExecution execution = mock(DelegateExecution.class);
        when(processInstanceService.getProcessInstance(eq(execution.getProcessInstanceId())))
                .thenReturn(processInstance);
        when(processInstance.getStartUserId()).thenReturn(startUserId.toString());

        // 调用
        Set<Long> userIds = strategy.calculateUsersByTask(execution, param);
        // 断言
        assertEquals(Sets.newLinkedHashSet(startUserId), userIds);
    }

    @Test
    public void testCalculateUsersByActivity() {
        // 准备参数
        Long startUserId = 1L;

        // 调用
        Set<Long> userIds =
                strategy.calculateUsersByActivity(null, null, null, startUserId, null, null);
        // 断言
        assertEquals(Sets.newLinkedHashSet(startUserId), userIds);
    }
}
