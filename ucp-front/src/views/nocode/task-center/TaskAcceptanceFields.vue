<script setup lang="ts">
import { computed, ref } from 'vue'
import UserSelector from '@/components/UserSelector/index.vue'
import type { User } from '@/types/system/user'
import type { TaskMember, TaskNodeInput } from '@/types/nocode/task-center'

const node = defineModel<Pick<TaskNodeInput, 'assigneeId' | 'acceptorId'>>({ required: true })
const props = withDefaults(
  defineProps<{
    members: TaskMember[]
    readonly?: boolean
    acceptorName?: string | null
    compact?: boolean
    tableEditing?: boolean
    extraOptions?: Array<{ value: string; label: string }>
    overrideValue?: string
  }>(),
  { tableEditing: true }
)
const emit = defineEmits<{ select: [value: string] }>()
const selectorOpen = ref(false)
const selected = ref<User[]>([])
const selectionError = ref('')
const acceptorName = computed(
  () => props.members.find(member => String(member.id) === String(node.value.acceptorId))?.name || props.acceptorName
)
const candidateIds = computed(() =>
  props.members.filter(member => String(member.id) !== String(node.value.assigneeId)).map(member => member.id)
)
const samePerson = computed(
  () => node.value.acceptorId != null && String(node.value.acceptorId) === String(node.value.assigneeId)
)
const tableOptions = computed(() => [
  { value: 'NONE', label: '无需验收', selectedLabel: '无需验收' },
  {
    value: 'ASSIGNED',
    label: '指定验收人',
    selectedLabel: acceptorName.value || selected.value[0]?.nickname || '指定验收人'
  },
  ...(props.extraOptions || []).map(option => ({ ...option, selectedLabel: option.label }))
])
function changeTableValue(value: unknown) {
  if (props.readonly) return
  if (value !== 'ASSIGNED') emit('select', String(value))
  if (props.extraOptions?.some(option => option.value === value)) return
  if (value === 'NONE') return clear()
  openSelector()
}
function openSelector() {
  if (props.readonly) return
  selectionError.value = ''
  selected.value =
    node.value.acceptorId == null
      ? []
      : [{ id: String(node.value.acceptorId), username: '', nickname: acceptorName.value || '已选验收人' }]
  selectorOpen.value = true
}
function confirm(users: User[]) {
  if (props.readonly) {
    selectorOpen.value = false
    return
  }
  const user = users[0]
  if (!user) return
  if (String(user.id) === String(node.value.assigneeId)) {
    selectionError.value = '负责人和验收人不能是同一人，请选择其他验收人。'
    selectorOpen.value = false
    return
  }
  node.value.acceptorId = user.id
  emit('select', 'ASSIGNED')
  selected.value = users
  selectionError.value = ''
  selectorOpen.value = false
}
function clear() {
  if (props.readonly) return
  node.value.acceptorId = null
  selected.value = []
  selectionError.value = ''
}
</script>
<template>
  <div class="task-acceptance-fields">
    <a-select
      v-if="tableEditing"
      :value="overrideValue || (node.acceptorId == null ? 'NONE' : 'ASSIGNED')"
      :disabled="readonly"
      aria-label="验收人"
      option-label-prop="selectedLabel"
      style="width: 100%"
      :options="tableOptions"
      @select="changeTableValue"
    />
    <a-space v-else wrap>
      <a-button :disabled="readonly" @click="openSelector">
        {{ node.acceptorId ? acceptorName || selected[0]?.nickname || '更换验收人' : '选择验收人' }}
      </a-button>
      <a-button v-if="node.acceptorId" type="link" :disabled="readonly" @click="clear">取消验收人</a-button>
    </a-space>
    <p v-if="!compact" class="task-list__hint">
      {{
        node.acceptorId
          ? '验收是整项任务的最后一步：负责人提交后进入待验收，通过后才算完成；退回后继续处理。'
          : '不设置验收人，负责人完成总任务后直接结束。子任务不单独设置验收人。'
      }}
    </p>
    <a-alert
      v-if="samePerson || selectionError"
      type="error"
      show-icon
      :message="samePerson ? '负责人和验收人不能是同一人，请更换负责人或验收人。' : selectionError"
    />
    <UserSelector
      v-if="selectorOpen"
      v-model:visible="selectorOpen"
      title="选择验收人"
      :multiple="false"
      :show-multiple-toggle="false"
      :selected-users="selected"
      :candidate-user-ids="candidateIds"
      :enabled-only="true"
      @confirm="confirm"
    />
  </div>
</template>
<style scoped>
.task-acceptance-fields {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-xs);
}
</style>
