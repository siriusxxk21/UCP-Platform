<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import dayjs from 'dayjs'
import type { TableColumnType } from 'ant-design-vue'
import { QuestionCircleOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { TaskEmployeeMetric, TaskEmployeeOverviewRow, TaskEmployeeSelection } from '@/types/nocode/task-management'
import '../management-tables.css'

const props = withDefaults(defineProps<{ date?: string; refreshKey?: number | string }>(), {
  date: undefined,
  refreshKey: 0
})
const emit = defineEmits<{ select: [selection: TaskEmployeeSelection] }>()
const platform = useNocodePlatform()
const allowed = computed(
  () =>
    platform.hasPermission('nocode:task:query') &&
    (platform.hasPermission('nocode:task:create') || platform.hasPermission('nocode:task:manage-all'))
)
const error = ref('')
const columns: TableColumnType[] = [
  { key: 'userName', title: '员工', width: 160, fixed: 'left', align: 'left' },
  { key: 'pendingCount', title: '未开始', width: 110 },
  { key: 'runningCount', title: '进行中', width: 110 },
  { key: 'overdueCount', title: '已逾期', width: 110 },
  { key: 'todayCount', title: '今日清单', width: 120 },
  { key: 'weekCount', title: '本周清单', width: 120 },
  { key: 'coordinationCount', title: '汇总协调', width: 120 },
  { key: 'actions', title: '操作', width: 110, fixed: 'right' }
]
type CountKey = Exclude<keyof TaskEmployeeOverviewRow, 'userId' | 'userName'>
const countMetrics: Record<CountKey, TaskEmployeeMetric> = {
  pendingCount: 'PENDING',
  runningCount: 'RUNNING',
  overdueCount: 'OVERDUE',
  todayCount: 'TODAY',
  weekCount: 'WEEK',
  coordinationCount: 'COORDINATION'
}
const metricLabels: Record<TaskEmployeeMetric, string> = {
  RELATED: '相关任务',
  ALL: '全部未结束执行任务',
  PENDING: '未开始任务',
  RUNNING: '进行中任务',
  OVERDUE: '已逾期任务',
  TODAY: '今日清单',
  WEEK: '本周清单',
  COORDINATION: '汇总协调任务'
}
const defaultQuery = () => ({ search: '', date: props.date || dayjs().format('YYYY-MM-DD') })
const {
  tableData,
  loading,
  pagination,
  queryForm,
  handleQuery,
  handleReset,
  handleTableChange,
  fetchData,
  getSearchParams
} = useOsTablePage<TaskEmployeeOverviewRow, ReturnType<typeof defaultQuery>>({
  defaultQuery,
  queryMode: 'submitted',
  clearDataOnError: true,
  correctOutOfRange: true,
  fetchFn: params => {
    error.value = ''
    return allowed.value
      ? platform.taskCenter.managementEmployees({
          search: params.search?.trim() || undefined,
          date: params.date,
          pageNo: params.pageNum,
          pageSize: params.pageSize
        })
      : Promise.resolve({ list: [], total: 0 })
  },
  onError: cause => (error.value = errorMessage(cause))
})
function select(row: TaskEmployeeOverviewRow, metric: TaskEmployeeMetric) {
  if (loading.value || !allowed.value) return
  emit('select', {
    userId: row.userId,
    userName: row.userName,
    metric,
    date: getSearchParams().date
  })
}
watch(
  () => props.date,
  value => {
    queryForm.date = value || dayjs().format('YYYY-MM-DD')
    handleQuery()
  }
)
watch(() => props.refreshKey, fetchData)
watch(allowed, handleQuery)
defineExpose({ refresh: fetchData })
</script>

<template>
  <section class="task-employee-overview nocode-list-page" aria-label="按员工查看任务">
    <a-result v-if="!allowed" status="403" title="暂无任务管理权限" />
    <template v-else>
      <a-alert v-if="error" class="notice" type="error" :message="error" show-icon>
        <template #action><a-button size="small" @click="fetchData">重新加载</a-button></template>
      </a-alert>
      <OsTablePage
        row-key="userId"
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        :pagination="pagination"
        :show-index="false"
        :scroll="{ x: 1060, y: '100%' }"
        server-pagination
        resizable
        show-column-settings
        column-settings-key="task-management-employees"
        @change="handleTableChange"
      >
        <template #search>
          <a-form layout="inline" @finish="handleQuery">
            <a-form-item label="员工">
              <a-input
                v-model:value="queryForm.search"
                class="nocode-filter-input"
                placeholder="搜索员工姓名"
                aria-label="搜索员工姓名"
                allow-clear
              />
            </a-form-item>
            <a-form-item label="查看日期">
              <a-date-picker
                v-model:value="queryForm.date"
                value-format="YYYY-MM-DD"
                :allow-clear="false"
                aria-label="查看日期"
              />
            </a-form-item>
            <a-form-item>
              <a-space>
                <a-button type="primary" html-type="submit">
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
        <template #title>
          <span>员工任务概况</span>
          <a-tooltip>
            <template #title>
              未开始、进行中、逾期仅计执行任务，不重复计入父任务；汇总协调单列员工负责的未结束父任务。
              今日与本周清单按所选日期查看，已完成记录保留；逾期指预计完成日期早于今天。任务状态为当前情况，不是历史快照。
              点击姓名查看相关任务，点击数量按该指标筛选后归组；多项工作可以属于同一任务组。
            </template>
            <button class="employee-overview-help" type="button" aria-label="员工概况统计说明">
              <QuestionCircleOutlined />
            </button>
          </a-tooltip>
        </template>
        <template #actions>
          <a-button :loading="loading" @click="fetchData">
            <ReloadOutlined />
            刷新
          </a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <a-button
            v-if="column.key === 'userName'"
            type="link"
            size="small"
            :disabled="loading"
            :aria-label="`查看${record.userName}的相关任务`"
            @click="select(record, 'RELATED')"
          >
            {{ record.userName }}
          </a-button>
          <a-button
            v-else-if="column.key in countMetrics"
            type="link"
            size="small"
            :disabled="loading"
            class="employee-overview-count"
            :class="{
              'employee-overview-count--zero': record[column.key] === 0,
              'employee-overview-count--overdue': column.key === 'overdueCount' && record.overdueCount > 0
            }"
            :aria-label="`查看${record.userName}的${metricLabels[countMetrics[column.key as CountKey]]}，${record[column.key]}项`"
            @click="select(record, countMetrics[column.key as CountKey])"
          >
            {{ record[column.key] }}
          </a-button>
          <a-button
            v-else-if="column.key === 'actions'"
            type="link"
            size="small"
            :disabled="loading"
            @click="select(record, 'RELATED')"
          >
            查看任务
          </a-button>
        </template>
        <template #empty>
          <a-empty :description="error ? '加载失败，请重新加载' : '当前管理范围内没有匹配的员工任务'" />
        </template>
      </OsTablePage>
    </template>
  </section>
</template>

<style scoped>
.task-employee-overview {
  flex: 1;
  height: auto;
  min-height: 0;
}
.employee-overview-help {
  display: inline-flex;
  align-items: center;
  margin-left: var(--spacing-sm);
  padding: 0;
  background: transparent;
  border: 0;
  color: var(--text-secondary);
  cursor: help;
}
.employee-overview-help:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 3px;
  border-radius: var(--radius-sm);
}
.employee-overview-count {
  min-width: 36px;
  font-variant-numeric: tabular-nums;
}
.employee-overview-count--zero {
  color: var(--text-tertiary);
}
.employee-overview-count--overdue {
  color: var(--error);
  font-weight: 600;
}
</style>
