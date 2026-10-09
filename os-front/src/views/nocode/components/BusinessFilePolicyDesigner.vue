<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { BusinessFileGroup, BusinessFilePolicy, SaveDesign } from '@/types/nocode/data-center'
import type { BusinessFileConfigSpace } from '@/types/nocode/business-file'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import {
  businessAttachFields,
  businessDateField,
  businessDirectoryPreview,
  businessFileIssues,
  businessGroupFields,
  businessLabelFields,
  businessRecordTitlePreview,
  fieldKey
} from '@/nocode/business-file-policy'

const props = defineProps<{ design: SaveDesign; disabled?: boolean }>()
const policy = defineModel<BusinessFilePolicy | null | undefined>()
const platform = useNocodePlatform()
const router = useRouter()
const spaces = ref<BusinessFileConfigSpace[]>([])
const spacesLoading = ref(false)
const spacesError = ref('')

/** 与服务端 BusinessFilePolicies 对齐，超出时提示并截断。 */
const MAX_FIXED_LEVELS = 5
const MAX_GROUPS = 2
const MAX_LABEL_FIELDS = 5
const MAX_PARTICIPATING = 20

const groupFields = computed(() => businessGroupFields(props.design))
const labelFields = computed(() => businessLabelFields(props.design))
const attachFields = computed(() => businessAttachFields(props.design))
const issues = computed(() => businessFileIssues(props.design))
const preview = computed(() => businessDirectoryPreview(props.design))
const recordTitlePreview = computed(() => businessRecordTitlePreview(props.design))
const spaceOptions = computed(() =>
  spaces.value.map(space => ({
    value: String(space.id),
    label: space.status === 0 ? space.name : `${space.name}（已停用）`,
    disabled: space.status !== 0 && String(policy.value?.spaceId || '') !== String(space.id)
  }))
)
const formatOptions = [
  { value: 'YEAR', label: '年份' },
  { value: 'MONTH', label: '年月' }
]
const labelOptions = computed(() => {
  const selected = policy.value?.recordLabelFields ?? []
  return labelFields.value.map(f => ({
    value: fieldKey(f),
    label: f.name,
    disabled: selected.length >= MAX_LABEL_FIELDS && !selected.includes(fieldKey(f))
  }))
})
watch(
  policy,
  value => {
    if (!value) return
    // 旧配置的数组字段可能缺失或为 null，进入编辑前补齐为可操作数组
    value.fixedPath ??= []
    value.groups ??= []
    value.recordLabelFields ??= []
    value.fieldIds ??= []
  },
  { immediate: true }
)

async function loadSpaces() {
  spacesLoading.value = true
  spacesError.value = ''
  try {
    spaces.value = await platform.bizFiles.configSpaces()
  } catch (error) {
    spacesError.value = errorMessage(error)
  } finally {
    spacesLoading.value = false
  }
}
onMounted(() => void loadSpaces())

