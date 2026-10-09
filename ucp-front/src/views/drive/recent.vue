<script setup lang="ts">
/**
 * 最近使用
 *
 * 访问时间由工作区与详情抽屉在打开节点时写入，这里只读不写；
 * 列表已由后端按访问时间倒序并过滤掉失效节点，前端不再排序。
 */
import { computed, ref } from 'vue'
import type { TableColumnType } from 'ant-design-vue'
import { FileOutlined, FolderOutlined, FolderOpenOutlined, ReloadOutlined, StarOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { getRecentList, updateFavorite } from '@/api/drive/mark'
import { DRIVE_ENTRY_TAG_MAP } from '@/types/drive'
import type { DriveId, DriveMarkedEntry } from '@/types/drive'
import { hasPermission } from '@/utils/access'
import { formatDateTime, formatFileSize } from '@/utils/format'
import EntryDetailDrawer from './components/EntryDetailDrawer.vue'
import { useDriveSpaces } from './composables/useDriveSpaces'
import './drive-list.css'

defineOptions({ name: 'DriveRecent' })

const canFavorite = computed(() => hasPermission('drive:mark:update'))
const pendingId = ref<DriveId | null>(null)

const columns: TableColumnType[] = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 300, align: 'left' as const, ellipsis: true },
  { title: '类型', dataIndex: 'type', key: 'type', width: 90 },
  { title: '所属空间', dataIndex: 'spaceName', key: 'spaceName', width: 180, ellipsis: true },
  { title: '大小', dataIndex: 'size', key: 'size', width: 110 },
  { title: '最近访问', dataIndex: 'accessTime', key: 'accessTime', width: 170 },
  { title: '操作', key: 'action', width: 300, fixed: 'right' }
]

const {
  loading,
  tableData,
  pagination,
  handleTableChange: changeTable,
  fetchData
} = useOsTablePage<DriveMarkedEntry, Record<string, never>>({
  fetchFn: () => getRecentList(50),
  defaultQuery: () => ({})
})

const { loadSpaces, openLocation } = useDriveSpaces()
void loadSpaces()

const detailOpen = ref(false)
const detailEntryId = ref<DriveId | null>(null)
const detailEntryName = ref('')
const detailSpaceName = ref('')

function openEntry(record: DriveMarkedEntry) {
  if (record.type === 'FOLDER') {
    void openLocation(record.spaceId, record.entryId)
    return
  }
  detailEntryId.value = record.entryId
  detailEntryName.value = record.name
  detailSpaceName.value = record.spaceName || ''
  detailOpen.value = true
}

async function toggleFavorite(record: DriveMarkedEntry) {
  if (pendingId.value !== null) return
  pendingId.value = record.entryId
  try {
    await updateFavorite(record.entryId, !record.favorite)
    await fetchData()
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    pendingId.value = null
  }
}
</script>

<template>
  <div class="drive-page">
    <OsTablePage
      class="drive-table"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      row-key="entryId"
      title="最近使用"
      :scroll="{ x: 'max-content' }"
      show-column-settings
      column-settings-key="drive-recent"
      resizable
      :tag-map="DRIVE_ENTRY_TAG_MAP"
      @change="changeTable"
    >
      <template #actions>
        <a-button :loading="loading" @click="fetchData">
          <ReloadOutlined />
          刷新
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <button type="button" class="drive-name drive-name--link" :title="record.name" @click="openEntry(record)">
            <FolderOutlined v-if="record.type === 'FOLDER'" class="drive-name__icon" />
            <FileOutlined v-else class="drive-name__icon" />
            <span class="drive-name__text">{{ record.name }}</span>
          </button>
        </template>
        <template v-else-if="column.key === 'size'">
          {{ record.type === 'FILE' ? formatFileSize(record.size || 0) : '-' }}
        </template>
        <template v-else-if="column.key === 'accessTime'">{{ formatDateTime(record.accessTime || 0) }}</template>
        <template v-else-if="column.key === 'action'">
          <div class="drive-actions">
            <a-button type="link" @click="openEntry(record)">
              <FolderOpenOutlined />
              打开
            </a-button>
            <a-button type="link" @click="openLocation(record.spaceId, record.parentId)">
              <FolderOutlined />
              所在目录
            </a-button>
            <a-button
              v-if="canFavorite"
              type="link"
              :loading="pendingId === record.entryId"
              :disabled="pendingId !== null && pendingId !== record.entryId"
              @click="toggleFavorite(record)"
            >
              <StarOutlined />
              {{ record.favorite ? '取消收藏' : '收藏' }}
            </a-button>
          </div>
        </template>
      </template>
    </OsTablePage>

    <EntryDetailDrawer
      :open="detailOpen"
      :entry-id="detailEntryId"
      :entry-name="detailEntryName"
      :space-name="detailSpaceName"
      @close="detailOpen = false"
      @changed="fetchData"
    />
  </div>
</template>
