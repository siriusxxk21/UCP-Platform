import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 组织管理页 Page Object Model
 *
 * 对应页面：src/views/system/organization/index.vue（/system/organization）
 * 组织树形列表 + 新增/编辑弹窗。
 *
 * 注意：页面组织类型枚举为 公司(1) / 分公司(2) / 部门(3)。
 */
export interface OrgFormData {
  /** 组织编码 */
  orgCode: string
  /** 组织名称 */
  orgName: string
  /** 组织类型：1=公司 2=分公司 3=部门 */
  orgType: number
  /** 联系电话 */
  phone?: string
  /** 电子邮箱 */
  email?: string
  /** 地址 */
  address?: string
  /** 状态：1=启用 0=禁用 */
  status: number
}

export class OrganizationPage {
  readonly page: Page

  // 搜索区
  readonly orgNameInput: Locator
  readonly statusSelect: Locator
  readonly queryButton: Locator
  readonly resetButton: Locator

  // 工具栏/操作
  readonly addButton: Locator

  // 表格主体（含行）
  readonly tableBody: Locator

  // 新增/编辑弹窗
  readonly modal: Locator

  constructor(page: Page) {
    this.page = page
    // 搜索区（状态为 a-select，placeholder 渲染为 span，需定位 select 元素）
    // 注：限定到搜索卡片内，避免与隐藏的弹窗残留输入框（同名 placeholder）冲突
    this.orgNameInput = page.locator('.la-table-page__search').getByPlaceholder('请输入组织名称')
    this.statusSelect = page.locator('.la-table-page__search .ant-select')
    this.queryButton = page.getByRole('button', { name: /查\s*询/ })
    this.resetButton = page.getByRole('button', { name: /重\s*置/ })

    // 操作按钮
    this.addButton = page.getByRole('button', { name: /新\s*增/ })

    // 表格主体
    this.tableBody = page.locator('.ant-table-tbody')

    // 新增/编辑弹窗（Ant Design Vue a-modal 默认 destroyOnClose=false，关闭后
    // .ant-modal-content 仍保留在 DOM 中；用 :visible 限定当前可见弹窗，避免
    // 关闭后留下的隐藏弹窗干扰 fillForm 的严格模式定位）
    this.modal = page.locator('.ant-modal-content:visible')
  }

  /** 跳转到组织管理页 */
  async goto(): Promise<void> {
    await this.page.goto('/system/organization')
    // 等待表格渲染
    await expect(this.page.getByText('组织列表')).toBeVisible()
  }

  // ==================== 查询 ====================

  /** 按组织名称查询 */
  async queryByOrgName(orgName: string): Promise<void> {
    await this.orgNameInput.fill(orgName)
    await this.queryButton.click()
    await this.waitForTableLoaded()
  }

  /** 按状态查询 */
  async queryByStatus(status: string): Promise<void> {
    await this.statusSelect.click()
    await this.page
      .locator('.ant-select-dropdown:visible')
      .getByText(status, { exact: true })
      .click()
    await this.waitForTableLoaded()
  }

  /** 重置查询条件 */
  async reset(): Promise<void> {
    await this.resetButton.click()
    await this.waitForTableLoaded()
  }

  /** 等待表格加载完成（loading 消失） */
  async waitForTableLoaded(): Promise<void> {
    await expect(this.page.locator('.ant-spin-spinning')).toHaveCount(0)
  }

  /** 点击指定组织行内的展开图标，展开其子节点 */
  async expandRow(orgName: string): Promise<void> {
    const row = this.rowByOrgName(orgName).first()
    const icon = row.locator('.ant-table-row-expand-icon')
    await expect(icon).toBeVisible()
    await icon.click()
    await this.waitForTableLoaded()
  }

  // ==================== 行定位 ====================

  /** 根据组织名称定位组织行 */
  rowByOrgName(orgName: string): Locator {
    return this.tableBody.locator('tr', { has: this.page.getByText(orgName, { exact: true }) })
  }

  /** 断言指定组织行在列表中可见 */
  async expectRowVisible(orgName: string): Promise<void> {
    await expect(this.rowByOrgName(orgName)).toBeVisible()
  }

  /** 断言指定组织行在列表中不可见 */
  async expectRowHidden(orgName: string): Promise<void> {
    await expect(this.rowByOrgName(orgName)).toHaveCount(0)
  }

  /** 断言指定组织所在行包含指定文本（如组织编码、联系电话） */
  async expectRowContains(orgName: string, text: string): Promise<void> {
    await expect(this.rowByOrgName(orgName)).toContainText(text)
  }

