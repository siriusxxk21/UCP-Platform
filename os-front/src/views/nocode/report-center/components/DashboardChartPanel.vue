<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
import type {
  DashboardChart,
  DashboardQuery,
  DashboardDetailPage,
  DashboardSelection
} from '@/types/nocode/report-dashboard'
import type { ReportResult } from '@/types/nocode/report'
import DashboardChartView from './DashboardChartView.vue'
const props = defineProps<{
  chart: DashboardChart
  result: ReportResult
  query: DashboardQuery
  transport?: DashboardRuntimeTransport
  canLink?: boolean
  canDrill?: boolean
}>()
const emit = defineEmits<{
  link: [value: DashboardSelection]
  drill: [value: DashboardSelection]
  fault: [cause: unknown]
}>()
const action = defineModel<'DETAIL' | 'LINK' | 'DRILL' | 'BUSINESS'>('action', { default: 'DETAIL' })
function select(value: DashboardSelection) {
  if (action.value === 'LINK' && props.canLink) emit('link', value)
  else if (action.value === 'DRILL' && props.canDrill) emit('drill', value)
  else if (action.value === 'BUSINESS' && canBusiness.value) business(value)
  else show(value)
}
watch(
  () => [props.canLink, props.canDrill],
  () => {
    if ((action.value === 'LINK' && !props.canLink) || (action.value === 'DRILL' && !props.canDrill))
      action.value = 'DETAIL'
  }
)
const canBusiness = computed(() => !!props.transport?.canBusinessDetails?.(props.chart.id))
function business(value: DashboardSelection = { group: [], columnGroup: [] }) {
  try {
    props.transport?.businessDetails?.({ query: props.query, ...value, pageNo: 1, pageSize: 20 })
  } catch (e) {
    emit('fault', e)
    error.value = errorMessage(e)
  }
}
const api = useNocodePlatform().reportCenter
const open = ref(false),
  loading = ref(false),
  exporting = ref(false),
  error = ref(''),
  exportError = ref(''),
  exportNotice = ref('')
const details = ref<DashboardDetailPage>(),
  pageNo = ref(1),
  pageSize = ref(20)
const selection = ref<DashboardSelection>({ group: [], columnGroup: [] })
const columns = computed(() => [
  { title: '记录编号', key: 'id', dataIndex: 'id', width: 120, align: 'left' as const },
  ...(details.value?.columns || []).map((c, i) => ({
    title: c.name,
    key: 'detail:' + c.id,
    dataIndex: ['labels', i],
    width: 180,
    align: 'left' as const
  }))
])
let generation = 0
let exportGeneration = 0,
  exportController: AbortController | undefined,
  detailController: AbortController | undefined
