/**
 * 测试配置工具
 * 统一读取 .env.test / .env.test.local 中的环境变量，提供默认值。
 */

export interface TestConfig {
  /** 被测应用基础地址 */
  baseURL: string
  /** 测试账号用户名 */
  username: string
  /** 测试账号密码 */
  password: string
}

/**
 * 读取当前测试配置。
 * 可通过 process.env 覆盖（由 playwright.config.ts 加载 .env.test）。
 */
export function getTestConfig(): TestConfig {
  return {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    username: process.env.E2E_USERNAME || 'admin',
    password: process.env.E2E_PASSWORD || 'admin123',
  }
}

/** 生成全局唯一的测试数据标识，避免用例间数据冲突 */
export function uniqueId(prefix = 'e2e'): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}
