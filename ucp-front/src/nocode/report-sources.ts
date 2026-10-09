import {
  MAX_REPORT_EXTRA_SOURCES,
  MAX_REPORT_MULTI_SOURCE_METRICS,
  ReportBucket,
  ReportDisplay,
  ReportGrain,
  type ReportConfig,
  type ReportDimension,
  type ReportMetric,
  type ReportResult,
  type ReportSource
} from '@/types/nocode/report'
import type { PublishedDefinition } from '@/types/nocode/application'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType, RelationType } from '@/types/nocode/enums'
import { SelectionKind, type SelectionSource } from '@/types/nocode/selection'
import { selectionSource } from './selection'
import { dateField, reportDetailId, reportDetails, reportFieldOptions, type ReportFieldEntry } from './report'
import {
  metricGrainError,
  multiSourceMetricLimitMessage,
  reportFieldScope,
  reportGrainMessages
} from './report-presentation'

/**
 * 统计视图多个数据来源（契约 laneM P1-CONTRACT）。来源 1 = 顶层配置本身，附加来源 = extraSources。
 * 这里只放判定与文案；界面（ReportConfigEditor / ReportBlock）只调用这些函数。前端判定只用于禁用与提示，以后端校验为准。
 */
export const MAIN_SOURCE_ID = 'main'
type Objects = Record<string, { definition: PublishedDefinition }>
type Readiness = Record<string, Record<string, string>>

/** 来源的只读视图：来源 1 与附加来源同形。 */
export interface ReportSourceView {
  id: string
  /** 显示名；来源 1 没填时为空串（展示时用 sourceLabel）。 */
  name: string
  main: boolean
  objectId: string
  grain: ReportGrain | null
  detailId: string | null
  dimensions: ReportDimension[]
  columnDimensions: ReportDimension[]
  dateFieldId: string | null
  filterTargets: Record<string, string>
  detailViewId: string | null
  detailEditable: boolean
}
export const multiSource = (config?: Pick<ReportConfig, 'extraSources'> | null) => !!config?.extraSources?.length
export function reportSources(config: ReportConfig): ReportSourceView[] {
  const main: ReportSourceView = {
    id: MAIN_SOURCE_ID,
    name: config.sourceName || '',
    main: true,
    objectId: config.objectId,
    grain: config.grain ?? null,
    detailId: config.detailId ?? null,
    dimensions: config.dimensions,
    columnDimensions: config.columnDimensions || [],
    dateFieldId: config.dateFieldId,
    filterTargets: {},
    detailViewId: config.detailViewId ?? null,
    detailEditable: !!config.detailEditable
  }
  return [
    main,
    ...(config.extraSources || []).map((s): ReportSourceView => ({
      id: s.id,
      name: s.name || '',
      main: false,
      objectId: s.objectId,
      grain: s.grain ?? null,
      detailId: s.detailId ?? null,
      dimensions: s.dimensions || [],
      columnDimensions: s.columnDimensions || [],
      dateFieldId: s.dateFieldId ?? null,
      filterTargets: s.filterTargets || {},
      detailViewId: s.detailViewId ?? null,
      detailEditable: !!s.detailEditable
    }))
  ]
}
/** 来源的显示名：填了用填的；来源 1 没填时用对象名，再没有就「来源 1」。 */
export function sourceLabel(source: Pick<ReportSourceView, 'name' | 'main' | 'objectId'>, objects?: Objects) {
  return source.name || objects?.[source.objectId]?.definition.objectName || (source.main ? '来源 1' : '来源')
}
/** 指标所属来源；计算指标不属于任何来源（undefined）。sourceId 指向不存在的来源时也是 undefined。 */
export function metricSource(config: ReportConfig, metric: Pick<ReportMetric, 'operation' | 'sourceId'> | undefined) {
  if (!metric || metric.operation === 'FORMULA') return undefined
  const id = metric.sourceId || MAIN_SOURCE_ID
  return reportSources(config).find(s => s.id === id)
}
/** 键的写法：字段ID 或 关系ID[/关系ID]:字段ID（同后端 validator.key）。 */
export const reportDimensionKey = (d: Pick<ReportDimension, 'fieldId' | 'relationPath'>) =>
  (d.relationPath ? d.relationPath + ':' : '') + d.fieldId
