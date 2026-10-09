import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { FormConfig } from '@/types/nocode/application-ui'

/** 默认只是应用内表单的用途；旧配置缺省时仍是普通表单。 */
export function isDefaultForm(resource: ApplicationResource): boolean {
  return (
    resource.kind === ResourceKind.FORM && (resource.config as unknown as FormConfig).options?.defaultForObject === true
  )
}

/** 调用方只传当前应用固定版本的资源，避免跨应用或跨发布版本继承。 */
export function resolveViewForm(
  resources: readonly ApplicationResource[],
  objectId: string,
  explicitFormId?: string | null
): ApplicationResource | undefined {
  if (explicitFormId) {
    const form = resources.find(resource => resource.id === explicitFormId)
    if (!form || form.kind !== ResourceKind.FORM) throw new Error('列表指定的业务表单不存在，请联系应用管理员重新配置')
    if (form.config.objectId !== objectId)
      throw new Error('列表指定的业务表单不属于当前数据对象，请联系应用管理员重新配置')
    return form
  }
  const defaults = resources.filter(resource => isDefaultForm(resource) && resource.config.objectId === objectId)
  if (defaults.length > 1) throw new Error('当前数据对象存在多个默认表单，请联系应用管理员保留一份默认表单')
  return defaults[0]
}
