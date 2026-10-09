<script lang="ts" setup>
import type { SimpleFlowNode } from '../consts'
import NodeFormEditor from '@/views/bpm/model/form/NodeFormEditor.vue'
import WorkflowTaskNodeEditor from '@/views/bpm/model/form/WorkflowTaskNodeEditor.vue'
import {
  newWorkflowTaskSetting,
  workflowTaskFromWire,
  workflowTaskSettingError,
  workflowTaskToWire,
} from '@/nocode/workflow-task-node'
import { bindingError, formSourceSummary } from '@/views/bpm/model/form/node-form'
import {
  APPROVE_METHODS,
  APPROVE_TYPE,
  ApproveMethodType,
  ASSIGN_EMPTY_HANDLER_TYPES,
  ASSIGN_START_USER_HANDLER_TYPES,
  AssignEmptyHandlerType,
  AssignStartUserHandlerType,
  BPM_HTTP_REQUEST_PARAM_TYPES,
  CANDIDATE_STRATEGY,
  CandidateStrategy,
  CHILD_PROCESS_MULTI_INSTANCE_SOURCE_TYPE,
  CHILD_PROCESS_START_USER_EMPTY_TYPE,
  CHILD_PROCESS_START_USER_TYPE,
  CONDITION_CONFIG_TYPES,
  ConditionType,
  DEFAULT_BUTTON_SETTING,
  DEFAULT_CONDITION_GROUP_VALUE,
  DELAY_TYPE,
  NODE_DEFAULT_TEXT,
  REJECT_HANDLER_TYPES,
  RejectHandlerType,
  TIMEOUT_HANDLER_TYPES,
  TRIGGER_TYPES,
  TriggerTypeEnum
} from '../consts'
import { DeleteOutlined, SaveOutlined, UserOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, ref, watch } from 'vue'
import UserSelector from '@/components/UserSelector/index.vue'
import type { User } from '@/api/system/user'
import { getUsersByIds } from '@/api/system/user'
import { BpmNodeTypeEnum } from '@/types/bpm'
import ProcessNodeTree from './process-node-tree.vue'

defineOptions({ name: 'SimpleProcessDesigner' })

type NodeEditContext = {
  parentNode?: SimpleFlowNode
  branchParent?: SimpleFlowNode
  branchIndex?: number
}

type UserSelectorTarget = 'candidate' | 'assignEmpty'

const props = defineProps<{
  inheritedForm?: import('@/views/bpm/model/form/node-form').FormSource
  modelValue?: SimpleFlowNode
  modelName?: string
  modelFormId?: string | number
  modelFormType?: number
  startUserIds?: Array<string | number>
  startDeptIds?: Array<string | number>
  flowNode?: SimpleFlowNode
}>()

const emit = defineEmits<{
  'update:modelValue': [data: SimpleFlowNode]
  success: [data: SimpleFlowNode]
  'configure-start': []
}>()

const internalFlow = ref<SimpleFlowNode>(createDefaultFlow())
const selectedNode = ref<SimpleFlowNode>()
const selectedNodeContext = ref<NodeEditContext>({})
const drawerOpen = ref(false)
const userSelectorOpen = ref(false)
const userSelectorTarget = ref<UserSelectorTarget>('candidate')
const nodeSourceText = ref('')
const userLabelMap = ref<Record<string, string>>({})
const userSelectorSelectedUsers = ref<User[]>([])
const taskConfigurationBusy = ref(false)

const selectedNodeType = computed(() => selectedNode.value?.type)
const canDeleteSelectedNode = computed(() => {
  const type = selectedNode.value?.type
  return !!selectedNode.value && type !== BpmNodeTypeEnum.START_USER_NODE && type !== BpmNodeTypeEnum.END_EVENT_NODE
})
const userSelectorTitle = computed(() =>
  userSelectorTarget.value === 'assignEmpty' ? '选择指定审批人' : '选择指定成员'
)
const candidateUserIds = computed(() => splitIds(selectedNode.value?.candidateParam))
const assignEmptyUserIds = computed(() => (selectedNode.value?.assignEmptyHandler?.userIds || []).map(String))
const isApproveNode = computed(() =>
  [BpmNodeTypeEnum.USER_TASK_NODE, BpmNodeTypeEnum.TRANSACTOR_NODE].includes(selectedNodeType.value as BpmNodeTypeEnum)
)
const isCopyNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.COPY_TASK_NODE)
const isStartNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.START_USER_NODE)
const isBusinessNode = computed(() => selectedNode.value?.formBinding?.source?.kind === 'APPLICATION_RESOURCE')
const isConditionNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.CONDITION_NODE)
const isDelayNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.DELAY_TIMER_NODE)
const isTriggerNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.TRIGGER_NODE)
const isChildProcessNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.CHILD_PROCESS_NODE)
const isTaskCenterNode = computed(() => selectedNodeType.value === BpmNodeTypeEnum.TASK_CENTER_NODE)
const taskPersonnelFormId = computed(() => {
  // 显式来源优先；切换为无表单或系统路由后，向导内存中可能仍保留旧 formId。
  if (props.inheritedForm)
    return props.inheritedForm.kind === 'FLOW_FORM' ? props.inheritedForm.formId : undefined
  return props.modelFormType === 10 ? props.modelFormId : undefined
})

