import { watch } from 'vue'
import { toPinyinInitials } from '../utils/pinyin'
import type { ResourceKind } from '../types/nocode/application'

export type CodeKind = 'OBJECT' | 'APP' | ResourceKind

/** 复用底座拼音转换。对象预留物理表 biz_ 前缀，避免超过 63 字符。 */
export function resourceCode(name: string, kind: CodeKind): string {
  const initials = toPinyinInitials(name.trim())
  if (!/[a-z0-9]/.test(initials)) return ''
  return `${kind.toLowerCase()}_${initials}`.slice(0, kind === 'OBJECT' ? 59 : 64)
}

/** 字段编码采用 c_ + 名称拼音首字母，遵循物理列名 63 字符上限。 */
export function fieldCode(name: string): string {
  const initials = toPinyinInitials(name.trim())
  return /[a-z0-9]/.test(initials) ? `c_${initials}`.slice(0, 63) : ''
}

/** 新建时跟随名称；手填后停止覆盖，清空编码后在下次改名时恢复自动生成。 */
export function useResourceCode(options: {
  name: () => string
  kind: () => CodeKind
  setCode: (code: string) => void
  enabled?: () => boolean
}) {
  let automatic = true
  watch(
    options.name,
    name => {
      if (automatic && (options.enabled?.() ?? true)) options.setCode(resourceCode(name, options.kind()))
    },
    { flush: 'sync' }
  )
  return {
    reset() {
      automatic = true
    },
    changeCode(code: string) {
      automatic = !code
      options.setCode(code)
    }
  }
}

/** 物理名只跟随默认建议值；用户自定义的表名保持原样。 */
export function suggestedTableName(code: string, previousCode = '', tableName = ''): string {
  if (tableName && tableName !== `biz_${previousCode}`.slice(0, 63)) return tableName
  return code ? `biz_${code}`.slice(0, 63) : ''
}

/** 新增明细始终采用当前前缀，并为后缀预留长度；已有主表的物理名不改写。 */
export function suggestedDetailTableName(mainTable: string, detailCode: string): string {
  const base = mainTable.replace(/^(?:nocode_data_|biz_)/, '') || 'object'
  const suffix = `_${detailCode}`
  return `biz_${base}`.slice(0, 63 - suffix.length) + suffix
}
