<script setup lang="ts">
defineOptions({ inheritAttrs: false })
defineProps<{ fieldId?: string | null; label: string }>()
</script>
<template>
  <a-alert
    class="unavailable-field-design"
    :data-unavailable-field-id="fieldId"
    type="warning"
    show-icon
    :message="`${label}（字段已不可用）`"
    description="此字段在当前对象版本中不可用。原有配置仍保留，请从画布移除此字段后保存。"
  />
</template>
