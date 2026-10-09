// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { event, find, flush, mount, text } from '../../tools/selection-regression/renderer'
import DetailFormSettings from '@/views/nocode/application/components/DetailFormSettings.vue'
import type { ApplicationResource, PublishedDefinition } from '@/types/nocode/application'
import type { ObjectDetail } from '@/types/nocode/data-center'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'

vi.mock('@/views/nocode/application/components/SelectionPresentationEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      inheritAttrs: false,
      setup:
        (_, { attrs }) =>
        () =>
          h('selection-editor', attrs)
    })
  }
})
vi.mock('@/views/nocode/application/components/FieldBehaviorEditor.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      inheritAttrs: false,
      setup:
        (_, { attrs }) =>
        () =>
          h('behavior-editor', attrs)
    })
  }
})
const field = (id: string, type: FieldType = FieldType.INTEGER) => ({ id, name: id, type })
const relation = (id: string, sourceDetailId?: string) => ({
  fieldId: id,
  kind: RelationType.REFERENCE,
  name: id,
  targetObjectId: id + '_target',
  sourceDetailId
})
const detail = {
  id: 'lines',
  name: '项目明细',
  fields: [field('category'), field('item'), field('memo', FieldType.TEXT), field('inactive')],
  fieldOptions: { inactive: { state: MemberState.INACTIVE } }
} as unknown as ObjectDetail
const definition = {
  objectId: 'document',
  fields: [field('parentVisible'), field('parentHidden')],
  fieldOptions: {},
  relations: [
    relation('parentVisible'),
    relation('parentHidden'),
    relation('category', 'lines'),
    relation('item', 'lines'),
    relation('otherItem', 'other')
  ],
  details: [detail]
} as unknown as PublishedDefinition
const resources: ApplicationResource[] = [
  { id: 'view', code: 'view', name: '物品视图', kind: 'VIEW', config: { objectId: 'item_target' } }
]
let page: ReturnType<typeof mount> | undefined
afterEach(() => page?.unmount())
function setup(initial?: UiNode[]) {
  const nodes = ref(initial)
  page = mount(
    defineComponent({
      setup: () => () =>
        h(DetailFormSettings, {
          definition,
          detail,
          objects: {},
          resources,
          formFieldIds: ['parentVisible'],
          modelValue: nodes.value,
          'onUpdate:modelValue': (value: UiNode[] | undefined) => {
            nodes.value = value
          }
        })
    })
  )
  return { root: page.root, nodes }
}
const node = (fieldId: string) => uiNode(NodeKind.FIELD, { fieldId, id: fieldId, presentation: {} })

describe('明细列配置复用表单字段能力', () => {
  it('候选联动分组包含本行和实际主表字段，限定视图得到应用资源，不再提供关联带入入口', async () => {
    const { root } = setup([node('category'), node('item'), node('memo')])
    await flush()
    await event(
      find(root, 'a-button', b => text(b) === 'item'),
      'onClick'
    )
    const selection = find(root, 'selection-editor').props
    expect(selection.resources).toEqual(resources)
    expect(selection['source-groups']).toEqual([
      { label: '本行字段', fieldIds: ['category', 'item', 'memo'] },
      { label: '主表字段', fieldIds: ['parentVisible'] }
    ])
    expect(selection['available-field-ids']).toEqual(['category', 'item', 'memo', 'parentVisible'])
    expect(selection.definition.relations.map((r: { fieldId: string }) => r.fieldId)).toEqual([
      'parentVisible',
      'parentHidden',
      'category',
      'item'
    ])
    // 表单「关联带入」入口已撤除（引用/联动统一配在数据对象）：明细列配置里不再出现带入编辑器。
    expect(text(root)).not.toContain('关联带入')
    expect(find(root, 'behavior-editor').props.fields.map((f: { id: string }) => f.id)).toEqual([
      'category',
      'item',
      'memo'
    ])
  })
  it('列移动保留选中字段及其规则；恢复默认不会遗留自定义节点', async () => {
    const original = [node('category'), node('item')]
    original[1]!.presentation!.selection = { linkFieldId: 'category', linkTargetFieldId: 'parent' }
    const { root, nodes } = setup(original)
    await event(
      find(root, 'a-button', b => text(b) === 'item'),
      'onClick'
    )
    await event(
      find(root, 'a-button', b => text(b) === '前移'),
      'onClick'
    )
    expect(nodes.value?.map(n => n.fieldId)).toEqual(['item', 'category'])
    expect(find(root, 'selection-editor').props.modelValue).toEqual({
      linkFieldId: 'category',
      linkTargetFieldId: 'parent'
    })
    await event(
      find(root, 'a-button', b => text(b) === '恢复对象默认字段'),
      'onClick'
    )
    expect(nodes.value).toBeUndefined()
  })
  it('从默认字段创建明细配置时排除停用列', async () => {
    const { root, nodes } = setup()
    await event(
      find(root, 'a-button', b => text(b) === '自定义明细列'),
      'onClick'
    )
    expect(nodes.value?.map(n => n.fieldId)).toEqual(['category', 'item', 'memo'])
  })
})
