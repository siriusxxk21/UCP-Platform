<script setup lang="ts">
import { recordTitle } from '@/nocode/business-fields'
import { onBeforeUnmount, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
const props = defineProps<{
  modelValue?: string | string[] | null
  multiple?: boolean
  applicationId: string
  targetObjectId: string
  disabled?: boolean
  readOnly?: boolean
  placeholder?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | string[] | null] }>()
const api = useNocodePlatform().runtime,
  options = ref<Array<{ value: string; label: string }>>([]),
  loading = ref(false),
  error = ref(''),
  title = ref('')
let generation = 0
function selectedIds(): string[] {
  return Array.isArray(props.modelValue) ? props.modelValue : props.modelValue ? [props.modelValue] : []
}
async function load(search = '') {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const model = await api.model(props.applicationId, props.targetObjectId)
    const page = await api.page({
      applicationId: props.applicationId,
      objectId: props.targetObjectId,
      pageNo: 1,
      pageSize: 30,
      search,
      descending: true
    })
    const choices = page.list.filter(r => r.id).map(r => ({ value: r.id!, label: recordTitle(model.object, r.values) }))
    const missing = selectedIds().filter(id => !choices.some(o => o.value === id))
    // 回显不拼接任意查询或绕过运行权限，分批复用正常记录接口。
    for (let from = 0; from < missing.length; from += 8) {
      const existing = await Promise.all(
        missing.slice(from, from + 8).map(async id => {
          try {
            const selected = await api.get(props.applicationId, props.targetObjectId, id)
            return { value: id, label: recordTitle(model.object, selected.record.values) }
          } catch {
            return { value: id, label: '已失效或无权限的引用' }
          }
        })
      )
      choices.unshift(...existing)
      if (current !== generation) return
    }
    if (current === generation) {
      options.value = choices
      title.value = model.object.objectName
    }
  } catch (e) {
    if (current === generation) {
      error.value = errorMessage(e)
      options.value = selectedIds().map(value => ({ value, label: '已失效或无权限的引用' }))
    }
  } finally {
    if (current === generation) loading.value = false
  }
}
// 目标对象的数据变了：选择器在表单里，不当场重载，下次展开下拉时再取候选。
let outdated = false
useRuntimeDataRefresh({
  interest: () => ({ applicationId: props.applicationId, objectIds: [props.targetObjectId] }),
  refresh: () => {
    outdated = true
  }
})
function refreshOutdated(open: boolean) {
  if (!open || !outdated) return
  outdated = false
  void load()
}
let searchTimer: number | undefined
function search(text: string) {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void load(text), 250)
}
onBeforeUnmount(() => {
  generation++
  window.clearTimeout(searchTimer)
})
function changed(value: unknown) {
  emit(
    'update:modelValue',
    props.multiple ? (Array.isArray(value) ? value.map(String) : []) : value == null ? null : String(value)
  )
}
watch(
  () => [props.applicationId, props.targetObjectId, props.modelValue],
  () => load(),
  { immediate: true }
)
</script>
<template>
  <div>
    <span v-if="readOnly">
      {{
        selectedIds()
          .map(id => options.find(o => o.value === id)?.label || (loading ? '加载中…' : '已失效或无权限的引用'))
          .join('、') || '—'
      }}
    </span>
    <a-select
      v-else
      :value="modelValue || undefined"
      :mode="multiple ? 'multiple' : undefined"
      :disabled="disabled"
      :loading="loading"
      :options="options"
      show-search
      allow-clear
      :filter-option="false"
      :placeholder="placeholder || (title ? '选择' + title : '搜索关联记录')"
      style="width: 100%"
      @search="search"
      @change="changed"
      @dropdown-visible-change="refreshOutdated"
    />
    <div v-if="error" class="reference-error">{{ error }}</div>
  </div>
</template>
<style scoped>
.reference-error {
  font-size: 12px;
  color: #dc2626;
  line-height: 1.5;
  margin-top: 4px;
}
</style>
