<script setup lang="ts">
import { businessFields, fieldRelation, linkableField } from '@/nocode/business-fields'
import { boundFields } from '@/nocode/application-ui'
import { computed, ref, watch, nextTick, inject } from 'vue'
import { Form } from 'ant-design-vue'
import { ResourceKind } from '@/types/nocode/application'
import type { PublishedDefinition } from '@/types/nocode/application'
import type { SelectionOption, SelectionPresentation } from '@/types/nocode/selection'
import { selectionSource, selectionInScope, selectionPreviewKey } from '@/nocode/selection'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { selectionLinkCompatible } from '@/nocode/selection-compatibility'
import { isOptionType } from '@/nocode/field-rules'
import { objectEditorHref, objectRuleHint } from '@/nocode/object-rule-hint'
const props = defineProps<{
  modelValue?: SelectionPresentation
  definition: PublishedDefinition
  fieldId: string
  versionNo?: number
  objects?: Record<string, import('@/types/nocode/application').PublishedObject>
  resources?: import('@/types/nocode/application').ApplicationResource[]
  availableFieldIds?: string[]
  sourceGroups?: { label: string; fieldIds: string[] }[]
}>()
const emit = defineEmits<{ 'update:modelValue': [value: SelectionPresentation] }>()
const formItem = Form.useInjectFormItemContext()
const api = useNocodePlatform().applications
const previewContext = inject(selectionPreviewKey, undefined)
const value = computed(() => props.modelValue || {})
const field = computed(() => businessFields(props.definition).find(f => f.id === props.fieldId)!)
const source = computed(() => selectionSource(field.value, props.definition.fieldOptions[props.fieldId]))
const relation = computed(() => fieldRelation(props.definition.relations, props.fieldId))
const targetDefinition = computed(() =>
  relation.value ? props.objects?.[relation.value.targetObjectId]?.definition : undefined
)
const hierarchy = computed(() => ['ORGANIZATION', 'DEPARTMENT'].includes(source.value?.directory || ''))
// 选项类未作答与选中默认项无法区分，表单层不再提供默认值（实施设计稿 D8、10.2）。
const optionField = computed(() => isOptionType(field.value.type))
const ruleHint = computed(() => objectRuleHint(props.definition, props.fieldId))
const choices = ref<SelectionOption[]>([]),
  selectedDefaults = ref<SelectionOption[]>([]),
  error = ref('')
