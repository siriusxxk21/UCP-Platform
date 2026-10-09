package com.lingan.ucp.module.drive.dal.dataobject.entry;

import lombok.Data;

/**
 * 网盘节点来源标记查询结果
 *
 * <p>节点经由哪个业务来源放入；来源键对网盘是不透明字符串。没有标记或标记已作废（deleted = 1）的节点不会出现在查询结果里。
 *
 * @author os
 */
@Data
public class DriveEntryOriginRow {

    /** 节点编号 */
    private Long entryId;

    /** 来源键 */
    private String originKey;
}
