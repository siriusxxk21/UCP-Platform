<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { FieldRules, RuleCondition } from '@/types/nocode/field-rules'
import { createDataCenterApi } from '@/api/nocode/data-center'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import {
  CURRENT_RECORD,
  LINKAGE_READ_ONLY_DEFAULT,
  MULTI_ROW_DEFAULT,
  autoUpdateBlocker,
  buildLinkage,
  conditionsError,
  createObjectCatalog,
  definitionOptions,
  emptyValueError,
  emptyValueKind,
  isLinkageIncomplete,
  linkageAutoUpdate,
  linkageReadOnly,
  linkageValueCompatible,
  loadPublishedDefinition,
  multiRowLabels,
  multiRowModesFor,
  objectOptionGroups,
  referenceTargetOf,
  roundingApplies,
  roundingCode,
  roundingOf,
  roundingOptions,
  type FieldChoiceGroup,
  type MultiRowMode,
  type PublishedDefinition,
  type RoundingMode
} from '@/nocode/field-rules'
import {
  conditionChoiceOptions,
  loadFieldChoices,
  type ConditionChoice,
  type ConditionChoiceSet
} from '@/nocode/rule-condition-choices'
import { RELATIVE_BLOCKED } from '@/nocode/relative-date'
import RuleConditionRows from './RuleConditionRows.vue'

/**
 * 数据联动：「默认值」与「选项」两处共用这一个弹层，配的是同一份 rules.linkage。
 * 弹层内是草稿，点「确定」才写回（照老系统 DataLinkageEditor）。
 * 「来源变化时自动更新」（2026-10-01 第一期）：新建联动的意向为开，存量联动没有这个键即关；
 * 确定时满足可开条件才写 autoUpdate: true，不满足就不写这个键，原因显示在开关下方。
 */
const props = defineProps<{
  field: ObjectField
  fieldOptions: FieldOptions
  referenceTarget?: string | null
  /** 当前对象 ID（已保存的对象才有）：来源字段的挑取值指向当前字段时也算类型相符。 */
  objectId?: string | null
  formGroups: FieldChoiceGroup[]
  /** 当前字段在内部明细里：明细字段暂不支持自动更新。 */
  detail?: boolean
  disabled?: boolean
}>()
const model = defineModel<FieldRules['linkage']>({ required: true })
const rounding = defineModel<FieldRules['rounding']>('rounding')

const api = createDataCenterApi(request)
const catalog = createObjectCatalog(api)
const open = ref(false),
  problem = ref(''),
  loadError = ref(''),
  loading = ref(false)
interface LinkageDraft {
  sourceObjectId: string
  conditions: RuleCondition[]
  valueFieldId: string
  multiRow: MultiRowMode | null
  readOnly: boolean
  /** 自动更新的意向；最终是否开启还要看可开条件（autoUpdateOn）。 */
  autoUpdate: boolean
  emptyValue: string
}
const emptyDraft = (): LinkageDraft => ({
  sourceObjectId: '',
  conditions: [],
  valueFieldId: '',
  multiRow: null,
  readOnly: LINKAGE_READ_ONLY_DEFAULT,
  autoUpdate: true,
  emptyValue: ''
})
const draft = ref<LinkageDraft>(emptyDraft())
const draftRounding = ref<RoundingMode>(roundingOf({ rounding: rounding.value }))
const definition = ref<PublishedDefinition | null>(null)
const labels = ref<Record<string, string>>({})
const money = computed(() => roundingApplies(props.field.type))

let generation = 0
async function loadDefinition(id: string) {
  const turn = ++generation
  definition.value = null
  loadError.value = ''
  if (!id) return
  loading.value = true
  try {
    const value = await loadPublishedDefinition(api, id)
    if (turn !== generation) return
    definition.value = value
    labels.value = { ...labels.value, [id]: value.label }
  } catch (cause) {
    if (turn === generation) loadError.value = errorMessage(cause)
  } finally {
    if (turn === generation) loading.value = false
  }
}
watch(
  () => model.value?.sourceObjectId,
  id => {
    if (!open.value) void loadDefinition(id ?? '')
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  generation++
  catalog.dispose()
})

