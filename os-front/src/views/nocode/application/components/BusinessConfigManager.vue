<script setup lang="ts">
import { v4 as uuidv4 } from 'uuid'
import { computed, inject, provide, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlayCircleOutlined,
  PlusOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import '../../management-tables.css'
import request from '@/utils/request'
import { getProcessDefinition } from '@/api/bpm/definition'
import { errorMessage } from '@/nocode/data-center'
import { useResourceCode } from '@/nocode/resource-code'
import { resourceSnapshot } from '@/nocode/application-resource'
import { confirmDiscard, useUnsavedNavigation } from '@/nocode/unsaved'
import { selectionPreviewKey } from '@/nocode/selection'
import { dateTriggerSummary, defaultAutomation, defaultDateAutomation, validateAutomation } from '@/nocode/automation'
import { nocodePlatformKey } from '@/nocode/platform'
import { captureCompatible, captureMappings, captureTarget } from '@/nocode/capture-action'
import type { AutomationConfig, DateTriggerStatus } from '@/types/nocode/automation'
import { ResourceKind, type ApplicationResource, type PublishedObject } from '@/types/nocode/application'
import { FieldType, MemberState } from '@/types/nocode/enums'
import {
  NumberPeriod,
  BusinessActionKind,
  type AppDictionary,
  type NumberRule,
  type AppBusinessAction
} from '@/types/nocode/business'
import RecordForm from './RecordForm.vue'
import AutomationConfigEditor from './AutomationConfigEditor.vue'
const props = defineProps<{ objects: Record<string, PublishedObject>; readOnly: boolean; applicationId?: string }>()
const resources = defineModel<ApplicationResource[]>({ required: true }),
  emit = defineEmits<{ change: [] }>()
const kind = ref<ResourceKind>(ResourceKind.DICTIONARY),
  editing = ref<ApplicationResource>(),
  open = ref(false),
  error = ref(''),
  loading = ref(false),
  applying = ref(false)
const captureRows = ref<Array<{ target: string; source: string }>>([])
let baseline = ''
const signature = () => JSON.stringify([editing.value, actionFields.value, variableRows.value, captureRows.value])
const changed = () => open.value && !props.readOnly && signature() !== baseline
useUnsavedNavigation(() => applying.value || changed())
async function close() {
  if (applying.value) return
  if (await confirmDiscard(changed())) open.value = false
}
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId || '',
    objects: Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    }))
  }))
)
const kinds = [
  { label: '应用字典', value: ResourceKind.DICTIONARY },
  { label: '业务编号', value: ResourceKind.NUMBER_RULE },
  { label: '业务动作', value: ResourceKind.ACTION }
]
/** 执行方式：「按日期自动执行」与「数据变化后自动执行」同属自动更新资源，以 mode=DATE 区分。 */
const DATE_EXECUTION = 'DATE' as const
type ExecutionKind = typeof ResourceKind.ACTION | typeof ResourceKind.AUTOMATION | typeof DATE_EXECUTION
const executionOptions = [
  { label: '手动执行', value: ResourceKind.ACTION },
  { label: '数据变化后自动执行', value: ResourceKind.AUTOMATION },
  { label: '按日期自动执行', value: DATE_EXECUTION }
]
const executionOf = (resource?: ApplicationResource): ExecutionKind | undefined =>
  resource?.kind === ResourceKind.AUTOMATION && resource.config.mode === 'DATE'
    ? DATE_EXECUTION
    : (resource?.kind as ExecutionKind | undefined)
const executionLabel = (resource: ApplicationResource) =>
  executionOptions.find(option => option.value === executionOf(resource))?.label || '手动执行'
const isDated = (resource: ApplicationResource) => executionOf(resource) === DATE_EXECUTION
const isAction = (value?: ResourceKind): value is typeof ResourceKind.ACTION | typeof ResourceKind.AUTOMATION =>
  value === ResourceKind.ACTION || value === ResourceKind.AUTOMATION
