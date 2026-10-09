package com.richuang.os.nocode.runtime.service.maintenance;

import com.richuang.os.nocode.api.LinkageSync;

/** 数据联动自动更新的总览、预告与回填；只有应用设计者可以调用。 */
public interface LinkageSyncService {
    /** 应用里开启了自动更新的字段、相对比较基准的变化，以及其它应用口径不一致的情况。 */
    LinkageSync.Overview overview(String applicationId, String basis, long actor);

    /** 一页预告：逐条按同一个求值入口算出应有值，与库中现值比较；只读，不取执行锁。 */
    LinkageSync.Preview preview(LinkageSync.PreviewRequest request, long actor);

    /** 一页回填：每条记录一个独立事务；幂等（按当前数据算出应有值，不是增量）。 */
    LinkageSync.Backfill backfill(LinkageSync.BackfillRequest request, long actor);
}
