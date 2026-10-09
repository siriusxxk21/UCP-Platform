<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { RuleCondition } from '@/types/nocode/field-rules'
import { createDataCenterApi } from '@/api/nocode/data-center'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import {
  COUNTED_CODES,
  MAX_RELATIVE_DAYS,
  RELATIVE_DATE_LABELS,
  RELATIVE_DATE_OPTIONS,
  RELATIVE_DATE_QUICK,
  isQuickPicked,
  isRelativeDate,
  relativeDateError,
  relativeDateField,
  relativeDateFromOption,
  relativeDateOption,
  relativeDateValue
} from '@/nocode/relative-date'
import {
  CONDITION_CAP,
  CURRENT_RECORD,
  RECORD_KEY,
  definitionOptions,
  formFieldCompatible,
  isValuelessOperator,
  loadPublishedDefinition,
  operatorLabel,
  operatorsFor,
  referenceTargetOf,
  type FieldChoiceGroup,
  type PublishedDefinition
} from '@/nocode/field-rules'
import {
  conditionChoiceOptions,
  isChoiceConditionField,
  loadConditionChoices,
  referenceConditionTarget,
  type ConditionChoice,
  type ConditionChoiceSet
} from '@/nocode/rule-condition-choices'
import ConditionRecordPicker from './ConditionRecordPicker.vue'

/**
 * 引用筛选与数据联动共用的条件行（老系统同样是一个零件）。
 * 条件之间只有「且」，结构上没有「或」与嵌套；值来源为固定值或当前字段（按记录匹配只用「等于」）。
 * 比较方式选「为空 / 不为空」时不需要右侧的值：值来源与取值控件不显示，保存出的条件不带值。
 * 来源字段是选项类（单选、多选）时，固定值从该字段的选项里选：显示名称、保存编码，不再是自由文本框。
 * 来源字段是单值引用字段时，固定值从目标对象的记录里选：显示名称、保存记录 ID（业务方 2026-10-04）；已存值不是有效记录时标红引导重选。
 * 数据联动另有值来源「当前记录」（allowCurrentRecord）：来源对象上指向本对象的单选关联字段 等于 当前这条记录；
 * 只能用「等于」，没有取值控件。引用筛选不传这个属性，不出现这一项。
 * 日期 / 日期时间来源字段配「早于 / 晚于 / 在范围内」时，值来源另有「相对日期」（今天、本周、过去 N 天……）：存 { relative, n? }、
 * 值来源仍是 CONSTANT，由服务端每次求值时按当天换算。数据联动传 relativeBlocked（取到的值写进字段，过了零点不会自己变），该项置灰并写明原因。
 */
const props = defineProps<{
  definition: PublishedDefinition | null
  formGroups: FieldChoiceGroup[]
  recordKey?: boolean
  disabled?: boolean
  emptyText: string
  /** 当前对象 ID（已保存的对象才有）：用来认出来源对象上哪些关联字段指向本对象。 */
  currentObjectId?: string | null
  /** 允许值来源选「当前记录」（只由数据联动弹层在可以开启自动更新时传入）。 */
  allowCurrentRecord?: boolean
  /** 相对日期不可用的原因（数据联动）；不传即可用（引用筛选）。 */
  relativeBlocked?: string | null
}>()
const model = defineModel<RuleCondition[]>({ required: true })

const conditionFields = computed(() =>
  (props.definition?.fields ?? []).filter(
    field => !!field.id && operatorsFor(field, definitionOptions(props.definition, field)).length
  )
)
const fieldChoices = computed(() => [
  ...(props.recordKey ? [{ value: RECORD_KEY, label: '按记录匹配（来源记录本身）' }] : []),
  ...conditionFields.value.map(field => ({ value: field.id ?? '', label: field.name || field.code }))
])
function sourceField(condition: RuleCondition) {
  return conditionFields.value.find(field => field.id === condition.fieldId)
}
/** 来源对象主表上指向本对象的单选关联字段（多选关联、明细里的关联不算）。 */
function pointsToCurrentObject(field: ObjectField | undefined): boolean {
  return (
    !!field &&
    !!props.currentObjectId &&
    referenceTargetOf(
      field,
      (props.definition?.relations ?? []).filter(relation => !relation.sourceDetailId)
    ) === props.currentObjectId
  )
}
/** 这一条条件的值来源能否选「当前记录」。 */
const currentRecordUsable = (condition: RuleCondition) =>
  !!props.allowCurrentRecord && pointsToCurrentObject(sourceField(condition))
