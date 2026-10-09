// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, ref, type App, type Ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView, useRoute, type Router } from 'vue-router'
import Antd, { Drawer, Modal } from 'ant-design-vue'
import { confirmClosingKeptPages, createKeptPageRegistry, KeptPages, type KeptPageRegistry } from './kept-pages'
import { providePageScope, usePageActivity } from './page-activity'
import { useUnsavedNavigation } from './unsaved'

let app: App | undefined, host: HTMLDivElement
const log: string[] = []
/** 每个页面自己说有没有未保存的修改 */
const dirty: Record<string, boolean> = {}

async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}

/** 一个有自己状态（输入框）的页面，记录创建与销毁，并把保活处境写在节点上。 */
const Probe = defineComponent({
  props: { name: { type: String, required: true } },
  setup(props) {
    const activity = usePageActivity()
    const text = ref('')
    log.push('setup:' + props.name)
    onUnmounted(() => log.push('unmount:' + props.name))
    onUnmounted(activity.trackUnsaved(() => !!dirty[props.name]))
    return () =>
      h(
        'section',
        {
          'data-page': props.name,
          'data-active': String(activity.active.value),
          'data-resumed': activity.resumed.value
        },
        [
          h('input', {
            value: text.value,
            onInput: (event: Event) => {
              text.value = (event.target as HTMLInputElement).value
            }
          })
        ]
      )
  }
})

interface Outlet {
  current: Ref<string | undefined>
  open: Ref<string[] | undefined>
  registry: KeptPageRegistry
}
function mount(max: number, first?: string): Outlet {
  const outlet = { current: ref(first), open: ref<string[]>(), registry: createKeptPageRegistry() }
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() =>
    h(KeptPages, { pageKey: outlet.current.value, openKeys: outlet.open.value, max, registry: outlet.registry }, () =>
      // 内容依赖当前 key：后台宿主若自己求值，会被渲染成当前页面。
      h(Probe, { key: outlet.current.value || 'plain', name: outlet.current.value || 'plain' })
    )
  )
  app.mount(host)
  return outlet
}
async function show(outlet: Outlet, key: string | undefined) {
  outlet.current.value = key
  await flush()
}
const page = (name: string) => host.querySelector<HTMLElement>(`[data-page="${name}"]`)
function type(name: string, text: string) {
  const input = page(name)!.querySelector('input')!
  input.value = text
  input.dispatchEvent(new Event('input'))
}

