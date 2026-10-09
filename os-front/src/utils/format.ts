import {TEMPLATE_TYPE_OPTIONS} from '@/constants'
import type {TemplateType} from '@/types'

/**
 * 从 TEMPLATE_TYPE_OPTIONS 构建模板类型映射
 */
const templateTypeMap: Record<string, string> = TEMPLATE_TYPE_OPTIONS.reduce(
  (acc, item) => {
    acc[item.value] = item.label
    return acc
  },
  {} as Record<string, string>,
)

/**
 * 获取模板类型名称
 * @param type 模板类型
 * @returns 类型名称
 */
export function getTemplateTypeName(type?: TemplateType | string): string {
  if (!type)
    return '-'
  return templateTypeMap[type as string] || type
}

/**
 * 转换为驼峰命名
 * @param str 原始字符串
 * @param capitalizeFirst 是否首字母大写
 * @returns 驼峰命名字符串
 */
export function toCamelCase(str: string, capitalizeFirst: boolean = false): string {
  if (!str)
    return str

  // 去掉表前缀
  const cleanStr = str.replace(/^(sys_|t_|tb_)/, '')

  const parts = cleanStr.split('_')
  let result = ''

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    if (!part)
      continue
    if (i === 0 && !capitalizeFirst) {
      result += part.toLowerCase()
    }
    else {
      result += part.charAt(0).toUpperCase() + part.slice(1).toLowerCase()
    }
  }

  return result
}

/**
 * 获取文件语言类型
 * @param fileName 文件名
 * @returns 语言类型
 */
export function getFileLanguage(fileName: string): string {
  if (!fileName)
    return 'text'
  if (fileName.endsWith('.java'))
    return 'java'
  if (fileName.endsWith('.vue'))
    return 'html'
  if (fileName.endsWith('.js') || fileName.endsWith('.ts'))
    return 'javascript'
  if (fileName.endsWith('.xml'))
    return 'xml'
  if (fileName.endsWith('.sql'))
    return 'sql'
  if (fileName.endsWith('.md'))
    return 'markdown'
  return 'text'
}

/**
 * 格式化日期时间
 * @param date 日期
 * @returns 格式化后的字符串
 */
export function formatDateTime(date: string | number | Date): string {
  if (!date)
    return '-'
  const d = date instanceof Date ? date : new Date(date)
  if (Number.isNaN(d.getTime()))
    return '-'

  const pad = (value: number) => String(value).padStart(2, '0')
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/**
 * 格式化文件大小
 * @param bytes 字节数
 * @returns 格式化后的字符串
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0)
    return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${Number.parseFloat((bytes / k ** i).toFixed(2))} ${sizes[i]}`
}
