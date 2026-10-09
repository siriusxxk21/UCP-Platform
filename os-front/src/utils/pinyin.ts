import {pinyin} from 'pinyin-pro'

/**
 * 将中文文本转换为拼音首字母缩写
 * 非中文字符（字母、数字、下划线）保留原样，其余字符忽略
 */
export function toPinyinInitials(text: string): string {
  if (!text)
    return ''
  const result = pinyin(text, {
    pattern: 'first',
    toneType: 'none',
    separator: '',
    nonZh: 'consecutive',
  })
  // 只保留字母、数字、下划线，统一转小写
  return result.replace(/\W/g, '').toLowerCase()
}

/** 将中文文本转换为不带声调、无分隔符的全拼，供前端小数据量筛选使用。 */
export function toPinyin(text: string): string {
  if (!text)
    return ''
  return pinyin(text, {
    pattern: 'pinyin',
    toneType: 'none',
    separator: '',
    nonZh: 'consecutive',
  }).replace(/\W/g, '').toLowerCase()
}
