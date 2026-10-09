package com.richuang.os.module.bpm.framework.flowable.core.candidate.strategy.dept;

import static com.richuang.os.module.bpm.support.BpmTestFixtures.bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static java.util.Arrays.asList;

import com.richuang.os.framework.common.util.collection.SetUtils;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.dto.DeptRespDTO;

import org.assertj.core.util.Sets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

@ExtendWith(MockitoExtension.class)
public class BpmTaskCandidateDeptLeaderStrategyTest {

    @InjectMocks private BpmTaskCandidateDeptLeaderStrategy strategy;

    @Mock private DeptApi deptApi;

    @Test
    public void testCalculateUsers() {
        // 准备参数
        String param = "10,20";
        // mock 方法
        when(deptApi.getDeptList(eq(SetUtils.asSet(10L, 20L))))
                .thenReturn(
                        asList(
                                bean(
                                        DeptRespDTO.class,
                                        o -> o.setId(10L).setParentId(10L).setLeaderUserId(11L)),
                                bean(
                                        DeptRespDTO.class,
                                        o -> o.setId(20L).setParentId(20L).setLeaderUserId(21L))));

        // 调用
        Set<Long> userIds = strategy.calculateUsers(param);
        // 断言结果
        assertEquals(Sets.newLinkedHashSet(11L, 21L), userIds);
    }
}