function configureStartForm() {
  drawerOpen.value = false
  emit('configure-start')
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value))
}
// 简单模型整体保存 JSON，任务日期仍走任务中心既有的时间戳边界。
function taskSettingsWire(flow: SimpleFlowNode, toWire: boolean): SimpleFlowNode {
  const result = clone(flow)
  const visit = (node?: SimpleFlowNode) => {
    if (!node)
      return
    if (node.taskCenterSetting?.task && Array.isArray(node.taskCenterSetting.nodes)) {
      node.taskCenterSetting = (
        toWire ? workflowTaskToWire(node.taskCenterSetting) : workflowTaskFromWire(node.taskCenterSetting)
      ) as typeof node.taskCenterSetting
    }
    node.conditionNodes?.forEach(visit)
    visit(node.childNode)
  }
  visit(result)
  return result
}

function splitIds(value?: string) {
  return (value || '')
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
}

function userLabel(id: string | number) {
  return userLabelMap.value[String(id)] || String(id)
}

function createSelectedUser(id: string | number): User {
  const label = userLabel(id)
  return {
    id: String(id),
    username: label,
    nickname: label
  }
}

async function resolveSelectedUsers(ids: Array<string | number>) {
  if (!ids.length) return []

  try {
    const users = await getUsersByIds(ids.map(String))
    const userMap = new Map(users.map(user => [String(user.id), user]))
    users.forEach(user => {
      userLabelMap.value[String(user.id)] = getUserDisplayName(user)
    })
    return ids.map(id => userMap.get(String(id)) || createSelectedUser(id))
  } catch {
    return ids.map(createSelectedUser)
  }
}

async function hydrateNodeUserLabels(node: SimpleFlowNode) {
  const ids = [...splitIds(node.candidateParam), ...(node.assignEmptyHandler?.userIds || []).map(String)]
  await resolveSelectedUsers([...new Set(ids)])
}

function ensureAssignEmptyHandler() {
  if (!selectedNode.value) return
  selectedNode.value.assignEmptyHandler ??= { type: AssignEmptyHandlerType.APPROVE, userIds: [] }
  selectedNode.value.assignEmptyHandler.userIds ??= []
}

function createDefaultFlow(): SimpleFlowNode {
  return {
    id: 'StartUserNode',
    type: BpmNodeTypeEnum.START_USER_NODE,
    name: '发起人',
    showText: props.modelName || '发起流程',
    childNode: {
      id: 'EndEvent',
      type: BpmNodeTypeEnum.END_EVENT_NODE,
      name: '结束',
      showText: '流程结束'
    }
  }
}

function normalizeNode(node: SimpleFlowNode) {
  node.name ||= '节点'
  node.showText ??= NODE_DEFAULT_TEXT.get(node.type) || ''
  if (node.type === BpmNodeTypeEnum.TASK_CENTER_NODE)
    node.taskCenterSetting ??= newWorkflowTaskSetting()

  if (isApproveNode.value) {
    node.approveType ??= 1
    node.candidateStrategy ??= CandidateStrategy.USER
    node.approveMethod ??= ApproveMethodType.SEQUENTIAL_APPROVE
    node.buttonsSetting ??= clone(DEFAULT_BUTTON_SETTING)
    node.rejectHandler ??= { type: RejectHandlerType.FINISH_PROCESS }
    node.timeoutHandler ??= { enable: false, type: 1, maxRemindCount: 1, timeDuration: '' }
    if (node.formBinding?.source?.kind !== 'APPLICATION_RESOURCE')
      node.assignEmptyHandler ??= { type: AssignEmptyHandlerType.APPROVE, userIds: [] }
    node.assignStartUserHandlerType ??= AssignStartUserHandlerType.START_USER_AUDIT
    node.taskCreateListener ??= { enable: false, path: '', header: [], body: [] }
    node.taskAssignListener ??= { enable: false, path: '', header: [], body: [] }
    node.taskCompleteListener ??= { enable: false, path: '', header: [], body: [] }
    node.fieldsPermission ??= []
  }

  if (isCopyNode.value) {
    node.candidateStrategy ??= CandidateStrategy.USER
  }

  if (isConditionNode.value) {
    node.conditionSetting ??= {
      conditionType: ConditionType.EXPRESSION,
      conditionExpression: '',
      conditionGroups: clone(DEFAULT_CONDITION_GROUP_VALUE),
      defaultFlow: false
    }
    node.conditionSetting.conditionGroups ??= clone(DEFAULT_CONDITION_GROUP_VALUE)
  }

  if (isDelayNode.value) {
    node.delaySetting ??= { delayType: 1, delayTime: '' }
  }

  if (isTriggerNode.value) {
    node.triggerSetting ??= {
      type: TriggerTypeEnum.HTTP_REQUEST,
      httpRequestSetting: { url: '', header: [], body: [], response: [] },
      formSettings: []
    }
    node.triggerSetting.httpRequestSetting ??= { url: '', header: [], body: [], response: [] }
    node.triggerSetting.httpRequestSetting.header ??= []
    node.triggerSetting.httpRequestSetting.body ??= []
    node.triggerSetting.httpRequestSetting.response ??= []
    node.triggerSetting.formSettings ??= []
  }

  if (isChildProcessNode.value) {
    node.childProcessSetting ??= {
      async: false,
      calledProcessDefinitionKey: '',
      calledProcessDefinitionName: '',
      skipStartUserNode: false,
      inVariables: [],
      outVariables: [],
      startUserSetting: { type: 1 },
      timeoutSetting: { enable: false },
      multiInstanceSetting: { enable: false }
    }
    node.childProcessSetting.inVariables ??= []
    node.childProcessSetting.outVariables ??= []
    node.childProcessSetting.startUserSetting ??= { type: 1 }
    node.childProcessSetting.timeoutSetting ??= { enable: false }
    node.childProcessSetting.multiInstanceSetting ??= { enable: false }
  }
}

