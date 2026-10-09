<script setup lang="ts">
import { computed, nextTick, onMounted, onUpdated, ref, watch } from 'vue'
import type { ReportConfig, ReportMetric, ReportResult, ReportPivotResult, ReportSort } from '@/types/nocode/report'
import { pivotOptions } from '@/nocode/report'
import {
  cellId,
  pivotBodyRows,
  pivotCellText,
  pivotColumnGroups,
  pivotHeaderRows,
  pivotRowGroupIds,
  pivotRowSpans,
  pivotRowWindow,
  pivotTotalRow,
  pivotWindowRows,
  PIVOT_ROW_HEIGHT,
  PIVOT_WINDOW_THRESHOLD,
  type PivotBodyRow,
  type PivotColumnGroup,
  type PivotHeaderCell,
  type PivotSelection
} from '@/nocode/report-pivot'
import { reportSortDirection, type ReportSortTarget } from '@/nocode/report-sort'

const props = defineProps<{
  config: ReportConfig
  result: ReportResult
  pivot: ReportPivotResult
  /** 查看时可点列头排序（运行页开启；配置预览不开）。排序由服务端完成，这里只发出意图并标出当前状态。 */
  sortable?: boolean
  sort?: ReportSort | null
}>()
/** 任意数值格（含小计、合计）下钻：行键前缀 → group，列键前缀 → columnGroup。sort：点了可排序的表头格。 */
const emit = defineEmits<{ select: [selection: PivotSelection]; sort: [target: ReportSortTarget] }>()
const options = computed(() => pivotOptions(props.config))
const metrics = computed(() => props.result.metrics)
const cells = computed(() => new Map(props.pivot.cells.map(c => [cellId(c.rowKeys, c.columnKeys), c])))
const groups = computed(() => pivotColumnGroups(props.pivot, options.value))
const header = computed(() => pivotHeaderRows(props.pivot, options.value, metrics.value))
const collapsed = ref(new Set<string>())
const body = computed(() => pivotBodyRows(props.pivot, options.value, collapsed.value))
const total = computed(() => (options.value.columnTotals ? pivotTotalRow(props.pivot) : undefined))
/**
 * 行很多时只为看得见的行建 DOM：上、下各用一行占位撑开滚动条，表头与合计行仍是 sticky。行数不超过阈值时整表渲染，与原先一致。
 * 行内不换行，每行一样高（与不窗口化时同高）；挂载后按实际行高校准。
 */
const scroller = ref<HTMLElement>(),
  scrollTop = ref(0),
  rowHeight = ref(PIVOT_ROW_HEIGHT)
const windowed = computed(() => body.value.length > PIVOT_WINDOW_THRESHOLD)
const range = computed(() =>
  pivotRowWindow(body.value.length, scrollTop.value, scroller.value?.clientHeight || 560, rowHeight.value)
)
const spans = computed(() => (windowed.value ? pivotRowSpans(body.value) : []))
const visible = computed(() =>
  windowed.value ? pivotWindowRows(body.value, spans.value, range.value.start, range.value.end) : body.value
)
const columnCount = computed(() => props.pivot.rowDimensionNames.length + groups.value.length * metrics.value.length)
function onScroll() {
  scrollTop.value = scroller.value?.scrollTop || 0
}
function calibrate() {
  if (!windowed.value) return
  // 行高可能不是整数（13px × 1.5 + 内边距 + 边框 = 32.5px）：取带小数的实际高度，上万行累计下来才不会错位
  const height = scroller.value?.querySelector<HTMLElement>('tbody tr[data-row-level]')?.getBoundingClientRect().height
  if (height && Math.abs(height - rowHeight.value) > 0.01) rowHeight.value = height
}
onMounted(calibrate)
onUpdated(calibrate)
// 换了排序以后从头看；数据静默刷新（排序没变）时保持滚动位置。
watch(
  () => JSON.stringify(props.sort ?? null),
  () =>
    nextTick(() => {
      if (scroller.value) scroller.value.scrollTop = 0
      scrollTop.value = 0
    })
)
const sortState = (h: PivotHeaderCell) =>
  props.sortable && h.sort ? reportSortDirection(props.sort, h.sort) : undefined
function sortMark(h: PivotHeaderCell) {
  const state = sortState(h)
  return state === 'ascending' ? '▲' : state === 'descending' ? '▼' : '↕'
}
const collapsible = computed(() => props.pivot.rowDimensionNames.length > 1)
/** 三层及以上行维度时可「展开到」中间某一层：该层及更深的分组全部折叠为小计行。 */
const expandLevels = computed(() =>
  props.pivot.rowDimensionNames.length > 2
    ? props.pivot.rowDimensionNames.slice(1, -1).map((name, i) => ({ level: i + 2, name }))
    : []
)
function toggle(group: string) {
  const next = new Set(collapsed.value)
  if (next.has(group)) next.delete(group)
  else next.add(group)
  collapsed.value = next
}
/** 全部展开 / 全部折叠（每一层分组都折叠，展开一层后下一层仍为折叠状态）。 */
function expandAll(open: boolean) {
  collapsed.value = open ? new Set() : pivotRowGroupIds(props.pivot, 1)
}
function expandTo(level: number) {
  collapsed.value = pivotRowGroupIds(props.pivot, level)
}
const cell = (row: PivotBodyRow, group: PivotColumnGroup) => cells.value.get(cellId(row.keys, group.keys))
const text = (row: PivotBodyRow, group: PivotColumnGroup, metric: ReportMetric) =>
  pivotCellText(cell(row, group), metric, options.value.percent)