function enable(enabled: boolean) {
  policy.value = enabled
    ? { spaceId: null, spaceName: '', fixedPath: [], groups: [], recordLabelFields: [], fieldIds: [] }
    : null
}
function changeSpace(value: unknown) {
  if (!policy.value) return
  const selected = spaces.value.find(space => String(space.id) === String(value || ''))
  policy.value.spaceId = selected ? String(selected.id) : null
  policy.value.spaceName = selected?.name || ''
}
function addGroup() {
  policy.value?.groups.push({ fieldId: '', format: null })
}
function groupOptions(index: number) {
  const used = new Set(
    (policy.value?.groups ?? []).filter((_, position) => position !== index).map(group => group.fieldId)
  )
  return groupFields.value.filter(f => !used.has(fieldKey(f))).map(f => ({ value: fieldKey(f), label: f.name }))
}
function groupDateField(group: BusinessFileGroup) {
  return businessDateField(groupFields.value.find(f => fieldKey(f) === group.fieldId))
}
function changeGroupField(group: BusinessFileGroup, value: unknown) {
  group.fieldId = typeof value === 'string' ? value : ''
  if (!groupDateField(group)) group.format = null
}
function changeGroupFormat(group: BusinessFileGroup, value: unknown) {
  group.format = value === 'YEAR' || value === 'MONTH' ? value : null
}
function changeLabels(value: unknown) {
  if (!policy.value) return
  const selected = Array.isArray(value) ? (value as string[]) : []
  if (selected.length > MAX_LABEL_FIELDS) {
    message.warning(`记录目录名称最多引用 ${MAX_LABEL_FIELDS} 个字段`)
    policy.value.recordLabelFields = selected.slice(0, MAX_LABEL_FIELDS)
    return
  }
  policy.value.recordLabelFields = selected
}
function participantLimited(key: string) {
  const selected = policy.value?.fieldIds ?? []
  return selected.length >= MAX_PARTICIPATING && !selected.includes(key)
}
function changeParticipants(value: unknown) {
  if (!policy.value) return
  const selected = Array.isArray(value) ? (value as string[]) : []
  if (selected.length > MAX_PARTICIPATING) {
    message.warning(`接入字段最多 ${MAX_PARTICIPATING} 个`)
    policy.value.fieldIds = selected.slice(0, MAX_PARTICIPATING)
    return
  }
  policy.value.fieldIds = selected
}
</script>

