import { defineConfig, devices } from '@playwright/test'
import { config as loadDotenv } from 'dotenv'
import { resolve } from 'path'

/**
 * Playwright 配置
 *
 * 测试目录结构：
 *   tests/
 *   ├── specs/   端到端测试用例
 *   ├── auth.setup.ts  认证 Setup（登录一次并保存登录态）
 *   ├── fixtures/ 自定义 Fixtures
 *   ├── pages/   Page Object Model 页面对象
 *   ├── utils/   测试工具函数
 *   ├── test-results/    失败产物（截图/视频/Trace）
 *   └── playwright-report/ HTML 测试报告
 *
 * 登录态复用：通过 auth-setup 项目先登录并保存 storageState，
 * 业务测试项目（chromium）通过 storageState 复用登录态，全程只需登录一次。
 *
 * 环境变量从 .env.test 加载，本地可用 .env.test.local 覆盖（不提交到 git）。
 */

// 加载测试环境变量
// 注意：dotenv 默认不覆盖已有变量，因此 .env.test.local 必须设置 override: true，
// 才能正确覆盖 .env.test 中的默认值（否则 E2E_HEADED 等永远取 .env.test 的值）。
loadDotenv({ path: resolve(process.cwd(), '.env.test') })
loadDotenv({ path: resolve(process.cwd(), '.env.test.local'), override: true })

const baseURL = process.env.E2E_BASE_URL || 'http://localhost:5173'

export default defineConfig({
  // 测试文件位置（含 auth.setup.ts 与 specs/ 下用例）
  testDir: './tests',
  // 测试文件命名规则
  testMatch: '**/*.spec.ts',

  // 完全并行执行
  fullyParallel: true,

  // 重试策略：CI 环境重试 1 次，本地不重试
  retries: process.env.CI ? 1 : 0,

  // 并行 worker 数量
  workers: process.env.CI ? 2 : undefined,

  // Reporter：本地用 list，CI 用 github 便于查看
  reporter: process.env.CI
    ? [
        ['html', { outputFolder: './tests/playwright-report', open: 'never' }],
        ['github']
      ]
    : [
        ['html', { outputFolder: './tests/playwright-report', open: 'on-failure' }],
        ['list']
      ],

  // 全局超时
  timeout: Number(process.env.E2E_TIMEOUT || 30_000),
  expect: {
    // expect 断言超时
    timeout: Number(process.env.E2E_TIMEOUT || 30_000) / 2
  },

  // 输出目录（失败产物：截图/视频/Trace）
  outputDir: './tests/test-results',

  use: {
    // 被测应用地址
    baseURL,
    // 有头/无头模式
    headless: process.env.E2E_HEADED !== 'true',
    // 浏览器视图大小
    viewport: { width: 1440, height: 900 },
    // 录制失败截图与视频
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
    // 页面交互后的自动等待策略
    actionTimeout: Number(process.env.E2E_TIMEOUT || 30_000) / 2,
    // 忽略 HTTPS 证书错误
    ignoreHTTPSErrors: true
  },

  // 仅在测试本地（localhost/127.0.0.1）环境时自动启动 dev server；
  // 测试远程环境（如 E2E_BASE_URL=http://10.8.0.7:30082）时不启动本地前端，
  // 直接访问远程站点，避免 webServer 与远程 baseURL 冲突导致浏览器异常关闭。
  webServer: /localhost|127\.0\.0\.1/.test(baseURL)
    ? {
        command: 'pnpm dev',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000
      }
    : undefined,

  // 各浏览器项目
  projects: [
    // 认证 Setup：先登录一次并保存登录态（storageState），
    // 供下方业务测试项目复用，避免每个用例重复登录。
    {
      name: 'auth-setup',
      testMatch: 'auth.setup.ts',
      use: { ...devices['Desktop Chrome'] }
    },
    {
      name: 'chromium',
      testIgnore: 'auth.setup.ts',
      use: { ...devices['Desktop Chrome'] },
      // 依赖 auth-setup 先执行完成登录并生成 user.json。
      // 登录态不通过 storageState 注入（该机制对远程 origin 的
      // localStorage 注入不可靠），改由 authedPage fixture 手动注入。
      dependencies: ['auth-setup']
    }
    // {
    //   name: 'firefox',
    //   use: { ...devices['Desktop Firefox'] }
    // },
    // {
    //   name: 'webkit',
    //   use: { ...devices['Desktop Safari'] }
    // }
  ]
})
