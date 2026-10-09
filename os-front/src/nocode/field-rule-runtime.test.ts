import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { FieldRuleResult } from '@/types/nocode/field-rules'
import type { NocodeHttpClient } from '@/api/nocode/object'
import type { TaskEntryContext } from '@/types/nocode/task-entry'
import { createRuntimeApi } from '@/api/nocode/runtime'
import { createApplicationApi } from '@/api/nocode/application'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import { taskEntryRuntime } from './task-entry'
import {
  businessFieldRules,
  fieldRulePatch,
  fieldRuleView,
  fieldRuleViews,
  FORMULA_LOCKED_TEXT,
  LINKAGE_LOCKED_TEXT,
  NO_MATCH_TEXT
} from './business-field-rules'
import {
  createFieldRuleCoordinator,
  pendingText,
  ruleAutoUpdate,
  ruleReadOnly,
  type FieldRuleEvaluatePart,
  type RuleDetailSource,
  type RuleSource,
  type RuleStates
} from './field-rule-runtime'

const ruled = (dependsOn: string[] = []) => ({ rules: { dependsOn } }) as FieldOptions
const plain = {} as FieldOptions
const result = (fieldId: string, value: unknown, extra: Partial<FieldRuleResult> = {}): FieldRuleResult => ({
  fieldId,
  kind: 'LINKAGE',
  state: 'APPLIED',
  value,
  matchedRows: 1,
  readOnly: false,
  message: null,
  pendingFields: [],
  ...extra
})
const deferred = () => {
  let resolve!: (value: FieldRuleResult[]) => void
  const promise = new Promise<FieldRuleResult[]>(r => {
    resolve = r
  })
  return { promise, resolve }
}
const rows = (count: number, creating = false) =>
  Array.from({ length: count }, (_, index) => ({
    rowKey: `row-${index}`,
    detailRecordId: creating ? null : String(index),
    creating,
    values: { line: `L${index}`, account: null as unknown }
  }))

