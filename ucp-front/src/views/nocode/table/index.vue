<script setup lang="ts">
import type { DisplayMode } from '@/components/ucp-modal-form/types'

import * as NC from '@/types/nocode/enums'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { EditOutlined, EyeOutlined, ImportOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage, formatBytes, label } from '@/nocode/data-center'
import { resourceCode, useResourceCode } from '@/nocode/resource-code'
import type * as DC from '@/types/nocode/data-center'
import '../management-tables.css'
import { createRequestSession } from '@/nocode/request-session'

const platform = useNocodePlatform(),
  api = platform.dataCenter,
  router = useRouter()
const rows = ref<DC.TableRow[]>([]),
  schemas = ref<string[]>(['public']),
  total = ref(0),
  loading = ref(false),
  error = ref('')
const query = reactive<DC.TableQuery>({
  pageNo: 1,
  pageSize: 10,
  schema: 'public',
  name: '',
  management: undefined,
  role: undefined,
  structureState: undefined,
  includeSystem: false
})
const canQuery = computed(() => platform.hasPermission('nocode:table:query'))
const canAdopt = computed(
  () =>
    platform.hasPermission('nocode:object:adopt') &&
    platform.hasPermission('nocode:object:create') &&
    platform.hasPermission('nocode:object:query')
)
const pagination = computed(() => ({
  current: query.pageNo,
  pageSize: query.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`,
  pageSizeOptions: ['10', '20', '50', '100']
}))
const columns = [
  { title: '数据表', key: 'tableName', dataIndex: 'tableName', width: 245, ellipsis: true },
  { title: '说明', key: 'comment', dataIndex: 'comment', width: 180, ellipsis: true },
  { title: '管理状态', key: 'management', width: 120 },
  { title: '角色', key: 'role', width: 105 },
  { title: '关联对象', key: 'objectName', dataIndex: 'objectName', width: 150, ellipsis: true },
  { title: '估算记录数', key: 'estimatedRows', dataIndex: 'estimatedRows', width: 110 },
  { title: '占用空间', key: 'bytes', width: 110 },
  { title: '结构状态', key: 'structure', width: 135 },
  { title: '操作', key: 'actions', width: 220, fixed: 'right' as const }
]
let requestNumber = 0
async function load() {
  if (!canQuery.value) return
  const number = ++requestNumber
  loading.value = true
  error.value = ''
  try {
    const result = await api.tables({ ...query })
    if (number === requestNumber) {
      rows.value = result.list
      total.value = result.total
    }
  } catch (cause) {
    if (number === requestNumber) error.value = errorMessage(cause)
  } finally {
    if (number === requestNumber) loading.value = false
  }
}
function search() {
  query.pageNo = 1
  void load()
}
function reset() {
  Object.assign(query, {
    pageNo: 1,
    schema: 'public',
    name: '',
    management: undefined,
    role: undefined,
    objectId: undefined,
    structureState: undefined,
    includeSystem: false
  })
  void load()
}
function page(value: { current?: number; pageSize?: number }) {
  query.pageNo = value.pageSize !== query.pageSize ? 1 : (value.current ?? 1)
  query.pageSize = value.pageSize ?? 10
  void load()
}
const detail = ref<DC.TableDetail>(),
  detailOpen = ref(false),
  detailLoading = ref(false),
  detailTab = ref('columns')
const preview = ref<DC.TablePreview>(),
  previewPage = ref(1),
  previewLoading = ref(false),
  previewError = ref('')
const detailSession = createRequestSession(),
  previewSession = createRequestSession(),
  adoptionSession = createRequestSession()
function closeDetail() {
  detailOpen.value = false
  detailSession.invalidate()
  previewSession.invalidate()
  detailLoading.value = false
  previewLoading.value = false
}
async function showDetail(row: DC.TableRow) {
  const current = detailSession.begin()
  previewSession.invalidate()
  previewLoading.value = false
  detailOpen.value = true
  detailLoading.value = true
  detail.value = undefined
  detailTab.value = 'columns'
  preview.value = undefined
  previewError.value = ''
  previewPage.value = 1
  try {
    const result = await api.table(row.schemaName, row.tableName)
    if (current()) detail.value = result
  } catch (cause) {
    if (current()) {
      error.value = errorMessage(cause)
      detailOpen.value = false
    }
  } finally {
    if (current()) detailLoading.value = false
  }
}
async function readPreview(page: number) {
  if (!detail.value || !detailOpen.value) return
  const table = detail.value
  const request = previewSession.begin()
  const current = () => request() && detailOpen.value && detail.value === table
  previewLoading.value = true
  previewError.value = ''
  try {
    const result = await api.preview(table.table.schemaName, table.table.tableName, page)
    if (!current()) return
    preview.value = result
    previewPage.value = page
    detailTab.value = 'preview'
  } catch (cause) {
    if (current()) previewError.value = errorMessage(cause)
  } finally {
    if (current()) previewLoading.value = false
  }
}
function showObject(id: string) {
  void router.push({ path: '/nocode/object/editor', query: { id } })
}
const preflight = ref<DC.AdoptionPreflight>(),
  adoptionOpen = ref(false),
  adoptionLoading = ref(false),
  adoptionError = ref('')
const adopting = ref(false)
const adoption = reactive({ objectCode: '', objectName: '', titleColumn: '' })
const codeSuggestion = useResourceCode({
  name: () => adoption.objectName,
  kind: () => 'OBJECT',
  setCode: code => {
    adoption.objectCode = code
  }
})
async function showAdoption(row: DC.TableRow) {
  if (adopting.value) return
  const current = adoptionSession.begin()
  adoptionOpen.value = true
  adoptionLoading.value = true
  adoptionError.value = ''
  preflight.value = undefined
  try {
    const result = await api.preflight(row.schemaName, row.tableName)
    if (!current()) return
    preflight.value = result
    codeSuggestion.reset()
    Object.assign(adoption, {
      objectCode: resourceCode(row.comment || row.tableName, 'OBJECT'),
      objectName: row.comment || row.tableName,
      titleColumn: preflight.value.titleColumns.find(c => /name|title/.test(c)) || preflight.value.titleColumns[0]
    })
  } catch (cause) {
    if (current()) adoptionError.value = errorMessage(cause)
  } finally {
    if (current()) adoptionLoading.value = false
  }
}
function closeAdoption() {
  if (adopting.value) {
    message.info('正在创建纳管草稿，请稍候')
    return
  }
  adoptionOpen.value = false
  adoptionSession.invalidate()
  adoptionLoading.value = false
}
async function adopt() {
  if (!preflight.value?.allowed || adoptionLoading.value || adopting.value) return
  const current = adoptionSession.begin()
  adopting.value = true
  adoptionLoading.value = true
  adoptionError.value = ''
  try {
    const d = await api.adopt({
      ...adoption,
      schemaName: preflight.value.schemaName,
      tableName: preflight.value.tableName,
      fingerprint: preflight.value.fingerprint
    })
    if (!current()) return
    adoptionOpen.value = false
    message.success('已建立纳管草稿，请核对字段后发布')
    showObject(d.draft.id)
  } catch (cause) {
    if (current()) adoptionError.value = errorMessage(cause)
  } finally {
    if (current()) {
      adoptionLoading.value = false
      adopting.value = false
    }
  }
}
onBeforeUnmount(() => {
  requestNumber++
  detailSession.invalidate()
  previewSession.invalidate()
  adoptionSession.invalidate()
})
function displayValue(value: unknown): string {
  if (value == null) return '—'
  const text = typeof value === 'object' ? JSON.stringify(value) : String(value)
  return text.length > 500 ? text.slice(0, 500) + '…' : text
}
onMounted(async () => {
  if (!canQuery.value) return
  try {
    schemas.value = await api.schemas()
  } catch (cause) {
    error.value = errorMessage(cause)
  }
  await load()
})
// 每个弹窗独立保留底座的弹窗/抽屉/全屏展示状态。
const modalModes = ref<DisplayMode[]>(Array(2).fill('modal'))
</script>

<template>
  <section class="nocode-list-page">
    <a-result v-if="!canQuery" status="403" title="暂无数据表访问权限" />
    <template v-else>
      <a-alert v-if="error" type="error" show-icon :message="error" class="notice" closable @close="error = ''" />
      <OsTablePage
        title="数据表目录"
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 'max-content', y: '100%' }"
        show-column-settings
        column-settings-key="nocode-table-list"
        resizable
        show-advanced-search
        :row-key="(row: DC.TableRow) => row.schemaName + '.' + row.tableName"
        @change="page"
        @search="search"
      >
        <template #search="{ triggerSearch }">
          <a-form layout="inline" :model="query" @finish="triggerSearch">
            <a-form-item label="Schema">
              <a-select
                v-model:value="query.schema"
                aria-label="查询 Schema"
                class="nocode-filter-select"
                :options="schemas.map(value => ({ value, label: value }))"
              />
            </a-form-item>
            <a-form-item label="表名">
              <a-input
                v-model:value="query.name"
                aria-label="查询表名"
                placeholder="请输入表名"
                class="nocode-filter-input"
                allow-clear
                @press-enter.prevent="triggerSearch"
              />
            </a-form-item>
            <a-form-item label="管理状态">
              <a-select
                v-model:value="query.management"
                aria-label="查询管理状态"
                placeholder="全部"
                allow-clear
                class="nocode-filter-select"
                :options="
                  [
                    NC.TableManagement.UNMANAGED,
                    NC.PublishState.PENDING,
                    NC.ObjectSource.GENERATED,
                    NC.ObjectSource.ADOPTED,
                    NC.ObjectStatus.DISABLED
                  ].map(value => ({ value, label: label(value) }))
                "
              />
            </a-form-item>
            <a-form-item>
              <a-space>
                <a-button type="primary" @click="triggerSearch">
                  <SearchOutlined />
                  查询
                </a-button>
                <a-button @click="reset">
                  <ReloadOutlined />
                  重置
                </a-button>
              </a-space>
            </a-form-item>
          </a-form>
        </template>
        <template #advancedSearch>
          <a-form layout="inline" :model="query" @finish="search">
            <a-form-item label="表角色">
              <a-select
                v-model:value="query.role"
                aria-label="查询表角色"
                placeholder="全部"
                allow-clear
                class="nocode-filter-select"
                :options="
                  [NC.TableRole.MAIN, NC.TableRole.DETAIL, NC.TableRole.RELATION, NC.TableManagement.UNMANAGED].map(
                    value => ({ value, label: label(value) })
                  )
                "
              />
            </a-form-item>
            <a-form-item label="结构状态">
              <a-select
                v-model:value="query.structureState"
                aria-label="查询结构状态"
                placeholder="全部"
                allow-clear
                class="nocode-filter-select"
                :options="
                  [
                    NC.StructureState.MATCHED,
                    NC.StructureState.DRIFTED,
                    NC.PublishState.PENDING,
                    NC.TableManagement.UNMANAGED
                  ].map(value => ({ value, label: label(value) }))
                "
              />
            </a-form-item>
            <a-form-item label="对象 ID">
              <a-input
                v-model:value="query.objectId"
                aria-label="查询对象 ID"
                placeholder="请输入对象 ID"
                class="nocode-filter-select"
                allow-clear
                @press-enter.prevent="search"
              />
            </a-form-item>
            <a-form-item v-if="platform.hasPermission('nocode:table:system')">
              <a-checkbox v-model:checked="query.includeSystem">显示系统表</a-checkbox>
            </a-form-item>
          </a-form>
        </template>
        <template #actions>
          <a-button @click="load">
            <ReloadOutlined />
            刷新目录
          </a-button>
        </template>
        <template #bodyCell="{ column, record }">
          <a v-if="column.dataIndex === 'tableName'" @click="showDetail(record)">{{ record.tableName }}</a>
          <a-tag v-if="column.key === 'management'">{{ record.system ? '系统表' : label(record.management) }}</a-tag>
          <template v-if="column.key === 'role'">{{ label(record.role) }}</template>
          <template v-if="column.key === 'bytes'">{{ formatBytes(record.totalBytes) }}</template>
          <a-tag
            v-if="column.key === 'structure'"
            :color="
              record.structureState === NC.StructureState.DRIFTED
                ? 'orange'
                : record.structureState === NC.StructureState.MATCHED
                  ? 'green'
                  : 'default'
            "
          >
            {{ label(record.structureState) }}
          </a-tag>
          <template v-if="column.key === 'actions'">
            <div class="nocode-table-actions">
              <a-button type="link" @click="showDetail(record)">
                <EyeOutlined />
                结构
              </a-button>
              <a-button
                v-if="record.objectId && platform.hasPermission('nocode:object:query')"
                type="link"
                @click="showObject(record.objectId)"
              >
                <EditOutlined />
                对象设计
              </a-button>
              <a-button v-if="!record.objectId && !record.system && canAdopt" type="link" @click="showAdoption(record)">
                <ImportOutlined />
                纳管
              </a-button>
            </div>
          </template>
        </template>
      </OsTablePage>
      <p class="nocode-list-footnote">记录数来自数据库统计估算；数据预览另行授权并按字段分类脱敏。</p>
    </template>
    <OsModalForm
      :open="detailOpen"
      :title="detail?.table.tableName || '数据表详情'"
      :width="1200"
      :show-footer="false"
      @cancel="closeDetail"
      :display-mode="modalModes[0]"
      @display-mode-change="modalModes[0] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <a-spin :spinning="detailLoading">
          <template v-if="detail">
            <a-descriptions :column="3" size="small" bordered class="notice">
              <a-descriptions-item label="Schema">{{ detail.table.schemaName }}</a-descriptions-item>
              <a-descriptions-item label="管理状态">{{ label(detail.table.management) }}</a-descriptions-item>
              <a-descriptions-item label="对象">{{ detail.table.objectName || '未关联' }}</a-descriptions-item>
              <a-descriptions-item label="估算行数">{{ detail.table.estimatedRows }}</a-descriptions-item>
              <a-descriptions-item label="表 / 索引大小">
                {{ formatBytes(detail.structure.statistics.tableBytes) }} /
                {{ formatBytes(detail.structure.statistics.indexBytes) }}
              </a-descriptions-item>
              <a-descriptions-item label="核验时间">
                {{ detail.table.verifiedAt?.replace('T', ' ').slice(0, 19) || '尚无发布基线' }}
              </a-descriptions-item>
            </a-descriptions>
            <a-alert
              v-for="check in detail.checks"
              :key="check.code + check.message"
              :type="check.blocking ? 'warning' : 'info'"
              :message="check.message"
              class="notice"
            />
            <a-tabs v-model:active-key="detailTab">
              <a-tab-pane key="columns" tab="列与字段映射">
                <OsTablePage
                  title="列与字段映射"
                  class="nocode-embedded-table"
                  show-column-settings
                  column-settings-key="nocode-table-columns"
                  resizable
                  :data-source="detail.structure.columns"
                  :pagination="false"
                  row-key="name"
                  :scroll="{ x: 'max-content', y: 420 }"
                  :columns="[
                    { title: '列名', key: 'name', dataIndex: 'name', width: 150, ellipsis: true },
                    { title: '类型', key: 'nativeType', dataIndex: 'nativeType', width: 170, ellipsis: true },
                    { title: '可空', key: 'nullable', width: 65 },
                    { title: '主键', key: 'primary', width: 65 },
                    {
                      title: '默认值',
                      key: 'defaultExpression',
                      dataIndex: 'defaultExpression',
                      width: 190,
                      ellipsis: true
                    },
                    { title: '对象字段 ID', key: 'field', width: 125 },
                    { title: '说明', key: 'comment', dataIndex: 'comment', width: 170, ellipsis: true }
                  ]"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'nullable'">{{ record.nullable ? '是' : '否' }}</template>
                    <template v-if="column.key === 'primary'">{{ record.primaryKeyPosition ? '是' : '' }}</template>
                    <template v-if="column.key === 'field'">{{ detail.fieldMapping[record.name] || '—' }}</template>
                  </template>
                </OsTablePage>
              </a-tab-pane>
              <a-tab-pane key="constraints" tab="约束 / 外键">
                <a-list :data-source="detail.structure.constraints">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <div>
                        <strong>{{ item.name }}</strong>
                        <pre class="sql-definition">{{ item.definition }}</pre>
                      </div>
                    </a-list-item>
                  </template>
                </a-list>
              </a-tab-pane>
              <a-tab-pane key="indexes" tab="索引">
                <a-list :data-source="detail.structure.indexes">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <div>
                        <strong>{{ item.name }}</strong>
                        <a-tag v-if="item.unique">唯一</a-tag>
                        <pre class="sql-definition">{{ item.definition }}</pre>
                      </div>
                    </a-list-item>
                  </template>
                </a-list>
              </a-tab-pane>
              <a-tab-pane key="triggers" tab="触发器 / 安全">
                <a-alert
                  type="info"
                  :message="
                    '数据库行安全：' +
                    (detail.structure.statistics.rowSecurity ? '启用' : '未启用') +
                    '；当前账号写权限：' +
                    (detail.structure.statistics.canWrite ? '有' : '无')
                  "
                  class="notice"
                />
                <a-list :data-source="detail.structure.triggers">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <div>
                        <strong>{{ item.name }}</strong>
                        <pre class="sql-definition">{{ item.definition }}</pre>
                      </div>
                    </a-list-item>
                  </template>
                </a-list>
              </a-tab-pane>
              <a-tab-pane
                v-if="!detail.table.system && platform.hasPermission('nocode:table:preview')"
                key="preview"
                tab="只读数据预览"
              >
                <a-alert type="info" show-icon message="预览按字段分类脱敏，访问会记录审计。" class="notice" />
                <a-alert v-if="previewError" type="error" :message="previewError" class="notice" />
                <a-space class="notice">
                  <a-button :loading="previewLoading" @click="readPreview(previewPage)">读取数据</a-button>
                  <a-button :disabled="previewPage === 1" @click="readPreview(previewPage - 1)">上一页</a-button>
                  <span>第 {{ previewPage }} 页</span>
                  <a-button :disabled="!preview?.hasMore" @click="readPreview(previewPage + 1)">下一页</a-button>
                </a-space>
                <a-table
                  v-if="preview"
                  :data-source="preview.rows"
                  :loading="previewLoading"
                  :pagination="false"
                  size="small"
                  :row-key="(_row: unknown, index: number) => index"
                  :scroll="{ x: 'max-content', y: 400 }"
                  :columns="preview.columns.map(name => ({ title: name, dataIndex: name, width: 180 }))"
                >
                  <template #bodyCell="{ text }">
                    <span class="preview-value">{{ displayValue(text) }}</span>
                  </template>
                </a-table>
              </a-tab-pane>
            </a-tabs>
          </template>
        </a-spin>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="adoptionOpen"
      title="已有表纳管"
      :width="820"
      :loading="adoptionLoading"
      :disabled="adopting"
      :show-footer="preflight?.allowed"
      ok-text="创建纳管草稿"
      @ok="adopt"
      @cancel="closeAdoption"
      :display-mode="modalModes[1]"
      @display-mode-change="modalModes[1] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <a-alert v-if="adoptionError" type="error" show-icon :message="adoptionError" class="notice" />
        <a-spin :spinning="adoptionLoading">
          <template v-if="preflight">
            <a-alert
              :type="preflight.readOnly ? 'warning' : 'info'"
              show-icon
              :message="
                preflight.schemaName +
                '.' +
                preflight.tableName +
                ' · ' +
                (preflight.readOnly ? '只读纳管' : '保留原表结构的纳管')
              "
              class="notice"
            />
            <a-alert
              v-for="check in preflight.checks"
              :key="check.code + check.message"
              :type="check.blocking ? 'error' : 'warning'"
              :message="check.message"
              class="notice"
            />

            <a-row :gutter="20">
              <a-col :span="12">
                <a-form-item label="对象名称" required>
                  <a-input v-model:value="adoption.objectName" :maxlength="128" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="对象编码" required>
                  <a-input
                    :value="adoption.objectCode"
                    :maxlength="64"
                    placeholder="例如：公司 → object_gs"
                    @update:value="codeSuggestion.changeCode"
                  />
                </a-form-item>
              </a-col>
            </a-row>

            <a-form-item label="记录标题列" required>
              <a-select
                v-model:value="adoption.titleColumn"
                :options="preflight.titleColumns.map(value => ({ value, label: value }))"
              />
            </a-form-item>

            <p class="muted">纳管只建立对象映射。原主键、表结构和业务数据保留，核对草稿后再发布。</p>
          </template>
        </a-spin>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.muted {
  color: var(--ant-color-text-secondary, #7b8492);
  margin: 0;
}
.notice {
  margin-bottom: 16px;
}
.sql-definition {
  margin: 8px 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
.preview-value {
  display: block;
  max-width: 300px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
