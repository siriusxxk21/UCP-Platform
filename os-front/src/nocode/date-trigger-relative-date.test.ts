// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import type { PublishedObject } from '@/types/nocode/application'
import type { AutomationConfig } from '@/types/nocode/automation'
import AutomationConfigEditor from '@/views/nocode/application/components/AutomationConfigEditor.vue'
import { defaultDateAutomation, newAutomationAssignment, validateAutomation } from './automation'
import { RELATIVE_BLOCKED } from './relative-date'

vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => ({}) }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))

// 「按日期自动执行」（DT）与「条件里的相对日期」（RD）合并后的衔接：
// 按日期规则的来源条件可以用相对日期（「今天」= 正在处理的业务日，补跑时按被补的那一天），持续维护仍禁，事件赋值照旧。
const objects = {
  stays: {
    objectId: 'stays',
    versionNo: 2,
    checksum: 's2',
    definition: {
      objectId: 'stays',
      objectName: '入住记录',
      fields: [
        { id: 'name', type: 'TEXT', name: '名称' },
        { id: 'checkin', type: 'DATE', name: '入住日' },
        { id: 'booked', type: 'DATE', name: '预订日' },
        { id: 'room', type: 'INTEGER', name: '房间' },
        { id: 'state', type: 'TEXT', name: '入住状态' }
      ],
      relations: [{ id: 'stayRoom', name: '入住房间', fieldId: 'room', targetObjectId: 'rooms', kind: 'REFERENCE' }],
      fieldOptions: { room: { generated: true } },
      details: []
    }
  },
  rooms: {
    objectId: 'rooms',
    versionNo: 1,
    checksum: 'r1',
    definition: {
      objectId: 'rooms',
      objectName: '房间',
      fields: [
        { id: 'name', type: 'TEXT', name: '房号' },
        { id: 'status', type: 'TEXT', name: '房间状态' }
      ],
      relations: [],
      fieldOptions: {},
      details: []
    }
  }
} as unknown as Record<string, PublishedObject>

const bookedThisWeek = () => ({
  logic: 'AND' as const,
  groups: [],
  conditions: [
    { fieldId: 'booked', operator: 'eq' as const, value: { relative: 'THIS_WEEK' }, valueSource: 'CONSTANT' as const }
  ]
})
const dateRule = (): AutomationConfig => ({
  ...defaultDateAutomation('stays'),
  dateFieldId: 'checkin',
  conditions: bookedThisWeek(),
  assignments: [{ ...newAutomationAssignment('DATE'), fieldId: 'state', value: '本周预订已入住' }]
})
const eventRule = (mode: 'EVENT' | 'MAINTAIN'): AutomationConfig => ({
  ...defaultDateAutomation('stays'),
  mode,
  events: mode === 'MAINTAIN' ? ['CREATE', 'UPDATE', 'DELETE'] : ['CREATE', 'UPDATE'],
  targetObjectId: 'rooms',
  binding: { relationId: 'stayRoom', direction: 'OUTGOING' },
  dateFieldId: null,
  offsetDays: null,
  conditions: bookedThisWeek(),
  assignments: [{ ...newAutomationAssignment(mode), fieldId: 'status' }]
})

async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const mounted: { app: App; host: HTMLElement }[] = []
beforeEach(() => {
  // antd 栅格在 jsdom 里要 matchMedia（同 calculation-editor.test.ts）
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
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  vi.unstubAllGlobals()
})
async function editor(config: AutomationConfig) {
  const model = ref(config)
  const app = createApp({
    setup: () => () =>
      h(AutomationConfigEditor, {
        modelValue: model.value,
        'onUpdate:modelValue': (value: AutomationConfig) => (model.value = value),
        objects,
        applicationId: 'app'
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  return host
}
/** 条件行上「相对日期」单选按钮是否置灰；没有这个按钮时返回 null。 */
const relativeDisabled = (host: ParentNode) => {
  const button = Array.from(host.querySelectorAll<HTMLElement>('.ant-radio-button-wrapper')).find(
    node => node.textContent?.trim() === '相对日期'
  )
  return button ? button.classList.contains('ant-radio-button-wrapper-disabled') : null
}
const hint = (host: ParentNode) => host.querySelector('[data-relative-hint]')?.textContent?.replace(/\s/g, '') ?? ''

describe('按日期自动执行 × 相对日期（R5 衔接）', () => {
  it('按日期规则：来源条件里的相对日期可选（不置灰），说明写明按正在处理的那一天、补做时按被补的那一天', async () => {
    const host = await editor(dateRule())
    expect(relativeDisabled(host)).toBe(false)
    expect(host.querySelector('[data-relative-mode]')?.getAttribute('data-relative-mode')).toBe('RELATIVE')
    expect(hint(host)).toContain('按正在执行的那一天判断')
    expect(hint(host)).toContain('补做10月1日时「本周」=10月1日所在的那一周')
    expect(hint(host)).not.toContain('事件发生当天')
    expect(hint(host)).not.toContain(RELATIVE_BLOCKED.maintain.replace(/\s/g, ''))
  })

  it('持续维护：相对日期仍置灰并写明原因；事件赋值：可选，按事件发生当天', async () => {
    const maintain = await editor(eventRule('MAINTAIN'))
    expect(relativeDisabled(maintain)).toBe(true)
    expect(hint(maintain)).toBe(RELATIVE_BLOCKED.maintain.replace(/\s/g, ''))
    const event = await editor(eventRule('EVENT'))
    expect(relativeDisabled(event)).toBe(false)
    expect(hint(event)).toContain('按事件发生当天判断')
  })

  it('保存前校验：按日期规则的来源条件带相对日期能通过', () => {
    const config = dateRule()
    expect(() => validateAutomation(config, objects)).not.toThrow()
    expect(config.conditions?.conditions[0].value).toEqual({ relative: 'THIS_WEEK' })
  })
})
