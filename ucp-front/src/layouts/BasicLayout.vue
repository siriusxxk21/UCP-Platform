<script setup lang="ts">
import { computed, onMounted, provide, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal, notification } from 'ant-design-vue'
// RouteLocationRaw 类型未使用，已移除
import {
  AppstoreOutlined,
  BellOutlined,
  FileTextOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  QuestionCircleOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { useUserStore } from '@/stores/user'
import { logout } from '@/api/auth'
import AppIcon from '@/components/AppIcon.vue'
import NavigationTabs from '@/components/navigation/NavigationTabs.vue'
import MobileNavigation from '@/components/navigation/MobileNavigation.vue'
import { useCompactViewport } from '@/composables/useCompactViewport'
import KeptRouteView from '@/nocode/kept-route-view'
import type { SystemNotification } from '@/realtime'
import { realtimeBus, realtimeRuntime, useRealtimeEvent } from '@/realtime'
import { useRealtimeStore } from '@/stores/realtime'
import type { Menu } from '@/types'
import {
  mountedApplicationPages,

  runtimeNavigationKey,
} from '@/nocode/runtime-navigation'
import type { RuntimeNavigationContext } from '@/nocode/runtime-navigation'
import { isApplicationRuntimeRoute } from '@/nocode/runtime-route'
import { applicationWorkShelfKey } from '@/nocode/work-context'
import type { ApplicationWorkShelf } from '@/nocode/work-context'
import {
  findBestMatchingMenu,
  findTopMenuFromCache,
  isClickableMenu,
  resolveMenuNavPath,
  resolveTopMenuNavPath,
} from '@/utils/menuNavigation'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const realtimeStore = useRealtimeStore()
const workShelf = shallowRef<ApplicationWorkShelf>()
provide(applicationWorkShelfKey, workShelf)
const currentWorkShelf = computed(() =>
  isApplicationRuntimeRoute(route) && workShelf.value?.applicationId === String(route.query.id || '')
    ? workShelf.value
    : undefined,
)
const runtimeNavigation = shallowRef<RuntimeNavigationContext>()
provide(runtimeNavigationKey, runtimeNavigation)
const currentRuntimeNavigation = computed(() =>
  isApplicationRuntimeRoute(route) && runtimeNavigation.value?.applicationId === String(route.query.id || '')
    ? runtimeNavigation.value
    : undefined,
)
const runtimePlatformPages = computed(() =>
  currentRuntimeNavigation.value
    ? mountedApplicationPages(userStore.menus, currentRuntimeNavigation.value.applicationId)
    : [],
)
// 从“我的应用”进入且没有获授平台入口时，复用同一侧栏呈现应用自身导航，不写入登录菜单。
const localRuntimeMenus = computed<Menu[]>(() =>
  currentRuntimeNavigation.value && !runtimePlatformPages.value.length
    ? currentRuntimeNavigation.value.items.map((item, sort) => ({
        ...item,
        id: `runtime:${item.id}`,
        parentId: 'runtime',
        sort,
        menuType: 2,
        component: 'nocode/application/runtime',
      }))
    : [],
)

declare const __APP_BUILD_INFO__: {
  commit: string
  commitTime: string
  buildTime: string
}

const appBuildInfo = __APP_BUILD_INFO__

function formatBuildTime(value?: string): string {
  if (!value || value === 'unknown')
    return 'unknown'
  const date = new Date(value)
  if (Number.isNaN(date.getTime()))
    return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

const collapsed = ref(false)
const compactNavigation = useCompactViewport('(max-width: 1023px)')
const mobileNavigationOpen = ref(false)
watch(compactNavigation, (compact: boolean) => {
  if (!compact)
    mobileNavigationOpen.value = false
})
const selectedTopKeys = ref<string[]>([])
const currentTopMenuId = ref('0')
// 隐藏办理页按显式声明的菜单归属高亮，避免业务 URL 前缀把用户带到另一中心。
const navigationPath = computed(() =>
  !isApplicationRuntimeRoute(route) && typeof route.meta.activeMenu === 'string'
    ? route.meta.activeMenu
    : route.fullPath,
)

// 根据当前顶部菜单ID获取左侧菜单
const sideMenus = computed<Menu[]>(() => {
  if (localRuntimeMenus.value.length)
    return localRuntimeMenus.value
  if (currentTopMenuId.value === '0')
    return []
  return userStore.getSideMenus(currentTopMenuId.value)
})

const selectedSideKeys = computed<string[]>(() => {
  const matched = findBestMatchingMenu(sideMenus.value, navigationPath.value)
  return matched ? [matched.path] : [route.path]
})

const collapsedSideMenus = computed<Menu[]>(() => {
  return sideMenus.value.filter(isClickableMenu)
})

// 一级菜单自己就是页面时没有二级菜单，二级面板整块不占位，内容区占满。
const hasSideMenus = computed(() => sideMenus.value.length > 0)

function toggleL2Collapsed() {
  collapsed.value = !collapsed.value
}

// 根据路由路径自动匹配所属的顶部菜单
function matchTopMenuId(path: string): string {
  const allMenus = userStore.menus
  // 菜单归属以缓存中的 parentId 为准，不能根据页面 URL 前缀推断。
  const cachedTopMenu = findTopMenuFromCache(allMenus, path)
  if (cachedTopMenu)
    return String(cachedTopMenu.id)
  // 没有菜单的隐藏页面仍保留应用所属目录，不凭名称或 URL 前缀猜测归属。
  const runtimeTop = runtimePlatformPages.value[0] && findTopMenuFromCache(allMenus, runtimePlatformPages.value[0].path)
  if (runtimeTop)
    return String(runtimeTop.id)

  // 隐藏详情页等不在菜单缓存中的路由，保留路径最长匹配作为兼容兜底。
  const topMenu = findBestMatchingMenu(userStore.topMenus, path)
  if (topMenu) {
    return String(topMenu.id)
  }
  return '0'
}

// 初始化时根据路由设置顶部和侧边菜单
function syncMenuByRoute(path: string) {
  let topId = matchTopMenuId(path)
  // 静态首页不属于后端菜单时，默认选中第一个可见的一级菜单。
  if (topId === '0' && userStore.topMenus.length > 0) {
    topId = String(userStore.topMenus[0].id)
  }
  if (topId !== '0') {
    currentTopMenuId.value = topId
    selectedTopKeys.value = [topId]
  }
}

// 监听路由变化，同步菜单选中状态
watch(
  () => [navigationPath.value, userStore.menus, currentRuntimeNavigation.value] as const,
  ([path]) => {
    syncMenuByRoute(path)
  },
  { immediate: true },
)

const msgUnReadCount = computed(() => realtimeStore.unreadCount)

/** 去除富文本中的 HTML 标签，返回纯文本摘要，供通知简要展示 */
function stripHtml(html: string | undefined | null): string {
  if (!html)
    return ''
  if (typeof document !== 'undefined') {
    const doc = new DOMParser().parseFromString(html, 'text/html')
    doc.querySelectorAll('script, style').forEach(el => el.remove())
    return (doc.body.textContent || '').replace(/\s+/g, ' ').trim()
  }
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, '\'')
    .replace(/\s+/g, ' ')
    .trim()
}

function showSystemNotification(msg: SystemNotification): void {
  const priorityMap: Record<number, string> = {
    0: '',
    1: '【重要】',
    2: '【紧急】',
  }
  const priorityLabel = priorityMap[msg.priority || 0] || ''
  notification.info({
    message: `${priorityLabel}${msg.data.title || '您有一条新消息'}`,
    description: stripHtml(msg.data.content) || '暂无内容',
    class: 'message-notification-item notification-desc-clamp',
    placement: 'topRight',
    duration: msg.priority === 2 ? 0 : 4.5,
    style: {
      borderLeft: '4px solid #4338CA',
      cursor: 'pointer',
      transition: 'all 0.3s ease',
    },
    onClick: () => {
      if (msg.data.route) {
        if (!route.path.includes(msg.data.route)) {
          router.push({
            path: msg.data.route,
            query: { sourceId: msg.sourceId, sourceType: msg.sourceType },
          })
        }
        else if (msg.sourceType && msg.sourceId) {
          realtimeBus.emit('notification.detail-requested', {
            kind: 'source',
            sourceType: msg.sourceType,
            sourceId: msg.sourceId,
          })
        }
        return
      }

      if (!route.path.includes('/message/list')) {
        router.push({ path: '/message/list', query: { messageId: msg.msgId } })
      }
      else {
        realtimeBus.emit('notification.detail-requested', { kind: 'message', messageId: msg.msgId })
      }
    },
  })
}

useRealtimeEvent('system.notification', showSystemNotification)

// 组件挂载时确保动态路由已初始化（处理页面刷新场景）
onMounted(() => {
  if (userStore.token && userStore.menus.length > 0) {
    userStore.initDynamicRoutes()
    // 重新同步菜单状态
    syncMenuByRoute(route.fullPath)
  }

  // 公共连接由认证后的应用壳启动，业务页面仅获取频道引用。
  if (userStore.token) {
    realtimeRuntime.start()
  }
})

// 顶部菜单选择
function handleTopMenuSelect({ key }: { key: string }) {
  // 有子菜单跳第一个；没有子菜单但自己是页面就打开它自己；都没有则不跳。
  const target = resolveTopMenuNavPath(userStore.menus, key)
  if (target) {
    router.push(target)
    return
  }
  message.info({ content: '这个菜单下还没有可以打开的页面', key: 'top-menu-without-page', duration: 2 })
}

// 左侧菜单点击
function handleSideMenuClick({ key }: { key: string }) {
  router.push(resolveMenuNavPath(key))
}

// 下拉菜单点击
function handleDropdownClick({ key }: { key: string }) {
  if (key === 'profile') {
    router.push('/profile')
  }
  else if (key === 'settings') {
    router.push('/system/tenant')
  }
  else if (key === 'application-work-shelf') {
    currentWorkShelf.value?.open()
  }
  else if (key === 'logout') {
    handleLogout()
  }
}

// 退出登录
function handleLogout() {
  Modal.confirm({
    title: '确认退出',
    content: '确定要退出登录吗？',
    onOk: async () => {
      try {
        await logout()
      }
      finally {
        try {
          // 先离开受保护页面，避免清空菜单时切换 keep-alive 分支并重新挂载当前页面。
          await router.replace('/login')
        }
        finally {
          userStore.logout()
          message.success('已退出登录')
        }
      }
    },
  })
}
</script>

<template>
  <!-- ================================================================
       全局顶部栏三区布局（品牌 + 项目信息 + 操作）+ 双层侧边栏模式
       ================================================================ -->
  <div class="app-layout-dual">
    <!-- ===== 全局顶部栏 ===== -->
    <header class="global-topbar">
      <a-button
        class="mobile-menu-toggle"
        type="text"
        aria-label="打开导航菜单"
        :aria-expanded="mobileNavigationOpen"
        @click="mobileNavigationOpen = true"
      >
        <MenuUnfoldOutlined />
      </a-button>
      <!-- 左侧：品牌 -->
      <div class="topbar-brand">
        <span class="topbar-brand-name">UCP 统一开发平台</span>
      </div>

      <!-- 中间：当前项目信息 -->
      <div class="topbar-project" />

      <!-- 右侧：操作图标 -->
      <div class="topbar-actions">
        <a-tooltip title="我的应用">
          <a-button type="text" aria-label="我的应用" @click="router.push('/nocode-app/mine')">
            <AppstoreOutlined />
          </a-button>
        </a-tooltip>
        <a-tooltip title="通知">
          <a-badge
            class="topbar-message-badge"
            :count="msgUnReadCount"
            :number-style="{
              fontSize: '10px',
              lineHeight: '15px',
              height: '15px',
              minWidth: '15px',
              padding: '0px',
              borderRadius: '50%',
            }"
          >
            <button type="button" class="topbar-icon" aria-label="通知" @click="router.push('/message/list')">
              <BellOutlined />
            </button>
          </a-badge>
        </a-tooltip>
        <a-dropdown trigger="click" placement="bottomRight">
          <span class="topbar-icon"><QuestionCircleOutlined /></span>
          <template #overlay>
            <div class="help-build-panel">
              <div class="help-build-title">
                前端版本
              </div>
              <div class="help-build-row">
                <span>提交</span>
                <code>{{ appBuildInfo.commit }}</code>
              </div>
              <div class="help-build-row">
                <span>提交时间</span>
                <strong>{{ formatBuildTime(appBuildInfo.commitTime) }}</strong>
              </div>
              <div class="help-build-row">
                <span>构建时间</span>
                <strong>{{ formatBuildTime(appBuildInfo.buildTime) }}</strong>
              </div>
            </div>
          </template>
        </a-dropdown>
        <a-dropdown overlay-class-name="topbar-user-dropdown" placement="bottomRight" :trigger="['click']">
          <button type="button" class="topbar-user" aria-label="账号菜单">
            <span class="avatar-sm">
              <img v-if="userStore.userInfo?.avatar" :src="userStore.userInfo.avatar" alt="avatar">
              <template v-else>
                {{ userStore.userInfo?.nickname?.charAt(0) || userStore.userInfo?.username?.charAt(0) || 'U' }}
              </template>
            </span>
            <span>{{ userStore.userInfo?.nickname || userStore.userInfo?.username }} ▾</span>
          </button>
          <template #overlay>
            <a-menu @click="handleDropdownClick">
              <a-menu-item key="profile">
                <UserOutlined />
                个人中心
              </a-menu-item>
              <a-menu-item v-if="currentWorkShelf" key="application-work-shelf">
                <FileTextOutlined />
                我的草稿 / 提交记录
              </a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout">
                <LogoutOutlined />
                退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </header>
    <MobileNavigation
      v-if="compactNavigation"
      v-model:open="mobileNavigationOpen"
      :context-menus="localRuntimeMenus"
      :context-title="currentRuntimeNavigation?.name"
    />

    <!-- ===== 主体：侧边栏 + 内容 ===== -->
    <div class="body-row">
      <!-- 侧边栏容器（L1+L2，不含品牌） -->
      <div :class="{ 'l2-collapsed': collapsed, 'l2-hidden': !hasSideMenus }" class="sidebar-wrap">
        <div class="sidebar-body">
          <!-- 一级窄条：图标导航 -->
          <nav class="sidebar-l1">
            <div class="l1-menu">
              <div
                v-for="menu in userStore.topMenus"
                :key="menu.id"
                :class="{ active: selectedTopKeys.includes(String(menu.id)) }"
                class="l1-item"
                @click="handleTopMenuSelect({ key: String(menu.id) })"
              >
                <span class="l1-icon">
                  <AppIcon v-if="menu.icon" :name="menu.icon">
                    <AppstoreOutlined />
                  </AppIcon>
                  <AppstoreOutlined v-else />
                </span>
                <span class="l1-label">{{ menu.name }}</span>
              </div>
            </div>
            <div class="l1-footer">
              <a-tooltip :title="userStore.tenantInfo ? `当前租户: ${userStore.tenantInfo.tenantName}` : '未选择租户'">
                <div :style="{ background: userStore.tenantInfo ? 'var(--brand)' : '#6B7280' }" class="l1-avatar">
                  {{ userStore.tenantInfo?.tenantName?.charAt(0) || '租' }}
                </div>
              </a-tooltip>
            </div>
          </nav>

          <!-- 二级宽面板：后台菜单 或 项目空间菜单 -->
          <aside v-if="hasSideMenus" :class="{ collapsed }" class="sidebar-l2">
            <!-- 系统一级目录可容纳多个应用，仅应用专属导航显示应用名称。 -->
            <div v-if="localRuntimeMenus.length && currentRuntimeNavigation && !collapsed" class="runtime-sidebar-title">
              {{ currentRuntimeNavigation.name }}
            </div>
            <button
              :aria-label="collapsed ? '展开二级菜单' : '收起二级菜单'"
              :class="{ compact: collapsed }"
              class="l2-collapse-toggle"
              type="button"
              @click="toggleL2Collapsed"
            >
              <MenuUnfoldOutlined v-if="collapsed" />
              <MenuFoldOutlined v-else />
              <span v-if="!collapsed" class="l2-collapse-text">收起菜单</span>
            </button>

            <div class="l2-panel-host">
              <div :aria-hidden="!collapsed" :class="{ active: collapsed }" class="l2-panel-layer l2-icon-menu">
                <template>
                  <a-tooltip v-for="menu in collapsedSideMenus" :key="menu.id" :title="menu.name" placement="right">
                    <button
                      :class="{ active: selectedSideKeys.includes(menu.path) }"
                      class="l2-icon-button"
                      type="button"
                      @click="handleSideMenuClick({ key: menu.path })"
                    >
                      <AppIcon v-if="menu.icon" :name="menu.icon">
                        <AppstoreOutlined />
                      </AppIcon>
                      <AppstoreOutlined v-else />
                    </button>
                  </a-tooltip>
                  <div v-if="collapsedSideMenus.length === 0" class="l2-empty compact">
                    无
                  </div>
                </template>
              </div>

              <div :aria-hidden="collapsed" :class="{ active: !collapsed }" class="l2-panel-layer l2-expanded-menu">
                <!-- ===== 后台 L2 菜单 ===== -->
                <div class="l2-menu">
                  <template v-for="menu in sideMenus" :key="menu.id">
                    <div v-if="menu.menuType === 0" class="l2-section">
                      {{ menu.name }}
                    </div>
                    <div
                      v-else
                      :class="{ active: selectedSideKeys.includes(menu.path) }"
                      class="l2-item"
                      @click="handleSideMenuClick({ key: menu.path })"
                    >
                      <span class="l2-icon">
                        <AppIcon v-if="menu.icon" :name="menu.icon" />
                      </span>
                      {{ menu.name }}
                    </div>
                  </template>
                  <div v-if="sideMenus.length === 0" class="l2-empty">
                    暂无子菜单
                  </div>
                </div>
              </div>
            </div>
          </aside>
        </div>
      </div>

      <!-- 右侧主区域 -->
      <div class="main-area">
        <!-- 全局访问标签（固定在顶栏下方，不随内容滚动） -->
        <NavigationTabs class="navigation-tabs-bar" />

        <!-- 内容区 -->
        <div class="content-scroll">
          <div class="content-wrapper-dual">
            <!-- Token 清空后立即卸载受保护页面，避免自动退出期间重新挂载并发起业务请求。 -->
            <!-- 应用运行页按页签保活（切回去是离开时的样子）；其余页面照旧随地址重建。 -->
            <router-view v-if="userStore.token" v-slot="{ Component }">
              <KeptRouteView :component="Component" />
            </router-view>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ================================================================
   全局顶部栏三区布局 + 双层侧边栏模式样式
   ================================================================ */
