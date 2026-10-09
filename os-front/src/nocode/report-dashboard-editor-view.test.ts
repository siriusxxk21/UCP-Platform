// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, modal, empty, find, event, text, flush } from '../../tools/selection-regression/renderer'
import Editor from '@/views/nocode/report-center/dashboard-editor.vue'
const api = vi.hoisted(() => ({
  dashboardGet: vi.fn(),
  dashboardQuery: vi.fn(),
  dashboardChartPreview: vi.fn(),
  dashboardSave: vi.fn(),
  dashboardPublish: vi.fn(),
  getUserInfo: vi.fn(),
  applyPermissionInfo: vi.fn(),
  push: vi.fn(),
  warning: vi.fn(),
  sourceObject: vi.fn(async () => ({ fields: [] as { id: string; type: string }[] })),
  page: vi.fn(),
  releases: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ reportCenter: api, hasPermission: () => false }) }))
vi.mock('@/api/auth', () => ({ getUserInfo: api.getUserInfo }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ applyPermissionInfo: api.applyPermissionInfo }) }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn(), warning: api.warning }, Modal: { confirm: vi.fn() } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { id: 'board' } }),
  useRouter: () => ({ push: api.push }),
  onBeforeRouteLeave: vi.fn()
}))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({ default: modal }))
vi.mock('@/views/nocode/report-center/components/DashboardRuntime.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/report-center/components/DashboardMenuSettings.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/report-center/components/DashboardAuthorization.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/report-center/components/DashboardInteractionEditor.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/report-center/components/DashboardChartView.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['result', 'chart'],
      setup: props => () => h('chart-view', {}, String(props.result.totals.sum))
    })
  }
})
const chart = {
  id: 'chart',
  title: '指标',
  display: 'METRIC',
  dataset: { id: 'data', versionNo: 1, checksum: 'fixed' },
  dimensions: [],
  metricIds: ['sum'],
  x: 0,
  y: 0,
  w: 6,
  h: 3
}
const board = {
  id: 'board',
  revision: 1,
  draft: { schemaVersion: 1, name: '看板', description: '', charts: [chart] },
  capabilities: { canEdit: true }
}
const release = {
  datasetId: 'data',
  versionNo: 1,
  checksum: 'fixed',
  definition: {
    source: { fields: [] },
    analysis: {
      metrics: [
        { id: 'sum', name: '金额', operation: 'SUM' },
        { id: 'count', name: '数量', operation: 'COUNT' }
      ]
    }
  }
}
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})
describe('设计页真实组件异步预览', () => {
  it('迟到预览不能覆盖新指标；应用只更新画布，未调用保存接口', async () => {
    vi.useFakeTimers()
    api.dashboardGet.mockResolvedValue(board)
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.page
      .mockResolvedValueOnce({
        list: Array.from({ length: 100 }, (_, index) => ({
          id: `other-${index}`,
          publishedVersion: 1,
          status: 'ACTIVE',
          draft: { name: `数据${index}` }
        })),
        total: 101
      })
      .mockResolvedValue({
        list: [{ id: 'data', publishedVersion: 1, status: 'ACTIVE', draft: { name: '数据集' } }],
        total: 101
      })
    api.releases.mockResolvedValue({ list: [release], total: 1 })
    const responses: ((value: unknown) => void)[] = []
    api.dashboardChartPreview.mockImplementation(() => new Promise(resolve => responses.push(resolve)))
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      await flush()
      await vi.advanceTimersByTimeAsync(500)
      expect(responses).toHaveLength(1)
      expect(api.page).toHaveBeenCalledWith({ pageNo: 2, pageSize: 100, search: '' })
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表数据集').props.options).toHaveLength(
        101
      )
      const dialog = find(wrapper.root, 'modal')
      await event(
        find(dialog, 'a-select', n => n.props['aria-label'] === '图表指标'),
        'onChange',
        ['count']
      )
      await vi.advanceTimersByTimeAsync(500)
      expect(responses).toHaveLength(2)
      responses[1]!({ totals: { sum: 'new' } })
      await flush()
      responses[0]!({ totals: { sum: 'stale' } })
      await flush()
      expect(text(find(wrapper.root, 'aside'))).toContain('new')
      expect(text(find(wrapper.root, 'aside'))).not.toContain('stale')
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(text(wrapper.root)).toContain('new')
      expect(api.dashboardSave).not.toHaveBeenCalled()
      expect(board.draft.charts[0]!.metricIds).toEqual(['sum'])
    } finally {
      wrapper.unmount()
    }
  })
  it('基础设置应用后保留查询结果，撤销恢复原说明，未保存到服务端', async () => {
    api.dashboardGet.mockResolvedValue(board)
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).includes('仪表板设置')),
        'onClick'
      )
      const dialog = find(wrapper.root, 'modal')
      await event(
        find(dialog, 'a-textarea', n => n.props['aria-label'] === '仪表板说明'),
        'onUpdate:value',
        '配置验收说明'
      )
      await event(dialog, 'onOk')
      expect(text(wrapper.root)).toContain('配置验收说明')
      expect(text(wrapper.root)).toContain('saved')
      await event(
        find(wrapper.root, 'a-button', n => n.props['aria-label'] === '撤销' || text(n).trim() === '撤销'),
        'onClick'
      )
      expect(text(wrapper.root)).not.toContain('配置验收说明')
      expect(text(wrapper.root)).toContain('saved')
      expect(api.dashboardSave).not.toHaveBeenCalled()
      expect(board.draft.description).toBe('')
    } finally {
      wrapper.unmount()
    }
  })
  it('兼容版本直接切换并保留选择，不弹出重置确认或保存到服务端', async () => {
    api.dashboardGet.mockResolvedValue(board)
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.page.mockResolvedValue({
      list: [{ id: 'data', publishedVersion: 2, status: 'ACTIVE', draft: { name: '数据集' } }],
      total: 1
    })
    const next = { ...release, versionNo: 2, checksum: 'next' }
    api.releases.mockResolvedValue({ list: [next, release], total: 2 })
    api.dashboardChartPreview.mockResolvedValue({ totals: { sum: 'preview' } })
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      await flush()
      const version = () => find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表数据集版本')
      await event(version(), 'onChange', 2)
      expect(text(wrapper.root)).not.toContain('查看影响详情')
      expect(version().props.value).toBe(2)
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表指标').props.value).toEqual(['sum'])
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(api.dashboardSave).not.toHaveBeenCalled()
      expect(board.draft.charts[0]!.dataset.versionNo).toBe(1)
    } finally {
      wrapper.unmount()
    }
  })
  it('候选加载失败可在抽屉重试，不丢失图表输入', async () => {
    api.dashboardGet.mockResolvedValue(board)
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.page.mockRejectedValueOnce(new Error('数据集暂时不可用')).mockResolvedValue({
      list: [{ id: 'data', publishedVersion: 1, status: 'ACTIVE', draft: { name: '数据集' } }],
      total: 1
    })
    api.releases.mockResolvedValue({ list: [release], total: 1 })
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      await flush()
      expect(find(wrapper.root, 'a-alert', n => n.props.message === '数据集暂时不可用')).toBeTruthy()
      await event(
        find(wrapper.root, 'a-input', n => n.props['aria-label'] === '图表标题'),
        'onUpdate:value',
        '重试前已修改'
      )
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '重试数据来源'),
        'onClick'
      )
      await flush()
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表数据集版本').props.value).toBe(1)
      expect(find(wrapper.root, 'a-input', n => n.props['aria-label'] === '图表标题').props.value).toBe('重试前已修改')
    } finally {
      wrapper.unmount()
    }
  })
  it('不兼容切换确认后重置指标，应用到画布才清理唯一筛选引用', async () => {
    const localBoard = {
      ...board,
      draft: {
        ...board.draft,
        charts: [{ ...chart, metricIds: ['count'] }],
        filters: [
          { id: 'filter', name: '唯一公司筛选', kind: 'TEXT', mappings: [{ chartId: 'chart', fieldId: 'name' }] }
        ]
      }
    }
    api.dashboardGet.mockResolvedValue(localBoard)
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.page.mockResolvedValue({
      list: [{ id: 'data', publishedVersion: 2, status: 'ACTIVE', draft: { name: '数据集' } }],
      total: 1
    })
    const next = {
      ...release,
      versionNo: 2,
      checksum: 'next',
      definition: {
        ...release.definition,
        source: {
          root: { objectId: 'object', versionNo: 1 },
          relations: [],
          fields: [{ id: 'name', sourceFieldId: 'name', name: '公司', role: 'DIMENSION', path: [] }]
        }
      }
    }
    api.releases.mockResolvedValue({ list: [next, release], total: 2 })
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      await flush()
      await event(
        find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表数据集版本'),
        'onChange',
        2
      )
      const confirmation = find(wrapper.root, 'a-modal')
      expect(text(confirmation)).toContain('1 个筛选映射（其中 1 个筛选将被移除）')
      expect(text(confirmation)).toContain('失去唯一映射，将移除整个筛选')
      expect(find(confirmation, 'details').props.open).toBeUndefined()
      await event(confirmation, 'onCancel')
      const version = () => find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表数据集版本')
      expect(version().props.value).toBe(1)
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表指标').props.value).toEqual(['count'])
      await event(version(), 'onChange', 2)
      await event(find(wrapper.root, 'a-modal'), 'onOk')
      expect(version().props.value).toBe(2)
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表指标').props.value).toEqual(['sum'])
      expect(text(wrapper.root)).toContain('唯一公司筛选')
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(text(wrapper.root)).not.toContain('唯一公司筛选')
      expect(localBoard.draft.filters).toHaveLength(1)
      expect(api.dashboardSave).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
  it('只有报表权限的制作者仍能读取获准字段类型，文本维度不提供日期粒度', async () => {
    const dimensionChart = { ...chart, display: 'BAR', dimensions: [{ fieldId: 'company', bucket: 'VALUE' }] }
    const typedRelease = {
      ...release,
      definition: {
        ...release.definition,
        source: {
          root: { objectId: 'object', versionNo: 1 },
          relations: [],
          fields: [{ id: 'company', sourceFieldId: 'name', name: '公司', role: 'DIMENSION', path: [] }]
        }
      }
    }
    api.dashboardGet.mockResolvedValue({ ...board, draft: { ...board.draft, charts: [dimensionChart] } })
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.page.mockResolvedValue({
      list: [{ id: 'data', publishedVersion: 1, status: 'ACTIVE', draft: { name: '数据集' } }],
      total: 1
    })
    api.releases.mockResolvedValue({ list: [typedRelease], total: 1 })
    api.sourceObject.mockResolvedValue({ fields: [{ id: 'name', type: 'TEXT' }] })
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      await flush()
      expect(api.sourceObject).toHaveBeenCalledWith('object', 1)
      expect(
        find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表维度').props.options[0].label
      ).toContain('文本')
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表分组粒度').props.options).toEqual([
        { value: 'VALUE', label: '原值' }
      ])
    } finally {
      wrapper.unmount()
    }
  })
  it('版本切换后迟到的旧字段类型不能覆盖新版本日期粒度；元数据失败仍保留原配置', async () => {
    const dimensionChart = { ...chart, display: 'BAR', dimensions: [{ fieldId: 'company', bucket: 'VALUE' }] }
    const source = {
      root: { objectId: 'object', versionNo: 1 },
      relations: [],
      fields: [{ id: 'company', sourceFieldId: 'name', name: '分组', role: 'DIMENSION', path: [] }]
    }
    const before = { ...release, definition: { ...release.definition, source } }
    const after = {
      ...before,
      versionNo: 2,
      checksum: 'next',
      definition: { ...before.definition, source: { ...source, root: { ...source.root, versionNo: 2 } } }
    }
    api.dashboardGet.mockResolvedValue({ ...board, draft: { ...board.draft, charts: [dimensionChart] } })
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.page.mockResolvedValue({
      list: [{ id: 'data', publishedVersion: 2, status: 'ACTIVE', draft: { name: '数据集' } }],
      total: 1
    })
    api.releases.mockResolvedValue({ list: [after, before], total: 2 })
    let resolveOld!: (value: { fields: { id: string; type: string }[] }) => void
    api.sourceObject
      .mockImplementationOnce(() => new Promise(resolve => (resolveOld = resolve)))
      .mockResolvedValueOnce({ fields: [{ id: 'name', type: 'DATE' }] })
      .mockRejectedValueOnce(new Error('旧模式无对象查询权限'))
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      await flush()
      const version = () => find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表数据集版本')
      const options = () => find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表维度').props.options
      await event(version(), 'onChange', 2)
      await event(find(wrapper.root, 'a-modal'), 'onOk')
      await flush()
      expect(options()[0].label).toContain('日期')
      resolveOld({ fields: [{ id: 'name', type: 'TEXT' }] })
      await flush()
      expect(options()[0].label).toContain('日期')
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表分组粒度').props.options).toHaveLength(
        4
      )
      await event(version(), 'onChange', 1)
      await event(find(wrapper.root, 'a-modal'), 'onOk')
      await flush()
      expect(version().props.value).toBe(1)
      expect(options()[0].label).toBe('分组')
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '图表维度').props.value).toEqual(['company'])
      expect(api.dashboardSave).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
})

describe('发布后刷新菜单', () => {
  it.each([false, true])('导航刷新失败=%s 时仍打开已发布仪表板', async failed => {
    api.dashboardGet.mockResolvedValue({ ...board, capabilities: { canEdit: true, canPublish: true } })
    api.dashboardQuery.mockResolvedValue({ totals: { sum: 'saved' } })
    api.dashboardPublish.mockResolvedValue({})
    if (failed) api.getUserInfo.mockRejectedValueOnce(new Error('暂时不可用'))
    else api.getUserInfo.mockResolvedValueOnce({ menus: [] })
    const wrapper = mount(Editor)
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '发布并打开'),
        'onClick'
      )
      await flush()
      expect(api.dashboardPublish).toHaveBeenCalled()
      expect(api.getUserInfo).toHaveBeenCalledOnce()
      expect(api.push).toHaveBeenCalledWith({ path: '/nocode/report-center/dashboard-view', query: { id: 'board' } })
      if (failed) expect(api.warning).toHaveBeenCalledOnce()
      else expect(api.applyPermissionInfo).toHaveBeenCalledWith({ menus: [] })
    } finally {
      wrapper.unmount()
    }
  })
})
