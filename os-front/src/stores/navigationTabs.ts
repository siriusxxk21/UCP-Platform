import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Menu } from '@/types'
import type { NavigationTab } from '@/router/navigationTabs'

const STORAGE_PREFIX = 'navigation-tabs:'
const MAX_TAB_COUNT = 15

interface PersistedNavigationTabs {
  tabs: NavigationTab[]
  activeKey: string
}

function buildStorageKey(userId: string, tenantId?: string): string {
  return `${STORAGE_PREFIX}${userId}:${tenantId || 'default'}`
}

function readPersistedTabs(storageKey: string): PersistedNavigationTabs {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) || '') as PersistedNavigationTabs
    return Array.isArray(value.tabs) ? value : { tabs: [], activeKey: '' }
  } catch {
    return { tabs: [], activeKey: '' }
  }
}

/** 清除当前浏览器会话内的全部导航标签，供退出登录流程调用。 */
export function clearNavigationTabsSession(): void {
  for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
    const key = sessionStorage.key(index)
    if (key?.startsWith(STORAGE_PREFIX)) sessionStorage.removeItem(key)
  }
}

/** 全局访问标签状态，独立于当前项目运行上下文。 */
export const useNavigationTabsStore = defineStore('navigationTabs', () => {
  const tabs = ref<NavigationTab[]>([])
  const activeKey = ref('')
  const currentStorageKey = ref('')
  const initialized = ref(false)

  const activeTab = computed(() => tabs.value.find(tab => tab.key === activeKey.value))

  function persist(): void {
    if (!currentStorageKey.value) return
    sessionStorage.setItem(currentStorageKey.value, JSON.stringify({ tabs: tabs.value, activeKey: activeKey.value }))
  }

  function enforceLimit(protectedKey: string): void {
    while (tabs.value.length > MAX_TAB_COUNT) {
      const removable = tabs.value
        .filter(tab => tab.closable && tab.key !== protectedKey)
        .sort((left, right) => left.lastActiveAt - right.lastActiveAt)[0]
      if (!removable) break
      tabs.value = tabs.value.filter(tab => tab.key !== removable.key)
    }
  }

  function initialize(userId: string, tenantId: string | undefined, fixedTab: NavigationTab | null): void {
    const storageKey = buildStorageKey(userId, tenantId)
    if (initialized.value && currentStorageKey.value === storageKey) {
      if (fixedTab) ensureFixedTab(fixedTab)
      return
    }
    const persisted = readPersistedTabs(storageKey)
    currentStorageKey.value = storageKey
    tabs.value = persisted.tabs
    activeKey.value = persisted.activeKey
    initialized.value = true
    if (fixedTab) ensureFixedTab(fixedTab)
    enforceLimit(activeKey.value)
    persist()
  }

  function ensureFixedTab(fixedTab: NavigationTab): void {
    const previousFixed = tabs.value.find(tab => !tab.closable)
    tabs.value = tabs.value.filter(tab => tab.closable || tab.key === fixedTab.key)
    const existingIndex = tabs.value.findIndex(tab => tab.key === fixedTab.key)
    if (existingIndex >= 0) {
      const existing = tabs.value[existingIndex]
      tabs.value[existingIndex] = {
        ...fixedTab,
        fullPath: existing.fullPath,
        lastActiveAt: existing.lastActiveAt,
        closable: false
      }
    } else {
      tabs.value.unshift(fixedTab)
    }
    if (!activeKey.value || (previousFixed && activeKey.value === previousFixed.key)) activeKey.value = fixedTab.key
  }

  function syncSystemMenus(menus: Menu[], fixedTab: NavigationTab | null): void {
    const validIds = new Set(menus.filter(menu => !!menu.component && !!menu.path).map(menu => String(menu.id)))
    tabs.value = tabs.value.filter(tab => validIds.has(tab.menuId))
    if (fixedTab) ensureFixedTab(fixedTab)
    if (!tabs.value.some(tab => tab.key === activeKey.value))
      activeKey.value = fixedTab?.key || tabs.value[0]?.key || ''
    persist()
  }

  function register(tab: NavigationTab): void {
    const existingIndex = tabs.value.findIndex(item => item.key === tab.key)
    if (existingIndex >= 0) {
      const closable = tabs.value[existingIndex].closable
      tabs.value[existingIndex] = { ...tabs.value[existingIndex], ...tab, closable }
    } else {
      tabs.value.push(tab)
    }
    activeKey.value = tab.key
    enforceLimit(tab.key)
    persist()
  }

  function close(key: string): string | undefined {
    const index = tabs.value.findIndex(tab => tab.key === key)
    if (index < 0 || !tabs.value[index].closable) return undefined
    const wasActive = activeKey.value === key
    tabs.value.splice(index, 1)
    if (wasActive) {
      const target = tabs.value[Math.max(0, index - 1)] || tabs.value[index] || tabs.value[0]
      activeKey.value = target?.key || ''
      persist()
      return target?.fullPath
    }
    persist()
    return undefined
  }

  function closeOthers(key: string): string | undefined {
    const target = tabs.value.find(tab => tab.key === key)
    if (!target) return undefined
    tabs.value = tabs.value.filter(tab => !tab.closable || tab.key === key)
    activeKey.value = key
    persist()
    return target.fullPath
  }

  function closableSideTabs(key: string, direction: 'left' | 'right'): NavigationTab[] {
    const targetIndex = tabs.value.findIndex(tab => tab.key === key)
    if (targetIndex < 0) return []
    return tabs.value.filter(
      (tab, index) => tab.closable && (direction === 'left' ? index < targetIndex : index > targetIndex)
    )
  }

  function canCloseSide(key: string, direction: 'left' | 'right'): boolean {
    return closableSideTabs(key, direction).length > 0
  }

  /** 按右键目标的位置关闭一侧；仅在当前页面被关闭时回到目标标签。 */
  function closeSide(key: string, direction: 'left' | 'right'): string | undefined {
    const removedKeys = new Set(closableSideTabs(key, direction).map(tab => tab.key))
    if (!removedKeys.size) return undefined
    const wasActiveRemoved = removedKeys.has(activeKey.value)
    tabs.value = tabs.value.filter(tab => !removedKeys.has(tab.key))
    if (wasActiveRemoved) activeKey.value = key
    persist()
    return wasActiveRemoved ? activeTab.value?.fullPath : undefined
  }

  function closeAll(): string | undefined {
    tabs.value = tabs.value.filter(tab => !tab.closable)
    const target = tabs.value[0]
    activeKey.value = target?.key || ''
    persist()
    return target?.fullPath
  }

  function reset(): void {
    if (currentStorageKey.value) sessionStorage.removeItem(currentStorageKey.value)
    tabs.value = []
    activeKey.value = ''
    currentStorageKey.value = ''
    initialized.value = false
  }

  return {
    tabs,
    activeKey,
    activeTab,
    initialize,
    syncSystemMenus,
    register,
    close,
    closeOthers,
    canCloseSide,
    closeSide,
    closeAll,
    reset
  }
})
