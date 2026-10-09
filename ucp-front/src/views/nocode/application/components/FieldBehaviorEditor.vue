<script setup lang="ts">
import type { FieldBehavior } from '@/types/nocode/application-ui'
import type { ObjectField } from '@/types/nocode/object'
import DocumentExpressionEditor from '../../components/DocumentExpressionEditor.vue'
const behavior = defineModel<FieldBehavior | null | undefined>()
defineProps<{ fields: ObjectField[] }>()
const conditions = [
  { key: 'showWhen', label: '满足条件时显示' },
  { key: 'requiredWhen', label: '满足条件时必填' },
  { key: 'readOnlyWhen', label: '满足条件时只读' }
] as const
function toggle(key: (typeof conditions)[number]['key'], enabled: boolean) {
  behavior.value = { ...behavior.value, [key]: enabled ? { op: 'VALUE', value: true, args: [] } : null }
}
</script>
<template>
  <div class="behavior-editor">
    <p>条件在填写和保存时生效；对象必填和权限限制始终保留。</p>
    <div v-for="condition in conditions" :key="condition.key">
      <a-checkbox :checked="!!behavior?.[condition.key]" @change="toggle(condition.key, $event.target.checked)">
        {{ condition.label }}
      </a-checkbox>
      <DocumentExpressionEditor
        v-if="behavior?.[condition.key]"
        v-model="behavior[condition.key]!"
        :fields="fields"
        :details="[]"
        :allow-aggregates="false"
      />
    </div>
    <a-checkbox v-if="behavior?.showWhen" v-model:checked="behavior.clearWhenHidden">
      隐藏时清空（默认保留已填内容）
    </a-checkbox>
  </div>
</template>
<style scoped>
.behavior-editor {
  display: grid;
  gap: 12px;
}
p {
  color: var(--os-text-secondary, #64748b);
  font-size: 12px;
}
</style>
