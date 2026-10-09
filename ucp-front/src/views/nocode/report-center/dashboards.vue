<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import './report-list.css'
import { QuestionCircleOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import DashboardAuthorization from './components/DashboardAuthorization.vue'
import DashboardGovernanceDialog from './components/DashboardGovernanceDialog.vue'
import ReportFolderDialog from './components/ReportFolderDialog.vue'
import { folderTree, folderPath } from '@/nocode/report-folders'
import type { ReportFolder, ReportResourceStatus } from '@/types/nocode/report-center'
import type { DashboardAvailableItem } from '@/types/nocode/report-dashboard'
const platform = useNocodePlatform(),
  api = platform.reportCenter,
  router = useRouter()
const rows = ref<DashboardAvailableItem[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(10),
  search = ref(''),
  loading = ref(false),
  error = ref(''),
  open = ref(false),
  name = ref(''),
  saving = ref(false)
const authorizationId = ref('')
const folders = ref<ReportFolder[]>([]),
  folderId = ref<string>(),
  status = ref<ReportResourceStatus | ''>('')
const tree = computed(() => folderTree(folders.value))
const selectedFolder = computed(() => folders.value.find(f => f.id === folderId.value))
const folderDialog = ref<{ folder?: ReportFolder; parentId: string | null; deleting?: boolean }>()
const governance = ref<{ mode: 'copy' | 'move' | 'status' | 'delete' | 'history'; dashboard: DashboardAvailableItem }>()
const columns = [
  { key: 'name', title: '仪表板', width: 300 },
  { key: 'folder', title: '目录', width: 180 },
  { key: 'status', title: '状态', width: 100 },
  { key: 'charts', title: '组件', width: 100 },
  { key: 'version', title: '发布版本', width: 130 },
  { key: 'action', title: '操作', width: 320, fixed: 'right' as const }
]
const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
  showTotal: (n: number) => `共 ${n} 条`
}))
let generation = 0
async function load() {
  const g = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = await api.dashboardAvailablePage({
      pageNo: page.value,
      pageSize: size.value,
      search: search.value,
      folderId: folderId.value,
      status: status.value || undefined
    })
    if (g !== generation) return
    const directory = await api.folders('DASHBOARD')
    if (g !== generation) return
    folders.value = directory
    if (folderId.value && folderId.value !== '0' && !directory.some(folder => folder.id === folderId.value)) {
      folderId.value = undefined
      return await load()
    }
    const last = Math.max(1, Math.ceil(data.total / size.value))
    if (page.value > last) {
      page.value = last
      return await load()
    }
    if (g === generation) {
      rows.value = data.list
      total.value = data.total
    }
  } catch (e) {
    if (g === generation) error.value = errorMessage(e)
  } finally {
    if (g === generation) loading.value = false
  }
}
async function create() {
  if (saving.value) return
  if (!name.value.trim()) {
    error.value = '请填写仪表板名称'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const data = await api.dashboardSave({
      id: null,
      expectedRevision: 0,
      content: { schemaVersion: 1, name: name.value.trim(), description: '', charts: [] },
      folderId: selectedFolder.value?.id || null
    })
    open.value = false
    await router.push({ path: '/nocode/report-center/dashboard-editor', query: { id: data.id } })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
function change(value: { current?: number; pageSize?: number }) {
  page.value = value.current || 1
  size.value = value.pageSize || 10
  void load()
}
function searchPage() {
  page.value = 1
  void load()
}
function resetSearch() {
  search.value = ''
  status.value = ''
  folderId.value = undefined
  searchPage()
}
function closeAuthorization() {
  authorizationId.value = ''
  void load()
}
function selectFolder(keys: (string | number)[]) {
  folderId.value = keys[0] ? String(keys[0]) : undefined
  searchPage()
}
function folderDone() {
  folderDialog.value = undefined
  void load()
}
function governanceDone() {
  governance.value = undefined
  void load()
}
function openCreate() {
  name.value = ''
  open.value = true
}
void load()
onScopeDispose(() => generation++)
</script>
<template>
  <section class="dashboard-list nocode-list-page report-center-list">
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <div class="dashboard-directory-layout">
      <aside class="dashboard-directories" aria-label="仪表板目录">
        <div class="dashboard-directory-heading">
          <div class="dashboard-directory-title">
            <strong>目录</strong>
            <a-tooltip title="选择目录时包含其子目录" :trigger="['hover', 'focus']">
              <a-button type="text" size="small" class="dashboard-directory-help" aria-label="目录筛选说明">
                <QuestionCircleOutlined />
              </a-button>
            </a-tooltip>
          </div>
          <a-button
            v-if="platform.hasPermission('nocode:report:manage')"
            type="link"
            @click="folderDialog = { parentId: null }"
          >
            新建目录
          </a-button>
        </div>
        <a-button block :type="folderId === undefined ? 'primary' : 'text'" @click="selectFolder([])">
          全部仪表板
        </a-button>
        <a-button block :type="folderId === '0' ? 'primary' : 'text'" @click="selectFolder(['0'])">未分类</a-button>
        <a-tree
          :tree-data="tree"
          :selected-keys="folderId && folderId !== '0' ? [folderId] : []"
          block-node
          @select="selectFolder"
        />
        <a-space
          v-if="selectedFolder && platform.hasPermission('nocode:report:manage')"
          class="dashboard-directory-tools"
          wrap
        >
          <a-button size="small" @click="folderDialog = { parentId: selectedFolder.id }">新建子目录</a-button>
          <a-button size="small" @click="folderDialog = { folder: selectedFolder, parentId: null }">编辑目录</a-button>
          <a-button
            size="small"
            danger
            @click="folderDialog = { folder: selectedFolder, parentId: null, deleting: true }"
          >
            删除目录
          </a-button>
        </a-space>
      </aside>
      <OsTablePage
        title="仪表板"
        :columns="columns"
        :data-source="rows"
        :pagination="pagination"
        :loading="loading"
        row-key="id"
        :scroll="{ x: 'max-content' }"
        show-column-settings
        resizable
        column-settings-key="report-dashboards"
        @change="change"
        @search="searchPage"
      >
        <template #search="{ triggerSearch }">
          <a-form layout="inline" @finish="triggerSearch">
            <a-form-item label="名称">
              <a-input
                v-model:value="search"
                class="nocode-filter-input"
                aria-label="搜索仪表板"
                placeholder="搜索仪表板"
                allow-clear
                @press-enter.prevent="triggerSearch"
              />
            </a-form-item>
            <a-form-item label="状态">
              <a-select
                v-model:value="status"
                class="nocode-filter-input"
                aria-label="仪表板状态筛选"
                :options="[
                  { value: '', label: '全部' },
                  { value: 'ACTIVE', label: '已启用' },
                  { value: 'INACTIVE', label: '已停用' }
                ]"
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
        <template #toolbar>
          <a-button v-if="platform.hasPermission('nocode:report:create')" type="primary" @click="openCreate">
            新建仪表板
          </a-button>
          <a-button @click="load">刷新</a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">{{ record.name }}</template>
          <template v-else-if="column.key === 'folder'">{{ folderPath(folders, record.folderId) }}</template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="record.status === 'INACTIVE' ? 'default' : 'green'">
              {{ record.status === 'INACTIVE' ? '已停用' : '已启用' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'charts'">{{ record.chartCount }}</template>
          <template v-else-if="column.key === 'version'">
            {{ record.publishedVersion ? 'V' + record.publishedVersion : '未发布' }}
            <a-tag v-if="record.modified">有草稿修改</a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <div class="nocode-table-actions">
              <a-button
                v-if="record.capabilities.canEdit || record.capabilities.canPublish"
                type="link"
                @click="router.push({ path: '/nocode/report-center/dashboard-editor', query: { id: record.id } })"
              >
                设计
              </a-button>
              <a-divider v-if="record.capabilities.canEdit || record.capabilities.canPublish" type="vertical" />
              <a-button
                type="link"
                :disabled="!record.publishedVersion || !record.capabilities.canView || record.status === 'INACTIVE'"
                @click="router.push({ path: '/nocode/report-center/dashboard-view', query: { id: record.id } })"
              >
                打开看板
              </a-button>
              <a-divider v-if="record.capabilities.canGrant" type="vertical" />
              <a-button v-if="record.capabilities.canGrant" type="link" @click="authorizationId = record.id">
                协作权限
              </a-button>
              <a-dropdown
                v-if="record.capabilities.canEdit || record.capabilities.canPublish || record.capabilities.canDelete"
                :trigger="['click']"
              >
                <a-button type="link">更多</a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item
                      v-if="record.capabilities.canEdit"
                      key="move"
                      @click="governance = { mode: 'move', dashboard: record }"
                    >
                      移动
                    </a-menu-item>
                    <a-menu-item
                      v-if="record.capabilities.canEdit && platform.hasPermission('nocode:report:create')"
                      key="copy"
                      @click="governance = { mode: 'copy', dashboard: record }"
                    >
                      复制
                    </a-menu-item>
                    <a-menu-item
                      v-if="record.capabilities.canEdit || record.capabilities.canPublish"
                      key="history"
                      @click="governance = { mode: 'history', dashboard: record }"
                    >
                      历史版本
                    </a-menu-item>
                    <a-menu-item
                      v-if="record.capabilities.canPublish && platform.hasPermission('nocode:report:manage')"
                      key="status"
                      @click="governance = { mode: 'status', dashboard: record }"
                    >
                      {{ record.status === 'INACTIVE' ? '启用' : '停用' }}
                    </a-menu-item>
                    <a-menu-item
                      v-if="record.capabilities.canDelete"
                      key="delete"
                      danger
                      @click="governance = { mode: 'delete', dashboard: record }"
                    >
                      删除
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </template>
        </template>
      </OsTablePage>
    </div>
    <ReportFolderDialog
      v-if="folderDialog"
      v-bind="folderDialog"
      :folders="folders"
      resource-kind="DASHBOARD"
      @close="folderDialog = undefined"
      @done="folderDone"
    />
    <DashboardGovernanceDialog
      v-if="governance"
      v-bind="governance"
      :folders="folders"
      @close="governance = undefined"
      @done="governanceDone"
    />
    <DashboardAuthorization v-if="authorizationId" :dashboard-id="authorizationId" @close="closeAuthorization" />
    <OsModalForm :open="open" title="新建仪表板" :loading="saving" @ok="create" @cancel="open = false">
      <template #formItems>
        <a-form-item label="名称" required>
          <a-input v-model:value="name" aria-label="仪表板名称" :maxlength="80" />
        </a-form-item>
      </template>
    </OsModalForm>
  </section>
</template>
<style scoped>
.dashboard-list {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}
.dashboard-directory-layout {
  flex: 1;
  display: flex;
  gap: var(--spacing-lg);
  min-height: 0;
}
.dashboard-directories {
  flex: 0 0 calc(var(--spacing-lg) * 13);
  padding: var(--spacing-lg);
  min-width: 0;
  overflow: auto;
  background: var(--color-bg-container);
  border-radius: var(--radius-sm);
}
.dashboard-directory-layout > :deep(.os-table-page) {
  flex: 1;
  min-width: 0;
}
.dashboard-directory-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}
.dashboard-directory-title {
  flex-shrink: 0;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}
.dashboard-directory-help {
  color: var(--text-tertiary);
}
.dashboard-directory-tools {
  margin-top: var(--spacing-md);
}
@media (max-width: 900px) {
  .dashboard-directory-layout {
    flex-direction: column;
  }
  .dashboard-directories {
    flex-basis: auto;
    max-height: calc(var(--spacing-lg) * 10);
  }
}
</style>