const targetFields = computed(() =>
  (targetDefinition.value?.fields || [])
    .filter(
      f =>
        !!value.value.linkFieldId &&
        selectionLinkCompatible(props.definition, value.value.linkFieldId, targetDefinition.value!, f.id!)
    )
    .map(f => ({ value: f.id!, label: f.name }))
)
const missingTarget = computed(
  () => !!value.value.linkTargetFieldId && !targetFields.value.some(f => f.value === value.value.linkTargetFieldId)
)
const defaultChoices = computed(() => {
  const map = new Map(
    selectionInScope(choices.value, value.value.rootIds, value.value.includeDescendants).map(o => [o.value, o])
  )
  // 用户候选分页时单独解析已选默认值；不可选的历史配置保留名称并标记禁用。
  for (const option of selectedDefaults.value) map.set(option.value, option)
  return [...map.values()]
})
const availableFields = computed(
  () =>
    props.availableFieldIds ?? (previewContext?.value.form ? boundFields(previewContext.value.form.nodes) : undefined)
)
const linkFields = computed(() =>
  props.definition.fields
    .filter(
      f =>
        f.id !== props.fieldId &&
        linkableField(f) &&
        (!availableFields.value || availableFields.value.includes(f.id!)) &&
        (relation.value ||
          (hierarchy.value &&
            selectionSource(f, props.definition.fieldOptions[f.id!])?.directory === source.value?.directory))
    )
    .map(f => ({ value: f.id!, label: f.name }))
)
const links = computed(() =>
  props.sourceGroups
    ? props.sourceGroups
        .map(group => ({ label: group.label, options: linkFields.value.filter(f => group.fieldIds.includes(f.value)) }))
        .filter(group => group.options.length)
    : linkFields.value
)
const missingLink = computed(
  () => !!value.value.linkFieldId && !linkFields.value.some(f => f.value === value.value.linkFieldId)
)
// 视图只贡献固定范围：候选查询叠加该范围，视图的列、排序、分页与候选不进入表单。
const views = computed(() =>
  (props.resources || [])
    .filter(
      r =>
        r.kind === ResourceKind.VIEW &&
        String((r.config as { objectId?: string }).objectId || '') === relation.value?.targetObjectId
    )
    .map(r => ({ value: r.id, label: r.name }))
)
const missingView = computed(() => !!value.value.viewId && !views.value.some(v => v.value === value.value.viewId))
function patch(p: Partial<SelectionPresentation>) {
  emit('update:modelValue', { ...value.value, ...p })
  void nextTick(() => formItem.onFieldChange())
}
let generation = 0
async function load(search?: string) {
  const current = ++generation
  error.value = ''
  choices.value = []
  selectedDefaults.value = []
  try {
    if (relation.value) {
      const target = props.objects?.[relation.value.targetObjectId]
      if (!target) throw new Error('请先在应用中引用关系目标对象版本')
    } else {
      const options =
        source.value?.kind === 'LOCAL_OPTIONS'
          ? (props.definition.fieldOptions[props.fieldId]?.options || []).map(o => ({
              value: o.code,
              label: o.label,
              disabled: !!o.disabled,
              code: o.code,
              parentValue: null,
              path: o.label,
              unavailable: false
            }))
          : await api.selectionOptions(
              props.definition.objectId,
              props.fieldId,
              search,
              props.versionNo,
              previewContext?.value.applicationId
            )
      if (current === generation) choices.value = options
      if (source.value?.directory === 'USER' && value.value.defaultValue != null && previewContext?.value) {
        const selected = Array.isArray(value.value.defaultValue) ? value.value.defaultValue : [value.value.defaultValue]
        const result = await api.previewSelection({
          objects: previewContext.value.objects,
          form: previewContext.value.form,
          query: {
            applicationId: previewContext.value.applicationId,
            objectId: props.definition.objectId,
            fieldId: props.fieldId,
            selected,
            pageNo: 1,
            pageSize: 1
          }
        })
        if (current === generation) selectedDefaults.value = result.selected
      }
    }
  } catch (e) {
    if (current === generation) error.value = errorMessage(e)
  }
}
watch(
  () => [
    props.definition.objectId,
    props.fieldId,
    props.versionNo,
    JSON.stringify(source.value),
    JSON.stringify(value.value.defaultValue)
  ],
  () => void load(),
  { immediate: true }
)
</script>
<template>
  <div class="selection-presentation">
    <p v-if="ruleHint" class="object-rule-hint">
      {{ ruleHint }}
      <a :href="objectEditorHref(definition.objectId)" target="_blank" rel="noopener">去数据对象修改</a>
    </p>
    <label>选择器外观</label>
    <a-select
      :id="formItem.id.value"
      :value="value.appearance || 'AUTO'"
      :options="[
        { value: 'AUTO', label: '自动' },
        { value: 'SELECT', label: '下拉选择' },
        ...(hierarchy ? [{ value: 'TREE', label: '树选择' }] : []),
        { value: 'MODAL', label: '弹窗选择' }
      ]"
      @change="patch({ appearance: $event })"
    />
    <template v-if="hierarchy">
      <label>本表单可选范围</label>
      <a-select
        :value="value.rootIds || []"
        :options="choices.map(o => ({ value: o.value, label: o.path || o.label }))"
        mode="multiple"
        show-search
        option-filter-prop="label"
        placeholder="沿用对象范围"
        @change="patch({ rootIds: $event as string[] })"
      />
      <a-checkbox :checked="value.includeDescendants" @change="patch({ includeDescendants: $event.target.checked })">
        包含下级
      </a-checkbox>
    </template>
    <template v-if="hierarchy || relation">
      <label>联动来源字段</label>
      <a-select
        :value="value.linkFieldId || undefined"
        :options="links"
        allow-clear
        show-search
        option-filter-prop="label"
        placeholder="不联动"
        @change="patch({ linkFieldId: $event == null ? null : String($event), linkTargetFieldId: null })"
      />
      <a-alert v-if="missingLink" type="warning" message="联动来源已失效或未放入当前表单，请重新选择或清空" />
      <template v-if="relation && value.linkFieldId">
        <label>关联对象中与来源相等的字段</label>
        <a-select
          :value="value.linkTargetFieldId || undefined"
          :options="targetFields"
          placeholder="选择与来源兼容的字段"
          @change="patch({ linkTargetFieldId: String($event) })"
        />
        <p>仅显示类型兼容的字段；对象引用必须指向同一对象，单选必须使用相同字典或一致选项。</p>
        <a-alert v-if="missingTarget" type="warning" message="关联筛选字段已失效或与来源不兼容，请重新选择" />
      </template>
      <p v-if="sourceGroups">本行字段只影响当前行；主表字段变化会影响使用它联动的各行。未填写上游字段时不可选择。</p>
    </template>
    <template v-if="relation">
      <label>限定视图</label>
      <a-select
        :value="value.viewId || undefined"
        :options="views"
        allow-clear
        show-search
        option-filter-prop="label"
        placeholder="不限"
        @change="patch({ viewId: $event == null ? null : String($event) })"
      />
      <p>候选取该视图的固定范围，只收紧不授权；留空不限制。视图的列、排序和分页不进入表单。</p>
      <a-alert v-if="missingView" type="warning" message="限定视图已不存在，请重新选择或清空后保存" />
    </template>
    <template v-if="!relation && !optionField">
      <label>本表单默认值</label>
      <a-select
        :value="value.defaultValue || undefined"
        :options="defaultChoices.map(o => ({ ...o, label: o.path || o.label }))"
        :mode="field.type === 'MULTI_SELECT' ? 'multiple' : undefined"
        allow-clear
        show-search
        option-filter-prop="label"
        placeholder="沿用对象默认值"
        @search="source?.directory === 'USER' && load($event)"
        @change="
          patch({ defaultValue: $event == null ? null : Array.isArray($event) ? $event.map(String) : String($event) })
        "
      />
    </template>
    <p>来源与选择数量由对象维护。范围只会收紧；上游字段变化后需重新选择。</p>
    <a-alert v-if="error" type="error" :message="error" />
  </div>
</template>
<style scoped>
.selection-presentation {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.selection-presentation label {
  margin-top: 8px;
}
.selection-presentation p {
  color: #64748b;
  font-size: 12px;
  margin: 4px 0;
}
</style>
