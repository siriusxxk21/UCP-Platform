<script setup lang="ts">
/**
 * 对象的「文件夹」设置
 *
 * 一行 = 表单下方的一个文件夹页签：指定网盘里的一个文件夹，或者用关联记录的文件夹；各有两种放法。
 * 这份设置与对象草稿是两回事：单独保存、保存即生效、不需要发布，所以有自己的保存按钮。
 * 放法是「子文件夹」的行可以再定：什么时候建、子文件夹叫什么、为已有记录补建。
 */
import { computed, defineAsyncComponent, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CloseOutlined,
  DeleteOutlined,
  FolderOutlined,
  LeftOutlined,
  LinkOutlined,
  PlusOutlined,
  RightOutlined
} from '@ant-design/icons-vue'
import { errorMessage } from '@/nocode/data-center'
import { useNocodePlatform } from '@/nocode/platform'
import { createRequestSession } from '@/nocode/request-session'
import { recordFolderNamePreview, recordFolderNoSourceCache, runRecordFolderBackfill } from '@/nocode/record-folder'
import {
  RECORD_FOLDER_DEFAULT_SEPARATOR,
  RECORD_FOLDER_LABEL_MAX,
  RECORD_FOLDER_MAX_SOURCES,
  RECORD_FOLDER_NAME_PARTS_MAX,
  RECORD_FOLDER_NAME_TEXT_MAX,
  RECORD_FOLDER_SEPARATORS,
  recordFolderIssues,
  recordFolderSameConfig,
  recordFolderSaveInput
} from '@/nocode/record-folder-config'
import type { DriveId } from '@/types/drive'
import type {
  RecordFolderBackfillTotal,
  RecordFolderCandidate,
  RecordFolderCreateMode,
  RecordFolderNameField,
  RecordFolderNamePart,
  RecordFolderPlacement,
  RecordFolderSource
} from '@/types/nocode/record-folder'

/** 界面上的一行：没保存过的行还没有 id 与 objectId */
type Row = Omit<RecordFolderSource, 'id' | 'objectId'> & { uid: number; id?: string | null }

const props = defineProps<{ objectId?: string; canManage: boolean }>()
// 选择器要直接调网盘接口（连带整套请求层与路由）：点「指定网盘里的文件夹」时才加载，对象编辑页本身不背这些依赖
const DriveFolderPicker = defineAsyncComponent(() => import('./DriveFolderPicker.vue'))

const api = useNocodePlatform().recordFolders
const session = createRequestSession()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saved = ref<RecordFolderSource[]>([])
const rows = ref<Row[]>([])
const candidates = ref<RecordFolderCandidate[]>([])
const nameFields = ref<RecordFolderNameField[]>([])
let nextUid = 1
const backfill = reactive<{
  open: boolean
  running: boolean
  total: RecordFolderBackfillTotal | null
  error: string
  run: { stop: () => void } | null
}>({ open: false, running: false, total: null, error: '', run: null })

const readOnly = computed(() => !props.canManage || !props.objectId || !!loadError.value)
const issues = computed(() => recordFolderIssues(rows.value))
const dirty = computed(() => !recordFolderSameConfig(rows.value, saved.value))
const full = computed(() => rows.value.length >= RECORD_FOLDER_MAX_SOURCES)

const placementOptions: Record<Row['kind'], Array<{ value: RecordFolderPlacement; label: string }>> = {
  FOLDER: [
    { value: 'RECORD_SUBFOLDER', label: '每条记录一个子文件夹' },
    { value: 'DIRECT', label: '所有记录共用这个文件夹' }
  ],
  RELATION: [
    { value: 'DIRECT', label: '直接放进关联记录的文件夹' },
    { value: 'RECORD_SUBFOLDER', label: '在其中为本记录建一个子文件夹' }
  ]
}
const createModeOptions: Array<{ value: RecordFolderCreateMode; label: string }> = [
  { value: 'ON_FIRST_WRITE', label: '第一次放东西时建' },
  { value: 'ON_SAVE', label: '记录保存时就建' }
]

watch(
  () => props.objectId,
  () => void load(),
  { immediate: true }
)
onBeforeUnmount(() => {
  session.invalidate()
  backfill.run?.stop()
})

const toRow = (source: RecordFolderSource): Row => ({ ...source, uid: nextUid++ })

