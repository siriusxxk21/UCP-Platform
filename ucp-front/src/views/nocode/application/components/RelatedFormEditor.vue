<script setup lang="ts">
import { Drawer, Modal } from 'ant-design-vue'
import { computed, ref, watch, provide, inject, onBeforeUnmount, nextTick } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import type { RelatedFormBinding } from '@/types/nocode/application-ui'
import type { Aggregate, RelatedFormResult, RelatedFormRow } from '@/types/nocode/runtime'
import type { ObjectDetail } from '@/types/nocode/data-center'
import { useNocodePlatform, nocodePlatformKey } from '@/nocode/platform'
import { boundFields } from '@/nocode/application-ui'
import { formLayoutNodes } from '@/nocode/form-detail-layout'
import { formFieldLabel, formFieldProjection } from '@/nocode/form-field-projection'
import { recordDefaults, recordPayload } from '@/nocode/record-form'
import { useRelatedFormProjection } from '@/nocode/record-editor-projection'
import { createRequestSession } from '@/nocode/request-session'
import { errorMessage } from '@/nocode/data-center'
import { editSignature } from '@/nocode/edit-signature'
import RecordForm from './RecordForm.vue'
import RecordReadView from './RecordReadView.vue'
import FormDetailProvider from './FormDetailProvider.vue'
import { taskFormAccessKey } from '@/nocode/task-form-access'
const props = defineProps<{
  interactionDisplayMode?: 'modal' | 'drawer'
  applicationId: string
  objectId: string
  formId: string
  binding: RelatedFormBinding
  recordId?: string | null
  readOnly?: boolean
  pendingRows?: RelatedFormRow[]
}>()
const emit = defineEmits<{ change: [] }>()
const platform = useNocodePlatform(),
  api = platform.runtime
const taskAccess = inject(taskFormAccessKey, undefined)
provide(nocodePlatformKey, {
  ...platform,
  runtime: {
    ...api,
    ...(taskAccess
      ? {
          evaluateFieldRules: async (child: Parameters<typeof api.evaluateFieldRules>[0]) =>
            taskAccess.relatedFieldRules(activeQuery(), child)
        }
      : {}),
    selection: async child => api.relatedSelection(activeQuery(), child),
    formFill: async child => api.relatedFill(activeQuery(), child)
  }
})
const result = ref<RelatedFormResult>()
const loading = ref(false),
  error = ref(''),
  search = ref(''),
  candidates = ref<Aggregate[]>([]),
  pickerOpen = ref(false)
interface EditRow {
  key: string
  aggregate: Aggregate
  original: string
  added: boolean
  removed: boolean
}
const rows = ref<EditRow[]>([]),
  editors = ref<Array<InstanceType<typeof RecordForm>>>([])
const visible = computed(() => rows.value.filter(r => !r.removed))
let cleanBaseline = ''
const inputSignature = () =>
  editSignature(rows.value.map(r => ({ aggregate: r.aggregate, added: r.added, removed: r.removed })))
function markClean() {
  cleanBaseline = inputSignature()
}
function query() {
  return {
    applicationId: props.applicationId,
    objectId: props.objectId,
    formId: props.formId,
    bindingId: props.binding.id,
    recordId: props.recordId
  }
}
const contextKey = computed(() => JSON.stringify(query()))
const loadedContextKey = ref('')
const ready = computed(() => !loading.value && !!result.value && loadedContextKey.value === contextKey.value)
function activeQuery() {
  if (!ready.value) throw new Error('关联数据正在加载或上下文已变化，请加载完成后重试')
  return query()
}
const loadSession = createRequestSession(),
  searchSession = createRequestSession()
let alive = true
const {
  target,
  caps,
  rowModel,
  fields,
  nodes,
  mainOptions,
  newModel,
  detailRelations,
  detailOptions,
  detailModel,
  publishedDetails
} = useRelatedFormProjection(
  props,
  () => result.value,
  () => rows.value
)
const title = (aggregate: Aggregate) =>
  aggregate.record.displayValues?.[target().model.object.titleFieldId] ||
  String(
    aggregate.record.values[target().model.object.titleFieldId] ||
      (aggregate.record.id ? `记录 ${aggregate.record.id}` : '新记录')
  )
