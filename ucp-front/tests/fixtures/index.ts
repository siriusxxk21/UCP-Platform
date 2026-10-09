/**
 * 自定义 Fixtures 统一入口
 *
 * 用法：
 *   import { test, expect } from '../fixtures'
 *
 * 可用 fixtures：
 *   - page:      Playwright 原生页面
 *   - loginPage: 登录页对象
 *   - authedPage: 已登录的页面
 */
export * from './authenticatedUser'
