<script setup lang="ts">
import { businessFields, fieldRelation } from '@/nocode/business-fields'
import { computed, ref, provide, watch, nextTick, onBeforeUnmount } from 'vue'
import { selectionPreviewKey, selectionSource } from '@/nocode/selection'
import { useNocodePlatform } from '@/nocode/platform'
import type { PublishedDefinition } from '@/types/nocode/application'
import type { FormConfig, UiNode } from '@/types/nocode/application-ui'
import { boundFields } from '@/nocode/application-ui'
import { formLayoutNodes } from '@/nocode/form-detail-layout'
import { newRowKey } from '@/nocode/document-save'
import { formDesignModel } from '@/nocode/form-design'
import { recordDefaults, writableField } from '@/nocode/record-form'
import { businessFieldOptions } from '@/nocode/business-field-rules'
import { formFieldLabel, formFieldProjection } from '@/nocode/form-field-projection'
import { MemberState } from '@/types/nocode/enums'
import {
  createFieldRuleCoordinator,
  EMPTY_RULE_STATES,
  fieldRuleNamesKey,
  type FieldRuleCoordinator,
  type RuleFieldName,
  type RuleStates
} from '@/nocode/field-rule-runtime'
import RecordForm from './RecordForm.vue'
import RecordReadView from './RecordReadView.vue'
import RelatedFormPreview from './RelatedFormPreview.vue'
import FormDetailProvider from './FormDetailProvider.vue'

const props = defineProps<{
  definition: PublishedDefinition
  nodes: UiNode[]
  options: FormConfig['options']
  detailIds: string[]
  detailNodes?: FormConfig['detailNodes']
  relatedForms?: FormConfig['relatedForms']
  resources?: import('@/types/nocode/application').ApplicationResource[]
  name: string
  applicationId: string
  objects: Record<string, import('@/types/nocode/application').PublishedObject>
}>()
const api = useNocodePlatform().applications
const previewContext = computed(() => ({
  applicationId: props.applicationId,
  objects: Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({ objectId, versionNo, checksum })),
  form: {
    objectId: props.definition.objectId,
    nodes: props.nodes,
    detailIds: props.detailIds,
    options: props.options,
    detailNodes: props.detailNodes
  }
}))
provide(selectionPreviewKey, previewContext)
const device = ref('desktop'),
  mode = ref('create'),
  result = ref(''),
  error = ref('')
const generation = ref(0)
const form = ref<InstanceType<typeof RecordForm>>()
const detailForms = ref<Array<InstanceType<typeof RecordForm>>>([])
const relatedEditors = ref<Array<InstanceType<typeof RelatedFormPreview>>>([])
const outgoingFields = computed(
  () =>
    new Set(
      (props.relatedForms || [])
        .filter(b => b.direction === 'OUTGOING')
        .map(b => props.definition.relations.find(r => r.id === b.relationId)?.fieldId)
    )
)
const fields = computed(() =>
  businessFields(props.definition).filter(
    f => props.definition.fieldOptions[f.id!]?.state !== MemberState.INACTIVE && !outgoingFields.value.has(f.id)
  )
)
const details = computed(() =>
  props.definition.details.filter(d => d.state === MemberState.ACTIVE && props.detailIds.includes(d.id!))
)
const layoutNodes = computed(() =>
  formLayoutNodes(
    props.nodes,
    details.value.map(detail => detail.id!)
  )
)
const detailModes = ref<Record<string, 'GRID' | 'CARDS'>>({})
const detailMode = (id: string, preferred?: 'GRID' | 'CARDS') => detailModes.value[id] || preferred || 'GRID'
const mainModel = computed(() => ({
  ...formDesignModel,
  writeFields: formFieldProjection(fields.value, props.nodes).writeFields
}))
const detailContexts = computed(() =>
  Object.fromEntries(
    details.value.map(detail => {
      const relations = props.definition.relations.filter(r => r.sourceDetailId === detail.id)
      const projection = formFieldProjection(detail.fields, props.detailNodes?.[detail.id!])
      return [
        detail.id!,
        {
          fields: projection.fields,
          relations,
          options: businessFieldOptions(detail.fieldOptions, relations),
          model: { ...formDesignModel, writeFields: projection.writeFields }
        }
      ]
    })
  )
)
const values = ref<Record<string, unknown>>({})
interface PreviewDetailRow {
  key: string
  values: Record<string, unknown>
}
const rows = ref<Record<string, PreviewDetailRow[]>>({})
// 预览明细行没有记录 ID，用行自带的稳定行键供规则批量求值按行回写。
const ruleStates = ref<RuleStates>({ master: {}, rows: {} }),
  ruleError = ref('')
