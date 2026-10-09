// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, modal, empty, find, event, flush } from '../../tools/selection-regression/renderer'
import Settings from '@/views/nocode/report-center/components/DashboardMenuSettings.vue'
const mocks = vi.hoisted(() => ({ menus: vi.fn(), permission: vi.fn(() => true), confirm: vi.fn() }))
vi.mock('@/api/system/menu', () => ({ getMenuList: mocks.menus }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ hasPermission: mocks.permission }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('ant-design-vue', () => ({ Modal: { confirm: mocks.confirm } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({ default: modal }))
vi.mock('@/components/IconSelector.vue', () => ({ default: empty }))
const content = { schemaVersion: 1 as const, name: '经营分析', description: '', charts: [] }
const directory = {
  id: '7',
  parentId: '0',
  name: '经营管理',
  path: '/business',
  sort: 0,
  status: 0,
  menuType: 1,
  visible: true
}
beforeEach(() => {
  mocks.menus.mockReset().mockResolvedValue([directory])
  mocks.permission.mockReturnValue(true)
  mocks.confirm.mockReset()
})
describe('仪表板菜单设置抽屉', () => {
  it('目录加载后应用完整配置，不改原内容', async () => {
    const apply = vi.fn(),
      close = vi.fn()
    const view = mount(Settings, { open: true, content, readOnly: false, onApply: apply, onClose: close })
    try {
      await flush()
      await event(find(view.root, 'a-switch'), 'onUpdate:checked', true)
      await event(find(view.root, 'a-select'), 'onUpdate:value', '7')
      await event(find(view.root, 'a-input'), 'onUpdate:value', '销售分析')
      await event(find(view.root, 'modal'), 'onOk')
      expect(apply).toHaveBeenCalledWith({
        showInMenu: true,
        platformParentId: '7',
        menuName: '销售分析',
        icon: 'BarChartOutlined',
        sort: 10
      })
      expect(close).toHaveBeenCalledOnce()
      expect(content).not.toHaveProperty('navigation')
    } finally {
      view.unmount()
    }
  })
  it('目录失败不应用，重试加载后保留输入并可应用', async () => {
    mocks.menus.mockRejectedValueOnce(new Error('目录加载失败')).mockResolvedValueOnce([directory])
    const apply = vi.fn()
    const view = mount(Settings, { open: true, content, readOnly: false, onApply: apply })
    try {
      await flush()
      await event(find(view.root, 'a-switch'), 'onUpdate:checked', true)
      await event(find(view.root, 'a-input'), 'onUpdate:value', '保留输入')
      await event(find(view.root, 'modal'), 'onOk')
      expect(apply).not.toHaveBeenCalled()
      await event(find(view.root, 'a-button'), 'onClick')
      await event(find(view.root, 'a-select'), 'onUpdate:value', '7')
      await event(find(view.root, 'modal'), 'onOk')
      expect(apply.mock.calls[0]?.[0].menuName).toBe('保留输入')
    } finally {
      view.unmount()
    }
  })
  it('未应用关闭提示放弃；无目录查询能力不请求目录', async () => {
    const close = vi.fn()
    const view = mount(Settings, { open: true, content, readOnly: false, onClose: close })
    try {
      await flush()
      await event(find(view.root, 'a-switch'), 'onUpdate:checked', true)
      await event(find(view.root, 'modal'), 'onCancel')
      expect(close).not.toHaveBeenCalled()
      expect(mocks.confirm).toHaveBeenCalledOnce()
    } finally {
      view.unmount()
    }
    mocks.menus.mockClear()
    mocks.permission.mockReturnValue(false)
    const denied = mount(Settings, { open: true, content, readOnly: false })
    try {
      await flush()
      expect(mocks.menus).not.toHaveBeenCalled()
      expect(find(denied.root, 'modal').props['show-footer']).toBe(false)
    } finally {
      denied.unmount()
    }
  })
})
