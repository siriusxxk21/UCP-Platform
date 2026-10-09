<script lang="ts" setup>
import type { FormInstance } from 'ant-design-vue'
import { message, Modal } from 'ant-design-vue'
import type { BpmCategoryApi } from '@/api/bpm/category'
import { getCategorySimpleList } from '@/api/bpm/category'
import type { BpmFormApi } from '@/api/bpm/form'
import { getFormSimpleList } from '@/api/bpm/form'
import type { BpmModelApi } from '@/api/bpm/model'
import { createModel, deployModel, getModel, updateModel } from '@/api/bpm/model'
import type { Department } from '@/types/system/system'
import type { User } from '@/types/system/user'
import { ArrowLeftOutlined, CloudUploadOutlined, SaveOutlined } from '@ant-design/icons-vue'
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { getProcessDefinition } from '@/api/bpm/definition'
import { getDepartmentTree } from '@/api/system/department'
import { getSimpleUserList } from '@/api/system/user'
import { useUserStore } from '@/stores/user'
import BusinessTaskConfig from './BusinessTaskConfig.vue'
import BpmnNodeForms from './BpmnNodeForms.vue'
import { inspectConfiguredBusinessTasks, startingForm } from './node-form'
import { copyProcessIdentity, parseProcess } from '@/nocode/flow-task-binding'
import { cloneModel, editorFingerprint, hydrateModel, modelPayload, parseSimpleModel } from './model-editor'

defineOptions({ name: 'BpmModelForm' })

type ActionType = 'create' | 'update' | 'copy' | 'definition'

const BPM_MODEL_TYPE = {
  BPMN: 10,
  SIMPLE: 20
}

const BPM_MODEL_FORM_TYPE = {
  NONE: 0,
  NORMAL: 10,
  CUSTOM: 20
}

