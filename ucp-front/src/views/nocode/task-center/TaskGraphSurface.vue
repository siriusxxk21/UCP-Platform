<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'

defineProps<{ open: boolean; readonly?: boolean; inline?: boolean }>()
const emit = defineEmits<{ close: [] }>()
// 底座抽屉宽度使用数值；随视口变化，而非受外层详情/模板抽屉宽度限制。
const graphWidth = () => Math.max(1, window.innerWidth - (window.innerWidth >= 768 ? 48 : 0))
const width = ref(graphWidth())
const resize = () => (width.value = graphWidth())
onMounted(() => window.addEventListener('resize', resize))
onBeforeUnmount(() => window.removeEventListener('resize', resize))
</script>

<template>
  <OsModalForm
    v-if="open && !inline"
    :open="true"
    :title="readonly ? '任务关系图' : '图上编排'"
    display-mode="drawer"
    maximizable
    :width="width"
    :wrap-form="false"
    :allow-switch-display="false"
    :resizable="false"
    :mask-closable="false"
    @cancel="emit('close')"
  >
    <template #formItems>
      <div class="task-graph-surface"><slot /></div>
    </template>
    <template #footer>
      <span class="task-list__hint">
        {{ readonly ? '关闭关系图返回任务列表。' : '修改已保留在当前草稿中，返回原页面后统一保存。' }}
      </span>
    </template>
  </OsModalForm>
  <slot v-else />
</template>

<style scoped>
.task-graph-surface :deep(.task-dag__viewport) {
  height: calc(100dvh - 330px);
  max-height: none;
}
</style>
