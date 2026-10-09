package com.lingan.ucp.module.bpm.framework.flowable.core.candidate.strategy.user;

import cn.hutool.core.text.StrPool;
import com.lingan.ucp.framework.common.util.string.StrUtils;
import com.lingan.ucp.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateStrategy;
import com.lingan.ucp.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;

/**
 * 用户 {@link BpmTaskCandidateStrategy} 实现类
 *
 * @author kyle
 */
@Component
public class BpmTaskCandidateUserStrategy implements BpmTaskCandidateStrategy {

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    public BpmTaskCandidateStrategyEnum getStrategy() {
        return BpmTaskCandidateStrategyEnum.USER;
    }

    @Override
    public void validateParam(String param) {
        adminUserApi.validateUserList(StrUtils.splitToLongSet(param));
    }

    @Override
    public LinkedHashSet<Long> calculateUsers(String param) {
        return new LinkedHashSet<>(StrUtils.splitToLong(param, StrPool.COMMA));
    }

}