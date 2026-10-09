// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createPinia, type Pinia } from 'pinia'
import ApplicationPublishDialog from '@/views/nocode/application/components/ApplicationPublishDialog.vue'
import type { ApplicationDetail, PublishedObject } from '@/types/nocode/application'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import { sameRuntimeApplication, useRuntimeApplicationCache } from './runtime-application-cache'

const api = vi.hoisted(() => ({ sharing: vi.fn(), publish: vi.fn(), publishAndEnable: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ applications: api, hasPermission: () => true })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', async () => {
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
vi.mock('@/views/nocode/components/ObjectSharingPanel.vue', () => ({ default: { render: () => null } }))

function runtime(versionNo: number, fieldIds = ['name']): RuntimeApplication {
  return {
    application: {
      id: '2210',
      code: 'assets',
      name: '资产管理',
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
    definition: {
      objects: [{ objectId: '4442', versionNo: 1, checksum: 'o' }],
      resources: [{ id: 'view', kind: 'VIEW', code: 'view', name: '资产', config: { objectId: '4442', fieldIds } }]
    }
  }
}

let app: App | undefined, host: HTMLDivElement | undefined, pinia: Pinia
const flush = async () => {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
const cache = () => app!.runWithContext(useRuntimeApplicationCache)

beforeEach(() => {
  pinia = createPinia()
  app = createApp({ render: () => null })
  app.use(pinia)
})
afterEach(() => {
  if (host) app?.unmount()
  app = undefined
  host?.remove()
  host = undefined
  vi.clearAllMocks()
})

describe('runtime application definition cache', () => {
  it('keeps the first copy when the same definition comes back, so callers can tell nothing changed', () => {
    const first = runtime(1)
    expect(cache().peek('2210')).toBeUndefined()
    expect(cache().remember('2210', first)).toBe(first)
    expect(cache().remember('2210', runtime(1))).toBe(first)
    expect(cache().peek('2210')).toBe(first)
  })

  it('takes the new copy when the published version or the permitted content differs', () => {
    cache().remember('2210', runtime(1))
    const republished = runtime(2)
    expect(cache().remember('2210', republished)).toBe(republished)
    // 版本没变，权限裁掉了一列
    const narrowed = runtime(2, [])
    expect(sameRuntimeApplication(republished, narrowed)).toBe(false)
    expect(cache().remember('2210', narrowed)).toBe(narrowed)
  })

  it('forgets one application or everything', () => {
    cache().remember('2210', runtime(1))
    cache().remember('9', runtime(1))
    cache().forget('2210')
    expect(cache().peek('2210')).toBeUndefined()
    expect(cache().peek('9')).toBeDefined()
    cache().forget()
    expect(cache().peek('9')).toBeUndefined()
  })

  it('does not cache at all in an application without a store installed', () => {
    // 独立夹具不装 Pinia：组件里拿到的是「不缓存」的那一份，每次照常取
    let bare!: ReturnType<typeof useRuntimeApplicationCache>
    const fixture = createApp({
      setup() {
        bare = useRuntimeApplicationCache()
        return () => null
      }
    })
    fixture.mount(document.createElement('div'))
    const definition = runtime(1)
    expect(bare.remember('2210', definition)).toBe(definition)
    expect(bare.peek('2210')).toBeUndefined()
    bare.forget()
    fixture.unmount()
  })
})

describe('publishing from this browser', () => {
  it('discards the cached runtime definition of the published application', async () => {
    const detail = {
      application: { id: '2210', name: '资产管理', revision: 3 },
      draft: { objects: [{ objectId: '4442', versionNo: 1, checksum: 'v1' }], resources: [] }
    } as unknown as ApplicationDetail
    const objects = {
      '4442': { objectId: '4442', definition: { objectName: '资产分类', fields: [] } }
    } as unknown as Record<string, PublishedObject>
    api.sharing.mockResolvedValue([
      { objectId: '4442', applicationId: '2210', permission: { actions: ['READ'], readFields: [], writeFields: [] } }
    ])
    api.publish.mockResolvedValue(detail)
    api.publishAndEnable.mockResolvedValue(detail)

    app = createApp(() => h(ApplicationPublishDialog, { open: true, detail, objects }))
    app.use(pinia)
    const plain = defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.default?.())
    })
    for (const name of ['ASpace', 'AFormItem', 'AAlert', 'AModal']) app.component(name, plain)
    app.component(
      'AButton',
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('button', slots.default?.())
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
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    await flush()
    cache().remember('2210', runtime(1))
    cache().remember('9', runtime(1))

    const input = host.querySelector('textarea')!
    input.value = '新增资产分类'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    Array.from(host.querySelectorAll('button'))
      .find(button => (button.textContent?.trim() || '').startsWith('发布'))!
      .click()
    await flush()

    expect(api.publish.mock.calls.length + api.publishAndEnable.mock.calls.length).toBe(1)
    expect(cache().peek('2210')).toBeUndefined()
    expect(cache().peek('9')).toBeDefined()
  })
})
