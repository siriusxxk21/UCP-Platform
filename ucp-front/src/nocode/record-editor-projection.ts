import { computed } from 'vue'
import type { FormConfig, RelatedFormBinding } from '@/types/nocode/application-ui'
import type { FieldOptions, ObjectDetail, ObjectRelation } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { Aggregate, RecordModel, RelatedFormResult, TableModel } from '@/types/nocode/runtime'
import { MemberState, RelationType } from '@/types/nocode/enums'
import { BusinessAction } from '@/types/nocode/authorization'
import { boundFields } from './application-ui'
import { businessFieldOptions } from './business-field-rules'
import { businessFields, isRelationFieldId, relationFieldId } from './business-fields'
import { formFieldProjection } from './form-field-projection'
import { defaultFormNodes } from './form-presentation'

interface RecordEditorSource {
  model: RecordModel
  record?: Aggregate
  form?: FormConfig
  readOnly?: boolean
  lockedValues?: Record<string, unknown>
}

/** 关联行的输入和脏状态由组件维护，投影只读取稳定身份及服务器权限。 */
export interface RelatedProjectionRow {
  key: string
  aggregate: Aggregate
}

interface DetailContext {
  fields: Array<ObjectField & { id: string }>
  relations: ObjectRelation[]
  options: Record<string, FieldOptions>
}
export type PublishedDetail = ObjectDetail & { id: string }

/** 已发布成员先验证实际 ID，再向控件和保存投影提供收窄类型；不替换为临时 key。 */
function requireMemberId<T extends { id: string | null }>(member: T): member is T & { id: string } {
  if (member.id === null) throw new Error('已发布表单成员缺少稳定标识')
  return true
}

function memberId(member: { id: string | null }): string {
  if (member.id === null) throw new Error('已发布表单成员缺少稳定标识')
  return member.id
}

function contextValue<T>(value: T | undefined): T {
  if (value === undefined) throw new Error('表单上下文尚未就绪，请稍后重试')
  return value
}

function detailContexts(details: ObjectDetail[], relations: ObjectRelation[], form?: FormConfig) {
  const contexts = new Map<string, DetailContext>()
  for (const detail of details) {
    const id = memberId(detail)
    const detailRelations = relations.filter(relation => relation.sourceDetailId === id)
    contexts.set(id, {
      fields: formFieldProjection(detail.fields, form?.detailNodes?.[id]).fields.filter(requireMemberId),
      relations: detailRelations,
      options: businessFieldOptions(detail.fieldOptions, detailRelations)
    })
  }
  return contexts
}

/** 控件、默认值、粘贴和保存共用投影；输入值变化不重建模型或关系选项。 */
export function useRecordEditorProjection(source: RecordEditorSource) {
  const lifecycle = computed(() => source.model.object.settings.documentPolicy?.lifecycle)
  // 锁定状态来自打开时的记录，不能随未保存的状态字段输入变化。
  const currentState = computed(
    () => source.record?.record.values[lifecycle.value?.fieldId || ''] ?? lifecycle.value?.initialState
  )
  const statePolicy = computed(() => lifecycle.value?.states.find(state => state.code === currentState.value))
  const formReadOnly = computed(() => source.readOnly || !!source.form?.options?.readOnly)
  const relatedBindings = computed(() => source.form?.relatedForms || [])
  const outgoingRelatedFields = computed(() =>
    relatedBindings.value
      .filter(binding => binding.direction === 'OUTGOING')
      .map(binding => source.model.object.relations.find(relation => relation.id === binding.relationId)?.fieldId)
  )
  const permissions = computed(() => source.record?.record.permissions || source.model.permissions)
  const fields = computed(() =>
    businessFields(source.model.object)
      .filter(requireMemberId)
      .filter(field => {
        const id = memberId(field)
        return (
          source.model.object.fieldOptions[id]?.state !== MemberState.INACTIVE &&
          !outgoingRelatedFields.value.includes(field.id) &&
          (isRelationFieldId(id)
            ? permissions.value.readRelations?.includes(id.slice(9))
            : permissions.value.readFields.includes(id))
        )
      })
  )
  const details = computed(() =>
    source.model.object.details
      .filter(requireMemberId)
      .filter(
        detail =>
          detail.state === MemberState.ACTIVE &&
          permissions.value.readDetails.includes(memberId(detail)) &&
          (!source.form || source.form.detailIds.includes(memberId(detail)))
      )
  )
  const relations = computed(() =>
    source.model.object.relations
      .filter(relation => relation.kind === RelationType.MANY_TO_MANY)
      .filter(requireMemberId)
      .filter(relation => permissions.value.readRelations?.includes(relation.id))
  )
  const formNodes = computed(() => {
    if (!source.form) return undefined
    if (source.form.options?.relationLayout) return source.form.nodes
    const used = boundFields(source.form.nodes)
    return [
      ...source.form.nodes,
      ...defaultFormNodes(
        fields.value.filter(field => isRelationFieldId(memberId(field)) && !used.includes(memberId(field)))
      )
    ]
  })
  const effectiveModel = computed(() => ({
    ...source.model,
    writable:
      !formReadOnly.value &&
      source.model.writable &&
      permissions.value.actions.includes(source.record?.record.id ? BusinessAction.UPDATE : BusinessAction.CREATE),
    writeFields: formFieldProjection(fields.value, formNodes.value, [
      ...permissions.value.writeFields,
      ...(permissions.value.writeRelations || []).map(relationFieldId)
    ]).writeFields.filter(
      id =>
        !(id in (source.lockedValues || {})) &&
        id !== lifecycle.value?.fieldId &&
        !statePolicy.value?.lockedFields.includes(id)
    )
  }))
  const mainOptions = computed(() =>
    businessFieldOptions(
      source.model.object.fieldOptions,
      source.model.object.relations.filter(relation => !relation.sourceDetailId)
    )
  )
  const contexts = computed(() => detailContexts(details.value, source.model.object.relations, source.form))
  const models = computed(() => {
    const models = new Map<string, TableModel>()
    for (const detail of details.value) {
      const id = memberId(detail)
      const model = source.model.details[id] || { writable: false, generatedKey: false, keyFieldId: null, keyType: '' }
      models.set(id, {
        ...model,
        writeFields: formFieldProjection(detail.fields, source.form?.detailNodes?.[id], model.writeFields).writeFields,
        writable:
          model.writable &&
          effectiveModel.value.writable &&
          permissions.value.writeDetails.includes(id) &&
          !statePolicy.value?.lockedDetails.includes(id)
      })
    }
    return models
  })
  const detailContext = (detail: ObjectDetail) => contextValue(contexts.value.get(memberId(detail)))
  return {
    lifecycle,
    currentState,
    formReadOnly,
    relatedBindings,
    permissions,
    fields,
    details,
    relations,
    formNodes,
    effectiveModel,
    mainOptions,
    detailFields: (detail: ObjectDetail) => detailContext(detail).fields,
    detailRelations: (detail: ObjectDetail) => detailContext(detail).relations,
    detailOptions: (detail: ObjectDetail) => detailContext(detail).options,
    detailModel: (detail: ObjectDetail) => contextValue(models.value.get(memberId(detail)))
  }
}

