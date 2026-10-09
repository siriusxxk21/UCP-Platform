import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RecordFolderApi } from '@/api/nocode/record-folder'
import type {
  RecordFolderBackfillResult,
  RecordFolderBackfillTotal,
  RecordFolderCredential,
  RecordFolderEntry,
  RecordFolderTab
} from '@/types/nocode/record-folder'
import {
  RECORD_FOLDER_LOCKED_NOTE,
  RECORD_FOLDER_LOCKED_REASON,
  RECORD_FOLDER_RAISED_CLASS,
  RECORD_FOLDER_VIEW_ONLY_NOTE,
  RECORD_FOLDER_VIEW_ONLY_REASON,
  createRecordFolderGateway,
  recordFolderDialogRaiser,
  recordFolderFinderId,
  recordFolderNamePreview,
  recordFolderNoSourceCache,
  recordFolderTabView,
  runRecordFolderBackfill
} from './record-folder'

const entry = (value: Partial<RecordFolderEntry> & Pick<RecordFolderEntry, 'id' | 'name'>): RecordFolderEntry => ({
  spaceId: '900',
  parentId: 0,
  type: 'FILE',
  size: 10,
  modifiable: true,
  ...value
})

/** 假接口：记下每次调用的方法名与请求体 */
function fakeApi(result: Partial<Record<keyof RecordFolderApi, unknown>> = {}) {
  const calls: Array<[string, Record<string, unknown>, ...unknown[]]> = []
  const method = (name: keyof RecordFolderApi) =>
    vi.fn(async (query: Record<string, unknown>, ...rest: unknown[]) => {
      calls.push([name, query, ...rest])
      const value = result[name]
      return typeof value === 'function' ? (value as (query: unknown) => unknown)(query) : value
    })
  const names = ['list', 'get', 'path', 'search', 'createFolder', 'rename', 'move', 'copy', 'trash', 'content'] as const
  const api = Object.fromEntries(names.map(name => [name, method(name)])) as unknown as RecordFolderApi
  return { api, calls, named: (name: string) => calls.filter(call => call[0] === name) }
}
const auth = { apiBase: '/api', token: () => 'tk-1' }
const credential: RecordFolderCredential = { applicationId: 'app', objectId: 'obj', recordId: 'rec', sourceId: '7' }
const four = { applicationId: 'app', objectId: 'obj', recordId: 'rec', sourceId: '7' }

