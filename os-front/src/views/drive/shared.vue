<script setup lang="ts">
/**
 * 与我共享
 *
 * 收到的共享与发起的共享是同一份数据的两种视角，按设计稿放在同一页用标签页切换：
 * 前者只读，后者可撤销；撤销后记录仍保留，用于回查分享历史。
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import type { TableColumnType } from 'ant-design-vue'
import {
  FileOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { getDriveSharePage, getSharedToMeList, revokeDriveShare } from '@/api/drive/share'
import { DRIVE_SHARE_STATUS_OPTIONS, DRIVE_SHARE_TAG_MAP } from '@/types/drive'
import type { DriveId, DriveShare, DriveShareQuery, DriveShareSubject } from '@/types/drive'
import { hasPermission } from '@/utils/access'
import { formatDateTime } from '@/utils/format'
import EntryDetailDrawer from './components/EntryDetailDrawer.vue'
import { useDriveSpaces } from './composables/useDriveSpaces'
import './drive-list.css'

defineOptions({ name: 'DriveShared' })

type ShareTab = 'received' | 'mine'

const canRevoke = computed(() => hasPermission('drive:share:delete'))
const revoking = ref<DriveId | null>(null)
const tab = ref<ShareTab>('received')

const receivedColumns: TableColumnType[] = [
  { title: '名称', dataIndex: 'entryName', key: 'entryName', width: 280, align: 'left' as const, ellipsis: true },
  { title: '类型', dataIndex: 'entryType', key: 'entryType', width: 90 },
  { title: '所属空间', dataIndex: 'spaceName', key: 'spaceName', width: 180, ellipsis: true },
  { title: '分享人', dataIndex: 'creatorName', key: 'creatorName', width: 140, ellipsis: true },
  { title: '权限', dataIndex: 'role', key: 'role', width: 110 },
  { title: '到期时间', dataIndex: 'expireTime', key: 'expireTime', width: 170 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' }
]

const mineColumns: TableColumnType[] = [
  { title: '名称', dataIndex: 'entryName', key: 'entryName', width: 240, align: 'left' as const, ellipsis: true },
  { title: '类型', dataIndex: 'entryType', key: 'entryType', width: 90 },
  { title: '所属空间', dataIndex: 'spaceName', key: 'spaceName', width: 170, ellipsis: true },
  { title: '权限', dataIndex: 'role', key: 'role', width: 100 },
  { title: '接收人', dataIndex: 'subjects', key: 'subjects', width: 240 },
  { title: '到期时间', dataIndex: 'expireTime', key: 'expireTime', width: 170 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 120, fixed: 'right' }
]

const {
  loading: receivedLoading,
  tableData: receivedData,
  pagination: receivedPagination,
  handleTableChange: receivedChange,
  fetchData: fetchReceived
} = useOsTablePage<DriveShare, Record<string, never>>({
  fetchFn: () => getSharedToMeList(),
  defaultQuery: () => ({}),
  immediate: false
})

const {
  loading: mineLoading,
  tableData: mineData,
  pagination: minePagination,
  queryForm: mineQuery,
  handleTableChange: mineChange,
  handleQuery: searchMine,
  handleReset: resetMine,
  fetchData: fetchMine
} = useOsTablePage<DriveShare, DriveShareQuery>({
  fetchFn: getDriveSharePage,
  defaultQuery: () => ({ status: undefined }),
  dataField: 'list',
  immediate: false
})

const { loadSpaces, openLocation } = useDriveSpaces()
void loadSpaces()

const detailOpen = ref(false)
const detailEntryId = ref<DriveId | null>(null)
const detailEntryName = ref('')
const detailSpaceName = ref('')

// 两个列表各自独立分页，切换标签页只加载当前视角，避免无谓请求
watch(
  tab,
  value => {
    if (value === 'received') void fetchReceived()
    else void fetchMine()
  },
  { immediate: true }
)

function openShare(row: DriveShare) {
  if (row.entryType === 'FOLDER') {
    void openLocation(row.spaceId, row.entryId)
    return
  }
  detailEntryId.value = row.entryId
  detailEntryName.value = row.entryName || ''
  detailSpaceName.value = row.spaceName || ''
  detailOpen.value = true
}

function expireText(expireTime?: number) {
  return expireTime ? formatDateTime(expireTime) : '长期有效'
}

function subjectLabel(subject: DriveShareSubject) {
  return subject.subjectName || (subject.subjectType === 'DEPT' ? '部门' : '用户')
}

async function revoke(row: DriveShare) {
  if (revoking.value !== null) return
  revoking.value = row.id
  try {
    await revokeDriveShare(row.id)
    message.success('分享已撤销')
    await fetchMine()
  } catch {
    /* 统一请求层展示错误 */
  } finally {
    revoking.value = null
  }
}
</script>