/** 主表：company → bank（联动），amount 与规则无关；明细 lines：line → account（本行），bank → account（主表）。 */
function fixture(options: { creating?: boolean; lineRows?: RuleDetailSource['rows']; debounceMs?: number } = {}) {
  const lines: RuleDetailSource = {
    detailId: 'lines',
    options: { line: plain, account: ruled(['line', 'company']) },
    fieldIds: ['line', 'account'],
    rows: options.lineRows || [],
    canWrite: () => true
  }
  const other: RuleDetailSource = {
    detailId: 'other',
    options: { note: plain, copy: ruled(['note']) },
    rows: [{ rowKey: 'o-1', detailRecordId: '9', creating: false, values: { note: 'n', copy: null } }],
    canWrite: () => true
  }
  const source: RuleSource = {
    creating: options.creating ?? false,
    options: { company: plain, bank: ruled(['company']), amount: plain },
    values: { company: 'C1', bank: null, amount: '1' },
    canWrite: () => true,
    details: [lines, other]
  }
  let states: RuleStates = { master: {}, rows: {} }
  const errors: unknown[] = []
  const evaluate = vi.fn<(part: FieldRuleEvaluatePart) => Promise<FieldRuleResult[]>>(async () => [])
  const coordinator = createFieldRuleCoordinator({
    read: () => source,
    evaluate,
    onStates: next => {
      states = next
    },
    onError: error => errors.push(error),
    debounceMs: options.debounceMs
  })
  return { source, lines, other, evaluate, coordinator, errors, states: () => states }
}
async function microtasks() {
  for (let i = 0; i < 30; i++) await Promise.resolve()
}

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('触发时机', () => {
  it('编辑已有记录打开时不求值，之后依赖变化才请求', async () => {
    const { source, evaluate, coordinator } = fixture({ creating: false, lineRows: rows(2) })
    coordinator.start()
    await vi.advanceTimersByTimeAsync(2000)
    expect(evaluate).not.toHaveBeenCalled()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    expect(evaluate).toHaveBeenCalledTimes(1)
    expect(evaluate.mock.calls[0]![0]).toMatchObject({ creating: false, changed: ['company'] })
  })

  it('新建表单挂载时全量求值一次（changed 为空，新行按 creating 全量）', async () => {
    const { evaluate, coordinator } = fixture({ creating: true, lineRows: rows(2, true) })
    coordinator.start()
    await microtasks()
    expect(evaluate).toHaveBeenCalledTimes(1)
    const part = evaluate.mock.calls[0]![0]
    expect(part).toMatchObject({ creating: true, changed: [] })
    expect(part.details).toEqual([
      expect.objectContaining({
        detailId: 'lines',
        rows: [
          expect.objectContaining({ rowKey: 'row-0', creating: true, changed: [] }),
          expect.objectContaining({ rowKey: 'row-1', creating: true, changed: [] })
        ]
      })
    ])
    await vi.advanceTimersByTimeAsync(2000)
    expect(evaluate).toHaveBeenCalledTimes(1)
  })

  it('300ms 内多次变化只发一个请求，changed 合并', async () => {
    const { source, lines, evaluate, coordinator } = fixture({ lineRows: rows(1) })
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(200)
    source.values.company = 'C3'
    lines.rows[0]!.values.line = 'changed'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(299)
    expect(evaluate).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(evaluate).toHaveBeenCalledTimes(1)
    const part = evaluate.mock.calls[0]![0]
    expect(part.changed).toEqual(['company'])
    expect(part.details?.[0]?.rows).toEqual([expect.objectContaining({ rowKey: 'row-0', changed: ['line'] })])
  })

  it('与规则无关的字段变化不发请求', async () => {
    const { source, evaluate, coordinator } = fixture()
    coordinator.start()
    source.values.amount = '99'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(1000)
    expect(evaluate).not.toHaveBeenCalled()
  })

  it('只有明细行变化时，主表 changed 带明细字段 ID，不退化成主表全量', async () => {
    const { lines, evaluate, coordinator } = fixture({ lineRows: rows(2) })
    coordinator.start()
    lines.rows[1]!.values.line = 'X'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    const part = evaluate.mock.calls[0]![0]
    expect(part.changed).toEqual(['line'])
    expect(part.details).toEqual([
      { detailId: 'lines', rows: [expect.objectContaining({ rowKey: 'row-1', changed: ['line'] })] }
    ])
  })

  it('新增、复制、粘贴的行以 creating=true 进入同一批', async () => {
    const { lines, evaluate, coordinator } = fixture({ lineRows: rows(1) })
    coordinator.start()
    lines.rows.push(
      { rowKey: 'new-1', creating: true, values: { line: 'P1', account: null } },
      { rowKey: 'new-2', creating: true, values: { line: 'P2', account: 'copied' } }
    )
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    expect(evaluate).toHaveBeenCalledTimes(1)
    expect(evaluate.mock.calls[0]![0].details?.[0]?.rows).toEqual([
      expect.objectContaining({ rowKey: 'new-1', creating: true, changed: [], overridable: ['account'] }),
      expect.objectContaining({ rowKey: 'new-2', creating: true, changed: [], overridable: [] })
    ])
  })
})

