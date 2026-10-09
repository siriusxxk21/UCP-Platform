<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CloseOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { useNavigationTabsStore } from '@/stores/navigationTabs'
import { confirmClosingKeptRoutes } from '@/nocode/kept-route-view'

/**
 * 应用级访问标签栏。
 * 组件只发起导航，标签激活状态由路由成功后的 afterEach 统一确认。
 */
const router = useRouter()
const navigationTabsStore = useNavigationTabsStore()
const scrollHost = ref<HTMLElement>()
const tabElements = new Map<string, HTMLElement>()
const hasOverflow = ref(false)
const canScrollLeft = ref(false)
const canScrollRight = ref(false)
let resizeObserver: ResizeObserver | undefined

function setTabElement(key: string, element: Element | null): void {
  if (element instanceof HTMLElement) tabElements.set(key, element)
  else tabElements.delete(key)
}

async function navigate(fullPath: string): Promise<void> {
  await router.push(fullPath)
}

async function closeTab(key: string): Promise<void> {
  // 保活的页面里可能留着没保存的表单；关页签会把它销毁，先问一次。
  if (!(await confirmClosingKeptRoutes([key]))) return
  const targetPath = navigationTabsStore.close(key)
  if (targetPath) await navigate(targetPath)
}

async function handleMiddleClick(event: MouseEvent, key: string, closable: boolean): Promise<void> {
  if (event.button !== 1 || !closable) return
  event.preventDefault()
  await closeTab(key)
}

async function handleContextAction(action: string, key: string): Promise<void> {
  if (action === 'close') {
    await closeTab(key)
    return
  }
  if (!['left', 'right', 'others', 'all'].includes(action)) return
  const anchor = navigationTabsStore.tabs.findIndex(tab => tab.key === key)
  if (anchor < 0) return
  const closing = navigationTabsStore.tabs
    .filter(
      (tab, index) =>
        tab.closable &&
        (action === 'left'
          ? index < anchor
          : action === 'right'
            ? index > anchor
            : action !== 'others' || tab.key !== key)
    )
    .map(tab => tab.key)
  if (!(await confirmClosingKeptRoutes(closing))) return
  const targetPath =
    action === 'left' || action === 'right'
      ? navigationTabsStore.closeSide(key, action)
      : action === 'others'
        ? navigationTabsStore.closeOthers(key)
        : navigationTabsStore.closeAll()
  if (targetPath) await navigate(targetPath)
}

