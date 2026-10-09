// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App, type Component } from 'vue'
import ObjectEditor from '@/views/nocode/object/editor.vue'
import Workspace from '@/views/nocode/application/workspace.vue'
import TableDirectory from '@/views/nocode/table/index.vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import CalculationEditor from '@/views/nocode/components/CalculationEditor.vue'
import DocumentPolicyDesigner from '@/views/nocode/components/DocumentPolicyDesigner.vue'
import SelectionSourceEditor from '@/views/nocode/components/SelectionSourceEditor.vue'
import { defaultFieldOptions, newDesign } from './data-center'
import { newField } from './object-draft'
import type { ObjectDesign, SaveDesign } from '@/types/nocode/data-center'

const mocks = vi.hoisted(() => ({
  data: {
    design: vi.fn(),
    history: vi.fn(),
    save: vi.fn(),
    schemas: vi.fn(),
    tables: vi.fn(),
    table: vi.fn(),
    preflight: vi.fn(),
    preview: vi.fn(),
    adopt: vi.fn(),
    objects: vi.fn(),
    version: vi.fn()
  },
  apps: {
    get: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    status: vi.fn(),
    objectVersion: vi.fn(),
    linkageOverview: vi.fn()
  },
  route: {} as any,
  router: { replace: vi.fn(), push: vi.fn() },
  message: { success: vi.fn(), warning: vi.fn(), info: vi.fn() },
  confirm: vi.fn(),
  guards: [] as Array<() => unknown>
}))
vi.mock('@/api/auth', () => ({ getUserInfo: vi.fn().mockResolvedValue({}) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ applyPermissionInfo: vi.fn() }) }))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol('test-platform'),
  useNocodePlatform: () => ({
    dataCenter: mocks.data,
    applications: mocks.apps,
    hasPermission: () => true,
    directory: { users: async () => [], departments: async () => [] }
  })
}))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => mocks.data }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('ant-design-vue', () => ({
  message: mocks.message,
  Modal: { confirm: mocks.confirm },
  Upload: { LIST_IGNORE: '' }
}))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
  onBeforeRouteLeave: (guard: () => unknown) => mocks.guards.push(guard),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@/views/nocode/application/components/ResourceManager.vue', () => ({ default: {} }))
vi.mock('@/views/nocode/application/components/BusinessConfigManager.vue', () => ({ default: {} }))
vi.mock('@/views/nocode/application/components/ApplicationPublishDialog.vue', () => ({ default: {} }))
vi.mock('@/views/nocode/application/components/PlatformMenuEntry.vue', () => ({ default: {} }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: {} }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({ default: {} }))
vi.mock('@/components/UserSelectorTrigger.vue', () => ({ default: {} }))
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: {} }))

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function deferred<T = any>() {
  let resolve!: (value: T) => void, reject!: (value: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const mounted: Array<{ app: App; host: HTMLElement }> = []
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
/** 保留真实 SFC setup、watch 和生命周期，仅关闭本测试无关的底座控件渲染。 */
async function setup(component: Component, props: Record<string, unknown> = {}) {
  const app = createApp({ ...component, render: () => null }, props)
  const host = document.createElement('div')
  document.body.append(host)
  const vm = app.mount(host)
  mounted.push({ app, host })
  await flush()
  return { state: (vm.$ as unknown as { setupState: Record<string, any> }).setupState, app }
}
function design(id = 'object'): ObjectDesign {
  const input = newDesign()
  const field = { ...input.draft.fields[0]!, id: 'field', key: 'field', name: '名称', code: 'name' }
  return {
    ...input,
    draft: {
      ...input.draft,
      id,
      objectCode: 'example',
      objectName: '保存前',
      tableName: 'biz_example',
      titleFieldId: field.id,
      fields: [field],
      lockVersion: 1,
      versionNo: 1,
      state: 'DRAFT',
      updatedAt: ''
    },
    source: 'GENERATED',
    schemaName: 'public',
    status: 'ACTIVE',
    publishedVersion: null,
    readOnly: false,
    versions: [],
    dependencies: []
  }
}
function saved(input: SaveDesign): ObjectDesign {
  return {
    ...design(input.draft.id || 'created'),
    ...clone(input),
    draft: {
      ...design().draft,
      ...clone(input.draft),
      id: input.draft.id || 'created',
      titleFieldId: input.draft.titleFieldKey,
      lockVersion: (input.draft.expectedLockVersion || 0) + 1
    }
  }
}
const application = (revision = 1, name = '应用') => ({
  application: {
    id: 'app',
    revision,
    code: 'app',
    name,
    description: null,
    icon: null,
    status: 'ACTIVE',
    publishedVersion: null
  },
  draft: { objects: [], resources: [] },
  issues: []
})
const preflight = (name: string) => ({
  schemaName: 'public',
  tableName: name,
  allowed: true,
  readOnly: false,
  fingerprint: `fp-${name}`,
  titleColumns: ['name'],
  structure: {
    columns: [
      { name: `key_${name}`, primaryKeyPosition: 1 },
      { name: 'parent_id', primaryKeyPosition: 0 }
    ]
  }
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.guards.length = 0
  mocks.route = reactive({ path: '/nocode/object/editor', query: { id: 'object' } })
  mocks.data.design.mockResolvedValue(design())
  mocks.data.history.mockResolvedValue([])
  mocks.data.schemas.mockResolvedValue(['public'])
  mocks.data.tables.mockResolvedValue({ list: [], total: 0 })
  mocks.data.objects.mockResolvedValue({ list: [], total: 0 })
  mocks.data.version.mockResolvedValue({ fields: [] })
  mocks.apps.get.mockResolvedValue(application())
  // 缺省：发布版里没有自动更新字段。
  mocks.apps.linkageOverview.mockResolvedValue({ fields: [], removed: [] })
})
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
})

