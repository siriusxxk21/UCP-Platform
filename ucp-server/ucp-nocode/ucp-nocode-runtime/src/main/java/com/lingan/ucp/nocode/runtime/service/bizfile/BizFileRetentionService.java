package com.lingan.ucp.nocode.runtime.service.bizfile;

import com.lingan.ucp.nocode.runtime.dal.mapper.BizFileRetentionMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务文件保留引用 Service
 *
 * <p>记录历史、工作草稿等持有者对受保护文件的登记；登记与业务写入同事务，不能有先写业务后补登记的窗口。 物理清理前必须经本服务复核，有有效引用的内容不进入清理。
 */
@Service
public class BizFileRetentionService {

    @Resource private BizFileRetentionMapper retentions;

    /**
     * 登记一批文件的保留引用
     *
     * <p>同一（文件、持有者）重复登记幂等；历史持有者以一次变更的操作编号为单位，天然不重复。
     */
    public void register(
            String holderType,
            String holderId,
            String objectId,
            String recordId,
            Collection<Long> fileIds,
            long actor) {
        List<Long> files = positiveFileIds(fileIds);
        if (files.isEmpty()) {
            return;
        }
        retentions.register(
                files,
                holderType,
                holderId,
                objectId,
                recordId == null ? "" : recordId,
                Long.toString(actor));
    }

    /**
     * 持有者内容整体替换（工作草稿重存）
     *
     * <p>草稿内容可增可删，旧登记先物理释放再登记当前集合，避免已移除文件被误留。
     */
    public void replace(
            String holderType,
            String holderId,
            String objectId,
            String recordId,
            Collection<Long> fileIds,
            long actor) {
        retentions.deleteByHolder(holderType, holderId);
        register(holderType, holderId, objectId, recordId, fileIds, actor);
    }

    /** 释放持有者的全部保留引用（草稿作废等场景） */
    public void release(String holderType, String holderId) {
        retentions.deleteByHolder(holderType, holderId);
    }

    /** 入参文件中仍被有效持有者引用的子集；物理清理只删除不在其中的文件 */
    public Set<Long> retainedFileIds(Collection<Long> fileIds) {
        List<Long> files = positiveFileIds(fileIds);
        if (files.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(retentions.selectEffectiveFileIds(files));
    }

    /** 保留引用只登记合法文件编号，忽略空值与非法值 */
    private List<Long> positiveFileIds(Collection<Long> fileIds) {
        Set<Long> files = new LinkedHashSet<>();
        if (fileIds != null) {
            for (Long fileId : fileIds) {
                if (fileId != null && fileId > 0) {
                    files.add(fileId);
                }
            }
        }
        return new ArrayList<>(files);
    }
}