type GrainOf = Pick<ReportConfig, 'grain' | 'detailId'>
const sameGrain = (a: GrainOf, b: GrainOf) => reportDetailId(a) === reportDetailId(b)
/** 契约第 8 章「按规则推得出来」的部分（不看 filterTargets）：按维度推 > 同对象同粒度；推不出返回 undefined。 */
export function inferredFilterKey(config: ReportConfig, source: ReportSourceView, key: string): string | undefined {
  if (source.main) return key
  const rows = config.dimensions.findIndex(d => reportDimensionKey(d) === key)
  if (rows >= 0) return source.dimensions[rows]?.fieldId ? reportDimensionKey(source.dimensions[rows]) : undefined
  const columns = (config.columnDimensions || []).findIndex(d => reportDimensionKey(d) === key)
  if (columns >= 0)
    return source.columnDimensions[columns]?.fieldId ? reportDimensionKey(source.columnDimensions[columns]) : undefined
  if (source.objectId === config.objectId && sameGrain(source, config)) return key
  return undefined
}
/** 契约第 8 章：顶层筛选键 K 映射到来源 S 的键。显式 filterTargets > 按维度推 > 同对象同粒度 > 推不出（undefined）。 */
export function sourceFilterKey(config: ReportConfig, source: ReportSourceView, key: string): string | undefined {
  if (source.main) return key
  return source.filterTargets[key] || inferredFilterKey(config, source, key)
}

// —— 文案（逐字同契约第 6 章；句末不加句号）——
export const reportSourceReasons = {
  R1: '一个是引用其它对象的字段，另一个不是',
  R2: (object1: string, object2: string) => `两者引用的不是同一个对象（「${object1}」与「${object2}」）`,
  R3: '日期按值对齐时两边都须是日期字段（不含时间）；含时间的请按日、按月或按年分组',
  R4: '两者不是同一套选项',
  R5: '小数、金额、百分比字段只能与同一个字段对齐',
  R6: '字段类型不同'
}
export const reportSourceMessages = {
  L1: '多个数据来源目前只用于透视表和汇总表',
  L2: '数据来源最多 4 个',
  L3: '数据来源编码无效或重复',
  L4: '请填写数据来源名称（最多 30 字）',
  L4b: '只有一个数据来源时不用填写来源名称',
  L6: (s: string) => `来源「${s}」要为每个行维度、列维度各指定一个对应字段`,
  L7: (s: string, field: string, dimension: string, bucket: string) =>
    `来源「${s}」的「${field}」要与「${dimension}」用同一种分组方式（${bucket}）`,
  L8: (s: string, field: string, dimension: string, reason: string) =>
    `来源「${s}」的「${field}」不能与「${dimension}」对齐：${reason}`,
  L9: (metric: string) => `指标「${metric}」的数据来源不存在`,
  L10: '计算指标不属于某个来源，不用选择数据来源',
  L11: (s: string) => `来源「${s}」还没有指标，请为它加一个指标或删除这个来源`,
  L12: multiSourceMetricLimitMessage,
  L13: (s: string) => `统计开放了日期范围，请为来源「${s}」指定日期范围字段`,
  L13b: (s: string) => `统计没有开放日期范围，来源「${s}」不用指定日期范围字段`,
  L14: (field: string, s: string) =>
    `用户可筛选字段「${field}」在来源「${s}」里没有对应字段，请在该来源的「筛选对应」里指定，或把它从可筛选字段里去掉`,
  L15: (s: string) => `来源「${s}」的筛选对应用到了没有开放的筛选字段`,
  L16: (s: string, field: string, target: string, reason: string) =>
    `来源「${s}」里与「${field}」对应的「${target}」不能用于同一个筛选：${reason}`,
  L17: '多个来源的统计暂不支持下钻明细视图，下钻会直接显示命中的记录',
  L18: '多个来源的统计暂不能放在记录详情页里',
  L19: '多个来源的统计请点具体指标查看明细',
  L23: '维度显示名应与维度一一对应，每个最多 30 字'
}
/** 附加来源上复用现有文案时加的前缀（契约第 6 章）。 */
export const sourcePrefix = (s: string) => `来源「${s}」：`
export const bucketLabels: Record<string, string> = { VALUE: '按值', DAY: '按日', MONTH: '按月', YEAR: '按年' }