describe('主表加明细批量（B61）', () => {
  it('batchesMasterAndRows：主表变化把受影响明细组的所有行放进同一个请求，不相关的组不带', async () => {
    const { source, evaluate, coordinator, states } = fixture({ lineRows: rows(3) })
    evaluate.mockImplementation(async part =>
      (part.details || []).flatMap(group =>
        group.rows.map(row =>
          result('account', `A-${row.rowKey}`, { detailId: group.detailId, rowKey: row.rowKey, readOnly: true })
        )
      )
    )
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    await microtasks()
    expect(evaluate).toHaveBeenCalledTimes(1)
    const part = evaluate.mock.calls[0]![0]
    expect(part.details?.map(d => d.detailId)).toEqual(['lines'])
    expect(part.details?.[0]?.rows.map(r => [r.rowKey, r.creating, r.changed])).toEqual([
      ['row-0', false, []],
      ['row-1', false, []],
      ['row-2', false, []]
    ])
    expect(source.details[0]!.rows.map(r => r.values.account)).toEqual(['A-row-0', 'A-row-1', 'A-row-2'])
    expect(states().rows.lines?.['row-1']?.account?.readOnly).toBe(true)
  })

  it('超过 500 行按组、按 500 行分片串行发送', async () => {
    const { source, evaluate, coordinator } = fixture({ lineRows: rows(1200) })
    const first = deferred()
    evaluate.mockImplementationOnce(() => first.promise)
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    expect(evaluate).toHaveBeenCalledTimes(1)
    first.resolve([])
    await microtasks()
    await coordinator.settle()
    expect(evaluate.mock.calls.map(([part]) => part.details?.[0]?.rows.length)).toEqual([500, 500, 200])
    expect(evaluate.mock.calls.every(([part]) => part.changed.includes('company'))).toBe(true)
  })

  it('删除的行不再接收结果，状态随之移除', async () => {
    const { lines, evaluate, coordinator, states } = fixture({ lineRows: rows(2) })
    const pending = deferred()
    evaluate.mockImplementationOnce(() => pending.promise)
    coordinator.start()
    lines.rows[0]!.values.line = 'X'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    const removed = lines.rows.shift()!
    coordinator.sync()
    pending.resolve([result('account', 'late', { detailId: 'lines', rowKey: 'row-0' })])
    await coordinator.settle()
    expect(removed.values.account).toBeNull()
    expect(states().rows.lines?.['row-0']).toBeUndefined()
  })
})

