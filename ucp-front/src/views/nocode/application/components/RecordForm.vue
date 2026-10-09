<script setup lang="ts">
import { computed, defineComponent, h, ref, reactive, nextTick, provide, watch, inject, onBeforeUnmount } from 'vue'
import { nocodePlatformKey } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { behaviorNodes, behaviorState, emptyFormValue } from '@/nocode/form-behavior'
import { selectionPreviewKey, selectionValuesKey } from '@/nocode/selection'
import FormCreate from '@form-create/ant-design-vue'
import type { Api, Rule } from '@form-create/ant-design-vue'
import { Tabs, Divider } from 'ant-design-vue'
import type { ObjectField } from '@/types/nocode/object'
import type { BusinessFilePolicy, FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { TableModel } from '@/types/nocode/runtime'
import { businessFieldRules, fieldRulePatch, fieldRuleViews, RULE_ERROR_PREFIX } from '@/nocode/business-field-rules'
import {
  createFieldRuleCoordinator,
  fieldRuleNamesKey,
  ruleAutoUpdate,
  ruledFields,
  type RuleFieldName,
  type RuleStateMap
} from '@/nocode/field-rule-runtime'
import { AUTO_UPDATE_MARK, AutoUpdateMark, autoUpdateInfo } from '@/nocode/auto-update-mark'
import { writableField } from '@/nocode/record-form'
import { nodesToRules } from '@/nocode/application-ui'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import BusinessFieldControl from './BusinessFieldControl.vue'
import HyperlinkField from './HyperlinkField.vue'
import { businessFormOptions, defaultFormNodes } from '@/nocode/form-presentation'
import { formDetailKey } from '@/nocode/form-detail-context'
import { detailFormContainerKey, RecordFormContainer } from '@/nocode/record-form-container'
import FormDetailOutlet from './FormDetailOutlet.vue'
import '@/styles/business-form.css'

const props = defineProps<{
  fields: ObjectField[]
  options: Record<string, FieldOptions>
  model: TableModel
  creating: boolean
  nodes?: UiNode[]
  applicationId?: string
  objectId?: string
  detailId?: string
  recordId?: string
  detailRecordId?: string
  clientRowKey?: string
  formId?: string
  relations?: ObjectRelation[]
  layout?: 'vertical' | 'horizontal'
  preview?: boolean
  compact?: boolean
  parentValues?: Record<string, unknown>
  businessPolicy?: BusinessFilePolicy | null
  /** 主表加明细协调器下发的本表单（明细时为本行）规则结果；缺省时退回单表模式自己求值。 */
  ruleStates?: RuleStateMap
}>()
const value = defineModel<Record<string, unknown>>({ required: true })
provide(
  detailFormContainerKey,
  computed(() => !!props.detailId)
)
provide(
  selectionValuesKey,
  computed(() => ({ ...props.parentValues, ...value.value }))
)
const api = ref<Api>()
const root = ref<HTMLElement>()
const uploads = new Map<string, { pending: boolean; failed: boolean }>()
const behaviorStates = ref<Record<string, ReturnType<typeof behaviorState>>>({})
const platform = inject(nocodePlatformKey, undefined)
const previewContext = inject(selectionPreviewKey, undefined)
const detailsContext = inject(formDetailKey, undefined)
watch(
  () => [props.nodes, value.value],
  () => {
    const numeric = new Set(
      props.fields
        .filter(f => ['INTEGER', 'DECIMAL', 'MONEY', 'PERCENT', 'FORMULA', 'SUMMARY'].includes(f.type))
        .map(f => f.id!)
    )
    const states = Object.fromEntries(
      behaviorNodes(props.nodes || []).map(n => [
        n.fieldId!,
        behaviorState(n.presentation?.behavior, value.value, numeric)
      ])
    )
    // 状态没变化时不重建设计引擎规则，保持输入焦点和光标。
    if (JSON.stringify(states) !== JSON.stringify(behaviorStates.value)) behaviorStates.value = states
    for (const node of behaviorNodes(props.nodes || [])) {
      if (
        !states[node.fieldId!]?.visible &&
        node.presentation?.behavior?.clearWhenHidden &&
        props.model.writable &&
        (!props.model.writeFields || props.model.writeFields.includes(node.fieldId!)) &&
        !emptyFormValue(value.value[node.fieldId!])
      )
        value.value[node.fieldId!] = null
    }
  },
  { deep: true, immediate: true }
)
// 对象字段规则（数据联动、公式默认值、引用筛选）：设计稿 5.5、10.3、15.4.6。
const injectedRuleNames = inject(fieldRuleNamesKey, undefined)
const ruleNames =
  injectedRuleNames ||
  computed<Record<string, RuleFieldName>>(() =>
    Object.fromEntries(props.fields.map(f => [f.id!, { name: f.name, detailId: props.detailId ?? null }]))
  )
if (!injectedRuleNames) provide(fieldRuleNamesKey, ruleNames)
const ownRuleStates = ref<RuleStateMap>({}),
  ruleError = ref('')
// 单表模式只服务主表单（如草稿面板）；明细行的规则由 RecordEditor / FormPreview 的协调器统一批量求值。
const ruleCoordinator =
  props.ruleStates === undefined &&
  !props.detailId &&
  !!platform &&
  !!props.applicationId &&
  !!props.objectId &&
  (props.preview ? !!previewContext?.value.form : !!props.formId) &&
  ruledFields(props.options).length > 0
    ? createFieldRuleCoordinator({
        read: () => ({
          creating: props.creating,
          options: props.options,
          values: value.value,
          canWrite: id => {
            const field = props.fields.find(f => f.id === id)
            return !!field && writableField(field, props.options[id], props.model, props.creating)
          },
          details: []
        }),
        evaluate: async part => {
          const query = {
            applicationId: props.applicationId!,
            objectId: props.objectId!,
            formId: props.formId,
            recordId: props.recordId,
            ...part
          }
          if (props.preview && previewContext?.value.form)
            return (
              await platform!.applications.previewFieldRules({
                query,
                objects: previewContext.value.objects,
                form: previewContext.value.form
              })
            ).results
          return (await platform!.runtime.evaluateFieldRules(query)).results
        },
        onStates: states => {
          ownRuleStates.value = states.master
          ruleError.value = ''
        },
        onError: e => {
          ruleError.value = `${RULE_ERROR_PREFIX}${errorMessage(e)}`
        }
      })
    : null
if (ruleCoordinator) {
  void nextTick(() => ruleCoordinator.start())
  watch(value, () => ruleCoordinator.sync(), { deep: true })
  onBeforeUnmount(() => ruleCoordinator.dispose())
}
const ruleViews = computed(() => {
  const states = props.ruleStates ?? ownRuleStates.value
  return fieldRuleViews(states, props.options, ruleNames.value, props.detailId)
})
// 文字以文本节点渲染，配置不能注入 HTML。
FormCreate.component('aForm', RecordFormContainer)
FormCreate.component('nocodeText', defineComponent({ props: { text: String }, setup: p => () => h('p', p.text) }))
FormCreate.component('nocodeTabs', Tabs)
FormCreate.component('nocodeTab', Tabs.TabPane)
FormCreate.component('aDivider', Divider)
FormCreate.component('nocodeInternalDetail', FormDetailOutlet)
FormCreate.component(AUTO_UPDATE_MARK, AutoUpdateMark)
const rules = computed(() => {
  const fields = businessFieldRules(props.fields, props.options, props.model, props.creating, {
    applicationId: props.applicationId,
    objectId: props.objectId,
    detailId: props.detailId,
    recordId: props.recordId,
    detailRecordId: props.detailRecordId,
    formId: props.formId,
    relations: props.relations,
    businessPolicy: props.businessPolicy,
    mode: props.preview ? 'preview' : 'runtime',
    onUploadStatus: (id, status) => uploads.set(id, status)
  })
  // 开了自动更新的只读联动字段：标签旁加「系统自动更新」标识（只读由规则呈现负责，这里不改）。
  for (const rule of fields) if (ruleAutoUpdate(props.options[String(rule.field)])) rule.info = autoUpdateInfo()
  // 同一发布表单供不同成员使用，后端裁剪字段后保留布局并移除无权查看的节点。
  const visibleNodes = (nodes: UiNode[]): UiNode[] =>
    nodes
      .filter(n =>
        n.type === NodeKind.INTERNAL_DETAIL
          ? !!n.detail?.detailId && !!detailsContext?.visible(n.detail.detailId)
          : n.type !== NodeKind.FIELD || fields.some(f => f.field === n.fieldId)
      )
      .map(n => ({ ...n, children: visibleNodes(n.children) }))
  const flatten = (nodes: UiNode[]): UiNode[] =>
    nodes.flatMap(n => (n.type === NodeKind.FIELD ? [n] : flatten(n.children)))
  const nodes = props.compact
    ? props.nodes
      ? flatten(props.nodes)
      : props.fields.map(f => uiNode(NodeKind.FIELD, { id: 'grid-' + f.id, fieldId: f.id! }))
    : props.nodes || defaultFormNodes(props.fields)
  const result = nodesToRules(visibleNodes(nodes), fields)
  if (props.compact)
    result.forEach((rule, index) => {
      const col = {
        ...(typeof rule.col === 'object' ? rule.col : {}),
        span: 24,
        style: { '--detail-column': index + 1 }
      }
      rule.col = col
    })
  return reactive(result) as Rule[]
})
// 条件变化只更新受影响字段，避免重载整个规则树时卸载仍在处理输入事件的控件。
const baseRules = new Map<
  string,
  {
    title: Rule['title']
    disabled: boolean
    validate: NonNullable<Rule['validate']>
    help?: string
    business: boolean
  }
>()
const appliedStates = new Map<string, string>()
const appliedRuleViews = new Set<string>()
const helpText = (wrap: Rule['wrap']) =>
  wrap && typeof wrap === 'object' && 'extra' in wrap && typeof wrap.extra === 'string' ? wrap.extra : undefined
watch(
  rules,
  nodes => {
    baseRules.clear()
    appliedStates.clear()
    appliedRuleViews.clear()
    const collect = (items: ReturnType<typeof nodesToRules>) =>
      items.forEach(rule => {
        if (rule.field)
          baseRules.set(String(rule.field), {
            title: rule.title,
            disabled: !!rule.props?.disabled,
            validate: [...(rule.validate || [])],
            help: helpText(rule.wrap),
            business: rule.type === 'nocodeBusinessField'
          })
        if (rule.children) collect(rule.children as ReturnType<typeof nodesToRules>)
      })
    collect(nodes)
  },
  { immediate: true, flush: 'sync' }
)
async function applyBehaviors() {
  await nextTick()
  if (!api.value) return
  const ids = new Set([...Object.keys(behaviorStates.value), ...Object.keys(ruleViews.value), ...appliedRuleViews])
  for (const id of ids) {
    const state = behaviorStates.value[id] || { visible: true, required: false, readOnly: false },
      view = ruleViews.value[id] || null
    const base = baseRules.get(id),
      signature = JSON.stringify([state, view])
    if (!base || appliedStates.get(id) === signature || !api.value.getRule(id)) continue
    // 只读规则字段（只读联动、公式默认值）与字段行为只读同样处理：禁用并移除必填校验，保存时由服务端重算强制。
    const readOnly = state.readOnly || !!view?.locked
    const validate = !state.visible || readOnly || base.disabled ? [] : [...base.validate]
    if (state.visible && state.required && !readOnly && !base.disabled)
      validate.push({
        validator: (_: unknown, input: unknown) =>
          emptyFormValue(input) ? Promise.reject(new Error('请填写' + base.title)) : Promise.resolve()
      })
    api.value.hidden(!state.visible, id)
    api.value.disabled(base.disabled || readOnly, id)
    api.value.updateValidate(id, validate, false)
    api.value.refreshValidate()
    if (view || appliedRuleViews.has(id)) {
      const patch = fieldRulePatch(view, { business: base.business, help: base.help, compact: props.compact })
      api.value.mergeRule(id, patch as Rule)
      if (view) appliedRuleViews.add(id)
      else appliedRuleViews.delete(id)
    }
    appliedStates.set(id, signature)
  }
}
watch([api, behaviorStates, rules, ruleViews], applyBehaviors, { flush: 'post' })
FormCreate.component('nocodeBusinessField', BusinessFieldControl)
FormCreate.component('nocodeHyperlink', HyperlinkField)
const option = computed(() => businessFormOptions(props.layout))
function preventDetailSubmit(event: KeyboardEvent) {
  // 仍让外层表格处理 Enter 移格，并保留文本域换行和输入法；只取消单行输入的原生提交。
  if (
    props.detailId &&
    event.key === 'Enter' &&
    !event.isComposing &&
    event.target instanceof HTMLInputElement &&
    !['button', 'submit', 'reset', 'file', 'checkbox', 'radio'].includes(event.target.type)
  )
    event.preventDefault()
}
function validateUploads() {
  if ([...uploads.values()].some(s => s.pending)) throw new Error('请等待附件上传完成')
  if ([...uploads.values()].some(s => s.failed)) throw new Error('有附件上传失败，请移除后重试')
}
defineExpose({
  rowKey: computed(() => props.clientRowKey),
  validateUploads,
  validate: async () => {
    // 保存前把防抖中的联动立即算完，避免带着过期建议值保存。
    await ruleCoordinator?.settle()
    validateUploads()
    await applyBehaviors()
    await nextTick()
    try {
      const result = await api.value?.validate()
      if (result === false) throw new Error('请检查必填字段')
    } catch {
      await nextTick()
      const invalid = root.value?.querySelector<HTMLElement>('.ant-form-item-has-error')
      // forceRender 让未访问页签也参加校验；失败时先切换祖先页签，再定位字段。
      const panels: HTMLElement[] = []
      for (let parent = invalid?.parentElement; parent; parent = parent.parentElement)
        if (parent.classList.contains('ant-tabs-tabpane')) panels.unshift(parent)
      for (const panel of panels) {
        panel
          .closest('.ant-tabs')
          ?.querySelector<HTMLElement>(`[role="tab"][aria-controls="${CSS.escape(panel.id)}"]`)
          ?.click()
        await nextTick()
      }
      invalid?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      invalid?.querySelector<HTMLElement>('input,textarea,[role="combobox"]')?.focus({ preventScroll: true })
      const label = invalid?.querySelector('label')?.textContent?.trim()
      throw new Error(label ? `请检查“${label}”的填写内容` : '请检查表单必填项和格式')
    }
  }
})
</script>
<template>
  <div
    ref="root"
    class="os-form-surface"
    :class="{ 'os-detail-grid-form': compact }"
    :style="compact ? { '--detail-columns': fields.length } : undefined"
    @keydown="preventDetailSubmit"
  >
    <p
      v-if="!detailId && !compact && model.managedFieldIds?.some(id => fields.some(field => field.id === id))"
      class="managed-field-notice"
    >
      {{
        fields
          .filter(field => model.managedFieldIds?.includes(field.id!))
          .map(field => field.name)
          .join('、')
      }}
      由业务规则维护，不能手工修改。
    </p>
    <FormCreate v-model="value" v-model:api="api" :rule="rules" :option="option" @mounted="applyBehaviors" />
    <a-alert v-if="ruleError" type="warning" show-icon :message="ruleError" style="margin-top: 12px" />
  </div>
</template>
<style scoped>
.managed-field-notice {
  margin: 0 0 12px;
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
}
</style>
