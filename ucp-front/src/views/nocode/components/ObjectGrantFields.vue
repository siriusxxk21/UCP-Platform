<script setup lang="ts">
import { computed, watch } from 'vue'
import { Collapse, CollapsePanel } from 'ant-design-vue'
import type { PublishedDefinition } from '@/types/nocode/application'
import { businessActionOptions, RecordScope, type ObjectGrant } from '@/types/nocode/authorization'
import { FieldType, MemberState, RelationType } from '@/types/nocode/enums'
import DataScopeEditor from './DataScopeEditor.vue'
import { emptyScope } from '@/nocode/data-scope'
import { includesSelection, isAll } from '@/nocode/selection-all'
import AllOrListSelect from './AllOrListSelect.vue'

const props = withDefaults(
  defineProps<{
    definition: PublishedDefinition
    ceiling?: ObjectGrant | null
    readonly?: boolean
    restrictionReason?: string
    streamlined?: boolean
    /** 「全部」跟随的上一层的叫法；不传时按有无上限取“应用数据权限”或“数据对象”。 */
    upperName?: string
    /** 可修改的三个清单是否提供「全部」；入口的引用资料对象传 false。 */
    writeAllAllowed?: boolean
  }>(),
  { writeAllAllowed: true }
)
const grant = defineModel<ObjectGrant>({ required: true })
const emit = defineEmits<{ change: [] }>()
// 条件编辑器原地修改嵌套数据；接口回填更换授权对象时重建监听，不把回填当作用户编辑。
watch(
  () => grant.value,
  (current, _previous, onCleanup) => {
    const stop = watch(
      () => current.actionScopes,
      () => emit('change'),
      { deep: true }
    )
    onCleanup(stop)
  },
  { immediate: true, flush: 'sync' }
)
const restricted = computed(() => props.ceiling !== undefined)
const upper = computed(() => props.upperName || (restricted.value ? '应用数据权限' : '数据对象'))
const advancedCount = computed(() => Object.keys(grant.value.actionScopes || {}).length)
/** 系统计算能否读这个对象，只看记录范围与查看条件；不再由人勾选来源字段。 */
const computeNotice = computed(() =>
  grant.value.scope !== RecordScope.ALL
    ? {
        type: 'warning' as const,
        message:
          '当前不能用于系统计算：记录范围是“当前操作者创建的记录”。改成“全部记录”后，本应用的公式、联动、自动更新才能读取这个对象。'
      }
    : grant.value.actionScopes?.READ
      ? {
          type: 'warning' as const,
          message: '当前不能用于系统计算：“查看”设了记录条件。取消该条件后才能用于系统计算。'
        }
      : { type: 'info' as const, message: '本应用的公式、联动、自动更新可以按这个对象的全部数据计算。' }
)
// 「全部」不含已停用字段（与后端展开口径一致）；清单里残留的已停用字段由选择组件隐藏并提示。
const fields = computed(() =>
  props.definition.fields
    .filter(f => props.definition.fieldOptions?.[String(f.id)]?.state !== MemberState.INACTIVE)
    .map(f => ({ label: f.name, value: f.id! }))
)
const details = computed(() =>
  props.definition.details.filter(d => d.state === MemberState.ACTIVE).map(d => ({ label: d.name, value: d.id! }))
)
const relations = computed(() =>
  props.definition.relations
    .filter(r => r.kind === RelationType.MANY_TO_MANY)
    .map(r => ({ label: r.name, value: r.id! }))
)
const readKeys: Partial<Record<keyof ObjectGrant, keyof ObjectGrant>> = {
  writeFields: 'readFields',
  writeDetails: 'readDetails',
  writeRelations: 'readRelations'
}
function allowed(key: keyof ObjectGrant, value: string): boolean {
  if (!restricted.value) return true
  const upperList = (props.ceiling?.[key] as string[] | undefined) || []
  if (!isAll(upperList)) return upperList.includes(value)
  // 上一层是「全部」：可查看的全部允许；可修改的「全部」= 上一层可查看的那些。
  const readKey = readKeys[key]
  return readKey ? allowed(readKey, value) : true
}
const writable = (id: string) => {
  const field = props.definition.fields.find(f => f.id === id)
  const option = props.definition.fieldOptions?.[id]
  return (
    !!field &&
    ![FieldType.FORMULA, FieldType.SUMMARY, FieldType.AUTO_NUMBER].includes(field.type as any) &&
    !option?.primaryKey &&
    (!option?.generated || props.definition.relations.some(relation => relation.fieldId === id))
  )
}
function selectActions() {
  grant.value.actions = businessActionOptions
    .filter(action => allowed('actions', action.value))
    .map(action => action.value)
  changed()
}
function clearActions() {
  grant.value.actions = []
  changed()
}
/** 可查看是「全部」⇒ 可修改不动；可修改是「全部」⇒ 保持「全部」；两边都是清单 ⇒ 可修改里超出可查看的项去掉。 */
const withinRead = (write: string[] | undefined, read: string[] | undefined) =>
  isAll(read) || isAll(write) ? write || [] : (write || []).filter(id => read?.includes(id))
