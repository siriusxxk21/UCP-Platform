import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 我的消息页 Page Object Model
 *
 * 对应页面：src/views/system/message/MessageList.vue（/message/list）
 * 消息列表 + 详情抽屉。点击标题可查看详情并标记已读。
 */

export class MessageListPage {
  readonly page: Page

  // 搜索区
  readonly keywordInput: Locator
  readonly msgTypeSelect: Locator
  readonly readStatusSelect: Locator

  // 表格主体
  readonly tableBody: Locator

  constructor(page: Page) {
    this.page = page
    this.keywordInput = page.getByPlaceholder('搜索标题/内容')
    this.msgTypeSelect = page.locator('.ant-select').filter({ hasText: '消息类型' }).first()
    this.readStatusSelect = page.locator('.ant-select').filter({ hasText: '阅读状态' }).first()
    this.tableBody = page.locator('.ant-table-tbody')
  }

  /** 跳转到我的消息页 */
  async goto(): Promise<void> {
    await this.page.goto('/message/list')
    // 用卡片标题定位，避免「我的消息」同时出现在菜单/导航 tab/卡片标题导致的严格模式冲突
    await expect(this.page.locator('.ant-card-head-title', { hasText: '我的消息' })).toBeVisible()
  }

  /** 等待表格加载完成 */
  async waitForTableLoaded(): Promise<void> {
    await expect(this.page.locator('.ant-spin-spinning')).toHaveCount(0)
  }

  /** 按关键词查询（标题/内容） */
  async queryByKeyword(keyword: string): Promise<void> {
    await this.keywordInput.fill(keyword)
    await this.keywordInput.press('Enter')
    await this.waitForTableLoaded()
  }

  /** 按阅读状态过滤（未读/已读） */
  async filterByReadStatus(status: string): Promise<void> {
    await this.readStatusSelect.click()
    await this.page
      .locator('.ant-select-dropdown:visible')
      .getByText(status, { exact: true })
      .click()
    await this.waitForTableLoaded()
  }

  /** 根据消息标题定位行 */
  rowByTitle(title: string): Locator {
    return this.tableBody.locator('tr', { has: this.page.getByText(title, { exact: true }) })
  }

  /** 断言消息行可见 */
  async expectRowVisible(title: string): Promise<void> {
    await expect(this.rowByTitle(title)).toBeVisible()
  }

  /** 断言消息行包含文本（如内容、已读状态） */
  async expectRowContains(title: string, text: string): Promise<void> {
    await expect(this.rowByTitle(title)).toContainText(text)
  }

  /** 点击消息标题，打开详情抽屉并标记已读 */
  async viewMessage(title: string): Promise<void> {
    await this.rowByTitle(title).locator('.title-link').click()
    const drawer = this.page.locator('.ant-drawer-content:visible')
    await expect(drawer).toBeVisible()
  }
}
