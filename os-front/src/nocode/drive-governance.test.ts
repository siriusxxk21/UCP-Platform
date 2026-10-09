import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn()
}))
vi.mock('@/utils/request', () => ({ default: http }))

import { createBusinessSpace, getManageSpaceList, updateDriveSpace } from '@/api/drive/space'
import { isSelectableUser } from '@/components/UserSelector/userSelection'
import { partitionPermissionSaveResults } from '@/views/drive/permission-batch'

describe('网盘治理前端契约', () => {
  beforeEach(() => vi.clearAllMocks())

  it('启用筛选时只允许状态明确为启用的用户', () => {
    expect(isSelectableUser({ status: 0 }, true)).toBe(true)
    expect(isSelectableUser({ status: 1 }, true)).toBe(false)
    expect(isSelectableUser({}, true)).toBe(false)
    expect(isSelectableUser({ status: 1 }, false)).toBe(true)
  })

  it('批量授权保留失败主体名称，且已成功主体不再重试', () => {
    const disabledError = new Error('授权主体不存在或已停用')
    const result = partitionPermissionSaveResults(
      [
        { id: 'enabled', label: '启用用户' },
        { id: 'disabled', label: '停用用户' }
      ],
      [
        { status: 'fulfilled', value: true },
        { status: 'rejected', reason: disabledError }
      ]
    )

    expect([...result.succeededIds]).toEqual(['enabled'])
    expect(result.failures).toEqual([{ subject: { id: 'disabled', label: '停用用户' }, reason: disabledError }])
  })

  it('使用独立治理列表与业务空间新建端点，编辑沿用统一端点', async () => {
    http.get.mockResolvedValue([])
    http.post.mockResolvedValue('1001')
    http.put.mockResolvedValue(true)

    await getManageSpaceList()
    await createBusinessSpace({ name: '采购合同', quotaBytes: 1024 })
    await updateDriveSpace({ id: '1001', name: '采购合同档案', quotaBytes: 2048, status: 0 })

    expect(http.get).toHaveBeenCalledWith('/drive/space/manage-list')
    expect(http.post).toHaveBeenCalledWith('/drive/space/create-business', {
      name: '采购合同',
      quotaBytes: 1024
    })
    expect(http.put).toHaveBeenCalledWith('/drive/space/update', {
      id: '1001',
      name: '采购合同档案',
      quotaBytes: 2048,
      status: 0
    })
  })
})
