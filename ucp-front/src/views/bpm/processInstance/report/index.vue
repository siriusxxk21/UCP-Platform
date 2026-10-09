<script lang="ts" setup>
import type { TablePaginationConfig } from 'ant-design-vue'
import { Input, message, Modal } from 'ant-design-vue'
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import { getProcessDefinition } from '@/api/bpm/definition'
import type { BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import { cancelProcessInstanceByAdmin, getProcessInstanceManagerPage } from '@/api/bpm/processInstance'
import type { User } from '@/api/system/user'
import { getSimpleUserList } from '@/api/system/user'
import { DeleteOutlined, EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { parseFormFields } from '@/components/form-create/helpers'
import { formatDateTime } from '@/utils/format'
import type { DateRange } from '../../task/shared'
import { buildCreateTimeParam, formatDuration, normalizePage } from '../../task/shared'

defineOptions({ name: 'BpmProcessInstanceReport' })

interface DynamicField {
  field: string
  title: string
  type: string
}

interface QueryForm {
  startUserId?: string
  name?: string
  status?: number
  createTime?: DateRange
  endTime?: DateRange
  formFieldsParams: Record<string, string>
}

const PROCESS_STATUS = [
  { label: '审批中', value: 1, color: 'processing' },
  { label: '审批通过', value: 2, color: 'success' },
  { label: '审批不通过', value: 3, color: 'error' },
  { label: '已取消', value: 4, color: 'default' }
]

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const cancellingId = ref<string | number>()
const definition = ref<BpmProcessDefinitionApi.ProcessDefinition>()
const dynamicFields = ref<DynamicField[]>([])
const users = ref<User[]>([])
const tableData = ref<BpmProcessInstanceApi.ProcessInstance[]>([])
const queryForm = reactive<QueryForm>({ formFieldsParams: {} })
const pagination = reactive<TablePaginationConfig>({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 条`
})

const processDefinitionId = computed(() => String(route.query.processDefinitionId || ''))
const processDefinitionKey = computed(() => String(route.query.processDefinitionKey || definition.value?.key || ''))
const pageTitle = computed(() => (definition.value?.name ? `${definition.value.name} - 实例报表` : '流程实例报表'))
const columns = computed(() => [
  {
    title: '流程名称',
    dataIndex: 'name',
    key: 'name',
    width: 220,
    fixed: 'left' as const,
    ellipsis: true,
    align: 'left' as const
  },
  { title: '发起人', key: 'startUser', width: 130 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 120 },
  { title: '发起时间', dataIndex: 'startTime', key: 'startTime', width: 180 },
  { title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '耗时', dataIndex: 'durationInMillis', key: 'durationInMillis', width: 130 },
  ...dynamicFields.value.map(item => ({ title: item.title, key: `form-${item.field}`, width: 160, ellipsis: true })),
  { title: '操作', key: 'action', width: 140, fixed: 'right' as const }
])

function getStatusMeta(status?: number) {
  return (
    PROCESS_STATUS.find(item => item.value === status) || {
      label: status == null ? '-' : String(status),
      color: 'default'
    }
  )
}

function parseDynamicFields(fields?: string[]) {
  const result: DynamicField[] = []
  for (const raw of fields || []) {
    try {
      parseFormFields(JSON.parse(raw), result)
    } catch {
      /* Ignore invalid historical form rules. */
    }
  }
  return result.filter(item => ['input', 'textarea'].includes(item.type))
}

function buildQueryParams() {
  return {
    pageNo: pagination.current,
    pageSize: pagination.pageSize,
    startUserId: queryForm.startUserId,
    name: queryForm.name?.trim() || undefined,
    status: queryForm.status,
    createTime: buildCreateTimeParam(queryForm.createTime),
    endTime: buildCreateTimeParam(queryForm.endTime),
    processDefinitionKey: processDefinitionKey.value || undefined,
    formFieldsParams: JSON.stringify(queryForm.formFieldsParams)
  }
}

async function handleUserSearch(keyword: string) {
  try {
    const result = await getSimpleUserList(keyword)
    users.value = Array.isArray(result) ? result : []
  } catch (error) {
    console.error('搜索流程发起人失败:', error)
  }
}

async function loadDefinition() {
  if (!processDefinitionId.value) return
  definition.value = await getProcessDefinition(processDefinitionId.value)
  dynamicFields.value = parseDynamicFields(definition.value?.formFields)
}

async function loadData() {
  loading.value = true
  try {
    const page = await getProcessInstanceManagerPage(buildQueryParams())
    const normalized = normalizePage(page)
    tableData.value = normalized.list
    pagination.total = normalized.total
  } catch (error: unknown) {
    console.error('加载流程实例报表失败:', error)
    message.error(error instanceof Error ? error.message : '加载流程实例报表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  loadData()
}

function handleReset() {
  Object.assign(queryForm, {
    startUserId: undefined,
    name: undefined,
    status: undefined,
    createTime: undefined,
    endTime: undefined,
    formFieldsParams: {}
  })
  handleSearch()
}

function handleTableChange(page: TablePaginationConfig) {
  pagination.current = page.current || 1
  pagination.pageSize = page.pageSize || 10
  loadData()
}

function handleDetail(row: BpmProcessInstanceApi.ProcessInstance) {
  router.push({ name: 'TaskInstanceDetail', query: { id: row.id } })
}

function handleCancel(row: BpmProcessInstanceApi.ProcessInstance) {
  let reason = ''
  Modal.confirm({
    title: '取消流程',
    okText: '确定',
    cancelText: '取消',
    content: () =>
      h(Input.TextArea, {
        placeholder: '请输入取消原因',
        rows: 3,
        allowClear: true,
        onChange: (event: Event) => {
          reason = (event.target as HTMLTextAreaElement).value
        }
      }),
    async onOk() {
      if (!reason.trim()) return Promise.reject(new Error('cancel reason is required'))
      cancellingId.value = row.id
      try {
        await cancelProcessInstanceByAdmin(row.id, reason.trim())
        message.success('取消成功')
        await loadData()
      } finally {
        cancellingId.value = undefined
      }
    }
  })
}

watch(processDefinitionId, async () => {
  await loadDefinition()
  handleSearch()
})
onMounted(async () => {
  const userList = await getSimpleUserList()
  users.value = Array.isArray(userList) ? userList : []
  await loadDefinition()
  await loadData()
})
</script>

<template>
  <div class="bpm-process-report-page">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1300 + dynamicFields.length * 160 }"
      row-key="id"
      :title="pageTitle"
      @change="handleTableChange"
      @search="handleSearch"
    >
      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" class="query-form" layout="inline">
          <a-form-item label="发起人">
            <a-select
              v-model:value="queryForm.startUserId"
              :options="users.map(item => ({ label: item.nickname || item.username, value: item.id }))"
              allow-clear
              class="query-control"
              :filter-option="false"
              placeholder="请选择发起人"
              show-search
              option-filter-prop="label"
              @search="handleUserSearch"
            />
          </a-form-item>
          <a-form-item label="流程名称">
            <a-input
              v-model:value="queryForm.name"
              allow-clear
              class="query-control"
              placeholder="请输入流程名称"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="流程状态">
            <a-select
              v-model:value="queryForm.status"
              :options="PROCESS_STATUS"
              allow-clear
              class="query-control"
              placeholder="请选择流程状态"
            />
          </a-form-item>
          <a-form-item label="发起时间">
            <a-range-picker v-model:value="queryForm.createTime" class="query-range" show-time />
          </a-form-item>
          <a-form-item label="结束时间">
            <a-range-picker v-model:value="queryForm.endTime" class="query-range" show-time />
          </a-form-item>
          <a-form-item v-for="field in dynamicFields" :key="field.field" :label="field.title">
            <a-input
              v-model:value="queryForm.formFieldsParams[field.field]"
              allow-clear
              class="query-control"
              :placeholder="`请输入${field.title}`"
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
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'startUser'">
          {{ record.startUser?.nickname || '-' }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="getStatusMeta(record.status).color">
            {{ getStatusMeta(record.status).label }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'startTime'">
          {{ formatDateTime(record.startTime || record.createTime) }}
        </template>
        <template v-else-if="column.key === 'endTime'">
          {{ formatDateTime(record.endTime) }}
        </template>
        <template v-else-if="column.key === 'durationInMillis'">
          {{ formatDuration(record.durationInMillis) }}
        </template>
        <template v-else-if="String(column.key).startsWith('form-')">
          {{ record.formVariables?.[String(column.key).slice(5)] ?? '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button size="small" type="link" @click="handleDetail(record)">
              <EyeOutlined />
              详情
            </a-button>
            <a-button
              v-if="record.status === 1"
              :loading="cancellingId === record.id"
              danger
              size="small"
              type="link"
              @click="handleCancel(record)"
            >
              <DeleteOutlined />
              取消
            </a-button>
          </a-space>
        </template>
      </template>
    </OsTablePage>
  </div>
</template>

<style scoped>
.bpm-process-report-page {
  height: 100%;
}
.query-form {
  row-gap: 12px;
}
.query-control {
  width: 200px;
}
.query-range {
  width: 340px;
}
</style>