describe('记录文件夹的接口口子', () => {
  it('每个方法都带齐凭据四项', async () => {
    const fake = fakeApi({
      list: [],
      get: entry({ id: '9', name: 'a' }),
      path: [],
      search: [],
      createFolder: '55',
      copy: '56',
      content: new Blob()
    })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.list(0)
    await gateway.get('9')
    await gateway.path('9')
    await gateway.search('合同', 100)
    await gateway.createFolder('3', '资料')
    await gateway.rename('9', '新名')
    await gateway.move('9', '3')
    await gateway.copy('9', '3')
    await gateway.trash(['9', '10'])
    await gateway.content('9', true)
    expect(fake.calls).toEqual([
      ['list', { ...four, parentId: 0 }],
      ['get', { ...four, id: '9' }],
      ['path', { ...four, id: '9' }],
      ['search', { ...four, name: '合同', limit: 100 }],
      ['createFolder', { ...four, parentId: '3', name: '资料' }],
      ['rename', { ...four, id: '9', name: '新名' }],
      ['move', { ...four, id: '9', targetParentId: '3' }],
      ['copy', { ...four, id: '9', targetParentId: '3' }],
      ['trash', { ...four, ids: ['9', '10'] }],
      ['content', { ...four, id: '9' }, true]
    ])
    // 逐个方法单独钉一遍：少带任何一项都不行
    for (const [name, query] of fake.calls)
      for (const key of ['objectId', 'recordId', 'sourceId'] as const)
        expect(query[key], `${name} 的 ${key}`).toBe(four[key])
  })

  it('应用编号为空时不带这一项（数据维护入口），而不是带一个空串', async () => {
    for (const applicationId of [undefined, ''] as const) {
      const fake = fakeApi({ list: [], path: [] })
      const gateway = createRecordFolderGateway(fake.api, { ...credential, applicationId }, auth)
      await gateway.list(0)
      await gateway.trash(['9'])
      for (const [, query] of fake.calls) expect('applicationId' in query).toBe(false)
      expect(gateway.upload.fields(0)).toEqual({ objectId: 'obj', recordId: 'rec', sourceId: '7', parentId: '0' })
      expect(gateway.contentUrl('9', false)).not.toContain('applicationId')
    }
  })

  it('path 把名称数组拼成 /a/b；根的直接子节点（空数组）是 /', async () => {
    const names: string[][] = [['合同', '2026'], []]
    const fake = fakeApi({ path: () => names.shift() })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    expect(await gateway.path('9')).toBe('/合同/2026')
    expect(await gateway.path('3')).toBe('/')
  })

  it('根自身（编号 0）的路径就是 /，不发请求：服务端不许对根取路径', async () => {
    const fake = fakeApi({ path: ['不该被调用'] })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    expect(await gateway.path(0)).toBe('/')
    expect(await gateway.path('0')).toBe('/')
    expect(fake.named('path')).toEqual([])
  })

  it('内容地址带凭据、节点编号、是否内联与访问令牌；没有令牌时不带', () => {
    const gateway = createRecordFolderGateway(fakeApi().api, credential, auth)
    expect(gateway.contentUrl('9', true)).toBe(
      '/api/nocode/record-folder/entry/content?applicationId=app&objectId=obj&recordId=rec&sourceId=7&id=9&inline=true&token=tk-1'
    )
    expect(gateway.contentUrl('9', false)).toContain('&inline=false&')
    const anonymous = createRecordFolderGateway(fakeApi().api, credential, { apiBase: '/api', token: () => '' })
    expect(anonymous.contentUrl('9', true)).not.toContain('token')
  })

  it('上传地址与表单字段：凭据 + 父节点编号，都是字符串', () => {
    const gateway = createRecordFolderGateway(fakeApi().api, credential, auth)
    expect(gateway.upload.endpoint).toBe('/nocode/record-folder/entry/upload')
    expect(gateway.upload.fields('31')).toEqual({ ...four, parentId: '31' })
  })

  it('没有收藏与最近访问的能力', () => {
    const gateway = createRecordFolderGateway(fakeApi().api, credential, auth)
    expect(gateway.favorite).toBeUndefined()
    expect(gateway.recordAccess).toBeUndefined()
  })

  it('返回的节点带着 modifiable，空值按网盘节点的形状补齐', async () => {
    const fake = fakeApi({ list: [entry({ id: '9', name: 'a', size: null, mimeType: null, modifiable: false })] })
    const [node] = await createRecordFolderGateway(fake.api, credential, auth).list(0)
    expect(node).toMatchObject({ id: '9', name: 'a', size: 0, modifiable: false })
    expect(node.mimeType).toBeUndefined()
  })
})

