// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, inject, nextTick, reactive, type App, type Component, type ComponentPublicInstance } from 'vue'
import RecordEditor from '@/views/nocode/application/components/RecordEditor.vue'
import RelatedFormEditor from '@/views/nocode/application/components/RelatedFormEditor.vue'
import type {
  Aggregate,
  BusinessRow,
  RecordModel,
  RelatedFormResult,
  RelatedFormRow,
  SaveRecord
} from '@/types/nocode/runtime'
import type { ObjectDetail } from '@/types/nocode/data-center'
import type { FormConfig, RelatedFormBinding } from '@/types/nocode/application-ui'
import { generatedBinding, newDesign } from './data-center'
import { newField } from './object-draft'
import { pendingDocumentKey, storePendingDocument } from './document-save'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import { taskFormAccessKey, type TaskFormAccess } from './task-form-access'

const api = vi.hoisted(() => ({
  submit: vi.fn(),
  submitReceipt: vi.fn(),
  relatedForm: vi.fn(),
  relatedSelection: vi.fn(),
  relatedFill: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol('runtime-editor-boundary'),
  useNocodePlatform: () => ({ runtime: api })
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'boundary' } }) }))
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
  const resolve = vi.fn<(value: T) => void>(),
    reject = vi.fn<(reason: unknown) => void>()
  const promise = new Promise<T>((yes, no) => {
    resolve.mockImplementation(yes)
    reject.mockImplementation(no)
  })
  return { promise, resolve, reject }
}
async function setup<T>(
  component: Component,
  props: Record<string, unknown>,
  probe?: () => void,
  taskAccess?: TaskFormAccess
) {
  let instance: ComponentPublicInstance | null = null
  const child = {
    setup() {
      probe?.()
      return () => null
    }
  }
  const target = { ...component, render: () => h(child) },
    host = document.createElement('div')
  const app = createApp(() =>
    h(target, {
      ...props,
      ref: (value: Element | ComponentPublicInstance | null) => {
        if (value && '$' in value) instance = value
      }
    })
  )
  if (taskAccess) app.provide(taskFormAccessKey, taskAccess)
  document.body.append(host)
  app.mount(host)
  apps.push({ app, host })
  await flush()
  if (!instance) throw new Error('组件未挂载')
  return (instance as ComponentPublicInstance & { $: { setupState: T } }).$.setupState
}
const row = (id: string | null, revision: string | null = '1'): BusinessRow => ({
  id,
  revision,
  values: { name: id || '新增' }
})
const aggregate = (id: string, revision: string | null = '1'): Aggregate => ({ record: row(id, revision), details: {} })
function fixture() {
  const field = { ...newField(0, '名称'), id: 'name', key: 'name', code: 'name' }
  const detail: ObjectDetail = {
    id: 'd',
    code: 'detail',
    name: '明细',
    tableName: 'biz_detail',
    state: 'ACTIVE',
    fields: [field],
    fieldOptions: {},
    indexes: []
  }
  const design = newDesign()
  const model: RecordModel = {
    writable: true,
    generatedKey: true,
    keyFieldId: null,
    keyType: 'UUID',
    permissions: {
      actions: ['CREATE', 'UPDATE', 'READ'],
      readFields: ['name'],
      writeFields: ['name'],
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
      titleFieldId: 'name',
      settings: design.settings,
      fields: [field],
      fieldOptions: {},
      relations: [],
      details: [detail],
      mainBinding: generatedBinding('public')
    }
  }
  const form: FormConfig = { objectId: 'object', nodes: [], detailIds: ['d'], detailNodes: { d: [] } }
  const binding: RelatedFormBinding = {
    id: 'binding',
    sourceObjectId: 'object',
    title: '关联',
    relationId: 'relation',
    direction: 'INCOMING',
    formId: 'target-form'
  }
  const related: RelatedFormResult = {
    model,
    form,
    records: [],
    multiple: true,
    linkFieldId: 'parent',
    required: false,
    truncated: false
  }
  return { model, detail, binding, related }
}
interface MainState {
  generation: number
  mainForm: { validate: () => Promise<void> }
  state: Aggregate
  pending: SaveRecord | null
  busy: boolean
  error: string
  moveDetail: (d: ObjectDetail, index: number, offset: number) => void
  save: () => Promise<void>
  sendPending: () => Promise<void>
  confirmResult: () => Promise<void>
}
interface EditRow {
  key: string
  aggregate: Aggregate
  added: boolean
  removed: boolean
  original: string
}
interface RelatedState {
  ready: boolean
  add: () => void
  select: (record: Aggregate) => void
  rows: EditRow[]
  candidates: Aggregate[]
  error: string
  loading: boolean
  load: () => Promise<void>
  find: () => Promise<void>
  openPicker: () => Promise<void>
  search: string
  removeDetail: (row: EditRow, id: string, index: number) => void
  payload: () => Promise<RelatedFormRow[]>
  editors: Array<{ validate: () => Promise<void>; validateUploads: () => void }>
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  api.relatedForm.mockResolvedValue(fixture().related)
})
afterEach(() => {
  apps.splice(0).forEach(({ app, host }) => {
    app.unmount()
    host.remove()
  })
  sessionStorage.clear()
})
describe('整单与关联表单的有效类型和请求会话', () => {
  it('宿主替换等价context和lockedValues不会清空输入或打断保存', async () => {
    const f = fixture(),
      gate = deferred<void>()
    const props = reactive({
      applicationId: 'app',
      model: f.model,
      context: { pageId: 'page', nodeId: 'node', recordId: 'A' },
      lockedValues: { parent: 'A', category: 'fixed' }
    })
    const state = await setup<MainState>(RecordEditor, props)
    state.state.record.values.name = '继续填写'
    const generation = state.generation
    state.mainForm = { validate: () => gate.promise }
    const saving = state.save()
    props.context = { recordId: 'A', nodeId: 'node', pageId: 'page' }
    props.lockedValues = { category: 'fixed', parent: 'A' }
    await flush()
    expect(state.generation).toBe(generation)
    expect(state.state.record.values.name).toBe('继续填写')
    expect(state.busy).toBe(true)
    api.submit.mockResolvedValueOnce({ outcome: 'EFFECTIVE', result: aggregate('created') })
    gate.resolve()
    await saving
    expect(api.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        context: props.context,
        values: expect.objectContaining({ name: '继续填写' })
      })
    )
  })
  it('锁定值的显式空值与省略字段不等价', async () => {
    const f = fixture(),
      props = reactive<{ applicationId: string; model: RecordModel; lockedValues: Record<string, unknown> }>({
        applicationId: 'app',
        model: f.model,
        lockedValues: {}
      })
    const state = await setup<MainState>(RecordEditor, props),
      generation = state.generation
    state.state.record.values.name = '输入'
    props.lockedValues = { name: null }
    await flush()
    expect(state.generation).toBe(generation + 1)
    expect(state.state.record.values.name).toBeNull()
    props.lockedValues = { name: '' }
    await flush()
    expect(state.generation).toBe(generation + 2)
    expect(state.state.record.values.name).toBe('')
  })
  it.each(['context', 'lockedValues'])('%s真实切换使旧异步校验失效并初始化新输入', async changed => {
    const f = fixture(),
      gate = deferred<void>()
    const props = reactive({
      applicationId: 'app',
      model: f.model,
      context: { pageId: 'page', nodeId: 'node', recordId: 'A' },
      lockedValues: { parent: 'A' }
    })
    const state = await setup<MainState>(RecordEditor, props)
    state.state.record.values.name = '旧输入'
    state.mainForm = { validate: () => gate.promise }
    const saving = state.save(),
      generation = state.generation
    if (changed === 'context') props.context = { ...props.context, recordId: 'B' }
    else props.lockedValues = { parent: 'B' }
    await flush()
    gate.resolve()
    await saving
    expect(state.generation).toBe(generation + 1)
    expect(state.state.record.values.name).not.toBe('旧输入')
    expect(state.state.record.values.parent).toBe(changed === 'context' ? 'A' : 'B')
    expect(state.busy).toBe(false)
    expect(api.submit).not.toHaveBeenCalled()
  })
  it.each(['success', 'failure'])('关联上下文切换后加载%s也不允许旧行或旧字段访问新上下文', async outcome => {
    const f = fixture(),
      gate = deferred<RelatedFormResult>()
    const relatedFieldRules = vi.fn().mockResolvedValue({ results: [] })
    api.relatedForm.mockResolvedValueOnce({ ...f.related, records: [aggregate('old')] })
    const props = reactive({
      applicationId: 'app',
      objectId: 'object',
      formId: 'form',
      binding: f.binding,
      recordId: 'A'
    })
    let provided: NocodePlatform | undefined
    const state = await setup<RelatedState>(
      RelatedFormEditor,
      props,
      () => {
        provided = inject(nocodePlatformKey)
      },
      { scope: () => 'task:task:entry', relatedFieldRules }
    )
    if (!provided) throw new Error('关联字段平台未注入')
    const runtime = provided.runtime
    expect(state.ready).toBe(true)
    api.relatedForm.mockReturnValueOnce(gate.promise)
    props.recordId = 'B'
    await flush()
    const calls = api.relatedForm.mock.calls.length
    state.add()
    state.select(aggregate('candidate'))
    await state.openPicker()
    expect(api.relatedForm.mock.calls).toHaveLength(calls)
    expect(state.rows.map(row => row.aggregate.record.id)).toEqual(['old'])
    expect(state.ready).toBe(false)
    const selection = { applicationId: 'app', objectId: 'child', fieldId: 'field', pageNo: 1, pageSize: 20 }
    const fill = {
      applicationId: 'app',
      objectId: 'child',
      formId: 'child-form',
      sourceFieldId: 'field',
      selectedId: 'selected'
    }
    await expect(runtime.selection(selection)).rejects.toThrow('上下文')
    await expect(runtime.formFill(fill)).rejects.toThrow('上下文')
    const fieldRules = { applicationId: 'app', objectId: 'child', formId: 'child-form', values: {} }
    await expect(runtime.evaluateFieldRules(fieldRules)).rejects.toThrow('上下文')
    expect(relatedFieldRules).not.toHaveBeenCalled()
    expect(api.relatedSelection).not.toHaveBeenCalled()
    expect(api.relatedFill).not.toHaveBeenCalled()
    if (outcome === 'success') gate.resolve({ ...f.related, records: [aggregate('new')] })
    else gate.reject(new Error('新关联加载失败'))
    await flush()
    if (outcome === 'success') {
      expect(state.ready).toBe(true)
      expect(state.rows.map(row => row.aggregate.record.id)).toEqual(['new'])
      await runtime.selection(selection)
      await runtime.formFill(fill)
      await runtime.evaluateFieldRules(fieldRules)
      expect(relatedFieldRules).toHaveBeenCalledWith(
        expect.objectContaining({ recordId: 'B', bindingId: f.binding.id }),
        fieldRules
      )
      expect(api.relatedSelection).toHaveBeenCalledWith(expect.objectContaining({ recordId: 'B' }), selection)
      expect(api.relatedFill).toHaveBeenCalledWith(expect.objectContaining({ recordId: 'B' }), fill)
    } else {
      expect(state.ready).toBe(false)
      expect(state.error).toContain('新关联加载失败')
      await expect(runtime.selection(selection)).rejects.toThrow('上下文')
      expect(api.relatedSelection).not.toHaveBeenCalled()
    }
  })
  it('明细非法下标不移动尾行，也不产生空行', async () => {
    const f = fixture(),
      state = await setup<MainState>(RecordEditor, { applicationId: 'app', model: f.model })
    state.state.details.d = [row('a'), row('b')]
    state.moveDetail(f.detail, -1, 1)
    state.moveDetail(f.detail, 4, -3)
    expect(state.state.details.d.map(r => r.id)).toEqual(['a', 'b'])
    state.moveDetail(f.detail, 1, -1)
    expect(state.state.details.d.map(r => r.id)).toEqual(['b', 'a'])
  })
  it('关联明细缺组或非法下标不误删末行', async () => {
    const f = fixture(),
      state = await setup<RelatedState>(RelatedFormEditor, {
        applicationId: 'app',
        objectId: 'object',
        formId: 'form',
        binding: f.binding
      })
    const entry: EditRow = {
      key: 'row',
      aggregate: { record: row('parent'), details: { d: [row('a')] } },
      added: false,
      removed: false,
      original: ''
    }
    expect(() => state.removeDetail(entry, 'missing', 0)).not.toThrow()
    state.removeDetail(entry, 'd', -1)
    expect(entry.aggregate.details.d).toHaveLength(1)
    state.removeDetail(entry, 'd', 0)
    expect(entry.aggregate.details.d).toHaveLength(0)
  })
  it('既有记录缺修订不提交，字符串0仍是合法修订', async () => {
    const f = fixture(),
      props = reactive({ applicationId: 'app', model: f.model, record: aggregate('record', null) })
    const state = await setup<MainState>(RecordEditor, props)
    await state.save()
    expect(api.submit).not.toHaveBeenCalled()
    expect(state.error).toContain('修订')
    props.record = aggregate('record', '0')
    await flush()
    api.submit.mockResolvedValueOnce({ outcome: 'EFFECTIVE', result: aggregate('record', '1') })
    await state.save()
    expect(api.submit).toHaveBeenCalledWith(expect.objectContaining({ id: 'record', expectedRevision: '0' }))
  })
  it('既有明细缺少修订不会进入整单提交', async () => {
    const f = fixture(),
      state = await setup<MainState>(RecordEditor, { applicationId: 'app', model: f.model })
    state.state.details.d = [row('existing-detail', null)]
    await state.save()
    expect(api.submit).not.toHaveBeenCalled()
    expect(state.error).toContain('明细缺少修订')
  })
  it('网络不确定后按原幂等键查询并重试同一命令，确认成功才删除原暂存', async () => {
    const f = fixture(),
      state = await setup<MainState>(RecordEditor, { applicationId: 'app', model: f.model })
    api.submit.mockRejectedValueOnce(new Error('网络中断'))
    api.submitReceipt.mockResolvedValueOnce(null)
    await state.save()
    const command = state.pending
    if (!command?.requestKey) throw new Error('没有保留原请求')
    expect(api.submitReceipt).toHaveBeenLastCalledWith('app', 'object', command.requestKey)
    expect(sessionStorage.getItem(pendingDocumentKey('boundary', 'app', 'object', null))).toContain(command.requestKey)
    api.submit.mockResolvedValueOnce({ outcome: 'EFFECTIVE', result: aggregate('created') })
    await state.sendPending()
    expect(api.submit.mock.calls[1]?.[0]).toEqual(command)
    expect(state.pending).toBeNull()
    expect(sessionStorage.getItem(pendingDocumentKey('boundary', 'app', 'object', null))).toBeNull()
  })
  it('恢复旧关联记录查询不到时保留待恢复内容，禁止转为新增', async () => {
    const f = fixture(),
      pendingRows = [{ id: 'missing', expectedRevision: '2', values: { name: '待恢复输入' } }]
    const state = await setup<RelatedState>(RelatedFormEditor, {
      applicationId: 'app',
      objectId: 'object',
      formId: 'form',
      binding: f.binding,
      pendingRows
    })
    expect(state.error).toContain('恢复')
    expect(pendingRows[0]?.values.name).toBe('待恢复输入')
    await expect(state.payload()).rejects.toThrow()
    expect(state.rows.some(r => !r.aggregate.record.id && r.aggregate.record.values.name === '待恢复输入')).toBe(false)
  })
  it.each(['success', 'failure'])('旧关联加载%s不覆盖新目标或错误', async outcome => {
    const f = fixture(),
      a = deferred<RelatedFormResult>(),
      b = deferred<RelatedFormResult>()
    api.relatedForm.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const props = reactive({
      applicationId: 'app',
      objectId: 'object',
      formId: 'form',
      binding: f.binding,
      recordId: 'A'
    })
    const state = await setup<RelatedState>(RelatedFormEditor, props)
    props.recordId = 'B'
    await flush()
    b.resolve({ ...f.related, records: [aggregate('B-related')] })
    await flush()
    if (outcome === 'success') a.resolve({ ...f.related, records: [aggregate('A-related')] })
    else a.reject(new Error('旧目标失败'))
    await flush()
    expect(state.rows.map(r => r.aggregate.record.id)).toEqual(['B-related'])
    expect(state.error).toBe('')
  })
  it.each(['success', 'failure'])('切记录后旧提交%s不删除新pending或回填新表单', async outcome => {
    const f = fixture(),
      gate = deferred<unknown>(),
      saved = vi.fn()
    const props = reactive({ applicationId: 'app', model: f.model, record: aggregate('A'), onSaved: saved })
    const state = await setup<MainState>(RecordEditor, props)
    api.submit.mockReturnValueOnce(gate.promise)
    const sending = state.save()
    await flush()
    const old = state.pending
    const newCommand: SaveRecord = {
      applicationId: 'app',
      objectId: 'object',
      id: 'B',
      expectedRevision: '3',
      requestKey: 'new-request',
      values: { name: 'B的待确认输入' }
    }
    const key = pendingDocumentKey('boundary', 'app', 'object', 'B')
    storePendingDocument(key, newCommand)
    props.record = aggregate('B', '3')
    await flush()
    if (outcome === 'success') gate.resolve({ outcome: 'EFFECTIVE', result: aggregate('A', '2') })
    else gate.reject({ businessCode: 400, message: '旧请求拒绝' })
    await sending
    expect(state.pending?.requestKey).toBe('new-request')
    expect(JSON.parse(sessionStorage.getItem(key) || 'null')?.requestKey).toBe('new-request')
    expect(state.state.record.values.name).toBe('B的待确认输入')
    expect(saved).not.toHaveBeenCalled()
    expect(old?.requestKey).toBeTruthy()
    const previous = sessionStorage.getItem(pendingDocumentKey('boundary', 'app', 'object', 'A'))
    if (outcome === 'success') expect(previous).toBeNull()
    else expect(previous).toContain(old!.requestKey)
  })
  it.each(['success', 'failure'])('切记录后迟到回执%s仍保留新记录待确认命令', async outcome => {
    const f = fixture(),
      gate = deferred<unknown>(),
      props = reactive({ applicationId: 'app', model: f.model, record: aggregate('A') })
    const first: SaveRecord = {
      applicationId: 'app',
      objectId: 'object',
      id: 'A',
      expectedRevision: '1',
      requestKey: 'request-a',
      values: { name: 'A' }
    }
    storePendingDocument(pendingDocumentKey('boundary', 'app', 'object', 'A'), first)
    const state = await setup<MainState>(RecordEditor, props)
    api.submitReceipt.mockReturnValueOnce(gate.promise)
    const querying = state.confirmResult()
    const second = { ...first, id: 'B', requestKey: 'request-b', values: { name: 'B' } }
    const key = pendingDocumentKey('boundary', 'app', 'object', 'B')
    storePendingDocument(key, second)
    props.record = aggregate('B')
    await flush()
    if (outcome === 'success') gate.resolve({ outcome: 'EFFECTIVE', result: aggregate('A', '2') })
    else gate.reject(new Error('旧回执失败'))
    await querying
    expect(api.submitReceipt).toHaveBeenCalledWith('app', 'object', 'request-a')
    expect(state.pending?.requestKey).toBe('request-b')
    expect(sessionStorage.getItem(key)).toContain('request-b')
    expect(state.error).not.toContain('暂时无法确认')
    const previous = sessionStorage.getItem(pendingDocumentKey('boundary', 'app', 'object', 'A'))
    if (outcome === 'success') expect(previous).toBeNull()
    else expect(previous).toContain('request-a')
  })
  it.each(['submit', 'receipt'])('同一表单重新打开后迟到%s不会清除新请求', async source => {
    const f = fixture(),
      gate = deferred<unknown>(),
      props = reactive({ applicationId: 'app', model: f.model })
    const key = pendingDocumentKey('boundary', 'app', 'object', null)
    const state = await setup<MainState>(RecordEditor, props)
    if (source === 'submit') api.submit.mockReturnValueOnce(gate.promise)
    else api.submitReceipt.mockReturnValueOnce(gate.promise)
    if (source === 'receipt') {
      state.pending = {
        applicationId: 'app',
        objectId: 'object',
        id: null,
        expectedRevision: null,
        requestKey: 'old-request',
        values: {}
      }
      storePendingDocument(key, state.pending)
    }
    const sending = source === 'submit' ? state.save() : state.confirmResult()
    await flush()
    const replacement: SaveRecord = {
      applicationId: 'app',
      objectId: 'object',
      requestKey: 'replacement-request',
      id: null,
      expectedRevision: null,
      values: { name: '新请求' }
    }
    storePendingDocument(key, replacement)
    props.model = fixture().model
    await flush()
    gate.resolve({ outcome: 'EFFECTIVE', result: aggregate('saved-old') })
    await sending
    expect(sessionStorage.getItem(key)).toContain('replacement-request')
    expect(state.pending?.requestKey).toBe('replacement-request')
    expect(state.state.record.values.name).toBe('新请求')
  })
  it('同对象不同任务反馈入口分别恢复待确认请求，切入口不会串入原输入', async () => {
    const f = fixture(),
      props = reactive({ applicationId: 'app', model: f.model, pendingScope: 'task-one:entry-a' })
    const base = pendingDocumentKey('boundary', 'app', 'object', null)
    const saved = (scope: string, name: string): SaveRecord => ({
      applicationId: 'app',
      objectId: 'object',
      id: null,
      expectedRevision: null,
      requestKey: scope,
      values: { name }
    })
    storePendingDocument(base + ':scope:' + encodeURIComponent('task-one:entry-a'), saved('request-a', '入口A输入'))
    storePendingDocument(base + ':scope:' + encodeURIComponent('task-two:entry-b'), saved('request-b', '入口B输入'))
    const state = await setup<MainState>(RecordEditor, props)
    expect(state.state.record.values.name).toBe('入口A输入')
    props.pendingScope = 'task-two:entry-b'
    await flush()
    expect(state.state.record.values.name).toBe('入口B输入')
    expect(state.pending?.requestKey).toBe('request-b')
    expect(sessionStorage.getItem(base + ':scope:' + encodeURIComponent('task-one:entry-a'))).toContain('request-a')
  })
  it('关联候选搜索只接纳当前关键字的响应', async () => {
    const f = fixture(),
      state = await setup<RelatedState>(RelatedFormEditor, {
        applicationId: 'app',
        objectId: 'object',
        formId: 'form',
        binding: f.binding
      })
    await state.openPicker()
    const a = deferred<RelatedFormResult>(),
      b = deferred<RelatedFormResult>()
    api.relatedForm.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    state.search = 'A'
    await flush()
    const first = state.find()
    state.search = 'B'
    await flush()
    const second = state.find()
    b.resolve({ ...f.related, records: [aggregate('B')] })
    await second
    a.resolve({ ...f.related, records: [aggregate('A')] })
    await first
    expect(state.candidates.map(r => r.record.id)).toEqual(['B'])
  })
  it('关联表单验证期间切换主记录会拒绝旧payload，保留新上下文', async () => {
    const f = fixture(),
      gate = deferred<void>(),
      props = reactive({ applicationId: 'app', objectId: 'object', formId: 'form', binding: f.binding, recordId: 'A' })
    const state = await setup<RelatedState>(RelatedFormEditor, props)
    state.editors = [{ validate: () => gate.promise, validateUploads: () => undefined }]
    const validation = state.payload()
    props.recordId = 'B'
    await flush()
    gate.resolve()
    await expect(validation).rejects.toThrow('上下文已变化')
  })
})