.app-layout-dual {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.mobile-menu-toggle {
  display: none;
}
.topbar-user,
button.topbar-icon {
  border: 0;
  background: transparent;
  font: inherit;
}

@media (max-width: 1023px) {
  .app-layout-dual .sidebar-wrap {
    display: none;
  }
  .app-layout-dual .global-topbar {
    padding: env(safe-area-inset-top, 0px) 8px 0;
    height: calc(56px + env(safe-area-inset-top, 0px));
    gap: 8px;
  }
  .mobile-menu-toggle {
    display: inline-flex;
    flex: none;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
  }
  .app-layout-dual .topbar-brand {
    margin: 0;
    gap: 6px;
    min-width: 0;
    flex: 1;
  }
  .app-layout-dual .topbar-brand-name {
    font-size: 15px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .app-layout-dual .topbar-project {
    display: none;
  }
  .app-layout-dual .topbar-actions {
    gap: 4px;
    margin-left: auto;
  }
  .app-layout-dual .topbar-user {
    padding: 4px;
    min-width: 40px;
    min-height: 44px;
  }
  .app-layout-dual .topbar-user > span:last-child {
    display: none;
  }
  .app-layout-dual .content-wrapper-dual {
    padding: 12px;
    min-width: 0;
  }
  .app-layout-dual .content-scroll {
    overscroll-behavior: contain;
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
}
@media (max-width: 480px) {
  .app-layout-dual .topbar-actions > :first-child,
  .app-layout-dual .topbar-actions > :nth-child(3) {
    display: none;
  }
  .app-layout-dual .content-wrapper-dual {
    padding: 8px;
  }
}

/* ===== 全局顶部栏 ===== */
.global-topbar {
  height: 56px;
  background: #ffffff;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  padding: 0 16px;
  flex-shrink: 0;
  z-index: 200;
}

/* 左侧：品牌 */
.topbar-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-right: 32px;
}

.topbar-brand-name {
  color: #1f2937;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
}

/* 中间：项目信息 */
.topbar-project {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

/* ===== 主体行 ===== */
.body-row {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* 侧边栏容器（不含品牌） */
.sidebar-wrap {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 266px;
}

.sidebar-wrap.l2-collapsed {
  width: 124px;
}

/* 没有二级菜单：只留一级窄条（72px），写在收起态之后以覆盖它的宽度 */
.sidebar-wrap.l2-hidden {
  width: 72px;
}

.sidebar-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* 一级窄条 */
.sidebar-l1 {
  width: 72px;
  min-width: 72px;
  background: linear-gradient(180deg, #1d194a 0%, #1e1b4b 40%, #1a1742 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  overflow-y: auto;
  overflow-x: hidden;
  /* 顶部轻微内阴影增加纵深 */
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.sidebar-l1::-webkit-scrollbar {
  width: 0;
}

.l1-menu {
  flex: 1;
  width: 100%;
  padding: 8px 0;
  /* 顶部渐隐分隔线 */
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}

.l1-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 10px 4px;
  margin: 2px 6px;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  text-align: center;
  position: relative;
}

.l1-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.9);
  transform: translateY(-1px);
}

.l1-item:active {
  transform: translateY(0);
  transition: transform 0.1s;
}

.l1-item.active {
  background: var(--brand);
  color: #fff;
  box-shadow: 0 2px 8px rgba(67, 56, 202, 0.35);
}

.l1-icon {
  font-size: 20px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 图标微浮雕 */
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.15));
}

.l1-item.active .l1-icon {
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.2));
}