describe('真实数据配置组件的保存会话', () => {
  it('对象草稿保存时清除旧多行文本正则', async () => {
    mocks.data.save.mockImplementation(async request => saved(request))
    const { state } = await setup(ObjectEditor)
    const field = { ...newField(1, '备注'), code: 'notes', type: 'TEXTAREA' as const }
    state.input.draft.fields.push(field)
    state.input.fieldOptions[field.key] = { ...defaultFieldOptions(), pattern: '[A-' }
    await state.save()
    expect(mocks.data.save).toHaveBeenCalledOnce()
    expect(mocks.data.save.mock.calls[0]![0].fieldOptions[field.key].pattern).toBeNull()
  })
  it('对象保存使用独立快照，期间明确只读，成功后恢复编辑和修订', async () => {
    const gate = deferred<ObjectDesign>()
    mocks.data.save.mockReturnValue(gate.promise)
    const { state } = await setup(ObjectEditor)
    state.input.draft.objectName = '待保存'
    const pending = state.save()
    expect(state.canEdit).toBe(false)
    expect(state.confirmLeave()).toBe(false)
    const request = mocks.data.save.mock.calls[0]![0]
    expect(request).not.toBe(state.input)
    state.saveIndex()
    expect(state.input.indexes).toEqual([])
    await state.save()
    expect(mocks.data.save).toHaveBeenCalledTimes(1)
    gate.resolve(saved(request))
    await pending
    expect(state.canEdit).toBe(true)
    expect(state.dirty).toBe(false)
    expect(state.input.draft.expectedLockVersion).toBe(2)
  })
  it('对象保存失败保留草稿和修订，卸载后的成功不导航也不回填', async () => {
    const gate = deferred<ObjectDesign>()
    mocks.data.save.mockReturnValue(gate.promise)
    const { state, app } = await setup(ObjectEditor)
    state.input.draft.objectName = '保留输入'
    let pending = state.save()
    gate.reject(new Error('失败'))
    await pending
    expect(state.input.draft.objectName).toBe('保留输入')
    expect(state.input.draft.expectedLockVersion).toBe(1)
    expect(state.dirty).toBe(true)
    const late = deferred<ObjectDesign>()
    mocks.data.save.mockReturnValue(late.promise)
    pending = state.save()
    app.unmount()
    late.resolve(saved(mocks.data.save.mock.calls.at(-1)![0]))
    await pending
    expect(mocks.router.replace).not.toHaveBeenCalled()
  })
  it('应用保存期间的名称和资源修改保留，下一次携带新修订保存', async () => {
    mocks.route.query.id = 'app'
    const gate = deferred()
    mocks.apps.save.mockReturnValue(gate.promise)
    const { state } = await setup(Workspace)
    state.draft.name = '首次提交'
    state.dirty = true
    const pending = state.save()
    state.draft.name = '继续输入'
    state.draft.definition.resources.push({
      id: 'stable-resource',
      kind: 'PAGE',
      code: 'page',
      name: '新增页面',
      config: {}
    })
    expect(mocks.apps.save.mock.calls[0]![0].definition.resources).toEqual([])
    gate.resolve(application(2, '首次提交'))
    await pending
    expect(state.draft.name).toBe('继续输入')
    expect(state.draft.definition.resources[0].id).toBe('stable-resource')
    expect(state.draft.expectedRevision).toBe(2)
    expect(state.dirty).toBe(true)
    mocks.apps.save.mockImplementation(async input => ({
      ...application(3, input.name),
      draft: clone(input.definition)
    }))
    await state.save()
    expect(mocks.apps.save.mock.calls.at(-1)![0].expectedRevision).toBe(2)
    expect(state.dirty).toBe(false)
  })
  it('应用失败保留新输入；离开会话后旧保存不回填', async () => {
    mocks.route.query.id = 'app'
    const gate = deferred()
    mocks.apps.save.mockReturnValue(gate.promise)
    const { state, app } = await setup(Workspace)
    state.draft.name = '第一次'
    state.dirty = true
    const pending = state.save()
    state.draft.name = '失败也保留'
    gate.reject(new Error('失败'))
    await pending
    expect(state.draft.name).toBe('失败也保留')
    expect(state.draft.expectedRevision).toBe(1)
    const late = deferred()
    mocks.apps.save.mockReturnValue(late.promise)
    const later = state.save()
    app.unmount()
    late.resolve(application(2, '旧保存'))
    await later
    expect(state.draft.name).toBe('失败也保留')
  })
  it('工作台切换应用释放旧busy，旧保存回调不能解除新应用的busy', async () => {
    mocks.route.query.id = 'app'
    const oldSave = deferred(),
      newSave = deferred()
    mocks.apps.save.mockReturnValueOnce(oldSave.promise).mockReturnValueOnce(newSave.promise)
    const { state } = await setup(Workspace)
    state.dirty = true
    const savingOld = state.save()
    expect(state.busy).toBe(true)
    const next = application()
    next.application.id = 'next'
    next.application.name = '下一应用'
    mocks.apps.get.mockResolvedValueOnce(next)
    mocks.route.query.id = 'next'
    await flush()
    expect(state.busy).toBe(false)
    expect(state.loaded).toBe(true)
    state.draft.name = '下一应用的新输入'
    state.dirty = true
    const savingNew = state.save()
    oldSave.resolve(application(2))
    await savingOld
    expect(state.busy).toBe(true)
    expect(state.draft.name).toBe('下一应用的新输入')
    expect(mocks.message.success).not.toHaveBeenCalled()
    newSave.resolve({ ...next, application: { ...next.application, revision: 2, name: state.draft.name } })
    await savingNew
    expect(state.busy).toBe(false)
    expect(state.dirty).toBe(false)
    expect(state.draft.expectedRevision).toBe(2)
  })
  it('工作台恢复与状态弹窗保留失败重试和成功反馈，发布保留新输入', async () => {
    mocks.route.query.id = 'app'
    const { state } = await setup(Workspace)
    state.openRestore(3)
    state.restoreReason = '恢复说明'
    mocks.apps.restore.mockRejectedValueOnce(new Error('版本不兼容'))
    await state.restore()
    expect(state.restoreOpen).toBe(true)
    expect(state.restoreError).toBe('版本不兼容')
    mocks.apps.restore.mockResolvedValueOnce(application(2))
    await state.restore()
    expect(state.restoreOpen).toBe(false)
    expect(mocks.apps.restore).toHaveBeenLastCalledWith({
      id: 'app',
      expectedRevision: 1,
      sourceVersion: 3,
      reason: '恢复说明'
    })
    state.openStatus()
    state.statusReason = '维护说明'
    mocks.apps.status.mockRejectedValueOnce(new Error('有运行中的审批'))
    await state.changeStatus()
    expect(state.statusOpen).toBe(true)
    expect(state.statusError).toBe('有运行中的审批')
    mocks.apps.status.mockResolvedValueOnce(application(3))
    await state.changeStatus()
    expect(state.statusOpen).toBe(false)
    expect(mocks.apps.status).toHaveBeenLastCalledWith({
      id: 'app',
      expectedRevision: 2,
      status: 'DISABLED',
      reason: '维护说明'
    })
    state.openPublish()
    state.draft.name = '发布期间继续输入'
    state.published(application(4))
    expect(state.publishOpen).toBe(false)
    expect(state.draft.name).toBe('发布期间继续输入')
    expect(state.draft.expectedRevision).toBe(4)
    expect(state.dirty).toBe(true)
  })
})

