import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { empty, event, find, findAll, flush, mount, table, text } from './renderer'
import { FieldType } from '@/types/nocode/enums'
import type { ViewQueryOptions } from '@/types/nocode/data-scope'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'

const calls = vi.hoisted(() => ({ preview: vi.fn(), runtime: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    applications: { previewSelection: calls.preview },
    runtime: { selection: calls.runtime }
  })
}))
vi.mock('ant-design-vue', () => ({
  message: { success: vi.fn() },
  Modal: { confirm: vi.fn() },
  Form: { useInjectFormItemContext: () => ({ id: ref(), onFieldChange: vi.fn() }) }
}))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/nocode/business-field-rules', () => ({ businessFieldRules: () => [] }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/views/nocode/application/components/BusinessDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/TinyPageDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FormObjectVersionNotice.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/PageFilterConfig.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ReportConfigEditor.vue', () => ({ default: empty }))
import ResourceManager from '@/views/nocode/application/components/ResourceManager.vue'
import ViewQueryEditor from '@/views/nocode/application/components/ViewQueryEditor.vue'
import RecordQueryField from '@/views/nocode/application/components/RecordQueryField.vue'

const ids = ['9007199254740993', '9007199254740994', '9007199254740995']
const options = ['流動資産', '固定資産', '流動負債'].map((label, index) => ({
  value: ids[index],
  label,
  code: null,
  path: null,
  parentValue: null,
  disabled: false,
  unavailable: false
}))
const objects = {
  account: {
    objectId: 'account',
    versionNo: 2,
    checksum: 'account-v2',
    definition: {
      objectId: 'account',
      objectName: '会计科目',
      fields: [
        { id: 'name', name: '科目名称', type: FieldType.TEXT },
        { id: 'category', name: '会计科目分类', type: FieldType.INTEGER },
        { id: 'element', name: '会计要素', type: FieldType.SELECT }
      ],
      fieldOptions: {},
      details: [],
      relations: [{ id: 'category-link', fieldId: 'category', kind: 'REFERENCE', targetObjectId: 'category' }]
    }
  },
  category: { objectId: 'category', versionNo: 1, checksum: 'category-v1', definition: {} }
} as unknown as Record<string, PublishedObject>
const fields = objects.account!.definition.fields
const pages: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.clearAllMocks()
  const result = { options, selected: [], total: 3, tree: false, defaultValue: null }
  calls.preview.mockResolvedValue(result)
  calls.runtime.mockResolvedValue(result)
})
afterEach(() => pages.splice(0).forEach(page => page.unmount()))
async function editor(query: ViewQueryOptions) {
  const model = ref(query)
  const page = mount(
    defineComponent({
      setup: () => () =>
        h(ViewQueryEditor, {
          modelValue: model.value,
          'onUpdate:modelValue': value => {
            model.value = value
          },
          fields,
          applicationId: 'app',
          objectId: 'account',
          objects,
          choices: id => (id === 'element' ? [{ label: '资产', value: 'ASSET' }] : [])
        })
    })
  )
  pages.push(page)
  await flush()
  return { ...page, model }
}