async function load() {
  const current = session.begin()
  rows.value = []
  saved.value = []
  loadError.value = ''
  if (!props.objectId) return
  loading.value = true
  try {
    const objectId = props.objectId
    const [sources, relationCandidates, fields] = await Promise.all([
      api.config(objectId),
      api.candidates(objectId).catch(() => []),
      api.nameFields(objectId).catch(() => [])
    ])
    if (!current()) return
    saved.value = sources || []
    rows.value = saved.value.map(toRow)
    candidates.value = relationCandidates || []
    nameFields.value = fields || []
  } catch (cause) {
    if (current()) loadError.value = errorMessage(cause)
  } finally {
    if (current()) loading.value = false
  }
}

async function save() {
  if (!props.objectId || saving.value || readOnly.value) return
  if (issues.value.length) {
    message.error(issues.value[0])
    return
  }
  const objectId = props.objectId
  saving.value = true
  try {
    const result = await api.saveConfig(objectId, recordFolderSaveInput(rows.value))
    saved.value = result || []
    rows.value = saved.value.map(toRow)
    recordFolderNoSourceCache().clear(objectId)
    message.success('文件夹设置已保存')
  } catch (cause) {
    message.error(errorMessage(cause))
  } finally {
    saving.value = false
  }
}

function move(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= rows.value.length) return
  const next = [...rows.value]
  ;[next[index], next[target]] = [next[target], next[index]]
  rows.value = next
}
function remove(index: number) {
  rows.value = rows.value.filter((_row, position) => position !== index)
}

/** 页签名称留空时实际会显示的名字 */
function defaultLabel(row: Row): string {
  if (row.kind === 'RELATION') return row.relationName || '页签名称'
  const path = row.folderPath || ''
  return path.includes(' / ') ? path.slice(path.lastIndexOf(' / ') + 3) : '页签名称'
}
// —— 指定网盘里的文件夹 ——
const pickerOpen = ref(false)
const pickerTarget = ref<Row | null>(null)
function pickFolder(row?: Row) {
  pickerTarget.value = row ?? null
  pickerOpen.value = true
}
function onPick(value: { spaceId: DriveId; entryId: DriveId; path: string }) {
  pickerOpen.value = false
  const target = pickerTarget.value
  if (target) {
    Object.assign(target, { spaceId: value.spaceId, entryId: value.entryId, folderPath: value.path, problem: null })
    return
  }
  rows.value = [
    ...rows.value,
    {
      uid: nextUid++,
      kind: 'FOLDER',
      placement: 'RECORD_SUBFOLDER',
      label: '',
      spaceId: value.spaceId,
      entryId: value.entryId,
      folderPath: value.path,
      createMode: 'ON_FIRST_WRITE',
      nameTemplate: null
    }
  ]
}
// —— 用关联记录的文件夹 ——
const relation = reactive<{ open: boolean; target: Row | null; fieldId?: string; sourceId?: string }>({
  open: false,
  target: null
})
const relationOptions = computed(() =>
  candidates.value.map(candidate => ({
    value: candidate.relationFieldId,
    label:
      `${candidate.relationName} → ${candidate.targetObjectName}` +
      (candidate.disabledReason ? `（${candidate.disabledReason}）` : ''),
    disabled: !!candidate.disabledReason
  }))
)
const relationCandidate = computed(() =>
  candidates.value.find(candidate => candidate.relationFieldId === relation.fieldId)
)
const relationSourceOptions = computed(() =>
  (relationCandidate.value?.sources ?? []).map(source => ({ value: source.id, label: source.label }))
)
function pickRelation(row?: Row) {
  relation.target = row ?? null
  relation.fieldId = row?.relationFieldId ?? undefined
  relation.sourceId = row?.targetSourceId ?? undefined
  relation.open = true
}
function changeRelationField(value: unknown) {
  relation.fieldId = String(value)
  const sources = relationCandidate.value?.sources ?? []
  relation.sourceId = sources.length === 1 ? sources[0].id : undefined
}
function confirmRelation() {
  const candidate = relationCandidate.value
  const source = candidate?.sources.find(item => item.id === relation.sourceId)
  if (!candidate || !source) return
  const picked = {
    relationFieldId: candidate.relationFieldId,
    targetSourceId: source.id,
    relationName: candidate.relationName,
    targetObjectId: candidate.targetObjectId,
    targetObjectName: candidate.targetObjectName,
    targetLabel: source.label,
    problem: null
  }
  if (relation.target) Object.assign(relation.target, picked)
  else
    rows.value = [
      ...rows.value,
      { uid: nextUid++, kind: 'RELATION', placement: 'DIRECT', label: '', createMode: 'ON_FIRST_WRITE', ...picked }
    ]
  relation.open = false
}
// —— 子文件夹名称 ——
const textDraft = reactive<Record<number, string>>({})
const dragging = ref<{ uid: number; index: number } | null>(null)