<template>
  <div class="drive-page">
    <a-tabs v-model:active-key="tab">
      <a-tab-pane key="received" tab="收到的共享">
        <OsTablePage
          class="drive-table"
          :columns="receivedColumns"
          :data-source="receivedData"
          :loading="receivedLoading"
          :pagination="receivedPagination"
          row-key="id"
          title="收到的共享"
          :scroll="{ x: 'max-content' }"
          show-column-settings
          column-settings-key="drive-shared-received"
          resizable
          :tag-map="DRIVE_SHARE_TAG_MAP"
          @change="receivedChange"
        >
          <template #actions>
            <a-button :loading="receivedLoading" @click="fetchReceived">
              <ReloadOutlined />
              刷新
            </a-button>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'entryName'">
              <span class="drive-name">
                <FolderOutlined v-if="record.entryType === 'FOLDER'" class="drive-name__icon" />
                <FileOutlined v-else class="drive-name__icon" />
                <span class="drive-name__text">{{ record.entryName }}</span>
              </span>
            </template>
            <template v-else-if="column.key === 'expireTime'">{{ expireText(record.expireTime) }}</template>
            <template v-else-if="column.key === 'action'">
              <div class="drive-actions">
                <a-button type="link" @click="openShare(record)">
                  <FolderOpenOutlined />
                  打开
                </a-button>
              </div>
            </template>
          </template>
        </OsTablePage>
      </a-tab-pane>

      <a-tab-pane key="mine" tab="我分享的">
        <OsTablePage
          class="drive-table"
          :columns="mineColumns"
          :data-source="mineData"
          :loading="mineLoading"
          :pagination="minePagination"
          row-key="id"
          title="我分享的"
          :scroll="{ x: 'max-content' }"
          show-column-settings
          column-settings-key="drive-shared-mine"
          resizable
          :tag-map="DRIVE_SHARE_TAG_MAP"
          @change="mineChange"
          @search="searchMine"
        >
          <template #search="{ triggerSearch }">
            <a-form layout="inline" :model="mineQuery">
              <a-form-item label="状态">
                <a-select
                  v-model:value="mineQuery.status"
                  class="drive-search-select"
                  :options="DRIVE_SHARE_STATUS_OPTIONS"
                  placeholder="全部"
                  allow-clear
                />
              </a-form-item>
              <a-form-item>
                <a-space>
                  <a-button type="primary" @click="triggerSearch">
                    <SearchOutlined />
                    查询
                  </a-button>
                  <a-button @click="resetMine">
                    <ReloadOutlined />
                    重置
                  </a-button>
                </a-space>
              </a-form-item>
            </a-form>
          </template>
          <template #actions>
            <a-button :loading="mineLoading" @click="fetchMine">
              <ReloadOutlined />
              刷新
            </a-button>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'entryName'">
              <span class="drive-name">
                <FolderOutlined v-if="record.entryType === 'FOLDER'" class="drive-name__icon" />
                <FileOutlined v-else class="drive-name__icon" />
                <span class="drive-name__text">{{ record.entryName }}</span>
              </span>
            </template>
            <template v-else-if="column.key === 'subjects'">
              <span v-if="record.subjects?.length" class="drive-subject-list">
                <a-tag v-for="subject in record.subjects" :key="subject.subjectType + String(subject.subjectId)">
                  {{ subjectLabel(subject) }}
                </a-tag>
              </span>
              <span v-else>-</span>
            </template>
            <template v-else-if="column.key === 'expireTime'">{{ expireText(record.expireTime) }}</template>
            <template v-else-if="column.key === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
            <template v-else-if="column.key === 'action'">
              <div class="drive-actions">
                <a-popconfirm
                  v-if="canRevoke && record.status === 'ACTIVE'"
                  title="撤销后接收人立即失去该分享权限，确定撤销吗？"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="revoke(record)"
                >
                  <a-button
                    type="link"
                    danger
                    :loading="revoking === record.id"
                    :disabled="revoking !== null && revoking !== record.id"
                  >
                    <StopOutlined />
                    撤销
                  </a-button>
                </a-popconfirm>
                <span v-else>-</span>
              </div>
            </template>
          </template>
        </OsTablePage>
      </a-tab-pane>
    </a-tabs>

    <EntryDetailDrawer
      :open="detailOpen"
      :entry-id="detailEntryId"
      :entry-name="detailEntryName"
      :space-name="detailSpaceName"
      @close="detailOpen = false"
    />
  </div>
</template>