let ruleCoordinator: FieldRuleCoordinator | null = null
provide(
  fieldRuleNamesKey,
  computed<Record<string, RuleFieldName>>(() => ({
    ...Object.fromEntries(fields.value.flatMap(f => (f.id ? [[f.id, { name: f.name, detailId: null }] as const] : []))),
    ...Object.fromEntries(
      details.value.flatMap(detail =>
        detail.fields.flatMap(f => (f.id ? [[f.id, { name: f.name, detailId: detail.id! }] as const] : []))
      )
    )
  }))
)
function rowRuleStates(detailId: string, row: PreviewDetailRow) {
  return ruleStates.value.rows[detailId]?.[row.key] || EMPTY_RULE_STATES
}
/** 设计预览与运行时同一协调器：规则取自当前引用的对象版本，走预览求值端点，不保存业务记录。 */
function startRules() {
  ruleCoordinator?.dispose()
  ruleCoordinator = null
  ruleStates.value = { master: {}, rows: {} }
  ruleError.value = ''
  if (mode.value === 'read') return
  const coordinator = createFieldRuleCoordinator({
    read: () => ({
      creating: mode.value === 'create',
      options: props.definition.fieldOptions,
      values: values.value,
      canWrite: id => {
        const field = fields.value.find(f => f.id === id)
        return !!field && writableField(field, props.definition.fieldOptions[id], mainModel.value, true)
      },
      details: details.value.map(detail => {
        const context = detailContexts.value[detail.id!]!
        return {
          detailId: detail.id!,
          options: detail.fieldOptions,
          fieldIds: detail.fields.flatMap(f => (f.id ? [f.id] : [])),
          rows: (rows.value[detail.id!] || []).map(row => ({
            rowKey: row.key,
            creating: true,
            values: row.values
          })),
          canWrite: (id: string) => {
            const field = context.fields.find(f => f.id === id)
            return !!field && writableField(field, context.options[id], context.model, true)
          }
        }
      })
    }),
    evaluate: async part =>
      (
        await api.previewFieldRules({
          query: {
            applicationId: props.applicationId,
            objectId: props.definition.objectId,
            ...part
          },
          objects: previewContext.value.objects,
          form: previewContext.value.form
        })
      ).results,
    onStates: states => {
      if (ruleCoordinator === coordinator) ruleStates.value = states
    },
    onError: e => {
      if (ruleCoordinator === coordinator)
        ruleError.value = `数据联动计算失败：${e instanceof Error ? e.message : String(e)}`
    }
  })
  ruleCoordinator = coordinator
  void nextTick(() => {
    if (ruleCoordinator === coordinator) coordinator.start()
  })
}
watch(
  () => [values.value, rows.value],
  () => ruleCoordinator?.sync(),
  { deep: true }
)
watch(mode, startRules)
onBeforeUnmount(() => ruleCoordinator?.dispose())
function reset() {
  values.value = recordDefaults(
    fields.value.filter(f => boundFields(props.nodes).includes(f.id!)),
    businessFieldOptions(props.definition.fieldOptions, props.definition.relations),
    mainModel.value,
    false
  )
  rows.value = Object.fromEntries(details.value.map(d => [d.id!, []]))
  detailModes.value = {}
  error.value = result.value = ''
  generation.value++
  startRules()
}
function addRow(id: string) {
  const context = detailContexts.value[id]!
  if (rows.value[id]!.length >= 500) {
    error.value = '每组明细最多 500 行'
    return
  }
  rows.value[id]!.push({
    key: newRowKey(),
    values: recordDefaults(context.fields, context.options, context.model, false)
  })
}
function moveRow(id: string, index: number, offset: number) {
  const group = rows.value[id]!
  const next = index + offset
  if (next < 0 || next >= group.length) return
  group.splice(next, 0, group.splice(index, 1)[0]!)
}
function copyRow(id: string, row: PreviewDetailRow) {
  try {
    detailForms.value.find(form => form.rowKey === row.key)?.validateUploads()
    if (rows.value[id]!.length >= 500) throw new Error('每组明细最多 500 行')
    rows.value[id]!.push({ key: newRowKey(), values: JSON.parse(JSON.stringify(row.values)) })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
async function check() {
  error.value = result.value = ''
  try {
    await ruleCoordinator?.settle()
    await form.value?.validate()
    for (const detail of detailForms.value || []) await detail.validate()
    for (const related of relatedEditors.value) await related.validate()
    const validateSelections = async (
      fields: typeof props.definition.fields,
      options: typeof props.definition.fieldOptions,
      values: Record<string, unknown>,
      detailId?: string
    ) => {
      for (const field of fields) {
        if (!selectionSource(field, options[field.id!]) && !fieldRelation(props.definition.relations, field.id))
          continue
        const raw = values[field.id!]
        const selected = raw == null ? [] : Array.isArray(raw) ? raw.map(String) : [String(raw)]
        await api.previewSelection({
          objects: previewContext.value.objects,
          form: previewContext.value.form,
          validate: true,
          query: {
            applicationId: props.applicationId,
            objectId: props.definition.objectId,
            fieldId: field.id!,
            detailId,
            selected,
            formValues: values,
            pageNo: 1,
            pageSize: 1
          }
        })
      }
    }
    await validateSelections(
      fields.value.filter(f => boundFields(props.nodes).includes(f.id!)),
      props.definition.fieldOptions,
      values.value
    )
    for (const detail of details.value)
      for (const row of rows.value[detail.id!] || [])
        await validateSelections(
          detailContexts.value[detail.id!]!.fields,
          detail.fieldOptions,
          { ...values.value, ...row.values },
          detail.id!
        )
    result.value = '校验通过；预览数据未保存。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
reset()
</script>
<template>
  <div class="form-preview">
    <div class="preview-tools">
      <a-segmented
        v-model:value="device"
        :options="[
          { label: '桌面', value: 'desktop' },
          { label: '窄屏', value: 'mobile' }
        ]"
      />
      <a-segmented
        v-model:value="mode"
        :options="[
          { label: '新增', value: 'create' },
          { label: '编辑', value: 'edit' },
          { label: '查看', value: 'read' }
        ]"
      />
      <a-button @click="reset">重置预览数据</a-button>
    </div>
    <p class="preview-note">
      选择字段读取当前引用版本及表单范围内的授权候选；填写值仅用于预览，不保存业务记录。附件使用示例文件。
    </p>
    <div class="preview-scroll">
      <div class="preview-paper" :class="{ 'preview-mobile': device === 'mobile' }">
        <h3>{{ name || '未命名表单' }}</h3>
        <a-alert v-if="error" type="error" :message="error" show-icon />
        <a-alert v-if="result" type="success" :message="result" show-icon />
        <a-alert v-if="ruleError" type="warning" :message="ruleError" show-icon />
        <FormDetailProvider :detail-ids="details.map(detail => detail.id!)">
          <RecordReadView
            v-if="mode === 'read'"
            :nodes="layoutNodes"
            :fields="fields"
            :values="values"
            :options="definition.fieldOptions"
            :application-id="applicationId"
            :object-id="definition.objectId"
            :relations="definition.relations"
            :layout="options?.layout"
            preview
          />
          <RecordForm
            v-else
            :key="generation"
            ref="form"
            v-model="values"
            :nodes="layoutNodes"
            :fields="fields"
            :options="definition.fieldOptions"
            :model="mainModel"
            :creating="mode === 'create'"
            :application-id="applicationId"
            :object-id="definition.objectId"
            :relations="definition.relations"
            :layout="options?.layout"
            :rule-states="ruleStates.master"
            preview
          />
          <template #detail="{ detailId, mode: preferredMode, title: detailTitle }">
            <section
              v-for="detail in details.filter(d => d.id === detailId)"
              :key="detail.id!"
              class="detail-preview"
              :class="{ 'detail-grid': mode !== 'read' && detailMode(detail.id!, preferredMode) === 'GRID' }"
            >
              <h3>{{ detailTitle || detail.name }}</h3>
              <a-radio-group
                v-if="mode !== 'read'"
                :value="detailMode(detail.id!, preferredMode)"
                :options="[
                  { label: '表格', value: 'GRID' },
                  { label: '卡片', value: 'CARDS' }
                ]"
                option-type="button"
                size="small"
                @update:value="detailModes[detail.id!] = $event"
              />
              <div class="detail-body">
                <div
                  v-if="mode !== 'read' && detailMode(detail.id!, preferredMode) === 'GRID'"
                  class="detail-grid-head"
                >
                  <span>序号 / 操作</span>
                  <span v-for="field in detailContexts[detail.id!]!.fields" :key="field.id!">
                    {{ formFieldLabel(field, detailNodes?.[detail.id!]) }}{{ field.required ? ' *' : '' }}
                  </span>
                </div>
                <div v-for="(row, index) in rows[detail.id!]" :key="row.key" class="detail-row" :data-row-key="row.key">
                  <RecordReadView
                    v-if="mode === 'read'"
                    :nodes="detailNodes?.[detail.id!]"
                    :fields="detailContexts[detail.id!]!.fields"
                    :values="row.values"
                    :options="detail.fieldOptions"
                    :application-id="applicationId"
                    :object-id="definition.objectId"
                    :detail-id="detail.id!"
                    :relations="detailContexts[detail.id!]!.relations"
                    preview
                  />
                  <template v-else>
                    <div class="detail-row-actions">
                      <strong>明细 {{ index + 1 }}</strong>
                      <a-space>
                        <a-button size="small" :disabled="index === 0" @click="moveRow(detail.id!, index, -1)">
                          上移
                        </a-button>
                        <a-button
                          size="small"
                          :disabled="index === rows[detail.id!]!.length - 1"
                          @click="moveRow(detail.id!, index, 1)"
                        >
                          下移
                        </a-button>
                        <a-button size="small" @click="copyRow(detail.id!, row)">复制</a-button>
                        <a-button size="small" type="link" danger @click="rows[detail.id!]!.splice(index, 1)">
                          移除
                        </a-button>
                      </a-space>
                    </div>
                    <RecordForm
                      ref="detailForms"
                      v-model="row.values"
                      :client-row-key="row.key"
                      :compact="detailMode(detail.id!, preferredMode) === 'GRID'"
                      :nodes="detailNodes?.[detail.id!]"
                      :parent-values="values"
                      :fields="detailContexts[detail.id!]!.fields"
                      :options="detail.fieldOptions"
                      :model="detailContexts[detail.id!]!.model"
                      :application-id="applicationId"
                      :object-id="definition.objectId"
                      :detail-id="detail.id!"
                      :relations="detailContexts[detail.id!]!.relations"
                      :rule-states="rowRuleStates(detail.id!, row)"
                      creating
                      preview
                    />
                  </template>
                </div>
              </div>
              <a-button v-if="mode !== 'read'" @click="addRow(detail.id!)">添加明细</a-button>
              <a-empty v-else-if="!rows[detail.id!]?.length" description="暂无明细" :image="null" />
            </section>
          </template>
        </FormDetailProvider>
        <RelatedFormPreview
          v-for="binding in relatedForms || []"
          ref="relatedEditors"
          :key="`${binding.id}:${generation}`"
          :binding="binding"
          :objects="objects"
          :resources="resources || []"
          :application-id="applicationId"
          :read-only="mode === 'read'"
        />
        <a-button v-if="mode !== 'read'" type="primary" @click="check">校验预览</a-button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.form-preview {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.preview-tools {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.preview-note {
  color: #64748b;
  margin: 12px 0;
}
.preview-scroll {
  overflow: auto;
  min-height: 0;
  flex: 1;
  background: #f5f6fa;
  padding: 20px;
}
.preview-paper {
  max-width: 1000px;
  margin: 0 auto;
  background: white;
  padding: 24px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.preview-mobile {
  width: 390px;
  max-width: 100%;
  padding: 16px;
}
.preview-paper h3 {
  margin: 0 0 24px;
  font-size: 18px;
  line-height: 26px;
  font-weight: 600;
}
.preview-paper :deep(.ant-alert) {
  margin-bottom: 16px;
}
.detail-preview {
  margin: 24px 0;
  border-top: 1px solid #e5e7eb;
  padding-top: 24px;
}
.detail-preview h3 {
  font-size: 15px;
  line-height: 24px;
  margin-bottom: 16px;
}
.detail-row {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 16px;
}
.detail-row + .detail-row {
  margin-top: 16px;
}
.detail-row-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.detail-grid-head {
  display: none;
}
@media (min-width: 769px) {
  .detail-grid .detail-body {
    overflow-x: auto;
    margin: 12px 0;
    border: 1px solid var(--border-color, #e5e7eb);
    border-radius: 6px;
  }
  .detail-grid-head,
  .detail-grid .detail-row {
    display: flex;
    width: max-content;
    min-width: 100%;
  }
  .detail-grid .detail-row {
    margin: 0;
    padding: 0;
    border: 0;
    border-bottom: 1px solid var(--border-color, #e5e7eb);
    border-radius: 0;
  }
  .detail-grid-head {
    background: var(--bg-secondary, #f8fafc);
  }
  .detail-grid-head > span {
    flex: none;
    width: 200px;
    padding: 10px 8px;
    box-sizing: border-box;
  }
  .detail-grid-head > span:first-child,
  .detail-grid .detail-row-actions {
    flex: none;
    width: 310px;
    padding: 8px;
    margin: 0;
    box-sizing: border-box;
  }
}
</style>
