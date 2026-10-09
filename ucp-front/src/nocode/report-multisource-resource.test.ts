// @vitest-environment jsdom
// 「应用到草稿」这一层（ResourceManager）：照 configuration-resource-manager.test.ts 的内存渲染与替身写法。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, find, event, text, table, empty, type TestNode } from '../../tools/selection-regression/renderer'
import ResourceManager from '@/views/nocode/application/components/ResourceManager.vue'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig } from '@/types/nocode/report'
import { example1Config, multiObjects } from './report-multisource-fixture'

const calls = vi.hoisted(() => ({
  confirm: vi.fn(),
  success: vi.fn(),
  report: undefined as undefined | { modelValue: ReportConfig }
}))
// dev 的 RecordFormContainer 在模块加载时展开 antd 的 Form（record-form-container.ts）；本文件不渲染表单，给个空对象即可。
vi.mock('ant-design-vue', () => ({ message: { success: calls.success }, Modal: { confirm: calls.confirm }, Form: {} }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: () => undefined }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/views/nocode/application/components/BusinessDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/TinyPageDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FormObjectVersionNotice.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/DetailFormSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/RelatedFormSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/DataViewSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/PageFilterConfig.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ViewQueryEditor.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ReportConfigEditor.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      props: ['modelValue', 'objects'],
      setup(props) {
        calls.report = props
        return () => null
      }
    })
  }
})

function report(config: ReportConfig): ApplicationResource {
  return { id: 'report', kind: ResourceKind.REPORT, name: '利润统计', code: 'profit', config: config as never }
}
let page: ReturnType<typeof mount> | undefined
async function open(resource: ApplicationResource) {
  const resources = ref([resource]),
    changed = vi.fn()
  page = mount(
    defineComponent({
      setup: () => () =>
        h(ResourceManager, {
          modelValue: resources.value,
          'onUpdate:modelValue': (value: ApplicationResource[]) => {
            resources.value = value
          },
          objects: multiObjects,
          readOnly: false,
          applicationId: 'app',
          onChange: changed
        })
    })
  )
  const root = page.root
  await event(find(root, 'a-segmented'), 'onUpdate:value', resource.kind)
  await event(
    find(root, 'a-button', node => text(node).trim() === '配置'),
    'onClick'
  )
  return { root, resources, changed }
}
const modal = (root: TestNode) => find(root, 'a-modal', node => node.props['ok-text'] === '应用到草稿')
beforeEach(() => {
  vi.clearAllMocks()
  calls.report = undefined
})
afterEach(() => {
  page?.unmount()
  page = undefined
})

describe('F7 多来源切到柱状图时「应用到草稿」禁用', () => {
  it('透视表可应用；同一份配置展示方式换成柱状图 ⇒ 按钮禁用（来源不删）', async () => {
    const { root } = await open(report(example1Config()))
    expect(modal(root).props['ok-button-props']).toEqual({ disabled: false })
    calls.report!.modelValue.display = 'BAR'
    await Promise.resolve()
    expect(modal(root).props['ok-button-props']).toEqual({ disabled: true })
    expect(calls.report!.modelValue.extraSources).toHaveLength(2)
  })
  it('把编辑器交给的对象原样传下去：只有应用已引用的对象', async () => {
    await open(report(example1Config()))
    expect(Object.keys((calls.report as unknown as { objects: object }).objects).sort()).toEqual(
      Object.keys(multiObjects).sort()
    )
  })
})

describe('应用到草稿：多来源校验与保存整理', () => {
  it('维度对应缺一位 ⇒ 拒绝应用并给 L6；补齐后应用，保存出的配置不带推得出来的筛选对应', async () => {
    const config = example1Config()
    config.extraSources![1].columnDimensions = [{ fieldId: '', relationPath: null, bucket: 'MONTH' }]
    const { root, resources, changed } = await open(report(config))
    await event(modal(root), 'onOk')
    expect(find(root, 'a-alert', node => node.props.type === 'error').props.message).toBe(
      '来源「支出」要为每个行维度、列维度各指定一个对应字段'
    )
    expect(changed).not.toHaveBeenCalled()
    calls.report!.modelValue.extraSources![1].columnDimensions = [
      { fieldId: 'expense_paid_on', relationPath: null, bucket: 'MONTH' }
    ]
    calls.report!.modelValue.filterFieldIds = ['stay_property']
    calls.report!.modelValue.extraSources![1].filterTargets = { stay_property: 'expense_property' }
    await event(modal(root), 'onOk')
    expect(changed).toHaveBeenCalledTimes(1)
    const saved = resources.value[0].config as unknown as ReportConfig
    expect(saved.extraSources![1]).not.toHaveProperty('filterTargets')
    expect(saved.extraSources!.map(s => s.id)).toEqual(['s2', 's3'])
  })
})

describe('来源 1 换数据对象', () => {
  it('附加来源不动、维度对应随来源 1 的维度一并清空；附加来源的指标保留，计算指标去掉', async () => {
    const { root } = await open(report(example1Config()))
    const objectSelect = find(root, 'a-select', node => node.props.value === 'stay' && !!node.props.onChange)
    await event(objectSelect, 'onUpdate:value', 'expense')
    await event(objectSelect, 'onChange', 'expense')
    const config = calls.report!.modelValue
    expect(config.objectId).toBe('expense')
    expect(config.display).toBe('PIVOT')
    expect(config.dimensions).toEqual([])
    expect(config.sourceName).toBe('当月')
    expect(config.extraSources!.map(s => [s.id, s.objectId, s.dimensions.length, s.columnDimensions])).toEqual([
      ['s2', 'stay', 0, null],
      ['s3', 'expense', 0, null]
    ])
    expect(config.metrics.map(m => m.id)).toEqual(['count', 'nxt', 'exp'])
  })
})