const creating = ref(false)
const executionDrafts = ref<
  Partial<
    Record<
      ExecutionKind,
      {
        config: Record<string, unknown>
        fields: string[]
        variables: Array<{ name: string; fieldId: string }>
        captures: Array<{ target: string; source: string }>
      }
    >
  >
>({})
const codeSuggestion = useResourceCode({
  name: () => editing.value?.name || '',
  kind: () => editing.value?.kind || kind.value,
  enabled: () => creating.value,
  setCode: code => {
    if (editing.value) editing.value.code = code
  }
})
const visible = computed(() =>
  resources.value.filter(r => (kind.value === ResourceKind.ACTION ? isAction(r.kind) : r.kind === kind.value))
)
const columns = computed(() => [
  { title: '名称', key: 'name', dataIndex: 'name', width: 240, ellipsis: true },
  { title: '编码', key: 'code', dataIndex: 'code', width: 220, ellipsis: true },
  ...(kind.value === ResourceKind.ACTION
    ? [
        { title: '执行方式', key: 'execution', width: 210 },
        { title: '动作内容', key: 'content', width: 240 },
        { title: '自动执行状态', key: 'enabled', width: 140 },
        { title: '最近执行', key: 'lastRun', width: 260 }
      ]
    : []),
  { title: '操作', key: 'actions', width: 300, fixed: 'right' as const }
])
const actionContent = (resource: ApplicationResource) =>
  isDated(resource)
    ? `更新数据 · ${dateTriggerSummary(resource.config as unknown as AutomationConfig, props.objects)}`
    : resource.kind === ResourceKind.AUTOMATION
      ? `更新关联数据 · ${resource.config.mode === 'MAINTAIN' ? '持续维护' : '事件赋值'}`
      : resource.config.kind === BusinessActionKind.START_PROCESS
        ? '发起流程'
        : resource.config.kind === BusinessActionKind.CAPTURE_VALUES
          ? '留存计算结果'
          : '更新当前记录字段'
const dictionary = computed(() => editing.value!.config as unknown as AppDictionary),
  number = computed(() => editing.value!.config as unknown as NumberRule),
  action = computed(() => editing.value!.config as unknown as AppBusinessAction),
  automation = computed({
    get: () => editing.value!.config as unknown as AutomationConfig,
    set: value => {
      editing.value!.config = value as unknown as Record<string, unknown>
    }
  })
