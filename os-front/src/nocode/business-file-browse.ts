import type {
  BusinessFileContentQuery,
  BusinessFileDirectory,
  BusinessFileEntry,
  BusinessFileSpace,
  BusinessRecordLabelStatus
} from '@/types/nocode/business-file'

/**
 * 网盘业务文件浏览的纯逻辑
 *
 * 入口选择、浏览位置推导、面包屑、深链解析与返回业务记录的路由目标都放在这里，
 * 页面只负责渲染与请求；位置身份与服务端内容读取保持同一口径（版本 + 分组键 + 记录/明细身份）。
 */

/** 数据维护入口在选择器与路由中的稳定取值；入口值为空表示尚未选择 */
export const BUSINESS_MAINTENANCE_ENTRY = '__maintenance__'

export type BusinessView = 'browse' | 'favorite' | 'recent'

/** 浏览位置：规则版本单独承载，其余层级由面包屑推导 */
export interface BusinessLocation {
  ruleVersion?: number
  groupKeys: string[]
  recordId?: string
  detailId?: string
  rowId?: string
  fieldId?: string
}

export interface BusinessDeepLink {
  applicationId?: string
  objectId: string
  recordId: string
  detailId?: string
  rowId?: string
  fieldId: string
  entryId: string
}

export interface BusinessCrumb {
  key: string
  label: string
  /** -1 表示回到空间根（规则版本层），其余为面包屑下标 */
  index: number
}

/** 选择器取值到入口身份：数据维护入口不携带 applicationId */
export function businessApplicationId(entry: string | undefined): string | undefined {
  return entry && entry !== BUSINESS_MAINTENANCE_ENTRY ? entry : undefined
}

/** 入口选项：应用入口按已发布应用枚举，数据维护入口需同时具备对象查询与管理权限 */
export function businessEntryOptions(
  applications: Array<{ id: string; name: string }>,
  canMaintenance: boolean
): Array<{ value: string; label: string }> {
  return [
    ...(canMaintenance ? [{ value: BUSINESS_MAINTENANCE_ENTRY, label: '数据维护' }] : []),
    ...applications.map(application => ({ value: application.id, label: application.name }))
  ]
}

export function businessSpaceOptions(spaces: BusinessFileSpace[]): Array<{ value: string; label: string }> {
  return spaces.map(space => ({ value: space.objectId, label: `${space.objectName}｜${space.spaceName}` }))
}

/** 规则版本选项：服务端已按“当前规则/历史规则”标注，未接入规则的对象没有选项 */
export function businessVersionOptions(nodes: BusinessFileDirectory[]): Array<{ value: number; label: string }> {
  return nodes
    .filter(node => node.kind === 'VERSION' && node.ruleVersion != null)
    .map(node => ({ value: node.ruleVersion as number, label: node.label }))
}

/** 位置推导：分组键按层级顺序收集，记录/明细身份取已进入的层级 */
export function businessLocation(ruleVersion: number | undefined, crumbs: BusinessFileDirectory[]): BusinessLocation {
  return {
    ruleVersion,
    groupKeys: crumbs
      .filter(item => item.kind === 'GROUP')
      .map(item => item.groupKey || '')
      .filter(Boolean),
    recordId: crumbs.find(item => item.kind === 'RECORD')?.recordId || undefined,
    detailId: crumbs.find(item => item.kind === 'REGION')?.detailId || undefined,
    rowId: crumbs.find(item => item.kind === 'ROW')?.rowId || undefined,
    fieldId: crumbs.find(item => item.kind === 'FIELD')?.fieldId || undefined
  }
}

/** 定位链拆分：规则版本进入版本选择，其余层级进入面包屑 */
export function splitLocatedPath(path: BusinessFileDirectory[]): {
  ruleVersion?: number
  crumbs: BusinessFileDirectory[]
} {
  return {
    ruleVersion: path.find(item => item.kind === 'VERSION')?.ruleVersion ?? undefined,
    crumbs: path.filter(item => item.kind !== 'VERSION')
  }
}

/** 面包屑：空间与规则版本作为起点，其余层级可逐级回退 */
export function businessCrumbTrail(
  space: Pick<BusinessFileSpace, 'objectName' | 'spaceName'>,
  ruleVersion: number | undefined,
  crumbs: BusinessFileDirectory[]
): BusinessCrumb[] {
  const trail: BusinessCrumb[] = [{ key: 'space', label: space.spaceName || space.objectName, index: -1 }]
  if (ruleVersion != null) trail.push({ key: `version-${ruleVersion}`, label: `规则 v${ruleVersion}`, index: -1 })
  for (const [index, item] of crumbs.entries()) {
    trail.push({ key: businessDirectoryKey(item), label: item.label, index })
  }
  return trail
}

