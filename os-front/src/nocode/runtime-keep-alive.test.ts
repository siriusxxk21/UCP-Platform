// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, ref, type App, type Ref } from 'vue'
import { createPinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { Modal } from 'ant-design-vue'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { KeptPages } from '@/nocode/kept-pages'
import { usePageActivity } from '@/nocode/page-activity'
import { useRuntimeApplicationCache } from '@/nocode/runtime-application-cache'
import Runtime from '@/views/nocode/application/runtime.vue'

const api = vi.hoisted(() => ({ application: vi.fn(), model: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
const log: string[] = []
/** 列表页面自己说有没有未保存的修改 */
const dirty: Record<string, boolean> = {}
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  default: defineComponent({
    props: ['applicationId', 'objectId', 'viewId', 'view'],
    setup(props) {
      const name = props.viewId || props.objectId
      const page = ref(1)
      log.push('list:' + name)
      onUnmounted(() => log.push('gone:' + name))
      onUnmounted(usePageActivity().trackUnsaved(() => !!dirty[name]))
      return () =>
        h(
          'button',
          { 'data-records': name, 'data-columns': (props.view?.fieldIds || []).join(','), onClick: () => page.value++ },
          '第 ' + page.value + ' 页'
        )
    }
  })
}))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/PageRenderer.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/WorkShelf.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/WorkDraftPanel.vue', () => ({ default: { render: () => null } }))

const passThrough = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.())
})
const Alert = defineComponent({
  props: ['message', 'type'],
  setup:
    (props, { slots }) =>
    () =>
      h('div', { 'data-alert': props.type }, [props.message, slots.action?.(), slots.description?.()])
})
const Button = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('button', slots.default?.())
})

const view = (id: string, objectId: string, fieldIds = ['name']): ApplicationResource => ({
  id,
  kind: ResourceKind.VIEW,
  code: id,
  name: id,
  config: { objectId, fieldIds }
})
const menu = (id: string, targetId: string): ApplicationResource => ({
  id,
  kind: ResourceKind.MENU,
  code: id,
  name: id,
  config: { targetId }
})
function definition(
  resources: ApplicationResource[],
  versionNo = 1,
  objects = ['voucher', 'flow']
): RuntimeApplication {
  return {
    application: {
      id: 'app',
      code: 'finance',
      name: '财务',
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision: 1,
      publishedVersion: versionNo,
      updateTime: ''
    },
    versionNo,
    checksum: 'v' + versionNo,
    warnings: [],
    definition: { objects: objects.map(objectId => ({ objectId, versionNo: 1, checksum: objectId })), resources }
  }
}
/** 两个应用内菜单：凭证列表、流水列表 */
const withMenus = (versionNo = 1, columns = ['name']) =>
  definition(
    [view('vouchers', 'voucher', columns), view('flows', 'flow'), menu('m1', 'vouchers'), menu('m2', 'flows')],
    versionNo
  )
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

let app: App | undefined, host: HTMLDivElement, router: Router, pinia: Pinia
async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}
/** shown 给了就把运行页放进一层保活宿主，模拟它所在的页签被切走、切回。 */
async function mount(query: Record<string, string> = {}, shown?: Ref<boolean>) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/nocode-app/runtime', component: { render: () => null } }]
  })
  await router.push({ path: '/nocode-app/runtime', query: { id: 'app', ...query } })
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() =>
    shown
      ? h(KeptPages, { pageKey: shown.value ? 'tab' : 'other', max: 5, stays: () => true }, () =>
          shown.value ? h(Runtime, { key: 'tab' }) : h('p', { key: 'other' }, '别的页签')
        )
      : h(Runtime)
  )
  app.use(pinia)
  app.use(router)
  for (const name of ['spin', 'select', 'empty', 'statistic', 'result', 'card']) app.component('a-' + name, passThrough)
  app.component('a-alert', Alert)
  app.component('a-button', Button)
  app.mount(host)
  await flush()
}
function unmount() {
  app?.unmount()
  app = undefined
  host?.remove()
}
async function go(query: Record<string, string>) {
  await router.push({ path: '/nocode-app/runtime', query: { id: 'app', ...query } })
  await flush()
}
const list = (name: string) => host.querySelector<HTMLElement>(`[data-records="${name}"]`)
const applicationCalls = () => api.application.mock.calls.length