  /**
   * 断言组织存在子节点：其所在行出现树展开图标。
   * AntD 树表格有子节点的行会渲染展开图标 .ant-table-row-expand-icon。
   */
  async expectHasChildren(orgName: string): Promise<void> {
    await expect(this.rowByOrgName(orgName).locator('.ant-table-row-expand-icon')).toBeVisible()
  }

  // ==================== 新增 ====================

  /** 点击「新增」打开新增弹窗（顶级） */
  async clickAdd(): Promise<void> {
    await this.addButton.click()
    await expect(this.modal).toBeVisible()
  }

  /** 点击指定组织行的「子节点」打开新增弹窗（指定上级） */
  async clickAddChild(parentOrgName: string): Promise<void> {
    const row = this.rowByOrgName(parentOrgName).first()
    await row.getByRole('button', { name: /子\s*节\s*点/ }).click()
    await expect(this.modal).toBeVisible()
  }

  // ==================== 编辑 / 删除 ====================

  /** 点击指定组织行的「编辑」 */
  async clickEdit(orgName: string): Promise<void> {
    const row = this.rowByOrgName(orgName).first()
    await row.getByRole('button', { name: /编\s*辑/ }).click()
    await expect(this.modal).toBeVisible()
  }

  /** 点击指定组织行的「删除」并确认（存在多个同名行时删除第一个） */
  async clickDelete(orgName: string): Promise<void> {
    const row = this.rowByOrgName(orgName).first()
    await row.getByRole('button', { name: /删\s*除/ }).click()
    // 确认删除弹窗（用 :visible 避免历史隐藏弹窗干扰严格模式）
    const confirm = this.page.locator('.ant-modal-confirm:visible')
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: /确\s*定/ }).click()
  }

  // ==================== 弹窗表单 ====================

  /** 校验必填项提示 */
  async expectRequiredErrors(): Promise<void> {
    await expect(this.modal.locator('.ant-form-item-explain-error')).toHaveCount(0)
  }

  /**
   * 填写新增/编辑弹窗表单。
   * 注意：上级组织通过「子节点」按钮（clickAddChild）预选，无需在此处理。
   */
  async fillForm(data: OrgFormData): Promise<void> {
    await this.modal.getByPlaceholder('请输入组织编码').fill(data.orgCode)
    await this.modal.getByPlaceholder('请输入组织名称').fill(data.orgName)

    // 组织类型（a-select：表单默认 orgType=2，弹窗打开时已有选中值「分公司」，
    // 不显示 placeholder，需定位对应 form-item 内的 select 控件点击展开）
    const typeField = this.modal.locator('.ant-form-item', { hasText: '组织类型' }).first()
    await typeField.locator('.ant-select-selector').click()
    await this.page
      .locator('.ant-select-dropdown:visible')
      .getByText(this.orgTypeLabel(data.orgType), { exact: true })
      .click()

    if (data.phone !== undefined) {
      await this.modal.getByPlaceholder('请输入联系电话').fill(data.phone)
    }
    if (data.email !== undefined) {
      await this.modal.getByPlaceholder('请输入电子邮箱').fill(data.email)
    }
    if (data.address !== undefined) {
      await this.modal.getByPlaceholder('请输入地址').fill(data.address)
    }

    // 状态（单选）
    const statusText = data.status === 1 ? '启用' : '禁用'
    await this.modal.locator('.ant-radio-wrapper', { hasText: statusText }).click()
  }

  /** 提交弹窗（点确定） */
  async submitModal(): Promise<void> {
    await this.modal.getByRole('button', { name: /确\s*定/ }).click()
    // 等待弹窗关闭：this.modal 已限定 :visible，关闭后无可见弹窗，count=0
    await expect(this.modal).toHaveCount(0)
  }

  /** 取消弹窗 */
  async cancelModal(): Promise<void> {
    await this.modal.getByRole('button', { name: /取\s*消/ }).click()
    await expect(this.modal).toHaveCount(0)
  }

  /** 断言成功提示 */
  async expectSuccessMessage(text: string): Promise<void> {
    await expect(this.page.locator('.ant-message', { hasText: text })).toBeVisible()
  }

  // ==================== 工具 ====================

  /** 组织类型数值转文案 */
  private orgTypeLabel(type: number): string {
    const map: Record<number, string> = { 1: '公司', 2: '分公司', 3: '部门' }
    return map[type] || '未知'
  }
}
