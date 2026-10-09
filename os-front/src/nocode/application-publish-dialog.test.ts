// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import ApplicationPublishDialog from '@/views/nocode/application/components/ApplicationPublishDialog.vue'
import type { ApplicationDetail, PublishedObject } from '@/types/nocode/application'
import type { LinkagePreviewPage, LinkageSyncField, LinkageSyncOverview } from '@/types/nocode/linkage-sync'

const api = vi.hoisted(() => ({
  sharing: vi.fn(),
  publish: vi.fn(),
  publishAndEnable: vi.fn(),
  hasPermission: vi.fn(),
  linkageOverview: vi.fn(),
  linkagePreview: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ applications: api, hasPermission: api.hasPermission })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', [slots.formItems?.(), slots.footer?.()]) : null
    })
  }
})
vi.mock('@/views/nocode/components/ObjectSharingPanel.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['applicationId', 'objectId'],
      emits: ['closed'],
      setup:
        (props, { emit }) =>
        () =>
          h('button', { onClick: () => emit('closed') }, `保存共享授权 ${props.applicationId}/${props.objectId}`)
    })
  }
})

const detail = {
  application: { id: '2210', name: '资产管理', revision: 3 },
  draft: { objects: [{ objectId: '4442', versionNo: 1, checksum: 'v1' }], resources: [] }
} as unknown as ApplicationDetail
const objects = {
  '4442': { objectId: '4442', definition: { objectName: '资产分类', fields: [] } }
} as unknown as Record<string, PublishedObject>
const grants = [
  { objectId: '4442', applicationId: '2210', permission: { actions: ['READ'], readFields: [], writeFields: [] } }
]
const overview = (patch: Partial<LinkageSyncOverview> = {}): LinkageSyncOverview => ({
  applicationId: '2210',
  basis: 'DRAFT',
  applicationVersion: null,
  fields: [],
  removed: [],
  ...patch
})
const syncField = (patch: Partial<LinkageSyncField> = {}): LinkageSyncField => ({
  targetObjectId: '5398',
  targetObjectName: '资金流水',
  targetObjectVersion: 33,
  targetFieldId: 'f1',
  targetFieldName: '凭证状态',
  sourceObjectId: '5391',
  sourceObjectName: '会计凭证录入',
  anchor: 'CURRENT_RECORD',
  anchorFieldId: 'a1',
  anchorFieldName: '资金流水',
  signature: 'sig-1',
  change: 'NEW',
  divergent: [],
  ...patch
})
const previewPage = (patch: Partial<LinkagePreviewPage> = {}): LinkagePreviewPage => ({
  signature: 'sig-1',
  total: null,
  scanned: 0,
  unchanged: 0,
  willFill: 0,
  willClear: 0,
  willChange: 0,
  failedCount: 0,
  failed: [],
  nextCursor: null,
  done: false,
  ...patch
})
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => {
    resolve = yes
  })
  return { promise, resolve }
}
let app: App, host: HTMLDivElement
const published = vi.fn()
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (text: string) =>
  Array.from(host.querySelectorAll('button')).find(node => node.textContent?.trim() === text)!

