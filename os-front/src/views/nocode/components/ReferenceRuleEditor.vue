<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectRelation } from '@/types/nocode/data-center'
import type { FieldRules, RuleCondition } from '@/types/nocode/field-rules'
import { createDataCenterApi } from '@/api/nocode/data-center'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import { loadPublishedDefinition, type FieldChoiceGroup, type PublishedDefinition } from '@/nocode/field-rules'
import RuleConditionRows from './RuleConditionRows.vue'

/** 挑对象：显示名字段与引用筛选挂在关系引用列的 rules.reference 上；关系本体仍在「对象关系」里维护。 */
const props = defineProps<{
  relation: ObjectRelation
  formGroups: FieldChoiceGroup[]
  filterable?: boolean
  disabled?: boolean
}>()
const model = defineModel<FieldRules['reference']>({ required: true })
const api = createDataCenterApi(request)
const definition = ref<PublishedDefinition | null>(null),
  loading = ref(false),
  error = ref('')
// 与 BusinessFields.linkable 同口径：多值、文件、富文本与汇总不能作为显示名。
const unlinkable: readonly string[] = [
  FieldType.MULTI_SELECT,
  FieldType.IMAGE,
  FieldType.ATTACHMENT,
  FieldType.REGION,
  FieldType.CASCADE,
  FieldType.SUMMARY,
  FieldType.RICH_TEXT
]
const labelFields = computed(() =>
  (definition.value?.fields ?? [])
    .filter(field => !!field.id && !unlinkable.includes(field.type))
    .map(field => ({ value: field.id ?? '', label: field.name || field.code }))
)
const templateHint = computed(
  () => `沿用目标对象标题模板：${definition.value?.titleTemplate || '（目标对象未设置标题模板）'}`
)
let generation = 0
watch(
  () => props.relation.targetObjectId,
  async id => {
    const turn = ++generation
    definition.value = null
    error.value = ''
    if (!id) return
    loading.value = true
    try {
      const value = await loadPublishedDefinition(api, id)
      if (turn === generation) definition.value = value
    } catch (cause) {
      if (turn === generation) error.value = errorMessage(cause)
    } finally {
      if (turn === generation) loading.value = false
    }
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  generation++
})
function update(value: { labelFieldId?: string | null; filter?: RuleCondition[] }) {
  if (props.disabled) return
  const next = { labelFieldId: model.value?.labelFieldId ?? null, filter: model.value?.filter ?? [], ...value }
  model.value = next.labelFieldId || next.filter.length ? next : null
}
</script>
<template>
  <div class="reference-rule">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-form-item label="显示名字段">
      <a-select
        :value="model?.labelFieldId || undefined"
        :loading="loading"
        :disabled="disabled"
        :options="labelFields"
        allow-clear
        show-search
        option-filter-prop="label"
        :placeholder="templateHint"
        aria-label="显示名字段"
        @update:value="update({ labelFieldId: typeof $event === 'string' ? $event : null })"
      />
      <p class="reference-hint">
        {{ model?.labelFieldId ? '候选、已选回显和列表只显示该字段；存的仍是记录 ID。' : templateHint }}
      </p>
    </a-form-item>
    <a-form-item v-if="filterable" label="引用筛选">
      <RuleConditionRows
        :model-value="model?.filter ?? []"
        :definition="definition"
        :form-groups="formGroups"
        :disabled="disabled"
        empty-text="未设置筛选：候选为目标对象中当前用户可见的全部记录。"
        @update:model-value="update({ filter: $event })"
      />
      <p class="reference-hint">
        依赖的当前字段没有值时，候选为空并提示先填写；与表单上的限定视图按「且」叠加，只会更严。
      </p>
    </a-form-item>
  </div>
</template>
<style scoped>
.reference-rule {
  display: grid;
  gap: var(--spacing-sm, 8px);
}
.reference-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
