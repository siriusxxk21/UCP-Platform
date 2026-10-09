export const MAX_IMAGES = 3
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024
export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

export function validateImage(file: Pick<File, 'size' | 'type'>, count: number): string {
  if (count >= MAX_IMAGES) return '最多添加 3 张图片'
  if (!IMAGE_TYPES.includes(file.type)) return '仅支持 PNG、JPG、WebP 图片'
  if (file.size === 0) return '图片不能为空'
  if (file.size > MAX_IMAGE_BYTES) return '单张图片不能超过 5 MB'
  return ''
}

/** 固定记录唤醒时的现场，过滤 URL 中的凭据，不随草稿跨路由自动改变。 */
export function feedbackPath(fullPath: string): string {
  const url = new URL(fullPath, 'https://local.invalid')
  for (const key of [...url.searchParams.keys()]) {
    if (/token|password|secret|authorization|credential/i.test(key)) url.searchParams.delete(key)
  }
  const query = url.searchParams.toString()
  return `${url.pathname}${query ? '?' + query : ''}`
}

export interface Area {
  x: number
  y: number
  width: number
  height: number
}
export function selectedArea(start: { x: number; y: number }, end: { x: number; y: number }): Area {
  return {
    x: Math.min(start.x, end.x),
    y: Math.min(start.y, end.y),
    width: Math.abs(end.x - start.x),
    height: Math.abs(end.y - start.y)
  }
}
