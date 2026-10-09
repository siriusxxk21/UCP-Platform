package com.richuang.os.module.bpm.framework.flowable.core.candidate.strategy.other;

import static com.richuang.os.module.bpm.support.BpmTestFixtures.bean;
import static com.richuang.os.module.bpm.support.BpmTestFixtures.uniqueId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import cn.hutool.core.collection.ListUtil;

import com.richuang.os.framework.common.util.collection.SetUtils;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.richuang.os.module.bpm.enums.definition.BpmUserTaskAssignEmptyHandlerTypeEnum;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import com.richuang.os.module.bpm.framework.flowable.core.util.FlowableUtils;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

@ExtendWith(MockitoExtension.class)
public class BpmTaskCandidateAssignEmptyStrategyTest {

    @InjectMocks private BpmTaskCandidateAssignEmptyStrategy strategy;

    @Mock private BpmProcessDefinitionService processDefinitionService;

    @Test
    public void testCalculateUsersByTask() {
        try (MockedStatic<FlowableUtils> flowableUtilMockedStatic =
                        mockStatic(FlowableUtils.class);
                MockedStatic<BpmnModelUtils> bpmnModelUtilsMockedStatic =
                        mockStatic(BpmnModelUtils.class)) {
            // 准备参数
            DelegateExecution execution = mock(DelegateExecution.class);
            String param = uniqueId();
            // mock 方法（execution）
            String processDefinitionId = uniqueId();
            when(execution.getProcessDefinitionId()).thenReturn(processDefinitionId);
            FlowElement flowElement = mock(FlowElement.class);
            when(execution.getCurrentFlowElement()).thenReturn(flowElement);
            // mock 方法（parseAssignEmptyHandlerType）
            bpmnModelUtilsMockedStatic
                    .when(() -> BpmnModelUtils.parseAssignEmptyHandlerType(same(flowElement)))
                    .thenReturn(BpmUserTaskAssignEmptyHandlerTypeEnum.ASSIGN_USER.getType());
            bpmnModelUtilsMockedStatic
                    .when(() -> BpmnModelUtils.parseAssignEmptyHandlerUserIds(same(flowElement)))
                    .thenReturn(ListUtil.of(1L, 2L));

            // 调用
            Set<Long> userIds = strategy.calculateUsersByTask(execution, param);
            // 断言
            assertEquals(SetUtils.asSet(1L, 2L), userIds);
        }
    }

    @Test
    public void testCalculateUsersByActivity() {
        try (MockedStatic<BpmnModelUtils> bpmnModelUtilsMockedStatic =
                mockStatic(BpmnModelUtils.class)) {
            // 准备参数
            String processDefinitionId = uniqueId();
            String activityId = uniqueId();
            String param = uniqueId();
            // mock 方法（getFlowElementById）
            FlowElement flowElement = mock(FlowElement.class);
            BpmnModel bpmnModel = mock(BpmnModel.class);
            bpmnModelUtilsMockedStatic
                    .when(() -> BpmnModelUtils.getFlowElementById(same(bpmnModel), eq(activityId)))
                    .thenReturn(flowElement);
            // mock 方法（parseAssignEmptyHandlerType）
            bpmnModelUtilsMockedStatic
                    .when(() -> BpmnModelUtils.parseAssignEmptyHandlerType(same(flowElement)))
                    .thenReturn(BpmUserTaskAssignEmptyHandlerTypeEnum.ASSIGN_ADMIN.getType());
            // mock 方法（getProcessDefinitionInfo）
            BpmProcessDefinitionInfoDO processDefinition =
                    bean(
                            BpmProcessDefinitionInfoDO.class,
                            o -> o.setManagerUserIds(ListUtil.of(1L, 2L)));
            when(processDefinitionService.getProcessDefinitionInfo(eq(processDefinitionId)))
                    .thenReturn(processDefinition);

            // 调用
            Set<Long> userIds =
                    strategy.calculateUsersByActivity(
                            bpmnModel, activityId, param, null, processDefinitionId, null);
            // 断言
            assertEquals(SetUtils.asSet(1L, 2L), userIds);
        }
    }
}
