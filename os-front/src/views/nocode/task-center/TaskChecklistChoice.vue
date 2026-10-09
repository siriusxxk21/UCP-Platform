<script setup lang="ts">
import type { TaskChecklistChoice } from '@/types/nocode/task-center'
withDefaults(
  defineProps<{
    disabled?: boolean
    allowLater?: boolean
    compact?: boolean
    action?: 'ADD' | 'REMOVE'
    laterLabel?: string
  }>(),
  { action: 'ADD' }
)
const value = defineModel<TaskChecklistChoice | 'LATER'>({ required: true })
</script>
<template>
  <a-radio-group
    v-model:value="value"
    :disabled="disabled"
    :aria-label="action === 'REMOVE' ? '从哪份计划移出' : '加入哪份计划'"
  >
    <a-radio-button value="DAY" :title="action === 'ADD' ? '加入今日时同时纳入本周' : undefined">
      {{ compact ? '今日' : action === 'REMOVE' ? '今日计划' : '加入今日计划' }}
    </a-radio-button>
    <a-radio-button value="WEEK">
      {{ compact ? '本周' : action === 'REMOVE' ? '本周计划' : '加入本周计划' }}
    </a-radio-button>
    <a-radio-button value="NEXT_WEEK">
      {{ compact ? '下周' : action === 'REMOVE' ? '下周计划' : '加入下周计划' }}
    </a-radio-button>
    <a-radio-button v-if="allowLater && action === 'ADD'" value="LATER">
      {{ laterLabel || (compact ? '暂不加入' : '稍后加入') }}
    </a-radio-button>
  </a-radio-group>
</template>
