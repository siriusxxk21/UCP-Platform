<script lang="ts" setup>
import type { BpmProcessDefinitionApi } from '@/api/bpm/definition'
import type { BpmProcessInstanceApi } from '@/api/bpm/processInstance'
import FormCreate from '@form-create/ant-design-vue'
import { ArrowLeftOutlined, CheckOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getProcessDefinition } from '@/api/bpm/definition'
import { createProcessInstance, getApprovalDetail as getApprovalDetailApi } from '@/api/bpm/processInstance'
import { decodeFields, setConfAndFields2 } from '@/components/form-create'
import { SimpleProcessViewer, type SimpleFlowNode } from '@/views/bpm/components/simple-process-design'
import { MyProcessViewer } from '@/views/bpm/components/bpmn-process-designer/package'

interface ProcessFormData {
  rule: any[]
  option: Record<string, any>
  value: Record<string, any>
}

interface StartUserSelectTask {
  id: string
  name: string
  candidateUsers?: BpmProcessInstanceApi.User[]
}

const props = defineProps<{
  selectProcessDefinition: BpmProcessDefinitionApi.ProcessDefinition
}>()

const emit = defineEmits<{
  cancel: []
}>()

const BPM_MODEL_FORM_TYPE_NORMAL = 10
const BPM_MODEL_FORM_TYPE_NONE = 0
const BPM_MODEL_FORM_TYPE_CUSTOM = 20
const BPM_CANDIDATE_STRATEGY_START_USER_SELECT = 35
const BPM_NODE_ID_START_USER = 'StartUserNode'
const BPM_FIELD_PERMISSION_READ = '1'
const BPM_FIELD_PERMISSION_WRITE = '2'
const BPM_FIELD_PERMISSION_NONE = '3'

const router = useRouter()
const activeTab = ref('form')
const submitLoading = ref(false)
const approvalLoading = ref(false)
const fApi = ref<any>()
const bpmnXML = ref('')
const simpleJson = ref('')
const simpleModel = ref<SimpleFlowNode>()
const activityNodes = ref<BpmProcessInstanceApi.ApprovalNodeInfo[]>([])
const startUserSelectTasks = ref<StartUserSelectTask[]>([])
const startUserSelectAssignees = reactive<Record<string, number[]>>({})
const lastAssignees = ref<Record<string, number[]>>({})
const initializing = ref(false)
const initializationError = ref('')
const approvalError = ref('')
let initializationGeneration = 0
let approvalGeneration = 0
const withoutForm = computed(() => props.selectProcessDefinition.formType === BPM_MODEL_FORM_TYPE_NONE)

const detailForm = ref<ProcessFormData>({
  rule: [],
  option: {},
  value: {}
})

const title = computed(() => `发起流程 · ${props.selectProcessDefinition.name}`)

async function initProcessInfo(row: BpmProcessDefinitionApi.ProcessDefinition, formVariables?: Record<string, any>) {
  const generation = ++initializationGeneration
  initializing.value = true
  initializationError.value = ''
  resetFormState()

  try {
    if (row.formType === BPM_MODEL_FORM_TYPE_CUSTOM) {
      if (!row.formCustomCreatePath) {
        message.error('未配置业务表单的提交路由，无法发起')
        emit('cancel')
        return
      }
      await router.push({ path: row.formCustomCreatePath })
      return
    }

    if (![BPM_MODEL_FORM_TYPE_NORMAL, BPM_MODEL_FORM_TYPE_NONE].includes(row.formType as number)) {
      message.error('当前流程定义不支持发起')
      emit('cancel')
      return
    }

    const safeVariables =
      row.formType === BPM_MODEL_FORM_TYPE_NONE ? {} : filterFormVariables(row.formFields, formVariables)
    if (row.formType === BPM_MODEL_FORM_TYPE_NORMAL)
      setConfAndFields2(detailForm, row.formConf, row.formFields, safeVariables)
    detailForm.value.option = {
      ...detailForm.value.option,
      submitBtn: false,
      resetBtn: false
    }

    await nextTick()
    await refreshApprovalDetail(safeVariables)
    if (generation !== initializationGeneration) return

    const definitionDetail = await getProcessDefinition(row.id)
    if (generation !== initializationGeneration) return
    if (!definitionDetail) throw new Error('流程定义读取失败，请重新进入后发起')
    bpmnXML.value = definitionDetail?.bpmnXml || ''
    simpleJson.value = definitionDetail?.simpleModel || ''
    simpleModel.value = parseSimpleModel(simpleJson.value)
  } catch (error: any) {
    if (generation === initializationGeneration) initializationError.value = error.message || '流程发起信息加载失败'
  } finally {
    if (generation === initializationGeneration) initializing.value = false
  }
}

