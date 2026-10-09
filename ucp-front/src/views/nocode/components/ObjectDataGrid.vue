<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import type { TableColumnType } from 'ant-design-vue'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useNocodePlatform } from '@/nocode/platform'
import { defaultFieldOptions, errorMessage } from '@/nocode/data-center'
import { changedObjectData, objectDataInput, objectDataValue } from '@/nocode/object-data'
import { documentProblems, isDocumentRejection, newRowKey } from '@/nocode/document-save'
import { recordDisplay } from '@/nocode/record-display'
import {
  calculationQueryReady,
  calculationValueField,
  storedOrderedCalculation
} from '@/nocode/calculation-presentation'
import { orderedReadiness, orderedStateLabel } from '@/nocode/ordered-calculation'
import { selectionSource } from '@/nocode/selection'
import { fieldTypes } from '@/nocode/object-draft'
import { FieldType, MemberState } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { Aggregate, BusinessRow } from '@/types/nocode/runtime'
import type { ObjectDataDelete, ObjectDataDeletePreview, ObjectDataModel } from '@/types/nocode/object-data'
import type { DocumentProblem } from '@/types/nocode/document-policy'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import FieldValueEditor from './FieldValueEditor.vue'
import ObjectDataSelectionField from './ObjectDataSelectionField.vue'
import ObjectDataClearColumn from './ObjectDataClearColumn.vue'
import OrderedCalculationCalibration from './OrderedCalculationCalibration.vue'

const props = withDefaults(
  defineProps<{
    objectId: string
    publishedVersion?: number | null
    fieldId?: string
    detailId?: string
    recordIds?: string[]
  }>(),
  { recordIds: () => [] }
)
const emit = defineEmits<{ editing: [value: boolean] }>()
const platform = useNocodePlatform(),
  api = platform.objectData,
  router = useRouter()
const authorized = computed(
  () => platform.hasPermission('nocode:object:query') && platform.hasPermission('nocode:object:manage')
)
const snapshot = ref<ObjectDataModel>(),
  loadingModel = ref(false),
  failure = ref(''),
  saving = ref(false),
  preparing = ref(false)
const editingRow = ref<BusinessRow>(),
  editing = reactive<Record<string, string | null>>({}),
  uploadStates = reactive<Record<string, { pending: boolean; failed: boolean }>>({})
const requestKey = ref('')
const uncertainSave = ref(false)
const problems = ref<DocumentProblem[]>([])
const gridRoot = ref<HTMLElement>()
const locating = ref(props.recordIds.length > 0)
const clearColumnOpen = ref(false)
const calibrationOpen = ref(false)
const fields = computed(() => snapshot.value?.model.object.fields || [])
const orderedStates = computed(() => orderedReadiness(snapshot.value?.model.orderedStates))
const hasStoredOrdered = computed(() =>
  fields.value.some(field => storedOrderedCalculation(snapshot.value?.model.object.fieldOptions[field.id || field.key]))
)
const fieldsById = computed(() => Object.fromEntries(fields.value.map(field => [field.id || field.key, field])))
const activeDetails = computed(() =>
  (snapshot.value?.model.object.details || []).filter(detail => detail.state === MemberState.ACTIVE)
)
const writable = computed(() => authorized.value && snapshot.value?.model.writable === true)
const busy = computed(() => saving.value || preparing.value)
const creating = computed(() => editingRow.value?.id === '__new__')
const canCreate = computed(() => writable.value && !snapshot.value?.createRestriction)
const options = (field: ObjectField) =>
  snapshot.value?.model.object.fieldOptions[field.id || field.key] || defaultFieldOptions()
