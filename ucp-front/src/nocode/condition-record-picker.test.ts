// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App, type Component } from 'vue'
import Antd from 'ant-design-vue'
import RuleConditionRows from '@/views/nocode/components/RuleConditionRows.vue'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { RuleCondition } from '@/types/nocode/field-rules'
import type { ObjectField } from '@/types/nocode/object'
import type { ObjectRelation } from '@/types/nocode/data-center'
import { newField } from './object-draft'
import type { PublishedDefinition } from './field-rules'
import { notRecordMessage, referenceConditionTarget, referenceValueMalformed } from './rule-condition-choices'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: http }))

function field(type: string, id: string, name: string): ObjectField {
  return { ...newField(0, name), key: id, id, code: id, type: type as ObjectField['type'] }
}
const relation = (fieldId: string, kind: string = RelationType.REFERENCE): ObjectRelation => ({
  id: 'r-' + fieldId,
  code: fieldId,
  name: '管理状态',
  kind: kind as ObjectRelation['kind'],
  targetObjectId: 'status',
  fieldId,
  targetFieldId: null,
  required: false,
  onDelete: 'RESTRICT'
})
/** 物件：管理状态是指向「管理状态」对象的引用字段（外键列 INTEGER）。 */
const definition: PublishedDefinition = {
  objectId: 'property',
  label: '物件',
  fields: [field(FieldType.TEXT, 'name', '物件名'), field(FieldType.INTEGER, 'gl', '管理状态')],
  fieldOptions: {},
  relations: [relation('gl')],
  titleTemplate: null
}
const option = (value: string, label: string, unavailable = false) => ({
  value,
  label,
  code: null,
  parentValue: null,
  path: null,
  disabled: unavailable,
  unavailable
})
/** 候选接口：管理状态里 1 民宿管理、2 一般管理；回显不存在的值时与后端一样标「已失效或无权限的引用」。 */
function candidates(body: { selected?: string[]; search?: string }) {
  const all = [option('1', '民宿管理'), option('2', '一般管理')]
  return {
    options: all.filter(o => !body.search || o.label.includes(body.search)),
    selected: (body.selected || []).map(
      id => all.find(o => o.value === id) || option(id, '已失效或无权限的引用', true)
    ),
    total: 2,
    tree: false,
    defaultValue: null
  }
}

