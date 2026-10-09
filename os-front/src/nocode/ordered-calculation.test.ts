// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { nocodePlatformKey, type NocodePlatform } from './platform'
import { useOrderedCalculationStates } from './ordered-calculation'
import type { PublishedObject } from '@/types/nocode/application'

const object = {
  objectId: 'ledger',
  versionNo: 1,
  checksum: 'version-1',
  definition: {
    fieldOptions: { balance: { calculation: { mode: 'RUNNING_TOTAL', updateMode: 'ON_SAVE' } } }
  }
} as unknown as PublishedObject
const applications: App[] = []
afterEach(() => applications.splice(0).forEach(app => app.unmount()))

async function inspect(hasPermission: (permission: string) => boolean) {
  const calculationStatus = vi
    .fn()
    .mockResolvedValue([
      { objectId: 'ledger', fieldId: 'balance', signature: 'rule-1', state: 'READY', cursor: {}, error: null }
    ])
  let state: ReturnType<typeof useOrderedCalculationStates> | undefined
  const app = createApp({
    setup() {
      state = useOrderedCalculationStates(() => ({ ledger: object }))
      return () => null
    }
  })
  app.provide(nocodePlatformKey, { hasPermission, objectData: { calculationStatus } } as unknown as NocodePlatform)
  app.mount(document.createElement('div'))
  applications.push(app)
  for (let i = 0; i < 4; i++) await nextTick()
  return { state, calculationStatus }
}

describe('设计器有序计算状态查看权限', () => {
  it('有对象查看权限的非管理员可读取摘要状态并配置已就绪字段', async () => {
    const permission = vi.fn((value: string) => value === 'nocode:object:query')
    const { state, calculationStatus } = await inspect(permission)
    expect(calculationStatus).toHaveBeenCalledExactlyOnceWith('ledger')
    expect(state?.readiness.value).toEqual({ ledger: { balance: 'READY' } })
    expect(state?.failure.value).toBe('')
    expect(permission).not.toHaveBeenCalledWith('nocode:object:manage')
  })
  it('无对象查看权限不请求状态，也不放开有序字段查询', async () => {
    const { state, calculationStatus } = await inspect(() => false)
    expect(calculationStatus).not.toHaveBeenCalled()
    expect(state?.readiness.value).toEqual({})
    expect(state?.failure.value).toContain('无法读取')
  })
})
