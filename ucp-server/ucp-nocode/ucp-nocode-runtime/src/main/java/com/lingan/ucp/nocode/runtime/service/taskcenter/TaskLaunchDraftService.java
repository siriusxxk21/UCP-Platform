package com.lingan.ucp.nocode.runtime.service.taskcenter;

import com.lingan.ucp.nocode.api.TaskCenter.Detail;
import com.lingan.ucp.nocode.api.TaskCenter.Draft;
import com.lingan.ucp.nocode.api.TaskCenter.DraftPublish;
import com.lingan.ucp.nocode.api.TaskCenter.DraftRef;
import com.lingan.ucp.nocode.api.TaskCenter.DraftSave;
import com.lingan.ucp.nocode.api.TaskCenter.DraftSummary;

import java.util.List;

/** 任务发起前的个人编排草稿；操作者取可信底座，业务资格留到发布时验证。 */
public interface TaskLaunchDraftService {
    /** 读取本人尚未发布的草稿，按最近保存时间倒序。 */
    List<DraftSummary> list(long actor);

    /** 读取本人完整编排及发布回执，不允许管理员越过所有者边界。 */
    Draft get(String id, long actor);

    /** 保存可不完整的任务配置与业务输入，只约束安全结构和大小。 */
    Draft save(DraftSave command, long actor);

    /** 按当前修订软删除未发布草稿；不触碰业务草稿或业务记录。 */
    void delete(DraftRef command, long actor);

    /** 锁定草稿修订，调用统一任务发起入口；任务、业务写入与发布回执原子提交。 */
    Detail publish(DraftPublish command, long actor);
}
