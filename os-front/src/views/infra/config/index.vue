<script lang="ts" setup>
import type { TableColumnType } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import {
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined
} from '@ant-design/icons-vue'
import { computed, ref } from 'vue'
import type { InfraConfig, InfraConfigQuery, InfraConfigType } from '@/api/infra/config'
import { deleteConfig, deleteConfigList, exportConfig, getConfigPage, INFRA_CONFIG_TYPE } from '@/api/infra/config'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useUserStore } from '@/stores/user'
import { hasAnyAccess } from '@/utils/access'
import { triggerDownload } from '@/utils/file'
import { formatDateTime } from '@/utils/format'
import ConfigModal from './components/ConfigModal.vue'

interface ConfigQueryForm {
  name?: string
  key?: string
  type?: InfraConfigType
  createTime?: [Dayjs, Dayjs]
}

const CONFIG_TYPE_OPTIONS: Array<{ label: string; value: InfraConfigType }> = [
  { label: '系统内置', value: INFRA_CONFIG_TYPE.SYSTEM },
  { label: '自定义', value: INFRA_CONFIG_TYPE.CUSTOM }
]

const userStore = useUserStore()
const modalOpen = ref(false)
const editingRecord = ref<InfraConfig | null>(null)
const exporting = ref(false)

function hasPermission(permission: string) {
  return hasAnyAccess(permission, userStore.permissions, userStore.roles.includes('super_admin'))
}

const canCreate = computed(() => hasPermission('infra:config:create'))
const canUpdate = computed(() => hasPermission('infra:config:update'))
const canDelete = computed(() => hasPermission('infra:config:delete'))
const canExport = computed(() => hasPermission('infra:config:export'))

function normalizeQuery(params: ConfigQueryForm & { pageNum: number; pageSize: number }): InfraConfigQuery {
  return {
    ...params,
    createTime: params.createTime
      ? [params.createTime[0].format('YYYY-MM-DD HH:mm:ss'), params.createTime[1].format('YYYY-MM-DD HH:mm:ss')]
      : undefined
  }
}

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
} = useOsTablePage<InfraConfig, ConfigQueryForm>({
  defaultQuery: () => ({ name: undefined, key: undefined, type: undefined, createTime: undefined }),
  dataField: 'list',
  fetchFn: params => getConfigPage(normalizeQuery(params)),
  onError: () => message.error('加载参数配置失败')
})

const columns: TableColumnType<InfraConfig>[] = [
  { title: '参数主键', dataIndex: 'id', key: 'id', width: 110 },
  { title: '参数分类', dataIndex: 'category', key: 'category', width: 130, ellipsis: true, align: 'left' },
  { title: '参数名称', dataIndex: 'name', key: 'name', width: 180, ellipsis: true, align: 'left' },
  { title: '参数键名', dataIndex: 'key', key: 'key', width: 220, ellipsis: true, align: 'left' },
  { title: '参数键值', dataIndex: 'value', key: 'value', width: 180, ellipsis: true, align: 'left' },
  { title: '是否可见', dataIndex: 'visible', key: 'visible', width: 100 },
  { title: '系统内置', dataIndex: 'type', key: 'type', width: 100 },
  { title: '备注', dataIndex: 'remark', key: 'remark', width: 160, ellipsis: true, align: 'left' },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 170,
    customRender: ({ text }) => formatDateTime(text)
  },
  { title: '操作', key: 'action', width: 160, fixed: 'right' }
]

const tagMap = {
  visible: {
    true: { label: '是', color: 'success' },
    false: { label: '否', color: 'default' }
  },
  type: {
    [INFRA_CONFIG_TYPE.SYSTEM]: { label: '是', color: 'blue' },
    [INFRA_CONFIG_TYPE.CUSTOM]: { label: '否', color: 'default' }
  }
}

function openCreate() {
  editingRecord.value = null
  modalOpen.value = true
}

function openEdit(record: InfraConfig) {
  editingRecord.value = record
  modalOpen.value = true
}

