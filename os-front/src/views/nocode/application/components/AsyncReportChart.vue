<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, type Component } from 'vue'
import type { ReportConfig, ReportResult } from '@/types/nocode/report'

defineProps<{ config: ReportConfig; result: ReportResult; compact?: boolean }>()
const emit = defineEmits<{ select: [groupIndex: number, metricId: string] }>()
const chart = shallowRef<Component>()
const loading = ref(true)
const failed = ref(false)
let generation = 0

/** 只有图表真正显示时才加载引擎；组件已关闭时不挂载迟到的结果。 */
async function load() {
  const current = ++generation
  loading.value = true
  failed.value = false
  try {
    const module = await import('./ReportChart.vue')
    if (current === generation) chart.value = module.default
  } catch {
    if (current === generation) failed.value = true
  } finally {
    if (current === generation) loading.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => generation++)
</script>

<template>
  <div class="async-report-chart" :aria-busy="loading">
    <a-alert v-if="failed" type="error" show-icon message="图表资源加载失败，请先保存未保存的内容，再刷新页面。" />
    <div v-else-if="loading" class="chart-loading" role="status">
      <a-spin size="small" />
      <span>图表加载中…</span>
    </div>
    <component
      v-else-if="chart"
      :is="chart"
      :config="config"
      :result="result"
      :compact="compact"
      @select="(index: number, id: string) => emit('select', index, id)"
    />
  </div>
</template>

<style scoped>
.async-report-chart {
  min-height: 360px;
  min-width: 0;
}
.chart-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 360px;
  color: var(--os-text-secondary, #666);
}
</style>
