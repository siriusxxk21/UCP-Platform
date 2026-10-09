package com.richuang.os.nocode.metadata.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 规则摘要跨 LIVE 切回保留，避免再次启用时覆盖原有存储口径。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_ordered_calculation_state", schema = "public")
public class OrderedCalculationStateDO extends BaseDO {
    private Long id;
    private Long objectId;
    private Long fieldId;
    private String signature;
    private String state;
    private String cursorJson;
    private Long totalRows;
    private Long updatedRows;
    private Long completedGroups;
    private String errorMessage;
    private Long lockVersion;
}
