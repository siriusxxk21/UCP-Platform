import { describe, expect, it } from 'vitest'
import { applicationUpgradeConfirmationError } from './application-upgrade'
import type { ApplicationUpgradeImpact } from '@/types/nocode/data-center'

const impact: ApplicationUpgradeImpact = {
  applicationId: '1',
  applicationName: '采购管理',
  revision: 2,
  applicationVersion: 1,
  objectVersion: 8,
  reasons: ['数量类型已变化'],
  blockers: [],
  route: '/nocode/application/workspace?id=1'
}

describe('对象发布暂停应用确认', () => {
  it('兼容应用不要求额外权限或确认', () => {
    expect(applicationUpgradeConfirmationError([], false, false)).toBeNull()
  })
  it('有影响时必须明确确认并具有应用管理权限', () => {
    expect(applicationUpgradeConfirmationError([impact], true, false)).toContain('权限')
    expect(applicationUpgradeConfirmationError([impact], false, true)).toContain('确认暂停')
    expect(applicationUpgradeConfirmationError([impact], true, true)).toBeNull()
  })
  it('确认不能绕过流程等真实阻断', () => {
    expect(applicationUpgradeConfirmationError([{ ...impact, blockers: ['采购审批仍在使用'] }], true, true)).toBe(
      '采购管理：采购审批仍在使用'
    )
  })
})
