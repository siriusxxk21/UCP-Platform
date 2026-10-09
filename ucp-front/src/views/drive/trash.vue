<script setup lang="ts">
/**
 * 回收站
 *
 * 回收站按空间隔离，列表接口必须带空间编号，因此进入页面先取空间清单再查内容；
 * 彻底删除是不可逆操作，按入口节点整树删除，删除前明确提示。
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import type { TableColumnType } from 'ant-design-vue'
import {
  DeleteOutlined,
  FileOutlined,
  FolderOutlined,
  ReloadOutlined,
  SearchOutlined,
  UndoOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { getDriveTrashList, purgeDriveEntries, restoreDriveEntry } from '@/api/drive/entry'
import { DRIVE_ENTRY_TAG_MAP } from '@/types/drive'
import type { DriveEntry, DriveId } from '@/types/drive'
import { hasPermission } from '@/utils/access'
import { formatDateTime, formatFileSize } from '@/utils/format'
import { useDriveSpaces } from './composables/useDriveSpaces'
import { useDriveUserNames } from './composables/useDriveUserNames'
import './drive-list.css'

defineOptions({ name: 'DriveTrash' })

const canDelete = computed(() => hasPermission('drive:entry:delete'))
const canRestore = computed(() => hasPermission('drive:entry:update'))
const pending = ref<DriveId | null>(null)

const columns: TableColumnType[] = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 300, align: 'left' as const, ellipsis: true },
  { title: '类型', dataIndex: 'type', key: 'type', width: 90 },
  { title: '大小', dataIndex: 'size', key: 'size', width: 110 },
  { title: '删除人', dataIndex: 'trashedBy', key: 'trashedBy', width: 140 },
  { title: '删除时间', dataIndex: 'trashedAt', key: 'trashedAt', width: 180 },
  { title: '操作', key: 'action', width: 190, fixed: 'right' }
]

const { spaces, loading: spacesLoading, loadSpaces } = useDriveSpaces()
const { loadUserNames, userName } = useDriveUserNames()

const spaceId = ref<DriveId | ''>('')

const spaceOptions = computed(() => spaces.value.map(space => ({ label: space.name, value: space.id })))

const {
  loading,
  tableData,
  pagination,
  queryForm,
  selectedRowKeys,
  selectedRows,
  handleTableChange: changeTable,
  handleQuery,
  handleReset,
  fetchData,
  clearSelection,
  updateSelection
} = useOsTablePage<DriveEntry, { name?: string }>({
  // 空间编号不进检索表单，由下方空间选择器单独维护
  fetchFn: params =>
    spaceId.value === '' ? Promise.resolve([]) : getDriveTrashList({ spaceId: spaceId.value, name: params.name }),
  defaultQuery: () => ({ name: undefined }),
  immediate: false
})

watch(
  spaceId,
  value => {
    if (value === '') return
    clearSelection()
    pagination.current = 1
    void fetchData()
  },
  { immediate: true }
)

async function init() {
  await Promise.all([loadSpaces(), loadUserNames()])
  if (spaceId.value === '') {
    spaceId.value = spaces.value.length ? spaces.value[0].id : ''
  }
}
void init()

function search() {
  clearSelection()
  handleQuery()
}

function reset() {
  clearSelection()
  handleReset()
}

async function restore(record: DriveEntry) {
  if (pending.value !== null) return
  pending.value = record.id
  try {
    const restored = await restoreDriveEntry(record.id)
    const moved = String(restored.parentId) !== String(record.parentId)
    const renamed = restored.name !== record.name
    message.success(`已还原${moved ? '到空间根目录' : ''}${renamed ? `，名称为“${restored.name}”` : ''}`)
    clearSelection()
    await fetchData()
  } catch {
    /* 同名冲突等由后端统一校验并提示 */
  } finally {
    pending.value = null
  }
}

async function purge(rows: DriveEntry[]) {
  if (!rows.length || pending.value !== null) return
  pending.value = rows[0].id
  try {
    await purgeDriveEntries(rows.map(row => row.id))
    message.success('已彻底删除')
    clearSelection()
    await fetchData()
  } catch {
    /* 引用中的文件由后端拦截 */
  } finally {
    pending.value = null
  }
}

function batchPurge(_keys: (string | number)[], rows: DriveEntry[]) {
  void purge(rows)
}
</script>

<template>
  <div class="drive-page">
    <OsTablePage
      class="drive-table"
      :columns="columns"
      :data-source="tableData"
      :loading="loading || spacesLoading || pending !== null"
      :pagination="pagination"
      row-key="id"
      title="回收站"
      :scroll="{ x: 'max-content' }"
      :row-selection="canDelete"
      :selected-row-keys="selectedRowKeys"
      :selected-rows="selectedRows"
      show-column-settings
      column-settings-key="drive-trash"
      resizable
      :tag-map="DRIVE_ENTRY_TAG_MAP"
      batch-delete-text="彻底删除"
      batch-delete-confirm-title="彻底删除确认"
      batch-delete-confirm-content="彻底删除后无法恢复，目录会连同内部内容一起删除。确定删除选中的 {count} 项吗？"
      @change="changeTable"
      @search="search"
      @selection-change="updateSelection"
      @batch-delete="batchPurge"
    >
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="空间" html-for="drive-trash-space">
            <a-select
              id="drive-trash-space"
              v-model:value="spaceId"
              class="drive-search-select"
              :options="spaceOptions"
              placeholder="选择空间"
              show-search
              option-filter-prop="label"
              :disabled="pending !== null"
              aria-label="回收站空间"
            />
          </a-form-item>
          <a-form-item label="名称">
            <a-input
              v-model:value="queryForm.name"
              class="drive-search-input"
              placeholder="请输入名称"
              allow-clear
              @press-enter="triggerSearch"
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
        <a-button :loading="loading" @click="fetchData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <span class="drive-name">
            <FolderOutlined v-if="record.type === 'FOLDER'" class="drive-name__icon" />
            <FileOutlined v-else class="drive-name__icon" />
            <span class="drive-name__text">{{ record.name }}</span>
          </span>
        </template>
        <template v-else-if="column.key === 'size'">
          {{ record.type === 'FILE' ? formatFileSize(record.size || 0) : '-' }}
        </template>
        <template v-else-if="column.key === 'trashedBy'">{{ userName(record.trashedBy) }}</template>
        <template v-else-if="column.key === 'trashedAt'">{{ formatDateTime(record.trashedAt || 0) }}</template>
        <template v-else-if="column.key === 'action'">
          <div class="drive-actions">
            <a-popconfirm
              v-if="canRestore"
              title="还原到原目录；原目录不存在时放到空间根目录，同名文件会自动改名。确定还原吗？"
              ok-text="确定"
              cancel-text="取消"
              @confirm="restore(record)"
            >
              <a-button type="link" :disabled="pending !== null">
                <UndoOutlined />
                还原
              </a-button>
            </a-popconfirm>
            <a-popconfirm
              v-if="canDelete"
              title="彻底删除后无法恢复，目录会连同内部内容一起删除。确定删除吗？"
              ok-text="确定"
              cancel-text="取消"
              @confirm="purge([record])"
            >
              <a-button type="link" danger :disabled="pending !== null">
                <DeleteOutlined />
                彻底删除
              </a-button>
            </a-popconfirm>
          </div>
        </template>
      </template>
      <template #empty>
        <a-empty description="回收站为空，删除的文件与目录会在这里保留以便还原。" />
      </template>
    </OsTablePage>
  </div>
</template>