describe('写回规则', () => {
  it('新建带出建议值；用户改过的值不被后续结果覆盖，也不再声明为可覆盖', async () => {
    const { source, evaluate, coordinator } = fixture({ creating: true })
    evaluate.mockResolvedValueOnce([result('bank', 'B1')])
    coordinator.start()
    await coordinator.settle()
    expect(source.values.bank).toBe('B1')
    source.values.bank = 'MINE'
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', 'B2')])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(evaluate.mock.calls[1]![0].overridable).not.toContain('bank')
    expect(source.values.bank).toBe('MINE')
  })

  it('未被改动的自动值随依赖变化更新，并声明为可覆盖', async () => {
    const { source, evaluate, coordinator } = fixture({ creating: true })
    evaluate.mockResolvedValueOnce([result('bank', 'B1')])
    coordinator.start()
    await coordinator.settle()
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', 'B2')])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(evaluate.mock.calls[1]![0].overridable).toContain('bank')
    expect(source.values.bank).toBe('B2')
  })

  it('请求在途时用户手工输入，响应不覆盖', async () => {
    const { source, evaluate, coordinator } = fixture()
    const pending = deferred()
    evaluate.mockImplementationOnce(() => pending.promise)
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    source.values.bank = 'typed'
    pending.resolve([result('bank', 'server')])
    await coordinator.settle()
    expect(source.values.bank).toBe('typed')
  })

  it('迟到响应按行 + 字段丢弃：旧请求的结果不能覆盖新一轮', async () => {
    const { source, evaluate, coordinator } = fixture()
    const first = deferred(),
      second = deferred()
    evaluate.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise)
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    source.values.company = 'C3'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    first.resolve([result('bank', 'for-C2')])
    await microtasks()
    expect(source.values.bank).toBeNull()
    expect(evaluate).toHaveBeenCalledTimes(2)
    second.resolve([result('bank', 'for-C3')])
    await coordinator.settle()
    expect(source.values.bank).toBe('for-C3')
  })

  it('只读且 APPLIED 强制写入并记录只读状态', async () => {
    const { source, evaluate, coordinator, states } = fixture()
    source.values.bank = 'user'
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', 'locked', { readOnly: true })])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(source.values.bank).toBe('locked')
    expect(states().master.bank).toMatchObject({ state: 'APPLIED', readOnly: true })
  })

  it('非 APPLIED 清掉未被用户改动的自动值；用户值保留', async () => {
    const { source, evaluate, coordinator, states } = fixture({ creating: true })
    evaluate.mockResolvedValueOnce([result('bank', 'B1')])
    coordinator.start()
    await coordinator.settle()
    source.values.company = ''
    coordinator.sync()
    evaluate.mockResolvedValueOnce([
      result('bank', null, { state: 'PENDING_ROW_VALUE', pendingFields: ['company'], message: 'x' })
    ])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(source.values.bank).toBeNull()
    expect(states().master.bank?.state).toBe('PENDING_ROW_VALUE')

    source.values.bank = 'user'
    source.values.company = 'C9'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', null, { state: 'NO_MATCH', message: '没有匹配' })])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(source.values.bank).toBe('user')
  })

  it('协调器自己的写回不会再次触发求值', async () => {
    const { source, evaluate, coordinator } = fixture({ creating: true })
    source.options.bank = ruled(['company'])
    source.options.amount = ruled(['bank', 'company'])
    evaluate.mockResolvedValueOnce([result('bank', 'B1'), result('amount', '10')])
    coordinator.start()
    await coordinator.settle()
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(1000)
    expect(evaluate).toHaveBeenCalledTimes(1)
  })

  it('无写权限时只记录状态不写值；公式默认值 NOT_APPLICABLE 不清空', async () => {
    const { source, evaluate, coordinator, states } = fixture()
    source.canWrite = () => false
    source.values.bank = 'db'
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([
      result('bank', 'x', { readOnly: true }),
      result('amount', null, { kind: 'DEFAULT_FORMULA', state: 'NOT_APPLICABLE' })
    ])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(source.values).toMatchObject({ bank: 'db', amount: '1' })
    expect(states().master.bank?.readOnly).toBe(true)
  })

  it('求值失败交给调用方提示，输入保留', async () => {
    const { source, evaluate, coordinator, errors } = fixture()
    evaluate.mockRejectedValueOnce(new Error('boom'))
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(errors).toHaveLength(1)
    expect(source.values.company).toBe('C2')
  })

  it('settle 立即发出防抖中的请求；dispose 后不再写回', async () => {
    const { source, evaluate, coordinator } = fixture()
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    expect(coordinator.busy()).toBe(true)
    const pending = deferred()
    evaluate.mockImplementationOnce(() => pending.promise)
    const settled = coordinator.settle()
    expect(coordinator.busy()).toBe(true)
    await microtasks()
    expect(evaluate).toHaveBeenCalledTimes(1)
    coordinator.dispose()
    pending.resolve([result('bank', 'late')])
    await settled
    expect(source.values.bank).toBeNull()
  })
})