function readonlyReason(field: ObjectField) {
  const id = field.id || field.key
  if (
    creating.value &&
    writable.value &&
    snapshot.value?.model.keyFieldId === id &&
    !snapshot.value.model.generatedKey &&
    !options(field).generated &&
    !options(field).autoNumber
  )
    return ''
  if (snapshot.value?.readonlyReasons[id]) return snapshot.value.readonlyReasons[id]
  if (!writable.value) return '当前对象数据为只读'
  if (snapshot.value?.model.writeFields && !snapshot.value.model.writeFields.includes(id)) return '此列由系统维护'
  return ''
}
const writeFields = computed(() => fields.value.filter(field => !readonlyReason(field)))
const selectedField = computed(() => fields.value.find(field => field.id === props.fieldId))
const plainFields = computed(() =>
  fields.value.filter(
    field =>
      calculationQueryReady(options(field), orderedStates.value[field.id || field.key]) &&
      ![FieldType.IMAGE, FieldType.ATTACHMENT, FieldType.RICH_TEXT, FieldType.MULTI_SELECT, FieldType.URL].some(
        type => type === field.type
      )
  )
)
function numeric(field: ObjectField) {
  if (snapshot.value?.model.object.relations.some(relation => relation.fieldId === field.id)) return false
  const type = [FieldType.FORMULA, FieldType.SUMMARY].some(value => value === field.type)
    ? options(field).resultType
    : field.type
  return [FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT].some(value => value === type)
}
function displayValue(row: BusinessRow, field: ObjectField) {
  const state = orderedStates.value[field.id || field.key]
  if (state !== 'READY' && storedOrderedCalculation(options(field))) return orderedStateLabel(state)
  return recordDisplay(row, field.id || field.key, options(field), field)
}
function columnWidth(field: ObjectField) {
  if (field.id === props.fieldId) return 200
  if (numeric(field)) return 130
  if (field.type === FieldType.BOOLEAN) return 90
  if (field.type === FieldType.DATE || field.type === FieldType.TIME) return 130
  if (field.type === FieldType.DATETIME) return 170
  if (field.type === FieldType.TEXTAREA || field.type === FieldType.RICH_TEXT) return 200
  return 160
}
function physicalType(key: unknown) {
  return snapshot.value?.columnTypes?.[String(key)] || '物理列未找到'
}
const columns = computed<TableColumnType[]>(() => [
  { title: '记录 ID', key: '__id', width: 95, fixed: 'left' },
  ...fields.value.map(field => ({
    title: field.name + (field.required ? ' *' : ''),
    customHeaderCell: () => ({
      title: `${field.code} · ${snapshot.value?.model.object.relations.some(relation => relation.fieldId === field.id) ? '单选（对象引用）' : fieldTypes.find(item => item.value === field.type)?.label || field.type}`
    }),
    key: field.id || field.key,
    width: columnWidth(field),
    className: field.id === props.fieldId ? 'object-data-focused' : undefined
  })),
  { title: '操作', key: '__actions', width: activeDetails.value.length ? 200 : 150, fixed: 'right' }
])

const table = useOsTablePage<BusinessRow, { search: string; fieldId: string; operator: string; value: string | null }>({
  immediate: false,
  defaultPageSize: 20,
  queryMode: 'submitted',
  correctOutOfRange: true,
  clearDataOnError: true,
  defaultQuery: () => ({ search: '', fieldId: '', operator: 'eq', value: null }),
  onError: cause => {
    failure.value = errorMessage(cause)
  },
  fetchFn: async query => {
    if (!snapshot.value) return { list: [], total: 0 }
    const filterField = fieldsById.value[query.fieldId]
    const conditions: DynamicSearchCondition | undefined = filterField
      ? {
          logic: 'AND',
          items: [
            {
              type: 'condition',
              field: query.fieldId,
              operator: query.operator === 'isNull' ? 'isNull' : query.operator === 'notNull' ? 'notNull' : 'eq',
              value: objectDataValue(calculationValueField(filterField, options(filterField)), query.value)
            }
          ]
        }
      : undefined
    return api.page({
      objectId: props.objectId,
      pageNo: query.pageNum,
      pageSize: query.pageSize,
      ...(locating.value ? { recordIds: props.recordIds.slice(0, 100) } : { search: query.search, conditions })
    })
  }
})
const { tableData, loading, pagination, queryForm } = table
const rows = computed(() =>
  creating.value && editingRow.value ? [editingRow.value, ...tableData.value] : tableData.value
)
const filterField = computed(() => fieldsById.value[queryForm.fieldId])
watch(
  () => [props.fieldId, tableData.value],
  async () => {
    if (!props.fieldId) return
    await nextTick()
    const body = gridRoot.value?.querySelector<HTMLElement>('.ant-table-body')
    const cell = body?.querySelector<HTMLElement>('.object-data-focused')
    if (body && cell)
      body.scrollLeft += cell.getBoundingClientRect().left - body.getBoundingClientRect().left - body.clientWidth / 3
  }
)

