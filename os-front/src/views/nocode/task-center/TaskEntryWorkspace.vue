<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { taskDisplayState, taskTime } from '@/nocode/task-center'
import { taskWorkRuleSummary } from '@/nocode/task-work-rule'
import { taskEntryAllowsAll } from '@/nocode/task-entry-scope'
import { FileTextOutlined, ArrowLeftOutlined, RightOutlined, DownOutlined } from '@ant-design/icons-vue'
import type { Aggregate } from '@/types/nocode/runtime'
import type { TaskRow, TaskFormContext } from '@/types/nocode/task-center'
import type {
  TaskWorkEntry,
  TaskWorkItem,
  TaskWorkFormTarget,
  TaskWorkMaterial
} from '@/types/nocode/task-work-entries'
import TaskEntryRecordEditor from './TaskEntryRecordEditor.vue'
import TaskWorkHistory from './TaskWorkHistory.vue'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import { useUserStore } from '@/stores/user'
import { isDocumentRejection } from '@/nocode/document-save'
import { loadTaskLink, storeTaskLink, taskLinkKey, type PendingTaskLink } from '@/nocode/task-link-recovery'
const props = defineProps<{
  task: TaskRow
  initialEntryKey?: string
  initialContributionId?: string
  readonlyReason?: string
  employeeView?: boolean
}>()
const emit = defineEmits<{
  updated: []
  resume: [location: { taskId: string; entryKey: string; contributionId: string }]
}>()
const api = useNocodePlatform().taskCenter
const user = useUserStore()
const linkIdentity = computed(() => taskLinkKey(String(user.userInfo?.id || ''), props.task.id))
const pendingLink = ref<PendingTaskLink | null>(null)
const linking = ref(false)
let disposed = false,
  identityGeneration = 0
watch(
  linkIdentity,
  key => {
    identityGeneration++
    pendingLink.value = loadTaskLink(key, props.task.id)
  },
  { immediate: true, flush: 'sync' }
)
const { confirm } = useTaskConfirmation()
const entries = ref<TaskWorkEntry[]>([]),
  active = ref(''),
  all = ref(false),
  onlyMine = ref(false),
  error = ref(''),
  entriesLoading = ref(false),
  selectionLoading = ref(false)
const materialDefinitions = ref<Record<string, TaskFormContext>>({})
const materialPreview = ref<{ target: TaskWorkFormTarget; snapshot: Aggregate; name: string }>()
const definition = ref<TaskFormContext>(),
  target = ref<TaskWorkFormTarget>(),
  readOnly = ref(false),
  sources = ref<TaskWorkItem>(),
  materials = ref<TaskWorkMaterial[]>(),
  materialsOpen = ref(false)
const editor = ref<InstanceType<typeof TaskEntryRecordEditor>>()
const historyTarget = ref<{ entryKey?: string; recordId?: string }>()
const searchRestricted = computed(() => all.value && selected.value?.config.readableFieldIds != null)
const selected = computed(() => entries.value.find(e => e.config.key === active.value))
const executionPaused = computed(() => taskDisplayState(props.task) === 'PAUSED')
const canWrite = computed(
  () =>
    !props.readonlyReason && !executionPaused.value && !selected.value?.unavailableReason && !!selected.value?.canWrite
)
const canDelete = computed(
  () =>
    !props.readonlyReason && !executionPaused.value && !selected.value?.unavailableReason && !!selected.value?.canDelete
)
const canLink = computed(
  () =>
    !props.readonlyReason &&
    !executionPaused.value &&
    !selected.value?.unavailableReason &&
    !!(selected.value?.canLink ?? selected.value?.canWrite)
)
const unified = computed(() => !!props.task.dataPolicy)
const workspaceOpen = ref(false)
const modalSize = () =>
  Math.max(1, window.innerWidth < 768 ? window.innerWidth - 24 : Math.round(window.innerWidth * 0.92))
