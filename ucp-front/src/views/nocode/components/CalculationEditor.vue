<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CalculationOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { createDataCenterApi } from '@/api/nocode/data-center'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import { createRequestSession } from '@/nocode/request-session'
import { relationForField } from '@/nocode/relation-editing'
import { FieldType, RelationType } from '@/types/nocode/enums'
import { calculationCategories, calculationCategory, type CalculationCategory } from '@/nocode/calculation-presentation'
import { RELATIVE_BLOCKED, isRelativeDate, relativeDateError, relativeDateField } from '@/nocode/relative-date'
import RelativeDateValue from './RelativeDateValue.vue'
const props = defineProps<{ fields: ObjectField[]; relations: ObjectRelation[]; disabled?: boolean }>()
const model = defineModel<CalculationOptions | null>()
const emit = defineEmits<{ 'result-type': ['INTEGER' | 'DECIMAL'] }>()
const api = createDataCenterApi(request)
const objects = ref<{ value: string; label: string; version: number }[]>([])
const selectedObjects = ref<Record<string, { value: string; label: string; version: number }>>({})
const objectLoading = ref(false),
  objectError = ref(''),
  objectSearch = ref(''),
  objectPage = ref(0),
  objectMore = ref(false)
const objectSession = createRequestSession()
const objectOptions = computed(() => {
  const id = model.value?.targetObjectId
  const selected = id ? selectedObjects.value[id] || { value: id, label: `对象 ${id}`, version: 0 } : undefined
  return [...new Map([...(selected ? [selected] : []), ...objects.value].map(o => [o.value, o])).values()]
})
async function loadObjects(search = objectSearch.value, append = false) {
  if (append && (objectLoading.value || !objectMore.value)) return
  const current = objectSession.begin()
  const page = append ? objectPage.value + 1 : 1
  objectSearch.value = search
  if (!append) objects.value = []
  objectLoading.value = true
  objectError.value = ''
  try {
    const result = await api.objects({ pageNo: page, pageSize: 100, name: search })
    if (!current()) return
    const candidates = result.list
      .filter(o => o.publishedVersion)
      .map(o => ({ value: o.id, label: `${o.objectName} · ${o.objectCode}`, version: o.publishedVersion! }))
    objects.value = append
      ? [...new Map([...objects.value, ...candidates].map(o => [o.value, o])).values()]
      : candidates
    candidates.forEach(o => {
      selectedObjects.value[o.value] = o
    })
    objectPage.value = page
    objectMore.value = result.total > page * 100
  } catch (e) {
    if (current()) objectError.value = errorMessage(e)
  } finally {
    if (current()) objectLoading.value = false
  }
}
function scrollObjects(event: Event) {
  const target = event.target as HTMLElement
  if (target.scrollTop + target.clientHeight >= target.scrollHeight - 24) void loadObjects(objectSearch.value, true)
}
const targetFields = ref<ObjectField[]>([]),
  error = ref(''),
  loading = ref(false)
