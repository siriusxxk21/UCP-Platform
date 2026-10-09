<script setup lang="ts">
import { defineComponent, inject } from 'vue'
import { formDetailKey, type FormDetailContext } from '@/nocode/form-detail-context'

defineOptions({ inheritAttrs: false })
const props = defineProps<FormDetailContext>()
const context = inject(formDetailKey, undefined)
// 在实际节点位置渲染同一份整单明细，不搬移 DOM，也不拆分主表的表单实例。
const DetailContent = defineComponent({
  setup: () => () => context?.render(props) || null
})
</script>
<template>
  <DetailContent />
</template>
