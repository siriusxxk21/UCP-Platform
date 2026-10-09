<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import request from '@/utils/request'
import { createObjectDataApi } from '@/api/nocode/object-data'
import { errorMessage } from '@/nocode/data-center'
import { referenceValueMalformed, notRecordMessage } from '@/nocode/rule-condition-choices'
import type { SelectionOption } from '@/types/nocode/selection'

/**
 * 条件行里引用字段的固定值：从目标对象的记录里选（按名称搜索，存记录 ID，显示名称）。业务方 2026-10-04：原先是自由文本框，填了「民宿管理」去比记录 ID，候选恒为空。
 * 候选走对象数据维护的候选接口（与对象设计同一权限），显示名与表单里挑记录同一套。已存值不是有效记录时标红并说明，引导重选。
 */
const props = defineProps<{
  /** 条件所属的来源对象（引用筛选的目标对象 / 数据联动的来源对象）。 */
  objectId: string
  /** 条件字段（来源对象上的引用字段）。 */
  fieldId: string
  fieldName: string
  fieldType?: string
  value: unknown
  disabled?: boolean
  index: number
}>()
const emit = defineEmits<{ change: [value: string | null] }>()

const api = createObjectDataApi(request)
const stored = computed(() => (props.value == null || props.value === '' ? '' : String(props.value)))
const options = ref<SelectionOption[]>([]),
  echo = ref<SelectionOption | null>(null),
  loading = ref(false),
  error = ref(''),
  keyword = ref('')
let generation = 0,
  timer: ReturnType<typeof setTimeout> | undefined

async function load() {
  const turn = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await api.selection({
      objectId: props.objectId,
      fieldId: props.fieldId,
      search: keyword.value,
      pageNo: 1,
      pageSize: 30,
      selected: stored.value ? [stored.value] : []
    })
    if (turn !== generation) return
    options.value = result.options
    echo.value = result.selected.find(o => o.value === stored.value) ?? null
    error.value = result.ruleMessage && !result.options.length ? result.ruleMessage : ''
  } catch (cause) {
    if (turn === generation) error.value = errorMessage(cause)
  } finally {
    if (turn === generation) loading.value = false
  }
}
watch(
  () => [props.objectId, props.fieldId, stored.value],
  () => void load(),
  { immediate: true }
)
onBeforeUnmount(() => {
  generation++
  clearTimeout(timer)
})
function search(text: string) {
  clearTimeout(timer)
  keyword.value = text
  timer = setTimeout(() => void load(), 250)
}
/** 已存值不是有效记录：格式就不对（例如整数外键里存了名称），或候选接口回显为已失效。 */
const invalid = computed(
  () =>
    !!stored.value &&
    (referenceValueMalformed(props.fieldType, stored.value) || (!!echo.value && echo.value.unavailable))
)
const problem = computed(() =>
  invalid.value ? notRecordMessage(props.index, props.fieldName, stored.value) : error.value
)
const choices = computed(() => {
  const list = options.value.map(o => ({ value: o.value, label: o.label, disabled: o.disabled }))
  if (stored.value && !list.some(o => o.value === stored.value))
    list.push({
      value: stored.value,
      label: invalid.value ? `${stored.value}（不是有效记录）` : (echo.value?.label ?? stored.value),
      disabled: true
    })
  return list
})
</script>
<template>
  <div class="rule-record" :data-record-invalid="invalid ? 'true' : undefined">
    <a-select
      :value="stored || undefined"
      :options="choices"
      :disabled="disabled"
      :loading="loading"
      :status="problem ? 'error' : undefined"
      show-search
      :filter-option="false"
      placeholder="选择记录"
      not-found-content="没有匹配的记录"
      :aria-label="`第 ${index + 1} 条条件固定值（记录）`"
      @search="search"
      @update:value="emit('change', $event == null ? null : String($event))"
    />
    <p v-if="problem" class="rule-record-problem" role="alert">{{ problem }}</p>
  </div>
</template>
<style scoped>
.rule-record {
  display: grid;
  gap: 4px;
  flex: 1;
  min-width: 180px;
}
.rule-record-problem {
  margin: 0;
  font-size: 12px;
  color: var(--ant-color-error, #ff4d4f);
}
</style>
