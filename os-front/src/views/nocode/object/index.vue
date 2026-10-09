<script setup lang="ts">
import { managementCategoryLabel } from '@/nocode/management-category'
import type { DisplayMode } from '@/components/os-modal-form/types'
import { DEFAULT_PAGE_SIZE } from '@/constants'

import * as NC from '@/types/nocode/enums'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  ImportOutlined,
  EditOutlined,
  CopyOutlined,
  CheckCircleOutlined,
  StopOutlined,
  DeleteOutlined
} from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage, label } from '@/nocode/data-center'
import { resourceCode, useResourceCode, suggestedTableName } from '@/nocode/resource-code'
import type * as DC from '@/types/nocode/data-center'
import { formatDateTime } from '@/utils/format'
import '../management-tables.css'
import CategoryTreePanel from '../components/CategoryTreePanel.vue'
import CategoryInput from '../components/CategoryInput.vue'
import ObjectOperationImpact from '../components/ObjectOperationImpact.vue'

const platform = useNocodePlatform(),
  api = platform.dataCenter,
  router = useRouter()
const rows = ref<DC.ObjectRow[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref('')
const categories = ref<string[]>([])
const query = reactive<DC.ObjectQuery>({
  pageNo: 1,
  pageSize: 10,
  name: '',
  code: '',
  source: undefined,
  status: undefined,
  ownerId: '',
  category: undefined
})
const ownerOptions = ref<{ label: string; value: string }[]>([])
async function loadOwners() {
  try {
    ownerOptions.value = await platform.directory.users()
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}

const canQuery = computed(() => platform.hasPermission('nocode:object:query'))
const canCreate = computed(() => canQuery.value && platform.hasPermission('nocode:object:create'))
const canManage = computed(() => platform.hasPermission('nocode:object:manage'))
const pagination = computed(() => ({
  current: query.pageNo,
  pageSize: query.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`,
  pageSizeOptions: ['10', '20', '50', '100']
}))
const columns = [
  { title: '对象名称', key: 'objectName', dataIndex: 'objectName', width: 180, ellipsis: true },
  { title: '数据对象分类', key: 'category', dataIndex: 'category', width: 160, ellipsis: true },
  { title: '对象编码', key: 'objectCode', dataIndex: 'objectCode', width: 185, ellipsis: true },
  { title: '主表', key: 'tableName', dataIndex: 'tableName', width: 220, ellipsis: true },
  { title: '来源', key: 'source', width: 130 },
  { title: '字段 / 明细 / 关系', key: 'members', width: 155 },
  { title: '状态 / 发布版本', key: 'status', width: 150 },
  { title: '更新时间', key: 'updatedAt', width: 175 },
  { title: '操作', key: 'actions', fixed: 'right' as const, width: 330 }
]
let requestNumber = 0
async function load() {
  if (!canQuery.value) return
  const number = ++requestNumber
  loading.value = true
  error.value = ''
  try {
    const [result, categoryOptions] = await Promise.all([api.objects({ ...query }), api.categories()])
    if (number === requestNumber) {
      categories.value = categoryOptions
      rows.value = result.list
      total.value = result.total
    }
  } catch (cause) {
    if (number === requestNumber) error.value = errorMessage(cause)
  } finally {
    if (number === requestNumber) loading.value = false
  }
}
function selectCategory(category: string | undefined) {
  query.category = category
  search()
}
function search() {
  query.pageNo = 1
  void load()
}
function reset() {
  Object.assign(query, {
    pageNo: 1,
    name: '',
    code: '',
    source: undefined,
    status: undefined,
    ownerId: '',
    category: undefined
  })
  void load()
}
function changePage(page: { current?: number; pageSize?: number }) {
  query.pageNo = page.pageSize !== query.pageSize ? 1 : (page.current ?? 1)
  query.pageSize = page.pageSize ?? 10
  void load()
}
function open(id?: string) {
  void router.push({ path: '/nocode/object/editor', query: id ? { id } : { category: query.category } })
}

const actionOpen = ref(false),
  action = ref(''),
  selected = ref<DC.ObjectRow>(),
  actionError = ref(''),
  acting = ref(false),
  reason = ref('')
const copied = reactive({ objectCode: '', objectName: '', tableName: '' })
const operationResult = ref<DC.ObjectOperationPreview>()
const operationRequest = computed<DC.ObjectOperationPreviewRequest | null>(() => {
  if (!actionOpen.value || !selected.value || action.value === 'copy') return null
  return {
    objectId: selected.value.id,
    expectedLockVersion: selected.value.lockVersion,
    operation: action.value as 'enable' | 'disable' | 'delete'
  }
})
function openImpact(route: string) {
  window.open(route, '_blank', 'noopener')
}
const copyCodeSuggestion = useResourceCode({
  name: () => copied.objectName,
  kind: () => 'OBJECT',
  setCode: code => {
    copied.tableName = suggestedTableName(code, copied.objectCode, copied.tableName)
    copied.objectCode = code
  }
})
function showAction(row: DC.ObjectRow, value: string) {
  selected.value = row
  action.value = value
  actionError.value = ''
  reason.value = ''
  operationResult.value = undefined
  copyCodeSuggestion.reset()
  const copyName = row.objectName + '（副本）'
  const copyCode = resourceCode(copyName, 'OBJECT')
  Object.assign(copied, { objectName: copyName, objectCode: copyCode, tableName: suggestedTableName(copyCode) })
  actionOpen.value = true
}
async function applyAction() {
  if (!selected.value) return
  if (action.value !== 'copy' && !operationResult.value?.allowed) return
  actionError.value = ''
  if (action.value !== 'copy' && !reason.value.trim()) {
    actionError.value = '请填写操作原因'
    return
  }
  acting.value = true
  try {
    if (action.value === 'copy') {
      const d = await api.copy({ id: selected.value.id, ...copied })
      actionOpen.value = false
      open(d.draft.id)
    } else {
      await api.lifecycle(action.value, {
        id: selected.value.id,
        expectedLockVersion: selected.value.lockVersion,
        reason: reason.value
      })
      actionOpen.value = false
      await load()
      message.success('对象状态已更新')
    }
  } catch (cause) {
    actionError.value = errorMessage(cause)
  } finally {
    acting.value = false
  }
}
const importOpen = ref(false),
  importing = ref(false),
  importError = ref(''),
  imported = ref<DC.ImportPreview>()
const importForm = reactive({ objectCode: '', objectName: '', tableName: '', titleColumn: '', category: '' })
const importCodeSuggestion = useResourceCode({
  name: () => importForm.objectName,
  kind: () => 'OBJECT',
  setCode: changeImportCode
})
function showImport() {
  importCodeSuggestion.reset()
  importOpen.value = true
  imported.value = undefined
  importError.value = ''
  Object.assign(importForm, {
    objectCode: '',
    objectName: '',
    tableName: '',
    titleColumn: '',
    category: query.category ?? ''
  })
}
async function downloadTemplate() {
  try {
    const blob = await api.template()
    const url = URL.createObjectURL(blob),
      link = document.createElement('a')
    link.href = url
    link.download = '数据对象结构模板.xlsx'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
  } catch (cause) {
    importError.value = errorMessage(cause)
  }
}
async function previewFile(file: File) {
  importing.value = true
  importError.value = ''
  try {
    imported.value = await api.importPreview(file)
    importForm.titleColumn = imported.value.columns.find(c => c.type === NC.FieldType.TEXT)?.code ?? ''
  } catch (cause) {
    importError.value = errorMessage(cause)
    imported.value = undefined
  } finally {
    importing.value = false
  }
  return false
}
async function createImported() {
  if (!imported.value || imported.value.errors.length) {
    importError.value = '请先上传并通过结构预检'
    return
  }
  importing.value = true
  try {
    const d = await api.importDesign({ ...importForm, columns: imported.value.columns })
    importOpen.value = false
    open(d.draft.id)
    message.success('结构已导入对象草稿')
  } catch (cause) {
    importError.value = errorMessage(cause)
  } finally {
    importing.value = false
  }
}
function changeImportCode(code: string) {
  const old = importForm.objectCode
  importForm.objectCode = code
  importForm.tableName = suggestedTableName(code, old, importForm.tableName)
}
onMounted(load)
// 每个弹窗独立保留底座的弹窗/抽屉/全屏展示状态。
const modalModes = ref<DisplayMode[]>(Array(2).fill('modal'))
</script>

<template>
  <section class="nocode-list-page">
    <a-result v-if="!canQuery" status="403" title="暂无数据对象访问权限" />
    <div v-else class="nocode-category-layout">
      <CategoryTreePanel
        title="数据对象分类"
        :categories="categories"
        :model-value="query.category"
        :loading="loading"
        @update:model-value="selectCategory"
      />
      <div class="nocode-list-page nocode-category-content">
        <a-alert v-if="error" type="error" show-icon :message="error" class="notice">
          <template #action><a-button size="small" @click="load">重试</a-button></template>
        </a-alert>
        <OsTablePage
          title="对象列表"
          :columns="columns"
          :data-source="rows"
          :loading="loading"
          :pagination="pagination"
          :scroll="{ x: 'max-content', y: '100%' }"
          show-column-settings
          column-settings-key="nocode-object-list"
          resizable
          show-advanced-search
          row-key="id"
          @change="changePage"
          @search="search"
        >
          <template #search="{ triggerSearch }">
            <a-form layout="inline" :model="query" @finish="triggerSearch">
              <a-form-item label="对象名称">
                <a-input
                  v-model:value="query.name"
                  aria-label="查询对象名称"
                  placeholder="请输入对象名称"
                  class="nocode-filter-input"
                  allow-clear
                  @press-enter.prevent="triggerSearch"
                />
              </a-form-item>
              <a-form-item label="对象编码">
                <a-input
                  v-model:value="query.code"
                  aria-label="查询对象编码"
                  placeholder="请输入对象编码"
                  class="nocode-filter-input"
                  allow-clear
                  @press-enter.prevent="triggerSearch"
                />
              </a-form-item>
              <a-form-item label="状态">
                <a-select
                  v-model:value="query.status"
                  aria-label="查询对象状态"
                  placeholder="全部"
                  allow-clear
                  class="nocode-filter-select"
                  :options="
                    [NC.ObjectStatus.DRAFT, NC.ObjectStatus.ACTIVE, NC.ObjectStatus.DISABLED].map(value => ({
                      value,
                      label: label(value)
                    }))
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
              <a-form-item label="来源">
                <a-select
                  v-model:value="query.source"
                  aria-label="查询对象来源"
                  placeholder="全部"
                  allow-clear
                  class="nocode-filter-select"
                  :options="
                    [NC.ObjectSource.GENERATED, NC.ObjectSource.ADOPTED].map(value => ({ value, label: label(value) }))
                  "
                />
              </a-form-item>
              <a-form-item label="负责人">
                <a-select
                  v-model:value="query.ownerId"
                  aria-label="查询对象负责人"
                  placeholder="全部"
                  allow-clear
                  show-search
                  option-filter-prop="label"
                  :options="ownerOptions"
                  class="nocode-filter-input"
                  @focus="loadOwners"
                />
              </a-form-item>
            </a-form>
          </template>
          <template #actions>
            <a-space>
              <a-button @click="load">
                <ReloadOutlined />
                刷新
              </a-button>
              <a-button v-if="canCreate && platform.hasPermission('nocode:object:import')" @click="showImport">
                <ImportOutlined />
                导入结构
              </a-button>
              <a-button v-if="canCreate" type="primary" @click="open()">
                <PlusOutlined />
                新建对象
              </a-button>
            </a-space>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'category'">{{ managementCategoryLabel(record.category) }}</template>
            <a v-if="column.dataIndex === 'objectName'" @click="open(record.id)">{{ record.objectName }}</a>
            <template v-if="column.key === 'source'">{{ label(record.source) }}</template>
            <template v-if="column.key === 'members'">
              {{ record.fieldCount }} / {{ record.detailCount }} / {{ record.relationCount }}
            </template>
            <template v-if="column.key === 'status'">
              <a-tag
                :color="
                  record.status === NC.ObjectStatus.ACTIVE
                    ? 'green'
                    : record.status === NC.ObjectStatus.DISABLED
                      ? 'default'
                      : 'blue'
                "
              >
                {{ label(record.status) }}
              </a-tag>
              <span>{{ record.publishedVersion ? 'V' + record.publishedVersion : '未发布' }}</span>
            </template>
            <template v-if="column.key === 'updatedAt'">{{ formatDateTime(record.updatedAt) }}</template>
            <template v-if="column.key === 'actions'">
              <div class="nocode-table-actions">
                <a-button type="link" @click="open(record.id)">
                  <EditOutlined />
                  设计
                </a-button>
                <a-button v-if="canCreate" type="link" @click="showAction(record, 'copy')">
                  <CopyOutlined />
                  复制
                </a-button>
                <a-button
                  v-if="canManage"
                  type="link"
                  :danger="record.status !== NC.ObjectStatus.DISABLED"
                  @click="showAction(record, record.status === NC.ObjectStatus.DISABLED ? 'enable' : 'disable')"
                >
                  <CheckCircleOutlined v-if="record.status === NC.ObjectStatus.DISABLED" />
                  <StopOutlined v-else />
                  {{ record.status === NC.ObjectStatus.DISABLED ? '启用' : '停用' }}
                </a-button>
                <a-button v-if="canManage" type="link" danger @click="showAction(record, 'delete')">
                  <DeleteOutlined />
                  删除
                </a-button>
              </div>
            </template>
          </template>
        </OsTablePage>
      </div>
    </div>
    <OsModalForm
      :open="actionOpen"
      :title="
        action === 'copy'
          ? '复制数据对象'
          : action === 'delete'
            ? '删除数据对象'
            : action === 'enable'
              ? '启用数据对象'
              : '停用数据对象'
      "
      :loading="acting"
      :width="820"
      @ok="applyAction"
      @cancel="actionOpen = false"
      :display-mode="modalModes[0]"
      @display-mode-change="modalModes[0] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <a-alert v-if="actionError" type="error" show-icon :message="actionError" class="notice" />

        <template v-if="action === 'copy'">
          <a-alert type="info" show-icon message="复制设计结构并分配新的字段身份，不复制业务记录。" class="notice" />
          <a-form-item label="对象名称" required>
            <a-input v-model:value="copied.objectName" :maxlength="128" />
          </a-form-item>
          <a-form-item label="对象编码" required>
            <a-input
              :value="copied.objectCode"
              :maxlength="64"
              placeholder="例如：公司 → object_gs"
              @update:value="copyCodeSuggestion.changeCode"
            />
          </a-form-item>
          <a-form-item label="主表名称" required>
            <a-input v-model:value="copied.tableName" :maxlength="63" />
          </a-form-item>
        </template>
        <template v-else>
          <p>对象：{{ selected?.objectName }}</p>
          <ObjectOperationImpact
            v-if="actionOpen"
            :request="operationRequest"
            @checked="operationResult = $event"
            @navigate="openImpact"
          />

          <a-form-item label="操作原因" required>
            <a-textarea v-model:value="reason" :rows="3" :maxlength="1000" />
          </a-form-item>
        </template>
      </template>
      <template #footer>
        <a-button :disabled="acting" @click="actionOpen = false">取消</a-button>
        <a-button
          type="primary"
          :loading="acting"
          :danger="action === 'delete'"
          :disabled="action !== 'copy' && !operationResult?.allowed"
          @click="applyAction"
        >
          {{
            action === 'copy'
              ? '复制对象'
              : action === 'delete'
                ? '确认删除对象'
                : action === 'enable'
                  ? '确认启用'
                  : '确认停用'
          }}
        </a-button>
      </template>
    </OsModalForm>
    <OsModalForm
      :open="importOpen"
      title="导入对象结构"
      :width="920"
      :loading="importing"
      ok-text="创建对象草稿"
      @ok="createImported"
      @cancel="importOpen = false"
      :display-mode="modalModes[1]"
      @display-mode-change="modalModes[1] = $event"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
    >
      <template #formItems>
        <a-alert v-if="importError" type="error" show-icon :message="importError" class="notice" />
        <a-space class="notice">
          <a-button @click="downloadTemplate">下载 Excel 模板</a-button>
          <a-upload accept=".xlsx,.xls,.csv" :before-upload="previewFile" :show-upload-list="false">
            <a-button :loading="importing">上传 Excel / CSV</a-button>
          </a-upload>
          <span class="muted">最多 200 个字段，2 MB</span>
        </a-space>
        <template v-if="imported">
          <a-alert
            v-for="item in imported.errors"
            :key="item.row"
            type="error"
            :message="`第 ${item.row} 行：${item.message}`"
            class="notice"
          />
          <a-table
            :data-source="imported.columns"
            :pagination="{ pageSize: DEFAULT_PAGE_SIZE }"
            row-key="code"
            size="small"
            :columns="[
              { title: '字段名称', dataIndex: 'name' },
              { title: '编码', dataIndex: 'code' },
              { title: '类型', dataIndex: 'type' },
              { title: '长度', dataIndex: 'length' }
            ]"
          />

          <a-form-item label="数据对象分类">
            <CategoryInput v-model="importForm.category" label="数据对象分类" :load-categories="api.categories" />
          </a-form-item>
          <a-row :gutter="20">
            <a-col :span="12">
              <a-form-item label="对象名称" required>
                <a-input v-model:value="importForm.objectName" :maxlength="128" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="对象编码" required>
                <a-input
                  :value="importForm.objectCode"
                  :maxlength="64"
                  placeholder="例如：公司 → object_gs"
                  @update:value="importCodeSuggestion.changeCode"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="20">
            <a-col :span="16">
              <a-form-item label="主表名称" required>
                <a-input v-model:value="importForm.tableName" :maxlength="63" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="记录标题" required>
                <a-select
                  v-model:value="importForm.titleColumn"
                  :options="
                    imported.columns
                      .filter(c => c.type === NC.FieldType.TEXT)
                      .map(c => ({ label: c.name, value: c.code }))
                  "
                />
              </a-form-item>
            </a-col>
          </a-row>
        </template>
      </template>
    </OsModalForm>
  </section>
</template>

<style scoped>
.muted {
  margin: 0;
  color: var(--ant-color-text-secondary, #7b8492);
}
.notice {
  margin-bottom: 16px;
}
</style>
