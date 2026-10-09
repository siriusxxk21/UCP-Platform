/// <reference path="../../types/vuefinder-locales.d.ts" />
import type { App } from 'vue'

/**
 * VueFinder 的选项通过 app.use 注入，但其体积较大（JS 约 300KB、CSS 约 260KB），
 * 不适合进入首屏包，因此不在 main.ts 静态安装，改为进入网盘路由时再动态安装。
 */
let appRef: App | undefined
let installPromise: Promise<void> | undefined

export function bindVueFinderApp(app: App) {
  appRef = app
}

/** 官方 zhCN 词典缺词，补齐界面残留的英文标题 */
const zhCNExtra: Record<string, string> = {
  'Search Files': '搜索文件',
  Filter: '筛选',
  'Toggle Tree View': '切换目录树',
  'Delete files': '移入回收站',
  'Are you sure you want to delete these files?': '确定将以下内容移入回收站吗？目录内的内容会一起移动。',
  "I'm sure delete it, This action cannot be undone.": '确认移入回收站，之后可在回收站还原。',
  'Yes, Delete!': '移入回收站',
  'Files deleted.': '已移入回收站',
  'Found %s results': '找到 %s 项结果'
}

export function ensureVueFinderInstalled(): Promise<void> {
  if (!installPromise) {
    installPromise = (async () => {
      if (!appRef) throw new Error('VueFinder 运行时未绑定应用实例')
      const [{ VueFinderPlugin }, { default: zhCN }] = await Promise.all([
        import('vuefinder'),
        import('vuefinder/dist/locales/zhCN.js')
      ])
      appRef.use(VueFinderPlugin, { locale: 'zhCN', i18n: { zhCN: { ...zhCN, ...zhCNExtra } } })
    })().catch(error => {
      // 失败后允许用户通过刷新重新加载，不能永久复用已拒绝的 Promise。
      installPromise = undefined
      throw error
    })
  }
  return installPromise
}

/**
 * 写入文件管理器的初始观感（列表视图、不显示隐藏文件）
 *
 * 组件的 config 入参只对非持久化项生效：4.7.6 的配置仓库会把 view、showHiddenFiles 这类持久化项
 * 覆盖回内置默认值（网格、显示隐藏文件），只能靠持久化状态注入，因此挂载前写入一次。
 * 已有记录视为用户自己的选择（设置面板可改），不再覆盖。
 */
export function seedFinderState(id: string): void {
  const key = `vuefinder_config_${id}`
  if (localStorage.getItem(key)) return
  localStorage.setItem(
    key,
    JSON.stringify({
      view: 'list',
      theme: 'silver',
      fullScreen: false,
      showTreeView: false,
      showHiddenFiles: false,
      metricUnits: false,
      showThumbnails: true,
      persist: false,
      path: '',
      pinnedFolders: [],
      expandTreeByDefault: false,
      expandedTreePaths: []
    })
  )
}