const objectGroups = computed(() => {
  const id = draft.value.sourceObjectId
  const known = catalog.objects.value.some(item => item.value === id)
  const selected = id && !known ? [{ value: id, label: labels.value[id] ?? `对象 ${id}`, category: '已选' }] : []
  return objectOptionGroups([...selected, ...catalog.objects.value])
})
const target = computed(() => ({
  field: props.field,
  options: props.fieldOptions,
  referenceTarget: props.referenceTarget ?? null,
  objectId: props.objectId ?? null
}))
const valueFields = computed(() =>
  (definition.value?.fields ?? []).filter(
    field =>
      !!field.id &&
      linkageValueCompatible(
        target.value,
        {
          field,
          options: definitionOptions(definition.value, field),
          referenceTarget: referenceTargetOf(field, definition.value?.relations)
        },
        draft.value.sourceObjectId
      )
  )
)
const valueField = computed(() => valueFields.value.find(field => field.id === draft.value.valueFieldId))
const multiRowModes = computed(() =>
  multiRowModesFor(
    valueField.value,
    valueField.value ? definitionOptions(definition.value, valueField.value) : null,
    props.field.type
  )
)
const multiRowValue = computed(() => draft.value.multiRow ?? MULTI_ROW_DEFAULT)

/* ── 来源变化时自动更新 ── */
const relationTarget = computed(() => !!props.referenceTarget || props.field.type === FieldType.REFERENCE)
const autoUpdateInput = computed(() => ({
  readOnly: draft.value.readOnly,
  detail: !!props.detail,
  fieldType: props.field.type,
  relation: relationTarget.value,
  sourceObjectId: draft.value.sourceObjectId,
  objectId: props.objectId ?? null,
  conditions: draft.value.conditions,
  valueField: valueField.value ?? null
}))
/** 现在开不了的原因（开关置灰并显示）；能开为 null。 */
const autoUpdateBlock = computed(() => autoUpdateBlocker(autoUpdateInput.value))
const autoUpdateOn = computed(() => draft.value.autoUpdate && !autoUpdateBlock.value)
/** 条件行能否选「当前记录」：除「还没有锚点」「取值字段」之外的可开条件都满足。 */
const currentRecordAllowed = computed(
  () =>
    !autoUpdateBlocker({
      ...autoUpdateInput.value,
      conditions: [{ fieldId: '', operator: 'eq', valueSource: CURRENT_RECORD }],
      valueField: null
    })
)
const notice = ref('')
function changeReadOnly(on: boolean) {
  const current = draft.value
  if (on) {
    draft.value = { ...current, readOnly: true }
    notice.value = ''
    return
  }
  // 可手改的联动不跟随来源变化：关掉只读就同时关掉自动更新，并清掉只在自动更新下才有意义的配置。
  const cleared = [
    ...(autoUpdateOn.value ? ['已同时关闭「来源变化时自动更新」'] : []),
    ...(current.emptyValue.trim() ? ['已清掉「没有匹配记录时填入」'] : []),
    ...(current.conditions.some(condition => condition.valueSource === CURRENT_RECORD)
      ? ['已移除「当前记录」条件']
      : [])
  ]
  draft.value = {
    ...current,
    readOnly: false,
    autoUpdate: false,
    emptyValue: '',
    conditions: current.conditions.filter(condition => condition.valueSource !== CURRENT_RECORD)
  }
  notice.value = cleared.length ? `${cleared.join('，')}：可手改的字段不会跟随来源变化。` : ''
}