describe('只读口径（业务方 2026-09-29 裁定）', () => {
  const names = { company: { name: '公司', detailId: null }, line: { name: '摘要', detailId: 'lines' } }
  const readOnlyRule = (dependsOn: string[], linkage = true) =>
    ({
      rules: {
        dependsOn,
        readOnly: true,
        ...(linkage ? { linkage: { readOnly: null, sourceObjectId: null, conditions: null, valueFieldId: null } } : {})
      }
    }) as unknown as FieldOptions

  it('只读联动 NO_MATCH / PENDING：用户输入被清空、字段锁定并显示原因；只读字段恒声明可覆盖', async () => {
    const { source, evaluate, coordinator, states } = fixture()
    source.options.bank = readOnlyRule(['company'])
    source.values.bank = 'user'
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', null, { state: 'NO_MATCH', readOnly: true, message: null })])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(evaluate.mock.calls[0]![0].overridable).toContain('bank')
    expect(source.values.bank).toBeNull()
    expect(fieldRuleViews(states().master, source.options, names).bank).toMatchObject({
      locked: true,
      message: NO_MATCH_TEXT
    })

    source.values.bank = 'typed again'
    source.values.company = ''
    coordinator.sync()
    evaluate.mockResolvedValueOnce([
      result('bank', null, { state: 'PENDING_ROW_VALUE', readOnly: true, pendingFields: ['company'] })
    ])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(source.values.bank).toBeNull()
    expect(fieldRuleViews(states().master, source.options, names).bank).toMatchObject({
      locked: true,
      message: '请先填写「公司」'
    })
  })

  it('结果未标 readOnly 时以投影 rules.readOnly 为准：非 APPLIED 仍清空', async () => {
    const { source, evaluate, coordinator } = fixture()
    source.options.bank = readOnlyRule(['company'])
    source.values.bank = 'user'
    coordinator.start()
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', null, { state: 'NO_MATCH', message: '这条联动一行都没命中' })])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(source.values.bank).toBeNull()
  })

  it('明细行只读联动 NO_MATCH 清空本行用户值', async () => {
    const { lines, evaluate, coordinator } = fixture({ lineRows: rows(1) })
    lines.options.account = readOnlyRule(['line', 'company'])
    lines.rows[0]!.values.account = 'typed'
    coordinator.start()
    lines.rows[0]!.values.line = 'X'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([
      result('account', null, { state: 'NO_MATCH', readOnly: true, detailId: 'lines', rowKey: 'row-0' })
    ])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(lines.rows[0]!.values.account).toBeNull()
  })

  it('编辑已有记录：依赖变化后公式默认值被重算、覆盖原值并锁定', async () => {
    const { source, evaluate, coordinator, states } = fixture({ creating: false })
    source.options.total = readOnlyRule(['amount'], false)
    source.values.total = '100'
    coordinator.start()
    await vi.advanceTimersByTimeAsync(1000)
    expect(evaluate).not.toHaveBeenCalled()
    source.values.amount = '2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('total', '200', { kind: 'DEFAULT_FORMULA', readOnly: true })])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(evaluate).toHaveBeenCalledTimes(1)
    expect(evaluate.mock.calls[0]![0]).toMatchObject({ creating: false, changed: ['amount'] })
    expect(evaluate.mock.calls[0]![0].overridable).toContain('total')
    expect(source.values.total).toBe('200')
    const view = fieldRuleViews(states().master, source.options, names).total!
    expect(view).toMatchObject({ kind: 'DEFAULT_FORMULA', locked: true, message: null })
    expect(fieldRulePatch(view, { business: false })).toEqual({ wrap: { extra: FORMULA_LOCKED_TEXT } })
  })

  it('编辑打开（尚无结果）：公式默认值与只读联动按投影直接锁定，主表与明细一致', () => {
    const options = { total: readOnlyRule(['amount'], false), bank: readOnlyRule(['company']), amount: {} } as never
    for (const detailId of [null, 'lines']) {
      const views = fieldRuleViews({}, options, names, detailId)
      expect(views.total).toMatchObject({ kind: 'DEFAULT_FORMULA', locked: true })
      expect(views.bank).toMatchObject({ kind: 'LINKAGE', locked: true })
      expect(views.amount).toBeUndefined()
    }
  })

  it('非只读联动（开关显式关掉）：仍是建议值，用户改过不覆盖，未命中提示可手动填写且不锁定', async () => {
    const { source, evaluate, coordinator, states } = fixture({ creating: true })
    source.options.bank = { rules: { dependsOn: ['company'], linkage: { readOnly: false } } } as unknown as FieldOptions
    evaluate.mockResolvedValueOnce([result('bank', 'B1')])
    coordinator.start()
    await coordinator.settle()
    source.values.bank = 'MINE'
    source.values.company = 'C2'
    coordinator.sync()
    evaluate.mockResolvedValueOnce([result('bank', null, { state: 'NO_MATCH', message: null })])
    await vi.advanceTimersByTimeAsync(300)
    await coordinator.settle()
    expect(evaluate.mock.calls[1]![0].overridable).not.toContain('bank')
    expect(source.values.bank).toBe('MINE')
    expect(fieldRuleViews(states().master, source.options, names).bank).toMatchObject({
      locked: false,
      message: '未能带出联动值，可手动填写'
    })
  })
})

