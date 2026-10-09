import { describe, expect, it, vi } from 'vitest'
import { createBusinessFileApi } from '@/api/nocode/business-file'
import type { NocodeHttpClient } from '@/api/nocode/object'

describe('业务文件内容请求', () => {
  it('设计器从专用端点读取可配置业务空间', async () => {
    const get = vi.fn().mockResolvedValue([])
    const api = createBusinessFileApi({ get, post: vi.fn(), put: vi.fn() } as unknown as NocodeHttpClient)
    await api.configSpaces()
    expect(get).toHaveBeenCalledWith('/nocode/biz-file/config/spaces', { quiet: true })
  })

  it('通过认证客户端读取 Blob，参数不携带令牌且强制附件响应', async () => {
    const get = vi.fn().mockResolvedValue(new Blob())
    const api = createBusinessFileApi({ get, post: vi.fn(), put: vi.fn() } as unknown as NocodeHttpClient)
    await api.content({ objectId: 'object', recordId: 'record', fieldId: 'field', entryId: 'entry' })

    expect(get).toHaveBeenCalledWith('/nocode/biz-file/content', {
      params: {
        objectId: 'object',
        recordId: 'record',
        fieldId: 'field',
        entryId: 'entry',
        inline: false
      },
      responseType: 'blob',
      timeout: 300000,
      quiet: true
    })
    expect(JSON.stringify(get.mock.calls)).not.toContain('token')
  })
})