async function mount() {
  const state = reactive({ open: true, detail, objects })
  app = createApp(() =>
    h(ApplicationPublishDialog, {
      ...state,
      onCancel: () => {
        state.open = false
      },
      onPublished: published
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  app.component('ASpace', plain)
  app.component('AFormItem', plain)
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup: props => () => h('div', `${props.message || ''} ${props.description || ''}`)
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h('textarea', {
            value: props.value,
            onInput: (event: Event) => {
              emit('update:value', (event.target as HTMLTextAreaElement).value)
              emit('change')
            }
          })
    })
  )
  app.component(
    'AModal',
    defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', slots.default?.()) : null
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return state
}
async function fillReason() {
  const input = host.querySelector('textarea')!
  input.value = '新增资产分类'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.hasPermission.mockReturnValue(true)
  api.sharing.mockReset().mockResolvedValue(grants)
  api.publish.mockReset().mockResolvedValue(detail)
  api.publishAndEnable.mockReset().mockResolvedValue(detail)
  // 缺省：应用里没有任何自动更新字段。
  api.linkageOverview.mockReset().mockResolvedValue(overview())
  api.linkagePreview.mockReset()
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('发布前检查交互', () => {
  it('普通停用应用可发布并启用整个应用，不套用回收恢复的人工保存要求', async () => {
    const state = await mount()
    state.detail = {
      ...detail,
      application: { ...detail.application, status: 'DISABLED', recoveryPending: false, recoveryNeedsEdit: true }
    }
    await flush()
    expect(host.textContent).toContain('启用整个应用')
    expect(host.textContent).not.toContain('请先人工编辑并保存应用草稿')
    await fillReason()
    expect(button('发布并启用').disabled).toBe(false)
    button('发布并启用').click()
    await flush()
    expect(api.publishAndEnable).toHaveBeenCalledWith({ id: '2210', expectedRevision: 3, reason: '新增资产分类' })
    expect(api.publish).not.toHaveBeenCalled()
  })
  it.each(['nocode:app:publish', 'nocode:app:manage'])('普通停用应用缺少 %s 时禁止发布启用', async permission => {
    api.hasPermission.mockImplementation(value => value !== permission)
    const state = await mount()
    state.detail = { ...detail, application: { ...detail.application, status: 'DISABLED', recoveryPending: false } }
    await flush()
    await fillReason()
    expect(button('发布并启用').disabled).toBe(true)
    button('发布并启用').click()
    await flush()
    expect(api.publishAndEnable).not.toHaveBeenCalled()
    expect(api.publish).not.toHaveBeenCalled()
  })
  it('普通停用应用发布启用失败保留说明，不能误报已启用', async () => {
    const state = await mount()
    state.detail = { ...detail, application: { ...detail.application, status: 'DISABLED', recoveryPending: false } }
    api.publishAndEnable.mockRejectedValueOnce(new Error('对象版本仍不兼容，请先同步'))
    await flush()
    await fillReason()
    button('发布并启用').click()
    await flush()
    expect(host.textContent).toContain('对象版本仍不兼容')
    expect(host.querySelector('textarea')?.value).toBe('新增资产分类')
    expect(published).not.toHaveBeenCalled()
    expect(api.publish).not.toHaveBeenCalled()
  })
  it('恢复后尚未人工保存时阻止发布启用', async () => {
    const state = await mount()
    state.detail = { ...detail, application: { ...detail.application, recoveryPending: true, recoveryNeedsEdit: true } }
    await flush()
    await fillReason()
    expect(button('发布并启用').disabled).toBe(true)
    expect(host.textContent).toContain('请先人工编辑并保存应用草稿')
    expect(api.publishAndEnable).not.toHaveBeenCalled()
  })
  it('恢复后使用发布启用接口并保留当前修订，不调用普通发布', async () => {
    const state = await mount()
    state.detail = {
      ...detail,
      application: { ...detail.application, recoveryPending: true, recoveryNeedsEdit: false }
    }
    await flush()
    await fillReason()
    button('发布并启用').click()
    await flush()
    expect(api.publishAndEnable).toHaveBeenCalledWith({ id: '2210', expectedRevision: 3, reason: '新增资产分类' })
    expect(api.publish).not.toHaveBeenCalled()
  })
  it('缺少应用管理权限时不能发布启用恢复应用', async () => {
    api.hasPermission.mockImplementation(permission => permission !== 'nocode:app:manage')
    const state = await mount()
    state.detail = {
      ...detail,
      application: { ...detail.application, recoveryPending: true, recoveryNeedsEdit: false }
    }
    await flush()
    expect(button('发布并启用').disabled).toBe(true)
    expect(host.textContent).toContain('发布并启用需要应用管理权限')
  })
  it('检查失败不能发布，重新检查成功后恢复按钮', async () => {
    api.sharing.mockRejectedValueOnce(new Error('网络暂不可用'))
    await mount()
    expect(host.textContent).toContain('未能读取最新对象授权')
    expect(button('发布应用').disabled).toBe(true)
    button('重新检查').click()
    await flush()
    expect(button('发布应用').disabled).toBe(false)
    expect(host.textContent).toContain('对象共享授权检查通过')
  })
  it('配置入口选中当前应用和对象，保存返回后保留发布说明并刷新检查', async () => {
    api.sharing.mockResolvedValueOnce([])
    await mount()
    await fillReason()
    expect(button('发布应用').disabled).toBe(true)
    button('去配置共享授权').click()
    await flush()
    button('保存共享授权 2210/4442').click()
    await flush()
    expect(host.querySelector('textarea')?.value).toBe('新增资产分类')
    expect(button('发布应用').disabled).toBe(false)
    expect(api.sharing).toHaveBeenCalledTimes(2)
  })
  it('无管理权限时显示管理员处理说明，不提供编辑入口', async () => {
    api.hasPermission.mockReturnValue(false)
    api.sharing.mockResolvedValueOnce([])
    await mount()
    expect(host.textContent).toContain('你没有共享授权管理权限')
    expect(button('去配置共享授权')).toBeUndefined()
    expect(button('返回已引用对象查看权限')).toBeDefined()
  })
  it('点击发布再次读取最新授权，已撤销时阻止提交', async () => {
    api.sharing.mockResolvedValueOnce(grants).mockResolvedValueOnce([{ ...grants[0], permission: null }])
    await mount()
    await fillReason()
    button('发布应用').click()
    await flush()
    expect(host.textContent).toContain('共享授权已被撤销')
    expect(api.publish).not.toHaveBeenCalled()
  })
  it('服务端拒绝后重新检查，显示并发撤权的修正入口', async () => {
    api.sharing
      .mockResolvedValueOnce(grants)
      .mockResolvedValueOnce(grants)
      .mockResolvedValueOnce([{ ...grants[0], permission: null }])
    api.publish.mockRejectedValueOnce(new Error('共享授权已被撤销'))
    await mount()
    await fillReason()
    button('发布应用').click()
    await flush()
    expect(api.publish).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('共享授权已被撤销')
    expect(button('去配置共享授权')).toBeDefined()
    expect(published).not.toHaveBeenCalled()
  })
  it('关闭后返回的旧检查结果不覆盖再次打开时的新结果', async () => {
    let finish: (value: unknown) => void = () => {}
    api.sharing.mockReturnValueOnce(
      new Promise(resolve => {
        finish = resolve
      })
    )
    const state = await mount()
    expect(button('发布应用').disabled).toBe(true)
    state.open = false
    await flush()
    state.open = true
    await flush()
    finish([])
    await flush()
    expect(host.textContent).toContain('对象共享授权检查通过')
    expect(button('发布应用').disabled).toBe(false)
  })
  it('发布成功使用当前修订号和原发布说明', async () => {
    await mount()
    await fillReason()
    button('发布应用').click()
    await flush()
    expect(api.publish).toHaveBeenCalledWith({ id: '2210', expectedRevision: 3, reason: '新增资产分类' })
    expect(published).toHaveBeenCalledWith(detail)
  })
})

// 第一期契约 9.3：生效点是应用发布，所以在发布对话框里预告「将更新 N 条」；预告不阻断发布。
describe('自动更新预告', () => {
  const section = () => host.querySelector('.publish-linkage')
  it('应用里没有自动更新字段时，这一块整段不出现，也不发预告请求', async () => {
    await mount()
    expect(api.linkageOverview).toHaveBeenCalledWith('2210', 'DRAFT')
    expect(section()).toBeNull()
    expect(host.textContent).not.toContain('自动更新')
    expect(api.linkagePreview).not.toHaveBeenCalled()
    expect(button('发布应用').disabled).toBe(false)
  })

  it('有新开或变更的字段：逐页预告到最后一页，显示将更新的条数与各项明细；没变的字段不预告', async () => {
    api.linkageOverview.mockResolvedValue(
      overview({
        fields: [
          syncField(),
          syncField({ targetFieldId: 'f2', targetFieldName: '凭证号', change: 'CHANGED', signature: 'sig-2' }),
          syncField({ targetFieldId: 'f3', targetFieldName: '摘要', change: 'UNCHANGED' })
        ]
      })
    )
    api.linkagePreview.mockImplementation(async (body: { targetFieldId: string; cursor: string | null }) => {
      if (body.targetFieldId === 'f2')
        return previewPage({ signature: 'sig-2', total: 5, scanned: 5, unchanged: 5, done: true })
      return body.cursor
        ? previewPage({
            scanned: 79,
            willFill: 60,
            willClear: 2,
            willChange: 3,
            failedCount: 1,
            unchanged: 13,
            done: true
          })
        : previewPage({ total: 279, scanned: 200, willFill: 200, nextCursor: 'c200' })
    })
    await mount()
    expect(section()?.textContent).toContain('本次发布将开启或变更 2 个自动更新字段')
    expect(section()?.textContent).toContain(
      '资金流水 · 凭证状态：将更新 265 条（由空变为有值 260 · 清空 2 · 值变化 3 · 无法求值 1）'
    )
    expect(section()?.textContent).toContain(
      '资金流水 · 凭证号：将更新 0 条（由空变为有值 0 · 清空 0 · 值变化 0 · 无法求值 0）'
    )
    expect(section()?.textContent).toContain('发布后请到「自动更新」里执行回填，存量记录才会更新。')
    expect(section()?.textContent).not.toContain('摘要')
    expect(api.linkagePreview.mock.calls.map(call => [call[0].targetFieldId, call[0].basis, call[0].cursor])).toEqual([
      ['f1', 'DRAFT', null],
      ['f1', 'DRAFT', 'c200'],
      ['f2', 'DRAFT', null]
    ])
  })

  it('预告还在进行时可以发布：按钮不等它，界面显示进度而不是条数', async () => {
    api.linkageOverview.mockResolvedValue(overview({ fields: [syncField()] }))
    const { promise, resolve: finish } = deferred<LinkagePreviewPage>()
    api.linkagePreview.mockReturnValueOnce(promise)
    await mount()
    expect(section()?.textContent).toContain('资金流水 · 凭证状态：正在预告')
    expect(section()?.textContent).not.toContain('将更新')
    expect(button('发布应用').disabled).toBe(false)
    await fillReason()
    button('发布应用').click()
    await flush()
    expect(api.publish).toHaveBeenCalledWith({ id: '2210', expectedRevision: 3, reason: '新增资产分类' })
    expect(published).toHaveBeenCalledWith(detail)
    finish(previewPage({ total: 1, scanned: 1, willFill: 1, done: true }))
    await flush()
  })

  it('预告失败不阻断发布：说明「预告未完成」，发布按钮照常可用', async () => {
    api.linkageOverview.mockResolvedValue(overview({ fields: [syncField()] }))
    api.linkagePreview.mockRejectedValue(new Error('网络暂不可用'))
    await mount()
    expect(section()?.textContent).toContain('资金流水 · 凭证状态：预告未完成（网络暂不可用）')
    expect(section()?.textContent).not.toContain('将更新')
    expect(button('发布应用').disabled).toBe(false)
    await fillReason()
    button('发布应用').click()
    await flush()
    expect(api.publish).toHaveBeenCalledTimes(1)
  })

  it('总览读不到时同样只说明「预告未完成」，不阻断发布', async () => {
    api.linkageOverview.mockRejectedValue(new Error('服务暂不可用'))
    await mount()
    expect(section()?.textContent).toContain('自动更新预告未完成：服务暂不可用')
    expect(button('发布应用').disabled).toBe(false)
  })

  it('其它应用还没同步到同一规则、以及不再自动更新的字段，各有一句说明', async () => {
    api.linkageOverview.mockResolvedValue(
      overview({
        fields: [
          syncField({
            change: 'UNCHANGED',
            divergent: [
              { applicationId: '9', applicationName: '出纳', reason: 'NO_RULE' },
              { applicationId: '10', applicationName: '资金日报', reason: 'DIFFERENT_RULE' }
            ]
          })
        ],
        removed: [
          { targetObjectId: '5398', targetObjectName: '资金流水', targetFieldId: 'f9', targetFieldName: '凭证号' }
        ]
      })
    )
    await mount()
    expect(section()?.textContent).toContain(
      '资金流水 · 凭证状态：以下应用还没有同步到同一规则，经它们保存「会计凭证录入」时不会按本规则更新：出纳、资金日报'
    )
    expect(section()?.textContent).toContain('以下字段不再自动更新，已有的值保留：资金流水 · 凭证号')
    // 没有新开或变更的字段：不显示「本次发布将开启或变更」，也不预告。
    expect(section()?.textContent).not.toContain('本次发布将开启或变更')
    expect(api.linkagePreview).not.toHaveBeenCalled()
  })

  it('关闭对话框后不再请求下一页', async () => {
    api.linkageOverview.mockResolvedValue(overview({ fields: [syncField()] }))
    const { promise, resolve: finish } = deferred<LinkagePreviewPage>()
    api.linkagePreview.mockReturnValueOnce(promise)
    const state = await mount()
    state.open = false
    await flush()
    finish(previewPage({ total: 900, scanned: 200, willFill: 200, nextCursor: 'c200' }))
    await flush()
    expect(api.linkagePreview).toHaveBeenCalledTimes(1)
  })

  it('不是应用创建者（接口 403）：这一块不出现，也不提示失败', async () => {
    api.linkageOverview.mockRejectedValue(Object.assign(new Error('只能管理自己创建的应用'), { businessCode: 403 }))
    await mount()
    expect(section()).toBeNull()
    expect(host.textContent).not.toContain('只能管理自己创建的应用')
    expect(button('发布应用').disabled).toBe(false)
  })

  it('没有应用管理权限时不调用预告接口，这一块不出现', async () => {
    api.hasPermission.mockImplementation(value => value !== 'nocode:app:manage')
    await mount()
    expect(api.linkageOverview).not.toHaveBeenCalled()
    expect(section()).toBeNull()
  })
})
