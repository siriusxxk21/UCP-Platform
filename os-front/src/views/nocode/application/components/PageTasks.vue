<script setup lang="ts">
import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { boundFields } from '@/nocode/application-ui'
import { recordDisplay } from '@/nocode/record-display'
import { richTextSummary } from '@/nocode/rich-text'
import type { FormConfig, TaskViewConfig } from '@/types/nocode/application-ui'
import type { RecordModel } from '@/types/nocode/runtime'
import type { TaskRow, TaskRecordRef, TaskBinding, TaskDetail as TaskDetailResult } from '@/types/nocode/task-center'
import type { ApplicationResource } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import TaskList from '@/views/nocode/task-center/TaskList.vue'
import TaskLinkPicker from '@/views/nocode/task-center/TaskLinkPicker.vue'
import { message } from 'ant-design-vue'
import { v4 as uuid } from 'uuid'
import TaskLaunch from '@/views/nocode/task-center/TaskLaunch.vue'
import { useTaskConfirmation } from '@/nocode/task-confirmation'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import OsDynamicSearch from '@/components/os-table-page/OsDynamicSearch.vue'
import type { DynamicSearchCondition } from '@/components/os-table-page/types'
import { dynamicQueryField, normalizeAdvancedQuery, supportsAdvancedQuery } from '@/nocode/runtime-list'
import { fieldRelation } from '@/nocode/business-fields'
import { selectionSource } from '@/nocode/selection'
import SelectionField from './SelectionField.vue'
const { confirm } = useTaskConfirmation()
const launchFooterTarget = ref<HTMLElement | null>(null)
// 公共抽屉的拖拽宽度使用数值；传 CSS min() 会被底座回退到默认宽度。
const launchWidth = ref(Math.min(1600, Math.floor(window.innerWidth * 0.96)))
function resizeLaunch() {
  launchWidth.value = Math.min(1600, Math.floor(window.innerWidth * 0.96))
}
onMounted(() => window.addEventListener('resize', resizeLaunch))
onBeforeUnmount(() => window.removeEventListener('resize', resizeLaunch))

/** 页面只传发布节点身份，实际对象、记录范围和权限由任务服务重新解析。 */
const props = defineProps<{
  applicationId: string
  applicationName?: string
  resources: ApplicationResource[]
  pageId?: string
  nodeId: string
  resourceId?: string | null
  recordId?: string
  title?: string
  refreshKey?: number
  taskView?: TaskViewConfig | null
}>()
const page = computed(() => props.resources.find(resource => resource.id === props.pageId))
const contextObjectId = computed(() => page.value?.config.contextObjectId)
const businessForm = computed(() =>
  props.resources.find(
    resource =>
      resource.id ===
        (props.taskView?.businessFormId || (!page.value?.config.contextObjectId ? props.resourceId : null)) &&
      resource.kind === ResourceKind.FORM
  )
)
const needsRecord = computed(() => !!page.value?.config.contextObjectId && !props.recordId)
const conditions = ref<DynamicSearchCondition | null>(null),
  conditionsOpen = ref(false)
const context = computed(() =>
  props.pageId
    ? {
        applicationId: props.applicationId,
        pageId: props.pageId,
        nodeId: props.nodeId,
        recordId: props.recordId,
        ...(conditions.value ? { conditions: conditions.value } : {})
      }
    : undefined
)
const invalidContext = computed(() => !context.value || page.value?.kind !== ResourceKind.PAGE)
const platform = useNocodePlatform()
const linkOpen = ref(false)
const linkContext = computed(() =>
  context.value?.recordId ? { ...context.value, recordId: context.value.recordId } : undefined
)
async function unlink(row: TaskRow) {
  if (!linkContext.value || !row.canUnlink) return
  const current = { ...linkContext.value },
    requestKey = uuid()
  if (!(await confirm('解除与当前记录的关联？', '任务本身、原项目和业务材料均保留。', '解除关联'))) return
  try {
    await api.recordLink({
      context: {
        applicationId: current.applicationId,
        pageId: current.pageId,
        nodeId: current.nodeId,
        recordId: current.recordId
      },
      taskId: row.id,
      expectedRevision: row.revision,
      include: false,
      requestKey
    })
    localRefresh.value++
  } catch (e) {
    fieldError.value = errorMessage(e)
  }
}
const launchOpen = ref(false),
  launchBusy = ref(false),
  localRefresh = ref(0)
const launchProject = ref<TaskRecordRef | null>(null),
  launchBinding = ref<TaskBinding | null>(null),
  launchApplicationName = ref('')