const rawTitle = (row: PivotBodyRow, group: PivotColumnGroup, metric: ReportMetric) => {
  return cell(row, group)?.values[metric.id] ?? undefined
}
const columnLabel = (group: PivotColumnGroup) =>
  group.level === 'total'
    ? '合计'
    : group.level === 'subtotal'
      ? group.labels.join(' / ') + ' 小计'
      : group.labels.join(' / ')
function select(row: PivotBodyRow, group: PivotColumnGroup, metric: ReportMetric) {
  const target = cell(row, group)
  if (!target) return
  emit('select', {
    rowKeys: row.keys,
    columnKeys: group.keys,
    metricId: metric.id,
    label: [row.labels.join(' / '), columnLabel(group)].filter(Boolean).join(' · '),
    values: target.values
  })
}
</script>
<template>
  <div class="report-pivot">
    <div class="pivot-notices">
      <a-alert
        v-if="pivot.columnsTruncated"
        type="warning"
        show-icon
        data-pivot-truncated="columns"
        :message="'列组仅显示前 ' + pivot.columns.length + ' 个，共 ' + pivot.totalColumnGroups + ' 个'"
      />
      <a-alert
        v-if="pivot.rowsTruncated"
        type="warning"
        show-icon
        data-pivot-truncated="rows"
        :message="'行仅显示前 ' + pivot.rows.length + ' 个，共 ' + pivot.totalRowGroups + ' 个'"
      />
      <p v-if="pivot.rowsTruncated && config.limit == null" class="pivot-guard" data-pivot-row-guard>
        共 {{ pivot.totalRowGroups }} 行，只显示前 {{ pivot.rows.length }} 行：已到一次展示的上限。
        小计与合计仍按全部数据计算；要看其余的行，请加筛选条件、改用更粗的分组，或导出。
      </p>
    </div>
    <div v-if="collapsible" class="pivot-tools">
      <a-button size="small" @click="expandAll(true)">全部展开</a-button>
      <a-button size="small" @click="expandAll(false)">全部折叠</a-button>
      <a-button
        v-for="option in expandLevels"
        :key="option.level"
        size="small"
        :data-expand-level="option.level"
        @click="expandTo(option.level)"
      >
        展开到「{{ option.name }}」
      </a-button>
    </div>
    <div ref="scroller" class="pivot-scroll" tabindex="0" role="region" aria-label="透视表" @scroll.passive="onScroll">
      <table class="pivot-table" :class="{ 'pivot-table--windowed': windowed }">
        <thead>
          <tr v-for="(line, index) in header" :key="index">
            <th
              v-for="h in line"
              :key="h.id"
              :colspan="h.colspan"
              :rowspan="h.rowspan"
              class="pivot-head"
              :class="'pivot-head--' + h.level"
              :data-level="h.level"
              :aria-sort="sortState(h)"
            >
              <button
                v-if="sortable && h.sort"
                type="button"
                class="pivot-sort"
                :class="{ 'pivot-sort--active': sortState(h) }"
                :title="'点击按「' + h.text + '」排序：升序 → 降序 → 恢复默认'"
                @click="emit('sort', h.sort)"
              >
                {{ h.text }}
                <span class="pivot-sort-mark" aria-hidden="true">{{ sortMark(h) }}</span>
              </button>
              <template v-else>{{ h.text }}</template>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="windowed && range.before" class="pivot-spacer" aria-hidden="true">
            <td :colspan="columnCount" :style="{ height: range.before + 'px' }"></td>
          </tr>
          <tr v-for="row in visible" :key="row.id" :class="'pivot-row--' + row.level" :data-row-level="row.level">
            <th
              v-for="(h, i) in row.headers"
              :key="i"
              scope="row"
              :rowspan="h.rowspan"
              :colspan="h.colspan"
              class="pivot-row-head"
              :class="{ 'pivot-row-head--first': h.column === 0 }"
            >
              <button
                v-if="h.toggle"
                type="button"
                class="pivot-toggle"
                :aria-expanded="!collapsed.has(h.toggle)"
                :aria-label="(collapsed.has(h.toggle) ? '展开 ' : '折叠 ') + h.text"
                @click="toggle(h.toggle)"
              >
                {{ collapsed.has(h.toggle) ? '▸' : '▾' }}
              </button>
              {{ h.text }}
            </th>
            <template v-for="g in groups" :key="g.id + g.level">
              <td
                v-for="m in metrics"
                :key="m.id"
                class="pivot-cell"
                :class="'pivot-cell--' + g.level"
                :data-column-level="g.level"
                :data-metric="m.id"
                :title="rawTitle(row, g, m)"
              >
                <button v-if="cell(row, g)" type="button" class="pivot-value" @click="select(row, g, m)">
                  {{ text(row, g, m) }}
                </button>
              </td>
            </template>
          </tr>
          <tr v-if="windowed && range.after" class="pivot-spacer" aria-hidden="true">
            <td :colspan="columnCount" :style="{ height: range.after + 'px' }"></td>
          </tr>
        </tbody>
        <tfoot v-if="total">
          <tr class="pivot-row--total" data-row-level="total">
            <th :colspan="total.headers[0].colspan" scope="row" class="pivot-row-head pivot-row-head--first">合计</th>
            <template v-for="g in groups" :key="g.id + g.level">
              <td
                v-for="m in metrics"
                :key="m.id"
                class="pivot-cell"
                :class="'pivot-cell--' + g.level"
                :data-column-level="g.level"
                :data-metric="m.id"
                :title="rawTitle(total, g, m)"
              >
                <button v-if="cell(total, g)" type="button" class="pivot-value" @click="select(total, g, m)">
                  {{ text(total, g, m) }}
                </button>
              </td>
            </template>
          </tr>
        </tfoot>
      </table>
    </div>
  </div>
