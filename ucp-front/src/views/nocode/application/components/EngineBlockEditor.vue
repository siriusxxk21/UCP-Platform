<script setup lang="ts">
import { computed } from 'vue'
import type { PublishedObject } from '@/types/nocode/application'
import type { EngineBlockConfig, EngineLibraryConfig, EngineWritebackConfig } from '@/types/nocode/application-ui'
import { DEFAULT_ENGINE_URL, engineUrlError, parseEngineConfig } from '@/nocode/engine-block'

/**
 * 设计引擎区块配置（laneEG）：引擎地址、材料库 / 构件库（对象 + 字段映射）、材料清单写回。
 * 区块固定绑定页面当前记录，这里没有“绑定记录”开关；字段类型与归属由后端发布校验再核一次。
 */
const props = defineProps<{
  value?: unknown
  contextObjectId?: string
  objects: Record<string, PublishedObject>
  readOnly?: boolean
}>()
const emit = defineEmits<{ change: [value: string] }>()

const config = computed<EngineBlockConfig>(() => {
  try {
    return parseEngineConfig(props.value) || {}
  } catch {
    return {}
  }
})
const objectOptions = computed(() =>
  Object.values(props.objects).map(o => ({ value: o.objectId, label: o.definition.objectName }))
)
const libraryKeys = [
  ['name', '名称', true],
  ['manufacturer', '厂家', false],
  ['model', '型号', false],
  ['specification', '规格', false],
  ['unit', '单位', false],
  ['unitPrice', '单价', false],
  ['methodCode', '工法编码', false]
] as const
const writebackKeys = [
  ['quantity', '数量', true],
  ['material', '材料（引用材料库）', false],
  ['name', '名称', false],
  ['unit', '单位', false],
  ['unitPrice', '单价（取自材料库）', false],
  ['methodCode', '工法编码', false],
  ['basis', '计算依据', false],
  ['space', '空间', false]
] as const

