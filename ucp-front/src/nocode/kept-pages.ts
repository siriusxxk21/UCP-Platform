import {
  defineComponent,
  getCurrentInstance,
  h,
  KeepAlive,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  provide,
  ref,
  resolveDynamicComponent,
  shallowReactive,
  shallowRef,
  watch,
  withDirectives,
  type Component,
  type PropType
} from 'vue'
import { routeLocationKey, START_LOCATION, useRouter, type RouteLocationNormalizedLoaded } from 'vue-router'
import { Modal } from 'ant-design-vue'
import { pageActivityKey, usePageActivity, type PageActivity, type RouteLike } from './page-activity'

interface KeptHost {
  hasUnsaved: () => boolean
  released: boolean
  /** 切走之前记下页面里各个滚动位置：节点移出文档后浏览器会把它们清零 */
  saveScroll: () => void
}
/** 一组保活页面的登记处；页签栏拿它在关页签之前询问未保存的修改。 */
export type KeptPageRegistry = Map<string, KeptHost>
export const createKeptPageRegistry = (): KeptPageRegistry => new Map()

/**
 * 关闭这些页面之前确认：其中有未保存修改时问一次，用户同意才放行。
 * 没有保活页面或都没有修改时直接放行。
 */
export function confirmClosingKeptPages(registry: KeptPageRegistry, keys: string[]): Promise<boolean> {
  const dirty = keys.map(key => registry.get(key)).filter((host): host is KeptHost => !!host?.hasUnsaved())
  if (!dirty.length) return Promise.resolve(true)
  return new Promise(resolve =>
    Modal.confirm({
      title: '要关闭的页签里有尚未保存的修改',
      content: '关闭后这些修改会丢失。',
      okText: '放弃修改并关闭',
      cancelText: '继续编辑',
      onOk: () => {
        dirty.forEach(host => (host.released = true))
        resolve(true)
      },
      onCancel: () => {
        resolve(false)
      }
    })
  )
}

// keep-alive 只能按组件名清缓存；宿主以页面 key 命名，才能单独淘汰某一个页面。
const hostName = (key: string) => 'kept:' + key.replace(/,/g, '%2C')

interface Outlet {
  stays: (to: RouteLike) => boolean
  isOpen: (key: string) => boolean
  routeKey?: (route: RouteLike) => string | undefined
  registry: KeptPageRegistry
}

function createHost(key: string, outlet: Outlet): Component {
  return defineComponent({
    name: hostName(key),
    inheritAttrs: false,
    setup(_, { slots }) {
      const parent = usePageActivity()
      const own = new Set<() => boolean>()
      const active = ref(true),
        resumed = ref(0)
      let away = false
      const instance = getCurrentInstance()
      let overlays: HTMLElement | undefined
      let scrolled: Array<[Element, number, number]> = []
      const host: KeptHost = {
        hasUnsaved: () => [...own].some(changed => changed()),
        released: false,
        saveScroll() {
          scrolled = []
          const end = instance?.subTree.anchor
          for (let node: Node | null | undefined = overlays; node && node !== end; node = node.nextSibling) {
            if (!(node instanceof Element)) continue
            for (const element of [node, ...Array.from(node.querySelectorAll('*'))])
              if (element.scrollTop || element.scrollLeft)
                scrolled.push([element, element.scrollTop, element.scrollLeft])
          }
        }
      }
      const activity: PageActivity = {
        kept: true,
        active,
        resumed,
        survives: to => !host.released && outlet.stays(to) && outlet.isOpen(key),
        released: () => host.released || parent.released(),
        trackUnsaved(changed) {
          own.add(changed)
          const release = parent.trackUnsaved(changed)
          return () => {
            own.delete(changed)
            release()
          }
        },
        hasUnsaved: host.hasUnsaved
      }
      provide(pageActivityKey, activity)
      outlet.registry.set(key, host)
      onBeforeUnmount(() => {
        if (outlet.registry.get(key) === host) outlet.registry.delete(key)
      })
      // 上级宿主被切到后台时，仍在前台的下级宿主同样收到这两个钩子。
      onDeactivated(() => {
        away = true
        active.value = false
      })
      onActivated(() => {
        active.value = true
        if (!away) return
        away = false
        for (const [element, top, left] of scrolled) {
          element.scrollTop = top
          element.scrollLeft = left
        }
        resumed.value++
      })

      if (outlet.routeKey) {
        // 后台实例仍是响应式的：让它继续读全局地址，用户切到别的应用时它会跟着去加载那个应用。
        // 所以子树里的 useRoute() 换成只在「当前地址属于本页面」时才更新的一份。
        const router = useRouter(),
          resolveKey = outlet.routeKey
        const location = shallowRef(router.currentRoute.value)
        watch(
          router.currentRoute,
          to => {
            if (resolveKey(to) === key) location.value = to
          },
          { flush: 'sync' }
        )
        const frozen = {} as RouteLocationNormalizedLoaded
        for (const name of Object.keys(START_LOCATION) as Array<keyof RouteLocationNormalizedLoaded>)
          Object.defineProperty(frozen, name, { get: () => location.value[name], enumerable: true })
        provide(routeLocationKey, shallowReactive(frozen))
      }

      // 抽屉和弹窗默认挂在 body 上，页面切到后台后会留在别的页面上面；
      // 改挂到宿主自己的容器里，随页面一起移出、移回，打开状态原样保留。
      const capture = { created: (element: HTMLElement) => void (overlays = element) }
      // Modal 不带参数调用，Drawer 传 document.body；下拉、气泡传触发节点，仍挂 body。
      const getPopupContainer = (trigger?: HTMLElement) =>
        (!trigger || trigger === document.body ? overlays : undefined) || document.body

      return () => {
        const content = slots.default?.()
        const provider = resolveDynamicComponent('AConfigProvider')
        return [
          withDirectives(h('div', { class: 'kept-page-overlays' }), [[capture]]),
          typeof provider === 'string' ? content : h(provider as Component, { getPopupContainer }, () => content)
        ]
      }
    }
  })
}