.l1-icon :deep(.anticon) {
  font-size: 20px;
}

.l1-label {
  font-size: 12px;
  line-height: 1.3;
  white-space: nowrap;
  /* 文字微浮雕 */
  text-shadow: 0 1px 1px rgba(0, 0, 0, 0.18);
}

.l1-item.active .l1-label {
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}

.l1-footer {
  width: 100%;
  padding: 14px 0 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  justify-content: center;
  flex-shrink: 0;
  /* 底部渐变过渡 */
  background: linear-gradient(180deg, transparent 0%, rgba(0, 0, 0, 0.08) 100%);
}

.l1-avatar {
  width: 32px;
  height: 32px;
  background: var(--brand);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
  font-weight: 500;
  cursor: pointer;
}

/* 二级宽面板 */
.sidebar-l2 {
  width: 194px;
  min-width: 194px;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
}
.runtime-sidebar-title {
  padding: 16px 18px 4px;
  color: var(--text-primary);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.sidebar-l2.collapsed {
  width: 52px;
  min-width: 52px;
}

.sidebar-l2::-webkit-scrollbar {
  width: 4px;
}

.sidebar-l2::-webkit-scrollbar-thumb {
  background: #d1d5db;
  border-radius: 2px;
}

.l2-collapse-toggle {
  width: calc(100% - 12px);
  height: 32px;
  margin: 8px 6px 4px;
  padding: 0 18px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  cursor: pointer;
  font-size: 14px;
  position: relative;
  z-index: 2;
  transition:
    background 0.2s,
    color 0.2s;
}

.l2-collapse-toggle.compact {
  width: 32px;
  margin: 8px auto 4px;
  padding: 0;
  justify-content: center;
  font-size: 16px;
}

.l2-collapse-toggle:hover {
  background: var(--brand-light);
  color: var(--brand);
}

.l2-collapse-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.l2-panel-host {
  flex: 1;
  min-height: 0;
  position: relative;
}

.l2-panel-layer {
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s ease;
  will-change: opacity;
  overflow-y: auto;
  overflow-x: hidden;
}

.l2-panel-layer.active {
  opacity: 1;
  pointer-events: auto;
}

.l2-icon-menu {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 0 10px;
}

.l2-icon-button {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 16px;
  position: relative;
  transition:
    background 0.2s,
    color 0.2s;
}

.l2-icon-button:hover,
.l2-icon-button.active {
  background: var(--brand-light);
  color: var(--brand);
}

.l2-icon-button.active::before {
  content: '';
  position: absolute;
  left: -8px;
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: var(--brand);
}

.l2-icon-badge {
  position: absolute;
  top: 3px;
  right: 3px;
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  border-radius: 7px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  line-height: 14px;
  font-weight: 600;
}

.l2-icon-badge.warn {
  background: #f59e0b;
}

.l2-menu {
  min-height: 0;
  padding: 0 0 8px;
}

/* 项目空间 L2：absolute 脱离 sidebar-l2 滚动体系，自己管理固定头+可滚动区 */
.l2-menu--project {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
}

/* 项目空间 L2 固定头部（不参与滚动） */
.l2-project-fixed-header {
  flex-shrink: 0;
  background: #ffffff;
  border-bottom: 1px solid var(--border);
  padding: 12px 10px 14px;
}

/* 项目空间 L2 可滚动菜单区 */
.l2-menu-scroll {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 4px 0 8px;
}

.l2-menu-scroll::-webkit-scrollbar {
  width: 4px;
}

.l2-menu-scroll::-webkit-scrollbar-thumb {
  background: #d1d5db;
  border-radius: 2px;
}

.l2-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  margin: 1px 6px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 14px;
  transition: all 0.2s;
  position: relative;
}

