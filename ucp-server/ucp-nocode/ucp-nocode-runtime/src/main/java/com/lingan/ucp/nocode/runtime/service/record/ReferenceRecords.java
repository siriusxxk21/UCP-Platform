package com.lingan.ucp.nocode.runtime.service.record;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.ReferenceRecordLookup;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** 对象设计校验引用字段固定值时，按目标对象当前发布版逐个读存储行判断记录是否存在（系统读，只回答「在不在」）。 */
@Service
public class ReferenceRecords implements ReferenceRecordLookup {
    /** 一次核对的上限：条件最多 20 条、每条至多 100 个值。 */
    private static final int MAX_IDS = 2000;

    @Resource private DataObjectApi objects;
    @Resource private RecordQueryAccess records;

    @Override
    public Set<String> existing(String objectId, Collection<String> ids) {
        if (objectId == null || ids == null || ids.size() > MAX_IDS) return null;
        try {
            objects.getPublished(objectId);
        } catch (ServiceException unavailable) {
            return null;
        }
        Set<String> found = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || id.length() > 500) continue;
            try {
                if (records.storedRow(objectId, id, 0L) != null) found.add(id);
            } catch (ServiceException unreadable) {
                // 主键格式与列类型不符等：这个值不是该对象的记录，按不存在处理。
            }
        }
        return found;
    }
}
