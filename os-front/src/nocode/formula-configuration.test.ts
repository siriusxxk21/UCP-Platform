// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App, type Component } from 'vue'
import FormulaConfigurationModal from '@/views/nocode/components/FormulaConfigurationModal.vue'
import FormulaSequencePreview from '@/views/nocode/components/FormulaSequencePreview.vue'
import SummaryExpressionEditor from '@/views/nocode/components/SummaryExpressionEditor.vue'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import type { CalculationOptions, FormulaPreviewRequest, FormulaPreviewResult } from '@/types/nocode/data-center'

vi.mock('@/utils/request', () => ({ default: {} }))
const mounted: App[] = []
const fields = [
  { ...newField(0, '金额'), key: 'amount', code: 'amount', type: 'DECIMAL' as const },
  { ...newField(1, '账户'), key: 'account', code: 'account', type: 'TEXT' as const },
  { ...newField(2, '流水序号'), key: 'serial', code: 'serial', type: 'INTEGER' as const }
]
const sequence = (): CalculationOptions & { sequence: NonNullable<CalculationOptions['sequence']> } => ({
  mode: 'SEQUENCE',
  updateMode: 'LIVE',
  targetObjectId: null,
  relationId: null,
  targetField: null,
  aggregate: 'SUM',
  logic: 'AND',
  conditions: [],
  excludeCurrent: false,
  groupFields: ['account'],
  sequence: {
    orderField: 'serial',
    tieBreakerField: null,
    direction: 'PREVIOUS',
    operation: 'CUMULATIVE',
    initialValue: '1000'
  }
})
async function mount(component: Component, props: Record<string, unknown>, preview = vi.fn()) {
  const app = createApp({ ...component, render: () => null }, props)
  app.provide(nocodePlatformKey, { dataCenter: { formulaPreview: preview } } as unknown as NocodePlatform)
  const vm = app.mount(document.createElement('div'))
  mounted.push(app)
  await nextTick()
  return (vm.$ as unknown as { setupState: Record<string, any> }).setupState
}
afterEach(() => {
  mounted.splice(0).forEach(app => app.unmount())
})

