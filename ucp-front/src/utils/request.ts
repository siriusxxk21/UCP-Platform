import type { AxiosInstance, AxiosRequestConfig } from 'axios'
import axios from 'axios'
import { notification } from 'ant-design-vue'
import { useUserStore } from '../stores/user'
import { isSameOriginRequest, realtimeClientId } from '../realtime/client-id'

declare module 'axios' {
  export interface AxiosRequestConfig {
    skipAuthRefresh?: boolean
    quiet?: boolean
    /** 这些业务码由调用方自己处理（如保存冲突后自动重试）：不弹全局错误通知，其它错误照旧。 */
    quietCodes?: number[]
  }
}

// 定义自定义的请求接口，返回值是 Promise<T> 而不是 Promise<AxiosResponse<T>>
interface CustomAxiosInstance extends AxiosInstance {
  get: <T = any>(url: string, config?: AxiosRequestConfig) => Promise<T>
  post: <T = any>(url: string, data?: any, config?: AxiosRequestConfig) => Promise<T>
  put: <T = any>(url: string, data?: any, config?: AxiosRequestConfig) => Promise<T>
  delete: <T = any>(url: string, config?: AxiosRequestConfig) => Promise<T>
}

// 401 登出锁，防止并发请求多次触发登出
let isLoggingOut = false
let refreshPromise: Promise<string> | null = null
export const REQUEST_TIMEOUT_MS = 10000
export const REQUEST_ERROR_DURATION_SECONDS = 3

/**
 * 展示接口错误。
 *
 * 使用可关闭的通知代替 Message，避免网络异常或后端错误长期遮挡页面操作。
 */
function showRequestError(errorMessage: string) {
  notification.error({
    message: errorMessage,
    duration: REQUEST_ERROR_DURATION_SECONDS,
    placement: 'topRight'
  })
}

function isUnsafeIntegerToken(token: string) {
  const unsigned = token.startsWith('-') ? token.slice(1) : token
  const digits = unsigned.replace(/^0+/, '') || '0'
  if (digits.length < 16) return false
  if (digits.length > 16) return true
  return digits > String(Number.MAX_SAFE_INTEGER)
}

function quoteUnsafeJsonIntegers(json: string) {
  let result = ''
  let index = 0
  let inString = false
  let escaping = false

  while (index < json.length) {
    const char = json[index]

    if (inString) {
      result += char
      if (escaping) {
        escaping = false
      } else if (char === '\\') {
        escaping = true
      } else if (char === '"') {
        inString = false
      }
      index += 1
      continue
    }

    if (char === '"') {
      inString = true
      result += char
      index += 1
      continue
    }

    if (char === '-' || (char >= '0' && char <= '9')) {
      const start = index
      let cursor = index
      let hasFractionOrExponent = false

      if (json[cursor] === '-') cursor += 1

      if (json[cursor] < '0' || json[cursor] > '9') {
        result += char
        index += 1
        continue
      }

      if (json[cursor] === '0') {
        cursor += 1
      } else {
        while (json[cursor] >= '0' && json[cursor] <= '9') cursor += 1
      }

      if (json[cursor] === '.') {
        hasFractionOrExponent = true
        cursor += 1
        while (json[cursor] >= '0' && json[cursor] <= '9') cursor += 1
      }

      if (json[cursor] === 'e' || json[cursor] === 'E') {
        hasFractionOrExponent = true
        cursor += 1
        if (json[cursor] === '+' || json[cursor] === '-') cursor += 1
        while (json[cursor] >= '0' && json[cursor] <= '9') cursor += 1
      }

      const token = json.slice(start, cursor)
      result += !hasFractionOrExponent && isUnsafeIntegerToken(token) ? `"${token}"` : token
      index = cursor
      continue
    }

    result += char
    index += 1
  }

  return result
}

function parseJsonPreserveLargeIntegers(data: unknown) {
  if (typeof data !== 'string') return data

  const content = data.trim()
  if (!content || (!content.startsWith('{') && !content.startsWith('['))) return data

  try {
    return JSON.parse(quoteUnsafeJsonIntegers(content))
  } catch {
    try {
      return JSON.parse(content)
    } catch {
      return data
    }
  }
}

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: REQUEST_TIMEOUT_MS,
  transformResponse: [parseJsonPreserveLargeIntegers],
  headers: {
    'Content-Type': 'application/json'
  }
}) as CustomAxiosInstance

