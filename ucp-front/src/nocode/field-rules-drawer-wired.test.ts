// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, ref, type App, type Component, type ComponentPublicInstance } from 'vue'
import Antd from 'ant-design-vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import { FieldType } from '@/types/nocode/enums'
import { SelectionKind } from '@/types/nocode/selection'
import type { FieldOptions, FieldSwitchPreview, PublishedFieldBaseline } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'

/**
 * 字段抽屉的值来源配置，按对象编辑器的真实接线挂载：已保存字段 + 已发布基线 + 转换预检（previewSwitch）+ 转换应用。
 * 以前的值来源用例都不带这些入参，走的是没有转换复核的分支；线上对象编辑器永远带着它们。
 * 这里钉住：带着转换复核流程时，数据联动 / 公式默认值 / 挑取值照样能配、能写回。
 */
const api = vi.hoisted(() => ({ objects: vi.fn(), design: vi.fn(), version: vi.fn() }))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => api }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: vi.fn(async () => []) }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: vi.fn(async () => []) }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h: render } = await import('vue')
  return {
    default: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open ? render('div', { class: 'drawer-stub' }, slots.formItems?.()) : null
    })
  }
})
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: { render: () => null } }))

const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function field(type: string, id: string, name: string, patch: Partial<ObjectField> = {}): ObjectField {
  return { ...newField(0, name), key: id, id, code: id, type: type as ObjectField['type'], ...patch }
}
const options = (patch: Partial<FieldOptions> = {}): FieldOptions => ({ ...defaultFieldOptions(), ...patch })
function baseline(item: ObjectField, option: FieldOptions): PublishedFieldBaseline {
  return {
    type: item.type,
    length: item.length ?? null,
    precision: item.precision ?? null,
    scale: item.scale ?? null,
    required: !!item.required,
    unique: !!item.unique,
    selection: option.selection ?? null,
    targetObjectId: null,
    minimum: option.minimum ?? null,
    maximum: option.maximum ?? null,
    pattern: option.pattern ?? null,
    defaultValue: option.defaultValue ?? null,
    options: option.options ?? []
  }
}
const compatible = {
  decision: 'COMPATIBLE',
  deploymentState: 'DEPLOYED',
  impacts: [],
  totalRows: 3,
  valueRows: 0,
  failedRows: 0
} as unknown as FieldSwitchPreview