.l2-item:hover {
  background: var(--brand-light);
  color: var(--brand);
}

.l2-item.active {
  background: var(--brand-light);
  color: var(--brand);
  font-weight: 600;
}

.l2-item.active::before {
  content: '';
  width: 3px;
  height: 16px;
  background: var(--brand);
  border-radius: 2px;
  margin-left: -12px;
  margin-right: 4px;
  flex-shrink: 0;
}

/* 分组 */
.l2-section {
  padding: 14px 16px 4px;
  font-size: 12px;
  color: #9ca3af;
  font-weight: 500;
  letter-spacing: 0.3px;
  cursor: default;
  user-select: none;
}

.l2-section:first-child {
  padding-top: 10px;
}

.l2-icon {
  font-size: 15px;
  flex-shrink: 0;
  display: flex;
}

.l2-empty {
  padding: 16px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}

.l2-empty.compact {
  padding: 8px 0;
  font-size: 12px;
}

.l2-menu-error {
  color: #dc2626;
}

/* 右侧主区域 */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部栏 */
.topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 24px;
  height: 56px;
  background: #ffffff;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  z-index: 100;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-left: auto;
  flex-shrink: 0;
}

.topbar-icon {
  font-size: 16px;
  color: var(--text-secondary);
  cursor: pointer;
  position: relative;
  transition: color 0.2s;
  display: flex;
}

