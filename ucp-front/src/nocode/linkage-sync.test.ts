import { describe, expect, it, vi } from 'vitest'
import type { LinkageBackfillPage, LinkagePreviewPage } from '@/types/nocode/linkage-sync'
import {
  LINKAGE_CONFLICT_CODE,
  LinkageRuleChangedError,
  isLinkageForbidden,
  isRuleChanged,
  pendingCount,
  runBackfill,
  runPreview
} from './linkage-sync'

const target = { applicationId: '3054', targetObjectId: '5398', targetFieldId: 'status' }
const previewPage = (patch: Partial<LinkagePreviewPage> = {}): LinkagePreviewPage => ({
  signature: 'sig-1',
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
const network = () => new Error('Network Error')
const rejected = (code: number, message: string) => Object.assign(new Error(message), { businessCode: code })

describe('预告循环 runPreview', () => {
  it('从第一页翻到 done，逐页累加；请求体只有契约里的六个键', async () => {
    const linkagePreview = vi
      .fn()
      .mockResolvedValueOnce(
        previewPage({
          total: 450,
          scanned: 200,
          unchanged: 180,
          willFill: 15,
          willClear: 2,
          willChange: 3,
          nextCursor: 'c200'
        })
      )
      .mockResolvedValueOnce(
        previewPage({ scanned: 200, unchanged: 190, willFill: 10, failedCount: 1, nextCursor: 'c400' })
      )
      .mockResolvedValueOnce(previewPage({ scanned: 50, unchanged: 49, willChange: 1, done: true }))
    const progress = vi.fn()
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'DRAFT' }, { onProgress: progress })
    expect(linkagePreview.mock.calls.map(call => call[0])).toEqual([
      { ...target, basis: 'DRAFT', cursor: null, limit: 200 },
      { ...target, basis: 'DRAFT', cursor: 'c200', limit: 200 },
      { ...target, basis: 'DRAFT', cursor: 'c400', limit: 200 }
    ])
    expect(result).toMatchObject({
      status: 'done',
      signature: 'sig-1',
      total: 450,
      scanned: 450,
      unchanged: 419,
      willFill: 25,
      willClear: 2,
      willChange: 4,
      failedCount: 1,
      cursor: null
    })
    expect(pendingCount(result)).toBe(31)
    // 中间进度逐页上报，且都不是最终结果。
    expect(progress.mock.calls.map(call => [call[0].scanned, call[0].status])).toEqual([
      [200, 'running'],
      [400, 'running'],
      [450, 'done']
    ])
  })

  // 后端实现（api-samples.md）：最后一页也带 nextCursor（= 本页最后一条记录的 ID），结束条件只看 done。
  it('最后一页也带着游标：只看 done 结束，不再多请求一页，返回的游标为空', async () => {
    const linkagePreview = vi
      .fn()
      .mockResolvedValue(previewPage({ total: 5, scanned: 5, willFill: 5, nextCursor: 'r5', done: true }))
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'PUBLISHED' })
    expect(linkagePreview).toHaveBeenCalledTimes(1)
    expect(result).toMatchObject({ status: 'done', scanned: 5, cursor: null })
    const linkageBackfill = vi
      .fn()
      .mockResolvedValue(backfillPage({ scanned: 3, updated: 2, failedCount: 1, nextCursor: 'r3', done: true }))
    const filled = await runBackfill({ linkageBackfill }, { ...target, signature: 'sig-1' })
    expect(linkageBackfill).toHaveBeenCalledTimes(1)
    expect(filled).toMatchObject({ status: 'done', scanned: 3, updated: 2, cursor: null })
  })

  it('没到 done 不算完成：中止后返回已完成部分，状态是 stopped 而不是 done', async () => {
    const controller = new AbortController()
    const linkagePreview = vi.fn().mockImplementation(async () => {
      controller.abort()
      return previewPage({ total: 900, scanned: 200, willFill: 200, nextCursor: 'c200' })
    })
    const result = await runPreview(
      { linkagePreview },
      { ...target, basis: 'PUBLISHED' },
      { signal: controller.signal }
    )
    expect(linkagePreview).toHaveBeenCalledTimes(1)
    expect(result.status).toBe('stopped')
    expect(result).toMatchObject({ scanned: 200, willFill: 200, total: 900, cursor: 'c200' })
  })

  it('开始前已中止：一个请求都不发', async () => {
    const controller = new AbortController()
    controller.abort()
    const linkagePreview = vi.fn()
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'DRAFT' }, { signal: controller.signal })
    expect(linkagePreview).not.toHaveBeenCalled()
    expect(result).toMatchObject({ status: 'stopped', scanned: 0, cursor: null })
  })

  it('网络失败只自动重试一次；仍失败则停在原游标，不跳页', async () => {
    const linkagePreview = vi
      .fn()
      .mockResolvedValueOnce(previewPage({ total: 300, scanned: 200, willFill: 5, nextCursor: 'c200' }))
      .mockRejectedValueOnce(network())
      .mockRejectedValueOnce(network())
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'DRAFT' })
    expect(linkagePreview).toHaveBeenCalledTimes(3)
    expect(linkagePreview.mock.calls.map(call => call[0].cursor)).toEqual([null, 'c200', 'c200'])
    expect(result.status).toBe('failed')
    expect(result.cursor).toBe('c200')
    expect(result.scanned).toBe(200)
    expect((result.error as Error).message).toBe('Network Error')
  })

  it('重试成功后继续往下翻', async () => {
    const linkagePreview = vi
      .fn()
      .mockRejectedValueOnce(network())
      .mockResolvedValueOnce(previewPage({ total: 10, scanned: 10, willFill: 10, done: true }))
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'DRAFT' })
    expect(result).toMatchObject({ status: 'done', scanned: 10, willFill: 10 })
  })

  it('服务端明确拒绝（业务错误）不重试', async () => {
    const linkagePreview = vi.fn().mockRejectedValue(rejected(1050000001, '该字段没有开启「来源变化时自动更新」'))
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'DRAFT' })
    expect(linkagePreview).toHaveBeenCalledTimes(1)
    expect(result.status).toBe('failed')
  })

  it('翻页途中规则签名变了：抛出「规则已变化」，不把两种规则的结果加在一起', async () => {
    const linkagePreview = vi
      .fn()
      .mockResolvedValueOnce(previewPage({ total: 300, scanned: 200, nextCursor: 'c200' }))
      .mockResolvedValueOnce(previewPage({ signature: 'sig-2', scanned: 100, done: true }))
    await expect(runPreview({ linkagePreview }, { ...target, basis: 'PUBLISHED' })).rejects.toBeInstanceOf(
      LinkageRuleChangedError
    )
  })

  it('没到 done 却没有下一页游标：按失败处理，不死循环', async () => {
    const linkagePreview = vi.fn().mockResolvedValue(previewPage({ total: 5, scanned: 5, nextCursor: null }))
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'DRAFT' })
    expect(linkagePreview).toHaveBeenCalledTimes(1)
    expect(result.status).toBe('failed')
  })

  it('无法求值的明细至多保留 50 条，条数照实累加', async () => {
    const failed = (from: number) =>
      Array.from({ length: 20 }, (_, index) => ({ recordId: String(from + index), reason: '命中多行' }))
    const linkagePreview = vi
      .fn()
      .mockResolvedValueOnce(
        previewPage({ total: 600, scanned: 200, failedCount: 30, failed: failed(0), nextCursor: 'a' })
      )
      .mockResolvedValueOnce(previewPage({ scanned: 200, failedCount: 30, failed: failed(100), nextCursor: 'b' }))
      .mockResolvedValueOnce(previewPage({ scanned: 200, failedCount: 30, failed: failed(200), done: true }))
    const result = await runPreview({ linkagePreview }, { ...target, basis: 'PUBLISHED' })
    expect(result.failedCount).toBe(90)
    expect(result.failed).toHaveLength(50)
    expect(result.failed[0]).toEqual({ recordId: '0', reason: '命中多行' })
  })
})

