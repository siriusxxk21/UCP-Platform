// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, nextTick, provide, ref, watch, type App, type Ref } from 'vue'
import { applicationRefreshKey, useApplicationRefresh } from './application-context'
import { KeptPages } from './kept-pages'
import {
  invalidateRuntimeData,
  openRuntimeApplications,
  runtimeDataPolicy,
  useRuntimeDataRefresh,
  type RuntimeDataInterest
} from './runtime-data'

let app: App | undefined, host: HTMLDivElement
/** 每个页面：静默重取了几次、响亮刷新（应用内刷新信号）了几次 */
const quiet: Record<string, number> = {}
const loud: Record<string, number> = {}
const interests: Record<string, RuntimeDataInterest | undefined> = {}
let pending: Array<() => void> = []
let hold = false
let signal: Ref<number>

async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}

const List = defineComponent({
  props: { name: { type: String, required: true } },
  setup(props) {
    const refresh = useApplicationRefresh()
    watch(
      () => refresh.value,
      () => {
        loud[props.name] = (loud[props.name] || 0) + 1
      }
    )
    useRuntimeDataRefresh({
      interest: () => interests[props.name],
      refresh: () => {
        quiet[props.name] = (quiet[props.name] || 0) + 1
        if (hold) return new Promise<void>(resolve => pending.push(resolve))
      }
    })
    return () => h('button', { 'data-list': props.name, onClick: () => refresh.value++ }, props.name)
  }
})

function mount(first: string) {
  const current = ref(first)
  signal = ref(0)
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(
    defineComponent({
      setup() {
        provide(applicationRefreshKey, signal)
        return () =>
          h(KeptPages, { pageKey: current.value, max: 5 }, () => h(List, { key: current.value, name: current.value }))
      }
    })
  )
  app.mount(host)
  return current
}

beforeEach(() => {
  for (const record of [quiet, loud, interests]) for (const name of Object.keys(record)) delete record[name]
  pending = []
  hold = false
  runtimeDataPolicy.refreshOnResume = true
  interests.a = { applicationId: 'app', objectIds: ['voucher'], recordIds: ['1', '2'] }
  interests.b = { applicationId: 'app', objectIds: ['flow'] }
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  runtimeDataPolicy.refreshOnResume = true
})

describe('runtime data invalidation', () => {
  it('quietly reloads only the visible subscribers the change is about', async () => {
    mount('a')
    await flush()
    invalidateRuntimeData({ applicationId: 'other', objectId: 'voucher' })
    invalidateRuntimeData({ applicationId: 'app', objectId: 'flow' })
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher', recordIds: ['9'] })
    await flush()
    expect(quiet.a).toBeUndefined()

    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher', recordIds: ['2', '9'] })
    await flush()
    expect(quiet.a).toBe(1)
    // 没指明记录 = 该对象任意记录；没指明对象 = 该应用任意对象
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    invalidateRuntimeData({ applicationId: 'app' })
    await flush()
    expect(quiet.a).toBe(3)
    expect(openRuntimeApplications()).toEqual(['app'])
  })

  it('merges a burst of changes into one reload and never overlaps reloads', async () => {
    mount('a')
    await flush()
    // 重取立刻返回时，一拍里的五次失效也只取一次
    for (let index = 0; index < 5; index++) invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    expect(quiet.a).toBe(1)
    quiet.a = 0
    hold = true
    for (let index = 0; index < 5; index++) invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    expect(quiet.a).toBe(1)
    // 上一次还没回来又变了：等它回来后再取一次
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    expect(quiet.a).toBe(1)
    pending.shift()!()
    await flush()
    expect(quiet.a).toBe(2)
  })

  it('skips a subscriber that has nothing to reload right now', async () => {
    mount('a')
    await flush()
    interests.a = undefined
    invalidateRuntimeData({ applicationId: 'app' })
    await flush()
    expect(quiet.a).toBeUndefined()
  })

  it('only marks a background page stale and reloads it when it returns', async () => {
    runtimeDataPolicy.refreshOnResume = false
    const current = mount('a')
    await flush()
    current.value = 'b'
    await flush()
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    expect(quiet.a).toBeUndefined()

    current.value = 'a'
    await flush()
    expect(quiet.a).toBe(1)
    // 没被标过期的页面回到前台不重取
    current.value = 'b'
    await flush()
    expect(quiet.b).toBeUndefined()
  })

  it('does not start the follow-up reload once the page has gone to the background', async () => {
    runtimeDataPolicy.refreshOnResume = false
    const current = mount('a')
    await flush()
    hold = true
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    // 第一次还没回来又变了一次，随后页面被切到后台
    invalidateRuntimeData({ applicationId: 'app', objectId: 'voucher' })
    await flush()
    current.value = 'b'
    await flush()
    pending.shift()!()
    await flush()
    expect(quiet.a).toBe(1)

    // 欠着的那一次等页面回来再取
    current.value = 'a'
    await flush()
    expect(quiet.a).toBe(2)
  })

  it('reloads every subscriber of a page returning to the foreground while push is not live', async () => {
    const current = mount('a')
    await flush()
    current.value = 'b'
    await flush()
    expect(quiet).toEqual({})
    current.value = 'a'
    await flush()
    expect(quiet).toEqual({ a: 1 })
    current.value = 'b'
    await flush()
    expect(quiet).toEqual({ a: 1, b: 1 })
  })

  it('stops reloading a subscriber once it is gone', async () => {
    const current = mount('a')
    await flush()
    app!.unmount()
    app = undefined
    invalidateRuntimeData({ applicationId: 'app' })
    await flush()
    expect(quiet.a).toBeUndefined()
    expect(openRuntimeApplications()).toEqual([])
    expect(current.value).toBe('a')
  })
})

describe('the in-application refresh signal on kept pages', () => {
  it('reaches the visible page at once and leaves background pages alone until they return', async () => {
    runtimeDataPolicy.refreshOnResume = false
    const current = mount('a')
    await flush()
    current.value = 'b'
    await flush()
    // b 页面里保存了一条记录
    host.querySelector<HTMLElement>('[data-list="b"]')!.click()
    await flush()
    expect(signal.value).toBe(1)
    expect(loud).toEqual({ b: 1 })
    expect(quiet).toEqual({})

    // a 错过了这次信号：回到前台时静默补取，不走响亮刷新
    current.value = 'a'
    await flush()
    expect(loud).toEqual({ b: 1 })
    expect(quiet).toEqual({ a: 1 })

    // 之后 a 在前台，信号照常到达，且发出的信号总是往前走
    host.querySelector<HTMLElement>('[data-list="a"]')!.click()
    await flush()
    expect(signal.value).toBe(2)
    expect(loud).toEqual({ a: 1, b: 1 })
  })
})
