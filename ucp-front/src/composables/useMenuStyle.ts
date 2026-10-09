/**
 * 菜单风格切换组合式函数
 *
 * 支持两种布局模式：
 * - 'dual':  双层侧边栏（L1 窄图标栏 + L2 宽文字面板）
 * - 'top':   顶部菜单 + 左侧边栏（现有模式）
 *
 * 状态通过 localStorage 持久化，刷新页面后保持用户选择。
 */

import {ref, watch} from 'vue'

const STORAGE_KEY = 'app_menu_style'
export type MenuStyle = 'dual' | 'top'

// 模块级单例 —— 所有组件共享同一份状态
const menuStyle = ref<MenuStyle>(
  (localStorage.getItem(STORAGE_KEY) as MenuStyle) || 'dual',
)

// 模块级 watcher —— 全局只注册一次，避免重复监听
watch(menuStyle, (val) => {
  localStorage.setItem(STORAGE_KEY, val)
})

export function useMenuStyle() {
  /** 切换菜单风格 */
  function toggleMenuStyle() {
    menuStyle.value = menuStyle.value === 'dual' ? 'top' : 'dual'
  }

  return {
    menuStyle,
    toggleMenuStyle,
  }
}
