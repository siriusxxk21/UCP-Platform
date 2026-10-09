<script setup lang="ts">
import { computed } from 'vue'
import type { TaskScheduleSummary } from '@/types/nocode/task-center'
const props = defineProps<{ summary?: TaskScheduleSummary }>()
const text = computed(() =>
  props.summary?.source === 'UNSCHEDULED' && !props.summary.warnings.length
    ? ''
    : props.summary?.partial
      ? '部分未排期'
      : props.summary?.warnings.length
        ? '排期需确认'
        : ''
)
</script>
<template>
  <a-tooltip v-if="text" :title="summary?.warnings.join('；') || '部分下级尚未排期，当前日期不代表完整任务周期'">
    <span class="task-schedule-notice" tabindex="0">{{ text }}</span>
  </a-tooltip>
</template>
<style scoped>
.task-schedule-notice {
  display: block;
  color: var(--color-warning, #d48806);
  font-size: var(--table-font-sm);
}
</style>
