// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, h, reactive, type App } from 'vue'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { ReportConfig, ReportFilter } from '@/types/nocode/report'
import { example1Config, multiObjects, singleStayConfig } from './report-multisource-fixture'
import { flush, pick, registerStubs } from './report-multisource-harness'
import PageFilterConfig from '@/views/nocode/application/components/PageFilterConfig.vue'

const report = (id: string, config: ReportConfig): ApplicationResource => ({
  id,
  kind: ResourceKind.REPORT,
  name: id,
  code: id,
  config: config as unknown as Record<string, unknown>
})
let app: App | undefined, host: HTMLDivElement, state: { filters: ReportFilter[] }
async function mount(filter: Partial<ReportFilter>, resources: ApplicationResource[]) {
  state = reactive({
    filters: [{ id: 'f', name: '筛选', objectId: 'stay', fieldId: '', dateRange: false, targets: {}, ...filter }]
  })
  app = createApp(() =>
    h(PageFilterConfig, {
      modelValue: state.filters,
      'onUpdate:modelValue': (value: ReportFilter[]) => {
        state.filters = value
      },
      objects: multiObjects,
      resources
    })
  )
  registerStubs(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})
const targetSelect = () => host.querySelector<HTMLSelectElement>('.target-select')!
const option = (select: HTMLSelectElement, value: string) =>
  select.querySelector<HTMLOptionElement>(`option[value="${value}"]`)!
const L14 =
  '用户可筛选字段「入住状态」在来源「支出」里没有对应字段，请在该来源的「筛选对应」里指定，或把它从可筛选字段里去掉'

describe('F14 页面公共筛选绑定多来源统计', () => {
  const profit = report('profit', { ...example1Config(), filterFieldIds: ['stay_property', 'stay_status'] })
  it('「物件」：例 1 统计可选，映射到 stay_property（每个来源都能按维度推出来）', async () => {
    await mount({ fieldId: 'stay_property' }, [profit])
    expect(option(targetSelect(), 'profit').disabled).toBe(false)
    await pick(targetSelect(), 'profit')
    expect(state.filters[0].targets).toEqual({ profit: 'stay_property' })
    const mapping = host.querySelector<HTMLSelectElement>('.mapping select')!
    expect(option(mapping, 'stay_property').disabled).toBe(false)
  })
  it('「入住状态」：在来源「支出」里推不出对应字段 ⇒ 该统计禁用并提示 L14；单来源的统计照常可选', async () => {
    const single = report('single', { ...singleStayConfig(), filterFieldIds: ['stay_status'] })
    await mount({ fieldId: 'stay_status' }, [profit, single])
    expect(option(targetSelect(), 'profit').disabled).toBe(true)
    expect(option(targetSelect(), 'profit').title).toBe(L14)
    expect(option(targetSelect(), 'single').disabled).toBe(false)
    expect(option(targetSelect(), 'single').title).toBe('')
  })
  it('已绑定的旧配置：映射下拉里该字段禁用并写明 L14', async () => {
    await mount({ fieldId: 'stay_status', targets: { profit: 'stay_status' } }, [profit])
    const mapping = host.querySelector<HTMLSelectElement>('.mapping select')!
    expect(option(mapping, 'stay_status').disabled).toBe(true)
    expect(option(mapping, 'stay_status').title).toBe(L14)
  })
})
