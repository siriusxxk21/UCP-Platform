// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import FieldConversionReview from '@/views/nocode/components/FieldConversionReview.vue'
import type { FieldConversion } from '@/types/nocode/data-center'

vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ hasPermission: () => true, dataCenter: {} }) }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: { template: '<div />' } }))
let app: App, host: HTMLDivElement
const item: FieldConversion = {
  fieldId: 'field-1',
  detailId: 'detail-1',
  fieldName: '数量',
  sourceName: '订单明细',
  fromType: 'text',
  toType: 'integer',
  action: 'CLEAR_COLUMN',
  affectedRows: 3,
  deletedRows: 1,
  failedRows: 2,
  masked: false,
  fingerprint: 'f',
  clearAllowed: true,
  conversionRule: '严格解析数字',
  impacts: []
}
async function mount(conversion: FieldConversion, blocked = false, reviewedApplicationIds: string[] = []) {
  const navigate = vi.fn()
  app = createApp(() =>
    h(FieldConversionReview, {
      planId: 'plan',
      objectId: '21',
      conversions: [conversion],
      blocked,
      reviewedApplicationIds,
      onNavigate: navigate
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  app.component('ACard', plain)
  app.component('ATag', plain)
  app.component('ACollapse', plain)
  app.component(
    'ACollapsePanel',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('details', slots.default?.())
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['type', 'message', 'description'],
      setup: props => () => h('div', { role: 'alert', 'data-type': props.type }, [props.message, props.description])
    })
  )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component(
    'ACheckbox',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['change'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              disabled: props.disabled,
              onChange: (event: Event) => emit('change', event)
            }),
            slots.default?.()
          ])
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await nextTick()
  return { navigate }
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('发布转换影响呈现', () => {
  it('清空范围和逻辑删除记录常显，最终发布统一确认，不再要求重复勾选', async () => {
    const { navigate } = await mount(item)
    expect(host.querySelectorAll('[role="alert"]')).toHaveLength(1)
    expect(host.querySelector('input[type="checkbox"]')).toBeNull()
    expect(host.querySelector('.conversion-conclusion')?.textContent).toContain('全部 3 个旧值')
    expect(host.textContent).toContain('整条记录和其他列保留')
    expect(host.textContent).toContain('确认发布后执行清空，无需先到数据列表手动处理')
    expect(host.textContent).toContain('1 个旧值属于已删除')
    expect(host.querySelector('details')?.textContent).toContain('严格解析数字')
    const maintenance = Array.from(host.querySelectorAll('button')).find(
      button => button.textContent === '查看对象数据'
    )
    if (!maintenance) throw new Error('应提供对象数据处理入口')
    maintenance.click()
    expect(navigate).toHaveBeenCalledWith('/nocode/object/editor?id=21&tab=data&fieldId=field-1&detailId=detail-1')
  })
  it('发布计划有其他阻断时显示阻断，不能诱导用户直接发布清空', async () => {
    await mount(item, true)
    expect(host.textContent).toContain('当前发布计划还有未解决的阻断')
    expect(host.textContent).not.toContain('无需先到数据列表手动处理')
  })
  it('清空不允许时不提供授权确认，具体来源与处理入口仍常显', async () => {
    const impact = {
      fieldId: 'field-1',
      sourceKind: 'OBJECT',
      sourceId: '22',
      sourceName: '汇总单',
      location: '公式 → 金额',
      message: '公式引用此字段',
      route: '/nocode/object/editor?id=22',
      blocking: true
    }
    await mount({ ...item, clearAllowed: false, impacts: [impact, { ...impact }] })
    expect(host.querySelectorAll('[role="alert"]')).toHaveLength(1)
    expect(host.querySelectorAll('.conversion-impact')).toHaveLength(1)
    expect(host.querySelector('.conversion-impact')?.closest('details')).toBeNull()
    expect(host.querySelector('.conversion-impact')?.textContent).toContain('汇总单 · 公式 → 金额')
    expect(host.querySelector('input[type="checkbox"]')).toBeNull()
  })
  it('已在应用暂停列表展示的提示才去重，其他应用与真实阻断仍常显', async () => {
    const applicationImpact = {
      fieldId: 'field-1',
      sourceKind: 'APPLICATION',
      sourceId: 'app-a',
      sourceName: '应用 A',
      location: '固定对象版本',
      message: '需要暂停应用',
      route: '/nocode-app/workspace?id=app-a',
      blocking: false
    }
    await mount(
      {
        ...item,
        impacts: [
          applicationImpact,
          { ...applicationImpact, sourceId: 'app-b', sourceName: '应用 B' },
          { ...applicationImpact, message: '应用有办理中的流程', blocking: true }
        ]
      },
      false,
      ['app-a']
    )
    const impacts = host.querySelectorAll('.conversion-impact')
    expect(impacts).toHaveLength(2)
    expect(impacts[0]?.textContent).toContain('应用 B')
    expect(impacts[1]?.textContent).toContain('应用有办理中的流程')
    expect(host.querySelector('.conversion-conclusion')?.getAttribute('data-type')).toBe('error')
  })
})
