<script setup lang="ts">
import { v4 as uuidv4 } from 'uuid'
import { businessFields, fieldRelation } from '@/nocode/business-fields'
import {
  computed,
  defineComponent,
  h,
  nextTick,
  onMounted,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
  provide
} from 'vue'
import type FcDesignerEngine from '@form-create/antd-designer'
import type { Config, DragRule } from '@form-create/antd-designer'
import { Card, Divider, message } from 'ant-design-vue'
import { EyeOutlined, SearchOutlined } from '@ant-design/icons-vue'
import type { Rule } from '@form-create/ant-design-vue'
import type { ApplicationResource, PublishedDefinition } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import { NodeKind, uiNode, type FormConfig, type UiNode } from '@/types/nocode/application-ui'
import { MemberState } from '@/types/nocode/enums'
import { boundFields, nodesToRules, rulesToNodes, unavailableFieldComponent } from '@/nocode/application-ui'
import { detailColumnsFromRules, formLayoutNodes, internalDetailIds } from '@/nocode/form-detail-layout'
import { cloneFormRule } from '@/nocode/form-rule'
import { loadFormDesigner } from '@/nocode/form-designer-loader'
import { editSignature } from '@/nocode/edit-signature'
import { fieldTypes } from '@/nocode/object-draft'
import { arrangeFormColumns, formDesignIssues } from '@/nocode/form-design'
import BusinessFieldControl from './BusinessFieldControl.vue'
import HyperlinkField from './HyperlinkField.vue'
import FormPreview from './FormPreview.vue'
import SelectionPresentationEditor from './SelectionPresentationEditor.vue'
import FieldBehaviorEditor from './FieldBehaviorEditor.vue'
import InternalDetailDesignerField from './InternalDetailDesignerField.vue'
import InternalDetailColumnSettings from './InternalDetailColumnSettings.vue'
import UnavailableFieldDesign from './UnavailableFieldDesign.vue'
import { internalDetailDesignerKey } from '@/nocode/internal-detail-designer-context'
import { selectionSource, selectionPreviewKey } from '@/nocode/selection'
import { businessFileField } from '@/nocode/business-file'
import { objectEditorHref, objectRuleHint } from '@/nocode/object-rule-hint'
import { businessFormOptions } from '@/nocode/form-presentation'
import '@/styles/business-form.css'

const props = defineProps<{
  nodes: UiNode[]
  fields?: Rule[]
  resources: ApplicationResource[]
  form: boolean
  readOnly?: boolean
  definition?: PublishedDefinition
  applicationId?: string
  objects?: Record<string, import('@/types/nocode/application').PublishedObject>
  /** 因关联而只读可读、但没有被应用引用的对象：只用于按关系查「目标对象的字段」。 */
  readableObjects?: Record<string, import('@/types/nocode/application').PublishedObject>
  formOptions?: FormConfig['options']
  detailIds?: string[]
  detailNodes?: FormConfig['detailNodes']
  relatedForms?: FormConfig['relatedForms']
  name?: string
}>()
const FcDesigner = shallowRef<typeof FcDesignerEngine>()
const designer = ref<InstanceType<typeof FcDesignerEngine>>()
const root = ref<HTMLElement>()
const loadError = ref('')
let disposed = false
const ready = ref(false),
  search = ref(''),
  unusedOnly = ref(false),
  previewOpen = ref(false),
  showIssues = ref(false)
