package com.lingan.ucp.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表目录仅存分类关系，沿用底座审计和逻辑删除。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_folder", schema = "public")
public class ReportFolderDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String resourceKind;
    private Long parentId;
    private String name;
    private Integer sortNo;
    private Integer lockVersion;
}
