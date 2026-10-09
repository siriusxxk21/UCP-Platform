import { fieldRelation } from './business-fields'
import type { ObjectField } from '@/types/nocode/object'
import type { BusinessFilePolicy, FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { TableModel } from '@/types/nocode/runtime'
import { FieldType } from '@/types/nocode/enums'
import { selectionSource } from './selection'
import { recordRules } from './record-form'
import { recordNumberError } from './record-number'
import { hyperlinkError, hyperlinkParts } from './hyperlink'
import type { FieldRuleResult } from '@/types/nocode/field-rules'
import {
  FieldRuleState,
  pendingText,
  ruleDependsOn,
  ruleReadOnly,
  type RuleFieldName,
  type RuleStateMap
} from './field-rule-runtime'

export type FormRenderMode = 'runtime' | 'design' | 'preview'
export interface BusinessFieldContext {
  applicationId?: string
  objectId?: string
  detailId?: string
  recordId?: string
  detailRecordId?: string
  formId?: string
  relations?: ObjectRelation[]
  mode?: FormRenderMode
  /** 对象业务文件策略；附件字段已接入业务模式时下发给控件，未配置时控件保持底座上传。 */
  businessPolicy?: BusinessFilePolicy | null
  onUploadStatus?: (id: string, status: { pending: boolean; failed: boolean }) => void
}
const specialTypes: FieldType[] = [
  FieldType.ORGANIZATION,
  FieldType.USER,
  FieldType.DEPARTMENT,
  FieldType.POST,
  FieldType.USER_GROUP,
  FieldType.IMAGE,
  FieldType.ATTACHMENT,
  FieldType.RICH_TEXT,
  FieldType.REFERENCE
]

/** 关系的 generated 表示平台建列，不表示业务值只读；与运行模型的归一化保持一致。 */
export function businessFieldOptions(options: Record<string, FieldOptions>, relations: ObjectRelation[] = []) {
  return Object.fromEntries(
    Object.entries(options).map(([id, value]) => [
      id,
      relations.some(relation => relation.fieldId === id) ? { ...value, generated: false } : value
    ])
  )
}

/** 设计、预览和运行共用字段映射；表单节点只绑定字段，不接收任意控件类型。 */
export function businessFieldRules(
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  model: TableModel,
  creating: boolean,
  context: BusinessFieldContext = {}
) {
  return recordRules(fields, businessFieldOptions(options, context.relations), model, creating).map(rule => {
    const field = fields.find(f => f.id === rule.field)!
    const relation = fieldRelation(context.relations, field.id)
    if (field.type === FieldType.URL) {
      rule.type = 'nocodeHyperlink'
      rule.modelField = 'modelValue'
      rule.validate = rule.props?.disabled
        ? []
        : [
            {
              validator: (_: unknown, value: unknown) => {
                const error =
                  hyperlinkError(value) ||
                  (field.required && !hyperlinkParts(value).link.trim() ? '请填写' + field.name : null)
                return error ? Promise.reject(new Error(error)) : Promise.resolve()
              }
            }
          ]
      return rule
    }
    if (
      !relation &&
      context.mode !== 'design' &&
      !rule.props?.disabled &&
      [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(t => t === field.type)
    ) {
      rule.validate = [
        ...(rule.validate || []),
        {
          validator: (_rule: unknown, value: unknown) => {
            const error = recordNumberError(field, options[field.id!], value)
            return error ? Promise.reject(new Error(error)) : Promise.resolve()
          }
        }
      ]
    }
    const source = selectionSource(field, options[field.id!])
    if (
      relation ||
      specialTypes.includes(field.type) ||
      (source && (source.kind !== 'LOCAL_OPTIONS' || !!context.objectId))
    ) {
      rule.type = 'nocodeBusinessField'
      rule.modelField = 'modelValue'
      rule.props = {
        ...rule.props,
        placeholder: rule.props?.disabled
          ? rule.props.placeholder
          : (field.type === FieldType.RICH_TEXT ? '请输入' : '请选择') + field.name,
        kind: relation ? FieldType.REFERENCE : field.type,
        mode: context.mode || 'runtime',
        applicationId: context.applicationId,
        objectId: context.objectId,
        detailId: context.detailId,
        recordId: context.recordId,
        detailRecordId: context.detailRecordId,
        formId: context.formId,
        fieldId: field.id,
        selection: !!source || !!relation,
        multiple: field.type === FieldType.MULTI_SELECT,
        creating,
        required: !!field.required,

        targetObjectId: relation?.targetObjectId,
        ...(context.businessPolicy ? { businessPolicy: context.businessPolicy } : {}),
        // 服务端投影只留依赖；明细行把依赖拆成本行与主表两组，主表依赖变化时选择器只标记过期。
        ...ruleDependencyProps(fields, options, field.id!, context.detailId),
        ...(context.onUploadStatus
          ? {
              onUploadStatus: (status: { pending: boolean; failed: boolean }) =>
                context.onUploadStatus!(field.id!, status)
            }
          : {})
      }
    }
    return rule
  })
}

function ruleDependencyProps(
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  fieldId: string,
  detailId?: string
) {
  const depends = ruleDependsOn(options, fieldId)
  if (!depends.length) return {}
  const own = new Set(fields.map(f => f.id))
  return {
    ruleDependsOn: detailId ? depends.filter(id => own.has(id)) : depends,
    ruleMasterDependsOn: detailId ? depends.filter(id => !own.has(id)) : []
  }
}

/** 运行时字段规则在控件上的呈现（设计稿 10.3、15.4.6）。 */
export interface FieldRuleView {
  kind: FieldRuleResult['kind']
  state: string
  /** 只读规则字段（只读联动、公式默认值）：无论是否取到值都禁用，并带「联动」/「公式」标记。 */
  locked: boolean
  /** 控件下方灰色提示；APPLIED、不适用、引用筛选时为空（引用筛选由选择器自己提示）。 */
  message: string | null
  /** PENDING 时的「请先填写「X」」或「请先填写主表「X」」。 */
  pending: string | null
  /** 引用筛选：已选值是否仍在筛选内；当前值为空时为 null。 */
  inScope: boolean | null
}
export const NO_MATCH_TEXT = '没有匹配到来源记录'
export const READ_ONLY_EMPTY_TEXT = '未能带出值，此字段只能由规则带出'
/**
 * 结果到呈现。readOnly 为投影上的只读标记（rules.readOnly），与结果的 readOnly 任一为真即锁定；
 * 只读字段没取到值时字段为空且不能填写，提示原因（以后端 message 为准）；非只读仍提示可手动填写。
 */
export function fieldRuleView(
  result: FieldRuleResult | undefined,
  names: Record<string, RuleFieldName>,
  detailId?: string | null,
  readOnly = false
): FieldRuleView | null {
  if (!result) return null
  const pending = result.state === FieldRuleState.PENDING ? pendingText(result.pendingFields, names, detailId) : null
  const quiet = result.state === FieldRuleState.APPLIED || result.state === FieldRuleState.NOT_APPLICABLE
  const locked = readOnly || (result.kind !== 'REFERENCE' && result.readOnly)
  const fallback = locked
    ? result.state === FieldRuleState.NO_MATCH
      ? NO_MATCH_TEXT
      : READ_ONLY_EMPTY_TEXT
    : '未能带出联动值，可手动填写'
  return {
    kind: result.kind,
    state: result.state,
    locked,
    message: result.kind === 'REFERENCE' || quiet ? null : pending || result.message || fallback,
    pending,
    inScope: result.inScope ?? null
  }
}

/** 投影上的只读规则字段属于联动还是公式默认值：只读联动保留 rules.linkage，公式默认值没有。 */
const storedRuleKind = (options?: FieldOptions | null): FieldRuleResult['kind'] =>
  options?.rules?.linkage ? 'LINKAGE' : 'DEFAULT_FORMULA'
/**
 * 表单（明细时为本行）所有字段的规则呈现。只读规则字段（rules.readOnly 或 rules.linkage.readOnly）
 * 新建与编辑打开时都直接锁定（编辑打开不求值，显示库里的值，保存时服务端重算强制）；
 * 有了求值结果后提示按结果给出，锁定不因结果放开（业务方 2026-09-29 裁定）。
 */
export function fieldRuleViews(
  states: RuleStateMap,
  options: Record<string, FieldOptions | undefined>,
  names: Record<string, RuleFieldName>,
  detailId?: string | null
): Record<string, FieldRuleView> {
  const views: Record<string, FieldRuleView> = {}
  for (const [id, option] of Object.entries(options))
    if (ruleReadOnly(option))
      views[id] = {
        kind: storedRuleKind(option),
        state: 'STORED',
        locked: true,
        message: null,
        pending: null,
        inScope: null
      }
  for (const [id, result] of Object.entries(states)) {
    const view = fieldRuleView(result, names, detailId, ruleReadOnly(options[id]))
    if (view) views[id] = view
  }
  return views
}

/** 求值请求失败：只读字段仍锁定（保存时服务端重算），其余字段保留输入可手填。 */
export const RULE_ERROR_PREFIX = '数据联动或公式计算失败，已保留输入，非只读字段可手动填写：'
export const LINKAGE_LOCKED_TEXT = '联动带出，不可修改'
export const FORMULA_LOCKED_TEXT = '公式计算，不可修改'
export const lockedText = (view: FieldRuleView | null) =>
  !view?.locked ? null : view.kind === 'DEFAULT_FORMULA' ? FORMULA_LOCKED_TEXT : LINKAGE_LOCKED_TEXT
/**
 * 按规则呈现生成表单规则增量，由 RecordForm 通过表单引擎 API 局部合并，不重建规则树。
 * 业务控件（选择、目录、附件等）把呈现交给 BusinessFieldControl；其它输入控件用表单项说明文字，
 * 明细表格模式空间不足，改为悬浮提示。
 */
export function fieldRulePatch(
  view: FieldRuleView | null,
  input: { business: boolean; help?: string; compact?: boolean }
) {
  if (input.business) return { props: { _osLinkage: view } }
  const notes = [lockedText(view), view?.message || null].filter(Boolean) as string[]
  if (input.compact) return { props: { title: notes.join('；') }, wrap: { extra: input.help || '' } }
  return { wrap: { extra: [input.help, ...notes].filter(Boolean).join(' · ') } }
}
