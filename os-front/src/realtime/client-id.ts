import { v4 as uuidv4 } from 'uuid'

/**
 * 本页签的发起人标识：模块加载时生成一次，之后发往本站的 HTTP 请求用请求头 X-Realtime-Client 带上。
 * 服务端只用它回填「记录变更通知」里的 origin，让发起变更的页签认出自己；不参与任何权限判断。
 */
export const realtimeClientId: string = uuidv4()

const ABSOLUTE_URL = /^(?:[a-z][a-z\d+.-]*:)?\/\//i

/**
 * 这个请求是不是发往本页面同源的地址。
 *
 * 只有同源请求才带 X-Realtime-Client：跨域时浏览器先发预检，而服务端的跨域放行清单
 * （CorsFilterConfig 的 ALLOWED_HEADERS）里没有这个头，带上会让预检失败、请求根本发不出去。
 * 跨域时不带的后果只是「认不出自己发起的变更」，多软刷新一次。
 */
export function isSameOriginRequest(baseURL: string | undefined, url: string | undefined): boolean {
  const target = [url, baseURL].find(value => !!value && ABSOLUTE_URL.test(value))
  // 相对地址一定同源
  if (!target) return true
  if (typeof location === 'undefined') return false
  try {
    return new URL(target, location.href).origin === location.origin
  } catch {
    return false
  }
}
