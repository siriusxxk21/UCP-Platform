package com.lingan.ucp.nocode.runtime.service.live;

import java.util.List;

/** 一次已提交事务里，各对象的记录变更。不可变。 */
public record RecordChangeBatch(List<ObjectChange> objects) {
    public RecordChangeBatch {
        objects = List.copyOf(objects);
    }

    /** many=true 时三个列表为空；否则三个列表互斥、无重复。 */
    public record ObjectChange(
            String objectId,
            boolean many,
            List<String> created,
            List<String> updated,
            List<String> deleted) {
        public ObjectChange {
            created = List.copyOf(created);
            updated = List.copyOf(updated);
            deleted = List.copyOf(deleted);
        }
    }
}