function setCustomName(row: Row, custom: boolean) {
  row.nameTemplate = custom ? { separator: RECORD_FOLDER_DEFAULT_SEPARATOR, parts: [] } : null
}
const partsOf = (row: Row): RecordFolderNamePart[] => row.nameTemplate?.parts ?? []
const partsFull = (row: Row) => partsOf(row).length >= RECORD_FOLDER_NAME_PARTS_MAX
function partLabel(part: RecordFolderNamePart): string {
  if (part.kind === 'TEXT') return part.text ?? ''
  return nameFields.value.find(field => field.fieldId === part.fieldId)?.name ?? '（字段已删除）'
}
/** 还没用过的字段：同一个字段不能出现两次 */
function availableFields(row: Row): RecordFolderNameField[] {
  const used = new Set(partsOf(row).map(part => (part.kind === 'FIELD' ? part.fieldId : undefined)))
  return nameFields.value.filter(field => !used.has(field.fieldId))
}
function addFieldPart(row: Row, fieldId: string) {
  if (!row.nameTemplate || partsFull(row)) return
  row.nameTemplate.parts = [...row.nameTemplate.parts, { kind: 'FIELD', fieldId }]
}
function addTextPart(row: Row) {
  const text = (textDraft[row.uid] ?? '').trim()
  if (!row.nameTemplate || partsFull(row) || !text) return
  row.nameTemplate.parts = [...row.nameTemplate.parts, { kind: 'TEXT', text }]
  textDraft[row.uid] = ''
}
function removePart(row: Row, index: number) {
  if (!row.nameTemplate) return
  row.nameTemplate.parts = row.nameTemplate.parts.filter((_part, position) => position !== index)
}
function movePart(row: Row, from: number, to: number) {
  if (!row.nameTemplate || from === to || to < 0 || to >= row.nameTemplate.parts.length) return
  const next = [...row.nameTemplate.parts]
  next.splice(to, 0, ...next.splice(from, 1))
  row.nameTemplate.parts = next
}
function dropPart(row: Row, index: number) {
  const from = dragging.value
  dragging.value = null
  if (from && from.uid === row.uid) movePart(row, from.index, index)
}
// —— 为已有记录补建 ——

/** 要先保存：补建按库里的设置跑，没保存的行与没保存的修改它都看不到 */
const backfillBlocked = (row: Row) => dirty.value || !row.id
const backfillSummary = computed(() => {
  const total = backfill.total
  return [
    `已处理 ${total?.scanned ?? 0} 条`,
    `新建 ${total?.created ?? 0}`,
    `已有 ${total?.existing ?? 0}`,
    `跳过 ${total?.skipped ?? 0}`,
    `失败 ${total?.failed ?? 0}`
  ].join('\u3000')
})

async function startBackfill(row: Row) {
  if (!props.objectId || !row.id || backfillBlocked(row) || backfill.running) return
  Object.assign(backfill, { open: true, running: true, total: null, error: '' })
  const run = runRecordFolderBackfill(api, props.objectId, row.id, total => (backfill.total = total))
  backfill.run = run
  try {
    backfill.total = await run.done
  } catch (cause) {
    // 已经累计的数字留着，只补一句原因
    backfill.error = errorMessage(cause)
  } finally {
    backfill.running = false
    backfill.run = null
  }
}
function closeBackfill() {
  if (backfill.running) return
  backfill.open = false
}
</script>

