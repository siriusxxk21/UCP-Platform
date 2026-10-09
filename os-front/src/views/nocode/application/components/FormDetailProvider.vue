<script setup lang="ts">
import { provide, type VNode } from 'vue'
import { formDetailKey, type FormDetailContext } from '@/nocode/form-detail-context'

const props = defineProps<{ detailIds: string[] }>()
const slots = defineSlots<{
  default(): VNode[]
  detail(context: FormDetailContext): VNode[]
}>()
// 每个整单提供独立上下文，关联记录不能误用外层主单的同名明细。
provide(formDetailKey, {
  visible: id => props.detailIds.includes(id),
  render: context => (props.detailIds.includes(context.detailId) ? slots.detail(context) : undefined)
})
</script>
<template>
  <slot />
</template>
