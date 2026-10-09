// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { event, find, flush, modal, mount, table, text, empty } from '../../tools/selection-regression/renderer'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import type { PublishedFieldBaseline } from '@/types/nocode/data-center'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { newFieldRelation } from './relation-editing'

vi.mock('ant-design-vue', () => ({ message: { warning: vi.fn() }, Modal: { confirm: vi.fn() } }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({ default: modal }))
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: empty }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ hasPermission: () => true, dataCenter: {} }) }))

const mounted: Array<ReturnType<typeof mount>> = []
afterEach(() => {
  mounted.splice(0).forEach(instance => instance.unmount())
  vi.useRealTimers()
})
function fixture(type: 'INTEGER' | 'TEXT' | 'REFERENCE' = 'INTEGER') {
  const field = { ...newField(0, '资金流水'), id: '11', key: '11', code: 'cash_id', type, length: null }
  const option = { ...defaultFieldOptions(), generated: type !== 'REFERENCE', columnName: 'cash_id' }
  const relation = { ...newFieldRelation(field), id: '21', fieldId: field.id, targetObjectId: '31' }
  const baseline: PublishedFieldBaseline = {
    type,
    length: null,
    precision: null,
    scale: null,
    required: false,
    unique: false,
    selection: null,
    targetObjectId: '31',
    minimum: null,
    maximum: null,
    pattern: null
  }
  return {
    modelValue: [field] as [typeof field],
    options: { [field.key]: option },
    relations: [relation] as [typeof relation],
    publishedBaselines: { '11': baseline }
  }
}
function render(props: Record<string, unknown>) {
  const instance = mount(FieldDesigner, props)
  mounted.push(instance)
  return instance.root
}

describe('字段列表发布状态', () => {
  it.each(['INTEGER', 'TEXT', 'REFERENCE'] as const)('%s 存储的原有引用未变更时不显示待发布', type => {
    const root = render(fixture(type))
    expect(text(root)).toContain('单选（对象引用）')
    expect(text(root)).not.toContain('待发布变更')
  })
  it('修改关系目标后仍显示真正的待发布变更', () => {
    const props = fixture()
    props.relations[0].targetObjectId = '32'
    expect(text(render(props))).toContain('待发布变更')
  })
  it('对象引用改回普通列仍显示待发布变更', () => {
    const props = fixture('REFERENCE')
    props.modelValue[0].type = 'TEXT'
    props.relations.splice(0)
    expect(text(render(props))).toContain('待发布变更')
  })
  it('草稿中真实约束变化仍显示待发布，结构保护保持有效', () => {
    const props = fixture()
    props.relations[0].required = true
    const root = render(props)
    expect(text(root)).toContain('待发布变更')
    expect(find(root, 'a-tooltip').props.title).toContain('由系统或对象关系自动维护')
  })
  it('已发布只读页面不显示编辑提示，查看配置不调用转换预检', async () => {
    vi.useFakeTimers()
    const props = fixture()
    // 即使历史快照存在格式差异，只读查看也不应被当作编辑或发起转换检查。
    props.publishedBaselines['11'].length = 200
    const previewSwitch = vi.fn()
    const root = render({ ...props, readOnly: true, previewSwitch })
    expect(text(root)).not.toContain('待发布变更')
    expect(text(root)).not.toContain('结构调整受限')
    await event(
      find(root, 'a-button', node => text(node).trim() === '查看'),
      'onClick'
    )
    await vi.advanceTimersByTimeAsync(300)
    await flush()
    expect(previewSwitch).not.toHaveBeenCalled()
  })
})