beforeEach(() => {
  log.length = 0
  for (const name of Object.keys(dirty)) delete dirty[name]
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('kept pages', () => {
  it('keeps a page in memory while another is shown and brings it back as it was left', async () => {
    const outlet = mount(5, 'a')
    await flush()
    type('a', '填了一半')
    const kept = page('a')!
    await show(outlet, 'b')

    // 后台页面不在文档里，也没有被渲染成当前页面
    expect(page('a')).toBeNull()
    expect(kept.isConnected).toBe(false)
    expect(kept.dataset.page).toBe('a')
    expect(kept.dataset.active).toBe('false')
    expect(page('b')).not.toBeNull()

    await show(outlet, 'a')
    expect(page('a')).toBe(kept)
    expect(page('a')!.querySelector('input')!.value).toBe('填了一半')
    expect(log).toEqual(['setup:a', 'setup:b'])
  })

  it('tells a page when it returns to the foreground, and not on its first appearance', async () => {
    const outlet = mount(5, 'a')
    await flush()
    expect(page('a')!.dataset.resumed).toBe('0')
    expect(page('a')!.dataset.active).toBe('true')
    await show(outlet, 'b')
    expect(page('b')!.dataset.resumed).toBe('0')
    await show(outlet, 'a')
    expect(page('a')!.dataset.resumed).toBe('1')
    expect(page('a')!.dataset.active).toBe('true')
    await show(outlet, 'b')
    await show(outlet, 'a')
    expect(page('a')!.dataset.resumed).toBe('2')
  })

  it('drops the least recently shown page beyond the limit', async () => {
    const outlet = mount(2, 'a')
    await flush()
    await show(outlet, 'b')
    await show(outlet, 'a')
    await show(outlet, 'c')
    // b 最久没看
    expect(log).toEqual(['setup:a', 'setup:b', 'setup:c', 'unmount:b'])
    await show(outlet, 'b')
    expect(log.slice(4)).toEqual(['setup:b', 'unmount:a'])
  })

  it('never drops a page that holds unsaved changes to make room', async () => {
    const outlet = mount(2, 'a')
    await flush()
    dirty.a = true
    await show(outlet, 'b')
    await show(outlet, 'c')
    expect(log).toEqual(['setup:a', 'setup:b', 'setup:c', 'unmount:b'])
    await show(outlet, 'a')
    expect(page('a')!.dataset.resumed).toBe('1')
  })

  it('releases a page once it is no longer open', async () => {
    const outlet = mount(5, 'a')
    outlet.open.value = ['a', 'b']
    await flush()
    await show(outlet, 'b')
    outlet.open.value = ['b']
    await flush()
    expect(log).toEqual(['setup:a', 'setup:b', 'unmount:a'])
    expect(outlet.registry.has('a')).toBe(false)
    expect(outlet.registry.has('b')).toBe(true)
  })

  it('renders content without a key as an ordinary page and keeps the others', async () => {
    const outlet = mount(5, 'a')
    await flush()
    await show(outlet, undefined)
    expect(page('plain')).not.toBeNull()
    expect(page('plain')!.dataset.active).toBe('true')
    await show(outlet, 'a')
    expect(log).toEqual(['setup:a', 'setup:plain', 'unmount:plain'])
    expect(page('a')!.dataset.resumed).toBe('1')
  })

  it('restores the scroll positions a page had when it left the screen', async () => {
    const outlet = mount(5, 'a')
    await flush()
    const section = page('a')!
    const input = section.querySelector('input')!
    section.scrollTop = 120
    input.scrollLeft = 40
    await show(outlet, 'b')
    // 浏览器在节点移出文档后会把滚动位置清零
    section.scrollTop = 0
    input.scrollLeft = 0
    await show(outlet, 'a')
    expect(section.scrollTop).toBe(120)
    expect(input.scrollLeft).toBe(40)
  })

  it('tells the page shown inside a kept page when the outer page comes back, and only that one', async () => {
    const outer = ref('tab'),
      inner = ref('x')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() =>
      h(KeptPages, { pageKey: outer.value, max: 5 }, () =>
        outer.value === 'tab'
          ? h(KeptPages, { key: 'tab', pageKey: inner.value, max: 5 }, () =>
              h(Probe, { key: inner.value, name: inner.value })
            )
          : h(Probe, { key: 'elsewhere', name: 'elsewhere' })
      )
    )
    app.mount(host)
    await flush()
    inner.value = 'y'
    await flush()
    expect(document.querySelectorAll('[data-page]')).toHaveLength(1)
    const shown = page('y')!

    outer.value = 'other'
    await flush()
    expect(shown.dataset.active).toBe('false')
    outer.value = 'tab'
    await flush()
    // 外层页签回来：里面正在显示的 y 回到前台；仍在后台的 x 不受影响
    expect(page('y')).toBe(shown)
    expect(shown.dataset.active).toBe('true')
    expect(shown.dataset.resumed).toBe('1')
    inner.value = 'x'
    await flush()
    expect(page('x')!.dataset.resumed).toBe('1')
    expect(log.filter(entry => entry.startsWith('setup:'))).toEqual(['setup:x', 'setup:y', 'setup:elsewhere'])
  })

  it('asks before closing pages that hold unsaved changes, once', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(() => ({ destroy: vi.fn(), update: vi.fn() }))
    const outlet = mount(5, 'a')
    await flush()
    await show(outlet, 'b')

    await expect(confirmClosingKeptPages(outlet.registry, ['a', 'b', 'never-opened'])).resolves.toBe(true)
    expect(confirm).not.toHaveBeenCalled()

    dirty.a = true
    const refused = confirmClosingKeptPages(outlet.registry, ['a'])
    confirm.mock.calls[0][0].onCancel?.()
    await expect(refused).resolves.toBe(false)

    const accepted = confirmClosingKeptPages(outlet.registry, ['a'])
    confirm.mock.calls[1][0].onOk?.()
    await expect(accepted).resolves.toBe(true)
    expect(confirm).toHaveBeenCalledTimes(2)
  })
})

