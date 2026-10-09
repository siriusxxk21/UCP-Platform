import { describe, expect, it, vi } from 'vitest'
import type { DriveEntry, DriveId, DrivePermissionRole } from '@/types/drive'

const uploader = vi.hoisted(() => ({ plugin: {} }))
vi.mock('@uppy/xhr-upload', () => ({ default: uploader.plugin }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ token: 'tk-1' }) }))
// 驱动只许经口子取数：直接引用网盘接口的调用在这里一律抛错
vi.mock('@/api/drive/entry', () => {
  const direct = (name: string) => () => {
    throw new Error('驱动不应直接调用 @/api/drive/entry：' + name)
  }
  return new Proxy(
    {},
    { get: (_target, name) => (typeof name === 'string' ? direct(name) : undefined), has: () => true }
  )
})

import { createDriveDriver } from './drive-driver'
import type { DriveGateway } from './drive-gateway'

const entry = (value: Partial<DriveEntry> & Pick<DriveEntry, 'id' | 'name'>): DriveEntry => ({
  spaceId: '900',
  parentId: 0,
  type: 'FILE',
  size: 10,
  inheritParent: true,
  ...value
})

/** 假口子：一棵内存里的目录树，记下每次调用 */
function fakeGateway(tree: Record<string, DriveEntry[]>, roles: Record<string, DrivePermissionRole> = {}) {
  const calls: Array<[string, ...unknown[]]> = []
  const record =
    <A extends unknown[], R>(name: string, run: (...args: A) => R) =>
    (...args: A): R => {
      calls.push([name, ...args])
      return run(...args)
    }
  const gateway: DriveGateway = {
    list: record('list', async (parentId: DriveId) => tree[String(parentId)] ?? []),
    get: record('get', async (id: DriveId) => entry({ id, name: 'x', role: roles[String(id)] })),
    path: record('path', async (id: DriveId) => (String(id) === '31' ? '/合同/深层' : '/')),
    createFolder: record('createFolder', async () => '77' as DriveId),
    rename: record('rename', async () => undefined),
    move: record('move', async () => undefined),
    copy: record('copy', async () => undefined),
    trash: record('trash', async () => undefined),
    search: record('search', async () => [entry({ id: '41', name: '搜到.txt', parentId: '31', modifiable: false })]),
    content: record('content', async () => new Blob(['正文'])),
    contentUrl: (id, inline) => `url:${String(id)}:${String(inline)}`,
    upload: { endpoint: '/x/upload', fields: parentId => ({ token: 'k', parentId: String(parentId) }) }
  }
  return { gateway, calls, named: (name: string) => calls.filter(call => call[0] === name) }
}

const tree = (): Record<string, DriveEntry[]> => ({
  '0': [
    entry({ id: '3', name: '合同', type: 'FOLDER' }),
    entry({ id: '4', name: '自己的.txt', modifiable: true }),
    entry({ id: '5', name: '别人的.txt', modifiable: false }),
    entry({ id: '6', name: '普通.txt' })
  ],
  '3': [entry({ id: '31', name: '深层', type: 'FOLDER', parentId: '3' })],
  '31': [entry({ id: '311', name: '最里.txt', parentId: '31' })]
})

function driverOf(fake: ReturnType<typeof fakeGateway>, role: DrivePermissionRole | undefined = 'EDITOR') {
  return createDriveDriver({ gateway: fake.gateway, storageName: '存储', getRootRole: () => role })
}

