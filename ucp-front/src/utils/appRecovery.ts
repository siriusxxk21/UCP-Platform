import type { Router } from 'vue-router'
import { Modal } from 'ant-design-vue'

export interface AppBuildInfo {
  commit: string
  commitTime: string
  buildTime: string
}

interface RecoveryDialogActions {
  onReload: () => void
  onCancel: () => void
}

interface ResourceLoadRecoveryOptions {
  buildInfo: AppBuildInfo
  isOnline: () => boolean
  logger: Pick<Console, 'error'>
  reload: () => void
  showDialog: (actions: RecoveryDialogActions) => void
}

const RESOURCE_LOAD_ERROR_PATTERNS = [
  'failed to fetch dynamically imported module',
  'error loading dynamically imported module',
  'importing a module script failed',
  'failed to load module script',
  'loading chunk',
  'chunkloaderror',
  'unable to preload css',
  'expected a javascript-or-wasm module script',
  'disallowed mime type',
]

function getErrorMessage(error: unknown): string {
  if (error instanceof Error)
    return `${error.name}: ${error.message}`
  if (typeof error === 'string')
    return error
  if (error && typeof error === 'object' && 'message' in error) {
    return String((error as { message?: unknown }).message || '')
  }
  return String(error || '')
}

function extractResourceUrl(message: string): string | undefined {
  return message.match(/(?:https?:\/\/|\/assets\/)[^\s)'"`]+/)?.[0]
}

/** 判断异常是否来自前端懒加载资源，避免把普通路由业务异常误判为版本失配。 */
export function isResourceLoadError(error: unknown): boolean {
  const message = getErrorMessage(error).toLowerCase()
  return RESOURCE_LOAD_ERROR_PATTERNS.some(pattern => message.includes(pattern))
}

/**
 * 创建资源加载失败恢复器。
 * 同一轮错误只展示一个弹窗，避免一个 chunk 失败引发的多个事件遮挡当前页面。
 */
export function createResourceLoadRecovery(options: ResourceLoadRecoveryOptions) {
  let dialogVisible = false

  return (error: unknown, source: 'preload' | 'router', force = false): boolean => {
    if (!force && !isResourceLoadError(error))
      return false

    const message = getErrorMessage(error)
    options.logger.error('[App Recovery] 前端资源加载失败', {
      source,
      error: message,
      resourceUrl: extractResourceUrl(message),
      online: options.isOnline(),
      commit: options.buildInfo.commit,
      commitTime: options.buildInfo.commitTime,
      buildTime: options.buildInfo.buildTime,
    })

    if (dialogVisible)
      return true
    dialogVisible = true
    options.showDialog({
      onReload: () => {
        dialogVisible = false
        options.reload()
      },
      onCancel: () => {
        // 用户选择保留未保存内容后，允许下一次导航失败再次提示。
        dialogVisible = false
      },
    })
    return true
  }
}

/** 注册 Vite 预加载与 Vue Router 懒加载异常的统一恢复入口。 */
export function installAppRecovery(router: Router, buildInfo: AppBuildInfo): () => void {
  const recover = createResourceLoadRecovery({
    buildInfo,
    isOnline: () => navigator.onLine,
    logger: console,
    reload: () => window.location.reload(),
    showDialog: ({ onReload, onCancel }) => {
      Modal.confirm({
        title: '页面资源加载失败',
        content: '网络异常或系统已更新，当前页面资源加载失败。刷新后即可继续使用，未保存的内容请先保留。',
        okText: '立即刷新',
        cancelText: '稍后处理',
        centered: true,
        onOk: onReload,
        onCancel,
      })
    },
  })

  const handlePreloadError = (event: Event) => {
    const preloadEvent = event as Event & { payload?: unknown }
    // 阻止 Vite 将异常继续升级为未处理错误，由恢复弹窗接管交互。
    preloadEvent.preventDefault()
    recover(preloadEvent.payload, 'preload', true)
  }
  window.addEventListener('vite:preloadError', handlePreloadError)
  function handleRouterError(error: unknown) {
    recover(error, 'router')
  }
  const removeRouterErrorHandler = router.onError(handleRouterError)

  return () => {
    window.removeEventListener('vite:preloadError', handlePreloadError)
    removeRouterErrorHandler()
  }
}