describe('口子里先拦：已知不能改的节点不发请求', () => {
  const listing = [
    entry({ id: '1', name: '自己的.txt', modifiable: true }),
    entry({ id: '2', name: '别人的.txt', modifiable: false })
  ]

  it('列出过、记着不能改的节点：改名、移动、删除都不发请求，抛出那句话', async () => {
    const fake = fakeApi({ list: listing })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.list(0)
    await expect(gateway.rename('2', 'x')).rejects.toThrow(RECORD_FOLDER_LOCKED_REASON)
    await expect(gateway.move('2', '3')).rejects.toThrow(RECORD_FOLDER_LOCKED_REASON)
    await expect(gateway.trash(['2'])).rejects.toThrow(RECORD_FOLDER_LOCKED_REASON)
    expect(fake.named('rename')).toEqual([])
    expect(fake.named('move')).toEqual([])
    expect(fake.named('trash')).toEqual([])
  })

  it('批量移动之前整批查一遍：有一个不能改就抛出那句话', async () => {
    const fake = fakeApi({ list: listing })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.list(0)
    expect(() => gateway.checkModifiable?.(['1', '2'])).toThrow(RECORD_FOLDER_LOCKED_REASON)
    expect(() => gateway.checkModifiable?.(['1', '99'])).not.toThrow()
  })

  it('只读页签（canWrite 为假）：上传、新建、改名、移动、复制、删除都先拦下，用「你只能查看」那句话，一个请求都不发', async () => {
    const fake = fakeApi({ list: listing })
    const gateway = createRecordFolderGateway(fake.api, credential, auth, { canWrite: false })
    await gateway.list(0)
    expect(() => gateway.upload.check?.()).toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    expect(() => gateway.checkModifiable?.(['1'])).toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    await expect(gateway.createFolder(0, '新夹')).rejects.toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    await expect(gateway.rename('1', '改')).rejects.toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    await expect(gateway.move('1', 0)).rejects.toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    await expect(gateway.copy('1', 0)).rejects.toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    await expect(gateway.trash(['1', '99'])).rejects.toThrow(RECORD_FOLDER_VIEW_ONLY_REASON)
    expect(fake.calls.map(call => call[0])).toEqual(['list'])
  })

  it('能写的页签：上传前不拦；没给 access 时按能写', () => {
    for (const gateway of [
      createRecordFolderGateway(fakeApi().api, credential, auth, { canWrite: true }),
      createRecordFolderGateway(fakeApi().api, credential, auth)
    ])
      expect(() => gateway.upload.check?.()).not.toThrow()
  })

  it('一批里只要有一个不能改，整批不发', async () => {
    const fake = fakeApi({ list: listing })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.list(0)
    await expect(gateway.trash(['1', '2'])).rejects.toThrow(RECORD_FOLDER_LOCKED_REASON)
    expect(fake.named('trash')).toEqual([])
  })

  it('能改的、以及没列出过的节点照常发请求（由服务端判）', async () => {
    const fake = fakeApi({ list: listing })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.list(0)
    await gateway.rename('1', 'x')
    await gateway.move('1', '3')
    await gateway.trash(['1', '99'])
    expect(fake.named('rename')).toHaveLength(1)
    expect(fake.named('move')).toHaveLength(1)
    expect(fake.named('trash')).toEqual([['trash', { ...four, ids: ['1', '99'] }]])
  })

  it('搜索与详情的结果同样记下', async () => {
    const fake = fakeApi({
      search: [entry({ id: '5', name: '搜到的.txt', modifiable: false })],
      get: entry({ id: '6', name: '详情.txt', modifiable: false })
    })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.search('搜', 100)
    await gateway.get('6')
    await expect(gateway.trash(['5'])).rejects.toThrow(RECORD_FOLDER_LOCKED_REASON)
    await expect(gateway.rename('6', 'x')).rejects.toThrow(RECORD_FOLDER_LOCKED_REASON)
  })

  it('自己新建、复制出来的节点记为可改：即使之前同一个编号记着不能改', async () => {
    const fake = fakeApi({
      list: [entry({ id: '55', name: '旧', modifiable: false }), entry({ id: '56', name: '旧2', modifiable: false })],
      createFolder: '55',
      copy: '56'
    })
    const gateway = createRecordFolderGateway(fake.api, credential, auth)
    await gateway.list(0)
    expect(await gateway.createFolder(0, '我的夹')).toBe('55')
    await gateway.copy('1', 0)
    await gateway.rename('55', '改名')
    await gateway.trash(['55', '56'])
    expect(fake.named('rename')).toHaveLength(1)
    expect(fake.named('trash')).toHaveLength(1)
  })

  it('两句只读文案', () => {
    expect(RECORD_FOLDER_LOCKED_NOTE).toBe('只读：不是经由这条记录放进去的')
    expect(RECORD_FOLDER_LOCKED_REASON).toBe('这个文件不是经由这条记录放进去的，只能查看和下载，不能改名、移动或删除。')
  })
})

