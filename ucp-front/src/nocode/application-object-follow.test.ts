// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, effectScope, h, nextTick, ref, type App } from 'vue'
import {
  announceFollowResult,
  followDisplay,
  followDirtyHint,
  followSuspendedHint,
  followSwitchHint,
  useApplicationObjectFollow
} from './application-object-follow'
import ObjectFollowCell from '@/views/nocode/application/components/ObjectFollowCell.vue'
import type { ApplicationDetail, FollowResult, FollowRun, ObjectFollow } from '@/types/nocode/application'

const mocks = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn(), success: vi.fn() }))
vi.mock('ant-design-vue', () => ({
  Modal: { confirm: mocks.confirm },
  message: { warning: mocks.warning, success: mocks.success }
}))
vi.mock('@/nocode/data-center', () => ({ errorMessage: (error: Error) => error.message }))

const follow = (patch: Partial<ObjectFollow> = {}): ObjectFollow => ({
  objectId: '2001',
  enabled: true,
  state: 'FOLLOWING',
  pinnedVersion: 11,
  latestVersion: 11,
  pendingVersion: null,
  pendingCode: null,
  pendingReason: null,
  followedAt: null,
  revision: 0,
  ...patch
})
const behind = () => follow({ latestVersion: 12 })
const pending = () =>
  follow({
    latestVersion: 12,
    state: 'PENDING',
    pendingVersion: 12,
    pendingCode: 'IN_FLIGHT',
    pendingReason:
      '应用里还有 1 条流程审批、0 条办理申请没有完结（审批中、审批通过后还没生效、或生效失败还没放弃的都算）。全部完结后系统会自动跟上，也可以点“重试”；生效失败的申请要由申请人放弃或重新提交。'
  })
const off = () => follow({ enabled: false, latestVersion: 12, revision: 1 })
const detail = (revision: number): ApplicationDetail =>
  ({
    application: { id: '3054', revision, publishedVersion: 62 },
    draft: { objects: [{ objectId: '2001', versionNo: 12, checksum: 'v12' }], resources: [] },
    issues: []
  }) as unknown as ApplicationDetail
const ran = (patch: Partial<FollowRun> = {}): FollowRun => ({
  outcome: 'FOLLOWED',
  follow: follow({ pinnedVersion: 12, latestVersion: 12 }),
  application: detail(8),
  draftSynced: true,
  draftReason: null,
  ...patch
})
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function must<T>(value: T | null | undefined, what: string): T {
  if (value == null) throw new Error(`${what} 不存在`)
  return value
}
const settle = async () => {
  for (let i = 0; i < 6; i++) await Promise.resolve()
}

function create(rows: ObjectFollow[] = [behind()]) {
  const api = {
    objectFollows: vi.fn(async (_id: string) => rows),
    setObjectFollow: vi.fn(async (_body: unknown) => ran()),
    runObjectFollow: vi.fn(async (_body: unknown) => ran())
  }
  const state = { id: ref('3054'), dirty: ref(false), canEdit: ref(true), suspended: ref(false), capture: true }
  const accept = vi.fn(),
    onError = vi.fn()
  const scope = effectScope()
  const module = must(
    scope.run(() =>
      useApplicationObjectFollow({
        api,
        applicationId: () => state.id.value,
        dirty: () => state.dirty.value,
        canEdit: () => state.canEdit.value,
        suspended: () => state.suspended.value,
        capture: () => state.capture,
        accept,
        onError
      })
    ),
    '跟随状态模块'
  )
  return { api, state, accept, onError, scope, module }
}
let warn: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.resetAllMocks()
  warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
})
afterEach(() => warn.mockRestore())

