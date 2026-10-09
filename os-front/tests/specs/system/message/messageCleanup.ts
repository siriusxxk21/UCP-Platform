import { getTestConfig } from '../../../utils/config'
import { loadAuthLocalStorage } from '../../../utils/auth'

/**
 * 系统消息模块 - 基于后台 API 的测试数据清理工具
 *
 * 清理内容：
 *  1. 消息模板（sys_msg_template）：通过 DELETE /api/msg/template/delete 删除，
 *     按 code 前缀匹配本次测试创建的模板。
 *  2. 消息/通知（sys_msg / sys_msg_notice）：后端**未提供删除接口**，
 *     且消息为当前用户自己的站内信，无法通过 API 清理，测试产生的消息数据保留。
 *
 * 认证：复用 auth.setup 保存的登录态，从 tests/.auth/user.json 读取 token，
 * 请求头携带 `Authorization: Bearer <token>`。
 */

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

/**
 * 清理测试消息模板：按 code 前缀匹配，调用后台 API 逐个删除。
 * 测试模板编码统一以 codePrefix 开头（如 TEST_MSG_），通过 GET /list 获取并过滤删除。
 */
export async function cleanupTestMsgTemplates(codePrefix: string): Promise<void> {
  const token = readToken()
  if (!token) {
    console.warn('[messageCleanup] 未读取到 token，跳过清理')
    return
  }

  const config = getTestConfig()
  const base = config.baseURL.replace(/\/+$/, '')
  const listUrl = `${base}/api/msg/template/list`

  const listRes = await apiRequest<{ code?: number; data?: Array<{ id: number; code: string }> }>(listUrl, token)
  const templates = (listRes.data ?? []).filter(t => String(t.code).startsWith(codePrefix))

  if (templates.length === 0) {
    return
  }

  console.log(`[messageCleanup] 清理 ${templates.length} 个消息模板`)
  for (const t of templates) {
    await apiRequest<{ code?: number }>(`${base}/api/msg/template/delete?id=${t.id}`, token, 'DELETE')
  }
}
