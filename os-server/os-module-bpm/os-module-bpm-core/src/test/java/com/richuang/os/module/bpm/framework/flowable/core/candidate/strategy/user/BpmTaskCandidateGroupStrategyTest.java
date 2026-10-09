package com.richuang.os.module.bpm.framework.flowable.core.candidate.strategy.user;

import static com.richuang.os.framework.common.util.collection.SetUtils.asSet;
import static com.richuang.os.module.bpm.support.BpmTestFixtures.bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.richuang.os.module.bpm.dal.dataobject.definition.BpmUserGroupDO;
import com.richuang.os.module.bpm.service.definition.BpmUserGroupService;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Set;

@Disabled // TODO 芋艿：临时注释
@ExtendWith(MockitoExtension.class)
public class BpmTaskCandidateGroupStrategyTest {

    @InjectMocks private BpmTaskCandidateGroupStrategy strategy;

    @Mock private BpmUserGroupService userGroupService;

    @Test
    public void testCalculateUsers() {
        // 准备参数
        String param = "1,2";
        // mock 方法
        BpmUserGroupDO userGroup1 = bean(BpmUserGroupDO.class, o -> o.setUserIds(asSet(11L, 12L)));
        BpmUserGroupDO userGroup2 = bean(BpmUserGroupDO.class, o -> o.setUserIds(asSet(21L, 22L)));
        when(userGroupService.getUserGroupList(eq(asSet(1L, 2L))))
                .thenReturn(Arrays.asList(userGroup1, userGroup2));

        // 调用
        Set<Long> userIds = strategy.calculateUsersByTask(null, param);
        // 断言
        assertEquals(asSet(11L, 12L, 21L, 22L), userIds);
    }
}