function handleSaved(action: 'create' | 'update') {
  message.success(action === 'create' ? '创建成功' : '更新成功')
  editingRecord.value = null
  fetchData()
}

async function handleDelete(record: InfraConfig) {
  if (record.id === undefined || record.type === INFRA_CONFIG_TYPE.SYSTEM) return
  await deleteConfig(record.id)
  message.success('删除成功')
  await fetchData()
}

async function handleBatchDelete(keys: Array<string | number>) {
  await deleteConfigList(keys)
  message.success(`已删除 ${keys.length} 个参数配置`)
  clearSelection()
  await fetchData()
}

async function handleExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const params = normalizeQuery({
      ...queryForm,
      pageNum: pagination.current,
      pageSize: pagination.pageSize
    })
    const { pageNum: _pageNum, pageSize: _pageSize, ...query } = params
    const blob = await exportConfig(query)
    triggerDownload(blob, '参数配置.xls')
    message.success('导出成功')
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="config-page">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :row-selection="
        canDelete
          ? { getCheckboxProps: (record: InfraConfig) => ({ disabled: record.type === INFRA_CONFIG_TYPE.SYSTEM }) }
          : false
      "
      :scroll="{ x: 'max-content', y: 'calc(100vh - 420px)' }"
      :selected-row-keys="selectedRowKeys"
      :selected-rows="selectedRows"
      :show-index="false"
      :tag-map="tagMap"
      column-settings-key="infra-config-list"
      resizable
      show-column-settings
      title="参数列表"
      @change="handleTableChange"
      @search="handleQuery"
      @selection-change="updateSelection"
      @batch-delete="handleBatchDelete"
    >
      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" layout="inline">
          <a-form-item label="参数名称">
            <a-input
              v-model:value="queryForm.name"
              allow-clear
              placeholder="请输入参数名称"
              style="width: 180px"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="参数键名">
            <a-input
              v-model:value="queryForm.key"
              allow-clear
              placeholder="请输入参数键名"
              style="width: 200px"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="系统内置">
            <a-select
              v-model:value="queryForm.type"
              :options="CONFIG_TYPE_OPTIONS"
              allow-clear
              placeholder="全部"
              style="width: 140px"
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

      <template #toolbar>
        <a-button v-if="canExport" :loading="exporting" @click="handleExport">
          <DownloadOutlined />
          导出
        </a-button>
      </template>

      <template #actions>
        <a-button v-if="canCreate" type="primary" @click="openCreate">
          <PlusOutlined />
          新增参数
        </a-button>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <div class="action-cell">
            <a-button v-if="canUpdate" type="link" @click="openEdit(record)">
              <EditOutlined />
              编辑
            </a-button>
            <template v-if="canUpdate && canDelete">
              <span class="action-sep">|</span>
            </template>
            <a-tooltip
              v-if="canDelete"
              :title="record.type === INFRA_CONFIG_TYPE.SYSTEM ? '系统内置参数不能删除' : undefined"
            >
              <span>
                <a-popconfirm
                  :disabled="record.type === INFRA_CONFIG_TYPE.SYSTEM"
                  :title="`确定删除“${record.name}”吗？`"
                  cancel-text="取消"
                  ok-text="删除"
                  @confirm="handleDelete(record)"
                >
                  <a-button :disabled="record.type === INFRA_CONFIG_TYPE.SYSTEM" danger type="link">
                    <DeleteOutlined />
                    删除
                  </a-button>
                </a-popconfirm>
              </span>
            </a-tooltip>
          </div>
        </template>
      </template>
    </OsTablePage>

    <ConfigModal v-model:open="modalOpen" :record="editingRecord" @success="handleSaved" />
  </div>
</template>

<style scoped>
.config-page {
  height: 100%;
}

.action-cell {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}

.action-sep {
  color: #d1d5db;
  padding: 0 2px;
  user-select: none;
}

.config-page :deep(.ant-btn-link) {
  height: auto;
  padding: 0 6px;
  font-size: var(--table-body-font-size, 14px);
}
</style>
