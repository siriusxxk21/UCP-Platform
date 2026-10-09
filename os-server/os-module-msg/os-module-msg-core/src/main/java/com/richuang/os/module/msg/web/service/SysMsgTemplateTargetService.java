package com.richuang.os.module.msg.web.service;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.tenant.core.context.TenantIdResolver;
import com.richuang.os.module.msg.api.MsgReceiver;
import com.richuang.os.module.msg.api.MsgTarget;
import com.richuang.os.module.msg.api.MsgTargetService;
import com.richuang.os.module.msg.api.MsgTargetType;
import com.richuang.os.module.msg.web.entity.SysMsgTemplate;
import com.richuang.os.module.msg.web.entity.SysMsgTemplateTarget;
import com.richuang.os.module.msg.web.mapper.SysMsgTemplateTargetMapper;
import com.richuang.os.module.system.dal.dataobject.permission.RoleDO;
import com.richuang.os.module.system.dal.dataobject.user.AdminUserDO;
import com.richuang.os.module.system.service.permission.RoleService;
import com.richuang.os.module.system.service.user.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理当前租户的消息模板默认接收对象，并在发送前展开为有效用户快照。
 */
@Service
@RequiredArgsConstructor
public class SysMsgTemplateTargetService extends ServiceImpl<SysMsgTemplateTargetMapper, SysMsgTemplateTarget> {

    private static final List<String> SUPPORTED_TYPES = List.of(MsgTargetType.USER.getCode(), MsgTargetType.ROLE.getCode());

    private final SysMsgTemplateService templateService;
    private final AdminUserService adminUserService;
    private final RoleService roleService;
    private final List<MsgTargetService> targetServices;
    private final TenantIdResolver tenantIdResolver;

    public List<SysMsgTemplateTarget> listByTemplateId(Long templateId) {
        Long tenantId = tenantIdResolver.getRequiredTenantId();
        return list(new LambdaQueryWrapper<SysMsgTemplateTarget>()
                .eq(SysMsgTemplateTarget::getTenantId, tenantId)
                .eq(SysMsgTemplateTarget::getMsgTemplateId, templateId)
                .orderByAsc(SysMsgTemplateTarget::getTargetType, SysMsgTemplateTarget::getTargetName));
    }

    public List<SysMsgTemplateTarget> listByTemplateCode(String msgCode) {
        SysMsgTemplate template = templateService.getByCode(msgCode);
        return template == null ? List.of() : listByTemplateId(template.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void replace(Long templateId, List<SysMsgTemplateTarget> targets) {
        Assert.notNull(templateService.getById(templateId), "消息模板不存在");
        Long tenantId = tenantIdResolver.getRequiredTenantId();
        List<SysMsgTemplateTarget> validated = validateAndNormalize(templateId, tenantId,
                targets == null ? List.of() : targets);
        baseMapper.deleteByTemplate(tenantId, templateId);
        if (!validated.isEmpty()) {
            saveBatch(validated);
        }
    }

    public List<MsgTarget> resolveUserTargets(String msgCode) {
        Long tenantId = tenantIdResolver.getRequiredTenantId();
        List<SysMsgTemplateTarget> configured = listByTemplateCode(msgCode);
        Map<String, MsgTarget> users = new LinkedHashMap<>();
        for (SysMsgTemplateTarget configuredTarget : configured) {
            MsgTarget target = new MsgTarget()
                    .setTargetType(new MsgTargetType(configuredTarget.getTargetType(), configuredTarget.getTargetType()))
                    .setTargetId(configuredTarget.getTargetId())
                    .setTargetName(configuredTarget.getTargetName());
            targetServices.stream()
                    .filter(service -> service.support().getCode().equals(configuredTarget.getTargetType()))
                    .findFirst()
                    .map(service -> service.getMsgReceivers(target))
                    .orElseGet(List::of)
                    .forEach(receiver -> addActiveUser(users, receiver, tenantId));
        }
        return new ArrayList<>(users.values());
    }

    private List<SysMsgTemplateTarget> validateAndNormalize(Long templateId, Long tenantId,
                                                            List<SysMsgTemplateTarget> targets) {
        Map<String, SysMsgTemplateTarget> unique = new LinkedHashMap<>();
        for (SysMsgTemplateTarget target : targets) {
            Assert.notBlank(target.getTargetType(), "接收对象类型不能为空");
            Assert.notBlank(target.getTargetId(), "接收对象不能为空");
            Assert.isTrue(SUPPORTED_TYPES.contains(target.getTargetType()), "反馈模板仅支持用户和角色");
            SysMsgTemplateTarget normalized = normalizeTarget(templateId, tenantId, target);
            unique.put(normalized.getTargetType() + ':' + normalized.getTargetId(), normalized);
        }
        return new ArrayList<>(unique.values());
    }

    private SysMsgTemplateTarget normalizeTarget(Long templateId, Long tenantId, SysMsgTemplateTarget target) {
        String targetName;
        if (MsgTargetType.USER.getCode().equals(target.getTargetType())) {
            AdminUserDO user = adminUserService.getUser(Long.valueOf(target.getTargetId()));
            Assert.notNull(user, "指定用户不存在");
            Assert.isTrue(!tenantIdResolver.isTenantEnabled() || Objects.equals(tenantId, user.getTenantId()),
                    "指定用户不属于当前租户");
            Assert.isTrue(CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus()), "指定用户已停用");
            targetName = user.getNickname();
        } else {
            RoleDO role = roleService.getRole(Long.valueOf(target.getTargetId()));
            Assert.notNull(role, "指定角色不存在");
            Assert.isTrue(!tenantIdResolver.isTenantEnabled() || Objects.equals(tenantId, role.getTenantId()),
                    "指定角色不属于当前租户");
            Assert.isTrue(CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()), "指定角色已停用");
            targetName = role.getName();
        }
        SysMsgTemplateTarget normalized = new SysMsgTemplateTarget();
        normalized.setTenantId(tenantId);
        normalized.setMsgTemplateId(templateId);
        normalized.setTargetType(target.getTargetType());
        normalized.setTargetId(target.getTargetId());
        normalized.setTargetName(targetName);
        return normalized;
    }

    private void addActiveUser(Map<String, MsgTarget> users, MsgReceiver receiver, Long tenantId) {
        try {
            AdminUserDO user = adminUserService.getUser(Long.valueOf(receiver.getReceiverId()));
            if (user == null
                    || (tenantIdResolver.isTenantEnabled() && !Objects.equals(tenantId, user.getTenantId()))
                    || !CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
                return;
            }
            users.putIfAbsent(receiver.getReceiverId(), new MsgTarget()
                    .setTargetType(MsgTargetType.USER)
                    .setTargetId(receiver.getReceiverId())
                    .setTargetName(user.getNickname()));
        } catch (NumberFormatException ignored) {
            // 历史脏数据或第三方目标服务可能返回非数字 ID，反馈通知只接受系统用户。
        }
    }

}
