<script setup lang="ts">
import { computed, onMounted, ref, markRaw, provide } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { fieldRelation } from '@/nocode/business-fields'
import { selectionSource, selectionPreviewKey } from '@/nocode/selection'
import SelectionField from './SelectionField.vue'
import { boundFields } from '@/nocode/application-ui'
import { dynamicQueryField, normalizeAdvancedQuery, supportsAdvancedQuery } from '@/nocode/runtime-list'
import { errorMessage } from '@/nocode/data-center'
import { parseTaskView } from '@/nocode/page-schema'
import { taskStates, taskPriorities } from '@/nocode/task-center'
import type { TaskViewConfig, FormConfig } from '@/types/nocode/application-ui'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import type { TaskTemplate, TaskState, TaskPriority } from '@/types/nocode/task-center'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import OsDynamicSearch from '@/components/ucp-table-page/OsDynamicSearch.vue'

const props = defineProps<{
  applicationId?: string
  value?: unknown
  resourceId?: unknown
  contextObjectId?: string
  resources: ApplicationResource[]
  objects: Record<string, PublishedObject>
  readOnly?: boolean
}>()
const emit = defineEmits<{ change: [value: string] }>()
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId || '',
    objects: Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    }))
  }))
)
const view = computed(() => parseTaskView(props.value) || {})
// 历史紧急排序仍随原配置保存，但不再提供可编辑入口。
const activeSort = computed(() => (view.value.sort?.field === 'urgency' ? null : view.value.sort))
const form = computed(() =>
  props.resources.find(
    resource =>
      resource.id === (view.value.businessFormId || (!props.contextObjectId ? props.resourceId : null)) &&
      resource.kind === 'FORM'
  )
)
const object = computed(() => props.objects[String(form.value?.config.objectId)]?.definition)
const fields = computed(() => {
  if (!form.value || !object.value) return []
  const ids = new Set(boundFields((form.value.config as unknown as FormConfig).nodes))
  return object.value.fields.filter(field => ids.has(field.id!))
})
const queryFields = computed(() => fields.value.filter(supportsAdvancedQuery))
const searchFields = computed(() =>
  queryFields.value.map(field => {
    const options = object.value?.fieldOptions[field.id!]
    const entry = dynamicQueryField(
      field,
      options,
      options?.options?.filter(option => !option.disabled).map(option => ({ value: option.code, label: option.label }))
    )
    const source = selectionSource(field, options)
    if (
      props.applicationId &&
      (fieldRelation(object.value!.relations, field.id) || (source && source.kind !== 'LOCAL_OPTIONS'))
    ) {
      entry.type = 'select'
      entry.operators = ['eq', 'neq', 'in']
      entry.valueComponent = markRaw(SelectionField)
      entry.valueProps = {
        applicationId: props.applicationId,
        objectId: object.value!.objectId,
        fieldId: field.id,
        preview: true,
        compact: true,
        placeholder: '选择固定范围'
      }
    }
    return entry
  })
)
const columns = computed(() => [
  { value: 'title', label: '任务名称（固定）' },
  ...fields.value.map(field => ({ value: `business:${field.id}`, label: field.name })),
  { value: 'owner', label: '业务归属 / 执行人' },
  { value: 'status', label: '执行状态' },
  { value: 'time', label: '预计时间' },
  { value: 'priority', label: '优先级' }
])
const selectedColumns = computed(() =>
  view.value.columnKeys?.length
    ? ['title', ...view.value.columnKeys.filter(key => key !== 'title' && key !== 'actions')]
    : columns.value.map(column => column.value)
)
const templates = ref<TaskTemplate[]>([]),
  error = ref(''),
  conditionsOpen = ref(false)