describe('数据视图对象引用固定范围', () => {
  it('使用草稿引用版本读取真实分类，切换属于任意后保存多个稳定 ID', async () => {
    const page = await editor({
      fixed: [{ fieldId: 'category', operator: 'eq', value: null }],
      defaults: {},
      candidates: {}
    })
    expect(calls.preview.mock.calls[0]![0]).toMatchObject({
      query: { applicationId: 'app', objectId: 'account', fieldId: 'category' },
      objects: [
        { objectId: 'account', versionNo: 2, checksum: 'account-v2' },
        { objectId: 'category', versionNo: 1, checksum: 'category-v1' }
      ]
    })
    expect(calls.runtime).not.toHaveBeenCalled()
    const match = find(page.root, 'a-select', n => n.props['aria-label'] === '范围匹配方式')
    await event(match, 'onUpdate:value', 'in')
    await event(match, 'onChange', 'in')
    const selector = find(page.root, 'a-select', n => n.props.placeholder === '请选择固定范围')
    expect(selector.props.mode).toBe('multiple')
    expect(selector.props.options.map((o: any) => o.label)).toEqual(['流動資産', '固定資産', '流動負債'])
    await event(selector, 'onChange', ids.slice(0, 2))
    expect(page.model.value.fixed[0]).toMatchObject({ operator: 'in', value: ids.slice(0, 2) })
  })

  it('默认查询和候选范围也能按名称选择，清空候选保留固定数据范围', async () => {
    const page = await editor({
      fixed: [{ fieldId: 'category', operator: 'in', value: ids.slice(0, 2) }],
      defaults: { category: null },
      candidates: { category: [] }
    })
    await event(
      find(page.root, 'a-select', n => n.props.placeholder === '默认查询值'),
      'onChange',
      ids[0]
    )
    const candidates = find(page.root, 'a-select', n => n.props.placeholder === '允许选择的值（留空表示无候选）')
    await event(candidates, 'onChange', ids.slice(0, 2))
    expect(page.model.value.defaults.category).toBe(ids[0])
    expect(page.model.value.candidates.category).toEqual(ids.slice(0, 2))
    await event(candidates, 'onChange', [])
    expect(page.model.value.candidates.category).toEqual([])
    expect(page.model.value.fixed[0]!.value).toEqual(ids.slice(0, 2))
  })

  it('重新打开时通过 selected 解析分页外的已选分类名称', async () => {
    calls.preview.mockImplementation(async ({ query }) => ({
      options: [],
      selected: options.filter(o => query.selected.includes(o.value)),
      total: 60,
      tree: false,
      defaultValue: null
    }))
    const page = await editor({
      fixed: [{ fieldId: 'category', operator: 'in', value: ids.slice(0, 2) }],
      defaults: {},
      candidates: {}
    })
    const selector = find(page.root, 'a-select', n => n.props.placeholder === '请选择固定范围')
    expect(calls.preview.mock.calls[0]![0].query.selected).toEqual(ids.slice(0, 2))
    expect(selector.props.options.map((o: any) => o.label)).toEqual(['流動資産', '固定資産'])
  })

  it('静态字典与普通文本保留原控件，空值条件不显示选择器', async () => {
    const page = await editor({
      fixed: [
        { fieldId: 'name', operator: 'eq', value: '现金' },
        { fieldId: 'element', operator: 'in', value: ['ASSET'] },
        { fieldId: 'category', operator: 'isNull', value: null }
      ],
      defaults: {},
      candidates: {}
    })
    expect(find(page.root, 'a-input', n => n.props['aria-label'] === '范围值').props.value).toBe('现金')
    expect(find(page.root, 'a-select', n => n.props['aria-label'] === '范围值').props.options).toEqual([
      { label: '资产', value: 'ASSET' }
    ])
    expect(calls.preview).not.toHaveBeenCalled()
  })

  it('正式资源配置可应用到草稿并重开，分类 ID 和多选方式不丢失', async () => {
    const resources = ref<ApplicationResource[]>([
      {
        id: 'view',
        kind: 'VIEW',
        name: '会计科目列表',
        code: 'account_view',
        config: {
          objectId: 'account',
          fieldIds: ['name', 'category'],
          equal: {},
          query: { fixed: [{ fieldId: 'category', operator: 'in', value: [] }], defaults: {}, candidates: {} }
        }
      }
    ])
    const page = mount(
      defineComponent({
        setup: () => () =>
          h(ResourceManager, {
            modelValue: resources.value,
            'onUpdate:modelValue': value => {
              resources.value = value
            },
            objects,
            applicationId: 'app',
            readOnly: false
          })
      })
    )
    pages.push(page)
    const open = () =>
      event(
        find(page.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
    await open()
    await event(
      find(page.root, 'a-select', n => n.props.placeholder === '请选择固定范围'),
      'onChange',
      ids.slice(0, 2)
    )
    await event(
      find(page.root, 'a-modal', n => n.props['ok-text'] === '应用到草稿'),
      'onOk'
    )
    expect((resources.value[0]!.config.query as ViewQueryOptions).fixed[0]!.value).toEqual(ids.slice(0, 2))
    await open()
    expect(find(page.root, 'a-select', n => n.props.placeholder === '请选择固定范围').props.value).toEqual(
      ids.slice(0, 2)
    )
    expect(findAll(page.root, n => n.type === 'a-alert' && n.props.type === 'error')).toHaveLength(0)
  })

  it('运行查询候选只显示固定分类，清空查询后仍保持候选限制', async () => {
    const value = ref<any>(ids[0])
    const page = mount(
      defineComponent({
        setup: () => () =>
          h(RecordQueryField, {
            modelValue: value.value,
            'onUpdate:modelValue': v => {
              value.value = v
            },
            applicationId: 'app',
            objectId: 'account',
            field: fields[1]!,
            reference: true,
            choices: [],
            allowedValues: ids.slice(0, 2)
          })
      })
    )
    pages.push(page)
    await flush()
    const selector = find(page.root, 'a-select', n => n.props.placeholder === '全部')
    expect(selector.props.options.map((o: any) => o.label)).toEqual(['流動資産', '固定資産'])
    await event(selector, 'onChange', [])
    expect(value.value).toEqual([])
    expect(selector.props.options.map((o: any) => o.value)).toEqual(ids.slice(0, 2))
  })
})