const previewNodes = ref<UiNode[]>([])
const emit = defineEmits<{ change: []; ready: [] }>()
let initialNodes = ''
const initialLayout = computed(() =>
  formLayoutNodes(
    props.nodes,
    props.definition?.details.filter(d => props.detailIds?.includes(d.id!)).map(d => d.id!) || props.detailIds || []
  )
)
const currentNodes = computed(() => (designer.value ? rulesToNodes(designer.value.getRule()) : initialLayout.value))
const currentDetailNodes = computed(() =>
  designer.value ? detailColumnsFromRules(designer.value.getRule()) : props.detailNodes || {}
)
const selectedDetailColumn = ref<{ detailId: string; fieldId?: string }>()
const availableDetails = computed(() => props.definition?.details.filter(d => d.state !== MemberState.INACTIVE) || [])
const usedDetails = computed(() => new Set(internalDetailIds(currentNodes.value)))
const designSignature = () => editSignature({ nodes: currentNodes.value, detailNodes: currentDetailNodes.value })
function initialDetailNodes() {
  return Object.fromEntries(
    availableDetails.value.map(detail => [
      detail.id!,
      props.detailNodes?.[detail.id!] ||
        detail.fields
          .filter(field => detail.fieldOptions[field.id!]?.state !== MemberState.INACTIVE)
          .map(field =>
            uiNode(NodeKind.FIELD, {
              id: 'detail-' + field.id,
              fieldId: field.id!,
              presentation: { label: field.name }
            })
          )
    ])
  )
}
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId || '',
    objects: Object.values(props.objects || {}).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    })),
    form: props.definition
      ? {
          objectId: props.definition.objectId,
          nodes: currentNodes.value,
          detailIds: internalDetailIds(currentNodes.value),
          detailNodes: currentDetailNodes.value,
          options: props.formOptions
        }
      : undefined
  }))
)
const usedFields = computed(() => new Set(boundFields(currentNodes.value)))
// 查关系目标对象的定义用「已引用 ∪ 因关联而可读取」；预览请求里的引用清单、资源归属仍只认已引用的对象。
const lookupObjects = computed(() => ({ ...(props.readableObjects || {}), ...(props.objects || {}) }))
provide(internalDetailDesignerKey, {
  definition: computed(() => props.definition),
  objects: lookupObjects,
  resources: computed(() => props.resources),
  formFieldIds: computed(() => [...usedFields.value]),
  readOnly: computed(() => !!props.readOnly),
  selectedColumn: selectedDetailColumn,
  select: selectDetail,
  setSelectedField: (detailId, fieldId) => {
    selectedDetailColumn.value = { detailId, fieldId }
  }
})
const issues = computed(() => {
  if (!props.definition) return []
  const definition = props.definition
  const result = formDesignIssues(
    currentNodes.value,
    definition,
    props.resources,
    props.objects,
    undefined,
    lookupObjects.value
  )
  for (const detail of definition.details.filter(d => usedDetails.value.has(d.id!))) {
    const detailNodes = currentDetailNodes.value[detail.id!]
    if (!detailNodes) continue
    const detailDefinition = {
      ...definition,
      fields: detail.fields,
      fieldOptions: detail.fieldOptions,
      details: [],
      relations: definition.relations
        .filter(r => r.sourceDetailId === detail.id)
        .map(r => ({ ...r, sourceDetailId: null }))
    }
    result.push(
      ...formDesignIssues(
        detailNodes,
        detailDefinition,
        props.resources,
        props.objects,
        {
          definition,
          fieldIds: boundFields(currentNodes.value)
        },
        lookupObjects.value
      ).map(issue => ({ ...issue, message: `${detail.name} → ${issue.message}` }))
    )
  }
  return result
})
const hiddenFields = computed(() =>
  (props.fields || [])
    .filter(rule => {
      const field = (props.definition ? businessFields(props.definition) : []).find(f => f.id === rule.field)
      return (
        (unusedOnly.value && usedFields.value.has(String(rule.field))) ||
        !`${rule.title} ${field?.code || ''}`.toLowerCase().includes(search.value.trim().toLowerCase())
      )
    })
    .map(rule => 'field_' + rule.field)
)
const config = computed<Config>(() => ({
  showAi: false,
  showComponentName: false,
  showMenuBar: false,
  showEventForm: false,
  showControl: false,
  showStyleForm: false,
  showCustomProps: false,
  showFormConfig: false,
  showValidateForm: false,
  showBaseForm: false,
  showInputData: false,
  showJsonPreview: false,
  showDevice: false,
  showLanguage: false,
  showSaveBtn: false,
  showPreviewBtn: false,
  fieldReadonly: true,
  nameReadonly: true,
  useTemplate: false,
  autoResetField: false,
  switchType: false,
  exitConfirm: false,
  updateConfigOnBlur: false,
  hiddenItem: [
    ...hiddenFields.value,
    ...availableDetails.value
      .filter(
        d =>
          (unusedOnly.value && usedDetails.value.has(d.id!)) ||
          !`${d.name} ${d.code}`.toLowerCase().includes(search.value.trim().toLowerCase())
      )
      .map(d => 'detail_' + d.id)
  ],
  componentPermission: [
    {
      tag: [
        'fcRow',
        'col',
        'aCard',
        'nocodeTabsDesign',
        'nocodeTabDesign',
        unavailableFieldComponent,
        ...(props.fields || []).map(f => 'field_' + f.field),
        ...availableDetails.value.map(d => 'detail_' + d.id)
      ],
      permission: { copy: false }
    }
  ],
  checkDrag: ({ rule, menu }) => {
    if (props.readOnly) return false
    const id = menu.name.startsWith('field_') ? menu.name.slice(6) : ''
    const detailId = menu.name.startsWith('detail_') ? menu.name.slice(7) : ''
    if (!rule && detailId && usedDetails.value.has(detailId)) {
      selectDetail(detailId)
      message.info('该明细已在画布中，已为你定位')
      return false
    }
    if (!rule && id && usedFields.value.has(id)) {
      locate(id)
      message.info('该字段已在画布中，已为你定位')
      return false
    }
    return true
  },
  hiddenMenu: ['main', 'subform', 'aide'],
  formOptions: businessFormOptions(props.formOptions?.layout),
  updateDefaultRule: {
    fcRow: { class: 'os-form-row', props: { gutter: 24 }, col: { show: false } },
    col: { class: 'os-form-column', col: { show: false } },
    aCard: { class: 'os-form-section', props: { size: 'small' }, col: { span: 24 } }
  },
  componentRule: {
    fcRow: () => [],
    col: () => [{ type: 'slider', field: 'span', title: '列宽（24 栅格）', props: { min: 1, max: 24 } }],
    aCard: () => [{ type: 'input', field: 'title', title: '卡片标题' }]
  }
}))
function updateFieldMenu() {
  if (!props.form) return
  designer.value?.addMenu({
    name: 'business',
    title: '对象字段',
    before: true,
    list: (props.fields || []).map(rule => ({
      name: 'field_' + rule.field,
      only: true,
      label: `${rule.title}${usedFields.value.has(String(rule.field)) ? ' · 已用' : ''}`,
      icon: 'icon-input'
    }))
  })
  designer.value?.addMenu({
    name: 'details',
    title: '内部明细',
    list: availableDetails.value.map(detail => ({
      name: 'detail_' + detail.id,
      only: true,
      label: `${detail.name}${usedDetails.value.has(detail.id!) ? ' · 已用' : ''}`,
      icon: 'icon-table'
    }))
  })
}
function selectDetail(detailId: string, fieldId?: string) {
  selectedDetailColumn.value = { detailId, fieldId }
  const find = (nodes: UiNode[]): UiNode | undefined => {
    for (const node of nodes) {
      if (node.type === NodeKind.INTERNAL_DETAIL && node.detail?.detailId === detailId) return node
      const child = find(node.children)
      if (child) return child
    }
  }
  const node = find(currentNodes.value)
  if (node) locate(node.id)
}
function locate(id: string) {
  if (!ready.value) return
  designer.value?.triggerActive(id)
  void nextTick(() =>
    root.value?.querySelector('._fd-drag-tool.active')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  )
}
function validate() {
  requireReady()
  showIssues.value = true
  if (issues.value.length) throw new Error(issues.value[0]!.message)
}
function preview() {
  validate()
  previewNodes.value = structuredClone(currentNodes.value)
  previewOpen.value = true
}
function openPreview() {
  try {
    preview()
  } catch (e) {
    message.warning(e instanceof Error ? e.message : String(e))
  }
}
// 使用引擎已有的历史栈，补上 setRule 不会自动记录的初始快照与快捷排版快照。
function checkpoint() {
  const engine = designer.value as InstanceType<typeof FcDesignerEngine> & { addOperationRecord: () => void }
  engine?.addOperationRecord()
}
function arrange(columns: 1 | 2 | 3) {
  if (!ready.value || !designer.value || props.readOnly) return
  const next = arrangeFormColumns(currentNodes.value, columns)
  designer.value.setRule(nodesToRules(next, props.fields || [], true, currentDetailNodes.value))
  checkpoint()
}
watch(() => [...usedFields.value].join(','), updateFieldMenu)
watch(() => [...usedDetails.value].join(','), updateFieldMenu)
watch(designSignature, (next, previous) => {
  if (ready.value && next !== previous) emit('change')
})
watch(
  () => props.formOptions?.layout,
  layout => {
    if (ready.value) designer.value?.mergeOptions(businessFormOptions(layout))
  }
)
// 开源引擎提供拖拽、嵌套、属性面板和撤销重做；设计态业务块不发起数据请求。
function registerBlock(FcDesigner: typeof FcDesignerEngine, name: string, label: string, kind?: ResourceKind) {
  const component = defineComponent({
    props: { resourceId: String, text: String },
    setup: p => () =>
      h(
        Card,
        { size: 'small', style: { width: '100%' } },
        () => p.text || props.resources.find(r => r.id === p.resourceId)?.name || label
      )
  })
  FcDesigner.component(name, component)
  const options = props.resources.filter(r => r.kind === kind).map(r => ({ value: r.id, label: r.name }))
  const rule: DragRule = {
    name,
    languageKey: [],
    label,
    icon: 'icon-card',
    menu: kind ? 'business' : 'layout',
    rule: () => ({
      type: name,
      name: uuidv4(),
      props: kind ? { resourceId: options[0]?.value || '' } : { text: '在此输入文字' }
    }),
    props: () =>
      kind
        ? [{ type: 'select', field: 'resourceId', title: '绑定资源', options, validate: [{ required: true }] }]
        : [{ type: 'textarea', field: 'text', title: '内容', props: { maxlength: 2000 } }]
  }
  designer.value?.addComponent(rule)
}
/**
 * 关联带入已迁到数据对象·数据联动（实施设计稿 9.4、10.2）：对象上配了联动或引用筛选的字段在属性面板只读提示一行并给跳转。
 * 选择类字段由「选择器设置」自带同一提示，这里只覆盖没有该面板的字段，避免重复。
 */