const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function rows(initial: RuleCondition[]) {
  const model = ref<RuleCondition[]>(initial)
  const app = createApp({
    setup: () => () =>
      h(RuleConditionRows as Component, {
        modelValue: model.value,
        'onUpdate:modelValue': (value: RuleCondition[]) => (model.value = value),
        definition,
        formGroups: [],
        emptyText: '无'
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  return { host, model }
}
const constant = (value: unknown, operator = 'eq'): RuleCondition => ({
  fieldId: 'gl',
  operator,
  valueSource: 'CONSTANT',
  value
})
beforeEach(() => {
  http.post.mockImplementation(async (url: string, body: { selected?: string[]; search?: string }) => {
    if (url === '/nocode/object-data/selection') return candidates(body)
    throw new Error('意外的请求 ' + url)
  })
})
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-select-dropdown').forEach(node => node.parentElement?.remove())
  vi.resetAllMocks()
})

// 业务方 2026-10-04：入住记录.物件名称 的引用筛选「管理状态 等于 固定值 民宿管理」，管理状态是对象引用，固定值却是自由文本框。
describe('条件行 · 引用字段的固定值选记录', () => {
  it('判定函数：单值引用字段才选记录；整数外键里存了名称即格式不对', () => {
    expect(referenceConditionTarget(definition, definition.fields[1])).toBe('status')
    expect(referenceConditionTarget(definition, definition.fields[0])).toBeNull()
    expect(
      referenceConditionTarget(
        { ...definition, relations: [relation('gl', RelationType.MANY_TO_MANY)] },
        definition.fields[1]
      )
    ).toBeNull()
    expect(referenceValueMalformed(FieldType.INTEGER, '民宿管理')).toBe(true)
    expect(referenceValueMalformed(FieldType.INTEGER, ' 12 ')).toBe(false)
    expect(referenceValueMalformed(FieldType.TEXT, '民宿管理')).toBe(false)
    expect(notRecordMessage(0, '管理状态', '民宿管理')).toBe(
      '第 1 条条件：固定值「民宿管理」不是「管理状态」里的记录（可能是早先按名称填写的文本，或记录已删除），请重新选择'
    )
  })

  it('存量文本值：不再是文本框，标红并说明，候选按记录取', async () => {
    const { host } = await rows([constant('民宿管理')])
    expect(host.querySelector('input[placeholder="固定值"]')).toBeNull()
    expect(host.querySelector('[aria-label="第 1 条条件固定值（记录）"]')).not.toBeNull()
    expect(host.querySelector('[data-record-invalid="true"]')).not.toBeNull()
    expect(host.querySelector('.rule-record-problem')?.textContent).toBe(notRecordMessage(0, '管理状态', '民宿管理'))
    expect(http.post).toHaveBeenCalledWith('/nocode/object-data/selection', {
      objectId: 'property',
      fieldId: 'gl',
      search: '',
      pageNo: 1,
      pageSize: 30,
      selected: ['民宿管理']
    })
  })

  it('从候选里选「民宿管理」：存记录 ID，标红消失', async () => {
    const { host, model } = await rows([constant('民宿管理')])
    const selector = host.querySelector('.rule-record .ant-select-selector')!
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).at(-1)!
    const items = Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option'))
    expect(items.map(item => item.textContent)).toEqual(['民宿管理', '一般管理', '民宿管理（不是有效记录）'])
    items[0]!.click()
    await flush()
    expect(model.value).toEqual([{ fieldId: 'gl', operator: 'eq', valueSource: 'CONSTANT', value: '1' }])
    expect(host.querySelector('[data-record-invalid="true"]')).toBeNull()
    expect(host.querySelector('.rule-record-problem')).toBeNull()
    expect(host.querySelector('.rule-record .ant-select-selection-item')?.textContent).toBe('民宿管理')
  })

  it('格式对但记录不存在（例如被删了）：同样标红', async () => {
    const { host } = await rows([constant('99', 'neq')])
    expect(host.querySelector('.rule-record-problem')?.textContent).toBe(notRecordMessage(0, '管理状态', '99'))
  })

  it('有效记录 ID 正常回显名称，不标红', async () => {
    const { host } = await rows([constant('2')])
    expect(host.querySelector('.rule-record-problem')).toBeNull()
    expect(host.querySelector('.rule-record .ant-select-selection-item')?.textContent).toBe('一般管理')
  })

  it('非引用字段仍是文本框；为空 / 不为空没有取值控件', async () => {
    const { host } = await rows([
      { fieldId: 'name', operator: 'eq', valueSource: 'CONSTANT', value: '银座' },
      { fieldId: 'gl', operator: 'isNull', valueSource: 'CONSTANT', value: null }
    ])
    expect(host.querySelector<HTMLInputElement>('input[placeholder="固定值"]')?.value).toBe('银座')
    expect(host.querySelector('.rule-record')).toBeNull()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('候选接口失败时，整数外键里存的名称照样标红（不等网络就能判断）', async () => {
    http.post.mockRejectedValue(new Error('网络中断'))
    const { host } = await rows([constant('民宿管理')])
    expect(host.querySelector('[data-record-invalid="true"]')).not.toBeNull()
    expect(host.querySelector('.rule-record-problem')?.textContent).toBe(notRecordMessage(0, '管理状态', '民宿管理'))
  })

  it('候选接口失败：显示原因，不退回文本框', async () => {
    http.post.mockRejectedValue(new Error('没有对象数据维护权限'))
    const { host } = await rows([constant('1')])
    expect(host.querySelector('input[placeholder="固定值"]')).toBeNull()
    expect(host.querySelector('.rule-record-problem')?.textContent).toBe('没有对象数据维护权限')
  })
})