const modalWidth = ref(modalSize())
const resizeModal = () => (modalWidth.value = modalSize())
onMounted(() => window.addEventListener('resize', resizeModal))
onBeforeUnmount(() => window.removeEventListener('resize', resizeModal))
const dataLabel = '业务数据'
// 数量计工时可能有小数；不能套用仅接受整数分钟的模板参考时长校验。
const minutesLabel = (minutes: number) => `${Number(minutes.toFixed(2))} 分钟`
const entryTitle = computed(() =>
  selected.value?.config.name === dataLabel
    ? definition.value?.model.object.objectName || dataLabel
    : selected.value?.config.name || dataLabel
)
const canUseAll = computed(() => !!selected.value && taskEntryAllowsAll(selected.value))
const dataScope = computed(() => (all.value ? 'ALL' : onlyMine.value ? 'NODE' : 'GROUP'))
const createBlockedReason = computed(() => {
  if (props.readonlyReason) return props.readonlyReason
  if (executionPaused.value) return props.task.pauseReason || '任务已暂停，恢复后可继续填写'
  if (!selected.value?.canWrite) {
    if (props.task.status === 'PENDING_ACCEPTANCE') return '任务待验收，数据仅供查看；退回修改后可继续填写'
    if (['COMPLETED', 'CANCELLED'].includes(props.task.status)) return '任务已结束，数据仅供查看'
    if (!props.task.canExecute) return '仅当前任务的负责人可以填写'
    if (props.task.status === 'PENDING') return '开始当前任务后可填写'
    return '当前业务资源只读'
  }
  return definition.value?.model.permissions.actions.includes('CREATE') ? '' : '当前业务资源不允许新增'
})
const deleting = ref(false)
const deleteKeys = new Map<string, string>()
const columns = computed(() => [
  ...(definition.value?.model.object.fields || [])
    .filter(f => definition.value?.model.permissions.readFields.includes(f.id!))
    .slice(0, 6)
    .map(f => ({ key: f.id!, title: f.name, width: 160 })),
  { key: 'status', title: '数据状态', width: 120 },
  { key: 'actions', title: '操作', width: 190, fixed: 'right' as const }
])
const table = useOsTablePage<TaskWorkItem, { search: string }>({
  defaultQuery: () => ({ search: '' }),
  immediate: false,
  clearDataOnError: true,
  correctOutOfRange: true,
  fetchFn: params =>
    active.value && definition.value
      ? api.entryPage({
          taskId: props.task.id,
          entryKey: active.value,
          all: all.value,
          onlyMine: onlyMine.value,
          pageNo: params.pageNum,
          pageSize: params.pageSize,
          search: params.search
        })
      : Promise.resolve({ list: [], total: 0 }),
  onError: e => {
    error.value = errorMessage(e)
  }
})
const { tableData, loading, pagination, queryForm, fetchData, handleQuery, handleTableChange } = table
let initialHandled = false
let pendingEntry = ''
let generation = 0,
  selectionGeneration = 0,
  openGeneration = 0
