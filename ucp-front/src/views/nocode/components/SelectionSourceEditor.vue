<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { FieldType } from '@/types/nocode/enums'
import { SelectionKind, type SelectionSource } from '@/types/nocode/selection'
import { selectionSource, selectionDirectories } from '@/nocode/selection'
import { fieldTypes } from '@/nocode/object-draft'
import { changeFieldType, changeSelectionCount, resetSelectionSource } from '@/nocode/field-editing'
import { message } from 'ant-design-vue'
import { getOrganizationTree } from '@/api/system/organization'
import { getDepartmentTree } from '@/api/system/department'
import request from '@/utils/request'
import { errorMessage } from '@/nocode/data-center'
import { directoryDefaultOptions, type DirectoryScopeOption } from '@/nocode/directory-scope-options'
import { createDataCenterApi } from '@/api/nocode/data-center'
import {
  createObjectCatalog,
  definitionOptions,
  loadPublishedDefinition,
  objectOptionGroups,
  type PublishedDefinition
} from '@/nocode/field-rules'
const props = defineProps<{
  field: ObjectField
  disabled?: boolean
  detail?: boolean
  reviewSwitch?: (target: ObjectField, options: FieldOptions) => Promise<boolean>
}>()
const options = defineModel<FieldOptions>('options', { required: true })
const emit = defineEmits<{ relation: []; newRelation: []; fieldChange: [field: ObjectField] }>()
const source = computed(() => selectionSource(props.field, options.value)!)
const multiple = computed(() => props.field.type === FieldType.MULTI_SELECT)
const dictionaries = ref<Array<{ name: string; type: string }>>([])
const roots = ref<DirectoryScopeOption[]>([]),
  error = ref('')
let generation = 0
function patch(values: Partial<SelectionSource>) {
  options.value.selection = { ...source.value, ...values }
}
function changeDefaultMode(mode: SelectionSource['defaultMode']) {
  patch({ defaultMode: mode })
  options.value.defaultValue = null
}
async function review(target: ObjectField, targetOptions: FieldOptions): Promise<boolean> {
  return !props.reviewSwitch || (await props.reviewSwitch(target, targetOptions))
}
function apply(target: ObjectField, targetOptions: FieldOptions) {
  emit('fieldChange', target)
  Object.assign(options.value, targetOptions)
}
async function kind(value: keyof typeof SelectionKind) {
  if (value === source.value.kind) return
  if (value === SelectionKind.OBJECT_RELATION) {
    emit('relation')
    return
  }
  const next: SelectionSource = {
    kind: value,
    directory: value === SelectionKind.DIRECTORY ? FieldType.ORGANIZATION : null,
    dictionaryType: null,
    rootIds: [],
    includeDescendants: false,
    organizationTypes: [],
    defaultMode: 'NONE',
    ...(value === SelectionKind.OBJECT_FIELD_OPTIONS ? { sourceObjectId: null, sourceFieldId: null } : {})
  }
  const target = { ...props.field }
  const targetOptions = JSON.parse(JSON.stringify(options.value)) as FieldOptions
  changeFieldType(
    target,
    targetOptions,
    multiple.value
      ? FieldType.MULTI_SELECT
      : value === SelectionKind.DIRECTORY
        ? FieldType.ORGANIZATION
        : FieldType.SELECT
  )
  targetOptions.options = []
  resetSelectionSource(targetOptions, next)
  if (await review(target, targetOptions)) apply(target, targetOptions)
}
/* ── 挑取值：来源对象 → 来源字段（只列单选、多选，含公共字典），下方只读预览实际生效的选项 ── */
const dataCenter = createDataCenterApi(request)
const catalog = createObjectCatalog(dataCenter)
const sourceDefinition = ref<PublishedDefinition | null>(null),
  sourceLoading = ref(false),
  sourceError = ref('')