/** 独立关联记录逐行使用自身权限，公共结构缓存不依赖填写值和增删标记。 */
export function useRelatedFormProjection(
  source: { binding: RelatedFormBinding; readOnly?: boolean },
  getResult: () => RelatedFormResult | undefined,
  getRows: () => RelatedProjectionRow[]
) {
  const target = () => contextValue(getResult())
  const caps = (row: RelatedProjectionRow) => row.aggregate.record.permissions || target().model.permissions
  const rowModels = computed(() => {
    const models = new Map<string, TableModel>()
    const result = getResult()
    if (!result) return models
    for (const row of getRows())
      models.set(row.key, {
        ...result.model,
        writable:
          !source.readOnly &&
          !result.form.options?.readOnly &&
          result.model.writable &&
          caps(row).actions.includes(row.aggregate.record.id ? BusinessAction.UPDATE : BusinessAction.CREATE),
        writeFields: formFieldProjection(
          result.model.object.fields,
          result.form.nodes,
          caps(row).writeFields
        ).writeFields.filter(id => source.binding.direction !== 'INCOMING' || id !== result.linkFieldId)
      })
    return models
  })
  const rowModel = (row: RelatedProjectionRow) => contextValue(rowModels.value.get(row.key))
  const fieldLists = computed(() => {
    const fields = new Map<string, Array<ObjectField & { id: string }>>()
    const result = getResult()
    if (!result) return fields
    for (const row of getRows())
      fields.set(
        row.key,
        result.model.object.fields
          .filter(requireMemberId)
          .filter(
            field =>
              caps(row).readFields.includes(memberId(field)) &&
              (source.binding.direction !== 'INCOMING' || field.id !== result.linkFieldId)
          )
      )
    return fields
  })
  const mainOptions = computed(() =>
    businessFieldOptions(getResult()?.model.object.fieldOptions || {}, getResult()?.model.object.relations || [])
  )
  const contexts = computed(() => {
    const result = getResult()
    return detailContexts(result?.model.object.details || [], result?.model.object.relations || [], result?.form)
  })
  const detailModels = computed(() => {
    const models = new Map<string, Map<string, TableModel>>()
    const result = getResult()
    if (!result) return models
    for (const row of getRows()) {
      const details = new Map<string, TableModel>()
      for (const detail of result.model.object.details) {
        const id = memberId(detail)
        const model = result.model.details[id]
        details.set(id, {
          ...model,
          generatedKey: model?.generatedKey ?? false,
          keyFieldId: model?.keyFieldId ?? null,
          keyType: model?.keyType ?? '',
          writable: !!model?.writable && rowModel(row).writable && caps(row).writeDetails.includes(id),
          writeFields: formFieldProjection(detail.fields, result.form.detailNodes?.[id], model?.writeFields).writeFields
        })
      }
      models.set(row.key, details)
    }
    return models
  })
  const newModel = computed(() => {
    const result = getResult()
    if (!result) return undefined
    return {
      ...result.model,
      writeFields: formFieldProjection(
        result.model.object.fields,
        result.form.nodes,
        result.model.permissions.writeFields
      ).writeFields
    }
  })
  return {
    target,
    caps,
    rowModel,
    mainOptions,
    newModel: () => contextValue(newModel.value),
    publishedDetails: () => target().model.object.details.filter(requireMemberId),
    fields: (row: RelatedProjectionRow) => fieldLists.value.get(row.key) || [],
    nodes: () => target().form.nodes,
    detailRelations: (id: string) => contextValue(contexts.value.get(id)).relations,
    detailOptions: (id: string) => contextValue(contexts.value.get(id)).options,
    detailModel: (row: RelatedProjectionRow, id: string) => contextValue(detailModels.value.get(row.key)?.get(id))
  }
}
