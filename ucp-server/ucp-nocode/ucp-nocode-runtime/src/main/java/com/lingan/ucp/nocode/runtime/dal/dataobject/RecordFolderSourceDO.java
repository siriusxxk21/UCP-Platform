package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 记录文件夹来源 DO
 *
 * <p>一行 = 某对象表单下方的一个文件夹页签；不随对象版本冻结，保存即生效。FOLDER 来源指定网盘里的一个普通目录；RELATION
 * 来源沿本对象主表上的单值关联字段，用对方对象的某个来源解析出来的文件夹。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_record_folder_source", schema = "public")
public class RecordFolderSourceDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String objectId;

    /** 页签顺序，等于保存时在清单里的下标 */
    private Integer sortNo;

    /** 页签名称；空串表示用文件夹或关联字段的名字 */
    private String label;

    /** FOLDER / RELATION */
    private String kind;

    /** DIRECT / RECORD_SUBFOLDER */
    private String placement;

    /** FOLDER：指定目录所在空间 */
    private Long spaceId;

    /** FOLDER：指定的网盘目录节点编号（普通节点） */
    private Long entryId;

    /** RELATION：本对象主表上的单值关联字段稳定 ID；FOLDER 为空串 */
    private String relationFieldId;

    /** RELATION：对方对象的文件夹来源编号 */
    private Long targetSourceId;

    /** 子文件夹的建立时机：ON_FIRST_WRITE / ON_SAVE；仅 RECORD_SUBFOLDER 有意义 */
    private String createMode;

    /** 子文件夹命名模板的 JSON 文本；空串表示用记录名称；仅 RECORD_SUBFOLDER 有意义 */
    private String nameTemplate;
}