// —— 对齐相容性（契约第 5 章）——
/** 字段的所属：对象（明细字段再加明细）。objects 用来取关系、字段配置与对象名。 */
export interface AlignmentOwner {
  objectId: string
  detailId?: string | null
  objects: Objects
}
export interface AlignmentResult {
  ok: boolean
  /** 不相容时的原因（L8·R1–R6 逐字）；相容时为空串。 */
  reason: string
  category?: 'SAME_FIELD' | 'REFERENCE' | 'DATE_BUCKET' | 'DATE' | 'OPTIONS' | 'VALUE'
}
const definitionOf = (owner: AlignmentOwner) => owner.objects[owner.objectId]?.definition
/** 单值关系（非多对多）：字段所在明细与关系的 sourceDetailId 一致。 */
function singleRelation(field: ObjectField, owner: AlignmentOwner) {
  const relation = (definitionOf(owner)?.relations || []).find(
    r => r.fieldId === field.id && (r.sourceDetailId || null) === (owner.detailId || null)
  )
  return relation && relation.kind !== RelationType.MANY_TO_MANY ? relation : undefined
}
function fieldOptionsOf(field: ObjectField, owner: AlignmentOwner) {
  const definition = definitionOf(owner)
  const holder = owner.detailId ? definition?.details?.find(d => d.id === owner.detailId) : definition
  return holder?.fieldOptions?.[field.id!]
}
const MULTIPLE = [FieldType.MULTI_SELECT, FieldType.REGION, FieldType.CASCADE] as string[]
/** 同后端 SelectionFields.identity。 */
function selectionIdentity(field: ObjectField, source: SelectionSource) {
  const identity = [
    source.kind,
    source.directory ?? '',
    source.dictionaryType ?? '',
    MULTIPLE.includes(field.type)
  ].join(':')
  return source.sourceObjectId == null && source.sourceFieldId == null
    ? identity
    : identity + ':' + (source.sourceObjectId ?? '') + ':' + (source.sourceFieldId ?? '')
}
/** 同后端 SelectionCompatibility.sameLocalOptions：编码 → 标签逐项一致（顺序、停用不计）。 */
function sameLocalOptions(
  a: Array<{ code: string; label: string }> = [],
  b: Array<{ code: string; label: string }> = []
) {
  const map = (list: Array<{ code: string; label: string }>) => new Map(list.map(o => [o.code, o.label]))
  const left = map(a),
    right = map(b)
  return left.size === right.size && [...left].every(([code, label]) => right.get(code) === label)
}
const DATE_TYPES = [FieldType.DATE, FieldType.DATETIME] as string[]
const PLAIN_TYPES = [FieldType.TEXT, FieldType.AUTO_NUMBER, FieldType.INTEGER, FieldType.BOOLEAN] as string[]
const DECIMAL_TYPES = [FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT] as string[]
/**
 * 来源 S 的字段 fieldS 能否与来源 1 同位维度的字段 field1 对齐（同一种分桶 bucket；分桶不同另报 L7）。
 * 复刻后端 ApplicationReportValidator.alignment 的判定次序：同一字段 → 引用同一对象 → 日期 → 同一套选项 → 同类原值 → 其它。
 */
