import { inject, provide, ref, type InjectionKey, type Ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

// 这个模块只依赖 vue：列表、统计等数据组件都会引到它，不能把整套组件库和路由带进它们的加载链。
export type RouteLike = Pick<RouteLocationNormalizedLoaded, 'path' | 'fullPath' | 'query' | 'matched'>

/** 页面相对保活的处境。不在任何保活宿主里的组件拿到的是「一直在前台」。 */
export interface PageActivity {
  /** 是否处在保活宿主里 */
  kept: boolean
  /** 现在是否在前台；宿主或它的任何一层上级被切到后台都算后台 */
  active: Ref<boolean>
  /** 第几次从后台回到前台；首次挂载不算 */
  resumed: Ref<number>
  /** 这次跳转之后本页面是否还活着（只是切到后台，或根本没动） */
  survives: (to: RouteLike) => boolean
  /** 用户已经确认放弃这个页面里的修改（关页签之前问过了），离开时不再重复询问 */
  released: () => boolean
  /** 登记「有没有未保存的修改」；返回注销函数。同时登记到所有上级，关页签时由最外层统一询问。 */
  trackUnsaved: (changed: () => boolean) => () => void
  hasUnsaved: () => boolean
}

const foreground: PageActivity = {
  kept: false,
  active: ref(true),
  resumed: ref(0),
  survives: () => false,
  released: () => false,
  trackUnsaved: () => () => undefined,
  hasUnsaved: () => false
}
export const pageActivityKey: InjectionKey<PageActivity> = Symbol.for('ucp-platform.kept-pages.activity')

export function usePageActivity(): PageActivity {
  return inject(pageActivityKey, foreground)
}

/**
 * 给一棵子树单独记账未保存修改，其余沿用上级的处境。
 * 运行页用它回答「本应用里有没有没保存的表单」；不在保活宿主里时由 stays 说明这次跳转后页面还在不在。
 */
export function providePageScope(stays: (to: RouteLike) => boolean): PageActivity {
  const parent = usePageActivity()
  const own = new Set<() => boolean>()
  const scope: PageActivity = {
    ...parent,
    survives: to => (parent.kept ? parent.survives(to) : stays(to)),
    trackUnsaved(changed) {
      own.add(changed)
      const release = parent.trackUnsaved(changed)
      return () => {
        own.delete(changed)
        release()
      }
    },
    hasUnsaved: () => [...own].some(changed => changed())
  }
  provide(pageActivityKey, scope)
  return scope
}
