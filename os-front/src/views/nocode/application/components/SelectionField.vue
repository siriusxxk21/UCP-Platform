<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, inject, nextTick } from 'vue'
import { Form } from 'ant-design-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { DEFAULT_PAGE_SIZE } from '@/constants'
import { errorMessage } from '@/nocode/data-center'
import { selectionTree, selectionValuesKey, selectionPreviewKey } from '@/nocode/selection'
import { FieldRuleState, fieldRuleNamesKey, pendingText, type RuleFieldName } from '@/nocode/field-rule-runtime'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
import type { SelectionOption, SelectionResult } from '@/types/nocode/selection'

const props = defineProps<{
  modelValue?: string | string[] | null
  applicationId: string
  objectId: string
  fieldId: string
  detailId?: string
  recordId?: string
  detailRecordId?: string
  preview?: boolean
  required?: boolean
  formId?: string
  presentation?: import('@/types/nocode/selection').SelectionPresentation
  multiple?: boolean
  compact?: boolean
  disabled?: boolean
  readOnly?: boolean
  creating?: boolean
  placeholder?: string
  allowedValues?: string[]
  /** 引用筛选依赖的本表（明细行时为本行）字段：变化后立即重新加载候选。 */
  ruleDependsOn?: string[]
  /** 明细行引用筛选依赖的主表字段：变化后只标记过期，展开或获得焦点时才重新加载。 */
  ruleMasterDependsOn?: string[]
  /** 批量求值判定已选值不在筛选内（inScope=false）。用「不在」而非「在」：布尔属性缺省会被转成 false。 */
  ruleOutOfScope?: boolean
  /** 批量求值给出的 PENDING 提示（过期未重新加载前使用）。 */
  rulePending?: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | string[] | null] }>()
// 在子控件关闭浮层前记录 Esc 归属，冒泡时仅阻止它继续关闭外层表单。
const popupEscapeEvents = new WeakSet<KeyboardEvent>()
function capturePopupEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  const target = event.target as HTMLElement
  if (target.closest('.ant-select')?.querySelector('[aria-expanded="true"]')) popupEscapeEvents.add(event)
}
function stopPopupEscape(event: KeyboardEvent) {
  if (popupEscapeEvents.has(event)) event.stopPropagation()
}
// 整个选择器只登记为一个表单字段；搜索框和弹窗候选不参与外层字段收集。
const formItem = Form.useInjectFormItemContext()
function updateModel(value: string | string[] | null) {
  emit('update:modelValue', value)
  void nextTick(() => formItem.onFieldChange())
}
const api = useNocodePlatform().runtime
const applicationApi = useNocodePlatform().applications
const previewContext = inject(selectionPreviewKey, undefined)
const formValues = inject(selectionValuesKey, ref<Record<string, unknown>>({}))
const modalOpen = ref(false),
  draft = ref<string[]>([])
const modal = computed(() => props.presentation?.appearance === 'MODAL')
const useTree = computed(() => result.value.tree && props.presentation?.appearance !== 'SELECT')
const linkedValue = computed(() =>
  props.presentation?.linkFieldId ? formValues.value[props.presentation.linkFieldId] : undefined
)
const blocked = computed(
  () => !!props.presentation?.linkFieldId && (linkedValue.value == null || linkedValue.value === '')
)
// 候选所在对象的数据变了：选择器在表单里，不当场重载，下次展开或获得焦点时再取。
let outdated = false
useRuntimeDataRefresh({
  interest: () => (props.preview ? undefined : { applicationId: props.applicationId }),
  refresh: () => {
    outdated = true
  }
})
function refreshStale() {
  if (stale.value || outdated) void load()
}
function openPicker() {
  refreshStale()
  draft.value = [...selectedIds()]
  modalOpen.value = true
}
function confirmPicker() {
  if (props.disabled || props.readOnly || locked.value) return
  initialized = true
  updateModel(props.multiple ? [...draft.value] : draft.value[0] || null)
  modalOpen.value = false
}

const result = ref<SelectionResult>({ options: [], selected: [], total: 0, tree: false, defaultValue: null })
const busy = ref(false),
  error = ref(''),
  keyword = ref(''),
  pageNo = ref(1)
let generation = 0,
  timer: ReturnType<typeof setTimeout> | undefined,
  initialized = false
const selectedIds = () =>
  Array.isArray(props.modelValue) ? props.modelValue : props.modelValue ? [String(props.modelValue)] : []