const isCurrentRecord = (condition: RuleCondition) => condition.valueSource === CURRENT_RECORD
/** 界面上的「相对日期」值来源：存储里仍是 CONSTANT，值是 { relative, n? }。 */
const RELATIVE = 'RELATIVE'
const RELATIVE_RULE_OPERATORS = ['lt', 'gt', 'between']
const isRelative = (condition: RuleCondition) => condition.valueSource === 'CONSTANT' && isRelativeDate(condition.value)
const relativeUsable = (condition: RuleCondition) =>
  relativeDateField(sourceField(condition)?.type) && RELATIVE_RULE_OPERATORS.includes(condition.operator)
function valueSourceOptions(condition: RuleCondition) {
  const relative = relativeUsable(condition)
    ? [
        {
          value: RELATIVE,
          label: '相对日期',
          disabled: !!props.relativeBlocked,
          title: props.relativeBlocked || undefined
        }
      ]
    : []
  if (condition.operator === 'between') return [{ value: 'CONSTANT', label: '固定值' }, ...relative]
  return [
    { value: 'CONSTANT', label: '固定值' },
    { value: 'FORM_FIELD', label: '当前字段' },
    ...relative,
    ...(currentRecordUsable(condition) ? [{ value: CURRENT_RECORD, label: '当前记录' }] : [])
  ]
}
const valueSourceOf = (condition: RuleCondition) => (isRelative(condition) ? RELATIVE : condition.valueSource)
function operators(condition: RuleCondition) {
  if (condition.fieldId === RECORD_KEY || isCurrentRecord(condition)) return ['eq']
  const field = sourceField(condition)
  return field ? operatorsFor(field, definitionOptions(props.definition, field)) : []
}
function operatorOptions(condition: RuleCondition) {
  const type = sourceField(condition)?.type
  return operators(condition).map(value => ({ value, label: operatorLabel(value, type) }))
}
/** 「当前字段」候选按类型兼容过滤；按记录匹配只列指向来源对象的关联字段。 */
function formChoices(condition: RuleCondition) {
  const field = sourceField(condition)
  return props.formGroups
    .map(group => ({
      label: group.label,
      options: group.options
        .filter(choice =>
          condition.fieldId === RECORD_KEY
            ? !!props.definition && choice.referenceTarget === props.definition.objectId
            : !!field &&
              formFieldCompatible(
                {
                  type: field.type,
                  options: definitionOptions(props.definition, field),
                  referenceTarget: referenceTargetOf(field, props.definition?.relations)
                },
                choice
              )
        )
        .map(choice => ({ value: choice.value, label: choice.label }))
    }))
    .filter(group => group.options.length)
}
/* ── 选项类来源字段的固定值：按字段加载选项（局部选项、公共字典、挑取值的来源字段），加载失败或没有选项时给出提示，不退回自由文本 ── */
interface ChoiceState {
  loading: boolean
  error: string
  set: ConditionChoiceSet | null
}
const dataCenter = createDataCenterApi(request)
const choiceStates = ref<Record<string, ChoiceState>>({})
let choiceGeneration = 0
let dictionaryItems: Promise<Array<{ dictType: string; value: string; label: string }>> | null = null
const choiceDeps = {
  definition: (objectId: string) => loadPublishedDefinition(dataCenter, objectId),
  // 与字段抽屉「挑取值」预览同一个接口：只返回启用的字典项。
  dictionary: async (type: string): Promise<ConditionChoice[]> => {
    dictionaryItems ??= request.get<Array<{ dictType: string; value: string; label: string }>>(
      '/system/dict-data/list-all-simple'
    )
    return (await dictionaryItems)
      .filter(item => item.dictType === type)
      .map(item => ({ value: item.value, label: item.label, disabled: false }))
  }
}
const isChoice = (condition: RuleCondition) => isChoiceConditionField(props.definition, sourceField(condition))
async function loadChoices(fieldId: string) {
  const definition = props.definition
  const field = definition?.fields.find(item => item.id === fieldId)
  const known = choiceStates.value[fieldId]
  // 已加载或加载中的不重复请求；上次失败的在条件再次变动时重试。
  if (!definition || !field || (known && !known.error)) return
  const turn = choiceGeneration
  choiceStates.value = { ...choiceStates.value, [fieldId]: { loading: true, error: '', set: null } }
  let next: ChoiceState
  try {
    next = { loading: false, error: '', set: await loadConditionChoices(definition, field, choiceDeps) }
  } catch (cause) {
    dictionaryItems = null
    next = { loading: false, error: errorMessage(cause), set: null }
  }
  if (turn === choiceGeneration) choiceStates.value = { ...choiceStates.value, [fieldId]: next }
}
watch(
  () => props.definition,
  () => {
    choiceGeneration++
    choiceStates.value = {}
  }
)
watch(
  () => [props.definition, ...model.value.filter(isChoice).map(condition => condition.fieldId)],
  () => {
    for (const condition of model.value) if (isChoice(condition)) void loadChoices(condition.fieldId)
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  choiceGeneration++
})
const choiceValues = (condition: RuleCondition) =>
  (Array.isArray(condition.value) ? condition.value : [condition.value])
    .filter(item => item !== null && item !== undefined && item !== '')
    .map(String)