describe('提示文案与端点', () => {
  it('PENDING 文案区分本行与主表字段', () => {
    const names = {
      company: { name: '公司', detailId: null },
      category: { name: '贷方科目分类', detailId: 'lines' }
    }
    expect(pendingText(['category'], names, 'lines')).toBe('请先填写「贷方科目分类」')
    expect(pendingText(['company'], names, 'lines')).toBe('请先填写主表「公司」')
    expect(pendingText(['company'], names, null)).toBe('请先填写「公司」')
  })

  it('运行、预览、任务入口三处求值走各自端点，任务入口拒绝跨对象', async () => {
    const post = vi.fn().mockResolvedValue({ results: [] }),
      client = { get: vi.fn(), post } as unknown as NocodeHttpClient
    const query = { applicationId: 'app', objectId: 'object', formId: 'form', values: {}, changed: [] }
    await createRuntimeApi(client).evaluateFieldRules(query)
    await createApplicationApi(client).previewFieldRules({ query, objects: [], form: {} as never })
    const context = {
      entry: { applicationId: 'app', entryId: 'entry', version: 3 },
      config: { objectId: 'object' },
      model: {}
    } as TaskEntryContext
    const runtime = taskEntryRuntime(createRuntimeApi(client), createTaskEntryApi(client), context)
    await runtime.evaluateFieldRules(query)
    await expect(runtime.evaluateFieldRules({ ...query, objectId: 'other' })).rejects.toThrow('不能切换')
    expect(post.mock.calls.map(call => [call[0], call[1]])).toEqual([
      ['/nocode/runtime/field-rules/evaluate', query],
      ['/nocode/application/field-rules-preview', { query, objects: [], form: {} }],
      ['/nocode/task-entry/field-rules', { entry: { applicationId: 'app', entryId: 'entry', version: 3 }, query }]
    ])
  })
})

