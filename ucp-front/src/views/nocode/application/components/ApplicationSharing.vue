<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { ObjectSharingGrant } from '@/types/nocode/authorization'
import { businessActionOptions } from '@/types/nocode/authorization'
import type { PublishedObject } from '@/types/nocode/application'
import { loadApplicationSharing } from '@/nocode/object-sharing'
import { isAll } from '@/nocode/selection-all'
import ObjectGrantFields from '../../components/ObjectGrantFields.vue'
import ObjectSharingPanel from '../../components/ObjectSharingPanel.vue'

const props = defineProps<{ applicationId: string; objects: Record<string, PublishedObject> }>()
const platform = useNocodePlatform()
const api = platform.applications
const managing = ref('')
async function closeManagement() {
  managing.value = ''
  await load()
}
const grants = ref<ObjectSharingGrant[]>([]),
  sharingObjects = ref<Record<string, PublishedObject>>({}),
  unavailable = ref<string[]>([]),
  busy = ref(false),
  error = ref('')
let loadRevision = 0
async function load() {
  const revision = ++loadRevision
  busy.value = true
  error.value = ''
  grants.value = []
  sharingObjects.value = {}
  unavailable.value = []
  try {
    const result = await loadApplicationSharing(api, props.applicationId, props.objects)
    if (revision !== loadRevision) return
    grants.value = result.grants
    sharingObjects.value = result.objects
    unavailable.value = result.unavailable
  } catch (e) {
    if (revision === loadRevision) error.value = errorMessage(e)
  } finally {
    if (revision === loadRevision) busy.value = false
  }
}
function find(id: string) {
  return grants.value.find(g => g.objectId === id)
}
watch(
  () => [
    props.applicationId,
    ...Object.values(props.objects).map(object => `${object.objectId}:${object.versionNo}:${object.checksum}`)
  ],
  load,
  { immediate: true }
)
onScopeDispose(() => loadRevision++)
</script>
<template>
  <a-space direction="vertical" size="middle" class="application-sharing">
    <a-alert
      type="info"
      show-icon
      message="先确定应用能使用哪些数据，再在“成员与权限”中分配给具体人员。多个应用使用同一对象时，业务数据仍是同一份。"
    />
    <a-button :loading="busy" @click="load">刷新应用数据权限</a-button>
    <a-alert v-if="error" type="error" :message="error" />
    <a-empty v-if="!Object.keys(objects).length" description="尚未引用对象" />
    <div class="permission-map" aria-label="应用和数据对象的权限总览">
      <article v-for="object in Object.values(objects)" :key="object.objectId" class="permission-card">
        <header>
          <strong>{{ object.definition.objectName }}</strong>
          <a-tag :color="find(object.objectId)?.permission ? 'green' : 'orange'">
            {{ find(object.objectId)?.permission ? '应用已获授权' : '待配置' }}
          </a-tag>
        </header>
        <template v-if="find(object.objectId)?.permission">
          <p>
            {{
              businessActionOptions
                .filter(action => find(object.objectId)!.permission!.actions.includes(action.value))
                .map(action => action.label)
                .join('、') || '未允许任何操作'
            }}
          </p>
          <div class="permission-counts">
            <span>
              查看字段
              <b>
                {{
                  isAll(find(object.objectId)!.permission!.readFields)
                    ? '全部'
                    : find(object.objectId)!.permission!.readFields.length
                }}
              </b>
            </span>
            <span>
              填写字段
              <b>
                {{
                  isAll(find(object.objectId)!.permission!.writeFields)
                    ? '全部'
                    : find(object.objectId)!.permission!.writeFields.length
                }}
              </b>
            </span>
            <span>{{ find(object.objectId)!.permission!.scope === 'ALL' ? '全部记录' : '本人创建记录' }}</span>
          </div>
          <a-alert
            v-if="!find(object.objectId)!.permission!.readFields.length"
            type="warning"
            message="尚未选择可查看字段，运行列表和表单将无法显示内容。"
            show-icon
          />
        </template>
        <p v-else>此应用尚不能使用该对象。配置允许操作及字段后再发布。</p>
        <a-button v-if="platform.hasPermission('nocode:object:share')" type="link" @click="managing = object.objectId">
          配置此应用的数据权限
        </a-button>
        <a-collapse ghost>
          <a-collapse-panel :key="object.objectId" header="查看字段、记录条件和权限详情">
            <a-alert
              v-if="!busy && !error && !find(object.objectId)?.permission"
              type="warning"
              show-icon
              message="尚未授权或已撤销。请使用本卡片的配置按钮，或联系该数据对象的管理员设置应用数据权限。"
            />
            <a-alert
              v-if="unavailable.includes(object.objectId)"
              type="warning"
              show-icon
              message="暂无法读取最新已发布对象结构，请刷新应用数据权限后重试。"
            />
            <template v-if="find(object.objectId)?.permission && sharingObjects[object.objectId]">
              <a-alert
                v-if="sharingObjects[object.objectId]!.versionNo > object.versionNo"
                class="sharing-version-notice"
                type="info"
                show-icon
                :message="`应用草稿引用 V${object.versionNo}，对象最新已发布 V${sharingObjects[object.objectId]!.versionNo}`"
                description="下方显示应用当前可用的数据范围。使用新增字段前，请到“已引用对象”同步版本，再保存并发布应用。"
              />
              <p>{{ find(object.objectId)?.reason }}</p>
              <ObjectGrantFields
                :model-value="find(object.objectId)!.permission!"
                :definition="sharingObjects[object.objectId]!.definition"
                readonly
              />
            </template>
          </a-collapse-panel>
        </a-collapse>
      </article>
    </div>
    <ObjectSharingPanel
      v-if="managing"
      :object-id="managing"
      :application-id="applicationId"
      editor-only
      @closed="closeManagement"
    />
  </a-space>
</template>

<style scoped>
.application-sharing {
  width: 100%;
}
.permission-map {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}
.permission-card {
  padding: 18px;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  background: var(--bg-container);
}
.permission-card header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.permission-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 12px 0;
  color: var(--text-secondary);
}
.sharing-action,
.sharing-version-notice {
  margin-bottom: 16px;
}
</style>
