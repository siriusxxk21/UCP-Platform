/**
 * 校验系统文件上传目录，规则与后端 FilePathUtils 保持一致。
 * 空目录表示使用默认存储目录，因此视为合法。
 */
export function validateUploadDirectory(value?: string) {
  const directory = value?.trim() ?? ''
  if (!directory) return null
  return validateSafeRelativePath(directory, '存储目录')
}

/** 校验上传文件名；文件名除满足安全相对路径外，不允许携带目录。 */
export function validateUploadFileName(value?: string) {
  if (!value) return '文件名不能为空'
  const pathError = validateSafeRelativePath(value, '文件名')
  if (pathError) return pathError
  if (value.includes('/')) return '文件名不能包含目录路径'
  return null
}

function validateSafeRelativePath(path: string, fieldName: string) {
  if (path.startsWith('/') || path.startsWith('\\')) {
    return `${fieldName}必须是相对路径，不能以 / 或 \\ 开头`
  }
  if (path.includes('\\')) {
    return `${fieldName}不能包含反斜杠 \\`
  }
  if (path.includes('\0')) {
    return `${fieldName}包含非法空字符`
  }
  if (/^[a-zA-Z]:/.test(path)) {
    return `${fieldName}不能使用 Windows 盘符路径`
  }

  const segments = path.split('/')
  if (segments.some(segment => segment === '')) {
    return `${fieldName}不能包含连续的 / 或以 / 结尾`
  }
  if (segments.some(segment => segment === '.' || segment === '..')) {
    return `${fieldName}不能包含 . 或 .. 路径段`
  }
  return null
}