describe('自动跟随的四种显示状态', () => {
  it('开且已是最新 / 开但落后 / 开且待处理 / 关', () => {
    expect(followDisplay(follow())).toBe('CURRENT')
    expect(followDisplay(behind())).toBe('BEHIND')
    expect(followDisplay(pending())).toBe('PENDING')
    expect(followDisplay(off())).toBe('OFF')
  })
  it('读不到最新发布版本时不当作落后', () => {
    expect(followDisplay(follow({ latestVersion: null }))).toBe('CURRENT')
  })
  it('现有的「同步最新版本」按钮：开着跟随时隐藏，关着或状态读不到时照常显示', async () => {
    const { module } = create([behind(), { ...off(), objectId: '2002' }])
    expect(module.showSync('2001')).toBe(true)
    await module.refresh()
    expect(module.available.value).toBe(true)
    expect(module.showSync('2001')).toBe(false)
    expect(module.showSync('2002')).toBe(true)
    // 草稿里刚引用、还没保存的对象没有状态行，按「跟随关着」处理。
    expect(module.showSync('9999')).toBe(true)
  })
  it('应用不是启用状态（被暂停 / 已停用）：开着跟随的行也照常显示「同步最新版本」，恢复应用不用先关开关', async () => {
    const { module, state } = create([behind(), pending()].map((row, i) => ({ ...row, objectId: String(2001 + i) })))
    await module.refresh()
    expect(module.suspended()).toBe(false)
    expect(module.showSync('2001')).toBe(false)
    expect(module.showSync('2002')).toBe(false)
    state.suspended.value = true
    expect(module.suspended()).toBe(true)
    expect(module.showSync('2001')).toBe(true)
    expect(module.showSync('2002')).toBe(true)
    // 重新启用后回到「开着就隐藏」
    state.suspended.value = false
    expect(module.showSync('2001')).toBe(false)
  })
  it('不传 suspended 的调用方按启用处理', async () => {
    const api = { objectFollows: vi.fn(async () => [behind()]), setObjectFollow: vi.fn(), runObjectFollow: vi.fn() }
    const scope = effectScope()
    const module = must(
      scope.run(() =>
        useApplicationObjectFollow({
          api,
          applicationId: () => '3054',
          dirty: () => false,
          canEdit: () => true,
          capture: () => true,
          accept: vi.fn()
        })
      ),
      '跟随状态模块'
    )
    await module.refresh()
    expect(module.suspended()).toBe(false)
    expect(module.showSync('2001')).toBe(false)
    scope.stop()
  })
})

describe('跟随状态的读取', () => {
  it('读取失败（含后端还没有这个接口）：整列不可用，不抛错、不弹错，只留一条控制台警告', async () => {
    const { module, api, onError } = create()
    api.objectFollows.mockRejectedValueOnce(new Error('404'))
    await expect(module.refresh()).resolves.toBeUndefined()
    expect(module.available.value).toBe(false)
    expect(module.unavailable.value).toBe(true)
    expect(module.follows).toEqual({})
    expect(module.showSync('2001')).toBe(true)
    expect(onError).not.toHaveBeenCalled()
    expect(mocks.warning).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalledTimes(1)
  })
  it('读取中不算失败（列在表格挂载时就在）；失败后再读取成功，列恢复', async () => {
    const { module, api } = create()
    const slow = deferred<ObjectFollow[]>()
    api.objectFollows.mockReturnValueOnce(slow.promise)
    const first = module.refresh()
    expect(module.unavailable.value).toBe(false)
    slow.reject(new Error('404'))
    await first
    expect(module.unavailable.value).toBe(true)
    await module.refresh()
    expect(module.unavailable.value).toBe(false)
    expect(module.available.value).toBe(true)
  })
  it('先发后到的旧结果不能覆盖新结果', async () => {
    const { module, api } = create()
    const old = deferred<ObjectFollow[]>()
    api.objectFollows.mockReturnValueOnce(old.promise).mockResolvedValueOnce([follow({ pinnedVersion: 12 })])
    const first = module.refresh()
    await module.refresh()
    old.resolve([follow({ pinnedVersion: 3 })])
    await first
    expect(module.follows['2001']?.pinnedVersion).toBe(12)
  })
  it('先发的旧请求后失败，也不能把已读到的状态清掉', async () => {
    const { module, api } = create()
    const old = deferred<ObjectFollow[]>()
    api.objectFollows.mockReturnValueOnce(old.promise).mockResolvedValueOnce([behind()])
    const first = module.refresh()
    await module.refresh()
    old.reject(new Error('超时'))
    await first
    expect(module.available.value).toBe(true)
    expect(module.follows['2001']).toBeDefined()
  })
  it('切换应用后忽略仍在途的结果，并清空上一个应用的状态', async () => {
    const { module, api, state } = create()
    await module.refresh()
    const old = deferred<ObjectFollow[]>()
    api.objectFollows.mockReturnValueOnce(old.promise)
    const inFlight = module.refresh()
    state.id.value = '4000'
    expect(module.available.value).toBe(false)
    expect(module.follows).toEqual({})
    old.resolve([behind()])
    await inFlight
    expect(module.available.value).toBe(false)
    expect(module.follows).toEqual({})
  })
})

