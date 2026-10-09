<script setup lang="ts">
import type { TableColumnType } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import {
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
  SearchOutlined,
  UploadOutlined
} from '@ant-design/icons-vue'
import { ref } from 'vue'
import type { InfraFile } from '@/api/infra/file'
import { deleteFile, deleteFileList, getFilePage } from '@/api/infra/file'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { copyText } from '@/utils/clipboard'
import { formatDateTime, formatFileSize } from '@/utils/format'
import FileUploadModal from './components/FileUploadModal.vue'

interface FileQuery {
  path: string
  type: string
  createTime?: [Dayjs, Dayjs]
}

const uploadOpen = ref(false)

const {
  loading,
  tableData,
  pagination,
  queryForm,
  selectedRowKeys,
  selectedRows,
  handleQuery,
  handleReset,
  handleTableChange,
  fetchData,
  clearSelection,
  updateSelection
} = useOsTablePage<InfraFile, FileQuery>({
  defaultQuery: () => ({ path: '', type: '', createTime: undefined }),
  dataField: 'list',
  fetchFn: params =>
    getFilePage({
      ...params,
      createTime: params.createTime
        ? [params.createTime[0].format('YYYY-MM-DD HH:mm:ss'), params.createTime[1].format('YYYY-MM-DD HH:mm:ss')]
        : undefined
    }),
  onError: () => message.error('加载文件列表失败')
})

const columns: TableColumnType<InfraFile>[] = [
  { title: '文件名', dataIndex: 'name', key: 'name', width: 180, ellipsis: true, align: 'left' },
  { title: '文件路径', dataIndex: 'path', key: 'path', width: 220, ellipsis: true },
  { title: '文件内容', key: 'content', width: 100, align: 'center' },
  {
    title: '文件大小',
    dataIndex: 'size',
    key: 'size',
    width: 110,
    customRender: ({ text }) => formatFileSize(Number(text) || 0)
  },
  { title: '文件类型', dataIndex: 'type', key: 'type', width: 180, ellipsis: true },
  {
    title: '上传时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
    customRender: ({ text }) => formatDateTime(text)
  },
  { title: '操作', key: 'action', width: 180, fixed: 'right' }
]

function isImage(file: InfraFile) {
  return file.type?.startsWith('image/') ?? false
}

function isPdf(file: InfraFile) {
  return file.type === 'application/pdf' || file.name?.toLowerCase().endsWith('.pdf')
}

function getFileAccessUrl(file: InfraFile) {
  return file.url?.replace('/admin-api/infra/file/', '/api/infra/file/') ?? ''
}

function openFile(file: InfraFile) {
  const url = getFileAccessUrl(file)
  if (!url) {
    message.warning('文件 URL 为空')
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}

async function copyUrl(file: InfraFile) {
  const url = getFileAccessUrl(file)
  if (!url) {
    message.warning('文件 URL 为空')
    return
  }

  try {
    await copyText(url)
    message.success('链接已复制')
  } catch {
    message.error('复制失败，请检查浏览器剪贴板权限')
  }
}

async function handleDelete(file: InfraFile) {
  await deleteFile(file.id)
  message.success('删除成功')
  await fetchData()
}

async function handleBatchDelete(keys: Array<string | number>) {
  await deleteFileList(keys)
  message.success(`已删除 ${keys.length} 个文件`)
  clearSelection()
  await fetchData()
}

function handleUploadSuccess() {
  pagination.current = 1
  fetchData()
}
</script>

<template>
  <div class="file-management-page">
    <OsTablePage
      title="文件列表"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1180, y: 'calc(100vh - 420px)' }"
      row-selection
      :selected-row-keys="selectedRowKeys"
      :selected-rows="selectedRows"
      show-column-settings
      column-settings-key="infra-file-list"
      resizable
      @search="handleQuery"
      @change="handleTableChange"
      @selection-change="updateSelection"
      @batch-delete="handleBatchDelete"
    >
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="文件路径">
            <a-input
              v-model:value="queryForm.path"
              placeholder="请输入文件路径"
              allow-clear
              style="width: 180px"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="文件类型">
            <a-input
              v-model:value="queryForm.type"
              placeholder="例如 image/png"
              allow-clear
              style="width: 180px"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="上传时间">
            <a-range-picker
              v-model:value="queryForm.createTime"
              :show-time="{ defaultValue: [dayjs().startOf('day'), dayjs().endOf('day')] }"
              allow-clear
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />
                重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>

      <template #actions>
        <a-button type="primary" @click="uploadOpen = true">
          <UploadOutlined />
          上传文件
        </a-button>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'content'">
          <a-image
            v-if="isImage(record)"
            :src="getFileAccessUrl(record)"
            :width="48"
            :height="48"
            class="file-thumbnail"
          />
          <a-button v-else type="link" size="small" @click="openFile(record)">
            <EyeOutlined v-if="isPdf(record)" />
            <DownloadOutlined v-else />
            {{ isPdf(record) ? '预览' : '下载' }}
          </a-button>
        </template>

        <template v-else-if="column.key === 'action'">
          <div class="action-cell">
            <a-button type="link" style="color: #059669; padding: 0 6px; font-weight: 500" @click="copyUrl(record)">
              <CopyOutlined />
              复制链接
            </a-button>
            <span class="action-sep">|</span>
            <a-popconfirm
              :title="`确定删除文件\u201C${record.name || record.path}\u201D吗？`"
              ok-text="删除"
              cancel-text="取消"
              @confirm="handleDelete(record)"
            >
              <a-button type="link" danger style="padding: 0 6px; font-weight: 500">
                <DeleteOutlined />
                删除
              </a-button>
            </a-popconfirm>
          </div>
        </template>
      </template>
    </OsTablePage>

    <FileUploadModal v-model:open="uploadOpen" @success="handleUploadSuccess" />
  </div>
</template>

<style scoped>
.file-management-page {
  height: 100%;
}

.action-cell {
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.action-sep {
  color: #d1d5db;
  padding: 0 6px;
  user-select: none;
}

.file-management-page :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.file-management-page :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}

.file-thumbnail :deep(img) {
  border-radius: 6px;
  object-fit: cover;
}
</style>
