<script setup lang="ts">
import { useNocodePlatform } from '@/nocode/platform'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { v4 as uuid } from 'uuid'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'

import { newTaskNode, newAutoTaskNode, taskNodeError } from '@/nocode/task-center'
import { taskBusinessConfigError } from '@/nocode/task-work-rule'
import { errorMessage } from '@/nocode/data-center'
import { isDocumentRejection } from '@/nocode/document-save'
import { useUnsavedNavigation } from '@/nocode/unsaved'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import type {
  TaskCreate,
  TaskDetail,
  TaskMember,
  TaskNodeInput,
  TaskRecordRef,
  TaskTemplate,
  TaskTemplateVersion,
  TaskTemplateVersionSummary,
  TaskBinding,
  TaskDraft,
  TaskSchedulePreview
} from '@/types/nocode/task-center'
import type { SaveRecord } from '@/types/nocode/runtime'
import type { ApplicationRow } from '@/types/nocode/application'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import TaskNodeFields from './TaskNodeFields.vue'
import TaskNodeEditor from './TaskNodeEditor.vue'
import TaskRecordPicker from './TaskRecordPicker.vue'
import TaskBusinessForm from './TaskBusinessForm.vue'
import TaskSchedulePreviewPanel from './TaskSchedulePreview.vue'
import { hasPermission } from '@/utils/access'
const { confirmDiscard, confirm } = useTaskConfirmation()
const props = defineProps<{
  embedded?: boolean
  initialTemplateId?: string
  initialTemplateVersion?: number
  templateIds?: string[] | null
  initialProject?: TaskRecordRef | null
  initialBinding?: TaskBinding | null
  initialApplicationId?: string | null
  initialApplicationName?: string
  draftId?: string
  footerTarget?: HTMLElement | null
  workspace?: boolean
}>()
const emit = defineEmits<{ created: [detail: TaskDetail]; draftSaved: [draft: TaskDraft]; close: [] }>()
const activeTab = ref('arrangement')
const platform = useNocodePlatform(),
  api = platform.taskCenter,
  router = useRouter(),
  node = ref<TaskNodeInput>({
    ...newAutoTaskNode(),
    dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
    binding: props.initialBinding ? { ...props.initialBinding } : null
  }),
  nodes = ref<TaskNodeInput[]>([]),
  members = ref<TaskMember[]>([]),
  templates = ref<TaskTemplate[]>([]),
  selectedTemplate = ref<string>(),
  template = ref<TaskTemplateVersion>(),
  versions = ref<TaskTemplateVersionSummary[]>([]),
  project = ref<TaskRecordRef | null>(props.initialProject ? { ...props.initialProject } : null),
  existingRecord = ref<TaskRecordRef | null>(null)
const draftId = ref(props.draftId || uuid()),
  draftRevision = ref(0),
  plannedStart = ref<string | null>(dayjs().format('YYYY-MM-DD'))
// 历史起点仅回显日期，重新选择后才写入日期值，避免恢复草稿时自动改动原排期。
const plannedStartDate = computed({
  get: () => (plannedStart.value ? dayjs(plannedStart.value).format('YYYY-MM-DD') : null),
  set: (value: string | null) => (plannedStart.value = value)
})
const restoredDraft = ref(false),
  draftLoading = ref(!!props.draftId)
const savedBusiness = ref<SaveRecord | null>(null)
const publishAttempt = ref<{ revision: number; requestKey: string }>()
const draftApplication = ref<string | null>(null),
  draftProject = ref<TaskRecordRef | null>(null)
const lockedApplicationId = computed(() => props.initialApplicationId || draftApplication.value)
const lockedProject = computed(() => props.initialProject || draftProject.value)
const needsPlannedStart = computed(() =>
  [node.value, ...nodes.value].some(item => ['AUTO', 'PLAN_START'].includes(item.schedule.mode))
)
const allNodes = computed(() => [
  node.value,
  ...nodes.value.map(child => ({ ...child, parentId: child.parentId || node.value.id }))
])
const applicationId = ref<string | null>(
  props.initialApplicationId || props.initialProject?.applicationId || props.initialBinding?.applicationId || null
)
const applications = ref<ApplicationRow[]>([]),
  applicationError = ref(''),
  applicationLoading = ref(false),
  membersError = ref(''),
  membersLoading = ref(false),
  templatesError = ref(''),
  templatesLoading = ref(false)