describe('页签该显示什么', () => {
  const tab = (value: Partial<RecordFolderTab>): RecordFolderTab => ({
    sourceId: '7',
    label: '合同文件',
    state: 'READY',
    writable: true,
    ...value,
    canWrite: value.canWrite ?? value.writable ?? true
  })
  const origin = { lockedNote: RECORD_FOLDER_LOCKED_NOTE, lockedReason: RECORD_FOLDER_LOCKED_REASON }
  const viewOnly = { lockedNote: RECORD_FOLDER_VIEW_ONLY_NOTE, lockedReason: RECORD_FOLDER_VIEW_ONLY_REASON }

  it('五种情形；能不能写与锁说明跟着 canWrite 走', () => {
    expect(recordFolderTabView(tab({ state: 'READY', writable: true }))).toEqual({
      kind: 'browser',
      rootRole: 'EDITOR',
      canWrite: true,
      ...origin
    })
    expect(recordFolderTabView(tab({ state: 'READY', writable: false }))).toEqual({
      kind: 'browser',
      rootRole: 'VIEWER',
      canWrite: false,
      ...viewOnly
    })
    expect(recordFolderTabView(tab({ state: 'PENDING', writable: true }))).toEqual({
      kind: 'browser',
      rootRole: 'EDITOR',
      canWrite: true,
      ...origin
    })
    expect(recordFolderTabView(tab({ state: 'PENDING', writable: false }))).toEqual({
      kind: 'empty',
      rootRole: 'VIEWER',
      text: '还没有文件',
      canWrite: false,
      ...viewOnly
    })
    expect(
      recordFolderTabView(tab({ state: 'RELATION_EMPTY', writable: false, message: '请先选择「所属合同」' }))
    ).toEqual({ kind: 'notice', rootRole: 'VIEWER', text: '请先选择「所属合同」', canWrite: false, ...viewOnly })
    expect(
      recordFolderTabView(tab({ state: 'UNAVAILABLE', writable: false, message: '文件夹已被删除' }))
    ).toMatchObject({ kind: 'notice', text: '文件夹已被删除' })
  })

  it('不能改记录、但本人在文件夹上有网盘编辑权限：能写，用「不是经由这条记录」那套说明', () => {
    expect(recordFolderTabView(tab({ state: 'READY', writable: false, canWrite: true }))).toEqual({
      kind: 'browser',
      rootRole: 'EDITOR',
      canWrite: true,
      ...origin
    })
  })

  it('两套锁说明的文案', () => {
    expect(RECORD_FOLDER_VIEW_ONLY_NOTE).toBe('只读：你只能查看这条记录的文件')
    expect(RECORD_FOLDER_VIEW_ONLY_REASON).toBe('你只能查看和下载这条记录的文件，不能上传、新建、改名、移动或删除。')
  })

  it('浏览器标识按对象与来源', () => {
    expect(recordFolderFinderId(credential)).toBe('record-folder-obj-7')
  })
})

describe('「没有任何文件夹」的短时记忆', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-03T00:00:00Z'))
    recordFolderNoSourceCache().clear('obj')
  })
  afterEach(() => vi.useRealTimers())

  it('记下之后 5 分钟内有效，之后过期', () => {
    const cache = recordFolderNoSourceCache()
    expect(cache.has('obj', 'app')).toBe(false)
    cache.mark('obj', 'app')
    expect(cache.has('obj', 'app')).toBe(true)
    vi.advanceTimersByTime(5 * 60 * 1000 - 1)
    expect(cache.has('obj', 'app')).toBe(true)
    vi.advanceTimersByTime(1)
    expect(cache.has('obj', 'app')).toBe(false)
  })

  it('配置保存后按对象整体清除；别的对象不受影响', () => {
    const cache = recordFolderNoSourceCache()
    cache.mark('obj', 'app')
    cache.mark('obj')
    cache.mark('other', 'app')
    cache.clear('obj')
    expect(cache.has('obj', 'app')).toBe(false)
    expect(cache.has('obj')).toBe(false)
    expect(cache.has('other', 'app')).toBe(true)
    cache.clear('other')
  })

  it('按「对象 + 应用」记：同一个对象在另一个应用里要重新问', () => {
    const cache = recordFolderNoSourceCache()
    cache.mark('obj', 'app')
    expect(cache.has('obj', 'other-app')).toBe(false)
    expect(cache.has('obj')).toBe(false)
  })

  it('各处拿到的是同一份记忆', () => {
    recordFolderNoSourceCache().mark('obj', 'app')
    expect(recordFolderNoSourceCache().has('obj', 'app')).toBe(true)
  })
})

