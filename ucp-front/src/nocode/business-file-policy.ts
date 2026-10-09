import type { BusinessFilePolicy, ObjectDetail, SaveDesign } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType, MemberState } from '@/types/nocode/enums'

/** 与服务端 BusinessFilePolicies 对齐的边界；设计器提示与保存前拦截共用。 */
const MAX_SPACE_NAME = 64
const MAX_FIXED_LEVELS = 5
const MAX_GROUPS = 2
const MAX_LABEL_FIELDS = 5
const MAX_PARTICIPATING = 20
const MAX_NAME_LENGTH = 100

/** 业务分组允许的主表字段类型：文本、单选、单值关联与日期。 */
const GROUP_TYPES = new Set<string>([
  FieldType.TEXT,
  FieldType.SELECT,
  FieldType.REFERENCE,
  FieldType.DATE,
  FieldType.DATETIME
])
/** 记录目录名称允许的类型：可稳定展示的标量字段，排除文件、集合、富文本与实时计算。 */
const LABEL_TYPES = new Set<string>([
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.INTEGER,
  FieldType.DECIMAL,
  FieldType.MONEY,
  FieldType.PERCENT,
  FieldType.BOOLEAN,
  FieldType.DATE,
  FieldType.DATETIME,
  FieldType.TIME,
  FieldType.SELECT,
  FieldType.URL,
  FieldType.AUTO_NUMBER,
  FieldType.UUID,
  FieldType.REFERENCE
])

export const fieldKey = (field: ObjectField) => field.id || field.key
export const businessDateField = (field: ObjectField | null | undefined): boolean =>
  !!field && (field.type === FieldType.DATE || field.type === FieldType.DATETIME)
const attachField = (field: ObjectField) => field.type === FieldType.ATTACHMENT || field.type === FieldType.IMAGE

/** 运行期兼容历史 name-only 规则；新设计保存由 businessFileIssues 强制选择稳定空间编号。 */
export function businessFileEnabled(policy: BusinessFilePolicy | null | undefined): boolean {
  return !!policy?.spaceName?.trim() && (policy.fieldIds?.length ?? 0) > 0
}

const activeMainFields = (design: SaveDesign): ObjectField[] =>
  design.draft.fields.filter(f => design.fieldOptions[f.key]?.state !== MemberState.INACTIVE)

const activeDetailFields = (detail: ObjectDetail): ObjectField[] =>
  detail.state === MemberState.ACTIVE
    ? detail.fields.filter(f => detail.fieldOptions[f.key]?.state !== MemberState.INACTIVE)
    : []

/** 分组候选：主表有效字段，限文本、单选、日期与单值关联。 */
export const businessGroupFields = (design: SaveDesign): ObjectField[] =>
  activeMainFields(design).filter(f => GROUP_TYPES.has(f.type))

/** 记录名称候选：主表有效字段，排除文件、集合与实时计算结果。 */
export const businessLabelFields = (design: SaveDesign): ObjectField[] =>
  activeMainFields(design).filter(f => LABEL_TYPES.has(f.type))

export interface BusinessAttachField {
  key: string
  label: string
  field: ObjectField
  detail: ObjectDetail | null
}

/** 参与字段候选：主表与有效明细中的附件/图片字段，明细字段带所在明细名。 */
export function businessAttachFields(design: SaveDesign): BusinessAttachField[] {
  const list: BusinessAttachField[] = []
  for (const field of activeMainFields(design)) {
    if (attachField(field)) list.push({ key: fieldKey(field), label: field.name, field, detail: null })
  }
  for (const detail of design.details) {
    for (const field of activeDetailFields(detail)) {
      if (attachField(field))
        list.push({ key: fieldKey(field), label: `${detail.name} · ${field.name}`, field, detail })
    }
  }
  return list
}