const object = computed(() => props.objects[String(editing.value?.config.objectId)]?.definition)
const fields = computed(
  () => object.value?.fields.filter(f => object.value?.fieldOptions[f.id!]?.state !== MemberState.INACTIVE) || []
)
const objectOptions = computed(() =>
  Object.values(props.objects).map(o => ({ label: o.definition.objectName, value: o.objectId }))
)
const targetFields = computed(() =>
  fields.value.filter(f => f.type === FieldType.TEXT).map(f => ({ label: f.name, value: f.id! }))
)
const processes = ref<Array<{ label: string; value: string }>>([])
const variableRows = ref<Array<{ name: string; fieldId: string }>>([])
const captureTargets = computed(() =>
  fields.value.filter(field =>
    captureTarget(
      field,
      object.value?.fieldOptions[field.id || ''],
      object.value?.relations.some(r => r.fieldId === field.id)
    )
  )
)
function captureSources(targetId: string) {
  const target = captureTargets.value.find(field => field.id === targetId)
  return target
    ? fields.value.filter(field => captureCompatible(field, object.value?.fieldOptions[field.id || ''], target))
    : []
}
async function loadProcesses() {
  try {
    // 应用专用流程可以不显示在通用发起页，不能使用会过滤 visible=false 的发起列表。
    const candidates = await request.get<Array<{ id: string }>>('/bpm/process-definition/simple-list')
    const definitions = []
    for (let index = 0; index < candidates.length; index += 8)
      definitions.push(...(await Promise.all(candidates.slice(index, index + 8).map(p => getProcessDefinition(p.id)))))
    processes.value = definitions
      .filter(p => p.formType === 20 && p.formCustomViewPath === '/nocode-app/process-record')
      .map(p => ({ label: p.name + ' · V' + p.version, value: p.id }))
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function actionKindChanged() {
  action.value.values = {}
  action.value.variables = {}
  action.value.processDefinitionId = null
  actionFields.value = []
  variableRows.value = []
  captureRows.value = action.value.kind === BusinessActionKind.CAPTURE_VALUES ? [{ target: '', source: '' }] : []
  delete action.value.captures
}
const actionFields = ref<string[]>([]),
  actionForm = ref<InstanceType<typeof RecordForm>>()
const editableFields = computed(() =>
  fields.value.filter(
    f =>
      ![FieldType.FORMULA, FieldType.SUMMARY, FieldType.AUTO_NUMBER].some(t => t === f.type) &&
      (!object.value?.fieldOptions[f.id!]?.generated || object.value?.relations.some(r => r.fieldId === f.id))
  )
)
const selectedActionFields = computed(() => editableFields.value.filter(f => actionFields.value.includes(f.id!)))
const actionModel = computed(() => ({
  writable: !props.readOnly,
  generatedKey: true,
  keyFieldId: null,
  keyType: 'bigint'
}))
const actionOptions = computed(() =>
  Object.fromEntries(
    Object.entries(object.value?.fieldOptions || {}).map(([id, options]) => [
      id,
      object.value?.relations.some(r => r.fieldId === id) ? { ...options, generated: false } : options
    ])
  )
)
const sourceTypes = ref<Array<{ label: string; value: string }>>([]),
  sourceType = ref<string>()
async function loadSources() {
  try {
    sourceTypes.value = (
      await request.get<Array<{ name: string; type: string }>>('/system/dict-type/list-all-simple')
    ).map(d => ({ label: d.name, value: d.type }))
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function copySource() {
  if (!sourceType.value) return
  loading.value = true
  error.value = ''
  try {
    const data = await request.get<Array<{ dictType: string; value: string; label: string }>>(
      '/system/dict-data/list-all-simple'
    )
    dictionary.value.items = data
      .filter(d => d.dictType === sourceType.value)
      .map(d => ({ code: d.value, label: d.label, disabled: false }))
    dictionary.value.sourceType = sourceType.value
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
function objectChanged() {
  if (!editing.value) return
  if (editing.value.kind === ResourceKind.NUMBER_RULE) number.value.fieldId = ''
  else {
    action.value.values = {}
    actionFields.value = []
    variableRows.value = []
    action.value.variables = {}
    delete action.value.captures
    captureRows.value = action.value.kind === BusinessActionKind.CAPTURE_VALUES ? [{ target: '', source: '' }] : []
  }
}
function defaultAction(objectId: string): Record<string, unknown> {
  return { objectId, kind: BusinessActionKind.UPDATE_FIELDS, values: {}, processDefinitionId: null, variables: {} }
}
/** 新建时分别保留两种执行方式的输入；已创建资源保持身份与既有引用不变。 */
function executionChanged(next: ExecutionKind) {
  const resource = editing.value
  if (
    !resource ||
    !creating.value ||
    props.readOnly ||
    applying.value ||
    !isAction(resource.kind) ||
    executionOf(resource) === next
  )
    return
  executionDrafts.value[executionOf(resource) || ResourceKind.ACTION] = {
    config: resourceSnapshot(resource).config,
    fields: [...actionFields.value],
    variables: variableRows.value.map(row => ({ ...row })),
    captures: captureRows.value.map(row => ({ ...row }))
  }
  const previous = executionDrafts.value[next]
  const objectId = String(resource.config.objectId || objectOptions.value[0]?.value || '')
  resource.kind = next === DATE_EXECUTION ? ResourceKind.AUTOMATION : next
  resource.config =
    previous?.config ||
    (next === DATE_EXECUTION
      ? (defaultDateAutomation(objectId) as unknown as Record<string, unknown>)
      : next === ResourceKind.AUTOMATION
        ? (defaultAutomation(objectId) as unknown as Record<string, unknown>)
        : defaultAction(objectId))
  actionFields.value = previous?.fields || []
  variableRows.value = previous?.variables || []
  captureRows.value = previous?.captures || []
  error.value = ''
  if (next === ResourceKind.ACTION) void loadProcesses()
}
function edit(resource?: ApplicationResource) {
  creating.value = !resource
  executionDrafts.value = {}
  codeSuggestion.reset()
  error.value = ''
  sourceType.value = undefined
  captureRows.value = Object.entries((resource?.config.captures as Record<string, string>) || {}).map(
    ([target, source]) => ({ target, source })
  )
  if (resource) editing.value = resourceSnapshot(resource)
  else {
    const objectId = objectOptions.value[0]?.value || ''
    const config =
      kind.value === ResourceKind.DICTIONARY
        ? { sourceType: null, items: [{ code: 'option_1', label: '选项一', disabled: false }] }
        : kind.value === ResourceKind.NUMBER_RULE
          ? { objectId, fieldId: '', prefix: 'ORD-', period: NumberPeriod.DAY, width: 4 }
          : defaultAction(objectId)
    editing.value = {
      id: uuidv4(),
      kind: kind.value,
      code: '',
      name: '',
      config
    }
  }
  actionFields.value = Object.keys((editing.value.config.values || {}) as object)
  variableRows.value = Object.entries((editing.value.config.variables || {}) as Record<string, string>).map(
    ([name, fieldId]) => ({ name, fieldId })
  )
  baseline = signature()
  if (editing.value.kind === ResourceKind.ACTION) void loadProcesses()
  open.value = true
  if (editing.value.kind === ResourceKind.DICTIONARY) void loadSources()
}
async function apply() {
  if (!editing.value || applying.value || props.readOnly) return
  applying.value = true
  try {
    if (!editing.value.name.trim()) throw new Error('请填写配置名称')
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(editing.value.code)) throw new Error('编码使用小写字母开头、数字和下划线')
    if (resources.value.some(r => r.id !== editing.value!.id && r.code === editing.value!.code))
      throw new Error('资源编码已存在，请修改编码，例如添加 _2 后缀')
    if (editing.value.kind === ResourceKind.ACTION) {
      if (action.value.kind === BusinessActionKind.UPDATE_FIELDS) {
        await actionForm.value?.validate()
        action.value.values = Object.fromEntries(actionFields.value.map(id => [id, action.value.values[id] ?? null]))
      } else if (action.value.kind === BusinessActionKind.CAPTURE_VALUES) {
        if (!object.value) throw new Error('请选择业务对象')
        action.value.captures = captureMappings(captureRows.value, object.value)
        action.value.values = {}
        action.value.variables = {}
        action.value.processDefinitionId = null
      } else {
        if (!action.value.processDefinitionId) throw new Error('请选择已发布流程')
        if (new Set(variableRows.value.map(v => v.name)).size !== variableRows.value.length)
          throw new Error('流程变量名称不能重复')
        action.value.variables = Object.fromEntries(variableRows.value.map(v => [v.name, v.fieldId]))
      }
    }
    if (editing.value.kind === ResourceKind.AUTOMATION) validateAutomation(automation.value, props.objects)
    const value = resourceSnapshot(editing.value)
    resources.value = resources.value.some(r => r.id === value.id)
      ? resources.value.map(r => (r.id === value.id ? value : r))
      : [...resources.value, value]
    emit('change')
    open.value = false
    message.success('已更新业务配置草稿，请保存应用后发布')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    applying.value = false
  }
}
/**
 * 自动执行的动作（数据变化后、按日期）写同一字段时按列表从上到下执行，后执行的为准；「上移」把它与上一条自动执行的动作对调。
 * 例：同日先退房后入住要显示「空房待清扫」，就把「退房日」那条排在「入住日」之后。
 */
const automationIds = computed(() => resources.value.filter(r => r.kind === ResourceKind.AUTOMATION).map(r => r.id))
function moveUp(id: string) {
  const ids = automationIds.value,
    at = ids.indexOf(id)
  if (props.readOnly || at <= 0) return
  const next = [...resources.value],
    from = next.findIndex(r => r.id === id),
    to = next.findIndex(r => r.id === ids[at - 1])
  ;[next[from], next[to]] = [next[to], next[from]]
  resources.value = next
  emit('change')
}
function remove(id: string) {
  resources.value = resources.value.filter(r => r.id !== id)
  emit('change')
}
/**
 * 按日期自动执行的最近执行结果：只对已发布版本里在跑的规则有数据（草稿里新加的还没在跑）。
 * 没有注入平台（单独挂载的测试）或读取失败时整列显示「—」，不弹全局错误。
 */
const platform = inject(nocodePlatformKey, null)
const dateRuns = ref<Record<string, DateTriggerStatus>>({}),
  running = ref('')
const canRunNow = computed(() => !!platform?.hasPermission('nocode:app:publish'))
async function loadDateRuns() {
  if (!platform || !props.applicationId) return
  try {
    const rows = await platform.applications.dateTriggerStatus(props.applicationId)
    dateRuns.value = Object.fromEntries(rows.map(row => [row.resourceId, row]))
  } catch {
    dateRuns.value = {}
  }
}
watch(
  () => [props.applicationId, kind.value] as const,
  ([, current]) => {
    if (current === ResourceKind.ACTION) void loadDateRuns()
  },
  { immediate: true }
)
const shortDate = (day?: string | null) => (day ? day.slice(5) : '')
async function runToday(resource: ApplicationResource) {
  if (!platform || !props.applicationId || running.value) return
  running.value = resource.id
  try {
    const result = await platform.applications.runDateTrigger({ id: props.applicationId, resourceId: resource.id })
    message.success(
      `今天（${result.businessDate}）已执行：更新 ${result.success} 条，已是该值 ${result.unchanged} 条，失败 ${result.failed} 条`
    )
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    running.value = ''
    void loadDateRuns()
  }
}
</script>
<template>
  <div class="toolbar">
    <a-segmented v-model:value="kind" :options="kinds" />
  </div>
  <p class="hint">
    {{
      kind === ResourceKind.ACTION
        ? '手动动作可配置到页面按钮；自动动作随数据变化或按日期执行，可更新关联对象的实际字段；多条自动动作写同一字段时按列表从上到下执行，后执行的为准。保存草稿并发布后生效。'
        : '字典用于应用页面筛选；业务编号沿用全局对象约束与运行权限，保存草稿后发布生效。'
    }}
  </p>
  <OsTablePage
    :key="kind"
    :title="kind === ResourceKind.ACTION ? '业务动作' : '业务配置列表'"
    class="nocode-embedded-table"
    show-column-settings
    :column-settings-key="kind === ResourceKind.ACTION ? 'nocode-app-business-actions' : 'nocode-app-business-config'"
    resizable
    :scroll="{ x: 'max-content' }"
    :data-source="visible"
    row-key="id"
    :pagination="false"
    :columns="columns"
  >
    <template #actions>
      <a-button v-if="!readOnly" type="primary" @click="edit()">
        <PlusOutlined />
        {{ kind === ResourceKind.ACTION ? '新增业务动作' : '新增配置' }}
      </a-button>
    </template>
    <template #bodyCell="{ column, record }">
      <a-tag
        v-if="column.key === 'execution'"
        :color="isDated(record) ? 'purple' : record.kind === ResourceKind.AUTOMATION ? 'cyan' : 'blue'"
      >
        {{ executionLabel(record) }}
      </a-tag>
      <template v-else-if="column.key === 'lastRun'">
        <template v-if="isDated(record) && dateRuns[record.id]?.businessDate">
          <span class="date-run">
            {{ shortDate(dateRuns[record.id].businessDate) }}
            {{ dateRuns[record.id].lastTrigger === 'MANUAL' ? '（手动）' : '' }}
            更新 {{ dateRuns[record.id].success }} · 已是该值 {{ dateRuns[record.id].unchanged }} ·
          </span>
          <a-tag v-if="dateRuns[record.id].failed" color="red">失败 {{ dateRuns[record.id].failed }}</a-tag>
          <span v-else>失败 0</span>
          <p
            v-for="failure in dateRuns[record.id].failures.slice(0, 3)"
            :key="failure.sourceRecordId"
            class="date-run-failure"
          >
            记录 {{ failure.sourceRecordId }}：{{ failure.message }}
          </p>
          <p v-if="dateRuns[record.id].failures.length > 3" class="date-run-failure">
            另有 {{ dateRuns[record.id].failures.length - 3 }} 条失败
          </p>
          <p v-if="dateRuns[record.id].lastError" class="date-run-failure">{{ dateRuns[record.id].lastError }}</p>
        </template>
        <span v-else-if="isDated(record) && dateRuns[record.id]?.armed">已生效，等待首次执行</span>
        <span v-else-if="isDated(record)" class="hint-inline">发布后开始执行</span>
        <span v-else>—</span>
      </template>
      <span v-else-if="column.key === 'content'">{{ actionContent(record) }}</span>
      <template v-else-if="column.key === 'enabled'">
        <a-tag
          v-if="record.kind === ResourceKind.AUTOMATION"
          :color="record.config.enabled === false ? 'default' : 'green'"
        >
          {{ record.config.enabled === false ? '已停用' : '已启用' }}
        </a-tag>
        <span v-else>—</span>
      </template>
      <div v-else-if="column.key === 'actions'" class="nocode-table-actions">
        <a-button type="link" @click="edit(record)">
          <EyeOutlined v-if="readOnly" />
          <EditOutlined v-else />
          {{ readOnly ? '查看' : '配置' }}
        </a-button>
        <a-popconfirm
          v-if="isDated(record) && dateRuns[record.id]?.armed && canRunNow"
          :title="`立即按今天执行「${record.name}」？今天已经执行过的记录不会重复执行，失败的会重试；会修改目标记录的数据。`"
          ok-text="立即执行"
          @confirm="runToday(record)"
        >
          <a-button type="link" :loading="running === record.id" :disabled="!!running">
            <PlayCircleOutlined />
            立即按今天执行
          </a-button>
        </a-popconfirm>
        <a-button
          v-if="!readOnly && record.kind === ResourceKind.AUTOMATION && automationIds.indexOf(record.id) > 0"
          type="link"
          title="与上一条自动执行的动作对调执行先后"
          @click="moveUp(record.id)"
        >
          <ArrowUpOutlined />
          上移
        </a-button>
        <a-popconfirm v-if="!readOnly" title="删除此业务配置？仍有依赖时保存会被阻止。" @confirm="remove(record.id)">
          <a-button type="link" danger>
            <DeleteOutlined />
            删除
          </a-button>
        </a-popconfirm>
      </div>
    </template>
  </OsTablePage>
  <a-modal
    wrap-class-name="os-scroll-modal"
    :open="open"
    :width="editing?.kind === ResourceKind.AUTOMATION ? 1080 : 900"
    :title="
      isAction(editing?.kind) ? (readOnly ? '查看业务动作' : creating ? '新增业务动作' : '配置业务动作') : '业务配置'
    "
    :destroy-on-close="true"
    :footer="readOnly ? null : undefined"
    :confirm-loading="applying"
    :closable="!applying"
    :mask-closable="!applying"
    @cancel="close"
    @ok="apply"
  >
    <a-alert v-if="error" type="error" :message="error" show-icon class="notice" />
    <a-form v-if="editing" layout="vertical" :disabled="readOnly || applying">
      <a-form-item v-if="isAction(editing.kind)" label="执行方式" required>
        <a-radio-group
          :value="executionOf(editing)"
          :options="executionOptions"
          :disabled="!creating || readOnly || applying"
          @update:value="executionChanged"
        />
        <p v-if="!creating" class="execution-hint">已创建动作的执行方式固定。如需其他执行方式，请新增业务动作。</p>
      </a-form-item>
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="名称" required><a-input v-model:value="editing.name" /></a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="编码" required>
            <a-input
              :value="editing.code"
              :maxlength="64"
              placeholder="按名称自动生成，可手动修改"
              @update:value="codeSuggestion.changeCode"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <template v-if="editing.kind === ResourceKind.DICTIONARY">
        <a-alert
          type="info"
          message="可从底座字典复制初始选项；此后由应用独立维护并随版本发布，不修改来源字典或全局对象字段。"
          class="notice"
        />
        <a-space class="notice">
          <a-select
            v-model:value="sourceType"
            :options="sourceTypes"
            show-search
            option-filter-prop="label"
            placeholder="选择底座字典来源"
            style="width: 260px"
          />
          <a-button :loading="loading" @click="copySource">复制选项</a-button>
        </a-space>
        <div v-for="(item, index) in dictionary.items" :key="index" class="option-row">
          <a-input v-model:value="item.code" placeholder="稳定编码" />
          <a-input v-model:value="item.label" placeholder="显示名称" />
          <a-checkbox v-model:checked="item.disabled">停用</a-checkbox>
          <a-button @click="dictionary.items.splice(index, 1)">移除</a-button>
        </div>
        <a-button @click="dictionary.items.push({ code: '', label: '', disabled: false })">添加选项</a-button>
      </template>
      <AutomationConfigEditor
        v-else-if="editing.kind === ResourceKind.AUTOMATION"
        v-model="automation"
        :objects="objects"
        :application-id="applicationId"
        :read-only="readOnly || applying"
      />
      <template v-else>
        <a-form-item label="数据对象" required>
          <a-select v-model:value="editing.config.objectId" :options="objectOptions" @change="objectChanged" />
        </a-form-item>
        <template v-if="editing.kind === ResourceKind.NUMBER_RULE">
          <a-alert
            type="info"
            message="绑定数据中心已有的唯一文本字段。新增记录自动编号，多个应用共享同一字段的计数器；保存失败整体回滚计数。"
            class="notice"
          />
          <a-form-item label="编号字段" required>
            <a-select v-model:value="number.fieldId" :options="targetFields" />
          </a-form-item>
          <a-row :gutter="16">
            <a-col :span="10">
              <a-form-item label="固定前缀"><a-input v-model:value="number.prefix" :maxlength="32" /></a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="日期与重置周期">
                <a-select
                  v-model:value="number.period"
                  :options="[
                    { label: '不重置', value: NumberPeriod.NONE },
                    { label: '每年', value: NumberPeriod.YEAR },
                    { label: '每月', value: NumberPeriod.MONTH },
                    { label: '每天', value: NumberPeriod.DAY }
                  ]"
                />
              </a-form-item>
            </a-col>
            <a-col :span="6">
              <a-form-item label="流水号最少位数">
                <a-input-number v-model:value="number.width" :min="1" :max="12" />
              </a-form-item>
            </a-col>
          </a-row>
        </template>
        <template v-else-if="editing.kind === ResourceKind.ACTION">
          <a-form-item label="动作类型">
            <a-select
              v-model:value="action.kind"
              :options="[
                { label: '更新字段', value: BusinessActionKind.UPDATE_FIELDS },
                { label: '留存计算结果', value: BusinessActionKind.CAPTURE_VALUES },
                { label: '发起流程', value: BusinessActionKind.START_PROCESS }
              ]"
              @change="actionKindChanged"
            />
          </a-form-item>
          <template v-if="action.kind === BusinessActionKind.START_PROCESS">
            <a-alert
              type="info"
              message="复用流程中心已发布的业务表单流程，查看路径须为 /nocode-app/process-record。审批人需要此应用的记录查看权限；首版使用流程预设审批人。"
              class="notice"
            />
            <a-form-item label="流程版本" required>
              <a-select
                v-model:value="action.processDefinitionId"
                :options="processes"
                show-search
                option-filter-prop="label"
              />
            </a-form-item>
            <p>业务变量以 nc_ 开头，例如 nc_amount；发起时从当前记录读取。</p>
            <div v-for="(variable, index) in variableRows" :key="index" class="option-row">
              <a-input v-model:value="variable.name" placeholder="nc_amount" />
              <a-select
                v-model:value="variable.fieldId"
                :options="editableFields.map(f => ({ label: f.name, value: f.id! }))"
                style="min-width: 240px"
              />
              <a-button @click="variableRows.splice(index, 1)">移除</a-button>
            </div>
            <a-button @click="variableRows.push({ name: 'nc_', fieldId: '' })">映射业务变量</a-button>
          </template>
          <template v-else-if="action.kind === BusinessActionKind.CAPTURE_VALUES">
            <a-alert
              type="info"
              show-icon
              class="notice"
              message="执行时读取当前已保存记录的公式结果并留存。目标尚未取值时为空；留存后普通编辑和来源变化不会覆盖，重复执行将被拒绝。"
            />
            <div v-for="(capture, index) in captureRows" :key="index" class="capture-row">
              <a-form-item label="留存到字段" required>
                <a-select
                  v-model:value="capture.target"
                  :options="captureTargets.map(f => ({ label: f.name, value: f.id! }))"
                  show-search
                  option-filter-prop="label"
                  placeholder="选择可空、无默认值的普通字段"
                  @change="capture.source = ''"
                />
              </a-form-item>
              <a-form-item label="来源公式" required>
                <a-select
                  v-model:value="capture.source"
                  :options="captureSources(capture.target).map(f => ({ label: f.name, value: f.id! }))"
                  show-search
                  option-filter-prop="label"
                  placeholder="选择同类型公式"
                  :disabled="!capture.target"
                />
              </a-form-item>
              <a-button danger @click="captureRows.splice(index, 1)">移除</a-button>
            </div>
            <a-button :disabled="captureRows.length >= 20" @click="captureRows.push({ target: '', source: '' })">
              添加留存字段
            </a-button>
            <p class="hint">
              请先在数据对象创建目标字段。发布后目标由此动作维护，执行者须拥有来源公式读取和目标字段修改权限。
            </p>
          </template>
          <template v-else>
            <a-alert
              type="info"
              message="点击动作会把选定字段更新为以下值；执行者仍须拥有对应记录和字段的修改权限。"
              class="notice"
            />
            <a-form-item label="更新字段" required>
              <a-select
                v-model:value="actionFields"
                mode="multiple"
                show-search
                option-filter-prop="label"
                :options="editableFields.map(f => ({ label: f.name, value: f.id! }))"
              />
            </a-form-item>
            <RecordForm
              v-if="object"
              ref="actionForm"
              v-model="action.values"
              :fields="selectedActionFields"
              :options="actionOptions"
              :model="actionModel"
              :creating="false"
              :application-id="applicationId"
              :object-id="object.objectId"
              :relations="object.relations"
              preview
            />
          </template>
        </template>
      </template>
    </a-form>
  </a-modal>
</template>
<style scoped>
.capture-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
}
.toolbar,
.option-row {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}
.option-row {
  margin-bottom: 12px;
}
.option-row .ant-checkbox-wrapper {
  white-space: nowrap;
}
.hint {
  color: #64748b;
  margin: 16px 0;
}
.notice {
  margin-bottom: 16px;
}
.date-run {
  margin-right: 4px;
}
.date-run-failure {
  max-width: 420px;
  margin: 4px 0;
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
.hint-inline {
  color: var(--text-color-secondary, #8c8c8c);
}
.execution-hint {
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
  margin: 8px 0 0;
}
.danger {
  color: #cf1322;
}
</style>
