<script setup lang="ts">
import { computed, inject, nextTick, reactive, ref, watch } from 'vue'
import { nocodePlatformKey } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import type { CalculationOptions } from '@/types/nocode/data-center'
import FormulaSequencePreview from './FormulaSequencePreview.vue'
import {
  describeFormula,
  emptyFormula,
  formulaNodeError,
  operationFormula,
  parseFormula,
  serializeFormula,
  type FormulaNode
} from '@/nocode/formula-builder'
import { defaultFormulaError } from '@/nocode/field-rules'
import {
  caretAfterNormalize,
  dateContextOfCalculation,
  dateFormulaOperations,
  formulaDateError,
  normalizeFormulaError,
  normalizeFormulaMessage,
  normalizeFormulaSource,
  type DateFormulaOperation
} from '@/nocode/formula-dates'
import FormulaNodeEditor from './FormulaNodeEditor.vue'
const props = defineProps<{
  modelValue?: string | null
  fields: ObjectField[]
  disabled?: boolean
  calculation?: CalculationOptions | null
  /** 金额目标的公式默认值：取整统一由「取整方式」决定，公式里不许写 round。 */
  roundForbidden?: boolean
  /** 明细字段的公式默认值：字段插入面板分「本行 / 主表」两组。 */
  groups?: { label: string; fields: ObjectField[] }[]
  /** 主表与本行重名的编码，公式引用时无法确定来源，插入面板标红。 */
  conflictCodes?: string[]
  /** 公式默认值才传：目标字段的类型。公式默认值可以用 TODAY() / NOW()；目标是数值字段时结果不能是日期。 */
  defaultTarget?: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [string | null] }>()
// 日期函数的类型检查口径：公式字段按计算方式决定（本行公式与保存时落库的不能用 TODAY() / NOW()），公式默认值单独一种。
const dateContext = computed(() =>
  props.defaultTarget === undefined
    ? dateContextOfCalculation(props.calculation)
    : { volatileAllowed: true, generated: false, defaultTarget: props.defaultTarget ?? '' }
)
const dateHint =
  '日期函数：YEAR、MONTH、DAY、WEEKDAY、DAYS、DATEDIF、EOMONTH、EDATE、DATE、TODAY、NOW；两个日期直接相减得天数，日期加减数字得日期。可以直接粘贴钉钉里的写法，[字段名] 会自动换成字段编码。'
const normalizeNote = ref('')
// 语法取自升级后的计算引擎；金额目标的公式默认值由「取整方式」统一取整，提示里去掉 round。
const expressionHint = computed(
  () =>
    (props.roundForbidden
      ? '支持四则运算、比较 =、!=、>、>=、<、<=，以及 IF、AND、OR、NOT、ISBLANK、coalesce 等函数；金额结果按取整方式统一取整，公式里不要写 round。'
      : '支持四则运算、比较 =、!=、>、>=、<、<=，以及 IF、AND、OR、NOT、ISBLANK、coalesce、round 等函数。') +
    '函数名不区分大小写，点击字段可插入到光标位置。'
)
const mode = ref('visual'),
  node = ref<FormulaNode>(emptyFormula()),
  raw = ref(''),
  touched = ref(false),
  modeError = ref('')
const original = props.modelValue ?? ''
const editor = ref<HTMLElement>()
const platform = inject(nocodePlatformKey, null)
const sampleValues = reactive<Record<string, string | null>>({})
const sampleBusy = ref(false),
  sampleResult = ref<string | null>(),
  sampleError = ref('')
const sampleFields = computed(() =>
  props.fields.filter(field => new RegExp('\\b' + field.code + '\\b').test(raw.value))
)
const currentSampleValues = () =>
  Object.fromEntries(
    sampleFields.value.map(field => [
      field.code,
      sampleValues[field.code] === '' ? null : (sampleValues[field.code] ?? null)
    ])
  )