function syncFromProps() {
  const original = props.modelValue || props.flowNode
  const source = original ? taskSettingsWire(original, false) : undefined
  // 父组件会回传本组件刚 emit 的数据；内容未变化时保留流程树引用，避免 selectedNode 指向旧节点。
  if (
    source
    && JSON.stringify(taskSettingsWire(source, true)) === JSON.stringify(taskSettingsWire(internalFlow.value, true))
  ) {
    return
  }
  internalFlow.value = source ? clone(source) : createDefaultFlow()
}

function openNodeConfig(node: SimpleFlowNode, context: NodeEditContext = {}) {
  selectedNode.value = node
  selectedNodeContext.value = context
  normalizeNode(node)
  void hydrateNodeUserLabels(node)
  refreshSelectedNodeSource()
  drawerOpen.value = true
}

function refreshSelectedNodeSource() {
  nodeSourceText.value = selectedNode.value ? JSON.stringify(selectedNode.value, null, 2) : ''
}

function applyNodeForm(binding: import('@/views/bpm/model/form/node-form').NodeFormBinding, manual: boolean) {
  if (!selectedNode.value) return
  selectedNode.value.formBinding = binding
  if (binding.taskMode)
    selectedNode.value.type =
      binding.taskMode === 'BUSINESS' ? BpmNodeTypeEnum.TRANSACTOR_NODE : BpmNodeTypeEnum.USER_TASK_NODE
  if (manual) {
    selectedNode.value.approveType = 1
    selectedNode.value.approveMethod = 1
    selectedNode.value.assignStartUserHandlerType = 1
    delete selectedNode.value.assignEmptyHandler
    delete selectedNode.value.skipExpression
  }
  refreshSelectedNodeSource()
}

function getUserDisplayName(user: User) {
  return user.nickname || user.username || String(user.id)
}

function syncSelectorUsers(target: UserSelectorTarget, users: User[]) {
  if (userSelectorTarget.value !== target) return
  userSelectorSelectedUsers.value = [...users]
}

function setSelectedUserIds(target: UserSelectorTarget, ids: Array<string | number>) {
  const normalizedIds = ids.map(String)
  if (target === 'assignEmpty') {
    ensureAssignEmptyHandler()
    if (selectedNode.value?.assignEmptyHandler) selectedNode.value.assignEmptyHandler.userIds = normalizedIds as any
    syncSelectorUsers(target, normalizedIds.map(createSelectedUser))
    return
  }

  if (selectedNode.value) {
    selectedNode.value.candidateParam = normalizedIds.join(',')
    selectedNode.value.showText = normalizedIds.length
      ? `指定成员：${normalizedIds.map(userLabel).join('、')}`
      : NODE_DEFAULT_TEXT.get(selectedNode.value.type) || ''
  }
  syncSelectorUsers(target, normalizedIds.map(createSelectedUser))
}

async function openUserSelector(target: UserSelectorTarget) {
  userSelectorTarget.value = target
  const ids = target === 'assignEmpty' ? assignEmptyUserIds.value : candidateUserIds.value
  userSelectorSelectedUsers.value = await resolveSelectedUsers(ids)
  if (userSelectorTarget.value !== target) return
  userSelectorOpen.value = true
}

function handleUserSelectorConfirm(users: User[]) {
  users.forEach(user => {
    userLabelMap.value[String(user.id)] = getUserDisplayName(user)
  })
  setSelectedUserIds(
    userSelectorTarget.value,
    users.map(user => user.id)
  )
}

