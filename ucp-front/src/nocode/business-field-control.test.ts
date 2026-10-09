// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, computed, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
import BusinessFieldControl from '@/views/nocode/application/components/BusinessFieldControl.vue'
import { FieldType } from '@/types/nocode/enums'
import { selectionPreviewKey } from './selection'

const api = vi.hoisted(() => ({
  selection: vi.fn(),
  previewSelection: vi.fn(),
  directory: vi.fn(),
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ runtime: api, applications: { previewSelection: api.previewSelection } })
}))
vi.mock('@/nocode/directory-options', () => ({ directoryOptions: api.directory }))

let app: App | undefined, host: HTMLDivElement | undefined
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(kind: FieldType, mode: 'design' | 'preview' | 'runtime', extra = {}) {
  app = createApp(BusinessFieldControl, {
    kind,
    mode,
    selection: true,
    applicationId: 'app',
    objectId: 'object',
    fieldId: 'selected-field',
    formId: 'form',
    targetObjectId: 'target',
    modelValue: 'saved-value',
    ...extra
  })
  app.use(Antd)
  app.provide(
    selectionPreviewKey,
    computed(() => ({
      applicationId: 'app',
      objects: [{ objectId: 'object', versionNo: 12, checksum: 'fixed' }],
      form: { objectId: 'object', nodes: [], detailIds: [] }
    }))
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return host
}
beforeEach(() => {
  vi.clearAllMocks()
  const result = {
    options: [],
    total: 0,
    tree: false,
    defaultValue: null,
    selected: [
      {
        value: 'saved-value',
        label: '历史可见名称',
        code: '',
        path: null,
        parentValue: null,
        disabled: false,
        unavailable: false
      }
    ]
  }
  api.selection.mockResolvedValue(result)
  api.previewSelection.mockResolvedValue(result)
  api.directory.mockResolvedValue([{ value: 'saved-value', label: '目录名称' }])
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.unstubAllGlobals()
})

describe('业务字段设计态与真实数据查询边界', () => {
  it.each([
    FieldType.REFERENCE,
    FieldType.SELECT,
    FieldType.MULTI_SELECT,
    FieldType.USER,
    FieldType.DEPARTMENT,
    FieldType.ORGANIZATION,
    FieldType.POST,
    FieldType.USER_GROUP
  ])('设计画布的 %s 即使带有完整选择上下文也只展示禁用结构控件', async kind => {
    const root = await mount(kind, 'design', { readOnly: true })
    expect(root.querySelector('.ant-select-disabled')).not.toBeNull()
    expect(root.textContent).not.toContain('历史可见名称')
    for (const method of Object.values(api)) expect(method).not.toHaveBeenCalled()
  })
  it.each([FieldType.REFERENCE, FieldType.DEPARTMENT])('设计画布兼容旧版 %s 控件，不加载引用记录或目录', async kind => {
    const root = await mount(kind, 'design', { selection: false })
    expect(root.querySelector('.ant-select-disabled')).not.toBeNull()
    for (const method of Object.values(api)) expect(method).not.toHaveBeenCalled()
  })
  it.each([
    ['preview', FieldType.REFERENCE],
    ['runtime', FieldType.REFERENCE],
    ['preview', FieldType.SELECT],
    ['runtime', FieldType.SELECT]
  ] as const)('%s 的 %s 禁用只读字段仍通过对应权限接口解析已有值', async (mode, kind) => {
    const root = await mount(kind, mode, { disabled: true, readOnly: true })
    expect(root.textContent).toContain('历史可见名称')
    if (mode === 'preview') {
      expect(api.previewSelection).toHaveBeenCalledOnce()
      expect(api.previewSelection).toHaveBeenCalledWith(
        expect.objectContaining({
          query: expect.objectContaining({ selected: ['saved-value'], objectId: 'object', fieldId: 'selected-field' }),
          objects: [{ objectId: 'object', versionNo: 12, checksum: 'fixed' }]
        })
      )
      expect(api.selection).not.toHaveBeenCalled()
    } else {
      expect(api.selection).toHaveBeenCalledOnce()
      expect(api.selection).toHaveBeenCalledWith(
        expect.objectContaining({
          selected: ['saved-value'],
          objectId: 'object',
          fieldId: 'selected-field',
          formId: 'form'
        })
      )
      expect(api.previewSelection).not.toHaveBeenCalled()
    }
  })
})
