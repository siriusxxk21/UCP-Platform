<script setup lang="ts">
import { computed } from 'vue'
import AsyncReportChart from '@/views/nocode/application/components/AsyncReportChart.vue'
import { efficiencyChart, efficiencyDuration } from '@/nocode/task-efficiency'
import type { TaskEfficiencyOverview, TaskEfficiencyQuery } from '@/types/nocode/task-efficiency'

/** 总览只负责展示；筛选、请求和下钻由工作区统一管理。 */
const props = defineProps<{
  overview?: TaskEfficiencyOverview
  loading: boolean
  error: string
  period: TaskEfficiencyQuery
}>()
const emit = defineEmits<{
  records: []
  employees: []
  employee: [row: { employeeId: number; employeeName: string }]
}>()
const trend = computed(() => (props.overview ? efficiencyChart(props.overview, 'trend', props.period) : null))
const distribution = computed(() => (props.overview ? efficiencyChart(props.overview, 'employees') : null))
function selectEmployee(index: number) {
  const id = distribution.value?.result.groups[index]?.keys[0]
  const row = props.overview?.employees.find(item => String(item.employeeId) === id)
  if (row) emit('employee', row)
}
</script>

<template>
  <a-spin :spinning="loading">
    <div class="efficiency-summary">
      <section class="efficiency-summary-group" aria-label="所选期间产出">
        <header>
          <h3>所选期间产出</h3>
          <span>按标准工时计量，不是实际在线时长</span>
        </header>
        <div class="efficiency-metrics">
          <article class="efficiency-metric efficiency-metric--primary">
            <span>标准工时</span>
            <strong>{{ overview ? efficiencyDuration(overview.standardMinutes) : '—' }}</strong>
            <small>
              {{
                overview
                  ? `${overview.employeeCount} 位员工 · ${overview.recordCount} 条计量记录`
                  : '按有效办理结果计量'
              }}
            </small>
            <a-button type="link" size="small" :disabled="!overview?.recordCount" @click="emit('records')">
              查看计量明细
            </a-button>
          </article>
          <article class="efficiency-metric">
            <span>完成执行节点</span>
            <strong>
              {{ overview?.completedNodeCount ?? '—' }}
              <em>个</em>
            </strong>
            <small>仅计最下级节点，父子不重复</small>
            <a-button type="link" size="small" @click="emit('employees')">查看员工分析</a-button>
          </article>
        </div>
      </section>
      <section class="efficiency-summary-group efficiency-summary-group--snapshot" aria-label="当前待跟进">
        <header>
          <h3>当前待跟进</h3>
          <span>当前状态 · 不受日期限制</span>
        </header>
        <div class="efficiency-metrics">
          <article class="efficiency-metric">
            <span>在办执行节点</span>
            <strong>
              {{ overview?.activeNodeCount ?? '—' }}
              <em>个</em>
            </strong>
            <small>含未开始、进行中、暂停、待验收</small>
          </article>
          <article class="efficiency-metric" :class="{ 'efficiency-metric--warning': overview?.overdueNodeCount }">
            <span>逾期执行节点</span>
            <strong>
              {{ overview?.overdueNodeCount ?? '—' }}
              <em>个</em>
            </strong>
            <small>超过预计完成时间且未结束</small>
          </article>
        </div>
        <p class="efficiency-snapshot-note">在办含待分配节点；员工、模板条件仍生效。</p>
      </section>
    </div>
  </a-spin>
  <div class="efficiency-charts">
    <section class="efficiency-chart-card">
      <header>
        <h3>标准工时趋势</h3>
        <span>按首次有效办理提交日 · 单位：小时</span>
      </header>
      <a-skeleton v-if="loading" active :paragraph="{ rows: 7 }" />
      <AsyncReportChart v-else-if="trend && overview?.recordCount" :config="trend.config" :result="trend.result" />
      <a-empty v-else :description="error ? '统计加载失败，请重试' : '此期间暂无计量记录'" />
    </section>
    <section class="efficiency-chart-card">
      <header>
        <h3>员工标准工时</h3>
        <a-button type="link" size="small" @click="emit('employees')">查看全部员工</a-button>
      </header>
      <a-skeleton v-if="loading" active :paragraph="{ rows: 7 }" />
      <AsyncReportChart
        v-else-if="distribution?.result.groups.length"
        compact
        :config="distribution.config"
        :result="distribution.result"
        @select="selectEmployee"
      />
      <a-empty v-else :description="error ? '统计加载失败，请重试' : '此期间暂无员工计量记录'" />
      <p class="efficiency-note">展示标准工时前 8 位；点击图柱查看明细，不作为效率排名。</p>
    </section>
  </div>
</template>

<style scoped>
.efficiency-note,
.efficiency-chart-card header > span {
  color: var(--text-secondary);
  font-size: var(--table-body-font-size);
}
.efficiency-summary {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}
.efficiency-summary-group {
  border: 1px solid var(--border);
  background: var(--color-bg-container);
  border-radius: var(--radius);
  padding: var(--spacing-lg);
}
.efficiency-summary-group header {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--spacing-sm) var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}
.efficiency-summary-group h3 {
  font-size: var(--font-size-md, 16px);
  margin: 0;
}
.efficiency-summary-group header > span {
  font-size: var(--table-font-sm);
  color: var(--text-secondary);
}
.efficiency-snapshot-note {
  margin-top: var(--spacing-sm);
  margin-bottom: 0;
  font-size: var(--table-font-sm);
  color: var(--text-secondary);
}
.efficiency-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--spacing-lg);
}
.efficiency-metric {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: 0;
  padding-right: var(--spacing-sm);
}
.efficiency-metric :deep(.ant-btn) {
  align-self: flex-start;
  padding: 0;
  height: auto;
}
.efficiency-metric + .efficiency-metric {
  border-left: 1px solid var(--border);
  padding-left: var(--spacing-lg);
}
.efficiency-metric > span,
.efficiency-metric small {
  color: var(--text-secondary);
}
.efficiency-metric strong {
  font-size: var(--font-size-xl, 24px);
  font-variant-numeric: tabular-nums;
  line-height: 1.5;
}
.efficiency-metric small {
  font-size: var(--table-font-sm);
}
.efficiency-metric em {
  margin-left: var(--spacing-sm);
  font-size: var(--table-body-font-size);
  font-style: normal;
  font-weight: normal;
  color: var(--text-secondary);
}
.efficiency-metric--primary strong {
  color: var(--brand);
}
.efficiency-metric--warning strong {
  color: var(--error);
}
.efficiency-charts {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: var(--spacing-lg);
}
.efficiency-chart-card {
  min-width: 0;
  padding: var(--spacing-lg);
  background: var(--color-bg-container);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.efficiency-chart-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
  flex-wrap: wrap;
}
.efficiency-chart-card h3 {
  margin: 0;
  font-size: var(--font-size-md, 16px);
}
.efficiency-chart-card :deep(.ant-empty) {
  min-height: 300px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.efficiency-note {
  margin: 0 0 var(--spacing-md);
  line-height: 1.7;
}
.efficiency-chart-card > .efficiency-note {
  margin: var(--spacing-sm) 0 0;
}
@media (max-width: 1100px) {
  .efficiency-summary,
  .efficiency-charts {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .efficiency-metrics {
    gap: var(--spacing-sm);
  }
  .efficiency-metric strong {
    font-size: var(--font-size-lg, 20px);
  }
}
</style>
