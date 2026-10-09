<script lang="ts" setup>
import { useCompactViewport } from '@/composables/useCompactViewport'
import type { BpmModelApi } from '@/api/bpm/model'
import {
  cleanModel,
  deleteModel,
  deployModel,
  getModelList,
  updateModelSortBatch,
  updateModelState
} from '@/api/bpm/model'
import { deleteCategory, getCategorySimpleList, updateCategorySortBatch } from '@/api/bpm/category'
import {
  AlignLeftOutlined,
  ApartmentOutlined,
  BarChartOutlined,
  ClearOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  HistoryOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SettingOutlined,
  UpOutlined
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { computed, onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import { ALL_MODELS, UNCLASSIFIED_MODELS, useModelCatalog } from './model-catalog'

defineOptions({ name: 'BpmModel' })
const compactViewport = useCompactViewport()
const mobileFiltersOpen = ref(false)

const router = useRouter()
const userStore = useUserStore()
const catalog = useModelCatalog({ models: () => getModelList(), categories: getCategorySimpleList })
const {
  models,
  categories,
  selectedKey,
  queryName,
  appliedName,
  current,
  pageSize,
  loading,
  error,
  sortIds,
  grouped,
  selectedCategory,
  categoryModels,
  tableData,
  pagination,
  loadData,
  selectCategory,
  search,
  reset,
  changePage
} = catalog
const categoryKeyword = ref('')
const categorySorting = ref(false)
const saveSortLoading = ref(false)
const busyModelId = ref<string | number>()
let originalCategories: typeof categories.value = []
const sorting = computed(() => categorySorting.value || !!sortIds.value)
const visibleCategories = computed(() =>
  grouped.value.groups.filter(
    category =>
      categorySorting.value ||
      category.name.toLocaleLowerCase().includes(categoryKeyword.value.trim().toLocaleLowerCase())
  )
)
const tableTitle = computed(() =>
  selectedCategory.value?.name
    ? `${selectedCategory.value.name} · 流程模型`
    : selectedKey.value === UNCLASSIFIED_MODELS
      ? '未归类 · 流程模型'
      : '流程模型'
)
const canOperateModel = (row: BpmModelApi.Model) =>
  !row.managerUserIds?.length || row.managerUserIds.map(String).includes(String(userStore.userInfo?.id || ''))
const canSortModels = computed(
  () =>
    !!selectedCategory.value &&
    categoryModels.value.length > 1 &&
    !appliedName.value &&
    categoryModels.value.every(canOperateModel)
)
const columns = computed(() => [
  { title: '流程名称', key: 'name', dataIndex: 'name', width: 260, ellipsis: true },
  { title: '流程分类', key: 'categoryName', dataIndex: 'categoryName', width: 130, ellipsis: true },
  { title: '可见范围', key: 'visible', width: 160, ellipsis: true },
  { title: '流程类型', key: 'type', width: 155 },
  { title: '发起表单', key: 'formInfo', width: 170, ellipsis: true },
  { title: '最后发布', key: 'deploymentTime', width: 220 },
  {
    title: sortIds.value ? '调整顺序' : '操作',
    key: 'action',
    width: sortIds.value ? 130 : 235,
    fixed: 'right' as const
  }
])

function modelTypeText(type?: number) {
  return type === 10 ? 'BPMN 设计器' : type === 20 ? '简易设计器' : '-'
}
function visibilityText(row: BpmModelApi.Model) {
  if (!row.startUsers?.length && !row.startDepts?.length) return '全部可见'
  return [...(row.startDepts || []).map(item => item.name), ...(row.startUsers || []).map(item => item.nickname)].join(
    '、'
  )
}
function formInfoText(row: BpmModelApi.Model) {
  if (row.formType === 0) return '无需发起表单'
  if (row.formType === 10) return row.formName || '未配置'
  if (row.formType === 20) return row.formName || row.formCustomCreatePath || '未配置'
  return '未配置'
}
const isSuspended = (row: BpmModelApi.Model) => row.processDefinition?.suspensionState === 2
const categoryName = (row: BpmModelApi.Model) =>
  grouped.value.groups.find(group => group.models.some(model => model.id === row.id))?.name || '未归类'
function refresh() {
  if (!sorting.value) return loadData()
}
function handleReset() {
  reset()
  categoryKeyword.value = ''
  return refresh()
}
function modelOperation(type: string, id?: string | number) {
  router.push({ path: '/bpm/model/form', query: { id, type } })
}
function handleDefinitionList(row: BpmModelApi.Model) {
  router.push({ path: '/bpm/model/definition', query: { key: row.key } })
}
function handleReport(row: BpmModelApi.Model) {
  router.push({
    path: '/bpm/instance/report',
    query: { processDefinitionId: row.processDefinition?.id, processDefinitionKey: row.key }
  })
}
function moveCategory(index: number, offset: -1 | 1) {
  const target = index + offset
  if (saveSortLoading.value || target < 0 || target >= categories.value.length) return
  const item = categories.value.splice(index, 1)[0]
  categories.value.splice(target, 0, item)
}
function startCategorySort() {
  if (loading.value || sorting.value) return
  originalCategories = [...categories.value]
  categoryKeyword.value = ''
  categorySorting.value = true
}
function cancelCategorySort() {
  categories.value = [...originalCategories]
  categorySorting.value = false
}
async function submitCategorySort() {
  if (saveSortLoading.value) return
  saveSortLoading.value = true
  try {
    await updateCategorySortBatch(categories.value.map(item => item.id))
    categorySorting.value = false
    message.success('分类排序成功')
    await loadData()
  } catch (reason: any) {
    message.error(reason?.message || '分类排序保存失败，请重试')
  } finally {
    saveSortLoading.value = false
  }
}
function startModelSort() {
  if (loading.value || sorting.value || !canSortModels.value) return
  sortIds.value = categoryModels.value.map(row => row.id)
  current.value = 1
}
function modelSortIndex(id: string | number) {
  return sortIds.value?.findIndex(item => String(item) === String(id)) ?? -1
}
function moveModel(id: string | number, offset: -1 | 1) {
  const list = sortIds.value
  if (!list || saveSortLoading.value) return
  const index = modelSortIndex(id)
  const target = index + offset
  if (index < 0 || target < 0 || target >= list.length) return
  const item = list.splice(index, 1)[0]
  list.splice(target, 0, item)
  current.value = Math.floor(target / pageSize.value) + 1
}
async function submitModelSort() {
  if (!sortIds.value || saveSortLoading.value) return
  saveSortLoading.value = true
  try {
    await updateModelSortBatch([...sortIds.value])
    sortIds.value = null
    message.success('流程排序成功')
    await loadData()
  } catch (reason: any) {
    message.error(reason?.message || '流程排序保存失败，请重试')
  } finally {
    saveSortLoading.value = false
  }
}
async function runModelAction(row: BpmModelApi.Model, action: () => Promise<unknown>, success: string) {
  if (!canOperateModel(row) || sorting.value || busyModelId.value !== undefined) return
  busyModelId.value = row.id
  try {
    await action()
    message.success(success)
    await loadData()
  } catch (reason: any) {
    message.error(reason?.message || '操作失败，请重试')
  } finally {
    busyModelId.value = undefined
  }
}
function handleDeploy(row: BpmModelApi.Model) {
  return runModelAction(row, () => deployModel(row.id), `发布[${row.name}]流程成功`)
}
function handleChangeState(row: BpmModelApi.Model) {
  const state = isSuspended(row) ? 1 : 2
  Modal.confirm({
    title: `确定要${state === 1 ? '启用' : '停用'}流程“${row.name}”吗？`,
    onOk: () => runModelAction(row, () => updateModelState(row.id, state), '流程状态已更新')
  })
}
function confirmRemove(row: BpmModelApi.Model, clean: boolean) {
  Modal.confirm({
    title: `确定要${clean ? '清理' : '删除'}流程“${row.name}”吗？`,
    content: clean ? '将终止该流程所有运行中实例，并删除相关历史与任务数据。' : '删除后该模型将不再显示。',
    okButtonProps: { danger: true },
    onOk: () =>
      runModelAction(
        row,
        () => (clean ? cleanModel(row.id) : deleteModel(row.id)),
        clean ? '流程清理成功' : '流程删除成功'
      )
  })
}
async function handleDeleteCategory() {
  const category = selectedCategory.value
  if (!category || category.models.length || loading.value || sorting.value) return
  try {
    await deleteCategory(category.id)
    message.success('删除分类成功')
    await loadData()
  } catch (reason: any) {
    message.error(reason?.message || '删除分类失败，请重试')
  }
}
let activatedOnce = false
onMounted(loadData)
onActivated(() => {
  if (activatedOnce) refresh()
  activatedOnce = true
})
</script>

<template>
  <div class="bpm-model-page">
    <a-button
      v-if="compactViewport"
      class="mobile-panel-toggle"
      :aria-expanded="mobileFiltersOpen"
      @click="mobileFiltersOpen = !mobileFiltersOpen"
    >
      {{ mobileFiltersOpen ? '收起流程分类' : '筛选流程分类' }}
    </a-button>
    <aside class="category-sidebar" v-show="!compactViewport || mobileFiltersOpen" aria-label="流程分类">
      <div class="sidebar-header">
        <span class="sidebar-title">流程分类</span>
        <a-dropdown v-if="!sorting" :trigger="['click']" placement="bottomRight">
          <a-button type="text" size="small" aria-label="分类设置"><SettingOutlined /></a-button>
          <template #overlay>
            <a-menu>
              <a-menu-item key="manage" @click="router.push('/bpm/category')">
                <ApartmentOutlined />
                分类管理
              </a-menu-item>
              <a-menu-item key="sort" :disabled="loading || categories.length < 2" @click="startCategorySort">
                <AlignLeftOutlined />
                分类排序
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
      <a-input
        v-model:value="categoryKeyword"
        :disabled="sorting"
        allow-clear
        placeholder="搜索流程分类"
        class="category-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <div class="category-list">
        <button
          v-if="!categorySorting"
          class="category-item"
          :class="{ selected: selectedKey === ALL_MODELS }"
          :disabled="sorting || loading"
          :aria-pressed="selectedKey === ALL_MODELS"
          @click="selectCategory(ALL_MODELS)"
        >
          <span>全部流程</span>
          <span class="category-count">{{ models.length }}</span>
        </button>
        <div v-for="(category, index) in visibleCategories" :key="category.key" class="category-row">
          <button
            class="category-item"
            :class="{ selected: selectedKey === category.key }"
            :disabled="sorting || loading"
            :aria-pressed="selectedKey === category.key"
            :title="category.name"
            @click="selectCategory(category.key)"
          >
            <span class="category-name">{{ category.name }}</span>
            <span class="category-count">{{ category.models.length }}</span>
          </button>
          <a-space v-if="categorySorting" :size="2">
            <a-button
              size="small"
              :disabled="saveSortLoading || index === 0"
              :aria-label="`上移分类 ${category.name}`"
              @click="moveCategory(index, -1)"
            >
              <UpOutlined />
            </a-button>
            <a-button
              size="small"
              :disabled="saveSortLoading || index === categories.length - 1"
              :aria-label="`下移分类 ${category.name}`"
              @click="moveCategory(index, 1)"
            >
              <DownOutlined />
            </a-button>
          </a-space>
        </div>
        <button
          v-if="!categorySorting && grouped.unclassified.length"
          class="category-item"
          :class="{ selected: selectedKey === UNCLASSIFIED_MODELS }"
          :disabled="sorting || loading"
          :aria-pressed="selectedKey === UNCLASSIFIED_MODELS"
          @click="selectCategory(UNCLASSIFIED_MODELS)"
        >
          <span>未归类</span>
          <span class="category-count">{{ grouped.unclassified.length }}</span>
        </button>
        <div v-if="!loading && !visibleCategories.length" class="category-empty">
          {{ error ? '分类加载失败，请在右侧重试' : categoryKeyword ? '没有匹配的分类' : '暂无流程分类' }}
        </div>
      </div>
      <div v-if="categorySorting" class="sidebar-footer">
        <a-button :disabled="saveSortLoading" @click="cancelCategorySort">取消</a-button>
        <a-button type="primary" :loading="saveSortLoading" @click="submitCategorySort">保存排序</a-button>
      </div>
      <div v-else-if="selectedCategory" class="sidebar-footer">
        <a-popconfirm
          title="确定要删除该流程分类吗？"
          :disabled="selectedCategory.models.length > 0 || sorting || loading"
          @confirm="handleDeleteCategory"
        >
          <a-button type="link" danger :disabled="selectedCategory.models.length > 0 || sorting || loading">
            <DeleteOutlined />
            删除分类
          </a-button>
        </a-popconfirm>
      </div>
    </aside>
    <div class="model-list-content">
      <OsTablePage
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        :pagination="pagination"
        :title="tableTitle"
        :scroll="{ x: 1490 }"
        row-key="id"
        resizable
        show-column-settings
        column-settings-key="bpm-model-list"
        @change="changePage"
        @search="search"
      >
        <template #search="{ triggerSearch }">
          <a-form layout="inline" class="query-form">
            <a-form-item label="流程名称">
              <a-input
                v-model:value="queryName"
                :disabled="sorting"
                allow-clear
                placeholder="请输入流程名称"
                style="width: 220px"
                @press-enter="triggerSearch"
              />
            </a-form-item>
            <a-form-item>
              <a-space>
                <a-button type="primary" :disabled="sorting || loading" @click="triggerSearch">
                  <SearchOutlined />
                  查询
                </a-button>
                <a-button :disabled="sorting || loading" @click="handleReset">
                  <ReloadOutlined />
                  重置
                </a-button>
              </a-space>
            </a-form-item>
          </a-form>
          <a-alert v-if="error" type="error" show-icon :message="error" class="load-alert">
            <template #action><a-button size="small" :loading="loading" @click="refresh">重试</a-button></template>
          </a-alert>
        </template>
        <template #actions>
          <a-button type="primary" :disabled="sorting" @click="modelOperation('create')">
            <PlusOutlined />
            新建模型
          </a-button>
        </template>
        <template #toolbar>
          <a-space v-if="sortIds">
            <span class="sort-hint">调整当前分类的全部流程顺序</span>
            <a-button :disabled="saveSortLoading" @click="sortIds = null">取消排序</a-button>
            <a-button type="primary" :loading="saveSortLoading" @click="submitModelSort">保存排序</a-button>
          </a-space>
          <template v-else>
            <a-tooltip title="选择分类并清空流程名称后，可调整该分类下的流程顺序">
              <a-button :disabled="sorting || loading || !canSortModels" @click="startModelSort">
                <AlignLeftOutlined />
                流程排序
              </a-button>
            </a-tooltip>
            <a-button :loading="loading" :disabled="sorting" @click="refresh">
              <ReloadOutlined />
              刷新
            </a-button>
          </template>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <div class="model-name-cell">
              <img v-if="record.icon" :src="record.icon" alt="" class="model-icon" />
              <div v-else class="model-avatar">{{ record.name?.slice(0, 2) || '流程' }}</div>
              <div class="model-title-wrap">
                <a-tooltip :title="record.name">
                  <span class="model-name">{{ record.name }}</span>
                </a-tooltip>
                <a-tooltip :title="record.key">
                  <span class="model-key">{{ record.key }}</span>
                </a-tooltip>
              </div>
            </div>
          </template>
          <template v-else-if="column.key === 'categoryName'">
            <a-tooltip :title="categoryName(record)">
              <span class="ellipsis-text">{{ categoryName(record) }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'visible'">
            <a-tooltip :title="visibilityText(record)">
              <span class="ellipsis-text">{{ visibilityText(record) }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'type'">
            <a-tag color="processing">{{ modelTypeText(record.type) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'formInfo'">
            <a-tooltip :title="formInfoText(record)">
              <span class="ellipsis-text">{{ formInfoText(record) }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'deploymentTime'">
            <div class="deployment-cell">
              <span v-if="record.processDefinition">{{ formatDateTime(record.processDefinition.deploymentTime) }}</span>
              <a-space :size="4">
                <a-tag v-if="record.processDefinition">v{{ record.processDefinition.version }}</a-tag>
                <a-tag v-else color="warning">未发布</a-tag>
                <a-tag v-if="isSuspended(record)" color="warning">已停用</a-tag>
              </a-space>
            </div>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space v-if="sortIds">
              <a-button
                size="small"
                :disabled="saveSortLoading || modelSortIndex(record.id) === 0"
                :aria-label="`上移流程 ${record.name}`"
                @click="moveModel(record.id, -1)"
              >
                <UpOutlined />
              </a-button>
              <a-button
                size="small"
                :disabled="saveSortLoading || modelSortIndex(record.id) === sortIds.length - 1"
                :aria-label="`下移流程 ${record.name}`"
                @click="moveModel(record.id, 1)"
              >
                <DownOutlined />
              </a-button>
            </a-space>
            <div v-else class="model-actions">
              <a-button
                type="link"
                :disabled="sorting || !canOperateModel(record) || busyModelId !== undefined"
                @click="modelOperation('update', record.id)"
              >
                <EditOutlined />
                修改
              </a-button>
              <span class="action-sep">|</span>
              <a-popconfirm
                title="确认要发布该流程吗？"
                :disabled="sorting || !canOperateModel(record) || busyModelId !== undefined"
                @confirm="handleDeploy(record)"
              >
                <a-button type="link" :disabled="sorting || !canOperateModel(record) || busyModelId !== undefined">
                  <PlayCircleOutlined />
                  发布
                </a-button>
              </a-popconfirm>
              <span class="action-sep">|</span>
              <a-dropdown :trigger="['click']" placement="bottomRight">
                <a-button type="link" :disabled="sorting || busyModelId !== undefined">
                  <MoreOutlined />
                  更多
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item key="copy" @click="modelOperation('copy', record.id)">
                      <CopyOutlined />
                      复制
                    </a-menu-item>
                    <a-menu-item key="history" @click="handleDefinitionList(record)">
                      <HistoryOutlined />
                      历史
                    </a-menu-item>
                    <a-menu-item key="report" :disabled="!record.processDefinition" @click="handleReport(record)">
                      <BarChartOutlined />
                      报表
                    </a-menu-item>
                    <a-menu-item
                      v-if="record.processDefinition"
                      key="state"
                      :disabled="!canOperateModel(record)"
                      :danger="!isSuspended(record)"
                      @click="handleChangeState(record)"
                    >
                      <PauseCircleOutlined />
                      {{ isSuspended(record) ? '启用' : '停用' }}
                    </a-menu-item>
                    <a-menu-item
                      key="clean"
                      :disabled="!canOperateModel(record)"
                      danger
                      @click="confirmRemove(record, true)"
                    >
                      <ClearOutlined />
                      清理
                    </a-menu-item>
                    <a-menu-item
                      key="delete"
                      :disabled="!canOperateModel(record)"
                      danger
                      @click="confirmRemove(record, false)"
                    >
                      <DeleteOutlined />
                      删除
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </template>
        </template>
      </OsTablePage>
    </div>
  </div>
</template>

<style scoped>
.bpm-model-page {
  height: 100%;
  display: flex;
  gap: 16px;
  min-height: 0;
  min-width: 0;
}
.category-sidebar {
  width: 240px;
  flex: 0 0 240px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--color-bg-container, #fff);
  border-radius: 8px;
  padding: 12px;
}
.sidebar-header,
.category-row,
.sidebar-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.sidebar-header {
  margin-bottom: 10px;
}
.sidebar-title {
  font-weight: 600;
  color: var(--color-text, #111827);
}
.category-search {
  margin-bottom: 10px;
}
.category-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.category-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  width: 100%;
  min-width: 0;
  padding: 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text, #374151);
  text-align: left;
  cursor: pointer;
  font: inherit;
}
.category-item:hover {
  background: var(--color-fill-tertiary, #f5f5f5);
}
.category-item.selected {
  color: var(--color-primary, #4338ca);
  background: var(--color-primary-bg, #eef2ff);
  font-weight: 500;
}
.category-item:disabled {
  cursor: default;
}
.category-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.category-count {
  margin-left: auto;
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
}
.category-empty {
  padding: 18px 8px;
  color: var(--color-text-tertiary, #9ca3af);
  font-size: 13px;
}
.sidebar-footer {
  border-top: 1px solid var(--color-border-secondary, #f0f0f0);
  padding-top: 12px;
  margin-top: 8px;
}
.model-list-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.query-form {
  flex-wrap: nowrap;
  white-space: nowrap;
}
.model-list-content :deep(.os-table-page__search-form) {
  overflow-x: auto;
}
.model-list-content :deep(.os-table-page__search-basic) {
  min-width: 0;
}
.query-form :deep(.ant-form-item) {
  flex: 0 0 auto;
}
.load-alert {
  margin-top: 12px;
}
.model-name-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  text-align: left;
}
.model-avatar,
.model-icon {
  width: 36px;
  height: 36px;
  margin-right: 10px;
  border-radius: 6px;
  flex: 0 0 auto;
}
.model-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: var(--color-primary, #4338ca);
  font-size: 12px;
}
.model-icon {
  object-fit: cover;
}
.model-title-wrap {
  min-width: 0;
}
.model-name,
.model-key,
.ellipsis-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-name {
  font-weight: 500;
}
.model-key {
  margin-top: 2px;
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
}
.deployment-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.model-actions {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}
.model-actions :deep(.ant-btn-link) {
  padding: 0 6px;
  height: auto;
}
.action-sep {
  color: #d1d5db;
  user-select: none;
}
.sort-hint {
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
}
@media (max-width: 960px) {
  .category-sidebar {
    width: 190px;
    flex-basis: 190px;
  }
  .bpm-model-page {
    gap: 12px;
  }
}

@media (max-width: 767px) {
  .bpm-model-page {
    flex-direction: column;
    gap: 10px;
    padding: 0;
    height: auto;
    min-height: 100%;
  }
  .bpm-model-page .category-sidebar {
    width: 100%;
    flex: none;
    max-height: 240px;
    overflow: auto;
    box-sizing: border-box;
  }
  .bpm-model-page .model-list-content {
    width: 100%;
    min-width: 0;
    flex: 1 0 auto;
  }
  .mobile-panel-toggle {
    flex: none;
    min-height: 40px;
    align-self: flex-start;
  }
}
</style>
