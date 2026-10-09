<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { MemberState } from '@/types/nocode/enums'
import type { PublishedObject } from '@/types/nocode/application'

const props = defineProps<{
  object: PublishedObject
  readOnly?: boolean
  synchronize?: (objectId: string) => Promise<PublishedObject>
}>()
const api = useNocodePlatform().applications
const latest = ref<PublishedObject>(),
  busy = ref(false),
  error = ref('')
const outdated = computed(() => latest.value && latest.value.versionNo > props.object.versionNo)
const added = computed(() => {
  const known = new Set(props.object.definition.fields.map(f => f.id))
  return (
    latest.value?.definition.fields.filter(
      f => !known.has(f.id) && latest.value?.definition.fieldOptions[f.id!]?.state !== MemberState.INACTIVE
    ) || []
  )
})
const title = computed(
  () =>
    `${props.object.definition.objectName}：当前应用草稿引用 V${props.object.versionNo}${latest.value ? `，最新已发布 V${latest.value.versionNo}` : ''}`
)
const description = computed(() => {
  if (error.value) return error.value
  if (outdated.value)
    return `${added.value.length ? `新版新增 ${added.value.length} 个字段：${added.value.map(f => f.name).join('、')}。` : '对象已有新版本。'}授权不会更新应用引用；同步后新增字段才会进入“仅未使用”。现有表单布局保留，运行版本需发布应用后才更新。`
  return '“仅未使用”和重建表单均以当前引用版本为准。对象新增字段须先发布，再同步到应用草稿；授权只决定使用权限。'
})
let generation = 0
async function check() {
  const current = ++generation
  const objectId = props.object.objectId
  busy.value = true
  error.value = ''
  latest.value = undefined
  try {
    const value = await api.objectVersion(objectId)
    if (current === generation) latest.value = value
  } catch (e) {
    if (current === generation) error.value = `无法检查对象更新：${errorMessage(e)}`
  } finally {
    if (current === generation) busy.value = false
  }
}
async function synchronize() {
  if (!props.synchronize || props.readOnly || busy.value || !outdated.value) return
  const current = ++generation
  busy.value = true
  error.value = ''
  try {
    await props.synchronize(props.object.objectId)
  } catch (e) {
    if (current === generation) error.value = errorMessage(e)
  } finally {
    if (current === generation) busy.value = false
  }
}
watch(() => [props.object.objectId, props.object.versionNo, props.object.checksum], check, { immediate: true })
onBeforeUnmount(() => generation++)
</script>
<template>
  <a-alert
    class="form-object-version"
    :type="error || outdated ? 'warning' : 'info'"
    show-icon
    :message="title"
    :description="description"
  >
    <template #action>
      <a-space>
        <a-button size="small" :loading="busy" @click="check">检查对象更新</a-button>
        <a-button
          v-if="outdated && !readOnly && props.synchronize"
          size="small"
          type="primary"
          :loading="busy"
          @click="synchronize"
        >
          同步最新版本
        </a-button>
      </a-space>
    </template>
  </a-alert>
</template>
<style scoped>
.form-object-version {
  margin-bottom: 12px;
  flex-shrink: 0;
}
</style>