beforeEach(() => {
  vi.clearAllMocks()
  log.length = 0
  for (const name of Object.keys(dirty)) delete dirty[name]
  pinia = createPinia()
  api.model.mockImplementation(async (_applicationId: string, objectId: string) => ({
    object: { objectName: objectId }
  }))
})
afterEach(() => {
  unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('application runtime: pages inside one application', () => {
  it('keeps an in-application page alive across menu switches without asking for the definition again', async () => {
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' })
    list('vouchers')!.click()
    await nextTick()
    const kept = list('vouchers')!
    expect(kept.textContent).toBe('第 2 页')

    await go({ menu: 'm2' })
    expect(list('flows')).not.toBeNull()
    expect(kept.isConnected).toBe(false)
    await go({ menu: 'm1' })

    expect(list('vouchers')).toBe(kept)
    expect(kept.textContent).toBe('第 2 页')
    expect(log).toEqual(['list:vouchers', 'list:flows'])
    expect(applicationCalls()).toBe(1)
  })

  it('asks for all object names at once when the application has no navigation', async () => {
    const answers = new Map<string, ReturnType<typeof deferred<{ object: { objectName: string } }>>>()
    api.model.mockImplementation((_applicationId: string, objectId: string) => {
      const answer = deferred<{ object: { objectName: string } }>()
      answers.set(objectId, answer)
      return answer.promise
    })
    api.application.mockResolvedValue(definition([], 1, ['a', 'b', 'c', 'd']))
    await mount()
    // 一个都还没回来，四个已经全部发出
    expect([...answers.keys()]).toEqual(['a', 'b', 'c', 'd'])
    answers.get('a')!.reject(new Error('no access'))
    for (const objectId of ['b', 'c', 'd'])
      answers.get(objectId)!.resolve({ object: { objectName: '对象' + objectId } })
    await flush()
    expect(api.model).toHaveBeenCalledTimes(4)
  })
})

describe('application runtime: definition cache', () => {
  it('shows a previously opened application from the cache before the definition request returns', async () => {
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' })
    unmount()

    const again = deferred<RuntimeApplication>()
    api.application.mockReturnValue(again.promise)
    await mount({ menu: 'm1' })
    // 定义请求还挂着，内容已经出来了
    expect(list('vouchers')).not.toBeNull()
    expect(applicationCalls()).toBe(2)
    const shown = list('vouchers')!

    // 回来的定义与缓存一样：什么都不动
    again.resolve(withMenus())
    await flush()
    expect(list('vouchers')).toBe(shown)
    expect(log).toEqual(['list:vouchers', 'gone:vouchers', 'list:vouchers'])
  })

  it('replaces the definition and rebuilds the kept pages when a new version was published', async () => {
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' })
    await go({ menu: 'm2' })
    await go({ menu: 'm1' })
    unmount()
    log.length = 0

    api.application.mockResolvedValue(withMenus(2, ['name', 'amount']))
    await mount({ menu: 'm1' })
    expect(list('vouchers')!.dataset.columns).toBe('name,amount')
    // 先按缓存画了一次，定义换新后按新定义重建
    expect([...log].sort()).toEqual(['gone:vouchers', 'list:vouchers', 'list:vouchers'])
  })

  it('treats a change in what the user may see as a change even when the version is the same', async () => {
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' })
    unmount()
    // 版本号、校验和都没变，但权限裁掉了一列
    api.application.mockResolvedValue(withMenus(1, []))
    await mount({ menu: 'm1' })
    expect(list('vouchers')!.dataset.columns).toBe('')
  })

  it('leaves the page alone while it holds unsaved changes and lets the user decide', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(options => {
      options.onOk?.()
      return { destroy: vi.fn(), update: vi.fn() }
    })
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' })
    unmount()

    const again = deferred<RuntimeApplication>()
    api.application.mockReturnValue(again.promise)
    await mount({ menu: 'm1' })
    dirty.vouchers = true
    const editing = list('vouchers')!
    again.resolve(withMenus(2, ['name', 'amount']))
    await flush()

    expect(list('vouchers')).toBe(editing)
    expect(editing.dataset.columns).toBe('name')
    const update = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '立即更新')!
    expect(update).toBeTruthy()

    update.click()
    await flush()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(list('vouchers')!.dataset.columns).toBe('name,amount')
    expect(Array.from(host.querySelectorAll('button')).some(button => button.textContent === '立即更新')).toBe(false)
  })
})

describe('application runtime: returning to its tab', () => {
  it('compares the definition in the background and keeps the page when nothing changed', async () => {
    const shown = ref(true)
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' }, shown)
    const kept = list('vouchers')!

    shown.value = false
    await flush()
    expect(applicationCalls()).toBe(1)
    const again = deferred<RuntimeApplication>()
    api.application.mockReturnValue(again.promise)
    shown.value = true
    await flush()

    // 切回来立刻是原来的页面；定义在后台比对
    expect(list('vouchers')).toBe(kept)
    expect(applicationCalls()).toBe(2)
    again.resolve(withMenus())
    await flush()
    expect(list('vouchers')).toBe(kept)
    expect(log).toEqual(['list:vouchers'])
  })

  it('reloads visibly when the definition was discarded while the tab was in the background', async () => {
    const shown = ref(true)
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' }, shown)
    shown.value = false
    await flush()
    // 本机刚发布了这个应用
    app!.runWithContext(useRuntimeApplicationCache).forget('app')
    api.application.mockResolvedValue(withMenus(2, ['name', 'amount']))
    shown.value = true
    await flush()
    expect(list('vouchers')!.dataset.columns).toBe('name,amount')
    expect(log).toEqual(['list:vouchers', 'gone:vouchers', 'list:vouchers'])
  })

  it('invalidates the page when the server refuses the application, but not on a network failure', async () => {
    const shown = ref(true)
    api.application.mockResolvedValue(withMenus())
    await mount({ menu: 'm1' }, shown)

    shown.value = false
    await flush()
    api.application.mockRejectedValue(new Error('Network Error'))
    shown.value = true
    await flush()
    expect(list('vouchers')).not.toBeNull()
    expect(host.querySelector('[data-alert="error"]')).toBeNull()

    shown.value = false
    await flush()
    api.application.mockRejectedValue(Object.assign(new Error('应用未发布或已停用'), { businessCode: 1 }))
    shown.value = true
    await flush()
    expect(list('vouchers')).toBeNull()
    expect(host.querySelector('[data-alert="error"]')!.textContent).toContain('应用未发布或已停用')
  })
})