function objectRuleHintProps(fieldId: string) {
  const definition = props.definition
  if (!definition) return []
  const target = businessFields(definition).find(f => f.id === fieldId)
  const selectionPanel =
    !!fieldRelation(definition.relations, fieldId) ||
    (!!target && !!selectionSource(target, definition.fieldOptions[fieldId]))
  const hint = selectionPanel ? null : objectRuleHint(definition, fieldId)
  if (!hint) return []
  return [
    {
      type: 'div',
      children: [
        hint + ' ',
        {
          type: 'a',
          props: { href: objectEditorHref(definition.objectId), target: '_blank', rel: 'noopener' },
          children: ['去数据对象修改']
        }
      ],
      style: { color: '#64748b', fontSize: '12px', marginBottom: '12px' }
    }
  ]
}
async function initialize(FcDesigner: typeof FcDesignerEngine) {
  await nextTick()
  if (disposed) return
  const engine = designer.value
  if (!engine) throw new Error('表单设计器未能初始化')
  FcDesigner.component('nocodeBusinessField', BusinessFieldControl)
  FcDesigner.component('nocodeHyperlink', HyperlinkField)
  FcDesigner.component('nocodeSelectionPresentation', SelectionPresentationEditor)
  FcDesigner.component('nocodeFieldBehavior', FieldBehaviorEditor)
  FcDesigner.component('nocodeInternalDetailDesign', InternalDetailDesignerField)
  FcDesigner.component('nocodeDetailColumnSettings', InternalDetailColumnSettings)
  FcDesigner.component(unavailableFieldComponent, UnavailableFieldDesign)
  engine.addComponent({
    name: unavailableFieldComponent,
    languageKey: [],
    label: '已失效字段',
    icon: 'icon-input',
    handleBtn: ['delete'],
    rule: () => ({ type: unavailableFieldComponent, name: uuidv4() }),
    props: () => [{ type: 'div', children: ['字段引用已失效。原有配置会保留，请从画布移除此字段后保存。'] }]
  })
  engine.addMenu({
    name: 'layout',
    title: '布局组件',
    list: [
      { name: 'fcRow', label: '分栏布局', icon: 'icon-row' },
      { name: 'aCard', label: '分组卡片', icon: 'icon-card' }
    ]
  })
  engine.addMenu({ name: 'business', title: props.form ? '对象字段' : '业务内容', before: true, list: [] })
  // 设计态展开各页签以便拖入字段；运行表单才按活动页签显示，隐藏页签字段仍参与提交校验。
  FcDesigner.component(
    'nocodeTabsDesign',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', { class: 'form-tabs-design' }, slots.default?.())
    })
  )
  FcDesigner.component(
    'nocodeTabDesign',
    defineComponent({
      props: { tab: String },
      setup:
        (p, { slots }) =>
        () =>
          h(Card, { title: p.tab || '页签', size: 'small' }, slots)
    })
  )
  FcDesigner.component('aDivider', Divider)
  engine.addComponent({
    name: 'nocodeTabsDesign',
    label: '页签分组',
    icon: 'icon-card',
    menu: 'layout',
    languageKey: [],
    drag: true,
    children: 'nocodeTabDesign',
    childrenLen: 2,
    allowDrag: ['nocodeTabDesign'],
    rule: () => ({ type: 'nocodeTabsDesign', name: uuidv4(), children: [] }),
    props: () => []
  })
  engine.addComponent({
    name: 'nocodeTabDesign',
    label: '单个页签',
    icon: 'icon-card',
    languageKey: [],
    drag: true,
    rule: () => ({ type: 'nocodeTabDesign', name: uuidv4(), props: { tab: '页签' }, children: [] }),
    props: () => [{ type: 'input', field: 'tab', title: '页签名称' }]
  })
  engine.addComponent({
    name: 'aDivider',
    label: '分隔线',
    icon: 'icon-card',
    menu: 'layout',
    languageKey: [],
    rule: () => ({ type: 'aDivider', name: uuidv4() }),
    props: () => []
  })
  registerBlock(FcDesigner, 'nocodeText', '文字说明')
  if (props.form) {
    const defaults = initialDetailNodes()
    engine.addMenu({ name: 'details', title: '内部明细', list: [] })
    for (const detail of availableDetails.value)
      engine.addComponent({
        name: 'detail_' + detail.id,
        languageKey: [],
        label: detail.name,
        menu: 'details',
        icon: 'icon-table',
        only: true,
        // 明细列自身提供配置入口，不能被引擎默认的整块点击遮罩覆盖。
        mask: false,
        handleBtn: ['delete'],
        rule: () => ({
          type: 'nocodeInternalDetailDesign',
          name: uuidv4(),
          col: { span: 24 },
          props: {
            detailId: detail.id,
            title: '',
            mode: 'GRID',
            _osDetailNodes: JSON.parse(JSON.stringify(defaults[detail.id!] || []))
          }
        }),
        props: () => [
          { type: 'div', children: [`内部明细：${detail.name}。从画布移除只影响本表单，不删除数据对象和历史记录。`] },
          { type: 'input', field: 'title', title: '明细标题', props: { placeholder: detail.name, maxlength: 160 } },
          {
            type: 'radio',
            field: 'mode',
            title: '展示方式',
            options: [
              { label: '表格', value: 'GRID' },
              { label: '卡片', value: 'CARDS' }
            ]
          },
          {
            type: 'nocodeDetailColumnSettings',
            field: '_osDetailNodes',
            title: '明细列设置',
            modelField: 'modelValue',
            props: { detailId: detail.id }
          }
        ]
      })
    for (const field of props.fields || [])
      engine.addComponent({
        name: 'field_' + field.field,
        languageKey: [],
        label: String(field.title),
        menu: 'business',
        only: true,
        handleBtn: ['delete'],
        icon: 'icon-input',
        rule: () => ({
          ...cloneFormRule(field),
          name: uuidv4(),
          col: { span: 24, class: 'os-field-column' }
        }),
        props: () => [
          {
            type: 'div',
            children: [
              `数据类型：${fieldRelation(props.definition?.relations, field.field) ? (String(field.field).startsWith('relation_') ? '多选（对象引用）' : '单选（对象引用）') : fieldTypes.find(t => t.value === (props.definition ? businessFields(props.definition) : []).find(f => f.id === field.field)?.type)?.label || '对象关系'}（由数据对象维护）`
            ],
            style: { color: '#64748b', fontSize: '12px', marginBottom: '12px' }
          },
          {
            type: 'input',
            field: '_osLabel',
            title: '显示名称',
            props: { placeholder: String(field.title), maxlength: 160 }
          },
          { type: 'input', field: '_osPlaceholder', title: '输入提示', props: { maxlength: 500 } },
          { type: 'textarea', field: '_osHelp', title: '填写说明', props: { maxlength: 500 } },
          { type: 'switch', field: '_osReadOnly', title: '本表单只读' },
          ...(props.definition && businessFileField(props.definition.settings.businessFilePolicy, String(field.field))
            ? [{ type: 'switch', field: '_osHideBusinessPath', title: '隐藏保存位置提示' }]
            : []),
          {
            type: 'nocodeFieldBehavior',
            field: '_osBehavior',
            title: '动态条件',
            modelField: 'modelValue',
            props: { fields: props.definition?.fields || [] }
          },
          ...objectRuleHintProps(String(field.field)),
          ...(props.definition &&
          (fieldRelation(props.definition.relations, field.field) ||
            selectionSource(
              businessFields(props.definition).find(f => f.id === field.field)!,
              props.definition.fieldOptions[String(field.field)]
            ))
            ? [
                {
                  type: 'nocodeSelectionPresentation',
                  field: '_osSelection',
                  title: '选择器设置',
                  modelField: 'modelValue',
                  props: {
                    definition: props.definition,
                    fieldId: String(field.field),
                    objects: lookupObjects.value,
                    resources: props.resources,
                    versionNo: props.objects?.[props.definition!.objectId]?.versionNo
                  }
                }
              ]
            : [])
        ],
        watch: {
          _osLabel: ({ value, rule }) => {
            rule.title = value || field.title
          },
          _osPlaceholder: ({ value, rule }) => {
            rule.props!.placeholder = value || field.props?.placeholder
          },
          _osHelp: ({ value, rule }) => {
            rule.wrap = { ...rule.wrap, extra: value || undefined }
            rule.info = ''
          },
          _osReadOnly: ({ value, rule }) => {
            rule.props!.disabled = !!field.props?.disabled || !!value
          },
          _osHideBusinessPath: ({ value, rule }) => {
            rule.props!.hideBusinessPath = !!value
          }
        }
      })
  } else {
    registerBlock(FcDesigner, 'nocodeView', '数据列表', ResourceKind.VIEW)
    registerBlock(FcDesigner, 'nocodeForm', '业务表单', ResourceKind.FORM)
    registerBlock(FcDesigner, 'nocodeMetric', '记录统计', ResourceKind.VIEW)
  }
  engine.setRule(nodesToRules(initialLayout.value, props.fields || [], true, initialDetailNodes()))
  await nextTick()
  if (disposed) return
  initialNodes = designSignature()
  checkpoint()
  updateFieldMenu()
  ready.value = true
  emit('ready')
}
function requireReady() {
  if (!ready.value || !designer.value) throw new Error(loadError.value || '表单设计器尚未就绪，请等待加载')
  return designer.value
}
defineExpose({
  isReady: () => ready.value,
  getNodes: () => rulesToNodes(requireReady().getRule()),
  getDetailNodes: () =>
    JSON.parse(JSON.stringify(detailColumnsFromRules(requireReady().getRule()))) as FormConfig['detailNodes'],
  validate,
  hasChanges: () => ready.value && !!initialNodes && designSignature() !== initialNodes,
  reset: (nodes: UiNode[]) =>
    requireReady().setRule(nodesToRules(nodes, props.fields || [], true, currentDetailNodes.value))
})
onMounted(async () => {
  let engine: typeof FcDesignerEngine
  try {
    engine = await loadFormDesigner()
    if (!engine) throw new Error('表单设计器资源不可用')
  } catch {
    if (!disposed) loadError.value = '表单设计器资源加载失败，请先保存未保存的内容，再刷新页面。'
    return
  }
  if (disposed) return
  FcDesigner.value = engine
  try {
    await initialize(engine)
  } catch (error) {
    if (!disposed) {
      ready.value = false
      loadError.value = error instanceof Error ? error.message : '表单设计器初始化失败'
    }
  }
})
onBeforeUnmount(() => {
  disposed = true
  ready.value = false
})
</script>
<template>
  <div
    ref="root"
    class="business-designer"
    :class="{ readonly: readOnly }"
    @input="emit('change')"
    @drop="emit('change')"
    @change="emit('change')"
  >
    <div v-if="form && ready" class="designer-tools">
      <a-input
        v-model:value="search"
        placeholder="搜索字段或明细"
        aria-label="搜索对象字段"
        allow-clear
        class="field-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <a-checkbox v-model:checked="unusedOnly">仅未使用</a-checkbox>
      <a-dropdown :trigger="['click']" class="field-count">
        <a-button type="text">定位字段（{{ usedFields.size }} / {{ fields?.length || 0 }}）</a-button>
        <template #overlay>
          <a-menu @click="(event: { key: string | number }) => locate(String(event.key))">
            <a-menu-item
              v-for="field in (fields || []).filter(f => usedFields.has(String(f.field)))"
              :key="String(field.field)"
            >
              {{ field.title }}
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-dropdown v-if="usedDetails.size" :trigger="['click']">
        <a-button>定位明细（{{ usedDetails.size }}）</a-button>
        <template #overlay>
          <a-menu @click="(event: { key: string | number }) => selectDetail(String(event.key))">
            <a-menu-item v-for="detail in availableDetails.filter(d => usedDetails.has(d.id!))" :key="detail.id!">
              {{ detail.name }}
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-dropdown v-if="!readOnly">
        <a-button>快捷排版</a-button>
        <template #overlay>
          <a-menu @click="(event: { key: string | number }) => arrange(Number(event.key) as 1 | 2 | 3)">
            <a-menu-item key="1">单列</a-menu-item>
            <a-menu-item key="2">双列</a-menu-item>
            <a-menu-item key="3">三列</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <a-button @click="showIssues = !showIssues">
        {{ issues.length ? issues.length + ' 项待检查' : '检查通过' }}
      </a-button>
      <a-button type="primary" :disabled="!ready" @click="openPreview">
        <EyeOutlined />
        预览
      </a-button>
    </div>
    <div v-if="showIssues && issues.length" class="design-issues" role="alert">
      <div v-for="(issue, index) in issues" :key="index">
        {{ issue.message }}
        <a-button
          v-if="issue.fieldId && usedFields.has(issue.fieldId)"
          type="link"
          size="small"
          @click="locate(issue.fieldId)"
        >
          定位字段
        </a-button>
      </div>
    </div>
    <div class="designer-engine" :aria-busy="!ready && !loadError">
      <a-alert v-if="loadError" type="error" show-icon :message="loadError" />
      <div v-else-if="!ready" class="engine-loading" role="status">
        <a-spin size="small" />
        正在加载表单设计器…
      </div>
      <component v-if="FcDesigner" v-show="ready" :is="FcDesigner" ref="designer" :config="config" height="100%" />
    </div>
    <a-modal
      v-model:open="previewOpen"
      title="表单预览"
      width="calc(100vw - 64px)"
      :style="{ top: '24px' }"
      :footer="null"
      destroy-on-close
      wrap-class-name="nocode-form-preview"
    >
      <FormPreview
        v-if="previewOpen && definition"
        :definition="definition"
        :application-id="applicationId || ''"
        :objects="objects || {}"
        :nodes="previewNodes"
        :options="formOptions"
        :detail-ids="internalDetailIds(previewNodes)"
        :detail-nodes="currentDetailNodes"
        :related-forms="relatedForms"
        :resources="resources"
        :name="name || ''"
      />
    </a-modal>
  </div>
