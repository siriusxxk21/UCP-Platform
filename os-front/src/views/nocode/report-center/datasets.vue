<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { PlusOutlined, QuestionCircleOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { DatasetDetail, ReportFolder } from '@/types/nocode/report-center'
import './report-list.css'
import DatasetLifecycleDialog from './components/DatasetLifecycleDialog.vue'
import ReportFolderDialog from './components/ReportFolderDialog.vue'
import DatasetMoveDialog from './components/DatasetMoveDialog.vue'
import { folderTree, folderPath } from '@/nocode/report-folders'
const platform = useNocodePlatform(),
  api = platform.reportCenter,
  router = useRouter()
const rows = ref<DatasetDetail[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref('')
const query = reactive({ pageNo: 1, pageSize: 10, search: '', folderId: undefined as string | undefined })
const folders = ref<ReportFolder[]>([])
const tree = computed(() => folderTree(folders.value))
const selectedFolder = computed(() => folders.value.find(f => f.id === query.folderId))
const folderDialog = ref<{ folder?: ReportFolder; parentId: string | null; deleting?: boolean }>()
const moving = ref<string>()
const createFolder = ref<string | null>(null)
function selectFolder(keys: (string | number)[]) {
  query.folderId = keys[0] ? String(keys[0]) : undefined
  search()
}
function folderDone() {
  folderDialog.value = undefined
  void load()
}
function moveDone() {
  moving.value = undefined
  void load()
}
const createOpen = ref(false),
  saving = ref(false),
  createError = ref('')
const lifecycle = ref<{ mode: 'copy' | 'delete'; dataset: DatasetDetail }>()
const form = reactive({ name: '', description: '' })
const columns = [
  { key: 'name', title: '数据集名称', width: 240, ellipsis: true },
  { key: 'folder', title: '目录', width: 200, ellipsis: true },
  { key: 'description', title: '说明', width: 260, ellipsis: true },
  { key: 'status', title: '状态', width: 110 },
  { key: 'version', title: '发布版本', width: 130 },
  { key: 'draft', title: '草稿', width: 110 },
  { key: 'action', title: '操作', width: 290, fixed: 'right' as const }
]
const pagination = computed(() => ({
  current: query.pageNo,
  pageSize: query.pageSize,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (count: number) => `共 ${count} 条`
}))
let sequence = 0
async function load() {
  const number = ++sequence
  loading.value = true
  error.value = ''
  try {
    const [result, directory] = await Promise.all([api.page({ ...query }), api.folders()])
    if (number !== sequence) return
    folders.value = directory
    if (query.folderId && query.folderId !== '0' && !directory.some(f => f.id === query.folderId)) {
      query.folderId = undefined
      return await load()
    }
    if (number !== sequence) return
    const last = Math.max(1, Math.ceil(result.total / query.pageSize))
    if (query.pageNo > last) {
      query.pageNo = last
      return await load()
    }
    rows.value = result.list
    total.value = result.total
  } catch (e) {
    if (number === sequence) error.value = errorMessage(e)
  } finally {
    if (number === sequence) loading.value = false
  }
}
function search() {
  query.pageNo = 1
  void load()
}
function reset() {
  query.search = ''
  search()
}
function openCreate() {
  Object.assign(form, { name: '', description: '' })
  createError.value = ''
  createFolder.value = selectedFolder.value?.id || null
  createOpen.value = true
}
function changePage(page: { current?: number; pageSize?: number }) {
  query.pageNo = page.pageSize !== query.pageSize ? 1 : (page.current ?? 1)
  query.pageSize = page.pageSize ?? 10
  void load()
}
function open(id: string) {
  void router.push({ path: '/nocode/report-center/dataset-editor', query: { id } })
}
async function create() {
  if (saving.value) return
  if (!form.name.trim()) {
    createError.value = '请填写数据集名称'
    return
  }
  saving.value = true
  createError.value = ''
  try {
    const result = await api.save({
      id: null,
      expectedRevision: 0,
      name: form.name.trim(),
      description: form.description,
      source: null,
      folderId: createFolder.value
    })
    createOpen.value = false
    await load()
    open(result.id)
  } catch (e) {
    createError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
function lifecycleDone() {
  lifecycle.value = undefined
  void load()
}
void load()
onScopeDispose(() => sequence++)
</script>
<template>
  <section class="nocode-list-page report-center-list">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <div class="dataset-directory-layout">
      <aside class="dataset-directories" aria-label="数据集目录">
        <div class="directory-heading">
          <div class="directory-title">
            <strong>目录</strong>
            <a-tooltip title="选择目录时包含其子目录" :trigger="['hover', 'focus']">
              <a-button type="text" size="small" class="directory-help" aria-label="目录筛选说明">
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
        <a-button block :type="query.folderId === undefined ? 'primary' : 'text'" @click="selectFolder([])">
          全部数据集
        </a-button>
        <a-button block :type="query.folderId === '0' ? 'primary' : 'text'" @click="selectFolder(['0'])">
          未分类
        </a-button>
        <a-tree
          :tree-data="tree"
          :selected-keys="query.folderId ? [query.folderId] : []"
          default-expand-all
          :key="folders.map(f => f.id).join(',')"
          block-node
          @select="
            (keys: (string | number)[]) => {
              if (keys.length) selectFolder(keys)
            }
          "
        />
        <a-space v-if="selectedFolder && platform.hasPermission('nocode:report:manage')" wrap class="directory-tools">
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
        title="数据集"
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 'max-content', y: '100%' }"
        show-column-settings
        column-settings-key="nocode-report-datasets"
        resizable
        row-key="id"
        @change="changePage"
        @search="search"
      >
        <template #search="{ triggerSearch }">
          <a-form layout="inline" :model="query" @finish="triggerSearch">
            <a-form-item label="数据集">
              <a-input
                v-model:value="query.search"
                aria-label="搜索数据集"
                class="nocode-filter-input"
                placeholder="数据集名称"
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
                <a-button @click="reset">
                  <ReloadOutlined />
                  重置
                </a-button>
              </a-space>
            </a-form-item>
          </a-form>
        </template>
        <template #actions>
          <a-button @click="load">
            <ReloadOutlined />
            刷新
          </a-button>
          <a-button v-if="platform.hasPermission('nocode:report:create')" type="primary" @click="openCreate">
            <PlusOutlined />
            新建数据集
          </a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <a v-if="column.key === 'name'" class="report-list-text" :title="record.draft.name" @click="open(record.id)">
            {{ record.draft.name }}
          </a>
          <span
            v-else-if="column.key === 'folder'"
            class="report-list-text"
            :title="folderPath(folders, record.folderId)"
          >
            {{ folderPath(folders, record.folderId) }}
          </span>
          <span v-else-if="column.key === 'description'" class="report-list-text" :title="record.draft.description">
            {{ record.draft.description || '—' }}
          </span>
          <a-tag v-else-if="column.key === 'status'" :color="record.status === 'ACTIVE' ? 'green' : 'default'">
            {{ record.status === 'ACTIVE' ? '已启用' : '已停用' }}
          </a-tag>
          <span v-else-if="column.key === 'version'">
            {{ record.publishedVersion ? `V${record.publishedVersion}` : '尚未发布' }}
          </span>
          <span v-else-if="column.key === 'draft'">{{ record.modified ? '待发布' : '已同步' }}</span>
          <div v-else-if="column.key === 'action'" class="nocode-table-actions">
            <a-button type="link" @click="open(record.id)">打开</a-button>
            <a-button v-if="platform.hasPermission('nocode:report:update')" type="link" @click="moving = record.id">
              移动
            </a-button>
            <a-button
              v-if="platform.hasPermission('nocode:report:create')"
              type="link"
              @click="lifecycle = { mode: 'copy', dataset: record }"
            >
              复制
            </a-button>
            <a-button
              v-if="platform.hasPermission('nocode:report:manage')"
              type="link"
              danger
              @click="lifecycle = { mode: 'delete', dataset: record }"
            >
              删除
            </a-button>
          </div>
        </template>
      </OsTablePage>
    </div>
    <ReportFolderDialog
      v-if="folderDialog"
      :folders="folders"
      v-bind="folderDialog"
      @close="folderDialog = undefined"
      @done="folderDone"
    />
    <DatasetMoveDialog v-if="moving" :id="moving" :folders="folders" @close="moving = undefined" @done="moveDone" />
    <DatasetLifecycleDialog
      v-if="lifecycle"
      :mode="lifecycle.mode"
      :dataset="lifecycle.dataset"
      @close="lifecycle = undefined"
      @done="lifecycleDone"
    />
    <OsModalForm
      :open="createOpen"
      title="新建数据集"
      :loading="saving"
      :width="600"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      @ok="create"
      @cancel="createOpen = false"
    >
      <template #formItems>
        <a-alert v-if="createError" type="error" :message="createError" show-icon />
        <a-form-item label="数据集名称" required>
          <a-input v-model:value="form.name" aria-label="数据集名称" :maxlength="80" />
        </a-form-item>
        <a-form-item label="目录">
          <a-tree-select
            v-model:value="createFolder"
            aria-label="新数据集目录"
            :tree-data="tree"
            placeholder="未分类"
            allow-clear
            tree-default-expand-all
            :disabled="saving"
          />
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea v-model:value="form.description" aria-label="数据集说明" :maxlength="1000" :rows="3" />
        </a-form-item>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.dataset-directory-layout {
  display: flex;
  flex: 1;
  gap: var(--spacing-lg);
  min-height: 0;
}
.dataset-directories {
  flex: 0 0 calc(var(--spacing-lg) * 13);
  padding: var(--spacing-lg);
  min-width: 0;
  overflow-x: hidden;
  overflow-y: auto;
  background: var(--color-bg-container);
  border-radius: var(--radius-sm);
}
.dataset-directory-layout > :deep(.os-table-page) {
  flex: 1;
  min-width: 0;
}
.dataset-directories :deep(.ant-tree-list-holder-inner > .ant-tree-treenode) {
  width: 100%;
}
.dataset-directories :deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.dataset-directories :deep(.ant-tree-title) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.directory-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}
.directory-title {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--spacing-xs);
  white-space: nowrap;
}
.directory-help {
  color: var(--text-tertiary);
}
.directory-tools {
  margin-top: var(--spacing-lg);
}
@media (max-width: 900px) {
  .dataset-directory-layout {
    flex-direction: column;
  }
  .dataset-directories {
    flex-basis: auto;
    max-height: calc(var(--spacing-lg) * 10);
  }
}
</style>