// 第一期契约 9.3：发布成功后若有新开启或规则变了的自动更新字段，自动打开「自动更新」面板并突出它们。
describe('工作台 · 发布后打开「自动更新」', () => {
  const syncField = (targetFieldId: string, change: string) => ({ targetObjectId: '5398', targetFieldId, change })
  it('发布版里有新开启或变更的字段：切到「自动更新」，只突出这些字段', async () => {
    mocks.route.query.id = 'app'
    mocks.apps.linkageOverview.mockResolvedValue({
      fields: [syncField('f1', 'NEW'), syncField('f2', 'UNCHANGED'), syncField('f3', 'CHANGED')],
      removed: []
    })
    const { state } = await setup(Workspace)
    state.openPublish()
    state.published(application(2))
    await flush()
    expect(mocks.apps.linkageOverview).toHaveBeenCalledWith('app', 'PUBLISHED')
    expect(state.activeTab).toBe('linkage-sync')
    expect(state.linkageHighlight).toEqual(['5398:f1', '5398:f3'])
  })
  it('没有新开启或变更的字段：留在原来的页签', async () => {
    mocks.route.query.id = 'app'
    mocks.apps.linkageOverview.mockResolvedValue({ fields: [syncField('f2', 'UNCHANGED')], removed: [] })
    const { state } = await setup(Workspace)
    state.openPublish()
    state.published(application(2))
    await flush()
    expect(state.activeTab).toBe('objects')
    expect(state.linkageHighlight).toEqual([])
  })
  it('总览读不到：发布照常完成，只提醒去「自动更新」里看', async () => {
    mocks.route.query.id = 'app'
    mocks.apps.linkageOverview.mockRejectedValue(new Error('服务暂不可用'))
    const { state } = await setup(Workspace)
    state.openPublish()
    state.published(application(2))
    await flush()
    expect(state.publishOpen).toBe(false)
    expect(state.activeTab).toBe('objects')
    expect(mocks.message.warning).toHaveBeenCalledWith(expect.stringContaining('请到「自动更新」里查看'))
  })
  it('发布结果没被接受（不是这次打开的发布）时不去读总览', async () => {
    mocks.route.query.id = 'app'
    const { state } = await setup(Workspace)
    state.published(application(2))
    await flush()
    expect(mocks.apps.linkageOverview).not.toHaveBeenCalled()
  })
})

