package com.richuang.os.module.bpm.framework.flowable.core.candidate.strategy.user;

import static com.richuang.os.framework.common.util.collection.SetUtils.asSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

@Disabled // TODO 芋艿：临时注释
@ExtendWith(MockitoExtension.class)
public class BpmTaskCandidateUserStrategyTest {

    @InjectMocks private BpmTaskCandidateUserStrategy strategy;

    @Test
    public void test() {
        // 准备参数
        String param = "1,2";

        // 调用
        Set<Long> userIds = strategy.calculateUsersByTask(null, param);
        // 断言
        assertEquals(asSet(1L, 2L), userIds);
    }
}
