<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { errorMessage } from '@/nocode/data-center'
import {
  dashboardDefaultFilterValues,
  dashboardDrilledChart,
  dashboardVersionChanged,
  toggleDashboardSelection
} from '@/nocode/report-dashboard'
import type {
  DashboardChart,
  DashboardFilterValue,
  DashboardLinkSelection,
  DashboardQuery,
  DashboardRelease,
  DashboardSelection
} from '@/types/nocode/report-dashboard'
import type { ReportResult } from '@/types/nocode/report'
import DashboardChartPanel from './DashboardChartPanel.vue'
import DashboardFilterControl from './DashboardFilterControl.vue'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
const props = withDefaults(
  defineProps<{
    transport: DashboardRuntimeTransport
    contextKey: string
    paused?: boolean
    title?: string
    versionLabel?: string
    versionChangedMessage?: string
  }>(),
  { paused: false, versionChangedMessage: '仪表板已发布新版本，请点击“刷新数据”加载完整的新版本。' }
)
const boundFilterIds = ref<string[]>([])
const visibleFilters = computed(() =>
  (board.value?.content.filters || []).filter(filter => !boundFilterIds.value.includes(filter.id))
)
const board = ref<DashboardRelease>(),
  results = ref<Record<string, ReportResult>>({}),
  errors = ref<Record<string, string>>({}),
  loading = ref(false),
  error = ref('')
const filterValues = ref<DashboardFilterValue[]>([]),
  selections = ref<DashboardLinkSelection[]>([])
const paths = ref<Record<string, (string | null)[]>>({}),
  pathLabels = ref<Record<string, string[]>>({}),
  selectionLabels = ref<Record<string, string>>({})
const pending = ref<Record<string, boolean>>({}),
  queries = ref<Record<string, DashboardQuery>>({})
const actions = ref<Record<string, 'DETAIL' | 'LINK' | 'DRILL' | 'BUSINESS'>>({})
const favorite = ref(false),
  preferenceReady = ref(false),
  favoriteBusy = ref(false),
  preferenceError = ref('')
const hasErrors = computed(() => Object.keys(errors.value).length > 0)
let generation = 0,
  batch = 0