describe('明细预检与表详情会话', () => {
  it('A/B预检乱序、切Schema及关闭后的回调均不混入当前绑定', async () => {
    const { state } = await setup(ObjectEditor)
    await state.showBindDetail()
    const a = deferred(),
      b = deferred()
    mocks.data.preflight.mockImplementation((_schema, name) => (name === 'A' ? a.promise : b.promise))
    state.detailCandidate.tableName = 'A'
    const pA = state.selectBindingTable('A')
    state.detailCandidate.tableName = 'B'
    const pB = state.selectBindingTable('B')
    b.resolve(preflight('B'))
    await pB
    a.resolve(preflight('A'))
    await pA
    expect(state.detailCandidate.binding.keyColumn).toBe('key_B')
    expect(state.bindingPreflight.tableName).toBe('B')
    const old = deferred()
    mocks.data.preflight.mockReturnValue(old.promise)
    const pending = state.selectBindingTable('B')
    state.detailCandidate.binding.schemaName = 'other'
    await state.changeBindingSchema()
    old.resolve(preflight('B'))
    await pending
    expect(state.bindingPreflight).toBeUndefined()
    expect(state.bindingLoading).toBe(false)
    state.detailCandidate.tableName = 'B'
    const closed = deferred()
    mocks.data.preflight.mockReturnValue(closed.promise)
    const final = state.selectBindingTable('B')
    state.closeBindDetail()
    closed.reject(new Error('旧错误'))
    await final
    expect(state.bindDetailError).toBe('')
  })
  it('关闭A读取并打开B时，A预览不能出现在B标题下；预览翻页只接受最后一次', async () => {
    const { state } = await setup(TableDirectory)
    mocks.data.table.mockImplementation(async (_schema, name) => ({ table: { schemaName: 'public', tableName: name } }))
    await state.showDetail({ schemaName: 'public', tableName: 'A' })
    const old = deferred()
    mocks.data.preview.mockReturnValue(old.promise)
    const pending = state.readPreview(1)
    state.closeDetail()
    await state.showDetail({ schemaName: 'public', tableName: 'B' })
    old.resolve({ rows: [{ from: 'A' }] })
    await pending
    expect(state.detail.table.tableName).toBe('B')
    expect(state.preview).toBeUndefined()
    expect(state.detailTab).toBe('columns')
    const one = deferred(),
      two = deferred()
    mocks.data.preview.mockImplementation((_s, _n, page) => (page === 1 ? one.promise : two.promise))
    const p1 = state.readPreview(1),
      p2 = state.readPreview(2)
    two.resolve({ rows: [{ page: 2 }] })
    await p2
    one.resolve({ rows: [{ page: 1 }] })
    await p1
    expect(state.preview.rows).toEqual([{ page: 2 }])
    expect(state.previewPage).toBe(2)
  })
  it('结构和纳管预检关闭后重开，仅当前结果和错误生效', async () => {
    const { state } = await setup(TableDirectory)
    const a = deferred(),
      b = deferred()
    mocks.data.table.mockImplementation((_s, name) => (name === 'A' ? a.promise : b.promise))
    const pA = state.showDetail({ schemaName: 'public', tableName: 'A' })
    state.closeDetail()
    const pB = state.showDetail({ schemaName: 'public', tableName: 'B' })
    b.resolve({ table: { tableName: 'B' } })
    await pB
    a.reject(new Error('旧错误'))
    await pA
    expect(state.detailOpen).toBe(true)
    expect(state.detail.table.tableName).toBe('B')
    expect(state.error).toBe('')
    const preA = deferred(),
      preB = deferred()
    mocks.data.preflight.mockImplementation((_s, name) => (name === 'A' ? preA.promise : preB.promise))
    const old = state.showAdoption({ schemaName: 'public', tableName: 'A' })
    state.closeAdoption()
    const current = state.showAdoption({ schemaName: 'public', tableName: 'B' })
    preB.resolve(preflight('B'))
    await current
    preA.resolve(preflight('A'))
    await old
    expect(state.preflight.tableName).toBe('B')
    expect(state.adoption.objectName).toBe('B')
  })
})

