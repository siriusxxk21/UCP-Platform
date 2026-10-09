// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, type App } from 'vue'
import { reportFieldOptions } from './report'
import { grainObjects } from './report-detail-grain-fixture'
import { registerStubs } from './report-detail-grain-harness'

interface Field {
  field: string
  label: string
  type: string
  options?: Array<{ value: unknown; label: string }>
  valueProps?: Record<string, unknown>
}
const captured = vi.hoisted(() => ({ fields: [] as unknown[] }))
vi.mock('@/components/ucp-table-page/OsDynamicSearch.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      props: ['fields'],
      setup: props => () => {
        captured.fields = props.fields
        return null
      }
    })
  }
})
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))

import ReportConditionEditor from '@/views/nocode/application/components/ReportConditionEditor.vue'

let app: App | undefined, host: HTMLDivElement
function mount(detailId?: string) {
  app = createApp(() =>
    h(ReportConditionEditor, {
      applicationId: 'app',
      entries: reportFieldOptions('voucher', grainObjects, {}, detailId),
      objects: grainObjects,
      previewObjects: [],
      label: '设置固定条件'
    })
  )
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  return Object.fromEntries((captured.fields as Field[]).map(f => [f.field, f]))
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('条件编辑器：明细粒度的字段', () => {
  it('分录的单选字段用分录自己的选项；分录的引用字段的取值控件带 detailId；主表字段不带', () => {
    const fields = mount('lines')
    expect(Object.keys(fields)).toEqual([
      'date',
      'memo',
      'income',
      'outgo',
      'company',
      'period',
      'debitAccount',
      'debitAmount',
      'creditAccount',
      'creditAmount',
      'lineType',
      'rCompany:companyName',
      'rDebit:accountCode',
      'rDebit:accountName',
      'rCredit:accountCode',
      'rCredit:accountName'
    ])
    expect(fields.lineType.label).toBe('分录 · 分录类型')
    expect(fields.lineType.options).toEqual([
      { value: 'NORMAL', label: '正常' },
      { value: 'ADJUST', label: '调整' }
    ])
    expect(fields.creditAccount.valueProps).toEqual({
      applicationId: 'app',
      objectId: 'voucher',
      fieldId: 'creditAccount',
      detailId: 'lines',
      preview: true,
      placeholder: '选择条件值'
    })
    // 主表上的引用字段：取值控件的属性与原来逐键相同（没有 detailId 这个键）
    expect(fields.company.valueProps).toEqual({
      applicationId: 'app',
      objectId: 'voucher',
      fieldId: 'company',
      preview: true,
      placeholder: '选择条件值'
    })
    expect('detailId' in fields.company.valueProps!).toBe(false)
    expect(fields.creditAmount.type).toBe('number')
  })

  it('主记录粒度：条件字段里没有分录的字段，也没有分录上的关系路径', () => {
    const fields = mount()
    expect(Object.keys(fields)).toEqual([
      'date',
      'memo',
      'income',
      'outgo',
      'company',
      'period',
      'rCompany:companyName'
    ])
  })
})
