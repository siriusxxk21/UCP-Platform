import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DriveEntry } from '@/types/drive'

const api = vi.hoisted(() => ({
  getDriveEntryList: vi.fn(),
  getDriveEntry: vi.fn(),
  getDriveEntryPath: vi.fn(),
  searchDriveEntry: vi.fn(),
  getDriveEntryContent: vi.fn(),
  renameDriveEntry: vi.fn(),
  moveDriveEntry: vi.fn(),
  trashDriveEntries: vi.fn()
}))
vi.mock('@/api/drive/entry', () => ({
  ...api,
  getDriveBreadcrumb: vi.fn(),
  DRIVE_ENTRY_UPLOAD_PATH: '/drive/entry/upload',
  buildDriveContentUrl: vi.fn(),
  copyDriveEntry: vi.fn(),
  createDriveFolder: vi.fn()
}))
vi.mock('@/api/drive/mark', () => ({ recordDriveAccess: vi.fn(), updateFavorite: vi.fn() }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ token: 'test-only' }) }))
vi.mock('@uppy/xhr-upload', () => ({ default: class XHRUpload {} }))

import { createDriveGateway } from '@/views/drive/driver/drive-gateway'
import { createDriveDriver } from '@/views/drive/driver/drive-driver'

function entry(id: string, parentId: string, name: string, type: DriveEntry['type'] = 'FOLDER'): DriveEntry {
  return { id, parentId, name, type, size: 0, spaceId: '1', trashState: 'NORMAL', inheritParent: true }
}

describe('网盘路径与上传契约回归', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.getDriveEntry.mockResolvedValue({ role: 'MANAGER' })
  })

  const driver = () =>
    createDriveDriver({ gateway: createDriveGateway('1'), storageName: '测试空间', getRootRole: () => 'MANAGER' })

  it('首次直接进入深层目录后，上传定位到当前目录编号', async () => {
    api.getDriveEntryList.mockImplementation(async ({ parentId }) => {
      if (String(parentId) === '0') return [entry('10', '0', '合同')]
      if (parentId === '10') return [entry('11', '10', '附件')]
      return []
    })
    const instance = driver()
    const listing = await instance.list({ path: '/合同/附件' })
    expect(listing.dirname).toBe('测试空间://合同/附件')
    expect(instance.peekId('/合同')).toBe('10')
    expect(instance.peekId('/合同/附件')).toBe('11')
    expect(instance.peekId('/合同/合同')).toBeUndefined()
  })

  it('根目录列出的子目录不能被误记为其自身父路径，搜索结果定位正确', async () => {
    api.getDriveEntryList.mockResolvedValue([entry('10', '0', '合同')])
    api.searchDriveEntry.mockResolvedValue([entry('11', '10', '清单.txt', 'FILE')])
    api.getDriveEntryPath.mockResolvedValue('/合同')
    const instance = driver()
    await instance.list({ path: '/' })
    const result = await instance.search({ path: '/', filter: '清单', deep: true })
    expect(result[0]?.path).toBe('/合同/清单.txt')
  })

  it('不含子文件夹时仅返回当前目录，并按大小筛选', async () => {
    api.searchDriveEntry.mockResolvedValue([
      { ...entry('1', '0', '小文件.txt', 'FILE'), size: 10 },
      { ...entry('2', '0', '大文件.txt', 'FILE'), size: 12 * 1024 * 1024 },
      { ...entry('3', '10', '子文件.txt', 'FILE'), size: 10 }
    ])
    const result = await driver().search({ path: '/', filter: '文件', deep: false, size: 'small' })
    expect(result.map(item => item.basename)).toEqual(['小文件.txt'])
    expect(api.getDriveEntryPath).not.toHaveBeenCalled()
  })

  it('包含子文件夹也不返回所选目录以外的同名文件', async () => {
    api.getDriveEntryList.mockResolvedValue([entry('10', '0', '合同')])
    api.searchDriveEntry.mockResolvedValue([
      entry('2', '0', '根目录.txt', 'FILE'),
      entry('3', '10', '本目录.txt', 'FILE')
    ])
    const instance = driver()
    await instance.list({ path: '/' })
    const result = await instance.search({ path: '/合同', filter: '目录', deep: true })
    expect(result.map(item => item.basename)).toEqual(['本目录.txt'])
  })

  it('重命名目录后清除其旧子路径，旧地址不能继续访问文件', async () => {
    let renamed = false
    api.getDriveEntryList.mockImplementation(async ({ parentId }) => {
      if (String(parentId) === '0') return [entry('10', '0', renamed ? '新合同' : '合同')]
      return [entry('11', '10', '清单.txt', 'FILE')]
    })
    api.renameDriveEntry.mockImplementation(async () => {
      renamed = true
    })
    const instance = driver()
    await instance.list({ path: '/' })
    await instance.list({ path: '/合同' })
    await instance.rename({ path: '/', item: '/合同', name: '新合同' })
    expect(instance.peekId('/合同/清单.txt')).toBeUndefined()
    await expect(instance.getContent({ path: '/合同/清单.txt' })).rejects.toThrow('未找到节点')
    expect(api.getDriveEntryContent).not.toHaveBeenCalled()
  })

  it('目录刷新后移除其他会话已删除节点的缓存，并刷新权限', async () => {
    api.getDriveEntryList
      .mockResolvedValueOnce([entry('10', '0', '合同')])
      .mockResolvedValueOnce([entry('11', '10', '清单.txt', 'FILE')])
      .mockResolvedValue([])
    api.getDriveEntry.mockResolvedValueOnce({ role: 'MANAGER' }).mockResolvedValue({ role: 'VIEWER' })
    const instance = driver()
    await instance.list({ path: '/' })
    await instance.list({ path: '/合同' })
    const listing = await instance.list({ path: '/合同' })
    expect(instance.peekId('/合同/清单.txt')).toBeUndefined()
    expect(listing.read_only).toBe(true)
  })

  it('HTTP 200 的业务拒绝必须让上传失败，而不是展示上传成功', () => {
    const instance = driver()
    const uppy = { use: vi.fn(), on: vi.fn() }
    instance.configureUploader!(uppy as never, { getTargetPath: () => '/' } as never)
    const options = uppy.use.mock.calls[0]![1]
    expect(options.getResponseData).toBeTypeOf('function')
    expect(() => options.getResponseData({ responseText: JSON.stringify({ code: 403, msg: '无权上传' }) })).toThrow(
      '无权上传'
    )
    expect(options.getResponseData({ responseText: JSON.stringify({ code: 0, data: { id: '1' } }) })).toEqual({
      code: 0,
      data: { id: '1' }
    })
    expect(() => options.getResponseData({ responseText: '<html>登录失效</html>' })).toThrow('上传响应无效')
  })
})
