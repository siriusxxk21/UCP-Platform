// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { richTextSummary, safeRichTextHtml } from './rich-text'

describe('富文本列表展示', () => {
  it('保留段落与强调，同时丢弃脚本、事件和危险链接', () => {
    const source =
      '<p><strong>采购</strong><em>说明</em></p><p>下一行<br>续行</p>' +
      '<script>window.unwanted = true</script><a href="javascript:alert(1)" onclick="alert(1)">查看</a>' +
      '<img src="x" onerror="alert(1)">'
    const html = safeRichTextHtml(source)
    const doc = new DOMParser().parseFromString(html, 'text/html')
    expect(doc.querySelector('strong')?.textContent).toBe('采购')
    expect(doc.querySelector('em')?.textContent).toBe('说明')
    expect(doc.querySelectorAll('p')).toHaveLength(2)
    expect(doc.querySelector('script,a,img,[onclick],[onerror]')).toBeNull()
    expect(doc.body.textContent).toContain('查看[图片]')
    expect(richTextSummary(source)).toBe('采购说明\n下一行\n续行\n查看[图片]')
  })
  it('只接受受控颜色和字号，空值显示占位', () => {
    const html = safeRichTextHtml('<span style="color:#cf1322;font-size:999px" onclick="alert(1)">重点</span>')
    const span = new DOMParser().parseFromString(html, 'text/html').querySelector('span')
    expect(span?.style.color).toBe('rgb(207, 19, 34)')
    expect(span?.style.fontSize).toBe('')
    expect(span?.hasAttribute('onclick')).toBe(false)
    expect(safeRichTextHtml(null)).toBe('—')
  })
})