onBeforeUnmount(() => {
  disposed = true
  generation++
  selectionGeneration++
  openGeneration++
})
async function load() {
  const token = ++generation
  selectionGeneration++
  openGeneration++
  historyTarget.value = undefined
  entriesLoading.value = true
  error.value = ''
  target.value = undefined
  tableData.value = []
  definition.value = undefined
  try {
    const data = await api.entryList(props.task.id)
    if (token !== generation) return
    entries.value = data
    if (pendingEntry) {
      const desiredEntry = pendingEntry
      pendingEntry = ''
      if (!data.some(entry => entry.config.key === desiredEntry)) {
        active.value = ''
        throw new Error('此数据入口当前不可访问，请联系任务负责人。')
      }
      active.value = desiredEntry
    } else if (!initialHandled && props.initialEntryKey) {
      if (!data.some(e => e.config.key === props.initialEntryKey)) {
        active.value = ''
        throw new Error('此申请对应的业务关联项已不可访问，请联系任务负责人。')
      }
      active.value = props.initialEntryKey
      workspaceOpen.value = true
    } else if (!data.some(e => e.config.key === active.value)) active.value = ''
    if (data.find(entry => entry.config.key === active.value)?.unavailableReason) {
      workspaceOpen.value = false
      active.value = ''
    }
    if (workspaceOpen.value && active.value) await select(active.value, false, true)
    if (token !== generation) return
    if (
      !initialHandled &&
      props.initialContributionId &&
      props.initialEntryKey &&
      active.value === props.initialEntryKey &&
      definition.value
    ) {
      target.value = {
        taskId: props.task.id,
        entryKey: props.initialEntryKey,
        contributionId: props.initialContributionId,
        recordId: null
      }
      readOnly.value = !canWrite.value
      initialHandled = true
    }
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  } finally {
    if (token === generation) entriesLoading.value = false
  }
}
watch(
  () => [props.task.id, props.initialEntryKey, props.initialContributionId],
  () => {
    initialHandled = false
    workspaceOpen.value = false
    materialPreview.value = undefined
    materialsOpen.value = false
    active.value = ''
    entries.value = []
    void load()
  },
  { immediate: true }
)
watch(
  () => [props.task.revision, props.task.status, props.task.pausedByTaskId, props.task.canExecute],
  () => {
    if (!target.value) void load()
  }
)
function changeScope() {
  if (searchRestricted.value) queryForm.search = ''
  handleQuery()
}
async function showHistory(record?: TaskWorkItem) {
  openGeneration++
  if (target.value && !(await requestClose())) return
  target.value = undefined
  historyTarget.value = record
    ? { entryKey: active.value, recordId: record.record?.id || record.id }
    : { entryKey: workspaceOpen.value ? active.value : undefined }
}
async function selectScope(key: string | number) {
  if (linking.value) return
  if (!['NODE', 'GROUP', 'ALL'].includes(String(key))) return
  if (key === 'ALL' && !canUseAll.value) return
  openGeneration++
  if (target.value && !(await requestClose())) return
  target.value = undefined
  all.value = key === 'ALL'
  onlyMine.value = key === 'NODE'
  changeScope()
}
async function select(key: string, openForm = false, preserveScope = false) {
  if (linking.value && !preserveScope) return
  if (entries.value.find(entry => entry.config.key === key)?.unavailableReason) return
  openGeneration++
  if (target.value && !(await requestClose())) return
  target.value = undefined
  const token = ++selectionGeneration,
    taskId = props.task.id
  tableData.value = []
  const keepScope = preserveScope && active.value === key
  active.value = key
  workspaceOpen.value = !!key
  if (!keepScope || (all.value && !canUseAll.value)) {
    all.value = false
    onlyMine.value = false
  }
  definition.value = undefined
  // 先提交空查询使表格旧请求失效，避免切换入口期间回填另一入口的数据。
  void fetchData()
  error.value = ''
  selectionLoading.value = false
  if (!key) return
  selectionLoading.value = true
  try {
    const response = await api.entryForm({ taskId, entryKey: key, recordId: null, contributionId: null })
    if (token !== selectionGeneration || taskId !== props.task.id || key !== active.value) return
    definition.value = response
    queryForm.search = ''
    pagination.current = 1
    await fetchData()
    if (token !== selectionGeneration || taskId !== props.task.id || key !== active.value) return
    if (openForm && !error.value) {
      if (pagination.total === 0 && !createBlockedReason.value) await open()
      else if (pagination.total === 1 && tableData.value[0]?.record) await open(tableData.value[0], !canWrite.value)
    }
  } catch (e) {
    if (token === selectionGeneration && taskId === props.task.id) error.value = errorMessage(e)
  } finally {
    if (token === selectionGeneration) selectionLoading.value = false
  }
}
async function refresh() {
  if (linking.value) return
  if (target.value && !(await requestClose())) return
  await load()
}
async function open(item?: TaskWorkItem, readonly = false) {
  if (disposed || linking.value) return
  const taskId = props.task.id,
    entryKey = active.value,
    token = selectionGeneration,
    opening = ++openGeneration,
    identity = identityGeneration
  // 同一入口内打开另一条记录也必须使旧定位请求失效，不能覆盖后来正在填写的表单。
  const current = () =>
    !disposed &&
    opening === openGeneration &&
    identity === identityGeneration &&
    token === selectionGeneration &&
    taskId === props.task.id &&
    entryKey === active.value
  if (target.value && !(await editor.value?.requestClose())) return
  if (!current()) return
  if (!readonly && canWrite.value && item?.requestId && ['REJECTED', 'CANCELED'].includes(item.status)) {
    try {
      const location = await api.entryHandlingLocation(item.requestId)
      if (!current() || !canWrite.value) return
      if (!location) throw new Error('原办理申请当前不可访问，请重新加载或联系任务负责人。')
      if (location.taskId !== taskId || location.entryKey !== entryKey) {
        emit('resume', location)
        return
      }
      target.value = { ...location, recordId: item.record?.id || null }
      readOnly.value = false
      return
    } catch (e) {
      if (current()) error.value = errorMessage(e)
      return
    }
  }
  target.value = {
    taskId: props.task.id,
    entryKey: active.value,
    recordId: item?.record?.id || null,
    contributionId: item && !all.value ? item.id : null
  }
  readOnly.value =
    readonly || !canWrite.value || (!!item?.record && !item.record.permissions?.actions.includes('UPDATE'))
}
async function close() {
  openGeneration++
  if ((await editor.value?.requestClose()) !== false) target.value = undefined
}
async function closeWorkspace() {
  if (deleting.value || !(await requestClose())) return
  selectionGeneration++
  target.value = undefined
  workspaceOpen.value = false
}
async function requestClose() {
  if (linking.value) return false
  openGeneration++
  return !target.value || (await editor.value?.requestClose()) !== false
}
async function prepareAction() {
  if (deleting.value || !(await requestClose())) return false
  target.value = undefined
  workspaceOpen.value = false
  return true
}
async function focusEntry(key: string) {
  // 刚切入业务页时入口目录尚未返回，保留要处理的缺项，避免默认入口覆盖定位。
  if (entriesLoading.value && !entries.value.length) {
    pendingEntry = key
    workspaceOpen.value = true
    return
  }
  if (!entries.value.some(entry => entry.config.key === key)) {
    error.value = '此数据入口当前不可访问，请重新加载或联系任务负责人。'
    return
  }
  await select(key)
}
defineExpose({ requestClose, prepareAction, select: focusEntry })
async function saved() {
  target.value = undefined
  message.success('业务数据已保存，工时按规则更新')
  await load()
  emit('updated')
}
async function link(item: TaskWorkItem) {
  if (disposed || !canLink.value || !item.record?.id || linking.value || pendingLink.value) return
  const command = { taskId: props.task.id, entryKey: active.value, recordId: item.record.id, requestKey: uuid() }
  const identity = linkIdentity.value
  const identityToken = identityGeneration
  linking.value = true
  try {
    if (
      !(await confirm(
        `将此记录关联为${unified.value ? '本组任务数据' : '本任务数据'}？`,
        '保留原业务身份并记录关联操作；仅关联不计标准工时。',
        '关联记录'
      )) ||
      disposed ||
      identityToken !== identityGeneration ||
      identity !== linkIdentity.value ||
      !canLink.value
    )
      return
    await submitLink(command, identity, false, identityToken)
  } finally {
    linking.value = false
  }
}
async function retryLink() {
  if (disposed || !pendingLink.value || linking.value) return
  linking.value = true
  try {
    await submitLink(pendingLink.value, linkIdentity.value, true, identityGeneration)
  } finally {
    linking.value = false
  }
}
async function submitLink(command: PendingTaskLink, identity: string, uncertain: boolean, identityToken: number) {
  const current = () => !disposed && identityToken === identityGeneration && identity === linkIdentity.value
  if (!current()) return
  storeTaskLink(identity, command)
  const clear = () => {
    if (loadTaskLink(identity, command.taskId)?.requestKey === command.requestKey) storeTaskLink(identity, null)
    if (identity === linkIdentity.value) pendingLink.value = null
  }
  let applied = false
  try {
    // 重试先找原回执，避免已提交的关联在任务结束后只能得到拒绝。
    const receipt = uncertain ? await api.entryReceipt({ ...command, contributionId: null }, command.requestKey) : null
    // 等待期间可能切换账号或离开详情；保留原账号的恢复记录，不能借用新会话继续提交。
    if (!current()) return
    if (!receipt) await api.entryLink(command.taskId, command.entryKey, command.recordId, command.requestKey)
    if (!current()) return
    applied = true
  } catch (e) {
    if (!current()) return
    if (!uncertain && isDocumentRejection(e)) clear()
    else {
      try {
        applied = !!(await api.entryReceipt(
          { taskId: command.taskId, entryKey: command.entryKey, recordId: command.recordId, contributionId: null },
          command.requestKey
        ))
      } catch {
        /* 无法确认回执时保留原请求，不生成新键。 */
      }
      if (!current()) return
      if (!applied && identity === linkIdentity.value) pendingLink.value = command
    }
    if (!applied && identity === linkIdentity.value) error.value = errorMessage(e)
  }
  if (!applied) return
  clear()
  if (!current()) return
  message.success('已关联到任务')
  await load()
  if (current()) emit('updated')
}
async function remove(item: TaskWorkItem) {
  const record = item.record
  if (
    deleting.value ||
    linking.value ||
    !canDelete.value ||
    !record?.id ||
    record.revision == null ||
    !record.permissions?.actions.includes('DELETE')
  )
    return
  if (
    !(await confirm(
      '删除这条业务记录？',
      '这是删除实际业务数据，不只是解除任务关联；本组其他任务及业务列表也将无法再使用它。',
      '删除记录'
    ))
  )
    return
  const taskId = props.task.id,
    entryKey = active.value,
    key = `${taskId}:${entryKey}:${record.id}:${record.revision}`
  if (!deleteKeys.has(key)) deleteKeys.set(key, uuid())
  deleting.value = true
  error.value = ''
  try {
    await api.entryDelete({
      taskId,
      entryKey,
      recordId: record.id,
      expectedRevision: record.revision,
      requestKey: deleteKeys.get(key)!
    })
    deleteKeys.delete(key)
    message.success('业务记录已删除')
    await load()
    emit('updated')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    deleting.value = false
  }
}
function openMaterial(material: TaskWorkMaterial, id: string) {
  const submission = material.submissions.find(s => s.contributionId === id)
  if (!submission) return
  materialPreview.value = {
    target: {
      taskId: props.task.id,
      entryKey: material.entryKey,
      recordId: submission.record.record.id,
      contributionId: null
    },
    snapshot: submission.record,
    name: material.name
  }
}
async function showMaterials() {
  try {
    const taskId = props.task.id,
      result = await api.entryMaterials(taskId)
    const definitions = await Promise.all(
      result.map(m => api.entryForm({ taskId, entryKey: m.entryKey, recordId: null, contributionId: null }))
    )
    if (taskId !== props.task.id) return
    materialDefinitions.value = Object.fromEntries(result.map((m, i) => [m.entryKey, definitions[i]]))
    materials.value = result
    materialsOpen.value = true
  } catch (e) {
    error.value = errorMessage(e)
  }
}
// 这里表达业务数据的保存状态，不表示任务已完成或已通过任务验收。
const dataStates: Record<string, { label: string; color: string; hint: string }> = {
  EFFECTIVE: { label: '已保存', color: 'green', hint: '数据已保存到业务列表，不代表任务已完成。' },
  SUBMITTED: { label: '审批中', color: 'blue', hint: '本次提交尚未写入业务数据，审批通过后更新。' },
  REJECTED: { label: '审批未通过', color: 'orange', hint: '本次提交未通过审批，可编辑后重新提交。' },
  CANCELED: { label: '已撤回', color: 'default', hint: '本次提交已撤回，未写入业务数据。' },
  APPLY_FAILED: { label: '保存失败', color: 'red', hint: '审批已通过，但数据写入失败。请查看详情处理。' }
}
const dataState = (status: string) =>
  dataStates[status] || { label: '状态待确认', color: 'default', hint: '请刷新数据或查看详情确认。' }
