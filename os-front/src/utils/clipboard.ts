/**
 * 复制文本到系统剪贴板。
 *
 * Clipboard API 在非安全上下文或被浏览器策略禁用时可能不存在或执行失败，
 * 此时使用 execCommand 兼容仍支持传统复制能力的浏览器。
 */
export async function copyText(text: string): Promise<void> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return
    }
  } catch {
    // 权限拒绝时继续尝试兼容方案，不能仅在 Clipboard API 不存在时降级。
  }

  copyTextWithSelection(text)
}

function copyTextWithSelection(text: string): void {
  if (typeof document === 'undefined' || typeof document.execCommand !== 'function') {
    throw new Error('当前环境不支持复制到剪贴板')
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)

  try {
    textarea.select()
    if (!document.execCommand('copy')) {
      throw new Error('浏览器拒绝复制到剪贴板')
    }
  } finally {
    document.body.removeChild(textarea)
  }
}
