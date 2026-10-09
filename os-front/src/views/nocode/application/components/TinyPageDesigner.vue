<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import type { UiNode } from '@/types/nocode/application-ui'
import { pageSchema, pageNodes, type PageSchemaNode } from '@/nocode/page-schema'
import { nestingMessage, pageStructureError, type NestingViolation } from '@/nocode/page-nesting'
import {
  NodeKind,
  PageActionKind,
  PageAlign,
  PageDirection,
  PageAlertType,
  PageButtonType,
  PageImageFit,
  RecordOpenMode
} from '@/types/nocode/application-ui'
import { pageActionTargets } from '@/nocode/page-actions'
import { isStandaloneApplicationPage } from '@/nocode/runtime-navigation'
import { applicationDashboardMatchesPage } from '@/nocode/application-dashboard'
import { resolveViewForm } from '@/nocode/default-form'
import { styleFields } from '@/nocode/page-appearance'
import { pageImageUrl } from '@/nocode/page-image'
import InfraUpload from '@/components/InfraUpload.vue'
import TaskViewEditor from './TaskViewEditor.vue'
import EngineBlockEditor from './EngineBlockEditor.vue'
import { RelationType } from '@/types/nocode/enums'

const props = defineProps<{
  applicationId?: string
  nodes: UiNode[]
  resources: ApplicationResource[]
  objects: Record<string, PublishedObject>
  contextObjectId?: string
  readOnly?: boolean
}>()
const emit = defineEmits<{ change: []; ready: [] }>()
const frame = ref<HTMLIFrameElement>(),
  frameAttempt = ref(0),
  ready = ref(false),
  error = ref(''),
  selected = ref<PageSchemaNode>(),
  latest = ref(pageSchema(props.nodes))
let initial = true
let readyGeneration = 0
let loadTimer: ReturnType<typeof setTimeout> | undefined
function clearLoadTimer() {
  clearTimeout(loadTimer)
  loadTimer = undefined
}
function failLoading(message: string) {
  readyGeneration++
  clearLoadTimer()
  // 嵌套画布可能在编辑器发出 READY 后才报错，此时也要回到可重试状态。
  ready.value = false
  error.value = message
}
function startLoading() {
  clearLoadTimer()
  loadTimer = setTimeout(() => {
    if (!ready.value) failLoading('页面设计器加载超时，请重试。')
  }, 30000)
}
function checkFrame() {
  // 静态入口缺失时，开发服务器可能返回 HTTP 200 的主站首页，不能只判断 load 事件。
  try {
    if (!frame.value?.contentDocument?.querySelector('meta[name="os-page-designer"][content="editor"]'))
      failLoading('页面设计器资源不可用，请联系管理员检查部署后重试。')
  } catch {
    failLoading('无法访问页面设计器，请重新加载。')
  }
}
function retryLoading() {
  readyGeneration++
  ready.value = false
  error.value = ''
  selected.value = undefined
  frameAttempt.value++
  startLoading()
}
const uploadStatus = ref({ pending: false, failed: false })
const resolvingImage = ref(false)
const imageBusy = computed(() => uploadStatus.value.pending || resolvingImage.value)
const insertMode = ref('root'),
  insertComponent = ref<string>()
