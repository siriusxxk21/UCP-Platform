import type { App, Directive, DirectiveBinding } from 'vue'
import { useUserStore } from '@/stores/user'
import type { AccessBindingValue } from '@/utils/access'
import { hasAnyAccess } from '@/utils/access'

function createAccessDirective(
  getGranted: () => string[],
  canBypass: () => boolean = () => false
): Directive<HTMLElement, AccessBindingValue> {
  const checkAccess = (el: HTMLElement, binding: DirectiveBinding<AccessBindingValue>) => {
    if (!hasAnyAccess(binding.value, getGranted(), canBypass())) {
      el.remove()
    }
  }

  return { mounted: checkAccess }
}

/** 注册系统权限和角色指令。 */
export function registerAccessDirectives(app: App): void {
  const userStore = useUserStore()
  app.directive(
    'hasPerm',
    createAccessDirective(
      () => {
        return [...userStore.permissions]
      },
      () => userStore.roles.includes('super_admin')
    )
  )
  app.directive(
    'hasRole',
    createAccessDirective(() => userStore.roles)
  )
}