const mode = computed(() => model.value?.mode || 'GENERATED')
const category = computed(() => calculationCategory(model.value))
const sequenceVariant = computed(() =>
  mode.value === 'RUNNING_TOTAL' ? 'RUNNING_TOTAL' : model.value?.sequence?.operation || 'ADJACENT'
)
const sourceOptions = computed(() => [
  { label: '关联记录', value: 'RELATION' },
  { label: '条件查询', value: 'LOOKUP' },
  ...(category.value === 'AGGREGATE' ? [{ label: '本对象记录', value: 'STATISTICS' }] : [])
])
const wholeTable = computed(() => ['STATISTICS', 'RUNNING_TOTAL', 'SEQUENCE'].includes(mode.value))
const running = computed(() => mode.value === 'RUNNING_TOTAL')
const sequence = computed(() => mode.value === 'SEQUENCE')
const baseFields = computed(() => props.fields.filter(f => !['FORMULA', 'SUMMARY'].includes(f.type)))
const numericTypes: readonly FieldType[] = [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT]
const orderTypes: readonly FieldType[] = [...numericTypes, FieldType.DATE, FieldType.DATETIME, FieldType.TIME]
const groupTypes: readonly FieldType[] = [
  ...orderTypes,
  FieldType.TEXT,
  FieldType.TEXTAREA,
  FieldType.SELECT,
  FieldType.BOOLEAN,
  FieldType.UUID,
  FieldType.REFERENCE,
  FieldType.USER,
  FieldType.DEPARTMENT,
  FieldType.ORGANIZATION,
  FieldType.POST,
  FieldType.USER_GROUP,
  FieldType.AUTO_NUMBER
]
const singleFields = computed(() =>
  props.fields.filter(f => relationForField(f, props.relations)?.kind !== RelationType.MANY_TO_MANY)
)
const numericFields = computed(() => singleFields.value.filter(f => numericTypes.includes(f.type)))
const orderFields = computed(() => singleFields.value.filter(f => orderTypes.includes(f.type)))
const tieBreakerFields = computed(() =>
  singleFields.value.filter(f =>
    [...orderTypes, FieldType.AUTO_NUMBER, FieldType.TEXT, FieldType.UUID].includes(f.type)
  )
)
const groupFields = computed(() => singleFields.value.filter(f => groupTypes.includes(f.type)))
const fieldOptions = (fields: ObjectField[]) => fields.map(f => ({ value: f.code, label: `${f.name}（${f.code}）` }))
const groupOptions = computed(() =>
  fieldOptions(groupFields.value).map(option => ({
    ...option,
    disabled: (model.value?.groupFields?.length ?? 0) >= 5 && !model.value?.groupFields?.includes(option.value)
  }))
)
const effectiveTarget = computed(() =>
  wholeTable.value
    ? null
    : model.value?.mode === 'RELATION'
      ? props.relations.find(r => r.id === model.value?.relationId)?.targetObjectId
      : model.value?.targetObjectId
)
const targets = computed(() => (effectiveTarget.value ? targetFields.value : props.fields))
const valueFields = computed(() =>
  wholeTable.value
    ? numericFields.value
    : targets.value.filter(
        f => !['SUMMARY', 'URL', 'MULTI_SELECT', 'IMAGE', 'ATTACHMENT', 'REGION', 'CASCADE'].includes(f.type)
      )
)
const conditionFields = computed(() =>
  targets.value.filter(
    f => !['FORMULA', 'SUMMARY', 'URL', 'IMAGE', 'ATTACHMENT', 'REGION', 'CASCADE', 'RICH_TEXT'].includes(f.type)
  )
)
/** 匹配条件的来源字段是日期 / 日期时间时，固定值可选相对日期（只在「读取时计算」可用）。 */
const conditionDate = (code: string | null | undefined) =>
  relativeDateField(targets.value.find(f => f.code === code)?.type)