describe('字段与计算配置会话', () => {
  it('选择来源双向模型保留父字段选项与目录范围，数量变化仍可保存', async () => {
    const field = reactive({ ...newField(0, '组织'), type: 'ORGANIZATION' })
    const options = reactive({
      ...defaultFieldOptions(),
      defaultValue: 'org',
      selection: {
        kind: 'DIRECTORY',
        directory: 'ORGANIZATION',
        dictionaryType: null,
        rootIds: ['root'],
        includeDescendants: true,
        organizationTypes: [1],
        defaultMode: 'FIXED'
      }
    })
    const { state } = await setup(SelectionSourceEditor, {
      field,
      options,
      onFieldChange: (next: typeof field) => Object.assign(field, next)
    })
    await state.count(true)
    expect(field.type).toBe('MULTI_SELECT')
    expect(options.selection.rootIds).toEqual(['root'])
    expect(options.defaultValue).toBe('["org"]')
    state.patch({ includeDescendants: false })
    expect(options.selection.includeDescendants).toBe(false)
  })
  it('状态设计器真实watch增量同步选项，失效动作修正前不允许移除状态', async () => {
    const input = reactive(newDesign())
    const field = input.draft.fields[0]!
    field.type = 'SELECT'
    field.key = 'status'
    field.id = 'status'
    input.fieldOptions.status = {
      ...defaultFieldOptions(),
      options: [
        { code: 'draft', label: '草稿', disabled: false },
        { code: 'posted', label: '登记', disabled: false }
      ]
    }
    const policy = reactive({
      rules: [],
      lifecycle: {
        fieldId: 'status',
        initialState: 'draft',
        states: [
          { code: 'draft', name: '草稿', lockedFields: ['amount'], lockedDetails: ['lines'], allowDelete: false },
          { code: 'posted', name: '登记', lockedFields: [], lockedDetails: [], allowDelete: false }
        ],
        actions: [{ code: 'post', name: '登记', fromStates: ['draft'], toState: 'posted', permission: 'UPDATE' }]
      }
    })
    const { state } = await setup(DocumentPolicyDesigner, { design: input, modelValue: policy })
    input.fieldOptions.status.options.push({ code: 'void', label: '作废', disabled: false })
    await flush()
    expect(policy.lifecycle.states.map(s => s.code)).toEqual(['draft', 'posted', 'void'])
    expect(policy.lifecycle.states[0]!.lockedFields).toEqual(['amount'])
    input.fieldOptions.status.options = input.fieldOptions.status.options.filter(o => o.code !== 'draft')
    await flush()
    state.removeRetiredStates()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(state.removalImpact.actions[0].code).toBe('post')
    policy.lifecycle.initialState = 'posted'
    policy.lifecycle.actions[0]!.fromStates = ['posted']
    await flush()
    state.removeRetiredStates()
    mocks.confirm.mock.calls[0]![0].onOk()
    await flush()
    expect(policy.lifecycle.states.map(s => s.code)).toEqual(['posted', 'void'])
    expect(policy.lifecycle.actions[0]!.code).toBe('post')
  })
  it('上传失败不影响下个文本字段，旧监听也不能写入新字段', async () => {
    const file = { ...newField(0, '附件'), type: 'ATTACHMENT' },
      text = newField(1, '名称')
    const emitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [file, text],
      options: { [file.key]: defaultFieldOptions(), [text.key]: defaultFieldOptions() },
      'onUpdate:modelValue': emitted
    })
    state.show(file)
    const oldListener = state.defaultUploadListener
    oldListener({ pending: false, failed: true })
    state.save()
    expect(state.error).toContain('上传失败')
    state.closeField()
    state.show(text)
    oldListener({ pending: true, failed: true })
    state.save()
    expect(state.error).toBe('')
    expect(emitted).toHaveBeenCalledTimes(1)
    state.show(file)
    expect(state.defaultUpload).toEqual({ pending: false, failed: false })
  })
  it('字段正则输入实时提示错误，修正后可以确认且不留下旧错误', async () => {
    const field = newField(0, '物品编码')
    const emitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      'onUpdate:modelValue': emitted
    })
    state.show(field)
    state.option.pattern = '[A-'
    await flush()
    expect(state.patternError).toContain('正则表达式无效')
    state.save()
    expect(state.error).toContain('正则表达式无效')
    expect(emitted).not.toHaveBeenCalled()
    state.option.pattern = '^[A-Z0-9]+$'
    await flush()
    expect(state.patternError).toBeNull()
    expect(state.error).toBe('')
    state.save()
    expect(emitted).toHaveBeenCalledTimes(1)
  })
  it('金额默认值低于最小值时立即提示且不能确认，修正后可以确认', async () => {
    const field = { ...newField(0, '金额'), type: 'MONEY' as const, precision: 18, scale: 2 }
    const emitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      'onUpdate:modelValue': emitted
    })
    state.show(field)
    state.option.defaultValue = '123123.00'
    state.option.minimum = '1111111111111.00'
    await flush()
    expect(state.numericIssue).toMatchObject({ input: 'defaultValue' })
    state.save()
    expect(state.error).toContain('不能小于最小值')
    expect(emitted).not.toHaveBeenCalled()
    state.option.minimum = '100000.00'
    await flush()
    expect(state.numericIssue).toBeNull()
    expect(state.error).toBe('')
    state.save()
    expect(emitted).toHaveBeenCalledTimes(1)
  })
  it('全角括号作为合法文字时提示其含义，允许确认', async () => {
    const field = newField(0, '备注')
    const emitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      'onUpdate:modelValue': emitted
    })
    state.show(field)
    state.option.pattern = '怕【哦【'
    await flush()
    expect(state.patternError).toBeNull()
    expect(state.patternHelp).toContain('未发现语法错误。全角括号会被当作普通文字')
    state.save()
    expect(emitted).toHaveBeenCalledTimes(1)
  })
  it('旧多行文本字段的隐藏正则在确认时清除', async () => {
    const field = { ...newField(0, '说明'), type: 'TEXTAREA' as const }
    const optionsEmitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: { ...defaultFieldOptions(), pattern: '[A-' } },
      'onUpdate:options': optionsEmitted
    })
    state.show(field)
    expect(state.option.pattern).toBeNull()
    state.save()
    expect(optionsEmitted.mock.calls[0]![0][field.key].pattern).toBeNull()
  })
  it.each([false, true])('未命名新字段切换公式只打开配置，不发起转换审核（明细=%s）', async detail => {
    const field = newField(0)
    const previewSwitch = vi.fn(),
      reviewSwitch = vi.fn(),
      emitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      detail,
      previewSwitch,
      reviewSwitch,
      'onUpdate:modelValue': emitted
    })
    await state.updateType(field, 'FORMULA')
    await flush()
    expect(state.open).toBe(true)
    expect(state.field.type).toBe('FORMULA')
    expect(state.switchReviewOpen).toBe(false)
    expect(state.switchChanged).toBe(false)
    expect(state.switchPreview).toBeUndefined()
    expect(previewSwitch).not.toHaveBeenCalled()
    expect(reviewSwitch).not.toHaveBeenCalled()
    state.save()
    expect(state.error).toContain('字段名称')
    expect(emitted).not.toHaveBeenCalled()
    state.closeField()
    expect(field.type).toBe('TEXT')
  })
  it('新字段公式补全后直接确认，重新打开本地新字段也不要求转换检查', async () => {
    const field = newField(0, '测试金额')
    const previewSwitch = vi.fn(),
      emitted = vi.fn(),
      optionsEmitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      previewSwitch,
      'onUpdate:modelValue': emitted,
      'onUpdate:options': optionsEmitted
    })
    state.show(field)
    await state.changeType('FORMULA')
    state.option.expression = '1 + 2'
    state.option.resultType = 'DECIMAL'
    state.save()
    expect(state.error).toBe('')
    expect(state.switchReviewOpen).toBe(false)
    expect(emitted.mock.calls[0]?.[0][0]).toMatchObject({ id: null, type: 'FORMULA' })
    expect(optionsEmitted.mock.calls[0]?.[0][field.key].expression).toBe('1 + 2')
    state.show(emitted.mock.calls[0]?.[0][0])
    await state.changeType('TEXT')
    expect(state.field.type).toBe('TEXT')
    expect(state.switchReviewOpen).toBe(false)
    expect(previewSwitch).not.toHaveBeenCalled()
  })
  it('新字段的选项来源切换和旧式审核回调也不会误走已有字段转换', async () => {
    const field = newField(0, '选择项')
    const reviewSwitch = vi.fn().mockResolvedValue(false)
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      reviewSwitch
    })
    state.show(field)
    await state.changeType('SELECT')
    expect(state.field.type).toBe('SELECT')
    expect(await state.reviewSelectionSwitch({ ...field, type: 'MULTI_SELECT' }, defaultFieldOptions())).toBe(true)
    expect(reviewSwitch).not.toHaveBeenCalled()
    expect(state.switchReviewOpen).toBe(false)
  })
  it('已保存但未发布字段仍检查转换，取消后恢复原类型且不写入草稿', async () => {
    vi.useFakeTimers()
    const field = { ...newField(0, '数量'), id: 'saved-field' }
    const previewSwitch = vi.fn().mockResolvedValue({
      deploymentState: 'UNPUBLISHED',
      decision: 'UNPUBLISHED',
      impacts: []
    })
    const emitted = vi.fn()
    try {
      const { state } = await setup(FieldDesigner, {
        modelValue: [field],
        options: { [field.key]: defaultFieldOptions() },
        previewSwitch,
        'onUpdate:modelValue': emitted
      })
      await state.updateType(field, 'INTEGER')
      await flush()
      expect(state.switchReviewOpen).toBe(true)
      await vi.advanceTimersByTimeAsync(300)
      expect(previewSwitch).toHaveBeenCalledTimes(1)
      expect(previewSwitch.mock.calls[0]?.[0].id).toBe('saved-field')
      expect(state.switchPreview.decision).toBe('UNPUBLISHED')
      state.cancelSwitchReview()
      expect(state.field.type).toBe('TEXT')
      expect(emitted).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
  it('字段配置保存接入计算校验，失败不写草稿，修正后保留新模式配置', async () => {
    const field = { ...newField(0, '余额'), type: 'FORMULA' }
    const emitted = vi.fn(),
      optionsEmitted = vi.fn()
    const { state } = await setup(FieldDesigner, {
      modelValue: [field],
      options: { [field.key]: defaultFieldOptions() },
      'onUpdate:modelValue': emitted,
      'onUpdate:options': optionsEmitted
    })
    state.show(field)
    state.option.calculation = {
      mode: 'RUNNING_TOTAL',
      updateMode: 'LIVE',
      aggregate: 'SUM',
      targetObjectId: null,
      relationId: null,
      targetField: 'income',
      groupFields: ['account'],
      conditions: [],
      logic: 'AND',
      excludeCurrent: false,
      runningTotal: {
        orderField: '',
        tieBreakerField: null,
        subtractField: 'expense',
        initialField: null,
        initialValue: '0'
      }
    }
    const validate = vi.fn().mockReturnValue('请选择有效的累计顺序字段')
    state.calculationEditor = { validate }
    state.save()
    expect(state.error).toContain('累计顺序字段')
    expect(emitted).not.toHaveBeenCalled()
    expect(optionsEmitted).not.toHaveBeenCalled()
    state.option.calculation.runningTotal.orderField = 'date'
    validate.mockReturnValue('')
    state.save()
    expect(state.error).toBe('')
    expect(emitted).toHaveBeenCalledTimes(1)
    expect(optionsEmitted.mock.calls[0]?.[0][field.key].calculation).toEqual(state.option.calculation)
  })
  it('计算来源远程搜索、加载第2页并保留页外已选标签，乱序不覆盖', async () => {
    const model = { mode: 'LOOKUP', targetObjectId: 'chosen', conditions: [], aggregate: 'COUNT' }
    mocks.data.design.mockResolvedValue({ ...design('chosen'), publishedVersion: 1 })
    mocks.data.objects.mockResolvedValueOnce({
      list: [{ id: 'first', objectName: '第一页', objectCode: 'first', publishedVersion: 1 }],
      total: 101
    })
    const { state } = await setup(CalculationEditor, { modelValue: model, fields: [], relations: [] })
    expect(state.objectOptions.some((o: any) => o.value === 'chosen' && o.label.includes('保存前'))).toBe(true)
    mocks.data.objects.mockResolvedValueOnce({
      list: [{ id: 'second', objectName: '第二页', objectCode: 'second', publishedVersion: 1 }],
      total: 101
    })
    await state.loadObjects('', true)
    expect(mocks.data.objects.mock.calls.at(-1)![0].pageNo).toBe(2)
    expect(state.objectOptions.map((o: any) => o.value)).toEqual(['chosen', 'first', 'second'])
    const old = deferred(),
      latest = deferred()
    mocks.data.objects.mockImplementation(query => (query.name === '旧' ? old.promise : latest.promise))
    const first = state.loadObjects('旧'),
      next = state.loadObjects('新')
    latest.resolve({ list: [{ id: 'new', objectName: '新来源', objectCode: 'new', publishedVersion: 1 }], total: 1 })
    await next
    old.resolve({ list: [{ id: 'old', objectName: '旧来源', objectCode: 'old', publishedVersion: 1 }], total: 1 })
    await first
    expect(state.objectOptions.map((o: any) => o.value)).toEqual(['chosen', 'new'])
  })
})
