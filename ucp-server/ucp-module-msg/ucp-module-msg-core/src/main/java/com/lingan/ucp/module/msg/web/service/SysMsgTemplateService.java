package com.lingan.ucp.module.msg.web.service;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingan.ucp.common.exception.BusinessException;
import com.lingan.ucp.module.msg.web.entity.SysMsgTemplate;
import com.lingan.ucp.module.msg.web.mapper.SysMsgTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SysMsgTemplateService extends ServiceImpl<SysMsgTemplateMapper, SysMsgTemplate> {

    public SysMsgTemplate getByCode(String code) {
        return this.getOne(new LambdaQueryWrapper<SysMsgTemplate>().eq(SysMsgTemplate::getCode, code));
    }

    @Transactional
    public boolean create(SysMsgTemplate entity) {
        Assert.notNull(entity);
        Assert.notBlank(entity.getCode());
        Assert.notBlank(entity.getName());
        // 验证编码唯一
        long count = this.count(new LambdaQueryWrapper<SysMsgTemplate>().eq(SysMsgTemplate::getCode, entity.getCode()));
        if (count > 0) {
            throw new BusinessException("消息类型编码 [" + entity.getCode() + "] 已存在");
        }
        this.save(entity);
        return true;
    }

    @Transactional
    public boolean modify(SysMsgTemplate entity) {
        Assert.notNull(entity);
        Assert.notNull(entity.getId());
        SysMsgTemplate byId = this.getById(entity.getId());
        Assert.notNull(byId, "数据不存在");
        entity.setCode(byId.getCode());
        this.updateById(entity);
        return true;
    }
}
