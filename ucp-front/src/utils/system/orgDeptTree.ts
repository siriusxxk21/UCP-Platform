/**
 * 组织部门筛选树节点。
 * 统一组织和用户选择场景的节点标识，避免各页面自行拼接前缀导致筛选 ID 失真。
 */
export interface OrgDeptTreeNode {
  id: string
  key: string
  rawId: string
  name: string
  nodeType: 'org' | 'dept'
  orgId?: string
  children?: OrgDeptTreeNode[]
  isLeaf?: boolean
}

export function transformOrgDeptTree(data: unknown[]): OrgDeptTreeNode[] {
  if (!Array.isArray(data)) return []

  return data.map((item: any, index: number) => {
    const nodeType: OrgDeptTreeNode['nodeType'] = item?.nodeType === 'dept' ? 'dept' : 'org'
    const rawId = String(item?.rawId ?? item?.id ?? index)
    const key = `${nodeType}_${rawId}`
    const name = item?.name || item?.orgName || item?.deptName || item?.label || `未命名${index + 1}`
    const children = transformOrgDeptTree(item?.children || [])

    return {
      id: key,
      key,
      rawId,
      name,
      nodeType,
      orgId: item?.orgId ? String(item.orgId) : undefined,
      children: children.length > 0 ? children : undefined,
      isLeaf: children.length === 0,
    }
  })
}

