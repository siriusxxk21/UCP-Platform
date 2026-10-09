package com.richuang.os.nocode.metadata.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 数据对象元数据；审计字段、自动填充与逻辑删除统一继承底座 BaseDO。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_object", schema = "public")
public class NocodeObjectDO extends BaseDO {

    /** 数据库生成的对象主键，与跨版本字段稳定 ID 分开。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台内唯一的对象编码。 */
    private String objectCode;

    private String objectName;
    private String description;

    /** 管理分类独立于不可变发布快照。 */
    private String category;

    private String sourceType;
    private String schemaName;
    private String settingsJson;
    private Boolean readOnly;
    private String titleTemplate;
    private String adoptionHash;
    private String reconciliationHash;
    private Long reconciliationVersionId;

    /** 物理名称种子；草稿阶段不会据此执行业务 DDL。 */
    private String physicalNameSeed;

    /** 记录标题指向当前对象的单行文本字段稳定 ID。 */
    private Long titleFieldStableId;

    private String status;
    private Integer latestVersionNo;
    private Integer currentPublishedVersionNo;

    /** 控制对象头、字段和 Schema 的整体并发版本。 */
    private Integer lockVersion;
}