function choiceOptions(condition: RuleCondition) {
  const set = choiceStates.value[condition.fieldId]?.set
  if (set) return conditionChoiceOptions(set, condition.value)
  // 选项还没加载到（加载中或失败）：已存的值照原样回显，不判断它是否有效。
  return choiceValues(condition).map(value => ({ value, label: value, disabled: true }))
}
/** 选项加载失败或一条启用的选项都没有时的说明；加载中与正常时为空。 */
function choiceProblem(condition: RuleCondition): string {
  if (!isChoice(condition) || isValuelessOperator(condition.operator) || condition.valueSource !== 'CONSTANT') return ''
  const state = choiceStates.value[condition.fieldId]
  const name = sourceField(condition)?.name ?? ''
  if (!state || state.loading) return ''
  if (state.error) return `「${name}」的选项加载失败：${state.error}。固定值只能从选项中选择，请稍后重试。`
  return state.set?.options.some(item => !item.disabled)
    ? ''
    : `「${name}」没有可选的选项，请先到来源对象为它配置选项；固定值只能从选项中选择。`
}
function choiceValue(condition: RuleCondition) {
  const values = choiceValues(condition)
  return condition.operator === 'containsAny' ? values : values[0]
}
const isBoolean = (condition: RuleCondition) => sourceField(condition)?.type === FieldType.BOOLEAN
/** 引用字段「等于 / 不等于」固定值：选记录。来源定义里没有对象 ID（未保存的对象）时退回文本框，避免查不了候选。 */
const isRecord = (condition: RuleCondition) =>
  !!props.definition?.objectId &&
  ['eq', 'neq'].includes(condition.operator) &&
  !!referenceConditionTarget(props.definition, sourceField(condition))