describe('公式独立配置与多行试算', () => {
  it.each(['STATISTICS', 'RUNNING_TOTAL', 'SEQUENCE'] as const)(
    '%s 数值计算仅提供数值结果，非法旧文本不能应用或通过字段校验',
    async mode => {
      const calculation = { ...sequence(), mode }
      const options = { ...defaultFieldOptions(), expression: 'amount', resultType: 'TEXT', calculation }
      const onApply = vi.fn()
      const state = await mount(FormulaConfigurationModal, {
        field: { ...newField(3, '计算结果'), key: 'result', code: 'result', type: 'FORMULA' },
        fields,
        options,
        fieldOptions: {},
        relations: [],
        onApply
      })
      state.openEditor()
      expect(state.resultTypeOptions.map((option: { value: string }) => option.value)).toEqual([
        'INTEGER',
        'DECIMAL',
        'MONEY'
      ])
      expect(state.formData.resultType).toBe('TEXT')
      expect(state.appliedError()).toContain('结果类型')
      state.apply()
      expect(state.error).toContain('结果类型')
      expect(state.modalVisible).toBe(true)
      expect(onApply).not.toHaveBeenCalled()
      for (const resultType of ['INTEGER', 'DECIMAL', 'MONEY']) {
        state.openEditor()
        state.formData.resultType = resultType
        state.apply()
        expect(state.error).toBe('')
        expect(onApply).toHaveBeenLastCalledWith({ expression: 'amount', resultType, calculation })
      }
      expect(options.resultType).toBe('TEXT')
    }
  )

  it.each([
    ['基础公式', null],
    ['本行公式', { ...sequence(), mode: 'LOCAL' }],
    ['关联取值', { ...sequence(), mode: 'RELATION', aggregate: 'SINGLE' }],
    ['条件查询', { ...sequence(), mode: 'LOOKUP', aggregate: 'SINGLE' }],
    ['关联聚合', { ...sequence(), mode: 'RELATION' }],
    ['条件聚合', { ...sequence(), mode: 'LOOKUP' }],
    ['相邻取值', { ...sequence(), sequence: { ...sequence().sequence, operation: 'ADJACENT' } }],
    ['旧相邻取值', { ...sequence(), sequence: { orderField: 'serial', tieBreakerField: null, direction: 'PREVIOUS' } }]
  ] as [string, CalculationOptions | null][])(
    '%s 保留文本结果且打开应用不改写计算模式',
    async (_label, calculation) => {
      const options = { ...defaultFieldOptions(), expression: 'account', resultType: 'TEXT', calculation }
      const onApply = vi.fn()
      const state = await mount(FormulaConfigurationModal, {
        field: { ...newField(3, '文本结果'), key: 'result', code: 'result', type: 'FORMULA' },
        fields,
        options,
        fieldOptions: {},
        relations: [],
        onApply
      })
      state.openEditor()
      expect(state.resultTypeOptions.map((option: { value: string }) => option.value)).toEqual([
        'TEXT',
        'INTEGER',
        'DECIMAL',
        'MONEY'
      ])
      expect(state.appliedError()).toBe('')
      state.apply()
      expect(onApply).toHaveBeenCalledWith({ expression: 'account', resultType: 'TEXT', calculation })
    }
  )

  it('相邻取值切换为累计后立即收紧候选和应用校验，切回后恢复文本能力', async () => {
    const calculation = sequence()
    calculation.sequence.operation = 'ADJACENT'
    const onApply = vi.fn()
    const state = await mount(FormulaConfigurationModal, {
      field: { ...newField(3, '计算结果'), key: 'result', code: 'result', type: 'FORMULA' },
      fields,
      options: { ...defaultFieldOptions(), expression: 'amount', resultType: 'TEXT', calculation },
      fieldOptions: {},
      relations: [],
      onApply
    })
    state.openEditor()
    state.formData.calculation.sequence.operation = 'CUMULATIVE'
    await nextTick()
    expect(state.resultTypeOptions.some((option: { value: string }) => option.value === 'TEXT')).toBe(false)
    state.apply()
    expect(onApply).not.toHaveBeenCalled()
    state.formData.calculation.sequence.operation = 'ADJACENT'
    await nextTick()
    expect(state.resultTypeOptions.some((option: { value: string }) => option.value === 'TEXT')).toBe(true)
    state.apply()
    expect(onApply).toHaveBeenCalledOnce()
  })

  it('包含计算结果允许明细汇总并按结果类型试算，基础公式和顺序计算不提供汇总候选', async () => {
    const summary = { ...newField(3, '明细合计'), key: 'total', code: 'total', type: 'SUMMARY' as const }
    const local = { ...sequence(), mode: 'LOCAL', aggregate: 'SINGLE' }
    const state = await mount(FormulaConfigurationModal, {
      field: { ...newField(4, '折后结果'), key: 'discount', code: 'discount', type: 'FORMULA' },
      fields: [...fields, summary],
      options: { ...defaultFieldOptions(), expression: 'total * 0.9', calculation: local },
      fieldOptions: { total: { ...defaultFieldOptions(), resultType: 'INTEGER', expression: 'count(items)' } },
      relations: []
    })
    state.openEditor()
    expect(state.expressionFields.find((field: { code: string }) => field.code === 'total')?.type).toBe('INTEGER')
    state.formData.calculation = null
    await nextTick()
    expect(state.expressionFields.some((field: { code: string }) => field.code === 'total')).toBe(false)
    state.formData.calculation = sequence()
    await nextTick()
    expect(state.expressionFields.some((field: { code: string }) => field.code === 'total')).toBe(false)
  })
  it('打开并应用旧相邻公式不自动写入新增默认属性，避免已发布定义出现无意义变更', async () => {
    const calculation = sequence()
    calculation.sequence = { orderField: 'serial', tieBreakerField: null, direction: 'PREVIOUS' }
    const options = {
      ...defaultFieldOptions(),
      expression: 'amount - coalesce(__previous_amount, 0)',
      resultType: 'DECIMAL',
      calculation
    }
    const onApply = vi.fn()
    const state = await mount(FormulaConfigurationModal, {
      field: { ...newField(3, '差额'), key: 'delta', code: 'delta', type: 'FORMULA' },
      fields,
      options,
      fieldOptions: {},
      relations: [],
      onApply
    })
    state.openEditor()
    state.apply()
    expect(onApply.mock.calls[0]?.[0].calculation).toEqual(calculation)
    expect(onApply.mock.calls[0]?.[0].calculation.sequence).not.toHaveProperty('operation')
    expect(onApply.mock.calls[0]?.[0].calculation.sequence).not.toHaveProperty('initialValue')
  })
  it('取消丢弃公式、分组与期初修改；应用仅返回计算配置且不共享嵌套引用', async () => {
    const options = {
      ...defaultFieldOptions(),
      expression: 'amount',
      calculation: sequence(),
      description: '其他字段设置'
    }
    const apply = vi.fn()
    const state = await mount(FormulaConfigurationModal, {
      field: { ...newField(3, '余额'), key: 'balance', code: 'balance', type: 'FORMULA' },
      fields,
      options,
      fieldOptions: {},
      relations: [],
      onApply: apply
    })
    state.openEditor()
    state.formData.expression = '-amount'
    state.formData.calculation.groupFields.push('serial')
    state.formData.calculation.sequence.initialValue = '999'
    state.closeModal()
    expect(options.expression).toBe('amount')
    expect(options.calculation.groupFields).toEqual(['account'])
    expect(options.calculation.sequence?.initialValue).toBe('1000')
    expect(apply).not.toHaveBeenCalled()
    state.openEditor()
    expect(state.formData.expression).toBe('amount')
    state.formData.expression = 'amount * 2'
    state.apply()
    const saved = apply.mock.calls[0]![0]
    expect(saved.expression).toBe('amount * 2')
    expect(saved).not.toHaveProperty('description')
    expect(saved.calculation.sequence.initialValue).toBe('1000')
    expect(saved.calculation).not.toBe(options.calculation)
    expect(options.expression).toBe('amount')
  })

  it('累计移除相邻候选，只允许本行基础与本行公式，排除跨记录公式和自身', async () => {
    const calculated = { ...newField(3, '净额'), key: 'net', code: 'net', type: 'FORMULA' as const }
    const lookup = { ...calculated, key: 'lookup', code: 'lookup' }
    const state = await mount(FormulaConfigurationModal, {
      field: { ...calculated, key: 'balance', code: 'balance' },
      fields: [...fields, calculated, lookup],
      options: { ...defaultFieldOptions(), expression: 'amount', calculation: sequence() },
      fieldOptions: {
        net: { ...defaultFieldOptions(), expression: 'amount' },
        lookup: { ...defaultFieldOptions(), calculation: { ...sequence(), mode: 'LOOKUP' } }
      },
      relations: []
    })
    state.openEditor()
    expect(state.expressionFields.map((field: { code: string }) => field.code)).toContain('net')
    expect(state.expressionFields.some((field: { code: string }) => field.code.startsWith('__previous_'))).toBe(false)
    expect(state.expressionFields.map((field: { code: string }) => field.code)).not.toContain('lookup')
    state.formData.calculation.sequence.operation = 'ADJACENT'
    await nextTick()
    expect(state.expressionFields.map((field: { code: string }) => field.code)).toContain('__previous_net')
  })

  it('多行试算使用服务端、保留高精度与空值，并在样例变更后忽略过期响应', async () => {
    let resolve!: (value: FormulaPreviewResult) => void
    const preview = vi.fn(
      (_request: FormulaPreviewRequest) =>
        new Promise<FormulaPreviewResult>(yes => {
          resolve = yes
        })
    )
    const state = await mount(
      FormulaSequencePreview,
      { expression: 'coalesce(amount, 0)', fields, calculation: sequence() },
      preview
    )
    state.samples = [
      { amount: '900719925474099312345.12', account: 'A', serial: '2' },
      { amount: '', account: 'A', serial: '1' }
    ]
    await nextTick()
    const pending = state.preview()
    expect(preview.mock.calls[0]?.[0]).toMatchObject({
      fieldTypes: { amount: 'DECIMAL', account: 'TEXT', serial: 'INTEGER' },
      rows: [
        { amount: '900719925474099312345.12', account: 'A', serial: '2' },
        { amount: null, account: 'A', serial: '1' }
      ],
      groupFields: ['account'],
      sequence: { operation: 'CUMULATIVE', initialValue: '1000' }
    })
    state.samples[0].amount = '20'
    await nextTick()
    resolve({
      value: null,
      resultType: 'DECIMAL',
      referencedFields: ['amount'],
      rows: [{ index: 0, value: 'old', contribution: 'old', adjacentIndex: 1 }]
    })
    await pending
    expect(state.results).toEqual([])
    expect(state.busy).toBe(false)
    preview.mockResolvedValueOnce({
      value: null,
      resultType: 'DECIMAL',
      referencedFields: ['amount'],
      rows: [{ index: 0, value: '1020', contribution: '20', adjacentIndex: 1 }]
    })
    await state.preview()
    expect(state.resultAt(0)?.value).toBe('1020')
    state.samples.splice(1, 1)
    await nextTick()
    expect(state.results).toEqual([])
  })
})

