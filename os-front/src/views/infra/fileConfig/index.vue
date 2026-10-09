<script setup lang="ts">
import type { TableColumnType } from 'ant-design-vue'
import type { Dayjs } from 'dayjs'
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { ref } from 'vue'
import {
  deleteFileConfig,
  deleteFileConfigList,
  FILE_STORAGE_OPTIONS,
  getFileConfigPage,
  testFileConfig,
  updateFileConfigMaster,
} from '@/api/infra/file-config'
import type { FileStorage, InfraFileConfig } from '@/api/infra/file-config'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { formatDateTime } from '@/utils/format'
import FileConfigModal from './components/FileConfigModal.vue'

interface FileConfigQueryForm {
  name: string
  storage?: FileStorage
  createTime?: [Dayjs, Dayjs]
}

const modalOpen = ref(false)
const editingRecord = ref<InfraFileConfig | null>(null)
const testingId = ref<number>()
const masterUpdatingId = ref<number>()

const storageMap = new Map(FILE_STORAGE_OPTIONS.map(item => [item.value, item.label]))

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
  updateSelection,
} = useOsTablePage<InfraFileConfig, FileConfigQueryForm>({
  defaultQuery: () => ({ name: '', storage: undefined, createTime: undefined }),
  dataField: 'list',
  fetchFn: params => getFileConfigPage({
    ...params,
    createTime: params.createTime
      ? [params.createTime[0].format('YYYY-MM-DD HH:mm:ss'), params.createTime[1].format('YYYY-MM-DD HH:mm:ss')]
      : undefined,
  }),
  onError: () => message.error('加载文件配置失败'),
})

const columns: TableColumnType<InfraFileConfig>[] = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 80 },
  { title: '配置名', dataIndex: 'name', key: 'name', width: 150, ellipsis: true, align: 'left' },
  { title: '存储器', dataIndex: 'storage', key: 'storage', width: 100 },
  { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
  { title: '主配置', dataIndex: 'master', key: 'master', width: 80, align: 'center' },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 150,
    customRender: ({ text }) => formatDateTime(text),
  },
  { title: '操作', key: 'action', width: 290 },
]

function openCreate() {
  editingRecord.value = null
  modalOpen.value = true
}

function openEdit(record: InfraFileConfig) {
  editingRecord.value = record
  modalOpen.value = true
}

function handleSaved() {
  message.success(editingRecord.value ? '更新成功' : '创建成功')
  editingRecord.value = null
  fetchData()
}

async function handleTest(record: InfraFileConfig) {
  if (!record.id)
    return
  testingId.value = record.id
  try {
    const url = await testFileConfig(record.id)
    Modal.confirm({
      title: '测试上传成功',
      content: '测试文件已成功上传，是否打开访问地址？',
      okText: '打开文件',
      cancelText: '关闭',
      onOk: () => window.open(url, '_blank', 'noopener,noreferrer'),
    })
  }
  finally {
    testingId.value = undefined
  }
}

async function handleSetMaster(record: InfraFileConfig) {
  if (!record.id || record.master)
    return
  masterUpdatingId.value = record.id
  try {
    await updateFileConfigMaster(record.id)
    message.success('主配置更新成功')
    await fetchData()
  }
  finally {
    masterUpdatingId.value = undefined
  }
}

async function handleDelete(record: InfraFileConfig) {
  if (!record.id)
    return
  await deleteFileConfig(record.id)
  message.success('删除成功')
  await fetchData()
}

async function handleBatchDelete(keys: Array<string | number>) {
  await deleteFileConfigList(keys)
  message.success(`已删除 ${keys.length} 个文件配置`)
  clearSelection()
  await fetchData()
}
</script>

<template>
  <div class="file-config-page">
    <OsTablePage
      title="文件配置列表"
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :row-selection="{ getCheckboxProps: (record: InfraFileConfig) => ({ disabled: record.master }) }"
      :selected-row-keys="selectedRowKeys"
      :selected-rows="selectedRows"
      show-column-settings
      column-settings-key="infra-file-config-list"
      resizable
      @search="handleQuery"
      @change="handleTableChange"
      @selection-change="updateSelection"
      @batch-delete="handleBatchDelete"
    >
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="配置名">
            <a-input
              v-model:value="queryForm.name"
              placeholder="请输入配置名"
              allow-clear
              style="width: 180px"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="存储器">
            <a-select
              v-model:value="queryForm.storage"
              :options="FILE_STORAGE_OPTIONS"
              placeholder="全部"
              allow-clear
              style="width: 180px"
            />
          </a-form-item>
          <a-form-item label="创建时间">
            <a-range-picker
              v-model:value="queryForm.createTime"
              :show-time="{ defaultValue: [dayjs().startOf('day'), dayjs().endOf('day')] }"
              allow-clear
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>

      <template #actions>
        <a-button type="primary" @click="openCreate">
          <PlusOutlined />
          新增配置
        </a-button>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'storage'">
          <a-tag color="blue">
            {{ storageMap.get(record.storage) || '未知存储器' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'master'">
          <a-tag :color="record.master ? 'success' : 'default'">
            {{ record.master ? '是' : '否' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <div class="action-cell">
            <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500" @click="openEdit(record)">
              <EditOutlined />
              编辑
            </a-button>
            <span class="action-sep">|</span>
            <a-button
              type="link"
              style="color: #6b7280; padding: 0 6px"
              :loading="testingId === record.id"
              @click="handleTest(record)"
            >
              <ExperimentOutlined />
              测试
            </a-button>
            <span class="action-sep">|</span>
            <a-popconfirm
              :title="`确定将\u201C${record.name}\u201D设为主配置吗？`"
              ok-text="确定"
              cancel-text="取消"
              @confirm="handleSetMaster(record)"
            >
              <a-button
                type="link"
                style="color: #6b7280; padding: 0 6px"
                :disabled="record.master"
                :loading="masterUpdatingId === record.id"
              >
                <CheckCircleOutlined />
                设为主配置
              </a-button>
            </a-popconfirm>
            <span class="action-sep">|</span>
            <a-popconfirm
              :title="record.master ? '主配置不能删除' : `确定删除\u201C${record.name}\u201D吗？`"
              ok-text="删除"
              cancel-text="取消"
              :disabled="record.master"
              @confirm="handleDelete(record)"
            >
              <a-button type="link" danger style="padding: 0 6px; font-weight: 500" :disabled="record.master">
                <DeleteOutlined />
                删除
              </a-button>
            </a-popconfirm>
          </div>
        </template>
      </template>
    </OsTablePage>

    <FileConfigModal v-model:open="modalOpen" :record="editingRecord" @success="handleSaved" />
  </div>
</template>

<style scoped>
.file-config-page {
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

.file-config-page :deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}

.file-config-page :deep(.ant-btn-link .anticon) {
  font-size: 13px;
}
</style>
