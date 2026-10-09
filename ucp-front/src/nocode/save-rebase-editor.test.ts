// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, provide, type App, type Component, type ComponentPublicInstance } from 'vue'
import { message } from 'ant-design-vue'
import RecordEditor from '@/views/nocode/application/components/RecordEditor.vue'
import type { Aggregate, RecordModel, SaveRecord } from '@/types/nocode/runtime'
import type { ObjectDetail } from '@/types/nocode/data-center'
import { generatedBinding, newDesign } from './data-center'
import { newField } from './object-draft'
import { pendingDocumentKey, storePendingDocument } from './document-save'
import { taskEntrySessionKey } from './task-entry-context'

const api = vi.hoisted(() => ({
  submit: vi.fn(),
  submitReceipt: vi.fn(),
  get: vi.fn(),
  relatedForm: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol('save-rebase-editor'),
  useNocodePlatform: () => ({ runtime: api })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'rebase' } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn(), confirmDiscard: async () => true }))
vi.mock('@/views/nocode/application/components/RecordForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({ default: { render: () => null } }))

const apps: Array<{ app: App; host: HTMLElement }> = []
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
interface EditorState {
  state: Aggregate
  pending: SaveRecord | null
  busy: boolean
  error: string
  mainForm: { validate: () => Promise<void> }
  save: (actionCode?: string) => Promise<void>
}
const emitted = { saved: [] as Aggregate[], cancel: 0 }
async function setup(props: Record<string, unknown>, { taskEntry = false } = {}) {
  let instance: ComponentPublicInstance | null = null
  const target = { ...(RecordEditor as Component), render: () => null },
    host = document.createElement('div')
  const app = createApp({
    setup() {
      if (taskEntry)
        provide(taskEntrySessionKey, {
          key: 'entry',
          saveDraft: async () => ({}) as never,
          loadDraft: async () => null,
          checkDraft: async () => null
        })
      return () =>
        h(target, {
          applicationId: 'app',
          model,
          // 宿主声明：record 就是刚从服务端取来的这条记录
          mergeOnConflict: true,
          ...props,
          onSaved: (value: Aggregate) => emitted.saved.push(value),
          onCancel: () => emitted.cancel++,
          ref: (value: Element | ComponentPublicInstance | null) => {
            if (value && '$' in value) instance = value
          }
        })
    }
  })
  document.body.append(host)
  app.mount(host)
  apps.push({ app, host })
  await flush()
  if (!instance) throw new Error('组件未挂载')
  const state = (instance as ComponentPublicInstance & { $: { setupState: EditorState } }).$.setupState
  state.mainForm = { validate: async () => undefined }
  return state
}

const fields = ['summary', 'amount'].map((id, index) => ({ ...newField(index, id), id, key: id, code: id }))
const detail: ObjectDetail = {
  id: 'd',
  code: 'detail',
  name: '明细',
  tableName: 'biz_detail',
  state: 'ACTIVE',
  fields: [{ ...newField(0, '品名'), id: 'item', key: 'item', code: 'item' }],
  fieldOptions: {},
  indexes: []
}
const model: RecordModel = {
  writable: true,
  generatedKey: true,
  keyFieldId: null,
  keyType: 'UUID',
  permissions: {
    actions: ['CREATE', 'UPDATE', 'READ'],
    readFields: ['summary', 'amount'],
    writeFields: ['summary', 'amount'],
    readDetails: ['d'],
    writeDetails: ['d'],
    readRelations: [],
    writeRelations: []
  },
  details: { d: { writable: true, generatedKey: true, keyFieldId: null, keyType: 'UUID' } },
  object: {
    objectId: 'object',
    objectCode: 'object',
    objectName: '对象',
    schemaName: 'public',
    tableName: 'biz_object',
    source: 'GENERATED',
    readOnly: false,
    titleFieldId: 'summary',
    settings: newDesign().settings,
    fields,
    fieldOptions: {},
    relations: [],
    details: [detail],
    mainBinding: generatedBinding('public')
  }
}
const record = (values: Record<string, unknown>, revision = '1', lines: Aggregate['details']['d'] = []): Aggregate => ({
  record: { id: 'r1', revision, values },
  details: { d: lines }
})
const opened = () => record({ summary: '旧摘要', amount: 100 })
const conflict = () => Object.assign(new Error('记录已被修改，请刷新后重试'), { businessCode: 1_050_000_004 })
const missing = () => Object.assign(new Error('记录不存在或不可访问'), { businessCode: 1_050_000_002 })
const invalid = () => Object.assign(new Error('金额不能为负'), { businessCode: 1_050_000_001 })
const effective = (value: Aggregate) => ({ outcome: 'EFFECTIVE', result: value })
const storageKey = pendingDocumentKey('rebase', 'app', 'object', 'r1')
const stored = () => JSON.parse(sessionStorage.getItem(storageKey) || 'null') as SaveRecord | null
const info = vi.spyOn(message, 'info').mockImplementation(() => (() => undefined) as never)
const success = vi.spyOn(message, 'success').mockImplementation(() => (() => undefined) as never)

beforeEach(() => {
  api.submit.mockReset()
  api.get.mockReset()
  // 默认取得到最新记录：不该进入自动重试的情形若进入了，红在「没有去取最新」那句断言上
  api.get.mockResolvedValue(record({ summary: '旧摘要', amount: 100 }, '9'))
  api.submitReceipt.mockReset()
  info.mockClear()
  success.mockClear()
  emitted.saved.length = 0
  emitted.cancel = 0
  sessionStorage.clear()
})
afterEach(() => {
  apps.splice(0).forEach(({ app, host }) => {
    app.unmount()
    host.remove()
  })
  sessionStorage.clear()
})

describe('保存遇到「记录已被修改」：以最新内容为底再存一次', () => {
  it('P1 P2 我改了摘要、对方改了金额：自动取最新再存，成功；提示一句「对方改的其他内容已保留」', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.summary = '我的摘要'
    const saved = record({ summary: '我的摘要', amount: 250 }, '3')
    api.submit.mockRejectedValueOnce(conflict()).mockResolvedValueOnce(effective(saved))
    api.get.mockResolvedValue(record({ summary: '旧摘要', amount: 250 }, '2'))
    const errors: string[] = []
    const saving = editor.save()
    // 整个过程里没有出现过错误提示
    for (let step = 0; step < 12; step++) {
      await Promise.resolve()
      errors.push(editor.error)
    }
    await saving
    errors.push(editor.error)
    expect(errors.filter(Boolean)).toEqual([])

    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.get).toHaveBeenCalledWith('app', 'object', 'r1', { quiet: true })
    expect(api.submit).toHaveBeenCalledTimes(2)
    const [first] = api.submit.mock.calls[0] as [SaveRecord],
      [second] = api.submit.mock.calls[1] as [SaveRecord]
    expect(first.expectedRevision).toBe('1')
    expect(second.expectedRevision).toBe('2')
    expect(second.requestKey).toBeTruthy()
    expect(second.requestKey).not.toBe(first.requestKey)
    expect(second.values).toEqual({ summary: '我的摘要', amount: 250 })
    // 我没动过明细：重试时不提交这个分组
    expect(first.details).toEqual({ d: [] })
    expect(second.details).toEqual({})

    expect(emitted.saved).toEqual([saved])
    expect(success).toHaveBeenCalledWith('整单已保存')
    expect(info).toHaveBeenCalledTimes(1)
    expect(info).toHaveBeenCalledWith('这条记录刚被别人改过，对方改的其他内容已保留。')
    // 用户的输入原样留在界面上
    expect(editor.state.record.values.summary).toBe('我的摘要')
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
    expect(editor.busy).toBe(false)
  })

  it('P2 只有系统算的列变了（修订号变了、可提交的字段没变）：成功，但不提示', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.summary = '我的摘要'
    api.submit
      .mockRejectedValueOnce(conflict())
      .mockResolvedValueOnce(effective(record({ summary: '我的摘要', amount: 100 }, '3')))
    api.get.mockResolvedValue(record({ summary: '旧摘要', amount: 100, balance: 700 }, '2'))
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(2)
    expect(emitted.saved).toHaveLength(1)
    expect(info).not.toHaveBeenCalled()
  })

  it('P3 取最新时发现记录已被别人删除：提示无法保存，不再提交', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.summary = '我的摘要'
    api.submit.mockRejectedValueOnce(conflict())
    api.get.mockRejectedValue(missing())
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(editor.error).toBe('这条记录已被别人删除，无法保存')
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
    expect(emitted.saved).toEqual([])
    expect(editor.state.record.values.summary).toBe('我的摘要')
    expect(editor.busy).toBe(false)
  })

  it('取最新因别的原因失败：按原来的冲突提示', async () => {
    const editor = await setup({ record: opened() })
    api.submit.mockRejectedValueOnce(conflict())
    api.get.mockRejectedValue(new Error('网络错误'))
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
  })

  it('P4 连续冲突：一共提交 3 次后停，提示原文', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.summary = '我的摘要'
    api.submit.mockRejectedValue(conflict())
    let revision = 1
    api.get.mockImplementation(async () => record({ summary: '旧摘要', amount: 100 + revision }, String(++revision)))
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(3)
    expect(api.get).toHaveBeenCalledTimes(2)
    // 每次都拿最新的修订号、换新的请求标识
    const sent = api.submit.mock.calls.map(([command]) => command as SaveRecord)
    expect(sent.map(command => command.expectedRevision)).toEqual(['1', '2', '3'])
    expect(new Set(sent.map(command => command.requestKey)).size).toBe(3)
    // 每次都是拿原来的提交去套最新的内容：对方第二次改的金额没有被第一次取到的旧值盖回去
    expect(sent[2].values).toEqual({ summary: '我的摘要', amount: 102 })
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
    expect(emitted.saved).toEqual([])
    expect(info).not.toHaveBeenCalled()
    expect(editor.busy).toBe(false)
  })

  it('P5 重试那次因别的业务原因被拒：显示那个原因，输入原样保留', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.amount = -5
    api.submit.mockRejectedValueOnce(conflict()).mockRejectedValueOnce(invalid())
    api.get.mockResolvedValue(record({ summary: '对方的摘要', amount: 100 }, '2'))
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(2)
    expect(editor.error).toBe('本次未保存：金额不能为负')
    expect(editor.state.record.values).toMatchObject({ summary: '旧摘要', amount: -5 })
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
    expect(info).not.toHaveBeenCalled()
  })

  it('重试那次没有拿到明确结果（网络中断）：按那次请求去确认结果', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.summary = '我的摘要'
    api.submit.mockRejectedValueOnce(conflict()).mockRejectedValueOnce(new Error('Network Error'))
    api.get.mockResolvedValue(record({ summary: '旧摘要', amount: 250 }, '2'))
    api.submitReceipt.mockResolvedValue(null)
    await editor.save()
    const [second] = api.submit.mock.calls[1] as [SaveRecord]
    expect(api.submitReceipt).toHaveBeenCalledWith('app', 'object', second.requestKey)
    // 结果未知：保留的是重试的那次请求
    expect(editor.pending?.requestKey).toBe(second.requestKey)
    expect(stored()?.requestKey).toBe(second.requestKey)
    expect(editor.error).toBe('尚未查到提交结果。输入已保留，可再次查询或重试原请求。')
  })

  it('我改过的明细行已被别人删除：提示原文，不再提交', async () => {
    const line = { id: 'd1', revision: '1', values: { item: '甲' } }
    const editor = await setup({ record: record({ summary: '旧摘要', amount: 100 }, '1', [line]) })
    editor.state.details.d[0].values.item = '我改的品名'
    api.submit.mockRejectedValueOnce(conflict())
    api.get.mockResolvedValue(record({ summary: '旧摘要', amount: 100 }, '2', []))
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(editor.error).toBe('本次未保存：你修改的明细行已被别人删除，请刷新后重新填写明细')
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
  })

  it('P7 P8 重试期间保存按钮不可再点；待确认的请求换成重试的那次', async () => {
    const editor = await setup({ record: opened() })
    editor.state.record.values.summary = '我的摘要'
    const latest = deferred<Aggregate>(),
      second = deferred<unknown>()
    api.submit.mockRejectedValueOnce(conflict()).mockReturnValueOnce(second.promise)
    api.get.mockReturnValue(latest.promise)
    const saving = editor.save()
    await flush()
    // 正在取最新
    expect(editor.busy).toBe(true)
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    const firstKey = (api.submit.mock.calls[0][0] as SaveRecord).requestKey
    expect(stored()?.requestKey).toBe(firstKey)

    latest.resolve(record({ summary: '旧摘要', amount: 250 }, '2'))
    await flush()
    // 正在重试
    expect(editor.busy).toBe(true)
    expect(api.submit).toHaveBeenCalledTimes(2)
    const retried = api.submit.mock.calls[1][0] as SaveRecord
    expect(editor.pending?.requestKey).toBe(retried.requestKey)
    expect(stored()?.requestKey).toBe(retried.requestKey)
    expect(stored()?.expectedRevision).toBe('2')
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(2)

    second.resolve(effective(record({ summary: '我的摘要', amount: 250 }, '3')))
    await saving
    expect(editor.busy).toBe(false)
    expect(editor.pending).toBeNull()
    expect(stored()).toBeNull()
  })
})

