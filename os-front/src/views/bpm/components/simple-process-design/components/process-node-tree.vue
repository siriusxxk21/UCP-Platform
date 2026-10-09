<script lang="ts" setup>
import type { SimpleFlowNode } from '../consts'
import {
  ApartmentOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  ForkOutlined,
  FormOutlined,
  PlusOutlined,
  SendOutlined,
  UserOutlined
} from '@ant-design/icons-vue'
import { computed, ref } from 'vue'
import { BpmNodeTypeEnum } from '@/types/bpm'
import {
  ApproveMethodType,
  AssignEmptyHandlerType,
  AssignStartUserHandlerType,
  CandidateStrategy,
  ConditionType,
  DEFAULT_CONDITION_GROUP_VALUE,
  NODE_DEFAULT_NAME,
  NODE_DEFAULT_TEXT,
  RejectHandlerType
} from '../consts'

defineOptions({ name: 'SimpleProcessNodeTree' })

const props = defineProps<{
  flowNode?: SimpleFlowNode
  parentNode?: SimpleFlowNode
  branchParent?: SimpleFlowNode
  branchIndex?: number
  readonly?: boolean
  nodeStates?: Record<string, string>
  taskNodeIds?: string[]
}>()

type NodeEditContext = {
  parentNode?: SimpleFlowNode
  branchParent?: SimpleFlowNode
  branchIndex?: number
}

const emit = defineEmits<{
  edit: [node: SimpleFlowNode, context: NodeEditContext]
  openTask: [nodeId: string]
}>()

const isBranchContainer = computed(() =>
  [
    BpmNodeTypeEnum.CONDITION_BRANCH_NODE,
    BpmNodeTypeEnum.PARALLEL_BRANCH_NODE,
    BpmNodeTypeEnum.INCLUSIVE_BRANCH_NODE,
    BpmNodeTypeEnum.ROUTER_BRANCH_NODE
  ].includes(props.flowNode?.type as BpmNodeTypeEnum)
)

const isEndNode = computed(() => props.flowNode?.type === BpmNodeTypeEnum.END_EVENT_NODE)
const isStartNode = computed(() => props.flowNode?.type === BpmNodeTypeEnum.START_USER_NODE)
const isConditionNode = computed(() => props.flowNode?.type === BpmNodeTypeEnum.CONDITION_NODE)
const addMenuOpen = ref(false)
const stateLabels: Record<string, string> = {
  running: '办理中',
  finished: '已完成',
  rejected: '已拒绝',
  invalidated: '已结束',
  blocked: '处理受阻',
}

const addOptions = [
  { type: BpmNodeTypeEnum.USER_TASK_NODE, label: '审批节点', icon: UserOutlined },
  { type: BpmNodeTypeEnum.TRANSACTOR_NODE, label: '业务任务', icon: FormOutlined },
  { type: BpmNodeTypeEnum.TASK_CENTER_NODE, label: '任务节点', icon: ApartmentOutlined },
  { type: BpmNodeTypeEnum.COPY_TASK_NODE, label: '抄送人', icon: CopyOutlined },
  { type: BpmNodeTypeEnum.CONDITION_BRANCH_NODE, label: '条件分支', icon: BranchesOutlined },
  { type: BpmNodeTypeEnum.PARALLEL_BRANCH_NODE, label: '并行分支', icon: ForkOutlined },
  { type: BpmNodeTypeEnum.INCLUSIVE_BRANCH_NODE, label: '包容分支', icon: ApartmentOutlined },
  { type: BpmNodeTypeEnum.DELAY_TIMER_NODE, label: '延迟器', icon: ClockCircleOutlined },
  { type: BpmNodeTypeEnum.TRIGGER_NODE, label: '触发器', icon: SendOutlined },
  { type: BpmNodeTypeEnum.CHILD_PROCESS_NODE, label: '子流程', icon: ApartmentOutlined }
]

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value))
}