describe('控件呈现（business-field-rules）', () => {
  const names = { company: { name: '公司', detailId: null } }
  // 2026-09-29 裁定：只读联动无论是否取到值都锁定（旧断言「NO_MATCH 时 locked=false」随之改为 true）。
  it('只读结果无论是否 APPLIED 都锁定；PENDING 显示请先填写；APPLIED 与不适用不出提示', () => {
    expect(fieldRuleView(result('bank', 'x', { readOnly: true }), names)).toMatchObject({ locked: true, message: null })
    const noMatch = result('bank', null, { readOnly: true, state: 'NO_MATCH', message: '没有匹配' })
    expect(fieldRuleView(noMatch, names)).toMatchObject({ locked: true, message: '没有匹配' })
    const pending = result('bank', null, { state: 'PENDING_ROW_VALUE', pendingFields: ['company'] })
    expect(fieldRuleView(pending, names, 'lines')).toMatchObject({
      locked: false,
      message: '请先填写主表「公司」',
      pending: '请先填写主表「公司」'
    })
    expect(fieldRuleView({ ...pending, readOnly: true }, names, 'lines')).toMatchObject({
      locked: true,
      message: '请先填写主表「公司」'
    })
    const formula = result('f', null, { kind: 'DEFAULT_FORMULA', state: 'NOT_APPLICABLE' })
    expect(fieldRuleView(formula, names)?.message).toBeNull()
    const reference = result('ref', null, { kind: 'REFERENCE', inScope: false })
    expect(fieldRuleView(reference, names)).toMatchObject({ message: null, inScope: false })
  })

  it('局部合并增量：业务控件交给 BusinessFieldControl，输入控件写表单项说明，表格模式改悬浮提示', () => {
    const locked = fieldRuleView(result('bank', 'x', { readOnly: true }), names)
    expect(fieldRulePatch(locked, { business: true })).toEqual({ props: { _osLinkage: locked } })
    expect(fieldRulePatch(locked, { business: false, help: '说明' })).toEqual({
      wrap: { extra: '说明 · ' + LINKAGE_LOCKED_TEXT }
    })
    expect(fieldRulePatch(null, { business: false, help: '说明' })).toEqual({ wrap: { extra: '说明' } })
    expect(fieldRulePatch(locked, { business: false, compact: true })).toEqual({
      props: { title: LINKAGE_LOCKED_TEXT },
      wrap: { extra: '' }
    })
  })

  // 2026-09-29 裁定：只读规则字段一律只读 —— 旧断言「新建不锁」「有结果后 NO_MATCH 放开」改为始终锁定；
  // fieldRuleViews 不再按 creating 区分，签名去掉 creating 参数。
  it('投影保留 readOnly 的只读联动字段直接锁定（新建与编辑、主表与明细）；有结果后提示按结果、锁定不放开', () => {
    // 投影后 linkage 只剩 readOnly，其它键为 null（运行时形态，类型上按非空声明）
    const options = {
      bank: { rules: { linkage: { readOnly: true, sourceObjectId: null, conditions: null, valueFieldId: null } } },
      memo: { rules: { linkage: { readOnly: false } } },
      amount: {}
    } as never
    const edit = fieldRuleViews({}, options, names)
    expect(Object.keys(edit)).toEqual(['bank'])
    expect(edit.bank).toMatchObject({ locked: true, message: null })
    expect(fieldRulePatch(edit.bank!, { business: false })).toEqual({ wrap: { extra: LINKAGE_LOCKED_TEXT } })
    const rowEdit = fieldRuleViews({}, options, names, 'lines')
    expect(rowEdit.bank?.locked).toBe(true)
    const noMatch = result('bank', null, { readOnly: false, state: 'NO_MATCH', message: '没有匹配' })
    expect(fieldRuleViews({ bank: noMatch }, options, names).bank).toMatchObject({
      locked: true,
      message: '没有匹配'
    })
  })

  it('明细行的引用字段把依赖拆成本行与主表两组', () => {
    const fields = [
      { id: 'category', key: 'category', code: 'category', name: '分类', type: 'TEXT' },
      { id: 'account', key: 'account', code: 'account', name: '科目', type: 'REFERENCE' }
    ] as never
    const options = { category: {}, account: { rules: { dependsOn: ['category', 'company'] } } } as never
    const relations = [{ id: 'r1', fieldId: 'account', targetObjectId: 'subjects', kind: 'REFERENCE' }] as never
    const model = { writable: true, generatedKey: true, keyFieldId: 'id', keyType: 'text' } as never
    const rule = businessFieldRules(fields, options, model, true, {
      applicationId: 'app',
      objectId: 'voucher',
      detailId: 'lines',
      relations
    }).find(r => r.field === 'account')!
    expect(rule.props).toMatchObject({ ruleDependsOn: ['category'], ruleMasterDependsOn: ['company'] })
  })
})

// 第一期契约 2.4、9.4：投影里只读联动且开了自动更新时 linkage.autoUpdate 为 true；没开时这个键不出现。
describe('系统自动更新标识的判据', () => {
  it('只有 rules.linkage.autoUpdate === true 才算；没有这个键、null、false 与没有规则的字段都不算', () => {
    const projected = (linkage: Record<string, unknown>) => ({ rules: { readOnly: true, linkage } }) as never
    expect(ruleAutoUpdate(projected({ readOnly: true, autoUpdate: true }))).toBe(true)
    expect(ruleAutoUpdate(projected({ readOnly: true }))).toBe(false)
    expect(ruleAutoUpdate(projected({ readOnly: true, autoUpdate: null }))).toBe(false)
    expect(ruleAutoUpdate(projected({ readOnly: true, autoUpdate: false }))).toBe(false)
    expect(ruleAutoUpdate({ rules: { readOnly: true } } as never)).toBe(false)
    expect(ruleAutoUpdate({} as never)).toBe(false)
    expect(ruleAutoUpdate(null)).toBe(false)
    expect(ruleAutoUpdate(undefined)).toBe(false)
  })
  it('自动更新字段仍按只读联动锁定，只读判据不变', () => {
    const option = { rules: { readOnly: true, linkage: { readOnly: true, autoUpdate: true } } } as never
    expect(ruleReadOnly(option)).toBe(true)
    expect(fieldRuleViews({}, { status: option }, {}).status).toMatchObject({ kind: 'LINKAGE', locked: true })
  })
})