const propertiesOpen = ref(window.innerWidth >= 1100)
const focusMode = ref(false)
let propertiesBeforeFocus = propertiesOpen.value
function toggleFocus() {
  focusMode.value = !focusMode.value
  if (focusMode.value) {
    propertiesBeforeFocus = propertiesOpen.value
    propertiesOpen.value = false
  } else propertiesOpen.value = propertiesBeforeFocus
  send('FOCUS', { enabled: focusMode.value })
}
function toggleProperties() {
  if (focusMode.value) {
    toggleFocus()
    propertiesOpen.value = true
  } else propertiesOpen.value = !propertiesOpen.value
}
const nodeLabels: Record<string, string> = {
  OsCard: '卡片',
  OsRow: '分栏容器',
  OsCol: '分栏',
  OsTabs: '页签容器',
  OsTab: '页签',
  OsText: '说明文字',
  OsDivider: '分隔线',
  OsView: '数据列表',
  OsReport: '统计报表',
  OsReportDashboard: '报表看板',
  OsForm: '业务表单',
  OsDetail: '记录详情',
  OsRelated: '相关列表',
  OsAttachments: '记录附件',
  OsProcesses: '审批记录',
  OsTasks: '任务列表',
  OsMetric: '记录统计',
  OsEngine: '设计引擎',
  OsHeading: '标题',
  OsImage: '图片',
  OsAlert: '提示信息',
  OsButton: '操作按钮',
  OsFlex: '弹性布局',
  OsSpacer: '留白'
}
const containerNames = ['OsCard', 'OsCol', 'OsRow', 'OsTabs', 'OsTab', 'OsFlex']
const insertParent = computed(() => (insertMode.value === 'selected' ? selected.value : undefined))
const insertOptions = computed(() =>
  Object.entries(nodeLabels)
    .filter(([name]) => {
      const parent = insertParent.value?.componentName || 'Page'
      if (parent === 'OsRow') return name === 'OsCol'
      if (parent === 'OsTabs') return name === 'OsTab'
      return name !== 'OsCol' && name !== 'OsTab'
    })
    .map(([value, label]) => ({ value, label }))
)
const canInsert = computed(
  () =>
    ready.value &&
    !props.readOnly &&
    !imageBusy.value &&
    !!insertComponent.value &&
    insertOptions.value.some(o => o.value === insertComponent.value) &&
    (insertMode.value === 'root' || containerNames.includes(selected.value?.componentName || ''))
)
function insert() {
  if (canInsert.value) send('INSERT', { parentId: insertParent.value?.id || '', componentName: insertComponent.value })
}
const send = (type: string, data: Record<string, unknown> = {}) =>
  frame.value?.contentWindow?.postMessage({ channel: 'os-page-designer', type, ...data }, location.origin)
