<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { FieldRules } from '@/types/nocode/field-rules'
import { formulaFields } from '@/nocode/formula-builder'
import {
  defaultValueMode,
  formFieldGroups,
  formulaFieldGroups,
  normalizeValueSource,
  referenceTargetOf,
  roundingApplies,
  roundingCode,
  roundingOf,
  roundingOptions,
  valueSourceModeLabels,
  valueSourceModesFor,
  type RoundingMode,
  type ValueSourceMode
} from '@/nocode/field-rules'
import DataLinkageEditor from './DataLinkageEditor.vue'
import FormulaExpressionEditor from './FormulaExpressionEditor.vue'
import ReferenceRuleEditor from './ReferenceRuleEditor.vue'

/**
 * 字段抽屉「值来源」分区：选项类只显示「选项」块，其它字段只显示「默认值」块，两块从不同时出现。
 * 留空即没有默认值，不设「启用默认值」开关。自定义默认值与选项行由抽屉通过插槽提供。
 */
const props = defineProps<{
  field: ObjectField
  relation?: ObjectRelation | null
  fields: ObjectField[]
  relations?: ObjectRelation[]
  fieldOptions?: Record<string, FieldOptions>
  /** 明细字段才有：主表字段与关系。数据联动据此认出「当前字段在内部明细里」（明细字段暂不支持自动更新）。 */
  master?: { fields: ObjectField[]; relations?: ObjectRelation[] } | null
  /** 当前对象 ID，传给数据联动用于判断来源字段是否挑的是当前字段、以及条件值来源「当前记录」。 */
  objectId?: string | null
  disabled?: boolean
}>()
const options = defineModel<FieldOptions>('options', { required: true })

const matrix = computed(() => valueSourceModesFor(props.field.type, props.relation))
const block = computed(() => matrix.value.block)
const defaultModes = computed(() =>
  matrix.value.modes.filter((mode): mode is 'CUSTOM' | 'LINKAGE' | 'FORMULA' =>
    ['CUSTOM', 'LINKAGE', 'FORMULA'].includes(mode)
  )
)
const mode = ref<ValueSourceMode>(defaultValueMode(options.value))
const linkageOn = ref(!!options.value.rules?.linkage)
watch(
  () => [props.field.type, props.relation?.kind],
  () => {
    mode.value = defaultValueMode(options.value)
    linkageOn.value = !!options.value.rules?.linkage
  }
)
const rules = computed<FieldRules>(() => options.value.rules ?? {})
const money = computed(() => roundingApplies(props.field.type))
const formGroups = computed(() =>
  formFieldGroups({
    fields: props.fields,
    relations: props.relations,
    selfKey: props.field.key,
    master: props.master
  })
)
const targetReference = computed(() =>
  props.relation ? props.relation.targetObjectId : referenceTargetOf(props.field, props.relations)
)
const formula = computed(() =>
  formulaFieldGroups(
    formulaFields(props.fields, props.field.key, false, props.fieldOptions ?? {}),
    props.master ? formulaFields(props.master.fields, '', false, {}) : null
  )
)

