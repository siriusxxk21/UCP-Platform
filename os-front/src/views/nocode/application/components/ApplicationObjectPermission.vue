<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { loadApplicationSharing } from '@/nocode/object-sharing'
import { errorMessage } from '@/nocode/data-center'
import type { PublishedObject } from '@/types/nocode/application'
import type { ObjectGrant } from '@/types/nocode/authorization'
import ObjectSharingPanel from '../../components/ObjectSharingPanel.vue'
import ObjectGrantFields from '../../components/ObjectGrantFields.vue'

const props = defineProps<{
  applicationId: string
  applicationName: string
  objectId: string
  object?: PublishedObject
}>()
const emit = defineEmits<{ closed: [] }>()
const platform = useNocodePlatform()
const canManage = computed(() => platform.hasPermission('nocode:object:share'))
const loading = ref(false),
  error = ref(''),
  permission = ref<ObjectGrant | null>(),
  definition = ref<PublishedObject['definition']>()
let request = 0
async function load() {
  if (canManage.value) return
  const current = ++request
  loading.value = true
  error.value = ''
  permission.value = undefined
  definition.value = undefined
  try {
    const object = props.object || (await platform.applications.objectVersion(props.objectId))
    if (current !== request) return
    const result = await loadApplicationSharing(platform.applications, props.applicationId, {
      [props.objectId]: object
    })
    if (current !== request) return
    if (result.unavailable.length) throw new Error('无法读取最新对象结构，请重试')
    definition.value = result.objects[props.objectId]?.definition
    permission.value = result.grants.find(grant => grant.objectId === props.objectId)?.permission || null
  } catch (e) {
    if (current === request) error.value = errorMessage(e)
  } finally {
    if (current === request) loading.value = false
  }
}
watch(() => [props.applicationId, props.objectId, canManage.value], load, { immediate: true })
onScopeDispose(() => request++)
</script>

<template>
  <ObjectSharingPanel
    v-if="canManage"
    :object-id="objectId"
    :application-id="applicationId"
    :application-name="applicationName"
    editor-only
    @closed="emit('closed')"
  />
  <a-drawer v-else open title="查看数据权限" :width="760" :mask-closable="true" @close="emit('closed')">
    <p class="permission-context">
      {{ applicationName }} · {{ definition?.objectName || object?.definition.objectName || objectId }}
    </p>
    <a-alert type="info" show-icon message="当前为只读查看。如需调整，请联系数据管理员。" />
    <a-spin :spinning="loading">
      <a-alert v-if="error" class="permission-status" type="error" show-icon :message="error">
        <template #action><a-button @click="load">重新加载</a-button></template>
      </a-alert>
      <a-empty
        v-else-if="!loading && !permission"
        class="permission-status"
        description="尚未授权给此应用或授权已撤销"
      />
      <ObjectGrantFields
        v-else-if="permission && definition"
        class="permission-status"
        :model-value="permission"
        :definition="definition"
        readonly
      />
    </a-spin>
    <template #footer><a-button @click="emit('closed')">关闭</a-button></template>
  </a-drawer>
</template>

<style scoped>
.permission-context {
  margin: 0 0 16px;
  font-weight: 600;
}
.permission-status {
  margin-top: 20px;
}
</style>
