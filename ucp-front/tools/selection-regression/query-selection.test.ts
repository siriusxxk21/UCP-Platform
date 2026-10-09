import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, find, event } from './renderer'
import { FieldType } from '@/types/nocode/enums'
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({
  default: defineComponent({
    setup(_, { attrs }) {
      return () => h('live-selection', attrs)
    }
  })
}))
vi.mock('@ant-design/icons-vue', () => ({
  PlusOutlined: () => h('icon'),
  DeleteOutlined: () => h('icon'),
  GroupOutlined: () => h('icon')
}))
import RecordQueryField from '@/views/nocode/application/components/RecordQueryField.vue'
import ReportFilterInput from '@/views/nocode/application/components/ReportFilterInput.vue'
import OsConditionGroupRenderer from '@/components/ucp-table-page/OsConditionGroupRenderer.vue'
const pages: ReturnType<typeof mount>[] = []
afterEach(() => pages.splice(0).forEach(p => p.unmount()))
describe('查询使用业务选择器', () => {
  it('统计目录筛选复用真实选择器并提交稳定身份', async () => {
    const field = { id: 'department', type: FieldType.DEPARTMENT, name: '部门' }
    const changed = vi.fn()
    const page = mount(ReportFilterInput, {
      applicationId: '1',
      field,
      model: { object: { objectId: '2', relations: [], fieldOptions: {} } },
      'onUpdate:modelValue': changed
    })
    pages.push(page)
    const input = find(page.root, 'live-selection')
    expect(input.props).toMatchObject({ 'object-id': '2', 'field-id': 'department' })
    await event(input, 'onUpdate:modelValue', '9007199254740993')
    expect(changed).toHaveBeenCalledWith('9007199254740993')
  })

  it.each([false, true])('对象单选和多选不要求输入 ID（多选=%s）', multiple => {
    const page = mount(RecordQueryField, {
      applicationId: '1',
      objectId: '2',
      reference: true,
      field: { id: 'f', name: '资产类型', type: multiple ? FieldType.MULTI_SELECT : FieldType.INTEGER },
      choices: []
    })
    pages.push(page)
    expect(find(page.root, 'live-selection').props).toMatchObject({
      'application-id': '1',
      'object-id': '2',
      'field-id': 'f',
      multiple
    })
  })
  it('高级条件复用动态选择器并随属于任一切换多选', async () => {
    const selector = defineComponent({
      emits: ['update:modelValue'],
      setup(_, { attrs, emit }) {
        return () => h('async-selection', { ...attrs, onChange: (value: unknown) => emit('update:modelValue', value) })
      }
    })
    const item = { id: 'c', type: 'condition', field: 'user', operator: 'in', value: [] }
    const page = mount(OsConditionGroupRenderer, {
      group: { id: 'g', type: 'group', logic: 'AND', items: [item] },
      fields: [
        {
          field: 'user',
          label: '用户',
          type: 'select',
          operators: ['eq', 'neq', 'in'],
          valueComponent: selector,
          valueProps: { fieldId: 'user' }
        }
      ],
      fieldOptions: [{ value: 'user', label: '用户' }]
    })
    pages.push(page)
    const field = find(page.root, 'async-selection')
    expect(field.props).toMatchObject({ multiple: true, fieldId: 'user' })
    await event(field, 'onChange', ['12', '13'])
    expect(item.value).toEqual(['12', '13'])
  })
})