function wrap(aggregate: Aggregate, added = false): EditRow {
  return { key: uuidv4(), aggregate, original: editSignature(aggregate), added, removed: false }
}
async function load() {
  const request = loadSession.begin(),
    key = contextKey.value,
    context = query()
  const current = () => request() && key === contextKey.value
  searchSession.invalidate()
  pickerOpen.value = false
  candidates.value = []
  loading.value = true
  error.value = ''
  try {
    const response = await api.relatedForm(context)
    if (!current()) return
    const restored = response.records.map(r => wrap(r))
    const pendingRows = props.pendingRows
    if (pendingRows)
      for (const pending of pendingRows) {
        const existing = pending.id ? restored.find(r => r.aggregate.record.id === pending.id) : undefined
        if (existing) {
          existing.aggregate.record.values = { ...existing.aggregate.record.values, ...pending.values }
          existing.aggregate.record.revision = pending.expectedRevision
          existing.aggregate.details = { ...existing.aggregate.details, ...pending.details }
          existing.removed = !!pending.unlink
        } else {
          const saved = pending.id
            ? (await api.relatedForm({ ...context, selectedId: pending.id })).records.find(
                r => r.record.id === pending.id
              )
            : undefined
          if (!current()) return
          if (pending.id && !saved)
            throw new Error('待恢复的关联记录已失效或无权读取；暂存内容已保留，请恢复访问后重试')
          const entry = wrap(saved || { record: { id: null, revision: null, values: {} }, details: {} }, true)
          entry.aggregate.record.values = { ...entry.aggregate.record.values, ...pending.values }
          entry.aggregate.record.revision = pending.expectedRevision
          entry.aggregate.details = { ...entry.aggregate.details, ...pending.details }
          entry.removed = !!pending.unlink
          restored.push(entry)
        }
      }
    if (!current()) return
    loadedContextKey.value = key
    result.value = response
    rows.value = restored
    loading.value = false
    await nextTick()
    if (current()) markClean()
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  } finally {
    if (current()) loading.value = false
  }
}
function add() {
  if (!ready.value || !result.value || (!result.value.multiple && visible.value.length)) return
  const model = result.value.model
  rows.value.push(
    wrap(
      {
        record: {
          id: null,
          revision: null,
          values: recordDefaults(model.object.fields, mainOptions.value, newModel(), false)
        },
        details: {}
      },
      true
    )
  )
  emit('change')
}
function removeDetail(row: EditRow, detailId: string, index: number) {
  if (!ready.value) return
  const children = row.aggregate.details[detailId]
  if (!children || !Number.isInteger(index) || index < 0 || index >= children.length) return
  children.splice(index, 1)
  emit('change')
}
async function find() {
  if (!ready.value) return
  const request = searchSession.begin(),
    key = contextKey.value,
    keyword = search.value
  const current = () => request() && pickerOpen.value && key === contextKey.value && keyword === search.value
  try {
    const found = await api.relatedForm({ ...query(), search: keyword })
    if (current()) candidates.value = found.records
  } catch (e) {
    if (current()) error.value = errorMessage(e)
  }
}
let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(search, () => {
  searchSession.invalidate()
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (pickerOpen.value) void find()
  }, 250)
})
watch(pickerOpen, open => {
  if (!open) searchSession.invalidate()
})
onBeforeUnmount(() => {
  alive = false
  clearTimeout(searchTimer)
  loadSession.invalidate()
  searchSession.invalidate()
})
async function openPicker() {
  if (!ready.value) return
  pickerOpen.value = true
  await find()
}
function select(aggregate: Aggregate) {
  if (!ready.value) return
  if (!aggregate.record.id) {
    error.value = '所选关联记录缺少稳定标识，请刷新候选后重试'
    return
  }
  if (visible.value.some(r => r.aggregate.record.id === aggregate.record.id)) return
  if (!result.value?.multiple && visible.value.length) return
  const removed = rows.value.find(r => r.removed && r.aggregate.record.id === aggregate.record.id)
  if (removed) removed.removed = false
  else rows.value.push(wrap(aggregate, true))
  emit('change')
  if (!result.value?.multiple) pickerOpen.value = false
}
function remove(row: EditRow) {
  if (!ready.value) return
  if (row.added) rows.value = rows.value.filter(r => r.key !== row.key)
  else row.removed = true
  emit('change')
}
function addDetail(row: EditRow, id: string) {
  if (!ready.value) return
  const d = publishedDetails().find(d => d.id === id)
  if (!d) throw new Error('关联表单明细配置已变化，请重新打开表单')
  ;(row.aggregate.details[id] ||= []).push({
    id: null,
    revision: null,
    clientRowKey: uuidv4(),
    values: recordDefaults(d.fields, detailOptions(id), detailModel(row, id), false)
  })
  emit('change')
}
const dirty = () => !loading.value && !!result.value && inputSignature() !== cleanBaseline
const visibleDetails = (row: EditRow) =>
  publishedDetails().filter(
    detail => target().form.detailIds.includes(detail.id) && caps(row).readDetails.includes(detail.id)
  )
