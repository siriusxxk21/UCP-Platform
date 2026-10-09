import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 消息模板管理页 Page Object Model
 *
 * 对应页面：src/views/system/message/MessageTemplateManage.vue（/message/template）
 * 模板列表 + 新增/编辑抽屉（SysMsgTemplateFormDrawer）。
 */

export interface MsgTemplateFormData {
  /** 模板编码 */
  code: string
  /** 模板名称 */
  name: string
  /** 标题模板 */
  templateTitle: string
  /** 内容模板 */
  templateContent?: string
  /** 路由模板 */
  templateUrl?: string
}

export class MessageTemplatePage {
  readonly page: Page

  // 搜索区（消息模板页搜索区只有 a-input-search，@search 触发查询）
  readonly searchInput: Locator
  readonly searchButton: Locator

  // 操作按钮
  readonly addButton: Locator

  // 表格主体
  readonly tableBody: Locator

  // 新增/编辑抽屉
  readonly drawer: Locator

  constructor(page: Page) {
    this.page = page
    // 搜索区（a-input-search：@search 触发查询，点击搜索按钮触发）
    this.searchInput = page.getByPlaceholder('模板编码/名称')
    this.searchButton = page.locator('.ant-input-search-button')

    // 新增按钮（带 PlusOutlined 图标，accessible name 为 "plus 消息模板"；
    // 用前缀精确匹配，避免与左侧导航 tab「消息模板」冲突）
    this.addButton = page.getByRole('button', { name: /plus 消息模板/ })

    // 表格主体
    this.tableBody = page.locator('.ant-table-tbody')

    // 抽屉（用 :visible 避免关闭后残留 DOM 干扰严格模式）
    this.drawer = page.locator('.ant-drawer-content:visible')
  }

  /** 跳转到消息模板页 */
  async goto(): Promise<void> {
    await this.page.goto('/message/template')
    await expect(this.page.getByText('消息模板列表')).toBeVisible()
  }

  /** 等待表格加载完成 */
  async waitForTableLoaded(): Promise<void> {
    await expect(this.page.locator('.ant-spin-spinning')).toHaveCount(0)
  }

  /**
   * 按关键字（编码/名称）查询。
   * 消息模板页的 a-input-search 绑定 @search 触发查询，须点击搜索按钮（放大镜）。
   * 点击后等待「含该关键字」的查询响应完成，避免匹配到旧请求导致的竞态。
   */
  async queryByKeyword(keyword: string): Promise<void> {
    await this.searchInput.fill(keyword)
    // 先注册 waitForResponse 精确匹配本次查询（URL 含编码后的 keyword），再点击搜索
    const respPromise = this.page.waitForResponse(
      resp =>
        resp.url().includes('/api/msg/template/page')
        && resp.url().includes(encodeURIComponent(keyword))
        && resp.request().method() === 'GET'
    )
    await this.searchButton.click()
    await respPromise
    await this.waitForTableLoaded()
  }

  /** 重置查询（清空搜索框并点击搜索，回到全量列表） */
  async reset(): Promise<void> {
    await this.searchInput.fill('')
    const respPromise = this.page.waitForResponse(
      resp =>
        resp.url().includes('/api/msg/template/page')
        && !resp.url().includes('keyword=')
        && resp.request().method() === 'GET'
    )
    await this.searchButton.click()
    await respPromise
    await this.waitForTableLoaded()
  }

  /** 根据模板编码定位行 */
  rowByCode(code: string): Locator {
    return this.tableBody.locator('tr', { has: this.page.getByText(code, { exact: true }) })
  }

  /** 断言模板行可见 */
  async expectRowVisible(code: string): Promise<void> {
    await expect(this.rowByCode(code)).toBeVisible()
  }

  /** 断言模板行不可见 */
  async expectRowHidden(code: string): Promise<void> {
    await expect(this.rowByCode(code)).toHaveCount(0)
  }

  /** 断言模板行包含文本（如名称） */
  async expectRowContains(code: string, text: string): Promise<void> {
    await expect(this.rowByCode(code)).toContainText(text)
  }

  // ==================== 新增 / 编辑 ====================

  /** 点击「消息模板」打开新增抽屉 */
  async clickAdd(): Promise<void> {
    await this.addButton.click()
    await this.waitForDrawerReady()
  }

  /** 点击模板行的「编辑」 */
  async clickEdit(code: string): Promise<void> {
    await this.rowByCode(code).getByRole('button', { name: /编\s*辑/ }).click()
    await this.waitForDrawerReady()
  }

