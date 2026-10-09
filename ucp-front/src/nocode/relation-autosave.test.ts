// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App, type Component } from 'vue'
import ObjectEditor from '@/views/nocode/object/editor.vue'
import { newDesign } from './data-center'
import { newField } from './object-draft'
import type { ObjectDesign, SaveDesign } from '@/types/nocode/data-center'
import { FieldType, RelationType } from '@/types/nocode/enums'

/**
 * 业务方 2026-09-29：从字段抽屉进入的对象关系，确定后回到字段抽屉。
 * 关系要保存后才生成真实引用列，所以确定时自动保存一次对象草稿（只保存、不发布）；
 * 保存失败留在关系窗口并显示原因，窗口内容保留、草稿撤回本次关系改动；从字段列表进入的行为不变。
 */
const mocks = vi.hoisted(() => ({
  data: {
    design: vi.fn(),
    history: vi.fn(),
    save: vi.fn(),
    schemas: vi.fn(),
    tables: vi.fn(),
    objects: vi.fn(),
    version: vi.fn(),
    publish: vi.fn()
  },
  route: {} as any,
  router: { replace: vi.fn(), push: vi.fn() },
  message: { success: vi.fn(), warning: vi.fn(), info: vi.fn() },
  confirm: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol('test-platform'),
  useNocodePlatform: () => ({
    dataCenter: mocks.data,
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
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: {} }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({ default: {} }))
vi.mock('@/components/UserSelectorTrigger.vue', () => ({ default: {} }))
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: {} }))

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const mounted: Array<{ app: App; host: HTMLElement }> = []
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
/** 保留真实 SFC setup 与保存逻辑，不渲染模板。 */
async function setup(component: Component) {
  const app = createApp({ ...component, render: () => null })
  const host = document.createElement('div')
  document.body.append(host)
  const vm = app.mount(host)
  mounted.push({ app, host })
  await flush()
  return (vm.$ as unknown as { setupState: Record<string, any> }).setupState
}
const pick = { ...newField(1, '客户'), key: 'pick-key', id: null, code: 'customer', type: FieldType.SELECT }
function design(): ObjectDesign {
  const input = newDesign()
  const name = { ...input.draft.fields[0]!, id: 'field', key: 'field', name: '名称', code: 'name' }
  return {
    ...input,
    draft: {
      ...input.draft,
      id: 'object',
      objectCode: 'example',
      objectName: '示例',
      tableName: 'biz_example',
      titleFieldId: name.id,
      fields: [name, pick],
      lockVersion: 1,
      versionNo: 1,
      state: 'DRAFT',
      updatedAt: ''
    },
    fieldOptions: {},
    source: 'GENERATED',
    schemaName: 'public',
    status: 'ACTIVE',
    publishedVersion: null,
    readOnly: false,
    versions: [],
    dependencies: []
  } as unknown as ObjectDesign
}
/** 服务端保存：关系生成引用列 customer_id 并回写 fieldId。 */
function saved(request: SaveDesign): ObjectDesign {
  const base = design()
  const generated = { ...pick, id: 'customer_id', key: 'customer_id', code: 'customer_id', type: FieldType.REFERENCE }
  return {
    ...base,
    ...clone(request),
    draft: {
      ...base.draft,
      ...clone(request.draft),
      fields: [...clone(request.draft.fields), generated],
      lockVersion: 2
    },
    fieldOptions: { ...clone(request.fieldOptions), customer_id: { generated: true } },
    relations: request.relations.map((item, index) => ({ ...item, id: `rel-${index}`, fieldId: 'customer_id' }))
  } as unknown as ObjectDesign
}

beforeEach(() => {
  vi.clearAllMocks()
  // 清掉上一条用例未消费的 mockRejectedValueOnce，避免串到下一条。
  mocks.data.save.mockReset()
  mocks.route = reactive({ path: '/nocode/object/editor', query: { id: 'object' } })
  mocks.data.design.mockResolvedValue(design())
  mocks.data.history.mockResolvedValue([])
  mocks.data.objects.mockResolvedValue({ list: [], total: 0 })
  mocks.data.version.mockResolvedValue({ fields: [] })
  // 已有字段改成对象关系前先过转换复核（变更影响确认框）；默认点「继续调整草稿」。
  mocks.confirm.mockImplementation((options: { onOk?: () => void }) => options.onOk?.())
})
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
})

async function openFromDrawer() {
  const state = await setup(ObjectEditor)
  const field = state.input.draft.fields.find((item: { key: string }) => item.key === 'pick-key')
  state.fieldRelation(field, undefined, 'drawer')
  expect(state.relationOpen).toBe(true)
  state.relation.targetObjectId = 'customers'
  return state
}