function handleWheel(event: WheelEvent): void {
  if (!scrollHost.value || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return
  scrollHost.value.scrollLeft += event.deltaY
  event.preventDefault()
}

/** 同步左右按钮状态，并容忍浏览器计算产生的亚像素误差。 */
function updateScrollState(): void {
  const host = scrollHost.value
  if (!host) {
    hasOverflow.value = false
    canScrollLeft.value = false
    canScrollRight.value = false
    return
  }
  const tolerance = 1
  hasOverflow.value = host.scrollWidth > host.clientWidth + tolerance
  canScrollLeft.value = hasOverflow.value && host.scrollLeft > tolerance
  canScrollRight.value = hasOverflow.value && host.scrollLeft + host.clientWidth < host.scrollWidth - tolerance
}

function scrollTabs(direction: 'left' | 'right'): void {
  const host = scrollHost.value
  if (!host) return
  const distance = Math.max(220, Math.min(420, host.clientWidth * 0.65))
  host.scrollBy({ left: direction === 'left' ? -distance : distance, behavior: 'smooth' })
}

async function scrollToActiveTab(key: string): Promise<void> {
  await nextTick()
  tabElements.get(key)?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
  updateScrollState()
}

watch(() => navigationTabsStore.activeKey, scrollToActiveTab, { immediate: true })
watch(
  () => navigationTabsStore.tabs.length,
  async () => {
    await nextTick()
    updateScrollState()
  }
)

onMounted(() => {
  updateScrollState()
  if (typeof ResizeObserver !== 'undefined' && scrollHost.value) {
    resizeObserver = new ResizeObserver(updateScrollState)
    resizeObserver.observe(scrollHost.value)
  }
})

onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<template>
  <div class="navigation-tabs">
    <button
      v-if="hasOverflow"
      :disabled="!canScrollLeft"
      aria-label="向左查看更多标签"
      class="navigation-tabs-control left"
      title="向左查看更多标签"
      type="button"
      @click="scrollTabs('left')"
    >
      <LeftOutlined />
    </button>
    <div ref="scrollHost" class="navigation-tabs-scroll" @scroll="updateScrollState" @wheel="handleWheel">
      <a-dropdown v-for="tab in navigationTabsStore.tabs" :key="tab.key" :trigger="['contextmenu']">
        <button
          :ref="element => setTabElement(tab.key, element as Element | null)"
          :class="{ active: navigationTabsStore.activeKey === tab.key }"
          :title="tab.title"
          class="navigation-tab"
          type="button"
          @click="navigate(tab.fullPath)"
          @auxclick="handleMiddleClick($event, tab.key, tab.closable)"
        >
          <span class="navigation-tab-title">{{ tab.title }}</span>
          <CloseOutlined v-if="tab.closable" class="navigation-tab-close" @click.stop="closeTab(tab.key)" />
        </button>
        <template #overlay>
          <a-menu @click="({ key }: { key: string | number }) => handleContextAction(String(key), tab.key)">
            <a-menu-item key="close" :disabled="!tab.closable">关闭当前</a-menu-item>
            <a-menu-item key="left" :disabled="!navigationTabsStore.canCloseSide(tab.key, 'left')">
              关闭左侧全部
            </a-menu-item>
            <a-menu-item key="right" :disabled="!navigationTabsStore.canCloseSide(tab.key, 'right')">
              关闭右侧全部
            </a-menu-item>
            <a-menu-item key="others">关闭其他</a-menu-item>
            <a-menu-item key="all">关闭全部</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
    <button
      v-if="hasOverflow"
      :disabled="!canScrollRight"
      aria-label="向右查看更多标签"
      class="navigation-tabs-control right"
      title="向右查看更多标签"
      type="button"
      @click="scrollTabs('right')"
    >
      <RightOutlined />
    </button>
  </div>
</template>

<style scoped>
.navigation-tabs {
  display: flex;
  align-items: stretch;
  width: 100%;
  min-width: 0;
  background: #f6f7f9;
  box-shadow: inset 0 -1px 0 var(--border, #e5e7eb);
}

.navigation-tabs-scroll {
  display: flex;
  align-items: flex-end;
  flex: 1;
  min-width: 0;
  gap: 3px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 6px 12px 0;
  scrollbar-width: none;
}

.navigation-tabs-scroll::-webkit-scrollbar {
  display: none;
}

.navigation-tabs-control {
  position: relative;
  z-index: 3;
  width: 32px;
  flex: 0 0 32px;
  color: var(--text-secondary, #6b7280);
  background: #f6f7f9;
  border: 0;
  cursor: pointer;
  font-size: 11px;
  transition:
    color 0.18s ease,
    background-color 0.18s ease;
}

.navigation-tabs-control.left {
  border-right: 1px solid rgba(229, 231, 235, 0.8);
  box-shadow: 4px 0 8px rgba(15, 23, 42, 0.04);
}

.navigation-tabs-control.right {
  border-left: 1px solid rgba(229, 231, 235, 0.8);
  box-shadow: -4px 0 8px rgba(15, 23, 42, 0.04);
}

.navigation-tabs-control:not(:disabled):hover {
  color: var(--brand, #4338ca);
  background: #fff;
}

.navigation-tabs-control:disabled {
  color: #c5cad3;
  cursor: default;
}

.navigation-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  max-width: 220px;
  height: 33px;
  padding: 0 12px;
  gap: 7px;
  color: var(--text-secondary, #6b7280);
  background: transparent;
  border: 1px solid transparent;
  border-bottom: 0;
  border-radius: 8px 8px 0 0;
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
  transition:
    color 0.2s cubic-bezier(0.4, 0, 0.2, 1),
    background-color 0.2s cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

.navigation-tab:hover {
  color: var(--text-primary, #1f2937);
  background: rgba(255, 255, 255, 0.65);
}

.navigation-tab.active {
  color: var(--brand, #4338ca);
  background: #fff;
  border-color: var(--border, #e5e7eb);
  font-weight: 600;
  box-shadow: 0 -1px 3px rgba(15, 23, 42, 0.06);
}

/* 激活态底部品牌色指示条，更贴近当前标签对应的内容区域 */
.navigation-tab.active::before {
  position: absolute;
  right: 12px;
  bottom: 0;
  left: 12px;
  height: 2px;
  background: var(--brand, #4338ca);
  border-radius: 2px 2px 0 0;
  content: '';
  z-index: 2;
}

/* 激活标签底部用白色遮住标签带与内容区的分隔线，形成"当前页"连通感 */
.navigation-tab.active::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 1px;
  background: #fff;
  content: '';
}

.navigation-tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.navigation-tab-close {
  padding: 3px;
  margin-right: -5px;
  color: #9ca3af;
  border-radius: 50%;
  font-size: 10px;
  opacity: 0;
  pointer-events: none;
  transition:
    opacity 0.2s ease,
    color 0.2s ease,
    background-color 0.2s ease;
}

/* 关闭按钮始终占位（避免显隐导致标题宽度抖动），悬停或激活时显现 */
.navigation-tab:hover .navigation-tab-close,
.navigation-tab.active .navigation-tab-close {
  opacity: 1;
  pointer-events: auto;
}

.navigation-tab-close:hover {
  color: #374151;
  background: #e5e7eb;
}

.navigation-tab.active .navigation-tab-close:hover {
  color: var(--brand, #4338ca);
  background: var(--brand-light, #eef2ff);
}
</style>
