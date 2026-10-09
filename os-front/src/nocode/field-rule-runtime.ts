import type { InjectionKey, Ref } from 'vue'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { FieldRuleEvaluateQuery, FieldRuleResult } from '@/types/nocode/field-rules'
import { editSignature } from './edit-signature'
import { emptyFormValue } from './form-behavior'

/** 与设计稿 5.5、15.4.6 一致：防抖窗口、每组行数上限（同保存上限）和单次组数上限。 */
export const FIELD_RULE_DEBOUNCE_MS = 300
export const FIELD_RULE_ROW_CAP = 500
export const FIELD_RULE_GROUP_CAP = 20
export const FieldRuleState = {
  APPLIED: 'APPLIED',
  PENDING: 'PENDING_ROW_VALUE',
  NO_MATCH: 'NO_MATCH',
  NOT_APPLICABLE: 'NOT_APPLICABLE'
} as const

export type FieldRuleEvaluatePart = Pick<FieldRuleEvaluateQuery, 'creating' | 'details'> &
  Required<Pick<FieldRuleEvaluateQuery, 'values' | 'changed' | 'overridable'>>
/** 按字段 ID 索引的最近一次求值结果。 */
export type RuleStateMap = Record<string, FieldRuleResult>
export interface RuleStates {
  master: RuleStateMap
  /** detailId → rowKey → fieldId → 结果。 */
  rows: Record<string, Record<string, RuleStateMap>>
}
export interface RuleRowSource {
  rowKey: string
  detailRecordId?: string | null
  creating: boolean
  values: Record<string, unknown>
}
export interface RuleDetailSource {
  detailId: string
  options: Record<string, FieldOptions | undefined>
  /** 本明细的字段 ID；缺省取 options 的键。用于区分依赖属于本行还是主表。 */
  fieldIds?: string[]
  rows: RuleRowSource[]
  canWrite: (fieldId: string) => boolean
}
/** 协调器每次读取当前的实时对象；写回直接落在这些对象上。 */
export interface RuleSource {
  creating: boolean
  options: Record<string, FieldOptions | undefined>
  values: Record<string, unknown>
  canWrite: (fieldId: string) => boolean
  details: RuleDetailSource[]
}
export interface FieldRuleCoordinatorOptions {
  read: () => RuleSource
  evaluate: (part: FieldRuleEvaluatePart) => Promise<FieldRuleResult[]>
  onStates?: (states: RuleStates) => void
  onError?: (error: unknown) => void
  debounceMs?: number
}

/** 字段显示名：用于「请先填写「X」」；detailId 为空表示主表字段。 */
export interface RuleFieldName {
  name: string
  detailId: string | null
}
export const fieldRuleNamesKey: InjectionKey<Ref<Record<string, RuleFieldName>>> = Symbol('field-rule-names')

export const EMPTY_RULE_STATES: RuleStateMap = Object.freeze({}) as RuleStateMap

/** 服务端投影后规则只剩 dependsOn（全局字段 ID 的传递闭包）；有 rules 键即表示该字段挂了规则。 */
export const ruleDependsOn = (options: Record<string, FieldOptions | undefined>, fieldId: string): string[] =>
  options[fieldId]?.rules?.dependsOn || []
/**
 * 只读规则字段（业务方 2026-09-29 裁定）：只读联动与公式默认值的值只能由规则带出，用户不能填写。
 * 投影给出 rules.readOnly；旧投影只保留 rules.linkage.readOnly，两者任一为 true 即只读。
 */
export const ruleReadOnly = (options?: FieldOptions | null): boolean =>
  options?.rules?.readOnly === true || options?.rules?.linkage?.readOnly === true
/** 新对象规则接管赋值后，旧关联带入不得再次覆盖同一目标；引用筛选本身不算赋值。 */
export const hasObjectValueRule = (options?: FieldOptions | null): boolean =>
  !!options?.rules?.linkage || options?.rules?.defaultFormula != null || options?.rules?.readOnly === true
