const discardedTags = new Set(['script', 'style', 'iframe', 'object', 'svg', 'math', 'form', 'video', 'audio'])
const displayTags = new Set([
  'p',
  'div',
  'br',
  'strong',
  'em',
  'u',
  's',
  'code',
  'pre',
  'blockquote',
  'ul',
  'ol',
  'li',
  'h1',
  'h2',
  'h3',
  'hr',
  'table',
  'thead',
  'tbody',
  'tr',
  'td',
  'th',
  'span',
  'mark',
  'sub',
  'sup'
])
const blockTags = new Set(['p', 'div', 'blockquote', 'li', 'h1', 'h2', 'h3', 'tr'])
const safeColor = (value: string) => /^(?:#[\da-f]{3,8}|rgba?\([\d\s.,%]+\))$/i.test(value)

/** 列表只重建允许的排版节点，不继承业务 HTML 中的事件、链接或其他属性。 */
export function safeRichTextHtml(value: unknown, options: { richContent?: boolean } = {}): string {
  if (!value) return '—'
  const doc = new DOMParser().parseFromString(String(value), 'text/html')
  const output = doc.createElement('div')
  const copy = (node: Node, parent: Element): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      parent.append(doc.createTextNode(node.textContent || ''))
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return
    const source = node as HTMLElement
    const tag = source.tagName.toLowerCase()
    if (discardedTags.has(tag)) return
    if (tag === 'img') {
      const src = source.getAttribute('src') || ''
      if (options.richContent && safeRichTextUrl(src, true)) {
        const img = doc.createElement('img')
        img.setAttribute('src', src)
        img.setAttribute('alt', source.getAttribute('alt') || '')
        img.setAttribute('referrerpolicy', 'no-referrer')
        parent.append(img)
        return
      }
      parent.append(doc.createTextNode('[图片]'))
      return
    }
    const safeLink = options.richContent && tag === 'a' && safeRichTextUrl(source.getAttribute('href') || '')
    const safeTag = tag === 'b' ? 'strong' : tag === 'i' ? 'em' : tag === 'a' && !safeLink ? 'span' : tag
    const target = displayTags.has(safeTag) || safeLink ? doc.createElement(safeTag) : parent
    if (target !== parent) {
      if (safeLink) {
        target.setAttribute('href', source.getAttribute('href') || '')
        target.setAttribute('target', '_blank')
        target.setAttribute('rel', 'noopener noreferrer')
      }
      if (options.richContent && ['td', 'th'].includes(tag)) {
        for (const name of ['colspan', 'rowspan']) {
          const span = Number(source.getAttribute(name))
          if (Number.isInteger(span) && span > 0 && span <= 100) target.setAttribute(name, String(span))
        }
      }
      if (options.richContent && ['ul', 'li'].includes(tag)) {
        const type = source.getAttribute('data-type')
        if (type === 'taskList' || type === 'taskItem') target.setAttribute('data-type', type)
        if (type === 'taskItem')
          target.setAttribute('data-checked', String(source.getAttribute('data-checked') === 'true'))
      }
      if (safeColor(source.style.color)) (target as HTMLElement).style.color = source.style.color
      if (safeColor(source.style.backgroundColor))
        (target as HTMLElement).style.backgroundColor = source.style.backgroundColor
      if (/^(?:1\d|2\d|3[0-2])px$/.test(source.style.fontSize))
        (target as HTMLElement).style.fontSize = source.style.fontSize
      if (['left', 'center', 'right', 'justify'].includes(source.style.textAlign))
        (target as HTMLElement).style.textAlign = source.style.textAlign
      parent.append(target)
    }
    for (const child of Array.from(source.childNodes)) copy(child, target)
  }
  for (const node of Array.from(doc.body.childNodes)) copy(node, output)
  return output.innerHTML || '—'
}

/** 完整内容按需保留安全链接与图片；默认列表展示仍只输出文字与排版。 */
function safeRichTextUrl(value: string, image = false): boolean {
  if (
    !value ||
    Array.from(value).some(character => {
      const code = character.charCodeAt(0)
      return code <= 32 || code === 127 || character === '\\'
    })
  )
    return false
  if (/^\/(?!\/)/.test(value) || /^#[\w-]+$/.test(value)) return true
  if (image && /^data:image\/(?:png|jpeg|gif|webp);base64,[a-z\d+/=]+$/i.test(value)) return true
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol)
  } catch {
    return false
  }
}

/** 富文本纯文字摘要保留段落和换行，供悬停提示与文字回退使用。 */
export function richTextSummary(value: unknown): string {
  if (!value) return '—'
  const doc = new DOMParser().parseFromString(String(value), 'text/html')
  let result = ''
  const lineBreak = () => {
    if (result && !result.endsWith('\n')) result += '\n'
  }
  const read = (node: Node): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      result += node.textContent || ''
      return
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return
    const element = node as Element
    const tag = element.tagName.toLowerCase()
    if (discardedTags.has(tag)) return
    if (tag === 'br') return lineBreak()
    if (tag === 'img') {
      result += '[图片]'
      return
    }
    if (blockTags.has(tag)) lineBreak()
    for (const child of Array.from(element.childNodes)) read(child)
    if (blockTags.has(tag)) lineBreak()
  }
  for (const node of Array.from(doc.body.childNodes)) read(node)
  return result.trim() || '—'
}