function resetFormState() {
  fApi.value = undefined
  approvalGeneration++
  approvalError.value = ''
  approvalLoading.value = false
  activeTab.value = 'form'
  detailForm.value = {
    rule: [],
    option: {},
    value: {}
  }
  bpmnXML.value = ''
  simpleJson.value = ''
  simpleModel.value = undefined
  activityNodes.value = []
  startUserSelectTasks.value = []
  lastAssignees.value = {}
  Object.keys(startUserSelectAssignees).forEach(key => delete startUserSelectAssignees[key])
}

function parseSimpleModel(value?: string) {
  if (!value) return undefined
  try {
    return JSON.parse(value) as SimpleFlowNode
  } catch (error) {
    console.error('解析 SIMPLE 流程图失败:', error)
    message.error('解析 SIMPLE 流程图失败')
    return undefined
  }
}

function filterFormVariables(fields?: string[], formVariables?: Record<string, any>) {
  if (!formVariables) return undefined
  const allowedFields = new Set(
    decodeFields(fields)
      .map((field: any) => field.field)
      .filter(Boolean)
  )
  return Object.keys(formVariables).reduce<Record<string, any>>((result, key) => {
    if (allowedFields.has(key)) result[key] = formVariables[key]
    return result
  }, {})
}

async function submitForm() {
  if (
    submitLoading.value ||
    initializing.value ||
    approvalLoading.value ||
    initializationError.value ||
    approvalError.value
  )
    return
  submitLoading.value = true
  try {
    if (!withoutForm.value) {
      if (detailForm.value.rule.length && !fApi.value) return
      if (fApi.value) await fApi.value.validate()
    }
    for (const task of startUserSelectTasks.value) {
      const assignees = startUserSelectAssignees[task.id]
      if (!Array.isArray(assignees) || assignees.length === 0) {
        message.warning(`请选择${task.name}的候选人`)
        return
      }
    }
    await createProcessInstance({
      processDefinitionId: props.selectProcessDefinition.id,
      variables: withoutForm.value ? {} : detailForm.value.value,
      startUserSelectAssignees
    })
    message.success('发起流程成功')
    await router.push('/bpm/instance')
  } catch (error: any) {
    console.error('发起流程失败:', error)
    message.error(error.message || '发起流程失败')
  } finally {
    submitLoading.value = false
  }
}

async function refreshApprovalDetail(formVariables?: Record<string, any>) {
  const generation = ++approvalGeneration
  approvalLoading.value = true
  approvalError.value = ''
  try {
    const data = await getApprovalDetailApi({
      processDefinitionId: props.selectProcessDefinition.id,
      activityId: BPM_NODE_ID_START_USER,
      processVariablesStr: JSON.stringify(formVariables || {})
    })
    if (generation !== approvalGeneration) return
    if (!data) {
      throw new Error('查询不到审批详情信息')
    }

    activityNodes.value = data.activityNodes || []
    applyFieldPermission(data.formFieldsPermission)
    resetStartUserSelectTasks()
  } catch (error: any) {
    if (generation === approvalGeneration) approvalError.value = error.message || '加载审批详情失败'
  } finally {
    if (generation === approvalGeneration) approvalLoading.value = false
  }
}

function resetStartUserSelectTasks() {
  const previousAssignees = lastAssignees.value
  Object.keys(startUserSelectAssignees).forEach(key => delete startUserSelectAssignees[key])

  startUserSelectTasks.value = activityNodes.value
    .filter(node => node.candidateStrategy === BPM_CANDIDATE_STRATEGY_START_USER_SELECT)
    .map(node => ({
      id: node.id,
      name: node.name,
      candidateUsers: node.candidateUsers || []
    }))

  startUserSelectTasks.value.forEach(task => {
    startUserSelectAssignees[task.id] = previousAssignees[task.id]?.length ? [...previousAssignees[task.id]] : []
  })
}

function applyFieldPermission(permissionMap?: Record<string, string | number>) {
  if (!permissionMap) return
  Object.entries(permissionMap).forEach(([field, permission]) => {
    const value = String(permission)
    if (value === BPM_FIELD_PERMISSION_READ) fApi.value?.disabled(true, field)
    if (value === BPM_FIELD_PERMISSION_WRITE) fApi.value?.disabled(false, field)
    if (value === BPM_FIELD_PERMISSION_NONE) fApi.value?.hidden(true, field)
  })
}

