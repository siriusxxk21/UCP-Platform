<script setup lang="ts">
import { computed, ref } from 'vue'
import { EditOutlined, SettingOutlined } from '@ant-design/icons-vue'
import UserSelector from '@/components/UserSelector/index.vue'
import type { User } from '@/types/system/user'
import type { TaskAssignmentMode, TaskMember, TaskNodeInput } from '@/types/nocode/task-center'

const node = defineModel<Pick<TaskNodeInput, 'assigneeId' | 'assignmentMode' | 'candidateUserIds' | 'acceptorId'>>({
  required: true
})
const props = withDefaults(
  defineProps<{
    members: TaskMember[]
    readonly?: boolean
    compact?: boolean
    tableEditing?: boolean
    quietSupplement?: boolean
    inlineCandidates?: boolean
    allowFollow?: boolean
    rootAssigneeId?: TaskNodeInput['assigneeId']
    delegateOnly?: boolean
    assignedOnly?: boolean
    extraOptions?: Array<{ value: string; label: string }>
    overrideValue?: string
  }>(),
  { tableEditing: true, inlineCandidates: true }
)
const emit = defineEmits<{ select: [value: string] }>()
const selectorOpen = ref(false)
const choosingCandidates = ref(false)
const selected = ref<User[]>([])
const error = ref('')
const mode = computed(() => node.value.assignmentMode || 'ASSIGNED')
const candidateSummary = computed(() =>
  node.value.candidateUserIds?.length
    ? `限 ${node.value.candidateUserIds.length} 人领取，点击设置领取范围`
    : '所有人可领取，点击限定领取人员'
)
const assigneeName = computed(
  () =>
    props.members.find(
      member =>
        String(member.id) ===
        String(node.value.assigneeId ?? (mode.value === 'FOLLOW_ROOT' ? props.rootAssigneeId : null))
    )?.name
)
const options = computed(() =>
  props.assignedOnly
    ? [{ value: 'ASSIGNED', label: '指定负责人' }]
    : [
        ...(props.allowFollow && !props.delegateOnly ? [{ value: 'FOLLOW_ROOT', label: '随总任务负责人' }] : []),
        ...(!props.delegateOnly ? [{ value: 'UNASSIGNED', label: '暂不分配' }] : []),
        { value: 'ASSIGNED', label: '指定负责人' },
        { value: 'OPEN', label: '开放领取' },
        ...(props.extraOptions || [])
      ]
)
// 下拉只选择分配方式；人员复用公共选择器，取消选择不改动原分配。
const tableOptions = computed(() =>
  options.value.map(option => ({
    ...option,
    selectedLabel:
      option.value === 'ASSIGNED' ? assigneeName.value || selected.value[0]?.nickname || option.label : option.label
  }))
)
function changeTableValue(value: unknown) {
  if (props.readonly || (props.assignedOnly && value !== 'ASSIGNED')) return
  if (value !== 'ASSIGNED') emit('select', String(value))
  if (props.extraOptions?.some(option => option.value === value)) return
  if (value === 'ASSIGNED') openSelector()
  else changeMode(value)
}
const followSummary = computed(() =>
  assigneeName.value
    ? node.value.assigneeId != null
      ? `当前由 ${assigneeName.value} 承接`
      : `总任务负责人：${assigneeName.value}，承接范围以服务端确认结果为准`
    : '总任务被领取或分配后承接；单独分工的分支不覆盖。'
)
function changeMode(value: unknown) {
  if (props.readonly || (props.assignedOnly && value !== 'ASSIGNED')) return
  const next = String(value) as TaskAssignmentMode
  node.value.assignmentMode = next
  error.value = ''
  selected.value = []
  if (next !== 'ASSIGNED') node.value.assigneeId = null
  if (next !== 'OPEN') node.value.candidateUserIds = []
}
function openSelector() {
  if (props.readonly) return
  error.value = ''
  choosingCandidates.value = false
  selected.value =
    node.value.assigneeId == null
      ? []
      : [{ id: String(node.value.assigneeId), username: '', nickname: assigneeName.value || '已选负责人' }]
  selectorOpen.value = true
}
function openCandidates() {
  if (props.readonly) return
  choosingCandidates.value = true
  selected.value = (node.value.candidateUserIds || []).map(id => ({
    id: String(id),
    username: '',
    nickname: props.members.find(member => String(member.id) === String(id))?.name || '已选成员'
  }))
  selectorOpen.value = true
}
function confirm(users: User[]) {
  if (props.readonly) {
    selectorOpen.value = false
    return
  }
  if (choosingCandidates.value) {
    node.value.candidateUserIds = users.map(user => user.id)
    selectorOpen.value = false
    return
  }
  if (!users[0]) return
  if (node.value.acceptorId != null && String(users[0].id) === String(node.value.acceptorId)) {
    error.value = '负责人和验收人不能是同一人，请选择其他负责人，或先更换验收人。'
    selectorOpen.value = false
    return
  }
  node.value.assignmentMode = 'ASSIGNED'
  node.value.candidateUserIds = []
  // 沿用公共选择器的雪花 ID 字符串，不能转为 Number 丢失精度。
  node.value.assigneeId = users[0].id
  emit('select', 'ASSIGNED')
  selected.value = users
  selectorOpen.value = false
}
</script>
<template>
  <div class="task-assignment-fields">
    <div
      v-if="tableEditing"
      class="task-assignment-fields__main"
      :title="mode === 'FOLLOW_ROOT' ? followSummary : undefined"
    >
      <a-select
        :value="overrideValue || mode"
        :disabled="readonly"
        aria-label="负责人安排"
        placeholder="选择负责人"
        option-label-prop="selectedLabel"
        :options="tableOptions"
        @select="changeTableValue"
      />
      <a-tooltip v-if="inlineCandidates && !overrideValue && mode === 'OPEN'" :title="candidateSummary">
        <a-button
          class="task-assignment-fields__candidates"
          :class="{ 'task-assignment-fields__candidates--limited': node.candidateUserIds?.length }"
          type="text"
          size="small"
          :disabled="readonly"
          aria-label="限定领取人员"
          @click.stop="openCandidates"
        >
          <SettingOutlined />
        </a-button>
      </a-tooltip>
    </div>
    <a-select
      v-else
      :value="mode"
      :disabled="readonly"
      aria-label="负责人安排"
      style="min-width: 130px; width: 100%"
      :options="options"
      @change="changeMode"
    />
    <a-button v-if="!tableEditing && mode === 'ASSIGNED'" :disabled="readonly" @click="openSelector">
      {{ assigneeName || selected[0]?.nickname || (node.assigneeId ? '更换负责人' : '选择负责人') }}
    </a-button>
    <p v-if="!tableEditing && mode === 'FOLLOW_ROOT'" class="task-list__hint" :title="followSummary">
      {{ compact ? assigneeName || '待总任务确定负责人' : followSummary }}
    </p>
    <p v-if="!compact && mode === 'OPEN'" class="task-list__hint">
      {{
        node.candidateUserIds?.length
          ? `已限制为 ${node.candidateUserIds.length} 位候选成员可领取`
          : '所有人可领取。领取后需单独开始任务。'
      }}
    </p>
    <p v-if="!compact && mode === 'UNASSIGNED'" class="task-list__hint">先加入任务池，之后由管理者分配。</p>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <div
      v-if="tableEditing && !inlineCandidates"
      class="task-assignment-fields__supplement"
      :class="{ 'task-assignment-fields__supplement--quiet': quietSupplement }"
    >
      <a-button v-if="mode === 'OPEN' && !readonly" type="link" size="small" @click="openCandidates">
        {{ node.candidateUserIds?.length ? `限 ${node.candidateUserIds.length} 人领取` : '限定领取人员' }}
        <EditOutlined v-if="quietSupplement" />
      </a-button>
      <span v-else-if="mode === 'FOLLOW_ROOT' && assigneeName" class="task-list__hint" :title="followSummary">
        {{ assigneeName }}
      </span>
    </div>
    <a-space v-if="!tableEditing && !compact && mode === 'OPEN' && !readonly">
      <a-button type="link" @click="openCandidates">限定可领取人员（可选）</a-button>
      <a-button v-if="node.candidateUserIds?.length" type="link" @click="node.candidateUserIds = []">
        恢复所有人可领取
      </a-button>
    </a-space>
    <UserSelector
      v-if="selectorOpen"
      v-model:visible="selectorOpen"
      :title="choosingCandidates ? '选择可领取人员' : '选择负责人'"
      :multiple="choosingCandidates"
      :show-multiple-toggle="false"
      :selected-users="selected"
      :candidate-user-ids="members.map(member => member.id)"
      :enabled-only="true"
      @confirm="confirm"
    />
  </div>
</template>
<style scoped>
.task-assignment-fields {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-xs);
}
.task-assignment-fields__supplement {
  min-height: var(--control-height-sm);
  width: 100%;
}
.task-assignment-fields__main {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  width: 100%;
  min-width: 0;
}
.task-assignment-fields__main > .ant-select {
  flex: 1;
  min-width: 0;
}
.task-assignment-fields__candidates {
  flex: 0 0 var(--control-height-sm);
  width: var(--control-height-sm);
  color: var(--text-secondary);
}
.task-assignment-fields__candidates--limited {
  color: var(--brand);
  background: var(--brand-light);
}
.task-assignment-fields__supplement :deep(.ant-btn) {
  padding-inline: 0;
}
.task-assignment-fields__supplement--quiet :deep(.ant-btn) {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
.task-assignment-fields__supplement--quiet :deep(.ant-btn:hover),
.task-assignment-fields__supplement--quiet :deep(.ant-btn:focus-visible) {
  color: var(--brand);
}
</style>
