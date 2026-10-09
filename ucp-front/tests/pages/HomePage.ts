import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 首页/工作台 Page Object Model
 *
 * 对应页面：src/views/workspace/index.vue（/workspace）
 * 登录成功后默认跳转的工作台页面。
 */
export class HomePage {
  readonly page: Page
  /** 顶部导航栏（BasicLayout 结构） */
  readonly topNav: Locator
  /** 用户头像/菜单入口 */
  readonly userMenu: Locator
  /** 工作台主内容区 —— 用语义化标题定位，避免依赖 AntD 内部 CSS 类名 */
  readonly mainContent: Locator

  constructor(page: Page) {
    this.page = page
    this.topNav = page.locator('.ant-layout-header')
    this.userMenu = page.getByTestId('user-menu')
    // 用工作台页面独有的标题元素定位主内容，替代脆弱的 .ant-layout-content
    this.mainContent = page.getByRole('heading', { name: '我的项目' })
  }

  /** 跳转到工作台 */
  async goto(): Promise<void> {
    await this.page.goto('/workspace')
  }

  /** 断言当前处于工作台页面 */
  async expectVisible(): Promise<void> {
    await expect(this.page).toHaveURL(/\/workspace/)
    await expect(this.mainContent).toBeVisible()
  }

  /** 通过顶部菜单导航到指定菜单（按文本匹配） */
  async navigateToMenu(menuText: string): Promise<void> {
    await this.topNav.getByRole('menuitem', { name: menuText }).first().click()
  }

  /** 打开用户菜单并选择退出登录 */
  async logout(): Promise<void> {
    await this.userMenu.click()
    await this.page.getByText('退出登录').click()
    await expect(this.page).toHaveURL(/\/login/)
  }
}
