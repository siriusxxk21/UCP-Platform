<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { TaskReadiness } from '@/types/nocode/task-center'

const props = defineProps<{
  taskId: string
  revision: number
  action?: 'COMPLETE' | 'CANCEL'
  completionLabel?: string
}>()
const emit = defineEmits<{
  loaded: [value: TaskReadiness | null]
  navigate: [check: TaskReadiness['checks'][number]]
}>()
const api = useNocodePlatform().taskCenter
const value = ref<TaskReadiness>(),
  loading = ref(false),
  error = ref('')
let generation = 0
onBeforeUnmount(() => generation++)
async function refresh() {
  const token = ++generation
  value.value = undefined
  emit('loaded', null)
  loading.value = true
  error.value = ''
  try {
    const result = await api.readiness(props.taskId)
    if (token !== generation) return
    value.value = result
    emit('loaded', result)
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(() => [props.taskId, props.revision, props.action], refresh, { immediate: true })
defineExpose({ refresh })
</script>
<template>
  <section
    class="task-readiness"
    :aria-label="action === 'CANCEL' ? '取消影响预览' : `${completionLabel || '完成'}条件`"
  >
    <div class="task-panel__header">
      <strong>{{ action === 'CANCEL' ? '取消影响预览' : `${completionLabel || '完成'}条件` }}</strong>
      <a-button type="link" size="small" :loading="loading" @click="refresh">重新检查</a-button>
    </div>
    <a-spin v-if="loading" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <template v-if="value && !loading">
      <template v-if="action === 'CANCEL'">
        <a-alert
          :type="value.canCancel ? 'warning' : 'error'"
          show-icon
          :message="value.cancelBlockedReason || '取消不算完成，依赖此任务的后续工作不会自动放行。'"
        />
        <ul v-if="value.cancellationImpacts.length" class="task-readiness__list">
          <li v-for="item in value.cancellationImpacts" :key="item.taskId">
            <strong>{{ item.title }}</strong>
            <a-tag>{{ item.direct ? '直接后续' : '间接受影响' }}</a-tag>
            <p class="task-list__hint">{{ item.reason }}</p>
          </li>
        </ul>
        <p v-else class="task-list__hint">当前没有可展示的未结束后续任务影响。</p>
      </template>
      <template v-else>
        <ul class="task-readiness__list">
          <li v-for="(check, index) in value.checks" :key="`${check.code}:${check.entryKey || index}`">
            <div class="task-readiness__item">
              <a-tag :color="check.passed ? 'success' : 'warning'">{{ check.passed ? '已满足' : '待处理' }}</a-tag>
              <strong>{{ check.label }}</strong>
              <a-button
                v-if="
                  !check.passed &&
                  (check.code === 'BUSINESS' ||
                    check.code === 'CHILDREN' ||
                    (check.code === 'FEEDBACK' && check.entryKey))
                "
                type="link"
                size="small"
                @click="emit('navigate', check)"
              >
                {{ check.code === 'CHILDREN' ? '查看子任务' : '去处理' }}
              </a-button>
            </div>
            <p v-if="check.reason" class="task-list__hint">{{ check.reason }}</p>
            <p
              v-if="!check.passed && ((check.code === 'FEEDBACK' && !check.entryKey) || check.code === 'ASSIGNEE')"
              class="task-list__hint"
            >
              请联系任务负责人核对办理资格与入口权限。
            </p>
          </li>
        </ul>
        <p class="task-list__hint">
          保存反馈与{{ completionLabel || '完成任务' }}是两个操作；确认时会再次校验最新状态和材料。
        </p>
      </template>
    </template>
  </section>
</template>
