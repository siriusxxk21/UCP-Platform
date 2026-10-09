<script lang="ts" setup>
import type { BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import { getApprovalDetail as getApprovalDetailApi, getProcessInstanceBpmnModelView } from '@/api/bpm/processInstance'
import type { BpmTaskApi } from '@/api/bpm/task'
import { approveTask, getTaskListByProcessInstanceId, rejectTask } from '@/api/bpm/task'
import FormCreate from '@form-create/ant-design-vue'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  FileTextOutlined,
  PrinterOutlined,
  ReloadOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { businessTaskNodes } from '@/nocode/flow-task-binding'
import { businessFormViewer } from '@/components/BusinessForm/registry'
import { setConfAndFields2 } from '@/components/form-create'
import { formatDateTime } from '@/utils/format'
import { MyProcessViewer } from '@/views/bpm/components/bpmn-process-designer/package'
import { type SimpleFlowNode, SimpleProcessViewer } from '@/views/bpm/components/simple-process-design'
import { formatDuration, getTaskStatusMeta } from '../../task/shared'
import request from '@/utils/request'
import { createFlowMaterialApi } from '@/api/nocode/flow-material'
import { useFlowMaterials } from './use-flow-materials'
import FlowMaterialCard from './FlowMaterialCard.vue'
import WorkflowTaskNodes from './WorkflowTaskNodes.vue'
import type { WorkflowTaskNodeView } from '@/types/nocode/workflow-task-node'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { editSignature } from '@/nocode/edit-signature'

defineOptions({ name: 'TaskInstanceDetail' })

interface DetailForm {
  rule: any[]
  option: Record<string, any>
  value: Record<string, any>
}

const route = useRoute()
const router = useRouter()

const BPM_MODEL_FORM_TYPE_NORMAL = 10
const BPM_MODEL_FORM_TYPE_CUSTOM = 20
const BPM_MODEL_TYPE_SIMPLE = 20
const BPM_TASK_STATUS_RUNNING = 1
const BPM_TASK_STATUS_APPROVED = 2
const BPM_TASK_STATUS_REJECTED = 3
const BPM_FIELD_PERMISSION_READ = '1'
const BPM_FIELD_PERMISSION_WRITE = '2'
const BPM_FIELD_PERMISSION_NONE = '3'
const APPROVAL_ACTION_MODAL_WIDTH = 560

const processStatusOptions = [
  { label: '审批中', value: 1, color: 'processing' },
  { label: '审批通过', value: 2, color: 'success' },
  { label: '审批不通过', value: 3, color: 'error' },
  { label: '已取消', value: 4, color: 'default' }
]

const loading = ref(false)
const workflowTaskNodes = ref<InstanceType<typeof WorkflowTaskNodes>>()
const linkedTaskNodes = ref<WorkflowTaskNodeView[]>([])
const taskRefreshKey = ref(0)
const activeTab = ref('form')
const businessForm = computed(() => businessFormViewer(processDefinition.value?.formCustomViewPath))
const actionLoading = ref(false)
const taskListLoading = ref(false)
const processInstance = ref<BpmProcessInstanceApi.ProcessInstance>()
const processDefinition = ref<Record<string, any>>({})
const modelView = ref<BpmProcessInstanceApi.ProcessInstanceBpmnModelView>()
const activityNodes = ref<BpmProcessInstanceApi.ApprovalNodeInfo[]>([])
const todoTask = ref<BpmTaskApi.Task>()
const independentTaskForm = computed(
  () => todoTask.value?.formBinding?.mode === 'OVERRIDE' && todoTask.value.formBinding.source?.kind === 'FLOW_FORM'
)
const taskHasNoForm = computed(() => todoTask.value?.formBinding?.source?.kind === 'NONE')
const hasNoForm = computed(
  () => taskHasNoForm.value || (!independentTaskForm.value && processDefinition.value?.formType === 0)
)
const canHandleTask = computed(
  () =>
    !!todoTask.value &&
    !todoTask.value.endTime &&
    processInstance.value?.status === 1 &&
    !processInstance.value?.endTime &&
    (!taskId.value || taskId.value === todoTask.value.id)
)
const taskList = ref<BpmTaskApi.Task[]>([])
const fApi = ref<any>()
const writableFields = ref<string[]>([])
const formFieldsPermission = ref<Record<string, string | number>>()
const detailForm = ref<DetailForm>({
  rule: [],
  option: {},
  value: {}
})
const approvalModalOpen = ref(false)
const approvalAction = ref<'approve' | 'reject'>('approve')
const approvalReason = ref('')

const processInstanceId = computed(() => getQueryString('id'))
const taskId = computed(() => getQueryString('taskId'))
const activityId = computed(() => getQueryString('activityId'))
const materials = useFlowMaterials(createFlowMaterialApi(request), () => ({
  processInstanceId: processInstanceId.value,
  taskId: canHandleTask.value ? todoTask.value!.id : taskId.value || undefined
}))
const materialState = computed(() => ({
  ...materials,
  items: materials.items.value,
  currentItems: materials.currentItems.value,
  groups: materials.groups.value,
  loading: materials.loading.value,
  loaded: materials.loaded.value,
  error: materials.error.value,
  blockedReason: materials.blockedReason.value,
  reviewRequired: materials.reviewRequired.value,
  activeId: materials.activeId.value,
  activeItem: materials.activeItem.value,
  approvalBlocked: materials.approvalBlocked.value
}))
const materialScroll = ref<HTMLElement>()
const currentInputSelected = ref(false)
const activeStep = computed(() =>
  currentInputSelected.value || (!materials.activeItem.value && (!hasNoForm.value || businessTodo.value))
    ? 'current-input'
    : `material:${materials.activeItem.value?.nodeId || ''}`
)
const activeVersions = computed(() => {
  const group = materials.groups.value.find(group => group.nodeId === materials.activeItem.value?.nodeId)
  return group ? [...group.current, ...group.history] : []
})
const detailError = ref('')
let initialForm = '',
  detailGeneration = 0
const hasUnsavedChanges = () =>
  canHandleTask.value && (approvalReason.value.trim() !== '' || editSignature(buildWritableVariables()) !== initialForm)
useUnsavedNavigation(() => actionLoading.value || hasUnsavedChanges())
onBeforeUnmount(() => detailGeneration++)
async function refreshDetail() {
  if (await confirmDiscard(hasUnsavedChanges())) await loadDetail()
}
async function refreshTaskProgress() {
  const stamp = detailGeneration
  await loadProcessModelView(stamp)
  // 并行审批可能尚有未提交填写；任务回调刷新不覆盖该表单。
  if (!hasUnsavedChanges())
    await loadApprovalDetail(stamp)
}
async function locateMaterial(id: string) {
  activeTab.value = 'form'
  currentInputSelected.value = false
  void materials.select(id)
  await nextTick()
  const pane = materialScroll.value?.querySelector<HTMLElement>('.material-step-tabs > .ant-tabs-content-holder')
  if (pane) pane.scrollTop = 0
}
async function selectMaterialStep(key: string | number) {
  currentInputSelected.value = key === 'current-input'
  if (!currentInputSelected.value) void materials.selectStep(String(key).slice('material:'.length))
  await nextTick()
  const pane = materialScroll.value?.querySelector<HTMLElement>('.material-step-tabs > .ant-tabs-content-holder')
  if (pane) pane.scrollTop = 0
}
function locateTaskMaterial(id: string) {
  const item = materials.items.value.find(item => item.taskId === id)
  if (!item) {
    message.info('本次任务材料不在当前可查阅范围内')
    return
  }
  void locateMaterial(item.id)
}
function retryMaterial(id: string) {
  if (materials.items.value.find(item => item.id === id)?.state === 'UNAVAILABLE') void materials.load()
  else void materials.loadMaterial(id)
}
const simpleModel = computed<SimpleFlowNode | undefined>(() => modelView.value?.simpleModel)
const bpmnXml = computed(() => modelView.value?.bpmnXml || processDefinition.value?.bpmnXml || '')
const businessNodes = computed(() => businessTaskNodes(bpmnXml.value))
const businessTodo = computed(
  () =>
    canHandleTask.value &&
    !!todoTask.value &&
    (todoTask.value.formBinding?.taskMode === 'BUSINESS' || businessNodes.value.has(todoTask.value.taskDefinitionKey))
)
const selectedBusinessTask = computed(() => {
  const rows = [...(modelView.value?.tasks || []), ...taskList.value]
  return rows.find(t => t.id === taskId.value && businessNodes.value.has(t.taskDefinitionKey))
})
function openBusinessTask(id: string) {
  router.push({ name: 'NocodeFlowTask', query: { taskId: id } })
}
const approvalModalConfig = computed(() =>
  approvalAction.value === 'reject'
    ? {
        title: '拒绝审批',
        label: '拒绝原因',
        placeholder: '请输入拒绝原因',
        okText: '拒绝'
      }
    : {
        title: '通过审批',
        label: '审批意见',
        placeholder: '同意',
        okText: '通过'
      }
)

const columns = [
  { title: '审批节点', dataIndex: 'name', key: 'name', width: 180, ellipsis: true, align: 'left' },
  { title: '审批人', key: 'approver', width: 150 },
  { title: '开始时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '审批状态', dataIndex: 'status', key: 'status', width: 120 },
  { title: '审批建议', dataIndex: 'reason', key: 'reason', width: 220, ellipsis: true, align: 'left' },
  { title: '耗时', dataIndex: 'durationInMillis', key: 'durationInMillis', width: 120 },
  { title: '操作', key: 'action', width: 110 }
]

function getQueryString(key: string) {
  const value = route.query[key]
  return Array.isArray(value) ? value[0] || '' : String(value || '')
}

function getProcessStatusMeta(status?: number) {
  return (
    processStatusOptions.find(item => item.value === status) || {
      label: status === undefined || status === null ? '-' : String(status),
      color: 'default'
    }
  )
}

function getTimelineNodeColor(status?: number) {
  if (status === BPM_TASK_STATUS_RUNNING) return 'blue'
  if (status === BPM_TASK_STATUS_APPROVED) return 'green'
  if (status === BPM_TASK_STATUS_REJECTED) return 'red'
  return 'gray'
}

function getApproverName(task: BpmTaskApi.Task) {
  return task.assigneeUser?.nickname || task.ownerUser?.nickname || '-'
}

function getNodeUsers(node: BpmProcessInstanceApi.ApprovalNodeInfo) {
  if (node.tasks?.length)
    return node.tasks
      .map(task => task.assigneeUser?.nickname || task.ownerUser?.nickname)
      .filter(Boolean)
      .join('、')
  if (node.candidateUsers?.length) return node.candidateUsers.map(user => user.nickname).join('、')
  return ''
}

async function loadDetail() {
  if (!processInstanceId.value) {
    message.error('流程实例编号不能为空')
    return
  }

  const stamp = ++detailGeneration
  loading.value = true
  detailError.value = ''
  currentInputSelected.value = false
  materials.reset()
  todoTask.value = undefined
  processInstance.value = undefined
  processDefinition.value = {}
  modelView.value = undefined
  activityNodes.value = []
  taskList.value = []
  detailForm.value = { rule: [], option: {}, value: {} }
  writableFields.value = []
  formFieldsPermission.value = undefined
  approvalReason.value = ''
  try {
    await Promise.all([loadApprovalDetail(stamp), loadProcessModelView(stamp)])
    if (stamp === detailGeneration)
      taskRefreshKey.value++
    if (stamp === detailGeneration) void materials.load()
  } catch (error: any) {
    if (stamp === detailGeneration) {
      detailError.value = error.message || '读取流程详情失败'
      todoTask.value = undefined
    }
  } finally {
    if (stamp === detailGeneration) {
      loading.value = false
      await nextTick()
      initialForm = editSignature(buildWritableVariables())
    }
  }
}

async function loadApprovalDetail(stamp: number) {
  const data = await getApprovalDetailApi({
    processInstanceId: processInstanceId.value,
    taskId: taskId.value || undefined,
    activityId: activityId.value || undefined
  })
  if (stamp !== detailGeneration) return
  if (!data?.processInstance || !data.processDefinition) {
    throw new Error('查询不到流程信息')
  }

  processInstance.value = data.processInstance
  processDefinition.value = data.processDefinition
  activityNodes.value = data.activityNodes || []
  // 历史链接不能借服务端的当前待办兜底结果切换为另一项可操作任务。
  todoTask.value = taskId.value && data.todoTask?.id !== taskId.value ? undefined : data.todoTask
  formFieldsPermission.value = data.formFieldsPermission

  if (hasNoForm.value) {
    writableFields.value = []
    detailForm.value = { rule: [], option: {}, value: {} }
    return
  }

  if (processDefinition.value.formType === BPM_MODEL_FORM_TYPE_NORMAL || independentTaskForm.value) {
    writableFields.value = []
    setConfAndFields2(
      detailForm,
      independentTaskForm.value ? todoTask.value?.formConf : processDefinition.value.formConf,
      independentTaskForm.value ? todoTask.value?.formFields : processDefinition.value.formFields,
      independentTaskForm.value ? todoTask.value?.formVariables || {} : processInstance.value.formVariables || {}
    )
    detailForm.value.option = {
      ...detailForm.value.option,
      submitBtn: false,
      resetBtn: false
    }
  }
}

// 加载卡片会延后挂载表单，必须等表单 API 就绪后再应用字段权限。
watch(
  [fApi, loading, independentTaskForm, canHandleTask],
  () => {
    if (loading.value || !fApi.value) return
    writableFields.value =
      independentTaskForm.value && canHandleTask.value ? collectFormFields(detailForm.value.rule) : []
    fApi.value.disabled?.(!independentTaskForm.value || !canHandleTask.value)
    applyFieldPermission(formFieldsPermission.value)
  },
  { flush: 'post' }
)

async function loadProcessModelView(stamp: number) {
  if (!processInstanceId.value) return
  try {
    const result = await getProcessInstanceBpmnModelView(processInstanceId.value)
    if (stamp === detailGeneration) modelView.value = result
  } catch (error: any) {
    console.error('加载流程图失败:', error)
    if (stamp === detailGeneration) message.error(error.message || '加载流程图失败')
  }
}

async function loadTaskList() {
  if (!processInstanceId.value) return
  const stamp = detailGeneration
  taskListLoading.value = true
  try {
    const data = await getTaskListByProcessInstanceId(processInstanceId.value)
    if (stamp === detailGeneration) taskList.value = Array.isArray(data) ? data : []
  } catch (error: any) {
    console.error('加载流转记录失败:', error)
    message.error(error.message || '加载流转记录失败')
  } finally {
    if (stamp === detailGeneration) taskListLoading.value = false
  }
}

function applyFieldPermission(permissionMap?: Record<string, string | number>) {
  if (hasNoForm.value) return
  if (!permissionMap) return
  Object.entries(permissionMap).forEach(([field, permission]) => {
    const value = String(permission)
    if (value === BPM_FIELD_PERMISSION_READ) {
      fApi.value?.disabled?.(true, field)
      writableFields.value = writableFields.value.filter(item => item !== field)
    }
    if (value === BPM_FIELD_PERMISSION_WRITE && canHandleTask.value) {
      fApi.value?.disabled?.(false, field)
      writableFields.value.push(field)
    }
    if (value === BPM_FIELD_PERMISSION_NONE) {
      fApi.value?.hidden?.(true, field)
      writableFields.value = writableFields.value.filter(item => item !== field)
    }
  })
}

function buildWritableVariables() {
  if (hasNoForm.value) return {}
  if (!canHandleTask.value || !writableFields.value.length) return {}
  return writableFields.value.reduce<Record<string, any>>((result, field) => {
    result[field] = detailForm.value.value?.[field]
    return result
  }, {})
}

async function openApproveModal() {
  if (actionLoading.value || !canHandleTask.value || !todoTask.value) return
  const stamp = detailGeneration,
    id = todoTask.value.id
  actionLoading.value = true
  try {
    if (!(await materials.prepareApproval())) throw new Error(materials.approvalBlocked.value || '请先重新读取必需材料')
    assertCurrentTask(stamp, id)
    approvalAction.value = 'approve'
    approvalModalOpen.value = true
  } catch (error: any) {
    message.warning(error.message || '读取审批材料失败')
  } finally {
    actionLoading.value = false
  }
}

function collectFormFields(rules: any[]): string[] {
  return rules.flatMap(rule => [
    ...(typeof rule.field === 'string' ? [rule.field] : []),
    ...collectFormFields(Array.isArray(rule.children) ? rule.children : [])
  ])
}

function openRejectModal() {
  if (actionLoading.value || !canHandleTask.value || !todoTask.value) return
  approvalAction.value = 'reject'
  approvalModalOpen.value = true
}

async function submitApprovalModal() {
  if (actionLoading.value || !canHandleTask.value || !todoTask.value) return
  if (approvalAction.value === 'reject' && !approvalReason.value.trim()) {
    message.warning('请输入拒绝原因')
    return
  }

  const stamp = detailGeneration,
    id = todoTask.value.id
  actionLoading.value = true
  try {
    if (approvalAction.value === 'approve') await handleApprove(approvalReason.value, stamp, id)
    else await handleReject(approvalReason.value, stamp, id)
    approvalModalOpen.value = false
  } catch (error: any) {
    message.error(error.message || '提交审批失败')
    if (/材料/.test(error.message || '')) materials.invalidateReview(error.message)
  } finally {
    actionLoading.value = false
  }
}
function assertCurrentTask(stamp: number, id: string) {
  if (stamp !== detailGeneration || !canHandleTask.value || todoTask.value?.id !== id)
    throw new Error('当前任务已变化，请重新读取后办理')
}
async function handleApprove(reason: string, stamp: number, id: string) {
  if (!(await materials.prepareApproval())) throw new Error(materials.approvalBlocked.value || '请先重新读取必需材料')
  if (independentTaskForm.value) await fApi.value?.validate?.()
  assertCurrentTask(stamp, id)
  await approveTask({
    id,
    reason: reason.trim() || '同意',
    variables: buildWritableVariables(),
    ...(materials.reviewToken.value ? { materialReviewToken: materials.reviewToken.value } : {})
  })
  if (stamp !== detailGeneration) return
  message.success('审批通过成功')
  await loadDetail()
  await loadTaskList()
}
async function handleReject(reason: string, stamp: number, id: string) {
  assertCurrentTask(stamp, id)
  await rejectTask({
    id,
    reason: reason.trim()
  })
  if (stamp !== detailGeneration) return
  message.success('审批拒绝成功')
  await loadDetail()
  await loadTaskList()
}

function handlePrint() {
  window.print()
}

watch(activeTab, value => {
  if (value === 'record' && taskList.value.length === 0) loadTaskList()
})

watch([processInstanceId, taskId, activityId], loadDetail, { immediate: true })
</script>

<template>
  <div class="bpm-detail-page">
    <a-card :bordered="false" class="detail-header-card" :loading="loading">
      <div class="detail-header">
        <div class="detail-heading">
          <div class="process-title">
            {{ processInstance?.name || '审批详情' }}
            <a-tag :color="getProcessStatusMeta(processInstance?.status).color">
              {{ getProcessStatusMeta(processInstance?.status).label }}
            </a-tag>
          </div>
          <div class="process-meta">
            <span>发起人：{{ processInstance?.startUser?.nickname || '—' }}</span>
            <span>{{ formatDateTime(processInstance?.startTime || processInstance?.createTime || '') }}</span>
            <span v-if="todoTask">当前节点：{{ todoTask.name }}</span>
            <span v-else>当前为只读查看</span>
          </div>
        </div>
        <a-space wrap>
          <a-button :disabled="actionLoading" @click="refreshDetail">
            <ReloadOutlined />
            刷新
          </a-button>
          <a-button @click="activeTab = 'diagram'">流程图</a-button>
          <a-button aria-label="打印流程" @click="handlePrint"><PrinterOutlined /></a-button>
        </a-space>
      </div>
    </a-card>
    <a-alert v-if="detailError" type="error" show-icon :message="detailError">
      <template #action><a-button @click="refreshDetail">重新读取</a-button></template>
    </a-alert>
    <WorkflowTaskNodes
      v-if="processInstance"
      ref="workflowTaskNodes"
      :process-instance-id="processInstanceId"
      :refresh-key="taskRefreshKey"
      @updated="linkedTaskNodes = $event"
      @changed="refreshTaskProgress"
    />
    <a-tabs v-model:active-key="activeTab" class="review-tabs" :destroy-inactive-tab-pane="false">
      <a-tab-pane key="form" tab="审批详情">
        <div class="review-workspace">
          <main ref="materialScroll" class="review-materials" aria-label="审批材料">
            <div class="review-section-header">
              <div>
                <h2>
                  审批材料
                  <span class="material-count">{{ materialState.currentItems.length }}</span>
                </h2>
                <span class="section-description">
                  {{ canHandleTask ? '前序节点提交的内容，仅供查阅' : '已提交的流程材料，仅供查阅' }}
                </span>
              </div>
              <span v-if="hasNoForm" class="current-form-note">
                {{ canHandleTask ? '当前节点无需填写表单，查阅后审批' : '只读查阅' }}
              </span>
            </div>
            <a-alert
              v-if="materialState.error || materialState.blockedReason"
              class="material-list-error"
              type="error"
              show-icon
              :message="materialState.error || materialState.blockedReason"
            >
              <template #action><a-button size="small" @click="materials.load">重新读取材料</a-button></template>
            </a-alert>
            <a-skeleton v-if="materialState.loading && !materialState.loaded" active :paragraph="{ rows: 6 }" />
            <a-empty
              v-else-if="
                materialState.loaded &&
                !materialState.items.length &&
                !materialState.error &&
                !materialState.blockedReason
              "
              description="当前没有可查阅的前序材料"
            />
            <a-tabs
              :active-key="activeStep"
              class="material-step-tabs"
              size="small"
              :destroy-inactive-tab-pane="false"
              @change="selectMaterialStep"
            >
              <template #moreIcon><span aria-label="更多步骤">···</span></template>
              <a-tab-pane
                v-for="(group, index) in materialState.groups"
                :key="`material:${group.nodeId}`"
                :tab="`${String(index + 1).padStart(2, '0')} ${group.current[0]?.nodeName || group.history[0]?.nodeName || '流程材料'}`"
              >
                <FlowMaterialCard
                  v-if="materialState.activeItem?.nodeId === group.nodeId"
                  :key="materialState.activeItem.id"
                  :item="materialState.activeItem"
                  :ordinal="index + 1"
                  :detail="materialState.details[materialState.activeId]"
                  :loading="materialState.detailLoading[materialState.activeId]"
                  :error="materialState.detailErrors[materialState.activeId]"
                  :approval-required="canHandleTask && !businessTodo"
                  @retry="retryMaterial(materialState.activeId)"
                  @failed="materials.renderFailed(materialState.activeId, $event)"
                >
                  <template v-if="activeVersions.length > 1" #versions>
                    <label class="version-label" for="flow-material-version">提交版本</label>
                    <a-select
                      id="flow-material-version"
                      :value="materialState.activeId"
                      class="material-version-select"
                      aria-label="提交版本"
                      :options="
                        activeVersions.map(item => ({
                          value: item.id,
                          label: `第 ${item.revision} 次提交 · ${item.submitterName || '未记录提交人'} · ${item.state === 'HISTORY' ? '历史' : '当前有效'}`
                        }))
                      "
                      @change="locateMaterial(String($event))"
                    />
                  </template>
                </FlowMaterialCard>
              </a-tab-pane>
              <a-tab-pane
                v-if="businessTodo || !hasNoForm"
                key="current-input"
                :tab="businessTodo ? '本节点业务办理' : canHandleTask ? '本节点填写' : '本节点表单'"
                force-render
              >
                <section v-if="businessTodo" class="current-input-section">
                  <h3>本节点业务办理</h3>
                  <p>请在业务办理页填写、暂存并提交本次材料。前序材料保持只读。</p>
                  <a-button type="primary" @click="openBusinessTask(todoTask!.id)">办理业务</a-button>
                </section>
                <section v-else-if="!hasNoForm" class="current-input-section" aria-label="本节点表单">
                  <h3>
                    {{ canHandleTask ? '本节点填写' : '本节点表单' }}
                    <a-tag>{{ canHandleTask ? '本次输入' : '只读' }}</a-tag>
                  </h3>
                  <template v-if="processDefinition?.formType === BPM_MODEL_FORM_TYPE_NORMAL || independentTaskForm">
                    <p v-if="independentTaskForm">{{ todoTask?.formName || '当前节点独立表单' }}</p>
                    <FormCreate
                      v-if="detailForm.rule.length > 0"
                      v-model="detailForm.value"
                      v-model:api="fApi"
                      :option="detailForm.option"
                      :rule="detailForm.rule"
                    />
                    <a-empty v-else description="当前节点暂无可填写字段" />
                  </template>
                  <component
                    :is="businessForm"
                    v-else-if="processDefinition?.formType === BPM_MODEL_FORM_TYPE_CUSTOM && businessForm"
                    :id="processInstance?.businessKey"
                  />
                  <a-alert
                    v-else-if="processDefinition?.formType === BPM_MODEL_FORM_TYPE_CUSTOM"
                    type="info"
                    show-icon
                    message="当前节点使用系统业务表单"
                    description="请从已配置的业务入口办理。"
                  />
                </section>
              </a-tab-pane>
            </a-tabs>
          </main>
          <aside class="review-context" aria-label="材料目录与审批操作">
            <div class="review-context-scroll">
              <section class="material-directory">
                <div class="review-section-header">
                  <h2>材料目录</h2>
                  <span>共 {{ materialState.currentItems.length }} 份材料</span>
                </div>
                <nav aria-label="材料目录">
                  <button
                    v-for="(item, index) in materialState.currentItems"
                    :key="item.id"
                    type="button"
                    :class="{ active: activeStep === `material:${item.nodeId}` }"
                    @click="locateMaterial(item.id)"
                  >
                    <span class="directory-number">{{ String(index + 1).padStart(2, '0') }}</span>
                    <span>{{ item.nodeName }}</span>
                    <span
                      class="directory-state"
                      :class="{ error: materialState.detailErrors[item.id] || item.state === 'UNAVAILABLE' }"
                    >
                      {{
                        materialState.detailErrors[item.id] || item.state === 'UNAVAILABLE'
                          ? '!'
                          : materialState.details[item.id]
                            ? '✓'
                            : '·'
                      }}
                    </span>
                  </button>
                </nav>
                <p class="context-help">点击目录切换左侧材料</p>
              </section>
              <section class="review-progress">
                <div class="review-section-header">
                  <h2>流程进度</h2>
                  <a-button type="link" size="small" @click="activeTab = 'record'">全部记录</a-button>
                </div>
                <a-timeline>
                  <a-timeline-item
                    v-for="node in activityNodes"
                    :key="node.id"
                    :color="getTimelineNodeColor(node.status)"
                    :class="{ 'approval-timeline-item-running': node.status === BPM_TASK_STATUS_RUNNING }"
                  >
                    <div class="node-name">{{ node.name }}</div>
                    <div class="node-users">
                      {{ getNodeUsers(node) || '—' }} · {{ getTaskStatusMeta(node.status).label }}
                    </div>
                    <div class="node-time">{{ formatDateTime(node.endTime || node.startTime || '') }}</div>
                    <div v-for="task in node.tasks || []" :key="task.id" class="task-reason">
                      <span v-if="task.reason">{{ task.reason }}</span>
                    </div>
                  </a-timeline-item>
                </a-timeline>
              </section>
            </div>
            <section v-if="canHandleTask" class="approval-box">
              <h2>审批意见</h2>
              <a-textarea
                v-model:value="approvalReason"
                :maxlength="500"
                show-count
                :auto-size="{ minRows: 3, maxRows: 4 }"
                placeholder="请输入审批意见，拒绝时必填"
                aria-label="当前审批意见"
                :disabled="actionLoading"
              />
              <div class="opinion-shortcuts">
                <a-button size="small" @click="approvalReason = '同意'">同意</a-button>
                <a-button size="small" @click="approvalReason = '材料齐全'">材料齐全</a-button>
              </div>
              <p v-if="materialState.approvalBlocked && !businessTodo" class="approval-blocked">
                {{ materialState.approvalBlocked }}
              </p>
              <p
                v-else-if="materialState.reviewRequired && materialState.currentItems.length && !businessTodo"
                class="context-help"
              >
                通过前将再次校验材料与读取权限
              </p>
              <div class="approval-actions">
                <a-button danger :loading="actionLoading" @click="openRejectModal">
                  <CloseCircleOutlined />
                  拒绝
                </a-button>
                <a-button v-if="businessTodo" type="primary" @click="openBusinessTask(todoTask!.id)">办理业务</a-button>
                <a-button
                  v-else
                  type="primary"
                  :loading="actionLoading"
                  :disabled="!!materialState.approvalBlocked"
                  @click="openApproveModal"
                >
                  <CheckCircleOutlined />
                  通过审批
                </a-button>
              </div>
            </section>
            <div v-else class="review-read-only">
              <a-tag>只读查看</a-tag>
              流程已结束或当前任务不在你的待办中。
            </div>
          </aside>
        </div>
      </a-tab-pane>
      <a-tab-pane key="diagram" tab="流程图">
        <div class="diagram-panel">
          <SimpleProcessViewer
            v-if="
              (processDefinition?.modelType === BPM_MODEL_TYPE_SIMPLE || !processDefinition?.modelType) && simpleModel
            "
            :flow-node="simpleModel"
            :process-instance="modelView?.processInstance"
            :tasks="modelView?.tasks"
            :task-nodes="linkedTaskNodes"
            @open-task="workflowTaskNodes?.openNode($event)"
          />
          <MyProcessViewer v-else-if="bpmnXml" :view="modelView" :xml="bpmnXml" />
          <a-empty v-else description="暂无流程图数据" />
        </div>
      </a-tab-pane>
      <a-tab-pane key="record" tab="流转记录">
        <a-table
          :columns="columns"
          :data-source="taskList"
          :loading="taskListLoading"
          :pagination="false"
          :scroll="{ x: 1260 }"
          row-key="id"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'approver'">{{ getApproverName(record) }}</template>
            <template v-else-if="column.key === 'createTime'">{{ formatDateTime(record.createTime) }}</template>
            <template v-else-if="column.key === 'endTime'">{{ formatDateTime(record.endTime) }}</template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="getTaskStatusMeta(record.status).color">
                {{ getTaskStatusMeta(record.status).label }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'durationInMillis'">
              {{ formatDuration(record.durationInMillis) }}
            </template>
            <template v-else-if="column.key === 'reason'">{{ record.reason || '—' }}</template>
            <template v-else-if="column.key === 'action'">
              <a-button
                v-if="businessTodo && record.id === todoTask?.id"
                size="small"
                type="link"
                @click="openBusinessTask(record.id)"
              >
                办理业务
              </a-button>
              <a-button
                v-else-if="materialState.items.some(item => item.taskId === record.id)"
                size="small"
                type="link"
                @click="locateTaskMaterial(record.id)"
              >
                <FileTextOutlined />
                查阅材料
              </a-button>
              <span v-else>—</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>
    <a-modal
      v-model:open="approvalModalOpen"
      :confirm-loading="actionLoading"
      :ok-button-props="{ danger: approvalAction === 'reject' }"
      :ok-text="approvalModalConfig.okText"
      :title="approvalModalConfig.title"
      :width="APPROVAL_ACTION_MODAL_WIDTH"
      cancel-text="取消"
      wrap-class-name="bpm-approval-action-modal-wrap"
      @ok="submitApprovalModal"
    >
      <div class="approval-action-form">
        <label class="approval-action-label" for="approval-action-reason">{{ approvalModalConfig.label }}</label>
        <a-textarea
          id="approval-action-reason"
          v-model:value="approvalReason"
          :maxlength="500"
          :auto-size="{ minRows: 2 }"
          :placeholder="approvalModalConfig.placeholder"
        />
      </div>
    </a-modal>
  </div>
</template>
<style scoped>
.bpm-detail-page {
  min-width: 0;
  height: 100%;
  container: approval-review / inline-size;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.detail-header-card {
  margin-bottom: 4px;
  flex-shrink: 0;
}
.detail-header-card :deep(.ant-card-body) {
  padding: 10px 16px;
}
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.detail-heading {
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 18px;
}
.process-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  color: var(--text-primary, #111827);
  font-size: 18px;
  font-weight: 700;
}
.process-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 0;
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
}
.review-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  height: 100%;
  min-height: 0;
}
.review-materials,
.review-context {
  min-width: 0;
  min-height: 0;
  background: #fff;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 9px;
}
.review-tabs {
  flex: 1;
  min-height: 0;
}
.review-tabs :deep(> .ant-tabs-nav) {
  margin-bottom: 12px;
}
.review-tabs :deep(> .ant-tabs-content-holder) {
  overflow: auto;
  min-height: 0;
}
.review-tabs :deep(> .ant-tabs-content-holder > .ant-tabs-content),
.review-tabs :deep(> .ant-tabs-content-holder > .ant-tabs-content > .ant-tabs-tabpane) {
  height: 100%;
}
.review-materials {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 16px;
}
.review-materials > .review-section-header {
  flex-shrink: 0;
  margin-bottom: 4px;
}
.material-step-tabs {
  flex: 1;
  min-height: 0;
}
.material-step-tabs :deep(> .ant-tabs-nav) {
  margin-bottom: 12px;
}
.material-step-tabs :deep(> .ant-tabs-content-holder) {
  overflow: auto;
  overscroll-behavior: contain;
  min-height: 0;
}
.material-version-select {
  width: 250px;
  max-width: 100%;
}
.version-label {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
.review-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.review-section-header > div {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.review-section-header h2,
.approval-box h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}
.section-description,
.review-section-header > span {
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.material-count {
  display: inline-grid;
  place-items: center;
  min-width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #eeeaff;
  color: #5135cf;
  font-size: 13px;
  margin-left: 8px;
}
.material-list-error {
  margin-bottom: 16px;
}
.current-form-note {
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.current-input-section {
  margin-top: 0;
  padding: 16px;
  border: 1px solid #d9d1ff;
  border-radius: 8px;
  background: #fdfcff;
  min-width: 0;
}
.current-input-section h3 {
  margin: 0 0 16px;
  font-size: 16px;
}
.current-input-section p {
  color: var(--text-secondary, #6b7280);
}
.review-context {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.review-context-scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
  overscroll-behavior: contain;
}
.material-directory,
.review-progress {
  padding: 20px;
}
.review-progress {
  border-top: 1px solid var(--border-color, #e5e7eb);
}
.review-context h2::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 16px;
  margin-right: 9px;
  background: #5135cf;
  border-radius: 2px;
  vertical-align: -2px;
}
.material-directory nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.material-directory nav button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 12px;
  border: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
}
.material-directory nav button.active {
  background: #f1eeff;
  color: #5135cf;
}
.directory-number {
  display: grid;
  place-items: center;
  width: 25px;
  height: 25px;
  flex-shrink: 0;
  border-radius: 50%;
  background: #eeeef5;
  color: #70768e;
  font-size: 12px;
}
.active .directory-number {
  background: #5135cf;
  color: #fff;
}
.directory-state {
  margin-left: auto;
  color: #229650;
  font-weight: 700;
}
.directory-state.error {
  color: #dc2626;
}
.context-help {
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
  margin: 12px 0 0;
}
.node-name {
  font-weight: 600;
  font-size: 13px;
}
.node-time,
.node-users,
.task-reason {
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
  margin-top: 3px;
}
.approval-box {
  flex-shrink: 0;
  border-top: 1px solid var(--border-color, #e5e7eb);
  padding: 16px 20px;
  background: #fff;
}
.approval-box h2 {
  margin-bottom: 12px;
}
.opinion-shortcuts {
  display: flex;
  gap: 8px;
  margin-top: 24px;
}
.opinion-shortcuts :deep(.ant-btn) {
  border-radius: 20px;
  font-size: 12px;
  color: #5135cf;
  background: #f5f2ff;
  border-color: #e5ddff;
}
.approval-blocked {
  color: #b45309;
  font-size: 12px;
  margin: 12px 0 0;
  line-height: 1.5;
  max-height: 54px;
  overflow: auto;
}
.approval-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
.approval-actions > :deep(.ant-btn) {
  flex: 1;
}
.review-read-only {
  flex-shrink: 0;
  padding: 20px;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  border-top: 1px solid var(--border-color, #e5e7eb);
}
.diagram-panel {
  min-width: 0;
  min-height: 480px;
  background: white;
  padding: 16px;
  border-radius: 8px;
}
:deep(.approval-timeline-item-running .ant-timeline-item-head) {
  background: #5135cf;
  border-color: #5135cf;
}
:global(.bpm-approval-action-modal-wrap .ant-modal) {
  max-width: calc(100vw - 32px);
}
:global(.bpm-approval-action-modal-wrap .approval-action-form) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
:global(.bpm-approval-action-modal-wrap .approval-action-label) {
  color: #4b5563;
  line-height: 22px;
}
:global(.bpm-approval-action-modal-wrap textarea.ant-input) {
  resize: none;
}
@container approval-review (max-width: 1050px) {
  .review-workspace {
    grid-template-columns: minmax(0, 1fr) 292px;
    gap: 12px;
  }
  .review-materials {
    padding: 16px;
  }
  .section-description {
    display: none;
  }
  .approval-box,
  .material-directory,
  .review-progress {
    padding: 16px;
  }
}
@container approval-review (max-width: 760px) {
  .review-workspace {
    display: flex;
    flex-direction: column;
    height: auto;
    min-height: 0;
  }
  .review-materials {
    height: 65dvh;
    min-height: 320px;
    flex-shrink: 0;
  }
  .review-context-scroll {
    max-height: 380px;
    flex: auto;
  }
  .detail-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
  .review-section-header {
    flex-wrap: wrap;
  }
}
@media print {
  .review-workspace {
    display: block;
    height: auto;
  }
  .review-materials,
  .review-context-scroll,
  .material-step-tabs :deep(> .ant-tabs-content-holder) {
    overflow: visible;
  }
  .approval-box,
  .detail-header :deep(.ant-btn) {
    display: none;
  }
}
</style>