const pickValue = computed(() => source.value.kind === SelectionKind.OBJECT_FIELD_OPTIONS)
const sourceObjectGroups = computed(() => {
  const id = source.value.sourceObjectId
  const selected =
    id && !catalog.objects.value.some(item => item.value === id)
      ? [{ value: id, label: sourceDefinition.value?.label ?? `对象 ${id}`, category: '已选' }]
      : []
  return objectOptionGroups([...selected, ...catalog.objects.value])
})
const sourceFields = computed(() =>
  (sourceDefinition.value?.fields ?? []).filter(
    item => !!item.id && (item.type === FieldType.SELECT || item.type === FieldType.MULTI_SELECT)
  )
)
function sourceFieldLabel(item: ObjectField) {
  const selection = definitionOptions(sourceDefinition.value, item)?.selection
  return (item.name || item.code) + (selection?.kind === SelectionKind.SYSTEM_DICTIONARY ? '（公共字典）' : '')
}
const sourceFieldChoices = computed(() =>
  sourceFields.value.map(item => ({ value: item.id ?? '', label: sourceFieldLabel(item) }))
)
const sourceField = computed(() => sourceFields.value.find(item => item.id === source.value.sourceFieldId))
const sourceFieldOptions = computed(() =>
  sourceField.value ? definitionOptions(sourceDefinition.value, sourceField.value) : undefined
)
/** 来源字段实际生效的选项集：公共字典取字典项，否则取字段自身的选项（与运行时候选同口径）。 */
const sourceDictionary = computed(() =>
  sourceFieldOptions.value?.selection?.kind === SelectionKind.SYSTEM_DICTIONARY
    ? sourceFieldOptions.value.selection.dictionaryType || null
    : null
)
const dictionaryItems = ref<{ code: string; label: string; disabled: boolean }[]>([])
const previewItems = computed(() =>
  sourceDictionary.value ? dictionaryItems.value : (sourceFieldOptions.value?.options ?? [])
)
const previewTitle = computed(() =>
  sourceDictionary.value
    ? `来源字段使用公共字典（${sourceDictionary.value}），候选为字典项（只读预览）`
    : '来源字段当前的选项（只读预览）'
)
let dictionaryGeneration = 0
watch(
  sourceDictionary,
  async type => {
    const turn = ++dictionaryGeneration
    dictionaryItems.value = []
    if (!type) return
    try {
      const result = await request.get<Array<{ dictType: string; value: string; label: string }>>(
        '/system/dict-data/list-all-simple'
      )
      if (turn === dictionaryGeneration)
        dictionaryItems.value = result
          .filter(item => item.dictType === type)
          .map(item => ({ code: item.value, label: item.label, disabled: false }))
    } catch (e) {
      if (turn === dictionaryGeneration) sourceError.value = errorMessage(e)
    }
  },
  { immediate: true }
)
let sourceGeneration = 0
watch(
  () => (pickValue.value ? source.value.sourceObjectId : null),
  async id => {
    const turn = ++sourceGeneration
    sourceDefinition.value = null
    sourceError.value = ''
    if (!id) return
    sourceLoading.value = true
    try {
      const value = await loadPublishedDefinition(dataCenter, id)
      if (turn === sourceGeneration) sourceDefinition.value = value
    } catch (e) {
      if (turn === sourceGeneration) sourceError.value = errorMessage(e)
    } finally {
      if (turn === sourceGeneration) sourceLoading.value = false
    }
  },
  { immediate: true }
)
watch(
  pickValue,
  value => {
    if (value && !catalog.objects.value.length) void catalog.load('')
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  sourceGeneration++
  dictionaryGeneration++
  catalog.dispose()
})
function sourceObject(value: unknown) {
  const id = typeof value === 'string' ? value : null
  if (props.disabled || id === source.value.sourceObjectId) return
  // 换来源对象即换了值的含义，来源字段一并清空。
  resetSelectionSource(options.value, { ...source.value, sourceObjectId: id, sourceFieldId: null })
}
function sourceFieldChange(value: unknown) {
  if (props.disabled) return
  resetSelectionSource(options.value, { ...source.value, sourceFieldId: typeof value === 'string' ? value : null })
}
function scrollObjects(event: Event) {
  const element = event.target as HTMLElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 24) void catalog.load(undefined, true)
}
async function directory(value: string) {
  if (value === source.value.directory) return
  const old = source.value
  const target = { ...props.field }
  const targetOptions = JSON.parse(JSON.stringify(options.value)) as FieldOptions
  changeFieldType(target, targetOptions, multiple.value ? FieldType.MULTI_SELECT : (value as FieldType))
  resetSelectionSource(targetOptions, { ...old, directory: value })
  if (await review(target, targetOptions)) apply(target, targetOptions)
}
async function dictionary(value: string) {
  if (value === source.value.dictionaryType) return
  const targetOptions = JSON.parse(JSON.stringify(options.value)) as FieldOptions
  resetSelectionSource(targetOptions, { ...source.value, dictionaryType: value })
  if (await review({ ...props.field }, targetOptions)) Object.assign(options.value, targetOptions)
}
async function count(value: boolean) {
  if (props.disabled) return
  const target = { ...props.field }
  const targetOptions = JSON.parse(JSON.stringify(options.value)) as FieldOptions
  const defaultReset = changeSelectionCount(target, targetOptions, value)
  if (!(await review(target, targetOptions))) return
  apply(target, targetOptions)
  if (defaultReset) message.warning('原默认值包含多项，切为单选后请重新选择默认值；候选范围已保留。')
}
watch(
  () => [source.value?.kind, source.value?.directory],
  async () => {
    const current = ++generation
    error.value = ''
    roots.value = []
    try {
      if (source.value.kind === SelectionKind.SYSTEM_DICTIONARY)
        dictionaries.value = await request.get('/system/dict-type/list-all-simple')
      if (source.value.kind === SelectionKind.DIRECTORY) {
        const values: DirectoryScopeOption[] = []
        if (source.value.directory === FieldType.ORGANIZATION) {
          const visit = (nodes: Awaited<ReturnType<typeof getOrganizationTree>>, path = ''): void => {
            for (const n of nodes) {
              const label = path ? path + ' / ' + n.orgName : n.orgName
              values.push({
                value: String(n.id),
                label,
                parentValue: n.parentId ? String(n.parentId) : null,
                organizationType: n.orgType,
                disabled: n.status !== 0
              })
              if (n.children) visit(n.children, label)
            }
          }
          visit(await getOrganizationTree())
        } else if (source.value.directory === FieldType.DEPARTMENT) {
          const visit = (nodes: Awaited<ReturnType<typeof getDepartmentTree>>, path = ''): void => {
            for (const n of nodes) {
              const label = path ? path + ' / ' + n.deptName : n.deptName
              values.push({
                value: String(n.id),
                label,
                parentValue: n.parentId ? String(n.parentId) : null,
                disabled: n.status !== 0
              })
              if (n.children) visit(n.children, label)
            }
          }
          visit(await getDepartmentTree())
        }
        if (current === generation) roots.value = values
      }
    } catch (e) {
      if (current === generation) error.value = errorMessage(e)
    }
  },
  { immediate: true }
)
</script>
<template>
  <div class="source-config">
    <a-row :gutter="20">
      <a-col :span="16">
        <a-form-item label="数据来源">
          <a-select
            :value="source.kind"
            :disabled="disabled"
            :options="[
              { value: SelectionKind.LOCAL_OPTIONS, label: '自定义选项（当前字段）' },
              { value: SelectionKind.SYSTEM_DICTIONARY, label: '平台公共字典' },
              { value: SelectionKind.DIRECTORY, label: '系统目录' },
              {
                value: SelectionKind.OBJECT_FIELD_OPTIONS,
                label: '关联其它表单数据·挑取值（引用其它对象字段的选项）'
              },
              ...(!detail || field.type !== FieldType.MULTI_SELECT
                ? [
                    {
                      value: SelectionKind.OBJECT_RELATION,
                      label: '关联其它表单数据·挑对象（将改为对象关系）',
                      disabled: !!field.id && multiple
                    }
                  ]
                : [])
            ]"
            @change="kind"
          />
        </a-form-item>
      </a-col>
      <a-col :span="8">
        <a-form-item label="选择数量">
          <a-select
            :value="multiple"
            :disabled="disabled"
            :options="[
              { value: false, label: '单选' },
              { value: true, label: '多选' }
            ]"
            @change="count"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <template v-if="pickValue">
      <a-row :gutter="20">
        <a-col :span="12">
          <a-form-item label="来源对象" required>
            <a-select
              :value="source.sourceObjectId || undefined"
              :disabled="disabled"
              show-search
              :filter-option="false"
              :loading="catalog.loading.value"
              :options="sourceObjectGroups"
              placeholder="选择已发布的数据对象（按分类分组）"
              @search="catalog.load($event)"
              @popup-scroll="scrollObjects"
              @update:value="sourceObject"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="来源字段" required>
            <a-select
              :value="source.sourceFieldId || undefined"
              :disabled="disabled || !sourceDefinition"
              :loading="sourceLoading"
              show-search
              option-filter-prop="label"
              :options="sourceFieldChoices"
              placeholder="只列单选、多选字段（含公共字典）"
              not-found-content="来源对象没有单选或多选字段"
              @update:value="sourceFieldChange"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-alert v-if="catalog.error.value" type="error" :message="catalog.error.value" show-icon />
      <a-alert v-if="sourceError" type="error" :message="sourceError" show-icon />
      <div v-if="sourceField" class="source-preview" aria-label="来源字段当前的选项">
        <span class="source-preview-title">{{ previewTitle }}</span>
        <a-tag v-for="item in previewItems" :key="item.code" :color="item.disabled ? 'default' : 'blue'">
          {{ item.label }}{{ item.disabled ? '（已停用）' : '' }}
        </a-tag>
        <a-alert
          v-if="!sourceDictionary && !previewItems.length"
          type="warning"
          show-icon
          message="来源字段当前没有配置选项，发布时会被拒绝；请先在来源对象为该字段配置选项并发布。"
        />
      </div>
    </template>
    <a-alert
      v-if="field.id && !disabled && (!detail || !multiple)"
      type="info"
      show-icon
      :message="multiple ? '已保存多选字段暂不支持原列转为多对多关系' : '切换时将检查当前列与转换影响'"
      :description="
        multiple
          ? '多选关系使用独立关联表；清空原列无法完成此类转换。可以新增多选对象关系。'
          : '确认对象关系时先展示本列历史值和兼容方式；保存草稿不修改数据，发布前会再次检查依赖和需要清空的值。'
      "
      class="source-relation-notice"
    >
      <template v-if="multiple" #action>
        <a-button type="link" @click="emit('newRelation')">新增对象关系</a-button>
      </template>
    </a-alert>
    <template v-if="pickValue">
      <a-row :gutter="20">
        <a-col :span="12">
          <a-form-item label="来源对象" required>
            <a-select
              :value="source.sourceObjectId || undefined"
              :disabled="disabled"
              show-search
              :filter-option="false"
              :loading="catalog.loading.value"
              :options="sourceObjectGroups"
              placeholder="选择已发布的数据对象（按分类分组）"
              @search="catalog.load($event)"
              @popup-scroll="scrollObjects"
              @update:value="sourceObject"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="来源字段" required>
            <a-select
              :value="source.sourceFieldId || undefined"
              :disabled="disabled || !sourceDefinition"
              :loading="sourceLoading"
              show-search
              option-filter-prop="label"
              :options="sourceFieldChoices"
              placeholder="只列单选、多选字段（含公共字典）"
              not-found-content="来源对象没有单选或多选字段"
              @update:value="sourceFieldChange"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-alert v-if="catalog.error.value" type="error" :message="catalog.error.value" show-icon />
      <a-alert v-if="sourceError" type="error" :message="sourceError" show-icon />
      <div v-if="sourceField" class="source-preview" aria-label="来源字段当前的选项">
        <span class="source-preview-title">{{ previewTitle }}</span>
        <a-tag v-for="item in previewItems" :key="item.code" :color="item.disabled ? 'default' : 'blue'">
          {{ item.label }}{{ item.disabled ? '（已停用）' : '' }}
        </a-tag>
        <a-alert
          v-if="!sourceDictionary && !previewItems.length"
          type="warning"
          show-icon
          message="来源字段当前没有配置选项，发布时会被拒绝；请先在来源对象为该字段配置选项并发布。"
        />
      </div>
    </template>
    <a-form-item v-if="source.kind === SelectionKind.DIRECTORY" label="系统目录">
      <a-select
        :value="source.directory"
        :disabled="disabled"
        :options="fieldTypes.filter(t => selectionDirectories.includes(t.value))"
        @change="directory"
      />
    </a-form-item>
    <a-form-item v-if="source.kind === SelectionKind.SYSTEM_DICTIONARY" label="公共字典" required>
      <a-select
        :value="source.dictionaryType"
        :disabled="disabled"
        show-search
        option-filter-prop="label"
        placeholder="选择系统公共字典"
        :options="dictionaries.map(d => ({ value: d.type, label: d.name }))"
        @change="dictionary(String($event))"
      />
    </a-form-item>
    <template
      v-if="
        source.kind === SelectionKind.DIRECTORY &&
        [FieldType.ORGANIZATION, FieldType.DEPARTMENT].some(t => t === source.directory)
      "
    >
      <a-form-item label="可选范围">
        <a-select
          :value="source.rootIds"
          :disabled="disabled"
          mode="multiple"
          show-search
          option-filter-prop="label"
          :options="roots"
          placeholder="不限制；可选择指定组织或部门"
          @change="patch({ rootIds: $event as string[] })"
        />
        <a-checkbox
          :checked="source.includeDescendants"
          :disabled="disabled"
          @change="patch({ includeDescendants: $event.target.checked })"
        >
          包含下级
        </a-checkbox>
      </a-form-item>
      <a-form-item v-if="source.directory === FieldType.ORGANIZATION" label="可选组织类型">
        <a-select
          :value="source.organizationTypes"
          :disabled="disabled"
          mode="multiple"
          show-search
          option-filter-prop="label"
          placeholder="全部类型"
          :options="[
            { value: 1, label: '公司' },
            { value: 2, label: '分公司' },
            { value: 3, label: '部门型组织' }
          ]"
          @change="patch({ organizationTypes: $event as number[] })"
        />
      </a-form-item>
    </template>
    <a-form-item v-if="source.directory === FieldType.ORGANIZATION" label="默认值方式">
      <a-select
        :value="source.defaultMode"
        :disabled="disabled"
        :options="[
          { value: 'NONE', label: '不自动填写' },
          { value: 'FIXED', label: '指定组织' },
          { value: 'CURRENT_USER_ORGANIZATION', label: '当前用户所属组织' }
        ]"
        @change="changeDefaultMode"
      />
    </a-form-item>
    <a-form-item v-if="source.directory === FieldType.ORGANIZATION && source.defaultMode === 'FIXED'" label="默认组织">
      <a-select
        :value="
          multiple ? (options.defaultValue ? JSON.parse(options.defaultValue) : []) : options.defaultValue || undefined
        "
        :mode="multiple ? 'multiple' : undefined"
        :options="directoryDefaultOptions(roots, source)"
        :disabled="disabled"
        show-search
        option-filter-prop="label"
        allow-clear
        @change="
          options.defaultValue = $event == null ? null : Array.isArray($event) ? JSON.stringify($event) : String($event)
        "
      />
    </a-form-item>
    <a-typography-text type="secondary">
      {{
        source.kind === SelectionKind.OBJECT_FIELD_OPTIONS
          ? '保存来源字段的选项编码；候选取自来源对象已发布版本的选项定义，不读取业务数据。'
          : source.kind === SelectionKind.LOCAL_OPTIONS || source.kind === SelectionKind.SYSTEM_DICTIONARY
            ? '保存稳定选项编码，显示名称随来源解析。'
            : '保存目录 ID，显示名称随目录更新；停用记录不能新选，已有引用保留。'
      }}
    </a-typography-text>
    <a-alert v-if="error" type="error" :message="error" show-icon />
  </div>
</template>
<style scoped>
.source-preview {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
}
.source-preview-title {
  width: 100%;
  font-size: 12px;
  color: var(--text-secondary);
}
.source-config {
  padding: 16px;
  margin-bottom: 20px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafbfc;
}
.source-relation-notice {
  margin-bottom: 16px;
}
</style>