describe('明细金额汇总配置', () => {
  it.each(['sum', 'avg', 'min', 'max'])('%s 金额字段及金额公式输出 MONEY，计数仍输出 INTEGER', async aggregate => {
    const amount = { ...newField(0, '金额'), key: 'amount', code: 'amount', type: 'MONEY' as const }
    const formula = { ...amount, key: 'net', code: 'net', type: 'FORMULA' as const }
    const textFormula = { ...formula, key: 'label', code: 'label' }
    const inactive = { ...amount, key: 'inactive', code: 'inactive' }
    const inactiveFormula = { ...formula, key: 'inactive_formula', code: 'inactive_formula' }
    const onResultType = vi.fn()
    const onUpdate = vi.fn()
    const state = await mount(SummaryExpressionEditor, {
      modelValue: `${aggregate}(items.amount)`,
      details: [
        {
          id: 'items',
          code: 'items',
          name: '明细',
          state: 'ACTIVE',
          tableName: 'biz_items',
          fields: [amount, formula, textFormula, fields[1], inactive, inactiveFormula],
          fieldOptions: {
            net: { ...defaultFieldOptions(), expression: 'amount * 0.9', resultType: 'MONEY' },
            label: { ...defaultFieldOptions(), expression: 'account', resultType: 'TEXT' },
            inactive: { ...defaultFieldOptions(), state: 'INACTIVE' },
            inactive_formula: { ...defaultFieldOptions(), state: 'INACTIVE', resultType: 'MONEY' }
          },
          indexes: []
        }
      ],
      onResultType,
      'onUpdate:modelValue': onUpdate
    })
    expect(state.fields.map((field: { code: string }) => field.code)).toEqual(['amount', 'net'])
    for (const source of ['amount', 'net']) {
      state.fieldCode = source
      state.update()
      expect(onUpdate).toHaveBeenLastCalledWith(`${aggregate}(items.${source})`)
      expect(onResultType).toHaveBeenLastCalledWith('MONEY')
    }
    state.aggregate = 'count'
    state.update()
    expect(onUpdate).toHaveBeenLastCalledWith('count(items)')
    expect(onResultType).toHaveBeenLastCalledWith('INTEGER')
  })
})