let generation = 0
function closeCalibration() {
  calibrationOpen.value = false
  void refresh()
}
async function refresh() {
  if (!authorized.value || !props.publishedVersion || editingRow.value) return
  const current = ++generation
  loadingModel.value = true
  failure.value = ''
  try {
    const result = await api.model(props.objectId)
    if (current !== generation) return
    snapshot.value = result
    await table.fetchData()
  } catch (cause) {
    if (current === generation) failure.value = errorMessage(cause)
  } finally {
    if (current === generation) loadingModel.value = false
  }
}
watch(
  () => [props.objectId, props.publishedVersion, authorized.value],
  () => {
    clearColumnOpen.value = false
    calibrationOpen.value = false
    if (editingRow.value) {
      failure.value = '对象版本已变化。当前输入已保留，请取消行编辑并刷新结构后重新核对。'
      return
    }
    snapshot.value = undefined
    tableData.value = []
    pagination.current = 1
    void refresh()
  },
  { immediate: true }
)
watch(
  () => props.recordIds.join(','),
  () => {
    if (editingRow.value) return
    locating.value = props.recordIds.length > 0
    pagination.current = 1
    void table.fetchData()
  }
)
watch(editingRow, row => emit('editing', !!row))
function query() {
  if (editingRow.value || locating.value) return
  failure.value = ''
  table.handleQuery()
}
function clearLocation() {
  if (editingRow.value) return
  locating.value = false
  pagination.current = 1
  void table.fetchData()
}
function columnCleared() {
  clearColumnOpen.value = false
  void refresh()
}
function changePage(page: { current?: number; pageSize?: number }) {
  if (editingRow.value) {
    message.info('请先保存或取消当前行，再切换分页')
    return
  }
  table.handleTableChange(page)
}
function resetEditing(row?: BusinessRow) {
  for (const key of Object.keys(editing)) delete editing[key]
  for (const key of Object.keys(uploadStates)) delete uploadStates[key]
  editingRow.value = row
  requestKey.value = ''
  uncertainSave.value = false
  problems.value = []
  failure.value = ''
  if (row) {
    for (const field of writeFields.value) {
      const id = field.id || field.key
      if (row.id !== '__new__') editing[id] = objectDataInput(field, row.values[id])
      else if (options(field).defaultValue != null) editing[id] = options(field).defaultValue ?? null
    }
  }
}
function create() {
  if (canCreate.value && !editingRow.value) resetEditing({ id: '__new__', revision: null, values: {} })
}
async function edit(row: BusinessRow) {
  if (!row.id || !writable.value || editingRow.value) return
  preparing.value = true
  failure.value = ''
  try {
    const fresh = await api.get(props.objectId, row.id)
    resetEditing(fresh.record)
    const position = tableData.value.findIndex(item => item.id === row.id)
    if (position >= 0) tableData.value[position] = fresh.record
  } catch (cause) {
    failure.value = errorMessage(cause)
  } finally {
    preparing.value = false
  }
}
function update(field: ObjectField, value: string | null) {
  if (uncertainSave.value) return
  editing[field.id || field.key] = value
  problems.value = problems.value.filter(problem => problem.fieldId !== field.id)
  requestKey.value = ''
}
function selection(field: ObjectField) {
  return (
    field.type === FieldType.REFERENCE ||
    snapshot.value?.model.object.relations.some(relation => relation.fieldId === field.id) ||
    !!selectionSource(field, options(field))
  )
}
function selectionValue(field: ObjectField): string | string[] | null | undefined {
  if (!((field.id || field.key) in editing)) return undefined
  const value = objectDataValue(field, editing[field.id || field.key] ?? null)
  return Array.isArray(value) ? value.map(String) : value == null ? null : String(value)
}
async function save() {
  if (!editingRow.value || !snapshot.value || saving.value) return
  if (Object.values(uploadStates).some(state => state.pending || state.failed)) {
    failure.value = '请先完成文件上传，或移除上传失败的文件后再保存。'
    return
  }
  const current = editingRow.value
  const values = changedObjectData(writeFields.value, current.values, editing, creating.value)
  if (!creating.value && !Object.keys(values).length) {
    resetEditing()
    return
  }
  saving.value = true
  failure.value = ''
  requestKey.value ||= newRowKey()
  try {
    const saved = await api.save({
      objectId: props.objectId,
      versionNo: snapshot.value.versionNo,
      checksum: snapshot.value.checksum,
      id: creating.value ? null : current.id,
      expectedRevision: creating.value ? null : current.revision,
      values,
      requestKey: requestKey.value
    })
    resetEditing()
    message.success(`记录 ${saved.record.id} 已保存，使用此对象的应用将看到此次修改`)
    await table.fetchData()
  } catch (cause) {
    problems.value = documentProblems(cause)
    uncertainSave.value = !isDocumentRejection(cause)
    if (!uncertainSave.value) requestKey.value = ''
    failure.value = uncertainSave.value
      ? '暂时无法确认保存结果。原请求和输入已保留，请点击“原样重试”核实结果，避免重复新增。'
      : `${errorMessage(cause)}；当前行输入已保留。`
  } finally {
    saving.value = false
  }
}
function cancelEdit() {
  if (saving.value || uncertainSave.value) return
  Modal.confirm({
    title: '放弃当前行修改？',
    content: '尚未保存的输入将被丢弃，已保存的数据保持不变。',
    okText: '放弃修改',
    cancelText: '继续编辑',
    onOk: () => resetEditing()
  })
}

