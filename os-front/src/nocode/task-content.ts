import { richTextSummary, safeRichTextHtml } from './rich-text'

// 沿用服务端 description 字段上限，包含 HTML 排版标记；不改历史版本的数据结构。
export const TASK_CONTENT_MAX_LENGTH = 4000

/** 历史纯文字先转义，再与新富文本走同一安全渲染入口。 */
export function taskContentHtml(value?: string | null): string {
  if (!value?.trim()) return ''
  let html = value
  if (
    !/<\/?(?:[pbiusa]|div|br|strong|em|h[1-6]|ul|ol|li|table|span|mark|img|pre|code|blockquote|hr|script|iframe|svg)\b[^>]*>/i.test(
      value
    )
  ) {
    const text = document.createElement('div')
    text.textContent = value
    html = `<p>${text.innerHTML.replace(/\r?\n/g, '<br>')}</p>`
  }
  const safe = safeRichTextHtml(html, { richContent: true })
  if (safe === '—' && value.trim() !== '—') return ''
  const doc = new DOMParser().parseFromString(safe, 'text/html')
  return doc.body.textContent?.trim() || doc.querySelector('img, hr') ? safe : ''
}

export function taskContentSummary(value?: string | null): string {
  const html = taskContentHtml(value)
  return html ? richTextSummary(html) : ''
}