describe('不进入自动重试的情形（保持原行为）', () => {
  it('P6 新建记录遇到冲突码', async () => {
    const editor = await setup({})
    editor.state.record.values.summary = '新建'
    api.submit.mockRejectedValueOnce(conflict())
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
  })

  it('P6 新建时带了初始内容（record 有内容但没有主键）', async () => {
    const template: Aggregate = { record: { id: null, revision: null, values: { summary: '模板摘要' } }, details: {} }
    const editor = await setup({ record: template })
    api.submit.mockRejectedValueOnce(conflict())
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
  })

  it('宿主没有声明 record 是服务端取来的记录（如「修改后重新提交」传进来的是上次申请的内容）', async () => {
    const editor = await setup({ record: opened(), mergeOnConflict: false })
    editor.state.record.values.summary = '我的摘要'
    api.submit.mockRejectedValueOnce(conflict())
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
  })

  it('P6 任务入口场景', async () => {
    const editor = await setup({ record: opened() }, { taskEntry: true })
    editor.state.record.values.summary = '我的摘要'
    api.submit.mockRejectedValueOnce(conflict())
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
  })

  it('P6 内容是从「尚未确认的保存请求」恢复出来的编辑器', async () => {
    const lingering: SaveRecord = {
      requestKey: 'lingering',
      applicationId: 'app',
      objectId: 'object',
      id: 'r1',
      expectedRevision: '1',
      values: { summary: '上次没确认的输入', amount: 100 },
      details: { d: [] },
      relations: {}
    }
    storePendingDocument(storageKey, lingering)
    const editor = (await setup({ record: opened() })) as EditorState & { sendPending: () => Promise<void> }
    expect(editor.error).toBe('发现尚未确认的保存请求，请查询结果或重试原请求')
    api.submit.mockRejectedValueOnce(conflict())
    await editor.sendPending()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(editor.error).toBe('本次未保存：记录已被修改，请刷新后重试')
  })

  it('别的业务拒绝（不是冲突）', async () => {
    const editor = await setup({ record: opened() })
    api.submit.mockRejectedValueOnce(invalid())
    await editor.save()
    expect(api.submit).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(editor.error).toBe('本次未保存：金额不能为负')
  })
})
