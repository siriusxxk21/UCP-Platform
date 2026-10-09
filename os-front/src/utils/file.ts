/**
 * 触发浏览器下载 Blob 文件
 * @param blob 文件 Blob 数据
 * @param filename 下载文件名
 */
export function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/**
 * 获取当天日期字符串（YYYY-MM-DD 格式），常用于导出文件命名
 */
export function today(): string {
  return new Date().toISOString().slice(0, 10)
}