function clearSelectedUsers(target: UserSelectorTarget) {
  setSelectedUserIds(target, [])
}

function handleSaveNodeConfig() {
  if (!selectedNode.value) return
  if (taskConfigurationBusy.value)
    return
  try {
    validateNode(selectedNode.value)
    if (selectedNode.value.type === BpmNodeTypeEnum.TASK_CENTER_NODE) {
      const setting = selectedNode.value.taskCenterSetting!
      selectedNode.value.showText = `${setting.task.title}${setting.source === 'TEMPLATE' ? ` · 模板 V${setting.templateVersion}` : ''} · 整件任务完成后继续`
    }
    refreshSelectedNodeSource()
    drawerOpen.value = false
    message.success('节点配置已保存')
  } catch (error: any) {
    message.error(error.message || '节点配置不完整')
  }
}

function handleDeleteSelectedNode() {
  if (!selectedNode.value || !canDeleteSelectedNode.value) return

  const { parentNode, branchParent, branchIndex } = selectedNodeContext.value
  if (branchParent && typeof branchIndex === 'number') {
    branchParent.conditionNodes?.splice(branchIndex, 1)
  } else if (parentNode?.childNode === selectedNode.value) {
    parentNode.childNode = selectedNode.value.childNode
  } else {
    message.warning('未找到当前节点的父级，无法删除')
    return
  }

  selectedNode.value = undefined
  selectedNodeContext.value = {}
  drawerOpen.value = false
  message.success('节点已删除')
}

function applySelectedNodeSource() {
  if (!selectedNode.value) return
  try {
    const parsed = JSON.parse(nodeSourceText.value)
    Object.keys(selectedNode.value).forEach(key => delete (selectedNode.value as any)[key])
    Object.assign(selectedNode.value, parsed)
    normalizeNode(selectedNode.value)
    refreshSelectedNodeSource()
    message.success('节点源码已应用')
  } catch (error: any) {
    message.error(error.message || '节点源码格式不正确')
  }
}

function addParam(list?: any[]) {
  list?.push({ key: '', type: 1, value: '' })
}

function removeParam(list: any[] | undefined, index: number) {
  list?.splice(index, 1)
}

function addKeyValue(list?: any[]) {
  list?.push({ source: '', target: '' })
}

function removeKeyValue(list: any[] | undefined, index: number) {
  list?.splice(index, 1)
}

function validateNode(node?: SimpleFlowNode) {
  if (!node) return
  if (!node.name) throw new Error('节点名称不能为空')
  if (node.type === BpmNodeTypeEnum.TASK_CENTER_NODE) {
    const error = workflowTaskSettingError(node.taskCenterSetting)
    if (error) throw new Error(`${node.name}：${error}`)
  }
  if (node.type === BpmNodeTypeEnum.USER_TASK_NODE && node.approveType === undefined)
    throw new Error('审批节点需要配置审批类型')
  if (node.formBinding) {
    const error = bindingError(node.formBinding)
    if (error) throw new Error(`${node.name}：${error}`)
  }
  node.conditionNodes?.forEach(validateNode)
  validateNode(node.childNode)
}

async function validate() {
  if (taskConfigurationBusy.value)
    throw new Error('任务配置加载中，请稍后保存')
  validateNode(internalFlow.value)
  return taskSettingsWire(internalFlow.value, true)
}

async function handleSave() {
  const data = await validate()
  emit('success', data)
  message.success('流程配置已更新')
}

watch(() => [props.modelValue, props.flowNode], syncFromProps, { immediate: true, deep: true })
watch(internalFlow, value => emit('update:modelValue', taskSettingsWire(value, true)), { deep: true })

defineExpose({ validate })
</script>