watch(
  () => [raw.value, ...Object.values(sampleValues)],
  () => {
    sampleResult.value = undefined
    sampleError.value = ''
  }
)
async function previewSamples() {
  if (validationError.value || sampleBusy.value) return
  sampleBusy.value = true
  sampleError.value = ''
  const expression = raw.value,
    values = currentSampleValues()
  const current = () => raw.value === expression && JSON.stringify(values) === JSON.stringify(currentSampleValues())
  try {
    if (!platform) throw new Error('当前页面未连接平台服务，无法试算')
    const result = await platform.dataCenter.formulaPreview({
      expression,
      fieldCodes: props.fields.map(field => field.code),
      fieldTypes: Object.fromEntries(props.fields.map(field => [field.code, field.type])),
      values
    })
    if (current()) sampleResult.value = result.value
  } catch (error) {
    if (current()) sampleError.value = errorMessage(error)
  } finally {
    sampleBusy.value = false
  }
}
function amountFormula() {
  const quantity = props.fields.find(field => /数量|qty|quantity/i.test(field.name + field.code))
  const price = props.fields.find(field => /单价|price/i.test(field.name + field.code))
  const multiply = operationFormula('*', quantity ? { kind: 'field', value: quantity.code } : emptyFormula())
  if (multiply.kind === 'operation' && price) multiply.args[1] = { kind: 'field', value: price.code }
  changeVisual(props.roundForbidden ? multiply : operationFormula('round', multiply))
}
let selection = { start: 0, end: 0 },
  emitted: string | null | undefined
const validationError = computed(() => {
  // [字段名] 没认出来（字段名不存在或重名）时先说这个，而不是报「第 N 个字符附近…」。
  const bracketError =
    mode.value === 'visual' ? '' : normalizeFormulaError(normalizeFormulaSource(raw.value, props.fields))
  if (bracketError) return bracketError
  try {
    const tree = mode.value === 'visual' ? node.value : parseFormula(raw.value)
    const error =
      formulaNodeError(tree, props.fields) ||
      defaultFormulaError(tree, { roundForbidden: props.roundForbidden, conflictCodes: props.conflictCodes })
    if (error) return error
    const dateError = formulaDateError(tree, props.fields, dateContext.value)
    if (dateError) return dateError
    parseFormula(mode.value === 'visual' ? serializeFormula(tree) : raw.value)
    return ''
  } catch (error) {
    return error instanceof Error ? error.message : '请检查计算配置'
  }
})
const description = computed(() => {
  try {
    return describeFormula(mode.value === 'visual' ? node.value : parseFormula(raw.value), props.fields)
  } catch {
    return ''
  }
})
function load(value: string) {
  raw.value = value
  try {
    node.value = value.trim() ? parseFormula(value) : emptyFormula()
    mode.value = 'visual'
  } catch {
    mode.value = 'advanced'
  }
}
watch(
  () => props.modelValue,
  value => {
    if (value === emitted) {
      emitted = undefined
      return
    }
    load(value ?? '')
  },
  { immediate: true }
)
function publish(value: string | null) {
  emitted = value
  emit('update:modelValue', value)
}
function changeVisual(value: FormulaNode) {
  if (props.disabled) return
  node.value = value
  touched.value = true
  modeError.value = ''
  raw.value = serializeFormula(value)
  publish(validationError.value ? null : raw.value)
}
function changeRaw(value: string) {
  if (props.disabled) return
  raw.value = value
  touched.value = true
  modeError.value = ''
  publish(value)
}
/** 输入或粘贴：先把钉钉 / Excel 的写法（[字段名]、全角括号逗号、中文引号）换成本系统的写法，再按原流程处理。 */
function typeRaw(value: string) {
  if (props.disabled) return
  const normalized = normalizeFormulaSource(value, props.fields)
  normalizeNote.value =
    normalizeFormulaMessage(normalized) ||
    (normalized.text !== value ? '已把全角的括号、逗号、引号换成半角，含义不变。' : '')
  if (normalized.text !== value) {
    const input = editor.value?.querySelector('textarea')
    const caret = caretAfterNormalize(input?.selectionStart ?? value.length, normalized.edits)
    selection = { start: caret, end: caret }
    nextTick(() => editor.value?.querySelector('textarea')?.setSelectionRange(caret, caret))
  }
  changeRaw(normalized.text)
}
/** 插入函数：光标停在括号里，接着点字段按钮就能填参数。 */
function insertFunction(operation: DateFormulaOperation) {
  if (props.disabled) return
  const start = selection.start
  changeRaw(raw.value.slice(0, start) + operation.insert + raw.value.slice(selection.end))
  const caret = start + (operation.range[1] === 0 ? operation.insert.length : operation.insert.indexOf('(') + 1)
  selection = { start: caret, end: caret }
  nextTick(() => {
    const input = editor.value?.querySelector('textarea')
    input?.focus()
    input?.setSelectionRange(caret, caret)
  })
}
const samplePlaceholder = (field: ObjectField) =>
  field.type === 'DATE'
    ? '例如 2026-07-30，空值可留空'
    : field.type === 'DATETIME'
      ? '例如 2026-07-30 15:00，空值可留空'
      : '空值可留空'