/**
 * 系统自动更新的字段（2026-10-01 第一期契约 2.4、9.4）：投影里只读联动且开了自动更新时 linkage.autoUpdate 为 true，
 * 没开时这个键不出现。只认 === true；只读行为由 ruleReadOnly 负责，这里只决定要不要加「系统自动更新」标识。
 */
export const ruleAutoUpdate = (options?: FieldOptions | null): boolean => options?.rules?.linkage?.autoUpdate === true
export const ruledFields = (options: Record<string, FieldOptions | undefined>): string[] =>
  Object.keys(options).filter(id => !!options[id]?.rules)
/** 依赖于 changed 中任一字段的规则字段。 */
export const dependentFields = (options: Record<string, FieldOptions | undefined>, changed: Iterable<string>) => {
  const set = new Set(changed)
  return ruledFields(options).filter(id => ruleDependsOn(options, id).some(d => set.has(d)))
}

/** 「请先填写「A」、主表「B」」：明细行的规则引用主表字段时加「主表」前缀。 */
export function pendingText(
  pending: string[] | null | undefined,
  names: Record<string, RuleFieldName>,
  detailId?: string | null
): string {
  const labels = (pending || []).map(id => {
    const name = names[id]
    const text = `「${name?.name || '依赖字段'}」`
    return detailId && name && !name.detailId ? '主表' + text : text
  })
  return '请先填写' + (labels.length ? [...new Set(labels)].join('、') : '依赖字段')
}

const MASTER = ''
const rowScope = (detailId: string, rowKey: string) => detailId + '\u0000' + rowKey
const stateKey = (scope: string, fieldId: string) => scope + '\u0001' + fieldId
const snapshot = (values: Record<string, unknown>) =>
  new Map(Object.entries(values).map(([id, value]) => [id, editSignature(value)]))
const copy = (values: Record<string, unknown>): Record<string, unknown> => JSON.parse(JSON.stringify(values))

interface PlannedRow {
  rowKey: string
  changed: string[]
}
interface Plan {
  seq: number
  full: boolean
  masterChanged: string[]
  hint: string[]
  groups: { detailId: string; rows: PlannedRow[] }[]
}

/**
 * 主表加全部明细共用的规则协调器（设计稿 5.5、15.4.6）。
 * - 新建挂载时全量求值一次；编辑已有记录打开时不求值，但依赖字段变化时照常求值（公式默认值编辑时也重算）。
 * - 依赖字段变化经 300ms 防抖合成一个请求；主表变化带上受影响明细组的全部行；超过 500 行按组分片串行。
 * - 迟到响应按「行 + 字段」代次丢弃；非只读字段用户改过的值不覆盖，非 APPLIED 只清掉未改动的自动值。
 * - 只读字段（结果 readOnly 或投影 rules.readOnly，业务方 2026-09-29 裁定）：APPLIED 强制写入；
 *   其它非 NOT_APPLICABLE 状态（PENDING、NO_MATCH 等）一律清空，不保留用户值。
 * - 协调器自身的写回同步进快照，不会再次触发求值；链式依赖由服务端按拓扑序一次算完。
 */
