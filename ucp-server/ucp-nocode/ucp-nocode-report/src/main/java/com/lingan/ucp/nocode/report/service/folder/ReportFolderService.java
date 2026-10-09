package com.lingan.ucp.nocode.report.service.folder;

import com.lingan.ucp.nocode.api.ReportFolders;
import com.lingan.ucp.nocode.enums.ReportResourceKindEnum;

import java.util.List;

/** 数据集与仪表板使用同一分类表；资源类别隔离，分类不授予内容权限。 */
public interface ReportFolderService {
    List<ReportFolders.Item> tree(long actor);

    List<ReportFolders.Item> tree(String resourceKind, long actor);

    ReportFolders.Item save(ReportFolders.Save request, long actor);

    ReportFolders.Deleted delete(ReportFolders.Delete request, long actor);

    /** 调用者已持全局设计锁；非管理者只能放入可发现的目录。 */
    Long destination(String id, long actor);

    Long destination(String id, long actor, ReportResourceKindEnum resourceKind);
}