.topbar-icon:hover {
  color: var(--brand);
}

.help-build-panel {
  width: 260px;
  padding: 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
}

.help-build-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.help-build-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 28px;
  font-size: 12px;
  color: var(--text-secondary);
}

.help-build-row code,
.help-build-row strong {
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-primary);
  text-align: right;
  overflow-wrap: anywhere;
}

.topbar-user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  transition: background 0.2s;
  white-space: nowrap;
}

:global(.topbar-user-dropdown .ant-dropdown-menu) {
  min-width: 120px;
}

:global(.topbar-user-dropdown .ant-dropdown-menu-item) {
  white-space: nowrap;
}

.topbar-user:hover {
  background: #f3f4f6;
}

.avatar-sm {
  width: 26px;
  height: 26px;
  background: var(--brand);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
  font-weight: 500;
  overflow: hidden;
  flex: 0 0 auto;
}

.avatar-sm img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 内容区 */
.content-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 15px 12px 15px 12px;
  display: flex;
  flex-direction: column;
}

.content-scroll::-webkit-scrollbar {
  width: 6px;
}

.content-scroll::-webkit-scrollbar-thumb {
  background: #d1d5db;
  border-radius: 3px;
}

.navigation-tabs-bar {
  /* 分隔线由 NavigationTabs 组件自身绘制（inset shadow），此处仅负责布局 */
  flex-shrink: 0;
}

