<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AppstoreOutlined } from '@ant-design/icons-vue'
import AppIcon from '@/components/AppIcon.vue'
import { useUserStore } from '@/stores/user'
import { findBestMatchingMenu, findTopMenuFromCache, isClickableMenu, resolveMenuNavPath } from '@/utils/menuNavigation'
import type { Menu } from '@/types'
import { isApplicationRuntimeRoute } from '@/nocode/runtime-route'

const props = withDefaults(defineProps<{ open: boolean, contextMenus?: Menu[], contextTitle?: string }>(), {
  contextMenus: () => [],
})
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const user = useUserStore()
const router = useRouter()
const route = useRoute()
const selected = ref('')
const items = computed(() =>
  selected.value === '__application' ? props.contextMenus : user.getSideMenus(selected.value),
)
const currentPath = computed(() =>
  !isApplicationRuntimeRoute(route) && typeof route.meta.activeMenu === 'string'
    ? route.meta.activeMenu
    : route.fullPath,
)
const activePath = computed(() => findBestMatchingMenu(items.value, currentPath.value)?.path)

watch(
  () => props.open,
  (open: boolean) => {
    if (open) {
      selected.value = props.contextMenus.length
        ? '__application'
        : String(findTopMenuFromCache(user.menus, currentPath.value)?.id ?? user.topMenus[0]?.id ?? '')
    }
  },
)

async function navigate(path: string) {
  const target = resolveMenuNavPath(path)
  const failure = await router.push(target)
  // 未保存表单取消离开时保留导航，不能把拒绝导航当成成功。
  if (!failure || route.fullPath === router.resolve(target).fullPath)
    emit('update:open', false)
}

function selectGroup(menu: Menu) {
  selected.value = String(menu.id)
  if (!user.getSideMenus(selected.value).some(isClickableMenu) && isClickableMenu(menu))
    void navigate(menu.path)
}
</script>

<template>
  <a-drawer
    :open="open"
    title="导航菜单"
    placement="left"
    width="min(360px, 92vw)"
    root-class-name="mobile-navigation"
    :body-style="{ padding: 0 }"
    @close="emit('update:open', false)"
  >
    <nav class="mobile-menu" aria-label="手机导航">
      <div class="mobile-menu-groups" aria-label="功能分组">
        <button
          v-if="contextMenus.length"
          type="button"
          :class="{ active: selected === '__application' }"
          @click="selected = '__application'"
        >
          <AppstoreOutlined />
          <span>{{ contextTitle || '当前应用' }}</span>
        </button>
        <button
          v-for="menu in user.topMenus"
          :key="menu.id"
          type="button"
          :class="{ active: selected === String(menu.id) }"
          :aria-label="menu.name"
          :aria-pressed="selected === String(menu.id)"
          @click="selectGroup(menu)"
        >
          <AppIcon v-if="menu.icon" :name="menu.icon">
            <AppstoreOutlined />
          </AppIcon>
          <AppstoreOutlined v-else />
          <span>{{ menu.name }}</span>
        </button>
      </div>
      <div class="mobile-menu-pages">
        <template v-for="menu in items" :key="menu.id">
          <div v-if="menu.menuType === 0" class="mobile-menu-section">
            {{ menu.name }}
          </div>
          <button
            v-else-if="isClickableMenu(menu)"
            type="button"
            :class="{ active: activePath === menu.path }"
            :aria-current="activePath === menu.path ? 'page' : undefined"
            @click="navigate(menu.path)"
          >
            {{ menu.name }}
          </button>
        </template>
        <a-empty v-if="!items.length" description="此分组没有子页面" :image="null" />
      </div>
    </nav>
  </a-drawer>
</template>

<style scoped>
.mobile-menu {
  display: flex;
  height: 100%;
  min-height: 0;
}
.mobile-menu-groups {
  width: 104px;
  flex: none;
  background: var(--bg-layout, #f5f6fa);
  overflow-y: auto;
  padding: 8px;
}
.mobile-menu-pages {
  min-width: 0;
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.mobile-menu button {
  width: 100%;
  min-height: 48px;
  padding: 12px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.mobile-menu-groups button {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
}
.mobile-menu button.active {
  color: var(--brand);
  background: var(--brand-light);
  font-weight: 600;
}
.mobile-menu button:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: -2px;
}
.mobile-menu-section {
  padding: 16px 10px 6px;
  color: var(--text-tertiary);
  font-size: 12px;
}
</style>
