<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import dayjs, { type Dayjs } from 'dayjs'
import { Grid } from 'ant-design-vue'
import { LeftOutlined, RightOutlined, ReloadOutlined, SearchOutlined, ArrowLeftOutlined } from '@ant-design/icons-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import {
  summaryCounts,
  historyValue,
  overviewTables,
  employeeOverview,
  changeDescription
} from '@/nocode/record-history'
import type {
  HistoryResult,
  HistoryRow,
  HistoryTableSummary,
  HistoryPage,
  HistoryDetail
} from '@/types/nocode/record-history'
import HistoryRecordDrawer from './HistoryRecordDrawer.vue'

const platform = useNocodePlatform()
const screens = Grid.useBreakpoint()
const compact = computed(() => screens.value.md === false)
const range = ref<[Dayjs, Dayjs]>([dayjs().startOf('day'), dayjs()])
const throughNow = ref(true),
  custom = ref(false)
const result = ref<HistoryResult>(),
  loading = ref(false),
  error = ref('')
const mode = ref<'business' | 'employee'>('business')
const business = ref<string>(),
  employee = ref<string>(),
  keyword = ref('')
const changedOnly = ref(true),
  deletedOnly = ref(false),
  summaryPage = ref(1)
const table = ref<HistoryTableSummary>(),
  drillEmployee = ref<string>(),
  sheet = ref('changes')
const page = ref(1),
  pageSize = ref(10),
  pageData = ref<HistoryPage>(),
  pageLoading = ref(false),
  pageError = ref('')
const detailId = ref<string>(),
  detailData = ref<HistoryDetail>(),
  detailLoading = ref(false),
  detailError = ref('')
const summaryAnchor = ref<HTMLElement>(),
  tableAnchor = ref<HTMLElement>()
let request = 0,
  pageRequest = 0,
  detailRequest = 0
let returnControl = ''

