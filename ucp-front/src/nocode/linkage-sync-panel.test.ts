// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Antd from 'ant-design-vue'
import type {
  LinkageBackfillPage,
  LinkagePreviewPage,
  LinkageSyncField,
  LinkageSyncOverview
} from '@/types/nocode/linkage-sync'
import { LINKAGE_CONFLICT_CODE } from './linkage-sync'

const api = vi.hoisted(() => ({
  linkageOverview: vi.fn(),
  linkagePreview: vi.fn(),
  linkageBackfill: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ applications: api, hasPermission: () => true })
}))
import LinkageSyncPanel from '@/views/nocode/application/components/LinkageSyncPanel.vue'

/**
 * 「自动更新」面板（第一期契约 9.3）：列出应用当前发布版里开了自动更新的字段，
 * 「检查」= 预告循环，「回填」= 先预告、确认「将更新 N 条」、再回填循环；可停止、继续、重新开始。
 */
const field = (patch: Partial<LinkageSyncField> = {}): LinkageSyncField => ({
  targetObjectId: '5398',
  targetObjectName: '资金流水',
  targetObjectVersion: 33,
  targetFieldId: 'f1',
  targetFieldName: '凭证状态',
  sourceObjectId: '5391',
  sourceObjectName: '会计凭证录入',
  anchor: 'CURRENT_RECORD',
  anchorFieldId: 'a1',
  anchorFieldName: '资金流水',
  signature: 'sig-overview',
  change: 'UNCHANGED',
  divergent: [],
  ...patch
})
const overview = (patch: Partial<LinkageSyncOverview> = {}): LinkageSyncOverview => ({
  applicationId: '3054',
  basis: 'PUBLISHED',
  applicationVersion: 61,
  fields: [field()],
  removed: [],
  ...patch
})
const previewPage = (patch: Partial<LinkagePreviewPage> = {}): LinkagePreviewPage => ({
  signature: 'sig-preview',
  total: null,
  scanned: 0,
  unchanged: 0,
  willFill: 0,
  willClear: 0,
  willChange: 0,
  failedCount: 0,
  failed: [],
  nextCursor: null,
  done: false,
  ...patch
})
const backfillPage = (patch: Partial<LinkageBackfillPage> = {}): LinkageBackfillPage => ({
  scanned: 0,
  updated: 0,
  unchanged: 0,
  failedCount: 0,
  failed: [],
  nextCursor: null,
  done: false,
  ...patch
})
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const apps: App[] = []
let host: HTMLElement
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
interface PanelProps {
  applicationId: string
  publishedVersion: number | null
  active: boolean
  highlight: string[]
}
async function mount(props: Partial<PanelProps> = {}) {
  const state = reactive<PanelProps>({
    applicationId: '3054',
    publishedVersion: 61,
    active: true,
    highlight: [],
    ...props
  })
  host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ setup: () => () => h(LinkageSyncPanel, { ...state }) })
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  await flush()
  return state
}
const row = (label = '资金流水 · 凭证状态') => {
  const found = Array.from(host.querySelectorAll<HTMLElement>('.linkage-sync-row')).find(node =>
    node.textContent?.includes(label)
  )
  if (!found) throw new Error('缺少字段行：' + label)
  return found
}
const buttons = (root: ParentNode = host) =>
  Array.from(root.querySelectorAll<HTMLButtonElement>('button')).filter(node => !node.closest('.ant-modal'))
const button = (text: string, root: ParentNode = host) => {
  const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    node => node.textContent?.replace(/\s/g, '') === text
  )
  if (!found) throw new Error('缺少按钮：' + text)
  return found
}
const labelsOf = (root: ParentNode) => buttons(root).map(node => node.textContent?.replace(/\s/g, ''))
async function click(text: string, root: ParentNode = host) {
  button(text, root).click()
  await flush()
}
const modal = () => {
  const found = Array.from(document.querySelectorAll('.ant-modal')).at(-1)
  if (!found) throw new Error('确认框未打开')
  return found
}

beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
  api.linkageOverview.mockReset().mockResolvedValue(overview())
  api.linkagePreview.mockReset()
  api.linkageBackfill.mockReset()
})
afterEach(async () => {
  await new Promise(resolve => setTimeout(resolve, 100))
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('自动更新面板 · 列表与空态', () => {
  it('应用还没发布：说明发布后才有，不调用接口', async () => {
    await mount({ publishedVersion: null })
    expect(host.textContent).toContain('应用发布后，这里会列出开启了自动更新的字段')
    expect(api.linkageOverview).not.toHaveBeenCalled()
  })

  it('已发布但没有自动更新字段：说明怎么开', async () => {
    api.linkageOverview.mockResolvedValue(overview({ fields: [] }))
    await mount()
    expect(api.linkageOverview).toHaveBeenCalledWith('3054', 'PUBLISHED')
    expect(host.textContent).toContain('这个应用里还没有开启自动更新的字段')
    expect(host.querySelector('.linkage-sync-row')).toBeNull()
  })

  it('列出发布版里的全部字段：来源对象、新开启 / 规则有变化的标记、没同步的应用、不再自动更新的字段', async () => {
    api.linkageOverview.mockResolvedValue(
      overview({
        fields: [
          field({ change: 'NEW' }),
          field({ targetFieldId: 'f2', targetFieldName: '凭证号', change: 'CHANGED' }),
          field({
            targetFieldId: 'f3',
            targetFieldName: '摘要',
            divergent: [{ applicationId: '9', applicationName: '出纳', reason: 'NO_RULE' }]
          })
        ],
        removed: [
          { targetObjectId: '5398', targetObjectName: '资金流水', targetFieldId: 'f9', targetFieldName: '旧状态' }
        ]
      })
    )
    await mount({ highlight: ['5398:f1'] })
    expect(host.querySelectorAll('.linkage-sync-row')).toHaveLength(3)
    expect(row().textContent).toContain('来源：会计凭证录入')
    expect(row().textContent).toContain('新开启')
    expect(row('资金流水 · 凭证号').textContent).toContain('规则有变化')
    expect(row('资金流水 · 摘要').textContent).not.toContain('新开启')
    expect(row('资金流水 · 摘要').textContent).toContain(
      '以下应用还没有同步到同一规则，经它们保存「会计凭证录入」时不会按本规则更新：出纳'
    )
    expect(host.textContent).toContain('以下字段不再自动更新，已有的值保留：资金流水 · 旧状态')
    // 发布后带过来的高亮只落在指定的字段上。
    expect(row().classList.contains('linkage-sync-highlight')).toBe(true)
    expect(row('资金流水 · 凭证号').classList.contains('linkage-sync-highlight')).toBe(false)
    expect(labelsOf(row())).toEqual(['检查', '回填'])
  })

  it('总览读不到：显示原因，可以重试', async () => {
    api.linkageOverview.mockRejectedValueOnce(new Error('服务暂不可用'))
    await mount()
    expect(host.textContent).toContain('服务暂不可用')
    await click('重新加载')
    expect(host.querySelectorAll('.linkage-sync-row')).toHaveLength(1)
  })

  it('应用重新发布后重新读取总览', async () => {
    const state = await mount()
    expect(api.linkageOverview).toHaveBeenCalledTimes(1)
    state.publishedVersion = 62
    await flush()
    expect(api.linkageOverview).toHaveBeenCalledTimes(2)
  })
})

describe('自动更新面板 · 检查', () => {
  it('结果为 0 显示「已是最新」；按发布版基准逐页预告', async () => {
    api.linkagePreview
      .mockResolvedValueOnce(previewPage({ total: 260, scanned: 200, unchanged: 200, nextCursor: 'c200' }))
      .mockResolvedValueOnce(previewPage({ scanned: 60, unchanged: 60, done: true }))
    await mount()
    await click('检查', row())
    expect(row().textContent).toContain('已是最新')
    expect(api.linkagePreview.mock.calls.map(call => call[0])).toEqual([
      {
        applicationId: '3054',
        basis: 'PUBLISHED',
        targetObjectId: '5398',
        targetFieldId: 'f1',
        cursor: null,
        limit: 200
      },
      {
        applicationId: '3054',
        basis: 'PUBLISHED',
        targetObjectId: '5398',
        targetFieldId: 'f1',
        cursor: 'c200',
        limit: 200
      }
    ])
  })

  it('规则有变化的字段：检查结果为 0 后不再提示「已有的记录还没有按这条规则更新」；检查出待更新时提示保留', async () => {
    const hint = '已有的记录还没有按这条规则更新'
    api.linkageOverview.mockResolvedValue(overview({ fields: [field({ change: 'CHANGED' })] }))
    api.linkagePreview
      .mockResolvedValueOnce(previewPage({ total: 60, scanned: 60, willChange: 3, unchanged: 57, done: true }))
      .mockResolvedValueOnce(previewPage({ total: 60, scanned: 60, unchanged: 60, done: true }))
    await mount()
    expect(row().textContent).toContain(hint)
    await click('检查', row())
    expect(row().textContent).toContain('将更新 3 条')
    expect(row().textContent).toContain(hint)
    await click('检查', row())
    expect(row().textContent).toContain('已是最新')
    expect(row().textContent).not.toContain(hint)
  })

  it('有待更新的记录：显示条数与明细；没翻到最后一页之前只显示进度', async () => {
    const second = deferred<LinkagePreviewPage>()
    api.linkagePreview
      .mockResolvedValueOnce(previewPage({ total: 260, scanned: 200, willFill: 200, nextCursor: 'c200' }))
      .mockReturnValueOnce(second.promise)
    await mount()
    await click('检查', row())
    expect(row().textContent).toContain('正在检查…已检查 200 / 260 条')
    expect(row().textContent).not.toContain('将更新')
    second.resolve(previewPage({ scanned: 60, willFill: 55, willClear: 1, willChange: 2, failedCount: 2, done: true }))
    await flush()
    expect(row().textContent).toContain('将更新 258 条（由空变为有值 255 · 清空 1 · 值变化 2 · 无法求值 2）')
  })

  it('检查失败：显示原因，不显示「已是最新」', async () => {
    api.linkagePreview.mockRejectedValue(new Error('网络暂不可用'))
    await mount()
    await click('检查', row())
    expect(row().textContent).toContain('检查未完成：网络暂不可用')
    expect(row().textContent).not.toContain('已是最新')
  })
})

describe('自动更新面板 · 回填', () => {
  const pending = () =>
    api.linkagePreview.mockResolvedValueOnce(
      previewPage({ total: 140, scanned: 140, willFill: 30, willChange: 8, unchanged: 102, done: true })
    )

  it('先预告，确认框显示将更新的条数；确认后循环回填到完成，每页都带预告返回的签名', async () => {
    pending()
    api.linkageBackfill
      .mockResolvedValueOnce(backfillPage({ scanned: 100, updated: 30, unchanged: 70, nextCursor: 'c100' }))
      .mockResolvedValueOnce(backfillPage({ scanned: 40, updated: 8, unchanged: 32, done: true }))
    await mount()
    await click('回填', row())
    expect(api.linkageBackfill).not.toHaveBeenCalled()
    expect(modal().textContent).toContain('资金流水 · 凭证状态')
    expect(modal().textContent).toContain('将更新 38 条（由空变为有值 30 · 清空 0 · 值变化 8 · 无法求值 0）')
    await click('开始回填', modal())
    expect(api.linkageBackfill.mock.calls.map(call => call[0])).toEqual([
      {
        applicationId: '3054',
        targetObjectId: '5398',
        targetFieldId: 'f1',
        signature: 'sig-preview',
        cursor: null,
        limit: 20
      },
      {
        applicationId: '3054',
        targetObjectId: '5398',
        targetFieldId: 'f1',
        signature: 'sig-preview',
        cursor: 'c100',
        limit: 20
      }
    ])
    expect(row().textContent).toContain('回填完成：已处理 140 条 · 已更新 38 条 · 失败 0 条')
  })

  it('确认框里点取消：不回填', async () => {
    pending()
    await mount()
    await click('回填', row())
    await click('取消', modal())
    expect(api.linkageBackfill).not.toHaveBeenCalled()
    expect(row().textContent).toContain('将更新 38 条')
  })

  it('预告结果为 0：直接显示「已是最新」，不弹确认框也不回填', async () => {
    api.linkagePreview.mockResolvedValueOnce(previewPage({ total: 5, scanned: 5, unchanged: 5, done: true }))
    await mount()
    await click('回填', row())
    expect(document.querySelector('.ant-modal')).toBeNull()
    expect(api.linkageBackfill).not.toHaveBeenCalled()
    expect(row().textContent).toContain('已是最新')
  })

  it('进行中显示进度并可停止；停止后可以「继续」（从下一页接着做）或「重新开始」', async () => {
    pending()
    const first = deferred<LinkageBackfillPage>()
    api.linkageBackfill.mockReturnValueOnce(first.promise)
    await mount()
    await click('回填', row())
    await click('开始回填', modal())
    expect(row().textContent).toContain('正在回填…已处理 0 / 140 条')
    expect(labelsOf(row())).toEqual(['停止'])
    await click('停止', row())
    first.resolve(backfillPage({ scanned: 100, updated: 30, unchanged: 70, nextCursor: 'c100' }))
    await flush()
    // 已发出的那一页照常计入；之后不再请求下一页。
    expect(api.linkageBackfill).toHaveBeenCalledTimes(1)
    expect(row().textContent).toContain('已停止：已处理 100 / 140 条 · 已更新 30 条 · 失败 0 条')
    expect(labelsOf(row())).toEqual(['继续', '重新开始'])

    api.linkageBackfill.mockResolvedValueOnce(backfillPage({ scanned: 40, updated: 8, unchanged: 32, done: true }))
    await click('继续', row())
    expect(api.linkageBackfill.mock.calls.at(-1)?.[0]).toMatchObject({ cursor: 'c100', signature: 'sig-preview' })
    expect(api.linkagePreview).toHaveBeenCalledTimes(1)
    expect(row().textContent).toContain('回填完成：已处理 140 条 · 已更新 38 条 · 失败 0 条')
  })

  it('「重新开始」重新预告并再次确认，从第一页回填', async () => {
    pending()
    const first = deferred<LinkageBackfillPage>()
    api.linkageBackfill.mockReturnValueOnce(first.promise)
    await mount()
    await click('回填', row())
    await click('开始回填', modal())
    await click('停止', row())
    first.resolve(backfillPage({ scanned: 100, updated: 30, unchanged: 70, nextCursor: 'c100' }))
    await flush()
    api.linkagePreview.mockResolvedValueOnce(
      previewPage({ total: 140, scanned: 140, willChange: 8, unchanged: 132, done: true })
    )
    api.linkageBackfill.mockResolvedValueOnce(backfillPage({ scanned: 140, updated: 8, unchanged: 132, done: true }))
    await click('重新开始', row())
    expect(modal().textContent).toContain('将更新 8 条')
    await click('开始回填', modal())
    expect(api.linkageBackfill.mock.calls.at(-1)?.[0]).toMatchObject({ cursor: null })
    expect(row().textContent).toContain('回填完成：已处理 140 条 · 已更新 8 条 · 失败 0 条')
  })

  it('规则已变化（CONFLICT）：提示重新检查，不再继续', async () => {
    pending()
    api.linkageBackfill.mockRejectedValue(
      Object.assign(new Error('规则已变化，请重新预告'), { businessCode: LINKAGE_CONFLICT_CODE })
    )
    await mount()
    await click('回填', row())
    await click('开始回填', modal())
    expect(row().textContent).toContain('规则已变化，请重新检查')
    expect(api.linkageBackfill).toHaveBeenCalledTimes(1)
    expect(labelsOf(row())).toEqual(['检查', '回填'])
  })

  it('新开启的字段：回填跑完且没有失败后，不再提示「已有的记录还没有按这条规则更新」', async () => {
    const hint = '已有的记录还没有按这条规则更新'
    api.linkageOverview.mockResolvedValue(overview({ fields: [field({ change: 'NEW' })] }))
    pending()
    api.linkageBackfill.mockResolvedValueOnce(backfillPage({ scanned: 140, updated: 38, unchanged: 102, done: true }))
    await mount()
    expect(row().textContent).toContain(hint)
    await click('回填', row())
    expect(row().textContent).toContain(hint)
    await click('开始回填', modal())
    expect(row().textContent).toContain('回填完成：已处理 140 条 · 已更新 38 条 · 失败 0 条')
    expect(row().textContent).not.toContain(hint)
    expect(row().textContent).toContain('新开启')
  })

  it('新开启的字段：回填有失败的记录时，提示保留', async () => {
    api.linkageOverview.mockResolvedValue(overview({ fields: [field({ change: 'NEW' })] }))
    pending()
    api.linkageBackfill.mockResolvedValueOnce(
      backfillPage({
        scanned: 140,
        updated: 37,
        unchanged: 102,
        failedCount: 1,
        failed: [{ recordId: '9001', reason: '命中多于一行' }],
        done: true
      })
    )
    await mount()
    await click('回填', row())
    await click('开始回填', modal())
    expect(row().textContent).toContain('失败 1 条')
    expect(row().textContent).toContain('已有的记录还没有按这条规则更新')
  })

  it('有记录回填失败：显示失败条数，可展开看每条的原因', async () => {
    pending()
    api.linkageBackfill.mockResolvedValueOnce(
      backfillPage({
        scanned: 140,
        updated: 36,
        unchanged: 102,
        failedCount: 2,
        failed: [
          { recordId: '9001', reason: '必填字段「摘要」不能为空' },
          { recordId: '9002', reason: '命中多于一行' }
        ],
        done: true
      })
    )
    await mount()
    await click('回填', row())
    await click('开始回填', modal())
    expect(row().textContent).toContain('回填完成：已处理 140 条 · 已更新 36 条 · 失败 2 条')
    expect(row().textContent).not.toContain('必填字段「摘要」不能为空')
    await click('查看失败原因', row())
    expect(row().textContent).toContain('记录 9001：必填字段「摘要」不能为空')
    expect(row().textContent).toContain('记录 9002：命中多于一行')
  })

  it('某一页网络失败：停在这一页，可以「继续」从同一页重试', async () => {
    pending()
    api.linkageBackfill
      .mockResolvedValueOnce(backfillPage({ scanned: 100, updated: 30, unchanged: 70, nextCursor: 'c100' }))
      .mockRejectedValueOnce(new Error('网络暂不可用'))
      .mockRejectedValueOnce(new Error('网络暂不可用'))
      .mockResolvedValueOnce(backfillPage({ scanned: 40, updated: 8, unchanged: 32, done: true }))
    await mount()
    await click('回填', row())
    await click('开始回填', modal())
    expect(row().textContent).toContain('已停止：已处理 100 / 140 条 · 已更新 30 条 · 失败 0 条')
    expect(row().textContent).toContain('网络暂不可用')
    await click('继续', row())
    expect(api.linkageBackfill.mock.calls.map(call => call[0].cursor)).toEqual([null, 'c100', 'c100', 'c100'])
    expect(row().textContent).toContain('回填完成：已处理 140 条 · 已更新 38 条 · 失败 0 条')
  })

  it('离开这个页签时停止循环；回来后可以继续', async () => {
    pending()
    const first = deferred<LinkageBackfillPage>()
    api.linkageBackfill.mockReturnValueOnce(first.promise)
    const state = await mount()
    await click('回填', row())
    await click('开始回填', modal())
    state.active = false
    await flush()
    first.resolve(backfillPage({ scanned: 100, updated: 30, unchanged: 70, nextCursor: 'c100' }))
    await flush()
    expect(api.linkageBackfill).toHaveBeenCalledTimes(1)
    state.active = true
    await flush()
    expect(labelsOf(row())).toEqual(['继续', '重新开始'])
    // 回到页签不重新读总览（游标只留在这次页面会话的内存里）。
    expect(api.linkageOverview).toHaveBeenCalledTimes(1)
  })

  it('一个字段在处理时，其它字段的按钮不可用', async () => {
    api.linkageOverview.mockResolvedValue(
      overview({ fields: [field(), field({ targetFieldId: 'f2', targetFieldName: '凭证号' })] })
    )
    api.linkagePreview.mockReturnValueOnce(deferred<LinkagePreviewPage>().promise)
    await mount()
    await click('检查', row())
    expect(buttons(row('资金流水 · 凭证号')).every(node => node.disabled)).toBe(true)
  })
})
