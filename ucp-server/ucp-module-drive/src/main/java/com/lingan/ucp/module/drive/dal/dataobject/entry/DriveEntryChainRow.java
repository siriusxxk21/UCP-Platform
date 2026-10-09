package com.lingan.ucp.module.drive.dal.dataobject.entry;

import lombok.Data;

/**
 * 网盘节点继承链查询结果
 *
 * <p>表示从某节点向上到继承边界为止的节点链，供授权判定与面包屑共用。
 *
 * @author os
 */
@Data
public class DriveEntryChainRow {

    /** 节点编号 */
    private Long id;

    /** 父节点编号 */
    private Long parentId;

    /** 是否继承上级授权 */
    private Boolean inheritParent;

    /** 距离起始节点的层级，起始节点为 0 */
    private Integer depth;
}
