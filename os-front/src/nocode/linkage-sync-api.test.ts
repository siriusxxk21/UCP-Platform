import { describe, expect, it, vi } from 'vitest'
import { createApplicationApi } from '@/api/nocode/application'
import type { NocodeHttpClient } from '@/api/nocode/object'
import { BACKFILL_PAGE_SIZE, runBackfill } from './linkage-sync'

/**
 * 集成时在真实环境里发现（2026-10-02）：回填一页 100 条要二三十秒，接口用的是默认 10 秒请求超时，
 * 界面每一页都报「timeout of 10000ms exceeded」并停下，而服务端那一页其实跑完了。
 */
describe('数据联动自动更新：预告、回填接口的请求超时与回填每页条数', () => {
  const post = vi.fn(async () => ({}))
  const api = createApplicationApi({ post } as unknown as NocodeHttpClient)
  const target = { applicationId: '36', targetObjectId: '110', targetFieldId: '635' }

  it('预告接口不用默认的 10 秒请求超时：放到 5 分钟，失败仍由调用处就地提示', async () => {
    const body = { ...target, basis: 'PUBLISHED' as const, cursor: null, limit: 200 }
    await api.linkagePreview(body)
    expect(post).toHaveBeenLastCalledWith('/nocode/application/linkage-sync/preview', body, {
      quiet: true,
      timeout: 300000
    })
  })

  it('回填接口不用默认的 10 秒请求超时：放到 5 分钟，失败仍由调用处就地提示', async () => {
    const body = { ...target, signature: 'sig', cursor: null, limit: 20 }
    await api.linkageBackfill(body)
    expect(post).toHaveBeenLastCalledWith('/nocode/application/linkage-sync/backfill', body, {
      quiet: true,
      timeout: 300000
    })
  })

  it('回填默认一页 20 条：一页只占几秒，进度与「停止」跟得上', async () => {
    const page = { scanned: 20, updated: 20, unchanged: 0, failedCount: 0, failed: [], nextCursor: '20', done: true }
    const linkageBackfill = vi.fn(async () => page)
    await runBackfill({ linkageBackfill }, { ...target, signature: 'sig' })
    expect(BACKFILL_PAGE_SIZE).toBe(20)
    expect(linkageBackfill).toHaveBeenCalledWith(expect.objectContaining({ cursor: null, limit: 20 }))
  })
})
