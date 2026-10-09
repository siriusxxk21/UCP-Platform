import type { ViewButton, ViewConfig } from '@/types/nocode/application-ui'
import type { ApplicationResource } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'

/** 未配置时兼容历史视图；空数组是明确隐藏，不能当成缺省值。 */
export function viewButtonEnabled(view: ViewConfig | undefined, button: ViewButton) {
  return !view?.interaction || view.interaction.buttons.includes(button)
}
export function viewBusinessActions(view: ViewConfig | undefined, resources: ApplicationResource[], objectId: string) {
  return resources.filter(
    r =>
      r.kind === ResourceKind.ACTION &&
      r.config.objectId === objectId &&
      (!view?.interaction || view.interaction.actionIds.includes(r.id))
  )
}
