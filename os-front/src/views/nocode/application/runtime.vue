<script setup lang="ts">
import { computed, inject, onBeforeUnmount, provide, ref, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { ResourceKind } from '@/types/nocode/application'
import type { RuntimeApplication } from '@/types/nocode/runtime'
import type { PageConfig } from '@/types/nocode/application-ui'
import { isStandaloneRuntimeList, runtimePageNodes } from '@/nocode/runtime-page'
import BusinessRecords from './components/BusinessRecords.vue'
import BusinessBlock from './components/BusinessBlock.vue'
import PageRenderer from './components/PageRenderer.vue'
import ApplicationDashboardBlock from './components/ApplicationDashboardBlock.vue'
import { applicationRefreshKey, pageRefreshKey } from '@/nocode/application-context'
import { applicationWorkShelfKey, workEntryKey } from '@/nocode/work-context'
import RecordSurface from './components/RecordSurface.vue'
import WorkShelf from './components/WorkShelf.vue'
import WorkDraftPanel from './components/WorkDraftPanel.vue'
import { confirmDiscard } from '@/nocode/unsaved'
import { KeptPages } from '@/nocode/kept-pages'
import { providePageScope } from '@/nocode/page-activity'
import { plainRouteKey, plainRouteSurvivalKey } from '@/nocode/runtime-route'
import { sameRuntimeApplication, useRuntimeApplicationCache } from '@/nocode/runtime-application-cache'
import {
  applicationHome,
  applicationPagePath,
  isStandaloneApplicationPage,
  pageNavigationName,
  publishedNavigation,
  runtimeNavigationKey
} from '@/nocode/runtime-navigation'
/**
 * 一个应用里同时保活的应用内页面个数：切回打开过的应用内页面，是离开时的样子。
 * 超出后淘汰最久没看的，再进它按首次打开处理。内存占用未实测，按实际再调。
 */
const KEPT_APPLICATION_PAGES = 5
const applicationRevision = ref(0),
  workRevision = ref(0),
  shelfOpen = ref(false),
  draftId = ref(''),
  warningsDismissed = ref(false)
provide(applicationRefreshKey, applicationRevision)
provide(pageRefreshKey, ref({}))
const platform = useNocodePlatform(),
  route = useRoute(),
  router = useRouter()
const applicationId = computed(() => String(route.query.id || '')),
  application = ref<RuntimeApplication>(),
  error = ref(''),
  loading = ref(false),
  names = ref<Record<string, string>>({})
function openDraft(id: string) {
  shelfOpen.value = false
  draftId.value = id
}
function closeWork() {
  shelfOpen.value = false
  draftId.value = ''
}
provide(workEntryKey, { application, openDraft })
watch(applicationId, () => {
  shelfOpen.value = false
  draftId.value = ''
  warningsDismissed.value = false
})
const resources = computed(() => application.value?.definition.resources || [])
const menus = computed(() => publishedNavigation(resources.value))
const managedNavigation = computed(() =>
  resources.value.some(r => r.kind === ResourceKind.MENU && r.config.navigationVersion === 2)
)
const layoutNavigation = inject(runtimeNavigationKey, undefined)
const layoutWorkShelf = inject(applicationWorkShelfKey, undefined)
const navigationOwner = Symbol('runtime-page')
const sidebarNavigation = computed(() => managedNavigation.value && !!layoutNavigation)
const objectId = computed(() =>
  String(route.query.objectId || application.value?.definition.objects[0]?.objectId || '')
)
const objectOptions = computed(
  () =>
    application.value?.definition.objects.map(r => ({
      value: r.objectId,
      label: names.value[r.objectId] || '对象 ' + r.objectId
    })) || []
)
// 未配置应用内导航时也使用发布视图，确保其表单、字段联动和操作设置生效。
const objectViews = computed(() =>
  resources.value.filter(r => r.kind === ResourceKind.VIEW && String(r.config.objectId) === objectId.value)
)
const selectedObjectView = computed(
  () => objectViews.value.find(r => r.id === String(route.query.viewId || '')) || objectViews.value[0]
)
const selectedMenu = computed(() => {
  if (route.query.page) return menus.value.find(menu => menu.config.targetId === String(route.query.page))
  if (route.query.menu)
    return resources.value.find(menu => menu.kind === ResourceKind.MENU && menu.id === String(route.query.menu))
  if (route.query.objectId && route.query.recordId)
    return menus.value.find(menu => menu.config.targetId === selectedObjectView.value?.id)
  const home = applicationHome(resources.value)
  return menus.value.find(menu => menu.config.targetId === home?.id)
})
const target = computed(() => {
  if (route.query.page) {
    const resource = resources.value.find(r => r.id === String(route.query.page))
    return isStandaloneApplicationPage(resource) ? resource : undefined
  }
  if (route.query.menu) {
    const resource = resources.value.find(r => r.id === selectedMenu.value?.config.targetId)
    return isStandaloneApplicationPage(resource) ? resource : undefined
  }
  if (route.query.objectId) return undefined
  return applicationHome(resources.value)
})
const invalidPage = computed(() => !!(route.query.page || route.query.menu) && !target.value)
const pageConfig = computed(() => target.value?.config as unknown as PageConfig | undefined)
const pageNodes = computed(() => runtimePageNodes(pageConfig.value?.nodes || []))
const standaloneList = computed(() => isStandaloneRuntimeList(pageNodes.value) && !pageConfig.value?.filters?.length)
const viewOptions = computed(() => objectViews.value.map(r => ({ value: r.id, label: r.name })))
const recordId = computed(() => (route.query.recordId ? String(route.query.recordId) : undefined))
const showPageNavigation = computed(() => menus.value.length > 1 && !sidebarNavigation.value)
const showObjectToolbar = computed(
  () => (!menus.value.length && !target.value && !invalidPage.value) || !!(route.query.objectId && recordId.value)
)
// 当前显示的应用内页面；保活按它区分实例。不同种类的编号可能相同，所以带上种类。
const contentKey = computed(() => {
  if (target.value?.kind === ResourceKind.PAGE) return `page:${target.value.id}:${recordId.value || ''}`
  if (target.value?.kind === ResourceKind.VIEW) return `view:${target.value.id}`
  if (target.value?.kind === ResourceKind.REPORT_DASHBOARD) return `dashboard:${target.value.id}`
  if (invalidPage.value) return undefined
  if (menus.value.length && !(route.query.objectId && route.query.recordId)) return undefined
  if (selectedObjectView.value) return `view:${selectedObjectView.value.id}`
  return objectId.value ? `object:${objectId.value}` : undefined
})
const cache = useRuntimeApplicationCache()
// 普通运行页可在同应用内复用；进入平台菜单页签时仍须遵循外层宿主的销毁边界。
const plainRouteSurvives = inject(plainRouteSurvivalKey, undefined)
const page = providePageScope(
  to => plainRouteKey(to) === plainRouteKey(route) && (!plainRouteSurvives || plainRouteSurvives(to))
)
watchEffect(() => {
  if (!layoutWorkShelf) return
  if (page.active.value && application.value) {
    layoutWorkShelf.value = {
      owner: navigationOwner,
      applicationId: applicationId.value,
      open: () => {
        if (!page.active.value) return
        draftId.value = ''
        shelfOpen.value = true
      }
    }
  } else if (layoutWorkShelf.value?.owner === navigationOwner) layoutWorkShelf.value = undefined
})
watchEffect(() => {
  if (!layoutNavigation) return
  if (page.active.value && managedNavigation.value && application.value) {
    layoutNavigation.value = {
      owner: navigationOwner,
      applicationId: applicationId.value,
      name: application.value.application.name,
      items: menus.value.map(menu => ({
        id: String(menu.config.targetId),
        name: pageNavigationName(menu, resources.value),
        path: applicationPagePath(applicationId.value, String(menu.config.targetId)),
        icon: String(menu.config.icon || 'AppstoreOutlined')
      }))
    }
  } else if (layoutNavigation.value?.owner === navigationOwner) layoutNavigation.value = undefined
})
onBeforeUnmount(() => {
  if (layoutNavigation?.value?.owner === navigationOwner) layoutNavigation.value = undefined
  if (layoutWorkShelf?.value?.owner === navigationOwner) layoutWorkShelf.value = undefined
})
// 完整应用入口解析为显式首页地址，刷新、菜单高亮和全局页签使用同一个身份。
watch([application, managedNavigation, () => route.fullPath, page.active], () => {
  if (
    !page.active.value ||
    !managedNavigation.value ||
    route.query.page ||
    route.query.menu ||
    route.query.objectId ||
    route.query.recordId
  )
    return
  const home = applicationHome(resources.value)
  if (home) void router.replace(applicationPagePath(applicationId.value, home.id))
})
// 定义换新后，按旧定义保活的应用内页面全部作废。
const contentEpoch = ref(0)
// 后台比对发现定义变了、而页面里有未保存的修改：先不换，等用户决定。
const pendingUpdate = ref<RuntimeApplication>()
let comparing = 0
async function loadNames() {
  const current = application.value,
    id = applicationId.value
  if (!current || menus.value.length) return
  // 各对象名称互不依赖，一起取；单个失败只影响它自己。
  await Promise.all(
    current.definition.objects.map(async ref => {
      try {
        names.value[ref.objectId] = (await platform.runtime.model(id, ref.objectId)).object.objectName
      } catch {
        names.value[ref.objectId] = '不可用对象 ' + ref.objectId
      }
    })
  )
}
function applyUpdate(next: RuntimeApplication) {
  pendingUpdate.value = undefined
  warningsDismissed.value = false
  application.value = next
  contentEpoch.value++
  void loadNames()
}
async function confirmUpdate() {
  const next = pendingUpdate.value
  if (next && (await confirmDiscard(page.hasUnsaved()))) applyUpdate(next)
}
/** 后台重取定义并与正在显示的比对：一样就不动；不一样才换（重新发布、权限变化、被停用）。 */
async function compare() {
  const turn = ++comparing,
    id = applicationId.value
  const current = () => turn === comparing && id === applicationId.value
  try {
    const next = cache.remember(id, await platform.runtime.application(id))
    if (!current()) return
    error.value = ''
    if (sameRuntimeApplication(next, application.value)) return
    if (page.hasUnsaved()) pendingUpdate.value = next
    else applyUpdate(next)
  } catch (e) {
    // 只有服务端明确拒绝（应用停用、无权访问）才让页面失效；网络抖动保持现状，下次再比。
    if (!current() || !(e instanceof Error) || !('businessCode' in e)) return
    cache.forget(id)
    error.value = errorMessage(e)
    // 有未保存的修改时保留内容，只提示错误。
    if (!page.hasUnsaved()) application.value = undefined
  }
}
async function load() {
  comparing++
  pendingUpdate.value = undefined
  error.value = ''
  const cached = cache.peek(applicationId.value)
  if (cached) {
    // 打开过的应用先用缓存画出来，定义在后台比对，不挡在数据请求前面。
    loading.value = false
    application.value = cached
    void loadNames()
    void compare()
    return
  }
  loading.value = true
  application.value = undefined
  try {
    application.value = cache.remember(applicationId.value, await platform.runtime.application(applicationId.value))
    await loadNames()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
function chooseMenu(menu: string) {
  const selected = menus.value.find(item => item.id === menu)
  if (managedNavigation.value && selected)
    void router.push(applicationPagePath(applicationId.value, String(selected.config.targetId)))
  else void router.replace({ path: '/nocode-app/runtime', query: { id: applicationId.value, menu } })
}
function chooseObject(objectId: string) {
  void router.replace({ path: '/nocode-app/runtime', query: { id: applicationId.value, objectId } })
}
function chooseView(viewId: string) {
  void router.replace({
    path: '/nocode-app/runtime',
    query: { id: applicationId.value, objectId: objectId.value, viewId }
  })
}
watch(applicationId, load, { immediate: true })
// 回到前台：定义被作废过（本机刚发布）或上次加载失败就重新加载，否则后台比对。
watch(page.resumed, () => {
  if (loading.value) return
  if (!application.value || (!cache.peek(applicationId.value) && !page.hasUnsaved())) void load()
  else void compare()
})
</script>
<template>
  <section class="application-runtime" :aria-label="application?.application.name || '应用运行'">
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-alert
      v-if="pendingUpdate"
      class="runtime-warning"
      type="info"
      show-icon
      message="应用已更新（重新发布或权限有变化），当前页面仍按原来的内容显示"
    >
      <template #action>
        <a-button size="small" type="primary" @click="confirmUpdate">立即更新</a-button>
      </template>
    </a-alert>
    <a-alert
      v-if="!warningsDismissed && (application?.warnings || []).length"
      class="runtime-warning"
      type="warning"
      show-icon
      closable
      message="应用固定的数据对象版本与最新结构不一致，当前仍按固定版本运行"
      @close="warningsDismissed = true"
    >
      <template #description>
        <ul class="runtime-warning-list">
          <li v-for="warning in application?.warnings || []" :key="warning">{{ warning }}</li>
        </ul>
      </template>
    </a-alert>
    <a-spin :spinning="loading">
      <template v-if="application">
        <!-- 草稿入口收进账号菜单，仅确有页面导航或对象选择时保留工具行。 -->
        <div
          v-if="showPageNavigation || showObjectToolbar"
          class="application-header"
          :class="{ 'application-header--tabs': showPageNavigation }"
        >
          <nav v-if="showPageNavigation" class="application-menu" aria-label="应用内导航">
            <a-button
              v-for="menu in menus"
              :key="menu.id"
              type="text"
              class="application-menu-item"
              :class="{ 'application-menu-item--active': selectedMenu?.id === menu.id }"
              :aria-current="selectedMenu?.id === menu.id ? 'page' : undefined"
              @click="chooseMenu(menu.id)"
            >
              {{ pageNavigationName(menu, resources) }}
            </a-button>
          </nav>
          <div v-else-if="showObjectToolbar" class="application-object-toolbar">
            <a-select
              :value="objectId"
              :options="objectOptions"
              class="application-object-select"
              aria-label="业务对象"
              @change="chooseObject"
            />
            <a-select
              v-if="objectViews.length > 1"
              :value="selectedObjectView?.id"
              :options="viewOptions"
              class="application-object-select"
              aria-label="业务视图"
              @change="chooseView"
            />
          </div>
        </div>
        <!-- 应用内页面保活：切回打开过的菜单、对象、视图是离开时的样子，数据在后台静默补取。 -->
        <KeptPages :key="contentEpoch" :page-key="contentKey" :max="KEPT_APPLICATION_PAGES">
          <a-empty v-if="invalidPage" description="此页面已移除或当前无权访问，请从左侧菜单选择其他页面" />
          <template v-else-if="route.query.objectId && recordId">
            <BusinessBlock
              v-if="selectedObjectView"
              :key="`${selectedObjectView.id}:${recordId}`"
              standalone
              :application-id="applicationId"
              :resources="resources"
              :resource-id="selectedObjectView.id"
              :initial-record-id="recordId"
            />
            <BusinessRecords
              v-else
              :key="`${objectId}:${recordId}`"
              standalone
              :application-id="applicationId"
              :object-id="objectId"
              :resources="resources"
              :initial-record-id="recordId"
            />
          </template>
          <ApplicationDashboardBlock
            v-else-if="target?.kind === ResourceKind.REPORT_DASHBOARD"
            :key="target.id"
            :application-id="applicationId"
            :resource="target"
            :resources="resources"
            :refresh-key="applicationRevision"
          />
          <PageRenderer
            v-else-if="target?.kind === ResourceKind.PAGE"
            :key="target.id"
            :nodes="pageNodes"
            :standalone="standaloneList"
            :application-id="applicationId"
            :resources="resources"
            :record-id="recordId"
            :page-id="target.id"
          />
          <BusinessBlock
            v-else-if="target?.kind === ResourceKind.VIEW"
            standalone
            :key="target.id"
            :application-id="applicationId"
            :resources="resources"
            :resource-id="target.id"
            :initial-record-id="recordId"
          />
          <template v-else-if="!menus.length || (route.query.objectId && recordId)">
            <BusinessBlock
              v-if="selectedObjectView"
              standalone
              :key="selectedObjectView.id"
              :application-id="applicationId"
              :resources="resources"
              :resource-id="selectedObjectView.id"
              :initial-record-id="recordId"
            />
            <BusinessRecords
              v-else-if="objectId"
              standalone
              :key="objectId"
              :application-id="applicationId"
              :object-id="objectId"
              :initial-record-id="recordId"
              :resources="resources"
            />
          </template>
          <a-empty v-else description="当前应用内导航的目标不可用" />
        </KeptPages>
      </template>
    </a-spin>
    <!-- 列表与详情共用一个容器，避免多次打开后旧抽屉遮住新内容。 -->
    <RecordSurface
      :open="shelfOpen || !!draftId"
      :title="draftId ? '草稿与提交材料' : '我的草稿 / 提交记录'"
      @update:open="
        value => {
          if (!value) closeWork()
        }
      "
    >
      <WorkDraftPanel
        v-if="draftId"
        :id="draftId"
        @changed="workRevision++"
        @submitted="applicationRevision++"
        @close="closeWork"
      />
      <WorkShelf v-else :application-id="applicationId" :revision="workRevision" @open-draft="openDraft" />
    </RecordSurface>
  </section>
</template>
<style scoped>
.runtime-warning {
  flex-shrink: 0;
  margin-bottom: 12px;
}
.runtime-warning-list {
  margin: 0;
  padding-left: 18px;
}
.application-header {
  flex-shrink: 0;
  display: flex;
  /* 窄屏放不下时选择项换行，不挤压导航项 */
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 12px;
}
.application-header--tabs {
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}
.application-runtime {
  /* 页面边距统一由系统公共布局提供，避免与运行内容叠加。 */
  padding: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
}
.application-runtime > :deep(.ant-spin-nested-loading) {
  flex: 1;
  min-height: 0;
}
.application-runtime > :deep(.ant-spin-nested-loading > .ant-spin-container) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.application-menu {
  flex: 1 0 auto;
  max-width: 100%;
  display: flex;
  gap: 4px;
  overflow-x: auto;
}
.application-menu-item {
  flex-shrink: 0;
  height: 36px;
  padding: 0 12px;
  border-radius: 0;
  border-bottom: 2px solid transparent;
}
.application-menu-item--active {
  color: var(--primary-color, #4f46e5);
  border-bottom-color: currentColor;
  font-weight: 600;
}
.application-object-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  flex: 1 0 auto;
  max-width: 100%;
}
.application-object-select {
  width: 280px;
  max-width: 100%;
  flex-shrink: 0;
}
</style>
