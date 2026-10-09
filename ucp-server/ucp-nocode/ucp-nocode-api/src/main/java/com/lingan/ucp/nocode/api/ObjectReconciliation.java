package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.api.DataCenter.*;

import java.util.List;

/** 物理结构差异核对与接受差异的公开契约；不包含 DDL 执行能力。 */
public final class ObjectReconciliation {
    private ObjectReconciliation() {}

    /** 预览的结构指纹用于确认时检测并发变化。 */
    public record Preview(
            String id,
            int revision,
            String fingerprint,
            boolean allowed,
            List<Step> changes,
            List<Check> checks) {}

    /** 仅接受与预览指纹、草稿修订同时匹配的结构变化。 */
    public record Apply(
            String id, Integer expectedLockVersion, String fingerprint, String reason) {}
}
