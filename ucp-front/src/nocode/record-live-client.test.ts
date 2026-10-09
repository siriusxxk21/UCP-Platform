// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import request from '@/utils/request'
import { isSameOriginRequest, realtimeClientId } from '@/realtime/client-id'
import { notification } from 'ant-design-vue'
import { createRuntimeApi, RECORD_CONFLICT, RECORD_NOT_FOUND } from '@/api/nocode/runtime'

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ token: 'test-session', isSessionIdle: () => false, updateToken: vi.fn(), logout: vi.fn() })
}))
vi.mock('ant-design-vue', () => ({ notification: { error: vi.fn() } }))

async function observe(send: () => Promise<unknown>) {
  const original = request.defaults.adapter
  const observed: InternalAxiosRequestConfig[] = []
  request.defaults.adapter = async config => {
    observed.push(config)
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 0, data: null } }
  }
  try {
    await send()
  } finally {
    request.defaults.adapter = original
  }
  return observed
}

describe('页签标识与请求头', () => {
  it('A1 页签标识是 UUID v4，同一次加载内不变', async () => {
    expect(realtimeClientId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    // 服务端只认这个形状
    expect(realtimeClientId).toMatch(/^[A-Za-z0-9-]{8,64}$/)
    const again = await import('@/realtime/client-id')
    expect(again.realtimeClientId).toBe(realtimeClientId)
  })

  it('A2 取数和写入的请求都带 X-Realtime-Client，登录凭证照旧', async () => {
    const [get] = await observe(() => request.get('/nocode/runtime/get', { params: { id: '1' } }))
    const [post] = await observe(() => request.post('/nocode/runtime/save', { id: '1' }))
    expect(get.method).toBe('get')
    expect(get.headers['X-Realtime-Client']).toBe(realtimeClientId)
    expect(post.method).toBe('post')
    expect(post.headers['X-Realtime-Client']).toBe(realtimeClientId)
    expect(get.headers.Authorization).toBe('Bearer test-session')
    expect(post.headers.Authorization).toBe('Bearer test-session')
  })

  it('跨域请求不带这个头（跨域预检的放行清单里没有它，带了请求会发不出去）；同源的绝对地址照带', async () => {
    const [cross] = await observe(() => request.get('https://files.example.com/download/1'))
    expect(cross.headers['X-Realtime-Client']).toBeUndefined()
    expect(cross.headers.Authorization).toBe('Bearer test-session')
    const [same] = await observe(() => request.get(location.origin + '/api/nocode/runtime/get'))
    expect(same.headers['X-Realtime-Client']).toBe(realtimeClientId)

    expect(isSameOriginRequest('/api', '/nocode/runtime/get')).toBe(true)
    expect(isSameOriginRequest(undefined, undefined)).toBe(true)
    expect(isSameOriginRequest(location.origin + '/api', '/x')).toBe(true)
    // 接口基址指到别的主机（开发环境直连远端服务）
    expect(isSameOriginRequest('http://10.0.0.8:48080/api', '/x')).toBe(false)
    expect(isSameOriginRequest('//cdn.example.com', '/x')).toBe(false)
    // 请求地址自己是绝对地址时以它为准
    expect(isSameOriginRequest('http://10.0.0.8:48080/api', location.origin + '/x')).toBe(true)
    expect(isSameOriginRequest('/api', 'http://10.0.0.8:48080/x')).toBe(false)
  })

  it('A2 调用方自己带了登录凭证时不被覆盖', async () => {
    const [config] = await observe(() => request.get('/x', { headers: { Authorization: 'Bearer other' } }))
    expect(config.headers.Authorization).toBe('Bearer other')
    expect(config.headers['X-Realtime-Client']).toBe(realtimeClientId)
  })
})

describe('调用方自己处理的业务码不弹全局错误通知', () => {
  const runtime = createRuntimeApi(request)
  /** 服务端以业务码拒绝（HTTP 200）。 */
  async function rejected(code: number, send: () => Promise<unknown>) {
    const original = request.defaults.adapter
    request.defaults.adapter = async config => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: { code, msg: '被拒绝', data: null }
    })
    vi.mocked(notification.error).mockClear()
    try {
      return await send().then(
        () => undefined,
        (error: unknown) => error as Error & { businessCode?: number }
      )
    } finally {
      request.defaults.adapter = original
    }
  }
  const deletion = { applicationId: 'app', objectId: 'object', id: 'r1', expectedRevision: '1' }

  it('删除遇到「记录已被修改」：不弹通知，错误照常交给调用方', async () => {
    const error = await rejected(RECORD_CONFLICT, () => runtime.delete(deletion))
    expect(error?.businessCode).toBe(RECORD_CONFLICT)
    expect(error?.message).toBe('被拒绝')
    expect(notification.error).not.toHaveBeenCalled()
  })

  it('删除因别的原因被拒：照旧弹通知', async () => {
    const error = await rejected(1_050_000_001, () => runtime.delete(deletion))
    expect(error?.businessCode).toBe(1_050_000_001)
    expect(notification.error).toHaveBeenCalledTimes(1)
  })

  it('静默取记录：记录不存在时不弹通知；普通取记录照旧弹', async () => {
    const quiet = await rejected(RECORD_NOT_FOUND, () => runtime.get('app', 'object', 'r1', { quiet: true }))
    expect(quiet?.businessCode).toBe(RECORD_NOT_FOUND)
    expect(notification.error).not.toHaveBeenCalled()
    await rejected(RECORD_NOT_FOUND, () => runtime.get('app', 'object', 'r1'))
    expect(notification.error).toHaveBeenCalledTimes(1)
  })

  it('静默的分页、统计、统计明细：失败不弹通知；不带静默选项的照旧弹', async () => {
    const query = { applicationId: 'app', objectId: 'object', pageNo: 1, pageSize: 10 } as never
    const report = { applicationId: 'app', reportId: 'report' } as never
    for (const send of [
      () => runtime.page(query, { quiet: true }),
      () => runtime.report(report, { quiet: true }),
      () => runtime.reportDetails(report, { quiet: true })
    ]) {
      const error = await rejected(500, send)
      expect(error?.message).toBe('被拒绝')
      expect(notification.error).not.toHaveBeenCalled()
    }
    for (const send of [() => runtime.page(query), () => runtime.report(report), () => runtime.reportDetails(report)]) {
      await rejected(500, send)
      expect(notification.error).toHaveBeenCalledTimes(1)
    }
    // 请求体不因静默选项而变
    const [plain] = await observe(() => runtime.page(query))
    const [quiet] = await observe(() => runtime.page(query, { quiet: true }))
    expect(quiet.data).toBe(plain.data)
    expect(quiet.url).toBe('/nocode/runtime/page')
  })

  it('取记录的请求参数不因静默选项而变', async () => {
    const [plain] = await observe(() => runtime.get('app', 'object', 'r1'))
    const [quiet] = await observe(() => runtime.get('app', 'object', 'r1', { quiet: true }))
    expect(plain.params).toEqual({ applicationId: 'app', objectId: 'object', id: 'r1' })
    expect(quiet.params).toEqual(plain.params)
    expect(quiet.url).toBe('/nocode/runtime/get')
  })
})