</template>
<style scoped>
.report-pivot {
  --pivot-border: #e5e7eb;
  --pivot-head: #f4f5fb;
  --pivot-subtotal: #f7f7fd;
  --pivot-total: #eef0fb;
  --pivot-accent: #312e81;
  min-width: 0;
}
.pivot-notices {
  display: grid;
  gap: 8px;
  margin-bottom: 8px;
}
.pivot-notices:empty {
  display: none;
}
.pivot-guard {
  margin: 0;
  color: #64748b;
  font-size: 12px;
}
.pivot-tools {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.pivot-scroll {
  max-height: 560px;
  overflow: auto;
  border: 1px solid var(--pivot-border);
  border-radius: 8px;
  -webkit-overflow-scrolling: touch;
}
.pivot-table {
  width: max-content;
  min-width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  /* 字号、行高、内边距、表头字重与运行端数据视图列表共用一组变量（style.css 的 --data-table-*），值就是透视表原来的字面值。 */
  font-size: var(--data-table-font-size);
  line-height: var(--data-table-line-height);
  font-variant-numeric: tabular-nums;
}
.pivot-table th,
.pivot-table td {
  padding: var(--data-table-cell-padding);
  border-right: 1px solid var(--pivot-border);
  border-bottom: 1px solid var(--pivot-border);
  white-space: nowrap;
}
.pivot-head {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--pivot-head);
  font-weight: var(--data-table-header-font-weight);
  text-align: center;
}
.pivot-head--dimension {
  left: 0;
  z-index: 4;
  text-align: left;
}
.pivot-head--dimension ~ .pivot-head--dimension {
  left: auto;
  z-index: 2;
}
.pivot-head--subtotal,
.pivot-head--total {
  color: var(--pivot-accent);
}
.pivot-row-head {
  background: white;
  font-weight: 500;
  text-align: left;
  vertical-align: top;
}
.pivot-row-head--first {
  position: sticky;
  left: 0;
  z-index: 1;
}
.pivot-cell {
  text-align: right;
  background: white;
}
.pivot-cell--subtotal,
.pivot-row--subtotal .pivot-cell,
.pivot-row--subtotal .pivot-row-head {
  background: var(--pivot-subtotal);
  font-weight: 600;
}
.pivot-cell--total {
  background: var(--pivot-total);
  font-weight: 600;
}
tfoot .pivot-cell,
tfoot .pivot-row-head {
  position: sticky;
  bottom: 0;
  background: var(--pivot-total);
  font-weight: 700;
  border-top: 2px solid #c7c9e8;
}
tfoot .pivot-row-head {
  z-index: 3;
}
.pivot-value {
  min-width: 2em;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: inherit;
  cursor: pointer;
}
.pivot-value:hover,
.pivot-value:focus-visible {
  color: #4f46e5;
  text-decoration: underline;
  outline: none;
}
.pivot-table .pivot-spacer td {
  padding: 0;
  border: 0;
  background: none;
}
.pivot-sort {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.pivot-sort-mark {
  color: #a3a8c3;
  font-size: 11px;
}
.pivot-sort:hover .pivot-sort-mark,
.pivot-sort:focus-visible .pivot-sort-mark,
.pivot-sort--active .pivot-sort-mark {
  color: #4f46e5;
}
.pivot-sort:focus-visible {
  outline: 2px solid #c7c9e8;
  outline-offset: 2px;
}
.pivot-toggle {
  margin-right: 4px;
  padding: 0 2px;
  border: 0;
  background: none;
  color: #64748b;
  cursor: pointer;
}
</style>
