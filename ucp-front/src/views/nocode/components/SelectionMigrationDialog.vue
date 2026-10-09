<script setup lang="ts">
import { ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { selectionSource } from '@/nocode/selection'
import { errorMessage } from '@/nocode/data-center'
import type { SaveDesign, Dependency } from '@/types/nocode/data-center'
import type { SelectionOption } from '@/types/nocode/selection'
const props = defineProps<{ open: boolean; design: SaveDesign; dependencies: Dependency[] }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; applied: [] }>()
const api = useNocodePlatform().dataCenter
const changes = ref<Awaited<ReturnType<typeof api.selectionChanges>>>([])
const choices = ref<Record<string, SelectionOption[]>>({}),
  busy = ref(false),
  error = ref('')
watch(
  () => props.open,
  async open => {
    if (!open || !props.design.draft.id) return
    busy.value = true
    error.value = ''
    try {
      changes.value = await api.selectionChanges(props.design.draft.id)
      choices.value = Object.fromEntries(
        await Promise.all(
          changes.value.map(async c => [c.fieldId, await api.selectionOptions(props.design.draft.id!, c.fieldId)])
        )
      )
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      busy.value = false
    }
  }
)
function apply() {
  for (const change of changes.value) {
    if (change.existingValues.some(v => !change.mapping[v]?.length)) {
      error.value = '请为每个旧值选择目标记录或编码'
      return
    }
    const tables = [
      { fields: props.design.draft.fields, options: props.design.fieldOptions },
      ...props.design.details.map(d => ({ fields: d.fields, options: d.fieldOptions }))
    ]
    for (const table of tables) {
      const field = table.fields.find(f => f.id === change.fieldId)
      if (field) {
        const option = table.options[field.key]!
        if (!option.selection) option.selection = selectionSource(field, option)
        if (!option.selection) {
          error.value = '请先在字段配置中明确新的数据来源'
          return
        }
        option.selection.migrationMap = JSON.parse(JSON.stringify(change.mapping))
      }
    }
  }
  emit('applied')
  emit('update:open', false)
}
</script>
<template>
  <a-modal
    wrap-class-name="os-scroll-modal"
    :open="open"
    title="检查选择字段转换"
    width="780px"
    :confirm-loading="busy"
    ok-text="应用映射到草稿"
    :ok-button-props="{ disabled: !changes.length || busy }"
    @ok="apply"
    @cancel="emit('update:open', false)"
  >
    <a-alert
      type="info"
      show-icon
      message="先逐项映射旧值，再保存草稿并生成发布计划。真正的数据转换与对象发布在同一事务中执行。"
    />
    <a-alert
      v-if="dependencies.length"
      type="warning"
      show-icon
      class="notice"
      :message="'当前依赖：' + dependencies.map(d => d.sourceName || d.sourceKey).join('、')"
      description="来源或数量变化会影响旧应用。发布计划会阻止不兼容的既有引用；先协调应用引用，再执行转换。"
    />
    <a-alert v-if="error" class="notice" type="error" show-icon :message="error" />
    <a-spin :spinning="busy">
      <a-empty v-if="!busy && !changes.length" description="没有需要转换的选择字段" />
      <a-card v-for="change in changes" :key="change.fieldId" :title="change.fieldName" size="small" class="notice">
        <a-alert v-if="change.error" type="warning" :message="change.error" />
        <p v-if="!change.existingValues.length">没有旧值需要映射；发布时检查类型转换及应用依赖。</p>
        <div v-for="value in change.existingValues" :key="value" class="mapping-row">
          <span class="old-value">{{ value }}</span>
          <span>→</span>
          <a-select
            v-model:value="change.mapping[value]"
            mode="multiple"
            show-search
            option-filter-prop="label"
            :options="(choices[change.fieldId] || []).map(o => ({ ...o, label: o.path || o.label }))"
            placeholder="选择目标 ID／编码；目标为单选时每条记录最终只能映射一个值"
          />
        </div>
      </a-card>
    </a-spin>
  </a-modal>
</template>
<style scoped>
.notice {
  margin-top: 14px;
}
.mapping-row {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) 24px minmax(260px, 2fr);
  gap: 12px;
  align-items: center;
  margin-top: 12px;
}
.old-value {
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .mapping-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
  }
}
</style>
