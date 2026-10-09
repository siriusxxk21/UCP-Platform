import type {
  LinkageBackfillPage,
  LinkageBackfillRequest,
  LinkagePreviewPage,
  LinkagePreviewRequest,
  LinkageSyncFailure,
  LinkageSyncField,
  LinkageSyncOverview
} from '@/types/nocode/linkage-sync'
import { errorMessage } from './data-center'

/**
 * 数据联动「来源变化时自动更新」的预告与回填循环（第一期契约 6.2、6.3、9.3）。
 * 服务端不记游标、不记「是否已回填」：翻页、累加、停止、续跑全在这里，界面层只调用这两个函数，不另写循环。
 */
export const PREVIEW_PAGE_SIZE = 200
/**
 * 回填每条记录是一次完整保存（实测每条约 0.2–0.3 秒）：一页 100 条要二三十秒，期间进度不动、「停止」也要等这一页跑完。
 * 压到 20 条一页（几秒一页）：进度看得见，停止来得及；总耗时不变。
 */
export const BACKFILL_PAGE_SIZE = 20
/** 失败明细至多保留这么多条；条数（failedCount）照实累加。 */
export const FAILURE_KEEP = 50
/** 后端 NocodeErrorCodes.CONFLICT：回填时签名与当前发布版登记的不一致（预告之后规则又变了）。 */
export const LINKAGE_CONFLICT_CODE = 1050000004

export class LinkageRuleChangedError extends Error {
  constructor(message = '规则已变化，请重新检查') {
    super(message)
    this.name = 'LinkageRuleChangedError'
  }
}
const businessCode = (error: unknown) => (error as { businessCode?: unknown } | null)?.businessCode
export const isRuleChanged = (error: unknown): boolean =>
  error instanceof LinkageRuleChangedError || businessCode(error) === LINKAGE_CONFLICT_CODE
/** 这三个接口只对应用的创建者或平台管理员开放；别人调用得到 403（「只能管理自己创建的应用」）。 */
export const isLinkageForbidden = (error: unknown): boolean => businessCode(error) === 403

/**
 * running：还在翻页（只出现在进度回调里）；done：翻到了最后一页；stopped：被调用方中止；
 * failed：某一页失败后停下。只有 done 的累计才是最终结果。
 */
export type LoopStatus = 'running' | 'done' | 'stopped' | 'failed'
interface LoopState {
  status: LoopStatus
  /** 下一次该从哪一页开始：done 为 null；stopped / failed 为还没完成的那一页的游标。 */
  cursor: string | null
  /** failed 时的原因。 */
  error?: unknown
}
export interface PreviewTotals {
  signature: string
  /** 目标记录总数（第一页给出）。 */
  total: number | null
  scanned: number
  unchanged: number
  willFill: number
  willClear: number
  willChange: number
  failedCount: number
  failed: LinkageSyncFailure[]
}
export type PreviewRun = PreviewTotals & LoopState
export interface BackfillTotals {
  scanned: number
  updated: number
  unchanged: number
  failedCount: number
  failed: LinkageSyncFailure[]
}
export type BackfillRun = BackfillTotals & LoopState
/** 「将更新 N 条」：由空变为有值 + 清空 + 值变化。 */
export const pendingCount = (totals: Pick<PreviewTotals, 'willFill' | 'willClear' | 'willChange'>) =>
  totals.willFill + totals.willClear + totals.willChange

/** 预告结果的一句话：发布对话框与「自动更新」面板共用同一句。 */
export const previewSummary = (totals: PreviewTotals) =>
  `将更新 ${pendingCount(totals)} 条（由空变为有值 ${totals.willFill} · 清空 ${totals.willClear} · 值变化 ${totals.willChange} · 无法求值 ${totals.failedCount}）`
/** 「对象名 · 字段名」。 */
export const linkageFieldLabel = (field: Pick<LinkageSyncField, 'targetObjectName' | 'targetFieldName'>) =>
  `${field.targetObjectName} · ${field.targetFieldName}`
export const linkageFieldKey = (field: Pick<LinkageSyncField, 'targetObjectId' | 'targetFieldId'>) =>
  `${field.targetObjectId}:${field.targetFieldId}`
/** 相对比较基准新开或规则变了的字段：这些字段的存量记录需要预告、回填。 */
export const changedLinkageFields = (overview?: Pick<LinkageSyncOverview, 'fields'> | null) =>
  (overview?.fields ?? []).filter(field => field.change === 'NEW' || field.change === 'CHANGED')
/** 循环没有走到最后一页的原因，给界面显示。 */
export function loopProblem(run: Pick<PreviewRun, 'status' | 'error'>): string {
  if (run.status === 'failed') return errorMessage(run.error)
  return run.status === 'stopped' ? '已停止' : ''
}

interface LoopOptions<Run> {
  /** 每完成一页回调一次；最后一次的 status 为 done。 */
  onProgress?: (run: Run) => void
  /** 中止后不再请求下一页；已经发出的那一页照常计入。 */
  signal?: AbortSignal
  limit?: number
}
const keep = (known: LinkageSyncFailure[], more: LinkageSyncFailure[] | null | undefined) =>
  [...known, ...(more ?? [])].slice(0, FAILURE_KEEP)