function canEdit(item: TaskWorkItem) {
  return (
    canWrite.value &&
    ['EFFECTIVE', 'REJECTED', 'CANCELED'].includes(item.status) &&
    (!item.record || item.record.permissions?.actions.includes('UPDATE'))
  )
}
</script>
<template>
  <section class="task-entry-workspace" aria-label="业务数据工作区">
    <div class="task-entry-workspace__header">
      <div>
        <h3>业务数据</h3>
        <p class="task-list__hint">选择业务卡片办理；标准工时按有效业务操作计算，与实际起止时间分开。</p>
      </div>
      <a-space>
        <a-button v-if="entries.length" @click="showHistory()">数据操作记录</a-button>
        <a-button :loading="entriesLoading" @click="refresh">刷新</a-button>
      </a-space>
    </div>
    <a-alert v-if="error && !workspaceOpen" type="error" show-icon :message="error" />
    <p v-if="entriesLoading && !entries.length" role="status" class="task-list__hint">正在加载业务关联项…</p>
    <a-empty v-else-if="!entries.length && !error" description="当前任务暂无可访问的业务关联项" />
    <div v-else-if="entries.length" class="task-business-cards">
      <button
        v-for="entry in entries"
        :key="entry.config.key"
        type="button"
        class="task-business-card"
        :disabled="!!entry.unavailableReason"
        :aria-label="`打开${entry.config.name}`"
        @click="select(entry.config.key, true)"
      >
        <span class="task-business-card__heading">
          <FileTextOutlined class="task-business-card__icon" />
          <strong>{{ entry.config.name }}</strong>
          <span v-if="entry.config.required" class="task-business-card__required">必办</span>
        </span>
        <span class="task-business-card__rule">{{ taskWorkRuleSummary(entry.config.workRule) }}</span>
        <span class="task-business-card__scope">
          {{ taskEntryAllowsAll(entry) ? '授权范围内业务数据' : unified ? '本组任务数据' : '任务关联数据' }}
        </span>
        <span v-if="entry.workSummary && entry.config.workRule" class="task-business-card__summary">
          <span>
            我的已计工时
            <strong>{{ minutesLabel(entry.workSummary.myMinutes) }}</strong>
          </span>
          <span>{{ entry.workSummary.myRecordCount }} 条计量记录</span>
        </span>
        <span v-if="entry.workSummary?.totalMinutes != null && entry.config.workRule" class="task-business-card__scope">
          当前节点合计 {{ minutesLabel(entry.workSummary.totalMinutes) }}
        </span>
        <span class="task-business-card__footer">
          <span v-if="entry.unavailableReason" class="task-business-card__unavailable">
            {{ entry.unavailableReason }}
          </span>
          <template v-else>
            <span>{{ !readonlyReason && !executionPaused && entry.canWrite ? '进入办理' : '查看数据' }}</span>
            <RightOutlined />
          </template>
        </span>
      </button>
    </div>
    <OsModalForm
      :open="workspaceOpen"
      :title="`${entryTitle}${target ? ` · ${readOnly ? '查看' : target.recordId || target.contributionId ? '编辑' : '新增'}` : ''}`"
      :width="modalWidth"
      :show-footer="false"
      :wrap-form="false"
      display-mode="modal"
      :allow-switch-display="false"
      :resizable="false"
      :mask-closable="false"
      @cancel="closeWorkspace"
    >
      <template #formItems>
        <div class="task-entry-modal">
          <a-alert
            v-if="pendingLink"
            type="warning"
            show-icon
            message="关联结果尚未确认，请重试原关联后再关联其他数据。"
          />
          <a-button v-if="pendingLink" :loading="linking" @click="retryLink">重试原关联</a-button>
          <a-alert v-if="error" type="error" show-icon :message="error" />
          <a-button v-if="error" :loading="entriesLoading" @click="refresh">重新加载数据</a-button>
          <div v-if="target" class="task-entry-editor-toolbar">
            <a-button @click="close">
              <ArrowLeftOutlined />
              返回数据列表
            </a-button>
            <span class="task-list__hint">{{ taskWorkRuleSummary(selected?.config.workRule) }}</span>
          </div>
          <TaskEntryRecordEditor
            v-if="target"
            ref="editor"
            :key="JSON.stringify(target)"
            :target="target"
            :readonly="readOnly || executionPaused || !!readonlyReason"
            @saved="saved"
            @cancel="close"
          />
          <div v-else-if="selectionLoading" class="task-entry-opening" role="status" aria-live="polite">
            <a-spin />
            <span>正在打开业务数据…</span>
          </div>
          <template v-else>
            <a-alert v-if="searchRestricted" type="info" message="此入口限制了可读字段，请切换任务数据范围后搜索。" />
            <OsTablePage
              class="task-entry-table"
              :columns="columns"
              :data-source="tableData"
              :loading="loading || selectionLoading"
              row-key="id"
              :pagination="pagination"
              :show-advanced-search="false"
              :show-batch-bar="false"
              :scroll="{ x: 'max-content' }"
              :column-settings-key="`task-feedback:${selected?.binding.resource.resourceId}:${active}`"
              dynamic-search-display-mode="drawer"
              @change="handleTableChange"
              @refresh="fetchData"
            >
              <template #search>
                <a-form layout="inline" class="task-entry-filters" @submit.prevent>
                  <a-form-item v-if="entries.length > 1" label="业务关联项">
                    <a-select
                      class="task-entry-control"
                      :value="active"
                      :options="entries.map(entry => ({ value: entry.config.key, label: entry.config.name }))"
                      :aria-label="`选择${dataLabel}表单`"
                      @change="(key: string | number) => select(String(key))"
                    />
                  </a-form-item>
                  <a-form-item label="数据范围">
                    <a-radio-group
                      :value="dataScope"
                      aria-label="数据范围"
                      @change="(event: { target: { value: string } }) => selectScope(event.target.value)"
                    >
                      <a-radio-button value="NODE">本节点数据</a-radio-button>
                      <a-radio-button value="GROUP">{{ unified ? '本组任务数据' : '任务关联数据' }}</a-radio-button>
                      <a-radio-button v-if="canUseAll" value="ALL">
                        {{ unified ? '全部业务数据' : '全部有权限数据' }}
                      </a-radio-button>
                    </a-radio-group>
                  </a-form-item>
                  <a-form-item label="关键词">
                    <a-input-search
                      v-model:value="queryForm.search"
                      class="task-entry-control"
                      :placeholder="`搜索${dataLabel}`"
                      :disabled="searchRestricted"
                      allow-clear
                      @search="handleQuery"
                    />
                  </a-form-item>
                </a-form>
              </template>
              <template #title>
                <div class="task-entry-heading">
                  <span>{{ entryTitle }}</span>
                  <a-tag v-if="!unified">
                    {{
                      selected?.config.dataMode === 'INDEPENDENT'
                        ? '本节点独立数据'
                        : selected?.config.dataMode === 'SOURCE_SHARED'
                          ? '指定来源共享'
                          : '上级入口共享数据'
                    }}
                  </a-tag>
                  <a-tag v-if="!unified && selected?.inherited">沿用上级入口</a-tag>
                  <a-tag v-if="selected?.config.required" color="orange">完成前需有{{ dataLabel }}</a-tag>
                </div>
              </template>
              <template #actions>
                <a-button @click="showHistory()">数据操作记录</a-button>
                <a-button v-if="entries.some(e => e.submitted)" @click="showMaterials">查看交付材料</a-button>
                <span v-if="!selectionLoading && createBlockedReason" class="task-list__hint">
                  {{ createBlockedReason }}
                </span>
                <a-button :loading="loading || selectionLoading" @click="refresh">刷新数据</a-button>
                <a-button
                  type="primary"
                  :disabled="selectionLoading || !!createBlockedReason"
                  :title="createBlockedReason || undefined"
                  @click="open()"
                >
                  新增业务数据
                </a-button>
              </template>
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'actions'">
                  <div class="task-entry-row-actions">
                    <a @click="open(record, true)">查看</a>
                    <a v-if="canEdit(record)" @click="open(record)">
                      {{ ['REJECTED', 'CANCELED'].includes(record.status) ? '编辑重提' : '编辑' }}
                    </a>
                    <a-dropdown
                      v-if="record.record?.id || record.sources.length"
                      :trigger="['click']"
                      placement="bottomRight"
                    >
                      <a-button type="link" class="task-entry-more" aria-label="更多数据操作" aria-haspopup="menu">
                        更多
                        <DownOutlined />
                      </a-button>
                      <template #overlay>
                        <a-menu>
                          <a-menu-item v-if="record.record?.id" key="history" @click="showHistory(record)">
                            数据操作记录
                          </a-menu-item>
                          <a-menu-item v-if="record.sources.length" key="sources" @click="sources = record">
                            任务来源
                          </a-menu-item>
                          <a-menu-item
                            v-if="canLink && record.record?.id && (!unified || all)"
                            key="link"
                            :disabled="linking || !!pendingLink"
                            @click="link(record)"
                          >
                            {{ unified ? '关联到本组任务' : '关联到任务' }}
                          </a-menu-item>
                          <a-menu-item
                            v-if="
                              canDelete && record.record?.id && record.record.permissions?.actions.includes('DELETE')
                            "
                            key="delete"
                            danger
                            :disabled="deleting"
                            @click="remove(record)"
                          >
                            删除数据
                          </a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </div>
                </template>
                <template v-else-if="column.key === 'status'">
                  <a-tooltip :title="dataState(record.status).hint" :trigger="['hover', 'focus']">
                    <a-tag :color="dataState(record.status).color" tabindex="0">
                      {{ dataState(record.status).label }}
                    </a-tag>
                  </a-tooltip>
                </template>
                <template v-else-if="column.key !== '_index'">
                  {{ record.record?.displayValues?.[column.key] ?? record.record?.values?.[column.key] ?? '—' }}
                </template>
              </template>
            </OsTablePage>
          </template>
        </div>
      </template>
    </OsModalForm>
    <TaskWorkHistory
      v-if="historyTarget"
      :task="task"
      :entries="entries"
      :initial-entry-key="historyTarget.entryKey"
      :initial-record-id="historyTarget.recordId"
      :employee-view="employeeView"
      @close="historyTarget = undefined"
    />
    <OsModalForm
      :open="!!sources"
      title="任务来源"
      :width="720"
      :show-footer="false"
      :wrap-form="false"
      display-mode="drawer"
      :allow-switch-display="false"
      @cancel="sources = undefined"
    >
      <template #formItems>
        <a-timeline>
          <a-timeline-item v-for="(source, index) in sources?.sources || []" :key="index">
            <strong>{{ source.taskTitle }} · {{ source.actorName }}</strong>
            <div>
              {{
                source.operation === 'CREATED'
                  ? '新增'
                  : source.operation === 'UPDATED'
                    ? '修改'
                    : source.operation === 'DELETED'
                      ? '删除'
                      : source.operation === 'UNCHANGED'
                        ? '保存内容未变化'
                        : source.operation === 'LINKED'
                          ? '关联到任务'
                          : '记录操作'
              }}
              ·
              {{ taskTime(source.time) }}
            </div>
            <a-typography-text type="secondary">业务记录版本 {{ source.revision || '—' }}</a-typography-text>
          </a-timeline-item>
        </a-timeline>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="materialsOpen"
      title="交付材料 · 已留存快照"
      :width="1000"
      :show-footer="false"
      :wrap-form="false"
      display-mode="drawer"
      :allow-switch-display="false"
      @cancel="materialsOpen = false"
    >
      <template #formItems>
        <p class="task-list__hint">以下是交付时留存的数据；后续编辑不会改变这些历史快照。</p>
        <a-collapse>
          <a-collapse-panel
            v-for="material in materials || []"
            :key="material.entryKey"
            :header="`${material.name} · ${material.records.length} 份办理快照`"
          >
            <div v-for="record in material.records" :key="record.id" class="task-entry-material">
              <a-tag>业务记录版本 {{ record.record?.revision || '—' }}</a-tag>
              <p v-for="(source, index) in record.sources" :key="index" class="task-list__hint">
                {{ source.taskTitle }} · {{ source.actorName }} · {{ taskTime(source.time) }}
              </p>
              <a-button type="link" @click="openMaterial(material, record.id)">查看完整表单与明细</a-button>
              <dl>
                <template v-for="(value, field) in record.record?.values || {}" :key="field">
                  <dt>
                    {{
                      materialDefinitions[material.entryKey]?.model.object.fields.find(f => f.id === field)?.name ||
                      field
                    }}
                  </dt>
                  <dd>{{ value }}</dd>
                </template>
              </dl>
            </div>
          </a-collapse-panel>
        </a-collapse>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="!!materialPreview"
      :title="`${materialPreview?.name || '交付材料'} · 历史快照`"
      :width="modalWidth"
      :show-footer="false"
      :wrap-form="false"
      display-mode="modal"
      :allow-switch-display="false"
      :resizable="false"
      @cancel="materialPreview = undefined"
    >
      <template #formItems>
        <div v-if="materialPreview" class="task-entry-modal">
          <div class="task-entry-editor-toolbar">
            <a-button @click="materialPreview = undefined">
              <ArrowLeftOutlined />
              返回交付材料
            </a-button>
            <a-tag>只读 · 非当前数据</a-tag>
          </div>
          <TaskEntryRecordEditor
            :key="JSON.stringify(materialPreview.target)"
            :target="materialPreview.target"
            :snapshot="materialPreview.snapshot"
            readonly
            @cancel="materialPreview = undefined"
          />
        </div>
      </template>
    </OsModalForm>
  </section>
