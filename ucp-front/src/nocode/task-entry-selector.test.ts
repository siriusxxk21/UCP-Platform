// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, shallowReactive, type App } from 'vue'
import TaskEntrySelector from '@/views/nocode/task-center/TaskEntrySelector.vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { TaskEntryCandidate } from './task-entry-selection'
const api = vi.hoisted(() => ({
  mine: vi.fn(),
  context: vi.fn(),
  applications: vi.fn(),
  application: vi.fn(),
  model: vi.fn()
}))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => api }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ runtime: { mine: api.applications, application: api.application, model: api.model } })
}))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title'],
    setup:
      (p, { slots }) =>
      () =>
        p.open ? h('section', [h('h2', p.title), h('main', slots.formItems?.()), h('footer', slots.footer?.())]) : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'selectedRowKeys', 'rowSelection', 'columns'],
    emits: ['selectionChange'],
    setup:
      (p, { emit }) =>
      () =>
        h('div', [
          h('header', p.columns.map((column: { title: string }) => column.title).join(' / ')),
          ...p.dataSource.map((row: TaskEntryCandidate) =>
            h('label', [
              h('input', {
                type: p.rowSelection.type || 'checkbox',
                name: 'business-form',
                checked: p.selectedRowKeys.includes(row.id),
                disabled: p.rowSelection.getCheckboxProps(row).disabled,
                onChange: (event: Event) =>
                  emit(
                    'selectionChange',
                    (event.target as HTMLInputElement).checked
                      ? [...p.selectedRowKeys, row.id]
                      : p.selectedRowKeys.filter((id: string) => id !== row.id)
                  )
              }),
              row.name,
              row.status,
              row.objectName,
              row.applicationName
            ])
          )
        ])
  })
}))
let app: App, host: HTMLElement
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const click = async (text: string) => {
  Array.from(host.querySelectorAll('button'))
    .find(el => el.textContent === text)!
    .click()
  await flush()
}
const selectApp = async (id: string) => {
  const el = host.querySelector('select')!
  el.value = id
  el.dispatchEvent(new Event('change'))
  await flush()
}
const checkbox = (text: string) =>
  Array.from(host.querySelectorAll('label'))
    .find(el => el.textContent?.includes(text))!
    .querySelector('input')!