describe('为已有记录补建', () => {
  const page = (value: Partial<RecordFolderBackfillResult>): RecordFolderBackfillResult => ({
    cursor: null,
    done: false,
    scanned: 0,
    created: 0,
    existing: 0,
    skipped: 0,
    failed: 0,
    failures: [],
    ...value
  })

  it('一页一页串行调，游标逐页往下传，直到 done；数字累计', async () => {
    const pages = [
      page({ cursor: 'c100', scanned: 100, created: 90, existing: 10 }),
      page({ cursor: 'c200', scanned: 100, created: 80, existing: 15, skipped: 5 }),
      page({ cursor: 'c250', scanned: 50, created: 50, done: true })
    ]
    const backfill = vi.fn(async (_query: object) => pages.shift() as RecordFolderBackfillResult)
    const progress: RecordFolderBackfillTotal[] = []
    const total = await runRecordFolderBackfill({ backfill }, 'obj', '7', value => progress.push(value)).done
    expect(backfill.mock.calls.map(call => call[0])).toEqual([
      { objectId: 'obj', sourceId: '7', cursor: null, limit: 100 },
      { objectId: 'obj', sourceId: '7', cursor: 'c100', limit: 100 },
      { objectId: 'obj', sourceId: '7', cursor: 'c200', limit: 100 }
    ])
    expect(total).toMatchObject({ scanned: 250, created: 220, existing: 25, skipped: 5, failed: 0, done: true })
    expect(total.stopped).toBe(false)
    expect(progress.map(value => value.scanned)).toEqual([100, 200, 250])
    expect(progress.at(-1)?.done).toBe(true)
  })

  it('done 为真之后不再请求', async () => {
    const backfill = vi.fn(async () => page({ cursor: 'c1', scanned: 3, existing: 3, done: true }))
    await runRecordFolderBackfill({ backfill }, 'obj', '7', () => undefined).done
    expect(backfill).toHaveBeenCalledTimes(1)
  })

  it('stop() 之后跑完当前这一页就停，已处理的照常计入', async () => {
    let release!: (value: RecordFolderBackfillResult) => void
    const backfill = vi
      .fn<(query: object) => Promise<RecordFolderBackfillResult>>()
      .mockResolvedValueOnce(page({ cursor: 'c100', scanned: 100, created: 100 }))
      .mockImplementationOnce(() => new Promise(resolve => (release = resolve)))
      .mockResolvedValue(page({ cursor: 'c300', scanned: 100, created: 100 }))
    const run = runRecordFolderBackfill({ backfill }, 'obj', '7', () => undefined)
    await vi.waitFor(() => expect(backfill).toHaveBeenCalledTimes(2))
    run.stop()
    release(page({ cursor: 'c200', scanned: 100, created: 60, failed: 40 }))
    const total = await run.done
    expect(backfill).toHaveBeenCalledTimes(2)
    expect(total).toMatchObject({ scanned: 200, created: 160, failed: 40, done: false, stopped: true })
  })

  it('请求失败：done 以该错误拒绝，之前累计的数字已经通过进度给出', async () => {
    const failure = new Error('没有此数据对象的管理权限')
    const backfill = vi
      .fn<(query: object) => Promise<RecordFolderBackfillResult>>()
      .mockResolvedValueOnce(page({ cursor: 'c100', scanned: 100, created: 70, existing: 30 }))
      .mockRejectedValueOnce(failure)
    const progress: RecordFolderBackfillTotal[] = []
    await expect(runRecordFolderBackfill({ backfill }, 'obj', '7', value => progress.push(value)).done).rejects.toBe(
      failure
    )
    expect(progress).toHaveLength(1)
    expect(progress[0]).toMatchObject({ scanned: 100, created: 70, existing: 30, done: false, stopped: false })
  })

  it('失败明细最多留 20 条', async () => {
    const failures = (from: number, count: number) =>
      Array.from({ length: count }, (_value, index) => ({ recordId: String(from + index), message: '网盘不可用' }))
    const pages = [
      page({ cursor: 'c1', scanned: 100, failed: 15, failures: failures(0, 15) }),
      page({ cursor: 'c2', scanned: 100, failed: 15, failures: failures(100, 15), done: true })
    ]
    const backfill = vi.fn(async (_query: object) => pages.shift() as RecordFolderBackfillResult)
    const total = await runRecordFolderBackfill({ backfill }, 'obj', '7', () => undefined).done
    expect(total.failed).toBe(30)
    expect(total.failures).toHaveLength(20)
    expect(total.failures.at(-1)?.recordId).toBe('104')
  })

  it('没扫完却没有往前走：停下并报错，不原地打转', async () => {
    const backfill = vi.fn(async () => page({ cursor: 'c1', scanned: 100, created: 100 }))
    await expect(runRecordFolderBackfill({ backfill }, 'obj', '7', () => undefined).done).rejects.toThrow(
      '补建没有继续往下处理'
    )
    expect(backfill).toHaveBeenCalledTimes(2)
  })
})

