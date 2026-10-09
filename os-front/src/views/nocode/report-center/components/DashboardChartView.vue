<script setup lang="ts">
import ReportPivotTable from '@/views/nocode/application/components/ReportPivotTable.vue'
import { computed } from 'vue'
import AsyncReportChart from '@/views/nocode/application/components/AsyncReportChart.vue'
import { formatReportValue } from '@/nocode/report-presentation'
import { dashboardChartConfig } from '@/nocode/report-dashboard'
import type { DashboardChart, DashboardSelection } from '@/types/nocode/report-dashboard'
import type { ReportResult } from '@/types/nocode/report'
const props = defineProps<{ chart: DashboardChart; result: ReportResult }>()
const emit = defineEmits<{ select: [selection: DashboardSelection] }>()
const config = computed(() => dashboardChartConfig(props.chart, props.result))
const columns = computed(() => [
  ...props.result.dimensionNames.map((title, i) => ({ title, key: 'dimension:' + i, dimensionIndex: i })),
  ...props.result.metrics.map(m => ({ title: m.name, key: m.id }))
])
const rows = computed(() => props.result.groups.map((g, i) => ({ ...g, index: i })))
</script>
<template>
  <div v-if="chart.display === 'METRIC'" class="dashboard-metrics">
    <div v-for="metric in result.metrics" :key="metric.id">
      <span>{{ metric.name }}</span>
      <strong>
        <a-button type="link" @click="emit('select', { group: [], columnGroup: [], metricId: metric.id })">
          {{ formatReportValue(result.totals[metric.id], metric) }}
        </a-button>
      </strong>
    </div>
  </div>
  <ReportPivotTable
    v-else-if="chart.display === 'PIVOT' && result.pivot"
    :config="config"
    :result="result"
    :pivot="result.pivot"
    @select="s => emit('select', { group: s.rowKeys, columnGroup: s.columnKeys, metricId: s.metricId })"
  />
  <template v-else-if="chart.display === 'TABLE'">
    <a-table
      :columns="columns"
      :data-source="rows"
      row-key="index"
      :pagination="false"
      size="small"
      :scroll="{ x: 'max-content' }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="typeof column.dimensionIndex === 'number'">
          {{ record.labels[column.dimensionIndex] }}
        </template>
        <template v-else>
          <a-button
            type="link"
            size="small"
            @click="emit('select', { group: record.keys, columnGroup: [], metricId: column.key })"
          >
            {{
              formatReportValue(
                record.values[column.key],
                result.metrics.find(m => m.id === column.key)!
              )
            }}
          </a-button>
        </template>
      </template>
      <template #footer>
        合计：
        <span v-for="metric in result.metrics" :key="metric.id">
          {{ metric.name }} {{ formatReportValue(result.totals[metric.id], metric) }} ·
        </span>
      </template>
    </a-table>
  </template>
  <a-empty v-else-if="!result.groups.length" description="当前范围没有数据" />
  <AsyncReportChart
    v-else
    :config="config"
    :result="result"
    @select="(i, metricId) => emit('select', { group: result.groups[i]!.keys, columnGroup: [], metricId })"
  />
  <div class="dashboard-scope">
    当前可见数据 {{ result.recordCount }} 条
    <span v-if="!result.pivot && result.totalGroups > result.groups.length">
      · 展示 {{ result.groups.length }} / {{ result.totalGroups }} 组
    </span>
  </div>
</template>
<style scoped>
.dashboard-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xl);
  padding: var(--spacing-xl);
}
.dashboard-metrics span {
  color: var(--text-secondary);
}
.dashboard-metrics strong {
  display: block;
  font-size: var(--font-size-report-metric);
  color: var(--brand);
  margin-top: var(--spacing-sm);
  font-variant-numeric: tabular-nums;
}
.dashboard-metrics strong :deep(.ant-btn) {
  font-size: inherit;
  height: auto;
  padding: 0;
}
.dashboard-scope {
  padding: var(--spacing-sm) 0;
  color: var(--text-tertiary);
  font-size: var(--table-body-font-size);
}
</style>