describe('回填循环 runBackfill', () => {
  const request = { ...target, signature: 'sig-1' }
  it('循环到 done 并累加；每一页都带预告返回的签名', async () => {
    const linkageBackfill = vi
      .fn()
      .mockResolvedValueOnce(backfillPage({ scanned: 100, updated: 37, unchanged: 63, nextCursor: 'c100' }))
      .mockResolvedValueOnce(
        backfillPage({
          scanned: 40,
          updated: 1,
          unchanged: 38,
          failedCount: 1,
          failed: [{ recordId: '9', reason: '必填字段不能为空' }],
          done: true
        })
      )
    const progress = vi.fn()
    const result = await runBackfill({ linkageBackfill }, request, { onProgress: progress })
    expect(linkageBackfill.mock.calls.map(call => call[0])).toEqual([
      { ...target, signature: 'sig-1', cursor: null, limit: 20 },
      { ...target, signature: 'sig-1', cursor: 'c100', limit: 20 }
    ])
    expect(result).toMatchObject({
      status: 'done',
      scanned: 140,
      updated: 38,
      unchanged: 101,
      failedCount: 1,
      failed: [{ recordId: '9', reason: '必填字段不能为空' }],
      cursor: null
    })
    expect(progress.mock.calls.map(call => [call[0].scanned, call[0].status])).toEqual([
      [100, 'running'],
      [140, 'done']
    ])
  })

  it('中途停止返回已完成部分与下一页游标；带着游标和已有累计可以续跑到完成', async () => {
    const controller = new AbortController()
    const linkageBackfill = vi
      .fn()
      .mockImplementationOnce(async () => {
        controller.abort()
        return backfillPage({ scanned: 100, updated: 20, unchanged: 80, nextCursor: 'c100' })
      })
      .mockResolvedValueOnce(backfillPage({ scanned: 60, updated: 5, unchanged: 55, done: true }))
    const first = await runBackfill({ linkageBackfill }, request, { signal: controller.signal })
    expect(first).toMatchObject({ status: 'stopped', scanned: 100, updated: 20, cursor: 'c100' })
    expect(linkageBackfill).toHaveBeenCalledTimes(1)

    const second = await runBackfill({ linkageBackfill }, request, { cursor: first.cursor, initial: first })
    expect(linkageBackfill.mock.calls.map(call => call[0])).toEqual([
      { ...target, signature: 'sig-1', cursor: null, limit: 20 },
      { ...target, signature: 'sig-1', cursor: 'c100', limit: 20 }
    ])
    expect(second).toMatchObject({ status: 'done', scanned: 160, updated: 25, unchanged: 135, cursor: null })
  })

  it('规则已变化（CONFLICT）：抛出可识别的错误，不重试', async () => {
    const linkageBackfill = vi.fn().mockRejectedValue(rejected(LINKAGE_CONFLICT_CODE, '规则已变化，请重新预告'))
    const failure = await runBackfill({ linkageBackfill }, request).catch(error => error)
    expect(failure).toBeInstanceOf(LinkageRuleChangedError)
    expect(isRuleChanged(failure)).toBe(true)
    expect(isRuleChanged(network())).toBe(false)
    expect(isLinkageForbidden(rejected(403, '只能管理自己创建的应用'))).toBe(true)
    expect(isLinkageForbidden(failure)).toBe(false)
    expect(linkageBackfill).toHaveBeenCalledTimes(1)
  })

  it('网络失败停在原游标：只自动重试一次，不跳到下一页', async () => {
    const linkageBackfill = vi
      .fn()
      .mockResolvedValueOnce(backfillPage({ scanned: 100, updated: 10, unchanged: 90, nextCursor: 'c100' }))
      .mockRejectedValueOnce(network())
      .mockRejectedValueOnce(network())
      .mockResolvedValueOnce(backfillPage({ scanned: 100, updated: 3, unchanged: 97, done: true }))
    const result = await runBackfill({ linkageBackfill }, request)
    expect(linkageBackfill).toHaveBeenCalledTimes(3)
    expect(linkageBackfill.mock.calls.map(call => call[0].cursor)).toEqual([null, 'c100', 'c100'])
    expect(result).toMatchObject({ status: 'failed', scanned: 100, updated: 10, cursor: 'c100' })
    // 从停下的游标继续：仍是 c100，不是下一页。
    const resumed = await runBackfill({ linkageBackfill }, request, { cursor: result.cursor, initial: result })
    expect(linkageBackfill.mock.calls.map(call => call[0].cursor)).toEqual([null, 'c100', 'c100', 'c100'])
    expect(resumed).toMatchObject({ status: 'done', scanned: 200, updated: 13 })
  })

  it('开始前已中止：不发请求，游标原样返回', async () => {
    const controller = new AbortController()
    controller.abort()
    const linkageBackfill = vi.fn()
    const result = await runBackfill({ linkageBackfill }, request, { signal: controller.signal, cursor: 'c300' })
    expect(linkageBackfill).not.toHaveBeenCalled()
    expect(result).toMatchObject({ status: 'stopped', cursor: 'c300' })
  })
})