export function alignment(
  field1: ObjectField,
  owner1: AlignmentOwner,
  fieldS: ObjectField,
  ownerS: AlignmentOwner,
  bucket: string
): AlignmentResult {
  const ok = (category: AlignmentResult['category']): AlignmentResult => ({ ok: true, reason: '', category })
  const no = (reason: string): AlignmentResult => ({ ok: false, reason })
  if (
    owner1.objectId === ownerS.objectId &&
    (owner1.detailId || null) === (ownerS.detailId || null) &&
    field1.id === fieldS.id
  )
    return ok('SAME_FIELD')
  const r1 = singleRelation(field1, owner1),
    rS = singleRelation(fieldS, ownerS)
  if (!!r1 !== !!rS) return no(reportSourceReasons.R1)
  if (r1 && rS) {
    if (r1.targetObjectId !== rS.targetObjectId) {
      const name = (id: string) =>
        owner1.objects[id]?.definition.objectName || ownerS.objects[id]?.definition.objectName || id
      return no(reportSourceReasons.R2(name(r1.targetObjectId), name(rS.targetObjectId)))
    }
    if (bucket === ReportBucket.VALUE) return ok('REFERENCE')
  }
  if (DATE_TYPES.includes(field1.type) && DATE_TYPES.includes(fieldS.type)) {
    if (bucket !== ReportBucket.VALUE) return ok('DATE_BUCKET')
    if (field1.type === FieldType.DATE && fieldS.type === FieldType.DATE) return ok('DATE')
    return no(reportSourceReasons.R3)
  }
  const s1 = selectionSource(field1, fieldOptionsOf(field1, owner1)),
    sS = selectionSource(fieldS, fieldOptionsOf(fieldS, ownerS))
  const options1 = s1 && s1.kind !== SelectionKind.OBJECT_RELATION ? s1 : null,
    optionsS = sS && sS.kind !== SelectionKind.OBJECT_RELATION ? sS : null
  if (options1 || optionsS) {
    if (
      options1 &&
      optionsS &&
      selectionIdentity(field1, options1) === selectionIdentity(fieldS, optionsS) &&
      (options1.kind !== SelectionKind.LOCAL_OPTIONS ||
        optionsS.kind !== SelectionKind.LOCAL_OPTIONS ||
        sameLocalOptions(fieldOptionsOf(field1, owner1)?.options, fieldOptionsOf(fieldS, ownerS)?.options))
    )
      return ok('OPTIONS')
    return no(reportSourceReasons.R4)
  }
  if (field1.type === fieldS.type && PLAIN_TYPES.includes(field1.type)) return ok('VALUE')
  if (DECIMAL_TYPES.includes(field1.type) || DECIMAL_TYPES.includes(fieldS.type)) return no(reportSourceReasons.R5)
  return no(reportSourceReasons.R6)
}

// —— 字段条目与名称 ——
export const sourceFieldOptions = (
  source: Pick<ReportSourceView, 'objectId' | 'grain' | 'detailId'>,
  objects: Objects,
  readiness?: Readiness
) => reportFieldOptions(source.objectId, objects, readiness, reportDetailId(source))
/** 契约第 6 章 {字段} / {维度名} 的写法：直接字段取字段名，关系路径取「关系名 / 字段名」。 */
export const entryName = (entry: ReportFieldEntry) => (entry.detailId ? entry.field.name : entry.label)
const ownerOf = (entry: ReportFieldEntry, objects: Objects): AlignmentOwner => ({
  objectId: entry.objectId,
  detailId: entry.detailId,
  objects
})
/** 下拉的一项：不相容的禁用，title 是原因（L8 原因逐字）。 */
export interface SourceFieldChoice {
  value: string
  label: string
  disabled?: boolean
  title?: string
}
export function alignedChoices(
  target: ReportFieldEntry | undefined,
  entries: ReportFieldEntry[],
  objects: Objects,
  bucket: string
): SourceFieldChoice[] {
  return entries.map(entry => {
    if (!target) return { value: entry.value, label: entry.label }
    const result = alignment(target.field, ownerOf(target, objects), entry.field, ownerOf(entry, objects), bucket)
    return result.ok
      ? { value: entry.value, label: entry.label }
      : { value: entry.value, label: entry.label, disabled: true, title: result.reason }
  })
}

