package com.richuang.os.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 带租户隔离的基础实体
 * 继承此类的实体表必须含有 tenant_id 列，会被多租户拦截器自动注入隔离条件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantBaseEntity extends BaseEntity {

    /**
     * 租户ID（自动填充，不需要业务层手动设置）
     */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;
}
