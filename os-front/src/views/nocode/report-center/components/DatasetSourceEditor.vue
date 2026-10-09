<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  datasetKey,
  fieldReferenced,
  referenceKey,
  removeSourceBranch,
  sourceNodes
} from '@/nocode/report-dataset-editor'
import type {
  DatasetAnalysis,
  DatasetSource,
  ReportObjectItem,
  ReportObjectVersion
} from '@/types/nocode/report-center'
const props = defineProps<{ readonly?: boolean; analysis?: DatasetAnalysis | null }>()
const model = defineModel<DatasetSource | null>({ required: true })
const emit = defineEmits<{ catalog: [value: Record<string, ReportObjectVersion>]; busy: [value: boolean] }>()
const api = useNocodePlatform().reportCenter
const catalog = ref<Record<string, ReportObjectVersion>>({})
const options = ref<ReportObjectItem[]>([])
const loading = ref(false),
  changing = ref(false),
  error = ref('')
let request = 0,
  disposed = false
const nodes = computed(() => (model.value ? sourceNodes(model.value) : []))
async function search(search = '') {
  const number = ++request
  loading.value = true
  try {
    const result = await api.sourceObjects({ pageNo: 1, pageSize: 50, search })
    if (number === request) options.value = result.list
  } catch (e) {
    if (number === request) error.value = errorMessage(e)
  } finally {
    if (number === request) loading.value = false
  }
}
async function choose(id: string) {
  if (changing.value || props.readonly) return
  changing.value = true
  error.value = ''
  try {
    const object = await api.sourceObject(id)
    if (disposed) return
    catalog.value[referenceKey(object.reference)] = object
    model.value = { schemaVersion: 1, root: object.reference, relations: [], fields: [] }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    changing.value = false
  }
}
async function addRelation(path: string[], relationId: string) {
  const source = model.value
  const node = nodes.value.find(n => JSON.stringify(n.path) === JSON.stringify(path))
  const relation = node && catalog.value[referenceKey(node.reference)]?.relations.find(r => r.id === relationId)
  if (!source || !relation || changing.value || props.readonly) return
  changing.value = true
  error.value = ''
  try {
    const target = await api.sourceObject(relation.targetObjectId)
    if (disposed || source !== model.value) return
    catalog.value[referenceKey(target.reference)] = target
    source.relations.push({ id: datasetKey('rel'), parentPath: [...path], relationId, target: target.reference })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    changing.value = false
  }
}
function selection(path: string[], field: ReportObjectVersion['fields'][number]) {
  return model.value?.fields.find(f => JSON.stringify(f.path) === JSON.stringify(path) && f.sourceFieldId === field.id)
}
function toggle(path: string[], field: ReportObjectVersion['fields'][number], checked: boolean) {
  if (!model.value || props.readonly) return
  const existing = selection(path, field)
  if (!checked && existing) {
    if (fieldReferenced(props.analysis, existing.id)) {
      error.value = '请先移除该字段的指标、固定条件和格式引用'
      return
    }
    model.value.fields = model.value.fields.filter(f => f.id !== existing.id)
  } else if (checked && !existing) {
    model.value.fields.push({
      id: datasetKey('field'),
      path: [...path],
      sourceFieldId: field.id,
      name: field.name,
      role: field.measure && !path.length ? 'MEASURE' : 'DIMENSION'
    })
  }
}
function removeRelation(alias: string) {
  if (!model.value || props.readonly) return
  try {
    model.value = removeSourceBranch(model.value, alias, props.analysis)
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}
let hydration = 0
watch(
  () => JSON.stringify(nodes.value),
  async () => {
    const number = ++hydration
    error.value = ''
    emit('busy', true)
    try {
      const values = await Promise.all(
        nodes.value.map(n => api.sourceObject(n.reference.objectId, n.reference.versionNo))
      )
      if (number !== hydration || disposed) return
      catalog.value = Object.fromEntries(values.map(value => [referenceKey(value.reference), value]))
      emit('catalog', catalog.value)
    } catch (e) {
      if (number === hydration) error.value = errorMessage(e)
    } finally {
      if (number === hydration) emit('busy', false)
    }
  },
  { immediate: true }
)
watch(changing, value => emit('busy', value))
onScopeDispose(() => {
  disposed = true
  request++
  hydration++
})
</script>
<template>
  <a-alert v-if="error" type="error" :message="error" show-icon />
  <a-form-item
    v-if="!model"
    label="来源对象"
    html-for="report-source-object"
    extra="搜索已发布的数据对象，选择后固定到当前发布版本。"
  >
    <a-select
      id="report-source-object"
      aria-label="来源对象"
      show-search
      :filter-option="false"
      :loading="loading || changing"
      :disabled="readonly || changing"
      :options="options.map(o => ({ value: o.id, label: `${o.name} (${o.code}) · V${o.publishedVersion}` }))"
      @search="search"
      @focus="search()"
      @change="(value: string) => choose(value)"
    />
  </a-form-item>
  <a-collapse v-if="model" :default-active-key="['']">
    <a-collapse-panel
      v-for="node in nodes"
      :key="node.path.join('/')"
      :header="`${node.path.length ? '关联' : '主对象'}：${catalog[referenceKey(node.reference)]?.name || '加载中'} · V${node.reference.versionNo}`"
    >
      <template #extra>
        <a-popconfirm
          v-if="node.path.length && !readonly"
          title="移除此关联、下级关联和所选字段？"
          @confirm="removeRelation(node.path[node.path.length - 1]!)"
        >
          <a-button type="link" danger size="small" :disabled="changing" @click.stop>移除关联</a-button>
        </a-popconfirm>
      </template>
      <a-table
        size="small"
        :scroll="{ x: 'max-content' }"
        :pagination="false"
        :data-source="catalog[referenceKey(node.reference)]?.fields || []"
        row-key="id"
        :columns="[
          { key: 'selected', title: '选用', width: 70 },
          { key: 'name', dataIndex: 'name', title: '来源字段', width: 200 },
          { key: 'alias', title: '分析名称', width: 240 },
          { key: 'role', title: '用途', width: 140 }
        ]"
      >
        <template #bodyCell="{ column, record }">
          <a-checkbox
            v-if="column.key === 'selected'"
            :aria-label="`选用${record.name}`"
            :checked="!!selection(node.path, record)"
            :disabled="readonly || changing"
            @change="(e: { target: { checked: boolean } }) => toggle(node.path, record, e.target.checked)"
          />
          <template v-else-if="column.key === 'alias'">
            <a-input
              v-if="selection(node.path, record)"
              :aria-label="`${record.name}分析名称`"
              :value="selection(node.path, record)!.name"
              :disabled="readonly"
              :maxlength="160"
              @update:value="
                (value: string) => {
                  selection(node.path, record)!.name = value
                }
              "
            />
          </template>
          <a-select
            v-else-if="column.key === 'role' && selection(node.path, record)"
            :value="selection(node.path, record)!.role"
            :disabled="readonly"
            :options="[
              { label: '维度', value: 'DIMENSION' },
              ...(record.measure && !node.path.length ? [{ label: '度量', value: 'MEASURE' }] : [])
            ]"
            @update:value="
              (value: 'DIMENSION' | 'MEASURE') => {
                selection(node.path, record)!.role = value
              }
            "
          />
        </template>
      </a-table>
      <a-form-item
        v-if="node.path.length < 2 && !readonly"
        label="添加关联对象"
        class="relation-input"
        extra="仅支持已登记的单值关联，最多两层。关联字段需要显示名称时，请同时选用目标对象的文本标题字段。"
      >
        <a-select
          :value="undefined"
          :aria-label="`${catalog[referenceKey(node.reference)]?.name || '来源'}添加关联`"
          placeholder="选择关联"
          :disabled="changing || model.relations.length >= 50"
          :options="
            (catalog[referenceKey(node.reference)]?.relations || [])
              .filter(
                r =>
                  !model!.relations.some(
                    existing =>
                      JSON.stringify(existing.parentPath) === JSON.stringify(node.path) && existing.relationId === r.id
                  )
              )
              .map(r => ({ value: r.id, label: r.name }))
          "
          @change="(value: string) => addRelation(node.path, value)"
        />
      </a-form-item>
    </a-collapse-panel>
  </a-collapse>
</template>
<style scoped>
.relation-input {
  margin-top: 16px;
}
</style>