describe('拨开关与立即跟随', () => {
  it('拨到关：先确认（文案逐字），确认后才发请求并带上开关修订号', async () => {
    const { module, api } = create([follow({ revision: 3 })])
    await module.refresh()
    module.toggle('2001', false)
    expect(api.setObjectFollow).not.toHaveBeenCalled()
    const dialog = must(mocks.confirm.mock.calls[0], '确认框')[0]
    expect(dialog.title).toBe('关闭自动跟随？')
    expect(dialog.content).toBe(
      '关闭后本应用固定在 V11，需要手动同步并发布。对象做不兼容的改动（例如停用字段）时，本应用可能被要求暂停。'
    )
    expect(dialog.okText).toBe('关闭')
    expect(dialog.cancelText).toBe('保持开启')
    await dialog.onOk()
    expect(api.setObjectFollow).toHaveBeenCalledWith({
      applicationId: '3054',
      objectId: '2001',
      enabled: false,
      expectedRevision: 3
    })
  })
  it('拨到开：不确认，直接发请求', async () => {
    const { module, api } = create([off()])
    await module.refresh()
    module.toggle('2001', true)
    await settle()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(api.setObjectFollow).toHaveBeenCalledWith({
      applicationId: '3054',
      objectId: '2001',
      enabled: true,
      expectedRevision: 1
    })
  })
  it('操作成功：把返回的应用详情交给工作台，再重新读取跟随状态', async () => {
    const { module, api, accept } = create()
    await module.refresh()
    const result = ran()
    api.runObjectFollow.mockResolvedValueOnce(result)
    api.objectFollows.mockResolvedValueOnce([follow({ pinnedVersion: 12, latestVersion: 12, followedAt: 1 })])
    await module.run('2001')
    expect(api.runObjectFollow).toHaveBeenCalledWith({ applicationId: '3054', objectId: '2001' })
    expect(accept).toHaveBeenCalledWith(result.application)
    expect(api.objectFollows).toHaveBeenCalledTimes(2)
    expect(module.follows['2001']?.followedAt).toBe(1)
    expect(module.acting.value).toBe('')
    expect(mocks.warning).not.toHaveBeenCalled()
  })
  it('工作台有未保存修改时三个操作都不发请求，并给出原因', async () => {
    const { module, api, state } = create([behind()])
    await module.refresh()
    state.dirty.value = true
    expect(module.blockedReason()).toBe('请先保存或放弃当前修改')
    expect(followDirtyHint).toBe('请先保存或放弃当前修改')
    expect(module.canOperate()).toBe(false)
    module.toggle('2001', false)
    await module.run('2001')
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(api.setObjectFollow).not.toHaveBeenCalled()
    expect(api.runObjectFollow).not.toHaveBeenCalled()
    state.dirty.value = false
    expect(module.blockedReason()).toBe('')
    expect(module.canOperate()).toBe(true)
  })
  it('没有应用编辑权限时不发请求', async () => {
    const { module, api, state } = create([behind()])
    await module.refresh()
    state.canEdit.value = false
    module.toggle('2001', false)
    await module.run('2001')
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(api.runObjectFollow).not.toHaveBeenCalled()
  })
  it('工作台此刻不能接收新的应用详情时不发请求', async () => {
    const { module, api, state } = create([behind()])
    await module.refresh()
    state.capture = false
    await module.run('2001')
    expect(api.runObjectFollow).not.toHaveBeenCalled()
  })
  it('应用已跟上但草稿没同步：提示文案逐字，并对该行恢复显示同步按钮', async () => {
    const { module, api } = create()
    await module.refresh()
    expect(module.showSync('2001')).toBe(false)
    api.runObjectFollow.mockResolvedValueOnce(ran({ draftSynced: false, draftReason: '草稿里有已停用的字段' }))
    await module.run('2001')
    expect(mocks.warning).toHaveBeenCalledWith(
      '应用已跟上，但草稿没能同步：草稿里有已停用的字段。请手工同步并修正后保存。'
    )
    expect(module.showSync('2001')).toBe(true)
    // 之后草稿同步上了，按钮再次隐藏。
    api.runObjectFollow.mockResolvedValueOnce(ran())
    await module.run('2001')
    expect(module.showSync('2001')).toBe(false)
  })
  it('操作失败：把原因交给工作台显示，状态解除占用，不交应用详情', async () => {
    const { module, api, accept, onError } = create()
    await module.refresh()
    api.runObjectFollow.mockRejectedValueOnce(new Error('请先打开自动跟随'))
    await module.run('2001')
    expect(onError).toHaveBeenCalledWith('请先打开自动跟随')
    expect(accept).not.toHaveBeenCalled()
    expect(module.acting.value).toBe('')
  })
  it('操作失败后重新读取跟随状态，下一次操作带上最新的开关修订号', async () => {
    const { module, api, onError } = create([off()])
    await module.refresh()
    api.setObjectFollow.mockRejectedValueOnce(new Error('自动跟随开关已被修改，请刷新后重试'))
    api.objectFollows.mockResolvedValueOnce([{ ...off(), revision: 5 }])
    module.toggle('2001', true)
    await settle()
    expect(onError).toHaveBeenCalledWith('自动跟随开关已被修改，请刷新后重试')
    expect(module.follows['2001']?.revision).toBe(5)
    expect(module.acting.value).toBe('')
    module.toggle('2001', true)
    await settle()
    expect(api.setObjectFollow).toHaveBeenLastCalledWith({
      applicationId: '3054',
      objectId: '2001',
      enabled: true,
      expectedRevision: 5
    })
  })
  it('一个操作在途时不再发第二个', async () => {
    const { module, api } = create()
    await module.refresh()
    const slow = deferred<FollowRun>()
    api.runObjectFollow.mockReturnValueOnce(slow.promise)
    const first = module.run('2001')
    expect(module.acting.value).toBe('2001')
    await module.run('2001')
    expect(api.runObjectFollow).toHaveBeenCalledTimes(1)
    slow.resolve(ran())
    await first
    expect(module.acting.value).toBe('')
  })
  it('切换应用后，在途操作的结果被忽略：不交应用详情、不报错', async () => {
    const { module, api, state, accept, onError } = create()
    await module.refresh()
    const slow = deferred<FollowRun>()
    api.runObjectFollow.mockReturnValueOnce(slow.promise)
    const inFlight = module.run('2001')
    state.id.value = '4000'
    slow.resolve(ran())
    await inFlight
    expect(accept).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    expect(module.follows).toEqual({})
  })
})