const layoutNodes = (row: EditRow) =>
  formLayoutNodes(
    nodes(),
    visibleDetails(row).map(detail => detail.id)
  )
const detailModes = ref<Record<string, 'GRID' | 'CARDS'>>({})
const detailFields = (detail: ObjectDetail) =>
  formFieldProjection(detail.fields, result.value?.form.detailNodes?.[detail.id!]).fields
const detailMode = (row: EditRow, id: string, preferred?: 'GRID' | 'CARDS') =>
  detailModes.value[`${row.key}:${id}`] || preferred || 'GRID'
async function payload(validate = true): Promise<RelatedFormRow[]> {
  if (!alive || loading.value || error.value || !result.value)
    throw new Error(`${props.binding.title}：请先解决关联数据加载问题`)
  const key = contextKey.value,
    published = result.value,
    originalRows = rows.value
  const current = () =>
    alive && key === contextKey.value && published === result.value && originalRows === rows.value && !loading.value
  for (const editor of editors.value) {
    if (validate) await editor.validate()
    else editor.validateUploads()
    if (!current()) throw new Error('关联表单上下文已变化，请检查当前输入后重新保存')
  }
  const outgoing = props.binding.direction === 'OUTGOING'
  return rows.value
    .filter(r => (outgoing ? !r.removed : r.added || r.removed || editSignature(r.aggregate) !== r.original))
    .map(row => {
      if (validate && row.aggregate.record.id && !row.aggregate.record.revision)
        throw new Error(`${props.binding.title}：关联记录缺少修订号，请保留输入并刷新后重试`)
      const unchanged = editSignature(row.aggregate) === row.original
      const model = rowModel(row)
      const values =
        unchanged && row.aggregate.record.id
          ? {}
          : recordPayload(
              fields(row).filter(f => boundFields(nodes()).includes(f.id)),
              mainOptions.value,
              model,
              !row.aggregate.record.id,
              row.aggregate.record.values
            )
      const details: RelatedFormRow['details'] = {}
      if (!unchanged)
        for (const detail of publishedDetails().filter(
          d => published.form.detailIds.includes(d.id) && detailModel(row, d.id).writable
        ))
          details[detail.id] = (row.aggregate.details[detail.id] || []).map(r => {
            if (validate && r.id && !r.revision) throw new Error(`${detail.name}：明细记录缺少修订号，请刷新后重试`)
            return {
              ...r,
              values: recordPayload(
                detail.fields,
                detailOptions(detail.id),
                detailModel(row, detail.id),
                !r.id,
                r.values
              )
            }
          })
      return {
        id: row.aggregate.record.id,
        expectedRevision: row.aggregate.record.revision,
        values,
        details,
        unlink: row.removed
      }
    })
}
watch(contextKey, load, {
  immediate: true
})
defineExpose({ payload, dirty, markClean, bindingId: props.binding.id })
</script>
<template>
  <section class="related-section">
    <header>
      <div>
        <h3>{{ binding.title }}</h3>
        <p>独立数据 · {{ result?.multiple ? '可填写多条' : '关联一条' }} · 与本次记录一起保存</p>
      </div>
      <a-space v-if="result && !readOnly && (result.multiple || !visible.length)">
        <a-button :disabled="!ready" @click="openPicker">选择已有</a-button>
        <a-button v-if="result.model.permissions.actions.includes('CREATE')" :disabled="!ready" @click="add">
          新增{{ binding.title }}
        </a-button>
      </a-space>
    </header>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-spin v-if="loading" />
    <a-alert
      v-if="result?.truncated"
      type="info"
      message="仅展示当前有权查看的前 100 条记录；未展示记录不会被本次保存删除。"
    />
    <a-empty
      v-if="result && !loading && !visible.length"
      description="尚未关联数据，可选择已有记录或直接新增"
      :image="undefined"
    />
    <article v-for="row in ready ? visible : []" :key="row.key">
      <div class="row-title">
        <strong>{{ title(row.aggregate) }}</strong>
        <a-button
          v-if="!readOnly && (!row.aggregate.record.id || binding.direction === 'OUTGOING' || !result?.required)"
          type="link"
          danger
          @click="remove(row)"
        >
          {{ row.aggregate.record.id ? '解除关联' : '取消新增' }}
        </a-button>
      </div>
      <FormDetailProvider :detail-ids="result ? visibleDetails(row).map(detail => detail.id) : []">
        <RecordReadView
          v-if="result && (readOnly || result.form.options?.readOnly)"
          :nodes="layoutNodes(row)"
          :fields="fields(row)"
          :values="row.aggregate.record.values"
          :display-values="row.aggregate.record.displayValues"
          :options="result.model.object.fieldOptions"
          :application-id="applicationId"
          :object-id="result.model.object.objectId"
          :record-id="row.aggregate.record.id || undefined"
          :relations="result.model.object.relations"
          :layout="result.form.options?.layout"
          :business-policy="result.model.object.settings.businessFilePolicy || null"
        />
        <RecordForm
          v-else-if="result"
          ref="editors"
          v-model="row.aggregate.record.values"
          :fields="fields(row)"
          :options="result.model.object.fieldOptions"
          :model="rowModel(row)"
          :creating="!row.aggregate.record.id"
          :nodes="layoutNodes(row)"
          :application-id="applicationId"
          :object-id="result.model.object.objectId"
          :record-id="row.aggregate.record.id || undefined"
          :form-id="binding.formId"
          :relations="result.model.object.relations"
          :layout="result.form.options?.layout"
          :business-policy="result.model.object.settings.businessFilePolicy || null"
          @update:model-value="emit('change')"
        />
        <template #detail="{ detailId, mode: preferredMode, title: detailTitle }">
          <section
            v-for="detail in (result ? visibleDetails(row) : []).filter(d => d.id === detailId)"
            :key="detail.id"
            class="related-detail"
            :class="{
              'related-detail-grid':
                detailMode(row, detail.id, preferredMode) === 'GRID' && !readOnly && !result?.form.options?.readOnly
            }"
          >
            <h4>{{ detailTitle || detail.name }}</h4>
            <a-radio-group
              v-if="!readOnly && !result?.form.options?.readOnly"
              :value="detailMode(row, detail.id, preferredMode)"
              :options="[
                { label: '表格', value: 'GRID' },
                { label: '卡片', value: 'CARDS' }
              ]"
              option-type="button"
              size="small"
              @update:value="detailModes[`${row.key}:${detail.id}`] = $event"
            />
            <div class="related-detail-body">
              <div
                v-if="
                  !readOnly && !result?.form.options?.readOnly && detailMode(row, detail.id, preferredMode) === 'GRID'
                "
                class="related-detail-head"
              >
                <span v-for="field in detailFields(detail)" :key="field.id!">
                  {{ formFieldLabel(field, result?.form.detailNodes?.[detail.id]) }}{{ field.required ? ' *' : '' }}
                </span>
              </div>
              <div
                v-for="(child, index) in row.aggregate.details[detail.id] || []"
                :key="child.id || child.clientRowKey || index"
              >
                <RecordReadView
                  v-if="readOnly || result?.form.options?.readOnly"
                  :nodes="result?.form.detailNodes?.[detail.id]"
                  :fields="detailFields(detail)"
                  :values="child.values"
                  :display-values="child.displayValues"
                  :options="detail.fieldOptions"
                  :application-id="applicationId"
                  :object-id="result?.model.object.objectId"
                  :record-id="row.aggregate.record.id || undefined"
                  :detail-id="detail.id"
                  :detail-record-id="child.id || undefined"
                  :relations="detailRelations(detail.id)"
                  :business-policy="result?.model.object.settings.businessFilePolicy || null"
                />
                <RecordForm
                  v-else
                  ref="editors"
                  :compact="detailMode(row, detail.id, preferredMode) === 'GRID'"
                  v-model="child.values"
                  :parent-values="row.aggregate.record.values"
                  :fields="detailFields(detail)"
                  :options="detail.fieldOptions"
                  :model="detailModel(row, detail.id)"
                  :creating="!child.id"
                  :nodes="result?.form.detailNodes?.[detail.id]"
                  :application-id="applicationId"
                  :object-id="result?.model.object.objectId"
                  :detail-id="detail.id"
                  :record-id="row.aggregate.record.id || undefined"
                  :detail-record-id="child.id || undefined"
                  :relations="detailRelations(detail.id)"
                  :form-id="binding.formId"
                  :business-policy="result?.model.object.settings.businessFilePolicy || null"
                  @update:model-value="emit('change')"
                />
                <a-button
                  v-if="detailModel(row, detail.id).writable"
                  danger
                  type="link"
                  @click="removeDetail(row, detail.id, index)"
                >
                  移除此明细行
                </a-button>
              </div>
            </div>
            <a-button v-if="detailModel(row, detail.id).writable" @click="addDetail(row, detail.id)">
              添加{{ detail.name }}
            </a-button>
          </section>
        </template>
      </FormDetailProvider>
    </article>
    <p v-if="rows.some(r => r.removed && r.aggregate.record.id)" class="hint">
      解除关联将在保存时生效；独立记录仍保留在原数据对象中。
    </p>
  </section>
  <component
    :is="interactionDisplayMode === 'drawer' ? Drawer : Modal"
    v-model:open="pickerOpen"
    @close="pickerOpen = false"
    :title="`选择已有${binding.title}`"
    :footer="null"
    :width="640"
  >
    <a-input-search v-model:value="search" placeholder="搜索记录名称" @search="find" />
    <a-list :data-source="candidates">
      <template #renderItem="{ item }">
        <a-list-item>
          <span>{{ title(item) }}</span>
          <a-button :disabled="visible.some(r => r.aggregate.record.id === item.record.id)" @click="select(item)">
            {{ visible.some(r => r.aggregate.record.id === item.record.id) ? '已选择' : '选择' }}
          </a-button>
        </a-list-item>
      </template>
    </a-list>
    <p class="hint">只显示你有权查看的数据，最多列出 30 条，请输入名称缩小范围。</p>
  </component>
</template>
<style scoped>
.related-section {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  margin-top: 20px;
  padding: 20px;
}
.related-section header,
.row-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
h3 {
  margin: 0;
}
.related-section p,
.hint {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}
article {
  margin-top: 16px;
  padding: 16px;
  background: var(--bg-secondary, #f8fafc);
  border-radius: 8px;
}
.related-detail {
  margin-top: 16px;
}
.related-detail-body {
  overflow-x: auto;
}
.related-detail-head {
  display: none;
}
@media (min-width: 769px) {
  .related-detail-head {
    display: flex;
    background: var(--bg-secondary, #f8fafc);
  }
  .related-detail-head > span {
    flex: none;
    width: 200px;
    padding: 10px 8px;
    box-sizing: border-box;
  }
}
.related-detail-grid .related-detail-body > div {
  width: max-content;
  min-width: 100%;
}
.row-title {
  margin-bottom: 12px;
}
@media (max-width: 640px) {
  .related-section {
    padding: 12px;
  }
  .related-section header {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .related-section header :deep(.ant-space) {
    flex-wrap: wrap;
  }
  .row-title {
    flex-wrap: wrap;
    gap: 8px;
  }
  .row-title strong {
    overflow-wrap: anywhere;
  }
}
</style>