<template>
  <div class="simple-designer">
    <div class="designer-canvas">
      <ProcessNodeTree :flow-node="internalFlow" @edit="openNodeConfig" />
    </div>

    <a-drawer
      v-model:open="drawerOpen"
      title="节点配置"
      :width="isTaskCenterNode ? '92vw' : 460"
      :mask-closable="!taskConfigurationBusy"
    >
      <template #footer>
        <div class="drawer-footer">
          <a-popconfirm
            v-if="canDeleteSelectedNode"
            cancel-text="取消"
            ok-text="删除"
            title="确定删除该节点吗？"
            @confirm="handleDeleteSelectedNode"
          >
            <a-button danger>
              <DeleteOutlined />
              删除节点
            </a-button>
          </a-popconfirm>
          <span v-else />
          <a-space>
            <a-button @click="drawerOpen = false">关闭</a-button>
            <a-button type="primary" :disabled="taskConfigurationBusy" @click="handleSaveNodeConfig">
              <SaveOutlined />
              保存配置
            </a-button>
          </a-space>
        </div>
      </template>
      <a-form v-if="selectedNode" :model="selectedNode" layout="vertical">
        <div v-if="isStartNode" class="start-form-config">
          <a-tag>发起表单</a-tag>
          <p>{{ formSourceSummary(inheritedForm) }}</p>
          <p class="start-form-hint">与向导中的发起表单使用同一份配置，后续节点可以独立设置。</p>
          <a-button @click="configureStartForm">配置发起表单</a-button>
        </div>
        <NodeFormEditor
          v-if="isApproveNode"
          :key="selectedNode.id"
          :binding="selectedNode.formBinding"
          :inherited="
            inheritedForm || { kind: 'FLOW_FORM', formId: modelFormId == null ? undefined : String(modelFormId) }
          "
          @apply="applyNodeForm"
        />
        <a-form-item label="节点名称">
          <a-input v-model:value="selectedNode.name" />
        </a-form-item>
        <a-form-item v-if="!isTaskCenterNode" label="展示文本">
          <a-textarea v-model:value="selectedNode.showText" :auto-size="{ minRows: 2 }" />
        </a-form-item>
        <WorkflowTaskNodeEditor
          v-if="isTaskCenterNode && selectedNode.taskCenterSetting"
          :key="selectedNode.id"
          v-model="selectedNode.taskCenterSetting"
          :form-id="taskPersonnelFormId"
          @busy="taskConfigurationBusy = $event"
        />

        <template v-if="isApproveNode">
          <a-divider>{{ isBusinessNode ? '业务任务人员与规则' : '审批设置' }}</a-divider>
          <a-alert
            v-if="isBusinessNode"
            type="info"
            show-icon
            message="业务任务由一人填写并提交材料后完成，使用独立绑定的业务表单。"
          />
          <a-form-item v-if="!isBusinessNode" label="审批类型">
            <a-select v-model:value="selectedNode.approveType" :options="APPROVE_TYPE" :disabled="isBusinessNode" />
          </a-form-item>
          <a-form-item :label="isBusinessNode ? '办理人策略' : '审批人策略'">
            <a-select v-model:value="selectedNode.candidateStrategy" :options="CANDIDATE_STRATEGY" allow-clear />
          </a-form-item>
          <a-form-item v-if="selectedNode.candidateStrategy === CandidateStrategy.USER" label="指定成员">
            <div class="user-picker">
              <div class="user-tags" @click="openUserSelector('candidate')">
                <a-tag v-for="id in candidateUserIds" :key="id">
                  {{ userLabel(id) }}
                </a-tag>
                <span v-if="!candidateUserIds.length" class="user-placeholder">
                  {{ isBusinessNode ? '请选择办理成员' : '请选择审批成员' }}
                </span>
              </div>
              <a-space>
                <a-button @click="openUserSelector('candidate')">
                  <UserOutlined />
                  选择成员
                </a-button>
                <a-button v-if="candidateUserIds.length" type="link" @click="clearSelectedUsers('candidate')">
                  清空
                </a-button>
              </a-space>
            </div>
          </a-form-item>
          <a-form-item v-else-if="selectedNode.candidateStrategy !== CandidateStrategy.PROJECT_OWNER" label="候选参数">
            <a-input v-model:value="selectedNode.candidateParam" placeholder="用户/角色/部门等编号，多个用逗号分隔" />
          </a-form-item>
          <a-alert v-else message="项目负责人由业务模块在发起流程时自动解析" show-icon type="info" />
          <a-form-item v-if="!isBusinessNode" label="多人审批方式">
            <a-select
              v-model:value="selectedNode.approveMethod"
              :options="APPROVE_METHODS"
              :disabled="isBusinessNode"
            />
          </a-form-item>
          <a-form-item v-if="selectedNode.approveMethod === ApproveMethodType.APPROVE_BY_RATIO" label="通过比例">
            <a-input-number
              v-model:value="selectedNode.approveRatio"
              :max="100"
              :min="1"
              addon-after="%"
              class="full-control"
            />
          </a-form-item>
          <a-form-item v-if="!isBusinessNode" label="允许加签">
            <a-switch v-model:checked="selectedNode.signEnable" />
          </a-form-item>
          <a-form-item v-if="!isBusinessNode" label="审批意见必填">
            <a-switch v-model:checked="selectedNode.reasonRequire" />
          </a-form-item>

          <template v-if="!isBusinessNode">
            <a-divider>按钮权限</a-divider>
            <div v-for="button in selectedNode.buttonsSetting" :key="button.id" class="button-row">
              <a-switch v-model:checked="button.enable" />
              <a-input v-model:value="button.displayName" />
            </div>
          </template>

          <a-divider>{{ isBusinessNode ? '办理规则' : '审批处理' }}</a-divider>
          <a-form-item label="拒绝处理">
            <a-select v-model:value="selectedNode.rejectHandler!.type" :options="REJECT_HANDLER_TYPES" />
          </a-form-item>
          <a-form-item
            v-if="selectedNode.rejectHandler!.type === RejectHandlerType.RETURN_USER_TASK"
            label="退回节点 ID"
          >
            <a-input v-model:value="selectedNode.rejectHandler!.returnNodeId" />
          </a-form-item>
          <a-form-item v-if="selectedNode.assignEmptyHandler" label="审批人为空">
            <a-select v-model:value="selectedNode.assignEmptyHandler!.type" :options="ASSIGN_EMPTY_HANDLER_TYPES" />
          </a-form-item>
          <a-form-item
            v-if="selectedNode.assignEmptyHandler?.type === AssignEmptyHandlerType.ASSIGN_USER"
            label="指定审批人"
          >
            <div class="user-picker">
              <div class="user-tags" @click="openUserSelector('assignEmpty')">
                <a-tag v-for="id in assignEmptyUserIds" :key="id">
                  {{ userLabel(id) }}
                </a-tag>
                <span v-if="!assignEmptyUserIds.length" class="user-placeholder">请选择审批人</span>
              </div>
              <a-space>
                <a-button @click="openUserSelector('assignEmpty')">
                  <UserOutlined />
                  选择审批人
                </a-button>
                <a-button v-if="assignEmptyUserIds.length" type="link" @click="clearSelectedUsers('assignEmpty')">
                  清空
                </a-button>
              </a-space>
            </div>
          </a-form-item>
          <a-form-item :label="isBusinessNode ? '办理人与发起人相同' : '审批人与发起人相同'">
            <a-select
              v-model:value="selectedNode.assignStartUserHandlerType"
              :options="ASSIGN_START_USER_HANDLER_TYPES"
              :disabled="isBusinessNode"
            />
          </a-form-item>
          <a-form-item v-if="!isBusinessNode" label="跳过表达式">
            <a-textarea v-model:value="selectedNode.skipExpression" :auto-size="{ minRows: 2 }" />
          </a-form-item>

          <a-divider>超时处理</a-divider>
          <a-form-item label="启用超时处理">
            <a-switch v-model:checked="selectedNode.timeoutHandler!.enable" />
          </a-form-item>
          <template v-if="selectedNode.timeoutHandler!.enable">
            <a-form-item label="处理动作">
              <a-select v-model:value="selectedNode.timeoutHandler!.type" :options="TIMEOUT_HANDLER_TYPES" />
            </a-form-item>
            <a-form-item label="超时时间表达式">
              <a-input v-model:value="selectedNode.timeoutHandler!.timeDuration" placeholder="例如 PT2H" />
            </a-form-item>
            <a-form-item label="最大提醒次数">
              <a-input-number
                v-model:value="selectedNode.timeoutHandler!.maxRemindCount"
                :min="1"
                class="full-control"
              />
            </a-form-item>
          </template>

          <a-divider>任务监听器</a-divider>
          <a-collapse ghost>
            <a-collapse-panel key="create" header="创建监听器">
              <a-form-item label="启用">
                <a-switch v-model:checked="selectedNode.taskCreateListener!.enable" />
              </a-form-item>
              <a-form-item label="请求路径">
                <a-input v-model:value="selectedNode.taskCreateListener!.path" />
              </a-form-item>
            </a-collapse-panel>
            <a-collapse-panel key="assign" header="分配监听器">
              <a-form-item label="启用">
                <a-switch v-model:checked="selectedNode.taskAssignListener!.enable" />
              </a-form-item>
              <a-form-item label="请求路径">
                <a-input v-model:value="selectedNode.taskAssignListener!.path" />
              </a-form-item>
            </a-collapse-panel>
            <a-collapse-panel key="complete" header="完成监听器">
              <a-form-item label="启用">
                <a-switch v-model:checked="selectedNode.taskCompleteListener!.enable" />
              </a-form-item>
              <a-form-item label="请求路径">
                <a-input v-model:value="selectedNode.taskCompleteListener!.path" />
              </a-form-item>
            </a-collapse-panel>
          </a-collapse>
        </template>

        <template v-if="isCopyNode">
          <a-divider>抄送设置</a-divider>
          <a-form-item label="抄送人策略">
            <a-select v-model:value="selectedNode.candidateStrategy" :options="CANDIDATE_STRATEGY" allow-clear />
          </a-form-item>
          <a-form-item v-if="selectedNode.candidateStrategy === CandidateStrategy.USER" label="指定成员">
            <div class="user-picker">
              <div class="user-tags" @click="openUserSelector('candidate')">
                <a-tag v-for="id in candidateUserIds" :key="id">
                  {{ userLabel(id) }}
                </a-tag>
                <span v-if="!candidateUserIds.length" class="user-placeholder">请选择抄送成员</span>
              </div>
              <a-space>
                <a-button @click="openUserSelector('candidate')">
                  <UserOutlined />
                  选择成员
                </a-button>
                <a-button v-if="candidateUserIds.length" type="link" @click="clearSelectedUsers('candidate')">
                  清空
                </a-button>
              </a-space>
            </div>
          </a-form-item>
          <a-form-item v-else-if="selectedNode.candidateStrategy !== CandidateStrategy.PROJECT_OWNER" label="候选参数">
            <a-input v-model:value="selectedNode.candidateParam" placeholder="用户/角色/部门等编号，多个用逗号分隔" />
          </a-form-item>
          <a-alert v-else message="项目负责人由业务模块在发起流程时自动解析" show-icon type="info" />
        </template>

        <template v-if="isConditionNode">
          <a-divider>条件设置</a-divider>
          <a-form-item label="条件类型">
            <a-select v-model:value="selectedNode.conditionSetting!.conditionType" :options="CONDITION_CONFIG_TYPES" />
          </a-form-item>
          <a-form-item label="默认分支">
            <a-switch v-model:checked="selectedNode.conditionSetting!.defaultFlow" />
          </a-form-item>
          <a-form-item label="条件表达式">
            <a-textarea
              v-model:value="selectedNode.conditionSetting!.conditionExpression"
              :auto-size="{ minRows: 3 }"
            />
          </a-form-item>
        </template>

        <template v-if="isDelayNode">
          <a-divider>延迟设置</a-divider>
          <a-form-item label="延迟类型">
            <a-select v-model:value="selectedNode.delaySetting!.delayType" :options="DELAY_TYPE" />
          </a-form-item>
          <a-form-item label="延迟表达式">
            <a-input
              v-model:value="selectedNode.delaySetting!.delayTime"
              placeholder="例如 PT1H 或 2026-01-01 10:00:00"
            />
          </a-form-item>
        </template>

        <template v-if="isTriggerNode">
          <a-divider>触发器设置</a-divider>
          <a-form-item label="触发器类型">
            <a-select v-model:value="selectedNode.triggerSetting!.type" :options="TRIGGER_TYPES" />
          </a-form-item>
          <a-form-item label="请求地址">
            <a-input
              v-model:value="selectedNode.triggerSetting!.httpRequestSetting!.url"
              placeholder="https://example.com/callback"
            />
          </a-form-item>
          <a-divider>请求头</a-divider>
          <div
            v-for="(item, index) in selectedNode.triggerSetting!.httpRequestSetting!.header"
            :key="`header-${index}`"
            class="param-row"
          >
            <a-input v-model:value="item.key" placeholder="参数名" />
            <a-select v-model:value="item.type" :options="BPM_HTTP_REQUEST_PARAM_TYPES" />
            <a-input v-model:value="item.value" placeholder="参数值" />
            <a-button danger @click="removeParam(selectedNode!.triggerSetting!.httpRequestSetting!.header, index)">
              删除
            </a-button>
          </div>
          <a-button block type="dashed" @click="addParam(selectedNode.triggerSetting!.httpRequestSetting!.header)">
            添加请求头
          </a-button>
          <a-divider>请求体</a-divider>
          <div
            v-for="(item, index) in selectedNode.triggerSetting!.httpRequestSetting!.body"
            :key="`body-${index}`"
            class="param-row"
          >
            <a-input v-model:value="item.key" placeholder="参数名" />
            <a-select v-model:value="item.type" :options="BPM_HTTP_REQUEST_PARAM_TYPES" />
            <a-input v-model:value="item.value" placeholder="参数值" />
            <a-button danger @click="removeParam(selectedNode!.triggerSetting!.httpRequestSetting!.body, index)">
              删除
            </a-button>
          </div>
          <a-button block type="dashed" @click="addParam(selectedNode.triggerSetting!.httpRequestSetting!.body)">
            添加请求体参数
          </a-button>
        </template>

        <template v-if="isChildProcessNode">
          <a-divider>子流程设置</a-divider>
          <a-form-item label="流程标识">
            <a-input v-model:value="selectedNode.childProcessSetting!.calledProcessDefinitionKey" />
          </a-form-item>
          <a-form-item label="流程名称">
            <a-input v-model:value="selectedNode.childProcessSetting!.calledProcessDefinitionName" />
          </a-form-item>
          <a-form-item label="异步发起">
            <a-switch v-model:checked="selectedNode.childProcessSetting!.async" />
          </a-form-item>
          <a-form-item label="跳过子流程发起人节点">
            <a-switch v-model:checked="selectedNode.childProcessSetting!.skipStartUserNode" />
          </a-form-item>
          <a-form-item label="发起人类型">
            <a-select
              v-model:value="selectedNode.childProcessSetting!.startUserSetting.type"
              :options="CHILD_PROCESS_START_USER_TYPE"
            />
          </a-form-item>
          <a-form-item label="发起人表单字段">
            <a-input v-model:value="selectedNode.childProcessSetting!.startUserSetting.formField" />
          </a-form-item>
          <a-form-item label="发起人为空处理">
            <a-select
              v-model:value="selectedNode.childProcessSetting!.startUserSetting.emptyType"
              :options="CHILD_PROCESS_START_USER_EMPTY_TYPE"
              allow-clear
            />
          </a-form-item>
          <a-divider>超时设置</a-divider>
          <a-form-item label="启用">
            <a-switch v-model:checked="selectedNode.childProcessSetting!.timeoutSetting.enable" />
          </a-form-item>
          <a-form-item label="超时表达式">
            <a-input v-model:value="selectedNode.childProcessSetting!.timeoutSetting.timeExpression" />
          </a-form-item>
          <a-divider>多实例设置</a-divider>
          <a-form-item label="启用">
            <a-switch v-model:checked="selectedNode.childProcessSetting!.multiInstanceSetting.enable" />
          </a-form-item>
          <a-form-item label="串行执行">
            <a-switch v-model:checked="selectedNode.childProcessSetting!.multiInstanceSetting.sequential" />
          </a-form-item>
          <a-form-item label="来源类型">
            <a-select
              v-model:value="selectedNode.childProcessSetting!.multiInstanceSetting.sourceType"
              :options="CHILD_PROCESS_MULTI_INSTANCE_SOURCE_TYPE"
              allow-clear
            />
          </a-form-item>
          <a-form-item label="来源值">
            <a-input v-model:value="selectedNode.childProcessSetting!.multiInstanceSetting.source" />
          </a-form-item>
          <a-form-item label="通过比例">
            <a-input-number
              v-model:value="selectedNode.childProcessSetting!.multiInstanceSetting.approveRatio"
              :max="100"
              :min="1"
              addon-after="%"
              class="full-control"
            />
          </a-form-item>
          <a-divider>入参映射</a-divider>
          <div
            v-for="(item, index) in selectedNode.childProcessSetting!.inVariables"
            :key="`in-${index}`"
            class="param-row"
          >
            <a-input v-model:value="item.source" placeholder="主流程变量" />
            <a-input v-model:value="item.target" placeholder="子流程变量" />
            <a-button danger @click="removeKeyValue(selectedNode!.childProcessSetting!.inVariables, index)">
              删除
            </a-button>
          </div>
          <a-button block type="dashed" @click="addKeyValue(selectedNode.childProcessSetting!.inVariables)">
            添加入参
          </a-button>
          <a-divider>出参映射</a-divider>
          <div
            v-for="(item, index) in selectedNode.childProcessSetting!.outVariables"
            :key="`out-${index}`"
            class="param-row"
          >
            <a-input v-model:value="item.source" placeholder="子流程变量" />
            <a-input v-model:value="item.target" placeholder="主流程变量" />
            <a-button danger @click="removeKeyValue(selectedNode!.childProcessSetting!.outVariables, index)">
              删除
            </a-button>
          </div>
          <a-button block type="dashed" @click="addKeyValue(selectedNode.childProcessSetting!.outVariables)">
            添加出参
          </a-button>
        </template>

        <a-collapse class="advanced-source" ghost>
          <a-collapse-panel key="source" header="高级配置 · 节点源码">
            <a-space class="source-actions">
              <a-button @click="refreshSelectedNodeSource">刷新源码</a-button>
              <a-button type="primary" @click="applySelectedNodeSource">应用源码</a-button>
            </a-space>
            <a-textarea v-model:value="nodeSourceText" :auto-size="{ minRows: 10 }" class="source-editor" />
          </a-collapse-panel>
        </a-collapse>
      </a-form>
    </a-drawer>

    <UserSelector
      v-model:visible="userSelectorOpen"
      :multiple="true"
      :selected-users="userSelectorSelectedUsers"
      :show-multiple-toggle="false"
      :title="userSelectorTitle"
      @confirm="handleUserSelectorConfirm"
    />
  </div>
</template>

<style scoped>
.start-form-config {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  margin-bottom: 16px;
}
.start-form-hint {
  color: var(--text-secondary);
  font-size: 12px;
}
.advanced-source {
  margin-top: 16px;
}
.simple-designer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 640px;
  background: #f6f7f9;
}

.designer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}

.designer-title {
  color: #101828;
  font-size: 15px;
  font-weight: 600;
}

.designer-subtitle {
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.designer-canvas {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 28px 20px 60px;
  background:
    linear-gradient(rgba(16, 24, 40, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(16, 24, 40, 0.04) 1px, transparent 1px);
  background-size: 18px 18px;
}

.full-control {
  width: 100%;
}

.button-row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.param-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 116px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.user-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.user-tags {
  min-height: 34px;
  padding: 5px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
}

.user-tags:hover {
  border-color: #1677ff;
}

.user-placeholder {
  color: #98a2b3;
  line-height: 22px;
}

.source-actions {
  margin-bottom: 8px;
}

.source-editor {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
}
</style>
