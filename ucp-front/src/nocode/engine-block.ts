import type { EngineBlockConfig } from '@/types/nocode/application-ui'

/**
 * 设计引擎区块（laneEG）的纯函数：地址校验、iframe 属性、消息过滤。
 *
 * 安全口径（DESIGN.md §1.4）：iframe 不给 allow-same-origin（引擎是不透明源，读不到本站 localStorage 里的登录令牌与父页面 DOM），
 * 不给顶层导航与弹窗；引擎地址只收站内相对路径（与后端 ApplicationEngineValidator 同一条规则）；令牌只经 postMessage 交给
 * 「本 iframe 的窗口」，且只在它先发 ready / token-request 之后。
 */
export const ENGINE_SANDBOX = 'allow-scripts allow-downloads allow-modals allow-forms'
export const ENGINE_URL_PATTERN = /^\/[A-Za-z0-9._~/-]{0,200}$/
export const DEFAULT_ENGINE_URL = '/engine01/'

export function engineUrlError(url: string | null | undefined): string | null {
  if (!url) return null
  if (!ENGINE_URL_PATTERN.test(url) || url.includes('//') || url.includes('..'))
    return '设计引擎地址只能是站内路径（如 /engine01/），不能是外部地址'
  return null
}

/** iframe 地址：站内路径 + 项目键（不含令牌；令牌不进 URL）。 */
export function engineFrameSrc(engineUrl: string, project: string): string {
  const invalid = engineUrlError(engineUrl)
  if (invalid) throw new Error(invalid)
  const query = new URLSearchParams({ embed: '1', project })
  return `${engineUrl}${engineUrl.includes('?') ? '&' : '?'}${query.toString()}`
}

export interface EngineFrameMessage {
  type: 'engine01:ready' | 'engine01:token-request'
  project?: string
}

/** 只接受「本 iframe 窗口」发来的 ready / token-request，且项目键与当前记录一致。 */
export function engineRequest(
  event: Pick<MessageEvent, 'source' | 'data'>,
  frame: Window | null | undefined,
  project: string
): EngineFrameMessage | null {
  if (!frame || event.source !== frame) return null
  const data = event.data as EngineFrameMessage | null
  if (!data || (data.type !== 'engine01:ready' && data.type !== 'engine01:token-request')) return null
  if (data.project !== undefined && data.project !== project) return null
  return data
}

export function parseEngineConfig(value: unknown): EngineBlockConfig | null {
  if (!value) return null
  const parsed = JSON.parse(String(value)) as EngineBlockConfig | null
  if (parsed !== null && (typeof parsed !== 'object' || Array.isArray(parsed))) throw new Error('设计引擎配置格式错误')
  return parsed
}