function handleCancel() {
  if (submitLoading.value) return
  emit('cancel')
}

watch(
  () => detailForm.value.value,
  value => {
    if (initializing.value || !value || Object.keys(value).length === 0) return
    lastAssignees.value = Object.keys(startUserSelectAssignees).reduce<Record<string, number[]>>((result, key) => {
      result[key] = [...(startUserSelectAssignees[key] || [])]
      return result
    }, {})
    refreshApprovalDetail(value)
  },
  { deep: true }
)

defineExpose({ initProcessInfo })
</script>

<template>
  <a-card :bordered="false" class="process-form-card">
    <template #title>
      <div class="page-title">{{ title }}</div>
    </template>
    <template #extra>
      <a-space>
        <a-button @click="handleCancel">
          <ArrowLeftOutlined />
          返回
        </a-button>
        <a-button
          :loading="submitLoading"
          :disabled="initializing || approvalLoading || !!initializationError || !!approvalError"
          type="primary"
          @click="submitForm"
        >
          <CheckOutlined />
          发起
        </a-button>
      </a-space>
    </template>

    <a-alert
      v-if="initializationError || approvalError"
      type="error"
      show-icon
      :message="initializationError || approvalError"
    >
      <template #action>
        <a-button size="small" @click="initProcessInfo(selectProcessDefinition, detailForm.value)">重新读取</a-button>
      </template>
    </a-alert>
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="form" :tab="withoutForm ? '发起确认' : '表单填写'">
        <a-row :gutter="[32, 16]">
          <a-col :lg="17" :xl="18" :xs="24">
            <a-result
              v-if="withoutForm"
              status="info"
              :title="selectProcessDefinition.name"
              sub-title="此流程无需填写发起表单。请确认流程信息和办理人后发起，后续节点将按各自配置收集材料。"
            />
            <FormCreate
              v-else-if="detailForm.rule.length > 0"
              v-model="detailForm.value"
              v-model:api="fApi"
              :option="detailForm.option"
              :rule="detailForm.rule"
            />
            <a-empty v-else class="empty-form" description="当前流程暂无表单配置" />
          </a-col>
          <a-col :lg="7" :xl="6" :xs="24">
            <a-spin :spinning="approvalLoading">
              <div class="approval-panel">
                <div class="panel-title">审批预览</div>
                <a-timeline>
                  <a-timeline-item v-for="node in activityNodes" :key="node.id">
                    <div class="node-name">{{ node.name }}</div>
                    <div v-if="node.candidateUsers?.length" class="node-users">
                      {{ node.candidateUsers.map(user => user.nickname).join('、') }}
                    </div>
                    <div v-else-if="node.tasks?.length" class="node-users">
                      {{
                        node.tasks
                          .map(task => task.assigneeUser?.nickname)
                          .filter(Boolean)
                          .join('、') || '-'
                      }}
                    </div>
                  </a-timeline-item>
                </a-timeline>

                <a-divider v-if="startUserSelectTasks.length" />
                <div v-if="startUserSelectTasks.length" class="panel-title">指定审批人</div>
                <a-form layout="vertical">
                  <a-form-item v-for="task in startUserSelectTasks" :key="task.id" :label="task.name" required>
                    <a-select
                      v-model:value="startUserSelectAssignees[task.id]"
                      :options="(task.candidateUsers || []).map(user => ({ label: user.nickname, value: user.id }))"
                      allow-clear
                      mode="multiple"
                      option-filter-prop="label"
                      placeholder="请选择候选人"
                      show-search
                    />
                  </a-form-item>
                </a-form>
              </div>
            </a-spin>
          </a-col>
        </a-row>
      </a-tab-pane>
      <a-tab-pane key="flow" tab="流程图">
        <div class="flow-preview">
          <SimpleProcessViewer v-if="simpleModel" :flow-node="simpleModel" />
          <MyProcessViewer v-else-if="bpmnXML" :xml="bpmnXML" />
          <a-empty v-else description="暂无流程图数据" />
        </div>
      </a-tab-pane>
    </a-tabs>
  </a-card>
</template>

<style scoped>
.process-form-card {
  min-height: calc(100vh - 112px);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.empty-form {
  padding: 96px 0;
}

.approval-panel {
  padding: 16px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.panel-title {
  margin-bottom: 12px;
  font-weight: 600;
  color: #1f2937;
}

.node-name {
  color: #1f2937;
  font-weight: 500;
}

.node-users {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}

.flow-preview {
  min-height: 420px;
  padding: 16px;
  overflow: auto;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.flow-preview pre {
  margin: 0;
  color: #374151;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