/* ── 没有匹配记录时填入 ── */
const emptyKind = computed(() =>
  emptyValueKind(props.field.type, relationTarget.value ? { kind: 'REFERENCE' } : null, props.fieldOptions)
)
interface TargetChoices {
  loading: boolean
  error: string
  set: ConditionChoiceSet | null
}
const targetChoices = ref<TargetChoices>({ loading: false, error: '', set: null })
let choiceTurn = 0
let dictionaryItems: Promise<Array<{ dictType: string; value: string; label: string }>> | null = null
/** 当前字段自己的选项（局部选项、公共字典、挑取值的来源字段）：显示名称，保存编码。 */
async function loadTargetChoices() {
  const turn = ++choiceTurn
  targetChoices.value = { loading: true, error: '', set: null }
  let next: TargetChoices
  try {
    const set = await loadFieldChoices(props.fieldOptions, {
      definition: id => loadPublishedDefinition(api, id),
      // 与条件行固定值、字段抽屉「挑取值」预览同一个接口：只返回启用的字典项。
      dictionary: async (type: string): Promise<ConditionChoice[]> => {
        dictionaryItems ??= request.get<Array<{ dictType: string; value: string; label: string }>>(
          '/system/dict-data/list-all-simple'
        )
        return (await dictionaryItems)
          .filter(item => item.dictType === type)
          .map(item => ({ value: item.value, label: item.label, disabled: false }))
      }
    })
    next = { loading: false, error: '', set }
  } catch (cause) {
    dictionaryItems = null
    next = { loading: false, error: errorMessage(cause), set: null }
  }
  if (turn === choiceTurn) targetChoices.value = next
}
// 摘要要显示选项名称：已配了「没有匹配记录时填入」的单选字段，不打开弹层也加载一次选项。
watch(
  () => emptyKind.value === 'choice' && !!model.value?.emptyValue,
  needed => {
    if (needed && !targetChoices.value.set && !targetChoices.value.loading) void loadTargetChoices()
  },
  { immediate: true }
)
const emptyChoiceOptions = computed(() => {
  const set = targetChoices.value.set
  if (set) return conditionChoiceOptions(set, draft.value.emptyValue)
  // 选项还没加载到（加载中或失败）：已存的值照原样回显。
  return draft.value.emptyValue
    ? [{ value: draft.value.emptyValue, label: draft.value.emptyValue, disabled: true }]
    : []
})
/** 单选选项加载失败或一条启用的选项都没有时的说明；加载中与正常时为空。不退回自由文本。 */
const emptyChoiceProblem = computed(() => {
  const state = targetChoices.value
  if (emptyKind.value !== 'choice' || state.loading) return ''
  if (state.error) return `选项加载失败：${state.error}。只能从选项中选择，请稍后重试。`
  if (!state.set) return ''
  return state.set.options.some(item => !item.disabled) ? '' : '这个字段还没有可选的选项，请先为它配置选项。'
})
const emptyValueProblem = computed(() =>
  autoUpdateOn.value && emptyKind.value ? emptyValueError(emptyKind.value, draft.value.emptyValue, props.field) : null
)
function changeEmptyValue(value: unknown) {
  draft.value = { ...draft.value, emptyValue: value == null ? '' : String(value) }
}
/** 摘要与回显用的显示名：单选显示选项名称，布尔显示是 / 否，其余原文。 */
function emptyValueLabel(value: string): string {
  if (emptyKind.value === 'choice')
    return targetChoices.value.set?.options.find(item => item.value === value)?.label ?? value
  if (emptyKind.value === 'boolean') return value === 'true' ? '是' : value === 'false' ? '否' : value
  return value
}

function show() {
  const current = model.value
  draft.value = {
    sourceObjectId: current?.sourceObjectId ?? '',
    conditions: JSON.parse(JSON.stringify(current?.conditions ?? [])),
    valueFieldId: current?.valueFieldId ?? '',
    multiRow: current?.multiRow ?? (props.field.type === FieldType.MULTI_SELECT ? 'ERROR' : null),
    readOnly: linkageReadOnly(current),
    // 新建联动的意向为开；已有联动照它存的：没有这个键就是关（存量联动不自动打开）。
    autoUpdate: current ? linkageAutoUpdate(current) : true,
    emptyValue: current?.emptyValue ?? ''
  }
  draftRounding.value = roundingOf({ rounding: rounding.value })
  problem.value = ''
  notice.value = ''
  if (emptyKind.value === 'choice') void loadTargetChoices()
  open.value = true
  void catalog.load('')
  void loadDefinition(draft.value.sourceObjectId)
}
function changeSource(id: unknown) {
  const value = typeof id === 'string' ? id : ''
  if (value === draft.value.sourceObjectId) return
  draft.value = { ...draft.value, sourceObjectId: value, conditions: [], valueFieldId: '' }
  void loadDefinition(value)
}
function changeMultiRow(value: unknown) {
  const mode = multiRowModes.value.find(item => item === value)
  if (mode) draft.value = { ...draft.value, multiRow: mode }
}
function changeValueField(id: unknown) {
  draft.value = { ...draft.value, valueFieldId: typeof id === 'string' ? id : '' }
}
watch(multiRowModes, modes => {
  // 换了取值字段后档位不再适用（例如非数值来源不能求和）时清回缺省，不静默保留；来源定义加载中不判断。
  if (!valueField.value) return
  if (draft.value.multiRow && !modes.includes(draft.value.multiRow))
    draft.value = { ...draft.value, multiRow: modes.includes(MULTI_ROW_DEFAULT) ? null : (modes.at(-1) ?? null) }
})
function scrollObjects(event: Event) {
  const element = event.target as HTMLElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 24) void catalog.load(undefined, true)
}
const CURRENT_RECORD_NEEDS_AUTO_UPDATE =
  '「当前记录」条件只在开启「来源变化时自动更新」后可用：请打开自动更新，或移除这条条件'
