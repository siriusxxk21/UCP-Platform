import { beforeEach, describe, expect, it, vi } from 'vitest'

const users = vi.hoisted(() => vi.fn())
vi.mock('@/api/system/user', () => ({ getSimpleUserList: users }))

describe('业务文件上传人名称缓存', () => {
  beforeEach(() => {
    vi.resetModules()
    users.mockReset()
  })

  it('同一租户会话复用请求，切换租户或账号后重新加载', async () => {
    users
      .mockResolvedValueOnce([{ id: '1', nickname: '租户甲用户' }])
      .mockResolvedValueOnce([{ id: '1', nickname: '租户乙用户' }])
    const { resolveBusinessUserNames } = await import('./business-file-names')

    expect((await resolveBusinessUserNames('tenant-a:user-1')).get('1')).toBe('租户甲用户')
    await resolveBusinessUserNames('tenant-a:user-1')
    expect(users).toHaveBeenCalledTimes(1)

    expect((await resolveBusinessUserNames('tenant-b:user-1')).get('1')).toBe('租户乙用户')
    expect(users).toHaveBeenCalledTimes(2)
  })
})
