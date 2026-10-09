<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { v4 as uuid } from 'uuid'
import { useNocodePlatform } from '@/nocode/platform'
import { taskNeedsAcceptance, taskPauseExplanation, taskResumeExplanation } from '@/nocode/task-center'
import { errorMessage } from '@/nocode/data-center'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { useTaskTransitionRecovery } from '@/nocode/task-transition-recovery'
import { cancellationConfirmationRequired, taskCompletionReady } from '@/nocode/task-completion-confirmation'
import type { TaskRow, TaskMember, TaskUserId, TaskReadiness } from '@/types/nocode/task-center'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import TaskReadinessPanel from './TaskReadinessPanel.vue'

const { confirmDiscard } = useTaskConfirmation()
const props = defineProps<{ task: TaskRow; action: 'COMPLETE' | 'COMMENT' | 'PAUSE' | 'RESUME' }>()
const emit = defineEmits<{ close: []; saved: []; inspect: [tab: 'overview' | 'business', entryKey?: string] }>()
const api = useNocodePlatform().taskCenter
const text = ref(''),
  mentions = ref<TaskUserId[]>([]),
  members = ref<TaskMember[]>([]),
  error = ref(''),
  busy = ref(false),
  readiness = ref<TaskReadiness | null>(null)
const confirmCancelledChildren = ref(false)
const needsCancellationConfirmation = computed(() => cancellationConfirmationRequired(readiness.value))
const recovery = useTaskTransitionRecovery(api, () => (props.action !== 'COMMENT' ? props.task.id : ''))
const pending = recovery.pending
const needsConfirmation = recovery.needsConfirmation
watch(
  () => [props.task.id, props.task.revision, readiness.value] as const,
  () => {
    // 刷新条件或切换目标后必须重新确认，不能沿用旧范围的勾选。
    confirmCancelledChildren.value = !!pending.value?.confirmCancelledChildren
  }
)
watch(
  () => [recovery.identity.value, pending.value] as const,
  ([identity, command], previous) => {
    if (previous && identity !== previous[0]) text.value = ''
    if (command) {
      text.value = command.note
      confirmCancelledChildren.value = !!command.confirmCancelledChildren
    }
  },
  { immediate: true }
)
const canSave = computed(() => {
  if (busy.value) return false
  if (props.action === 'COMMENT') return !!text.value.trim()
  if (pending.value) return pending.value.action === props.action
  if (props.action === 'PAUSE') return !!props.task.canPause
  if (props.action === 'RESUME') return !!props.task.canResume
  return (
    readiness.value?.taskId === props.task.id &&
    readiness.value.revision === props.task.revision &&
    taskCompletionReady(readiness.value, confirmCancelledChildren.value, text.value)
  )
})
const completionLabel = computed(() => (taskNeedsAcceptance(props.task) ? '提交验收' : '完成任务'))
const actionLabel = computed(() =>
  props.action === 'PAUSE' ? '暂停任务' : props.action === 'RESUME' ? '恢复任务' : completionLabel.value
)
const confirmationLabel = computed(() =>
  props.action === 'PAUSE'
    ? '确认暂停'
    : props.action === 'RESUME'
      ? '确认恢复'
      : taskNeedsAcceptance(props.task)
        ? '确认提交验收'
        : '确认完成'
)
const noteLabel = computed(() =>
  props.action === 'COMMENT'
    ? '评论内容'
    : props.action === 'PAUSE'
      ? '暂停原因（选填）'
      : props.action === 'RESUME'
        ? '恢复说明（选填）'
        : needsCancellationConfirmation.value
          ? '交付说明'
          : '完成备注'
)
const requestKey = uuid()
onMounted(async () => {
  if (props.action === 'COMMENT') {
    try {
      members.value = await api.members(props.task.id)
    } catch (e) {
      error.value = errorMessage(e)
    }
  }
})
async function save() {
  if (!canSave.value) return
  busy.value = true
  error.value = ''
  try {
    if (props.action === 'COMMENT')
      await api.comment({
        taskId: props.task.id,
        parentId: null,
        content: text.value.trim(),
        mentionedUserIds: mentions.value,
        requestKey
      })
    else {
      const attempt = recovery.begin(
        pending.value || {
          id: props.task.id,
          expectedRevision: props.task.revision,
          action: props.action,
          note: text.value,
          requestKey,
          ...(props.action === 'COMPLETE' && needsCancellationConfirmation.value
            ? { confirmCancelledChildren: true }
            : {})
        }
      )
      try {
        await api.transition(attempt.command)
        recovery.succeed(attempt)
        if (recovery.identity.value !== attempt.key) return
      } catch (cause) {
        const result = await recovery.fail(attempt, cause)
        if (recovery.identity.value !== attempt.key) return
        if (!result.applied) {
          if (result.superseded) error.value = '原操作未生效，任务已更新。请查看最新任务后再操作，原备注已保留。'
          else error.value = errorMessage(cause)
          return
        }
      }
    }
    emit('saved')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function close() {
  if (!busy.value && (await confirmDiscard(!!text.value.trim() || !!mentions.value.length, '放弃尚未提交的内容？')))
    emit('close')
}
async function inspect(check?: TaskReadiness['checks'][number]) {
  if (busy.value || !(await confirmDiscard(!!text.value.trim(), '先处理任务条件？未提交的备注将放弃。'))) return
  emit(
    'inspect',
    check?.code === 'BUSINESS' || check?.code === 'FEEDBACK' ? 'business' : 'overview',
    check?.entryKey || undefined
  )
  emit('close')
}
</script>
<template>
  <OsModalForm
    display-mode="modal"
    :open="true"
    :title="`${action === 'COMMENT' ? '评论' : actionLabel}：${task.title}`"
    :allow-switch-display="false"
    :resizable="false"
    :width="560"
    :loading="busy"
    @ok="save"
    @cancel="close"
  >
    <template #formItems>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert
        v-if="needsConfirmation && pending"
        type="warning"
        show-icon
        :message="
          pending.action === action
            ? '原操作结果待确认。重试仅核对同一请求，备注暂不可修改。'
            : '此任务另有待确认操作，请在任务详情中确认原操作结果。'
        "
      />
      <a-button v-if="needsConfirmation && pending && pending.action !== action" @click="inspect()">
        查看原操作
      </a-button>
      <a-alert
        v-if="action === 'PAUSE' || action === 'RESUME'"
        type="info"
        show-icon
        :message="action === 'PAUSE' ? taskPauseExplanation : taskResumeExplanation"
      />
      <a-alert
        v-if="action === 'COMPLETE' && taskNeedsAcceptance(task)"
        type="info"
        show-icon
        :message="`提交后由 ${task.acceptorName || '指定验收人'} 验收，通过后任务才会完成。`"
      />
      <TaskReadinessPanel
        v-if="action === 'COMPLETE'"
        :task-id="task.id"
        :revision="task.revision"
        action="COMPLETE"
        :completion-label="completionLabel"
        @loaded="readiness = $event"
        @navigate="inspect"
      />
      <template v-if="readiness && readiness.revision !== task.revision">
        <a-alert type="warning" message="任务已有更新，请查看最新任务后再操作。" />
        <a-button @click="inspect()">查看最新任务</a-button>
      </template>
      <a-checkbox
        v-if="action === 'COMPLETE' && needsCancellationConfirmation"
        v-model:checked="confirmCancelledChildren"
        :disabled="busy || !!pending"
        aria-label="确认取消后的交付范围"
      >
        我已确认上述取消范围，剩余工作可以交付；取消项不计为已完成。
      </a-checkbox>
      <a-form-item
        :label="noteLabel"
        :required="action === 'COMMENT' || (action === 'COMPLETE' && needsCancellationConfirmation)"
      >
        <a-textarea
          v-model:value="text"
          :rows="4"
          :maxlength="action === 'COMMENT' ? 4000 : 2000"
          :aria-label="noteLabel"
          :disabled="busy || !!pending"
        />
      </a-form-item>
      <a-form-item v-if="action === 'COMMENT'" label="@ 提醒成员">
        <a-select
          v-model:value="mentions"
          mode="multiple"
          show-search
          option-filter-prop="label"
          :options="members.map(member => ({ value: member.id, label: member.name }))"
          aria-label="提醒成员"
        />
      </a-form-item>
    </template>
    <template #footer>
      <a-button :disabled="busy" @click="close">暂不操作</a-button>
      <a-button type="primary" :loading="busy" :disabled="!canSave" @click="save">
        {{
          busy
            ? needsConfirmation
              ? '正在确认结果'
              : '正在提交'
            : action === 'COMMENT'
              ? '发表评论'
              : needsConfirmation
                ? '确认原操作结果'
                : confirmationLabel
        }}
      </a-button>
    </template>
  </OsModalForm>
</template>