describe('网盘驱动经接口口子取数', () => {
  it('根目录固定为编号 0', async () => {
    const fake = fakeGateway(tree())
    const data = await driverOf(fake).list({ path: '存储://' })
    expect(fake.named('list')).toEqual([['list', 0]])
    expect(data.dirname).toBe('/')
    expect(data.storages).toEqual(['存储'])
    expect(data.files.map(file => file.path)).toEqual(['/合同', '/自己的.txt', '/别人的.txt', '/普通.txt'])
  })

  it('没列过的路径按名称逐级下钻', async () => {
    const fake = fakeGateway(tree(), { '31': 'EDITOR' })
    const data = await driverOf(fake).list({ path: '存储://合同/深层' })
    // 根 → 合同 → 深层，最后一次才是目标目录自己的列表
    expect(fake.named('list').map(call => call[1])).toEqual([0, '3', '31'])
    expect(data.files.map(file => file.path)).toEqual(['/合同/深层/最里.txt'])
  })

  it('根目录是否只读取自 getRootRole；子目录取自口子返回的角色', async () => {
    const viewer = fakeGateway(tree())
    expect((await driverOf(viewer, 'VIEWER').list({ path: '/' })).read_only).toBe(true)
    expect(viewer.named('get')).toEqual([])
    const editor = fakeGateway(tree(), { '3': 'VIEWER' })
    const driver = driverOf(editor, 'EDITOR')
    expect((await driver.list({ path: '/' })).read_only).toBe(false)
    expect((await driver.list({ path: '/合同' })).read_only).toBe(true)
    expect(editor.named('get')).toEqual([['get', '3']])
    expect(driver.peekRole('/合同')).toBe('VIEWER')
  })

  it('改名、移动、复制、删除、新建、取内容都经口子，参数是节点编号', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    await driver.list({ path: '/' })
    await driver.rename({ path: '/', item: '/普通.txt', name: '改.txt' })
    await driver.move({ path: '/', sources: ['/自己的.txt'], destination: '/合同' })
    await driver.copy({ path: '/', sources: ['/别人的.txt'], destination: '/合同' })
    await driver.delete({ path: '/', items: [{ path: '/别人的.txt', type: 'file' }] })
    await driver.createFolder({ path: '/合同', name: '新夹' })
    const content = await driver.getContent({ path: '/别人的.txt' })
    expect(fake.named('rename')).toEqual([['rename', '6', '改.txt']])
    expect(fake.named('move')).toEqual([['move', '4', '3']])
    expect(fake.named('copy')).toEqual([['copy', '5', '3']])
    expect(fake.named('trash')).toEqual([['trash', ['5']]])
    expect(fake.named('createFolder')).toEqual([['createFolder', '3', '新夹']])
    expect(fake.named('content')).toEqual([['content', '5', true]])
    expect(content.content).toBe('正文')
  })

  it('预览与下载地址取自口子', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    await driver.list({ path: '/' })
    expect(driver.getPreviewUrl({ path: '/普通.txt' })).toBe('url:6:true')
    expect(driver.getDownloadUrl({ path: '/普通.txt' })).toBe('url:6:false')
  })

  it('搜索结果的父目录用口子的 path 还原成名称路径', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    const found = await driver.search({ path: '/', filter: ' 搜 ', deep: true })
    expect(fake.named('search')).toEqual([['search', '搜', 100]])
    expect(fake.named('path')).toEqual([['path', '31']])
    expect(found.map(file => file.path)).toEqual(['/合同/深层/搜到.txt'])
    expect(driver.peekId('/合同/深层/搜到.txt')).toBe('41')
  })

  it('上传地址与表单字段取自口子', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    await driver.list({ path: '/' })
    const handlers: Record<string, () => void> = {}
    const metas: Array<[string, Record<string, string>]> = []
    const uppy = {
      use: vi.fn(),
      on: (event: string, handler: () => void) => (handlers[event] = handler),
      getFiles: () => [{ id: 'f1' }, { id: 'f2' }],
      setFileMeta: (id: string, meta: Record<string, string>) => metas.push([id, meta])
    }
    driver.configureUploader?.(uppy as never, { getTargetPath: () => '存储://合同' } as never)
    expect(uppy.use).toHaveBeenCalledWith(
      uploader.plugin,
      expect.objectContaining({
        endpoint: '/api/x/upload',
        fieldName: 'file',
        headers: { Authorization: 'Bearer tk-1' }
      })
    )
    handlers.upload()
    expect(metas).toEqual([
      ['f1', { token: 'k', parentId: '3' }],
      ['f2', { token: 'k', parentId: '3' }]
    ])
  })

  it('口子的上传前检查抛出：这一批不写元数据、原样抛出那句话（上传框显示它，请求不发）', async () => {
    const fake = fakeGateway(tree())
    const fields = vi.fn(() => ({}))
    const check = () => {
      throw new Error('你只能查看')
    }
    const driver = driverOf({ ...fake, gateway: { ...fake.gateway, upload: { endpoint: '/x/upload', fields, check } } })
    await driver.list({ path: '/' })
    const handlers: Record<string, () => void> = {}
    const uppy = {
      use: vi.fn(),
      on: (event: string, handler: () => void) => (handlers[event] = handler),
      getFiles: () => [{ id: 'f1' }],
      setFileMeta: vi.fn()
    }
    driver.configureUploader?.(uppy as never, { getTargetPath: () => '存储://合同' } as never)
    expect(() => handlers.upload()).toThrow('你只能查看')
    expect(fields).not.toHaveBeenCalled()
    expect(uppy.setFileMeta).not.toHaveBeenCalled()
  })
})

describe('逐项只读', () => {
  it('modifiable 为 false 的节点标成只读；为 true 或没有这个字段的不标', async () => {
    const fake = fakeGateway(tree())
    const data = await driverOf(fake).list({ path: '/' })
    const flags = Object.fromEntries(data.files.map(file => [file.basename, file.read_only]))
    expect(flags).toEqual({ 合同: false, '自己的.txt': false, '别人的.txt': true, '普通.txt': false })
  })

  it('peekModifiable 取已缓存节点的 modifiable；没列出过或口子没给时是 undefined', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    expect(driver.peekModifiable('/别人的.txt')).toBeUndefined()
    await driver.list({ path: '/' })
    expect(driver.peekModifiable('存储://别人的.txt')).toBe(false)
    expect(driver.peekModifiable('/自己的.txt')).toBe(true)
    expect(driver.peekModifiable('/普通.txt')).toBeUndefined()
  })

  it('搜索结果同样带只读标记并可 peek', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    const found = await driver.search({ path: '/', filter: '搜', deep: true })
    expect(found[0].read_only).toBe(true)
    expect(driver.peekModifiable(found[0].path)).toBe(false)
  })
})

describe('批量移动', () => {
  it('口子能先拦时整批先查：有一个不能动就一个都不移', async () => {
    const fake = fakeGateway(tree())
    const checked: DriveId[][] = []
    fake.gateway.checkModifiable = ids => {
      checked.push(ids)
      if (ids.includes('5')) throw new Error('不能动')
    }
    const driver = driverOf(fake)
    await driver.list({ path: '/' })
    await expect(
      driver.move({ path: '/', sources: ['/自己的.txt', '/别人的.txt'], destination: '/合同' })
    ).rejects.toThrow('不能动')
    expect(checked).toEqual([['4', '5']])
    expect(fake.named('move')).toEqual([])
  })

  it('普通网盘口子没有先拦：逐个移动，与原来相同', async () => {
    const fake = fakeGateway(tree())
    const driver = driverOf(fake)
    await driver.list({ path: '/' })
    await driver.move({ path: '/', sources: ['/自己的.txt', '/普通.txt'], destination: '/合同' })
    expect(fake.named('move')).toEqual([
      ['move', '4', '3'],
      ['move', '6', '3']
    ])
  })
})