/** 规则问题清单：镜像服务端保存校验，用于设计器提示与保存前拦截；空数组表示可保存。 */
export function businessFileIssues(design: SaveDesign): string[] {
  const policy = design.settings.businessFilePolicy
  if (!policy) return []
  const issues: string[] = []
  if (!policy.spaceId) issues.push('请选择已有业务空间')
  if (!policy.spaceName?.trim() || policy.spaceName.length > MAX_SPACE_NAME)
    issues.push(`业务空间名称不能为空或超过 ${MAX_SPACE_NAME} 字`)
  const fixedPath = policy.fixedPath ?? []
  if (fixedPath.length > MAX_FIXED_LEVELS) issues.push(`固定目录最多 ${MAX_FIXED_LEVELS} 层`)
  for (const level of fixedPath) {
    if (!level.trim() || level.length > MAX_NAME_LENGTH) issues.push(`固定目录名称不能为空或超过 ${MAX_NAME_LENGTH} 字`)
    else if (/[\\/]/.test(level) || level.trim().startsWith('.'))
      issues.push('固定目录名称不能包含路径分隔符或以点开头')
  }
  const groups = policy.groups ?? []
  if (groups.length > MAX_GROUPS) issues.push(`业务分组最多 ${MAX_GROUPS} 层`)
  const main = activeMainFields(design)
  const usedGroups = new Set<string>()
  for (const group of groups) {
    const field = main.find(f => fieldKey(f) === group.fieldId)
    if (!field) {
      issues.push('业务分组必须引用主表有效字段')
      continue
    }
    if (!GROUP_TYPES.has(field.type)) issues.push(`业务分组仅支持主表文本、单选、日期或单值关联字段：${field.name}`)
    if (group.format && group.format !== 'YEAR' && group.format !== 'MONTH') issues.push('业务分组格式仅支持年份或年月')
    else if (group.format && !businessDateField(field)) issues.push(`年份/年月格式仅适用于日期字段：${field.name}`)
    if (usedGroups.has(group.fieldId)) issues.push('业务分组字段重复')
    usedGroups.add(group.fieldId)
  }
  const labels = policy.recordLabelFields ?? []
  if (labels.length > MAX_LABEL_FIELDS) issues.push(`记录目录名称最多引用 ${MAX_LABEL_FIELDS} 个字段`)
  for (const key of labels) {
    const field = main.find(f => fieldKey(f) === key)
    if (!field) issues.push('记录目录名称必须引用主表有效字段')
    else if (!LABEL_TYPES.has(field.type)) issues.push(`记录目录名称不支持此字段类型：${field.name}`)
  }
  const participants = policy.fieldIds ?? []
  if (!participants.length) issues.push('接入业务网盘时至少选择一个附件或图片字段')
  if (participants.length > MAX_PARTICIPATING) issues.push(`接入字段最多 ${MAX_PARTICIPATING} 个`)
  const attach = new Map(businessAttachFields(design).map(item => [item.key, item]))
  const usedParticipants = new Set<string>()
  for (const key of participants) {
    if (usedParticipants.has(key)) issues.push('接入字段重复')
    usedParticipants.add(key)
    if (!attach.has(key)) issues.push('接入字段不存在或已停用')
  }
  return issues
}

/** 设计期仅示意标题来源，不读取实际业务数据；模板按字段编码替换为字段名。 */
export function businessRecordTitlePreview(design: SaveDesign): string {
  const main = activeMainFields(design)
  const selected = design.settings.businessFilePolicy?.recordLabelFields ?? []
  if (selected.length)
    return selected
      .map(key => {
        const field = main.find(item => fieldKey(item) === key)
        return field ? `{${field.name}}` : '（名称配置失效）'
      })
      .join(' · ')
  const template = design.settings.titleTemplate?.trim()
  if (template)
    return template.replace(/\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}/g, (_, code: string) => {
      const field = main.find(item => item.code === code)
      return field ? `{${field.name}}` : '（名称配置失效）'
    })
  const field = main.find(item => item.key === design.draft.titleFieldKey || item.id === design.draft.titleFieldKey)
  return field ? `{${field.name}}` : '未配置记录标题'
}

/** 目录预览层级：空间 / 固定目录 / 业务分组 / 记录目录 / 字段分组；无实际记录时用字段名与当前年份示意。 */
export function businessDirectoryPreview(design: SaveDesign): string[] {
  const policy = design.settings.businessFilePolicy
  if (!policy) return []
  const now = new Date()
  const year = String(now.getFullYear())
  const month = `${year}-${String(now.getMonth() + 1).padStart(2, '0')}`
  const main = activeMainFields(design)
  const segments = [policy.spaceName?.trim() || '业务空间']
  for (const level of policy.fixedPath ?? []) if (level.trim()) segments.push(level.trim())
  for (const group of policy.groups ?? []) {
    const field = main.find(f => fieldKey(f) === group.fieldId)
    if (!field) continue
    if (businessDateField(field) && group.format === 'YEAR') segments.push(year)
    else if (businessDateField(field) && group.format === 'MONTH') segments.push(month)
    else segments.push(`{${field.name}}`)
  }
  segments.push(businessRecordTitlePreview(design))
  const attach = businessAttachFields(design).filter(item => (policy.fieldIds ?? []).includes(item.key))
  if (attach.length === 1) segments.push(attach[0].label)
  else if (attach.length > 1) segments.push(`${attach[0].label} 等 ${attach.length} 个字段分组`)
  return segments
}