const ruleNames = inject(fieldRuleNamesKey, ref<Record<string, RuleFieldName>>({}))
const ruleKeys = computed(() => [...(props.ruleDependsOn || []), ...(props.ruleMasterDependsOn || [])])
const hasRule = computed(() => ruleKeys.value.length > 0)
const ruleValues = (keys: string[] | undefined) => Object.fromEntries((keys || []).map(k => [k, formValues.value[k]]))
// 主表依赖变化后候选已过期，但不立即请求：批量求值给出 inScope，展开或获得焦点时再加载。
const stale = ref(false)
const rulePendingText = computed(() => {
  if (stale.value && props.rulePending) return props.rulePending
  return result.value.ruleState === FieldRuleState.PENDING
    ? pendingText(result.value.pendingFields, ruleNames.value, props.detailId)
    : null
})
const locked = computed(() => blocked.value || !!rulePendingText.value)
/**
 * 引用筛选本身求不了值（例如条件里引用字段的固定值存成了名称）：原先候选静默为空，业务方不知道改哪里（2026-10-04）。
 * 服务端以「配置有误：」开头的原样接在「引用筛选」后面，其余接「无法求值：」。
 */
const ruleProblem = computed(() => {
  const state = result.value.ruleState
  const text = result.value.ruleMessage
  if (!state || !text || state === FieldRuleState.APPLIED || state === FieldRuleState.PENDING) return ''
  if (state === FieldRuleState.NOT_APPLICABLE) return ''
  return '引用筛选' + (text.startsWith('配置有误：') ? text : '无法求值：' + text)
})
const ruleEmpty = computed(
  () =>
    hasRule.value &&
    result.value.ruleState === FieldRuleState.APPLIED &&
    result.value.total === 0 &&
    !keyword.value &&
    !busy.value &&
    !error.value
)
/** 已选但不在当前筛选内：明细行取批量结果的 inScope；主表取候选接口标记为不可选的已选项。 */
const outOfScope = computed(() => {
  const ids = selectedIds()
  if (props.ruleOutOfScope) return new Set(ids)
  if (!hasRule.value || result.value.ruleState !== FieldRuleState.APPLIED) return new Set<string>()
  return new Set(
    result.value.selected.filter(o => o.disabled && !o.unavailable && ids.includes(o.value)).map(o => o.value)
  )
})
const OUT_OF_SCOPE = '（不符合当前筛选）'
const options = computed(() => {
  const map = new Map<string, SelectionOption>()
  for (const o of [...result.value.options, ...result.value.selected]) map.set(o.value, o)
  return [...map.values()].filter(o => props.allowedValues == null || props.allowedValues.includes(o.value))
})
const tree = computed(() => selectionTree(options.value))
const selectedText = computed(
  () =>
    selectedIds()
      .map(id => {
        const o = options.value.find(o => o.value === id)
        if (o && outOfScope.value.has(id)) return o.label + OUT_OF_SCOPE
        return o ? o.label + (o.unavailable ? '' : o.disabled ? '（已停用或不可选）' : '') : '正在读取…'
      })
      .join('、') || '—'
)
async function load(append = false, selected = selectedIds()) {
  // 联动来源清空也要使旧请求失效，防止迟到响应恢复已经清空的候选和默认值。
  const current = ++generation
  stale.value = false
  outdated = false
  if (blocked.value) {
    result.value = { options: [], selected: [], total: 0, tree: false, defaultValue: null }
    busy.value = false
    error.value = ''
    return
  }
  busy.value = true
  error.value = ''
  if (!append) result.value = { options: [], selected: [], total: 0, tree: false, defaultValue: null }
  try {
    const query = {
      applicationId: props.applicationId,
      objectId: props.objectId,
      fieldId: props.fieldId,
      detailId: props.detailId,
      recordId: props.recordId,
      detailRecordId: props.detailRecordId,
      creating: props.creating,
      formId: props.formId,
      // 服务端只取该字段 dependsOn 与 linkFieldId 里的键；候选以服务端结果为准，前端不再本地筛。
      formValues:
        props.presentation?.linkFieldId || hasRule.value
          ? {
              ...ruleValues(ruleKeys.value),
              ...(props.presentation?.linkFieldId ? { [props.presentation.linkFieldId]: linkedValue.value } : {})
            }
          : undefined,
      selected,
      search: keyword.value,
      pageNo: pageNo.value,
      pageSize: modal.value ? DEFAULT_PAGE_SIZE : 30
    }
    if (props.preview && !previewContext?.value) throw new Error('缺少预览对象版本上下文')
    const value = props.preview
      ? await applicationApi.previewSelection({
          query,
          objects: previewContext!.value.objects,
          form: previewContext!.value.form
        })
      : await api.selection(query)
    if (current !== generation) return
    result.value = { ...value, options: append ? [...result.value.options, ...value.options] : value.options }
    if (!initialized && props.creating && props.modelValue === undefined && value.defaultValue != null)
      updateModel(value.defaultValue)
    initialized = true
  } catch (e) {
    if (current === generation) error.value = errorMessage(e)
  } finally {
    if (current === generation) busy.value = false
  }
}
function search(text: string) {
  clearTimeout(timer)
  keyword.value = text
  pageNo.value = 1
  timer = setTimeout(() => void load(), 250)
}
function changed(value: unknown) {
  initialized = true
  if (modalOpen.value) {
    draft.value = Array.isArray(value) ? value.map(String) : value == null ? [] : [String(value)]
    return
  }

  updateModel(props.multiple ? (Array.isArray(value) ? value.map(String) : []) : value == null ? null : String(value))
}
function more(event: Event) {
  const el = event.target as HTMLElement
  if (
    !result.value.tree &&
    !busy.value &&
    result.value.options.length < result.value.total &&
    el.scrollTop + el.clientHeight >= el.scrollHeight - 30
  ) {
    pageNo.value++
    void load(true)
  }
}
watch(
  () => [
    props.applicationId,
    props.objectId,
    props.fieldId,
    props.detailId,
    props.recordId,
    props.detailRecordId,
    props.formId,
    JSON.stringify(props.presentation),
    props.preview ? JSON.stringify(previewContext?.value) : ''
  ],
  () => {
    initialized = false
    pageNo.value = 1
    keyword.value = ''
    void load()
  },
  { immediate: true }
)
watch(
  () => JSON.stringify(props.modelValue),
  () => {
    if (selectedIds().some(id => !options.value.some(o => o.value === id))) void load()
  }
)
watch(
  () => JSON.stringify(linkedValue.value),
  (value, previous) => {
    // 首次等待上游默认值时保留缺省语义；之后的联动变化才视为清空旧选择。
    const awaitingDefault = props.creating && props.modelValue === undefined && !initialized
    const clearSelection = value !== previous && !awaitingDefault && !props.disabled && !props.readOnly
    if (clearSelection) updateModel(props.multiple ? [] : null)
    pageNo.value = 1
    // emit 后父表单尚未回写 props，刷新时显式排除旧选择，避免作为历史回显混入新候选。
    void load(false, clearSelection ? [] : selectedIds())
  }
)
watch(
  () => JSON.stringify(ruleValues(props.ruleDependsOn)),
  (value, previous) => {
    if (value === previous) return
    pageNo.value = 1
    void load()
  }
)
watch(
  () => JSON.stringify(ruleValues(props.ruleMasterDependsOn)),
  (value, previous) => {
    if (value === previous) return
    // 主表字段属于本表单时（非明细行）照常立即加载。
    if (!props.detailId) {
      pageNo.value = 1
      void load()
      return
    }
    generation++
    busy.value = false
    stale.value = true
  }
)
onBeforeUnmount(() => {
  generation++
  clearTimeout(timer)
})
</script>
<template>
  <div class="selection-field" @keydown.capture="capturePopupEscape" @keydown="stopPopupEscape">
    <span v-if="readOnly" :title="result.selected.map(o => o.path || o.label).join('、')">{{ selectedText }}</span>
    <a-button v-else-if="modal" :id="formItem.id.value" block :disabled="disabled || locked" @click="openPicker">
      {{ selectedIds().length ? selectedText : placeholder || '请选择' }}
    </a-button>
    <a-tree-select
      v-else-if="useTree"
      :id="formItem.id.value"
      :value="modelValue || undefined"
      :tree-data="tree"
      :multiple="multiple"
      :max-tag-count="compact && multiple ? 'responsive' : undefined"
      :disabled="disabled || locked"
      :loading="busy"
      :placeholder="rulePendingText || (blocked ? '请先填写联动来源字段' : placeholder || '请选择')"
      show-search
      allow-clear
      tree-default-expand-all
      tree-node-filter-prop="searchText"
      style="width: 100%"
      @change="changed"
      @focus="refreshStale"
      @dropdown-visible-change="(open: boolean) => open && refreshStale()"
    >
      <template v-if="compact" #maxTagPlaceholder="omittedValues">
        <a-tooltip :title="selectedText" :trigger="['hover', 'focus']">
          <span tabindex="0" :aria-label="`已选 ${selectedIds().length} 项：${selectedText}`">
            +{{ omittedValues.length }} 项
          </span>
        </a-tooltip>
      </template>
    </a-tree-select>
    <a-select
      v-else
      :id="formItem.id.value"
      :value="modelValue || undefined"
      :options="
        options.map(o => ({ ...o, label: (o.path || o.label) + (outOfScope.has(o.value) ? OUT_OF_SCOPE : '') }))
      "
      :mode="multiple ? 'multiple' : undefined"
      :max-tag-count="compact && multiple ? 'responsive' : undefined"
      :disabled="disabled || locked"
      :loading="busy"
      :placeholder="rulePendingText || (blocked ? '请先填写联动来源字段' : placeholder || '输入名称或编码搜索')"
      show-search
      allow-clear
      :filter-option="false"
      style="width: 100%"
      @search="search"
      @popup-scroll="more"
      @change="changed"
      @focus="refreshStale"
      @dropdown-visible-change="(open: boolean) => open && refreshStale()"
    >
      <template v-if="compact" #maxTagPlaceholder="omittedValues">
        <a-tooltip :title="selectedText" :trigger="['hover', 'focus']">
          <span tabindex="0" :aria-label="`已选 ${selectedIds().length} 项：${selectedText}`">
            +{{ omittedValues.length }} 项
          </span>
        </a-tooltip>
      </template>
    </a-select>
    <a-modal v-model:open="modalOpen" title="选择记录" :width="760" @ok="confirmPicker">
      <a-input-search
        v-if="!result.tree"
        :value="keyword"
        placeholder="搜索名称或编码"
        allow-clear
        @change="search($event.target.value || '')"
        style="margin-bottom: 16px"
      />
      <a-tree-select
        v-if="result.tree"
        :value="multiple ? draft : draft[0]"
        :tree-data="tree"
        :multiple="multiple"
        show-search
        tree-default-expand-all
        tree-node-filter-prop="searchText"
        style="width: 100%"
        @change="changed"
      />
      <a-table
        v-else
        row-key="value"
        :data-source="result.options.filter(o => allowedValues == null || allowedValues.includes(o.value))"
        :loading="busy"
        :columns="[
          { title: '名称', dataIndex: 'label' },
          { title: '编码', dataIndex: 'code' },
          { title: '路径', dataIndex: 'path' }
        ]"
        :row-selection="{
          type: multiple ? 'checkbox' : 'radio',
          selectedRowKeys: draft,
          onChange: (keys: (string | number)[]) => {
            draft = keys.map(String)
          },
          getCheckboxProps: (o: SelectionOption) => ({ disabled: o.disabled }),
          preserveSelectedRowKeys: true
        }"
        :pagination="{ current: pageNo, pageSize: DEFAULT_PAGE_SIZE, total: result.total, showSizeChanger: false }"
        @change="
          (p: { current?: number }) => {
            pageNo = p.current || 1
            load()
          }
        "
      />
      <a-typography-text type="secondary">已选 {{ draft.length }} 项</a-typography-text>
      <a-button v-if="!required && !disabled && !readOnly" type="link" @click="draft = []">清空选择</a-button>
    </a-modal>
    <a-alert
      v-if="creating && modelValue === undefined && result.defaultWarning"
      type="warning"
      :message="result.defaultWarning"
      show-icon
    />
    <div v-if="rulePendingText && !readOnly" class="selection-hint">{{ rulePendingText }}</div>
    <div v-else-if="outOfScope.size && !readOnly" class="selection-warning">已选值不符合当前筛选，请重新选择</div>
    <div v-else-if="ruleEmpty && !readOnly" class="selection-hint">筛选已生效 · 符合条件的候选 0 条</div>
    <div v-if="ruleProblem && !readOnly" class="selection-error" role="alert">{{ ruleProblem }}</div>
    <div v-if="error" class="selection-error">
      {{ error }}
      <a @click="load()">重试</a>
    </div>
  </div>
</template>
<style scoped>
.selection-error {
  color: #dc2626;
  font-size: 12px;
  margin-top: 4px;
}
.selection-hint {
  color: var(--text-color-secondary, #8c8c8c);
  font-size: 12px;
  margin-top: 4px;
}
.selection-warning {
  color: #d97706;
  font-size: 12px;
  margin-top: 4px;
}
</style>
