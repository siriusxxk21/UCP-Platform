// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App, type ComponentPublicInstance } from 'vue'
import Antd from 'ant-design-vue'
import type { HistoryChange, HistoryDetail } from '@/types/nocode/record-history'

const api = vi.hoisted(() => ({ model: vi.fn(), page: vi.fn(), get: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'linkage-sync-runtime-test' } }) }))
// 表格桩：逐列渲染列头插槽（真实的 OsTablePage 在 headerCell 里渲染同一个插槽）。
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns'],
    setup:
      (props, { slots }) =>
      () =>
        h(
          'div',
          (props.columns as { key: string; title: string }[]).map(column =>
            h('header', { 'data-column': column.key }, slots.columnTitle?.({ column }) ?? column.title)
          )
        )
  })
}))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/DataViewChildren.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'
import HistoryRecordDrawer from '@/views/nocode/record-history/HistoryRecordDrawer.vue'

const apps: App[] = []
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
beforeEach(() => {
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
})
afterEach(async () => {
  await new Promise(resolve => setTimeout(resolve, 50))
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.resetAllMocks()
})

// 第一期契约 9.4：列表里开了自动更新的那一列，列头加同样的标识。
describe('列表列头：系统自动更新的列有标识', () => {
  const permissions = {
    actions: ['READ'],
    readFields: ['name', 'status', 'bank'],
    writeFields: [],
    readDetails: [],
    writeDetails: []
  }
  async function mount() {
    api.model.mockResolvedValue({
      writable: true,
      permissions,
      object: {
        objectId: 'flow',
        objectName: '资金流水',
        fields: [
          { id: 'name', name: '摘要', type: 'TEXT' },
          { id: 'status', name: '凭证状态', type: 'TEXT' },
          { id: 'bank', name: '开户行', type: 'TEXT' }
        ],
        // 运行模型投影：只读联动且开了自动更新 ⇒ linkage.autoUpdate 为 true；没开的只读联动没有这个键。
        fieldOptions: {
          status: { rules: { readOnly: true, linkage: { readOnly: true, autoUpdate: true } } },
          bank: { rules: { readOnly: true, linkage: { readOnly: true } } }
        },
        details: [],
        relations: [],
        settings: {}
      },
      details: {}
    })
    api.page.mockResolvedValue({ list: [], total: 0 })
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp(() => h(BusinessRecords, { applicationId: 'app', objectId: 'flow' }))
    app.use(Antd)
    app.mount(host)
    apps.push(app)
    await flush()
    return host
  }
  const header = (host: HTMLElement, key: string) => {
    const found = host.querySelector<HTMLElement>(`header[data-column="${key}"]`)
    if (!found) throw new Error('缺少列：' + key)
    return found
  }
  it('只有开了自动更新的列带标识；列名照常显示，其它列（含操作列）没有', async () => {
    const host = await mount()
    const status = header(host, 'status')
    expect(status.textContent).toContain('凭证状态')
    expect(status.querySelector('.nocode-auto-update-mark')?.getAttribute('aria-label')).toBe('系统自动更新')
    for (const key of ['name', 'bank', 'actions']) {
      expect(header(host, key).querySelector('.nocode-auto-update-mark')).toBeNull()
    }
    expect(header(host, 'bank').textContent).toContain('开户行')
    expect(header(host, 'actions').textContent).toContain('操作')
  })
})

// 第一期契约第 8 章：历史抽屉「修改过程」里，系统自动更新产生的变更带标签；回填另行标出。
describe('记录变化抽屉：系统自动更新的标签', () => {
  const change = (id: string, source: HistoryChange['source']): HistoryChange => ({
    id,
    operation: 'UPDATE',
    time: `2026-10-01T0${id}:00:00Z`,
    employeeId: '7',
    employeeName: '木村',
    before: { status: null },
    after: { status: '已录入' },
    fields: [{ id: 'status', name: '凭证状态' }],
    source
  })
  const base = { applicationId: '3054', version: 61, name: '凭证状态' }
  const data: HistoryDetail = {
    fields: [{ id: 'status', name: '凭证状态' }],
    row: {
      id: 'r1',
      startValues: { status: null },
      endValues: { status: '已录入' },
      values: { status: '已录入' },
      changedFields: ['status'],
      deleted: false,
      createdInRange: false,
      restored: false,
      changes: [
        change('1', { ...base, kind: 'LINKAGE', fieldIds: ['status'], sourceObjectId: '5391', sourceRecordId: '9' }),
        change('2', { ...base, kind: 'LINKAGE', fieldIds: ['status'], backfill: true }),
        change('3', { ...base, kind: 'AUTOMATION', name: '同步凭证号' }),
        change('4', null)
      ]
    }
  }
  it('普通触发、回填、别的来源各自的标签', async () => {
    const instance = ref<ComponentPublicInstance>()
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({
      setup: () => () =>
        h(HistoryRecordDrawer, {
          ref: instance,
          open: true,
          data,
          loading: false,
          error: '',
          tableName: '资金流水',
          recordId: 'r1'
        })
    })
    app.use(Antd)
    app.mount(host)
    apps.push(app)
    await flush()
    const state = (instance.value?.$ as unknown as { setupState: { tab: string } } | undefined)?.setupState
    if (!state) throw new Error('抽屉未挂载')
    state.tab = 'process'
    await flush()
    const events = Array.from(document.querySelectorAll('.history-event'))
    expect(events).toHaveLength(4)
    const tags = events.map(event => Array.from(event.querySelectorAll('.ant-tag')).map(tag => tag.textContent?.trim()))
    expect(tags[0]).toContain('系统自动更新 · 凭证状态')
    expect(tags[1]).toContain('系统自动更新（存量回填） · 凭证状态')
    expect(tags[1]).not.toContain('系统自动更新 · 凭证状态')
    expect(tags[2]).toEqual(['自动更新 · 同步凭证号 · V61', '修改'])
    expect(tags[3]).toEqual(['修改'])
  })
})