<template>
  <div class="record-folder-designer" :class="{ 'record-folder-designer--disabled': !objectId }">
    <div class="designer-toolbar">
      <div>
        <h3>文件夹</h3>
        <p class="muted">表单下方按这里的顺序显示页签；只有一个时不显示页签栏。保存后立即生效，不需要发布。</p>
      </div>
      <a-space v-if="canManage && objectId && !loadError">
        <span v-if="dirty" class="designer-dirty">有未保存的修改</span>
        <a-button type="primary" :loading="saving" :disabled="loading" @click="save">保存文件夹设置</a-button>
      </a-space>
    </div>

    <a-alert v-if="!objectId" type="info" show-icon class="notice" message="请先保存对象" />
    <a-alert v-else-if="loadError" type="warning" show-icon class="notice" :message="loadError">
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>
    <a-alert
      v-else-if="issues.length && !readOnly"
      type="warning"
      show-icon
      class="notice"
      message="设置尚不完整，保存时会被拒绝"
      :description="issues.join('；')"
    />

    <a-spin :spinning="loading">
      <div v-if="rows.length" class="designer-rows">
        <section v-for="(row, index) in rows" :key="row.uid" class="designer-row">
          <header class="designer-row__head">
            <div class="designer-row__target">
              <FolderOutlined v-if="row.kind === 'FOLDER'" />
              <LinkOutlined v-else />
              <span class="designer-row__path">
                {{
                  row.kind === 'FOLDER'
                    ? row.folderPath || '（未选择文件夹）'
                    : `${row.relationName || '（关联字段）'} → ${row.targetLabel || row.targetObjectName || ''}`
                }}
              </span>
              <a-button
                v-if="!readOnly"
                type="link"
                size="small"
                @click="row.kind === 'FOLDER' ? pickFolder(row) : pickRelation(row)"
              >
                更换
              </a-button>
            </div>
            <div class="designer-row__label">
              <span>页签名称</span>
              <a-input
                v-model:value="row.label"
                class="designer-row__label-input"
                :placeholder="defaultLabel(row)"
                :maxlength="RECORD_FOLDER_LABEL_MAX"
                :disabled="readOnly"
              />
              <template v-if="!readOnly">
                <a-tooltip title="上移">
                  <a-button size="small" :disabled="index === 0" aria-label="上移" @click="move(index, -1)">
                    <ArrowUpOutlined />
                  </a-button>
                </a-tooltip>
                <a-tooltip title="下移">
                  <a-button
                    size="small"
                    :disabled="index === rows.length - 1"
                    aria-label="下移"
                    @click="move(index, 1)"
                  >
                    <ArrowDownOutlined />
                  </a-button>
                </a-tooltip>
                <a-button size="small" danger @click="remove(index)">
                  <DeleteOutlined />
                  删除
                </a-button>
              </template>
            </div>
          </header>
          <p v-if="row.problem" class="designer-row__problem">{{ row.problem }}</p>

          <div class="designer-line">
            <span class="designer-line__name">放法</span>
            <a-radio-group v-model:value="row.placement" :disabled="readOnly">
              <a-radio v-for="option in placementOptions[row.kind]" :key="option.value" :value="option.value">
                {{ option.label }}
              </a-radio>
            </a-radio-group>
          </div>

          <template v-if="row.placement === 'RECORD_SUBFOLDER'">
            <div class="designer-line">
              <span class="designer-line__name">什么时候建</span>
              <div>
                <a-radio-group
                  :value="row.createMode || 'ON_FIRST_WRITE'"
                  :disabled="readOnly"
                  @change="
                    (event: { target: { value?: RecordFolderCreateMode } }) => (row.createMode = event.target.value)
                  "
                >
                  <a-radio v-for="option in createModeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </a-radio>
                </a-radio-group>
                <p v-if="row.createMode === 'ON_SAVE'" class="muted designer-line__hint">
                  每条记录都会有一个子文件夹；记录很多时，在网盘里打开上一层文件夹会比较慢。
                </p>
              </div>
            </div>

            <div class="designer-line">
              <span class="designer-line__name">子文件夹名称</span>
              <div class="designer-name">
                <a-radio-group
                  :value="row.nameTemplate ? 'custom' : 'title'"
                  :disabled="readOnly"
                  @change="
                    (event: { target: { value?: string } }) => setCustomName(row, event.target.value === 'custom')
                  "
                >
                  <a-radio value="title">用记录名称</a-radio>
                  <a-radio value="custom">自己组合</a-radio>
                </a-radio-group>
                <template v-if="row.nameTemplate">
                  <div class="designer-name__parts">
                    <span
                      v-for="(part, position) in row.nameTemplate.parts"
                      :key="`${part.kind}:${part.fieldId ?? part.text}:${position}`"
                      class="designer-part"
                      :class="{ 'designer-part--text': part.kind === 'TEXT' }"
                      :draggable="!readOnly"
                      @dragstart="dragging = { uid: row.uid, index: position }"
                      @dragover.prevent
                      @drop.prevent="dropPart(row, position)"
                      @dragend="dragging = null"
                    >
                      <template v-if="!readOnly">
                        <button
                          type="button"
                          class="designer-part__button"
                          aria-label="前移"
                          :disabled="position === 0"
                          @click="movePart(row, position, position - 1)"
                        >
                          <LeftOutlined />
                        </button>
                      </template>
                      <span class="designer-part__label">{{ partLabel(part) }}</span>
                      <template v-if="!readOnly">
                        <button
                          type="button"
                          class="designer-part__button"
                          aria-label="后移"
                          :disabled="position === row.nameTemplate.parts.length - 1"
                          @click="movePart(row, position, position + 1)"
                        >
                          <RightOutlined />
                        </button>
                        <button
                          type="button"
                          class="designer-part__button"
                          aria-label="删除这一段"
                          @click="removePart(row, position)"
                        >
                          <CloseOutlined />
                        </button>
                      </template>
                    </span>
                    <template v-if="!readOnly">
                      <a-dropdown :trigger="['click']" :disabled="partsFull(row) || !availableFields(row).length">
                        <a-button size="small" :disabled="partsFull(row) || !availableFields(row).length">
                          <PlusOutlined />
                          字段
                        </a-button>
                        <template #overlay>
                          <a-menu @click="(info: { key: string | number }) => addFieldPart(row, String(info.key))">
                            <a-menu-item v-for="field in availableFields(row)" :key="field.fieldId">
                              {{ field.name }}
                            </a-menu-item>
                          </a-menu>
                        </template>
                      </a-dropdown>
                      <a-input
                        v-model:value="textDraft[row.uid]"
                        size="small"
                        class="designer-name__text"
                        placeholder="固定文字"
                        :maxlength="RECORD_FOLDER_NAME_TEXT_MAX"
                        :disabled="partsFull(row)"
                        @press-enter="addTextPart(row)"
                      />
                      <a-button
                        size="small"
                        :disabled="partsFull(row) || !(textDraft[row.uid] ?? '').trim()"
                        @click="addTextPart(row)"
                      >
                        <PlusOutlined />
                        固定文字
                      </a-button>
                    </template>
                  </div>
                  <div class="designer-name__preview">
                    <span>分隔符</span>
                    <a-select
                      v-model:value="row.nameTemplate.separator"
                      size="small"
                      class="designer-name__separator"
                      :options="RECORD_FOLDER_SEPARATORS"
                      :disabled="readOnly"
                    />
                    <span>示例：{{ recordFolderNamePreview(row.nameTemplate, nameFields) }}</span>
                  </div>
                </template>
                <p class="muted designer-line__hint">
                  记录建好以后再改这些字段，文件夹不会跟着改名。名称里的 / 和 \ 会换成空格；重名时自动加 (2)。
                </p>
              </div>
            </div>

            <div v-if="canManage" class="designer-line">
              <span class="designer-line__name" />
              <a-tooltip :title="backfillBlocked(row) ? '请先保存文件夹设置' : undefined">
                <span>
                  <a-button :disabled="readOnly || backfillBlocked(row)" @click="startBackfill(row)">
                    为已有记录补建文件夹
                  </a-button>
                </span>
              </a-tooltip>
            </div>
          </template>
        </section>
      </div>
      <a-empty v-else-if="objectId && !loading && !loadError" description="还没有设置文件夹" />
    </a-spin>

    <a-space v-if="canManage && objectId && !loadError" class="designer-add">
      <a-tooltip :title="full ? `一个对象最多配置 ${RECORD_FOLDER_MAX_SOURCES} 个文件夹` : undefined">
        <span>
          <a-button :disabled="full || loading" @click="pickFolder()">
            <PlusOutlined />
            指定网盘里的文件夹
          </a-button>
        </span>
      </a-tooltip>
      <a-tooltip :title="full ? `一个对象最多配置 ${RECORD_FOLDER_MAX_SOURCES} 个文件夹` : undefined">
        <span>
          <a-button :disabled="full || loading" @click="pickRelation()">
            <PlusOutlined />
            用关联记录的文件夹
          </a-button>
        </span>
      </a-tooltip>
    </a-space>

    <DriveFolderPicker v-if="pickerOpen" :open="pickerOpen" @close="pickerOpen = false" @pick="onPick" />

    <a-modal
      v-model:open="relation.open"
      title="用关联记录的文件夹"
      ok-text="确定"
      cancel-text="取消"
      :ok-button-props="{ disabled: !relation.fieldId || !relation.sourceId }"
      @ok="confirmRelation"
    >
      <a-empty v-if="!candidates.length" description="这个对象的已发布版本里没有主表上的单值关联字段" />
      <a-form v-else layout="vertical">
        <a-form-item label="关联字段">
          <a-select
            :value="relation.fieldId"
            :options="relationOptions"
            placeholder="选择关联字段"
            @change="changeRelationField"
          />
        </a-form-item>
        <a-form-item label="对方的文件夹">
          <a-select
            v-model:value="relation.sourceId"
            :options="relationSourceOptions"
            :disabled="!relation.fieldId"
            placeholder="选择对方对象上的一个文件夹"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      :open="backfill.open"
      title="为已有记录补建文件夹"
      :closable="!backfill.running"
      :mask-closable="false"
      :keyboard="false"
      @cancel="closeBackfill"
    >
      <p class="backfill-summary">{{ backfillSummary }}</p>
      <a-alert v-if="backfill.error" type="error" show-icon class="notice" :message="backfill.error" />
      <p v-if="!backfill.running && backfill.total?.stopped" class="muted">
        已停止。再次补建会从头开始，已经有的计入「已有」。
      </p>
      <p v-if="(backfill.total?.skipped ?? 0) > 0" class="muted">跳过的是还没有选关联、或文件夹当前不可用的记录。</p>
      <ul v-if="!backfill.running && backfill.total?.failures.length" class="backfill-failures">
        <li v-for="failure in backfill.total.failures" :key="failure.recordId">
          {{ failure.recordId }}：{{ failure.message }}
        </li>
      </ul>
      <template #footer>
        <a-button v-if="backfill.running" danger @click="backfill.run?.stop()">停止</a-button>
        <a-button v-else type="primary" @click="closeBackfill">关闭</a-button>
      </template>
    </a-modal>
  </div>
