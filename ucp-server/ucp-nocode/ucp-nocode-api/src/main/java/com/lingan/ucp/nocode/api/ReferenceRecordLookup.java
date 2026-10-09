package com.lingan.ucp.nocode.api;

import java.util.Collection;
import java.util.Set;

/**
 * 对象设计校验用：引用字段的条件固定值必须是目标对象里的记录（业务方 2026-10-04：固定值存了名称「民宿管理」去比记录 ID，候选恒为空）。 元数据模块不直接读业务表，由运行层实现。
 */
public interface ReferenceRecordLookup {
    /**
     * 按目标对象当前发布版判断哪些记录 ID 存在（系统读，不按操作者裁剪，已删除的不算）；不返回记录内容。
     *
     * @return 存在的 ID；目标对象未发布或读不了时返回 null（无从核对，调用方不据此拦截）
     */
    Set<String> existing(String objectId, Collection<String> ids);
}