describe('字段抽屉进入的对象关系：确定即自动保存并回到抽屉', () => {
  it('确定后自动保存一次草稿（不发布），关系窗口关闭并请求回到该关系的引用字段抽屉', async () => {
    mocks.data.save.mockImplementation(async (request: SaveDesign) => saved(request))
    const state = await openFromDrawer()
    await state.saveRelation()
    await flush()
    expect(mocks.data.save).toHaveBeenCalledTimes(1)
    expect(mocks.data.publish).not.toHaveBeenCalled()
    // 顺序：转换复核 → 应用关系 → 自动保存草稿 → 回到字段抽屉。
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(mocks.confirm.mock.invocationCallOrder[0]).toBeLessThan(mocks.data.save.mock.invocationCallOrder[0]!)
    expect(state.relationSaving).toBe(false)
    const request = mocks.data.save.mock.calls[0]![0] as SaveDesign
    expect(request.relations).toEqual([expect.objectContaining({ code: 'customer', fieldId: null })])
    expect(request.draft.fields.map(item => item.key)).not.toContain('pick-key')
    expect(state.relationOpen).toBe(false)
    expect(state.relationFocus).toMatchObject({ code: 'customer' })
    expect(state.input.relations[0]).toMatchObject({ id: 'rel-0', fieldId: 'customer_id' })
    expect(state.tab).toBe('fields')
  })

  it('转换复核在先：取消复核则不应用关系、不自动保存，关系窗口保持打开、草稿原样', async () => {
    mocks.confirm.mockImplementation((options: { onCancel?: () => void }) => options.onCancel?.())
    const state = await openFromDrawer()
    await state.saveRelation()
    await flush()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(mocks.data.save).not.toHaveBeenCalled()
    expect(state.relationOpen).toBe(true)
    expect(state.relationError).toBe('')
    expect(state.input.relations).toEqual([])
    expect(state.input.draft.fields.map((item: { key: string }) => item.key)).toContain('pick-key')
    expect(state.relationFocus).toBeNull()
    expect(state.relationSaving).toBe(false)
  })

  it('自动保存失败：停留在关系窗口并显示原因，窗口内容保留，草稿撤回本次关系改动，可修正后重试', async () => {
    mocks.data.save.mockRejectedValueOnce(new Error('编码冲突'))
    const state = await openFromDrawer()
    state.relation.name = '客户（改）'
    await state.saveRelation()
    await flush()
    expect(mocks.data.save).toHaveBeenCalledTimes(1)
    expect(state.relationOpen).toBe(true)
    expect(state.relationError).toContain('编码冲突')
    expect(state.relation).toMatchObject({ name: '客户（改）', targetObjectId: 'customers', code: 'customer' })
    expect(state.input.relations).toEqual([])
    expect(state.input.draft.fields.map((item: { key: string }) => item.key)).toContain('pick-key')
    expect(state.relationFocus).toBeNull()
    expect(state.error).toBe('')

    mocks.data.save.mockImplementation(async (request: SaveDesign) => saved(request))
    await state.saveRelation()
    await flush()
    expect(mocks.data.save).toHaveBeenCalledTimes(2)
    expect(state.relationOpen).toBe(false)
    expect(state.input.relations).toHaveLength(1)
    expect(state.relationFocus).toMatchObject({ code: 'customer' })
  })

  it('从字段列表进入（非抽屉）：行为不变，确定只改草稿、不自动保存', async () => {
    const state = await setup(ObjectEditor)
    const field = state.input.draft.fields.find((item: { key: string }) => item.key === 'pick-key')
    state.fieldRelation(field)
    state.relation.targetObjectId = 'customers'
    await state.saveRelation()
    await flush()
    expect(mocks.data.save).not.toHaveBeenCalled()
    expect(state.relationOpen).toBe(false)
    expect(state.input.relations).toEqual([expect.objectContaining({ code: 'customer', id: null })])
    expect(state.relationFocus).toBeNull()
  })

  it('占位行「请先保存」里的「保存」：保存成功后请求打开该关系的引用字段抽屉；失败不请求', async () => {
    const state = await setup(ObjectEditor)
    state.input.draft.fields = state.input.draft.fields.filter((item: { key: string }) => item.key !== 'pick-key')
    state.input.relations.push({
      id: null,
      code: 'customer',
      name: '客户',
      kind: RelationType.REFERENCE,
      targetObjectId: 'customers',
      fieldId: null,
      targetFieldId: null,
      required: false,
      onDelete: 'RESTRICT',
      sourceDetailId: null
    })
    mocks.data.save.mockRejectedValueOnce(new Error('网络错误'))
    await state.saveThenConfigureRelation('customer')
    expect(state.relationFocus).toBeNull()
    mocks.data.save.mockImplementation(async (request: SaveDesign) => saved(request))
    await state.saveThenConfigureRelation('customer')
    await flush()
    expect(mocks.data.save).toHaveBeenCalledTimes(2)
    expect(state.relationFocus).toMatchObject({ code: 'customer' })
  })
})
