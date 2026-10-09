<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import './report-list.css'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { formatDateTime } from '@/utils/format'
import type { DashboardPreferenceItem } from '@/types/nocode/report-dashboard'

const platform = useNocodePlatform(),
  api = platform.reportCenter,
  router = useRouter()
const rows = ref<DashboardPreferenceItem[]>([]),
  view = ref<'ALL' | 'FAVORITE' | 'RECENT'>('ALL'),
  page = ref(1),
  size = ref(10),
  search = ref(''),
  total = ref(0),
  loading = ref(false),
  savingId = ref(''),
  error = ref('')
const columns = [
  { key: 'name', title: '看板名称', width: 300 },
  { key: 'version', title: '发布版本', width: 120 },
  { key: 'charts', title: '组件', width: 100 },
  { key: 'lastVisitedAt', title: '最近访问', width: 170 },
  { key: 'action', title: '操作', width: 260, fixed: 'right' as const }
]
const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`
}))
let generation = 0
async function load() {
  const g = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = await api.dashboardPreferencePage({
      pageNo: page.value,
      pageSize: size.value,
      view: view.value,
      search: search.value
    })
    if (g !== generation) return
    const last = Math.max(1, Math.ceil(data.total / size.value))
    if (page.value > last) {
      page.value = last
      return await load()
    }
    rows.value = data.list
    total.value = data.total
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) loading.value = false
  }
}
function searchPage() {
  page.value = 1
  void load()
}
function resetSearch() {
  search.value = ''
  searchPage()
}
function changePage(value: { current?: number; pageSize?: number }) {
  page.value = value.pageSize !== size.value ? 1 : value.current || 1
  size.value = value.pageSize || 10
  void load()
}
async function toggleFavorite(item: DashboardPreferenceItem) {
  if (savingId.value) return
  const g = generation
  savingId.value = item.dashboard.id
  error.value = ''
  try {
    await api.dashboardFavorite({ id: item.dashboard.id, favorite: !item.favorite })
    if (g === generation) await load()
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    savingId.value = ''
  }
}
function open(item: DashboardPreferenceItem) {
  void router.push({ path: '/nocode/report-center/dashboard-view', query: { id: item.dashboard.id } })
}
void load()
onScopeDispose(() => generation++)
</script>
<template>
  <section class="report-home nocode-list-page report-center-list">
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-tabs v-model:active-key="view" @change="searchPage">
      <a-tab-pane key="ALL" tab="全部看板" />
      <a-tab-pane key="FAVORITE" tab="我的收藏" />
      <a-tab-pane key="RECENT" tab="最近访问" />
    </a-tabs>
    <OsTablePage
      title="报表工作台"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      :row-key="(item: DashboardPreferenceItem) => item.dashboard.id"
      :scroll="{ x: 'max-content' }"
      show-column-settings
      resizable
      column-settings-key="report-workbench"
      @change="changePage"
      @search="searchPage"
    >
      <template #search="{ triggerSearch }">
        <a-form layout="inline" @finish="triggerSearch">
          <a-form-item label="看板名称">
            <a-input
              v-model:value="search"
              aria-label="搜索看板"
              class="nocode-filter-input"
              placeholder="搜索已发布看板"
              allow-clear
              @press-enter.prevent="triggerSearch"
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="resetSearch">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>
      <template #actions>
        <a-button @click="load">刷新</a-button>
        <a-button
          v-if="platform.hasPermission('nocode:report:create')"
          @click="router.push('/nocode/report-center/dashboards')"
        >
          管理仪表板
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <a v-if="column.key === 'name'" @click="open(record)">{{ record.dashboard.name }}</a>
        <template v-else-if="column.key === 'version'">V{{ record.dashboard.publishedVersion }}</template>
        <template v-else-if="column.key === 'charts'">{{ record.dashboard.chartCount }}</template>
        <template v-else-if="column.key === 'lastVisitedAt'">
          {{ record.lastVisitedAt ? formatDateTime(record.lastVisitedAt) : '尚未访问' }}
        </template>
        <div v-else-if="column.key === 'action'" class="nocode-table-actions">
          <a-button type="link" @click="open(record)">打开看板</a-button>
          <a-button
            type="link"
            :disabled="!!savingId"
            :loading="savingId === record.dashboard.id"
            @click="toggleFavorite(record)"
          >
            {{ record.favorite ? '取消收藏' : '收藏' }}
          </a-button>
        </div>
      </template>
      <template #empty>
        <a-empty
          :description="
            view === 'FAVORITE'
              ? '暂无可访问的收藏看板'
              : view === 'RECENT'
                ? '暂无可访问的最近看板'
                : '暂无可访问的已发布看板'
          "
        />
      </template>
    </OsTablePage>
  </section>
</template>
<style scoped>
.report-home {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: var(--spacing-md);
}
.report-home :deep(.ant-tabs-nav) {
  margin: 0;
}
</style>
