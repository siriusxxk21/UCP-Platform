/**
 * 随机数据生成工具
 * 用于在测试中生成唯一、可预期的测试数据。
 */

const CHARSET = 'abcdefghijklmnopqrstuvwxyz0123456789'

/** 生成随机字符串 */
export function randomString(length = 8, prefix = ''): string {
  let result = prefix
  for (let i = 0; i < length; i++) {
    result += CHARSET.charAt(Math.floor(Math.random() * CHARSET.length))
  }
  return result
}

/** 生成随机邮箱 */
export function randomEmail(prefix = 'e2e'): string {
  return `${randomString(8, `${prefix}_`)}@example.com`
}

/** 生成随机手机号（中国大陆格式） */
export function randomPhone(): string {
  const prefixes = ['13', '15', '17', '18']
  const head = prefixes[Math.floor(Math.random() * prefixes.length)]
  let tail = ''
  for (let i = 0; i < 9; i++) {
    tail += Math.floor(Math.random() * 10)
  }
  return `${head}${tail}`
}
