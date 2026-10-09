import { describe, expect, it } from 'vitest'
import { businessImageType, businessPreviewableType } from './business-file-content'

describe('业务文件内容安全策略', () => {
  it('只允许被动内容内联预览', () => {
    expect(businessPreviewableType('application/pdf')).toBe(true)
    expect(businessPreviewableType('image/png')).toBe(true)
    expect(businessPreviewableType('video/mp4')).toBe(true)
    expect(businessPreviewableType('text/plain; charset=utf-8')).toBe(true)
    expect(businessPreviewableType('text/html')).toBe(false)
    expect(businessPreviewableType('image/svg+xml')).toBe(false)
    expect(businessPreviewableType('application/javascript')).toBe(false)
    expect(businessPreviewableType(null)).toBe(false)
  })

  it('缩略图排除 SVG 等主动图片内容', () => {
    expect(businessImageType('image/jpeg')).toBe(true)
    expect(businessImageType('image/webp')).toBe(true)
    expect(businessImageType('image/svg+xml')).toBe(false)
  })
})
