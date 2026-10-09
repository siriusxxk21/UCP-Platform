import { effectScope } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useLatestObjectVersions } from './application-object-versions'
import type { PublishedObject } from '@/types/nocode/application'

const object = (versionNo: number) => ({ objectId: '10', versionNo, checksum: `v${versionNo}` }) as PublishedObject
const deferred = () => {
  let resolve!: (value: PublishedObject) => void
  const promise = new Promise<PublishedObject>(accept => {
    resolve = accept
  })
  return { promise, resolve }
}

describe('应用引用对象最新版本对照', () => {
  it('查询最新发布不传固定版本，且不修改草稿引用', async () => {
    const api = { objectVersion: vi.fn().mockResolvedValue(object(3)) }
    const scope = effectScope()
    const state = scope.run(() => useLatestObjectVersions(api))!
    const fixed = object(1)
    await state.refresh([fixed])
    expect(api.objectVersion).toHaveBeenCalledWith('10')
    expect(state.versions['10']).toEqual({ status: 'ready', versionNo: 3, checksum: 'v3' })
    expect(fixed.versionNo).toBe(1)
    scope.stop()
  })

  it('读取失败清除旧的成功状态，允许重试', async () => {
    const api = { objectVersion: vi.fn().mockRejectedValueOnce(new Error('网络失败')).mockResolvedValue(object(4)) }
    const scope = effectScope()
    const state = scope.run(() => useLatestObjectVersions(api))!
    state.accept(object(1))
    await state.refresh([object(1)])
    expect(state.versions['10']).toEqual({ status: 'error', message: '网络失败' })
    await state.refresh([object(1)])
    expect(state.versions['10']).toMatchObject({ status: 'ready', versionNo: 4 })
    scope.stop()
  })

  it('先发后到的旧请求不能覆盖新查询和同步结果', async () => {
    const old = deferred()
    const api = { objectVersion: vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue(object(3)) }
    const scope = effectScope()
    const state = scope.run(() => useLatestObjectVersions(api))!
    const pending = state.refresh([object(1)])
    await state.refresh([object(1)])
    state.accept(object(4))
    old.resolve(object(2))
    await pending
    expect(state.versions['10']).toMatchObject({ versionNo: 4 })
    scope.stop()
  })

  it('切换应用或离开页面后忽略仍在途的结果', async () => {
    const old = deferred()
    const api = { objectVersion: vi.fn().mockReturnValue(old.promise) }
    const scope = effectScope()
    const state = scope.run(() => useLatestObjectVersions(api))!
    const pending = state.refresh([object(1)])
    state.reset()
    scope.stop()
    old.resolve(object(5))
    await pending
    expect(state.versions).toEqual({})
  })
})