const deleteOpen = ref(false),
  deletion = ref<ObjectDataDelete>(),
  deletePreview = ref<ObjectDataDeletePreview>(),
  checkingDelete = ref(false),
  deleting = ref(false),
  deleteError = ref('')
async function inspectDelete(row: BusinessRow) {
  if (!snapshot.value || !row.id || !row.revision || editingRow.value) return
  deletion.value = {
    objectId: props.objectId,
    versionNo: snapshot.value.versionNo,
    checksum: snapshot.value.checksum,
    id: row.id,
    expectedRevision: row.revision
  }
  deleteOpen.value = true
  deletePreview.value = undefined
  deleteError.value = ''
  checkingDelete.value = true
  try {
    deletePreview.value = await api.deletePreview(deletion.value)
  } catch (cause) {
    deleteError.value = errorMessage(cause)
  } finally {
    checkingDelete.value = false
  }
}
async function remove() {
  if (!deletion.value || !deletePreview.value?.allowed || deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api.delete({ ...deletion.value, impactToken: deletePreview.value.impactToken })
    deleteOpen.value = false
    message.success('记录已删除')
    await table.fetchData()
  } catch (cause) {
    deleteError.value = errorMessage(cause)
    deletePreview.value = undefined
  } finally {
    deleting.value = false
  }
}
function impactRecordLink(objectId: string, recordId: string) {
  return router.resolve({
    path: '/nocode/object/editor',
    query: { id: objectId, tab: 'data', recordIds: recordId }
  }).href
}

const detailsOpen = ref(false),
  detailsLoading = ref(false),
  detailsError = ref(''),
  aggregate = ref<Aggregate>()
async function showDetails(row: BusinessRow) {
  if (!row.id) return
  detailsOpen.value = true
  detailsLoading.value = true
  detailsError.value = ''
  aggregate.value = undefined
  try {
    aggregate.value = await api.get(props.objectId, row.id)
  } catch (cause) {
    detailsError.value = errorMessage(cause)
  } finally {
    detailsLoading.value = false
  }
}
onBeforeUnmount(() => {
  generation++
  emit('editing', false)
})
</script>

