package com.richuang.os.module.system.legacy.dto;

import com.richuang.os.common.dto.DynamicConditionDTO;
import lombok.Data;

@Data
public class UserQueryDTO {

    private String username;

    private String nickname;

    private String phone;

    private Integer status;

    private String orgId;

    private String deptId;

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /**
     * 高级检索动态条件（递归树型结构，支持嵌套分组）
     * 非空时优先使用此条件构建查询，简单字段作为补充
     */
    private DynamicConditionDTO conditions;
}