const recordOpen = ref<string[]>(project.value ? ['record'] : [])
const applicationName = computed(
  () =>
    applications.value.find(app => app.id === applicationId.value)?.name || props.initialApplicationName || '当前应用'
)
const businessContextSummary = computed(() =>
  [applicationId.value ? applicationName.value : '', project.value?.label || (project.value ? '已关联业务记录' : '')]
    .filter(Boolean)
    .join(' · ')
)
const applicationOptions = computed(() => {
  const options = applications.value.map(app => ({ value: app.id, label: app.name }))
  if (applicationId.value && !options.some(option => option.value === applicationId.value))
    options.push({ value: applicationId.value, label: applicationName.value })
  return options
})
const mode = ref(props.initialTemplateId || props.templateIds?.length ? 'TEMPLATE' : 'NEW'),
  recordMode = ref('NEW'),
  busy = ref(false),
  formOpen = ref(false),
  error = ref(''),
  requestKey = ref(uuid()),
  completed = ref(false)
const savingDraft = ref(false)
const schedulePreview = ref<TaskSchedulePreview>()
const schedulePreviewOpen = ref(false)
const schedulePreviewKey = ref('')
const previewToLaunch = ref(false)
const editingLocked = computed(() => busy.value || !!publishAttempt.value)
const editorLocked = computed(() => editingLocked.value || (mode.value === 'TEMPLATE' && !template.value))
const templateDataLocked = computed(() => mode.value === 'TEMPLATE' && (!template.value || !!template.value.task))
const templateBindingError = computed(() => {
  const fixed = mode.value === 'TEMPLATE' ? template.value?.task?.binding : null
  if (mode.value !== 'TEMPLATE' || !template.value?.task || !props.initialBinding) return ''
  const same =
    fixed &&
    ['applicationId', 'formId', 'entryId'].every(
      key => (fixed[key as keyof TaskBinding] || null) === (props.initialBinding?.[key as keyof TaskBinding] || null)
    )
  return same ? '' : '当前页面业务资源与模板固定业务资源不一致，请选择兼容模板或从任务中心新建。'
})
const published = computed(() =>
  templates.value.filter(t => t.publishedVersion && (!props.templateIds?.length || props.templateIds.includes(t.id)))
)
const templateOptions = computed(() => {
  const current = template.value
  const options = published.value.map(item => ({
    value: item.id,
    label: item.name
  }))
  if (current && !options.some(option => option.value === current.id))
    options.push({
      value: current.id,
      label: `${current.name}（草稿固定模板）`
    })
  return options
})
const versionOptions = computed(() => {
  const current = template.value
  const options = versions.value.map(item => ({
    value: item.version,
    label: `V${item.version}${item.primary ? ' · 主版本' : ''}`
  }))
  // 恢复任务草稿后即使版本目录加载失败，也不能丢掉已经固定的来源版本。
  if (current && !options.some(item => item.value === current.version))
    options.push({ value: current.version, label: `V${current.version} · 本次固定版本` })
  return options
})
const versionsError = ref('')
const businessForm = ref<InstanceType<typeof TaskBusinessForm>>()
const signature = () =>
  JSON.stringify({
    node: node.value,
    nodes: nodes.value,
    project: project.value,
    mode: mode.value,
    templateId: selectedTemplate.value,
    recordMode: recordMode.value,
    existingRecord: existingRecord.value,
    applicationId: applicationId.value,
    plannedStart: plannedStart.value,
    templateVersion: template.value?.version
  })
const initial = ref(signature())
const arrangementSignature = () =>
  JSON.stringify({ node: node.value, nodes: nodes.value, plannedStart: plannedStart.value })
