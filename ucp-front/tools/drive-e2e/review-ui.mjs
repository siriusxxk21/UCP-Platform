/** 网盘前端走查，复用 verify.mjs 的真实服务与已登记夹具，禁止操作其他文件。 */
import assert from 'node:assert/strict'
import { expect } from '@playwright/test'

export async function reviewDriveUi({
  page,
  api,
  spaceId,
  uploadDir,
  output,
  checks,
  origin,
  prefix,
  payload,
  upload
}) {
  const teamUrl = path => origin + '/drive/team?' + new URLSearchParams({ space: spaceId, path })
  const fileName = '前端走查-文件预览与收藏.txt'
  const file = await upload(uploadDir, fileName)
  const entry = name => page.locator('.vuefinder__explorer__item-name').filter({ hasText: name })
  await page.goto(teamUrl('/'))
  await page.waitForLoadState('networkidle')
  await entry(/^来源目录$/).dblclick()
  await entry(/^上传目录$/).dblclick()
  await expect(entry(fileName)).toBeVisible()
  await page.waitForLoadState('networkidle')
  const externalFile = await upload(uploadDir, '刷新后新增.txt')
  await page
    .locator('.drive-workspace__actions')
    .getByRole('button', { name: /刷\s*新$/ })
    .click()
  await expect(entry(externalFile.name)).toBeVisible()
  await expect(entry(fileName)).toBeVisible()
  checks.push('前端：逐层进入目录后页头刷新仍停留当前目录，并显示其他会话新增文件')

  await entry(fileName).dblclick()
  const drawer = page.locator('.ant-drawer-open')
  await expect(drawer.locator('pre')).toHaveText(payload.trim())
  await expect(drawer).toContainText('来源目录 / 上传目录')
  await drawer.getByRole('button', { name: /收\s*藏$/ }).click()
  await expect(drawer.getByRole('button', { name: /已收藏$/ })).toBeVisible()
  await drawer.locator('.ant-drawer-close').click()
  await page.goto(origin + '/drive/favorite')
  const favoriteRow = page.getByRole('row').filter({ hasText: fileName })
  await expect(favoriteRow).toBeVisible()
  await favoriteRow.locator('.drive-name--link').focus()
  await page.keyboard.press('Enter')
  await expect(drawer.locator('pre')).toHaveText(payload.trim())
  await drawer.getByRole('button', { name: /已收藏$/ }).click()
  await expect(favoriteRow).toHaveCount(0)
  await drawer.locator('.ant-drawer-close').click()
  checks.push('前端：文件详情真实文本与目录正确，收藏可键盘打开，抽屉取消收藏同步刷新列表')

  await page.goto(teamUrl('/来源目录/上传目录'))
  await page.waitForLoadState('networkidle')
  await entry(fileName).click()
  await page.getByTitle('删除', { exact: true }).click()
  const modal = page.locator('.vuefinder__modal-layout__container')
  await expect(modal).toContainText('移入回收站')
  await expect(modal).not.toContainText('此操作不能撤销')
  await modal.getByRole('checkbox').check()
  await modal.getByRole('button', { name: '移入回收站', exact: true }).click()
  await expect(entry(fileName)).toHaveCount(0)
  await page.goto(origin + '/drive/trash')
  const spaceSelect = page.locator('#drive-trash-space')
  await spaceSelect.fill(prefix)
  await page.locator('.ant-select-dropdown:visible').getByTitle(prefix, { exact: true }).click()
  const trashRow = page.getByRole('row').filter({ hasText: fileName })
  await expect(trashRow).toBeVisible()
  await trashRow.getByRole('button', { name: /还\s*原$/ }).click()
  await page
    .locator('.ant-popover:visible')
    .getByRole('button', { name: /确\s*定$/ })
    .click()
  await expect(trashRow).toHaveCount(0)
  assert.equal((await api('/drive/entry/get?id=' + file.id)).parentId, uploadDir)
  checks.push('前端：删除确认准确说明回收站语义，真实页面删除后在回收站还原到原目录')

  await page.setViewportSize({ width: 1024, height: 768 })
  await page.goto(teamUrl('/来源目录/上传目录'))
  await page.waitForLoadState('networkidle')
  const header = await page.locator('.drive-workspace__header').boundingBox()
  assert.ok(header && header.x + header.width <= 1024)
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth))
  await page.screenshot({ path: output + '/ui-workspace-1024.png' })
  await entry(fileName).dblclick()
  await expect(drawer.locator('pre')).toHaveText(payload.trim())
  await page.screenshot({ path: output + '/ui-detail-1024.png' })
  await drawer.locator('.ant-drawer-close').click()
  await page.goto(origin + '/drive/recent')
  await expect(page.getByRole('row').filter({ hasText: fileName })).toBeVisible()
  await page.screenshot({ path: output + '/ui-recent-1024.png' })
  checks.push('前端：1024px 下页头不溢出，详情和最近使用操作可用，保留横向表格滚动')

  // 列表与治理入口只读走查，不修改既有空间或业务文件配置。
  for (const route of ['my-file', 'shared', 'space', 'business']) {
    await page.goto(origin + '/drive/' + route)
    await page.waitForLoadState('networkidle')
    await expect(page.locator(route === 'my-file' ? '.drive-workspace' : '.drive-page')).toBeVisible()
    await expect(page.locator('.ant-result-error')).toHaveCount(0)
    if (route === 'shared') {
      const table = await page.locator('.ant-tabs-tabpane-active .drive-table').boundingBox()
      if (!table || table.height <= 500) {
        console.log(
          await page.locator('.ant-tabs-tabpane-active .drive-table').evaluate(element => {
            const ancestors = []
            for (let node = element; node; node = node.parentElement) {
              const style = getComputedStyle(node)
              ancestors.push({
                class: node.className,
                height: node.clientHeight,
                display: style.display,
                flex: style.flex,
                direction: style.flexDirection
              })
            }
            return ancestors
          })
        )
      }
      assert.ok(table && table.height > 500, '共享列表应沿标签页高度链填充剩余空间')
    }
    await page.screenshot({ path: output + '/ui-' + route + '-1024.png' })
  }
  checks.push('前端：我的文件、与我共享、空间管理、业务文件入口只读走查无页面运行错误')
}