function changed() {
  grant.value.writeFields = withinRead(grant.value.writeFields, grant.value.readFields)
  grant.value.writeDetails = withinRead(grant.value.writeDetails, grant.value.readDetails)
  grant.value.writeRelations = withinRead(grant.value.writeRelations, grant.value.readRelations)
  if (grant.value.actionScopes)
    for (const key of Object.keys(grant.value.actionScopes))
      if (!grant.value.actions.includes(key as any)) delete grant.value.actionScopes[key]
  emit('change')
}
function toggleScope(action: string, enabled: boolean) {
  grant.value.actionScopes ||= {}
  if (enabled) grant.value.actionScopes[action] = emptyScope()
  else delete grant.value.actionScopes[action]
  changed()
}
</script>
<template>
  <a-form
    layout="vertical"
    :disabled="readonly"
    class="object-grant-fields"
    :class="{ 'object-grant-fields--streamlined': streamlined }"
  >
    <a-form-item label="允许操作">
      <a-button v-if="!readonly" type="link" size="small" @click="selectActions">全选允许的操作</a-button>
      <a-button
        v-if="streamlined && !readonly"
        type="link"
        size="small"
        :disabled="!grant.actions.length"
        @click="clearActions"
      >
        清空操作
      </a-button>
      <a-checkbox-group
        v-model:value="grant.actions"
        :options="businessActionOptions.map(a => ({ ...a, disabled: !allowed('actions', a.value) }))"
        @change="changed"
      />
      <p v-if="restricted" class="grant-field-hint">
        {{ restrictionReason || '只可选择上级已允许的操作和字段；需要更多权限时请联系数据对象管理员。' }}
      </p>
    </a-form-item>
    <a-form-item label="记录范围">
      <a-radio-group v-model:value="grant.scope" @change="changed">
        <a-radio :value="RecordScope.OWN">当前操作者创建的记录</a-radio>
        <a-radio :value="RecordScope.ALL" :disabled="readonly || (restricted && ceiling?.scope !== RecordScope.ALL)">
          全部记录
        </a-radio>
      </a-radio-group>
    </a-form-item>
    <component :is="streamlined ? Collapse : 'div'" class="grant-advanced">
      <component
        :is="streamlined ? CollapsePanel : 'div'"
        key="advanced"
        :header="`高级设置 · 记录条件${advancedCount ? `（已配置 ${advancedCount} 项）` : ''}`"
      >
        <a-collapse class="scope-actions">
          <a-collapse-panel
            v-for="action in businessActionOptions.filter(a => grant.actions.includes(a.value))"
            :key="action.value"
            :header="action.label + '的记录条件'"
          >
            <a-checkbox
              :checked="!!grant.actionScopes?.[action.value]"
              :disabled="readonly"
              @change="toggleScope(action.value, $event.target.checked)"
            >
              仅允许对符合以下条件的记录执行{{ action.label }}
            </a-checkbox>
            <DataScopeEditor
              v-if="grant.actionScopes?.[action.value]"
              v-model="grant.actionScopes[action.value]"
              :fields="definition.fields"
              :readonly="readonly"
              dynamic
              @update:model-value="changed"
            />
            <p v-if="ceiling?.actionScopes?.[action.value]" class="grant-field-hint">
              同时满足对象授予应用的以下条件：
            </p>
            <DataScopeEditor
              v-if="ceiling?.actionScopes?.[action.value]"
              :model-value="ceiling.actionScopes[action.value]"
              :fields="definition.fields"
              readonly
              dynamic
            />
          </a-collapse-panel>
        </a-collapse>
        <p class="grant-field-hint">
          例如：可以查看全部采购单，但只能修改“状态为草稿”的采购单。不设置额外条件时，使用上方记录范围。
        </p>
        <a-alert
          v-if="!restricted"
          class="grant-compute-notice"
          :type="computeNotice.type"
          :message="computeNotice.message"
          show-icon
        />
      </component>
    </component>
    <h4 v-if="streamlined" class="grant-fields-title">字段与关联范围</h4>
    <p class="grant-field-hint">
      {{
        readonly
          ? '以下为已配置的授权范围，可滚动查看完整清单。'
          : '先选择可查看范围，再配置可修改范围。移除查看权限时，对应的修改权限也会移除。'
      }}
      <template v-if="restricted && !readonly">{{ restrictionReason || '灰色项未获上级授权。' }}</template>
    </p>
    <div class="grant-field-grid">
      <div>
        <a-form-item label="可查看字段">
          <AllOrListSelect
            v-model="grant.readFields"
            label="可查看字段"
            :upper-name="upper"
            :readonly="readonly"
            :options="fields.map(f => ({ ...f, disabled: !allowed('readFields', f.value) }))"
            @change="changed"
          />
        </a-form-item>
      </div>
      <div>
        <a-form-item label="可填写和修改字段">
          <AllOrListSelect
            v-model="grant.writeFields"
            label="可填写和修改字段"
            :upper-name="upper"
            :allow-all="writeAllAllowed"
            :readonly="readonly"
            :placeholder="grant.readFields.length ? '从可查看字段中选择' : '请先选择可查看字段'"
            :options="
              fields
                .filter(f => includesSelection(grant.readFields, f.value))
                .map(f => ({
                  ...f,
                  disabled: !allowed('writeFields', f.value) || !writable(f.value),
                  title: allowed('writeFields', f.value) ? f.label : undefined
                }))
            "
            @change="changed"
          />
        </a-form-item>
      </div>
      <div v-if="details.length">
        <a-form-item label="可查看内部明细">
          <AllOrListSelect
            v-model="grant.readDetails"
            label="可查看内部明细"
            :upper-name="upper"
            :readonly="readonly"
            placeholder="搜索并选择内部明细"
            :options="details.map(d => ({ ...d, disabled: !allowed('readDetails', d.value) }))"
            @change="changed"
          />
        </a-form-item>
      </div>
      <div v-if="details.length">
        <a-form-item label="可修改内部明细">
          <AllOrListSelect
            v-model="grant.writeDetails"
            label="可修改内部明细"
            :upper-name="upper"
            :allow-all="writeAllAllowed"
            :readonly="readonly"
            placeholder="从可查看内部明细中选择"
            :options="
              details
                .filter(d => includesSelection(grant.readDetails, d.value))
                .map(d => ({ ...d, disabled: !allowed('writeDetails', d.value) }))
            "
            @change="changed"
          />
        </a-form-item>
      </div>
      <div v-if="relations.length">
        <a-form-item label="可查看多对多关系">
          <AllOrListSelect
            v-model="grant.readRelations"
            label="可查看多对多关系"
            :upper-name="upper"
            :readonly="readonly"
            placeholder="搜索并选择关系"
            :options="relations.map(r => ({ ...r, disabled: !allowed('readRelations', r.value) }))"
            @change="changed"
          />
        </a-form-item>
      </div>
      <div v-if="relations.length">
        <a-form-item label="可修改多对多关系">
          <AllOrListSelect
            v-model="grant.writeRelations"
            label="可修改多对多关系"
            :upper-name="upper"
            :allow-all="writeAllAllowed"
            :readonly="readonly"
            placeholder="从可查看关系中选择"
            :options="
              relations
                .filter(r => includesSelection(grant.readRelations, r.value))
                .map(r => ({ ...r, disabled: !allowed('writeRelations', r.value) }))
            "
            @change="changed"
          />
        </a-form-item>
      </div>
    </div>
  </a-form>
</template>

<style scoped>
.object-grant-fields {
  container-type: inline-size;
}
.object-grant-fields--streamlined {
  display: flex;
  flex-direction: column;
}
.object-grant-fields--streamlined > .grant-advanced {
  order: 4;
  margin-top: 8px;
}
.grant-fields-title {
  margin: 0 0 8px;
  font-size: 14px;
}
.object-grant-fields--streamlined .ant-form-item {
  margin-bottom: 16px;
}
.grant-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.grant-field-hint {
  margin: 0 0 16px;
  color: var(--text-secondary);
  font-size: 12px;
}
.grant-compute-notice {
  margin-bottom: 16px;
}
.object-grant-fields :deep(.ant-checkbox-group),
.object-grant-fields :deep(.ant-radio-group) {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 0;
}
@container (max-width: 560px) {
  .grant-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