.content-wrapper-dual {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding-bottom: 0px;
}

/* ===== 项目空间 L2 头部 ===== */
.project-l2-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--brand-light, rgba(67, 56, 202, 0.08));
}

.project-l2-avatar {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}

.project-l2-info {
  flex: 1;
  min-width: 0;
}

.project-l2-name {
  font-size: 13px;
  font-weight: 600;
  color: #4338ca;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-l2-subtitle {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.4;
}

/* ===== 项目空间 TopBar ===== */
.project-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}

.project-topbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.back-btn {
  color: #6b7280;
}

.project-topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ===== 项目切换器下拉 ===== */
.switcher-list {
  max-height: 380px;
  overflow-y: auto;
  min-width: 260px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

/* ===== 项目切换器下拉 ===== */
.project-switcher-wrapper {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border-radius: 20px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  cursor: pointer;
  transition: all 0.2s;
}
.project-switcher-wrapper:hover {
  background: #f3f4f6;
  border-color: #d1d5db;
}
.project-switch-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.project-switch-label {
  font-size: 12px;
  color: #9ca3af;
  white-space: nowrap;
}
.project-switch-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
}
.project-mode-text {
  font-size: 13px;
  color: #6b7280;
  white-space: nowrap;
}
.project-sprint-detail {
  font-size: 13px;
  color: #6b7280;
  white-space: nowrap;
}
.proj-switch-arrow {
  font-size: 12px;
  color: #9ca3af;
  margin-left: 2px;
}

