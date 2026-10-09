<script setup lang="ts">
import { managementCategoryLabel } from '@/nocode/management-category'
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DeleteOutlined, LoginOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import OsModalForm from '@/components/os-modal-form/OsModalForm.vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { useResourceCode } from '@/nocode/resource-code'
import type { ApplicationRow } from '@/types/nocode/application'
import { ApplicationStatus } from '@/types/nocode/application'
import { formatDateTime } from '@/utils/format'
import '../management-tables.css'
import CategoryTreePanel from '../components/CategoryTreePanel.vue'
import CategoryInput from '../components/CategoryInput.vue'
import ApplicationDeleteDialog from './components/ApplicationDeleteDialog.vue'
import ApplicationRecycleBin from './components/ApplicationRecycleBin.vue'
const platform = useNocodePlatform(),
  api = platform.applications,
  router = useRouter(),
  route = useRoute()
const canManage = computed(() => platform.hasPermission('nocode:app:manage'))
const recycle = computed(() => route.query.recycle === '1')
const deleting = ref<ApplicationRow>()
function showRecycle(value: boolean) {
  void router.push({ path: route.path, query: { ...route.query, recycle: value ? '1' : undefined } })
}
async function deleted() {
  deleting.value = undefined
  await load()
}
const loading = ref(false),
  error = ref(''),
  rows = ref<ApplicationRow[]>([]),
  total = ref(0)
const categories = ref<string[]>([])
const query = reactive({ pageNo: 1, pageSize: 10, search: '', category: undefined as string | undefined })
const createOpen = ref(false),
  saving = ref(false),
  createError = ref('')
