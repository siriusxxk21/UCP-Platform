<script setup lang="ts">
import { managementCategoryLabel } from '@/nocode/management-category'
import { computed, ref } from 'vue'
import { errorMessage } from '@/nocode/data-center'

const props = defineProps<{
  modelValue?: string
  label: string
  loadCategories: () => Promise<string[]>
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const categories = ref<string[]>([])
const loading = ref(false)
const error = ref('')
const options = computed(() => categories.value.map(value => ({ value, label: managementCategoryLabel(value) })))
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    categories.value = await props.loadCategories()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <a-auto-complete
      :value="modelValue ?? ''"
      :options="options"
      :disabled="disabled"
      :filter-option="
        (input: string, option: { value: string }) =>
          option.value.toLocaleLowerCase().includes(input.toLocaleLowerCase())
      "
      style="width: 100%"
      @update:value="emit('update:modelValue', String($event ?? ''))"
      @focus="load"
    >
      <a-input :aria-label="label" placeholder="选择已有分类或输入新分类" :maxlength="100" allow-clear />
    </a-auto-complete>
    <div v-if="error" class="category-error" role="alert">
      {{ error }}
      <a-button type="link" size="small" @click="load">重试</a-button>
    </div>
  </div>
</template>

<style scoped>
.category-error {
  color: var(--ant-color-error, #ff4d4f);
  font-size: 12px;
}
</style>
