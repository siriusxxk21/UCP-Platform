<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { Menu } from '@/types'
import AppBreadcrumb from '@/components/common/AppBreadcrumb.vue'

interface BreadcrumbItem {
  title: string
  path?: string
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const breadcrumbs = ref<BreadcrumbItem[]>([])

/** 检查路由是否存在 */
function isRouteExist(path: string): boolean {
  try {
    const resolved = router.resolve(path)
    return resolved && resolved.name !== 'NotFound' && resolved.matched.length > 0
  }
  catch {
    return false
  }
}

// 构建菜单路径映射表
const menuPathMap = computed(() => {
  const map = new Map<string, Menu>()
  const flattenMenus = (menus: Menu[]) => {
    menus.forEach((menu) => {
      if (menu.path)
        map.set(menu.path, menu)
      if (menu.children?.length)
        flattenMenus(menu.children)
    })
  }
  flattenMenus(userStore.menus)
  return map
})

// 构建菜单ID映射表
const menuIdMap = computed(() => {
  const map = new Map<string, Menu>()
  const flattenMenus = (menus: Menu[]) => {
    menus.forEach((menu) => {
      map.set(menu.id, { ...menu })
      if (menu.children?.length)
        flattenMenus(menu.children)
    })
  }
  flattenMenus(userStore.menus)
  return map
})

// 父菜单ID → 子菜单列表
const parentChildrenMap = computed(() => {
  const map = new Map<string, Menu[]>()
  const allMenus = Array.from(menuIdMap.value.values())
  allMenus.forEach((menu) => {
    if (menu.parentId) {
      if (!map.has(menu.parentId))
        map.set(menu.parentId, [])
      map.get(menu.parentId)!.push(menu)
    }
  })
  return map
})

/** 查找菜单的第一个可点击子菜单路径 */
function findFirstClickablePath(menuId: string): string | undefined {
  const children = parentChildrenMap.value.get(menuId)
  if (!children || children.length === 0)
    return undefined

  const sorted = [...children].sort((a, b) => a.sort - b.sort)
  for (const child of sorted) {
    if (child.component)
      return child.path
    const grandChildPath = findFirstClickablePath(child.id)
    if (grandChildPath)
      return grandChildPath
  }
  return undefined
}

/** 对面包屑数组做相邻重名去重：保留更深层级（更具体的路径） */
function deduplicateBreadcrumbs(items: BreadcrumbItem[]): BreadcrumbItem[] {
  const result: BreadcrumbItem[] = []
  for (const item of items) {
    const prev = result[result.length - 1]
    if (prev && prev.title === item.title) {
      // 相邻同名：替换为当前项（保留更深层级）
      result[result.length - 1] = item
    }
    else {
      result.push(item)
    }
  }
  // 兜底：去重后为空则保留原始数据
  return result.length > 0 ? result : items
}

/** 根据当前路由路径生成面包屑 */
function generateBreadcrumbs() {
  // 1. 优先使用路由配置的 breadcrumb
  const routeBreadcrumb = route.meta?.breadcrumb as BreadcrumbItem[] | undefined
  if (routeBreadcrumb && routeBreadcrumb.length > 0) {
    const mapped = routeBreadcrumb.map((item, index) => ({
      ...item,
      path: index === routeBreadcrumb.length - 1 ? undefined : item.path,
    }))
    breadcrumbs.value = deduplicateBreadcrumbs(mapped)
    return
  }

  // 2. 从菜单数据中动态生成面包屑
  const items: BreadcrumbItem[] = []

  const currentMenu = menuPathMap.value.get(route.path)

  if (currentMenu) {
    const menuChain: Menu[] = []
    let menu: Menu | undefined = currentMenu

    while (menu) {
      menuChain.unshift(menu)
      menu = menu.parentId ? menuIdMap.value.get(menu.parentId) : undefined
    }

    menuChain.forEach((menu, index) => {
      const isLast = index === menuChain.length - 1
      let path: string | undefined

      if (!isLast) {
        if (menu.component && isRouteExist(menu.path))
          path = menu.path
        else
          path = findFirstClickablePath(menu.id)
      }

      items.push({ title: menu.name, path })
    })
  }
  else {
    // 3. 兜底：使用路由 meta 标题
    const title = route.meta?.title as string
    if (title && route.path !== '/dashboard')
      items.push({ title })
  }

  breadcrumbs.value = deduplicateBreadcrumbs(items)
}

watch(() => ({ path: route.path, tab: route.query.tab }), generateBreadcrumbs, { immediate: true })
watch(() => userStore.menus, generateBreadcrumbs, { deep: true })
</script>

<template>
  <div class="breadcrumb-wrapper">
    <div class="breadcrumb-left">
      <AppBreadcrumb :items="breadcrumbs" :show-home="false" />
    </div>
    <div class="breadcrumb-right">
      <slot name="extra" />
    </div>
  </div>
</template>

<style scoped lang="less">
.breadcrumb-wrapper {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.breadcrumb-left {
  flex: 1;
}

.breadcrumb-right {
  flex-shrink: 0;
}
</style>