describe('kept pages under the router', () => {
  let router: Router
  let outlet: { open: Ref<string[] | undefined>; registry: KeptPageRegistry }
  const tab = (route: { query: Record<string, unknown> }) => (route.query.tab ? String(route.query.tab) : undefined)

  /** 页面读地址；保活后后台的那个不能跟着别的页面的地址变。 */
  const Reader = defineComponent({
    props: { name: { type: String, required: true } },
    setup(props) {
      const route = useRoute()
      useUnsavedNavigation(() => !!dirty[props.name])
      onUnmounted(() => log.push('unmount:' + props.name))
      return () => h('section', { 'data-page': props.name }, route.fullPath)
    }
  })
  const Shell = defineComponent({
    setup() {
      const route = useRoute()
      return () =>
        h(
          KeptPages,
          {
            pageKey: tab(route),
            openKeys: outlet.open.value,
            max: 5,
            routeKey: tab,
            stays: () => true,
            registry: outlet.registry
          },
          () => h(Reader, { key: tab(route) || 'plain', name: tab(route) || 'plain' })
        )
    }
  })
  async function start(path: string) {
    outlet = { open: ref<string[]>(), registry: createKeptPageRegistry() }
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/p', component: Shell },
        { path: '/elsewhere', component: { render: () => h('p', '别处') } }
      ]
    })
    await router.push(path)
    await router.isReady()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() => h(RouterView))
    app.use(router)
    app.mount(host)
    await flush()
  }
  async function go(path: string) {
    const failure = await router.push(path)
    await flush()
    return failure
  }

  it('freezes the address a background page sees until that page is shown again', async () => {
    await start('/p?tab=a&x=1')
    const kept = page('a')!
    await go('/p?tab=b&x=9')
    expect(kept.isConnected).toBe(false)
    expect(kept.textContent).toBe('/p?tab=a&x=1')
    expect(page('b')!.textContent).toBe('/p?tab=b&x=9')
    await go('/p?tab=a&x=2')
    expect(page('a')).toBe(kept)
    expect(kept.textContent).toBe('/p?tab=a&x=2')
  })

  it('does not ask about unsaved changes when the page only goes to the background', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(() => ({ destroy: vi.fn(), update: vi.fn() }))
    await start('/p?tab=a')
    dirty.a = true
    expect(await go('/p?tab=b')).toBeUndefined()
    expect(confirm).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query.tab).toBe('b')
    expect(log).toEqual([])
  })

  it('still asks when the navigation is about to destroy the page', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(options => {
      options.onCancel?.()
      return { destroy: vi.fn(), update: vi.fn() }
    })
    await start('/p?tab=a')
    dirty.a = true
    // 页签已经关了：这次跳走之后页面不会留着
    outlet.open.value = []
    await go('/p?tab=b')
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query.tab).toBe('a')
  })

  it('lets a page that is not kept say for itself whether a navigation destroys it', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(options => {
      options.onCancel?.()
      return { destroy: vi.fn(), update: vi.fn() }
    })
    // 从「我的应用」进入的运行页不保活：在本应用里跳转它还在，离开本应用才销毁
    const Plain = defineComponent({
      setup() {
        const route = useRoute()
        providePageScope(to => to.path === '/p' && to.query.tab === route.query.tab)
        return () => h(Reader, { name: 'plain' })
      }
    })
    outlet = { open: ref<string[]>(), registry: createKeptPageRegistry() }
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/p', component: Plain },
        { path: '/elsewhere', component: { render: () => h('p', '别处') } }
      ]
    })
    await router.push('/p?tab=a&x=1')
    await router.isReady()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() => h(RouterView))
    app.use(router)
    app.mount(host)
    await flush()
    dirty.plain = true

    await go('/p?tab=a&x=2')
    expect(confirm).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query.x).toBe('2')
    await go('/elsewhere')
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.path).toBe('/p')
  })

  it('does not ask a second time once closing the page has been confirmed', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(options => {
      options.onOk?.()
      return { destroy: vi.fn(), update: vi.fn() }
    })
    await start('/p?tab=a')
    dirty.a = true
    await expect(confirmClosingKeptPages(outlet.registry, ['a'])).resolves.toBe(true)
    outlet.open.value = []
    await go('/elsewhere')
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.path).toBe('/elsewhere')
  })
})

describe('drawers and dialogs of a kept page', () => {
  const Sheet = defineComponent({
    props: { name: { type: String, required: true } },
    setup(props) {
      const open = ref(false)
      const text = ref('')
      return () =>
        h('section', { 'data-page': props.name }, [
          h('button', { onClick: () => (open.value = true) }, '打开'),
          h(Drawer, { open: open.value, title: props.name + ' 的抽屉' }, () =>
            h('input', {
              'data-drawer-input': props.name,
              value: text.value,
              onInput: (event: Event) => {
                text.value = (event.target as HTMLInputElement).value
              }
            })
          ),
          h(Modal, { open: open.value, title: props.name + ' 的弹窗' }, () => h('p', '弹窗内容'))
        ])
    }
  })

  it('leave the screen with their page and come back open, with what was typed', async () => {
    const current = ref('a')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() =>
      h(KeptPages, { pageKey: current.value, max: 5 }, () => h(Sheet, { key: current.value, name: current.value }))
    )
    app.use(Antd)
    app.mount(host)
    await flush()
    page('a')!.querySelector('button')!.click()
    await flush()

    const drawerInput = document.querySelector<HTMLInputElement>('[data-drawer-input="a"]')!
    expect(drawerInput).not.toBeNull()
    // 抽屉和弹窗挂在页面自己的容器里，不在 body 下
    expect(drawerInput.closest('.kept-page-overlays')).not.toBeNull()
    expect(document.querySelector('.ant-modal')!.closest('.kept-page-overlays')).not.toBeNull()
    drawerInput.value = '抽屉里填的'
    drawerInput.dispatchEvent(new Event('input'))

    current.value = 'b'
    await flush()
    expect(document.querySelector('.ant-drawer')).toBeNull()
    expect(document.querySelector('.ant-modal')).toBeNull()
    expect(drawerInput.isConnected).toBe(false)

    current.value = 'a'
    await flush()
    expect(document.querySelector('[data-drawer-input="a"]')).toBe(drawerInput)
    expect(drawerInput.value).toBe('抽屉里填的')
    expect(document.querySelector('.ant-modal')).not.toBeNull()
  })
})
