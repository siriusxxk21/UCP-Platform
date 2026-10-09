package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务目录绑定 DO
 *
 * <p>记录/明细区/明细行/字段身份到网盘受管目录节点的稳定映射；身份列空串表示该层级不适用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_biz_directory_binding", schema = "public")
public class BizDirectoryBindingDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String objectId;
    private String recordId;

    /** 所属内部明细稳定 ID，空串表示主表 */
    private String detailId;

    /** 内部明细行持久 ID，空串表示非明细行级目录 */
    private String rowId;

    /** 附件字段稳定 ID，空串表示记录/明细区/明细行目录本身 */
    private String fieldId;

    private Long spaceId;
    private Long entryId;

    /** 建立绑定时生效的对象发布版本号；已有记录沿用该版本，新规则只作用于新记录 */
    private Integer ruleVersion;

    /** 业务分组稳定键串，业务值变更时据此判断是否调整目录 */
    private String groupKeys;
}