</template>
<style scoped>
.task-entry-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: var(--spacing-md);
}
.task-entry-workspace__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-md);
}
.task-entry-workspace__header h3 {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-lg, 16px);
}
.task-entry-workspace__header p {
  margin: 0;
}
.task-business-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr));
  gap: var(--spacing-md);
}
.task-business-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg);
  border: 1px solid var(--border-color, #e8e8ed);
  border-radius: var(--border-radius-lg, 8px);
  background: var(--bg-color-container, #fff);
  color: var(--text-color-primary, #303846);
  font: inherit;
  text-align: left;
  cursor: pointer;
  min-width: 0;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}
.task-business-card:not(:disabled):hover,
.task-business-card:focus-visible {
  border-color: var(--primary-color, #5137cf);
  box-shadow: 0 2px 8px var(--primary-color-light, #eeebff);
  outline: none;
}
.task-business-card:disabled {
  cursor: not-allowed;
  border-style: dashed;
}
.task-business-card__unavailable {
  color: var(--text-color-secondary, #808693);
  font-size: var(--font-size-sm, 12px);
  line-height: 1.6;
}
.task-business-card__heading,
.task-business-card__footer,
.task-business-card__summary {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}
.task-business-card__heading strong {
  flex: 1;
  overflow-wrap: anywhere;
}
.task-business-card__icon,
.task-business-card__footer {
  color: var(--primary-color, #5137cf);
}
.task-business-card__required {
  font-size: var(--font-size-sm, 12px);
  color: var(--warning-color, #ad6800);
}
.task-business-card__rule,
.task-business-card__scope {
  font-size: var(--font-size-sm, 12px);
  color: var(--text-color-secondary, #808693);
  overflow-wrap: anywhere;
}
.task-business-card__summary {
  align-items: flex-start;
  flex-direction: column;
  font-size: var(--font-size-sm, 12px);
}
.task-business-card__footer {
  justify-content: space-between;
  margin-top: auto;
  padding-top: var(--spacing-sm);
  border-top: 1px solid var(--border-color, #e8e8ed);
}
.task-entry-opening {
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
  color: var(--text-secondary);
}
.task-entry-modal {
  min-height: min(60vh, 540px);
  display: grid;
  align-content: start;
  gap: var(--spacing-md);
}
.task-entry-editor-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-md);
}
.task-entry-table {
  height: auto;
  min-width: 0;
}
.task-entry-filters {
  align-items: center;
}
.task-entry-filters :deep(.ant-form-item) {
  margin-inline-end: var(--spacing-lg);
}
.task-entry-control {
  width: calc(var(--spacing-lg) * 12);
  max-width: 100%;
}
.task-entry-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
.task-entry-row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
  white-space: nowrap;
}
.task-entry-more {
  height: auto;
  padding: 0;
}
.task-entry-table :deep(.ant-card-head-wrapper) {
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}
.task-entry-table :deep(.ant-card-extra > .ant-space) {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.task-entry-material {
  padding: 12px;
  border-bottom: 1px solid var(--os-border-color, #eee);
}
.task-entry-material dl {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 2fr;
  gap: 8px;
}
.task-entry-material dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