const blankArrangement = arrangementSignature()
const changed = () => !completed.value && signature() !== initial.value
async function requestClose() {
  if (busy.value) return false
  if (!(await confirmDiscard(changed(), '放弃尚未保存的任务修改？'))) return false
  if (formOpen.value && businessForm.value) {
    if (!(await businessForm.value.requestClose())) return false
    // 业务编辑器批准离开后立即销毁，后续导航取消也不能留下失效的弃改许可。
    formOpen.value = false
  }
  return true
}
async function closeWorkspace() {
  if (await requestClose()) emit('close')
}
defineExpose({ requestClose })
async function closeForm() {
  if (!businessForm.value || (await businessForm.value.requestClose())) formOpen.value = false
}
useUnsavedNavigation(changed, { confirm: requestClose })
onMounted(async () => {
  await Promise.all([loadMembers(), loadTemplates()])
  if (props.draftId) await restoreDraft(props.draftId)
  else if (props.initialTemplateId) {
    mode.value = 'TEMPLATE'
    selectedTemplate.value = props.initialTemplateId
    await selectTemplate(props.initialTemplateId, props.initialTemplateVersion)
  }
  await loadApplicationContext()
})
async function loadMembers() {
  if (membersLoading.value) return
  membersLoading.value = true
  membersError.value = ''
  try {
    members.value = await api.members()
  } catch (cause) {
    membersError.value = `人员列表加载失败：${errorMessage(cause)}。仍可保留所有人可领取，稍后再安排人员。`
  } finally {
    membersLoading.value = false
  }
}
async function loadTemplates() {
  if (templatesLoading.value) return
  templatesLoading.value = true
  templatesError.value = ''
  try {
    templates.value = await api.templates()
    if (selectedTemplate.value && !template.value)
      await selectTemplate(
        selectedTemplate.value,
        selectedTemplate.value === props.initialTemplateId ? props.initialTemplateVersion : undefined
      )
  } catch (cause) {
    templatesError.value = `模板列表加载失败：${errorMessage(cause)}`
  } finally {
    templatesLoading.value = false
  }
}
async function retryDraft() {
  if (busy.value || !props.draftId) return
  await restoreDraft(props.draftId)
  await loadApplicationContext()
}
async function restoreDraft(id: string) {
  busy.value = true
  error.value = ''
  restoredDraft.value = true
  try {
    const draft = await api.draftGet(id),
      content = draft.content
    if (draft.publishedTaskId) {
      created(await api.detail(draft.publishedTaskId))
      return
    }
    draftId.value = draft.id
    draftRevision.value = draft.revision
    node.value = JSON.parse(JSON.stringify(content.task)) as TaskNodeInput
    applicationId.value = content.applicationId || null
    draftApplication.value = applicationId.value
    project.value = content.project || null
    draftProject.value = content.project || null
    plannedStart.value = content.plannedStart || null
    savedBusiness.value = content.business || null
    requestKey.value = content.requestKey
    mode.value = content.templateId ? 'TEMPLATE' : 'NEW'
    selectedTemplate.value = content.templateId || undefined
    if (content.templateId && content.templateVersion)
      template.value = await api.templateVersion(content.templateId, content.templateVersion)
    if (content.templateId) void loadVersions(content.templateId)
    const restoredRootId = node.value.id
    if (template.value?.task) node.value.id = template.value.task.id
    // 旧草稿未保存节点覆盖时回填固定版本；显式空数组表示已移除所有子任务。
    nodes.value = copyNodes(content.nodes ?? template.value?.nodes ?? [], restoredRootId)
    existingRecord.value = content.existingRecord || null
    recordMode.value = content.existingRecord ? 'EXISTING' : 'NEW'
    recordOpen.value = project.value ? ['record'] : []
    await nextTick()
    initial.value = signature()
    draftLoading.value = false
  } catch (cause) {
    error.value = `草稿加载失败：${errorMessage(cause)}`
  } finally {
    restoredDraft.value = false
    busy.value = false
  }
}
async function loadApplicationContext() {
  // 先恢复草稿再决定是否需要目录；锁定来源不能被其他应用的目录错误干扰。
  if (completed.value || draftLoading.value || applicationLoading.value) return
  const lockedId = lockedApplicationId.value
  if (lockedId && props.initialApplicationId === lockedId && props.initialApplicationName) return
  applicationLoading.value = true
  applicationError.value = ''
  try {
    if (lockedId) {
      const current = await platform.runtime.application(lockedId)
      if (lockedApplicationId.value === lockedId) applications.value = [current.application]
    } else applications.value = await platform.runtime.mine()
  } catch (e) {
    applicationError.value = `${lockedId ? '关联应用名称暂不可用' : '应用列表加载失败'}：${errorMessage(e)}`
  } finally {
    applicationLoading.value = false
  }
}
function changeApplication(value: unknown) {
  const next = value ? String(value) : null
  if (editingLocked.value || next === applicationId.value || lockedApplicationId.value) return
  applicationId.value = next
  // 实例归属独立于模板批准的业务资源；切换归属只清除旧记录，不改写模板授权。
  if (!templateDataLocked.value) node.value.binding = null
  existingRecord.value = null
  project.value = null
  recordOpen.value = []
  recordMode.value = 'NEW'
}
watch(
  () => JSON.stringify(node.value.binding),
  () => {
    // 更换或清空业务定义后，不能把旧对象的记录提交给新绑定。
    if (!restoredDraft.value) {
      existingRecord.value = null
      savedBusiness.value = null
    }
  }
)
let templateGeneration = 0
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  templateGeneration++
})
function copyNodes(source: TaskNodeInput[], sourceRootId?: string) {
  return source.map(child => ({
    ...JSON.parse(JSON.stringify(child)),
    parentId: child.parentId === sourceRootId || child.parentId === node.value.id ? null : child.parentId
  })) as TaskNodeInput[]
}
async function changeMode(value: string) {
  if (value === mode.value || editingLocked.value) return
  if (value === 'NEW' && template.value) {
    if (!(await confirm('改为直接新建？', '将清空当前模板配置，请先保存需要保留的草稿。', '重新新建'))) return
    node.value = {
      ...newAutoTaskNode(),
      dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
      binding: props.initialBinding ? { ...props.initialBinding } : null
    }
    nodes.value = []
    selectedTemplate.value = undefined
    template.value = undefined
    versions.value = []
    versionsError.value = ''
    plannedStart.value = dayjs().format('YYYY-MM-DD')
    savedBusiness.value = null
  }
  mode.value = value
  activeTab.value = 'arrangement'
}
async function changeTemplate(value: unknown) {
  const id = String(value)
  if ((id === selectedTemplate.value && template.value) || editingLocked.value) return
  if (
    arrangementSignature() !== blankArrangement &&
    !(await confirm('更换任务模板？', '将用所选模板替换本次任务编排，未保存的调整会丢失。', '更换模板'))
  )
    return
  await selectTemplate(id)
}
async function changeVersion(value: unknown) {
  const version = Number(value)
  if (
    !selectedTemplate.value ||
    !Number.isInteger(version) ||
    version === template.value?.version ||
    editingLocked.value
  )
    return
  if (!(await confirm('更换模板版本？', '将用所选版本替换本次任务配置，未保存的调整和业务填写会丢失。', '更换版本')))
    return
  await selectTemplate(selectedTemplate.value, version)
}
let versionsGeneration = 0
async function loadVersions(id: string) {
  const generation = ++versionsGeneration
  versionsError.value = ''
  try {
    const result = await api.templateVersions(id)
    if (generation === versionsGeneration && !disposed && selectedTemplate.value === id) versions.value = result
  } catch (cause) {
    if (generation === versionsGeneration && !disposed && selectedTemplate.value === id)
      versionsError.value = `模板版本列表加载失败：${errorMessage(cause)}`
  }
}
async function selectTemplate(id: string, explicitVersion?: number) {
  const generation = ++templateGeneration
  const item = published.value.find(t => t.id === id)
  if (!item?.publishedVersion) {
    error.value = '该模板尚未发布或当前不可使用，请重新选择已发布模板'
    return
  }
  busy.value = true
  error.value = ''
  try {
    // 默认版本由服务端在请求时解析，避免其他窗口切主后沿用过期的模板目录。
    const version = await api.templateVersion(id, explicitVersion)
    if (generation === templateGeneration && !disposed) {
      if (selectedTemplate.value !== id) versions.value = []
      selectedTemplate.value = id
      template.value = version
      void loadVersions(id)
      if (version.task) {
        node.value = {
          ...JSON.parse(JSON.stringify(version.task)),
          id: version.task.id,
          parentId: null,
          title: version.task.title || version.name
        }
      } else {
        // 旧模板没有总任务默认配置，不能把首个步骤当根，也不自动升级历史共享策略。
        node.value = {
          ...newTaskNode(),
          id: node.value.id,
          title: version.name,
          binding: props.initialBinding ? { ...props.initialBinding } : null
        }
      }
      nodes.value = copyNodes(version.nodes, version.task?.id)
      // 计划开始日期属于本次发起，不随模板或版本切换清空。
      existingRecord.value = null
      savedBusiness.value = null
      recordMode.value = 'NEW'
    }
  } catch (e) {
    if (generation === templateGeneration) error.value = errorMessage(e)
  } finally {
    if (generation === templateGeneration) busy.value = false
  }
}
function validate() {
  const businessError = taskBusinessConfigError(allNodes.value)
  error.value =
    templateBindingError.value ||
    taskNodeError(allNodes.value) ||
    businessError ||
    (needsPlannedStart.value && !plannedStart.value ? '请选择计划开始日期' : '') ||
    (mode.value === 'TEMPLATE' && !template.value ? '请选择已发布任务模板' : '') ||
    (recordMode.value === 'EXISTING' && node.value.binding && !existingRecord.value ? '请选择已有业务记录' : '')
  if (error.value)
    activeTab.value =
      businessError || (recordMode.value === 'EXISTING' && node.value.binding && !existingRecord.value)
        ? 'business'
        : 'arrangement'
  return !error.value
}
function command(business?: SaveRecord, draft = false): TaskCreate {
  if (!hasPermission('nocode:task:create') || !hasPermission('nocode:task:query'))
    throw new Error('当前没有新建任务权限')
  if (templateBindingError.value) throw new Error(templateBindingError.value)
  if (mode.value === 'TEMPLATE' && !template.value) throw new Error('请先加载已发布模板，再保存或加入任务池')
  if (!draft && !validate()) throw new Error(error.value)
  return {
    task: node.value,
    nodes:
      mode.value === 'TEMPLATE' || nodes.value.length
        ? nodes.value.map(child => ({ ...child, parentId: child.parentId === node.value.id ? null : child.parentId }))
        : null,
    templateId: mode.value === 'TEMPLATE' ? template.value?.id || selectedTemplate.value || null : null,
    templateVersion: mode.value === 'TEMPLATE' ? template.value?.version || null : null,
    plannedStart: plannedStart.value,
    project: project.value,
    applicationId: applicationId.value,
    existingRecord: node.value.binding && recordMode.value === 'EXISTING' ? existingRecord.value : null,
    business: business || savedBusiness.value || null,
    requestKey: requestKey.value
  }
}
// 首次保存使用稳定 UUID + revision 0；请求失败重试不创建第二份草稿。
async function persistDraft(business?: SaveRecord) {
  if (publishAttempt.value) throw new Error('上次加入任务池的结果尚未确认，请先重试“加入任务池”，确认后再调整任务')
  const draft = await api.draftSave({
    id: draftId.value,
    expectedRevision: draftRevision.value,
    content: command(business, true)
  })
  draftRevision.value = draft.revision
  if (business) savedBusiness.value = business
  initial.value = signature()
  return draft
}
async function saveBusinessDraft(business: SaveRecord) {
  const draft = await persistDraft(business)
  message.success('任务编排和业务填写已保存为草稿，尚未写入业务记录')
  emit('draftSaved', draft)
}
async function saveDraft() {
  if (busy.value || draftLoading.value) return
  busy.value = true
  savingDraft.value = true
  error.value = ''
  try {
    const draft = await persistDraft()
    message.success('草稿已保存，可在任务管理的“我的草稿”继续编辑')
    emit('draftSaved', draft)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
    savingDraft.value = false
  }
}
async function publish(business?: SaveRecord) {
  if (publishAttempt.value) {
    try {
      return await api.draftPublish({
        id: draftId.value,
        expectedRevision: publishAttempt.value.revision,
        requestKey: publishAttempt.value.requestKey
      })
    } catch (cause) {
      // 未知结果后的拒绝不等于未提交；查草稿回执，不另保存或换键创建。
      if (isDocumentRejection(cause)) {
        const receipt = await api.draftGet(draftId.value)
        if (receipt.publishedTaskId) return api.detail(receipt.publishedTaskId)
      }
      throw cause
    }
  }
  if (!draftRevision.value && !props.draftId) return api.create(command(business))
  await persistDraft(business)
  publishAttempt.value = { revision: draftRevision.value, requestKey: requestKey.value }
  try {
    return await api.draftPublish({
      id: draftId.value,
      expectedRevision: draftRevision.value,
      requestKey: requestKey.value
    })
  } catch (cause) {
    // 明确业务拒绝可修改后重试；网络中断则保留版本，不能先保存已发布的草稿。
    if (isDocumentRejection(cause)) publishAttempt.value = undefined
    throw cause
  }
}
function created(detail: TaskDetail) {
  completed.value = true
  formOpen.value = false
  message.success('任务已加入任务池')
  emit('created', detail)
  if (!props.embedded) router.push({ path: '/nocode-app/task-center/manage', query: { taskId: detail.task.id } })
}
async function inspectSchedule(forLaunch = false) {
  if (busy.value || draftLoading.value || !validate()) return
  const key = signature()
  const input = JSON.parse(JSON.stringify({ nodes: allNodes.value, plannedStart: plannedStart.value }))
  busy.value = true
  error.value = ''
  try {
    const result = await api.schedulePreview(input)
    if (disposed || key !== signature()) return
    schedulePreview.value = result
    schedulePreviewKey.value = key
    previewToLaunch.value = forLaunch
    schedulePreviewOpen.value = true
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function confirmSchedule() {
  if (busy.value) return
  if (!previewToLaunch.value) {
    schedulePreviewOpen.value = false
    return
  }
  // 预览期间切换模板或改动规则后必须重新计算，不能拿旧日期确认新任务。
  if (schedulePreviewKey.value !== signature()) {
    schedulePreviewOpen.value = false
    await inspectSchedule(true)
    return
  }
  schedulePreviewOpen.value = false
  await launch(true)
}
async function launch(confirmed = false) {
  if (!hasPermission('nocode:task:create') || !hasPermission('nocode:task:query')) {
    error.value = '当前没有新建任务权限'
    return
  }
  if (busy.value || draftLoading.value) return
  if (!publishAttempt.value && !validate()) return
  if (publishAttempt.value) {
    busy.value = true
    try {
      created(await publish())
    } catch (cause) {
      error.value = errorMessage(cause)
    } finally {
      busy.value = false
    }
    return
  }
  if (!confirmed) {
    await inspectSchedule(true)
    return
  }
  if (!node.value.dataPolicy && node.value.binding && recordMode.value === 'NEW') {
    formOpen.value = true
    return
  }
  busy.value = true
  try {
    created(await publish())
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="task-launch" :class="{ 'task-launch--workspace': workspace }" :aria-busy="busy || draftLoading">
    <header class="task-launch__header">
      <div class="task-launch__identity">
        <a-button v-if="workspace" :disabled="busy" aria-label="返回任务列表" @click="closeWorkspace">返回</a-button>
        <a-button v-else-if="!embedded" :disabled="busy" @click="router.push('/nocode-app/task-center')">
          返回我的任务
        </a-button>
        <h3 :title="node.title || (draftRevision ? '编辑任务草稿' : '新建任务')">
          {{ node.title || (draftRevision ? '编辑任务草稿' : '新建任务') }}
        </h3>
        <a-tag>草稿</a-tag>
        <span v-if="changed()" class="task-list__hint">未保存</span>
      </div>
      <Teleport :to="footerTarget || 'body'" :disabled="!footerTarget">
        <div
          class="task-editor__footer task-launch__footer"
          :class="{ 'task-launch__footer--hosted': !!footerTarget }"
          aria-label="新建任务操作"
        >
          <a-button
            :disabled="
              editorLocked ||
              draftLoading ||
              !hasPermission('nocode:task:create') ||
              !hasPermission('nocode:task:query')
            "
            :loading="savingDraft"
            @click="saveDraft"
          >
            保存草稿
          </a-button>
          <a-button
            type="primary"
            :loading="busy && !savingDraft"
            :disabled="
              savingDraft || draftLoading || !hasPermission('nocode:task:create') || !hasPermission('nocode:task:query')
            "
            @click="launch()"
          >
            加入任务池
          </a-button>
        </div>
      </Teleport>
    </header>
    <a-alert v-if="draftLoading && error" type="error" :message="error" show-icon>
      <template #action><a-button :loading="busy" @click="retryDraft">重新加载草稿</a-button></template>
    </a-alert>
    <p v-else-if="draftLoading" role="status" class="task-list__hint">正在恢复任务草稿，请稍候…</p>
    <template v-if="!draftLoading">
      <section class="task-launch__source" aria-label="任务创建设置">
        <div class="task-launch__source-controls" role="group" aria-label="创建方式与模板选择">
          <div class="task-launch__mode-field">
            <span class="task-launch__field-label">创建方式</span>
            <a-radio-group
              :value="mode"
              button-style="solid"
              :disabled="editingLocked"
              aria-label="创建方式"
              @update:value="changeMode"
            >
              <a-radio-button v-if="!templateIds?.length" value="NEW">新建任务</a-radio-button>
              <a-radio-button value="TEMPLATE">从模板新建</a-radio-button>
            </a-radio-group>
          </div>
          <template v-if="mode === 'TEMPLATE'">
            <div class="task-launch__source-field">
              <label for="task-launch-template" class="task-launch__field-label">任务模板</label>
              <a-select
                id="task-launch-template"
                :value="selectedTemplate"
                :options="templateOptions"
                :loading="templatesLoading || busy"
                :disabled="editingLocked"
                show-search
                option-filter-prop="label"
                aria-label="已发布模板"
                placeholder="选择已发布的任务模板"
                @change="changeTemplate"
              />
            </div>
            <div class="task-launch__source-field task-launch__source-field--version">
              <label for="task-launch-version" class="task-launch__field-label">模板版本</label>
              <a-select
                id="task-launch-version"
                :value="template?.version"
                :options="versionOptions"
                :loading="busy"
                :disabled="editingLocked || !template"
                aria-label="模板版本"
                placeholder="选择模板后默认主版本"
                @change="changeVersion"
              />
            </div>
          </template>
        </div>
        <div
          v-if="mode === 'TEMPLATE' && template"
          class="task-launch__source-note"
          role="note"
          aria-label="模板来源说明"
        >
          <span>来源：{{ template.name }} · v{{ template.version }}</span>
          <span>本次调整不会修改模板。</span>
        </div>
      </section>
      <a-alert v-if="mode === 'TEMPLATE' && versionsError" type="warning" :message="versionsError" show-icon>
        <template #action>
          <a-button @click="selectedTemplate && loadVersions(selectedTemplate)">重试版本列表</a-button>
        </template>
      </a-alert>
      <a-alert v-if="mode === 'TEMPLATE' && templatesError" type="warning" :message="templatesError" show-icon>
        <template #action>
          <a-button :loading="templatesLoading" @click="loadTemplates">重试模板列表</a-button>
        </template>
      </a-alert>
      <p
        v-else-if="mode === 'TEMPLATE' && !templatesLoading && !templateOptions.length"
        class="task-list__hint"
        role="status"
      >
        暂无可用的已发布模板。{{
          templateIds?.length ? '请联系模板负责人发布后重试。' : '可先新建任务，或在任务模板中发布模板。'
        }}
      </p>
      <a-alert v-if="membersError" type="warning" :message="membersError" show-icon>
        <template #action><a-button :loading="membersLoading" @click="loadMembers">重试人员列表</a-button></template>
      </a-alert>
      <a-alert v-if="templateBindingError || error" type="error" show-icon :message="templateBindingError || error" />
      <a-button
        v-if="mode === 'TEMPLATE' && selectedTemplate && !template"
        :loading="busy"
        @click="
          selectTemplate(
            selectedTemplate,
            props.initialTemplateId === selectedTemplate ? initialTemplateVersion : undefined
          )
        "
      >
        重试加载模板
      </a-button>
      <a-tabs v-model:active-key="activeTab" class="task-launch__tabs" :animated="false">
        <a-tab-pane key="arrangement" tab="任务编排">
          <div class="task-launch__arrangement">
            <a-form v-if="needsPlannedStart" layout="inline" class="task-launch__schedule" :disabled="editorLocked">
              <a-form-item label="计划开始日期" required>
                <a-date-picker
                  v-model:value="plannedStartDate"
                  format="YYYY-MM-DD"
                  value-format="YYYY-MM-DD"
                  aria-label="计划开始日期"
                  placeholder="计划哪天开始"
                />
              </a-form-item>
              <a-button :disabled="editorLocked" @click="inspectSchedule()">预览排期</a-button>
              <span class="task-list__hint">子任务跟随顺序，总任务汇总日期。</span>
            </a-form>
            <div class="task-launch__editor">
              <TaskNodeEditor
                v-model="nodes"
                v-model:root="node"
                inline-configuration
                auto-schedule
                :members="members"
                :root-entries="node.entries || []"
                :planned-start="plannedStart"
                :readonly="editorLocked"
                :data-readonly="templateDataLocked"
                :work-adjustment="mode === 'TEMPLATE' && !editorLocked"
                :template-instance="mode === 'TEMPLATE'"
                :binding-locked="!!initialBinding"
                :application-id="applicationId"
                :frozen-ids="editorLocked ? [node.id, ...nodes.map(child => child.id)] : []"
                :closed-ids="editorLocked ? [node.id, ...nodes.map(child => child.id)] : []"
              />
            </div>
            <p class="task-list__hint">不需要拆分时，只填写总任务即可。加入任务池后仍需手动开始执行。</p>
          </div>
        </a-tab-pane>
        <a-tab-pane key="business" tab="业务关联">
          <a-form layout="vertical" :disabled="editorLocked">
            <TaskNodeFields
              v-model="node"
              section="business"
              :members="members"
              is-root
              :nodes="allNodes"
              :hierarchy-root-id="node.id"
              :application-id="applicationId"
              :business-context-summary="businessContextSummary"
              :binding-locked="!!initialBinding && !templateDataLocked"
              :data-readonly="templateDataLocked"
              :work-adjustment="mode === 'TEMPLATE' && !editorLocked"
              :readonly="editorLocked"
            >
              <template #business-context>
                <a-form-item label="关联应用">
                  <template v-if="lockedApplicationId">
                    <a-tag color="blue">{{ applicationName }}</a-tag>
                    <p class="task-list__hint">从此应用新建，任务和后续拆分的子任务将归属此应用。</p>
                  </template>
                  <template v-else>
                    <a-select
                      :value="applicationId || undefined"
                      :options="applicationOptions"
                      :loading="applicationLoading"
                      allow-clear
                      show-search
                      option-filter-prop="label"
                      aria-label="关联应用"
                      :placeholder="templateDataLocked ? '选择本次任务关联应用（可选）' : '不关联应用，作为独立任务'"
                      @change="changeApplication"
                    />
                    <p v-if="templateDataLocked" class="task-list__hint">
                      {{
                        !applicationId && node.binding
                          ? '未另选时沿用模板业务数据所属应用；选择应用后可关联具体记录。'
                          : '仅设置本次任务归属，业务表单与数据范围仍沿用模板。'
                      }}
                    </p>
                    <p v-else class="task-list__hint">
                      {{
                        applicationId
                          ? '已归属此应用；不需要业务数据时，可直接加入任务池。应用任务列表可查看同一任务。'
                          : '独立任务只需填写基本信息，也可按需添加业务办理项。'
                      }}
                    </p>
                  </template>
                  <a-alert v-if="applicationError" type="warning" :message="applicationError">
                    <template #action>
                      <a-button :loading="applicationLoading" @click="loadApplicationContext">重试应用列表</a-button>
                    </template>
                  </a-alert>
                </a-form-item>
                <a-form-item v-if="lockedProject" label="关联业务记录">
                  <a-tag>{{ lockedProject.label || '当前业务记录' }}</a-tag>
                  <p class="task-list__hint">自动关联当前记录，可在原业务页面的任务列表查看。</p>
                </a-form-item>
                <a-collapse v-else-if="applicationId" v-model:active-key="recordOpen" class="task-business-options">
                  <a-collapse-panel key="record" header="关联具体业务记录（可选）">
                    <p class="task-list__hint">例如当前施工项目或订单；任务及拆分出的子任务会挂在这条记录下。</p>
                    <TaskRecordPicker
                      v-model="project"
                      :application-id="applicationId"
                      placeholder="选择已有业务记录，无需重复登记"
                    />
                  </a-collapse-panel>
                </a-collapse>
              </template>
              <template #business-record>
                <template v-if="node.binding && !node.dataPolicy">
                  <a-form-item label="数据登记方式">
                    <a-radio-group v-model:value="recordMode">
                      <a-radio value="NEW">填写新数据</a-radio>
                      <a-radio value="EXISTING">使用已有数据</a-radio>
                    </a-radio-group>
                  </a-form-item>
                  <a-form-item v-if="recordMode === 'EXISTING'" label="已有记录">
                    <TaskRecordPicker v-model="existingRecord" :binding="node.binding" placeholder="选择已有业务数据" />
                  </a-form-item>
                </template>
              </template>
            </TaskNodeFields>
          </a-form>
        </a-tab-pane>
      </a-tabs>
    </template>
    <OsModalForm
      v-if="schedulePreviewOpen && schedulePreview"
      :open="true"
      :title="previewToLaunch ? '确认整组任务排期' : '整组排期预览'"
      :width="1060"
      :wrap-form="false"
      :allow-switch-display="false"
      :loading="busy"
      :ok-text="previewToLaunch ? '确认并加入任务池' : '返回编排'"
      cancel-text="继续调整"
      @cancel="schedulePreviewOpen = false"
      @ok="confirmSchedule"
    >
      <template #formItems>
        <TaskSchedulePreviewPanel :preview="schedulePreview" :nodes="allNodes" />
      </template>
      <template v-if="!previewToLaunch" #footer>
        <a-button type="primary" @click="schedulePreviewOpen = false">返回编排</a-button>
      </template>
    </OsModalForm>
    <OsModalForm
      :wrap-form="false"
      :allow-switch-display="false"
      v-if="formOpen && node.binding"
      :open="true"
      title="填写业务数据并加入任务池"
      display-mode="drawer"
      :width="1000"
      :show-footer="false"
      @cancel="closeForm"
    >
      <template #formItems>
        <TaskBusinessForm
          ref="businessForm"
          :binding="node.binding"
          :create="command"
          :publish="publish"
          :initial-business="savedBusiness"
          :save-draft="saveBusinessDraft"
          :draft-key="draftId"
          @created="created"
          @cancel="formOpen = false"
        />
      </template>
    </OsModalForm>
  </section>
</template>
<style scoped>
.task-launch {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-width: 0;
  min-height: 0;
}
.task-launch--workspace {
  flex: 1;
  overflow: hidden;
}
.task-launch__header,
.task-launch__identity {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}
.task-launch__header {
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-md) var(--spacing-xl);
  padding: var(--spacing-xs) var(--spacing-md);
}
.task-launch__identity {
  flex: 1 1 280px;
}
.task-launch__identity > :not(h3) {
  flex-shrink: 0;
}
.task-launch__identity h3 {
  margin: 0;
  min-width: 0;
  max-width: min(36vw, 440px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-launch__source {
  flex-shrink: 0;
  padding: var(--spacing-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--color-bg-container);
}
.task-launch__source-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--spacing-lg) var(--spacing-xl);
}
.task-launch__source :deep(.ant-select) {
  width: 100%;
}
.task-launch__mode-field,
.task-launch__source-field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: 0;
  max-width: 100%;
}
.task-launch__mode-field {
  flex-shrink: 0;
}
.task-launch__source-field {
  width: 320px;
}
.task-launch__field-label {
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
  line-height: 1.5;
  white-space: nowrap;
}
.task-launch__source-field--version {
  width: 220px;
}
.task-launch__source-note {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs) var(--spacing-lg);
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.task-launch__footer {
  position: static;
  flex-shrink: 0;
  flex-wrap: wrap;
  margin-left: auto;
  padding: 0;
  border-top: 0;
}
.task-launch__tabs {
  flex: 1;
  min-height: 0;
  padding: 0 var(--spacing-md);
  background: var(--bg-container);
  border-radius: var(--border-radius-lg);
}
.task-launch--workspace .task-launch__tabs :deep(.ant-tabs-content-holder),
.task-launch--workspace .task-launch__tabs :deep(.ant-tabs-content) {
  min-height: 0;
  height: 100%;
}
.task-launch--workspace .task-launch__tabs :deep(.ant-tabs-tabpane) {
  height: 100%;
  overflow: auto;
  padding-bottom: var(--spacing-md);
}
.task-launch__schedule {
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}
.task-launch--workspace .task-launch__arrangement {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.task-launch--workspace .task-launch__editor {
  flex: 1;
  min-height: 0;
}
.task-launch__arrangement > .task-list__hint {
  flex-shrink: 0;
  margin: var(--spacing-sm) 0 0;
}
</style>
