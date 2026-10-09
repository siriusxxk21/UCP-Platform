<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { resolveViewForm } from '@/nocode/default-form'
import { recordDisplay } from '@/nocode/record-display'
import { dynamicQueryField, supportsAdvancedQuery } from '@/nocode/runtime-list'
import { hasRuntimeFieldId } from '@/nocode/runtime-field-projection'
import type { DataViewModel, ChildFilter } from '@/types/nocode/data-view'
import type { BusinessRow, Aggregate } from '@/types/nocode/runtime'
import type { ApplicationResource } from '@/types/nocode/application'
import type { ViewConfig, FormConfig } from '@/types/nocode/application-ui'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import { FieldType } from '@/types/nocode/enums'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import RecordEditor from './RecordEditor.vue'
import RichTextDisplay from '../../components/RichTextDisplay.vue'
import '../../management-tables.css'
import { useInheritedReadOnly } from '@/nocode/record-read-only'
import { useRuntimeDataRefresh } from '@/nocode/runtime-data'
const readOnly = useInheritedReadOnly()
const props = withDefaults(
  defineProps<{
    applicationId: string
    objectId: string
    viewId: string
    parent: BusinessRow
    model: DataViewModel
    filters: ChildFilter[]
    resources?: ApplicationResource[]
    refreshKey?: number
    inheritDefaultForm?: boolean
  }>(),
  { inheritDefaultForm: true }
)
const emit = defineEmits<{ filter: [value: ChildFilter]; editParent: []; saved: [] }>()
const api = useNocodePlatform().runtime
const sections = computed(() => props.model.composition?.sections.filter(s => s.showTable !== false) || [])
const active = ref(sections.value[0]?.id || '')
const section = computed(() => sections.value.find(s => s.id === active.value))
const config = computed(() => props.model.sections[active.value])
const rows = ref<BusinessRow[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(10),
  busy = ref(false),
  error = ref('')
const search = ref(''),
  conditions = ref<DynamicSearchCondition | null>(null),
  requireMatch = ref(false),
  sort = ref<string>(),
  descending = ref(false)
const initialFilter = computed(() => props.filters.find(f => f.sectionId === active.value))
const fields = computed(
  () => config.value?.fields.filter(hasRuntimeFieldId).filter(f => section.value?.fieldIds.includes(f.id)) || []
)
const advancedFields = computed(() => {
  const current = config.value
  return (
    current?.fields
      .filter(hasRuntimeFieldId)
      .filter(supportsAdvancedQuery)
      .map(f => dynamicQueryField(f, current.fieldOptions[f.id])) || []
  )
})
const rootId = computed(() => props.parent.parentId || props.parent.id)
const targetModel = computed(() => config.value?.recordModel)
const incoming = computed(() =>
  section.value?.binding?.direction === 'INCOMING'
    ? targetModel.value?.object.relations.find(r => r.id === section.value?.binding?.relationId)
    : undefined
)
const formResolution = computed(() => {
  try {
    const relatedView = props.resources?.find(r => r.id === section.value?.viewId)?.config as unknown as
      ViewConfig | undefined
    const objectId = section.value?.objectId || targetModel.value?.object.objectId
    const resource =
      objectId && (relatedView?.formId || props.inheritDefaultForm !== false)
        ? resolveViewForm(props.resources || [], objectId, relatedView?.formId)
        : undefined
    return { resource, error: '' }
  } catch (e) {
    return { resource: undefined, error: errorMessage(e) }
  }
})
const form = computed(() => formResolution.value.resource?.config as unknown as FormConfig | undefined)
const editable = (row: BusinessRow) =>
  !readOnly.value && !!targetModel.value?.writable && row.permissions?.actions.includes('UPDATE')
const canCreate = computed(
  () =>
    !readOnly.value &&
    !!targetModel.value?.writable &&
    !!incoming.value?.fieldId &&
    incoming.value.kind !== 'MANY_TO_MANY' &&
    targetModel.value.permissions.actions.includes('CREATE')
)
const columns = computed(() => [
  ...fields.value.map(f => ({ title: f.name, key: f.id, width: 170, sorter: supportsAdvancedQuery(f) })),
  ...(targetModel.value?.permissions.actions.includes('UPDATE')
    ? [{ title: '操作', key: 'actions', width: 100, fixed: 'right' as const }]
    : [])
])
function display(record: BusinessRow, key: string) {
  return recordDisplay(
    record,
    key,
    config.value?.fieldOptions[key],
    fields.value.find(field => field.id === key)
  )
}
let generation = 0
onBeforeUnmount(() => generation++)
// quiet：静默重取当前页，不出 loading，行和总数没变不动界面；不占用代次，不打断正在打开的编辑。
async function load(quiet = false) {
  if (quiet && busy.value) return
  const current = quiet ? generation : ++generation
  const selectedSection = section.value,
    recordId = rootId.value
  if (!selectedSection || !recordId) {
    busy.value = false
    rows.value = []
    total.value = 0
    error.value = selectedSection && !recordId ? '主记录尚未保存，暂不能查询子表' : ''
    return
  }
  if (!quiet) {
    busy.value = true
    error.value = ''
  }
  try {
    const result = await api.viewChildren({
      applicationId: props.applicationId,
      objectId: props.objectId,
      viewId: props.viewId,
      sectionId: selectedSection.id,
      recordId,
      pageNo: page.value,
      pageSize: size.value,
      search: initialFilter.value?.search,
      equal: initialFilter.value?.equal,
      conditions: initialFilter.value?.conditions,
      sortFieldId: sort.value,
      descending: descending.value
    })
    if (current !== generation) return
    if (quiet && result.total === total.value && JSON.stringify(result.list) === JSON.stringify(rows.value)) return
    rows.value = result.list
    total.value = result.total
  } catch (e) {
    if (current === generation && !quiet) error.value = errorMessage(e)
  } finally {
    if (current === generation && !quiet) busy.value = false
  }
}
useRuntimeDataRefresh({
  interest: () => {
    // 关联对象的子表看那个对象；内部明细随主记录一起保存，看主对象。
    const objectId = section.value?.objectId || targetModel.value?.object.objectId || props.objectId
    return rootId.value && section.value ? { applicationId: props.applicationId, objectIds: [objectId] } : undefined
  },
  refresh: () => load(true)
})
watch(
  [
    active,
    rootId,
    () => props.refreshKey,
    () => JSON.stringify(initialFilter.value),
    () => props.applicationId,
    () => props.objectId,
    () => props.viewId
  ],
  (current, previous) => {
    if (current[0] !== previous?.[0]) {
      sort.value = undefined
      descending.value = false
    }
    rows.value = []
    total.value = 0
    page.value = 1
    size.value = section.value?.pageSize || 10
    search.value = initialFilter.value?.search || ''
    conditions.value = initialFilter.value?.conditions || null
    requireMatch.value = !!initialFilter.value?.requireMatch
    void load()
  },
  { immediate: true }
)
function applyFilter() {
  emit('filter', {
    sectionId: active.value,
    search: search.value,
    conditions: conditions.value,
    requireMatch: requireMatch.value
  })
}
const editing = ref<Aggregate>(),
  open = ref(false)
async function edit(row?: BusinessRow) {
  const selectedSection = section.value,
    objectId = selectedSection?.objectId || targetModel.value?.object.objectId,
    recordId = row?.id,
    current = generation
  if (!selectedSection || !objectId || !rootId.value || (row && !recordId)) {
    error.value = '关联记录或所属主记录尚未保存，请刷新后重试'
    return
  }
  try {
    if (formResolution.value.error) throw new Error(formResolution.value.error)
    const record = recordId ? await api.get(props.applicationId, objectId, recordId) : undefined
    if (current !== generation) return
    editing.value = record
    open.value = true
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function saved() {
  open.value = false
  emit('saved')
  void load()
}
</script>
<template>
  <div class="data-view-children">
    <a-tabs v-model:active-key="active" size="small">
      <a-tab-pane v-for="s in sections" :key="s.id" :tab="s.name" />
    </a-tabs>
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-alert v-if="formResolution.error" :message="formResolution.error" type="error" show-icon />
    <template v-if="section && config">
      <p class="explanation">
        {{ section.detailId ? '内部明细：编辑时与主记录整单保存。' : '关联对象：独立保存，通过关系关联当前主记录。' }}
      </p>
      <OsTablePage
        :key="active"
        :title="section.name"
        :columns="columns"
        :data-source="rows"
        row-key="id"
        :loading="busy"
        :pagination="{ current: page, pageSize: size, total, showSizeChanger: true }"
        :scroll="{ x: 'max-content' }"
        show-column-settings
        :column-settings-key="`nocode-child:${applicationId}:${viewId}:${active}`"
        resizable
        :show-advanced-search="true"
        advanced-search-mode="dynamic"
        :dynamic-search-fields="advancedFields"
        @dynamic-search="conditions = $event"
        @search="applyFilter"
        @change="
          (p, _f, s) => {
            page = p.current || 1
            size = p.pageSize || 10
            sort = s?.order ? String(s.columnKey) : undefined
            descending = s?.order === 'descend'
            load()
          }
        "
      >
        <template #search>
          <a-space wrap>
            <a-input v-model:value="search" placeholder="搜索此子表" @press-enter="applyFilter" />
            <a-checkbox v-model:checked="requireMatch">同时只显示含匹配子记录的主记录</a-checkbox>
            <a-button @click="applyFilter">应用筛选</a-button>
          </a-space>
        </template>
        <template #toolbar>
          <a-button
            v-if="!readOnly && section.detailId && parent.permissions?.actions.includes('UPDATE')"
            @click="emit('editParent')"
          >
            编辑整单明细
          </a-button>
          <a-button v-if="canCreate" type="primary" @click="edit()">新增关联记录</a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <a-button v-if="column.key === 'actions' && editable(record)" type="link" @click="edit(record)">
            编辑
          </a-button>
          <RichTextDisplay
            v-else-if="fields.some(f => f.id === column.key && f.type === FieldType.RICH_TEXT)"
            :value="record.values[String(column.key)]"
            compact
          />
          <span
            v-else-if="column.key !== '_index'"
            :title="display(record, String(column.key))"
            :class="{
              'nocode-table-multiline': fields.some(f => f.id === column.key && f.type === FieldType.TEXTAREA)
            }"
          >
            {{ display(record, String(column.key)) }}
          </span>
        </template>
      </OsTablePage>
    </template>
    <a-modal
      v-model:open="open"
      :title="editing ? '编辑关联记录' : '新增关联记录'"
      :footer="null"
      :mask-closable="false"
      :closable="false"
      width="min(1100px, 96vw)"
      destroy-on-close
    >
      <RecordEditor
        v-if="open && targetModel && section && rootId && !formResolution.error"
        :application-id="applicationId"
        :model="targetModel"
        :record="editing"
        :form="form"
        :form-id="formResolution.resource?.id"
        :context="{ pageId: viewId, nodeId: section.id, recordId: rootId }"
        :locked-values="incoming?.fieldId ? { [incoming.fieldId]: rootId } : undefined"
        merge-on-conflict
        @saved="saved"
        @cancel="open = false"
      />
    </a-modal>
  </div>
</template>
<style scoped>
.data-view-children {
  padding: 8px 16px 16px;
  border-inline-start: 3px solid var(--os-primary-color, #1677ff);
  background: var(--os-bg-container, #fff);
}
.explanation {
  color: var(--os-text-secondary, #666);
  margin-bottom: 12px;
}
</style>
