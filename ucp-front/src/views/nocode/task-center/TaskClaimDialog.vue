<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type {
  TaskRow,
  TaskClaimPreview,
  TaskClaimGroupCommand,
  TaskChecklistChoice as ChecklistChoice
} from '@/types/nocode/task-center'
import TaskChecklistChoice from './TaskChecklistChoice.vue'
import { checklistCommand, createChecklistAttempt } from '@/nocode/task-checklist'

const props = defineProps<{
  task: Pick<TaskRow, 'id' | 'rootId' | 'title'> & Partial<Pick<TaskRow, 'revision' | 'acceptorId' | 'acceptorName'>>
  planReadonly?: boolean
}>()
const emit = defineEmits<{ close: []; saved: [] }>()
const api = useNocodePlatform().taskCenter
const choice = ref<ChecklistChoice | 'LATER'>('WEEK')
const includePlan = computed(() => !props.planReadonly && choice.value !== 'LATER')
const busy = ref(false),
  error = ref(''),
  claimed = ref(false),
  uncertain = ref(false)
const claimBody = { id: props.task.id, expectedRevision: props.task.revision, requestKey: uuid() }
const group = computed(() => props.task.id === props.task.rootId)
const preview = ref<TaskClaimPreview>()
const remainingOnly = computed(() => preview.value?.remainingOnly === true)
const includedChildren = computed(() => preview.value?.items.filter(item => item.id !== preview.value?.rootId) || [])
const previewBusy = ref(false)
const claimUncertain = ref(false)
let groupBody: TaskClaimGroupCommand | undefined
async function loadPreview() {
  if (busy.value || previewBusy.value || claimUncertain.value || claimed.value) return
  previewBusy.value = true
  error.value = ''
  preview.value = undefined
  groupBody = undefined
  try {
    preview.value = await api.claimPreview(props.task.rootId, true)
    groupBody = {
      rootId: preview.value.rootId,
      expectedInstanceRevision: preview.value.instanceRevision,
      requestKey: uuid(),
      includeOpen: true
    }
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    previewBusy.value = false
  }
}
onMounted(() => {
  if (group.value) void loadPreview()
})
const attempt = createChecklistAttempt()
async function claim() {
  if (busy.value) return
  if (!claimed.value && group.value && !groupBody) {
    error.value = '任务信息尚未加载完成，请加载后再领取'
    return
  }
  busy.value = true
  error.value = ''
  try {
    if (!claimed.value) {
      if (group.value && groupBody) await api.claimGroup(groupBody)
      else {
        if (claimBody.expectedRevision == null) throw new Error('领取版本缺失，请刷新任务')
        await api.claim({ ...claimBody, expectedRevision: claimBody.expectedRevision })
      }
      claimed.value = true
      claimUncertain.value = false
      emit('saved')
    }
    if (includePlan.value) {
      await attempt.submit(api, async () => {
        const context = await api.checklistContext({ ids: [props.task.id], target: 'SELF' })
        return checklistCommand(
          context,
          [props.task.id],
          'SELF',
          choice.value === 'LATER' ? 'WEEK' : choice.value,
          'ADD'
        )
      })
      emit('saved')
    }
    message.success(
      remainingOnly.value
        ? includePlan.value
          ? '已领取剩余子任务，总任务已加入计划'
          : '已领取剩余子任务'
        : includePlan.value
          ? '已领取并加入计划，任务尚未开始'
          : '已领取，尚未加入计划'
    )
    emit('close')
  } catch (cause) {
    if (!claimed.value) {
      claimUncertain.value = !(cause && typeof cause === 'object' && 'businessCode' in cause)
      if (!claimUncertain.value && group.value) {
        groupBody = undefined
        preview.value = undefined
      }
    }
    uncertain.value = !!attempt.pending
    error.value =
      (claimed.value
        ? remainingOnly.value
          ? '子任务已领取，但总任务加入计划未完成：'
          : '已领取，但加入计划未完成：'
        : '') + errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <OsModalForm
    :open="true"
    :title="
      claimed
        ? remainingOnly
          ? '子任务已领取，继续安排计划'
          : '已领取，继续加入计划'
        : remainingOnly
          ? '领取剩余子任务'
          : group
            ? includedChildren.length
              ? '领取整个任务'
              : '领取任务'
            : '领取子任务'
    "
    :loading="busy"
    :width="560"
    :allow-switch-display="false"
    :ok-text="
      claimed
        ? !includePlan
          ? '完成领取，暂不加入'
          : '重试加入计划'
        : !includePlan
          ? remainingOnly
            ? '确认领取剩余子任务'
            : '仅领取'
          : '领取并加入计划'
    "
    :cancel-text="claimed ? '稍后处理' : '取消'"
    @ok="claim"
    @cancel="!busy && emit('close')"
  >
    <template #formItems>
      <div class="task-claim-dialog__summary">
        <div class="task-list__hint">
          {{ remainingOnly ? '总任务已由你负责' : claimed ? '已由你负责' : '领取后，你将负责' }}
        </div>
        <strong>{{ preview?.title || task.title }}</strong>
        <template v-if="group && preview && !claimed">
          <template v-if="includedChildren.length">
            <div class="task-claim-dialog__children-label">
              {{ remainingOnly ? '本次领取的子任务' : '一起领取的子任务' }}（{{ includedChildren.length }}）
            </div>
            <ul class="task-claim-dialog__children">
              <li v-for="item in includedChildren" :key="item.id">{{ item.title }}</li>
            </ul>
          </template>
          <p v-else class="task-list__hint task-claim-dialog__note">本次只领取这个任务，不会自动领取其他任务。</p>
        </template>
      </div>
      <template v-if="group && !claimed">
        <p v-if="previewBusy" class="task-list__hint">
          <a-spin size="small" />
          正在确认可领取的任务…
        </p>
        <a-button v-if="!previewBusy && !preview && !claimUncertain" :disabled="busy" @click="loadPreview">
          重新加载
        </a-button>
      </template>
      <p v-if="!claimed" class="task-list__hint">
        {{
          group
            ? '仅包含当前你有资格领取且未分配的任务，已分配给同事的任务保持不变。'
            : '只领取当前子任务，不影响其他任务的负责人。'
        }}
        {{
          remainingOnly ? '新领取的子任务不会自动开始，总任务当前进度保持不变。' : '领取后仍需点击“开始任务”才会执行。'
        }}
      </p>
      <a-alert
        v-if="claimed"
        type="success"
        :message="
          remainingOnly
            ? '子任务已归你负责；只会重试总任务加入计划，不会重复领取。'
            : '任务已经归你负责；只会重试加入计划，不会重复领取。'
        "
      />
      <template v-else>
        <p v-if="task.id === task.rootId && task.acceptorId && !remainingOnly" class="task-list__hint">
          本任务需要{{ task.acceptorName || '指定验收人' }}验收。你将成为负责人，处理完成后提交验收。
        </p>
      </template>
      <p v-if="planReadonly" class="task-list__hint">领取后可在“我的任务”中自行加入今日或本周计划。</p>
      <div v-else class="task-claim-dialog__plan">
        <div class="task-claim-dialog__plan-label">
          {{ remainingOnly ? '顺便将总任务加入我的计划' : '顺便加入我的计划' }}
        </div>
        <TaskChecklistChoice v-model="choice" :disabled="busy || uncertain" allow-later />
        <p v-if="group && includedChildren.length" class="task-list__hint">
          这里只为总任务安排计划，子任务可稍后单独安排。
        </p>
      </div>
      <p v-if="claimUncertain" class="task-list__hint">领取结果尚未确认，请重试，不会重复领取。</p>
      <p v-if="uncertain" class="task-list__hint">加入计划的结果尚未确认，请重试，不会重复添加。</p>
      <a-alert v-if="error" type="error" :message="error" />
    </template>
  </OsModalForm>
</template>
<style scoped>
.task-claim-dialog__summary {
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  background: var(--bg-layout, #f8fafc);
  overflow-wrap: anywhere;
}
.task-claim-dialog__summary > strong {
  display: block;
  margin-top: 4px;
  font-size: 16px;
}
.task-claim-dialog__children-label {
  margin-top: 12px;
}
.task-claim-dialog__children {
  max-height: 200px;
  overflow-y: auto;
  margin: 8px 0 0;
  padding-left: 20px;
}
.task-claim-dialog__children > li + li {
  margin-top: 4px;
}
.task-claim-dialog__note {
  margin: 8px 0 0;
}
.task-claim-dialog__plan {
  margin-top: 20px;
}
.task-claim-dialog__plan-label {
  margin-bottom: 8px;
  font-weight: 500;
}
</style>