// —— 新来源的默认值 ——
/** 下一个来源编码：s2、s3…（不与已有重复）。 */
export function nextSourceId(config: Pick<ReportConfig, 'extraSources'>) {
  const used = new Set((config.extraSources || []).map(s => s.id))
  let n = 2
  while (used.has('s' + n)) n++
  return 's' + n
}
const emptySlot = (d: ReportDimension): ReportDimension => ({ fieldId: '', relationPath: null, bucket: d.bucket })
/** 开放了日期范围时的默认日期字段：取本来源对齐到日期维度（先列后行）的那个字段，须是主表日期字段。 */
export function defaultSourceDateField(
  config: ReportConfig,
  source: Pick<ReportSource, 'objectId' | 'dimensions' | 'columnDimensions'>,
  objects: Objects
): string | null {
  if (!config.dateFieldId) return null
  const fields = objects[source.objectId]?.definition.fields || []
  const pairs = [
    ...(config.columnDimensions || []).map((d, i) => [d, source.columnDimensions?.[i]] as const),
    ...config.dimensions.map((d, i) => [d, source.dimensions[i]] as const)
  ]
  for (const [, mine] of pairs) {
    if (!mine?.fieldId || mine.relationPath) continue
    const field = fields.find(f => f.id === mine.fieldId)
    if (field && dateField(field)) return field.id!
  }
  return null
}
/** 新来源：同对象时粒度、维度逐位抄来源 1；不同对象时维度留空（分桶跟来源 1）。 */
export function defaultSource(config: ReportConfig, objectId: string, objects: Objects): ReportSource {
  const same = objectId === config.objectId
  const copy = (list: ReportDimension[] = []) => list.map(d => (same ? { ...d } : emptySlot(d)))
  const source: ReportSource = {
    id: nextSourceId(config),
    name: '',
    objectId,
    dimensions: copy(config.dimensions),
    columnDimensions: config.columnDimensions?.length ? copy(config.columnDimensions) : null
  }
  if (same && reportDetailId(config)) {
    source.grain = ReportGrain.DETAIL
    source.detailId = config.detailId
  }
  const date = defaultSourceDateField(config, source, objects)
  if (date) source.dateFieldId = date
  return source
}
/** 换对象时的附加来源：粒度回到主记录、维度留空、条件 / 日期 / 筛选对应 / 下钻视图清空。 */
export function resetSourceObject(config: ReportConfig, source: ReportSource, objectId: string): ReportSource {
  return {
    id: source.id,
    name: source.name,
    objectId,
    dimensions: config.dimensions.map(emptySlot),
    columnDimensions: config.columnDimensions?.length ? config.columnDimensions.map(emptySlot) : null
  }
}
/**
 * 来源 1 的行 / 列维度增删后，附加来源的维度对应与维度显示名逐位跟着增删：
 * previous / next 是同一份维度对象的前后两个列表（按对象引用对应；新增的位留空，分桶跟来源 1）。
 */
export function alignSlots<T>(
  previous: readonly ReportDimension[],
  next: readonly ReportDimension[],
  slots: T[] | null | undefined,
  empty: (d: ReportDimension) => T
): T[] {
  const old = slots || []
  if (previous.length === next.length) return next.map((d, i) => (i < old.length ? old[i] : empty(d)))
  return next.map(d => {
    const index = previous.indexOf(d)
    return index >= 0 && index < old.length ? old[index] : empty(d)
  })
}

// —— 规范化与校验 ——
/**
 * 保存前整理（prepareReportConfig 调用）：
 * 单来源 ⇒ 去掉 sourceName / extraSources（与存量逐键相同）、指标去掉 sourceId；显示名全空 ⇒ 去掉。
 * 多来源 ⇒ 来源的分桶跟来源 1、列维度为空时为 null、推得出来的 / 没开放的筛选对应不存、ROOT 粒度归一为不带键。
 */
