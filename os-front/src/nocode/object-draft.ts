import { v4 as uuidv4 } from 'uuid'
import * as NC from '@/types/nocode/enums'
import type { FieldType, ObjectDraft, ObjectField, SaveObjectDraft } from '@/types/nocode/object'
import { fieldCode } from './resource-code'

/** 与后端 BaseDOColumns 对应；主键和明细归属列另有自己的写入规则。 */
export const auditFieldNames = new Set(['creator', 'create_time', 'updater', 'update_time', 'deleted'])
export const baseFieldNames = new Set(['id', ...auditFieldNames, 'tenant_id', 'parent_id', 'lock_version'])

export const fieldTypes: { value: FieldType; label: string }[] = [
  { value: NC.FieldType.TEXT, label: '单行文本' },
  { value: NC.FieldType.TEXTAREA, label: '多行文本' },
  { value: NC.FieldType.INTEGER, label: '整数' },
  { value: NC.FieldType.DECIMAL, label: '小数' },
  { value: NC.FieldType.BOOLEAN, label: '开关' },
  { value: NC.FieldType.DATE, label: '日期' },
  { value: NC.FieldType.DATETIME, label: '日期时间' },
  { value: NC.FieldType.RICH_TEXT, label: '富文本' },
  { value: NC.FieldType.URL, label: '超链接' },
  { value: NC.FieldType.MONEY, label: '金额' },
  { value: NC.FieldType.PERCENT, label: '百分比' },
  { value: NC.FieldType.TIME, label: '时间' },
  { value: NC.FieldType.SELECT, label: '单选' },
  { value: NC.FieldType.MULTI_SELECT, label: '多选' },
  { value: NC.FieldType.ORGANIZATION, label: '组织' },
  { value: NC.FieldType.USER, label: '用户' },
  { value: NC.FieldType.DEPARTMENT, label: '部门' },
  { value: NC.FieldType.POST, label: '岗位' },
  { value: NC.FieldType.USER_GROUP, label: '用户组' },
  { value: NC.FieldType.IMAGE, label: '图片' },
  { value: NC.FieldType.ATTACHMENT, label: '附件' },
  { value: NC.FieldType.REGION, label: '地区' },
  { value: NC.FieldType.CASCADE, label: '级联选择' },
  { value: NC.FieldType.AUTO_NUMBER, label: '自动编号' },
  { value: NC.FieldType.FORMULA, label: '公式' },
  { value: NC.FieldType.SUMMARY, label: '汇总' },
  { value: NC.FieldType.UUID, label: NC.FieldType.UUID }
]
/** 临时 key 复用底座 uuid 以兼容 HTTP 环境；持久化稳定 ID 由后端分配。 */
export function newField(sort: number, name = ''): ObjectField {
  return {
    key: `new-${uuidv4()}`,
    id: null,
    code: fieldCode(name),
    name,
    type: NC.FieldType.TEXT,
    length: 200,
    precision: null,
    scale: null,
    required: false,
    unique: false,
    sort
  }
}
export function newDraft(): SaveObjectDraft {
  const field = { ...newField(0, '名称'), required: true }
  return {
    id: null,
    expectedLockVersion: null,
    objectCode: '',
    objectName: '',
    category: '',
    description: '',
    tableName: '',
    titleFieldKey: field.key,
    fields: [field],
    removedFieldIds: []
  }
}
/** 转为独立编辑副本，保留整体修订号供后端检查并发覆盖。 */
export function editDraft(draft: ObjectDraft): SaveObjectDraft {
  return {
    id: draft.id,
    expectedLockVersion: draft.lockVersion,
    objectCode: draft.objectCode,
    objectName: draft.objectName,
    category: draft.category ?? '',
    description: draft.description,
    tableName: draft.tableName,
    titleFieldKey: draft.titleFieldId,
    fields: draft.fields.map(field => ({ ...field })),
    removedFieldIds: []
  }
}
/** 类型切换时清除不适用的配置，避免遗留长度或精度被意外提交。 */
export function setFieldType(field: ObjectField, type: FieldType): void {
  field.type = type
  field.length = type === NC.FieldType.TEXT ? 200 : null
  field.precision = ([NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]).includes(
    type
  )
    ? 18
    : null
  field.scale = ([NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]).includes(type)
    ? 2
    : null
}
/** 既有字段须显式登记删除；未保存的新字段只从本地草稿移除。 */
export function removeField(draft: SaveObjectDraft, key: string): void {
  const field = draft.fields.find(item => item.key === key)
  if (!field) return
  if (field.id && !draft.removedFieldIds.includes(field.id)) draft.removedFieldIds.push(field.id)
  draft.fields = draft.fields.filter(item => item.key !== key)
  if (draft.titleFieldKey === key) draft.titleFieldKey = ''
}
/** 标题候选与后端保存规则一致；空白模板不能放宽字段类型。 */
export function recordTitleFields(draft: SaveObjectDraft, template = draft.titleTemplate): ObjectField[] {
  return draft.fields.filter(field => !!template?.trim() || field.type === NC.FieldType.TEXT)
}

/** 字段转换或移除后清理悬空标题；只有一个候选时可确定替代项，否则由用户选择。 */
export function reconcileRecordTitle(draft: SaveObjectDraft, template = draft.titleTemplate): boolean {
  const fields = recordTitleFields(draft, template)
  if (fields.some(field => field.key === draft.titleFieldKey)) return false
  const next = fields.length === 1 ? fields[0].key : ''
  if (draft.titleFieldKey === next) return false
  draft.titleFieldKey = next
  return true
}

export function validateDraft(draft: SaveObjectDraft, adopted = false, previousTableName?: string): string | null {
  if (!draft.objectName.trim()) return '请填写对象名称'
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(draft.objectCode)) return '对象编码须以小写字母开头，仅使用小写字母、数字、下划线'
  const unchangedLegacy =
    !!draft.id && draft.tableName === previousTableName && /^nocode_data_[a-z][a-z0-9_]*$/.test(draft.tableName)
  if ((!adopted && !unchangedLegacy && !/^biz_[a-z][a-z0-9_]*$/.test(draft.tableName)) || draft.tableName.length > 63)
    return '新物理表名须以 biz_ 开头，后缀以小写字母开头，最多 63 字符'
  if (draft.fields.length < 1 || draft.fields.length > 200) return '请配置 1–200 个字段'
  if (!recordTitleFields(draft).some(field => field.key === draft.titleFieldKey))
    return draft.titleTemplate?.trim()
      ? '请选择当前对象中存在的记录标题字段'
      : '请选择一个单行文本字段作为记录标题；引用关系请在“对象关系”中配置'
  const codes = new Set<string>()
  for (const field of draft.fields) {
    if (!field.name.trim() || !/^[a-z][a-z0-9_]{0,62}$/.test(field.code)) return '请检查字段名称和编码'
    if (baseFieldNames.has(field.code)) return `字段编码与底座公共字段冲突：${field.code}`
    if (codes.has(field.code)) return `字段编码重复：${field.code}`
    codes.add(field.code)
  }
  return null
}
