<script lang="ts" setup>
import type { Dayjs } from 'dayjs'
import type { TablePaginationConfig } from 'ant-design-vue'
import { Input, message, Modal } from 'ant-design-vue'
import type { BpmCategoryApi } from '@/api/bpm/category'
import { getCategorySimpleList } from '@/api/bpm/category'
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import { getSimpleProcessDefinitionList } from '@/api/bpm/definition'
import type { BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import { cancelProcessInstanceByAdmin, getProcessInstanceManagerPage } from '@/api/bpm/processInstance'
import type { User } from '@/api/system/user'
import { getSimpleUserList } from '@/api/system/user'
import { DeleteOutlined, EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { formatDateTime } from '@/utils/format'
import { buildCreateTimeParam, formatDuration, normalizePage } from '../../task/shared'

defineOptions({ name: 'BpmProcessInstanceManager' })

type DateRange = [Dayjs, Dayjs]

interface QueryForm {
  startUserId?: string
  name?: string
  processDefinitionId?: string
  category?: string
  status?: number
  createTime?: DateRange
}

const BPM_PROCESS_INSTANCE_STATUS_RUNNING = 1

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
const userOptions = ref<User[]>([])
const queryForm = reactive<QueryForm>({})
const pagination = reactive<TablePaginationConfig>({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 条`
})

const columns = [
  { title: '流程编号', dataIndex: 'id', key: 'id', width: 260, fixed: 'left' as const, ellipsis: true },
  {
    title: '流程名称',
    dataIndex: 'name',
    key: 'name',
    width: 220,
    fixed: 'left' as const,
    ellipsis: true,
    align: 'left' as const
  },
  { title: '流程分类', dataIndex: 'categoryName', key: 'categoryName', width: 140 },
  { title: '发起人', key: 'startUser', width: 140 },
  { title: '发起部门', key: 'startDept', width: 140 },
  { title: '流程状态', dataIndex: 'status', key: 'status', width: 130 },
  { title: '发起时间', dataIndex: 'startTime', key: 'startTime', width: 180 },
  { title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '流程耗时', dataIndex: 'durationInMillis', key: 'durationInMillis', width: 140 },
  { title: '当前审批任务', dataIndex: 'tasks', key: 'tasks', width: 260 },
  { title: '操作', key: 'action', width: 160, fixed: 'right' as const }
]

const hasOptions = computed(
  () => categoryOptions.value.length > 0 || processDefinitionOptions.value.length > 0 || userOptions.value.length > 0
)

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
    startUserId: queryForm.startUserId,
    name: queryForm.name?.trim() || undefined,
    processDefinitionId: queryForm.processDefinitionId,
    category: queryForm.category,
    status: queryForm.status,
    createTime: buildCreateTimeParam(queryForm.createTime)
  }
}

async function loadOptions() {
  try {
    const [categories, definitions, users] = await Promise.all([
      getCategorySimpleList(),
      getSimpleProcessDefinitionList(),
      getSimpleUserList()
    ])
    categoryOptions.value = normalizeList(categories)
    processDefinitionOptions.value = normalizeList(definitions)
    userOptions.value = normalizeList(users)
  } catch (error) {
    console.error('加载 BPM 流程实例管理筛选选项失败:', error)
  }
}

async function loadData() {
  loading.value = true
  try {
    const page = await getProcessInstanceManagerPage(buildQueryParams())
    const normalized = normalizePage(page)
    tableData.value = normalized.list
    pagination.total = normalized.total
  } catch (error: any) {
    console.error('加载流程实例管理失败:', error)
    message.error(error.message || '加载流程实例管理失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  loadData()
}

async function handleUserSearch(keyword: string) {
  try {
    userOptions.value = normalizeList(await getSimpleUserList(keyword))
  } catch (error) {
    console.error('搜索流程发起人失败:', error)
  }
}

function handleReset() {
  Object.assign(queryForm, {
    startUserId: undefined,
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

function handleDetail(row: BpmProcessInstanceApi.ProcessInstance, taskId?: string | number) {
  router.push({
    name: 'BpmInstanceDetail',
    query: {
      id: row.id,
      ...(taskId ? { taskId } : {})
    }
  })
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
        await cancelProcessInstanceByAdmin(row.id, reason.trim())
        message.success('取消成功')
        loadData()
      } finally {
        cancelLoading.value = false
      }
    }
  })
}

onMounted(() => {
  loadOptions()
  loadData()
})
</script>

<template>
  <div class="bpm-process-manager-page">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 2060 }"
      row-key="id"
      title="流程实例管理"
      @change="handleTableChange"
      @search="handleSearch"
    >
      <template #search="{ triggerSearch }">
        <a-form :model="queryForm" class="query-form" layout="inline">
          <a-form-item label="发起人">
            <a-select
              v-model:value="queryForm.startUserId"
              :options="userOptions.map(item => ({ label: item.nickname || item.username, value: item.id }))"
              allow-clear
              class="query-control"
              :filter-option="false"
              option-filter-prop="label"
              placeholder="请选择发起人"
              show-search
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
          message="流程定义、分类或用户为空时，筛选下拉框会保持空列表，流程实例列表仍可正常查询。"
          show-icon
          type="info"
        />
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'startUser'">
          {{ record.startUser?.nickname || '-' }}
        </template>
        <template v-else-if="column.key === 'startDept'">
          {{ record.startUser?.deptName || '-' }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="getProcessStatusMeta(record.status).color">
            {{ getProcessStatusMeta(record.status).label }}
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
        <template v-else-if="column.key === 'tasks'">
          <a-space v-if="record.tasks?.length" wrap>
            <a-button
              v-for="task in record.tasks"
              :key="task.id"
              class="task-link"
              size="small"
              type="link"
              @click="handleDetail(record, task.id)"
            >
              {{ task.name }}
            </a-button>
          </a-space>
          <span v-else>-</span>
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
          </a-space>
        </template>
      </template>
    </OsTablePage>
  </div>
</template>

<style scoped>
.bpm-process-manager-page {
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

.task-link {
  height: auto;
  padding: 0;
}
</style>