export function normalizeReportSources(config: ReportConfig): ReportConfig {
  const result: ReportConfig = { ...config }
  const labels = (list: string[] | null | undefined, length: number) =>
    list && list.some(v => v?.trim()) ? Array.from({ length }, (_, i) => list[i]?.trim() || '') : undefined
  const rows = labels(config.dimensionLabels, config.dimensions.length)
  const columns = labels(config.columnDimensionLabels, (config.columnDimensions || []).length)
  if (rows) result.dimensionLabels = rows
  else delete result.dimensionLabels
  if (columns) result.columnDimensionLabels = columns
  else delete result.columnDimensionLabels
  if (!multiSource(config)) {
    delete result.sourceName
    delete result.extraSources
    result.metrics = config.metrics.map(m => {
      if (!('sourceId' in m)) return m
      const { sourceId: _, ...rest } = m
      return rest
    })
    return result
  }
  result.sourceName = config.sourceName?.trim() || ''
  result.metrics = config.metrics.map(m => {
    const { sourceId, ...rest } = m
    return sourceId && sourceId !== MAIN_SOURCE_ID && m.operation !== 'FORMULA' ? { ...rest, sourceId } : rest
  })
  const view = (s: ReportSource) => reportSources({ ...config, extraSources: [s] })[1]
  result.extraSources = config.extraSources!.map(s => {
    const columnsOf = config.columnDimensions?.length
      ? config.columnDimensions.map((d, i) => ({ ...(s.columnDimensions?.[i] || emptySlot(d)), bucket: d.bucket }))
      : null
    const next: ReportSource = {
      id: s.id,
      name: s.name?.trim() || '',
      objectId: s.objectId,
      dimensions: config.dimensions.map((d, i) => ({ ...(s.dimensions[i] || emptySlot(d)), bucket: d.bucket })),
      columnDimensions: columnsOf
    }
    if (s.grain === ReportGrain.DETAIL) {
      next.grain = ReportGrain.DETAIL
      next.detailId = s.detailId || null
    }
    if (s.conditions) next.conditions = s.conditions
    if (s.dateFieldId) next.dateFieldId = s.dateFieldId
    const targets = Object.fromEntries(
      Object.entries(s.filterTargets || {}).filter(
        ([key, value]) => !!value && config.filterFieldIds.includes(key) && !inferredFilterKey(config, view(next), key)
      )
    )
    if (Object.keys(targets).length) next.filterTargets = targets
    // 各来源各自的下钻明细视图（业务方 2026-10-04）；明细粒度的来源也可以挂（R6 衔接，与来源 1 同口径：视图须按同一明细逐行显示，
    // 配置器只列出这样的视图，保存时后端再按资源判一次）。契约变更 C1：detailEditable 只存 true 或 null。
    if (s.detailViewId) {
      next.detailViewId = s.detailViewId
      if (s.detailEditable) next.detailEditable = true
    }
    return next
  })
  return result
}
const nameOk = (name: string | null | undefined) => !!name?.trim() && name.trim().length <= 30
/** 维度显示名（契约 L23 的 {维度名}）：显示名为空时取来源 1 该维度的字段名。 */
export function dimensionName(
  config: ReportConfig,
  column: boolean,
  index: number,
  objects: Objects,
  readiness?: Readiness
) {
  const label = (column ? config.columnDimensionLabels : config.dimensionLabels)?.[index]?.trim()
  if (label) return label
  const d = (column ? config.columnDimensions || [] : config.dimensions)[index]
  const entry =
    d && sourceFieldOptions(reportSources(config)[0], objects, readiness).find(e => e.value === reportDimensionKey(d))
  return entry ? entryName(entry) : d?.fieldId || ''
}
/**
 * 多来源的配置校验（保存、预览共用；后端独立校验全部规则）：返回第一条问题的整句，没有问题返回 null。
 * 单来源只查 L4b 与 L23（其余规则与现在相同，在 validateReport 里）。
 */
