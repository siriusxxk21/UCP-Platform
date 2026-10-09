import { describe, expect, it } from 'vitest'
import { isProxy, reactive, toRaw } from 'vue'
import type { ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig } from '@/types/nocode/report'
import { defaultReport } from './report'
import { resourceSnapshot } from './application-resource'

describe('应用资源的草稿快照', () => {
  it('移除指标后仍可应用并重开，指标条件和格式完整保留且相互独立', () => {
    const editing = reactive<ApplicationResource>({
      id: 'report',
      kind: 'REPORT',
      code: 'report_assets',
      name: '部门资产',
      config: defaultReport('asset') as unknown as Record<string, unknown>
    })
    const report = editing.config as unknown as ReportConfig
    report.metrics = [
      { id: 'total', name: '总数', operation: 'SUM', fieldId: 'quantity' },
      {
        id: 'used',
        name: '在用数',
        operation: 'SUM',
        fieldId: 'quantity',
        format: { unit: '台', decimals: 0, color: '#4f46e5' },
        conditions: {
          logic: 'AND',
          items: [{ type: 'condition', field: 'status', operator: 'in', value: ['used', 'loaned'] }]
        }
      },
      { id: 'remove', name: '待移除', operation: 'COUNT', fieldId: null }
    ]
    // 与实际指标移除相同：新数组中保留了从响应式数组读取的指标代理。
    report.metrics = report.metrics.filter(m => m.id !== 'remove')
    expect(isProxy((toRaw(editing).config as unknown as ReportConfig).metrics[0])).toBe(true)
    const saved = resourceSnapshot(editing)
    const savedReport = saved.config as unknown as ReportConfig
    expect(savedReport.metrics).toEqual(report.metrics)
    expect(isProxy(savedReport.metrics[0])).toBe(false)
    report.metrics[1].name = '编辑中'
    expect(savedReport.metrics[1].name).toBe('在用数')
    const reopened = reactive(resourceSnapshot(saved))
    const reopenedReport = reopened.config as unknown as ReportConfig
    reopenedReport.metrics[1].format!.unit = '个'
    expect(savedReport.metrics[1].format!.unit).toBe('台')
    expect(reopenedReport.metrics[1].conditions).toEqual(savedReport.metrics[1].conditions)
  })

  it('按保存协议保留嵌套条件、空值、布尔值和大整数身份字符串', () => {
    const condition = reactive({
      logic: 'OR',
      items: [
        { type: 'condition', field: 'department', operator: 'eq', value: '9007199254740993' },
        {
          type: 'group',
          groupLogic: 'AND',
          groupItems: [
            { type: 'condition', field: 'active', operator: 'eq', value: false },
            { type: 'condition', field: 'status', operator: 'isNull', value: null }
          ]
        }
      ]
    })
    const source: ApplicationResource = {
      id: 'r',
      kind: 'REPORT',
      code: 'report_test',
      name: '统计',
      config: { conditions: condition, limit: 0, optional: undefined }
    }
    const saved = resourceSnapshot(source)
    expect(saved.config).toEqual(JSON.parse(JSON.stringify(source.config)))
    expect(saved.config).not.toHaveProperty('optional')
  })
})