const aggregateOptions = computed(() =>
  category.value === 'LOOKUP'
    ? [{ label: '唯一取值（多条时报错）', value: 'SINGLE' }]
    : [
        { label: '记录数', value: 'COUNT' },
        { label: '求和', value: 'SUM' },
        { label: '平均值', value: 'AVG' },
        { label: '最小值', value: 'MIN' },
        { label: '最大值', value: 'MAX' }
      ]
)
type RunningTotal = NonNullable<CalculationOptions['runningTotal']>
const defaultRunningTotal = (): RunningTotal => ({
  orderField: '',
  tieBreakerField: null,
  subtractField: null,
  initialValue: '0',
  initialField: null
})
const runningTotal = computed(() => model.value?.runningTotal ?? defaultRunningTotal())
const defaultSequence = (): NonNullable<CalculationOptions['sequence']> => ({
  orderField: '',
  tieBreakerField: null,
  direction: 'PREVIOUS',
  operation: 'ADJACENT',
  initialValue: '0'
})
const sequenceRule = computed(() => model.value?.sequence ?? defaultSequence())
const cumulative = computed(() => sequence.value && sequenceRule.value.operation === 'CUMULATIVE')
const initialSource = computed(() => (runningTotal.value.initialField !== null ? 'FIELD' : 'FIXED'))
function patch(value: Partial<CalculationOptions>) {
  if (props.disabled || !model.value) return
  model.value = { ...model.value, ...value }
}
function patchRunning(value: Partial<RunningTotal>) {
  if (!running.value) return
  patch({ runningTotal: { ...runningTotal.value, ...value } })
}
function patchSequence(value: Partial<NonNullable<CalculationOptions['sequence']>>) {
  if (!sequence.value) return
  patch({ sequence: { ...sequenceRule.value, ...value } })
}
function changeInitialSource(value: string) {
  // 空字段占位保留“首笔字段”选项；切换时不保留另一来源，避免初始值被重复计入。
  patchRunning(value === 'FIELD' ? { initialField: '', initialValue: null } : { initialField: null, initialValue: '0' })
}
function changeGroups(values: string[]) {
  if (values.length > 5 || new Set(values).size !== values.length) return
  if (values.some(code => !groupFields.value.some(field => field.code === code))) return
  patch({ groupFields: values })
}
function changeAggregate(value: CalculationOptions['aggregate']) {
  if (props.disabled || !model.value || running.value) return
  if (wholeTable.value) {
    if (value === 'SINGLE') return
    patch({ aggregate: value, ...(value === 'COUNT' ? { targetField: null } : {}) })
    emit('result-type', value === 'COUNT' ? 'INTEGER' : 'DECIMAL')
  } else patch({ aggregate: value })
}
function resetTarget() {
  patch({ targetField: null, conditions: [] })
}
const validated = ref(false)
function defaultCalculation(value: CalculationOptions['mode']): CalculationOptions {
  const full = ['STATISTICS', 'RUNNING_TOTAL', 'SEQUENCE'].includes(value)
  // 旧三种模式不追加可选属性，继续保存原 JSON 形状。
  return {
    mode: value,
    updateMode: ['RUNNING_TOTAL', 'SEQUENCE'].includes(value) ? 'ON_SAVE' : 'LIVE',
    targetObjectId: null,
    relationId: null,
    targetField: null,
    aggregate: full ? 'SUM' : 'SINGLE',
    logic: 'AND',
    conditions: [],
    excludeCurrent: false,
    ...(full
      ? {
          groupFields: [],
          runningTotal: value === 'RUNNING_TOTAL' ? defaultRunningTotal() : null,
          ...(value === 'SEQUENCE' ? { sequence: defaultSequence() } : {})
        }
      : {})
  }
}
function changeMode(value: string) {
  if (props.disabled || value === mode.value) return
  validated.value = false
  if (value === 'GENERATED') {
    model.value = null
    return
  }
  if (!['LOCAL', 'RELATION', 'LOOKUP', 'STATISTICS', 'RUNNING_TOTAL', 'SEQUENCE'].includes(value)) return
  model.value = defaultCalculation(value as CalculationOptions['mode'])
  if (['STATISTICS', 'RUNNING_TOTAL', 'SEQUENCE'].includes(value)) emit('result-type', 'DECIMAL')
}
function changeCategory(value: CalculationCategory) {
  if (props.disabled || value === category.value) return
  if (value === 'FORMULA') changeMode('GENERATED')
  else if (value === 'SEQUENCE') changeMode('SEQUENCE')
  else if (model.value && ['RELATION', 'LOOKUP'].includes(mode.value)) {
    // 查找与汇总共用同一个来源，只修改结果处理，保留原条件及更新方式。
    patch({ aggregate: value === 'LOOKUP' ? 'SINGLE' : 'SUM' })
    if (value === 'AGGREGATE') emit('result-type', 'DECIMAL')
  } else if (value === 'LOOKUP' && model.value?.mode === 'STATISTICS') {
    const source = model.value
    validated.value = false
    model.value = {
      ...defaultCalculation('LOOKUP'),
      targetField: source.targetField,
      conditions: source.conditions,
      logic: source.logic,
      excludeCurrent: source.excludeCurrent,
      updateMode: source.updateMode
    }
  } else changeMode(value === 'LOOKUP' ? 'LOOKUP' : 'STATISTICS')
}
function changeSource(value: string) {
  if (props.disabled || value === mode.value || !sourceOptions.value.some(item => item.value === value)) return
  const aggregate = model.value?.aggregate ?? 'SINGLE'
  const updateMode = model.value?.updateMode ?? 'LIVE'
  validated.value = false
  model.value = { ...defaultCalculation(value as CalculationOptions['mode']), aggregate, updateMode }
}
function changeSequenceVariant(value: string) {
  if (props.disabled || value === sequenceVariant.value) return
  if (value === 'RUNNING_TOTAL') changeMode('RUNNING_TOTAL')
  else if (['ADJACENT', 'CUMULATIVE'].includes(value)) {
    const operation = value as 'ADJACENT' | 'CUMULATIVE'
    if (sequence.value) patchSequence({ operation, direction: 'PREVIOUS' })
    else {
      validated.value = false
      model.value = { ...defaultCalculation('SEQUENCE'), sequence: { ...defaultSequence(), operation } }
      emit('result-type', 'DECIMAL')
    }
  }
}
function configurationError(): string {
  const value = model.value
  if (!value || !wholeTable.value) return ''
  if (value.targetObjectId || value.relationId) return '本对象汇总和顺序计算仅支持当前对象'
  const groups = value.groupFields ?? []
  if (groups.length > 5 || new Set(groups).size !== groups.length) return '分组字段最多 5 个且不能重复'
  if (groups.some(code => !groupFields.value.some(f => f.code === code))) return '分组字段须为当前对象的基本单值字段'
  if (sequence.value) {
    const rule = value.sequence
    if (value.conditions.length || value.excludeCurrent) return '通用顺序计算不配置筛选或排除本记录'
    if (!rule || !orderFields.value.some(f => f.code === rule.orderField)) return '请选择有效的顺序字段'
    if (rule.tieBreakerField && !tieBreakerFields.value.some(f => f.code === rule.tieBreakerField))
      return '请选择可排序的基本类型同序字段'
    if (!['ADJACENT', 'CUMULATIVE'].includes(rule.operation ?? 'ADJACENT')) return '请选择有效的顺序计算方式'
    if (!['PREVIOUS', 'NEXT'].includes(rule.direction)) return '请选择有效的相邻方向'
    if (cumulative.value && rule.direction !== 'PREVIOUS') return '顺序累计仅支持从首笔累计到当前记录'
    if (cumulative.value && !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(rule.initialValue ?? '0'))
      return '初始值须为有效数字'
    return ''
  }
  if (!['COUNT', 'SUM', 'AVG', 'MIN', 'MAX'].includes(value.aggregate)) return '全表统计不支持唯一取值'
  const numericField = (code: string | null | undefined) => numericFields.value.some(f => f.code === code)
  if (value.aggregate !== 'COUNT' && !numericField(value.targetField))
    return running.value ? '请选择基本数值类型的增加值字段' : '请选择基本数值类型的取值字段'
  if (value.aggregate === 'COUNT' && value.targetField) return '计数不需要取值字段，请清除后重试'
  if (value.conditions.length > 20) return '匹配条件最多 20 个'
  for (const condition of value.conditions) {
    if (running.value && condition.localField) return '增减值累计的匹配条件仅支持固定值'
    if (!conditionFields.value.some(f => f.code === condition.targetField)) return '请选择有效的匹配条件来源字段'
    if (!['eq', 'neq', 'gt', 'lt'].includes(condition.operator)) return '请选择有效的匹配条件运算符'
    if (condition.localField) {
      if (!baseFields.value.some(f => f.code === condition.localField)) return '请选择有效的匹配条件本行字段'
    } else if (condition.value === null || condition.value === undefined || condition.value === '')
      return '请填写匹配条件固定值'
    else if (isRelativeDate(condition.value)) {
      // 相对日期只在「读取时计算」里成立：保存时计算的结果是快照，过了零点不会自己变。
      if (value.updateMode !== 'LIVE') return RELATIVE_BLOCKED.onSave
      const error = relativeDateError(condition.value)
      if (error) return error
    }
  }
  if (!running.value) return ''
  if (value.aggregate !== 'SUM' || value.excludeCurrent) return '增减值累计固定为求和，并包含本记录'
  const rule = value.runningTotal
  if (!rule || !orderFields.value.some(f => f.code === rule.orderField)) return '请选择有效的累计顺序字段'
  if (rule.tieBreakerField && !tieBreakerFields.value.some(f => f.code === rule.tieBreakerField))
    return '请选择可排序的基本类型同序字段'
  if (rule.subtractField && !numericField(rule.subtractField)) return '减少值字段须为基本数值类型'
  if (rule.initialField !== null) {
    if (rule.initialValue !== null) return '固定初始值与首条记录字段只能选择一种'
    if (!numericField(rule.initialField)) return '请选择基本数值类型的首条初始值字段'
  } else if (typeof rule.initialValue !== 'string' || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(rule.initialValue))
    return '初始值须为有效数字，未设置时请填写 0'
  return ''
}
const validationError = computed(() => (validated.value ? configurationError() : ''))
function validate() {
  validated.value = true
  return configurationError()
}
defineExpose({ validate })
let generation = 0
watch(
  effectiveTarget,
  async id => {
    const turn = ++generation
    targetFields.value = []
    error.value = ''
    loading.value = false
    if (!id) return
    loading.value = true
    try {
      const design = await api.design(id)
      if (turn !== generation) return
      if (!design.publishedVersion) throw new Error('来源对象须先发布')
      selectedObjects.value[id] = {
        value: id,
        label: `${design.draft.objectName} · ${design.draft.objectCode}`,
        version: design.publishedVersion
      }
      const version = (await api.version(id, design.publishedVersion)) as any
      if (turn === generation) targetFields.value = (version.definition || version).fields || []
    } catch (e) {
      if (turn === generation) error.value = errorMessage(e)
    } finally {
      if (turn === generation) loading.value = false
    }
  },
  { immediate: true }
)
onMounted(() => void loadObjects())
onBeforeUnmount(() => {
  generation++
  objectSession.invalidate()
})
</script>
<template>
  <div class="calculation-editor">
    <a-form-item label="计算方式">
      <a-select :value="category" :disabled="disabled" :options="calculationCategories" @change="changeCategory" />
    </a-form-item>
    <a-form-item v-if="category === 'FORMULA'" label="参与字段">
      <a-radio-group :value="mode" :disabled="disabled" @update:value="changeMode">
        <a-radio value="GENERATED">仅基础字段</a-radio>
        <a-radio value="LOCAL">包含计算结果</a-radio>
      </a-radio-group>
      <p v-if="!model" class="hint">
        引用本条记录的基础字段，保存记录时自动更新，可用于筛选、排序和统计。需要引用公式或明细汇总结果时，选择“包含计算结果”。
      </p>
      <p v-else class="hint">
        可引用本条记录的基础字段、公式及明细汇总结果。依赖字段的读取权限继续生效；更新和查询行为由下方更新方式决定。
      </p>
    </a-form-item>
    <a-form-item v-else-if="category === 'LOOKUP' || category === 'AGGREGATE'" label="数据来源">
      <a-select :value="mode" :options="sourceOptions" :disabled="disabled" @change="changeSource" />
    </a-form-item>
    <a-form-item v-else-if="category === 'SEQUENCE'" label="顺序计算方式" required>
      <a-select
        :value="sequenceVariant"
        :disabled="disabled"
        :options="[
          { value: 'ADJACENT', label: '相邻记录取值' },
          { value: 'CUMULATIVE', label: '逐笔计算后累计' },
          { value: 'RUNNING_TOTAL', label: '增减值累计' }
        ]"
        @change="changeSequenceVariant"
      />
    </a-form-item>
    <template v-if="model">
      <a-alert v-if="validationError" type="error" :message="validationError" show-icon />
      <a-form-item label="更新方式">
        <a-radio-group v-model:value="model.updateMode" :disabled="disabled">
          <a-radio value="ON_SAVE">{{ running || sequence ? '保存时落库，同组联动' : '本记录保存时重算' }}</a-radio>
          <a-radio value="LIVE">读取时计算</a-radio>
        </a-radio-group>
        <p v-if="(running || sequence) && model.updateMode === 'ON_SAVE'" class="hint">
          保存或删除记录后，同组受影响结果在同一事务中更新。历史数据须先校准，就绪后可筛选、排序和统计；校准期间相关写入暂停。
        </p>
        <p v-else-if="model.updateMode === 'LIVE'" class="hint">
          结果在读取时计算，不写入结果列，不能用于筛选、排序和统计。历史记录增删改后按当前数据重新计算。
        </p>
        <p v-else class="hint">
          只在本记录保存时重算并写入结果，来源变化不会联动更新其他记录。这是保存快照，不等同于业务确认留存。
        </p>
        <p v-if="running || sequence" class="hint">需要业务时点留存时，使用独立的业务动作和普通字段。</p>
      </a-form-item>
      <template v-if="model.mode !== 'LOCAL'">
        <a-form-item v-if="model.mode === 'RELATION'" label="已有关系" required>
          <a-select
            v-model:value="model.relationId"
            :disabled="disabled"
            :options="relations.filter(r => r.id).map(r => ({ value: r.id!, label: r.name }))"
            placeholder="先保存关系，再配置取值"
            @change="resetTarget"
          />
        </a-form-item>
        <a-form-item v-else-if="model.mode === 'LOOKUP'" label="来源对象">
          <a-select
            v-model:value="model.targetObjectId"
            allow-clear
            show-search
            :filter-option="false"
            :loading="objectLoading"
            :disabled="disabled"
            :options="objectOptions"
            placeholder="本表"
            @search="loadObjects($event)"
            @popup-scroll="scrollObjects"
            @change="resetTarget"
          />
        </a-form-item>
        <a-alert v-if="model.mode === 'LOOKUP' && objectError" type="error" :message="objectError" show-icon>
          <template #action><a-button size="small" @click="loadObjects()">重新加载对象</a-button></template>
        </a-alert>
        <a-button
          v-if="model.mode === 'LOOKUP' && objectMore"
          size="small"
          :loading="objectLoading"
          :disabled="disabled"
          @click="loadObjects(objectSearch, true)"
        >
          加载更多来源对象
        </a-button>
        <a-alert v-if="error" type="error" :message="error" show-icon />
        <a-form-item v-if="wholeTable" label="分组字段">
          <a-select
            :value="model.groupFields || []"
            mode="multiple"
            allow-clear
            :disabled="disabled"
            :options="groupOptions"
            placeholder="不选即整表，最多 5 个字段"
            @update:value="changeGroups"
          />
          <p class="hint">
            仅计算当前对象。按本记录的相同字段值分组；只能选择基本单值字段，不能选择公式、汇总或多值字段。
          </p>
        </a-form-item>
        <template v-if="sequence">
          <a-form-item v-if="!cumulative" label="相邻记录" required>
            <a-select
              :value="sequenceRule.direction"
              :disabled="disabled"
              :options="[
                { value: 'PREVIOUS', label: '上一条记录' },
                { value: 'NEXT', label: '下一条记录' }
              ]"
              @update:value="patchSequence({ direction: $event })"
            />
          </a-form-item>
          <a-form-item label="顺序字段" required>
            <a-select
              :value="sequenceRule.orderField || undefined"
              :disabled="disabled"
              :options="fieldOptions(orderFields)"
              @update:value="patchSequence({ orderField: $event })"
            />
          </a-form-item>
          <a-form-item label="同序字段">
            <a-select
              :value="sequenceRule.tieBreakerField"
              allow-clear
              :disabled="disabled"
              :options="fieldOptions(tieBreakerFields)"
              @update:value="patchSequence({ tieBreakerField: $event ?? null })"
            />
            <p class="hint">均按升序排列，空值最后；同序最终按记录 ID 保证稳定。分页、筛选和界面排序不改变计算范围。</p>
          </a-form-item>
          <a-form-item v-if="cumulative" label="固定初始值" required>
            <a-input-number
              :value="sequenceRule.initialValue ?? '0'"
              string-mode
              :disabled="disabled"
              @update:value="patchSequence({ initialValue: $event == null ? '' : String($event) })"
            />
            <p class="hint">
              每组从初始值开始，加上各笔公式结果，累计到当前记录。逐笔公式可引用本行基础字段及本行公式；空值贡献按 0
              计入。
            </p>
          </a-form-item>
          <p v-else class="hint">
            表达式中选择“相邻记录”字段参与计算；组内第一条的上一条、最后一条的下一条为空，可使用 coalesce 配置备用值。
          </p>
        </template>
        <a-form-item v-if="!sequence" label="结果处理" required>
          <a-select
            :value="model.aggregate"
            :disabled="disabled || running"
            :options="aggregateOptions"
            @update:value="changeAggregate"
          />
        </a-form-item>
        <a-form-item
          v-if="!sequence && model.aggregate !== 'COUNT'"
          :label="running ? '增加值字段' : '取值字段'"
          required
        >
          <a-select
            :value="model.targetField"
            :loading="loading"
            :disabled="disabled"
            :options="fieldOptions(valueFields)"
            @update:value="patch({ targetField: $event })"
          />
        </a-form-item>
        <template v-if="running">
          <a-form-item label="减少值字段">
            <a-select
              :value="runningTotal.subtractField"
              allow-clear
              :disabled="disabled"
              :options="fieldOptions(numericFields)"
              placeholder="可不选，仅累计增加值"
              @update:value="patchRunning({ subtractField: $event ?? null })"
            />
          </a-form-item>
          <a-form-item label="累计顺序字段" required>
            <a-select
              :value="runningTotal.orderField || undefined"
              :disabled="disabled"
              :options="fieldOptions(orderFields)"
              placeholder="选择日期、时间或基本数值字段"
              @update:value="patchRunning({ orderField: $event })"
            />
          </a-form-item>
          <a-form-item label="同序字段">
            <a-select
              :value="runningTotal.tieBreakerField"
              allow-clear
              :disabled="disabled"
              :options="fieldOptions(tieBreakerFields)"
              placeholder="累计顺序相同时，按此字段排序"
              @update:value="patchRunning({ tieBreakerField: $event ?? null })"
            />
            <p class="hint">均升序、空值最后，最终自动追加记录唯一 ID 确保顺序稳定；分页和界面排序不影响累计结果。</p>
          </a-form-item>
          <a-form-item label="初始值来源" required>
            <a-radio-group :value="initialSource" :disabled="disabled" @update:value="changeInitialSource">
              <a-radio value="FIXED">固定初始值</a-radio>
              <a-radio value="FIELD">本组第一条记录的字段</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="initialSource === 'FIXED'" label="固定初始值" required>
            <a-input-number
              :value="runningTotal.initialValue"
              string-mode
              :disabled="disabled"
              placeholder="默认 0"
              @update:value="patchRunning({ initialValue: $event == null ? null : String($event), initialField: null })"
            />
          </a-form-item>
          <a-form-item v-else label="首条初始值字段" required>
            <a-select
              :value="runningTotal.initialField || undefined"
              :disabled="disabled"
              :options="fieldOptions(numericFields)"
              placeholder="取本组第一条记录的字段值"
              @update:value="patchRunning({ initialField: $event, initialValue: null })"
            />
          </a-form-item>
          <p class="hint">
            累计结果 = 上一条累计结果 + 本条增加值 − 本条减少值；数值空值按 0 处理，每组初始值只加一次。
          </p>
        </template>
        <a-form-item v-if="!sequence" label="匹配条件" :required="model.mode === 'LOOKUP'">
          <a-select
            v-model:value="model.logic"
            :disabled="disabled"
            :options="[
              { label: '全部满足', value: 'AND' },
              { label: '任一满足', value: 'OR' }
            ]"
          />
          <div v-for="(item, index) in model.conditions" :key="index" class="match-row">
            <a-select
              v-model:value="item.targetField"
              :disabled="disabled"
              :options="conditionFields.map(f => ({ value: f.code, label: f.name }))"
              placeholder="来源字段"
            />
            <a-select
              v-model:value="item.operator"
              :disabled="disabled"
              :options="[
                { label: '等于', value: 'eq' },
                { label: '不等于', value: 'neq' },
                { label: '大于', value: 'gt' },
                { label: '小于', value: 'lt' }
              ]"
            />
            <a-select
              v-if="!running"
              v-model:value="item.localField"
              allow-clear
              :disabled="disabled"
              :options="baseFields.map(f => ({ value: f.code, label: '本行 · ' + f.name }))"
              placeholder="固定值"
              @change="item.value = null"
            />
            <a-select
              v-if="!item.localField && targets.find(f => f.code === item.targetField)?.type === 'BOOLEAN'"
              :value="item.value === true ? 'true' : item.value === false ? 'false' : undefined"
              :disabled="disabled"
              :options="[
                { value: 'true', label: '是' },
                { value: 'false', label: '否' }
              ]"
              @update:value="item.value = $event === 'true'"
            />
            <RelativeDateValue
              v-else-if="!item.localField && conditionDate(item.targetField)"
              v-model="item.value"
              :operator="item.operator"
              :disabled="disabled"
              :blocked-reason="model.updateMode === 'LIVE' ? null : RELATIVE_BLOCKED.onSave"
            >
              <a-input v-model:value="item.value" :disabled="disabled" placeholder="固定值" />
            </RelativeDateValue>
            <a-input
              v-else-if="!item.localField"
              v-model:value="item.value"
              :disabled="disabled"
              placeholder="固定值"
            />
            <a-button :disabled="disabled" @click="model.conditions.splice(index, 1)">移除</a-button>
          </div>
          <a-button
            :disabled="disabled || model.conditions.length >= 20"
            @click="model.conditions.push({ targetField: '', operator: 'eq', localField: null, value: null })"
          >
            添加条件
          </a-button>
          <p v-if="running" class="hint">仅支持固定值条件；不设条件即全部参与累计，未匹配条件的记录结果为空。</p>
          <p v-else-if="wholeTable" class="hint">
            不设条件即全表统计；设置分组后统计本记录所在组，条件可引用本行字段。
          </p>
        </a-form-item>
        <a-checkbox
          v-if="!effectiveTarget && !running && !sequence"
          v-model:checked="model.excludeCurrent"
          :disabled="disabled"
        >
          排除本记录
        </a-checkbox>
        <p v-if="sequence" class="hint">
          按当前对象的完整分组计算，超过计算上限时会明确提示。来源字段须获得计算取数授权；读取时计算的结果不能参与筛选、排序和报表。
        </p>
        <p v-else-if="wholeTable" class="hint">
          由数据库全量计算，无 500
          条截断；仍需来源字段的计算取数授权，不改变应用成员权限。读取时计算的结果不能参与筛选、排序和报表。计数使用整数结果类型。
        </p>
        <p v-else class="hint">
          来源对象须加入应用并获得计算取数授权；每次最多匹配 500 条，超过时明确报错。计数使用整数结果类型。
        </p>
      </template>
    </template>
  </div>
</template>
<style scoped>
.calculation-editor {
  display: grid;
  gap: var(--spacing-sm);
}
.calculation-editor .ant-input-number {
  width: 100%;
}
.match-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin: var(--spacing-md) 0;
}
.match-row > .ant-select,
.match-row > .ant-input {
  min-width: 140px;
  flex: 1;
}
.hint {
  font-size: 12px;
  color: var(--text-secondary);
  margin: var(--spacing-sm) 0;
}
</style>