const controllers = new Map<string, AbortController>()
function cancelQueries() {
  batch++
  controllers.forEach(c => c.abort())
  controllers.clear()
  pending.value = {}
}
function runtimeFault(cause: unknown) {
  if (!dashboardVersionChanged(cause)) return false
  cancelQueries()
  results.value = {}
  errors.value = {}
  queries.value = {}
  board.value = undefined
  preferenceReady.value = false
  error.value = props.versionChangedMessage
  return true
}
defineExpose({ runtimeFault })
function queryFor(chartId: string): DashboardQuery {
  return {
    id: board.value!.id,
    chartId,
    preview: false,
    versionNo: board.value!.versionNo,
    checksum: board.value!.checksum,
    filterValues: JSON.parse(JSON.stringify(filterValues.value)),
    selections: JSON.parse(JSON.stringify(selections.value)),
    drillPath: [...(paths.value[chartId] || [])]
  }
}
async function queryChart(chart: DashboardChart, g: number) {
  const request = queryFor(chart.id),
    controller = new AbortController()
  controllers.get(chart.id)?.abort()
  controllers.set(chart.id, controller)
  queries.value[chart.id] = request
  pending.value[chart.id] = true
  delete errors.value[chart.id]
  delete results.value[chart.id]
  try {
    const data = await props.transport.query(request, controller.signal)
    if (g === batch && controllers.get(chart.id) === controller) results.value[chart.id] = data
  } catch (e) {
    if (g === batch && controllers.get(chart.id) === controller && !runtimeFault(e))
      errors.value[chart.id] = errorMessage(e)
  } finally {
    if (g === batch && controllers.get(chart.id) === controller) {
      pending.value[chart.id] = false
      controllers.delete(chart.id)
    }
  }
}
async function refresh() {
  cancelQueries()
  results.value = {}
  errors.value = {}
  queries.value = {}
  if (!board.value || props.paused) return
  const g = batch
  await Promise.all(board.value.content.charts.map(chart => queryChart(chart, g)))
}
async function load() {
  const g = ++generation
  loading.value = true
  error.value = ''
  cancelQueries()
  results.value = {}
  errors.value = {}
  preferenceReady.value = false
  preferenceError.value = ''
  try {
    const model = await props.transport.load()
    const current = model.dashboard
    if (g !== generation) return
    if (
      board.value?.id !== current.id ||
      board.value?.versionNo !== current.versionNo ||
      board.value?.checksum !== current.checksum
    ) {
      filterValues.value = dashboardDefaultFilterValues(current.content, model.boundFilterIds || [])
      selections.value = []
      paths.value = {}
      pathLabels.value = {}
      selectionLabels.value = {}
      actions.value = {}
    }
    board.value = current
    boundFilterIds.value = model.boundFilterIds || []
    if (props.transport.preference) void loadPreference(current.id, g)
    await refresh()
    // 最近访问只记录成功打开或手动刷新，不把筛选和下钻计作新的访问。
    if (props.transport.visit && g === generation && Object.keys(results.value).length) {
      try {
        await props.transport.visit!(current.id)
      } catch (e) {
        if (g === generation) preferenceError.value = errorMessage(e)
      }
    }
  } catch (e) {
    if (g === generation) {
      error.value = errorMessage(e)
      board.value = undefined
    }
  } finally {
    if (g === generation) loading.value = false
  }
}
async function loadPreference(id: string, g: number) {
  try {
    const state = await props.transport.preference!(id)
    if (g === generation) {
      favorite.value = state.favorite
      preferenceReady.value = true
    }
  } catch (e) {
    if (g === generation) preferenceError.value = errorMessage(e)
  }
}
async function toggleFavorite() {
  if (!props.transport.favorite || !board.value || !preferenceReady.value || favoriteBusy.value) return
  const g = generation,
    id = board.value.id
  favoriteBusy.value = true
  preferenceError.value = ''
  try {
    const state = await props.transport.favorite!({ id, favorite: !favorite.value })
    if (g === generation) favorite.value = state.favorite
  } catch (e) {
    if (g === generation) preferenceError.value = errorMessage(e)
  } finally {
    favoriteBusy.value = false
  }
}
function setFilter(value: DashboardFilterValue) {
  filterValues.value = [...filterValues.value.filter(v => v.filterId !== value.filterId), value]
  void refresh()
}
function resetFilters() {
  filterValues.value = board.value ? dashboardDefaultFilterValues(board.value.content, boundFilterIds.value) : []
  void refresh()
}
function clearLinks(id?: string) {
  selections.value = id ? selections.value.filter(s => s.chartId !== id) : []
  void refresh()
}
function linked(chart: DashboardChart, value: DashboardSelection) {
  if (paths.value[chart.id]?.length || value.group.length !== chart.dimensions.length) return
  const label = results.value[chart.id]?.groups
    .find(g => JSON.stringify(g.keys) === JSON.stringify(value.group))
    ?.labels.join(' / ')
  selectionLabels.value[chart.id] = label || value.group.map(v => (v === null ? '空值' : v)).join(' / ')
  selections.value = toggleDashboardSelection(selections.value, chart.id, value.group)
  void refresh()
}
function drill(chart: DashboardChart, value: DashboardSelection) {
  const path = paths.value[chart.id] || []
  if (path.length >= (chart.drillDimensions?.length || 0) || value.group.length !== 1) return
  const label = results.value[chart.id]?.groups.find(g => JSON.stringify(g.keys) === JSON.stringify(value.group))
    ?.labels[0]
  paths.value[chart.id] = [...path, value.group[0]!]
  pathLabels.value[chart.id] = [
    ...(pathLabels.value[chart.id] || []),
    label || (value.group[0] === null ? '空值' : value.group[0]!)
  ]
  // 下钻后的键不再属于联动来源的基础维度，撤销该来源保留其他来源和公共筛选。
  selections.value = selections.value.filter(s => s.chartId !== chart.id)
  void refresh()
}
function rollup(chartId: string, depth: number) {
  paths.value[chartId] = (paths.value[chartId] || []).slice(0, depth)
  pathLabels.value[chartId] = (pathLabels.value[chartId] || []).slice(0, depth)
  void refresh()
}
watch(
  () => props.contextKey,
  () => {
    board.value = undefined
    void load()
  },
  { immediate: true }
)
watch(
  () => props.paused,
  () => {
    void refresh()
  }
)
onScopeDispose(() => {
  generation++
  cancelQueries()
})
</script>
<template>
  <section class="dashboard-page">
    <header class="dashboard-toolbar">
      <a-space>
        <slot name="leading-actions" />
        <h2>{{ title || board?.content.name || '仪表板' }}</h2>
        <a-tag v-if="board">{{ versionLabel || 'V' + board.versionNo }}</a-tag>
      </a-space>
      <a-space>
        <slot name="trailing-actions" />
        <a-button v-if="transport.favorite && preferenceReady && board" :loading="favoriteBusy" @click="toggleFavorite">
          {{ favorite ? '取消收藏' : '收藏' }}
        </a-button>
        <a-button :loading="loading" @click="load">刷新数据</a-button>
      </a-space>
    </header>
    <p v-if="board?.content.description">{{ board.content.description }}</p>
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-alert v-if="preferenceError" :message="'个人偏好保存或读取失败：' + preferenceError" type="warning" show-icon />
    <a-alert v-if="hasErrors" message="部分图表加载失败，其余图表已显示；可单独重试。" type="warning" show-icon />
    <a-skeleton v-if="loading && !board" active />
    <template v-if="board">
      <slot name="inputs" :dashboard="board" />
      <section v-if="!paused && visibleFilters.length" class="dashboard-filter-bar" aria-label="公共筛选">
        <header class="dashboard-tile-header">
          <h3>公共筛选</h3>
          <a-button @click="resetFilters">重置筛选</a-button>
        </header>
        <div class="dashboard-filter-fields">
          <DashboardFilterControl
            v-for="filter in visibleFilters"
            :key="board.id + ':' + filter.id"
            :filter="filter"
            :transport="transport"
            :value="filterValues.find(v => v.filterId === filter.id)"
            :query="queryFor(filter.mappings[0]!.chartId)"
            @change="setFilter"
            @fault="runtimeFault"
          />
        </div>
      </section>
      <a-space v-if="selections.length" class="dashboard-link-tags" wrap aria-label="生效联动">
        <span>联动条件</span>
        <a-tag
          v-for="selection in selections"
          :key="selection.chartId"
          closable
          @close.prevent="clearLinks(selection.chartId)"
        >
          {{ board.content.charts.find(c => c.id === selection.chartId)?.title }} =
          {{ selectionLabels[selection.chartId] }}
        </a-tag>
        <a-button size="small" @click="clearLinks()">清除联动</a-button>
      </a-space>
      <div v-if="!paused" class="dashboard-grid">
        <article
          v-for="chart in board.content.charts"
          :key="chart.id"
          class="dashboard-tile"
          :style="{ gridColumn: `${chart.x + 1} / span ${chart.w}`, gridRow: `${chart.y + 1} / span ${chart.h}` }"
        >
          <h3>{{ chart.title }}</h3>
          <a-space v-if="paths[chart.id]?.length" wrap class="dashboard-drill-path" aria-label="钻取路径">
            <a-button type="link" size="small" @click="rollup(chart.id, 0)">全部</a-button>
            <template v-for="(label, index) in pathLabels[chart.id]" :key="index">
              <span>/</span>
              <a-button type="link" size="small" @click="rollup(chart.id, index + 1)">{{ label }}</a-button>
            </template>
            <a-button size="small" @click="rollup(chart.id, paths[chart.id]!.length - 1)">返回上层</a-button>
          </a-space>
          <template v-if="errors[chart.id]">
            <a-alert :message="errors[chart.id]" type="error" show-icon />
            <a-button @click="queryChart(chart, batch)">重试图表</a-button>
          </template>
          <a-skeleton v-else-if="pending[chart.id]" active />
          <DashboardChartPanel
            v-else-if="results[chart.id] && queries[chart.id]"
            :chart="dashboardDrilledChart(chart, paths[chart.id]?.length || 0)"
            :transport="transport"
            v-model:action="actions[chart.id]"
            :result="results[chart.id]!"
            :query="queries[chart.id]!"
            :can-link="!!chart.links?.length && !paths[chart.id]?.length"
            :can-drill="(paths[chart.id]?.length || 0) < (chart.drillDimensions?.length || 0)"
            @fault="runtimeFault"
            @link="linked(chart, $event)"
            @drill="drill(chart, $event)"
          />
        </article>
      </div>
    </template>
  </section>
</template>
<style src="../dashboard.css"></style>
