import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 消息发送页 Page Object Model
 *
 * 对应页面：src/views/system/message/MessageSend.vue（/message/send）
 * 表单：模板类型下拉 + 标题 + 内容 + 接收对象（UserSelector）。
 */

export class MessageSendPage {
  readonly page: Page

  // 标题/内容
  readonly titleInput: Locator
  readonly contentInput: Locator

  // 接收对象
  readonly addUserButton: Locator
  readonly sendButton: Locator
  readonly resetButton: Locator

  constructor(page: Page) {
    this.page = page
    this.titleInput = page.getByPlaceholder('请输入消息标题')
    this.contentInput = page.getByPlaceholder('请输入消息内容')
    this.addUserButton = page.getByRole('button', { name: /添加用户/ })
    this.sendButton = page.getByRole('button', { name: /发送消息/ })
    this.resetButton = page.getByRole('button', { name: /重\s*置/ })
  }

  /** 跳转到消息发送页 */
  async goto(): Promise<void> {
    await this.page.goto('/message/send')
    // 用卡片标题定位，避免「发送消息」同时出现在卡片标题与发送按钮导致的严格模式冲突
    await expect(this.page.locator('.ant-card-head-title', { hasText: '发送消息' })).toBeVisible()
    // 等待模板列表加载完成（listAllSysMsgTemplate 响应返回）
    await this.page.waitForResponse(
      resp => resp.url().includes('/api/msg/template/list') && resp.request().method() === 'GET'
    )
  }

  /** 模板类型下拉（标题输入组内的 select；不要用 placeholder 过滤，选中模板后 placeholder 会消失） */
  templateSelect(): Locator {
    return this.page.locator('.ant-input-group .ant-select').first()
  }

  /** 选择消息模板（按名称） */
  async selectTemplate(templateName: string): Promise<void> {
    await this.templateSelect().click()
    await this.page
      .locator('.ant-select-dropdown:visible')
      .getByText(templateName, { exact: true })
      .click()
  }

  /** 填写标题 */
  async fillTitle(title: string): Promise<void> {
    await this.titleInput.fill(title)
  }

  /** 填写内容 */
  async fillContent(content: string): Promise<void> {
    await this.contentInput.fill(content)
  }

  /**
   * 添加接收对象（USER 类型，选择当前用户）。
   * 先确保选中「用户」目标类型（接收对象按钮文本随目标类型动态变化），
   * 再打开 UserSelector 按用户名搜索并勾选，最后确认。
   */
  async addUserRecipient(username: string): Promise<void> {
    // 确保选择「用户」目标类型（a-radio-button 渲染为 .ant-radio-button-wrapper）
    await this.page.locator('.ant-radio-button-wrapper', { hasText: /用户/ }).click()

    // 点击「添加用户」按钮（此时目标类型为 USER）
    await this.page.getByRole('button', { name: /添加用户/ }).click()
    // UserSelector 模态框
    const selector = this.page.locator('.ant-modal-content:visible')
    await expect(selector).toBeVisible()

    // 按用户名搜索并点「查询」
    await selector.getByPlaceholder('请输入用户名').fill(username)
    await selector.getByRole('button', { name: /查\s*询/ }).click()

    // 等待目标用户行出现（用户名单元格含头像字符，用 hasText 子串匹配）
    const row = selector.locator('.ant-table-tbody tr', { hasText: username }).first()
    await expect(row).toBeVisible()

    // 勾选该用户行
    await row.locator('input[type="checkbox"]').check()

    // 确认（UserSelector 确认按钮文案为「确 认」）
    await selector.getByRole('button', { name: /确\s*认/ }).click()
  }

  /** 发送消息 */
  async send(): Promise<void> {
    await this.sendButton.click()
  }

  /** 断言成功提示 */
  async expectSuccessMessage(text: string): Promise<void> {
    await expect(this.page.locator('.ant-message', { hasText: text })).toBeVisible()
  }

  /**
   * 等待 WebSocket 站内通知弹窗出现。
   * 消息发送成功后，后台通过 WebSocket 推送站内通知，BasicLayout 的
   * showSystemNotification 弹出右上角系统通知（class 含 message-notification-item）。
   * 通知标题可能为渠道模板标题而非消息标题，故只断言弹窗出现并包含消息标题子串。
   */
  async expectWebSocketNotification(title: string): Promise<void> {
    const notice = this.page.locator('.message-notification-item')
    await expect(notice).toBeVisible({ timeout: 20000 })
    // 通知内容应包含消息标题（渠道模板标题或消息标题）
    await expect(notice).toContainText(title)
  }
}