async function load() {
  const g = ++generation
  detailController?.abort()
  detailController = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const data = await (props.transport?.details || api.dashboardDetails)(
      {
        query: props.query,
        ...selection.value,
        pageNo: pageNo.value,
        pageSize: pageSize.value
      },
      detailController.signal
    )
    if (g === generation) details.value = data
  } catch (e) {
    if (g === generation) {
      emit('fault', e)
      error.value = errorMessage(e)
      details.value = undefined
    }
  } finally {
    if (g === generation) loading.value = false
  }
}
function show(value: DashboardSelection = { group: [], columnGroup: [] }) {
  details.value = undefined
  selection.value = value
  pageNo.value = 1
  open.value = true
  void load()
}
function change(page: { current?: number; pageSize?: number }) {
  pageNo.value = page.current || 1
  pageSize.value = page.pageSize || 20
  void load()
}
async function download() {
  if (exporting.value || !props.result.canExport) return
  const g = ++exportGeneration
  exportController?.abort()
  exportController = new AbortController()
  exporting.value = true
  exportError.value = ''
  exportNotice.value = ''
  try {
    const identity = JSON.stringify(props.query)
    const blob = await (props.transport?.export || api.dashboardExport)(props.query, exportController.signal)
    if (g !== exportGeneration || identity !== JSON.stringify(props.query)) return
    if (!blob.type.includes('spreadsheet') && !blob.type.includes('octet-stream')) {
      const body = JSON.parse(await blob.text())
      throw Object.assign(new Error(body.msg || body.message || '导出失败'), { businessCode: body.code })
    }
    const url = URL.createObjectURL(blob),
      link = document.createElement('a')
    link.href = url
    link.download = props.chart.title + '.xlsx'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    exportNotice.value = '文件已生成并发起下载，请在浏览器下载中查看。'
  } catch (e) {
    if (g === exportGeneration) {
      emit('fault', e)
      exportError.value = errorMessage(e)
    }
  } finally {
    if (g === exportGeneration) exporting.value = false
  }
}
function cancelExport() {
  // 请求中止不保证服务端停止计算；序号隔离确保迟到文件也不会触发下载。
  exportGeneration++
  exportController?.abort()
  exporting.value = false
  exportError.value = ''
  exportNotice.value = '已取消本次下载。'
}
watch(open, value => {
  if (!value) {
    generation++
    detailController?.abort()
    details.value = undefined
    loading.value = false
  }
})
watch(
  () => JSON.stringify(props.query),
  () => {
    generation++
    exportGeneration++
    detailController?.abort()
    exportController?.abort()
    exporting.value = false
    exportError.value = ''
    exportNotice.value = ''
    open.value = false
    details.value = undefined
  },
  { deep: true }
)
onScopeDispose(() => {
  generation++
  exportGeneration++
  detailController?.abort()
  exportController?.abort()
})
</script>
<template>
  <a-space class="dashboard-chart-actions">
    <a-button size="small" @click="show()">查看明细</a-button>
    <a-button v-if="canBusiness" size="small" @click="business()">业务明细</a-button>
    <a-tooltip
      :title="
        result.canExport ? '按导出权限重新计算当前图表；文件保留精确原值、总计及截断说明。' : '当前没有此图表的导出权限'
      "
    >
      <a-button size="small" :loading="exporting" :disabled="!result.canExport" @click="download">导出 Excel</a-button>
    </a-tooltip>
    <a-button v-if="exporting" size="small" @click="cancelExport">取消导出</a-button>
  </a-space>
  <a-alert v-if="exporting" type="info" message="正在生成 Excel，当前筛选和下钻条件会用于本次导出。" show-icon />
  <a-alert v-if="exportNotice" type="info" :message="exportNotice" show-icon closable />
  <a-alert v-if="exportError" type="error" :message="exportError" show-icon>
    <template #action>
      <a-button size="small" :disabled="!result.canExport" @click="download">重试导出</a-button>
    </template>
  </a-alert>
  <a-radio-group
    v-if="canLink || canDrill || canBusiness"
    v-model:value="action"
    size="small"
    aria-label="图表点击动作"
    class="dashboard-chart-actions"
  >
    <a-radio-button value="DETAIL">查看明细</a-radio-button>
    <a-radio-button v-if="canBusiness" value="BUSINESS">进入业务明细</a-radio-button>
    <a-radio-button v-if="canLink" value="LINK">点击联动</a-radio-button>
    <a-radio-button v-if="canDrill" value="DRILL">层级下钻</a-radio-button>
  </a-radio-group>
  <DashboardChartView :chart="chart" :result="result" @select="select" />
  <OsModalForm
    :open="open"
    :title="chart.title + ' · 只读明细'"
    width="85%"
    display-mode="drawer"
    :allow-switch-display="false"
    :wrap-form="false"
    :show-footer="false"
    maximizable
    @cancel="open = false"
  >
    <template #formItems>
      <p>仅显示参与此图表的字段。点击数值时按该格原始键与指标条件查看记录；不提供编辑操作。</p>
      <a-alert v-if="error" type="error" :message="error" show-icon>
        <template #action>
          <a-button size="small" :loading="loading" @click="load">重试明细</a-button>
        </template>
      </a-alert>
      <a-skeleton v-if="loading && !details" active />
      <OsTablePage
        v-if="details"
        :columns="columns"
        :data-source="details?.list || []"
        :loading="loading"
        row-key="id"
        resizable
        show-column-settings
        :scroll="{ x: 'max-content' }"
        :pagination="{
          current: pageNo,
          pageSize,
          total: details?.total || 0,
          showSizeChanger: true,
          showTotal: (n: number) => '共 ' + n + ' 条'
        }"
        @change="change"
      />
    </template>
  </OsModalForm>
</template>
<style scoped>
.dashboard-chart-actions {
  margin-bottom: var(--spacing-sm);
}
</style>
