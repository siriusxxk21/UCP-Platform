<script setup lang="ts">
import { computed, provide, ref } from 'vue'
import type { RelatedFormBinding, FormConfig } from '@/types/nocode/application-ui'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import { relatedTarget } from '@/nocode/related-form'
import { selectionPreviewKey } from '@/nocode/selection'
import { formDesignModel } from '@/nocode/form-design'
import { MemberState } from '@/types/nocode/enums'
import { recordDefaults } from '@/nocode/record-form'
import { businessFieldOptions } from '@/nocode/business-field-rules'
import { formFieldLabel, formFieldProjection } from '@/nocode/form-field-projection'
import { formLayoutNodes } from '@/nocode/form-detail-layout'
import { newRowKey } from '@/nocode/document-save'
import RecordForm from './RecordForm.vue'
import RecordReadView from './RecordReadView.vue'
import FormDetailProvider from './FormDetailProvider.vue'
const props = defineProps<{
  binding: RelatedFormBinding
  objects: Record<string, PublishedObject>
  resources: ApplicationResource[]
  applicationId: string
  readOnly?: boolean
}>()
const target = computed(() => props.objects[relatedTarget(props.binding, props.objects)]?.definition)
const form = computed(
  () => props.resources.find(r => r.id === props.binding.formId)?.config as unknown as FormConfig | undefined
)
const values = ref<Record<string, unknown>>({})
const mainForm = ref<InstanceType<typeof RecordForm>>()
const detailForms = ref<Array<InstanceType<typeof RecordForm>>>([])
async function validate() {
  await mainForm.value?.validate()
  for (const editor of detailForms.value) await editor.validate()
}
defineExpose({ validate })
const rows = ref<Record<string, Array<{ key: string; values: Record<string, unknown> }>>>({})
const details = computed(
  () =>
    target.value?.details.filter(d => d.state === MemberState.ACTIVE && form.value?.detailIds?.includes(d.id!)) || []
)
const layoutNodes = computed(() =>
  formLayoutNodes(
    form.value?.nodes || [],
    details.value.map(detail => detail.id!)
  )
)
const detailModes = ref<Record<string, 'GRID' | 'CARDS'>>({})
const detailMode = (id: string, preferred?: 'GRID' | 'CARDS') => detailModes.value[id] || preferred || 'GRID'
const linkFieldId = computed(
  () =>
    props.objects[props.binding.sourceObjectId]?.definition.relations.find(r => r.id === props.binding.relationId)
      ?.fieldId
)
const fields = computed(
  () => target.value?.fields.filter(f => props.binding.direction !== 'INCOMING' || f.id !== linkFieldId.value) || []
)
const mainModel = computed(() => ({
  ...formDesignModel,
  writeFields: formFieldProjection(fields.value, form.value?.nodes).writeFields
}))
const detailContexts = computed(() =>
  Object.fromEntries(
    details.value.map(detail => {
      const relations = target.value!.relations.filter(r => r.sourceDetailId === detail.id)
      const projection = formFieldProjection(detail.fields, form.value?.detailNodes?.[detail.id!])
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
function addDetail(id: string) {
  const context = detailContexts.value[id]!
  ;(rows.value[id] ||= []).push({
    key: newRowKey(),
    values: recordDefaults(context.fields, context.options, context.model, false)
  })
}
provide(
  selectionPreviewKey,
  computed(() => ({
    applicationId: props.applicationId,
    objects: Object.values(props.objects).map(({ objectId, versionNo, checksum }) => ({
      objectId,
      versionNo,
      checksum
    })),
    form: form.value!
  }))
)
</script>
<template>
  <section style="border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; margin-top: 20px">
    <h4>
      {{ binding.title }}
      <a-tag>独立关联数据</a-tag>
    </h4>
    <p>预览一条关联记录的填写效果。实际运行可选择已有或新增，保存主记录时联合提交。</p>
    <FormDetailProvider :detail-ids="details.map(detail => detail.id!)">
      <RecordReadView
        v-if="target && form && readOnly"
        :values="values"
        :nodes="layoutNodes"
        :fields="fields"
        :options="target.fieldOptions"
        :application-id="applicationId"
        :object-id="target.objectId"
        :relations="target.relations"
        :layout="form.options?.layout"
        preview
      />
      <RecordForm
        v-else-if="target && form"
        ref="mainForm"
        v-model="values"
        :nodes="layoutNodes"
        :fields="fields"
        :options="target.fieldOptions"
        :model="mainModel"
        :application-id="applicationId"
        :object-id="target.objectId"
        :relations="target.relations"
        :layout="form.options?.layout"
        creating
        preview
      />
      <a-alert v-else type="warning" message="请先选择关联对象的表单" />
      <template #detail="{ detailId, mode: preferredMode, title: detailTitle }">
        <section
          v-for="detail in details.filter(d => d.id === detailId)"
          :key="detail.id!"
          style="margin-top: 16px; padding: 12px; background: #f8fafc; border-radius: 8px"
        >
          <h4>
            {{ detailTitle || detail.name }}
            <a-tag>内部明细</a-tag>
          </h4>
          <a-radio-group
            v-if="!readOnly"
            :value="detailMode(detail.id!, preferredMode)"
            :options="[
              { label: '表格', value: 'GRID' },
              { label: '卡片', value: 'CARDS' }
            ]"
            option-type="button"
            size="small"
            @update:value="detailModes[detail.id!] = $event"
          />
          <div class="related-preview-details">
            <div v-if="!readOnly && detailMode(detail.id!, preferredMode) === 'GRID'" class="related-preview-head">
              <span v-for="field in detailContexts[detail.id!]!.fields" :key="field.id!">
                {{ formFieldLabel(field, form?.detailNodes?.[detail.id!]) }}{{ field.required ? ' *' : '' }}
              </span>
            </div>
            <div v-for="(row, index) in rows[detail.id!]" :key="row.key">
              <RecordReadView
                v-if="readOnly"
                :values="row.values"
                :nodes="form?.detailNodes?.[detail.id!]"
                :fields="detailContexts[detail.id!]!.fields"
                :options="detail.fieldOptions"
                :application-id="applicationId"
                :object-id="target!.objectId"
                :detail-id="detail.id!"
                :relations="detailContexts[detail.id!]!.relations"
                preview
              />
              <template v-else>
                <RecordForm
                  ref="detailForms"
                  v-model="row.values"
                  :client-row-key="row.key"
                  :compact="detailMode(detail.id!, preferredMode) === 'GRID'"
                  :nodes="form?.detailNodes?.[detail.id!]"
                  :parent-values="values"
                  :fields="detailContexts[detail.id!]!.fields"
                  :options="detail.fieldOptions"
                  :model="detailContexts[detail.id!]!.model"
                  :application-id="applicationId"
                  :object-id="target!.objectId"
                  :detail-id="detail.id!"
                  :relations="detailContexts[detail.id!]!.relations"
                  creating
                  preview
                />
                <a-button type="link" danger @click="rows[detail.id!]!.splice(index, 1)">移除此行</a-button>
              </template>
            </div>
          </div>
          <a-button v-if="!readOnly" @click="addDetail(detail.id!)">添加明细</a-button>
          <a-empty v-else-if="!rows[detail.id!]?.length" description="暂无明细" :image="null" />
        </section>
      </template>
    </FormDetailProvider>
  </section>
</template>
<style scoped>
.related-preview-details {
  overflow-x: auto;
}
.related-preview-head {
  display: none;
}
@media (min-width: 769px) {
  .related-preview-head {
    display: flex;
    width: max-content;
    min-width: 100%;
    background: var(--bg-secondary, #f8fafc);
  }
  .related-preview-head > span {
    flex: none;
    width: 200px;
    padding: 10px 8px;
    box-sizing: border-box;
  }
}
</style>