</template>
<style scoped>
.readonly .designer-engine {
  pointer-events: none;
}
.business-designer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  overflow: hidden;
}
.designer-engine {
  flex: 1;
  min-height: 0;
}
.engine-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 360px;
  color: var(--os-text-secondary, #666);
}
.designer-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 12px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.field-search {
  width: 230px;
}
.field-count {
  color: #64748b;
  margin-right: auto;
}
.design-issues {
  max-height: 110px;
  overflow: auto;
  padding: 8px 12px;
  background: #fff7e6;
  color: #ad6800;
  flex-shrink: 0;
}
:global(.nocode-form-preview .ant-modal-content) {
  height: calc(100dvh - 48px);
  display: flex;
  flex-direction: column;
}
:global(.nocode-form-preview .ant-modal-body) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.business-designer :deep(._fc-l) {
  width: 210px !important;
  min-width: 210px !important;
  max-width: 210px !important;
  flex: 0 0 210px !important;
}
.business-designer :deep(._fc-r) {
  width: 340px !important;
  min-width: 340px !important;
  max-width: 340px !important;
  flex: 0 0 340px !important;
}
.business-designer :deep(._fc-m-con) {
  padding: 12px;
}
.business-designer :deep(._fc-r-config) {
  grid-template-columns: minmax(0, 1fr);
}
.business-designer :deep(._fc-l-list) {
  grid-template-columns: repeat(2, 1fr);
}
.business-designer :deep(._fc-designer) {
  min-height: 0;
}
/* 当前业务协议没有任意隐藏开关；仅隐藏引擎固定面板中这一个直属设置。 */
.business-designer :deep(._fc-r-tab-props > ._fd-config-item) {
  display: none;
}
@media (max-width: 1000px) {
  .business-designer :deep(._fc-l) {
    width: 150px !important;
    min-width: 150px !important;
    max-width: 150px !important;
    flex-basis: 150px !important;
  }
  .business-designer :deep(._fc-r) {
    width: 200px !important;
    min-width: 200px !important;
    max-width: 200px !important;
    flex-basis: 200px !important;
  }
  .business-designer :deep(._fc-l-item) {
    width: auto;
  }
}
</style>