const form = reactive({ name: '', code: '', description: '', category: '' })
const codeSuggestion = useResourceCode({
  name: () => form.name,
  kind: () => 'APP',
  setCode: code => {
    form.code = code
  }
})
const columns = [
  { title: '应用名称', key: 'name', dataIndex: 'name', width: 220, ellipsis: true },
  { title: '应用分类', key: 'category', dataIndex: 'category', width: 160, ellipsis: true },
  { title: '应用编码', key: 'code', dataIndex: 'code', width: 180, ellipsis: true },
  { title: '运行状态', key: 'status', width: 110 },
  { title: '发布版本', key: 'publishedVersion', width: 110 },
  { title: '更新时间', key: 'updateTime', dataIndex: 'updateTime', width: 180 },
  { title: '操作', key: 'action', width: 220, fixed: 'right' as const }
]
const pagination = computed(() => ({
  current: query.pageNo,
  pageSize: query.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`,
  pageSizeOptions: ['10', '20', '50', '100']
}))
let requestNumber = 0
async function load() {
  const number = ++requestNumber
  loading.value = true
  error.value = ''
  try {
    const [data, categoryOptions] = await Promise.all([api.page({ ...query }), api.categories()])
    if (number === requestNumber) {
      const last = Math.max(1, Math.ceil(data.total / query.pageSize))
      if (query.pageNo > last) {
        query.pageNo = last
        return await load()
      }
      categories.value = categoryOptions
      rows.value = data.list
      total.value = data.total
    }
  } catch (e) {
    if (number === requestNumber) error.value = errorMessage(e)
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
function changePage(p: { current?: number; pageSize?: number }) {
  query.pageNo = p.pageSize !== query.pageSize ? 1 : (p.current ?? 1)
  query.pageSize = p.pageSize ?? 10
  void load()
}
function reset() {
  query.search = ''
  query.category = undefined
  search()
}
function openCreate() {
  codeSuggestion.reset()
  Object.assign(form, { name: '', code: '', description: '', category: query.category ?? '' })
  createError.value = ''
  createOpen.value = true
}
function open(app: ApplicationRow) {
  void router.push({ path: '/nocode-app/workspace', query: { id: app.id } })
}
async function create() {
  saving.value = true
  createError.value = ''
  try {
    const result = await api.save({
      id: null,
      expectedRevision: null,
      ...form,
      icon: 'AppstoreOutlined',
      definition: { objects: [], resources: [] }
    })
    createOpen.value = false
    open(result.application)
  } catch (e) {
    createError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
watch(
  recycle,
  value => {
    deleting.value = undefined
    if (!value) void load()
    else {
      requestNumber++
      loading.value = false
    }
  },
  { immediate: true }
)
onScopeDispose(() => requestNumber++)
</script>
<template>
  <ApplicationRecycleBin
    v-if="recycle && canManage"
    @back="showRecycle(false)"
    @edit="id => router.push({ path: '/nocode-app/workspace', query: { id } })"
  />
  <section v-else-if="recycle" class="nocode-list-page">
    <a-result status="403" title="没有应用回收站管理权限">
      <template #extra><a-button @click="showRecycle(false)">返回应用列表</a-button></template>
    </a-result>
  </section>
  <section v-else class="nocode-list-page">
    <a-alert v-if="error" type="error" show-icon :message="error" class="notice" />
    <div class="nocode-category-layout">
      <CategoryTreePanel
        title="应用分类"
        :categories="categories"
        :model-value="query.category"
        :loading="loading"
        @update:model-value="selectCategory"
      />
      <div class="nocode-list-page nocode-category-content">
        <OsTablePage
          title="应用列表"
          :columns="columns"
          :data-source="rows"
          :loading="loading"
          :pagination="pagination"
          :scroll="{ x: 'max-content', y: '100%' }"
          show-column-settings
          column-settings-key="nocode-application-list"
          resizable
          row-key="id"
          @change="changePage"
          @search="search"
        >
          <template #search="{ triggerSearch }">
            <a-form layout="inline" :model="query" @finish="triggerSearch">
              <a-form-item label="应用">
                <a-input
                  v-model:value="query.search"
                  aria-label="搜索应用"
                  placeholder="应用名称或编码"
                  class="nocode-filter-input"
                  allow-clear
                  @press-enter.prevent="triggerSearch"
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
          <template #actions>
            <a-button v-if="canManage" @click="showRecycle(true)">
              <DeleteOutlined />
              回收站
            </a-button>
            <a-button @click="load">
              <ReloadOutlined />
              刷新
            </a-button>
            <a-button v-if="platform.hasPermission('nocode:app:create')" type="primary" @click="openCreate">
              <PlusOutlined />
              新建应用
            </a-button>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'category'">{{ managementCategoryLabel(record.category) }}</template>
            <template v-if="column.key === 'name'">
              <a-tooltip :title="record.description || record.name">
                <a @click="open(record)">{{ record.name }}</a>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="record.status === ApplicationStatus.ACTIVE ? 'green' : 'default'">
                {{ record.status === ApplicationStatus.ACTIVE ? '已启用' : '已停用' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'publishedVersion'">
              {{ record.publishedVersion ? 'V' + record.publishedVersion : '尚未发布' }}
            </template>
            <template v-else-if="column.key === 'updateTime'">{{ formatDateTime(record.updateTime) }}</template>
            <template v-else-if="column.key === 'action'">
              <div class="nocode-table-actions">
                <a-button type="link" @click="open(record)">
                  <LoginOutlined />
                  进入应用
                </a-button>
                <a-button v-if="canManage" type="link" danger @click="deleting = record">
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
      :open="createOpen"
      title="新建应用"
      :width="620"
      :loading="saving"
      layout="vertical"
      :label-col="{ span: 24 }"
      :wrapper-col="{ span: 24 }"
      @ok="create"
      @cancel="createOpen = false"
    >
      <template #formItems>
        <a-alert v-if="createError" type="error" :message="createError" show-icon class="notice" />
        <a-form-item label="应用名称" required>
          <a-input v-model:value="form.name" aria-label="应用名称" :maxlength="160" />
        </a-form-item>
        <a-form-item label="应用编码" required extra="按名称自动生成，可手动修改；创建后保持不变">
          <a-input
            :value="form.code"
            @update:value="codeSuggestion.changeCode"
            aria-label="应用编码"
            placeholder="例如：公司 → app_gs"
            :maxlength="64"
          />
        </a-form-item>
        <a-form-item label="应用分类">
          <CategoryInput v-model="form.category" label="应用分类" :load-categories="api.categories" />
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea v-model:value="form.description" aria-label="应用说明" :maxlength="2000" :rows="3" />
        </a-form-item>
      </template>
    </OsModalForm>
    <ApplicationDeleteDialog :application="deleting" @cancel="deleting = undefined" @deleted="deleted" />
  </section>
</template>
<style scoped>
.notice {
  margin-bottom: 12px;
}
</style>