const toggle = async (text: string) => {
  checkbox(text).click()
  await flush()
}
const existing = (): TaskWorkEntryConfig => ({
  key: 'stable',
  name: '原名称',
  binding: { applicationId: 'a', viewId: 'form-view', formId: 'form', entryId: null },
  dataMode: 'INDEPENDENT',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: ['field'],
  writableFieldIds: [],
  required: true,
  allowAll: true
})
async function mount(entries: TaskWorkEntryConfig[] = [], overrides: Record<string, unknown> = {}) {
  const confirm = vi.fn(),
    cancel = vi.fn(),
    catalog = vi.fn()
  const props = shallowReactive({
    open: true,
    entries,
    onConfirm: confirm,
    onCancel: cancel,
    onCatalog: catalog,
    ...overrides
  })
  app = createApp(() => h(TaskEntrySelector, props))
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ASpace', 'ATypographyText', 'ATag']) app.component(name, plain)
  app.component('AEmpty', defineComponent({ props: ['description'], setup: p => () => h('p', p.description) }))
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', [p.message, slots.action?.()])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      emits: ['click'],
      setup:
        (p, { slots, emit }) =>
        () =>
          h('button', { disabled: p.disabled || p.loading, onClick: () => emit('click') }, slots.default?.())
    })
  )
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            'data-search': 'true',
            value: p.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              value: p.value || '',
              disabled: p.disabled,
              onChange: (e: Event) => emit('update:value', (e.target as HTMLSelectElement).value || undefined)
            },
            [
              h('option', { value: '' }, '全部'),
              ...p.options.map((o: { value: string; label: string }) => h('option', { value: o.value }, o.label))
            ]
          )
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { confirm, cancel, catalog, props }
}
beforeEach(() => {
  api.applications.mockResolvedValue([
    { id: 'a', name: '应用甲' },
    { id: 'b', name: '应用乙' }
  ])
  api.mine.mockResolvedValue([
    { applicationId: 'a', entryId: 'entry', name: '甲列表', applicationName: '应用甲', mode: 'LIST' },
    { applicationId: 'b', entryId: 'other', name: '乙列表', applicationName: '应用乙', mode: 'LIST' },
    { applicationId: 'a', entryId: 'direct', name: '纯填写入口', applicationName: '应用甲', mode: 'FORM' }
  ])
  api.application.mockImplementation(async id => ({
    definition: {
      resources: [
        { id: 'form', kind: 'FORM', name: `${id}表单`, config: { objectId: 'object' } },
        { id: 'form-view', kind: 'VIEW', name: `${id}表单`, config: { objectId: 'object', formId: 'form' } }
      ]
    }
  }))
  api.model.mockResolvedValue({
    object: { objectName: '测试对象' },
    writable: true,
    permissions: { actions: ['CREATE', 'UPDATE'] }
  })
  api.context.mockResolvedValue({
    config: { mode: 'LIST', formId: 'form' },
    entry: { applicationName: '应用甲' },
    resources: [{ id: 'form', kind: 'FORM', name: 'a表单' }],
    model: { object: { objectName: '测试对象' } }
  })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.resetAllMocks()
})
describe('反馈表单多选抽屉', () => {
  it('跨对象联合表单对应视图提前禁用，普通引用字段和内部明细仍可办理', async () => {
    api.application.mockResolvedValue({
      definition: {
        resources: [
          {
            id: 'related-form',
            kind: 'FORM',
            name: '联合表单',
            config: {
              objectId: 'object',
              relatedForms: [
                {
                  id: 'binding',
                  sourceObjectId: 'object',
                  relationId: 'relation',
                  direction: 'OUTGOING',
                  formId: 'room-form',
                  title: '房间资料'
                }
              ]
            }
          },
          {
            id: 'related-view',
            kind: 'VIEW',
            name: '联合办理视图',
            config: { objectId: 'object', formId: 'related-form' }
          },
          {
            id: 'form',
            kind: 'FORM',
            name: '普通表单',
            config: {
              objectId: 'object',
              nodes: [{ id: 'reference-field', type: 'FIELD', fieldId: 'room-reference' }],
              detailIds: ['items'],
              detailNodes: { items: [{ id: 'item-field', type: 'FIELD', fieldId: 'item-name' }] },
              relatedForms: []
            }
          },
          { id: 'form-view', kind: 'VIEW', name: '本对象办理视图', config: { objectId: 'object', formId: 'form' } }
        ]
      }
    })
    const { confirm } = await mount([], { applicationId: 'a' })
    expect(checkbox('联合办理视图').disabled).toBe(true)
    expect(host.textContent).toContain('请把关联对象配置为独立办理项')
    expect(checkbox('本对象办理视图').disabled).toBe(false)
    await toggle('联合办理视图')
    await toggle('本对象办理视图')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0].map((entry: TaskWorkEntryConfig) => entry.binding?.viewId)).toEqual(['form-view'])
  })
  it('旧无视图关联的联合表单保留原路径，不因新视图限制丢弃或转换配置', async () => {
    const original = { ...existing(), binding: { applicationId: 'a', viewId: null, entryId: null, formId: 'form' } }
    api.application.mockResolvedValue({
      definition: {
        resources: [
          {
            id: 'form',
            kind: 'FORM',
            name: '旧联合表单',
            config: {
              objectId: 'object',
              relatedForms: [
                {
                  id: 'binding',
                  sourceObjectId: 'object',
                  relationId: 'relation',
                  direction: 'OUTGOING',
                  formId: 'room-form',
                  title: '房间资料'
                }
              ]
            }
          }
        ]
      }
    })
    const { confirm } = await mount([original], { applicationId: 'a' })
    expect(checkbox('原名称').checked).toBe(true)
    expect(host.textContent).not.toContain('暂不支持跨对象联合录入')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0][0]).toBe(original)
    expect(original.binding.viewId).toBeNull()
  })
  it.each([
    ['fixed', 'CURRENT_USER'],
    ['fixed', 'CURRENT_DEPARTMENT'],
    ['fixed', 'CURRENT_DEPARTMENT_TREE'],
    ['scope', 'CURRENT_USER'],
    ['scope', 'CURRENT_DEPARTMENT'],
    ['scope', 'CURRENT_DEPARTMENT_TREE']
  ])('%s 中动态身份范围 %s 在选择时禁用并说明，不误禁固定条件', async (shape, valueSource) => {
    const condition = { fieldId: 'owner', operator: 'eq', value: null, valueSource }
    const query =
      shape === 'fixed'
        ? { fixed: [condition] }
        : {
            scope: {
              logic: 'AND',
              conditions: [],
              groups: [{ logic: 'OR', conditions: [], groups: [{ logic: 'AND', conditions: [condition], groups: [] }] }]
            }
          }
    api.application.mockResolvedValue({
      definition: {
        resources: [
          { id: 'form', kind: 'FORM', name: '房间表单', config: { objectId: 'object' } },
          { id: 'dynamic', kind: 'VIEW', name: '动态房间', config: { objectId: 'object', formId: 'form', query } },
          {
            id: 'constant',
            kind: 'VIEW',
            name: '固定房间',
            config: {
              objectId: 'object',
              formId: 'form',
              query: {
                fixed: [
                  { fieldId: 'owner', operator: 'eq', value: 'CURRENT_USER', valueSource: 'CONSTANT' },
                  { fieldId: 'room', operator: 'eq', value: '101' }
                ]
              }
            }
          }
        ]
      }
    })
    const { confirm } = await mount([], { applicationId: 'a' })
    expect(checkbox('动态房间').disabled).toBe(true)
    expect(host.textContent).toContain('任务办理暂不支持动态身份范围，请使用固定条件视图')
    expect(checkbox('固定房间').disabled).toBe(false)
    await toggle('动态房间')
    await toggle('固定房间')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0].map((entry: TaskWorkEntryConfig) => entry.binding?.viewId)).toEqual(['constant'])
  })
  it('历史已选视图出现动态范围时保留原引用和规则，不随确认选择静默丢弃', async () => {
    const original = { ...existing(), workRule: { mode: 'RECORD_ONCE' as const, minutes: 15 } }
    api.application.mockResolvedValue({
      definition: {
        resources: [
          { id: 'form', kind: 'FORM', name: '房间表单', config: { objectId: 'object' } },
          {
            id: 'form-view',
            kind: 'VIEW',
            name: '已改动态范围',
            config: {
              objectId: 'object',
              formId: 'form',
              query: { fixed: [{ fieldId: 'owner', operator: 'eq', valueSource: 'CURRENT_USER' }] }
            }
          }
        ]
      }
    })
    const { confirm } = await mount([original], { applicationId: 'a' })
    expect(checkbox('已改动态范围').disabled).toBe(true)
    expect(checkbox('已改动态范围').checked).toBe(true)
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0][0]).toBe(original)
    expect(original.workRule.minutes).toBe(15)
  })
  it('已选视图改绑表单后不能暗中升级引用，原配置仍完整保留', async () => {
    const original = existing()
    api.application.mockResolvedValue({
      definition: {
        resources: [
          { id: 'new-form', kind: 'FORM', name: '新表单', config: { objectId: 'object' } },
          { id: 'form-view', kind: 'VIEW', name: '房间视图', config: { objectId: 'object', formId: 'new-form' } }
        ]
      }
    })
    const { confirm } = await mount([original], { applicationId: 'a' })
    expect(checkbox('房间视图').disabled).toBe(true)
    expect(host.textContent).toContain('表单已变更')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0][0]).toBe(original)
    expect(original.binding?.formId).toBe('form')
  })
  it.each([false, true])('多选=%s时选择后续表单不改变候选顺序，重新打开也不把已选项置顶', async multiple => {
    api.application.mockResolvedValue({
      definition: {
        resources: [
          { id: 'first', kind: 'FORM', name: '采购登记', config: { objectId: 'object' } },
          { id: 'first-view', kind: 'VIEW', name: '采购登记', config: { objectId: 'object', formId: 'first' } },
          { id: 'second-view', kind: 'VIEW', name: '采购验收', config: { objectId: 'object', formId: 'second' } },
          { id: 'second', kind: 'FORM', name: '采购验收', config: { objectId: 'object' } },
          { id: 'third', kind: 'FORM', name: '费用登记', config: { objectId: 'object' } },
          { id: 'third-view', kind: 'VIEW', name: '费用登记', config: { objectId: 'object', formId: 'third' } }
        ]
      }
    })
    const original = {
      ...existing(),
      binding: { applicationId: 'a', viewId: 'second-view', formId: 'second', entryId: null }
    }
    const { props, confirm } = await mount([original], { multiple, applicationId: 'a' })
    const labels = () => Array.from(host.querySelectorAll('label'))
    const assertOrder = () => {
      expect(labels()[0]?.textContent).toContain('采购登记')
      expect(labels()[1]?.textContent).toContain('采购验收')
      expect(labels()[2]?.textContent).toContain('费用登记')
    }
    assertOrder()
    expect(checkbox('采购验收').checked).toBe(true)
    await toggle('费用登记')
    assertOrder()
    expect(checkbox('费用登记').checked).toBe(true)
    await click('确认选择')
    props.entries = confirm.mock.calls[0]?.[0]
    props.open = false
    await flush()
    props.open = true
    await flush()
    assertOrder()
    expect(checkbox('费用登记').checked).toBe(true)
    expect(checkbox('采购验收').checked).toBe(multiple)
  })

  it('新选表单刷新后失效时阻止静默丢弃，既有配置仍不变', async () => {
    const { confirm } = await mount()
    await selectApp('a')
    await toggle('a表单')
    api.model.mockRejectedValueOnce(new Error('权限已收回'))
    await click('刷新视图')
    await click('确认选择')
    expect(confirm).not.toHaveBeenCalled()
    expect(host.textContent).toContain('部分新选表单暂不可用')
    await click('刷新视图')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0]).toHaveLength(1)
  })
  it('关闭抽屉后忽略迟到结果，重开才重新核对表单', async () => {
    let resolveApplication!: (value: unknown) => void
    api.application.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveApplication = resolve
        })
    )
    const { props, catalog } = await mount()
    await selectApp('a')
    props.open = false
    await flush()
    const reports = catalog.mock.calls.length
    resolveApplication({
      definition: { resources: [{ id: 'late', kind: 'FORM', name: '迟到表单', config: { objectId: 'old' } }] }
    })
    await flush()
    expect(catalog).toHaveBeenCalledTimes(reports)
    props.open = true
    await flush()
    expect(host.textContent).not.toContain('迟到表单')
  })
  it('先选应用再加载真实表单，跨筛选保留多选且不扫描业务列表入口', async () => {
    const { confirm } = await mount()
    expect(api.application).not.toHaveBeenCalled()
    expect(api.mine).not.toHaveBeenCalled()
    expect(api.context).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请先选择应用')
    expect(host.querySelector('footer')?.textContent).toContain('确认选择')
    expect(host.querySelector('main')?.textContent).not.toContain('确认选择')
    expect(host.textContent).not.toContain('纯填写入口')
    expect(host.textContent).not.toContain('甲列表')
    await selectApp('a')
    expect(host.textContent).not.toContain('业务列表视图')
    await toggle('a表单')
    await selectApp('b')
    await toggle('b表单')
    await selectApp('a')
    expect(checkbox('a表单').checked).toBe(true)
    const search = host.querySelector<HTMLInputElement>('input[data-search]')!
    search.value = '不存在'
    search.dispatchEvent(new Event('input'))
    await flush()
    expect(host.querySelectorAll('label')).toHaveLength(0)
    await click('确认选择')
    const configs = confirm.mock.calls[0]![0] as TaskWorkEntryConfig[]
    expect(configs.map(e => e.name)).toEqual(['a表单', 'b表单'])
    expect(configs[0]?.binding).toEqual({ applicationId: 'a', viewId: 'form-view', entryId: null, formId: 'form' })
    expect(api.context).not.toHaveBeenCalled()
  })
  it('业务与反馈共用三列候选和搜索，业务单选确认只返回当前选中的表单', async () => {
    const { confirm } = await mount([], { multiple: false })
    expect(host.querySelector('h2')?.textContent).toBe('选择业务视图')
    await selectApp('a')
    expect(host.querySelector('header')?.textContent).toBe('业务视图 / 办理表单 / 数据对象 / 所属应用')
    expect(checkbox('a表单').type).toBe('radio')
    await toggle('a表单')
    await selectApp('b')
    await toggle('b表单')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0].map((entry: TaskWorkEntryConfig) => entry.binding?.applicationId)).toEqual(['b'])
    const search = host.querySelector<HTMLInputElement>('input[data-search]')!
    search.value = '测试对象'
    search.dispatchEvent(new Event('input'))
    await flush()
    expect(host.querySelectorAll('label')).toHaveLength(1)
    expect(api.mine).not.toHaveBeenCalled()
  })
  it('清空应用筛选只保留已选项，未选择的候选仍须先选择其应用', async () => {
    await mount()
    await selectApp('a')
    await toggle('a表单')
    await selectApp('b')
    await selectApp('')
    expect(host.querySelectorAll('label')).toHaveLength(1)
    expect(checkbox('a表单').checked).toBe(true)
    expect(host.textContent).not.toContain('b表单')
  })
  it('业务固定应用自动核对表单，重新打开不复用陈旧目录', async () => {
    const { props } = await mount([], { multiple: false, applicationId: 'a' })
    expect(host.querySelector('select')?.disabled).toBe(true)
    expect(host.querySelector('select')?.value).toBe('a')
    expect(host.textContent).toContain('a表单')
    expect(api.application).toHaveBeenCalledWith('a')
    props.open = false
    await flush()
    api.application.mockResolvedValue({ definition: { resources: [] } })
    props.open = true
    await flush()
    expect(host.querySelectorAll('label')).toHaveLength(0)
    expect(api.application).toHaveBeenCalledTimes(2)
  })
  it('业务单选可明确替换失效原配置，反馈多选的保护合并不误用于单选', async () => {
    api.context.mockRejectedValue(new Error('无权访问'))
    const original = { ...existing(), binding: { applicationId: 'a', entryId: 'old', formId: 'old-form' } }
    const { confirm } = await mount([original], { multiple: false })
    await selectApp('a')
    expect(checkbox('原名称').disabled).toBe(true)
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0]).toEqual([original])
    await toggle('a表单')
    await click('确认选择')
    expect(confirm.mock.calls[1]?.[0]).toHaveLength(1)
    expect(confirm.mock.calls[1]?.[0][0].binding).toEqual({
      applicationId: 'a',
      viewId: 'form-view',
      formId: 'form',
      entryId: null
    })
    expect(original.binding.entryId).toBe('old')
  })
  it('旧入口不在可访问应用目录仍显示原名称和原引用，不降级为直接表单', async () => {
    api.applications.mockResolvedValue([])
    const original = { ...existing(), binding: { applicationId: 'a', entryId: 'entry', formId: 'form' } }
    const { confirm } = await mount([original], { multiple: false })
    expect(host.textContent).toContain('应用甲')
    expect(checkbox('a表单').checked).toBe(true)
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0][0]).toBe(original)
    expect(api.application).not.toHaveBeenCalled()
    expect(api.model).not.toHaveBeenCalled()
  })
  it('旧入口换绑后的表单不冒充原配置，同表单直接候选保持去重', async () => {
    api.context.mockResolvedValue({
      config: { formId: 'changed' },
      entry: { applicationName: '应用甲' },
      resources: [{ id: 'changed', kind: 'FORM', name: '被换绑表单' }],
      model: { object: { objectName: '被换绑对象' } }
    })
    const original = { ...existing(), binding: { applicationId: 'a', entryId: 'entry', formId: 'form' } }
    const { confirm } = await mount([original])
    await selectApp('a')
    expect(host.querySelectorAll('label')).toHaveLength(2)
    expect(checkbox('原名称').disabled).toBe(true)
    expect(host.textContent).toContain('表单已变更')
    expect(host.textContent).not.toContain('被换绑表单')
    expect(host.textContent).not.toContain('被换绑对象')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0][0]).toBe(original)
  })
  it('业务应用变更后不能确认另一应用的旧选择，原配置不会被清空', async () => {
    const original = existing()
    const { confirm, props } = await mount([original], { multiple: false, applicationId: 'b' })
    await click('确认选择')
    expect(confirm).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请选择当前应用的业务表单')
    expect(props.entries).toEqual([original])
    await toggle('b表单')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0][0].binding.applicationId).toBe('b')
  })
  it('同一对象的不同表单分别保留，不把配置规则按数据对象合并', async () => {
    api.application.mockResolvedValue({
      definition: {
        resources: [
          { id: 'first', kind: 'FORM', name: '采购登记', config: { objectId: 'object' } },
          { id: 'first-view', kind: 'VIEW', name: '采购登记', config: { objectId: 'object', formId: 'first' } },
          { id: 'second-view', kind: 'VIEW', name: '采购验收', config: { objectId: 'object', formId: 'second' } },
          { id: 'second', kind: 'FORM', name: '采购验收', config: { objectId: 'object' } }
        ]
      }
    })
    const { confirm } = await mount()
    await selectApp('a')
    await toggle('采购登记')
    await toggle('采购验收')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0].map((entry: TaskWorkEntryConfig) => entry.binding?.formId)).toEqual([
      'first',
      'second'
    ])
    expect(api.model).toHaveBeenCalledTimes(1)
  })
  it('取消不提交修改；重新确认原选择保留完整高级配置', async () => {
    const original = existing(),
      before = JSON.stringify(original)
    const { confirm, cancel } = await mount([original])
    await selectApp('a')
    expect(checkbox('a表单').checked).toBe(true)
    await selectApp('b')
    await toggle('b表单')
    await click('取消')
    expect(cancel).toHaveBeenCalledOnce()
    expect(confirm).not.toHaveBeenCalled()
    expect(JSON.stringify(original)).toBe(before)
    await toggle('b表单')
    await click('确认选择')
    expect(confirm.mock.calls[0]![0]).toEqual([original])
    expect(confirm.mock.calls[0]![0][0]).toBe(original)
  })
  it('候选请求失败可重试，保留未加载历史项', async () => {
    api.application.mockRejectedValue(new Error('temporary'))
    const original = existing(),
      { confirm, catalog } = await mount([original])
    await selectApp('a')
    expect(host.textContent).toContain('加载失败')
    expect(host.textContent).toContain('已选办理项')
    expect(
      catalog.mock.calls.at(-1)?.[0].find((item: TaskEntryCandidate) => item.name === '原名称')?.pendingVerification
    ).toBe(true)
    expect(checkbox('原名称').disabled).toBe(true)
    await click('确认选择')
    expect(confirm.mock.calls[0]![0]).toEqual([original])
  })
  it('旧入口按实际表单展示、隐藏重复直接表单，确认不改写原绑定或高级配置', async () => {
    const original = { ...existing(), binding: { applicationId: 'a', entryId: 'entry', formId: 'form' } }
    const { confirm } = await mount([original])
    expect(checkbox('a表单').checked).toBe(true)
    await selectApp('a')
    expect(host.querySelectorAll('label')).toHaveLength(2)
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0]).toEqual([original])
    expect(confirm.mock.calls[0]?.[0][0]).toBe(original)
    expect(api.context).toHaveBeenCalledTimes(1)
  })
  it('旧入口核对失败保留引用，刷新可恢复后明确取消选择', async () => {
    api.context.mockRejectedValueOnce(new Error('temporary'))
    const original = { ...existing(), binding: { applicationId: 'a', entryId: 'entry', formId: 'form' } }
    const { confirm } = await mount([original])
    expect(checkbox('原名称').disabled).toBe(true)
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0]).toEqual([original])
    await click('刷新视图')
    expect(checkbox('a表单').disabled).toBe(false)
    await toggle('a表单')
    await click('确认选择')
    expect(confirm.mock.calls[1]?.[0]).toEqual([])
  })
  it('刷新所选应用的表单而非只刷新应用列表，并保留跨应用勾选', async () => {
    const { confirm } = await mount()
    await selectApp('a')
    await toggle('a表单')
    api.application.mockRejectedValueOnce(new Error('temporary'))
    await selectApp('b')
    expect(host.textContent).toContain('表单候选加载失败')
    await click('刷新视图')
    await toggle('b表单')
    await click('确认选择')
    expect(confirm.mock.calls[0]?.[0].map((entry: TaskWorkEntryConfig) => entry.name)).toEqual(['a表单', 'b表单'])
  })
  it('拒绝无权对象的表单，迟到的应用加载不能覆盖当前候选', async () => {
    let resolveA!: (value: unknown) => void
    api.application.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveA = resolve
        })
    )
    await mount()
    await selectApp('a')
    await selectApp('b')
    resolveA({
      definition: { resources: [{ id: 'late', kind: 'FORM', name: '迟到表单', config: { objectId: 'late' } }] }
    })
    await flush()
    expect(host.textContent).toContain('b表单')
    expect(host.textContent).not.toContain('迟到表单')
    api.model.mockRejectedValueOnce(new Error('forbidden'))
    await click('刷新视图')
    expect(host.querySelectorAll('label')).toHaveLength(1)
    expect(checkbox('b表单').disabled).toBe(true)
    expect(host.textContent).toContain('暂无法核对授权')
  })
  it('无表单、复合视图与只读对象明确禁用，不伪装成可办理入口', async () => {
    api.application.mockResolvedValue({
      definition: {
        resources: [
          { id: 'form', kind: 'FORM', name: '房间表单', config: { objectId: 'object' } },
          { id: 'no-form', kind: 'VIEW', name: '尚未配置表单', config: { objectId: 'object' } },
          {
            id: 'aggregate',
            kind: 'VIEW',
            name: '房间统计',
            config: { objectId: 'object', formId: 'form', composition: {} }
          },
          { id: 'readonly', kind: 'VIEW', name: '只读房间', config: { objectId: 'object', formId: 'form' } }
        ]
      }
    })
    api.model.mockResolvedValue({
      object: { objectName: '房间' },
      writable: false,
      permissions: { actions: ['QUERY'] }
    })
    await mount()
    await selectApp('a')
    expect(checkbox('尚未配置表单').disabled).toBe(true)
    expect(checkbox('房间统计').disabled).toBe(true)
    expect(checkbox('只读房间').disabled).toBe(true)
    expect(host.textContent).toContain('未配置可用表单')
    expect(host.textContent).toContain('复合／聚合')
    expect(host.textContent).toContain('仅有查看权限')
  })
})