const api = useNocodePlatform().taskCenter
onMounted(async () => {
  try {
    templates.value = await api.templates()
  } catch (e) {
    error.value = errorMessage(e)
  }
})
function update(values: Partial<TaskViewConfig>) {
  if (!props.readOnly) emit('change', JSON.stringify({ ...view.value, ...values }))
}
function updateFilter(values: Partial<NonNullable<TaskViewConfig['taskFilter']>>) {
  update({ taskFilter: { ...view.value.taskFilter, ...values } })
}
function changeForm(value: string | undefined) {
  update({ businessFormId: value || null, conditions: null, columnKeys: [], sort: null })
}
function toggleColumn(key: string, checked: boolean) {
  update({
    columnKeys: checked ? [...selectedColumns.value, key] : selectedColumns.value.filter(value => value !== key)
  })
}
function moveColumn(index: number, delta: number) {
  const keys = [...selectedColumns.value],
    target = index + delta
  if (index <= 0 || target <= 0 || target >= keys.length) return
  ;[keys[index], keys[target]] = [keys[target]!, keys[index]!]
  update({ columnKeys: keys })
}
function applyConditions(value: DynamicSearchCondition | null) {
  try {
    update({ conditions: normalizeAdvancedQuery(value, queryFields.value) })
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
const sorts = computed(() => [
  { value: 'createdAt', label: '创建时间' },
  { value: 'expectedEnd', label: '预计完成时间' },
  { value: 'title', label: '任务名称' },
  { value: 'priority', label: '优先级' },
  ...queryFields.value.map(field => ({ value: `business:${field.id}`, label: field.name }))
])
</script>
<template>
  <section class="task-view-editor">
    <a-alert v-if="error" type="warning" :message="error" show-icon />
    <a-form-item v-if="contextObjectId || !resourceId" label="业务数据筛选（可选）">
      <a-select
        :value="view.businessFormId || undefined"
        :disabled="readOnly"
        allow-clear
        :options="
          resources
            .filter(resource => resource.kind === 'FORM')
            .map(resource => ({ value: resource.id, label: resource.name }))
        "
        placeholder="不限制业务表单，展示任务基本信息"
        @change="changeForm"
      />
      <div class="muted">
        {{ contextObjectId ? '仅展示当前记录的关联任务。' : '仅展示属于当前应用且有权查看的任务。' }}
        选择后进一步限定任务的业务数据对象，并可显示、筛选其字段。
      </div>
    </a-form-item>
    <a-form-item label="固定任务状态">
      <a-select
        :value="view.taskFilter?.statuses || []"
        mode="multiple"
        :disabled="readOnly"
        :options="Object.entries(taskStates).map(([value, label]) => ({ value, label }))"
        placeholder="全部状态"
        @change="(value: TaskState[]) => updateFilter({ statuses: value })"
      />
    </a-form-item>
    <a-form-item label="固定优先级">
      <a-select
        :value="view.taskFilter?.priorities || []"
        mode="multiple"
        :disabled="readOnly"
        :options="Object.entries(taskPriorities).map(([value, label]) => ({ value, label }))"
        placeholder="全部"
        @change="(value: TaskPriority[]) => updateFilter({ priorities: value })"
      />
    </a-form-item>
    <a-form-item label="固定任务类别">
      <a-select
        :value="view.taskFilter?.category || undefined"
        :disabled="readOnly"
        allow-clear
        :options="[
          { value: 'PROJECT', label: '有关联业务记录' },
          { value: 'DAILY', label: '未关联业务记录' }
        ]"
        placeholder="全部类别"
        @change="(value: 'PROJECT' | 'DAILY' | undefined) => updateFilter({ category: value || null })"
      />
    </a-form-item>
    <a-form-item label="任务模板范围">
      <a-select
        :value="view.templateIds || []"
        :disabled="readOnly"
        mode="multiple"
        :options="templates.filter(item => item.publishedVersion).map(item => ({ value: item.id, label: item.name }))"
        placeholder="全部模板及直接发起的任务"
        @change="(value: string[]) => update({ templateIds: value })"
      />
    </a-form-item>
    <a-form-item v-if="queryFields.length" label="固定业务条件">
      <a-space>
        <a-button :disabled="readOnly" @click="conditionsOpen = true">配置条件</a-button>
        <a-button v-if="view.conditions" type="link" :disabled="readOnly" @click="update({ conditions: null })">
          清除
        </a-button>
        <a-tag v-if="view.conditions">已配置</a-tag>
      </a-space>
    </a-form-item>
    <a-form-item label="显示列与顺序">
      <div v-for="(key, index) in selectedColumns" :key="key" class="task-view-editor__column">
        <span>{{ columns.find(column => column.value === key)?.label || '已失效字段' }}</span>
        <a-space>
          <a-button size="small" :disabled="readOnly || index <= 1" @click="moveColumn(index, -1)">上移</a-button>
          <a-button
            size="small"
            :disabled="readOnly || index === 0 || index === selectedColumns.length - 1"
            @click="moveColumn(index, 1)"
          >
            下移
          </a-button>
          <a-button size="small" :disabled="readOnly || key === 'title'" @click="toggleColumn(key, false)">
            隐藏
          </a-button>
        </a-space>
      </div>
      <a-select
        :value="undefined"
        :disabled="readOnly"
        placeholder="添加显示列"
        :options="columns.filter(column => !selectedColumns.includes(column.value))"
        @change="(value: string) => toggleColumn(value, true)"
      />
    </a-form-item>
    <a-form-item label="默认排序">
      <a-select
        :value="activeSort?.field"
        :disabled="readOnly"
        :options="sorts"
        allow-clear
        placeholder="系统默认"
        @change="
          (value: string | undefined) =>
            update({ sort: value ? { field: value, descending: activeSort?.descending ?? true } : null })
        "
      />
      <a-radio-group
        v-if="activeSort"
        :value="activeSort.descending"
        :disabled="readOnly"
        @change="
          (event: { target: { value: boolean } }) =>
            update({ sort: { field: activeSort!.field, descending: event.target.value } })
        "
      >
        <a-radio :value="false">升序</a-radio>
        <a-radio :value="true">降序</a-radio>
      </a-radio-group>
    </a-form-item>
    <p class="muted">发布后固定范围始终生效，使用者的临时筛选只能进一步缩小结果。</p>
    <OsDynamicSearch
      v-model:open="conditionsOpen"
      :model-value="view.conditions || null"
      :fields="searchFields"
      :allow-save="false"
      strict
      @confirm="applyConditions"
    />
  </section>
</template>
<style scoped>
.task-view-editor {
  margin-top: 16px;
}
.task-view-editor__column {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.task-view-editor__column > span {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
