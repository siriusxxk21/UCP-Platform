import { describe, expect, it, vi } from 'vitest'
import { createRecordFolderApi } from '@/api/nocode/record-folder'
import type { NocodeHttpClient } from '@/api/nocode/object'

function client() {
  const get = vi.fn().mockResolvedValue(undefined),
    post = vi.fn().mockResolvedValue(undefined),
    put = vi.fn()
  return { get, post, put, api: createRecordFolderApi({ get, post, put } as unknown as NocodeHttpClient) }
}
const credential = { applicationId: 'app', objectId: 'obj', recordId: 'rec', sourceId: '7' }

describe('记录文件夹接口', () => {
  it('配置的四个读取 / 保存接口：地址、方法、参数，全部不弹全局错误', async () => {
    const { api, get, post } = client()
    await api.config('obj')
    expect(get).toHaveBeenLastCalledWith('/nocode/record-folder/config', { params: { objectId: 'obj' }, quiet: true })
    await api.candidates('obj')
    expect(get).toHaveBeenLastCalledWith('/nocode/record-folder/config/candidates', {
      params: { objectId: 'obj' },
      quiet: true
    })
    await api.nameFields('obj')
    expect(get).toHaveBeenLastCalledWith('/nocode/record-folder/config/name-fields', {
      params: { objectId: 'obj' },
      quiet: true
    })
    const sources = [{ kind: 'FOLDER' as const, placement: 'DIRECT' as const, label: '', spaceId: '1', entryId: '2' }]
    await api.saveConfig('obj', sources)
    expect(post).toHaveBeenLastCalledWith(
      '/nocode/record-folder/config/save',
      { objectId: 'obj', sources },
      { quiet: true }
    )
  })

  it('补建与打开', async () => {
    const { api, post } = client()
    await api.backfill({ objectId: 'obj', sourceId: '7', cursor: 'c1', limit: 100 })
    expect(post).toHaveBeenLastCalledWith(
      '/nocode/record-folder/config/backfill',
      { objectId: 'obj', sourceId: '7', cursor: 'c1', limit: 100 },
      { quiet: true }
    )
    await api.open({ applicationId: 'app', objectId: 'obj', recordId: 'rec' })
    expect(post).toHaveBeenLastCalledWith(
      '/nocode/record-folder/open',
      { applicationId: 'app', objectId: 'obj', recordId: 'rec' },
      { quiet: true }
    )
  })

  it('浏览与写入都是 POST 到 /entry/*，请求体原样带出，全部 quiet', async () => {
    const { api, post, get } = client()
    const cases: Array<[keyof typeof api, string, object]> = [
      ['list', 'list', { parentId: 0 }],
      ['get', 'get', { id: '9' }],
      ['path', 'path', { id: '9' }],
      ['search', 'search', { name: '合同', limit: 100 }],
      ['createFolder', 'create-folder', { parentId: 0, name: '资料' }],
      ['rename', 'rename', { id: '9', name: '新名' }],
      ['move', 'move', { id: '9', targetParentId: '3' }],
      ['copy', 'copy', { id: '9', targetParentId: '3' }],
      ['trash', 'trash', { ids: ['9', '10'] }],
      ['trashList', 'trash-list', {}],
      ['restore', 'restore', { id: '9' }]
    ]
    for (const [method, path, extra] of cases) {
      const query = { ...credential, ...extra }
      await (api[method] as (query: object) => Promise<unknown>)(query)
      expect(post).toHaveBeenLastCalledWith('/nocode/record-folder/entry/' + path, query, { quiet: true })
    }
    expect(post).toHaveBeenCalledTimes(cases.length)
    expect(get).not.toHaveBeenCalled()
  })

  it('取内容走认证客户端的 Blob 请求，超时 5 分钟，参数里不带令牌', async () => {
    const { api, get } = client()
    get.mockResolvedValue(new Blob())
    await api.content({ ...credential, id: '9' }, true)
    expect(get).toHaveBeenCalledWith('/nocode/record-folder/entry/content', {
      params: { ...credential, id: '9', inline: true },
      responseType: 'blob',
      timeout: 300000,
      quiet: true
    })
    expect(JSON.stringify(get.mock.calls)).not.toContain('token')
  })
})
