// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, provide, ref } from 'vue'
import { event, find, findAll, flush, mount } from '../../tools/selection-regression/renderer'
import SelectionPresentationEditor from '@/views/nocode/application/components/SelectionPresentationEditor.vue'
import type { PublishedDefinition, PublishedObject } from '@/types/nocode/application'
import type { SelectionPresentation } from '@/types/nocode/selection'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'

vi.mock('ant-design-vue', async () => {
  const { ref } = await import('vue')
  return { Form: { useInjectFormItemContext: () => ({ id: ref('selector'), onFieldChange: vi.fn() }) } }
})
const field = (id: string) => ({ id, name: id, type: FieldType.INTEGER })
const relation = (fieldId: string, targetObjectId: string) => ({
  fieldId,
  kind: RelationType.REFERENCE,
  targetObjectId
})
const definition = {
  objectId: 'formObject',
  fields: ['parent', 'rowCategory', 'hidden', 'item'].map(field),
  fieldOptions: {},
  relations: [
    relation('parent', 'category'),
    relation('rowCategory', 'category'),
    relation('hidden', 'category'),
    relation('item', 'catalog')
  ]
} as unknown as PublishedDefinition
const catalog = {
  objectId: 'catalog',
  fields: ['category', 'unrelated', 'numeric', 'inactive'].map(field),
  fieldOptions: { inactive: { state: MemberState.INACTIVE } },
  relations: [relation('category', 'category'), relation('unrelated', 'other'), relation('inactive', 'category')]
} as unknown as PublishedDefinition
let page: ReturnType<typeof mount> | undefined
afterEach(() => page?.unmount())

describe('联动属性面板', () => {
  it('区分本行与主表，排除未放入表单的来源，匹配字段仅提供同目标引用', async () => {
    const presentation = ref<SelectionPresentation>({ linkFieldId: 'rowCategory', linkTargetFieldId: 'category' })
    page = mount(
      defineComponent({
        setup() {
          provide(nocodePlatformKey, { applications: {} } as NocodePlatform)
          return () =>
            h(SelectionPresentationEditor, {
              modelValue: presentation.value,
              'onUpdate:modelValue': value => {
                presentation.value = value
              },
              definition,
              fieldId: 'item',
              objects: { catalog: { definition: catalog } as PublishedObject },
              availableFieldIds: ['rowCategory', 'parent', 'item'],
              sourceGroups: [
                { label: '本行字段', fieldIds: ['rowCategory', 'item'] },
                { label: '主表字段', fieldIds: ['parent'] }
              ],
              resources: [
                {
                  id: 'catalog_view',
                  kind: 'VIEW',
                  name: '有效物品',
                  code: 'catalog_view',
                  config: { objectId: 'catalog' }
                },
                { id: 'other_view', kind: 'VIEW', name: '其他资料', code: 'other_view', config: { objectId: 'other' } }
              ]
            })
        }
      })
    )
    await flush()
    const root = page.root
    const sources = find(root, 'a-select', n => n.props.placeholder === '不联动')
    expect(sources.props.options).toEqual([
      { label: '本行字段', options: [{ label: 'rowCategory', value: 'rowCategory' }] },
      { label: '主表字段', options: [{ label: 'parent', value: 'parent' }] }
    ])
    expect(find(root, 'a-select', n => n.props.placeholder === '选择与来源兼容的字段').props.options).toEqual([
      { value: 'category', label: 'category' }
    ])
    expect(find(root, 'a-select', n => n.props.placeholder === '不限').props.options).toEqual([
      { value: 'catalog_view', label: '有效物品' }
    ])
    await event(sources, 'onChange', 'parent')
    expect(presentation.value).toEqual({ linkFieldId: 'parent', linkTargetFieldId: null })
    presentation.value = { linkFieldId: 'hidden', linkTargetFieldId: 'unrelated' }
    await flush()
    const warnings = findAll(root, n => n.type === 'a-alert').map(n => n.props.message)
    expect(warnings).toContain('联动来源已失效或未放入当前表单，请重新选择或清空')
    expect(warnings).toContain('关联筛选字段已失效或与来源不兼容，请重新选择')
  })
})
