<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { createTaskEntryApi } from '@/api/nocode/task-entry'
import request from '@/utils/request'
import type { TaskBinding, TaskRecordRef } from '@/types/nocode/task-center'
import type { ApplicationRow } from '@/types/nocode/application'
import type { RecordModel } from '@/types/nocode/runtime'
import type { TaskEntryLocator } from '@/types/nocode/task-entry'

const record = defineModel<TaskRecordRef | null>({ required: true })
const props = withDefaults(
  defineProps<{ placeholder?: string; binding?: TaskBinding | null; applicationId?: string }>(),
  {
    placeholder: '选择关联业务记录'
  }
)
const platform = useNocodePlatform(),
  entryApi = createTaskEntryApi(request),
  appId = ref(props.applicationId || record.value?.applicationId),
  objectId = ref(record.value?.objectId)
const bound = computed(() => !!props.binding),
  boundModel = ref<RecordModel>(),
  boundEntry = ref<TaskEntryLocator>(),
  bindingLoading = ref(false)
const apps = ref<ApplicationRow[]>([]),
  objects = ref<Array<{ value: string; label: string }>>([]),
  rows = ref<Array<{ value: string; label: string }>>([]),
  loading = ref(false),
  error = ref('')
let generation = 0,
  bindingGeneration = 0,
  objectGeneration = 0
onMounted(async () => {
  if (bound.value || props.applicationId) return
  try {
    apps.value = await platform.runtime.mine()
  } catch (e) {
    error.value = errorMessage(e)
  }
})
watch(
  () => props.applicationId,
  id => {
    if (bound.value || appId.value === id) return
    record.value = null
    objectId.value = undefined
    appId.value = id
  }
)
watch(
  appId,
  async id => {
    const token = ++objectGeneration
    if (bound.value) return
    generation++
    loading.value = false
    objects.value = []
    rows.value = []
    if (!id) return
    try {
      const app = await platform.runtime.application(id)
      const results = await Promise.allSettled(app.definition.objects.map(o => platform.runtime.model(id, o.objectId)))
      if (token === objectGeneration && !bound.value && appId.value === id)
        objects.value = results.flatMap(r =>
          r.status === 'fulfilled' ? [{ value: r.value.object.objectId, label: r.value.object.objectName }] : []
        )
    } catch (e) {
      if (token === objectGeneration && !bound.value && appId.value === id) error.value = errorMessage(e)
    }
  },
  { immediate: true }
)
/** 任务业务内容已选表单时，记录候选沿用同一业务对象及入口授权，避免重新选择或越界回退。 */
async function loadBinding(clearSelection = false) {
  const token = ++bindingGeneration,
    binding = props.binding
  generation++
  loading.value = false
  boundModel.value = undefined
  boundEntry.value = undefined
  rows.value = []
  error.value = ''
  if (clearSelection) record.value = null
  if (!binding) {
    bindingLoading.value = false
    return
  }
  bindingLoading.value = true
  appId.value = binding.applicationId
  objectId.value = undefined
  try {
    let model: RecordModel
    let entry: TaskEntryLocator | undefined
    if (binding.entryId) {
      const context = await entryApi.context({ applicationId: binding.applicationId, entryId: binding.entryId })
      if (context.config.mode !== 'LIST') throw new Error('此入口仅支持填写新记录；关联已有记录请选择列表办理入口。')
      model = context.model
      entry = { applicationId: binding.applicationId, entryId: binding.entryId, version: context.entry.version }
    } else {
      model = (await platform.taskCenter.formPreview(binding)).model
    }
    if (token !== bindingGeneration) return
    boundModel.value = model
    boundEntry.value = entry
    objectId.value = model.object.objectId
    if (
      record.value &&
      (record.value.applicationId !== binding.applicationId || record.value.objectId !== objectId.value)
    )
      record.value = null
    await search()
  } catch (e) {
    if (token === bindingGeneration) error.value = errorMessage(e)
  } finally {
    if (token === bindingGeneration) bindingLoading.value = false
  }
}
watch(
  () => JSON.stringify(props.binding || null),
  (value, previous) => {
    if (value === 'null' && previous === undefined) return
    return loadBinding(previous !== undefined && value !== previous)
  },
  { immediate: true }
)
async function search(keyword = '') {
  if (!appId.value || !objectId.value || (bound.value && !boundModel.value)) return
  const token = ++generation,
    applicationId = appId.value,
    selectedObject = objectId.value,
    entry = boundEntry.value
  loading.value = true
  error.value = ''
  try {
    const query = {
      applicationId,
      objectId: selectedObject,
      pageNo: 1,
      pageSize: 50,
      search: keyword,
      descending: true
    }
    const [model, page] = await Promise.all([
      boundModel.value || platform.runtime.model(applicationId, selectedObject),
      entry ? entryApi.page(entry, query) : platform.runtime.page(query)
    ])
    if (token === generation && applicationId === appId.value && selectedObject === objectId.value)
      rows.value = page.list.map(row => ({
        value: row.id!,
        label: String(row.displayValues?.[model.object.titleFieldId] ?? row.values[model.object.titleFieldId] ?? row.id)
      }))
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(
  objectId,
  () => {
    if (bound.value) return
    generation++
    rows.value = []
    loading.value = false
    void search()
  },
  { immediate: true }
)
function choose(value: string | undefined) {
  record.value =
    value && appId.value && objectId.value
      ? {
          applicationId: appId.value,
          objectId: objectId.value,
          recordId: value,
          label: rows.value.find(r => r.value === value)?.label || value
        }
      : null
}
</script>
<template>
  <div class="task-record-picker">
    <a-select
      v-if="!bound && !applicationId"
      v-model:value="appId"
      allow-clear
      show-search
      option-filter-prop="label"
      placeholder="所属应用"
      :options="apps.map(a => ({ value: a.id, label: a.name }))"
      @change="
        () => {
          objectId = undefined
          record = null
        }
      "
    />
    <a-select
      v-if="appId && !bound"
      v-model:value="objectId"
      allow-clear
      show-search
      option-filter-prop="label"
      placeholder="数据对象"
      :options="objects"
      @change="
        () => {
          record = null
        }
      "
    />
    <span v-if="boundModel" class="task-list__hint">业务对象：{{ boundModel.object.objectName }}</span>
    <a-select
      v-if="objectId || bound"
      :value="record?.recordId"
      :loading="loading || bindingLoading"
      :disabled="bound && (!boundModel || bindingLoading)"
      show-search
      allow-clear
      :filter-option="false"
      :placeholder="placeholder"
      :options="rows"
      @search="search"
      @change="(value: unknown) => choose(value ? String(value) : undefined)"
    />
    <a-alert v-if="error" type="warning" :message="error" />
  </div>
</template>
<style scoped>
.task-record-picker {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.task-record-picker .ant-select {
  min-width: 150px;
  flex: 1;
}
</style>