const AUTO_APPROVE_TYPE = {
  NONE: 0,
  APPROVE_ALL: 1,
  APPROVE_SEQUENT: 2
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const editorPath = route.path
let internalNavigation = false

const loading = ref(false)
const optionsLoading = ref(false)
const saveLoading = ref(false)
const deployLoading = ref(false)
const currentStep = ref(0)
const designMounted = ref(false)
const basicFormRef = ref<FormInstance>()
const formDesignRef = ref<FormInstance>()
const extraFormRef = ref<FormInstance>()

const formList = ref<BpmFormApi.Form[]>([])
const categoryList = ref<BpmCategoryApi.Category[]>([])
const userList = ref<User[]>([])
const userOptionCache = new Map<string, User>()
const deptTree = ref<Department[]>([])
const processData = ref<any>()
const loadError = ref('')
const saveError = ref('')
const ready = ref(false)
const savedFingerprint = ref('')
const loadedSource = ref<Record<string, any>>({})
const initialForm = ref<Record<string, any>>({})
const simpleDesignerRef = ref<{ validate: () => Promise<any> }>()
const busy = computed(() => loading.value || saveLoading.value || deployLoading.value)
const dirty = computed(() => ready.value && editorFingerprint(formData, processData.value) !== savedFingerprint.value)
const saveStatus = computed(() =>
  saveLoading.value
    ? '保存中'
    : deployLoading.value
      ? '发布中'
      : dirty.value
        ? '有未保存修改'
        : formData.id
          ? '草稿已保存'
          : '尚未保存'
)
const taskBindings = computed(() => {
  try {
    return inspectConfiguredBusinessTasks(processData.value, formData.type, formData.autoApprovalType ?? 0)
  } catch {
    return []
  }
})

const MyProcessDesigner = defineAsyncComponent(() =>
  import('@/views/bpm/components/bpmn-process-designer').then(module => module.MyProcessDesigner)
)
const SimpleProcessDesigner = defineAsyncComponent(() =>
  import('@/views/bpm/components/simple-process-design').then(module => module.SimpleProcessDesigner)
)

const actionType = computed<ActionType>(() => {
  const raw = String(route.query.type || route.params.type || 'create')
  return ['create', 'update', 'copy', 'definition', 'edit'].includes(raw)
    ? ((raw === 'edit' ? 'update' : raw) as ActionType)
    : 'create'
})

const routeId = computed(() => {
  const id = route.query.id ?? route.params.id
  const value = Array.isArray(id) ? id[0] : id
  return value == null || value === '' ? undefined : String(value)
})

const pageTitle = computed(() => {
  if (actionType.value === 'copy') return '复制流程模型'
  if (actionType.value === 'definition') return '恢复流程模型'
  if (actionType.value === 'update') return '编辑流程模型'
  return '新建流程模型'
})

const formData = reactive<any>({
  id: undefined,
  name: '',
  key: '',
  category: undefined,
  icon: '',
  description: '',
  type: BPM_MODEL_TYPE.SIMPLE,
  formType: BPM_MODEL_FORM_TYPE.NONE,
  formId: undefined,
  formCustomCreatePath: '',
  formCustomViewPath: '',
  visible: true,
  startUserType: 0,
  startUserIds: [],
  startDeptIds: [],
  managerUserIds: [],
  allowCancelRunningProcess: true,
  allowWithdrawTask: false,
  processIdRule: {
    enable: false,
    prefix: '',
    infix: '',
    postfix: '',
    length: 5
  },
  autoApprovalType: AUTO_APPROVE_TYPE.NONE,
  titleSetting: {
    enable: false,
    title: ''
  },
  summarySetting: {
    enable: false,
    summary: []
  },
  bpmnXml: '',
  simpleModel: undefined
})
const defaultForm = cloneModel(formData)

const steps = [{ title: '基本信息' }, { title: '发起表单' }, { title: '流程设计' }, { title: '更多设置' }]

const basicRules = {
  name: [{ required: true, message: '请输入流程名称', trigger: 'blur' }],
  key: [{ required: true, message: '请输入流程标识', trigger: 'blur' }],
  category: [{ required: true, message: '请选择流程分类', trigger: 'change' }],
  type: [{ required: true, message: '请选择流程类型', trigger: 'change' }],
  managerUserIds: [{ required: true, type: 'array', min: 1, message: '请选择流程管理员', trigger: 'change' }]
}

const formRules = {
  formType: [{ required: true, message: '请选择表单类型', trigger: 'change' }],
  formId: [
    {
      required: computed(() => formData.formType === BPM_MODEL_FORM_TYPE.NORMAL),
      message: '请选择流程表单',
      trigger: 'change'
    }
  ],
  formCustomCreatePath: [
    {
      required: computed(() => formData.formType === BPM_MODEL_FORM_TYPE.CUSTOM),
      message: '请输入自定义表单提交路径',
      trigger: 'blur'
    }
  ],
  formCustomViewPath: [
    {
      required: computed(() => formData.formType === BPM_MODEL_FORM_TYPE.CUSTOM),
      message: '请输入自定义表单查看路径',
      trigger: 'blur'
    }
  ]
}

function flattenDept(items: Department[]): Department[] {
  return items.flatMap(item => [item, ...flattenDept(item.children || [])])
}

const categoryOptions = computed(() =>
  categoryList.value.map(item => ({
    value: item.code,
    label: item.name
  }))
)

const userOptions = computed(() =>
  userList.value.map(user => ({
    value: String(user.id),
    label: user.nickname || user.username
  }))
)

const deptOptions = computed(() =>
  flattenDept(deptTree.value).map(dept => ({
    value: String(dept.id),
    label: dept.deptName
  }))
)

const formOptions = computed(() =>
  formList.value.map(form => ({
    value: String(form.id),
    label: form.name
  }))
)

function resetStartScope(type: number) {
  formData.startUserType = type
}
function handleStartScopeChange(event: { target: { value: number } }) {
  resetStartScope(event.target.value)
}

function assignModel(data: Record<string, any>) {
  loadedSource.value = cloneModel(data)
  Object.assign(formData, hydrateModel(defaultForm, data))
}

function normalizePayload() {
  const payload = modelPayload(formData, processData.value, loadedSource.value, initialForm.value)
  if (
    formData.type === BPM_MODEL_TYPE.BPMN &&
    processData.value &&
    (!formData.id || formData.name !== loadedSource.value.name)
  ) {
    payload.bpmnXml = copyProcessIdentity(processData.value, formData.key, formData.name)
  }
  return payload as BpmModelApi.Model
}

async function loadOptions() {
  optionsLoading.value = true
  try {
    const [forms, categories, users, departments] = await Promise.all([
      getFormSimpleList(),
      getCategorySimpleList(),
      getSimpleUserList(),
      getDepartmentTree()
    ])
    formList.value = forms
    categoryList.value = categories
    userList.value = users
    userOptionCache.clear()
    users.forEach(user => userOptionCache.set(String(user.id), user))
    deptTree.value = departments
  } finally {
    optionsLoading.value = false
  }
}

/** 用户下拉搜索交给后端，确保账号、昵称、全拼和首拼走同一套查询逻辑。 */
async function handleUserSearch(keyword: string) {
  try {
    const result = await getSimpleUserList(keyword)
    const selectedIds = new Set([...(formData.managerUserIds || []), ...(formData.startUserIds || [])].map(String))
    result.forEach(user => userOptionCache.set(String(user.id), user))
    const selectedUsers = [...userOptionCache.values()].filter(user => selectedIds.has(String(user.id)))
    const merged = new Map(result.map(user => [String(user.id), user]))
    selectedUsers.forEach(user => merged.set(String(user.id), user))
    userList.value = [...merged.values()]
  } catch (error) {
    console.error('搜索流程用户失败:', error)
  }
}

async function loadModelData() {
  if (actionType.value === 'definition' && routeId.value) {
    const definition = await getProcessDefinition(routeId.value)
    assignModel({
      ...definition,
      id: definition.modelId,
      type: definition.modelType,
      simpleModel: parseSimpleModel(definition.simpleModel)
    })
  } else if (['copy', 'update'].includes(actionType.value) && routeId.value) {
    const model = await getModel(routeId.value)
    assignModel(model)
    if (actionType.value === 'copy') {
      const oldName = formData.name
      const oldKey = formData.key
      formData.id = undefined
      formData.processDefinition = undefined
      formData.name = `${oldName}副本`
      formData.key = `${oldKey}_copy`
      if (formData.bpmnXml) {
        formData.bpmnXml = copyProcessIdentity(formData.bpmnXml, formData.key, formData.name)
      }
    }
  } else {
    formData.startUserType = 0
    formData.managerUserIds = userStore.userInfo?.id ? [String(userStore.userInfo.id)] : []
  }
}

async function initData() {
  ready.value = false
  loadError.value = ''
  saveError.value = ''
  currentStep.value = 0
  designMounted.value = false
  Object.keys(formData).forEach(key => delete formData[key])
  Object.assign(formData, cloneModel(defaultForm))
  loadedSource.value = {}
  loading.value = true
  const optionsResult = loadOptions()
    .then(() => undefined)
    .catch(error => error)
  try {
    await loadModelData()
    processData.value = formData.type === BPM_MODEL_TYPE.BPMN ? formData.bpmnXml : formData.simpleModel
    await nextTick()
    initialForm.value = cloneModel(formData)
    savedFingerprint.value = editorFingerprint(formData, processData.value)
    ready.value = true
  } catch (error: any) {
    console.error('初始化流程模型失败:', error)
    loadError.value = error.message || '初始化流程模型失败，已阻止保存'
  } finally {
    loading.value = false
  }

  const optionsError = await optionsResult
  if (optionsError) {
    console.warn('加载流程模型选项失败:', optionsError)
    message.warning(optionsError.message || '部分基础选项加载失败，请刷新重试')
  }
}

watch(
  () => formData.type,
  () => {
    processData.value = formData.type === BPM_MODEL_TYPE.BPMN ? formData.bpmnXml : formData.simpleModel
  }
)

watch(currentStep, step => {
  if (step === 2) designMounted.value = true
})

async function validateBasic() {
  await basicFormRef.value?.validate()
  if (
    (formData.startUserType === 1 && !formData.startUserIds.length) ||
    (formData.startUserType === 2 && !formData.startDeptIds.length) ||
    (formData.startUserType === 3 && !formData.startUserIds.length && !formData.startDeptIds.length)
  ) {
    throw new Error('请至少选择一位可发起用户或一个部门')
  }
}

async function validateForm() {
  await formDesignRef.value?.validate()
}

async function validateProcess() {
  if (formData.type === BPM_MODEL_TYPE.SIMPLE && simpleDesignerRef.value)
    processData.value = await simpleDesignerRef.value.validate()
  if (formData.type === BPM_MODEL_TYPE.BPMN && !processData.value) {
    throw new Error('请完善 BPMN 流程设计')
  }
  if (formData.type === BPM_MODEL_TYPE.SIMPLE && !processData.value) {
    throw new Error('请完善 SIMPLE 流程设计')
  }
  if (formData.type === BPM_MODEL_TYPE.BPMN) {
    const doc = parseProcess(processData.value)
    const processes = doc.getElementsByTagNameNS('http://www.omg.org/spec/BPMN/20100524/MODEL', 'process')
    if (processes.length !== 1) throw new Error('当前编辑器仅支持包含一个流程的 BPMN 模型，请检查流程设计')
    if (formData.id && processes[0]?.getAttribute('id') !== formData.key)
      throw new Error('流程标识与模型不一致，请恢复原流程标识后保存')
  }
}

async function validateExtra() {
  await extraFormRef.value?.validate()
}

async function validateAll() {
  for (const [index, validate] of [validateBasic, validateForm, validateProcess, validateExtra].entries()) {
    try {
      await validate()
    } catch (error) {
      currentStep.value = index
      throw error
    }
  }
}

async function handleStepClick(index: number) {
  if (!busy.value && ready.value) currentStep.value = index
}

function describeError(error: any) {
  return error?.errorFields?.[0]?.errors?.[0] || error?.message || '操作失败，请检查配置后重试'
}

async function persistDraft() {
  const payload = normalizePayload()
  if (payload.id) await updateModel(payload)
  else formData.id = await createModel(payload)
  if (payload.bpmnXml) processData.value = payload.bpmnXml
  savedFingerprint.value = editorFingerprint(formData, processData.value)
  // 新建／复制保存后转为编辑地址，重试发布和刷新不会重复创建。
  if (String(routeId.value) !== String(formData.id) || actionType.value !== 'update') {
    await navigateAfterSave(() =>
      router.replace({ path: route.path, query: { ...route.query, type: 'update', id: String(formData.id) } })
    )
  }
}

async function handleSave() {
  if (busy.value || !ready.value) return
  saveLoading.value = true
  saveError.value = ''
  try {
    await validateAll()
    await persistDraft()
    message.success('草稿已保存')
  } catch (error) {
    saveError.value = describeError(error)
  } finally {
    saveLoading.value = false
  }
}

async function handleDeploy() {
  if (busy.value || !ready.value) return
  deployLoading.value = true
  saveError.value = ''
  let draftSaved = false
  try {
    await validateAll()
    const bindings = inspectConfiguredBusinessTasks(processData.value, formData.type, formData.autoApprovalType ?? 0)
    const invalid = bindings.find(binding => binding.issues.length)
    if (invalid) {
      currentStep.value = 2
      throw new Error(`${invalid.name}：${invalid.issues.join('；')}`)
    }
    await persistDraft()
    draftSaved = true
    await deployModel(formData.id)
    message.success('发布成功')
    await navigateAfterSave(() => router.push('/bpm/model'))
  } catch (error) {
    saveError.value = `${draftSaved ? '草稿已保存，发布未成功：' : ''}${describeError(error)}`
  } finally {
    deployLoading.value = false
  }
}

async function navigateAfterSave(navigate: () => Promise<unknown>) {
  internalNavigation = true
  try {
    await navigate()
  } finally {
    internalNavigation = false
  }
}

function confirmLeave(): Promise<boolean> | boolean {
  if (internalNavigation) return true
  if (busy.value) return false
  if (!dirty.value) return true
  return new Promise(resolve =>
    Modal.confirm({
      title: '有未保存的流程修改',
      content: '离开将放弃本次修改，已发布版本不会改变。',
      okText: '放弃修改并离开',
      cancelText: '继续编辑',
      onOk: () => {
        resolve(true)
      },
      onCancel: () => {
        resolve(false)
      }
    })
  )
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate(confirmLeave)
watch(
  () => route.fullPath,
  () => {
    if (route.path === editorPath && !busy.value && !internalNavigation) void initData()
  }
)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

function handleBack() {
  router.push('/bpm/model')
}

onMounted(initData)
</script>

<template>
  <a-modal
    :open="true"
    :footer="null"
    :closable="false"
    :mask-closable="false"
    :keyboard="!busy"
    :z-index="900"
    :wrap-props="{ 'aria-label': pageTitle }"
    width="100%"
    wrap-class-name="bpm-model-designer-modal"
    @cancel="handleBack"
  >
    <div class="bpm-model-form-page">
      <a-card :bordered="false" class="model-form-card">
        <div class="model-topbar">
          <div class="model-topbar-left">
            <a-button type="text" :disabled="busy" class="topbar-back" @click="handleBack">
              <ArrowLeftOutlined />
              返回
            </a-button>
            <div class="model-heading">
              <strong class="topbar-title">{{ formData.name || pageTitle }}</strong>
              <span class="model-context">
                {{ formData.type === BPM_MODEL_TYPE.BPMN ? 'BPMN' : 'SIMPLE' }} ·
                {{
                  formData.processDefinition?.version
                    ? `已发布 V${formData.processDefinition.version}`
                    : formData.id
                      ? '编辑草稿'
                      : '未发布'
                }}
              </span>
            </div>
          </div>

          <nav aria-label="流程模型配置步骤" class="model-topbar-tabs">
            <button
              v-for="(step, index) in steps"
              :key="step.title"
              :class="{ active: currentStep === index }"
              :aria-current="currentStep === index ? 'step' : undefined"
              :disabled="busy || !ready"
              class="model-topbar-tab"
              type="button"
              @click="handleStepClick(index)"
            >
              <span class="tab-index">
                {{ index + 1 }}
              </span>
              <span class="tab-title">{{ step.title }}</span>
            </button>
          </nav>

          <div class="model-topbar-right">
            <span role="status" class="save-status">{{ saveStatus }}</span>
            <a-button :loading="saveLoading" :disabled="busy || !ready" @click="handleSave">
              <SaveOutlined />
              保存草稿
            </a-button>
            <a-button type="primary" :loading="deployLoading" :disabled="busy || !ready" @click="handleDeploy">
              <CloudUploadOutlined />
              发布流程
            </a-button>
          </div>
        </div>

        <a-alert v-if="loadError" type="error" :message="loadError" show-icon class="workspace-message">
          <template #action><a-button :disabled="busy" @click="initData">重新加载</a-button></template>
        </a-alert>
        <a-alert v-if="saveError" type="error" :message="saveError" show-icon class="workspace-message" />
        <div v-if="busy" class="form-loading-overlay">
          <a-spin :tip="loading ? '加载中…' : '处理中…'" :delay="200" />
        </div>
        <section v-show="currentStep === 0" :inert="busy || !ready" class="step-panel narrow-panel">
          <a-form ref="basicFormRef" :model="formData" :rules="basicRules" layout="vertical">
            <a-row :gutter="16">
              <a-col :span="12">
                <a-form-item label="流程名称" name="name">
                  <a-input v-model:value="formData.name" placeholder="请输入流程名称" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="流程标识" name="key">
                  <a-input v-model:value="formData.key" :disabled="!!formData.id" placeholder="请输入流程标识" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="16">
              <a-col :span="12">
                <a-form-item label="流程分类" name="category">
                  <a-select
                    v-model:value="formData.category"
                    :loading="optionsLoading"
                    :options="categoryOptions"
                    placeholder="请选择流程分类"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="流程类型" name="type">
                  <a-radio-group v-model:value="formData.type" :disabled="!!formData.id || !!processData">
                    <a-radio :value="BPM_MODEL_TYPE.SIMPLE">SIMPLE 设计器</a-radio>
                    <a-radio :value="BPM_MODEL_TYPE.BPMN">BPMN 设计器</a-radio>
                  </a-radio-group>
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="流程图标" name="icon">
              <a-input v-model:value="formData.icon" placeholder="请输入图标 URL" />
            </a-form-item>
            <a-form-item label="流程描述" name="description">
              <a-textarea
                v-model:value="formData.description"
                :auto-size="{ minRows: 3 }"
                placeholder="请输入流程描述"
              />
            </a-form-item>
            <a-form-item label="流程管理员" name="managerUserIds">
              <a-select
                v-model:value="formData.managerUserIds"
                :loading="optionsLoading"
                :options="userOptions"
                mode="multiple"
                option-filter-prop="label"
                :filter-option="false"
                placeholder="请选择流程管理员"
                show-search
                @search="handleUserSearch"
              />
            </a-form-item>
            <a-form-item label="可见范围">
              <a-radio-group :value="formData.startUserType" @change="handleStartScopeChange">
                <a-radio :value="0">全部可见</a-radio>
                <a-radio :value="1">指定用户</a-radio>
                <a-radio :value="2">指定部门</a-radio>
                <a-radio :value="3">用户及部门</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item v-if="[1, 3].includes(formData.startUserType)" label="可发起用户">
              <a-select
                v-model:value="formData.startUserIds"
                :loading="optionsLoading"
                :options="userOptions"
                mode="multiple"
                option-filter-prop="label"
                :filter-option="false"
                placeholder="请选择可发起用户"
                show-search
                @search="handleUserSearch"
              />
            </a-form-item>
            <a-form-item v-if="[2, 3].includes(formData.startUserType)" label="可发起部门">
              <a-select
                v-model:value="formData.startDeptIds"
                :loading="optionsLoading"
                :options="deptOptions"
                mode="multiple"
                option-filter-prop="label"
                placeholder="请选择可发起部门"
                show-search
              />
            </a-form-item>
          </a-form>
        </section>

        <section v-show="currentStep === 1" :inert="busy || !ready" class="step-panel narrow-panel">
          <p class="section-hint">发起表单用于流程开始时收集信息。无需发起材料时可不绑定；后续节点按需独立配置表单。</p>
          <a-form ref="formDesignRef" :model="formData" :rules="formRules" layout="vertical">
            <a-form-item label="发起时填写" name="formType">
              <a-radio-group v-model:value="formData.formType">
                <a-radio :value="BPM_MODEL_FORM_TYPE.NONE">无需表单</a-radio>
                <a-radio :value="BPM_MODEL_FORM_TYPE.NORMAL">流程表单</a-radio>
                <a-radio :value="BPM_MODEL_FORM_TYPE.CUSTOM">系统业务表单</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item v-if="formData.formType === BPM_MODEL_FORM_TYPE.NORMAL" label="流程表单" name="formId">
              <a-select
                v-model:value="formData.formId"
                :loading="optionsLoading"
                :options="formOptions"
                option-filter-prop="label"
                placeholder="请选择流程表单"
                show-search
              />
            </a-form-item>
            <template v-else-if="formData.formType === BPM_MODEL_FORM_TYPE.CUSTOM">
              <a-form-item label="提交路径" name="formCustomCreatePath">
                <a-input v-model:value="formData.formCustomCreatePath" placeholder="例如 /bpm/oa/leave/create" />
              </a-form-item>
              <a-form-item label="查看路径" name="formCustomViewPath">
                <a-input v-model:value="formData.formCustomViewPath" placeholder="例如 /bpm/oa/leave/detail" />
              </a-form-item>
            </template>
            <a-alert
              v-if="formData.formType === BPM_MODEL_FORM_TYPE.NONE"
              type="info"
              show-icon
              message="发起时确认流程信息即可；继承此配置的审批节点不显示填写表单，业务任务需独立绑定可提交的表单。"
            />
          </a-form>
        </section>

        <section v-show="currentStep === 2" :inert="busy || !ready" class="step-panel design-panel">
          <template v-if="designMounted">
            <BusinessTaskConfig
              v-if="formData.type === BPM_MODEL_TYPE.BPMN && !processData"
              v-model="processData"
              :process-key="formData.key"
              :process-name="formData.name"
              :auto-approval-type="formData.autoApprovalType ?? 0"
            />
            <BpmnNodeForms
              v-if="formData.type === BPM_MODEL_TYPE.BPMN && processData"
              v-model="processData"
              :inherited="startingForm(formData)"
              @configure-start="currentStep = 1"
            />
            <MyProcessDesigner v-if="formData.type === BPM_MODEL_TYPE.BPMN" v-model="processData" :model="formData" />
            <SimpleProcessDesigner
              v-else
              ref="simpleDesignerRef"
              v-model="processData"
              :model-form-id="formData.formId"
              :model-form-type="formData.formType"
              :model-name="formData.name"
              :inherited-form="startingForm(formData)"
              @configure-start="currentStep = 1"
            />
          </template>
        </section>

        <section v-show="currentStep === 3" :inert="busy || !ready" class="step-panel narrow-panel">
          <a-alert
            v-if="taskBindings.length"
            type="info"
            show-icon
            message="本流程包含业务办理任务，需要人工提交材料。自动去重必须保持不处理；发布时会再次校验办理规则与表单权限。"
            class="workspace-message"
          />
          <a-form ref="extraFormRef" :model="formData" layout="vertical">
            <a-form-item label="流程可见">
              <a-switch v-model:checked="formData.visible" checked-children="可见" un-checked-children="隐藏" />
            </a-form-item>
            <a-form-item label="允许发起人撤销审批中的申请">
              <a-switch v-model:checked="formData.allowCancelRunningProcess" />
            </a-form-item>
            <a-form-item label="允许审批人撤回任务">
              <a-switch v-model:checked="formData.allowWithdrawTask" />
            </a-form-item>
            <a-form-item label="自动去重">
              <a-radio-group v-model:value="formData.autoApprovalType">
                <a-radio :value="AUTO_APPROVE_TYPE.NONE">不处理</a-radio>
                <a-radio :value="AUTO_APPROVE_TYPE.APPROVE_ALL">仅保留一条</a-radio>
                <a-radio :value="AUTO_APPROVE_TYPE.APPROVE_SEQUENT">连续审批自动通过</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-divider>流程 ID 规则</a-divider>
            <a-form-item label="启用流程 ID 规则">
              <a-switch v-model:checked="formData.processIdRule.enable" />
            </a-form-item>
            <a-row :gutter="16">
              <a-col :span="8">
                <a-form-item label="前缀">
                  <a-input v-model:value="formData.processIdRule.prefix" />
                </a-form-item>
              </a-col>
              <a-col :span="8">
                <a-form-item label="中缀">
                  <a-input v-model:value="formData.processIdRule.infix" />
                </a-form-item>
              </a-col>
              <a-col :span="8">
                <a-form-item label="后缀">
                  <a-input v-model:value="formData.processIdRule.postfix" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="序列长度">
              <a-input-number v-model:value="formData.processIdRule.length" :max="12" :min="1" class="full-control" />
            </a-form-item>
          </a-form>
        </section>
      </a-card>
    </div>
  </a-modal>
</template>

<style scoped>
:global(.bpm-model-designer-modal .ant-modal) {
  top: 0;
  width: 100vw !important;
  max-width: none;
  margin: 0;
  padding-bottom: 0;
}

:global(.bpm-model-designer-modal .ant-modal-content) {
  height: 100dvh;
  padding: 0;
  overflow: hidden;
  border-radius: 0;
}

:global(.bpm-model-designer-modal .ant-modal-body) {
  height: 100%;
}

.bpm-model-form-page {
  container-type: inline-size;
  height: 100dvh;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow: hidden;
  background: var(--bg-page);
}

.model-form-card {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 0;
}
.form-loading-overlay {
  position: absolute;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.55);
}

.model-form-card :deep(> .ant-card-body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
}

.model-form-card :deep(.ant-spin-nested-loading) {
  flex: 1;
  min-height: 0;
}

.model-form-card :deep(.ant-spin-container) {
  height: 100%;
  min-height: 0;
}

.model-topbar {
  z-index: 10;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  min-height: 68px;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 16px;
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 1px 2px 0 rgba(16, 24, 40, 0.06);
}

.model-topbar-left {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
  max-width: 100%;
}
.model-heading {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.topbar-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}
.model-context,
.save-status,
.section-hint {
  color: var(--text-secondary);
  font-size: 12px;
}
.save-status {
  white-space: nowrap;
}
.workspace-message {
  margin: 0 16px 12px;
}
.section-hint {
  margin-bottom: 20px;
}

.topbar-back {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
  cursor: pointer;
}

.topbar-back:hover {
  color: var(--brand);
}

.topbar-title {
  color: var(--text-primary);
  font-weight: 600;
}

.model-topbar-tabs {
  grid-column: 1 / -1;
  grid-row: 2;
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  min-width: 0;
  overflow-x: auto;
}

.model-topbar-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 14px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  line-height: 1;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
}

