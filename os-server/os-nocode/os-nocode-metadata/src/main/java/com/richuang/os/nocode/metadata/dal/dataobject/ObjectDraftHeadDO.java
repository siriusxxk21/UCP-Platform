package com.richuang.os.nocode.metadata.dal.dataobject;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 对象、当前版本及主表的联合查询投影；仅用于读取，不直接写表。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ObjectDraftHeadDO extends NocodeObjectDO {

    private Long versionId;
    private String versionState;
    private Long tableId;
    private String tableName;
    private Integer fieldCount;
    private Integer detailCount;
    private Integer relationCount;
}