function uuid(prefix: string) {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`
}

function baseNode(type: BpmNodeTypeEnum, childNode?: SimpleFlowNode): SimpleFlowNode {
  const node: SimpleFlowNode = {
    id:
      type === BpmNodeTypeEnum.CONDITION_BRANCH_NODE ||
      type === BpmNodeTypeEnum.PARALLEL_BRANCH_NODE ||
      type === BpmNodeTypeEnum.INCLUSIVE_BRANCH_NODE
        ? uuid('Gateway')
        : uuid('Activity'),
    type,
    name: NODE_DEFAULT_NAME.get(type) || '节点',
    showText: NODE_DEFAULT_TEXT.get(type) || '',
    childNode
  }

  if (type === BpmNodeTypeEnum.USER_TASK_NODE || type === BpmNodeTypeEnum.TRANSACTOR_NODE) {
    Object.assign(node, {
      candidateStrategy: CandidateStrategy.USER,
      approveMethod: ApproveMethodType.SEQUENTIAL_APPROVE,
      rejectHandler: { type: RejectHandlerType.FINISH_PROCESS },
      timeoutHandler: { enable: false },
      assignEmptyHandler: { type: AssignEmptyHandlerType.APPROVE },
      assignStartUserHandlerType: AssignStartUserHandlerType.START_USER_AUDIT,
      taskCreateListener: { enable: false },
      taskAssignListener: { enable: false },
      taskCompleteListener: { enable: false }
    })
  }

  if (type === BpmNodeTypeEnum.COPY_TASK_NODE) {
    node.candidateStrategy = CandidateStrategy.USER
  }
  if (type === BpmNodeTypeEnum.TRANSACTOR_NODE) {
    node.name = '业务任务'
    node.showText = '请绑定业务表单并设置办理人'
    node.formBinding = { mode: 'OVERRIDE', taskMode: 'BUSINESS', source: { kind: 'APPLICATION_RESOURCE' } }
    node.approveType = 1
    node.approveMethod = ApproveMethodType.RANDOM_SELECT_ONE_APPROVE
    node.assignStartUserHandlerType = AssignStartUserHandlerType.START_USER_AUDIT
    delete node.assignEmptyHandler
  }

  if (type === BpmNodeTypeEnum.DELAY_TIMER_NODE) {
    node.delaySetting = { delayType: 1, delayTime: '' }
  }

  if (type === BpmNodeTypeEnum.TRIGGER_NODE) {
    node.triggerSetting = { type: 1, httpRequestSetting: { url: '' } }
  }

  if (type === BpmNodeTypeEnum.CHILD_PROCESS_NODE) {
    node.childProcessSetting = {
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
  }

  if (
    [
      BpmNodeTypeEnum.CONDITION_BRANCH_NODE,
      BpmNodeTypeEnum.PARALLEL_BRANCH_NODE,
      BpmNodeTypeEnum.INCLUSIVE_BRANCH_NODE
    ].includes(type)
  ) {
    const conditional = type !== BpmNodeTypeEnum.PARALLEL_BRANCH_NODE
    node.conditionNodes = [
      createBranchNode(conditional ? '条件1' : '并行1', conditional),
      createBranchNode(conditional ? '其它情况' : '并行2', false, conditional)
    ]
  }

  return node
}

function createBranchNode(name: string, configurable = true, defaultFlow = false): SimpleFlowNode {
  return {
    id: uuid('Flow'),
    type: BpmNodeTypeEnum.CONDITION_NODE,
    name,
    showText: defaultFlow ? '未满足其它条件时进入此分支' : configurable ? '请设置条件' : '无需配置条件同时执行',
    conditionSetting: configurable
      ? {
          defaultFlow,
          conditionType: ConditionType.RULE,
          conditionGroups: clone(DEFAULT_CONDITION_GROUP_VALUE)
        }
      : { defaultFlow }
  }
}

function addAfter(type: BpmNodeTypeEnum) {
  if (!props.flowNode) return
  props.flowNode.childNode = baseNode(type, props.flowNode.childNode)
  addMenuOpen.value = false
}

function addBranch() {
  if (!props.flowNode) return
  const index = (props.flowNode.conditionNodes?.length || 0) + 1
  const configurable = props.flowNode.type !== BpmNodeTypeEnum.PARALLEL_BRANCH_NODE
  props.flowNode.conditionNodes ||= []
  props.flowNode.conditionNodes.splice(
    Math.max(props.flowNode.conditionNodes.length - 1, 0),
    0,
    createBranchNode(configurable ? `条件${index}` : `并行${index}`, configurable)
  )
}

function removeCurrent() {
  if (!props.flowNode || isStartNode.value || isEndNode.value) return
  if (props.branchParent && typeof props.branchIndex === 'number') {
    props.branchParent.conditionNodes?.splice(props.branchIndex, 1)
    return
  }
  if (props.parentNode?.childNode === props.flowNode) {
    props.parentNode.childNode = props.flowNode.childNode
  }
}

function emitEdit(node: SimpleFlowNode) {
  if (props.readonly) return
  emit('edit', node, {
    parentNode: props.parentNode,
    branchParent: props.branchParent,
    branchIndex: props.branchIndex
  })
}

function forwardEdit(node: SimpleFlowNode, context: NodeEditContext) {
  emit('edit', node, context)
}

function nodeClass(node?: SimpleFlowNode) {
  if (!node) return ''
  return {
    'node-start': node.type === BpmNodeTypeEnum.START_USER_NODE,
    'node-end': node.type === BpmNodeTypeEnum.END_EVENT_NODE,
    'node-approve': node.type === BpmNodeTypeEnum.USER_TASK_NODE || node.type === BpmNodeTypeEnum.TRANSACTOR_NODE,
    'node-copy': node.type === BpmNodeTypeEnum.COPY_TASK_NODE,
    'node-branch': isBranchContainer.value || node.type === BpmNodeTypeEnum.CONDITION_NODE,
    'node-delay': node.type === BpmNodeTypeEnum.DELAY_TIMER_NODE,
    'node-trigger': node.type === BpmNodeTypeEnum.TRIGGER_NODE,
    'node-child': node.type === BpmNodeTypeEnum.CHILD_PROCESS_NODE,
    'node-task-center': node.type === BpmNodeTypeEnum.TASK_CENTER_NODE,
    'node-business': node.formBinding?.source?.kind === 'APPLICATION_RESOURCE'
  }
}

function nodeTypeText(node?: SimpleFlowNode) {
  if (!node) return ''
  if (node.type === BpmNodeTypeEnum.START_USER_NODE) return '开始'
  if (node.type === BpmNodeTypeEnum.END_EVENT_NODE) return '结束'
  if (node.type === BpmNodeTypeEnum.CONDITION_NODE) return '分支'
  if (isBranchContainer.value) return '网关'
  if (node.formBinding?.source?.kind === 'APPLICATION_RESOURCE') return '业务任务'
  return NODE_DEFAULT_NAME.get(node.type) || '节点'
}
</script>

<template>
  <div v-if="flowNode" class="flow-chain">
    <div class="node-wrap">
      <div
        :class="[
          nodeClass(flowNode),
          { 'node-readonly': readonly },
          nodeStates?.[flowNode.id] ? `state-${nodeStates[flowNode.id]}` : ''
        ]"
        class="flow-node"
        @click="emitEdit(flowNode)"
      >
        <div class="node-head">
          <span class="node-type">{{ nodeTypeText(flowNode) }}</span>
          <a-tag v-if="nodeStates?.[flowNode.id]" class="node-state">
            {{
              flowNode.type === BpmNodeTypeEnum.TASK_CENTER_NODE && nodeStates[flowNode.id] === 'running'
                ? '等待任务完成'
                : stateLabels[nodeStates[flowNode.id]] || nodeStates[flowNode.id]
            }}
          </a-tag>
          <a-space v-if="!readonly" :size="2" class="node-actions" @click.stop>
            <a-button size="small" type="text" @click="emitEdit(flowNode)">
              <EditOutlined />
            </a-button>
            <a-popconfirm v-if="!isStartNode && !isEndNode" title="确定删除该节点吗？" @confirm="removeCurrent">
              <a-button danger size="small" type="text">
                <DeleteOutlined />
              </a-button>
            </a-popconfirm>
          </a-space>
        </div>
        <div class="node-title">{{ flowNode.name }}</div>
        <div v-if="flowNode.showText" class="node-desc">{{ flowNode.showText }}</div>
        <a-button
          v-if="readonly && taskNodeIds?.includes(flowNode.id)"
          type="link"
          size="small"
          @click.stop="emit('openTask', flowNode.id)"
        >
          查看任务
        </a-button>
      </div>

      <a-dropdown v-if="!readonly && !isEndNode && !isConditionNode" v-model:open="addMenuOpen" trigger="click">
        <button class="add-node" type="button">
          <PlusOutlined />
        </button>
        <template #overlay>
          <a-menu>
            <a-menu-item v-for="item in addOptions" :key="item.type" @click="addAfter(item.type)">
              <component :is="item.icon" />
              {{ item.label }}
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>

    <div v-if="isBranchContainer" class="branch-shell">
      <div v-if="!readonly" class="branch-toolbar">
        <a-button size="small" type="link" @click="addBranch">
          <PlusOutlined />
          添加分支
        </a-button>
      </div>
      <div class="branch-list">
        <div v-for="(branch, index) in flowNode.conditionNodes" :key="branch.id" class="branch-column">
          <SimpleProcessNodeTree
            :branch-index="index"
            :branch-parent="flowNode"
            :flow-node="branch"
            :readonly="readonly"
            :node-states="nodeStates"
            :task-node-ids="taskNodeIds"
            @edit="forwardEdit"
            @open-task="emit('openTask', $event)"
          />
        </div>
      </div>
    </div>

    <SimpleProcessNodeTree
      v-if="flowNode.childNode"
      :flow-node="flowNode.childNode"
      :parent-node="flowNode"
      :readonly="readonly"
      :node-states="nodeStates"
      :task-node-ids="taskNodeIds"
      @edit="forwardEdit"
      @open-task="emit('openTask', $event)"
    />
  </div>
</template>

<style scoped>
.flow-chain {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.node-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}

.node-wrap::after {
  content: '';
  width: 1px;
  height: 22px;
  background: #d0d5dd;
}

.node-wrap:has(.node-end)::after {
  display: none;
}

.flow-node {
  width: 220px;
  min-height: 92px;
  padding: 10px 12px;
  border: 1px solid #d0d5dd;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(16, 24, 40, 0.06);
  cursor: pointer;
}

.flow-node:hover {
  border-color: #1677ff;
}
.node-readonly {
  cursor: default;
}
.node-business .node-type {
  background: #ecfeff;
  color: #0e7490;
}
.node-task-center .node-type {
  background: var(--brand-light);
  color: var(--brand);
}
.state-running {
  border-color: var(--primary-color, #6956db);
  box-shadow: 0 0 0 2px #6956db1a;
}
.state-finished {
  border-color: #52a675;
}
.state-rejected {
  border-color: #d85d5d;
}
.state-blocked {
  border-color: var(--warning-color, #d89614);
}
.state-invalidated {
  border-color: var(--border);
  opacity: 0.8;
}
.node-state {
  margin-inline-end: 0;
  font-size: 11px;
}

.node-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 24px;
}

.node-type {
  padding: 2px 8px;
  border-radius: 999px;
  background: #eef4ff;
  color: #1677ff;
  font-size: 12px;
}

.node-title {
  margin-top: 8px;
  color: #101828;
  font-weight: 600;
}

.node-desc {
  margin-top: 6px;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.node-start .node-type {
  background: #ecfdf3;
  color: #039855;
}

.node-end .node-type {
  background: #f2f4f7;
  color: #475467;
}

.node-copy .node-type {
  background: #fdf2fa;
  color: #c11574;
}

.node-branch .node-type {
  background: #fffaeb;
  color: #b54708;
}

.node-delay .node-type {
  background: #f4f3ff;
  color: #5925dc;
}

.node-trigger .node-type {
  background: #eff8ff;
  color: #175cd3;
}

.node-child .node-type {
  background: #f0f9ff;
  color: #026aa2;
}

.add-node {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin: 8px 0;
  border: 1px solid #1677ff;
  border-radius: 50%;
  color: #1677ff;
  background: #fff;
  cursor: pointer;
}

.branch-shell {
  width: 100%;
  padding: 12px 0 20px;
}

.branch-toolbar {
  display: flex;
  justify-content: center;
  margin-bottom: 8px;
}

.branch-list {
  display: flex;
  justify-content: center;
  gap: 16px;
  overflow-x: auto;
  padding: 8px 12px 12px;
}

.branch-column {
  min-width: 260px;
  padding: 12px;
  border: 1px dashed #d0d5dd;
  border-radius: 8px;
  background: #f9fafb;
}

.node-actions {
  opacity: 0;
  transition: opacity 0.15s ease;
}

.flow-node:hover .node-actions {
  opacity: 1;
}
</style>
