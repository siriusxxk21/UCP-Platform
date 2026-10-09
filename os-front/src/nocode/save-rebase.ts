import { v4 as uuidv4 } from 'uuid'
import { RECORD_CONFLICT, RECORD_NOT_FOUND } from '@/api/nocode/runtime'
import type { Aggregate, BusinessRow, SaveRecord } from '@/types/nocode/runtime'
import { editSignature } from './edit-signature'

export { RECORD_CONFLICT, RECORD_NOT_FOUND }

const businessCode = (error: unknown) => (error as { businessCode?: unknown } | null | undefined)?.businessCode
/** 保存或删除被拒是因为记录在这期间被别人改过。 */
export const isRecordConflict = (error: unknown): boolean => businessCode(error) === RECORD_CONFLICT
/** 记录已经不存在（被别人删了，或不再可访问）。 */
export const isRecordMissing = (error: unknown): boolean => businessCode(error) === RECORD_NOT_FOUND

/**
 * 列表删除遇到「记录已被修改」时，取最新修订号再删一次（后操作的生效）。
 * 认为删除应当保守时改为 false，即恢复「一次冲突即失败」。
 */
export const REBASE_DELETE = true

export interface RebaseInput {
  /** 编辑器打开时的记录 */
  base: Aggregate
  /** 刚取回的最新记录 */
  latest: Aggregate
  /** 被拒的那次提交 */
  command: SaveRecord
}
export type RebaseResult =
  | { ok: true; command: SaveRecord; keptTheirs: string[] }
  | { ok: false; reason: 'DETAIL_ROW_DELETED' | 'RELATED_RECORDS'; message: string }

type Values = Record<string, unknown>
/** 空串、null、没有这个键视为相同；对象键序无关。 */
const same = (a: unknown, b: unknown) => editSignature(a) === editSignature(b)
const copy = <T>(value: T): T => (value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T))
/** 没改过的字段跟最新的走；最新记录里根本没有这个键（读不到）时不敢当成被清空，沿用原提交的值。 */
const theirs = (latest: Values, key: string, mine: unknown) =>
  Object.hasOwn(latest, key) ? (copy(latest[key]) ?? null) : copy(mine)
/** 多对多只看是哪些记录，顺序无关。 */
const sameIds = (a: string[], b: string[]) => same([...a].sort(), [...b].sort())

/**
 * 保存被拒是因为记录在这期间被别人改过：以最新内容为底，套上我改过的字段，得到可以再提交的一次保存。
 * 我改过的字段用我的（后保存的生效），我没动的跟最新的走；提交的键集合不变。不修改传入的对象。
 */
export function rebaseSave(input: RebaseInput): RebaseResult {
  const { base, latest, command } = input
  // 带关联区域的联合保存牵涉别的记录的修订号，不自动合并。
  if (Object.keys(command.relatedRecords || {}).length)
    return { ok: false, reason: 'RELATED_RECORDS', message: '记录已被修改，请刷新后重试' }
  const keptTheirs: string[] = []

  const values: Values = {}
  for (const [key, mine] of Object.entries(command.values)) {
    if (!same(mine, base.record.values[key])) values[key] = copy(mine)
    else {
      values[key] = theirs(latest.record.values, key, mine)
      if (!same(latest.record.values[key], base.record.values[key])) keptTheirs.push(key)
    }
  }

  let relations: Record<string, string[]> | undefined
  if (command.relations) {
    relations = {}
    for (const [key, mine] of Object.entries(command.relations)) {
      const before = base.relations?.[key] || [],
        now = latest.relations?.[key] || []
      if (!sameIds(mine, before)) relations[key] = [...mine]
      else {
        relations[key] = [...now]
        if (!sameIds(now, before)) keptTheirs.push(key)
      }
    }
  }

  let details: Record<string, BusinessRow[]> | undefined
  if (command.details) {
    details = {}
    for (const [group, mine] of Object.entries(command.details)) {
      const before = base.details[group] || []
      const rowChanged = (row: BusinessRow, origin?: BusinessRow) =>
        !origin || Object.entries(row.values).some(([key, value]) => !same(value, origin.values[key]))
      // 我没动过这个分组（行序、行、我会写的每个字段都与打开时相同）：不提交它，别人对它的改动原样保留。
      if (
        mine.length === before.length &&
        mine.every((row, index) => row.id === before[index].id && !rowChanged(row, before[index]))
      )
        continue
      const beforeById = new Map(before.map(row => [row.id, row]))
      const nowById = new Map((latest.details[group] || []).map(row => [row.id, row]))
      const rows: BusinessRow[] = []
      // 本表单对已有行会写哪些字段：别人新增的行只带这些，否则服务端会以「包含只读字段」拒绝。
      const writable = new Set<string>()
      for (const row of mine) {
        if (!row.id) {
          rows.push(copy(row))
          continue
        }
        Object.keys(row.values).forEach(key => writable.add(key))
        const origin = beforeById.get(row.id),
          current = nowById.get(row.id)
        if (!current) {
          if (rowChanged(row, origin))
            return {
              ok: false,
              reason: 'DETAIL_ROW_DELETED',
              message: '你修改的明细行已被别人删除，请刷新后重新填写明细'
            }
          continue
        }
        const merged: Values = {}
        for (const [key, value] of Object.entries(row.values))
          merged[key] = origin && same(value, origin.values[key]) ? theirs(current.values, key, value) : copy(value)
        rows.push({ ...copy(row), revision: current.revision, values: merged })
      }
      const submitted = new Set(mine.map(row => row.id))
      for (const row of latest.details[group] || []) {
        // 别人新增的行：打开时没有、我这边也没有。不带上的话，这次保存会把它删掉。
        if (!row.id || beforeById.has(row.id) || submitted.has(row.id)) continue
        const kept: Values = {}
        for (const key of writable) if (Object.hasOwn(row.values, key)) kept[key] = copy(row.values[key]) ?? null
        rows.push({ id: row.id, revision: row.revision, clientRowKey: `row-${row.id}`, values: kept })
      }
      details[group] = rows
    }
  }

  return {
    ok: true,
    keptTheirs,
    command: {
      ...command,
      requestKey: uuidv4(),
      expectedRevision: latest.record.revision,
      values,
      ...(details ? { details } : {}),
      ...(relations ? { relations } : {})
    }
  }
}