export function reportSourcesError(config: ReportConfig, objects: Objects, readiness?: Readiness): string | null {
  const labelsBad = (list: string[] | null | undefined, length: number) =>
    !!list && (list.length !== length || list.some(v => (v || '').length > 30))
  if (
    labelsBad(config.dimensionLabels, config.dimensions.length) ||
    labelsBad(config.columnDimensionLabels, (config.columnDimensions || []).length)
  )
    return reportSourceMessages.L23
  if (!multiSource(config)) return config.sourceName ? reportSourceMessages.L4b : null
  if (config.display !== ReportDisplay.PIVOT && config.display !== ReportDisplay.TABLE) return reportSourceMessages.L1
  const extras = config.extraSources!
  if (extras.length > MAX_REPORT_EXTRA_SOURCES) return reportSourceMessages.L2
  const ids = extras.map(s => s.id)
  if (ids.some(id => !/^[a-z][a-z0-9_]{0,19}$/.test(id) || id === MAIN_SOURCE_ID) || new Set(ids).size !== ids.length)
    return reportSourceMessages.L3
  if (!nameOk(config.sourceName) || extras.some(s => !nameOk(s.name))) return reportSourceMessages.L4
  if (config.metrics.length > MAX_REPORT_MULTI_SOURCE_METRICS) return reportSourceMessages.L12
  for (const m of config.metrics) {
    if (m.operation === 'FORMULA') {
      if (m.sourceId) return reportSourceMessages.L10
    } else if (m.sourceId && m.sourceId !== MAIN_SOURCE_ID && !ids.includes(m.sourceId))
      return reportSourceMessages.L9(m.name)
  }
  const sources = reportSources(config)
  const main = sources[0]
  const mainEntries = sourceFieldOptions(main, objects, readiness)
  for (const source of sources.slice(1)) {
    const s = source.name.trim()
    if (!objects[source.objectId]) return sourcePrefix(s) + '统计对象未被应用引用'
    if (source.grain === ReportGrain.DETAIL) {
      const enabled = reportDetails(objects[source.objectId].definition)
      if (!source.detailId) return sourcePrefix(s) + reportGrainMessages.M11b
      if (!enabled.some(d => d.id === source.detailId)) return sourcePrefix(s) + '统计所选的内部明细不存在'
    }
    const entries = sourceFieldOptions(source, objects, readiness)
    const sides: Array<[ReportDimension[], ReportDimension[], boolean]> = [
      [config.dimensions, source.dimensions, false],
      [config.columnDimensions || [], source.columnDimensions, true]
    ]
    for (const [mine, theirs, column] of sides) {
      if (mine.length !== theirs.length || theirs.some(d => !d?.fieldId)) return reportSourceMessages.L6(s)
      for (let i = 0; i < mine.length; i++) {
        const target = mainEntries.find(e => e.value === reportDimensionKey(mine[i]))
        const entry = entries.find(e => e.value === reportDimensionKey(theirs[i]))
        const dimension = dimensionName(config, column, i, objects, readiness)
        if (!entry) return reportSourceMessages.L6(s)
        if (theirs[i].bucket !== mine[i].bucket)
          return reportSourceMessages.L7(s, entryName(entry), dimension, bucketLabels[mine[i].bucket] || mine[i].bucket)
        if (!target) continue
        const result = alignment(
          target.field,
          ownerOf(target, objects),
          entry.field,
          ownerOf(entry, objects),
          mine[i].bucket
        )
        if (!result.ok) return reportSourceMessages.L8(s, entryName(entry), dimension, result.reason)
      }
    }
    // 契约变更 C2 的预留一行（按明细行统计的来源不能挂下钻视图）已随 laneDV 放开（R6 衔接）：视图形状由配置器与后端保存校验判。
    const own = config.metrics.filter(m => m.operation !== 'FORMULA' && m.sourceId === source.id)
    if (!own.length) return reportSourceMessages.L11(s)
    const scope = reportFieldScope(source, objects[source.objectId]?.definition)
    for (const m of own) {
      const error = metricGrainError(m, source, scope)
      if (error) return sourcePrefix(s) + error
    }
    if (config.dateFieldId && !source.dateFieldId) return reportSourceMessages.L13(s)
    if (!config.dateFieldId && source.dateFieldId) return reportSourceMessages.L13b(s)
    for (const [key, value] of Object.entries(source.filterTargets)) {
      if (!config.filterFieldIds.includes(key)) return reportSourceMessages.L15(s)
      const target = mainEntries.find(e => e.value === key)
      const entry = entries.find(e => e.value === value)
      if (target && entry) {
        const result = alignment(target.field, ownerOf(target, objects), entry.field, ownerOf(entry, objects), 'VALUE')
        if (!result.ok) return reportSourceMessages.L16(s, entryName(target), entryName(entry), result.reason)
      }
    }
    for (const key of config.filterFieldIds)
      if (!sourceFilterKey(config, source, key)) {
        const target = mainEntries.find(e => e.value === key)
        return reportSourceMessages.L14(target ? entryName(target) : key, s)
      }
  }
  return null
}
export function validateReportSources(config: ReportConfig, objects: Objects, readiness?: Readiness) {
  const error = reportSourcesError(config, objects, readiness)
  if (error) throw new Error(error)
}