.proj-switcher-popup {
  width: 340px;
  max-height: 420px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}
.proj-switch-search {
  padding: 12px 14px 10px;
  border-bottom: 1px solid #f0f0f0;
}
.proj-switch-list {
  max-height: 344px;
  overflow-y: auto;
  padding: 4px 0;
}

.proj-switch-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  cursor: pointer;
}
.proj-switch-item:hover {
  background: #f3f4f6;
}
.proj-switch-item.selected {
  background: #eef2ff;
}

.proj-switch-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
  margin-top: 1px;
}
.proj-switch-info {
  flex: 1;
  min-width: 0;
}
.proj-switch-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.proj-switch-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.proj-switch-desc {
  display: block;
  font-size: 12px;
  color: #9ca3af;
  margin-top: 3px;
  line-height: 1.4;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}
.proj-switch-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.proj-switch-status {
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 500;
  white-space: nowrap;
}
.status-active {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
}
.status-paused {
  background: rgba(245, 158, 11, 0.1);
  color: #d97706;
}
.status-archived {
  background: #f3f4f6;
  color: #6b7280;
}
.proj-switch-mode {
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 10px;
  color: #fff;
  font-weight: 500;
  white-space: nowrap;
}
.proj-switch-check {
  font-size: 15px;
  color: #4338ca;
  flex-shrink: 0;
  margin-top: 7px;
}

