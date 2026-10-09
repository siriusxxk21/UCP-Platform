package com.richuang.os.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 入口授权只影响对应应用的一个资源，不复用普通应用成员的存储范围。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_task_entry_access")
public class TaskEntryAccessDO extends BaseDO {
    private Long id;
    private Long applicationId;
    private String entryId;
    private Integer lockVersion;
    private Boolean enabled;
    private String policyJson;
}