async function receive(event: MessageEvent) {
  if (
    event.origin !== location.origin ||
    event.source !== frame.value?.contentWindow ||
    event.data?.channel !== 'os-page-designer'
  )
    return
  if (event.data.type === 'READY') {
    const generation = ++readyGeneration
    const source = event.source
    error.value = ''
    // 初次 latest 来自 props；重试从最后一次合法编辑恢复，并重新解析临时图片地址。
    const schema = pageSchema(pageNodes(latest.value))
    await hydrateImages(schema)
    if (generation !== readyGeneration || source !== frame.value?.contentWindow) return
    clearLoadTimer()
    error.value = ''
    ready.value = true
    send('INIT', { schema })
    send('FOCUS', { enabled: focusMode.value })
    emit('ready')
  }
  if (event.data.type === 'ERROR') failLoading(String(event.data.message || '页面设计器初始化失败'))
  // 画布拖放出现父子不合规（例如把页签容器拖到页签容器空白处）：编辑器已撤回这一步，这里说明原因。
  if (event.data.type === 'REJECTED' && event.data.violation)
    void message.warning(nestingMessage(event.data.violation as NestingViolation, nodeLabels))
  if (event.data.type === 'CHANGE') {
    try {
      pageNodes(event.data.schema)
      const changed = JSON.stringify(latest.value.children) !== JSON.stringify(event.data.schema.children)
      latest.value = event.data.schema
      selected.value = event.data.selected || undefined
      error.value = ''
      if (changed && !initial) emit('change')
      initial = false
    } catch (e) {
      error.value = (e as Error).message
    }
  }
}
/** 只把已经由宿主核验的同站图片地址交给画布，令牌始终留在底座。 */
async function hydrateImages(schema: PageSchemaNode) {
  await Promise.all((schema.children || []).map(hydrateImages))
  if (schema.componentName === 'OsImage' && schema.props?.display_imageFileId) {
    try {
      schema.props.imageUrl = await pageImageUrl(String(schema.props.display_imageFileId))
    } catch {
      schema.props.imageUrl = ''
    }
  }
}
const actionLabels: Record<PageActionKind, string> = {
  CREATE: '新增记录',
  EDIT: '编辑当前记录',
  VIEW: '查看当前记录',
  REFRESH: '刷新区块',
  OPEN_PAGE: '打开业务页面',
  NAVIGATE: '跳转业务页面',
  EXECUTE_ACTION: '执行业务动作'
}
const actionOptions = Object.entries(actionLabels).map(([value, label]) => ({ value, label }))
const blockAction = computed(() =>
  [PageActionKind.CREATE, PageActionKind.EDIT, PageActionKind.VIEW, PageActionKind.REFRESH].some(
    k => k === selected.value?.props?.action_kind
  )
)
const targetOptions = computed(() => {
  const kind = String(selected.value?.props?.action_kind || '')
  return pageActionTargets(pageNodes(latest.value), kind)
    .filter(n => {
      const r = props.resources.find(r => r.id === n.resourceId)
      if (!r) return false
      if (kind === PageActionKind.CREATE) {
        try {
          if (!resolveViewForm(props.resources, String(r.config.objectId), r.config.formId as string | null))
            return false
        } catch {
          return false
        }
        if (n.type === NodeKind.RELATED)
          return (
            n.binding?.direction === 'INCOMING' &&
            props.objects[String(r.config.objectId)]?.definition.relations.some(
              relation => relation.id === n.binding?.relationId && relation.kind !== RelationType.MANY_TO_MANY
            )
          )
      }
      if ([PageActionKind.EDIT, PageActionKind.VIEW].some(k => k === kind))
        return !!props.contextObjectId && r.config.objectId === props.contextObjectId
      return true
    })
    .map(n => ({
      value: n.id,
      label: `${n.text || '未命名区块'} · ${props.resources.find(r => r.id === n.resourceId)?.name}`
    }))
})
const actionResources = computed(() =>
  props.resources
    .filter(r => {
      const kind = selected.value?.props?.action_kind
      if (kind === PageActionKind.OPEN_PAGE)
        return (
          r.kind === ResourceKind.PAGE &&
          (!r.config.contextObjectId || r.config.contextObjectId === props.contextObjectId)
        )
      if (kind === PageActionKind.EXECUTE_ACTION)
        return r.kind === ResourceKind.ACTION && !!props.contextObjectId && r.config.objectId === props.contextObjectId
      if (kind === PageActionKind.NAVIGATE) {
        const target = props.resources.find(t => t.id === r.config.targetId)
        // 新配置直接选页面；旧按钮引用 MENU 时保留选项，不强制重写存量动作。
        return (
          isStandaloneApplicationPage(r) ||
          (r.kind === ResourceKind.MENU &&
            isStandaloneApplicationPage(target) &&
            r.id === selected.value?.props?.action_resourceId)
        )
      }
      return false
    })
    .map(r => ({ value: r.id, label: r.name }))
)
const modeOptions = [
  { value: RecordOpenMode.DRAWER, label: '右侧抽屉' },
  { value: RecordOpenMode.MODAL, label: '居中弹窗' }
]
const alignmentOptions = [
  { value: PageAlign.START, label: '靠左 / 起始' },
  { value: PageAlign.CENTER, label: '居中' },
  { value: PageAlign.END, label: '靠右 / 末尾' },
  { value: PageAlign.SPACE_BETWEEN, label: '两端分布' }
]
const spacingFields = [
  { key: 'padding', label: '内边距', max: 48 },
  { key: 'marginBottom', label: '下方间距', max: 48 },
  { key: 'radius', label: '圆角', max: 24 },
  { key: 'minHeight', label: '最小高度', max: 800 }
]
function actionChanged(kind: string) {
  update({
    action_kind: kind,
    action_targetNodeId: '',
    action_resourceId: '',
    action_openMode: '',
    action_confirmText: ''
  })
}
async function imageChanged(ids: string[]) {
  const id = ids.at(-1) || '',
    nodeId = selected.value?.id
  if (!id) {
    update({ display_imageFileId: '', imageUrl: '' })
    return
  }
  resolvingImage.value = true
  try {
    const url = await pageImageUrl(id)
    // 文件标识和画布地址作为一次编辑提交，撤销不会停在“有标识、无图片”的中间态。
    if (selected.value?.id === nodeId) update({ display_imageFileId: id, imageUrl: url })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    resolvingImage.value = false
  }
}
function resetStyle() {
  update(Object.fromEntries(styleFields.map(k => [`style_${k}`, ''])))
}
const isBusiness = computed(() =>
  [
    'OsView',
    'OsReport',
    'OsReportDashboard',
    'OsRelated',
    'OsForm',
    'OsDetail',
    'OsMetric',
    'OsAttachments',
    'OsProcesses',
    'OsTasks'
  ].includes(selected.value?.componentName || '')
)
const isList = computed(() => ['OsView', 'OsRelated'].includes(selected.value?.componentName || ''))
function changeListMode(related: boolean) {
  if (!ready.value || props.readOnly || !isList.value || (related && !props.contextObjectId)) return
  send('LIST_MODE', { id: selected.value!.id, related })
}
const resourceOptions = computed(() =>
  props.resources
    .filter(r => {
      if (selected.value?.componentName === 'OsReportDashboard')
        return applicationDashboardMatchesPage(r, props.contextObjectId)
      const form = ['OsForm', 'OsDetail', 'OsAttachments', 'OsProcesses', 'OsTasks'].includes(
        selected.value?.componentName || ''
      )
      if (
        r.kind !==
        (form
          ? ResourceKind.FORM
          : selected.value?.componentName === 'OsReport'
            ? ResourceKind.REPORT
            : ResourceKind.VIEW)
      )
        return false
      return !form || !props.contextObjectId || r.config.objectId === props.contextObjectId
    })
    .map(r => ({ label: r.name, value: r.id }))
)
const relations = computed(() => {
  const current = props.objects[props.contextObjectId || '']?.definition
  const resource = props.resources.find(r => r.id === selected.value?.props?.resourceId)
  const target = props.objects[String(resource?.config.objectId || '')]?.definition
  if (!current || !target) return []
  return [
    ...target.relations
      .filter(r => r.targetObjectId === current.objectId)
      .map(r => ({ label: `${r.name} · 目标记录关联当前记录`, value: `INCOMING:${r.id}` })),
    ...current.relations
      .filter(r => r.targetObjectId === target.objectId)
      .map(r => ({ label: `${r.name} · 当前记录关联目标`, value: `OUTGOING:${r.id}` }))
  ]
})
function update(values: Record<string, unknown>) {
  if (!selected.value || props.readOnly) return
  selected.value.props = { ...selected.value.props, ...values }
  send('PROPS', { id: selected.value.id, props: values })
}
function relationChanged(value?: string) {
  if (!value) {
    update({ relationId: '', direction: 'INCOMING' })
    return
  }
  const [direction, relationId] = value.split(':')
  update({ direction, relationId })
}
onMounted(() => {
  addEventListener('message', receive)
  startLoading()
})
onBeforeUnmount(() => {
  readyGeneration++
  clearLoadTimer()
  removeEventListener('message', receive)
})
watch(
  () => props.contextObjectId,
  () => {
    error.value = ''
  }
)
defineExpose({
  isReady: () => ready.value && !error.value && !imageBusy.value && !uploadStatus.value.failed,
  propertiesOpen,
  focusMode,
  toggleProperties,
  toggleFocus,
  // 比较可持久化节点，撤销回原样后不再仅凭引擎 CHANGE 事件判定未保存。
  hasChanges: () =>
    !!error.value || JSON.stringify(pageNodes(latest.value)) !== JSON.stringify(pageNodes(pageSchema(props.nodes))),
  getNodes: () => {
    if (imageBusy.value || uploadStatus.value.failed) throw new Error('请等待图片上传完成，或移除失败的文件后重试')
    if (!ready.value) throw new Error('页面设计器尚未就绪，请等待加载')
    if (error.value) throw new Error(error.value)
    // 应用到草稿前就核对父子关系与空页签容器，不等保存应用时被后端拒。
    const structure = pageStructureError(latest.value, nodeLabels)
    if (structure) throw new Error(structure)
    return pageNodes(latest.value)
  }
})
</script>
<template>
  <div class="page-designer">
    <div class="canvas-wrap" :class="{ readonly: readOnly }">
      <div v-if="!ready" class="loading">
        <span>{{ error || '正在加载页面设计器…' }}</span>
        <a-button v-if="error" size="small" @click="retryLoading">重新加载</a-button>
      </div>
      <div v-else-if="imageBusy" class="loading">图片上传中，请稍候…</div>
      <iframe
        :key="frameAttempt"
        ref="frame"
        title="页面容器设计画布"
        src="/nocode-designer/index.html?type=app&id=os&pageid=os-page"
        @load="checkFrame"
        @error="failLoading('页面设计器加载失败，请重试。')"
      />
    </div>
    <aside v-show="propertiesOpen" class="properties">
      <div class="properties-heading">
        <h3>节点属性</h3>
        <a-button type="text" size="small" @click="toggleProperties">收起</a-button>
      </div>
      <a-tag v-if="selected && nodeLabels[selected.componentName]" color="purple">
        {{ nodeLabels[selected.componentName] }}
      </a-tag>
      <p class="muted">选中容器或内容块后配置</p>
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-form v-if="selected && selected.componentName !== 'Page'" layout="vertical" :disabled="readOnly">
        <a-form-item label="节点名称 / 说明">
          <a-textarea
            :value="String(selected.props?.text || '')"
            :maxlength="selected.componentName === 'OsButton' ? 60 : 2000"
            @change="update({ text: $event.target.value })"
          />
        </a-form-item>
        <a-form-item v-if="selected.componentName === 'OsCol'" label="列宽（24 格）">
          <a-slider
            :value="Number(selected.props?.span || 12)"
            :min="1"
            :max="24"
            @change="(value: number) => update({ span: value })"
          />
        </a-form-item>
        <a-form-item
          v-if="isBusiness && !(selected.componentName === 'OsTasks' && contextObjectId)"
          :label="selected.componentName === 'OsTasks' ? '业务表单（可选）' : '业务资源'"
          :required="selected.componentName !== 'OsTasks'"
        >
          <a-select
            :value="selected.props?.resourceId || undefined"
            :options="resourceOptions"
            :allow-clear="selected.componentName === 'OsTasks' && !contextObjectId"
            :placeholder="
              selected.componentName === 'OsReportDashboard'
                ? '选择已配置报表看板'
                : selected.componentName === 'OsTasks' && !contextObjectId
                  ? '不选择：展示当前应用的任务'
                  : '选择视图或表单'
            "
            @change="(value: string | undefined) => update({ resourceId: value || '', relationId: '' })"
          />
        </a-form-item>
        <a-form-item v-if="selected.componentName === 'OsTasks' && contextObjectId" label="任务范围">
          <span>当前页面记录</span>
        </a-form-item>
        <a-alert
          v-if="selected.componentName === 'OsReportDashboard'"
          type="info"
          show-icon
          message="使用应用资源中的固定看板版本"
          description="请先在“报表看板”中配置固定引用、输入及明细视图；绑定了当前记录对象的看板须与页面对象一致。"
        />
        <template v-if="isList">
          <a-form-item label="数据范围">
            <a-radio-group
              :value="selected.componentName === 'OsRelated'"
              class="list-scope-options"
              @change="changeListMode($event.target.value)"
            >
              <a-radio :value="false">按数据视图展示</a-radio>
              <a-radio :value="true" :disabled="!contextObjectId">仅与当前记录关联</a-radio>
            </a-radio-group>
          </a-form-item>
          <p v-if="!contextObjectId" class="muted">需要关联当前记录时，请先在顶部“页面设置”选择“当前记录对象”。</p>
          <p v-else-if="selected.componentName === 'OsView'" class="muted">
            在详情页中展示该记录的账户、流水等数据，请选择“仅与当前记录关联”，再配置下面的关系。
          </p>
        </template>
        <a-alert
          v-if="selected.componentName === 'OsTasks' && !contextObjectId"
          type="info"
          show-icon
          :message="selected.props?.resourceId ? '按所选业务表单展示任务' : '展示当前应用的任务'"
          description="不选择业务表单时，展示归属当前应用的任务，发起时自动带入所属应用；选择表单可保留按业务对象展示的视图。"
        />
        <TaskViewEditor
          v-if="selected.componentName === 'OsTasks'"
          :key="selected.id"
          :application-id="applicationId"
          :value="selected.props?.taskViewJson"
          :resource-id="selected.props?.resourceId"
          :context-object-id="contextObjectId"
          :resources="resources"
          :objects="objects"
          :read-only="readOnly"
          @change="value => update({ taskViewJson: value })"
        />
        <EngineBlockEditor
          v-if="selected.componentName === 'OsEngine'"
          :key="selected.id"
          :value="selected.props?.engineJson"
          :context-object-id="contextObjectId"
          :objects="objects"
          :read-only="readOnly"
          @change="value => update({ engineJson: value })"
        />
        <template
          v-if="selected.componentName === 'OsRelated' || (selected.componentName === 'OsReport' && contextObjectId)"
        >
          <a-form-item label="与当前记录的关系" :required="selected.componentName === 'OsRelated'">
            <a-select
              :value="
                selected.props?.relationId ? `${selected.props.direction}:${selected.props.relationId}` : undefined
              "
              :options="relations"
              :allow-clear="selected.componentName === 'OsReport'"
              placeholder="选择已有关系"
              @change="relationChanged"
            />
          </a-form-item>
          <a-alert
            v-if="!relations.length"
            type="info"
            :message="
              !contextObjectId
                ? '请先在顶部“页面设置”选择“当前记录对象”。'
                : !selected.props?.resourceId
                  ? '请先选择要展示的业务资源（数据视图）。'
                  : '当前对象与所选视图的对象之间没有可选关系。请到数据中心定义并发布对象关系，再同步应用引用的对象版本。'
            "
          />
          <p class="muted">列表自动跟随当前记录；新增时带入关联字段。字段和操作仍受应用授权限制。</p>
        </template>
        <a-form-item v-if="selected.componentName === 'OsHeading'" label="标题层级">
          <a-select
            :value="selected.props?.display_headingLevel || 2"
            :options="[2, 3, 4, 5].map(v => ({ value: v, label: `H${v} 标题` }))"
            @change="(value: number) => update({ display_headingLevel: value })"
          />
        </a-form-item>
        <a-form-item v-if="selected.componentName === 'OsAlert'" label="提示类型">
          <a-select
            :value="selected.props?.display_alertType || PageAlertType.INFO"
            :options="[
              { value: PageAlertType.INFO, label: '信息' },
              { value: PageAlertType.SUCCESS, label: '成功' },
              { value: PageAlertType.WARNING, label: '提醒' },
              { value: PageAlertType.ERROR, label: '错误' }
            ]"
            @change="(value: string) => update({ display_alertType: value })"
          />
        </a-form-item>
        <template v-if="selected.componentName === 'OsImage'">
          <a-form-item label="展示图片" required>
            <InfraUpload
              :key="selected.id"
              :model-value="selected.props?.display_imageFileId ? [String(selected.props.display_imageFileId)] : []"
              :disabled="readOnly || imageBusy"
              accept="image/png,image/jpeg,image/webp,image/gif"
              :max-count="1"
              @update:model-value="imageChanged"
              @upload-status="uploadStatus = $event"
            >
              <a-button>上传 / 更换图片</a-button>
            </InfraUpload>
          </a-form-item>
          <p class="muted">用于应用展示的素材。记录中的业务文件请使用“记录附件”区块。</p>
          <a-form-item label="图片说明">
            <a-input
              :value="selected.props?.display_imageAlt"
              :maxlength="200"
              @change="update({ display_imageAlt: $event.target.value })"
            />
          </a-form-item>
          <a-form-item label="图片高度">
            <a-input-number
              :value="selected.props?.display_imageHeight || 160"
              :min="32"
              :max="600"
              :precision="0"
              @change="(value: number | null) => update({ display_imageHeight: value ?? 160 })"
            />
            px
          </a-form-item>
          <a-form-item label="显示方式">
            <a-select
              :value="selected.props?.display_imageFit || PageImageFit.CONTAIN"
              :options="[
                { value: PageImageFit.CONTAIN, label: '完整显示' },
                { value: PageImageFit.COVER, label: '铺满并裁剪' }
              ]"
              @change="(value: string) => update({ display_imageFit: value })"
            />
          </a-form-item>
        </template>
        <template v-if="selected.componentName === 'OsButton'">
          <a-form-item label="按钮样式">
            <a-select
              :value="selected.props?.display_buttonType || PageButtonType.PRIMARY"
              :options="[
                { value: PageButtonType.PRIMARY, label: '主要按钮' },
                { value: PageButtonType.DEFAULT, label: '普通按钮' },
                { value: PageButtonType.TEXT, label: '文字按钮' }
              ]"
              @change="(value: string) => update({ display_buttonType: value })"
            />
          </a-form-item>
          <a-form-item label="点击动作" required>
            <a-select
              :value="selected.props?.action_kind || PageActionKind.REFRESH"
              :options="actionOptions"
              @change="actionChanged"
            />
          </a-form-item>
          <a-form-item
            v-if="blockAction"
            label="目标区块"
            :required="selected.props?.action_kind !== PageActionKind.REFRESH"
          >
            <a-select
              :value="selected.props?.action_targetNodeId || undefined"
              :options="targetOptions"
              :allow-clear="selected.props?.action_kind === PageActionKind.REFRESH"
              placeholder="刷新全部区块 / 选择目标"
              @change="(value: string) => update({ action_targetNodeId: value || '' })"
            />
          </a-form-item>
          <a-form-item v-else label="目标资源" required>
            <a-select
              :value="selected.props?.action_resourceId || undefined"
              :options="actionResources"
              placeholder="选择本应用的资源"
              @change="(value: string) => update({ action_resourceId: value })"
            />
          </a-form-item>
          <a-form-item
            v-if="
              ![PageActionKind.REFRESH, PageActionKind.NAVIGATE, PageActionKind.EXECUTE_ACTION].some(
                k => k === selected?.props?.action_kind
              )
            "
            label="打开方式"
          >
            <a-select
              :value="selected.props?.action_openMode || RecordOpenMode.DRAWER"
              :options="modeOptions"
              @change="(value: string) => update({ action_openMode: value })"
            />
          </a-form-item>
          <a-form-item label="操作确认（可选）">
            <a-input
              :value="selected.props?.action_confirmText"
              :maxlength="200"
              placeholder="例如：确认提交审批？"
              @change="update({ action_confirmText: $event.target.value })"
            />
          </a-form-item>
          <p class="muted">只使用应用已有资源。关联新增沿用目标区块的所属关系；操作仍由后台校验实时权限。</p>
        </template>
        <a-collapse ghost>
          <a-collapse-panel key="appearance" header="外观与间距">
            <div class="style-grid">
              <a-form-item v-for="item in spacingFields" :key="item.key" :label="item.label">
                <a-input-number
                  :value="selected.props?.[`style_${item.key}`]"
                  :min="0"
                  :max="item.max"
                  :precision="0"
                  placeholder="默认"
                  @change="(value: number | null) => update({ [`style_${item.key}`]: value ?? '' })"
                />
              </a-form-item>
            </div>
            <a-form-item v-if="['OsFlex', 'OsRow'].includes(selected.componentName)" label="子项间距">
              <a-slider
                :value="Number(selected.props?.style_gap ?? 12)"
                :min="0"
                :max="48"
                @change="(value: number) => update({ style_gap: value })"
              />
            </a-form-item>
            <a-form-item label="背景颜色">
              <input
                type="color"
                :value="selected.props?.style_background || '#ffffff'"
                :disabled="readOnly"
                @input="update({ style_background: ($event.target as HTMLInputElement).value })"
              />
              <a-button type="link" size="small" @click="update({ style_background: '' })">默认</a-button>
            </a-form-item>
            <a-form-item label="文字颜色">
              <input
                type="color"
                :value="selected.props?.style_color || '#172554'"
                :disabled="readOnly"
                @input="update({ style_color: ($event.target as HTMLInputElement).value })"
              />
              <a-button type="link" size="small" @click="update({ style_color: '' })">默认</a-button>
            </a-form-item>
            <a-form-item label="对齐方式">
              <a-select
                :value="selected.props?.style_align || PageAlign.START"
                :options="
                  alignmentOptions.filter(
                    o => selected?.componentName === 'OsFlex' || o.value !== PageAlign.SPACE_BETWEEN
                  )
                "
                @change="(value: string) => update({ style_align: value })"
              />
            </a-form-item>
            <a-form-item v-if="selected.componentName === 'OsFlex'" label="排列方向">
              <a-select
                :value="selected.props?.style_direction || PageDirection.ROW"
                :options="[
                  { value: PageDirection.ROW, label: '横向，宽度不足换行' },
                  { value: PageDirection.COLUMN, label: '纵向' }
                ]"
                @change="(value: string) => update({ style_direction: value })"
              />
            </a-form-item>
            <a-checkbox
              :checked="!!selected.props?.style_border"
              @change="update({ style_border: $event.target.checked })"
            >
              显示边框
            </a-checkbox>
            <a-button block class="reset-style" @click="resetStyle">恢复默认外观</a-button>
          </a-collapse-panel>
        </a-collapse>
      </a-form>
      <a-empty v-else description="从左侧拖入区块，或在画布中选择节点" :image-style="{ height: '48px' }" />
      <div class="quick-insert">
        <h4>快捷添加</h4>
        <a-select
          v-model:value="insertMode"
          :disabled="readOnly || uploadStatus.pending"
          :options="[
            { value: 'root', label: '添加到页面根容器' },
            { value: 'selected', label: '添加到当前选中容器' }
          ]"
        />
        <a-select
          v-model:value="insertComponent"
          :disabled="readOnly || uploadStatus.pending"
          :options="insertOptions"
          placeholder="选择要添加的组件"
        />
        <a-button block :disabled="!canInsert" @click="insert">添加组件</a-button>
        <p v-if="insertMode === 'selected' && !containerNames.includes(selected?.componentName || '')" class="muted">
          请先选择一个容器；内容块不能包含其他组件。
        </p>
      </div>
      <div class="note">画布展示结构示意；保存并发布后，在运行页面验证真实数据和交互。</div>
    </aside>
  </div>
