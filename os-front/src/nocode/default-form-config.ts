import { v4 as uuidv4 } from 'uuid'
import { ResourceKind, type ApplicationResource, type PublishedDefinition } from '@/types/nocode/application'
import { ViewButton, type FormConfig, type ViewConfig } from '@/types/nocode/application-ui'
import { MemberState } from '@/types/nocode/enums'
import { businessFields } from './business-fields'
import { resourceSnapshot } from './application-resource'
import { resourceCode } from './resource-code'
import { defaultFormNodes } from './form-presentation'
import { formLayoutNodes } from './form-detail-layout'
import { isDefaultForm, resolveViewForm } from './default-form'

/** 自动命名只用于新资源，不按名称寻找或替换已有表单。 */
function uniqueCode(name: string, resources: readonly ApplicationResource[]): string {
  const base = resourceCode(name, ResourceKind.FORM) || 'form_default'
  let code = base
  for (let suffix = 2; resources.some(resource => resource.code === code); suffix++) {
    const ending = `_${suffix}`
    code = base.slice(0, 64 - ending.length) + ending
  }
  return code
}

/** 复用通用表单布局，按应用固定对象版本生成可管理的 FORM；不改对象及业务数据。 */
export function createObjectForm(
  definition: PublishedDefinition,
  resources: readonly ApplicationResource[],
  asDefault = false
): ApplicationResource {
  const suffix = asDefault ? '默认表单' : '表单'
  const name = `${definition.objectName.slice(0, 160 - suffix.length)}${suffix}`
  const details = definition.details.filter(detail => detail.state === MemberState.ACTIVE)
  const detailIds = details.map(detail => detail.id!)
  const config: FormConfig = {
    objectId: definition.objectId,
    nodes: formLayoutNodes(
      defaultFormNodes(
        businessFields(definition).filter(field => definition.fieldOptions[field.id!]?.state !== MemberState.INACTIVE)
      ),
      detailIds
    ),
    detailIds,
    detailNodes: Object.fromEntries(
      details.map(detail => [
        detail.id!,
        defaultFormNodes(detail.fields.filter(field => detail.fieldOptions[field.id!]?.state !== MemberState.INACTIVE))
      ])
    ),
    relatedForms: [],
    options: { layout: 'vertical', submitText: '保存记录', relationLayout: true, defaultForObject: asDefault }
  }
  return { id: uuidv4(), kind: ResourceKind.FORM, code: uniqueCode(name, resources), name, config: { ...config } }
}

export function copyObjectForm(
  source: ApplicationResource,
  resources: readonly ApplicationResource[]
): ApplicationResource {
  if (source.kind !== ResourceKind.FORM) throw new Error('只能复制业务表单')
  const copy = resourceSnapshot(source)
  copy.id = uuidv4()
  copy.name = `${source.name}副本`.slice(0, 160)
  copy.code = uniqueCode(copy.name, resources)
  const config = copy.config as unknown as FormConfig
  config.options = { layout: 'vertical', submitText: '保存记录', ...config.options, defaultForObject: false }
  return copy
}

export function inheritedFormViews(resources: readonly ApplicationResource[], objectId: string): ApplicationResource[] {
  return resources.filter(
    resource => resource.kind === ResourceKind.VIEW && resource.config.objectId === objectId && !resource.config.formId
  )
}

/** 资源图中只按稳定 ID 识别绑定；同名表单和文本内容均不算引用。 */
function referencesForm(value: unknown, formId: string): boolean {
  if (Array.isArray(value)) return value.some(item => referencesForm(item, formId))
  if (!value || typeof value !== 'object') return false
  return Object.entries(value).some(
    ([key, item]) =>
      (['formId', 'resourceId', 'businessFormId'].includes(key) && item === formId) || referencesForm(item, formId)
  )
}

export function formUsage(resources: readonly ApplicationResource[], form: ApplicationResource): ApplicationResource[] {
  const inherited = new Set(
    isDefaultForm(form) ? inheritedFormViews(resources, String(form.config.objectId)).map(r => r.id) : []
  )
  return resources.filter(
    resource => resource.id !== form.id && (inherited.has(resource.id) || referencesForm(resource.config, form.id))
  )
}

/** 更换默认只修改用途标记，固定指定的 formId 保留。 */
export function setObjectDefaultForm(resources: readonly ApplicationResource[], formId: string): ApplicationResource[] {
  const target = resources.find(resource => resource.id === formId && resource.kind === ResourceKind.FORM)
  if (!target) throw new Error('表单不存在，请刷新后重试')
  return resources.map(resource => {
    if (resource.kind !== ResourceKind.FORM || resource.config.objectId !== target.config.objectId) return resource
    const copy = resourceSnapshot(resource)
    const config = copy.config as unknown as FormConfig
    config.options = {
      layout: 'vertical',
      submitText: '保存记录',
      ...config.options,
      defaultForObject: copy.id === formId
    }
    return copy
  })
}

export function formRemovalReason(resources: readonly ApplicationResource[], form: ApplicationResource): string {
  if (isDefaultForm(form)) return '请先将同对象的其他表单设为默认，再删除此表单'
  const usages = formUsage(resources, form)
  return usages.length ? `请先替换以下位置的表单引用：${usages.map(resource => resource.name).join('、')}` : ''
}

/** 仅新建可录入的普通列表自动准备默认表单，旧列表编辑与只读对象保持原行为。 */
export function needsDefaultForm(
  resources: readonly ApplicationResource[],
  view: ApplicationResource,
  definition: PublishedDefinition | undefined
): boolean {
  if (view.kind !== ResourceKind.VIEW || !definition || definition.readOnly) return false
  const config = view.config as unknown as ViewConfig
  if (config.formId || config.composition?.grain === 'DETAIL' || resolveViewForm(resources, config.objectId))
    return false
  const buttons = config.interaction?.buttons || Object.values(ViewButton)
  return buttons.includes(ViewButton.CREATE) || buttons.includes(ViewButton.UPDATE)
}
