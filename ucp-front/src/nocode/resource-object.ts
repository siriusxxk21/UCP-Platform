import type { ApplicationResource, PublishedDefinition } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import { NodeKind, uiNode, type FormConfig, type ViewConfig, type ViewInteraction } from '@/types/nocode/application-ui'
import { MemberState } from '@/types/nocode/enums'
import { businessFields } from './business-fields'
import { defaultViewList } from './runtime-list'

/** 更换对象时重建所有对象相关配置；仅保留展示选项，不能夹带旧关系/明细的稳定 ID。 */
export function objectResourceConfig(
  resource: ApplicationResource,
  definition: PublishedDefinition | undefined,
  interaction: ViewInteraction
): Record<string, unknown> {
  const fields = definition
    ? businessFields(definition).filter(field => definition.fieldOptions[field.id!]?.state !== MemberState.INACTIVE)
    : []
  const objectId = String(resource.config.objectId || '')
  if (resource.kind === ResourceKind.FORM) {
    const previous = resource.config as unknown as FormConfig
    const config: FormConfig = {
      objectId,
      nodes: fields.map(field => uiNode(NodeKind.FIELD, { fieldId: field.id! })),
      detailIds:
        definition?.details.filter(detail => detail.state === MemberState.ACTIVE).map(detail => detail.id!) || [],
      detailNodes: {},
      relatedForms: [],
      options: previous.options ? { ...previous.options } : { layout: 'vertical', submitText: '保存记录' }
    }
    return config as unknown as Record<string, unknown>
  }
  if (resource.kind === ResourceKind.VIEW) {
    const previous = resource.config as unknown as ViewConfig
    const config: ViewConfig = {
      objectId,
      fieldIds: fields.slice(0, 12).map(field => field.id!),
      descending: previous.descending,
      pageSize: previous.pageSize,
      sortFieldId: null,
      formId: null,
      detailPageId: null,
      composition: null,
      interaction,
      list: defaultViewList(),
      equal: {},
      filterDictionaries: {},
      query: { fixed: [], defaults: {}, candidates: {} }
    }
    return config as unknown as Record<string, unknown>
  }
  throw new Error('此资源不支持更换数据对象')
}
