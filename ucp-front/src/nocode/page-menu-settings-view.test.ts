// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { event, find, flush, modal, mount, text } from '../../tools/selection-regression/renderer'
import PageMenuSettings from '@/views/nocode/application/components/PageMenuSettings.vue'
import type { ApplicationResource } from '@/types/nocode/application'
import { MenuStatus, MenuType } from '@/types/system/menu'

const calls = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('ant-design-vue', () => ({ Modal: { confirm: calls.confirm } }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({ default: modal }))
vi.mock('@/components/IconSelector.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['modelValue'],
      setup:
        (props, { emit }) =>
        () =>
          h('icon-selector', { value: props.modelValue, onChange: (value: string) => emit('update:modelValue', value) })
    })
  }
})
const page: ApplicationResource = {
  id: 'orders',
  kind: 'VIEW',
  name: '采购订单',
  code: 'orders_view',
  config: { objectId: 'order' }
}
const props = () => ({
  open: true,
  resource: page,
  resources: [page],
  directories: [
    {
      id: '100',
      parentId: '0',
      name: '采购管理',
      path: '/purchase',
      sort: 1,
      menuType: MenuType.DIRECTORY,
      status: MenuStatus.ENABLED
    }
  ],
  readOnly: false,
  directoriesLoading: false,
  canQueryDirectories: true
})
afterEach(() => vi.clearAllMocks())

describe('页面菜单抽屉配置', () => {
  it('未选目录拒绝保存并保留输入，选择目录后只更新本地草稿和首页', async () => {
    const apply = vi.fn(),
      close = vi.fn(),
      original = JSON.stringify(page)
    const wrapper = mount(PageMenuSettings, { ...props(), onApply: apply, onClose: close })
    try {
      await flush()
      await event(
        find(wrapper.root, 'a-switch', n => n.props['aria-label'] === '显示在平台菜单'),
        'onUpdate:checked',
        true
      )
      await event(
        find(wrapper.root, 'a-input', n => n.props['aria-label'] === '菜单名称'),
        'onUpdate:value',
        '采购台账'
      )
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply).not.toHaveBeenCalled()
      expect(find(wrapper.root, 'a-alert', n => n.props.type === 'error').props.message).toContain('平台一级目录')
      await event(
        find(wrapper.root, 'a-select', n => n.props['aria-label'] === '平台一级目录'),
        'onUpdate:value',
        '100'
      )
      await event(find(wrapper.root, 'icon-selector'), 'onChange', 'ShoppingOutlined')
      await event(
        find(wrapper.root, 'a-switch', n => n.props['aria-label'] === '设为应用首页'),
        'onUpdate:checked',
        true
      )
      await event(find(wrapper.root, 'modal'), 'onOk')
      expect(apply).toHaveBeenCalledOnce()
      expect(apply.mock.calls[0]![0].find((item: ApplicationResource) => item.kind === 'MENU').config).toMatchObject({
        menuName: '采购台账',
        icon: 'ShoppingOutlined',
        platformParentId: '100',
        showInMenu: true,
        defaultHome: true
      })
      expect(close).toHaveBeenCalledOnce()
      expect(JSON.stringify(page)).toBe(original)
    } finally {
      wrapper.unmount()
    }
  })

  it('关闭未保存抽屉需放弃确认，确认前不影响草稿', async () => {
    const close = vi.fn(),
      apply = vi.fn()
    const wrapper = mount(PageMenuSettings, { ...props(), onApply: apply, onClose: close })
    try {
      await event(
        find(wrapper.root, 'a-input', n => n.props['aria-label'] === '页面名称'),
        'onUpdate:value',
        '新页面名称'
      )
      await event(find(wrapper.root, 'modal'), 'onCancel')
      expect(close).not.toHaveBeenCalled()
      expect(calls.confirm).toHaveBeenCalledOnce()
      calls.confirm.mock.calls[0]![0].onOk()
      expect(close).toHaveBeenCalledOnce()
      expect(apply).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })

  it('缺目录查询权限仍可查看原配置，直接触发保存也不能改写', async () => {
    const apply = vi.fn()
    const wrapper = mount(PageMenuSettings, { ...props(), canQueryDirectories: false, onApply: apply })
    try {
      expect(find(wrapper.root, 'modal').props['show-footer']).toBe(false)
      expect(text(wrapper.root)).toContain('基本信息')
      expect(find(wrapper.root, 'a-alert', n => n.props.type === 'warning').props.message).toContain('查询权限')
      await find(wrapper.root, 'modal').props.onOk()
      expect(apply).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })
})