  /**
   * 等待抽屉完全加载。
   * 抽屉 watch 会异步 loadChannels + 回填 form，若在回填前填充字段会被覆盖。
   * 等待渠道 tab（系统弹窗）渲染完成，证明异步加载与回填已完成。
   */
  private async waitForDrawerReady(): Promise<void> {
    await expect(this.drawer).toBeVisible()
    await expect(this.drawer.getByRole('tab', { name: '系统弹窗' })).toBeVisible()
  }

  /** 点击模板行的「删除」并确认（a-popconfirm） */
  async clickDelete(code: string): Promise<void> {
    await this.rowByCode(code).getByRole('button', { name: /删\s*除/ }).click()
    // a-popconfirm 确认
    const popconfirm = this.page.locator('.ant-popconfirm:visible')
    await expect(popconfirm).toBeVisible()
    await popconfirm.getByRole('button', { name: /确\s*定/ }).click()
  }

  // ==================== 抽屉表单 ====================

  /**
   * 填写抽屉基本信息（编码/名称/标题模板/内容模板）。
   * 用 form-item 的 label 语义化定位到 control 内的输入控件，填充后验证值，
   * 确保字段真正写入（避免「提示修改成功但数据未变更」）。
   * 注意：模板编码在编辑模式（record.id 存在）下被禁用，禁用时跳过填充。
   */
  async fillForm(data: MsgTemplateFormData): Promise<void> {
    // 模板编码（编辑模式禁用，跳过）
    const codeInput = this.fieldByLabel('模板编码')
    if (await codeInput.isEnabled()) {
      await codeInput.fill(data.code)
    }
    // 模板名称
    await this.fieldByLabel('模板名称').fill(data.name)
    // 标题模板
    await this.fieldByLabel('标题模板').fill(data.templateTitle)
    if (data.templateContent !== undefined) {
      // 内容模板
      await this.fieldByLabel('内容模板').fill(data.templateContent)
    }

    // 校验已填入的关键字段值，确保修改生效
    await expect(this.fieldByLabel('模板名称')).toHaveValue(data.name)
    await expect(this.fieldByLabel('标题模板')).toHaveValue(data.templateTitle)
  }

  /** 根据 form-item label 定位 control 内的输入控件（input/textarea） */
  private fieldByLabel(label: string): Locator {
    return this.drawer
      .locator('.ant-form-item', { hasText: label })
      .locator('.ant-form-item-control input, .ant-form-item-control textarea')
      .first()
  }

  /**
   * 配置系统弹窗渠道（站内信 WebSocket 推送，渠道 code=default）：
   * 切换到「系统弹窗」tab，启用渠道开关，并填写渠道模板的标题/内容元数据。
   * 系统弹窗渠道的 metadata 字段为 title（标题）、content（内容）。
   */
  async configureSystemPopup(title: string, content: string): Promise<void> {
    await this.drawer.getByRole('tab', { name: '系统弹窗' }).click()
    const pane = this.drawer.locator('.ant-tabs-tabpane-active')

    // 启用渠道开关（a-switch 选中态通过 .ant-switch-checked class 判断）
    const switchEl = pane.locator('.ant-switch').first()
    const cls = await switchEl.getAttribute('class')
    if (!cls?.includes('ant-switch-checked')) {
      await switchEl.click()
    }

    // 渠道元数据：标题（text）、内容（textarea）
    await pane.getByPlaceholder('请输入标题').fill(title)
    await pane.getByPlaceholder('请输入内容').fill(content)
  }

  /**
   * 提交抽屉（点保存）。
   * @param successText 可选：保存成功后出现的提示文案（添加成功/修改成功），
   *                     先等待该提示确认操作成功，若 API 失败会在此明确报错。
   */
  async submitDrawer(successText?: string): Promise<void> {
    await this.drawer.getByRole('button', { name: /保\s*存/ }).click()
    if (successText) {
      await expect(this.page.locator('.ant-message', { hasText: successText })).toBeVisible()
    }
    // a-drawer 默认 destroyOnClose=false，关闭后 DOM 可能保留；用 toBeHidden 而非 toHaveCount(0)
    await expect(this.drawer).toBeHidden()
  }

  /** 取消抽屉 */
  async cancelDrawer(): Promise<void> {
    await this.drawer.getByRole('button', { name: /取\s*消/ }).click()
    await expect(this.drawer).toBeHidden()
  }

  /** 断言成功提示 */
  async expectSuccessMessage(text: string): Promise<void> {
    await expect(this.page.locator('.ant-message', { hasText: text })).toBeVisible()
  }
}