function fieldOptions(objectId?: string) {
  return (props.objects[objectId || '']?.definition.fields || []).flatMap(f =>
    f.id ? [{ value: f.id, label: `${f.name}（${f.type}）` }] : []
  )
}
/** 写回对象上指向 targetObjectId 的单值引用字段。 */
function referenceOptions(objectId?: string, targetObjectId?: string) {
  const definition = props.objects[objectId || '']?.definition
  if (!definition || !targetObjectId) return []
  const ids = new Set(
    definition.relations
      .filter(r => r.targetObjectId === targetObjectId && (r.kind === 'REFERENCE' || r.kind === 'ONE_TO_ONE'))
      .map(r => r.fieldId)
  )
  return fieldOptions(objectId).filter(o => ids.has(o.value))
}
function emitConfig(next: EngineBlockConfig) {
  if (props.readOnly) return
  emit('change', JSON.stringify(next))
}
function setUrl(value: string) {
  emitConfig({ ...config.value, engineUrl: value.trim() || null })
}
function setLibrary(kind: 'materials' | 'components', patch: Partial<EngineLibraryConfig> | null) {
  const current = config.value[kind] || { objectId: '', fields: {} }
  emitConfig({ ...config.value, [kind]: patch === null ? null : { ...current, ...patch } })
}
function setLibraryField(kind: 'materials' | 'components', key: string, value?: string) {
  const current = config.value[kind] || { objectId: '', fields: {} }
  const fields = { ...current.fields, [key]: value || undefined }
  setLibrary(kind, { fields: JSON.parse(JSON.stringify(fields)) })
}
function setBom(patch: Partial<EngineWritebackConfig> | null) {
  const current = config.value.bom || { objectId: '', recordFieldId: '', keyFieldId: '', fields: {} }
  emitConfig({ ...config.value, bom: patch === null ? null : { ...current, ...patch } })
}
function setBomField(key: string, value?: string) {
  const fields = { ...(config.value.bom?.fields || {}), [key]: value || undefined }
  setBom({ fields: JSON.parse(JSON.stringify(fields)) })
}
const urlError = computed(() => engineUrlError(config.value.engineUrl))
</script>
<template>
  <div class="engine-block-editor">
    <a-alert
      v-if="!contextObjectId"
      type="warning"
      show-icon
      message="设计引擎区块只能放在有当前记录对象的页面"
      description="请先在顶部“页面设置”选择“当前记录对象”（例如工事列表），否则发布时会被拒绝。"
    />
    <a-form-item label="引擎地址" :validate-status="urlError ? 'error' : undefined" :help="urlError || undefined">
      <a-input
        :value="config.engineUrl || ''"
        :placeholder="`留空使用部署默认（${DEFAULT_ENGINE_URL}）`"
        :maxlength="200"
        @change="setUrl($event.target.value)"
      />
    </a-form-item>
    <a-form-item label="绑定记录"><span>当前页面记录（每条记录一份设计）</span></a-form-item>
    <template v-for="kind in ['materials', 'components'] as const" :key="kind">
      <a-divider orientation="left" plain>{{ kind === 'materials' ? '材料库' : '构件库' }}</a-divider>
      <a-form-item label="数据对象">
        <a-select
          :value="config[kind]?.objectId || undefined"
          :options="objectOptions"
          allow-clear
          placeholder="不配置：引擎里不显示此库"
          @change="(v?: string) => setLibrary(kind, v ? { objectId: v, fields: {} } : null)"
        />
      </a-form-item>
      <template v-if="config[kind]?.objectId">
        <a-form-item v-for="[key, label, required] in libraryKeys" :key="key" :label="label" :required="required">
          <a-select
            :value="config[kind]?.fields?.[key] || undefined"
            :options="fieldOptions(config[kind]?.objectId)"
            allow-clear
            @change="(v?: string) => setLibraryField(kind, key, v)"
          />
        </a-form-item>
      </template>
    </template>
    <a-divider orientation="left" plain>材料清单写回</a-divider>
    <a-form-item label="写回对象">
      <a-select
        :value="config.bom?.objectId || undefined"
        :options="objectOptions"
        allow-clear
        placeholder="不配置：引擎不能同步材料清单"
        @change="(v?: string) => setBom(v ? { objectId: v, recordFieldId: '', keyFieldId: '', fields: {} } : null)"
      />
    </a-form-item>
    <template v-if="config.bom?.objectId">
      <a-form-item label="关联当前记录的字段" required>
        <a-select
          :value="config.bom?.recordFieldId || undefined"
          :options="referenceOptions(config.bom?.objectId, contextObjectId)"
          placeholder="写回对象上引用当前对象的字段"
          @change="(v: string) => setBom({ recordFieldId: v })"
        />
      </a-form-item>
      <a-form-item label="引擎行键（文本字段）" required>
        <a-select
          :value="config.bom?.keyFieldId || undefined"
          :options="fieldOptions(config.bom?.objectId)"
          @change="(v: string) => setBom({ keyFieldId: v })"
        />
      </a-form-item>
      <a-form-item v-for="[key, label, required] in writebackKeys" :key="key" :label="label" :required="required">
        <a-select
          :value="config.bom?.fields?.[key] || undefined"
          :options="
            key === 'material'
              ? referenceOptions(config.bom?.objectId, config.materials?.objectId)
              : fieldOptions(config.bom?.objectId)
          "
          allow-clear
          @change="(v?: string) => setBomField(key, v)"
        />
      </a-form-item>
      <p class="muted">
        设计每次保存成功后自动同步（3
        秒内多次保存只同步一次；只读不同步；失败时引擎里提示，可手动重试）。同步按“关联字段 +
        行键”覆盖本记录的清单：引擎写过而这次没有的行会删除，行键为空的人工行不动。
      </p>
    </template>
  </div>
</template>
<style scoped>
.muted {
  color: #8c8c8c;
  font-size: 12px;
}
</style>