<template>
  <section ref="gridRoot" class="object-data-grid" aria-label="对象数据维护">
    <a-result v-if="!authorized" status="403" title="需要数据对象管理权限才能查看和维护对象数据" />
    <a-empty v-else-if="!publishedVersion" description="对象尚未发布。首次发布后，可在这里查看和维护数据。" />
    <template v-else>
      <div class="data-notice">
        <span>
          已发布结构
          <a-tag v-if="snapshot">V{{ snapshot.versionNo }}</a-tag>
          · 保存数据立即生效，并由引用此对象的应用共享。
        </span>
        <span class="muted">结构草稿不会自动应用到这里。</span>
      </div>
      <a-alert v-if="failure" type="error" show-icon :message="failure" />
      <a-alert v-if="snapshot?.createRestriction" type="info" show-icon :message="snapshot.createRestriction" />
      <a-alert
        v-if="detailId"
        type="info"
        show-icon
        message="本次问题涉及内部明细。可通过记录右侧“查看明细”核对；此处仅维护主表列，保存主记录会保留原有明细。"
      />
      <div v-if="selectedField || locating" class="location-note">
        <span v-if="selectedField">
          已定位列：
          <strong>{{ selectedField.name }}</strong>
        </span>
        <template v-if="locating">
          <span>
            当前限定检查页中的 {{ recordIds.length }} 条记录，已删除或不存在的记录不显示；这不是全部冲突记录。
          </span>
          <a-button type="link" :disabled="!!editingRow" @click="clearLocation">查看全部记录</a-button>
        </template>
      </div>
      <a-spin v-if="loadingModel && !snapshot" tip="正在读取已发布结构" />
      <a-button v-else-if="!snapshot" @click="refresh">重新读取</a-button>
      <a-config-provider v-if="snapshot" component-size="small">
        <OsTablePage
          class="nocode-embedded-table data-grid-table"
          size="small"
          bordered
          :index-width="44"
          index-fixed="left"
          :columns="columns"
          :data-source="rows"
          :loading="loading || loadingModel"
          :pagination="{ ...pagination, disabled: !!editingRow }"
          :scroll="{ x: 'max-content', y: '100%' }"
          row-key="id"
          resizable
          show-column-settings
          :column-settings-key="`nocode-object-data-${objectId}-${snapshot.versionNo}${fieldId ? `-focus-${fieldId}` : ''}`"
          @change="changePage"
        >
          <template #columnTitle="{ column }">
            <div v-if="column.key === '__id' || fieldsById[column.key]" class="data-column-title">
              <span class="data-column-name">{{ column.title }}</span>
              <small :title="`数据库实际类型：${physicalType(column.key)}`">{{ physicalType(column.key) }}</small>
            </div>
            <span v-else>{{ column.title }}</span>
          </template>
          <template #search>
            <a-form layout="inline" size="small" :disabled="!!editingRow || locating">
              <a-form-item label="记录标题">
                <a-input
                  v-model:value="queryForm.search"
                  class="title-search"
                  placeholder="输入标题关键词"
                  allow-clear
                  @press-enter="query"
                />
              </a-form-item>
              <a-form-item label="筛选列">
                <a-select
                  v-model:value="queryForm.fieldId"
                  allow-clear
                  class="filter-field"
                  :options="plainFields.map(field => ({ label: field.name, value: field.id || field.key }))"
                />
              </a-form-item>
              <a-form-item v-if="filterField" label="条件">
                <a-select
                  v-model:value="queryForm.operator"
                  class="filter-operator"
                  :options="[
                    { label: '等于', value: 'eq' },
                    { label: '为空', value: 'isNull' },
                    { label: '不为空', value: 'notNull' }
                  ]"
                />
              </a-form-item>
              <a-form-item v-if="filterField && queryForm.operator === 'eq'" label="值" class="filter-value">
                <ObjectDataSelectionField
                  v-if="selection(filterField)"
                  :object-id="objectId"
                  :field-id="filterField.id!"
                  :model-value="queryForm.value"
                  :disabled="!!editingRow || locating"
                  @update:model-value="queryForm.value = typeof $event === 'string' ? $event : null"
                />
                <FieldValueEditor
                  v-else
                  :field="calculationValueField(filterField, options(filterField))"
                  :options="options(filterField)"
                  v-model="queryForm.value"
                  :disabled="!!editingRow || locating"
                />
              </a-form-item>
              <a-form-item>
                <a-button type="primary" @click="query">
                  <SearchOutlined aria-hidden="true" />
                  查询
                </a-button>
                <a-button class="reset-query" @click="table.handleReset">
                  <ReloadOutlined aria-hidden="true" />
                  重置
                </a-button>
              </a-form-item>
            </a-form>
          </template>
          <template #toolbar>
            <a-tooltip :title="snapshot.createRestriction || (!writable ? '当前对象为只读' : '')">
              <a-button type="primary" :disabled="!canCreate || !!editingRow || busy" @click="create">
                <PlusOutlined aria-hidden="true" />
                新增一行
              </a-button>
            </a-tooltip>
            <a-button :disabled="!!editingRow || busy || loadingModel" @click="clearColumnOpen = true">
              清空整列
            </a-button>
            <a-button
              v-if="hasStoredOrdered"
              :disabled="!!editingRow || busy || loadingModel"
              @click="calibrationOpen = true"
            >
              校准有序计算
            </a-button>
            <a-button :disabled="!!editingRow || busy" :loading="loadingModel" @click="refresh">
              <ReloadOutlined aria-hidden="true" />
              刷新
            </a-button>
          </template>
          <template #actions />
          <template #bodyCell="{ column, record }">
            <span v-if="column.key === '__id'">{{ record.id === '__new__' ? '新增记录' : record.id }}</span>
            <a-space v-else-if="column.key === '__actions'" class="row-actions">
              <template v-if="editingRow?.id === record.id">
                <a-button type="link" :loading="saving" @click="save">
                  {{ uncertainSave ? '原样重试' : '保存本行' }}
                </a-button>
                <a-button type="link" :disabled="saving || uncertainSave" @click="cancelEdit">取消</a-button>
              </template>
              <template v-else>
                <a-button type="link" :disabled="!writable || !!editingRow || busy" @click="edit(record)">
                  <EditOutlined aria-hidden="true" />
                  编辑
                </a-button>
                <a-button
                  type="link"
                  danger
                  :disabled="!writable || !!editingRow || busy"
                  @click="inspectDelete(record)"
                >
                  <DeleteOutlined aria-hidden="true" />
                  删除
                </a-button>
                <a-button v-if="activeDetails.length" type="link" :disabled="!!editingRow" @click="showDetails(record)">
                  查看明细
                </a-button>
              </template>
            </a-space>
            <div
              v-else-if="fieldsById[column.key]"
              class="data-cell"
              :class="{
                'numeric-cell': numeric(fieldsById[column.key]!),
                changed:
                  editingRow?.id === record.id &&
                  objectDataInput(fieldsById[column.key]!, record.values[column.key]) !==
                    (editing[column.key] ?? null) &&
                  column.key in editing
              }"
            >
              <template v-if="editingRow?.id === record.id && !readonlyReason(fieldsById[column.key]!)">
                <ObjectDataSelectionField
                  v-if="selection(fieldsById[column.key]!)"
                  :object-id="objectId"
                  :field-id="column.key"
                  :record-id="creating ? undefined : record.id"
                  :creating="creating"
                  :model-value="selectionValue(fieldsById[column.key]!)"
                  :multiple="fieldsById[column.key]!.type === FieldType.MULTI_SELECT"
                  :disabled="saving || uncertainSave"
                  @update:model-value="
                    update(fieldsById[column.key]!, objectDataInput(fieldsById[column.key]!, $event))
                  "
                />
                <FieldValueEditor
                  v-else
                  :field="fieldsById[column.key]!"
                  :options="options(fieldsById[column.key]!)"
                  :model-value="editing[column.key]"
                  :disabled="saving || uncertainSave"
                  :object-id="objectId"
                  :record-id="creating ? undefined : record.id"
                  :business-policy="snapshot?.model.object.settings.businessFilePolicy || null"
                  @update:model-value="update(fieldsById[column.key]!, $event)"
                  @upload-status="uploadStates[column.key] = $event"
                />
                <small
                  v-for="problem in problems.filter(item => item.fieldId === column.key)"
                  :key="(problem.ruleId || '') + problem.message"
                  class="field-problem"
                >
                  {{ problem.message }}
                </small>
              </template>
              <template v-else>
                <FieldValueEditor
                  v-if="[FieldType.IMAGE, FieldType.ATTACHMENT].some(type => type === fieldsById[column.key]!.type)"
                  :field="fieldsById[column.key]!"
                  :options="options(fieldsById[column.key]!)"
                  :model-value="objectDataInput(fieldsById[column.key]!, record.values)"
                  :object-id="objectId"
                  :record-id="record.id"
                  :business-policy="snapshot?.model.object.settings.businessFilePolicy || null"
                  display-only
                />
                <span
                  v-else
                  class="data-value"
                  :class="{ 'readonly-value': !!readonlyReason(fieldsById[column.key]!) }"
                  :title="
                    displayValue(record, fieldsById[column.key]!) +
                    (readonlyReason(fieldsById[column.key]!)
                      ? `\n只读：${readonlyReason(fieldsById[column.key]!)}`
                      : '')
                  "
                >
                  {{ displayValue(record, fieldsById[column.key]!) }}
                </span>
              </template>
            </div>
          </template>
        </OsTablePage>
      </a-config-provider>
    </template>

    <OrderedCalculationCalibration
      v-if="calibrationOpen && snapshot"
      :object-id="objectId"
      :model="snapshot"
      @cancel="closeCalibration"
    />
    <ObjectDataClearColumn
      v-if="clearColumnOpen && snapshot"
      :object-id="objectId"
      :model="snapshot"
      :initial-field-id="fieldId || queryForm.fieldId || undefined"
      @cancel="clearColumnOpen = false"
      @cleared="columnCleared"
    />

    <OsModalForm
      v-if="deleteOpen"
      :open="deleteOpen"
      title="检查删除影响"
      :width="820"
      @cancel="!deleting && (deleteOpen = false)"
    >
      <template #formItems>
        <a-spin v-if="checkingDelete" tip="正在检查关联记录和删除规则" />
        <a-alert v-if="deleteError" type="error" show-icon :message="deleteError" />
        <template v-if="deletePreview">
          <p>
            删除记录：
            <strong>{{ deletePreview.recordTitle || deletion?.id }}</strong>
          </p>
          <a-alert :type="deletePreview.allowed ? 'warning' : 'error'" show-icon :message="deletePreview.message" />
          <a-list :data-source="deletePreview.impacts">
            <template #renderItem="{ item }">
              <a-list-item>
                <div>
                  <strong>
                    {{ item.objectName }} · {{ item.recordTitle || item.recordId }} · {{ item.relationName }}
                  </strong>
                  <p>{{ item.message }}</p>
                  <a
                    v-if="item.objectId && item.recordId"
                    :href="impactRecordLink(item.objectId, item.recordId)"
                    target="_blank"
                    rel="noopener"
                  >
                    新标签页查看记录
                  </a>
                </div>
              </a-list-item>
            </template>
          </a-list>
        </template>
      </template>
      <template #footer>
        <a-button :disabled="deleting" @click="deleteOpen = false">取消</a-button>
        <a-button
          danger
          type="primary"
          :disabled="!deletePreview?.allowed || checkingDelete"
          :loading="deleting"
          @click="remove"
        >
          确认删除
        </a-button>
      </template>
    </OsModalForm>
    <OsModalForm
      v-if="detailsOpen"
      :open="detailsOpen"
      title="查看内部明细"
      :width="1000"
      :show-footer="false"
      @cancel="detailsOpen = false"
    >
      <template #formItems>
        <a-alert
          type="info"
          show-icon
          message="内部明细在这里只读查看。主表行编辑不会覆盖或删除这些明细；修改整单明细请使用相应业务入口。"
        />
        <a-spin v-if="detailsLoading" />
        <a-alert v-if="detailsError" type="error" show-icon :message="detailsError" />
        <template v-if="aggregate">
          <section v-for="detail in activeDetails" :key="detail.id!" class="detail-section">
            <h4>{{ detail.name }}{{ detail.id === detailId ? ' · 本次定位明细' : '' }}</h4>
            <OsTablePage
              class="detail-data-grid"
              size="small"
              bordered
              :columns="
                detail.fields.map(field => ({
                  title: field.name,
                  key: field.id || field.key,
                  width: 180,
                  className: field.id === fieldId ? 'object-data-focused' : undefined
                }))
              "
              :data-source="aggregate.details[detail.id!] || []"
              row-key="id"
              :pagination="false"
              :scroll="{ x: 'max-content', y: 260 }"
              resizable
            >
              <template #columnTitle="{ column }">
                <div v-if="column.key !== '_index'" class="data-column-title">
                  <span class="data-column-name">{{ column.title }}</span>
                  <small :title="`数据库实际类型：${physicalType(column.key)}`">{{ physicalType(column.key) }}</small>
                </div>
                <span v-else>{{ column.title }}</span>
              </template>
              <template #bodyCell="{ column, record }">
                {{
                  recordDisplay(
                    record,
                    column.key,
                    detail.fieldOptions[column.key],
                    detail.fields.find(field => field.id === column.key)
                  )
                }}
              </template>
            </OsTablePage>
          </section>
        </template>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.object-data-grid,
