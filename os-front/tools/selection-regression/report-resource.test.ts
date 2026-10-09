import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, find, findAll, event, text, table, empty } from './renderer'
import { defaultReport } from '@/nocode/report'
import type { ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig } from '@/types/nocode/report'

const calls = vi.hoisted(() => ({ success: vi.fn() }))
vi.mock('ant-design-vue', () => ({ message: { success: calls.success }, Modal: { confirm: vi.fn() } }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: {} }) }))
vi.mock('@/nocode/business-field-rules', () => ({ businessFieldRules: () => [] }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: table }))
vi.mock('@/views/nocode/application/components/BusinessDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/TinyPageDesigner.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FormObjectVersionNotice.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/PageFilterConfig.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/FixedFilterField.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ReportChart.vue', () => ({ default: empty }))
vi.mock('@/views/nocode/application/components/ReportConditionEditor.vue', () => ({ default: empty }))
import ResourceManager from '@/views/nocode/application/components/ResourceManager.vue'

describe('统计视图实际应用草稿事件', () => {
  it('打开、移除指标、应用到草稿、重新打开并再次应用不会丢失独立条件', async () => {
    const config: ReportConfig = {
      ...defaultReport('asset'),
      display: 'BAR',
      dimensions: [{ fieldId: 'department', relationPath: null, bucket: 'VALUE' }],
      metrics: [
        { id: 'total', name: '总数', operation: 'SUM', fieldId: 'quantity' },
        {
          id: 'used',
          name: '在用数',
          operation: 'SUM',
          fieldId: 'quantity',
          conditions: { logic: 'AND', items: [{ type: 'condition', field: 'status', operator: 'eq', value: 'used' }] },
          format: { unit: '台', decimals: 0 }
        },
        { id: 'discard', name: '待移除', operation: 'COUNT', fieldId: null }
      ]
    }
    const resources = ref<ApplicationResource[]>([
      {
        id: 'r',
        kind: 'REPORT',
        name: '部门资产统计',
        code: 'report_asset',
        config: config as unknown as Record<string, unknown>
      }
    ])
    const objects = {
      asset: {
        objectId: 'asset',
        versionNo: 1,
        checksum: 'test',
        definition: {
          objectId: 'asset',
          objectName: '资产',
          fields: [
            { id: 'department', name: '部门', type: 'TEXT' },
            { id: 'status', name: '状态', type: 'TEXT' },
            { id: 'quantity', name: '数量', type: 'INTEGER' }
          ],
          fieldOptions: {},
          relations: [],
          details: []
        }
      }
    }
    const host = defineComponent({
      setup: () => () =>
        h(ResourceManager, {
          modelValue: resources.value,
          'onUpdate:modelValue': value => {
            resources.value = value
          },
          objects: objects as any,
          readOnly: false,
          applicationId: 'app'
        })
    })
    const page = mount(host)
    try {
      await event(find(page.root, 'a-segmented'), 'onUpdate:value', 'REPORT')
      await event(
        find(page.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      const editors = findAll(page.root, n => n.props.class === 'metric-editor')
      expect(editors).toHaveLength(3)
      await event(
        find(editors[2], 'a-button', n => text(n) === '移除'),
        'onClick'
      )
      const dialog = find(page.root, 'a-modal', n => n.props['ok-text'] === '应用到草稿')
      await event(dialog, 'onOk')
      expect(dialog.props.open).toBe(false)
      expect(calls.success).toHaveBeenCalledWith('已更新本地草稿，请保存应用后发布')
      const saved = resources.value[0].config as unknown as ReportConfig
      expect(saved.metrics.map(m => m.id)).toEqual(['total', 'used'])
      expect(saved.metrics[1].conditions).toEqual(config.metrics[1].conditions)
      expect(saved.metrics[1].format).toMatchObject({ unit: '台', decimals: 0 })
      await event(
        find(page.root, 'a-button', n => text(n).trim() === '配置'),
        'onClick'
      )
      const inputs = findAll(page.root, n => n.type === 'a-input' && n.props['aria-label'] === '指标名称')
      expect(inputs.map(n => n.props.value)).toEqual(['总数', '在用数'])
      await event(inputs[1], 'onUpdate:value', '在用笔记本')
      expect(saved.metrics[1].name).toBe('在用数')
      await event(
        find(page.root, 'a-modal', n => n.props['ok-text'] === '应用到草稿'),
        'onOk'
      )
      expect((resources.value[0].config as unknown as ReportConfig).metrics[1].name).toBe('在用笔记本')
      expect(findAll(page.root, n => n.type === 'a-alert' && n.props.type === 'error')).toHaveLength(0)
    } finally {
      page.unmount()
    }
  })
})
