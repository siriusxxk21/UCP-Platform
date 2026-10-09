package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不可变的业务记录历史实体；读取接口使用独立投影，不提供历史编辑接口。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_record_history")
public class RecordHistoryDO extends BaseDO {
    private Long id;
    private Long objectId;
    private String recordId;
    private Long applicationId;
    private String operation;
    private java.time.OffsetDateTime occurredAt;
    private String beforeJson;
    private String afterJson;
    private String definitionJson;
    private String sourceJson;
}
