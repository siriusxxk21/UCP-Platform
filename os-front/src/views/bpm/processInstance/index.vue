<script lang="ts" setup>
import type { Dayjs } from 'dayjs'
import type { TablePaginationConfig } from 'ant-design-vue'
import { Input, message, Modal } from 'ant-design-vue'
import type { BpmCategoryApi } from '@/api/bpm/category'
import { getCategorySimpleList } from '@/api/bpm/category'
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import { getProcessDefinition, getSimpleProcessDefinitionList } from '@/api/bpm/definition'
import type { BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import { cancelProcessInstanceByStartUser, getProcessInstanceMyPage } from '@/api/bpm/processInstance'
import {
  DeleteOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  RetweetOutlined,
  SearchOutlined
} from '@ant-design/icons-vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { formatDateTime } from '@/utils/format'
import { buildCreateTimeParam, formatSummary, normalizePage } from '../task/shared'

defineOptions({ name: 'BpmProcessInstanceMy' })

type DateRange = [Dayjs, Dayjs]

interface QueryForm {
  name?: string
  processDefinitionId?: string
  category?: string
  status?: number
  createTime?: DateRange
}

const BPM_PROCESS_INSTANCE_STATUS_RUNNING = 1
const BPM_MODEL_FORM_TYPE_CUSTOM = 20
const BPM_MODEL_FORM_TYPE_NORMAL = 10

const processStatusOptions = [
  { label: '审批中', value: 1, color: 'processing' },
  { label: '审批通过', value: 2, color: 'success' },
  { label: '审批不通过', value: 3, color: 'error' },
  { label: '已取消', value: 4, color: 'default' }
]

const router = useRouter()
const loading = ref(false)
const cancelLoading = ref(false)
const tableData = ref<BpmProcessInstanceApi.ProcessInstance[]>([])
const categoryOptions = ref<BpmCategoryApi.Category[]>([])
const processDefinitionOptions = ref<BpmProcessDefinitionApi.ProcessDefinition[]>([])
const queryForm = reactive<QueryForm>({})
const pagination = reactive<TablePaginationConfig>({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 条`
})

const columns = [
  {
    title: '流程名称',
    dataIndex: 'name',
    key: 'name',
    width: 220,
    fixed: 'left' as const,
    ellipsis: true,
    align: 'left' as const
  },
  { title: '摘要', dataIndex: 'summary', key: 'summary', width: 260 },
  { title: '流程分类', dataIndex: 'categoryName', key: 'categoryName', width: 140 },
  { title: '流程状态', dataIndex: 'status', key: 'status', width: 220 },
  { title: '发起时间', dataIndex: 'startTime', key: 'startTime', width: 180 },
  { title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '流程编号', dataIndex: 'id', key: 'id', width: 260, ellipsis: true },
  { title: '操作', key: 'action', width: 240, fixed: 'right' as const }
]

const hasOptions = computed(() => categoryOptions.value.length > 0 || processDefinitionOptions.value.length > 0)

function normalizeList<T>(payload: T[] | { list?: T[]; records?: T[] } | null | undefined): T[] {
  if (Array.isArray(payload)) return payload
  return payload?.list || payload?.records || []
}

function getProcessStatusMeta(status?: number) {
  return (
    processStatusOptions.find(item => item.value === status) || {
      label: status === undefined || status === null ? '-' : String(status),
      color: 'default'
    }
  )
}

function buildQueryParams() {
  return {
    pageNo: pagination.current,
    pageSize: pagination.pageSize,
    name: queryForm.name?.trim() || undefined,
    processDefinitionId: queryForm.processDefinitionId,
    category: queryForm.category,
    status: queryForm.status,
    createTime: buildCreateTimeParam(queryForm.createTime)
  }
}

async function loadOptions() {
  try {
    const [categories, definitions] = await Promise.all([getCategorySimpleList(), getSimpleProcessDefinitionList()])
    categoryOptions.value = normalizeList(categories)
    processDefinitionOptions.value = normalizeList(definitions)
  } catch (error) {
    console.error('加载 BPM 流程实例筛选选项失败:', error)
  }
}

async function loadData() {
  loading.value = true
  try {
    const page = await getProcessInstanceMyPage(buildQueryParams())
    const normalized = normalizePage(page)
    tableData.value = normalized.list
    pagination.total = normalized.total
  } catch (error: any) {
    console.error('加载我的流程实例失败:', error)
    message.error(error.message || '加载我的流程实例失败')
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
    name: undefined,
    processDefinitionId: undefined,
    category: undefined,
    status: undefined,
    createTime: undefined
  })
  handleSearch()
}

function handleTableChange(pag: TablePaginationConfig) {
  pagination.current = pag.current || 1
  pagination.pageSize = pag.pageSize || 10
  loadData()
}

function handleCreate() {
  router.push({ name: 'BpmProcessInstanceCreate' })
}

function handleDetail(row: BpmProcessInstanceApi.ProcessInstance) {
  router.push({
    name: 'TaskInstanceDetail',
    query: { id: row.id }
  })
}

async function handleRestart(row: BpmProcessInstanceApi.ProcessInstance) {
  try {
    const definition = await getProcessDefinition(row.processDefinitionId)
    if (definition?.formType === BPM_MODEL_FORM_TYPE_CUSTOM) {
      if (!definition.formCustomCreatePath) {
        message.error('未配置业务表单的提交路由，无法重新发起')
        return
      }
      await router.push({
        path: definition.formCustomCreatePath,
        query: { id: row.businessKey }
      })
      return
    }
    if (definition?.formType === BPM_MODEL_FORM_TYPE_NORMAL) {
      await router.push({
        name: 'BpmProcessInstanceCreate',
        query: { processInstanceId: row.id }
      })
      return
    }
    message.error('流程定义表单类型不支持重新发起')
  } catch (error: any) {
    console.error('重新发起流程失败:', error)
    message.error(error.message || '重新发起流程失败')
  }
}

function handleCancel(row: BpmProcessInstanceApi.ProcessInstance) {
  let reason = ''
  Modal.confirm({
    title: '取消流程',
    content: () =>
      h(Input.TextArea, {
        placeholder: '请输入取消原因',
        rows: 3,
        allowClear: true,
        onChange: (event: Event) => {
          reason = (event.target as HTMLTextAreaElement).value
        }
      }),
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (!reason.trim()) {
        message.warning('请输入取消原因')
        return Promise.reject(new Error('cancel reason is required'))
      }
      cancelLoading.value = true
      try {
        await cancelProcessInstanceByStartUser(row.id, reason.trim())
        message.success('取消成功')
        loadData()
      } finally {
        cancelLoading.value = false
      }
    }
  })
}

function getRunningStatusText(row: BpmProcessInstanceApi.ProcessInstance) {
  const tasks = row.tasks || []
  if (row.status !== BPM_PROCESS_INSTANCE_STATUS_RUNNING || !tasks.length) return ''
  const firstTask = tasks[0]
  if (tasks.length === 1) return `${firstTask.assigneeUser?.nickname || '-'}（${firstTask.name}）审批中`
  return `${firstTask.assigneeUser?.nickname || '-'} 等 ${tasks.length} 人（${firstTask.name}）审批中`
}

onMounted(() => {
  loadOptions()
  loadData()
})
</script>

<template>
  <div class="bpm-process-instance-page">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1680 }"
      row-key="id"
      title="我的流程"
      @change="handleTableChange"
      @search="handleSearch"
    >
      <template #actions>
        <a-button v-hasPerm="'bpm:process-instance:create'" type="primary" @click="handleCreate">
          <PlusOutlined />
          发起流程
        </a-button>
      </template>

      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" class="query-form" layout="inline">
          <a-form-item label="流程名称">
            <a-input
              v-model:value="queryForm.name"
              allow-clear
              class="query-control"
              placeholder="请输入流程名称"
              @press-enter="triggerSearch"
            />
          </a-form-item>
          <a-form-item label="所属流程">
            <a-select
              v-model:value="queryForm.processDefinitionId"
              :options="processDefinitionOptions.map(item => ({ label: item.name, value: item.id }))"
              allow-clear
              class="query-control"
              option-filter-prop="label"
              placeholder="请选择流程定义"
              show-search
            />
          </a-form-item>
          <a-form-item label="流程分类">
            <a-select
              v-model:value="queryForm.category"
              :options="categoryOptions.map(item => ({ label: item.name, value: item.code }))"
              allow-clear
              class="query-control"
              option-filter-prop="label"
              placeholder="请选择流程分类"
              show-search
            />
          </a-form-item>
          <a-form-item label="流程状态">
            <a-select
              v-model:value="queryForm.status"
              :options="processStatusOptions"
              allow-clear
              class="query-control"
              placeholder="请选择流程状态"
            />
          </a-form-item>
          <a-form-item label="发起时间">
            <a-range-picker v-model:value="queryForm.createTime" class="query-range" show-time />
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

        <a-alert
          v-if="!hasOptions"
          class="option-alert"
          message="流程定义或分类为空时，筛选下拉框会保持空列表，我的流程列表仍可正常查询。"
          show-icon
          type="info"
        />
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'summary'">
          <span class="summary-text">{{ formatSummary(record.summary) }}</span>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-button
            v-if="getRunningStatusText(record)"
            class="status-link"
            size="small"
            type="link"
            @click="handleDetail(record)"
          >
            {{ getRunningStatusText(record) }}
          </a-button>
          <a-tag v-else :color="getProcessStatusMeta(record.status).color">
            {{ getProcessStatusMeta(record.status).label }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'startTime'">
          {{ formatDateTime(record.startTime || record.createTime) }}
        </template>
        <template v-else-if="column.key === 'endTime'">
          {{ formatDateTime(record.endTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button size="small" type="link" @click="handleDetail(record)">
              <EyeOutlined />
              详情
            </a-button>
            <a-button
              v-if="record.status === BPM_PROCESS_INSTANCE_STATUS_RUNNING"
              :loading="cancelLoading"
              danger
              size="small"
              type="link"
              @click="handleCancel(record)"
            >
              <DeleteOutlined />
              取消
            </a-button>
            <a-button v-else size="small" type="link" @click="handleRestart(record)">
              <RetweetOutlined />
              重新发起
            </a-button>
          </a-space>
        </template>
      </template>
    </OsTablePage>
  </div>
</template>

<style scoped>
.bpm-process-instance-page {
  height: 100%;
}

.query-form {
  row-gap: 12px;
}

.query-control {
  width: 200px;
}

.query-range {
  width: 360px;
}

.option-alert {
  margin-top: 12px;
}

.summary-text {
  white-space: pre-line;
}

.status-link {
  height: auto;
  padding: 0;
  white-space: normal;
  text-align: left;
}
</style>
