package com.lingan.ucp.module.system.legacy.vo;

import lombok.Data;

import java.util.List;

/**
 * 组织与部门混合树节点VO
 */
@Data
public class OrgDeptTreeVO {

    /**
     * 节点唯一ID，格式为 org_${id} 或 dept_${id}
     */
    private String id;

    /**
     * 原始ID (SysOrganization的id或SysDepartment的id)
     */
    private String rawId;

    /**
     * 显示的名称
     */
    private String name;

    /**
     * 节点类型: "org" (组织) 或 "dept" (部门)
     */
    private String nodeType;

    /**
     * 所属组织ID (部门节点专属)
     */
    private String orgId;

    /**
     * 子节点列表
     */
    private List<OrgDeptTreeVO> children;
}
