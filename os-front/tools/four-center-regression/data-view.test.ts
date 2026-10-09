import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
const mocks = vi.hoisted(() => ({ viewChildren: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: mocks }) }))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
import DataViewChildren from '@/views/nocode/application/components/DataViewChildren.vue'
import DataViewSettings from '@/views/nocode/application/components/DataViewSettings.vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
const apps: App[] = []
beforeAll(() => {
  const getStyle = window.getComputedStyle.bind(window)
  window.getComputedStyle = element => getStyle(element)
  window.matchMedia = vi.fn().mockImplementation(() => ({ matches: false, addListener() {}, removeListener() {} }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Element.prototype.scrollIntoView = vi.fn()
})
afterEach(() => {
  apps.splice(0).forEach(a => a.unmount())
  document.body.innerHTML = ''
  vi.clearAllMocks()
})
const flush = async () => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 70))
  await nextTick()
}
const button = (text: string) =>
  Array.from(document.querySelectorAll('button')).find(b => b.textContent?.replace(/\s/g, '').includes(text))!
const field = {
  id: '11',
  key: '11',
  code: 'label',
  name: '内容',
  type: 'TEXT',
  required: false,
  length: null,
  precision: null,
  scale: null,
  sort: 0
}
function mount(component: any, props: any) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ render: () => h(component, props) })
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  return host
}
describe('多对象视图实际表格与设置交互', () => {
  it('公共表格正确渲染展开列的 VNode 标题，并保留业务列和序号', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      render: () =>
        h(
          OsTablePage,
          {
            columns: [{ key: 'name', title: '名称', dataIndex: 'name' }],
            dataSource: [{ id: '1', name: '单据 A' }],
            rowKey: 'id',
            resizable: true
          },
          { expandedRowRender: () => h('div', '内部明细') }
        )
    })
    app.use(Antd)
    app.mount(host)
    apps.push(app)
    await flush()
    expect(host.querySelector('thead')?.textContent).toContain('名称')
    expect(host.querySelector('thead')?.textContent).not.toContain('[ "" ]')
    expect(host.textContent).toContain('单据 A')
    expect(host.querySelectorAll('.la-resize-handle')).toHaveLength(2)
  })
  it('子表独立分页、切换子表及筛选主记录是显式选择', async () => {
    const onFilter = vi.fn()
    mocks.viewChildren.mockImplementation(async query => ({
      list: [
        {
          id: query.sectionId,
          revision: '1',
          values: { [query.sectionId === 'items' ? '11' : '12']: query.sectionId === 'items' ? '项目记录' : '费用记录' }
        }
      ],
      total: 3
    }))
    const sections = ['items', 'costs'].map(id => ({
      id,
      name: id === 'items' ? '项目' : '费用',
      detailId: id,
      objectId: null,
      viewId: null,
      binding: null,
      fieldIds: [id === 'items' ? '11' : '12'],
      conditions: null,
      pageSize: 1,
      showTable: true
    }))
    const host = mount(DataViewChildren, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'view',
      parent: { id: 'parent', values: {}, permissions: { actions: ['READ'] } },
      filters: [],
      model: {
        composition: { grain: 'ROOT', detailId: null, sections, columns: [] },
        fields: [],
        fieldOptions: {},
        sections: Object.fromEntries(
          sections.map(s => [s.id, { fields: [{ ...field, id: s.fieldIds[0] }], fieldOptions: {}, recordModel: null }])
        )
      },
      onFilter
    })
    await flush()
    expect(mocks.viewChildren).toHaveBeenLastCalledWith(
      expect.objectContaining({ sectionId: 'items', recordId: 'parent', pageNo: 1, pageSize: 1 })
    )
    expect(host.textContent).toContain('项目记录')
    const next = host.querySelector<HTMLButtonElement>('.ant-pagination-next button')!
    next.click()
    await flush()
    expect(mocks.viewChildren).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 2 }))
    const input = host.querySelector<HTMLInputElement>('input[placeholder="搜索此子表"]')!
    input.value = '匹配内容'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('应用筛选').click()
    await flush()
    expect(onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ search: '匹配内容', requireMatch: false }))
    const check = Array.from(host.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')).find(c =>
      c.closest('label')?.textContent?.includes('含匹配子记录')
    )!
    check.click()
    await flush()
    button('应用筛选').click()
    await flush()
    expect(onFilter).toHaveBeenLastCalledWith(expect.objectContaining({ requireMatch: true }))
    Array.from(host.querySelectorAll<HTMLElement>('[role="tab"]'))
      .find(t => t.textContent === '费用')!
      .click()
    await flush()
    expect(mocks.viewChildren).toHaveBeenLastCalledWith(expect.objectContaining({ sectionId: 'costs', pageNo: 1 }))
    expect(host.textContent).toContain('费用记录')
    expect(host.textContent).not.toContain('编辑整单明细')
  })
  it('配置子表和明细粒度会保留稳定来源，移除子表同步清除关联列', async () => {
    const value = ref<any>({ grain: 'ROOT', detailId: null, sections: [], columns: [] })
    const object: any = {
      objectId: 'object',
      definition: {
        objectName: '单据',
        fields: [field],
        relations: [],
        details: [{ id: 'items', name: '明细', fields: [field], state: 'ACTIVE' }]
      }
    }
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      render: () =>
        h(DataViewSettings, {
          objectId: 'object',
          objects: { object },
          resources: [],
          modelValue: value.value,
          'onUpdate:modelValue': (v: any) => {
            value.value = v
          }
        })
    })
    app.use(Antd)
    app.mount(host)
    apps.push(app)
    button('添加子表或关联对象').click()
    await flush()
    expect(value.value.sections[0].detailId).toBe('items')
    button('添加关联字段或汇总列').click()
    await flush()
    expect(value.value.columns[0]).toMatchObject({ kind: 'COUNT', sectionId: value.value.sections[0].id })
    const header = host.querySelector<HTMLElement>('.ant-collapse-header')!
    header.click()
    await flush()
    button('移除此子表及其关联列').click()
    await flush()
    expect(value.value.sections).toHaveLength(0)
    expect(value.value.columns).toHaveLength(0)
  })
})