</template>

<style scoped>
.record-folder-designer {
  max-width: 1080px;
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid var(--border, #e5e7eb);
}
.record-folder-designer--disabled .designer-toolbar {
  opacity: 0.6;
}
.designer-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
h3 {
  margin: 0 0 6px;
}
.muted {
  margin: 0;
  color: var(--os-text-secondary, #666);
}
.notice {
  margin-bottom: 16px;
}
.designer-dirty {
  color: var(--os-color-warning, #d48806);
}
.designer-rows {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
}
.designer-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
}
.designer-row + .designer-row {
  border-top: 1px solid var(--border, #e5e7eb);
}
.designer-row__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
}
.designer-row__target {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 500;
}
.designer-row__path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.designer-row__label {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
}
.designer-row__label-input {
  width: 180px;
}
.designer-row__problem {
  margin: 0;
  color: var(--os-color-error, #ff4d4f);
}
.designer-line {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.designer-line__name {
  flex: 0 0 96px;
  color: var(--os-text-secondary, #666);
  line-height: 22px;
}
.designer-line__hint {
  margin-top: 4px;
  font-size: 12px;
}
.designer-name {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.designer-name__parts,
.designer-name__preview {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.designer-name__text {
  width: 140px;
}
.designer-name__separator {
  width: 96px;
}
.designer-part {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 1px 4px;
  border: 1px solid var(--ant-color-primary-border, #91caff);
  border-radius: 4px;
  background: var(--ant-color-primary-bg, #e6f4ff);
  line-height: 20px;
}
.designer-part[draggable='true'] {
  cursor: grab;
}
.designer-part--text {
  border-color: var(--border, #d9d9d9);
  background: var(--os-fill-quaternary, #f5f5f5);
}
.designer-part__label {
  padding: 0 4px;
}
.designer-part__button {
  display: inline-flex;
  align-items: center;
  padding: 2px;
  border: 0;
  background: transparent;
  color: var(--os-text-secondary, #666);
  font-size: 10px;
  cursor: pointer;
}
.designer-part__button:disabled {
  opacity: 0.3;
  cursor: default;
}
.designer-add {
  margin-top: 16px;
}
.backfill-summary {
  margin: 0 0 12px;
  font-size: 15px;
}
.backfill-failures {
  max-height: 220px;
  margin: 8px 0 0;
  padding-left: 18px;
  overflow: auto;
}
</style>
