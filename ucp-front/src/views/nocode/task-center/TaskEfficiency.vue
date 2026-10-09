<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import type { TableColumnType } from 'ant-design-vue'
import { QuestionCircleOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import EfficiencyOverview from './TaskEfficiencyOverview.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskStates, taskStateColors, taskTime } from '@/nocode/task-center'
import { defaultEfficiencyQuery, efficiencyDuration, efficiencyPeriodError } from '@/nocode/task-efficiency'
import type {
  TaskEfficiencyEmployee,
  TaskEfficiencyOptions,
  TaskEfficiencyOverview,
  TaskEfficiencyQuery,
  TaskEfficiencyRecord,
  TaskEfficiencyTask
} from '@/types/nocode/task-efficiency'
import TaskDetail from './TaskDetail.vue'
import '../management-tables.css'

const platform = useNocodePlatform(),
  api = platform.taskCenter
const allowed = computed(
  () =>
    platform.hasPermission('nocode:task:query') &&
    (platform.hasPermission('nocode:task:create') || platform.hasPermission('nocode:task:manage-all'))
)
const allManaged = computed(() => platform.hasPermission('nocode:task:manage-all'))
const filter = reactive(defaultEfficiencyQuery())
const range = ref<[string, string]>([filter.from, filter.to])
const applied = ref<TaskEfficiencyQuery>({ ...filter })
const tab = ref('overview')
const overview = ref<TaskEfficiencyOverview>(),
  loading = ref(false),
  error = ref(''),
  filterError = ref('')
const optionsError = reactive({ employees: '', templates: '' })
const optionsLoading = reactive({ employees: false, templates: false })
const options = ref<TaskEfficiencyOptions>({ employees: [], templates: [] })
const selectedTask = ref<string>()
const recordContext = ref<{ title: string; employeeId?: number; rootTaskId?: string }>()
const drawerWidth = ref(Math.min(window.innerWidth - 24, 1360))
const resizeDrawer = () => (drawerWidth.value = Math.min(window.innerWidth - 24, 1360))
const recordError = ref(''),
  employeeError = ref(''),
  taskError = ref('')
const taskSearch = ref(''),
  appliedTaskSearch = ref('')
const presets = [
  { label: '近 7 天', value: [dayjs().subtract(6, 'day'), dayjs()] },
  { label: '本月', value: [dayjs().startOf('month'), dayjs()] },
  { label: '上月', value: [dayjs().subtract(1, 'month').startOf('month'), dayjs().subtract(1, 'month').endOf('month')] }
]

const employeeColumnDefs: TableColumnType[] = [
  { key: 'employeeName', dataIndex: 'employeeName', title: '员工', width: 150, fixed: 'left' },
  { key: 'standardMinutes', title: '期间标准工时', width: 165, sorter: true },
  { key: 'recordCount', dataIndex: 'recordCount', title: '计量记录', width: 110, sorter: true },
  { key: 'participatedTaskCount', dataIndex: 'participatedTaskCount', title: '计量任务组', width: 110 },
  { key: 'completedNodeCount', dataIndex: 'completedNodeCount', title: '期间完成节点', width: 140, sorter: true },
  { key: 'activeNodeCount', dataIndex: 'activeNodeCount', title: '当前在办节点', width: 135 },
  { key: 'overdueNodeCount', title: '当前逾期节点', width: 135, sorter: true },
  { key: 'actions', title: '操作', width: 170, fixed: 'right' }
]
const taskColumnDefs: TableColumnType[] = [
  { key: 'title', title: '整组任务 / 模板', width: 260, fixed: 'left' },
  { key: 'assigneeName', dataIndex: 'assigneeName', title: '总负责人', width: 120 },
  { key: 'status', title: '当前状态', width: 110 },
  { key: 'standardMinutes', title: '期间标准工时', width: 165, sorter: true },
  { key: 'referenceMinutes', title: '整项参考时长', width: 150 },
  { key: 'progress', title: '整组完成进度', width: 160 },
  { key: 'overdueNodeCount', title: '当前逾期节点', width: 135, sorter: true },
  { key: 'recordCount', dataIndex: 'recordCount', title: '计量记录', width: 110, sorter: true },
  { key: 'elapsedMinutes', title: '完成自然历时', width: 160 },
  { key: 'actualEnd', title: '实际完成时间', width: 175 },
  { key: 'actions', title: '操作', width: 170, fixed: 'right' }
]
const recordColumns: TableColumnType[] = [
  { key: 'firstCountedAt', title: '首次计量时间', width: 170 },
  { key: 'employeeName', dataIndex: 'employeeName', title: '员工', width: 110 },
  { key: 'taskTitle', title: '任务节点 / 整组任务', width: 230 },
  { key: 'entryName', dataIndex: 'entryName', title: '办理项', width: 160 },
  { key: 'recordId', dataIndex: 'recordId', title: '业务记录 ID', width: 170, ellipsis: true },
  { key: 'calculation', title: '计量依据', width: 210 },
  { key: 'standardMinutes', title: '计得标准工时', width: 145 },
  { key: 'lastHandledAt', title: '最近办理时间', width: 170 },
  { key: 'actions', title: '操作', width: 110, fixed: 'right' }
]
const ruleLabels = { RECORD_ONCE: '按数据条数', QUANTITY: '按业务数量', CONDITION: '按条件计量' }
const tableDefaults = () => ({ sortBy: 'standardMinutes' as TaskEfficiencyQuery['sortBy'], descending: true })
const employees = useOsTablePage<TaskEfficiencyEmployee, ReturnType<typeof tableDefaults>>({
  defaultQuery: tableDefaults,
  immediate: false,
  clearDataOnError: true,
  correctOutOfRange: true,
  fetchFn: async params => {
    employeeError.value = ''
    const snapshot = applied.value
    const result = allowed.value
      ? await api.efficiencyEmployees({
          ...snapshot,
          sortBy: params.sortBy,
          descending: params.descending,
          pageNo: params.pageNum,
          pageSize: params.pageSize
        })
      : { list: [], total: 0 }
    return allowed.value && snapshot === applied.value ? result : { list: [], total: 0 }
  },
  onError: cause => (employeeError.value = errorMessage(cause))
})
const tasks = useOsTablePage<TaskEfficiencyTask, ReturnType<typeof tableDefaults>>({
  defaultQuery: tableDefaults,
  immediate: false,
  clearDataOnError: true,
  correctOutOfRange: true,
  fetchFn: async params => {
    taskError.value = ''
    const snapshot = applied.value
    const result = allowed.value
      ? await api.efficiencyTasks({
          ...snapshot,
          search: appliedTaskSearch.value || undefined,
          sortBy: params.sortBy,
          descending: params.descending,
          pageNo: params.pageNum,
          pageSize: params.pageSize
        })
      : { list: [], total: 0 }
    return allowed.value && snapshot === applied.value ? result : { list: [], total: 0 }
  },
  onError: cause => (taskError.value = errorMessage(cause))
})
const records = useOsTablePage<TaskEfficiencyRecord, { search: string }>({
  defaultQuery: () => ({ search: '' }),
  immediate: false,
  queryMode: 'submitted',
  clearDataOnError: true,
  correctOutOfRange: true,
  fetchFn: async params => {
    recordError.value = ''
    const context = recordContext.value
    const snapshot = applied.value
    const result =
      context && allowed.value
        ? await api.efficiencyRecords({
            ...applied.value,
            ...(context.employeeId == null ? {} : { employeeId: context.employeeId }),
            ...(context.rootTaskId ? { rootTaskId: context.rootTaskId } : {}),
            search: params.search || undefined,
            pageNo: params.pageNum,
            pageSize: params.pageSize
          })
        : { list: [], total: 0 }
    return allowed.value && context === recordContext.value && snapshot === applied.value
      ? result
      : { list: [], total: 0 }
  },
  onError: cause => (recordError.value = errorMessage(cause))
})
// setup 返回的嵌套 refs 不会在模板属性里自动解包。
const { tableData: employeeRows, loading: employeeLoading } = employees
const { tableData: taskRows, loading: taskLoading } = tasks
const { tableData: recordRows, loading: recordLoading } = records
const employeeOptions = computed(() => options.value.employees.map(row => ({ value: row.id, label: row.name })))
const templateOptions = computed(() => options.value.templates.map(row => ({ value: row.id, label: row.name })))
const periodLabel = computed(() => `${applied.value.from} 至 ${applied.value.to}`)
const employeeLabel = computed(
  () =>
    options.value.employees.find(row => row.id === applied.value.employeeId)?.name ||
    (applied.value.employeeId ? `员工 #${applied.value.employeeId}` : '全部员工')
)
const templateLabel = computed(
  () =>
    options.value.templates.find(row => row.id === applied.value.templateId)?.name ||
    (applied.value.templateId ? '所选模板' : '全部模板')
)
const dirtyFilter = computed(
  () =>
    range.value?.[0] !== applied.value.from ||
    range.value?.[1] !== applied.value.to ||
    filter.employeeId !== applied.value.employeeId ||
    filter.templateId !== applied.value.templateId
)
function sortedColumns(columns: TableColumnType[], query: ReturnType<typeof tableDefaults>): TableColumnType[] {
  return columns.map(column =>
    column.sorter
      ? {
          ...column,
          // 服务端始终保留排序，避免「取消排序」回到默认倒序后无法切换。
          sortDirections: ['descend', 'ascend', 'descend'],
          sortOrder: query.sortBy === column.key ? (query.descending ? 'descend' : 'ascend') : null
        }
      : column
  )
}
const employeeColumns = computed(() => sortedColumns(employeeColumnDefs, employees.queryForm))
const taskColumns = computed(() => sortedColumns(taskColumnDefs, tasks.queryForm))

let generation = 0
type OptionKind = keyof TaskEfficiencyOptions
const optionGeneration = { employees: 0, templates: 0 }
const optionTimers: Partial<Record<OptionKind, ReturnType<typeof setTimeout>>> = {}
async function loadOptions(kind: OptionKind, search = '') {
  if (!allowed.value) return
  clearTimeout(optionTimers[kind])
  const token = ++optionGeneration[kind]
  optionsLoading[kind] = true
  optionsError[kind] = ''
  try {
    const result = await api.efficiencyOptions({
      ...applied.value,
      employeeId: undefined,
      templateId: kind === 'templates' ? undefined : filter.templateId,
      search: search || undefined
    })
    if (token !== optionGeneration[kind]) return
    // 两个选择器独立搜索、防乱序，并保留已选和已应用名称，避免下钻后退化为 ID。
    if (kind === 'employees') {
      const kept = options.value.employees.filter(
        row =>
          [filter.employeeId, applied.value.employeeId].includes(row.id) &&
          !result.employees.some(item => item.id === row.id)
      )
      options.value.employees = [...kept, ...result.employees]
    } else {
      const kept = options.value.templates.filter(
        row =>
          [filter.templateId, applied.value.templateId].includes(row.id) &&
          !result.templates.some(item => item.id === row.id)
      )
      options.value.templates = [...kept, ...result.templates]
    }
  } catch (cause) {
    if (token === optionGeneration[kind]) optionsError[kind] = errorMessage(cause)
  } finally {
    if (token === optionGeneration[kind]) optionsLoading[kind] = false
  }
}
function searchOptions(kind: OptionKind, search: string) {
  clearTimeout(optionTimers[kind])
  optionGeneration[kind]++
  optionTimers[kind] = setTimeout(() => void loadOptions(kind, search), 250)
}
async function loadOverview() {
  if (!allowed.value) return
  const token = ++generation
  loading.value = true
  error.value = ''
  overview.value = undefined
  try {
    const result = await api.efficiencyOverview({ ...applied.value })
    if (token === generation) overview.value = result
  } catch (cause) {
    if (token === generation) error.value = errorMessage(cause)
  } finally {
    if (token === generation) loading.value = false
  }
}
function reloadTable() {
  if (tab.value === 'employees') void employees.fetchData()
  if (tab.value === 'tasks') void tasks.fetchData()
}
function applyQuery(next: TaskEfficiencyQuery, nextTab = tab.value) {
  applied.value = next
  generation++
  overview.value = undefined
  loading.value = false
  error.value = ''
  employees.pagination.current = tasks.pagination.current = 1
  employeeRows.value = []
  taskRows.value = []
  recordContext.value = undefined
  recordRows.value = []
  if (nextTab === 'overview') void loadOverview()
  if (nextTab !== tab.value) tab.value = nextTab
  else reloadTable()
}
function query() {
  filterError.value = efficiencyPeriodError(range.value?.[0], range.value?.[1])
  if (filterError.value) return
  applyQuery({
    from: range.value[0],
    to: range.value[1],
    employeeId: filter.employeeId,
    templateId: filter.templateId
  })
}
function reset() {
  const defaults = defaultEfficiencyQuery()
  Object.assign(filter, defaults, { employeeId: undefined, templateId: undefined })
  range.value = [defaults.from, defaults.to]
  taskSearch.value = appliedTaskSearch.value = ''
  query()
  void loadOptions('employees')
  void loadOptions('templates')
}
function refresh() {
  if (tab.value === 'overview') void loadOverview()
  else {
    generation++
    overview.value = undefined
    loading.value = false
  }
  reloadTable()
  if (recordContext.value) void records.fetchData()
}
function openEmployee(row: Pick<TaskEfficiencyEmployee, 'employeeId' | 'employeeName'>) {
  recordRows.value = []
  recordContext.value = { employeeId: row.employeeId, title: `${row.employeeName || '员工'} · 工时明细` }
  records.handleReset()
}
function openTaskRecords(row: TaskEfficiencyTask) {
  recordRows.value = []
  recordContext.value = { rootTaskId: row.rootTaskId, title: `${row.title} · 工时明细` }
  records.handleReset()
}
function openAllRecords() {
  recordRows.value = []
  recordContext.value = { title: '全部计量记录' }
  records.handleReset()
}
function clearTaskSearch() {
  taskSearch.value = ''
  searchTasks()
}
function employeeTasks(row: TaskEfficiencyEmployee) {
  if (!options.value.employees.some(item => item.id === row.employeeId))
    options.value.employees.push({ id: row.employeeId, name: row.employeeName })
  filter.employeeId = row.employeeId
  taskSearch.value = appliedTaskSearch.value = ''
  applyQuery({ ...applied.value, employeeId: row.employeeId }, 'tasks')
}
function searchTasks() {
  appliedTaskSearch.value = taskSearch.value.trim()
  tasks.handleQuery()
}
function changeTable(
  kind: 'employees' | 'tasks',
  pagination: { current: number; pageSize: number },
  _filters: unknown,
  sorter: { columnKey?: TaskEfficiencyQuery['sortBy']; order?: string },
  extra?: { action?: string }
) {
  const table = kind === 'employees' ? employees : tasks
  if (extra?.action === 'sort') {
    table.queryForm.sortBy = sorter.order ? sorter.columnKey : 'standardMinutes'
    table.queryForm.descending = sorter.order !== 'ascend'
    table.handleTableChange({ ...pagination, current: 1 })
  } else table.handleTableChange(pagination)
}
watch(tab, value => {
  if (value === 'overview' && !overview.value) void loadOverview()
  else reloadTable()
})
watch(allowed, value => {
  if (value) {
    refresh()
    void loadOptions('employees')
    void loadOptions('templates')
  } else {
    generation++
    optionGeneration.employees++
    optionGeneration.templates++
    clearTimeout(optionTimers.employees)
    clearTimeout(optionTimers.templates)
    overview.value = undefined
    recordContext.value = undefined
    selectedTask.value = undefined
    applied.value = { ...applied.value }
    employeeRows.value = []
    taskRows.value = []
    recordRows.value = []
    options.value = { employees: [], templates: [] }
  }
})
onMounted(() => {
  window.addEventListener('resize', resizeDrawer)
  if (allowed.value) {
    void loadOverview()
    void loadOptions('employees')
    void loadOptions('templates')
  }
})
onBeforeUnmount(() => {
  generation++
  optionGeneration.employees++
  optionGeneration.templates++
  clearTimeout(optionTimers.employees)
  clearTimeout(optionTimers.templates)
  window.removeEventListener('resize', resizeDrawer)
})
</script>

<template>
  <main class="task-efficiency" aria-label="能效统计">
    <a-result
      v-if="!allowed"
      status="403"
      title="暂无能效统计权限"
      sub-title="仅任务管理人员可查看授权范围内的统计。"
    />
    <template v-else>
      <div class="efficiency-toolbar">
        <span class="efficiency-scope">{{ allManaged ? '全部可管理任务' : '仅我创建的任务组' }}</span>
        <a-space>
          <a-popover title="统计口径" trigger="click" placement="bottomRight">
            <template #content>
              <div class="efficiency-help">
                <p>标准工时：按办理项规则计得的工作量，不是员工实际在线时长。</p>
                <p>计量任务组只统计期间产生有效标准工时的任务组；没有配置工时的任务仍可计入完成节点。</p>
                <p>
                  同一任务节点、办理项、员工、数据只计一次，反复保存不重复。按首次有效办理记录的提交日期归属；待审批通过后回溯计入提交日。后续数量变化、删除会更新结果，不能作为冻结结算。
                </p>
                <p>
                  节点数量只计最下级执行节点，避免父子重复。完成按实际完成日期统计；当前在办、逾期不受日期限制，仍受员工与模板条件限制。
                </p>
                <p>在办包含未开始、进行中、暂停和待验收；已完成与已取消不计入。逾期以预计完成时间判断。</p>
                <p>整项参考时长独立保留；自然历时是实际开始到完成的时间，可能包含等待，不与标准工时相加或据此打分。</p>
              </div>
            </template>
            <a-button type="text">
              <QuestionCircleOutlined />
              统计口径
            </a-button>
          </a-popover>
          <a-button :loading="loading" @click="refresh">
            <ReloadOutlined />
            刷新
          </a-button>
        </a-space>
      </div>
      <section class="efficiency-filters" aria-label="统计筛选">
        <a-form :model="filter" layout="inline" @finish="query">
          <a-form-item label="统计日期">
            <a-range-picker
              v-model:value="range"
              value-format="YYYY-MM-DD"
              :presets="presets"
              :allow-clear="false"
              aria-label="统计日期"
            />
          </a-form-item>
          <a-form-item label="员工">
            <a-select
              v-model:value="filter.employeeId"
              class="efficiency-select"
              show-search
              allow-clear
              :filter-option="false"
              :loading="optionsLoading.employees"
              :options="employeeOptions"
              placeholder="全部员工"
              aria-label="员工"
              @search="(search: string) => searchOptions('employees', search)"
              @dropdown-visible-change="(open: boolean) => open && loadOptions('employees')"
            />
          </a-form-item>
          <a-form-item label="模板">
            <a-select
              v-model:value="filter.templateId"
              class="efficiency-select"
              show-search
              allow-clear
              :filter-option="false"
              :loading="optionsLoading.templates"
              :options="templateOptions"
              placeholder="全部模板"
              aria-label="模板"
              @search="(search: string) => searchOptions('templates', search)"
              @dropdown-visible-change="(open: boolean) => open && loadOptions('templates')"
            />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" html-type="submit">
                <SearchOutlined />
                查询
              </a-button>
              <a-button @click="reset">重置</a-button>
            </a-space>
          </a-form-item>
        </a-form>
        <div class="efficiency-applied" aria-live="polite">
          <span>已应用：{{ periodLabel }}</span>
          <span>{{ employeeLabel }} · {{ templateLabel }}</span>
          <a-tag v-if="dirtyFilter" color="orange">筛选已修改，点击查询生效</a-tag>
        </div>
        <a-alert v-if="filterError" type="warning" :message="filterError" show-icon />
        <template v-for="kind in ['employees', 'templates'] as const" :key="kind">
          <a-alert
            v-if="optionsError[kind]"
            type="warning"
            :message="`${kind === 'employees' ? '员工' : '模板'}候选加载失败，已选条件仍保留`"
            show-icon
          >
            <template #action><a-button size="small" @click="loadOptions(kind)">重试</a-button></template>
          </a-alert>
        </template>
      </section>

      <a-alert v-if="tab === 'overview' && error" type="error" :message="error" show-icon>
        <template #action><a-button size="small" @click="loadOverview">重新加载</a-button></template>
      </a-alert>
      <a-tabs v-model:active-key="tab" class="efficiency-tabs">
        <a-tab-pane key="overview" tab="总览">
          <EfficiencyOverview
            :overview="overview"
            :loading="loading"
            :error="error"
            :period="applied"
            @records="openAllRecords"
            @employees="tab = 'employees'"
            @employee="openEmployee"
          />
        </a-tab-pane>
        <a-tab-pane key="employees" tab="员工分析">
          <p class="efficiency-note">标准工时与完成数按所选期间统计；在办、逾期为当前状态。待分配节点不归属员工。</p>
          <a-alert v-if="employeeError" type="error" :message="employeeError" show-icon>
            <template #action><a-button size="small" @click="employees.fetchData">重试</a-button></template>
          </a-alert>
          <OsTablePage
            class="nocode-embedded-table efficiency-table"
            title="员工工作量"
            row-key="employeeId"
            index-fixed="left"
            :columns="employeeColumns"
            :data-source="employeeRows"
            :loading="employeeLoading"
            :pagination="employees.pagination"
            server-pagination
            resizable
            show-column-settings
            column-settings-key="task-efficiency-employees-v2"
            :hidden-column-keys="['recordCount', 'participatedTaskCount']"
            :scroll="{ x: 980 }"
            @change="(p, f, s, e) => changeTable('employees', p, f, s, e)"
          >
            <template #bodyCell="{ column, record }">
              <a-button
                v-if="column.key === 'standardMinutes'"
                type="link"
                size="small"
                :disabled="!record.recordCount"
                @click="openEmployee(record)"
              >
                {{ efficiencyDuration(record.standardMinutes) }}
              </a-button>
              <span
                v-else-if="column.key === 'overdueNodeCount'"
                :class="{ 'efficiency-overdue': record.overdueNodeCount }"
              >
                {{ record.overdueNodeCount }}
              </span>
              <div v-else-if="column.key === 'actions'" class="nocode-table-actions">
                <a-button type="link" size="small" @click="employeeTasks(record)">看任务</a-button>
                <a-button type="link" size="small" :disabled="!record.recordCount" @click="openEmployee(record)">
                  工时明细
                </a-button>
              </div>
              <span v-else-if="column.dataIndex">{{ record[column.dataIndex] ?? '—' }}</span>
            </template>
            <template #empty>{{ employeeError ? '加载失败，请重试' : '当前筛选下暂无员工数据' }}</template>
          </OsTablePage>
        </a-tab-pane>
        <a-tab-pane key="tasks" tab="任务分析">
          <p class="efficiency-note">
            每行一个任务组；{{
              applied.employeeId
                ? '工时为所选员工在期间内的贡献，进度与逾期仍为整组现状。'
                : '工时为所选期间产出，进度与逾期为整组现状。'
            }}
            仅展示期间存续或产生工时的任务。
          </p>
          <a-alert v-if="taskError" type="error" :message="taskError" show-icon>
            <template #action><a-button size="small" @click="tasks.fetchData">重试</a-button></template>
          </a-alert>
          <OsTablePage
            class="nocode-embedded-table efficiency-table"
            title="任务工作量"
            row-key="rootTaskId"
            index-fixed="left"
            :columns="taskColumns"
            :data-source="taskRows"
            :loading="taskLoading"
            :pagination="tasks.pagination"
            server-pagination
            resizable
            show-column-settings
            column-settings-key="task-efficiency-tasks-v2"
            :hidden-column-keys="['referenceMinutes', 'recordCount', 'elapsedMinutes', 'actualEnd']"
            :scroll="{ x: 1150 }"
            @change="(p, f, s, e) => changeTable('tasks', p, f, s, e)"
          >
            <template #search>
              <a-form :model="{ search: taskSearch }" layout="inline" @finish="searchTasks">
                <a-form-item label="任务名称">
                  <a-input v-model:value="taskSearch" placeholder="搜索整组任务" allow-clear />
                </a-form-item>
                <a-form-item>
                  <a-button html-type="submit" type="primary">
                    <SearchOutlined />
                    查询任务
                  </a-button>
                </a-form-item>
                <a-form-item v-if="appliedTaskSearch">
                  <a-button @click="clearTaskSearch">清除任务筛选</a-button>
                </a-form-item>
              </a-form>
            </template>
            <template #bodyCell="{ column, record }">
              <div v-if="column.key === 'title'" class="efficiency-name">
                <a-button type="link" size="small" @click="selectedTask = record.rootTaskId">
                  {{ record.title }}
                </a-button>
                <small>
                  {{ record.templateName || '独立任务'
                  }}{{ record.templateVersion ? ` · V${record.templateVersion}` : '' }}
                </small>
              </div>
              <a-tag
                v-else-if="column.key === 'status'"
                :color="taskStateColors[record.status as keyof typeof taskStateColors]"
              >
                {{ taskStates[record.status as keyof typeof taskStates] || record.status }}
              </a-tag>
              <a-button
                v-else-if="column.key === 'standardMinutes'"
                type="link"
                size="small"
                :disabled="!record.recordCount"
                @click="openTaskRecords(record)"
              >
                {{ efficiencyDuration(record.standardMinutes) }}
              </a-button>
              <span v-else-if="column.key === 'referenceMinutes'">
                {{ record.referenceMinutes ? efficiencyDuration(record.referenceMinutes) : '未设置' }}
              </span>
              <span v-else-if="column.key === 'elapsedMinutes'">{{ efficiencyDuration(record.elapsedMinutes) }}</span>
              <span v-else-if="column.key === 'actualEnd'">
                {{ record.actualEnd ? taskTime(record.actualEnd) : '—' }}
              </span>
              <div v-else-if="column.key === 'progress'" class="efficiency-progress">
                <span>正常完成 {{ record.completedNodeCount }} / {{ record.totalNodeCount }}</span>
                <small v-if="record.cancelledNodeCount">另有 {{ record.cancelledNodeCount }} 个已取消节点</small>
              </div>
              <span
                v-else-if="column.key === 'overdueNodeCount'"
                :class="{ 'efficiency-overdue': record.overdueNodeCount }"
              >
                {{ record.overdueNodeCount }}
              </span>
              <div v-else-if="column.key === 'actions'" class="nocode-table-actions">
                <a-button type="link" size="small" @click="selectedTask = record.rootTaskId">任务详情</a-button>
                <a-button type="link" size="small" :disabled="!record.recordCount" @click="openTaskRecords(record)">
                  工时明细
                </a-button>
              </div>
              <span v-else-if="column.dataIndex">{{ record[column.dataIndex] ?? '—' }}</span>
            </template>
            <template #empty>{{ taskError ? '加载失败，请重试' : '当前筛选下暂无任务数据' }}</template>
          </OsTablePage>
        </a-tab-pane>
      </a-tabs>

      <OsModalForm
        :open="!!recordContext"
        :title="recordContext?.title || '工时明细'"
        :width="drawerWidth"
        :show-footer="false"
        :wrap-form="false"
        display-mode="drawer"
        :allow-switch-display="false"
        @cancel="recordContext = undefined"
      >
        <template #formItems>
          <p class="efficiency-note">
            {{ periodLabel }} · {{ recordContext?.employeeId ? '' : `${employeeLabel} · ` }}{{ templateLabel }}
            <br />
            每行一条有效计量记录，同人同节点同办理项下重复保存不重复计量。
          </p>
          <a-alert v-if="recordError" type="error" :message="recordError" show-icon>
            <template #action><a-button size="small" @click="records.fetchData">重试</a-button></template>
          </a-alert>
          <OsTablePage
            class="nocode-embedded-table efficiency-table"
            title="计量记录"
            :row-key="(row: TaskEfficiencyRecord) => `${row.taskId}:${row.entryKey}:${row.employeeId}:${row.recordId}`"
            :columns="recordColumns"
            :data-source="recordRows"
            :loading="recordLoading"
            :pagination="records.pagination"
            server-pagination
            resizable
            show-column-settings
            column-settings-key="task-efficiency-records-v2"
            :hidden-column-keys="['recordId', 'lastHandledAt']"
            :scroll="{ x: 1190 }"
            @change="records.handleTableChange"
          >
            <template #search>
              <a-form :model="records.queryForm" layout="inline" @finish="records.handleQuery">
                <a-form-item label="关键字">
                  <a-input v-model:value="records.queryForm.search" placeholder="员工、任务或办理项" allow-clear />
                </a-form-item>
                <a-form-item>
                  <a-button type="primary" html-type="submit">
                    <SearchOutlined />
                    查询
                  </a-button>
                </a-form-item>
              </a-form>
            </template>
            <template #bodyCell="{ column, record }">
              <span v-if="column.key === 'firstCountedAt' || column.key === 'lastHandledAt'">
                {{ taskTime(record[column.key]) }}
              </span>
              <div v-else-if="column.key === 'taskTitle'" class="efficiency-name">
                <span>{{ record.taskTitle }}</span>
                <small v-if="record.taskId !== record.rootTaskId">{{ record.rootTitle }}</small>
              </div>
              <div v-else-if="column.key === 'calculation'" class="efficiency-name">
                <span v-if="record.unitMinutes == null">{{ record.quantity }} · 分段计时</span>
                <span v-else>{{ record.quantity }} × {{ efficiencyDuration(record.unitMinutes) }}</span>
                <small>{{ ruleLabels[record.ruleMode as keyof typeof ruleLabels] }}</small>
              </div>
              <span v-else-if="column.key === 'unitMinutes' || column.key === 'standardMinutes'">
                {{
                  column.key === 'unitMinutes' && record.unitMinutes == null
                    ? '分段计时'
                    : efficiencyDuration(record[column.key])
                }}
              </span>
              <a-button
                v-else-if="column.key === 'actions'"
                type="link"
                size="small"
                @click="selectedTask = record.taskId"
              >
                任务详情
              </a-button>
              <span v-else-if="column.dataIndex">{{ record[column.dataIndex] ?? '—' }}</span>
            </template>
            <template #empty>{{ recordError ? '加载失败，请重试' : '当前筛选下暂无有效计量记录' }}</template>
          </OsTablePage>
        </template>
      </OsModalForm>
      <TaskDetail
        v-if="selectedTask"
        :id="selectedTask"
        @close="selectedTask = undefined"
        @select="id => (selectedTask = id)"
        @changed="refresh"
      />
    </template>
  </main>
</template>

<style scoped>
.task-efficiency {
  min-width: 0;
  height: 100%;
  overflow: auto;
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}
.efficiency-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}
.efficiency-scope,
.efficiency-note {
  color: var(--text-secondary);
  font-size: var(--table-body-font-size);
}
.efficiency-help {
  max-width: 420px;
  line-height: 1.7;
}
.efficiency-help p:last-child {
  margin-bottom: 0;
}
.efficiency-filters {
  padding: var(--spacing-lg);
  background: var(--color-bg-container);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.efficiency-filters :deep(.ant-form) {
  gap: var(--spacing-md) var(--spacing-lg);
}
.efficiency-filters :deep(.ant-form-item) {
  margin: 0;
}
.efficiency-filters :deep(.ant-alert) {
  margin-top: var(--spacing-md);
}
.efficiency-select {
  width: 180px;
}
.efficiency-applied {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-sm) var(--spacing-lg);
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.efficiency-overdue {
  color: var(--error);
}
.efficiency-tabs {
  min-width: 0;
}
.efficiency-note {
  margin: 0 0 var(--spacing-md);
  line-height: 1.7;
}
.efficiency-table {
  min-height: 280px;
  margin-top: var(--spacing-md);
}
.efficiency-progress {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  font-variant-numeric: tabular-nums;
}
.efficiency-progress small {
  color: var(--text-secondary);
}
.efficiency-name {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-xs);
  text-align: left;
  overflow-wrap: anywhere;
}
.efficiency-name :deep(.ant-btn) {
  padding: 0;
  white-space: normal;
  height: auto;
  text-align: left;
}
.efficiency-name small {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
@media (max-width: 600px) {
  .task-efficiency {
    padding: var(--spacing-md);
  }
  .efficiency-filters :deep(.ant-form-item) {
    width: 100%;
  }
  .efficiency-filters :deep(.ant-picker-range),
  .efficiency-select {
    width: 100%;
  }
}
</style>
