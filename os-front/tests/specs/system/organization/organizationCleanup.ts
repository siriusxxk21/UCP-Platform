import { getTestConfig } from '../../../utils/config'
import { loadAuthLocalStorage } from '../../../utils/auth'

/**
 * 组织管理 - 基于后台 API 的测试数据清理工具
 *
 * 背景：后端禁止删除「存在子组织的父组织」（返回「存在子组织，无法删除」），
 * 因此通过 UI 循环删除时若遇到有子节点的父组织会失败。
 * 本工具直接调用后台 API，先按「后序遍历」删除所有子节点，再删除父节点，彻底清理。
 *
 * 认证：复用 auth.setup 保存的登录态，从 tests/.auth/user.json 读取 token，
 * 请求头携带 `Authorization: Bearer <token>`。
 */

interface OrgNode {
  id: string
  orgName?: string
  children?: OrgNode[]
}

/** 从 user.json 的 localStorage 中读取 accessToken */
function readToken(): string {
  const auth = loadAuthLocalStorage()
  const entry = auth.find(e => e.name === 'token')
  return entry?.value ?? ''
}

/** 发起带认证的 JSON 请求，返回后端 Result 结构 */
async function apiRequest<T>(url: string, token: string, method = 'GET'): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  })
  if (!res.ok) {
    throw new Error(`API 请求失败: ${method} ${url} -> HTTP ${res.status}`)
  }
  return (await res.json()) as T
}

/** 后序遍历收集删除顺序：先所有子节点（叶子），再父节点 */
function collectDeleteOrder(nodes: OrgNode[], out: string[] = []): string[] {
  for (const node of nodes) {
    if (node.children?.length) {
      collectDeleteOrder(node.children, out)
    }
    if (node.id) {
      out.push(node.id)
    }
  }
  return out
}

/**
 * 删除所有名称包含指定前缀的组织（及其子树）。
 * 使用后台 API：GET /tree 获取匹配树，按叶子优先 DELETE 逐个删除。
 */
export async function cleanupTestOrganizations(namePrefix: string): Promise<void> {
  const token = readToken()
  if (!token) {
    console.warn('[organizationCleanup] 未读取到 token，跳过清理')
    return
  }

  const config = getTestConfig()
  const base = config.baseURL.replace(/\/+$/, '')
  const treeUrl = `${base}/api/system/organization/tree?orgName=${encodeURIComponent(namePrefix)}`

  const treeRes = await apiRequest<{ code?: number; data?: OrgNode[] }>(treeUrl, token)
  const tree = treeRes.data ?? []

  const deleteOrder = collectDeleteOrder(tree)
  if (deleteOrder.length === 0) {
    return
  }

  console.log(`[organizationCleanup] 清理 ${deleteOrder.length} 个组织（叶子优先）`)
  for (const id of deleteOrder) {
    await apiRequest<{ code?: number }>(`${base}/api/system/organization/${id}`, token, 'DELETE')
  }
}