// 请求拦截器
request.interceptors.request.use(
  config => {
    const userStore = useUserStore()
    if (userStore.token && !config.headers.Authorization) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    // 带上本页签的标识：服务端据此在「记录变更通知」里注明是谁发起的，本页签不重复刷新自己的变更。
    // 只在同源请求上带：跨域预检的放行清单里没有这个头，带了请求会发不出去。
    if (isSameOriginRequest(config.baseURL, config.url)) config.headers['X-Realtime-Client'] = realtimeClientId
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  async response => {
    // 检查是否有新 Token（Token 续期）
    const newToken = response.headers['x-new-token']
    if (newToken) {
      const userStore = useUserStore()
      userStore.updateToken(newToken)
      console.log('Token 已自动续期')
    }

    // 文件接口也可能返回 JSON 业务错误；沿用统一鉴权及错误处理，避免把拒绝信息下载成文件。
    if (response.config.responseType === 'blob') {
      if (response.data instanceof Blob && response.data.type.toLowerCase().includes('json')) {
        response.data = JSON.parse(await response.data.text())
      } else {
        return response.data
      }
    }

    const res = response.data
    if (res.code !== 0 && res.code !== 200) {
      const errorMessage = res.msg || res.message || '请求失败'
      // 调用方声明自己处理这个业务码（如保存冲突后自动重试）：这一次不弹全局错误通知。
      if (response.config.quietCodes?.includes(res.code)) response.config.quiet = true

      // 正式会话中途过期后先退出，再次完成预认证才能取得一次性改密凭证。
      if (res.code === 1002000009) {
        handleLogout(errorMessage)
        return Promise.reject(new Error(errorMessage))
      }

      // 后端返回业务 code 401（HTTP 200），Token 已失效
      // 先尝试刷新 Token，成功则重试原请求；失败则登出
      if (res.code === 401) {
        const originalConfig = response.config as AxiosRequestConfig & { _retry?: boolean }
        if (!originalConfig?.skipAuthRefresh && !originalConfig?._retry) {
          try {
            originalConfig._retry = true
            const refreshed = await refreshAccessToken()
            originalConfig.headers = originalConfig.headers || {}
            originalConfig.headers.Authorization = `Bearer ${refreshed}`
            return request(originalConfig)
          } catch (error) {
            if (!isAuthRejection(error)) return Promise.reject(error)
          }
        }
        handleLogout()
      } else {
        if (!response.config.quiet) showRequestError(errorMessage)
      }
      // 保存界面需要区分明确业务拒绝与网络未知结果，并定位到明细行。
      return Promise.reject(Object.assign(new Error(errorMessage), { businessCode: res.code, details: res.data }))
    }
    return res.data
  },
  async error => {
    // 处理 HTTP 401 状态码（兜底，正常情况不会走到这里，因为后端返回的是 HTTP 200）
    if (error.response && error.response.status === 401) {
      const originalConfig = error.config as AxiosRequestConfig & { _retry?: boolean }
      if (!originalConfig?.skipAuthRefresh && !originalConfig?._retry) {
        try {
          originalConfig._retry = true
          const newToken = await refreshAccessToken()
          originalConfig.headers = originalConfig.headers || {}
          originalConfig.headers.Authorization = `Bearer ${newToken}`
          return request(originalConfig)
        } catch (refreshError) {
          if (!isAuthRejection(refreshError)) return Promise.reject(refreshError)
        }
      }
      handleLogout()
      return Promise.reject(error)
    }
    if (!error.config?.quiet) showRequestError(error.message || '网络错误')
    return Promise.reject(error)
  }
)

/** 处理登出逻辑，防止并发请求多次触发 */
function handleLogout(messageText = '登录已过期，请重新登录') {
  if (isLoggingOut) return
  isLoggingOut = true
  showRequestError(messageText)
  const userStore = useUserStore()
  userStore.logout()
  // 延迟跳转，避免多个并发请求同时触发
  setTimeout(() => {
    window.location.href = '/login'
    isLoggingOut = false
  }, 100)
}

/** 服务端明确拒绝身份时才清理登录态；网络中断和服务故障保留凭证。 */
export function isAuthRejection(error: unknown): boolean {
  const failure = error as { authRejected?: boolean; response?: { status?: number } }
  return failure?.authRejected === true || [400, 401, 403].includes(failure?.response?.status || 0)
}

/** 使用 refreshToken 换取新的 accessToken，多个并发 401 只刷新一次 */
export async function refreshAccessToken() {
  const userStore = useUserStore()
  if (!userStore.refreshToken) {
    throw Object.assign(new Error('refreshToken 不存在'), { authRejected: true })
  }
  if (!refreshPromise) {
    const sessionToken = userStore.refreshToken
    const previousAccessToken = userStore.token
    const renew = async () => {
      if (localStorage.getItem('refreshToken') !== sessionToken) throw new Error('登录会话已切换')
      // 另一个标签页已完成续期时复用其结果，避免相互废弃新签发的访问凭证。
      const currentAccessToken = localStorage.getItem('token')
      if (currentAccessToken && currentAccessToken !== previousAccessToken) {
        userStore.setAuthTokens(currentAccessToken, sessionToken, localStorage.getItem('expiresTime') || undefined)
        return currentAccessToken
      }
      return axios
        .post(`${import.meta.env.VITE_API_BASE_URL || '/api'}/system/auth/refresh-token`, undefined, {
          params: { refreshToken: sessionToken },
          // 路由守卫会等待刷新结果，必须设置上限，避免网络半开时所有菜单永久阻塞。
          timeout: REQUEST_TIMEOUT_MS
        })
        .then(response => {
          const res = response.data
          if (res.code !== 0 && res.code !== 200) {
            throw Object.assign(new Error(res.msg || res.message || '刷新令牌失败'), {
              authRejected: [400, 401, 403, 1002000009].includes(res.code)
            })
          }
          // 退出或切换账号后，迟到的刷新响应不能恢复旧会话。
          if (userStore.refreshToken !== sessionToken || localStorage.getItem('refreshToken') !== sessionToken) {
            throw new Error('登录会话已切换')
          }
          const data = res.data
          userStore.setAuthTokens(data.accessToken, data.refreshToken, data.expiresTime)
          return data.accessToken as string
        })
    }
    const renewWithLock = async () =>
      navigator.locks ? await navigator.locks.request('os-auth-refresh', renew) : await renew()
    refreshPromise = renewWithLock().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

export default request
