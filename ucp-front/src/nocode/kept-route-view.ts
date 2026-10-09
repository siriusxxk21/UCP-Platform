import {
  cloneVNode,
  computed,
  defineComponent,
  h,
  inject,
  onBeforeUnmount,
  provide,
  unref,
  watch,
  type PropType,
  type VNode
} from 'vue'
import { useRoute, viewDepthKey, type RouteLocationNormalizedLoaded } from 'vue-router'
import { resolveNavigationTab } from '@/router/navigationTabs'
import { useNavigationTabsStore } from '@/stores/navigationTabs'
import { useUserStore } from '@/stores/user'
import { confirmClosingKeptPages, createKeptPageRegistry, KeptPages } from './kept-pages'
import { piniaInstalled, useRuntimeApplicationCache } from './runtime-application-cache'
import { isApplicationRuntimeRoute, plainRouteKey, plainRouteSurvivalKey } from './runtime-route'

type RouteLike = Pick<RouteLocationNormalizedLoaded, 'path' | 'fullPath' | 'query' | 'matched'>
type Menus = Parameters<typeof resolveNavigationTab>[1]

/**
 * 同时保活的应用运行页个数（页签上限是 15）。
 * 超出后淘汰最久没看的；被淘汰的页签还在，再点它按首次打开处理。内存占用未实测，按实际再调。
 */
export const KEPT_ROUTE_PAGES = 8

/**
 * 这个地址落在哪个保活页面上：只有「应用运行页 + 有对应页签」才保活，key 就是页签 key。
 * 同一个运行组件对应多个页签（不同应用、不同菜单）时各是各的实例。
 */
export function keptRouteKey(route: RouteLike, menus: Menus): string | undefined {
  if (!isApplicationRuntimeRoute(route) || !route.query.id) return undefined
  return resolveNavigationTab(route, menus)?.key
}

const routePages = createKeptPageRegistry()

/** 页签栏关页签之前调用：要关的页签里有未保存修改时问一次，用户同意才返回 true。 */
export const confirmClosingKeptRoutes = (keys: string[]) => confirmClosingKeptPages(routePages, keys)

/** 布局内容区的出口：应用运行页按页签保活，其余页面照旧随地址重建。 */
export default defineComponent({
  name: 'KeptRouteView',
  props: { component: Object as PropType<VNode> },
  setup(props) {
    const route = useRoute()
    const user = useUserStore()
    // 没装 Pinia 的独立夹具拿不到页签列表：访问过的页面都留着，只受个数上限约束。
    const tabs = piniaInstalled() ? useNavigationTabsStore() : undefined
    const definitions = useRuntimeApplicationCache()
    const depth = inject(viewDepthKey, 1)
    const pageKey = computed(() => keptRouteKey(route, user.menus))
    const openKeys = computed(() => tabs?.tabs.map(tab => tab.key))
    // 换账号、换租户：上一个身份留下的页面和应用定义都不能带过来。
    const identity = computed(
      () => `${user.userInfo?.id ?? ''}:${user.tenantInfo?.id ?? user.userInfo?.tenantId ?? ''}`
    )
    watch(identity, () => definitions.forget())
    onBeforeUnmount(() => definitions.forget())
    /** 本出口在这次跳转后还在：它上面每一层路由记录都没换（去登录页时布局本身就卸载了）。 */
    function stays(to: RouteLike) {
      for (let level = 0; level < unref(depth) - 1; level++)
        if (to.matched[level] !== route.matched[level]) return false
      return true
    }
    const routeKey = (target: RouteLike) => keptRouteKey(target, user.menus)
    // 普通运行页切入有平台菜单的页签时会被移除，不能仅因应用编号相同就跳过弃改确认。
    provide(plainRouteSurvivalKey, to => !routeKey(to) && stays(to))
    return () => {
      const page = props.component
      const key = page ? pageKey.value : undefined
      return h(
        KeptPages,
        {
          key: identity.value,
          pageKey: key,
          openKeys: openKeys.value,
          max: KEPT_ROUTE_PAGES,
          stays,
          routeKey,
          registry: routePages
        },
        () => (page ? (key ? page : cloneVNode(page, { key: plainRouteKey(route) })) : undefined)
      )
    }
  }
})
