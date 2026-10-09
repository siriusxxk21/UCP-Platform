import { toRaw } from 'vue'
import type { Rule } from '@form-create/ant-design-vue'

/**
 * 对象字段物料包含校验函数，不能按持久化 JSON 或 structuredClone 复制。
 * 只复制规则的可变数据容器，保留运行时函数；每次拖入都得到独立 props/validate。
 */
export function cloneFormRule(rule: Rule): Rule {
  const copies = new WeakMap<object, unknown>()
  function copy(value: unknown): unknown {
    if (value === null || typeof value !== 'object') return value
    const raw = toRaw(value)
    if (copies.has(raw)) return copies.get(raw)
    if (raw instanceof Date) return new Date(raw.getTime())
    if (raw instanceof RegExp) return new RegExp(raw.source, raw.flags)
    if (!Array.isArray(raw) && Object.getPrototypeOf(raw) !== Object.prototype) return raw
    const result: unknown[] | Record<string, unknown> = Array.isArray(raw) ? [] : {}
    copies.set(raw, result)
    for (const [key, item] of Object.entries(raw)) (result as Record<string, unknown>)[key] = copy(item)
    return result
  }
  return copy(rule) as Rule
}