/* ===== 工作台 L2 专属样式 ===== */
.ws-quick-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  flex-shrink: 0;
}
.ws-quick-icon.ws-q-project {
  background: rgba(67, 56, 202, 0.1);
  color: #4338ca;
}
.ws-quick-icon.ws-q-create {
  background: rgba(16, 185, 129, 0.1);
  color: #10b981;
}
.ws-quick-icon.ws-q-review {
  background: rgba(245, 158, 11, 0.1);
  color: #f59e0b;
}

.ws-project-item {
  gap: 8px;
}
.ws-project-avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}
.ws-project-avatar.compact {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  font-size: 12px;
}
.ws-project-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

/* ===== 项目空间 L2 Badge ===== */
.l2-badge {
  margin-left: auto;
  font-size: 12px;
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--brand, #4338ca);
  color: #fff;
  font-weight: 500;
  line-height: 18px;
  flex-shrink: 0;
}
.l2-badge.warn {
  background: #f59e0b;
}

/* ===== 项目空间 L2 标签 "当前项目空间" ===== */
.l2-project-label {
  font-size: 12px;
  color: #9ca3af;
  margin-bottom: 6px;
  letter-spacing: 0.5px;
  user-select: none;
}

/* ===== 修复 L2 icon 与文字间距 ===== */
.l2-item-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

<style>
/* 系统通知描述：去除富文本标签后的纯文本，最多两行省略展示
   （antd notification 通过 teleport 渲染到 body，需全局样式） */
.message-notification-item .ant-notification-notice-description {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-clamp: 2;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: break-word;
}
</style>
