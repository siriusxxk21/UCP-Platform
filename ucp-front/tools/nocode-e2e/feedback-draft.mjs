import assert from 'node:assert/strict'
import { expect } from '@playwright/test'
import { resolve } from 'node:path'

/** 只编辑当前浏览器内存中的反馈草稿，不向反馈接口提交消息。 */
export async function verifyFeedbackDraft(page, prefix, output, width) {
  const launcher = page.getByRole('button', { name: '提交系统反馈', exact: true })
  await launcher.click()
  const panel = page.getByRole('dialog', { name: '问题与需求反馈', exact: true })
  await expect(panel).toBeVisible()
  const title = panel.getByRole('textbox', { name: '反馈标题', exact: true })
  const description = panel.getByRole('textbox', { name: '反馈描述', exact: true })
  await title.fill('布局验收 ' + prefix)
  await description.fill('仅保留于测试浏览器，检查窄屏打开和最小化后的草稿。')
  if (!(await panel.locator('.feedback-image').count()))
    await panel.locator('input[type=file]').setInputFiles({
      name: prefix + '.png',
      mimeType: 'image/png',
      buffer: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9o0AAAAASUVORK5CYII=',
        'base64'
      )
    })
  await expect(panel.locator('.feedback-image')).toHaveCount(1)
  await panel.getByRole('button', { name: '最小化反馈框', exact: true }).click()
  await expect(panel).toBeHidden()
  await expect(launcher.locator('.draft-dot')).toBeVisible()
  await launcher.click()
  await expect(title).toHaveValue('布局验收 ' + prefix)
  await expect(description).toHaveValue('仅保留于测试浏览器，检查窄屏打开和最小化后的草稿。')
  await expect(panel.locator('.feedback-image')).toHaveCount(1)
  const box = await panel.boundingBox()
  assert.ok(box && box.x >= 0 && box.x + box.width <= width + 1, '反馈面板应完整位于当前视口内')
  await page.screenshot({
    path: resolve(output, `feedback-draft-${width}.png`),
    fullPage: true,
    animations: 'disabled'
  })
  await panel.getByRole('button', { name: '关闭反馈框并保留草稿', exact: true }).click()
  await expect(panel).toBeHidden()
}