async function openDrawer(target: ObjectField, option: FieldOptions, others: ObjectField[] = []) {
  const instance = ref<ComponentPublicInstance>()
  const events = { options: vi.fn(), fields: vi.fn() }
  const wiring = {
    previewSwitch: vi.fn(async () => compatible),
    previewRows: vi.fn(),
    applyConversion: vi.fn(() => null),
    reviewOperation: vi.fn(async () => true),
    loadRelationTargets: vi.fn(async () => {})
  }
  const all = [...others, target]
  const props = reactive<Record<string, unknown>>({
    modelValue: all,
    options: Object.fromEntries(all.map(item => [item.key, item === target ? option : options()])),
    relations: [],
    objectId: 'voucher',
    revision: 'voucher:1:1',
    legacyAutoNumberIds: [],
    publishedBaselines: Object.fromEntries(
      all.map(item => [item.id!, baseline(item, item === target ? option : options())])
    ),
    relationTargets: [],
    canViewConversionRows: true,
    canClearColumn: true,
    ...wiring
  })
  const app = createApp({
    setup: () => () =>
      h(FieldDesigner as Component, {
        ref: instance,
        ...props,
        'onUpdate:options': events.options,
        'onUpdate:modelValue': events.fields
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  const state = (instance.value!.$ as unknown as { setupState: Record<string, any> }).setupState
  state.show(target)
  await flush()
  const drawer = host.querySelector<HTMLElement>('.drawer-stub')
  if (!drawer) throw new Error('字段抽屉没有打开')
  const saved = () => {
    const call = events.options.mock.calls.at(-1)
    if (!call) throw new Error('确定后没有写回字段配置')
    return (call[0] as Record<string, FieldOptions>)[target.key]!
  }
  return { drawer, state, events, wiring, saved }
}
const section = (state: Record<string, any>) =>
  state.valueSourceSection as { mode: string; validate: () => string } & Record<string, any>

beforeEach(() => {
  vi.clearAllMocks()
  vi.useRealTimers()
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
  api.objects.mockResolvedValue({
    list: [{ id: 'cash', objectName: '资金流水', objectCode: 'zjls', publishedVersion: 1, category: '财务' }],
    total: 1
  })
  api.design.mockResolvedValue({ publishedVersion: 1, draft: { objectName: '资金流水', objectCode: 'zjls' } })
  api.version.mockResolvedValue({
    definition: {
      fields: [
        field(FieldType.TEXT, 'bank', '开户行'),
        field(FieldType.MONEY, 'amount', '金额'),
        field(FieldType.SELECT, 'status', '凭证状态')
      ],
      fieldOptions: {
        status: options({ options: [{ code: 'ylr', label: '已录入', disabled: false }] })
      },
      relations: []
    }
  })
})
afterEach(async () => {
  await new Promise(resolve => setTimeout(resolve, 100))
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-modal-root, .ant-select-dropdown').forEach(node => node.remove())
  vi.unstubAllGlobals()
})

describe('对象编辑器接线下的值来源配置（已保存字段 + 已发布基线 + 转换预检）', () => {
  it('没有默认值的文本字段配数据联动：确定直接写回，不进入转换复核', async () => {
    const target = field(FieldType.TEXT, 'memo', '摘要')
    const { drawer, state, saved, wiring, events } = await openDrawer(target, options({ columnName: 'memo' }))
    const radios = Array.from(drawer.querySelectorAll<HTMLElement>('.value-source .ant-radio-button-wrapper'))
    expect(radios.map(node => node.textContent?.trim())).toEqual(['自定义', '数据联动', '公式编辑'])
    radios[1]!.click()
    await flush()
    expect(section(state).mode).toBe('LINKAGE')
    const linkage = { sourceObjectId: 'cash', conditions: [], valueFieldId: 'bank', multiRow: null, readOnly: true }
    state.editingOptions = { ...state.option, rules: { linkage } }
    await flush()
    expect(state.switchChanged).toBe(false)
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(state.switchReviewOpen).toBe(false)
    expect(saved().rules).toEqual({ linkage })
    expect(events.fields).toHaveBeenCalledTimes(1)
    expect(wiring.applyConversion).not.toHaveBeenCalled()
  })

  it('原有自定义默认值的字段改成数据联动：默认值被清掉属于配置变更，先过变更复核，确认后规则与清空的默认值一起写回', async () => {
    const target = field(FieldType.TEXT, 'memo', '摘要')
    const { drawer, state, saved, wiring } = await openDrawer(
      target,
      options({ columnName: 'memo', defaultValue: '无' })
    )
    Array.from(drawer.querySelectorAll<HTMLElement>('.value-source .ant-radio-button-wrapper'))[1]!.click()
    await flush()
    const linkage = { sourceObjectId: 'cash', conditions: [], valueFieldId: 'bank', multiRow: null, readOnly: true }
    state.editingOptions = { ...state.option, rules: { linkage } }
    await flush()
    expect(state.option.defaultValue ?? null).toBeNull()
    expect(state.switchChanged).toBe(true)
    state.save()
    await flush()
    // 第一次确定：打开变更影响复核，尚未写回。
    expect(state.switchReviewOpen).toBe(true)
    expect(state.open).toBe(true)
    await new Promise(resolve => setTimeout(resolve, 300))
    await flush()
    expect(wiring.previewSwitch).toHaveBeenCalled()
    expect(state.canApplySwitch).toBe(true)
    state.confirmSwitchReview()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(saved().rules).toEqual({ linkage })
    expect(saved().defaultValue ?? null).toBeNull()
  })

  it('金额字段配公式默认值：取整方式随规则写回；公式写了 round 被拦住', async () => {
    const price = field(FieldType.DECIMAL, 'price', '单价', { code: 'c_dj' })
    const qty = field(FieldType.INTEGER, 'qty', '数量', { code: 'c_sl' })
    const target = field(FieldType.MONEY, 'total', '合计', { precision: 18, scale: 0 })
    const { drawer, state, saved } = await openDrawer(target, options({ columnName: 'total' }), [price, qty])
    Array.from(drawer.querySelectorAll<HTMLElement>('.value-source .ant-radio-button-wrapper'))[2]!.click()
    await flush()
    expect(section(state).mode).toBe('FORMULA')
    state.editingOptions = { ...state.option, rules: { defaultFormula: 'round(c_dj * c_sl, 0)' } }
    await flush()
    state.save()
    await flush()
    expect(state.error).toContain('公式默认值')
    expect(state.open).toBe(true)
    state.editingOptions = { ...state.option, rules: { defaultFormula: 'c_dj * c_sl', rounding: 'HALF_UP' } }
    await flush()
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(saved().rules).toEqual({ defaultFormula: 'c_dj * c_sl', rounding: 'HALF_UP' })
  })

  it('单选字段的数据来源改成「挑取值」：选好来源对象与来源字段，过变更复核后写回，联动规则不丢', async () => {
    const target = field(FieldType.SELECT, 'state', '状态')
    const linkage = { sourceObjectId: 'cash', conditions: [], valueFieldId: 'status', multiRow: null, readOnly: true }
    const { state, saved, wiring } = await openDrawer(
      target,
      options({
        columnName: 'state',
        options: [{ code: 'a', label: '甲', disabled: false }],
        selection: {
          kind: SelectionKind.LOCAL_OPTIONS,
          directory: null,
          dictionaryType: null,
          rootIds: [],
          includeDescendants: false,
          organizationTypes: [],
          defaultMode: 'NONE'
        },
        rules: { linkage }
      })
    )
    // 数据来源下拉选「关联其它表单数据·挑取值」：对象编辑器接线下由字段抽屉接管并打开变更复核。
    const next = { ...target }
    const nextOptions = options({
      columnName: 'state',
      options: [],
      selection: {
        kind: SelectionKind.OBJECT_FIELD_OPTIONS,
        directory: null,
        dictionaryType: null,
        rootIds: [],
        includeDescendants: false,
        organizationTypes: [],
        defaultMode: 'NONE',
        sourceObjectId: null,
        sourceFieldId: null
      },
      rules: { linkage }
    })
    expect(await state.reviewSelectionSwitch(next, nextOptions)).toBe(false)
    await flush()
    expect(state.switchReviewOpen).toBe(true)
    expect(state.option.selection.kind).toBe(SelectionKind.OBJECT_FIELD_OPTIONS)
    // 复核弹窗里点「去配置」回到抽屉，补上来源对象与来源字段。
    state.switchReviewOpen = false
    state.editingOptions = {
      ...state.option,
      selection: { ...state.option.selection, sourceObjectId: 'cash', sourceFieldId: 'status' }
    }
    await flush()
    state.save()
    await flush()
    expect(state.switchReviewOpen).toBe(true)
    await new Promise(resolve => setTimeout(resolve, 300))
    await flush()
    expect(wiring.previewSwitch).toHaveBeenCalled()
    state.confirmSwitchReview()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(saved().selection).toEqual(
      expect.objectContaining({
        kind: SelectionKind.OBJECT_FIELD_OPTIONS,
        sourceObjectId: 'cash',
        sourceFieldId: 'status'
      })
    )
    expect(saved().rules).toEqual({ linkage })
    expect(saved().defaultValue ?? null).toBeNull()
  })

  it('已保存的金额字段在抽屉里改成文本：规则按新类型清理（取整方式不再保留），数据联动留下', async () => {
    const target = field(FieldType.MONEY, 'total', '合计', { precision: 18, scale: 0 })
    const linkage = { sourceObjectId: 'cash', conditions: [], valueFieldId: 'amount', multiRow: 'SUM', readOnly: true }
    const { state } = await openDrawer(
      target,
      options({ columnName: 'total', rules: { linkage: linkage as never, rounding: 'HALF_UP' } })
    )
    await state.changeType(FieldType.TEXT)
    await flush()
    expect(state.field.type).toBe(FieldType.TEXT)
    expect(state.switchReviewOpen).toBe(true)
    expect(state.option.rules).toEqual({ linkage })
    expect(state.option.rules).not.toHaveProperty('rounding')
  })
})