describe('子文件夹名称的示例', () => {
  const fields = [
    { fieldId: 'f1', name: '合同编号', type: 'AUTO_NUMBER' },
    { fieldId: 'f2', name: '合同名称', type: 'TEXT' }
  ]

  it('没有模板：记录名称', () => {
    expect(recordFolderNamePreview(null, fields)).toBe('记录名称')
    expect(recordFolderNamePreview(undefined, fields)).toBe('记录名称')
  })

  it('字段段显示字段名，固定文字段显示文字，用分隔符连起来', () => {
    expect(
      recordFolderNamePreview(
        {
          separator: '-',
          parts: [
            { kind: 'FIELD', fieldId: 'f1' },
            { kind: 'FIELD', fieldId: 'f2' },
            { kind: 'TEXT', text: '资料' }
          ]
        },
        fields
      )
    ).toBe('合同编号-合同名称-资料')
  })

  it('不分隔与别的分隔符', () => {
    const parts = [
      { kind: 'FIELD' as const, fieldId: 'f1' },
      { kind: 'FIELD' as const, fieldId: 'f2' }
    ]
    expect(recordFolderNamePreview({ separator: '', parts }, fields)).toBe('合同编号合同名称')
    expect(recordFolderNamePreview({ separator: ' · ', parts }, fields)).toBe('合同编号 · 合同名称')
  })

  it('字段已不在可选字段里：该段显示（字段已删除）', () => {
    expect(
      recordFolderNamePreview(
        {
          separator: '_',
          parts: [
            { kind: 'FIELD', fieldId: 'gone' },
            { kind: 'FIELD', fieldId: 'f2' }
          ]
        },
        fields
      )
    ).toBe('（字段已删除）_合同名称')
  })
})

describe('嵌在抽屉里时抬高组件对话框的开关', () => {
  const body = () => {
    const names = new Set<string>()
    const classList = {
      toggle(name: string, on: boolean) {
        if (on) names.add(name)
        else names.delete(name)
        return on
      },
      contains: (name: string) => names.has(name)
    } as unknown as DOMTokenList
    return { classList }
  }

  it('打开才挂类，关掉就摘；重复打开 / 关闭不重复计数', () => {
    const target = body()
    const raiser = recordFolderDialogRaiser(target)
    expect(target.classList.contains(RECORD_FOLDER_RAISED_CLASS)).toBe(false)
    raiser.set(true)
    raiser.set(true)
    expect(target.classList.contains(RECORD_FOLDER_RAISED_CLASS)).toBe(true)
    raiser.set(false)
    expect(target.classList.contains(RECORD_FOLDER_RAISED_CLASS)).toBe(false)
    raiser.set(false)
    expect(target.classList.contains(RECORD_FOLDER_RAISED_CLASS)).toBe(false)
  })

  it('两块面板：一块关掉时另一块还开着，类不摘', () => {
    const target = body()
    const a = recordFolderDialogRaiser(target),
      b = recordFolderDialogRaiser(target)
    a.set(true)
    b.set(true)
    a.set(false)
    expect(target.classList.contains(RECORD_FOLDER_RAISED_CLASS)).toBe(true)
    b.set(false)
    expect(target.classList.contains(RECORD_FOLDER_RAISED_CLASS)).toBe(false)
  })
})
