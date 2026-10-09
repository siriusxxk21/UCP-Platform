<script setup lang="ts">
import { computed } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import type { FormConfig, RelatedFormBinding } from '@/types/nocode/application-ui'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'
import { relatedFormOptions, relatedTarget } from '@/nocode/related-form'
const props = defineProps<{
  objectId: string
  objects: Record<string, PublishedObject>
  resources: ApplicationResource[]
}>()
const value = defineModel<RelatedFormBinding[]>({ default: () => [] })
const options = computed(() => relatedFormOptions(props.objectId, props.objects))
const forms = (binding: RelatedFormBinding) =>
  props.resources
    .filter(
      r =>
        r.kind === 'FORM' &&
        r.config.objectId === relatedTarget(binding, props.objects) &&
        !(r.config as unknown as FormConfig).relatedForms?.length
    )
    .map(r => ({ label: r.name, value: r.id }))
function add(key: string) {
  const selected = options.value.find(o => o.value === key)
  if (!selected) return
  value.value = [
    ...value.value,
    {
      id: uuidv4(),
      sourceObjectId: selected.sourceObjectId,
      relationId: selected.relationId,
      direction: selected.direction,
      formId: '',
      title: props.objects[selected.targetId]!.definition.objectName
    }
  ]
}
</script>
<template>
  <a-divider />
  <h4>关联数据一起填写</h4>
  <p class="hint">
    把已有独立对象的表单放在这里。填写后与主记录一次保存，任何一项失败均不生效；解除关联不会删除独立记录。
  </p>
  <div v-for="binding in value" :key="binding.id" class="related-config">
    <a-input v-model:value="binding.title" placeholder="区域名称" />
    <a-select
      v-model:value="binding.formId"
      :options="forms(binding)"
      placeholder="选择关联对象的表单"
      style="width: 100%"
    />
    <small>
      {{ objects[relatedTarget(binding, objects)]?.definition.objectName }} ·
      {{ binding.direction === 'INCOMING' ? '关联到当前记录的数据' : '当前记录选择的数据' }}
    </small>
    <a-button type="link" danger @click="value = value.filter(b => b.id !== binding.id)">移除录入区</a-button>
  </div>
  <a-select
    :value="undefined"
    :options="
      options.filter(o => !value.some(b => b.relationId === o.relationId && b.sourceObjectId === o.sourceObjectId))
    "
    placeholder="添加一个关联对象"
    style="width: 100%"
    @change="add"
  />
  <p class="hint">
    没有可选项时，先在数据中心配置对象关系，并在应用中引用对象。当前支持一层关联、直接生效；支持暂存的新建表单可一起暂存，其他表单请填写后直接保存。
  </p>
</template>
<style scoped>
.related-config {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
}
.hint,
small {
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.7;
}
</style>
