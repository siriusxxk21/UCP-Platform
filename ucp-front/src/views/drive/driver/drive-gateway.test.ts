import { beforeEach, describe, expect, it, vi } from 'vitest'

const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: request }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ token: 'tk-1' }) }))

import { createDriveGateway } from './drive-gateway'

/**
 * 普通网盘的口子只是把现有 /drive 接口逐个包一层：每个方法调到的地址与参数必须和拆之前驱动直接调用时相同。
 */
describe('普通网盘的接口口子', () => {
  const gateway = createDriveGateway('900')

  beforeEach(() => {
    Object.values(request).forEach(fn => fn.mockReset().mockResolvedValue(undefined))
  })

  it('列目录带空间编号与父节点编号', async () => {
    request.get.mockResolvedValue([{ id: '1' }])
    expect(await gateway.list(0)).toEqual([{ id: '1' }])
    expect(request.get).toHaveBeenCalledWith('/drive/entry/list', { params: { spaceId: '900', parentId: 0 } })
  })

  it('取详情按节点编号', async () => {
    request.get.mockResolvedValue({ id: '7', role: 'EDITOR' })
    expect(await gateway.get('7')).toEqual({ id: '7', role: 'EDITOR' })
    expect(request.get).toHaveBeenCalledWith('/drive/entry/get', { params: { id: '7' } })
  })

  it('路径由面包屑拼成名称路径；只有根时返回 /', async () => {
    request.get.mockResolvedValue([
      { id: 0, name: '' },
      { id: '3', name: '合同' },
      { id: '7', name: '2026' }
    ])
    expect(await gateway.path('7')).toBe('/合同/2026')
    expect(request.get).toHaveBeenCalledWith('/drive/entry/breadcrumb', { params: { entryId: '7' } })
    request.get.mockResolvedValue([{ id: 0, name: '' }])
    expect(await gateway.path('9')).toBe('/')
  })

  it('新建目录带空间编号、父节点与名称，返回新编号', async () => {
    request.post.mockResolvedValue('55')
    expect(await gateway.createFolder('7', '资料')).toBe('55')
    expect(request.post).toHaveBeenCalledWith('/drive/entry/create-folder', {
      spaceId: '900',
      parentId: '7',
      name: '资料'
    })
  })

  it('改名、移动、删除的地址与请求体', async () => {
    await gateway.rename('7', '新名')
    expect(request.put).toHaveBeenLastCalledWith('/drive/entry/rename', { id: '7', name: '新名' })
    await gateway.move('7', '8')
    expect(request.put).toHaveBeenLastCalledWith('/drive/entry/move', { id: '7', targetParentId: '8' })
    await gateway.trash(['7', '9'])
    expect(request.put).toHaveBeenLastCalledWith('/drive/entry/trash', { ids: ['7', '9'] })
  })

  it('复制的目标空间就是当前空间', async () => {
    await gateway.copy('7', '8')
    expect(request.post).toHaveBeenCalledWith('/drive/entry/copy', {
      id: '7',
      targetSpaceId: '900',
      targetParentId: '8'
    })
  })

  it('搜索带空间编号、关键字与条数上限', async () => {
    request.get.mockResolvedValue([])
    await gateway.search('合同', 100)
    expect(request.get).toHaveBeenCalledWith('/drive/entry/search', {
      params: { spaceId: '900', name: '合同', limit: 100 }
    })
  })

  it('取内容走认证客户端的 Blob 请求', async () => {
    const blob = new Blob(['x'])
    request.get.mockResolvedValue(blob)
    expect(await gateway.content('7', true)).toBe(blob)
    expect(request.get).toHaveBeenCalledWith('/drive/entry/content', {
      params: { id: '7', inline: true },
      responseType: 'blob'
    })
  })

  it('内容地址带节点编号、是否内联与访问令牌', () => {
    expect(gateway.contentUrl('7', true)).toBe('/api/drive/entry/content?id=7&inline=true&token=tk-1')
    expect(gateway.contentUrl('7', false)).toBe('/api/drive/entry/content?id=7&inline=false&token=tk-1')
  })

  it('上传地址与表单字段：空间编号与父节点编号都转成字符串', () => {
    expect(gateway.upload.endpoint).toBe('/drive/entry/upload')
    expect(gateway.upload.fields(0)).toEqual({ spaceId: '900', parentId: '0' })
    expect(gateway.upload.fields('7')).toEqual({ spaceId: '900', parentId: '7' })
  })

  it('收藏与登记最近访问', async () => {
    await gateway.favorite?.('7', true)
    expect(request.put).toHaveBeenCalledWith('/drive/mark/favorite', undefined, {
      params: { entryId: '7', favorite: true }
    })
    await gateway.recordAccess?.('7')
    expect(request.post).toHaveBeenCalledWith('/drive/mark/access', undefined, { params: { entryId: '7' } })
  })
})