function apply(next: FieldOptions, nextMode = mode.value) {
  if (props.disabled) return
  options.value = normalizeValueSource(props.field.type, props.relation, next, nextMode)
}
function changeMode(value: ValueSourceMode) {
  if (props.disabled) return
  mode.value = value
  apply(options.value, value)
}
function patchRules(value: Partial<FieldRules>) {
  apply({ ...options.value, rules: { ...rules.value, ...value } })
}
function toggleLinkage(on: boolean) {
  if (props.disabled) return
  linkageOn.value = on
  if (!on) patchRules({ linkage: null })
}
function changeRounding(value: RoundingMode) {
  patchRules({ rounding: roundingCode(value) })
}
const formulaEditor = ref<{ validate: () => string }>()
/** 抽屉保存时调用：公式档必须是可解析、且金额目标不含 round 的公式。 */
function validate(): string {
  if (block.value !== 'DEFAULT' || mode.value !== 'FORMULA') return ''
  const problem = formulaEditor.value?.validate() ?? ''
  return problem ? '公式默认值：' + problem : ''
}
defineExpose({ mode, validate, formGroups })
</script>
<template>
  <section v-if="block !== 'NONE'" class="value-source" aria-label="值来源">
    <a-form-item v-if="block === 'DEFAULT'" label="默认值">
      <a-radio-group
        v-if="defaultModes.length > 1"
        :value="mode"
        size="small"
        button-style="solid"
        :disabled="disabled"
        aria-label="默认值来源"
        @update:value="changeMode"
      >
        <a-radio-button v-for="item in defaultModes" :key="item" :value="item">
          {{ valueSourceModeLabels[item] }}
        </a-radio-button>
      </a-radio-group>
      <div v-if="mode === 'CUSTOM'" class="value-source-body">
        <slot name="custom" />
      </div>
      <div v-else-if="mode === 'LINKAGE'" class="value-source-body">
        <DataLinkageEditor
          :model-value="rules.linkage ?? null"
          :rounding="rules.rounding"
          :field="field"
          :field-options="options"
          :reference-target="targetReference"
          :object-id="objectId"
          :form-groups="formGroups"
          :detail="!!master"
          :disabled="disabled"
          @update:model-value="patchRules({ linkage: $event })"
          @update:rounding="patchRules({ rounding: $event })"
        />
      </div>
      <div v-else-if="mode === 'FORMULA'" class="value-source-body">
        <FormulaExpressionEditor
          ref="formulaEditor"
          :model-value="rules.defaultFormula ?? null"
          :fields="formula.fields"
          :groups="formula.groups"
          :conflict-codes="formula.conflictCodes"
          :round-forbidden="money"
          :default-target="field.type"
          :disabled="disabled"
          @update:model-value="patchRules({ defaultFormula: $event })"
        />
        <p class="value-source-hint">只读，依赖字段变化时自动重算。</p>
      </div>
      <div v-if="money && mode === 'FORMULA'" class="value-source-body">
        <strong class="value-source-title">取整方式</strong>
        <a-radio-group
          :value="roundingOf(rules)"
          :disabled="disabled"
          class="value-source-choices"
          aria-label="取整方式"
          @update:value="changeRounding"
        >
          <a-radio v-for="item in roundingOptions" :key="item.value" :value="item.value">
            {{ item.label }}
            <span class="value-source-hint">{{ item.example }}</span>
          </a-radio>
        </a-radio-group>
        <p class="value-source-hint">金额按日元存整数；带小数的结果按这里取整，缺省向下取整。</p>
      </div>
    </a-form-item>
    <a-form-item v-else label="选项">
      <ReferenceRuleEditor
        v-if="relation"
        :model-value="rules.reference ?? null"
        :relation="relation"
        :form-groups="formGroups"
        :filterable="matrix.modes.includes('REFERENCE_FILTER')"
        :disabled="disabled"
        @update:model-value="patchRules({ reference: $event })"
      />
      <slot v-else name="options" />
      <div v-if="matrix.modes.includes('LINKAGE')" class="value-source-body">
        <label class="value-source-switch">
          <a-switch
            :checked="linkageOn"
            :disabled="disabled"
            aria-label="数据联动（可选）"
            @update:checked="toggleLinkage(!!$event)"
          />
          数据联动（可选）
        </label>
        <DataLinkageEditor
          v-if="linkageOn"
          :model-value="rules.linkage ?? null"
          :field="field"
          :field-options="options"
          :reference-target="targetReference"
          :object-id="objectId"
          :form-groups="formGroups"
          :detail="!!master"
          :disabled="disabled"
          @update:model-value="patchRules({ linkage: $event })"
        />
      </div>
      <p v-if="!relation" class="value-source-hint">选项类字段没有默认值：未作答与选中无法区分，会影响统计。</p>
    </a-form-item>
  </section>
</template>
<style scoped>
.value-source {
  margin-bottom: 8px;
}
.value-source-body {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}
.value-source-title {
  font-weight: 500;
}
.value-source-choices {
  display: grid;
  gap: 6px;
}
.value-source-switch {
  display: flex;
  align-items: center;
  gap: 8px;
}
.value-source-hint {
  margin: 0 0 0 4px;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