describe('跟随单元格', () => {
  let app: App | undefined, host: HTMLDivElement
  const flush = async () => {
    for (let i = 0; i < 4; i++) {
      await Promise.resolve()
      await nextTick()
    }
  }
  function mount(props: Record<string, unknown>) {
    const onToggle = vi.fn(),
      onRun = vi.fn()
    app = createApp(() => h(ObjectFollowCell, { ...props, onToggle, onRun }))
    app.component(
      'ATooltip',
      defineComponent({
        props: ['title'],
        setup:
          (p, { slots }) =>
          () =>
            h('span', { 'data-tip': p.title || '' }, slots.default?.())
      })
    )
    app.component(
      'ASwitch',
      defineComponent({
        props: ['checked', 'disabled'],
        emits: ['change'],
        setup:
          (p, { emit }) =>
          () =>
            h('button', {
              role: 'switch',
              'aria-checked': String(!!p.checked),
              disabled: !!p.disabled,
              onClick: () => emit('change', !p.checked)
            })
      })
    )
    app.component(
      'ATag',
      defineComponent({
        props: ['color'],
        setup:
          (p, { slots }) =>
          () =>
            h('em', { 'data-color': p.color || '' }, slots.default?.())
      })
    )
    app.component(
      'AButton',
      defineComponent({
        props: ['disabled'],
        setup:
          (p, { slots }) =>
          () =>
            h('button', { type: 'button', disabled: !!p.disabled }, slots.default?.())
      })
    )
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    return { onToggle, onRun }
  }
  const toggler = () => must(host.querySelector<HTMLButtonElement>('[role="switch"]'), '开关')
  const tags = () => Array.from(host.querySelectorAll('em')).map(el => el.textContent?.trim())
  const buttons = () =>
    Array.from(host.querySelectorAll<HTMLButtonElement>('button:not([role="switch"])')).map(b => b.textContent?.trim())
  afterEach(() => {
    app?.unmount()
    app = undefined
    host?.remove()
  })
  it('开且已是最新：只有开着的开关，悬停说明逐字', async () => {
    mount({ follow: follow() })
    await flush()
    expect(toggler().getAttribute('aria-checked')).toBe('true')
    expect(must(toggler().closest('[data-tip]'), '开关提示').getAttribute('data-tip')).toBe(
      '对象发布新版本后，本应用自动同步并发布，不需要手动操作。'
    )
    expect(followSwitchHint).toBe('对象发布新版本后，本应用自动同步并发布，不需要手动操作。')
    expect(tags()).toEqual([])
    expect(buttons()).toEqual([])
  })
  it('开但落后：「待跟随」+「立即跟随」', async () => {
    const { onRun } = mount({ follow: behind() })
    await flush()
    expect(tags()).toEqual(['待跟随'])
    expect(buttons()).toEqual(['立即跟随'])
    must(host.querySelector<HTMLButtonElement>('button:not([role="switch"])'), '立即跟随').click()
    expect(onRun).toHaveBeenCalledTimes(1)
  })
  it('应用不是启用状态：落后或待处理的行不出「立即跟随 / 重试」，只说明不自动跟随（悬停文案逐字）；开关照常', async () => {
    for (const row of [behind(), pending()]) {
      const { onRun } = mount({ follow: row, suspended: true })
      await flush()
      expect(toggler().getAttribute('aria-checked')).toBe('true')
      expect(tags()).toEqual(['应用停用中，不自动跟随'])
      expect(buttons()).toEqual([])
      expect(
        must(must(host.querySelector('em'), '状态标签').closest('[data-tip]'), '说明').getAttribute('data-tip')
      ).toBe('应用已停用，自动跟随不处理已停用的应用。请点这一行的“同步最新版本”，调整配置并保存，然后“发布并启用”。')
      expect(onRun).not.toHaveBeenCalled()
      app?.unmount()
      app = undefined
      host.remove()
    }
    expect(followSuspendedHint).toBe(
      '应用已停用，自动跟随不处理已停用的应用。请点这一行的“同步最新版本”，调整配置并保存，然后“发布并启用”。'
    )
    // 已是最新或开关关着的行不受影响
    mount({ follow: follow(), suspended: true })
    await flush()
    expect(tags()).toEqual([])
    expect(buttons()).toEqual([])
  })
  it('开且待处理：橙色「跟随待处理」（悬停显示原因）+「重试」', async () => {
    const { onRun } = mount({ follow: pending() })
    await flush()
    const tag = must(host.querySelector('em'), '状态标签')
    expect(tag.textContent?.trim()).toBe('跟随待处理')
    expect(tag.getAttribute('data-color')).toBe('orange')
    expect(must(tag.closest('[data-tip]'), '原因提示').getAttribute('data-tip')).toBe(pending().pendingReason)
    expect(buttons()).toEqual(['重试'])
    must(host.querySelector<HTMLButtonElement>('button:not([role="switch"])'), '重试').click()
    expect(onRun).toHaveBeenCalledTimes(1)
  })
  it('关：只有关着的开关，没有标签和按钮，也没有悬停说明', async () => {
    const { onToggle } = mount({ follow: off() })
    await flush()
    expect(toggler().getAttribute('aria-checked')).toBe('false')
    expect(must(toggler().closest('[data-tip]'), '开关提示').getAttribute('data-tip')).toBe('')
    expect(tags()).toEqual([])
    expect(buttons()).toEqual([])
    toggler().click()
    expect(onToggle).toHaveBeenCalledWith(true)
  })
  it('有未保存修改：开关和按钮都禁用，悬停「请先保存或放弃当前修改」', async () => {
    mount({ follow: behind(), blockedReason: '请先保存或放弃当前修改' })
    await flush()
    expect(toggler().disabled).toBe(true)
    const action = must(host.querySelector<HTMLButtonElement>('button:not([role="switch"])'), '立即跟随')
    expect(action.disabled).toBe(true)
    expect(must(toggler().closest('[data-tip]'), '开关提示').getAttribute('data-tip')).toBe('请先保存或放弃当前修改')
    expect(must(action.closest('[data-tip]'), '按钮提示').getAttribute('data-tip')).toBe('请先保存或放弃当前修改')
  })
  it('没有应用编辑权限：开关只读', async () => {
    mount({ follow: pending(), readonly: true })
    await flush()
    expect(toggler().disabled).toBe(true)
    expect(must(host.querySelector<HTMLButtonElement>('button:not([role="switch"])'), '重试').disabled).toBe(true)
  })
  it('没有状态（对象还没保存进应用）时什么都不显示', async () => {
    mount({})
    await flush()
    expect(host.textContent).toBe('')
    expect(host.querySelector('[role="switch"]')).toBeNull()
  })
})