const launch = ref<InstanceType<typeof TaskLaunch>>()
const taskList = ref<InstanceType<typeof TaskList>>()
const launchContextIdentity = ref('')
async function openLaunch() {
  if (invalidContext.value || needsRecord.value) return
  launchBusy.value = true
  fieldError.value = ''
  const identity = JSON.stringify(context.value)
  try {
    launchProject.value = null
    launchBinding.value = null
    launchApplicationName.value = props.applicationName || ''
    if (!launchApplicationName.value) {
      const applications = await platform.runtime.mine().catch(() => [])
      if (identity !== JSON.stringify(context.value)) return
      launchApplicationName.value =
        applications.find(application => application.id === props.applicationId)?.name || '当前应用'
    }
    if (contextObjectId.value && props.recordId) {
      // 记录身份来自页面，不依赖展示或录入记录的表单。
      const objectId = String(contextObjectId.value)
      const [current, record] = await Promise.all([
        platform.runtime.model(props.applicationId, objectId),
        platform.runtime.get(props.applicationId, objectId, props.recordId)
      ])
      if (identity !== JSON.stringify(context.value)) return
      launchProject.value = {
        applicationId: props.applicationId,
        objectId,
        recordId: props.recordId,
        label: String(
          record.record.displayValues?.[current.object.titleFieldId] ??
            record.record.values[current.object.titleFieldId] ??
            props.recordId
        )
      }
    }
    if (businessForm.value)
      launchBinding.value = { applicationId: props.applicationId, formId: businessForm.value.id, entryId: null }
    launchContextIdentity.value = identity
    launchOpen.value = true
  } catch (error) {
    fieldError.value = errorMessage(error)
  } finally {
    launchBusy.value = false
  }
}
async function closeLaunch() {
  if (!launch.value || (await launch.value.requestClose())) launchOpen.value = false
}
async function created(detail: TaskDetailResult) {
  // 核对发起时的身份，旧记录请求晚返回不能在新记录里定位任务。
  const identity = launchContextIdentity.value
  if (!launchOpen.value || !identity || identity !== JSON.stringify(context.value)) return
  if (props.taskView?.conditions || props.taskView?.taskFilter)
    message.info('任务已加入任务池，当前视图仅展示符合固定条件的任务。')
  launchOpen.value = false
  localRefresh.value++
  // 和任务中心发起一致：新任务即使不匹配固定筛选，也能直接继续查看或办理。
  await nextTick()
  if (identity === launchContextIdentity.value && identity === JSON.stringify(context.value))
    taskList.value?.openDetail(detail.task.id)
}
function draftSaved() {
  launchOpen.value = false
  localRefresh.value++
}
const api = platform.taskCenter
const model = ref<RecordModel>(),
  fieldError = ref('')
const businessValues = ref<Record<string, Record<string, unknown>>>({})
const businessColumns = computed(() => {
  if (!model.value || !businessForm.value) return []
  const ids = new Set(boundFields((businessForm.value.config as unknown as FormConfig).nodes))
  return model.value.object.fields
    .filter(field => ids.has(field.id!) && model.value!.permissions.readFields.includes(field.id!))
    .map(field => ({ key: field.id!, title: field.name }))
})
const queryFields = computed(() => {
  if (!model.value) return []
  const ids = new Set(businessColumns.value.map(column => column.key))
  return model.value.object.fields.filter(field => ids.has(field.id!) && supportsAdvancedQuery(field))
})
const searchFields = computed(() =>
  queryFields.value.map(field => {
    const options = model.value!.object.fieldOptions[field.id!]
    const entry = dynamicQueryField(
      field,
      options,
      (options?.options || [])
        .filter(option => !option.disabled)
        .map(option => ({ value: option.code, label: option.label }))
    )
    const source = selectionSource(field, options)
    if (fieldRelation(model.value!.object.relations, field.id) || (source && source.kind !== 'LOCAL_OPTIONS')) {
      entry.type = 'select'
      entry.operators = ['eq', 'neq', 'in']
      entry.valueComponent = markRaw(SelectionField)
      entry.valueProps = {
        applicationId: props.applicationId,
        objectId: model.value!.object.objectId,
        fieldId: field.id,
        compact: true,
        placeholder: '选择关联条件'
      }
    }
    return entry
  })
)
function applyConditions(value: DynamicSearchCondition | null) {
  try {
    conditions.value = normalizeAdvancedQuery(value, queryFields.value)
    fieldError.value = ''
    localRefresh.value++
  } catch (error) {
    fieldError.value = errorMessage(error)
  }
}
let modelGeneration = 0,
  rowGeneration = 0