function at(index: number): RuleCondition {
  return model.value[index] ?? { fieldId: '', operator: '', valueSource: 'CONSTANT', value: null }
}
function built(
  fieldId: string,
  operator: string,
  valueSource: RuleCondition['valueSource'],
  value: unknown,
  formFieldId: string | null
): RuleCondition {
  // 「当前记录」：只有三个键，比较方式固定「等于」，不带值也不带当前字段。
  if (valueSource === CURRENT_RECORD) return { fieldId, operator: 'eq', valueSource }
  if (isValuelessOperator(operator)) return { fieldId, operator, valueSource: 'CONSTANT', value: null }
  if (valueSource === 'FORM_FIELD') return { fieldId, operator, valueSource, formFieldId }
  if (isRelativeDate(value)) return { fieldId, operator, valueSource, value }
  const range = Array.isArray(value) ? value : ['', '']
  return { fieldId, operator, valueSource, value: operator === 'between' ? range : value }
}
function replace(index: number, next: RuleCondition) {
  if (props.disabled) return
  model.value = model.value.map((item, position) => (position === index ? next : item))
}
function changeField(index: number, fieldId: string) {
  const record = fieldId === RECORD_KEY
  const operator = record ? 'eq' : (operators({ ...at(index), fieldId })[0] ?? '')
  replace(index, built(fieldId, operator, record ? 'FORM_FIELD' : 'CONSTANT', null, null))
}
function changeOperator(index: number, operator: string) {
  const current = at(index)
  if (isCurrentRecord(current)) return
  const between = operator === 'between'
  // 相对日期在「早于 / 晚于 / 在范围内」之间切换时保留；具体值仍按原口径（进出「在范围内」清空）。
  const keepRelative = isRelative(current) && RELATIVE_RULE_OPERATORS.includes(operator)
  const keepValue = keepRelative || (!between && current.operator !== 'between')
  replace(
    index,
    built(
      current.fieldId,
      operator,
      between || keepRelative ? 'CONSTANT' : current.valueSource,
      keepValue ? current.value : null,
      between ? null : (current.formFieldId ?? null)
    )
  )
}
function changeValueSource(index: number, value: unknown) {
  const current = at(index)
  if (value === RELATIVE) {
    if (props.relativeBlocked || !relativeUsable(current)) return
    replace(index, built(current.fieldId, current.operator, 'CONSTANT', relativeDateValue('TODAY'), null))
    return
  }
  const valueSource: RuleCondition['valueSource'] =
    value === CURRENT_RECORD && currentRecordUsable(current)
      ? CURRENT_RECORD
      : value === 'FORM_FIELD'
        ? 'FORM_FIELD'
        : 'CONSTANT'
  // 从「当前记录」换回别的值来源时比较方式沿用「等于」。
  replace(index, built(current.fieldId, current.operator, valueSource, null, null))
}
/**
 * 已存的「当前记录」不再适用（关联字段改指了别的对象、父组件不再允许）时退回「固定值」并清空，
 * 让「请填写固定值」的校验把它点出来，不静默保留一个无效条件。来源定义加载中与只读查看时不动。
 */
watch(
  () => [props.definition, props.allowCurrentRecord, props.currentObjectId, model.value] as const,
  () => {
    if (props.disabled || !props.definition) return
    if (!model.value.some(condition => isCurrentRecord(condition) && !currentRecordUsable(condition))) return
    model.value = model.value.map(condition =>
      isCurrentRecord(condition) && !currentRecordUsable(condition)
        ? built(condition.fieldId, 'eq', 'CONSTANT', null, null)
        : condition
    )
  },
  { immediate: true }
)
function changeValue(index: number, value: unknown) {
  const current = at(index)
  replace(index, built(current.fieldId, current.operator, 'CONSTANT', value, null))
}
function changeRange(index: number, position: 0 | 1, value: string) {
  const current = at(index)
  const range = Array.isArray(current.value) ? [...current.value] : ['', '']
  range[position] = value
  changeValue(index, range)
}
/** 快捷按钮（今天 / 本周 / 本月）：日期字段配早于 / 晚于 / 在范围内时直接列出，一点即选。 */
const quickUsable = (condition: RuleCondition) =>
  !props.disabled && !props.relativeBlocked && relativeUsable(condition) && condition.valueSource !== 'FORM_FIELD'
function pickQuick(index: number, code: (typeof RELATIVE_DATE_QUICK)[number]) {
  const current = at(index)
  if (!quickUsable(current)) return
  replace(index, built(current.fieldId, current.operator, 'CONSTANT', relativeDateValue(code), null))
}
function changeRelative(index: number, option: unknown) {
  const current = at(index)
  replace(
    index,
    built(current.fieldId, current.operator, 'CONSTANT', relativeDateFromOption(String(option), current.value), null)
  )
}
function changeDays(index: number, days: unknown) {
  const current = at(index)
  if (!isRelativeDate(current.value)) return
  const n = typeof days === 'number' ? days : Number(days)
  changeValue(index, { relative: current.value.relative, n: Number.isFinite(n) ? n : undefined })
}
const relativeOption = (condition: RuleCondition) =>
  isRelativeDate(condition.value) ? relativeDateOption(condition.value) : undefined
const relativeCounted = (condition: RuleCondition) =>
  isRelativeDate(condition.value) && COUNTED_CODES.includes(condition.value.relative)
