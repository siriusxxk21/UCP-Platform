package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 记录文件夹对应关系 DO
 *
 * <p>记录在某来源下的子文件夹；按网盘节点编号绑定，文件夹在网盘里改名或移动不影响。只有 RECORD_SUBFOLDER 的来源产生行；删除记录、删除来源都不删行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_record_folder_binding", schema = "public")
public class RecordFolderBindingDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String objectId;
    private String recordId;
    private Long sourceId;

    /** 子文件夹建在谁下面：FOLDER 来源恒为 0；RELATION 来源为关联记录解析出的文件夹编号 */
    private Long anchorEntryId;

    private Long spaceId;
    private Long entryId;

    /** AUTO 系统建立；MANUAL 人工指定（二期） */
    private String origin;

    /** 建立时系统取的名字，供二期判断文件夹是否被人工改过名 */
    private String autoName;
}