describe('对象发布成功后的跟随提示', () => {
  const result = (applicationName: string, outcome: FollowResult['outcome']): FollowResult => ({
    applicationId: applicationName,
    applicationName,
    outcome,
    fromVersion: 11,
    toVersion: 12,
    applicationVersion: 62,
    reason: null
  })
  const notify = () => ({ success: vi.fn(), warning: vi.fn() })
  it('有应用没跟上：警告，文案逐字', async () => {
    const to = notify()
    await announceFollowResult(
      async () => [result('资金管理', 'PENDING'), result('合同', 'FOLLOWED'), result('工程', 'PENDING')],
      to
    )
    expect(to.warning).toHaveBeenCalledWith(
      '对象已发布。2 个应用暂时没跟上：资金管理、工程。原因见各应用的“已引用对象”。'
    )
    expect(to.success).not.toHaveBeenCalled()
  })
  it('全部跟上：成功提示，文案逐字', async () => {
    const to = notify()
    await announceFollowResult(async () => [result('资金管理', 'FOLLOWED'), result('合同', 'FOLLOWED')], to)
    expect(to.success).toHaveBeenCalledWith('对象已发布，2 个应用已自动跟上。')
    expect(to.warning).not.toHaveBeenCalled()
  })
  it('没有任何跟随：不出提示', async () => {
    const to = notify()
    await announceFollowResult(async () => [], to)
    expect(to.success).not.toHaveBeenCalled()
    expect(to.warning).not.toHaveBeenCalled()
  })
  it('接口失败：不抛错、不出提示，只留一条控制台警告', async () => {
    const to = notify()
    await expect(
      announceFollowResult(async () => {
        throw new Error('404')
      }, to)
    ).resolves.toBeUndefined()
    expect(to.success).not.toHaveBeenCalled()
    expect(to.warning).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalledTimes(1)
  })
})
