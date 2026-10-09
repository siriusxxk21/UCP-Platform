import { triggerDownload } from '@/utils/file'

const SAFE_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])

/**
 * 仅允许浏览器可安全承载的被动内容在 Blob URL 中预览。
 * HTML、SVG 等可执行内容即使上传端声明了 MIME，也只能下载。
 */
export function businessPreviewableType(mimeType: string | null | undefined): boolean {
  const type = mimeType?.split(';', 1)[0]?.trim().toLowerCase() || ''
  return (
    SAFE_IMAGE_TYPES.has(type) ||
    type === 'application/pdf' ||
    type === 'text/plain' ||
    type.startsWith('audio/') ||
    type.startsWith('video/')
  )
}

export function businessImageType(mimeType: string | null | undefined): boolean {
  return SAFE_IMAGE_TYPES.has(mimeType?.split(';', 1)[0]?.trim().toLowerCase() || '')
}

/** 用时回收仅服务于当次新窗口预览；组件内长期缩略图由组件卸载时回收。 */
export function openBusinessBlob(blob: Blob): boolean {
  if (!businessPreviewableType(blob.type)) return false
  const url = URL.createObjectURL(blob)
  const opened = window.open(url, '_blank', 'noopener,noreferrer')
  if (!opened) {
    URL.revokeObjectURL(url)
    return false
  }
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  return true
}

export function downloadBusinessBlob(blob: Blob, name: string) {
  triggerDownload(blob, name)
}
