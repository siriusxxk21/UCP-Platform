<script setup lang="ts">
import { computed, ref } from 'vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useOsModalForm } from '@/composables/useOsModalForm'
import { formulaFields, formulaNodeError, parseFormula, sequenceFormulaFields } from '@/nocode/formula-builder'
import { calculationResultTypeError, calculationResultTypeOptions } from '@/nocode/calculation-presentation'
import { dateContextOfCalculation, formulaDateError } from '@/nocode/formula-dates'
import type { CalculationOptions, FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import CalculationEditor from './CalculationEditor.vue'
import FormulaExpressionEditor from './FormulaExpressionEditor.vue'

const props = defineProps<{
  field: ObjectField
  options: FieldOptions
  fields: ObjectField[]
  fieldOptions: Record<string, FieldOptions>
  relations: ObjectRelation[]
  disabled?: boolean
  detail?: boolean
}>()
const emit = defineEmits<{
  apply: [value: { expression: string | null; resultType: string; calculation: CalculationOptions | null }]
}>()
const { formData, modalVisible, modalProps, openEdit, closeModal, switchDisplayMode } = useOsModalForm({
  defaultForm: () => ({
    expression: null as string | null,
    resultType: 'DECIMAL',
    calculation: null as CalculationOptions | null
  }),
  titles: { edit: '配置计算公式' },
  displayModes: ['modal', 'fullscreen'],
  displayModeStorageKey: 'nocode-formula-configuration'
})
const calculationEditor = ref<{ validate: () => string }>()
const expressionEditor = ref<{ validate: () => string }>()
const error = ref('')
const session = ref(0)
const resultTypeOptions = computed(() => calculationResultTypeOptions(formData.calculation))
const needsExpression = computed(
  () => !formData.calculation || ['LOCAL', 'SEQUENCE'].includes(formData.calculation.mode)
)
function availableFields(calculation: CalculationOptions | null | undefined) {
  let candidates =
    calculation?.mode === 'SEQUENCE'
      ? sequenceFormulaFields(props.fields, props.field.key, props.fieldOptions)
      : formulaFields(props.fields, props.field.key, calculation?.mode === 'LOCAL', props.fieldOptions)
  candidates = candidates.map(field =>
    ['FORMULA', 'SUMMARY'].includes(field.type)
      ? { ...field, type: (props.fieldOptions[field.key]?.resultType || 'DECIMAL') as ObjectField['type'] }
      : field
  )
  if (calculation?.mode !== 'SEQUENCE' || calculation.sequence?.operation === 'CUMULATIVE') return candidates
  return [
    ...candidates,
    ...candidates.map(field => ({
      ...field,
      key: 'previous:' + field.key,
      code: '__previous_' + field.code,
      name: '相邻记录：' + field.name
    }))
  ]
}
const expressionFields = computed(() => availableFields(formData.calculation))
function openEditor() {
  error.value = ''
  session.value++
  // 弹窗只编辑计算配置的副本，取消或切换字段不会改变外层字段草稿。
  openEdit(
    JSON.parse(
      JSON.stringify({
        expression: props.options.expression ?? null,
        resultType: props.options.resultType || 'DECIMAL',
        calculation: props.options.calculation ?? null
      })
    )
  )
}
function appliedError() {
  const calculation = props.options.calculation
  const resultTypeError = calculationResultTypeError(props.options.resultType || 'DECIMAL', calculation)
  if (resultTypeError) return resultTypeError
  if (!calculation || ['LOCAL', 'SEQUENCE'].includes(calculation.mode)) {
    try {
      const result = formulaNodeError(parseFormula(props.options.expression || ''), availableFields(calculation))
      if (result) return result
      const dateError = formulaDateError(
        parseFormula(props.options.expression || ''),
        availableFields(calculation),
        dateContextOfCalculation(calculation)
      )
      if (dateError) return dateError
    } catch (cause) {
      return cause instanceof Error ? cause.message : '请完成公式配置'
    }
  }
  if (calculation?.mode === 'SEQUENCE' && !calculation.sequence?.orderField) return '请选择顺序字段'
  return ''
}
function apply() {
  if (props.disabled) return
  error.value =
    calculationResultTypeError(formData.resultType, formData.calculation) ||
    calculationEditor.value?.validate() ||
    (needsExpression.value ? expressionEditor.value?.validate() : '') ||
    ''
  if (error.value) return
  emit('apply', JSON.parse(JSON.stringify(formData)))
  closeModal()
}
defineExpose({ openEditor, validate: appliedError })
</script>
<template>
  <OsModalForm
    v-bind="modalProps"
    :title="'配置计算公式 · ' + field.name"
    :width="1120"
    :height="760"
    layout="vertical"
    :label-col="{}"
    :wrapper-col="{}"
    :disabled="disabled"
    :destroy-on-close="true"
    :show-footer="!disabled"
    ok-text="应用到字段"
    @ok="apply"
    @cancel="closeModal"
    @display-mode-change="switchDisplayMode"
  >
    <template #formItems>
      <div v-if="modalVisible" :key="session" class="formula-configuration">
        <a-alert v-if="error" type="error" show-icon :message="error" />
        <p class="hint">配置计算来源、规则和结果，并使用样例试算。应用后还需保存字段及对象草稿。</p>
        <div class="configuration-layout" :class="{ 'without-expression': !needsExpression }">
          <section class="calculation-settings" aria-label="公式来源与结果">
            <CalculationEditor
              v-if="!detail"
              ref="calculationEditor"
              v-model="formData.calculation"
              :fields="fields"
              :relations="relations"
              :disabled="disabled"
              @result-type="formData.resultType = $event"
            />
            <a-form-item label="结果类型">
              <a-select v-model:value="formData.resultType" :disabled="disabled" :options="resultTypeOptions" />
            </a-form-item>
            <p class="hint">
              需要保留某个业务时点的结果时，使用应用里的“留存计算结果”业务动作，写入独立普通字段。实时计算与本记录保存时重算均不会自动生成确认值。
            </p>
          </section>
          <section v-if="needsExpression" class="calculation-rule" aria-label="公式表达式与试算">
            <a-form-item
              v-if="needsExpression"
              :label="formData.calculation?.sequence?.operation === 'CUMULATIVE' ? '本笔贡献公式' : '计算规则'"
              required
            >
              <FormulaExpressionEditor
                ref="expressionEditor"
                v-model="formData.expression"
                :fields="expressionFields"
                :calculation="formData.calculation"
                :disabled="disabled"
              />
            </a-form-item>
          </section>
        </div>
      </div>
    </template>
  </OsModalForm>
</template>
<style scoped>
.formula-configuration {
  display: grid;
  gap: var(--spacing-md);
}
.configuration-layout {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: start;
}
.calculation-settings,
.calculation-rule {
  min-width: 0;
}
.configuration-layout.without-expression {
  grid-template-columns: minmax(0, 1fr);
}
.without-expression .calculation-settings {
  width: 100%;
  max-width: 900px;
  margin-inline: auto;
}
.hint {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--table-font-sm);
}
@media (max-width: 850px) {
  .configuration-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
