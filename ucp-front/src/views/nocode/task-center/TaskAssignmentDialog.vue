<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskNodeInput } from '@/nocode/task-center'
import type { TaskRow, TaskMember, TaskDetail, TaskUserId } from '@/types/nocode/task-center'
import TaskAssignmentFields from './TaskAssignmentFields.vue'
import TaskAcceptanceFields from './TaskAcceptanceFields.vue'
const props = defineProps<{ task: TaskRow; currentUserId?: TaskUserId }>()
const emit = defineEmits<{ close: []; saved: [detail: TaskDetail] }>()
const api = useNocodePlatform().taskCenter,
  node = ref(taskNodeInput(props.task)),
  members = ref<TaskMember[]>([])
const busy = ref(false),
  error = ref(''),
  uncertain = ref(false)
let pending: Parameters<typeof api.assign>[0] | undefined
const assignmentMode = computed(() => node.value.assignmentMode || 'ASSIGNED')
const transfer = computed(() => !!props.task.canTransfer && ['RUNNING', 'PAUSED'].includes(props.task.status))
const handoverNote = ref('')
const isRoot = computed(() => props.task.id === props.task.rootId)
const delegateOnly = computed(() => !props.task.canAssign && !!props.task.canDelegate)
const canTakeOver = computed(
  () =>
    props.currentUserId != null &&
    String(props.currentUserId) !== String(props.task.assigneeId) &&
    !!(props.task.canAssign || props.task.canDelegate || props.task.canTransfer)
)
if (transfer.value) {
  node.value.assignmentMode = 'ASSIGNED'
  node.value.assigneeId = null
  node.value.candidateUserIds = []
}
if (delegateOnly.value && !['ASSIGNED', 'OPEN'].includes(node.value.assignmentMode || ''))
  node.value.assignmentMode = 'ASSIGNED'
onMounted(async () => {
  try {
    // 分配允许选择尚未参与任务的启用员工；带 taskId 的目录仅用于评论提醒。
    members.value = await api.members()
  } catch (cause) {
    error.value = errorMessage(cause)
  }
})
async function save() {
  if (busy.value) return
  if (transfer.value && assignmentMode.value !== 'ASSIGNED') {
    error.value = '转交任务需要指定新的负责人'
    return
  }
  if (delegateOnly.value && !['ASSIGNED', 'OPEN'].includes(assignmentMode.value)) {
    error.value = '请选择指定负责人或开放领取'
    return
  }
  if (assignmentMode.value === 'ASSIGNED' && !node.value.assigneeId) {
    error.value = '请选择负责人'
    return
  }
  if (transfer.value && String(node.value.assigneeId) === String(props.task.assigneeId)) {
    error.value = '请选择与当前负责人不同的接收人'
    return
  }
  if (transfer.value && !handoverNote.value.trim()) {
    error.value = '请填写交接说明，方便接收人继续办理'
    return
  }
  if (
    isRoot.value &&
    node.value.acceptorId != null &&
    String(node.value.acceptorId) === String(node.value.assigneeId)
  ) {
    error.value = '负责人和验收人不能是同一人，请更换负责人或验收人。'
    return
  }
  busy.value = true
  try {
    pending ||= {
      id: props.task.id,
      expectedRevision: props.task.revision,
      assignmentMode: assignmentMode.value,
      assigneeId: node.value.assigneeId,
      candidateUserIds: [...(node.value.candidateUserIds || [])],
      ...(isRoot.value && !transfer.value ? { acceptance: { acceptorId: node.value.acceptorId ?? null } } : {}),
      ...(transfer.value ? { transfer: true, note: handoverNote.value.trim() } : {}),
      requestKey: uuid()
    }
    const detail = await api.assign(pending)
    pending = undefined
    emit('saved', detail)
    message.success(transfer.value ? '任务已转交，原进度和记录已保留' : '人员安排已保存')
    emit('close')
  } catch (cause) {
    uncertain.value = !(cause && typeof cause === 'object' && 'businessCode' in cause)
    if (!uncertain.value) pending = undefined
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
function takeOver() {
  if (!canTakeOver.value || props.currentUserId == null || busy.value || uncertain.value) return
  node.value.assignmentMode = 'ASSIGNED'
  node.value.assigneeId = props.currentUserId
  node.value.candidateUserIds = []
}
</script>
<template>
  <OsModalForm
    :open="true"
    :title="transfer ? '转交任务' : '安排负责人'"
    :loading="busy"
    :ok-text="transfer ? '确认转交' : '保存人员安排'"
    cancel-text="取消"
    :width="600"
    :allow-switch-display="false"
    @ok="save"
    @cancel="!busy && emit('close')"
  >
    <template #formItems>
      <p>{{ task.title }}</p>
      <p class="task-list__hint">当前负责人：{{ task.assigneeName || '待领取' }}</p>
      <a-form-item :label="transfer ? '交给谁' : '负责人安排'">
        <TaskAssignmentFields
          v-model="node"
          :members="members"
          :readonly="busy || uncertain"
          :delegate-only="delegateOnly"
          :assigned-only="transfer"
          :allow-follow="!isRoot && !delegateOnly && !transfer"
        />
        <a-button v-if="canTakeOver" type="link" size="small" :disabled="busy || uncertain" @click="takeOver">
          改由我负责
        </a-button>
      </a-form-item>
      <a-form-item v-if="transfer" label="交接说明" required>
        <a-textarea
          v-model:value="handoverNote"
          :disabled="busy || uncertain"
          :rows="3"
          :maxlength="1000"
          placeholder="说明当前进度及需要接着做的事项"
          aria-label="交接说明"
        />
        <p class="task-list__hint">
          保留当前进度、状态和办理记录{{ task.status === 'PAUSED' ? '，转交后仍为暂停状态' : '' }}。
        </p>
      </a-form-item>
      <a-form-item v-if="isRoot && !transfer" label="验收人（可选）">
        <TaskAcceptanceFields
          v-model="node"
          :members="members"
          :acceptor-name="task.acceptorName"
          :readonly="busy || uncertain"
        />
      </a-form-item>
      <a-alert v-if="error" type="error" :message="error" />
      <p v-if="uncertain" class="task-list__hint">人员安排结果尚未确认；重试保留原人员、版本和请求键。</p>
    </template>
  </OsModalForm>
</template>
