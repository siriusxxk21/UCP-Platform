<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import UserSelectorTrigger from '@/components/UserSelectorTrigger.vue'
import { directoryOptions } from '@/nocode/directory-options'
import { FieldType } from '@/types/nocode/enums'
import { errorMessage } from '@/nocode/data-center'
const props = defineProps<{
  modelValue?: string | null
  kind: FieldType
  disabled?: boolean
  readOnly?: boolean
  placeholder?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()
const ids = computed(() => (props.modelValue ? [props.modelValue] : [])),
  options = ref<Array<{ value: string; label: string }>>([]),
  error = ref('')
async function load() {
  error.value = ''
  try {
    if (props.kind !== FieldType.USER || props.readOnly) options.value = await directoryOptions(props.kind, ids.value)
  } catch (e) {
    error.value = errorMessage(e)
  }
}
watch(() => [props.kind, props.readOnly, props.modelValue], load, { immediate: true })
function changed(value: unknown) {
  emit('update:modelValue', value == null ? null : String(value))
}
</script>
<template>
  <div>
    <span v-if="readOnly">
      {{ modelValue ? options.find(o => o.value === modelValue)?.label || '未提供可见名称' : '—' }}
    </span>
    <UserSelectorTrigger
      v-else-if="kind === FieldType.USER"
      selector-type="user"
      mode="single"
      :model-value="ids"
      :disabled="disabled"
      :placeholder="placeholder"
      @update:model-value="value => emit('update:modelValue', value[0] ? String(value[0]) : null)"
    />
    <a-select
      v-else
      :value="modelValue || undefined"
      :options="options"
      :placeholder="placeholder"
      :disabled="disabled"
      show-search
      allow-clear
      option-filter-prop="label"
      style="width: 100%"
      @change="changed"
    />
    <div v-if="error" class="error">{{ error }}</div>
  </div>
</template>
<style scoped>
.error {
  color: #dc2626;
  font-size: 12px;
}
</style>