</template>
<style scoped>
.page-designer {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  background: #f4f6fb;
}
.canvas-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
}
.loading button {
  margin-left: 12px;
}
iframe {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
}
.readonly iframe {
  pointer-events: none;
}
.properties {
  box-sizing: border-box;
  width: 256px;
  flex-shrink: 0;
  padding: 12px;
  background: white;
  border-left: 1px solid #e5e7eb;
  overflow: auto;
}
.properties-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.list-scope-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
@media (max-width: 1100px) {
  .properties {
    position: absolute;
    right: 0;
    top: 0;
    bottom: 0;
    z-index: 3;
    box-shadow: -4px 0 12px #00000012;
  }
}
.properties h3 {
  font-size: 14px;
  margin-bottom: 4px;
}
.muted,
.note {
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.7;
}
.note {
  padding: 12px;
  margin-top: 24px;
  border-radius: 6px;
  background: #f4f6fb;
}
.loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: #f4f6fb;
  z-index: 1;
}
.style-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.quick-insert {
  border-top: 1px solid #e5e7eb;
  margin-top: 18px;
  padding-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.quick-insert h4 {
  font-size: 13px;
  margin: 0;
}
.quick-insert .ant-select {
  width: 100%;
}
.style-grid .ant-input-number {
  width: 100%;
}
.reset-style {
  margin-top: 16px;
}
input[type='color'] {
  width: 48px;
  height: 30px;
  border: 1px solid #e5e7eb;
  background: transparent;
  border-radius: 4px;
  vertical-align: middle;
  cursor: pointer;
}
</style>
