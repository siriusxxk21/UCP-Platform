package com.lingan.ucp.nocode.runtime.service.bizfile;

/**
 * 过期临时上传清理的单次执行统计
 *
 * @param expiredMarked 标记为待清理的过期会话数
 * @param filesCleaned 完成内容删除的文件数
 * @param sessionsCleaned 登记为已清理的会话数
 * @param skipped 因有效保留引用或受管节点暂缓清理的文件数
 * @param failed 内容删除失败并已登记补偿的文件数
 */
public record BizUploadCleanupResult(
        int expiredMarked, int filesCleaned, int sessionsCleaned, int skipped, int failed) {}