/**
 * 按 key 保活的页面出口：同一位置轮流显示多个页面，切走的留在内存里，切回来是离开时的样子。
 * 没有 pageKey 时照常渲染、不保活。超出 max 淘汰最久未用的；有未保存修改的不淘汰。
 */
export const KeptPages = defineComponent({
  name: 'KeptPages',
  props: {
    pageKey: String,
    max: { type: Number, required: true },
    /** 只有仍在这个列表里的页面才留着（如仍开着的页签）；不给 = 访问过的都留着，只受 max 限制 */
    openKeys: Array as PropType<string[]>,
    /** 这次跳转之后本出口还在不在；不给 = 跟随上级 */
    stays: Function as PropType<(to: RouteLike) => boolean>,
    /** 给了就为每个页面冻结地址：地址属于哪个页面由它回答 */
    routeKey: Function as PropType<(route: RouteLike) => string | undefined>,
    registry: Object as PropType<KeptPageRegistry>
  },
  setup(props, { slots }) {
    const parent = usePageActivity()
    const registry = props.registry || createKeptPageRegistry()
    const hosts = new Map<string, Component>()
    // 最久未用的在前
    const alive = shallowRef<string[]>([])
    const outlet: Outlet = {
      stays: to => (props.stays ? props.stays(to) : parent.survives(to)),
      isOpen: key => !props.openKeys || props.openKeys.includes(key),
      routeKey: props.routeKey,
      registry
    }
    let shown: string | undefined
    function sync() {
      const current = props.pageKey
      // 这时旧页面还在文档里，滚动位置读得到
      if (shown && shown !== current) registry.get(shown)?.saveScroll()
      shown = current
      let next = alive.value.filter(key => key !== current && outlet.isOpen(key))
      if (current) next.push(current)
      let excess = next.length - props.max
      if (excess > 0)
        next = next.filter(key => {
          if (excess <= 0 || key === current || registry.get(key)?.hasUnsaved()) return true
          excess--
          return false
        })
      if (next.length === alive.value.length && next.every((key, index) => key === alive.value[index])) return
      for (const key of hosts.keys()) if (!next.includes(key)) hosts.delete(key)
      alive.value = next
    }
    watch(() => [props.pageKey, props.openKeys], sync, { immediate: true, flush: 'sync' })
    function hostFor(key: string) {
      let host = hosts.get(key)
      if (!host) hosts.set(key, (host = createHost(key, outlet)))
      return host
    }
    return () => {
      // 内容在出口这一层求值：后台宿主不会因为内容依赖的数据变化而重新渲染成别的页面。
      const content = slots.default?.()
      const key = props.pageKey
      return [
        h(KeepAlive, { include: alive.value.map(hostName) }, () =>
          key ? h(hostFor(key), { key }, () => content) : undefined
        ),
        key ? undefined : content
      ]
    }
  }
})