// —— 页脚 ——
/** 契约第 12 章页脚：`当月 5 条 · 次月 5 条 · 时区 …`；明细粒度来源写 `借方 5 行（明细「分录」）`。单来源返回 null。 */
export function reportSourcesFooter(result: Pick<ReportResult, 'sources' | 'timeZone'>): string | null {
  if (!result.sources?.length) return null
  const parts = result.sources.map(s =>
    s.detailName ? `${s.name} ${s.recordCount} 行（明细「${s.detailName}」）` : `${s.name} ${s.recordCount} 条`
  )
  return [...parts, '时区 ' + result.timeZone].join(' · ')
}

// —— 来源 1 换对象 ——
/**
 * 来源 1 换数据对象（ResourceManager.objectChanged 把配置重置为 defaultReport）：附加来源不动，
 * 但来源 1 的维度清空了，附加来源的维度对应、筛选对应一并清空；展示方式、来源名称、附加来源的指标保留，计算指标去掉。
 */
export function carryReportSources(previous: ReportConfig, next: ReportConfig): ReportConfig {
  if (!multiSource(previous)) return next
  const kept = previous.metrics.filter(m => m.operation !== 'FORMULA' && m.sourceId && m.sourceId !== MAIN_SOURCE_ID)
  const used = new Set(kept.map(m => m.id))
  return {
    ...next,
    display: previous.display,
    columnDimensions: [],
    pivot: previous.display === ReportDisplay.PIVOT ? previous.pivot || null : null,
    detailViewId: null,
    detailEditable: null,
    sourceName: previous.sourceName ?? '',
    metrics: [...next.metrics.map(m => (used.has(m.id) ? { ...m, id: m.id + '_main' } : m)), ...kept],
    extraSources: previous.extraSources!.map(s => {
      const { filterTargets: _, ...rest } = s
      return { ...rest, dimensions: [], columnDimensions: null }
    })
  }
}
/** 换对象后给编辑器的提示。 */
export const carriedSourcesNotice =
  '来源 1 换了数据对象，行维度、列维度已清空；附加来源的维度对应、筛选对应也一并清空，请重新指定'
/** 有附加来源、展示方式却不是透视表 / 汇总表（L1）：编辑器显示 L1，「应用到草稿」禁用。 */
export const reportSourcesBlocked = (config: Pick<ReportConfig, 'extraSources' | 'display'>) =>
  multiSource(config) && config.display !== ReportDisplay.PIVOT && config.display !== ReportDisplay.TABLE
/**
 * 页面公共筛选绑定到多来源统计时（PageFilterConfig）：这个键在某个附加来源里映射不到 ⇒ 返回 L14 整句（日期范围：某来源没有日期范围字段 ⇒ L13）。
 * 单来源、或每个来源都能映射时返回 null。label = 来源 1 里该字段的名称。
 */
export function sourceFilterProblem(
  config: ReportConfig,
  key: string,
  dateRange: boolean,
  label: string
): string | null {
  if (!multiSource(config)) return null
  for (const source of reportSources(config).slice(1)) {
    if (dateRange ? !source.dateFieldId : !sourceFilterKey(config, source, key))
      return dateRange ? reportSourceMessages.L13(source.name) : reportSourceMessages.L14(label, source.name)
  }
  return null
}
