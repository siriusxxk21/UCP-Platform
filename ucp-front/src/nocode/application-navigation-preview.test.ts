// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { find, mount, text } from '../../tools/selection-regression/renderer'
import ApplicationNavigationPreview from '@/views/nocode/application/components/ApplicationNavigationPreview.vue'
import type { ApplicationResource } from '@/types/nocode/application'
import { applyPageNavigation } from './application-navigation'

vi.mock('@/components/AppIcon.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ setup: () => () => h('icon') }) }
})

const page = (id: string, name: string): ApplicationResource => ({
  id,
  name,
  kind: 'PAGE',
  code: id,
  config: { nodes: [] }
})
const directory = { id: '100', parentId: '0', name: '采购管理', path: '/purchase', sort: 1 }
describe('发布导航结构预览', () => {
  it('按排序展示平台二级页面；升级后旧别名不重复出现，隐藏页和旧入口分别提示', () => {
    const initial: ApplicationResource[] = [
      page('orders', '采购订单'),
      page('home', '采购首页'),
      page('detail', '订单详情'),
      page('legacy', '旧列表'),
      { id: 'old-order', kind: 'MENU', code: 'old_order', name: '原订单入口', config: { targetId: 'orders' } },
      {
        id: 'old-order-alias',
        kind: 'MENU',
        code: 'old_order_alias',
        name: '原订单副本',
        config: { targetId: 'orders' }
      },
      { id: 'old', kind: 'MENU', code: 'old', name: '旧列表入口', config: { targetId: 'legacy' } }
    ]
    const orders = applyPageNavigation(
      initial,
      'orders',
      {
        resourceName: '采购订单',
        showInMenu: true,
        platformParentId: '100',
        menuName: '采购订单',
        icon: '',
        sort: 20,
        defaultHome: false
      },
      [directory]
    )
    const resources = applyPageNavigation(
      orders,
      'home',
      {
        resourceName: '采购首页',
        showInMenu: true,
        platformParentId: '100',
        menuName: '采购首页',
        icon: '',
        sort: 10,
        defaultHome: true
      },
      [directory]
    )
    const wrapper = mount(ApplicationNavigationPreview, { resources, directories: [directory], loading: false })
    try {
      const content = text(wrapper.root)
      expect(content.indexOf('采购首页')).toBeLessThan(content.indexOf('采购订单'))
      expect(content).toContain('2 个平台菜单入口')
      expect(content).not.toContain('原订单副本')
      expect(
        text(find(wrapper.root, 'div', item => item.props.class === 'navigation-group hidden-navigation'))
      ).toContain('订单详情')
      expect(
        text(find(wrapper.root, 'div', item => item.props.class === 'navigation-group legacy-navigation'))
      ).toContain('旧列表')
    } finally {
      wrapper.unmount()
    }
  })
})