const relativeDays = (condition: RuleCondition) => (isRelativeDate(condition.value) ? condition.value.n : undefined)
/** 相对日期这一条的问题：数据联动里出现（存量或换了来源字段）、比较方式不能组合、天数不对。 */
function relativeProblem(condition: RuleCondition): string {
  if (!isRelative(condition)) return ''
  if (props.relativeBlocked) return props.relativeBlocked
  if (!relativeUsable(condition)) return '相对日期只能用于日期字段的「早于」「晚于」「在范围内」'
  return relativeDateError(condition.value) ?? ''
}
function changeFormField(index: number, formFieldId: string) {
  const current = at(index)
  replace(index, built(current.fieldId, current.operator, 'FORM_FIELD', null, formFieldId))
}
function add() {
  if (props.disabled || model.value.length >= CONDITION_CAP) return
  model.value = [...model.value, { fieldId: '', operator: '', valueSource: 'CONSTANT', value: null }]
}
function remove(index: number) {
  if (props.disabled) return
  model.value = model.value.filter((_, position) => position !== index)
}
const rangeValue = (condition: RuleCondition, position: 0 | 1) =>
  Array.isArray(condition.value) ? String(condition.value[position] ?? '') : ''
</script>
<template>
  <div class="rule-conditions">
    <a-alert v-if="!definition" type="info" show-icon message="请先选择来源对象，再设置条件。" />
    <template v-else>
      <a-alert v-if="!model.length" type="info" show-icon :message="emptyText" />
      <template v-for="(condition, index) in model" :key="index">
        <div v-if="index > 0" class="rule-and" aria-label="条件之间为且">且</div>
        <div class="rule-row" :data-condition-index="index">
          <a-select
            :value="condition.fieldId || undefined"
            :disabled="disabled"
            :options="fieldChoices"
            show-search
            option-filter-prop="label"
            placeholder="来源字段"
            :aria-label="`第 ${index + 1} 条条件来源字段`"
            @update:value="changeField(index, String($event))"
          />
          <a-select
            :value="condition.operator || undefined"
            :disabled="disabled || !condition.fieldId || isCurrentRecord(condition)"
            :options="operatorOptions(condition)"
            placeholder="比较方式"
            :aria-label="`第 ${index + 1} 条条件比较方式`"
            @update:value="changeOperator(index, String($event))"
          />
          <template v-if="!isValuelessOperator(condition.operator)">
            <a-select
              :value="valueSourceOf(condition)"
              :disabled="disabled || (condition.operator === 'between' && valueSourceOptions(condition).length < 2)"
              :options="valueSourceOptions(condition)"
              :aria-label="`第 ${index + 1} 条条件值来源`"
              @update:value="changeValueSource(index, $event)"
            />
            <!-- 「当前记录」没有取值控件：比较的就是当前这条记录本身。 -->
            <template v-if="isCurrentRecord(condition)" />
            <template v-else-if="isRelative(condition)">
              <a-select
                :value="relativeOption(condition)"
                :options="RELATIVE_DATE_OPTIONS"
                :disabled="disabled"
                :aria-label="`第 ${index + 1} 条条件相对日期`"
                @update:value="changeRelative(index, $event)"
              />
              <a-input-number
                v-if="relativeCounted(condition)"
                :value="relativeDays(condition)"
                :min="1"
                :max="MAX_RELATIVE_DAYS"
                :precision="0"
                :disabled="disabled"
                addon-after="天"
                :aria-label="`第 ${index + 1} 条条件天数`"
                @update:value="changeDays(index, $event)"
              />
            </template>
            <a-select
              v-else-if="condition.valueSource === 'FORM_FIELD'"
              :value="condition.formFieldId || undefined"
              :disabled="disabled"
              :options="formChoices(condition)"
              show-search
              option-filter-prop="label"
              placeholder="当前字段"
              not-found-content="没有类型相符、已保存的当前字段"
              :aria-label="`第 ${index + 1} 条条件当前字段`"
              @update:value="changeFormField(index, String($event))"
            />
            <template v-else-if="condition.operator === 'between'">
              <a-input
                :value="rangeValue(condition, 0)"
                :disabled="disabled"
                placeholder="起"
                @update:value="changeRange(index, 0, $event)"
              />
              <a-input
                :value="rangeValue(condition, 1)"
                :disabled="disabled"
                placeholder="止"
                @update:value="changeRange(index, 1, $event)"
              />
            </template>
            <ConditionRecordPicker
              v-else-if="isRecord(condition)"
              :object-id="definition.objectId"
              :field-id="condition.fieldId"
              :field-name="sourceField(condition)?.name || ''"
              :field-type="sourceField(condition)?.type"
              :value="condition.value"
              :disabled="disabled"
              :index="index"
              @change="changeValue(index, $event)"
            />
            <a-select
              v-else-if="isBoolean(condition)"
              :value="condition.value === true ? 'true' : condition.value === false ? 'false' : undefined"
              :disabled="disabled"
              :options="[
                { value: 'true', label: '是' },
                { value: 'false', label: '否' }
              ]"
              placeholder="固定值"
              @update:value="changeValue(index, $event === 'true')"
            />
            <a-select
              v-else-if="isChoice(condition)"
              :value="choiceValue(condition)"
              :mode="condition.operator === 'containsAny' ? 'multiple' : undefined"
              :disabled="disabled"
              :loading="choiceStates[condition.fieldId]?.loading"
              :status="choiceProblem(condition) ? 'error' : undefined"
              :options="choiceOptions(condition)"
              show-search
              option-filter-prop="label"
              placeholder="请选择选项"
              not-found-content="没有可选的选项"
              :aria-label="`第 ${index + 1} 条条件固定值`"
              @update:value="changeValue(index, $event)"
            />
            <a-input
              v-else
              :value="condition.value == null ? '' : String(condition.value)"
              :disabled="disabled"
              placeholder="固定值"
              @update:value="changeValue(index, $event)"
            />
          </template>
          <span v-if="quickUsable(condition)" class="rule-quick" role="group" aria-label="常用相对日期">
            <a-button
              v-for="code in RELATIVE_DATE_QUICK"
              :key="code"
              size="small"
              :type="isQuickPicked(condition.value, code) ? 'primary' : 'default'"
              :data-quick="code"
              @click="pickQuick(index, code)"
            >
              {{ RELATIVE_DATE_LABELS[code] }}
            </a-button>
          </span>
          <a-button :disabled="disabled" @click="remove(index)">移除</a-button>
        </div>
        <p v-if="choiceProblem(condition)" class="rule-choice-problem" role="alert">{{ choiceProblem(condition) }}</p>
        <p v-if="relativeProblem(condition)" class="rule-choice-problem" role="alert">
          {{ relativeProblem(condition) }}
        </p>
      </template>
      <a-button size="small" :disabled="disabled || model.length >= CONDITION_CAP" @click="add">
        <PlusOutlined />
        添加条件
      </a-button>
      <p class="rule-hint">
        条件之间只有「且」；最多 {{ CONDITION_CAP }} 条。「当前字段」取表单里该字段的现值。
        「不等于」会选中该字段为空的记录；「为空 / 不为空」不用填值。
      </p>
      <p v-if="!relativeBlocked && conditionFields.some(f => relativeDateField(f.type))" class="rule-hint">
        日期字段可选值来源「相对日期」（今天、本周、过去 N
        天等）：每次查候选时按当天换算；已选中的记录不会因为过了零点而作废。
      </p>
      <p v-if="allowCurrentRecord" class="rule-hint">
        {{
          currentObjectId
            ? '来源字段选「指向本对象的关联字段」时，值来源可选「当前记录」：只取关联到这条记录的来源记录。'
            : '值来源「当前记录」：先保存数据对象后才能使用。'
        }}
      </p>
    </template>
  </div>
</template>
<style scoped>
.rule-conditions {
  display: grid;
  gap: var(--spacing-sm, 8px);
}
.rule-row {
  display: flex;
  flex-wrap: wrap;
  /* 记录选择器下方可能带一行说明：其余控件按顶端对齐，不被拉高。 */
  align-items: flex-start;
  gap: var(--spacing-sm, 8px);
}
.rule-row > .ant-select,
.rule-row > .ant-input {
  min-width: 140px;
  flex: 1;
}
.rule-quick {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
.rule-and {
  width: fit-content;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 20px;
  color: var(--ant-color-primary, #1677ff);
  background: var(--primary-bg, #f0f5ff);
}
.rule-choice-problem {
  margin: 0;
  font-size: 12px;
  color: var(--ant-color-error, #ff4d4f);
}
.rule-hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
