package com.lingan.ucp.module.bpm.framework.flowable.core.candidate.strategy.dept;

import static com.lingan.ucp.module.bpm.support.BpmTestFixtures.bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lingan.ucp.module.system.api.dept.DeptApi;
import com.lingan.ucp.module.system.api.dept.dto.DeptRespDTO;

import org.assertj.core.util.Sets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.Set;

@ExtendWith(MockitoExtension.class)
public class BpmTaskCandidateDeptLeaderMultiStrategyTest {

    @InjectMocks private BpmTaskCandidateDeptLeaderMultiStrategy strategy;

    @Mock private DeptApi deptApi;

    @Test
    public void testCalculateUsers() {
        // 准备参数
        String param = "10,20|2";
        // mock 方法
        when(deptApi.getDept(any()))
                .thenAnswer(
                        (Answer<DeptRespDTO>)
                                invocationOnMock -> {
                                    Long deptId = invocationOnMock.getArgument(0);
                                    return bean(
                                            DeptRespDTO.class,
                                            o ->
                                                    o.setId(deptId)
                                                            .setParentId(deptId * 100)
                                                            .setLeaderUserId(deptId + 1));
                                });

        // 调用
        Set<Long> userIds = strategy.calculateUsers(param);
        // 断言结果
        assertEquals(Sets.newLinkedHashSet(11L, 1001L, 21L, 2001L), userIds);
    }
}