<template>
  <div class="business-file-policy">
    <div class="policy-toolbar">
      <div>
        <h3>业务文件</h3>
        <p class="muted">
          接入后，参与字段的新上传进入业务网盘受管目录并按下方模板归档；取消接入的字段沿用普通系统附件，已有业务文件保持原身份和访问规则。
        </p>
      </div>
      <a-switch
        :checked="!!policy"
        :disabled="disabled"
        checked-children="已接入"
        un-checked-children="未接入"
        @change="(v: boolean | string | number) => enable(!!v)"
      />
    </div>
    <template v-if="policy">
      <a-alert
        v-if="issues.length"
        type="warning"
        show-icon
        class="notice"
        message="规则尚不完整，保存草稿时将被服务端拒绝"
        :description="issues.join('；')"
      />
      <a-form layout="vertical" :disabled="disabled">
        <a-form-item label="业务空间" required>
          <a-select
            :value="policy.spaceId ? String(policy.spaceId) : undefined"
            :options="spaceOptions"
            :loading="spacesLoading"
            show-search
            option-filter-prop="label"
            placeholder="选择已有业务空间"
            @change="changeSpace"
          />
          <a-alert
            v-if="spacesError"
            type="warning"
            show-icon
            class="space-alert"
            :message="`业务空间加载失败：${spacesError}`"
          >
            <template #action><a-button size="small" @click="loadSpaces">重试</a-button></template>
          </a-alert>
          <a-alert
            v-else-if="!policy.spaceId && policy.spaceName"
            type="warning"
            show-icon
            class="space-alert"
            message="这是按名称保存的历史规则；再次保存前必须选择一个已有业务空间。"
          />
          <a-alert
            v-else-if="!spacesLoading && !spaceOptions.length"
            type="info"
            show-icon
            class="space-alert"
            message="暂无可用业务空间"
            description="请先到网盘「空间管理」明确新建业务空间。普通团队空间不会出现在此列表，也不支持转换。"
          >
            <template #action>
              <a-button size="small" @click="router.push('/drive/space')">前往空间管理</a-button>
            </template>
          </a-alert>
        </a-form-item>
        <a-form-item label="固定目录" extra="最多 5 层；名称不能包含路径分隔符或以点开头">
          <div class="level-list">
            <div v-for="(level, index) in policy.fixedPath" :key="index" class="level-row">
              <a-input v-model:value="policy.fixedPath[index]" :maxlength="100" placeholder="如：采购合同" />
              <a-button type="text" danger :disabled="disabled" @click="policy.fixedPath.splice(index, 1)">
                移除
              </a-button>
            </div>
            <a-button
              :disabled="disabled || policy.fixedPath.length >= MAX_FIXED_LEVELS"
              @click="policy.fixedPath.push('')"
            >
              添加层级
            </a-button>
          </div>
        </a-form-item>
        <a-form-item label="业务分组" extra="按主表字段取值分层归档，最多 2 层；空值进入“未分类”">
          <div class="level-list">
            <div v-for="(group, index) in policy.groups" :key="index" class="level-row">
              <a-select
                class="group-field"
                :value="group.fieldId || undefined"
                :options="groupOptions(index)"
                placeholder="选择主表字段"
                @change="(value: unknown) => changeGroupField(group, value)"
              />
              <a-select
                v-if="groupDateField(group)"
                class="group-format"
                :value="group.format ?? undefined"
                allow-clear
                :options="formatOptions"
                placeholder="原文"
                @change="(value: unknown) => changeGroupFormat(group, value)"
              />
              <a-button type="text" danger :disabled="disabled" @click="policy.groups.splice(index, 1)">移除</a-button>
            </div>
            <a-button :disabled="disabled || policy.groups.length >= MAX_GROUPS" @click="addGroup">添加分组</a-button>
          </div>
        </a-form-item>
        <a-form-item
          label="记录目录名称"
          extra="默认沿用对象的记录标题或标题模板；需要独立名称时，可选择最多 5 个主表字段，清空后恢复默认。"
        >
          <a-select
            :value="policy.recordLabelFields"
            mode="multiple"
            :options="labelOptions"
            placeholder="沿用对象记录标题（默认）"
            allow-clear
            @change="changeLabels"
          />
          <p class="label-preview">
            {{ policy.recordLabelFields.length ? '自定义名称示意' : '对象标题示意' }}：{{ recordTitlePreview }}
          </p>
        </a-form-item>
        <a-form-item
          label="参与字段"
          :extra="`勾选后该字段的新上传进入业务网盘，最多 ${MAX_PARTICIPATING} 个；每个字段各自建立目录层`"
        >
          <a-empty v-if="!attachFields.length" description="当前对象没有可接入的附件或图片字段" />
          <a-checkbox-group
            v-else
            :value="policy.fieldIds"
            class="participants"
            :disabled="!!disabled"
            @change="changeParticipants"
          >
            <div v-for="item in attachFields" :key="item.key" class="participant">
              <a-checkbox :value="item.key" :disabled="participantLimited(item.key)">{{ item.label }}</a-checkbox>
              <span class="muted">目录：{{ item.field.name }}</span>
            </div>
          </a-checkbox-group>
        </a-form-item>
      </a-form>
      <a-divider />
      <h3>目录预览</h3>
      <p class="preview-path">{{ preview.join(' / ') }}</p>
      <p class="muted">
        业务分组与记录名称按实际记录取值，这里用字段名与当前年份示意；记录 ID 在保存后生成，并作为名称下方的辅助信息。
      </p>
      <a-alert
        type="info"
        show-icon
        class="notice"
        message="发布范围"
        description="新规则只用于新增记录，以及首次建立业务文件归属的旧记录；已有业务目录保留原规则版本。历史规则未配置记录名称时，浏览时也会沿用当前入口可见的对象记录标题。"
      />
    </template>
    <a-empty v-else description="尚未接入业务网盘；接入后参与字段的新上传进入业务网盘受管目录" />
  </div>
</template>

<style scoped>
.business-file-policy {
  max-width: 1080px;
}
.policy-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
h3 {
  margin: 0 0 6px;
}
.muted {
  color: var(--os-text-secondary, #666);
  margin: 0;
}
.notice {
  margin-bottom: 16px;
}
.space-alert {
  margin-top: 8px;
}
.level-list {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}
.level-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.group-field {
  width: 280px;
}
.group-format {
  width: 120px;
}
.participants {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.participant {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.label-preview {
  margin: 8px 0 0;
  color: var(--os-text-secondary, #666);
}
.preview-path {
  padding: 8px 12px;
  background: var(--os-fill-quaternary, #f5f5f5);
  border-radius: 6px;
  word-break: break-all;
}
</style>
