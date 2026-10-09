import { expect, type Locator, type Page } from '@playwright/test'
import { getTestConfig } from '../utils/config'

/**
 * 登录页 Page Object Model
 *
 * 对应页面：src/views/login/index.vue
 * 登录表单字段：用户名、密码
 */
export class LoginPage {
  readonly page: Page
  readonly usernameInput: Locator
  readonly passwordInput: Locator
  readonly rememberAccountCheckbox: Locator
  readonly submitButton: Locator
  readonly errorMessage: Locator

  constructor(page: Page) {
    this.page = page
    // 使用 placeholder 定位，避免依赖 Ant Design 内部类名
    this.usernameInput = page.getByPlaceholder('请输入用户名')
    this.passwordInput = page.getByPlaceholder('请输入密码')
    this.rememberAccountCheckbox = page.getByRole('checkbox', { name: '记住账号' })
    this.submitButton = page.getByRole('button', { name: /登\s*录/ })
    // 表单校验错误提示
    this.errorMessage = page.locator('.ant-form-item-explain-error')
  }

  /** 跳转到登录页 */
  async goto(): Promise<void> {
    const config = getTestConfig()
    await this.page.goto(`${config.baseURL}/login`)
  }

  /** 填写用户名 */
  async fillUsername(username: string): Promise<void> {
    await this.usernameInput.fill(username)
  }

  /** 填写密码 */
  async fillPassword(password: string): Promise<void> {
    await this.passwordInput.fill(password)
  }

  /**
   * 执行登录
   * 默认读取测试配置中的账号，也可传入自定义账号。
   */
  async login(username?: string, password?: string): Promise<void> {
    const config = getTestConfig()
    await this.fillUsername(username ?? config.username)
    await this.fillPassword(password ?? config.password)
    await this.submitButton.click()
  }

  /** 断言当前处于登录页 */
  async expectVisible(): Promise<void> {
    await expect(this.submitButton).toBeVisible()
  }

  /** 断言登录成功并跳转到目标路径 */
  async expectLoginSuccess(targetUrl = /\/workspace/): Promise<void> {
    await this.page.waitForURL(targetUrl, { timeout: 15_000 })
  }

  /** 断言登录失败并显示错误提示（可能同时出现多个字段错误） */
  async expectLoginError(): Promise<void> {
    // 空表单提交时用户名/密码可能同时报错，存在多个 .ant-form-item-explain-error
    // 使用 .first() 断言"至少出现一个错误提示"，避免严格模式违规
    await expect(this.errorMessage.first()).toBeVisible()
  }
}