.data-grid-table {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.object-data-grid {
  gap: 6px;
}
.data-notice,
.location-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  line-height: 22px;
}
.data-notice {
  font-size: var(--ant-font-size-sm, 12px);
}
.muted {
  color: var(--text-secondary);
}
.filter-field {
  width: 150px;
}
.filter-operator {
  width: 88px;
}
.filter-value {
  width: 175px;
}
.title-search {
  width: 175px;
}
.reset-query {
  margin-left: 8px;
}
.data-cell {
  min-width: 0;
  text-align: left;
  padding: 0 2px;
  border-radius: 2px;
  line-height: 22px;
}
.data-cell small {
  display: block;
  margin-top: 4px;
  font-size: var(--ant-font-size-sm, 12px);
}
.data-value {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.data-column-title {
  min-width: 0;
  max-width: 100%;
  line-height: 18px;
}
.data-column-name,
.data-column-title small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.data-column-title small {
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: normal;
  line-height: 15px;
}
.numeric-cell {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.readonly-value {
  color: var(--text-secondary);
  cursor: help;
}
.changed {
  background: var(--warning-bg);
  outline: 1px solid var(--warning);
}
.field-problem {
  color: var(--error);
}
.row-actions {
  gap: 0 !important;
}
.row-actions :deep(.ant-btn) {
  height: 24px;
  padding-inline: 4px;
  font-size: 12px;
}
.detail-section {
  margin-top: 16px;
}
:deep(.object-data-focused) {
  background: var(--brand-light) !important;
}
/* 仅对象数据采用数据库结果网格密度，其他管理表格继续沿用公共规范。 */
.object-data-grid :deep(.data-grid-table) {
  gap: 4px;
}
.object-data-grid :deep(.data-grid-table > .os-table-page__search) {
  border-radius: 4px;
  box-shadow: none;
}
.object-data-grid :deep(.data-grid-table > .os-table-page__search > .ant-card-body) {
  padding: 6px 8px;
}
.object-data-grid :deep(.data-grid-table .os-table-page__search-form) {
  overflow-x: auto;
}
.object-data-grid :deep(.data-grid-table .ant-form-inline) {
  flex-wrap: nowrap;
  gap: 8px;
  white-space: nowrap;
}
.object-data-grid :deep(.data-grid-table .ant-form-item) {
  flex: 0 0 auto;
  margin-inline-end: 0;
}
.object-data-grid :deep(.data-grid-table .ant-form-item-label > label) {
  height: 26px;
  font-size: 12px;
}
.object-data-grid :deep(.data-grid-table > .os-table-page__table > .ant-card-head) {
  min-height: 30px;
  margin-bottom: 4px;
}
.object-data-grid :deep(.data-grid-table .ant-card-head-wrapper) {
  min-height: 30px;
}
.object-data-grid :deep(.data-grid-table .ant-table-thead > tr > th),
.detail-data-grid :deep(.ant-table-thead > tr > th) {
  height: 42px;
  padding: 4px 8px;
  font-size: 13px;
  line-height: 22px;
  border-bottom: 1px solid var(--border);
  border-inline-end: 1px solid var(--border);
  background: var(--neutral-bg);
}
.object-data-grid :deep(.data-grid-table .ant-table-tbody > tr:not(.ant-table-measure-row) > td),
.detail-data-grid :deep(.ant-table-tbody > tr:not(.ant-table-measure-row) > td) {
  height: 34px;
  padding: 4px 8px;
  font-size: 13px;
  line-height: 22px;
  border-bottom: 1px solid var(--border);
  border-inline-end: 1px solid var(--border);
}
.object-data-grid :deep(.data-grid-table .ant-table-container) {
  border: 1px solid var(--border);
  border-radius: 0;
}
.object-data-grid :deep(.data-grid-table .ant-pagination) {
  margin: 6px 0 0;
}
.object-data-grid :deep(.data-cell .ant-input),
.object-data-grid :deep(.data-cell .ant-input-affix-wrapper),
.object-data-grid :deep(.data-cell .ant-input-number),
.object-data-grid :deep(.data-cell .ant-picker) {
  min-height: 26px;
  font-size: 13px;
}
.object-data-grid :deep(.data-cell .ant-select-single.ant-select-sm .ant-select-selector) {
  height: 26px;
  font-size: 13px;
}
.object-data-grid :deep(.numeric-cell .ant-input-number-input) {
  text-align: right;
}
</style>