watch(
  () => [
    props.applicationId,
    props.pageId,
    props.nodeId,
    props.resourceId,
    props.recordId,
    JSON.stringify(props.taskView)
  ],
  async () => {
    const generation = ++modelGeneration
    rowGeneration++
    model.value = undefined
    businessValues.value = {}
    conditions.value = null
    fieldError.value = ''
    linkOpen.value = false
    // 离开原应用/记录后销毁原发起表单，避免未提交内容带着新上下文写入。
    launchOpen.value = false
    launchContextIdentity.value = ''
    if (!businessForm.value?.config.objectId) return
    try {
      const current = await platform.runtime.model(props.applicationId, String(businessForm.value.config.objectId))
      if (generation === modelGeneration) model.value = current
    } catch (error) {
      if (generation === modelGeneration) fieldError.value = errorMessage(error)
    }
  },
  { immediate: true }
)
async function loadBusiness(rows: TaskRow[]) {
  const generation = ++rowGeneration
  businessValues.value = {}
  if (!businessForm.value) return
  const objectId = String(businessForm.value.config.objectId)
  // 每页重新核验权限，不缓存跨应用或撤权后的值；任务可见不等于业务字段可见。
  const results = await Promise.all(
    rows.map(async row => {
      if (!row.business?.recordId || row.business.object.objectId !== objectId) return [row.id, {}] as const
      try {
        const value = await api.form(row.id, { quiet: true })
        if (!value.record) return [row.id, {}] as const
        const fields = value.model.object.fields.filter(field => value.model.permissions.readFields.includes(field.id!))
        const visible = value.record.record.permissions?.readFields
        return [
          row.id,
          Object.fromEntries(
            fields
              .filter(field => !visible || visible.includes(field.id!))
              .map(field => [
                field.id!,
                field.type === 'RICH_TEXT'
                  ? richTextSummary(value.record!.record.values[field.id!])
                  : recordDisplay(value.record!.record, field.id!, value.model.object.fieldOptions[field.id!], field)
              ])
          )
        ] as const
      } catch {
        return [
          row.id,
          { ...Object.fromEntries(businessColumns.value.map(column => [column.key, '业务内容不可用或无权查看'])) }
        ] as const
      }
    })
  )
  if (generation === rowGeneration) businessValues.value = Object.fromEntries(results)
}
</script>

<template>
  <section class="page-tasks">
    <header class="page-tasks__header">
      <h3>{{ title || (page?.config.contextObjectId ? '关联任务' : '应用任务') }}</h3>
      <a-space wrap>
        <a-button v-if="linkContext && !invalidContext && !needsRecord" @click="linkOpen = true">关联已有任务</a-button>
        <a-button
          v-if="!invalidContext && !needsRecord && platform.hasPermission('nocode:task:create')"
          type="primary"
          :loading="launchBusy"
          @click="openLaunch"
        >
          新建任务
        </a-button>
      </a-space>
    </header>
    <a-alert v-if="invalidContext" type="warning" message="任务列表缺少所属页面，请先保存并发布页面" show-icon />
    <a-empty v-else-if="needsRecord" description="请先从列表选择一条记录" />
    <template v-else>
      <a-alert v-if="fieldError" type="warning" :message="fieldError" show-icon />
      <a-space v-if="searchFields.length" wrap style="margin-bottom: 12px">
        <a-button @click="conditionsOpen = true">筛选业务字段</a-button>
        <a-tag v-if="conditions" color="blue">已应用业务字段条件</a-tag>
        <a-button v-if="conditions" type="link" @click="applyConditions(null)">清除业务筛选</a-button>
      </a-space>
      <OsDynamicSearch
        display-mode="drawer"
        v-model:open="conditionsOpen"
        :model-value="conditions"
        :fields="searchFields"
        :allow-save="false"
        strict
        @confirm="applyConditions"
      />
      <TaskList
        ref="taskList"
        embedded
        scope="MANAGE"
        :context="context"
        :view="taskView"
        :refresh-key="(refreshKey || 0) + localRefresh"
        :business-columns="businessColumns"
        :business-values="businessValues"
        @loaded="loadBusiness"
        @unlink="unlink"
      />
    </template>
    <TaskLinkPicker
      v-if="linkOpen && linkContext"
      :context="linkContext"
      @close="linkOpen = false"
      @linked="localRefresh++"
    />
    <OsModalForm
      :wrap-form="false"
      :open="launchOpen"
      title="新建任务"
      display-mode="drawer"
      :allow-switch-display="false"
      maximizable
      :width="launchWidth"
      @cancel="closeLaunch"
    >
      <template #formItems>
        <TaskLaunch
          v-if="launchOpen"
          ref="launch"
          embedded
          :footer-target="launchFooterTarget"
          :initial-application-id="applicationId"
          :initial-application-name="launchApplicationName"
          :initial-project="launchProject"
          :template-ids="taskView?.templateIds"
          :initial-binding="launchBinding || undefined"
          @created="created"
          @draft-saved="draftSaved"
        />
      </template>
      <template #footer><div ref="launchFooterTarget" /></template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.page-tasks {
  min-width: 0;
  width: 100%;
}
.page-tasks__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 12px;
}
.page-tasks__header h3 {
  margin: 0;
  min-width: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-color, #1f2937);
  overflow-wrap: anywhere;
}
</style>