/** 目录行身份：层级类型与各级身份共同构成稳定键 */
export function businessDirectoryKey(item: BusinessFileDirectory): string {
  return [
    item.kind,
    item.ruleVersion ?? '',
    item.groupKey ?? '',
    item.recordId ?? '',
    item.detailId ?? '',
    item.rowId ?? '',
    item.fieldId ?? ''
  ].join(':')
}

export const BUSINESS_DIRECTORY_KINDS: Record<BusinessFileDirectory['kind'], { label: string; color?: string }> = {
  VERSION: { label: '规则版本' },
  GROUP: { label: '业务分组', color: 'blue' },
  RECORD: { label: '业务记录', color: 'geekblue' },
  REGION: { label: '明细区', color: 'purple' },
  ROW: { label: '明细行', color: 'cyan' },
  FIELD: { label: '附件字段', color: 'green' }
}

export const businessMarkType = (view: Exclude<BusinessView, 'browse'>): 'FAVORITE' | 'RECENT' =>
  view === 'favorite' ? 'FAVORITE' : 'RECENT'

/** 深链解析：位置身份缺一不可，缺省 applicationId 表示数据维护入口 */
export function businessDeepLink(query: Record<string, unknown>): BusinessDeepLink | undefined {
  const text = (key: string) => {
    const value = query[key]
    return typeof value === 'string' && value.trim() ? value.trim() : undefined
  }
  const objectId = text('objectId')
  const recordId = text('recordId')
  const fieldId = text('fieldId')
  const entryId = text('entryId')
  if (!objectId || !recordId || !fieldId || !entryId) return undefined
  if (!/^[1-9]\d*$/.test(objectId) || !/^[1-9]\d*$/.test(entryId)) return undefined
  return {
    applicationId: text('applicationId'),
    objectId,
    recordId,
    detailId: text('detailId'),
    rowId: text('rowId'),
    fieldId,
    entryId
  }
}

/** 文件位置身份：与内容读取、收藏登记共用同一口径 */
export function businessFileLocation(
  applicationId: string | undefined,
  objectId: string,
  file: Pick<BusinessFileEntry, 'recordId' | 'detailId' | 'rowId' | 'fieldId' | 'entryId'>
): BusinessFileContentQuery {
  return {
    applicationId,
    objectId,
    recordId: file.recordId || '',
    detailId: file.detailId || undefined,
    rowId: file.rowId || undefined,
    fieldId: file.fieldId || '',
    entryId: file.entryId
  }
}

/** 位置身份完整才可预览/下载/收藏；标记身份缺失的行不提供操作 */
export function businessFileAccessible(file: Pick<BusinessFileEntry, 'recordId' | 'fieldId' | 'entryId'>): boolean {
  return !!file.recordId && !!file.fieldId && !!file.entryId
}

export const businessFavoriteKey = (entryId: string | number) => String(entryId)

export function businessFavoriteKeys(files: BusinessFileEntry[]): Set<string> {
  return new Set(files.map(file => businessFavoriteKey(file.entryId)))
}

/** 返回业务记录：应用入口打开运行时应用，数据维护入口打开对象数据维护页 */
export function businessRecordTarget(
  applicationId: string | undefined,
  objectId: string,
  recordId: string
): { path: string; query: Record<string, string> } {
  if (applicationId) {
    return { path: '/nocode-app/runtime', query: { id: applicationId, objectId, recordId } }
  }
  return { path: '/nocode/object/editor', query: { id: objectId, tab: 'data', recordIds: recordId } }
}

/** 只说明名称状态，不据此禁用有权查看的业务记录或附件。旧响应仍按 restricted 兼容。 */
export function businessLabelNotice(status?: BusinessRecordLabelStatus, restricted?: boolean) {
  const current = status ?? (restricted ? 'RESTRICTED' : 'NORMAL')
  if (current === 'RESTRICTED')
    return { label: '名称不可见', color: 'orange', description: '当前入口没有名称来源字段的查看权限。' }
  if (current === 'INVALID')
    return { label: '名称暂不可用', color: 'default', description: '名称配置已失效，请联系有配置权限的管理员检查。' }
  if (current === 'EMPTY')
    return { label: '未填写标题', color: 'default', description: '业务记录的标题字段尚未填写，可通过记录 ID 区分。' }
  return undefined
}

/** 附件来源仅采用服务端按当前入口裁剪过的字段/明细名称。 */
export function businessFileSource(item: BusinessFileEntry): string {
  return [item.detailLabel, item.rowLabel, item.fieldLabel].filter(Boolean).join(' · ') || '附件'
}