const isCurrentRecordCondition = (condition: RuleCondition) => condition.valueSource === CURRENT_RECORD
/**
 * 上面这句拦截提示说的是「打开自动更新，或移除这条条件」：照做之后它就过期了，立刻收起，不等下一次点「确定」
 *（否则开关已经打开，窗口顶上还挂着「请打开自动更新」）。其它拦截提示照旧，下一次点「确定」时重新判断。
 */
watch(
  () => autoUpdateOn.value || !draft.value.conditions.some(isCurrentRecordCondition),
  resolved => {
    if (resolved && problem.value === CURRENT_RECORD_NEEDS_AUTO_UPDATE) problem.value = ''
  }
)
function draftProblem(value: LinkageDraft): string {
  if (!value.sourceObjectId) return '请选择来源对象'
  if (!value.valueFieldId || !valueField.value) return '请选择要带入的来源字段'
  const conditions = conditionsError(value.conditions)
  if (conditions) return conditions
  if (!multiRowModes.value.includes(multiRowValue.value)) return '请重新选择多行匹配方式'
  if (!autoUpdateOn.value)
    return value.conditions.some(isCurrentRecordCondition) ? CURRENT_RECORD_NEEDS_AUTO_UPDATE : ''
  if (!emptyKind.value || !value.emptyValue.trim()) return ''
  if (emptyValueProblem.value) return '没有匹配记录时填入：' + emptyValueProblem.value
  // 单选只能填生效的选项编码；选项还没加载出来时不拦，发布时后端再核。
  const set = emptyKind.value === 'choice' ? targetChoices.value.set : null
  return set && !set.options.some(item => !item.disabled && item.value === value.emptyValue)
    ? '没有匹配记录时填入：请从选项中重新选择'
    : ''
}
function confirm() {
  if (props.disabled) return
  const value = draft.value
  problem.value = draftProblem(value)
  if (problem.value) return
  // 自动更新只在意向为开且满足可开条件时写出；「没有匹配记录时填入」只跟着自动更新走。
  model.value = buildLinkage({
    sourceObjectId: value.sourceObjectId,
    conditions: value.conditions,
    valueFieldId: value.valueFieldId,
    multiRow: value.multiRow,
    readOnly: value.readOnly,
    autoUpdate: autoUpdateOn.value,
    emptyValue: autoUpdateOn.value && emptyKind.value ? value.emptyValue : ''
  })
  if (money.value) rounding.value = roundingCode(draftRounding.value)
  open.value = false
}
const summary = computed(() => {
  const current = model.value
  if (!current) return null
  const valueName =
    definition.value?.objectId === current.sourceObjectId
      ? definition.value.fields.find(field => field.id === current.valueFieldId)?.name
      : undefined
  return {
    source: labels.value[current.sourceObjectId] ?? `对象 ${current.sourceObjectId}`,
    conditions: current.conditions.length,
    value: valueName ?? current.valueFieldId,
    multiRow: multiRowLabels[current.multiRow ?? MULTI_ROW_DEFAULT]?.label ?? `「${current.multiRow}」无效，请重选`,
    readOnly: linkageReadOnly(current),
    autoUpdate: linkageAutoUpdate(current),
    emptyValue: linkageAutoUpdate(current) && current.emptyValue ? emptyValueLabel(current.emptyValue) : '',
    rounding: roundingOptions.find(item => item.value === roundingOf({ rounding: rounding.value }))?.label
  }
})
defineExpose({ show })
</script>
<template>
  <div class="data-linkage">
    <div v-if="summary" class="linkage-summary">
      <div>来源对象：{{ summary.source }}</div>
      <div>条件：{{ summary.conditions ? `${summary.conditions} 条（之间为「且」）` : '未设置，命中全部行' }}</div>
      <div>联动：{{ field.name || '当前字段' }} 自动取「{{ summary.value }}」的值</div>
      <div>
        多行匹配：{{ summary.multiRow }} · 当前字段只读：{{ summary.readOnly ? '开' : '关' }} · 自动更新：{{
          summary.autoUpdate ? '开' : '关'
        }}
        <template v-if="summary.emptyValue">· 没有匹配时填：{{ summary.emptyValue }}</template>
        <template v-if="money">· 取整方式：{{ summary.rounding }}</template>
      </div>
    </div>
    <p v-else class="linkage-hint">还没有配置数据联动。</p>
    <a-alert v-if="isLinkageIncomplete(model)" type="warning" show-icon message="数据联动配置不完整，请重新设置。" />
    <a-button block :disabled="disabled" @click="show">数据联动设置</a-button>
    <a-modal
      :open="open"
      title="数据联动设置"
      :width="880"
      ok-text="确定"
      cancel-text="取消"
      destroy-on-close
      @ok="confirm"
      @cancel="open = false"
    >
      <div class="linkage-body">
        <a-alert v-if="problem" type="error" show-icon :message="problem" />
        <a-alert v-if="notice" type="info" show-icon :message="notice" />
        <a-form layout="vertical" :disabled="disabled">
          <a-form-item label="来源对象" required>
            <a-select
              :value="draft.sourceObjectId || undefined"
              show-search
              :filter-option="false"
              :loading="catalog.loading.value"
              :options="objectGroups"
              placeholder="选择已发布的数据对象（按分类分组）"
              aria-label="来源对象"
              @search="catalog.load($event)"
              @popup-scroll="scrollObjects"
              @update:value="changeSource"
            />
            <a-alert v-if="catalog.error.value" type="error" show-icon :message="catalog.error.value" />
            <a-alert v-if="loadError" type="error" show-icon :message="loadError" />
          </a-form-item>
          <a-form-item label="满足以下条件时">
            <RuleConditionRows
              v-model="draft.conditions"
              :definition="definition"
              :form-groups="formGroups"
              :disabled="disabled"
              :current-object-id="objectId"
              :allow-current-record="currentRecordAllowed"
              :relative-blocked="RELATIVE_BLOCKED.linkage"
              record-key
              empty-text="未设置条件：将命中来源对象全部行（合法配置，命中多行时按下方「多行匹配」落成一个值）。"
            />
          </a-form-item>
          <a-form-item label="触发以下联动" required>
            <div class="linkage-target">
              <a-input :value="field.name || '当前字段'" disabled aria-label="当前字段（不可改）" />
              <span>自动取</span>
              <a-select
                :value="draft.valueFieldId || undefined"
                :loading="loading"
                :disabled="disabled || !definition"
                :options="valueFields.map(item => ({ value: item.id!, label: item.name || item.code }))"
                show-search
                option-filter-prop="label"
                placeholder="来源字段"
                not-found-content="来源对象里没有类型相符的字段"
                aria-label="联动取值字段"
                @update:value="changeValueField"
              />
              <span>的值</span>
            </div>
            <p v-if="definition && !valueFields.length" class="linkage-hint">
              来源字段须与当前字段类型一致（数值对数值）；单行文本可取文本、自动编号、链接和结果为文本的公式，多行文本另可取多行文本；关联字段须指向同一对象；选项类字段须与本字段选择身份一致（同一公共字典），或一方的候选来源为「挑取值」并指向另一方。
            </p>
          </a-form-item>
          <a-form-item label="多行匹配">
            <a-radio-group :value="multiRowValue" class="linkage-choices" @update:value="changeMultiRow">
              <a-radio v-for="mode in multiRowModes" :key="mode" :value="mode">
                {{ multiRowLabels[mode].label }}
                <span class="linkage-hint">{{ multiRowLabels[mode].hint }}</span>
              </a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="money" label="取整方式">
            <a-radio-group v-model:value="draftRounding" class="linkage-choices" aria-label="取整方式">
              <a-radio v-for="item in roundingOptions" :key="item.value" :value="item.value">
                {{ item.label }}
                <span class="linkage-hint">{{ item.example }}</span>
              </a-radio>
            </a-radio-group>
            <p class="linkage-hint">金额按日元存整数；带小数的结果按这里取整，缺省向下取整。</p>
          </a-form-item>
          <a-form-item label="当前字段只读（默认开启）">
            <a-switch
              :checked="draft.readOnly"
              aria-label="当前字段只读（默认开启）"
              @update:checked="changeReadOnly(!!$event)"
            />
            <p class="linkage-hint">
              值只能由联动带出；没取到值时字段为空且不能填写。关闭后联动只带出建议值，可手动修改。保存时服务端会重算并以重算结果为准。
            </p>
          </a-form-item>
          <a-form-item label="来源变化时自动更新">
            <a-switch
              :checked="autoUpdateOn"
              :disabled="disabled || !!autoUpdateBlock"
              aria-label="来源变化时自动更新"
              @update:checked="draft = { ...draft, autoUpdate: !!$event }"
            />
            <p class="linkage-hint">
              来源记录新增、修改、删除后，系统自动重算并保存这个字段，可用于筛选、排序和统计。配置在发布数据对象、并到应用里同步对象版本后生效。
            </p>
            <p v-if="autoUpdateBlock" class="linkage-blocked" role="note">现在不能开启：{{ autoUpdateBlock }}</p>
          </a-form-item>
          <a-form-item v-if="autoUpdateOn && emptyKind" label="没有匹配记录时填入">
            <template v-if="emptyKind === 'choice'">
              <a-select
                :value="draft.emptyValue || undefined"
                :loading="targetChoices.loading"
                :status="emptyChoiceProblem ? 'error' : undefined"
                :options="emptyChoiceOptions"
                allow-clear
                show-search
                option-filter-prop="label"
                placeholder="不填：没有匹配记录时这个字段为空"
                not-found-content="没有可选的选项"
                aria-label="没有匹配记录时填入"
                class="linkage-empty"
                @update:value="changeEmptyValue"
              />
              <p v-if="emptyChoiceProblem" class="linkage-blocked" role="alert">{{ emptyChoiceProblem }}</p>
            </template>
            <a-radio-group
              v-else-if="emptyKind === 'boolean'"
              :value="draft.emptyValue"
              aria-label="没有匹配记录时填入"
              @update:value="changeEmptyValue"
            >
              <a-radio value="">不填</a-radio>
              <a-radio value="true">是</a-radio>
              <a-radio value="false">否</a-radio>
            </a-radio-group>
            <a-input
              v-else
              :value="draft.emptyValue"
              :status="emptyValueProblem ? 'error' : undefined"
              :inputmode="emptyKind === 'text' ? undefined : emptyKind === 'decimal' ? 'decimal' : 'numeric'"
              :placeholder="
                emptyKind === 'money'
                  ? '不填：没有匹配记录时为空；金额请填整数'
                  : emptyKind === 'text'
                    ? '不填：没有匹配记录时这个字段为空'
                    : '不填：没有匹配记录时为空；请填数字'
              "
              aria-label="没有匹配记录时填入"
              class="linkage-empty"
              @update:value="changeEmptyValue"
            />
            <p v-if="emptyValueProblem" class="linkage-blocked" role="alert">{{ emptyValueProblem }}</p>
            <p class="linkage-hint">来源对象里没有匹配的记录时，系统把这个值填进字段；不填则为空。</p>
          </a-form-item>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>
<style scoped>
.data-linkage {
  display: grid;
  gap: var(--spacing-sm, 8px);
}
.linkage-summary {
  font-size: 13px;
  line-height: 1.8;
}
.linkage-body {
  display: grid;
  gap: 12px;
}
.linkage-target {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.linkage-target > .ant-input,
.linkage-target > .ant-select {
  width: 230px;
}
.linkage-choices {
  display: grid;
  gap: 6px;
}
.linkage-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}
.linkage-blocked {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ant-color-warning, #d48806);
}
.linkage-empty {
  max-width: 320px;
}
</style>
