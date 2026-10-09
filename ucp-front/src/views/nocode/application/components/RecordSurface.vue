<script setup lang="ts">
import { provide, inject, onBeforeUnmount } from 'vue'
import { Modal, Drawer } from 'ant-design-vue'
import { editorCloseKey } from '@/nocode/edit-boundary'
import { RecordOpenMode } from '@/types/nocode/application-ui'
const props = defineProps<{ open: boolean; title: string; mode?: RecordOpenMode; inline?: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const parent = inject(editorCloseKey, undefined),
  checks = new Set<() => Promise<boolean>>()
provide(editorCloseKey, checks)
async function check() {
  if (!props.open) return true
  for (const verify of checks) if (!(await verify())) return false
  return true
}
parent?.add(check)
onBeforeUnmount(() => parent?.delete(check))
async function close() {
  if (await check()) emit('update:open', false)
}
</script>
<template>
  <section v-if="inline && open" class="record-inline">
    <a-space class="record-inline__heading">
      <a-button @click="close">返回列表</a-button>
      <strong>{{ title }}</strong>
    </a-space>
    <slot />
  </section>
  <component
    v-else-if="!inline"
    :is="mode === RecordOpenMode.MODAL ? Modal : Drawer"
    :open="open"
    :title="title"
    :width="mode === RecordOpenMode.MODAL ? 'min(1000px, 94vw)' : 'min(1180px, 94vw)'"
    :destroy-on-close="true"
    :footer="null"
    :mask-closable="false"
    @cancel="close"
    @close="close"
  >
    <slot v-if="open" />
  </component>
</template>
<style scoped>
.record-inline__heading {
  margin-bottom: 20px;
}
</style>
