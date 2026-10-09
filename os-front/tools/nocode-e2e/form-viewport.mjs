import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

/** 读取实际布局而非模拟 CSS；内部横向明细表可以滚动，办理窗口本身不能裁切表单。 */
export async function verifyFormViewport(container, output, name) {
  const layout = await container.evaluate(body => {
    const rect = element => {
      const value = element.getBoundingClientRect()
      return {
        left: value.left,
        right: value.right,
        top: value.top,
        bottom: value.bottom,
        width: value.width,
        height: value.height
      }
    }
    const visible = element => {
      const r = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return r.width > 2 && r.height > 2 && style.visibility !== 'hidden' && style.display !== 'none'
    }
    const elements = [
      ...body.querySelectorAll(
        '.ant-form-item-label label, .detail-section > .detail-heading h3, .grid-hint, .editor-footer button'
      )
    ]
      .filter(visible)
      .map(element => ({ text: element.textContent.trim(), ...rect(element) }))
    const overflow = [...body.querySelectorAll('*')]
      .filter(element => visible(element) && element.scrollWidth > element.clientWidth + 1)
      .slice(0, 20)
      .map(element => ({
        tag: element.tagName,
        class: element.className,
        ...rect(element),
        scrollWidth: element.scrollWidth,
        clientWidth: element.clientWidth,
        scrollLeft: element.scrollLeft,
        overflowX: getComputedStyle(element).overflowX
      }))
    const launcher = document.querySelector('.feedback-launcher')
    const feedback = launcher && visible(launcher) ? rect(launcher) : null
    const bodyRect = body.getBoundingClientRect()
    const occluded = feedback
      ? [
          ...body.querySelectorAll(
            'input:not([type=hidden]), textarea, button, [role=combobox], .ant-form-item-label label, .grid-hint'
          )
        ]
          .filter(visible)
          .filter(element => {
            const r = element.getBoundingClientRect()
            return (
              Math.min(r.right, feedback.right, bodyRect.right, innerWidth) >
                Math.max(r.left, feedback.left, bodyRect.left, 0) + 1 &&
              Math.min(r.bottom, feedback.bottom, bodyRect.bottom, innerHeight) >
                Math.max(r.top, feedback.top, bodyRect.top, 0) + 1
            )
          })
          .map(element => ({
            tag: element.tagName,
            text: element.getAttribute('aria-label') || element.textContent.trim(),
            ...rect(element)
          }))
      : []
    return {
      viewport: innerWidth,
      body: {
        ...rect(body),
        scrollWidth: body.scrollWidth,
        clientWidth: body.clientWidth,
        scrollLeft: body.scrollLeft
      },
      elements,
      overflow,
      feedback,
      occluded
    }
  })
  await writeFile(resolve(output, name + '.json'), JSON.stringify(layout, null, 2))
  assert.ok(layout.feedback, '已登录办理页保留反馈入口')
  assert.equal(
    layout.occluded.length,
    0,
    '反馈入口不能遮挡表单文字或操作：' + layout.occluded.map(item => item.text || item.tag).join('、')
  )
  assert.ok(layout.body.scrollWidth <= layout.body.clientWidth + 1, '办理表单容器不能横向溢出并裁切文字')
  assert.ok(Math.abs(layout.body.scrollLeft) <= 1, '表单滚动定位不能推动整个办理容器横向滚动')
  assert.ok(layout.elements.length > 0, '须检查真实表单标签和操作')
  for (const element of layout.elements) {
    assert.ok(element.left >= layout.body.left - 1, '文字或操作被左侧裁切：' + element.text)
    assert.ok(element.right <= layout.body.right + 1, '文字或操作超出右侧：' + element.text)
  }
  return layout
}
