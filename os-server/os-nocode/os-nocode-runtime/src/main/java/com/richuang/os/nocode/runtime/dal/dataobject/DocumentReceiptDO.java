package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.*;

/** 与主从事务共同提交的不可变收据；没有普通修改或删除入口。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_document_receipt", schema = "public")
public class DocumentReceiptDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;
    private Long objectId;
    private String operation;
    private String requestKey;
    private String requestDigest;
    private String operationId;
    private String recordId;
    private String recordRevision;
    private String policyVersion;
    private String resultJson;
}
