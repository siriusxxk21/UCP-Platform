import { describe, it, expect, vi, beforeEach } from 'vitest'
vi.mock('@/utils/request', () => ({ default: { get: vi.fn() } }))
import request from '@/utils/request'
import { pageImageUrl } from './page-image'

describe('page image uses the foundation file service', () => {
  beforeEach(() => vi.resetAllMocks())
  it('rejects URLs and unsupported file content instead of using a file supplied remote URL', async () => {
    await expect(pageImageUrl('https://example.com/image.png')).rejects.toThrow('展示图片')
    expect(request.get).not.toHaveBeenCalled()
    vi.mocked(request.get).mockResolvedValue([{ id: '123', type: 'image/svg+xml', configId: '1', path: 'unsafe.svg' }])
    await expect(pageImageUrl('123')).rejects.toThrow('图片格式')
  })
  it('builds the foundation download route from verified image metadata and encodes the file path', async () => {
    vi.mocked(request.get).mockResolvedValue([
      {
        id: '123',
        type: 'image/png',
        configId: '1',
        path: 'logos/公司 图标.png',
        url: 'https://example.com/ignored.png'
      }
    ])
    expect(await pageImageUrl('123')).toBe('/api/infra/file/1/get/logos/%E5%85%AC%E5%8F%B8%20%E5%9B%BE%E6%A0%87.png')
    expect(request.get).toHaveBeenCalledWith('/infra/file/get-list', { params: { ids: '123' } })
  })
})