export function createFieldRuleCoordinator(options: FieldRuleCoordinatorOptions) {
  const debounceMs = options.debounceMs ?? FIELD_RULE_DEBOUNCE_MS
  let disposed = false,
    started = false,
    sequence = 0,
    running = 0,
    timer: ReturnType<typeof setTimeout> | undefined,
    inflight: Promise<void> = Promise.resolve()
  const snapshots = new Map<string, Map<string, string>>()
  const autoValues = new Map<string, string>()
  const latest = new Map<string, number>()
  let states: RuleStates = { master: {}, rows: {} }
  let full = false
  const masterChanged = new Set<string>()
  const rowChanged = new Map<string, { detailId: string; rowKey: string; fields: Set<string>; fresh: boolean }>()

  const emit = () => options.onStates?.(states)
  const pending = () => full || masterChanged.size > 0 || rowChanged.size > 0
  const ownFields = (detail: RuleDetailSource) => new Set(detail.fieldIds || Object.keys(detail.options))
  const hasRules = (source: RuleSource) =>
    ruledFields(source.options).length > 0 || source.details.some(d => ruledFields(d.options).length > 0)
  /** 主表规则只能引用主表字段；明细规则引用的非本明细字段即主表字段（设计稿 15.4.2）。 */
  const masterTriggers = (source: RuleSource) => {
    const triggers = new Set<string>()
    for (const id of ruledFields(source.options)) ruleDependsOn(source.options, id).forEach(dep => triggers.add(dep))
    for (const detail of source.details) {
      const own = ownFields(detail)
      for (const id of ruledFields(detail.options))
        for (const dep of ruleDependsOn(detail.options, id)) if (!own.has(dep)) triggers.add(dep)
    }
    return triggers
  }
  const rowTriggers = (detail: RuleDetailSource) => {
    const own = ownFields(detail)
    const triggers = new Set<string>()
    for (const id of ruledFields(detail.options))
      for (const dep of ruleDependsOn(detail.options, id)) if (own.has(dep)) triggers.add(dep)
    return triggers
  }
  const diff = (scope: string, values: Record<string, unknown>) => {
    const before = snapshots.get(scope) || new Map<string, string>()
    const after = snapshot(values)
    snapshots.set(scope, after)
    return [...new Set([...before.keys(), ...after.keys()])].filter(id => before.get(id) !== after.get(id))
  }
  const markRow = (detailId: string, rowKey: string, fields: string[], fresh: boolean) => {
    const scope = rowScope(detailId, rowKey)
    const entry = rowChanged.get(scope) || { detailId, rowKey, fields: new Set<string>(), fresh: false }
    fields.forEach(f => entry.fields.add(f))
    entry.fresh ||= fresh
    rowChanged.set(scope, entry)
  }
  const schedule = () => {
    clearTimeout(timer)
    timer = setTimeout(flush, debounceMs)
  }
  const forgetScope = (scope: string, detailId: string, rowKey: string) => {
    snapshots.delete(scope)
    rowChanged.delete(scope)
    for (const map of [autoValues, latest])
      for (const key of [...map.keys()]) if (key.startsWith(scope + '\u0001')) map.delete(key)
    const rows = states.rows[detailId]
    if (rows && Object.hasOwn(rows, rowKey)) {
      const { [rowKey]: _removed, ...rest } = rows
      states = { ...states, rows: { ...states.rows, [detailId]: rest } }
      return true
    }
    return false
  }

  /** 比较实时值与上次快照，只把规则依赖字段的变化计入待求值集合。 */
  function sync() {
    if (disposed || !started) return
    const source = options.read()
    const triggers = masterTriggers(source)
    for (const id of diff(MASTER, source.values)) if (triggers.has(id)) masterChanged.add(id)
    const seen = new Set<string>([MASTER])
    for (const detail of source.details) {
      const own = rowTriggers(detail)
      for (const row of detail.rows) {
        const scope = rowScope(detail.detailId, row.rowKey)
        seen.add(scope)
        if (!snapshots.has(scope)) {
          snapshots.set(scope, snapshot(row.values))
          if (row.creating && ruledFields(detail.options).length) markRow(detail.detailId, row.rowKey, [], true)
          continue
        }
        const fields = diff(scope, row.values).filter(id => own.has(id))
        if (fields.length) markRow(detail.detailId, row.rowKey, fields, false)
      }
    }
    let removed = false
    for (const scope of [...snapshots.keys()])
      if (!seen.has(scope)) {
        const [detailId = '', rowKey = ''] = scope.split('\u0000')
        removed = forgetScope(scope, detailId, rowKey) || removed
      }
    if (removed) emit()
    if (pending()) schedule()
  }

  function plan(source: RuleSource): Plan {
    const seq = ++sequence
    const changed = [...masterChanged]
    const detailsById = new Map(source.details.map(d => [d.detailId, d]))
    const groups = new Map<string, PlannedRow[]>()
    const add = (detailId: string, row: PlannedRow) => {
      const rows = groups.get(detailId) || []
      if (!rows.some(r => r.rowKey === row.rowKey)) rows.push(row)
      groups.set(detailId, rows)
    }
    for (const entry of rowChanged.values())
      add(entry.detailId, { rowKey: entry.rowKey, changed: entry.fresh ? [] : [...entry.fields] })
    for (const detail of source.details) {
      const affected = changed.length > 0 && dependentFields(detail.options, changed).length > 0
      for (const row of detail.rows)
        if (affected || (full && row.creating)) add(detail.detailId, { rowKey: row.rowKey, changed: [] })
    }
    // 代次在入队时推进：比本批更早发出的响应对同一「行 + 字段」一律作废。
    const masterTargets = full ? ruledFields(source.options) : dependentFields(source.options, changed)
    masterTargets.forEach(id => latest.set(stateKey(MASTER, id), seq))
    const hint = new Set<string>()
    for (const [detailId, rows] of groups) {
      const detail = detailsById.get(detailId)
      if (!detail) continue
      for (const planned of rows) {
        const row = detail.rows.find(r => r.rowKey === planned.rowKey)
        const all = !!row?.creating && !planned.changed.length
        const targets = all
          ? ruledFields(detail.options)
          : dependentFields(detail.options, [...planned.changed, ...changed])
        targets.forEach(id => latest.set(stateKey(rowScope(detailId, planned.rowKey), id), seq))
        ;(all ? ruledFields(detail.options) : planned.changed).forEach(id => hint.add(id))
      }
    }
    const result: Plan = {
      seq,
      full,
      masterChanged: changed,
      hint: [...hint],
      groups: [...groups].map(([detailId, rows]) => ({ detailId, rows }))
    }
    full = false
    masterChanged.clear()
    rowChanged.clear()
    return result
  }

  function flush() {
    clearTimeout(timer)
    timer = undefined
    if (disposed || !pending()) return
    const batch = plan(options.read())
    running++
    inflight = inflight
      .then(() => send(batch))
      .catch(error => {
        if (!disposed) options.onError?.(error)
      })
      .finally(() => {
        running--
      })
  }

  /** 只读规则字段恒可覆盖：值只能由规则带出。 */
  const overridable = (
    scope: string,
    values: Record<string, unknown>,
    ids: string[],
    fieldOptions: Record<string, FieldOptions | undefined>
  ) =>
    ids.filter(
      id =>
        ruleReadOnly(fieldOptions[id]) ||
        emptyFormValue(values[id]) ||
        editSignature(values[id]) === autoValues.get(stateKey(scope, id))
    )

  /** 分片：整批不超过 500 行且不超过 20 组时一次发送；否则按组、按 500 行切片串行发送。 */
  function chunks(groups: Plan['groups']): Plan['groups'][] {
    const total = groups.reduce((sum, g) => sum + g.rows.length, 0)
    if (total <= FIELD_RULE_ROW_CAP && groups.length <= FIELD_RULE_GROUP_CAP) return [groups]
    const parts: Plan['groups'][] = []
    for (const group of groups)
      for (let start = 0; start < group.rows.length; start += FIELD_RULE_ROW_CAP)
        parts.push([{ detailId: group.detailId, rows: group.rows.slice(start, start + FIELD_RULE_ROW_CAP) }])
    return parts
  }

  async function send(batch: Plan) {
    for (const part of chunks(batch.groups)) {
      if (disposed) return
      const source = options.read()
      const before = new Map<string, string>()
      const remember = (scope: string, values: Record<string, unknown>, ids: string[]) =>
        ids.forEach(id => before.set(stateKey(scope, id), editSignature(values[id])))
      const masterIds = ruledFields(source.options)
      remember(MASTER, source.values, masterIds)
      const details = part.flatMap(group => {
        const detail = source.details.find(d => d.detailId === group.detailId)
        if (!detail) return []
        const ids = ruledFields(detail.options)
        const rows = group.rows.flatMap(planned => {
          const row = detail.rows.find(r => r.rowKey === planned.rowKey)
          if (!row) return []
          const scope = rowScope(detail.detailId, row.rowKey)
          remember(scope, row.values, ids)
          return [
            {
              rowKey: row.rowKey,
              ...(row.detailRecordId ? { detailRecordId: row.detailRecordId } : {}),
              creating: row.creating,
              values: copy(row.values),
              changed: planned.changed,
              overridable: overridable(scope, row.values, ids, detail.options)
            }
          ]
        })
        return rows.length ? [{ detailId: detail.detailId, rows }] : []
      })
      // changed 为空表示全量，只用于新建打开；仅明细变化时带上明细字段 ID，主表不会被当作全量重算。
      const changed = batch.full ? [] : batch.masterChanged.length ? batch.masterChanged : batch.hint
      if (!batch.full && !changed.length) continue
      const results = await options.evaluate({
        creating: source.creating,
        values: copy(source.values),
        changed,
        overridable: overridable(MASTER, source.values, masterIds, source.options),
        ...(details.length ? { details } : {})
      })
      if (disposed) return
      apply(batch.seq, results, before)
    }
  }

  function apply(seq: number, results: FieldRuleResult[], before: Map<string, string>) {
    const source = options.read()
    let masterStates = states.master
    const rowStates = { ...states.rows }
    for (const result of results) {
      const detail = result.detailId ? source.details.find(d => d.detailId === result.detailId) : undefined
      const row = detail && result.rowKey ? detail.rows.find(r => r.rowKey === result.rowKey) : undefined
      if (result.detailId && !row) continue
      const scope = detail && row ? rowScope(detail.detailId, row.rowKey) : MASTER
      const values = row ? row.values : source.values
      const canWrite = detail ? detail.canWrite : source.canWrite
      const readOnly = result.readOnly || ruleReadOnly((detail ? detail.options : source.options)[result.fieldId])
      const key = stateKey(scope, result.fieldId)
      if ((latest.get(key) ?? 0) > seq) continue
      if (detail && row) {
        const current = rowStates[detail.detailId] || {}
        rowStates[detail.detailId] = {
          ...current,
          [row.rowKey]: { ...current[row.rowKey], [result.fieldId]: result }
        }
      } else masterStates = { ...masterStates, [result.fieldId]: result }
      if (result.kind === 'REFERENCE' || result.state === FieldRuleState.NOT_APPLICABLE) continue
      const signature = editSignature(values[result.fieldId])
      const unchanged = signature === (before.get(key) ?? signature)
      const auto = autoValues.get(key)
      let next: { value: unknown } | null = null
      if (result.state === FieldRuleState.APPLIED) {
        const free = emptyFormValue(values[result.fieldId]) || signature === auto
        if (readOnly || (unchanged && free)) next = { value: result.value }
      } else if (readOnly) next = { value: null }
      else if (auto !== undefined && signature === auto && unchanged) next = { value: null }
      if (!next || !canWrite(result.fieldId)) continue
      if (next.value == null) autoValues.delete(key)
      else autoValues.set(key, editSignature(next.value))
      if (editSignature(next.value) !== signature) values[result.fieldId] = next.value
      snapshots.get(scope)?.set(result.fieldId, editSignature(values[result.fieldId]))
    }
    states = { master: masterStates, rows: rowStates }
    emit()
  }

  return {
    /** 挂载后调用一次：新建时全量求值；编辑已有记录只记快照，不求值。 */
    start() {
      if (disposed || started) return
      started = true
      const source = options.read()
      snapshots.set(MASTER, snapshot(source.values))
      for (const detail of source.details)
        for (const row of detail.rows) snapshots.set(rowScope(detail.detailId, row.rowKey), snapshot(row.values))
      if (!source.creating || !hasRules(source)) return
      full = true
      flush()
    },
    sync,
    /** 立即发出防抖中的请求并等待全部在途请求结束；保存前调用，避免带着未算完的联动保存。 */
    async settle() {
      if (timer) flush()
      let current: Promise<void>
      do {
        current = inflight
        await current
      } while (current !== inflight)
    },
    busy: () => !!timer || running > 0,
    states: () => states,
    dispose() {
      disposed = true
      clearTimeout(timer)
      timer = undefined
    }
  }
}
export type FieldRuleCoordinator = ReturnType<typeof createFieldRuleCoordinator>
