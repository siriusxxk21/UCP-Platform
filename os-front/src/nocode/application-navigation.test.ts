import { describe, expect, it } from 'vitest'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { NodeKind, PageActionKind, uiNode } from '@/types/nocode/application-ui'
import {
  applyPageNavigation,
  pageNavigation,
  pageNavigationSettings,
  pageNavigationUnavailableReason,
  pageRemovalReferences,
  removePageAndNavigation
} from './application-navigation'

const page = (id = 'page'): ApplicationResource => ({
  id,
  kind: ResourceKind.PAGE,
  name: `页面${id}`,
  code: `page_${id}`,
  config: { nodes: [] }
})
const legacy = (id = 'menu', targetId = 'page'): ApplicationResource => ({
  id,
  kind: ResourceKind.MENU,
  name: '原菜单名称',
  code: `menu_${id}`,
  config: { targetId }
})
const directories = [{ id: '100', name: '采购管理' }]
const settings = () => ({
  resourceName: '采购工作台',
  menuName: '采购首页',
  platformParentId: '100',
  icon: 'HomeOutlined',
  sort: 10,
  showInMenu: true,
  defaultHome: true
})

describe('页面入口随应用草稿管理', () => {
  it('复用原 MENU 身份并保留重复旧别名，不改变源资源或原页面按钮', () => {
    const buttonPage = page('button')
    buttonPage.config.nodes = [
      uiNode(NodeKind.BUTTON, { action: { kind: PageActionKind.NAVIGATE, resourceId: 'menu' } })
    ]
    const original = [page(), legacy(), legacy('alias'), buttonPage]
    const snapshot = JSON.stringify(original)
    const updated = applyPageNavigation(original, 'page', settings(), directories)
    expect(JSON.stringify(original)).toBe(snapshot)
    expect(updated).toHaveLength(4)
    expect(pageNavigation(updated, 'page')).toMatchObject({
      id: 'menu',
      code: 'menu_menu',
      name: '采购首页',
      config: { navigationVersion: 2, targetId: 'page', platformParentId: '100', showInMenu: true, defaultHome: true }
    })
    expect(updated.find(item => item.id === 'alias')).toEqual(original[2])
    expect(updated.find(item => item.id === 'button')).toEqual(buttonPage)
  })

  it('隐藏入口保留可跳转身份；首页切换全应用唯一且允许隐藏首页', () => {
    const original = applyPageNavigation([page(), legacy(), page('other')], 'page', settings(), directories)
    const hidden = applyPageNavigation(original, 'page', { ...settings(), showInMenu: false }, directories)
    expect(pageNavigation(hidden, 'page')?.id).toBe('menu')
    expect(pageNavigationSettings(hidden, hidden[0]!)).toMatchObject({ showInMenu: false, defaultHome: true })
    const next = applyPageNavigation(hidden, 'other', { ...settings(), showInMenu: false }, directories)
    expect(next.filter(item => item.config.defaultHome === true)).toHaveLength(1)
    expect(pageNavigation(next, 'page')?.config.defaultHome).toBe(false)
    expect(pageNavigation(next, 'other')?.config.defaultHome).toBe(true)
  })

  it('缺失目录、记录上下文和统计资源不能生成可见入口；上下文页可关闭旧入口', () => {
    expect(() => applyPageNavigation([page()], 'page', settings(), [])).toThrow('平台一级目录')
    const contextual = { ...page(), config: { nodes: [], contextObjectId: 'order' } }
    expect(pageNavigationUnavailableReason(contextual)).toContain('业务记录')
    expect(() => applyPageNavigation([contextual], 'page', settings(), directories)).toThrow('业务记录')
    expect(() => applyPageNavigation([contextual], 'page', { ...settings(), showInMenu: false }, directories)).toThrow(
      '业务记录'
    )
    expect(
      applyPageNavigation(
        [contextual, legacy()],
        'page',
        { ...settings(), showInMenu: false, defaultHome: false },
        []
      ).find(item => item.id === 'menu')?.config.showInMenu
    ).toBe(false)
    expect(() =>
      applyPageNavigation([{ ...page(), kind: ResourceKind.REPORT }], 'page', settings(), directories)
    ).toThrow('页面已不存在')
  })

  it('没有 MENU 的页面只创建一条内部入口，反复修改不会重复创建', () => {
    const first = applyPageNavigation([page()], 'page', settings(), directories)
    const second = applyPageNavigation(first, 'page', { ...settings(), sort: 20 }, directories)
    expect(second).toHaveLength(2)
    expect(pageNavigation(second, 'page')?.id).toBe(pageNavigation(first, 'page')?.id)
    expect(pageNavigation(second, 'page')?.config.sort).toBe(20)
  })

  it('删除识别深层按钮旧地址、详情页及组合视图引用，解除引用后连带清理所有入口', () => {
    const button = page('button')
    button.config.nodes = [
      uiNode(NodeKind.CARD, {
        children: [uiNode(NodeKind.BUTTON, { action: { kind: PageActionKind.NAVIGATE, resourceId: 'alias' } })]
      })
    ]
    const view: ApplicationResource = {
      id: 'view',
      kind: ResourceKind.VIEW,
      name: '采购订单',
      code: 'view_order',
      config: { detailPageId: 'page' }
    }
    const original = [page(), legacy(), legacy('alias'), button, view]
    expect(pageRemovalReferences(original, 'page').map(item => item.id)).toEqual(['button', 'view'])
    expect(() => removePageAndNavigation(original, 'page')).toThrow('采购订单')
    expect(removePageAndNavigation(original.slice(0, 3), 'page')).toEqual([])
  })
})