function changeMode(value: string) {
  if (value === 'visual') {
    try {
      node.value = raw.value.trim() ? parseFormula(raw.value) : emptyFormula()
    } catch {
      modeError.value = '请先修正表达式，再切换到选择式配置；当前内容已保留。'
      return
    }
  }
  modeError.value = ''
  mode.value = value
}
function rememberSelection(event: Event) {
  const input = event.target as HTMLTextAreaElement
  selection = { start: input.selectionStart, end: input.selectionEnd }
}
function insertField(value: string) {
  changeRaw(raw.value.slice(0, selection.start) + value + raw.value.slice(selection.end))
  selection = { start: selection.start + value.length, end: selection.start + value.length }
  editor.value?.querySelector('textarea')?.focus()
}
function restore() {
  if (props.disabled) return
  load(original)
  touched.value = false
  modeError.value = ''
  publish(original || null)
}
defineExpose({ validate: () => validationError.value })
</script>
<template>
  <section class="expression-editor" aria-label="计算规则配置">
    <div class="expression-toolbar">
      <a-radio-group :value="mode" size="small" @change="changeMode($event.target.value)">
        <a-radio-button value="visual">选择式配置</a-radio-button>
        <a-radio-button value="advanced">直接编辑表达式</a-radio-button>
      </a-radio-group>
      <a-button v-if="!disabled && touched" type="link" size="small" @click="restore">恢复原公式</a-button>
    </div>
    <template v-if="mode === 'visual'">
      <p class="expression-hint">选择计算方式和字段，系统自动生成公式。每一项也可以继续组合计算。</p>
      <p v-if="roundForbidden" class="expression-hint">金额结果按取整方式统一取整，公式里不要写 round。</p>
      <div v-if="!modelValue && node.kind === 'field' && !node.value" class="quick-start">
        <span>常用计算</span>
        <a-button size="small" :disabled="disabled" @click="amountFormula">
          {{ roundForbidden ? '数量 × 单价' : '数量 × 单价（保留 2 位）' }}
        </a-button>
        <a-button
          v-if="!roundForbidden"
          size="small"
          :disabled="disabled"
          @click="changeVisual(operationFormula('round'))"
        >
          保留两位小数
        </a-button>
        <a-button size="small" :disabled="disabled" @click="changeVisual(operationFormula('||'))">拼接文字</a-button>
        <a-button size="small" :disabled="disabled" @click="changeVisual(operationFormula('if'))">
          如果…那么…否则…
        </a-button>
      </div>
      <FormulaNodeEditor :model-value="node" :fields="fields" :disabled="disabled" @update:model-value="changeVisual" />
    </template>
    <template v-else>
      <p class="expression-hint">
        {{ expressionHint }}
      </p>
      <p class="expression-hint">{{ dateHint }}</p>
      <div ref="editor">
        <a-textarea
          :value="raw"
          aria-label="计算表达式"
          :rows="4"
          :maxlength="1000"
          :disabled="disabled"
          placeholder="例如 price * quantity"
          @update:value="typeRaw"
          @click="rememberSelection"
          @keyup="rememberSelection"
          @select="rememberSelection"
        />
      </div>
      <div v-if="!groups" class="field-inserts">
        <a-button
          v-for="field in fields"
          :key="field.key"
          size="small"
          :disabled="disabled"
          @click="insertField(field.code)"
        >
          {{ field.name }}
        </a-button>
      </div>
      <div v-for="group in groups || []" :key="group.label" class="field-inserts" :aria-label="group.label + '字段'">
        <span class="expression-hint">{{ group.label }}</span>
        <a-button
          v-for="field in group.fields"
          :key="group.label + ':' + field.key"
          size="small"
          :danger="conflictCodes?.includes(field.code)"
          :title="conflictCodes?.includes(field.code) ? '主表与本行存在同名编码，公式引用时无法确定来源' : undefined"
          :disabled="disabled"
          @click="insertField(field.code)"
        >
          {{ group.label }} · {{ field.name }}
        </a-button>
      </div>
    </template>
    <template v-if="mode !== 'visual'">
      <div class="field-inserts" aria-label="日期函数">
        <span class="expression-hint">日期函数</span>
        <a-button
          v-for="operation in dateFormulaOperations"
          :key="operation.value"
          size="small"
          :title="operation.signature + '：' + operation.help"
          :disabled="disabled"
          @click="insertFunction(operation)"
        >
          {{ operation.value.toUpperCase() }}
        </a-button>
      </div>
      <a-alert v-if="normalizeNote" type="info" show-icon :message="normalizeNote" />
    </template>
    <div v-if="description" class="expression-preview" aria-live="polite">
      <span>计算规则</span>
      <strong>{{ description }}</strong>
    </div>
    <a-alert
      v-if="modeError || ((touched || !!modelValue) && validationError)"
      type="warning"
      show-icon
      :message="modeError || validationError"
    />
    <p v-else-if="!validationError" class="expression-valid">配置已完整，保存时将检查字段依赖和计算规则。</p>
    <a-collapse>
      <a-collapse-panel key="help" header="函数说明与例子">
        <table class="formula-help-table">
          <tbody>
            <tr>
              <th>用途</th>
              <th>表达式示例</th>
              <th>说明</th>
            </tr>
            <tr v-if="!roundForbidden">
              <td>行金额</td>
              <td>round(qty * price, 2)</td>
              <td>先相乘，再保留两位小数</td>
            </tr>
            <tr>
              <td>空值按零</td>
              <td>coalesce(amount, 0)</td>
              <td>amount 没填写时使用 0</td>
            </tr>
            <tr>
              <td>按类型确定收支</td>
              <td>if(type = 'IN', amount, -amount)</td>
              <td>使用选项编码判断；请通过字段必填和选项范围校验收支类型</td>
            </tr>
            <tr>
              <td>避免除零</td>
              <td>if(or(isblank(qty), qty = 0), null, amount / qty)</td>
              <td>只执行选中的分支；空值判断使用 isblank</td>
            </tr>
            <tr>
              <td>合并文字</td>
              <td>name || ' - ' || code</td>
              <td>按顺序拼接名称和编码</td>
            </tr>
            <tr>
              <td>绝对值</td>
              <td>abs(amount)</td>
              <td>把负数变为正数</td>
            </tr>
          </tbody>
        </table>
        <table class="formula-help-table">
          <tbody>
            <tr>
              <th>日期函数</th>
              <th>写法</th>
              <th>说明</th>
            </tr>
            <tr v-for="operation in dateFormulaOperations" :key="operation.value">
              <td>{{ operation.label }}</td>
              <td>{{ operation.signature }}</td>
              <td>{{ operation.help }}</td>
            </tr>
            <tr>
              <td>日期加减</td>
              <td>checkout - checkin、checkin + 3</td>
              <td>两个日期相减得天数；日期加减数字得日期（只能当中间值，再套 YEAR / MONTH / DAY 或用于比较）</td>
            </tr>
          </tbody>
        </table>
        <p class="expression-hint">
          日期时间字段按日期部分计算。任一日期为空时结果为空。函数名大小写、空格随意；等于可以写 = 或
          ==；文字可以用单引号或双引号。
        </p>
        <p class="expression-hint">
          普通计算中任一输入为空时，结果可能为空；如需按零计算，请显式使用空值备用值。金额合计在主表“汇总”字段中选择明细的行金额公式。
        </p>
      </a-collapse-panel>
    </a-collapse>
    <a-collapse v-if="calculation?.mode === 'SEQUENCE'">
      <a-collapse-panel key="sequence-trial" header="填写多行样例，查看分组与顺序计算结果">
        <FormulaSequencePreview
          :expression="raw"
          :fields="fields"
          :calculation="calculation"
          :invalid="!!validationError"
        />
      </a-collapse-panel>
    </a-collapse>
    <a-collapse v-else>
      <a-collapse-panel key="trial" header="填写样例值，试算结果">
        <p class="expression-hint">
          只计算你在这里填写的样例，不读取或保存业务数据。使用与正式记录相同的服务端计算规则；引用公式或明细汇总字段时，填写它的样例结果。
        </p>
        <div class="sample-fields">
          <label v-for="field in sampleFields" :key="field.key">
            {{ field.name }}
            <a-input
              v-model:value="sampleValues[field.code]"
              :aria-label="'试算样例：' + field.name"
              :maxlength="1000"
              allow-clear
              :placeholder="samplePlaceholder(field)"
            />
          </label>
        </div>
        <a-button :loading="sampleBusy" :disabled="!!validationError" @click="previewSamples">试算</a-button>
        <a-alert v-if="sampleError" type="error" show-icon :message="sampleError" />
        <a-alert
          v-else-if="sampleResult !== undefined"
          type="success"
          show-icon
          :message="sampleResult === null ? '结果为空；请检查样例值，或配置空值备用值。' : '试算结果：' + sampleResult"
        />
      </a-collapse-panel>
    </a-collapse>
  </section>
</template>
<style scoped>
.expression-editor {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border-color, #e6e6ed);
  border-radius: 8px;
  min-width: 0;
  background: var(--bg-container, #fff);
}
.expression-toolbar,
.quick-start,
.field-inserts {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.expression-toolbar {
  justify-content: space-between;
}
.expression-hint,
.quick-start > span {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary, #666);
}
.expression-preview {
  display: grid;
  gap: 6px;
  padding: 12px;
  border-radius: 6px;
  background: var(--primary-bg, #f6f4ff);
  overflow-wrap: anywhere;
}
.expression-preview > span {
  font-size: 12px;
  color: var(--text-secondary, #666);
}
.expression-preview strong {
  font-weight: 500;
}
.expression-valid {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary, #666);
}
.formula-help-table {
  border-collapse: collapse;
  width: 100%;
  font-size: 12px;
  margin-bottom: 12px;
}
.formula-help-table td,
.formula-help-table th {
  border: 1px solid var(--border, #eee);
  padding: 8px;
  text-align: left;
}
.sample-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin: 12px 0;
}
.sample-fields label {
  display: grid;
  gap: 6px;
}
@media (max-width: 600px) {
  .expression-editor {
    padding: 12px;
  }
}
</style>
