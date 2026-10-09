<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { ReportConfig, ReportResult } from '@/types/nocode/report'
import { reportChartOption } from '@/nocode/report-presentation'
const props = defineProps<{ config: ReportConfig; result: ReportResult; compact?: boolean }>()
const emit = defineEmits<{ select: [groupIndex: number, metricId: string] }>()
const canvas = ref<HTMLElement>()
const error = ref('')
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined
async function render() {
  await nextTick()
  if (!canvas.value) return
  error.value = ''
  chart ||= echarts.init(canvas.value)
  chart.clear()
  try {
    chart.setOption(
      reportChartOption(props.config, props.result, {
        compact: props.compact,
        primaryColor: props.compact ? getComputedStyle(canvas.value).getPropertyValue('--brand').trim() : undefined
      }),
      true
    )
  } catch (e) {
    error.value = (e as Error).message
  }
  chart.off('click')
  chart.on('click', (p: any) => {
    if (p.data?.groupIndex != null) emit('select', p.data.groupIndex, p.data.metricId)
  })
}
onMounted(() => {
  observer = new ResizeObserver(() => chart?.resize())
  if (canvas.value) observer.observe(canvas.value)
  void render()
})
watch(() => [props.config, props.result, props.compact], render, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>
<template>
  <a-alert v-if="error" :message="error" type="warning" show-icon />
  <div ref="canvas" class="report-chart" role="img" aria-label="统计图表" />
</template>
<style scoped>
.report-chart {
  width: 100%;
  min-width: 0;
  height: 360px;
}
</style>