/** 取一页：服务端明确拒绝（带业务码）不重试；网络之类的未知失败自动重试一次，仍失败就把错误交回，由调用处停在原游标。 */
async function page<T>(load: () => Promise<T>): Promise<T> {
  try {
    return await load()
  } catch (first) {
    if (isRuleChanged(first)) throw new LinkageRuleChangedError()
    if (typeof businessCode(first) === 'number') throw first
  }
  try {
    return await load()
  } catch (second) {
    if (isRuleChanged(second)) throw new LinkageRuleChangedError()
    throw second
  }
}
const stalled = () => new Error('服务端没有返回下一页的位置，已停止')

/** 预告：从第一页翻到 done，累加各页；不写任何数据。翻页途中规则签名变了即抛 LinkageRuleChangedError。 */
export async function runPreview(
  api: { linkagePreview: (body: LinkagePreviewRequest) => Promise<LinkagePreviewPage> },
  request: Omit<LinkagePreviewRequest, 'cursor' | 'limit'>,
  options: LoopOptions<PreviewRun> = {}
): Promise<PreviewRun> {
  const limit = options.limit ?? PREVIEW_PAGE_SIZE
  let run: PreviewRun = {
    status: 'running',
    cursor: null,
    signature: '',
    total: null,
    scanned: 0,
    unchanged: 0,
    willFill: 0,
    willClear: 0,
    willChange: 0,
    failedCount: 0,
    failed: []
  }
  for (let first = true; ; first = false) {
    if (options.signal?.aborted) return { ...run, status: 'stopped' }
    const cursor = run.cursor
    let result: LinkagePreviewPage
    try {
      result = await page(() =>
        api.linkagePreview({
          applicationId: request.applicationId,
          basis: request.basis,
          targetObjectId: request.targetObjectId,
          targetFieldId: request.targetFieldId,
          cursor,
          limit
        })
      )
    } catch (error) {
      if (error instanceof LinkageRuleChangedError) throw error
      return { ...run, status: 'failed', error }
    }
    if (!first && result.signature !== run.signature) throw new LinkageRuleChangedError()
    const next: PreviewRun = {
      status: result.done ? 'done' : 'running',
      cursor: result.done ? null : result.nextCursor,
      signature: result.signature,
      total: first ? result.total : run.total,
      scanned: run.scanned + result.scanned,
      unchanged: run.unchanged + result.unchanged,
      willFill: run.willFill + result.willFill,
      willClear: run.willClear + result.willClear,
      willChange: run.willChange + result.willChange,
      failedCount: run.failedCount + result.failedCount,
      failed: keep(run.failed, result.failed)
    }
    // 没到最后一页却没有（或没有前进的）游标：停下来报错，不重复请求同一页。
    if (!result.done && (!result.nextCursor || result.nextCursor === cursor))
      return { ...next, status: 'failed', cursor, error: stalled() }
    run = next
    options.onProgress?.(run)
    if (result.done) return run
  }
}

/**
 * 回填：每页带上预告返回的签名；可从给定游标续跑并接着已有累计往上加。
 * 收到「规则已变化」抛 LinkageRuleChangedError；某一页失败则停在这一页的游标，不跳页。
 */
export async function runBackfill(
  api: { linkageBackfill: (body: LinkageBackfillRequest) => Promise<LinkageBackfillPage> },
  request: Omit<LinkageBackfillRequest, 'cursor' | 'limit'>,
  options: LoopOptions<BackfillRun> & { cursor?: string | null; initial?: BackfillTotals | null } = {}
): Promise<BackfillRun> {
  const limit = options.limit ?? BACKFILL_PAGE_SIZE
  const initial = options.initial
  let run: BackfillRun = {
    status: 'running',
    cursor: options.cursor ?? null,
    scanned: initial?.scanned ?? 0,
    updated: initial?.updated ?? 0,
    unchanged: initial?.unchanged ?? 0,
    failedCount: initial?.failedCount ?? 0,
    failed: keep([], initial?.failed)
  }
  for (;;) {
    if (options.signal?.aborted) return { ...run, status: 'stopped' }
    const cursor = run.cursor
    let result: LinkageBackfillPage
    try {
      result = await page(() =>
        api.linkageBackfill({
          applicationId: request.applicationId,
          targetObjectId: request.targetObjectId,
          targetFieldId: request.targetFieldId,
          signature: request.signature,
          cursor,
          limit
        })
      )
    } catch (error) {
      if (error instanceof LinkageRuleChangedError) throw error
      return { ...run, status: 'failed', error }
    }
    const next: BackfillRun = {
      status: result.done ? 'done' : 'running',
      cursor: result.done ? null : result.nextCursor,
      scanned: run.scanned + result.scanned,
      updated: run.updated + result.updated,
      unchanged: run.unchanged + result.unchanged,
      failedCount: run.failedCount + result.failedCount,
      failed: keep(run.failed, result.failed)
    }
    if (!result.done && (!result.nextCursor || result.nextCursor === cursor))
      return { ...next, status: 'failed', cursor: result.nextCursor ?? cursor, error: stalled() }
    run = next
    options.onProgress?.(run)
    if (result.done) return run
  }
}
