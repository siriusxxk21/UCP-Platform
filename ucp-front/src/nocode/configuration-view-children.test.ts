// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import DataViewChildren from '@/views/nocode/application/components/DataViewChildren.vue'
import type { ChildQuery, DataViewModel } from '@/types/nocode/data-view'
import { FieldType } from '@/types/nocode/enums'

const api = vi.hoisted(() => ({ viewChildren: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['columns', 'dataSource', 'pagination', 'title'],
      emits: ['change'],
      setup:
        (props, { emit }) =>
        () =>
          h('section', [
            h('h3', props.title),
            h('div', { 'data-rows': true }, JSON.stringify(props.dataSource)),
            h(
              'button',
              {
                onClick: () =>
                  emit(
                    'change',
                    { current: 2, pageSize: 20 },
                    {},
                    { columnKey: props.columns[0].key, order: 'descend' }
                  )
              },
              '按首列降序'
            ),
            h(
              'button',
              {
                onClick: () =>
                  emit(
                    'change',
                    { current: 3, pageSize: 20 },
                    {},
                    { columnKey: props.columns[0].key, order: 'descend' }
                  )
              },
              '排序后翻页'
            )
          ])
    })
  }
})
const model: DataViewModel = {
  composition: {
    grain: 'ROOT',
    detailId: null,
    columns: [],
    sections: ['a', 'b'].map(id => ({
      id,
      name: `子表 ${id.toUpperCase()}`,
      detailId: `detail-${id}`,
      objectId: null,
      viewId: null,
      binding: null,
      fieldIds: [`field-${id}`],
      conditions: null,
      pageSize: id === 'b' ? 15 : 10,
      showTable: true
    }))
  },
  fields: [],
  fieldOptions: {},
  sections: Object.fromEntries(
    ['a', 'b'].map(id => [
      id,
      {
        fields: [
          {
            key: `field-${id}`,
            id: `field-${id}`,
            code: `field_${id}`,
            name: `字段 ${id}`,
            type: FieldType.TEXT,
            length: 100,
            precision: null,
            scale: null,
            required: false,
            unique: false,
            sort: 0
          }
        ],
        fieldOptions: {},
        recordModel: null
      }
    ])
  )
}
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  Array.from(host.querySelectorAll('button')).find(el => el.textContent?.trim() === label)!
async function mount() {
  const state = reactive({ refreshKey: 0 })
  app = createApp(() =>
    h(DataViewChildren, {
      applicationId: 'application',
      objectId: 'orders',
      viewId: 'view',
      parent: { id: 'record', revision: '1', values: {} },
      model,
      filters: [],
      refreshKey: state.refreshKey
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ASpace', 'ACheckbox', 'AModal', 'AInput']) app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component('AAlert', defineComponent({ props: ['message'], setup: p => () => h('p', p.message) }))
  app.component('ATabPane', { render: () => null })
  app.component(
    'ATabs',
    defineComponent({
      props: ['activeKey'],
      emits: ['update:activeKey'],
      setup:
        (_, { emit }) =>
        () =>
          h(
            'nav',
            ['a', 'b'].map(id =>
              h('button', { onClick: () => emit('update:activeKey', id) }, `切换 ${id.toUpperCase()}`)
            )
          )
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return state
}
beforeEach(() => {
  vi.clearAllMocks()
  api.viewChildren.mockImplementation(async (query: ChildQuery) => ({
    list: [{ id: `row-${query.sectionId}`, values: {} }],
    total: 1
  }))
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('数据视图子表查询上下文', () => {
  it('A 排序后切换 B 清空排序和旧行，使用 B 的第一页与页大小', async () => {
    await mount()
    button('按首列降序').click()
    await flush()
    expect(api.viewChildren).toHaveBeenLastCalledWith(
      expect.objectContaining({ sectionId: 'a', sortFieldId: 'field-a', descending: true, pageNo: 2, pageSize: 20 })
    )
    let finish!: (value: unknown) => void
    api.viewChildren.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve
        })
    )
    button('切换 B').click()
    await flush()
    expect(api.viewChildren).toHaveBeenLastCalledWith(
      expect.objectContaining({ sectionId: 'b', sortFieldId: undefined, descending: false, pageNo: 1, pageSize: 15 })
    )
    expect(host.querySelector('[data-rows]')!.textContent).toBe('[]')
    finish({ list: [{ id: 'row-b', values: {} }], total: 1 })
    await flush()
    expect(host.querySelector('[data-rows]')!.textContent).toContain('row-b')
    expect(host.querySelector('[data-rows]')!.textContent).not.toContain('row-a')
  })
  it('同一子表刷新保留排序，旧子表迟到的响应不能回填 B', async () => {
    const state = await mount()
    button('按首列降序').click()
    await flush()
    state.refreshKey++
    await flush()
    expect(api.viewChildren).toHaveBeenLastCalledWith(
      expect.objectContaining({ sectionId: 'a', sortFieldId: 'field-a', descending: true })
    )
    let finish!: (value: unknown) => void
    api.viewChildren.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve
        })
    )
    button('排序后翻页').click()
    await flush()
    button('切换 B').click()
    await flush()
    finish({ list: [{ id: 'late-a', values: {} }], total: 99 })
    await flush()
    expect(host.querySelector('[data-rows]')!.textContent).toContain('row-b')
    expect(host.querySelector('[data-rows]')!.textContent).not.toContain('late-a')
  })
})
