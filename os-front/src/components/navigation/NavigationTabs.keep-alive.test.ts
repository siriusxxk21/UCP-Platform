// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, getCurrentInstance, h, nextTick } from 'vue'
import type { App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Router } from 'vue-router'
import type { NavigationTab } from '@/router/navigationTabs'
import { useNavigationTabsStore } from '@/stores/navigationTabs'
import NavigationTabs from './NavigationTabs.vue'

const kept = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('@/nocode/kept-route-view', () => ({ confirmClosingKeptRoutes: kept.confirm }))

const Dropdown = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', [slots.default?.(), slots.overlay?.()])
})
const Menu = defineComponent({
  emits: ['click'],
  setup:
    (_, { slots, emit }) =>
    () =>
      h(
        'div',
        { onClick: (event: Event) => emit('click', { key: (event.target as HTMLElement).dataset.action }) },
        slots.default?.()
      )
})
const MenuItem = defineComponent({
  setup(_, { slots }) {
    const action = String(getCurrentInstance()?.vnode.key)
    return () => h('button', { 'data-action': action }, slots.default?.())
  }
})

function tab(id: string, closable = true): NavigationTab {
  return {
    key: `system:${id}`,
    scope: 'system',
    title: id,
    menuId: id,
    menuPath: `/page/${id}`,
    fullPath: `/page/${id}`,
    closable,
    lastActiveAt: Number(id)
  }
}

// jsdom 没有 scrollIntoView；页签栏在激活页签变化后会调用它。
Element.prototype.scrollIntoView ||= () => undefined

let app: App | undefined, host: HTMLDivElement, router: Router, tabs: ReturnType<typeof useNavigationTabsStore>

async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}

async function mount() {
  const pinia = createPinia()
  tabs = useNavigationTabsStore(pinia)
  tabs.tabs = [tab('1', false), tab('2'), tab('3'), tab('4')]
  tabs.activeKey = 'system:3'
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/page/:id', component: { render: () => null } }]
  })
  await router.push('/page/3')
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(NavigationTabs)
  app.use(pinia)
  app.use(router)
  app.component('a-dropdown', Dropdown)
  app.component('a-menu', Menu)
  app.component('a-menu-item', MenuItem)
  app.mount(host)
  await flush()
}

const tabButton = (title: string) => host.querySelector<HTMLElement>(`.navigation-tab[title="${title}"]`)
const openKeys = () => tabs.tabs.map(item => item.key)

async function closeByCross(title: string) {
  tabButton(title)!
    .querySelector<HTMLElement>('.navigation-tab-close')!
    .dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flush()
}

async function closeByMenu(title: string, action: string) {
  tabButton(title)!.parentElement!.querySelector<HTMLElement>(`[data-action="${action}"]`)!.click()
  await flush()
}

describe('navigation tabs with kept pages', () => {
  beforeEach(() => kept.confirm.mockReset())
  afterEach(() => {
    app?.unmount()
    app = undefined
    host?.remove()
  })

  it('asks about the tab being closed first and leaves it open when the user declines', async () => {
    kept.confirm.mockResolvedValue(false)
    await mount()

    await closeByCross('3')

    expect(kept.confirm).toHaveBeenCalledWith(['system:3'])
    expect(openKeys()).toEqual(['system:1', 'system:2', 'system:3', 'system:4'])
    expect(router.currentRoute.value.fullPath).toBe('/page/3')
  })

  it('closes the tab once the user agrees', async () => {
    kept.confirm.mockResolvedValue(true)
    await mount()

    await closeByCross('3')

    expect(openKeys()).toEqual(['system:1', 'system:2', 'system:4'])
    expect(router.currentRoute.value.fullPath).toBe('/page/2')
  })

  it('asks about exactly the tabs that closing others or all would close', async () => {
    kept.confirm.mockResolvedValue(false)
    await mount()

    await closeByMenu('3', 'others')
    expect(kept.confirm).toHaveBeenLastCalledWith(['system:2', 'system:4'])
    await closeByMenu('3', 'all')
    expect(kept.confirm).toHaveBeenLastCalledWith(['system:2', 'system:3', 'system:4'])
    expect(openKeys()).toEqual(['system:1', 'system:2', 'system:3', 'system:4'])

    kept.confirm.mockResolvedValue(true)
    await closeByMenu('3', 'others')
    expect(openKeys()).toEqual(['system:1', 'system:3'])
  })

  it.each([
    ['left', ['system:2'], ['system:1', 'system:3', 'system:4']],
    ['right', ['system:4'], ['system:1', 'system:2', 'system:3']]
  ])('confirms and closes only tabs on the %s side', async (side, closing, remaining) => {
    kept.confirm.mockResolvedValue(false)
    await mount()
    await closeByMenu('3', side as string)
    expect(kept.confirm).toHaveBeenLastCalledWith(closing)
    expect(openKeys()).toEqual(['system:1', 'system:2', 'system:3', 'system:4'])

    kept.confirm.mockResolvedValue(true)
    await closeByMenu('3', side as string)
    expect(openKeys()).toEqual(remaining)
    expect(router.currentRoute.value.fullPath).toBe('/page/3')
  })
})