const singleDay = computed(() => range.value[0].isSame(range.value[1], 'day'))
const nextDisabled = computed(() => !singleDay.value || !range.value[0].startOf('day').isBefore(dayjs().startOf('day')))
const apps = computed(() => {
  const options = new Map<string, string>()
  for (const t of result.value?.tables || [])
    t.applicationIds.forEach((id, i) => options.set(id, t.applicationNames[i] || id))
  return [...options]
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
})
const employees = computed(() =>
  employeeOverview(result.value?.tables || []).map(p => ({ value: p.id, label: p.name }))
)
const selectedTables = computed(() =>
  overviewTables(result.value?.tables || [], {
    application: business.value,
    employee: employee.value,
    keyword: keyword.value
  })
)
const tables = computed(() =>
  overviewTables(selectedTables.value, {
    employee: employee.value,
    changedOnly: changedOnly.value,
    deletedOnly: deletedOnly.value
  })
)
const counts = computed(() => summaryCounts(selectedTables.value, employee.value))
const activeTables = computed(() => selectedTables.value.filter(t => summaryCounts([t], employee.value).operations > 0))
const people = computed(() => employeeOverview(tables.value, employee.value))
const businessRows = computed(() =>
  tables.value.map(t => ({ id: t.objectId, name: t.name, table: t, counts: summaryCounts([t], employee.value) }))
)
const summaryRows = computed(() => (mode.value === 'business' ? businessRows.value : people.value))
const summaryColumns = computed(() =>
  [
    {
      title: mode.value === 'business' ? '业务表格' : '人员',
      key: 'name',
      width: compact.value ? undefined : 210,
      align: 'center' as const
    },
    { title: '变化内容', key: 'changes', width: 280, align: 'center' as const },
    {
      title: mode.value === 'business' ? '参与人员' : '涉及表格',
      key: 'involved',
      width: 320,
      align: 'center' as const
    },
    { title: '操作', key: 'actions', width: compact.value ? 96 : 115, fixed: 'right' as const }
  ].filter(column => !compact.value || column.key === 'name' || (column.key === 'actions' && mode.value === 'business'))
)
const rowColumns = computed(() => [
  ...(pageData.value?.table.fields || []).map(f => ({ title: f.name, key: f.id, width: 200 })),
  { title: '记录编号', dataIndex: 'id', key: '_recordId', width: 130 },
  { title: '这段时间的变化', key: '_state', width: 190 },
  { title: '操作', key: '_actions', width: 115, fixed: 'right' as const }
])
const rows = computed(() => (pageLoading.value || pageError.value ? [] : pageData.value?.table.rows || []))
const tableCounts = computed(() => summaryCounts(table.value ? [table.value] : [], drillEmployee.value))
const endCovered = computed(
  () => !!result.value && !!table.value && Date.parse(result.value.end) >= Date.parse(table.value.coveredFrom)
)
const personName = computed(() => employees.value.find(p => p.value === drillEmployee.value)?.label || '所选人员')
const time = (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—')
const rangeLabel = computed(() =>
  !result.value
    ? ''
    : dayjs(result.value.start).isSame(dayjs(result.value.end), 'day')
      ? dayjs(result.value.start).format('M月D日') + '的变化'
      : dayjs(result.value.start).format('M月D日') + ' — ' + dayjs(result.value.end).format('M月D日')
)
const visibleParticipants = (t: HistoryTableSummary) =>
  t.employees.filter(p => p.counts.operations > 0 && (!employee.value || p.id === employee.value))

function closeDetail() {
  ++detailRequest
  detailId.value = undefined
  detailData.value = undefined
  detailLoading.value = false
  detailError.value = ''
}
function closeTable() {
  ++pageRequest
  closeDetail()
  table.value = undefined
  pageData.value = undefined
  pageLoading.value = false
}
async function backToSummary() {
  closeTable()
  await nextTick()
  // 表格重新挂载后恢复原入口的焦点，长列表和键盘操作不必从头寻找。
  const trigger = Array.from(summaryAnchor.value?.querySelectorAll<HTMLElement>('[aria-label]') || []).find(
    element => element.getAttribute('aria-label') === returnControl
  )
  const target = trigger || summaryAnchor.value
  target?.focus({ preventScroll: true })
  target?.scrollIntoView({ block: 'nearest' })
}
async function openTable(next: HistoryTableSummary, person = employee.value) {
  returnControl = document.activeElement?.getAttribute('aria-label') || ''
  closeDetail()
  pageData.value = undefined
  drillEmployee.value = person
  sheet.value = 'changes'
  page.value = 1
  table.value = next
  await nextTick()
  tableAnchor.value?.focus({ preventScroll: true })
  tableAnchor.value?.scrollIntoView({ block: 'nearest' })
}
function context() {
  if (!result.value || !table.value) throw new Error('请重新查询')
  return {
    query: { start: result.value.start, end: result.value.end, employeeId: drillEmployee.value },
    visibility: result.value.visibility,
    objectId: table.value.objectId
  }
}
async function loadPage() {
  const id = ++pageRequest
  pageData.value = undefined
  pageError.value = ''
  if (!table.value || !result.value) {
    pageLoading.value = false
    return
  }
  pageLoading.value = true
  try {
    const data = await platform.runtime.historyPage({
      ...context(),
      changesOnly: sheet.value === 'changes',
      pageNo: page.value,
      pageSize: pageSize.value
    })
    if (id === pageRequest) pageData.value = data
  } catch (e) {
    if (id === pageRequest) pageError.value = errorMessage(e)
  } finally {
    if (id === pageRequest) pageLoading.value = false
  }
}
async function openDetail(row: Pick<HistoryRow, 'id'>) {
  const id = ++detailRequest
  detailId.value = row.id
  detailData.value = undefined
  detailError.value = ''
  detailLoading.value = true
  try {
    const data = await platform.runtime.historyDetail({ ...context(), recordId: row.id })
    if (id === detailRequest) detailData.value = data
  } catch (e) {
    if (id === detailRequest) detailError.value = errorMessage(e)
  } finally {
    if (id === detailRequest) detailLoading.value = false
  }
}
async function load() {
  const id = ++request
  closeTable()
  error.value = ''
  loading.value = true
  result.value = undefined
  summaryPage.value = 1
  try {
    if (!range.value?.[0] || !range.value?.[1] || range.value[0].isAfter(range.value[1]))
      throw new Error('请选择有效的检索起止时间')
    const data = await platform.runtime.history({
      start: range.value[0].toISOString(),
      end: throughNow.value ? undefined : range.value[1].toISOString()
    })
    if (id === request) {
      result.value = data
      if (throughNow.value) range.value = [range.value[0], dayjs(data.end)]
    }
  } catch (e) {
    if (id === request) error.value = errorMessage(e)
  } finally {
    if (id === request) loading.value = false
  }
}
function selectDay(value: Dayjs | null) {
  if (!value || value.startOf('day').isAfter(dayjs().startOf('day'))) return
  throughNow.value = value.isSame(dayjs(), 'day')
  range.value = [value.startOf('day'), throughNow.value ? dayjs() : value.endOf('day')]
  custom.value = false
  load()
}
function recentDays() {
  throughNow.value = true
  range.value = [dayjs().subtract(6, 'day').startOf('day'), dayjs()]
  custom.value = true
  load()
}
function clearFilters() {
  business.value = undefined
  employee.value = undefined
  keyword.value = ''
  changedOnly.value = true
  deletedOnly.value = false
}
function changePage(p: { current: number; pageSize: number }) {
  if (pageSize.value !== p.pageSize) {
    pageSize.value = p.pageSize
    page.value = 1
  } else page.value = p.current
}
watch([() => table.value?.objectId, sheet, page, pageSize, drillEmployee], () => {
  closeDetail()
  loadPage()
})
watch(sheet, () => {
  page.value = 1
})
watch([business, employee, keyword, changedOnly, deletedOnly], () => {
  closeTable()
  summaryPage.value = 1
})
watch(mode, () => {
  closeTable()
  summaryPage.value = 1
})
onMounted(load)
</script>
<template>
  <section class="record-history">
    <div class="history-filter">
      <div class="history-date-toolbar">
        <div class="history-day-picker">
          <a-button
            aria-label="前一天"
            :disabled="!singleDay || loading"
            @click="selectDay(range[0].subtract(1, 'day'))"
          >
            <LeftOutlined />
          </a-button>
          <a-date-picker
            :value="singleDay ? range[0] : null"
            :allow-clear="false"
            placeholder="选择一天"
            format="YYYY-MM-DD"
            aria-label="查看日期"
            :disabled-date="(d: Dayjs) => d.startOf('day').isAfter(dayjs().startOf('day'))"
            @change="(value: Dayjs | null) => selectDay(value)"
          />
          <a-button aria-label="后一天" :disabled="nextDisabled || loading" @click="selectDay(range[0].add(1, 'day'))">
            <RightOutlined />
          </a-button>
        </div>
        <a-space wrap>
          <a-button
            :type="singleDay && range[0].isSame(dayjs(), 'day') ? 'primary' : 'default'"
            @click="selectDay(dayjs())"
          >
            今日
          </a-button>
          <a-button @click="selectDay(dayjs().subtract(1, 'day'))">昨日</a-button>
          <a-button @click="recentDays">近7天</a-button>
          <a-button :type="custom ? 'primary' : 'text'" @click="custom = !custom">自定义时间</a-button>
        </a-space>
        <a-button class="history-refresh" :loading="loading" @click="load">
          <ReloadOutlined />
          刷新
        </a-button>
      </div>
      <div v-if="custom" class="history-custom-range">
        <span>起止时间</span>
        <a-range-picker
          v-model:value="range"
          show-time
          format="YYYY-MM-DD HH:mm"
          :allow-clear="false"
          @change="throughNow = false"
        />
        <a-button type="primary" :loading="loading" @click="load">
          <SearchOutlined />
          查询
        </a-button>
      </div>
      <div class="history-view-toolbar">
        <a-radio-group v-model:value="mode" button-style="solid" aria-label="查看视角">
          <a-radio-button value="business">业务动态</a-radio-button>
          <a-radio-button value="employee">人员动态</a-radio-button>
        </a-radio-group>
        <a-select
          v-model:value="business"
          aria-label="业务筛选"
          placeholder="全部业务"
          :options="apps"
          allow-clear
          show-search
          option-filter-prop="label"
          class="history-select"
        />
        <a-select
          v-model:value="employee"
          aria-label="人员筛选"
          placeholder="全部参与人员"
          :options="employees"
          allow-clear
          show-search
          option-filter-prop="label"
          class="history-select"
        />
        <a-input
          v-model:value="keyword"
          aria-label="搜索表格或业务"
          placeholder="搜索表格或业务"
          allow-clear
          class="history-search"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
        <a-button
          v-if="business || employee || keyword || deletedOnly || !changedOnly"
          type="link"
          @click="clearFilters"
        >
          清除筛选
        </a-button>
      </div>
    </div>

    <a-alert v-if="error" type="error" :message="error" show-icon>
      <template #action><a-button @click="load">重试</a-button></template>
    </a-alert>
    <a-spin :spinning="loading">
      <template v-if="result">
        <div class="history-period">
          <strong>{{ rangeLabel }}</strong>
          <span>{{ time(result.start) }} 至 {{ time(result.end) }}</span>
          <a-popover title="统计口径">
            <template #content>
              <div class="history-explanation">
                <p>仅统计当前有权查看且已留存的变化。变化次数与涉及记录分别计数，同一共享表总体只计一次。</p>
                <p>
                  所属业务表示哪些应用使用这张表，不表示操作来自哪个应用。多人共同修改同一记录时，每人都可查看，总体只计一条记录。
                </p>
                <p>操作次数用于了解工作过程，不直接代表工作量或绩效；没有留存变化也不代表没有工作。</p>
              </div>
            </template>
            <a-button size="small" type="link">统计口径</a-button>
          </a-popover>
        </div>
        <div class="history-metrics">
          <div class="history-metric">
            <span>有变化的表格</span>
            <strong>
              {{ activeTables.length }}
              <small>张</small>
            </strong>
            <span>{{ counts.employees }} 位人员参与</span>
          </div>
          <div class="history-metric">
            <span>涉及记录</span>
            <strong>
              {{ counts.records }}
              <small>条</small>
            </strong>
            <span>同一表内记录去重</span>
          </div>
          <div class="history-metric">
            <span>新增次数</span>
            <strong class="history-create">{{ counts.create }}</strong>
            <span>保存成功的新增</span>
          </div>
          <div class="history-metric">
            <span>修改次数</span>
            <strong>{{ counts.update }}</strong>
            <span>含多次修改同一记录</span>
          </div>
          <button
            class="history-metric history-delete-filter"
            :class="{ active: deletedOnly }"
            :aria-pressed="deletedOnly"
            @click="deletedOnly = !deletedOnly"
          >
            <span>删除次数</span>
            <strong class="history-delete">{{ counts.delete }}</strong>
            <span>{{ deletedOnly ? '正在看有删除的表格 · 取消' : '查看有删除的表格' }}</span>
          </button>
        </div>

        <div v-if="!table" ref="summaryAnchor" tabindex="-1" class="history-summary">
          <div class="history-section-heading">
            <div>
              <h3>{{ mode === 'business' ? '哪些业务数据发生了变化' : '每个人参与了哪些业务' }}</h3>
              <p>
                {{
                  mode === 'business'
                    ? '先看表格，再查看记录的最终变化和修改过程。'
                    : '按姓名展示参与人员；点击表格查看该人员涉及的记录。'
                }}
              </p>
            </div>
            <a-checkbox v-model:checked="changedOnly">仅看有变化的表格</a-checkbox>
          </div>
          <a-alert
            v-if="deletedOnly"
            type="info"
            show-icon
            message="当前只列出发生过删除的表格；进入后仍可查看该表全部变化。"
            closable
            @close="deletedOnly = false"
          />
          <a-alert
            v-if="selectedTables.some(t => !t.complete)"
            type="info"
            show-icon
            message="部分表格的历史未覆盖整个时间范围，统计仅包含已留存的变化。"
          />
          <OsTablePage
            v-if="summaryRows.length"
            :key="mode + String(compact)"
            :show-index="false"
            show-column-settings
            resizable
            :column-settings-key="'history-overview-' + mode + (compact ? '-compact' : '')"
            :columns="summaryColumns"
            :data-source="summaryRows"
            :title="mode === 'business' ? '业务动态' : '人员动态'"
            :show-advanced-search="false"
            :scroll="compact ? undefined : { x: 925 }"
            :pagination="{
              current: summaryPage,
              pageSize: 10,
              showSizeChanger: false,
              showTotal: (total: number) => '共 ' + total + (mode === 'business' ? ' 张表格' : ' 位人员')
            }"
            @change="p => (summaryPage = p.current)"
          >
            <template #bodyCell="{ column, record }">
              <div v-if="column.key === 'name'" class="history-name">
                <strong>{{ record.name }}</strong>
                <template v-if="mode === 'business'">
                  <span>{{ record.table.applicationNames.join(' / ') }}</span>
                  <a-tag v-if="record.table.applicationIds.length > 1">共享表 · 计一次</a-tag>
                  <a-tag v-if="!record.table.complete" color="orange">历史不完整</a-tag>
                </template>
                <span v-else>参与 {{ record.tables.length }} 张表格</span>
                <div v-if="compact" class="history-mobile-summary">
                  <strong>{{ record.counts.records }} 条记录有变化</strong>
                  <span>{{ changeDescription(record.counts) }}</span>
                  <a-space wrap v-if="mode === 'business'">
                    <a-button
                      v-for="person in visibleParticipants(record.table)"
                      :key="person.id"
                      type="link"
                      :aria-label="person.name + '在' + record.name + '的变化'"
                      @click="openTable(record.table, person.id)"
                    >
                      {{ person.name }} · {{ person.counts.records }} 条
                    </a-button>
                  </a-space>
                  <a-space wrap v-else>
                    <a-button
                      v-for="t in record.tables"
                      :key="t.objectId"
                      type="link"
                      :aria-label="record.name + ' · ' + t.name + ' · 查看变化'"
                      @click="openTable(t, record.id)"
                    >
                      {{ t.name }} · {{ summaryCounts([t], record.id).records }} 条
                    </a-button>
                  </a-space>
                </div>
              </div>
              <div v-else-if="column.key === 'changes'" class="history-change-summary">
                <strong>{{ record.counts.records }} 条记录有变化</strong>
                <span>{{ changeDescription(record.counts) }}</span>
              </div>
              <a-space v-else-if="column.key === 'involved'" wrap>
                <template v-if="mode === 'business'">
                  <a-button
                    v-for="person in visibleParticipants(record.table)"
                    :key="person.id"
                    type="link"
                    class="history-person"
                    :aria-label="person.name + '在' + record.name + '的变化'"
                    @click="openTable(record.table, person.id)"
                  >
                    {{ person.name }} · {{ person.counts.records }} 条
                  </a-button>
                  <span v-if="!visibleParticipants(record.table).length" class="history-muted">暂无留存变更</span>
                </template>
                <a-button
                  v-for="t in mode === 'employee' ? record.tables : []"
                  :key="t.objectId"
                  type="link"
                  :aria-label="record.name + ' · ' + t.name + ' · 查看变化'"
                  @click="openTable(t, record.id)"
                >
                  {{ t.name }} · {{ summaryCounts([t], record.id).records }} 条
                </a-button>
              </a-space>
              <a-button
                v-else-if="column.key === 'actions' && mode === 'business'"
                type="link"
                :aria-label="record.name + ' · 查看变化'"
                @click="openTable(record.table)"
              >
                查看变化
              </a-button>
              <span v-else-if="column.key === 'actions'" class="history-muted">选择左侧表格</span>
            </template>
          </OsTablePage>
          <a-empty
            v-else
            :description="selectedTables.length ? '当前条件下没有留存变化' : '当前条件下没有可查看的表格'"
          >
            <a-button v-if="business || employee || keyword || deletedOnly" @click="clearFilters">清除筛选</a-button>
            <a-button v-else @click="selectDay(range[0].subtract(1, 'day'))">看看前一天</a-button>
          </a-empty>
          <p class="history-help">先看变化内容，再结合业务结果评估工作。无变化仅表示此范围内没有可见的留存事件。</p>
        </div>
        <div v-else ref="tableAnchor" tabindex="-1" class="history-table-section">
          <div class="history-table-heading">
            <a-button @click="backToSummary">
              <ArrowLeftOutlined />
              返回{{ mode === 'business' ? '业务动态' : '人员动态' }}
            </a-button>
            <div>
              <h3>{{ table.name }}</h3>
              <p>
                {{ drillEmployee ? personName + '涉及' : '共涉及' }} {{ tableCounts.records }} 条记录 ·
                {{ changeDescription(tableCounts) }}
              </p>
            </div>
          </div>
          <a-alert
            v-if="drillEmployee"
            type="info"
            show-icon
            :message="'正在查看' + personName + '涉及的记录。记录详情保留所有人的修改过程，全表保留其他记录。'"
          >
            <template #action>
              <a-button size="small" @click="drillEmployee = undefined">查看此表所有人员</a-button>
            </template>
          </a-alert>
          <a-alert
            v-if="!table.complete"
            type="info"
            show-icon
            :message="'本表从 ' + time(table.coveredFrom) + ' 开始留存，此前变化不可还原。'"
          />
          <a-tabs v-model:active-key="sheet">
            <a-tab-pane key="changes" :tab="'变化记录 ' + tableCounts.records" />
            <a-tab-pane
              key="all"
              :disabled="!endCovered"
              :tab="endCovered ? '截至当时的全表' : '全表（该时点未留存）'"
            />
          </a-tabs>
          <p class="history-help">
            {{ sheet === 'all' ? '展示检索结束时仍存在的记录，包含未变更记录。' : '包含新增、修改及删除的记录。' }}
            高亮表示期间修改过，最终恢复原值也会保留过程。
          </p>
          <a-alert v-if="pageError" type="error" :message="pageError" show-icon>
            <template #action><a-button @click="loadPage">重试</a-button></template>
          </a-alert>
          <OsTablePage
            :key="table.objectId + sheet + (pageData?.table.fields || []).map(f => f.id).join(':')"
            :show-index="false"
            show-column-settings
            resizable
            :column-settings-key="'history-records-' + table.objectId"
            :columns="rowColumns"
            :data-source="rows"
            :loading="pageLoading"
            :title="table.name"
            :pagination="{
              current: page,
              pageSize,
              total: pageData?.total || 0,
              showSizeChanger: true,
              pageSizeOptions: ['10', '20', '50', '100'],
              showTotal: (total: number) => '共 ' + total + ' 条'
            }"
            :show-advanced-search="false"
            :scroll="{ x: 'max-content' }"
            @change="changePage"
          >
            <template #bodyCell="{ column, record }">
              <a-button
                v-if="column.key === '_actions'"
                type="link"
                :aria-label="'记录 ' + record.id + ' · 查看变化详情'"
                @click="openDetail(record)"
              >
                变化详情
              </a-button>
              <a-space v-else-if="column.key === '_state'" wrap>
                <a-tag v-if="record.deleted" color="red">{{ record.createdInRange ? '新增后删除' : '已删除' }}</a-tag>
                <a-tag v-else-if="record.createdInRange" color="green">新增</a-tag>
                <a-tag v-else-if="record.restored" color="orange">改后恢复原值</a-tag>
                <a-tag v-else-if="record.changeCount" color="blue">修改</a-tag>
                <span v-else>未变更</span>
                <span v-if="record.changeCount">{{ record.changeCount }} 次</span>
              </a-space>
              <span v-else-if="column.key === '_recordId'">{{ record.id }}</span>
              <span v-else :class="{ 'history-changed': record.changedFields.includes(String(column.key)) }">
                {{ historyValue(record.values[column.key]) }}
              </span>
            </template>
          </OsTablePage>
        </div>
      </template>
    </a-spin>
    <HistoryRecordDrawer
      :open="!!detailId"
      :data="detailData"
      :loading="detailLoading"
      :error="detailError"
      :table-name="table?.name || ''"
      :record-id="detailId || ''"
      :start="result?.start"
      :end="result?.end"
      :employee-id="drillEmployee"
      @close="closeDetail"
      @retry="detailId && openDetail({ id: detailId })"
    />
  </section>
</template>
<style scoped src="./record-history.css"></style>
