// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { mount, modal, find, findAll, event, text, flush } from '../../tools/selection-regression/renderer'
import Editor from '@/views/nocode/report-center/components/DashboardInteractionEditor.vue'
import type { DashboardChart, DashboardFilter } from '@/types/nocode/report-dashboard'
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({ default: modal }))

describe('公共筛选逐项配置', () => {
  it('切换保留输入，应用时定位未配置项，修正后一次提交全部筛选', async () => {
    const charts: DashboardChart[] = [
      {
        id: 'chart',
        title: '金额',
        display: 'METRIC',
        dataset: { id: 'data', versionNo: 1, checksum: 'fixed' },
        dimensions: [],
        metricIds: ['sum'],
        x: 0,
        y: 0,
        w: 4,
        h: 3
      }
    ]
    const filters: DashboardFilter[] = [
      { id: 'first', name: '公司', kind: 'TEXT', mappings: [{ chartId: 'chart', fieldId: 'company' }] },
      { id: 'second', name: '未配置', kind: 'TEXT', mappings: [] }
    ]
    const apply = vi.fn()
    const wrapper = mount(Editor, {
      open: true,
      charts,
      filters,
      fields: { chart: [{ id: 'company', name: '公司', role: 'DIMENSION' }] },
      fieldErrors: {},
      onApply: apply
    })
    const control = (type: string, label: string) => find(wrapper.root, type, n => n.props['aria-label'] === label)
    try {
      await event(control('a-input', '筛选名称'), 'onUpdate:value', '所属公司')
      await event(control('a-select', '选择要配置的筛选'), 'onUpdate:value', 'second')
      await event(control('a-select', '选择要配置的筛选'), 'onUpdate:value', 'first')
      expect(control('a-input', '筛选名称').props.value).toBe('所属公司')
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply).not.toHaveBeenCalled()
      expect(control('a-select', '选择要配置的筛选').props.value).toBe('second')
      expect(find(wrapper.root, 'a-alert', n => n.props.type === 'error').props.message).toContain(
        '至少需要映射一张图表'
      )
      await event(control('a-select', '未配置映射金额'), 'onChange', 'company')
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply).toHaveBeenCalledWith([
        { ...filters[0], name: '所属公司' },
        { ...filters[1], mappings: [{ chartId: 'chart', fieldId: 'company' }] }
      ])
      expect(filters[0]!.name).toBe('公司')
      expect(filters[1]!.mappings).toEqual([])
    } finally {
      wrapper.unmount()
    }
  })
  it('按明确参照补齐同固定版本空映射，跳过已有、异版本及加载失败图表；搜索不会丢失隐藏映射', async () => {
    const charts = ['first', 'reference', 'empty', 'different', 'failed'].map(id => ({
      id,
      title: id,
      display: 'METRIC' as const,
      dataset: { id: 'data', versionNo: 1, checksum: id === 'different' ? 'other-fixed' : 'fixed' },
      dimensions: [],
      metricIds: ['sum'],
      x: 0,
      y: 0,
      w: 4,
      h: 3
    }))
    const fields = Object.fromEntries(
      charts.map(chart => [
        chart.id,
        [
          { id: 'company', name: '公司', role: 'DIMENSION' },
          { id: 'status', name: '状态', role: 'DIMENSION' }
        ]
      ])
    )
    const filters: DashboardFilter[] = [
      {
        id: 'filter',
        name: '筛选',
        kind: 'TEXT',
        mappings: [
          { chartId: 'first', fieldId: 'company' },
          { chartId: 'reference', fieldId: 'status' }
        ]
      }
    ]
    const apply = vi.fn()
    const wrapper = mount(Editor, {
      open: true,
      charts,
      filters,
      fields,
      fieldErrors: { failed: '请求失败' },
      onApply: apply
    })
    const control = (type: string, label: string) => find(wrapper.root, type, n => n.props['aria-label'] === label)
    try {
      await flush()
      await event(control('a-select', '批量映射参照图表'), 'onUpdate:value', 'reference')
      await event(control('a-radio-group', '映射状态'), 'onUpdate:value', 'UNMAPPED')
      await event(control('a-input', '搜索作用图表'), 'onUpdate:value', 'empty')
      expect(
        findAll(wrapper.root, n => n.type === 'a-select' && String(n.props['aria-label']).startsWith('筛选映射'))
      ).toHaveLength(1)
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '补齐 1 张图表'),
        'onClick'
      )
      expect(text(wrapper.root)).toContain('已补齐 1 张图表')
      expect(find(wrapper.root, 'a-button', n => text(n).trim() === '补齐 0 张图表').props.disabled).toBe(true)
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply.mock.calls[0]![0][0].mappings).toEqual([
        { chartId: 'first', fieldId: 'company' },
        { chartId: 'reference', fieldId: 'status' },
        { chartId: 'empty', fieldId: 'status' }
      ])
      expect(filters[0]!.mappings).toHaveLength(2)
    } finally {
      wrapper.unmount()
    }
  })
  it('应用时定位另一筛选的失效字段与已删除图表，清除搜索后可逐项修复', async () => {
    const charts: DashboardChart[] = [
      {
        id: 'chart',
        title: '金额',
        display: 'METRIC',
        dataset: { id: 'data', versionNo: 1, checksum: 'fixed' },
        dimensions: [],
        metricIds: ['sum'],
        x: 0,
        y: 0,
        w: 4,
        h: 3
      }
    ]
    const filters: DashboardFilter[] = [
      { id: 'good', name: '正常', kind: 'TEXT', mappings: [{ chartId: 'chart', fieldId: 'company' }] },
      {
        id: 'broken',
        name: '待修复',
        kind: 'TEXT',
        mappings: [
          { chartId: 'chart', fieldId: 'old' },
          { chartId: 'deleted', fieldId: 'company' }
        ]
      }
    ]
    const apply = vi.fn()
    const wrapper = mount(Editor, {
      open: true,
      charts,
      filters,
      fields: { chart: [{ id: 'company', name: '公司', role: 'DIMENSION' }] },
      fieldErrors: {},
      onApply: apply
    })
    const control = (type: string, label: string) => find(wrapper.root, type, n => n.props['aria-label'] === label)
    try {
      await event(control('a-input', '搜索作用图表'), 'onUpdate:value', '不会匹配')
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply).not.toHaveBeenCalled()
      expect(control('a-select', '选择要配置的筛选').props.value).toBe('broken')
      expect(control('a-input', '搜索作用图表').props.value).toBe('')
      expect(control('a-radio-group', '映射状态').props.value).toBe('ISSUE')
      expect(control('a-select', '待修复映射金额').props['aria-invalid']).toBe(true)
      await event(control('a-select', '待修复映射金额'), 'onChange', 'company')
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '删除失效映射'),
        'onClick'
      )
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply.mock.calls[0]![0][1].mappings).toEqual([{ chartId: 'chart', fieldId: 'company' }])
      expect(filters[1]!.mappings).toHaveLength(2)
    } finally {
      wrapper.unmount()
    }
  })
  it('加载失败的映射禁用编辑和批量参照，应用不接受残留字段，允许重试', async () => {
    const retry = vi.fn(),
      apply = vi.fn()
    const wrapper = mount(Editor, {
      open: true,
      charts: [
        {
          id: 'chart',
          title: '金额',
          display: 'METRIC',
          dataset: { id: 'data', versionNo: 1, checksum: 'fixed' },
          dimensions: [],
          metricIds: ['sum'],
          x: 0,
          y: 0,
          w: 4,
          h: 3
        }
      ],
      filters: [{ id: 'filter', name: '公司', kind: 'TEXT', mappings: [{ chartId: 'chart', fieldId: 'company' }] }],
      fields: { chart: [{ id: 'company', name: '公司', role: 'DIMENSION' }] },
      fieldErrors: { chart: '请求失败' },
      onRetry: retry,
      onApply: apply
    })
    try {
      await flush()
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '公司映射金额').props.disabled).toBe(true)
      expect(find(wrapper.root, 'a-select', n => n.props['aria-label'] === '批量映射参照图表').props.options).toEqual(
        []
      )
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply).not.toHaveBeenCalled()
      await event(
        find(wrapper.root, 'a-button', n => text(n).trim() === '重新加载字段'),
        'onClick'
      )
      expect(retry).toHaveBeenCalledOnce()
    } finally {
      wrapper.unmount()
    }
  })
})
