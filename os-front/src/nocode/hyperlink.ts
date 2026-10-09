export interface HyperlinkValue {
  link: string
  text: string
}
export function hyperlinkParts(value: unknown): HyperlinkValue {
  if (typeof value === 'string') return { link: value, text: '' }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const v = value as Partial<HyperlinkValue>
    return { link: typeof v.link === 'string' ? v.link : '', text: typeof v.text === 'string' ? v.text : '' }
  }
  return { link: '', text: '' }
}
export function hyperlinkError(value: unknown): string | null {
  const { link: address, text } = hyperlinkParts(value),
    link = address.trim()
  if (!link && !text.trim()) return null
  if (link.length > 4096 || text.trim().length > 500) return '地址最多 4096 字符，显示文字最多 500 字符'
  try {
    const url = new URL(link)
    if (!/^https?:\/\//i.test(link) || url.username || url.password || /[\s\\]/.test(link) || !url.hostname)
      throw new Error()
    return null
  } catch {
    return '请填写有效的 HTTP 或 HTTPS 链接地址'
  }
}
export function hyperlinkHref(value: unknown): string | undefined {
  return hyperlinkError(value) ? undefined : hyperlinkParts(value).link.trim() || undefined
}