.model-topbar-tab:hover {
  color: var(--brand);
  background: var(--control-hover-bg);
}

.model-topbar-tab.active {
  border-color: var(--border-hover);
  background: var(--brand-light);
  color: var(--brand);
  font-weight: 600;
}

.tab-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid #d0d5dd;
  border-radius: 999px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  background: #fff;
}

.model-topbar-tab.active .tab-index,
.model-topbar-tab.done .tab-index {
  border-color: var(--brand);
  background: var(--brand);
  color: #fff;
}

.tab-title {
  min-width: 0;
}

.model-topbar-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
}

@container (max-width: 560px) {
  .model-topbar {
    grid-template-columns: minmax(0, 1fr);
  }
  .model-topbar-right {
    grid-row: 2;
    justify-content: flex-start;
    flex-wrap: wrap;
  }
  .model-topbar-tabs {
    grid-row: 3;
  }
}

.step-panel {
  flex: 1;
  min-height: 0;
  padding: 12px 24px 24px;
  overflow: auto;
}

.narrow-panel {
  width: 100%;
  max-width: 920px;
  margin: 0 auto;
}

.design-panel {
  height: 100%;
  min-height: 0;
  padding: 0 16px 24px;
  border: 0;
  border-radius: 0;
  overflow: auto;
}

.full-control {
  width: 100%;
}

@media (max-width: 720px) {
  .model-topbar {
    height: auto;
    min-height: 52px;
    flex-wrap: wrap;
    gap: 10px;
    padding: 10px 12px;
  }

  .model-topbar-left,
  .model-topbar-right {
    min-width: 0;
  }

  .model-topbar-tabs {
    order: 3;
    width: 100%;
    flex-basis: 100%;
    justify-content: flex-start;
    overflow-x: auto;
  }
}
</style>
