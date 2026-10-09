# Component Patterns — API Reference

This document provides the canonical usage patterns for all reusable UI components.

---

## OsTablePage — Universal CRUD Table

**Location**: `src/components/ucp-table-page/OsTablePage.vue`
**Composable**: `src/composables/useOsTablePage.ts`

### When to Use

Any page that has: search form + data table + pagination + CRUD toolbar.

### Props (commonly used)

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `columns` | `TableColumnType[]` | required | Ant Design column definitions |
| `dataSource` | `any[]` | required | Table data |
| `loading` | `boolean` | `false` | Loading state |
| `pagination` | `object \| false` | — | Pagination config (pass the reactive pagination from useOsTablePage) |
| `rowKey` | `string \| function` | `'id'` | Row key |
| `showIndex` | `boolean` | `true` | Show auto-index column |
| `indexWidth` | `number` | `60` | Index column width |
| `size` | `'small' \| 'middle' \| 'large'` | `'middle'` | Table size |
| `title` | `string` | — | Card title |
| `rowSelection` | `object \| boolean` | `false` | Enable row selection |
| `selectedRowKeys` | `(string\|number)[]` | `[]` | Selected keys (bind from useOsTablePage) |
| `showExport` | `boolean` | `false` | Show export button |
| `showImport` | `boolean` | `false` | Show import button |
| `showDownloadTemplate` | `boolean` | `false` | Import button becomes dropdown with template download |
| `showAdvancedSearch` | `boolean` | `false` | Show advanced search |
| `advancedSearchMode` | `'static' \| 'dynamic'` | `'static'` | Static=slot, Dynamic=condition builder popup |
| `dynamicSearchFields` | `DynamicSearchField[]` | `[]` | Fields for dynamic search mode |
| `showColumnSettings` | `boolean` | `false` | Show column visibility settings |
| `columnSettingsKey` | `string` | — | localStorage persistence key |
| `resizable` | `boolean` | `false` | Enable column resize |
| `showBatchBar` | `boolean` | `true` | Show batch action bar on selection |
| `tagMap` | `Record<string, Record<string\|number, {label, color?}>>` | — | Auto-render enum columns as Tags |
| `scroll` | `{ x?: number \| string; y?: number \| string }` | — | **Horizontal scroll config** — MUST set `x` when total column width exceeds container. Use `'max-content'` for flexible columns, or sum all column widths for fixed layouts |

### Events

| Event | Payload | When |
|-------|---------|------|
| `@change` | `(pag, filters, sorter, extra)` | Table pagination/filter/sort change |
| `@rowClick` | `(record, index, event)` | Row click |
| `@export` | — | Export button clicked |
| `@import` | `(file: File)` | File selected for import |
| `@downloadTemplate` | — | Template download clicked |
| `@batchDelete` | `(keys, rows)` | Batch delete confirmed |
| `@selectionChange` | `(keys, rows)` | Selection changes (bind to useOsTablePage.updateSelection) |
| `@dynamicSearch` | `(conditions)` | Dynamic search conditions change (bind to useOsTablePage.handleDynamicSearch) |
| `@search` | — | Search triggered (bind to useOsTablePage.handleQuery) |

### Slots

| Slot | Scope | Purpose |
|------|-------|---------|
| `search` | `{ triggerSearch }` | Simple search form area. Use `triggerSearch` for the search button's click handler |
| `advancedSearch` | — | Advanced search form (static mode, shown when expanded) |
| `toolbar` | — | Extra toolbar buttons (between export and column settings) |
| `actions` | — | Extra action buttons (after column settings) |
| `batchActions` | — | Extra batch action buttons |
| `bodyCell` | `{ column, record, index, text, value }` | Custom cell rendering |
| `title` | — | Custom card title |
| `summary` | — | Table summary row |
| `empty` | — | Custom empty state |

### useOsTablePage Composable

```ts
import { useOsTablePage } from '@/composables/useOsTablePage'

const {
  loading,          // Ref<boolean> — table loading state
  tableData,        // Ref<T[]> — table row data
  pagination,       // Reactive pagination object — bind to OsTablePage :pagination
  queryForm,        // Reactive query form — bind to v-model of search inputs
  selectedRowKeys,  // Ref<(string|number)[]> — selected row keys
  selectedRows,     // Ref<T[]> — selected row data
  hasSelection,     // ComputedRef<boolean>
  dynamicConditions,// Ref<DynamicSearchCondition | null>
  handleQuery,      // () => void — trigger search
  handleReset,      // () => void — reset form and search
  handleTableChange,// (pag, filters, sorter) => void — table change handler
  fetchData,        // () => Promise<void> — manual data fetch
  clearSelection,   // () => void — clear all selections
  updateSelection,  // (keys, rows) => void — update selection
  handleDynamicSearch, // (conditions) => void — handle dynamic search
  getSearchParams,  // () => Q & PageParams
  getExportParams,  // () => Record<string, any>
} = useOsTablePage({
  fetchFn: getXxxList,          // API function
  defaultQuery: () => ({ ... }), // Factory function for initial query
  immediate: true,              // Auto-load on mount
  defaultPageSize: 10,
  pageSizeOptions: ['10', '20', '50', '100'],
})
```

### Quick Start Template

```vue
<script setup lang="ts">
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import OsModalForm from '@/components/ucp-modal-form/OsModalForm.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useOsModalForm } from '@/composables/useOsModalForm'
import { getXxxList, createXxx, updateXxx, deleteXxx } from '@/api/xxx'
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue'

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 150 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 80 },
  { title: '操作', key: 'action', width: 180, fixed: 'right' },
]

const tagMap = {
  status: {
    1: { label: '启用', color: 'success' },
    0: { label: '禁用', color: 'error' },
  },
}

const {
  loading, tableData, pagination, queryForm,
  selectedRowKeys, selectedRows,
  handleQuery, handleReset, handleTableChange,
  updateSelection,
} = useOsTablePage({
  fetchFn: getXxxList,
  defaultQuery: () => ({ name: '', status: undefined }),
})

const {
  modalOpen, modalLoading, modalTitle, formData,
  openCreate, openEdit, closeModal, handleOk,
} = useOsModalForm({
  createFn: createXxx,
  updateFn: updateXxx,
  defaultForm: () => ({ name: '', status: 1 }),
  onSuccess: handleQuery,
})
</script>

<template>
  <div class="page-container">
    <OsTablePage
      :columns="columns"
      :data-source="tableData"
      :loading="loading"
      :pagination="pagination"
      :selected-row-keys="selectedRowKeys"
      :tag-map="tagMap"
      row-key="id"
      show-column-settings
      column-settings-key="xxx-list"
      show-export
      show-import
      @change="handleTableChange"
      @selection-change="updateSelection"
    >
      <!-- Search Slot -->
      <template #search="{ triggerSearch }">
        <a-form layout="inline" :model="queryForm">
          <a-form-item label="名称">
            <a-input v-model:value="queryForm.name" placeholder="请输入" allow-clear @press-enter="triggerSearch" />
          </a-form-item>
          <a-form-item>
            <a-space>
              <a-button type="primary" @click="triggerSearch">
                <SearchOutlined />查询
              </a-button>
              <a-button @click="handleReset">
                <ReloadOutlined />重置
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </template>

      <!-- Toolbar Slot -->
      <template #toolbar>
        <a-button type="primary" @click="openCreate()">
          <PlusOutlined />新增
        </a-button>
      </template>

      <!-- Actions Column -->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="deleteXxx(record.id).then(handleQuery)">
              <a class="text-error">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </OsTablePage>

    <!-- Form Modal -->
    <OsModalForm
      :open="modalOpen"
      :loading="modalLoading"
      :title="modalTitle"
      :form-data="formData"
      @ok="handleOk"
      @cancel="closeModal"
    >
      <template #formItems="{ formData: fd }">
        <a-form-item label="名称" name="name" :rules="[{ required: true, message: '请输入名称' }]">
          <a-input v-model:value="fd.name" placeholder="请输入" />
        </a-form-item>
      </template>
    </OsModalForm>
  </div>
</template>
```

---

## OsModalForm — Universal Form Container

**Location**: `src/components/ucp-modal-form/OsModalForm.vue`
**Composable**: `src/composables/useOsModalForm.ts`

### When to Use

Any form displayed in a modal, drawer, or fullscreen overlay — especially for CRUD create/edit forms.

### Props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `open` | `boolean` | required | Visibility (bind from useOsModalForm.modalOpen) |
| `loading` | `boolean` | `false` | Submit loading state (bind from useOsModalForm.modalLoading) |
| `title` | `string` | `''` | Form title |
| `width` | `string\|number` | `600` | Modal/drawer width |
| `formData` | `Record<string,any>` | `{}` | Form reactive data |
| `rules` | `Record<string,any>` | `{}` | Form validation rules |
| `labelCol` | `object` | `{ span: 5 }` | Label column span |
| `wrapperCol` | `object` | `{ span: 17 }` | Wrapper column span |
| `showFooter` | `boolean` | `true` | Show footer buttons |
| `okText` | `string` | `'确定'` | OK button text |
| `cancelText` | `string` | `'取消'` | Cancel button text |
| `displayMode` | `'modal' \| 'drawer' \| 'fullscreen'` | `'modal'` | Display mode |
| `allowSwitchDisplay` | `boolean` | `true` | Allow user to switch display mode |
| `displayModes` | `DisplayMode[]` | `['modal','drawer','fullscreen']` | Available modes |
| `resizable` | `boolean` | `true` | Allow drag-to-resize |
| `maskClosable` | `boolean` | `false` | Close on mask click (should stay false for forms) |

### Slots

| Slot | Scope | Purpose |
|------|-------|---------|
| `formItems` | `{ formData }` | **Primary slot** — put your `a-form-item`s here |
| `toolbar` | — | Extra buttons in the title bar |
| `footer` | `{ loading }` | Custom footer (default: Cancel + OK) |

### Footer Default

```html
<!-- Default footer (right-aligned) -->
<a-button @click="cancel">取消</a-button>
<a-button type="primary" :loading="loading" @click="ok">确定</a-button>
```

---

## UserSelector — Multi-Purpose User/Dept/Role/Org Picker

**Location**: `src/components/UserSelector/index.vue`

### When to Use

- Multi-user/multi-dept selection with modal picker
- Tree-based dept/org selection
- Any complex selection scenario involving users, departments, roles, or organizations
- **All selections must display avatars**

### Props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `value` / `modelValue` | `string \| string[]` | — | Selected value(s) |
| `mode` | `'single' \| 'multiple'` | `'multiple'` | Selection mode |
| `type` | `'user' \| 'dept' \| 'role' \| 'org'` | `'user'` | Entity type |
| `placeholder` | `string` | — | Trigger placeholder |

### Key Features

- Multi-tab: supports switching between user/dept/role/org
- Tree view with search
- List view with pagination
- Avatar display in selected items
- Modal-based for complex selection, inline for simple

---

## MemberSelect — Lightweight Single-Select with Avatar

**Location**: `src/components/MemberSelect.vue`

### When to Use

- **Single-select** dropdown for project members in forms
- Lightweight alternative to UserSelector for simple use cases
- Contains built-in avatar display

### Props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `value` / `modelValue` | `string` | — | Selected member ID |
| `projectId` | `string` | — | Project ID for member list |

---

## Decision Tree: Which Selector to Use

```
Need to select users/members?
├── Multi-select OR modal picker needed?
│   └── YES → Use UserSelector (type="user", mode="multiple")
├── Single-select, dropdown style?
│   ├── Project members only? → Use MemberSelect
│   └── All users/depts/roles? → Use UserSelector (mode="single")
├── Tree-based selection (dept hierarchy)?
│   └── Use UserSelector (type="dept")
└── Avatar display required?
    └── Both UserSelector and MemberSelect support avatars
```

---

## BreadcrumbNav — Page Breadcrumb

**Location**: `src/components/BreadcrumbNav.vue`

Used automatically in `BasicLayout.vue`. No manual usage needed in page components.

---

## CodeEditor — Code/Markdown Editor

**Location**: `src/components/CodeEditor.vue`

Use for any code or markdown editing scenario (pipeline config, document editing, etc.).

---

## Custom `.btn` / `.card` Classes (Legacy — Avoid in New Code)

Defined in `src/style.css`, these are native CSS classes ported from a prototype.
**Avoid using `.btn`, `.btn-primary`, `.btn-default`, `.btn-sm`, `.card`, `.card-header`, `.card-body` in new code.**
Use Ant Design `<a-button>` and `<a-card>` instead.

Exception: The workitem and wbs modules still use these classes for historical reasons.
When touching these modules, migrate to Ant Design components when feasible.

---

## Action Column Button Patterns (操作列按钮规范)

### Standard Pattern

所有表格操作列必须使用以下模式：`a-button type="link"` + 图标 + 文字，按钮间用竖线分隔符分隔。

```vue
<template #bodyCell="{ column, record }">
  <template v-if="column.key === 'action'">
    <div class="action-cell">
      <!-- 编辑：品牌紫色 -->
      <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500" @click="handleEdit(record)">
        <EditOutlined />编辑
      </a-button>
      <span class="action-sep">|</span>
      <!-- 删除：danger 红色 -->
      <a-popconfirm title="确认删除？" @confirm="handleDelete(record)">
        <a-button type="link" danger style="padding: 0 6px; font-weight: 500">
          <DeleteOutlined />删除
        </a-button>
      </a-popconfirm>
    </div>
  </template>
</template>

<style scoped>
.action-cell {
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}
.action-sep {
  color: #d1d5db;
  padding: 0 6px;
  user-select: none;
}
/* 操作列链接按钮字号统一为表格正文字号 */
:deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}
:deep(.ant-btn-link .anticon) {
  font-size: 13px;
}
</style>
```

### Button Color Reference

| 操作 | 颜色 | CSS / Prop |
|------|------|-----------|
| 编辑 | `#4338CA` | `style="color: #4338CA; font-weight: 500"` |
| 复制 | `#059669` | `style="color: #059669"` |
| 删除 | red | `danger` prop |
| 新建/新增 | `#D97706` | `style="color: #D97706"` |
| 详情/查看 | `#1677ff` | default link color |
| 中性操作 | `#6B7280` | `style="color: #6b7280"` |

### Rules

1. **必须使用 `type="link"`** — 文字按钮风格，不要使用 `type="primary"` 或默认样式
2. **必须有图标前缀** — 使用 `@ant-design/icons-vue` 的图标组件
3. **分隔符用 `|` 字符** — 不要使用 CSS border 或伪元素
4. **padding: 0 6px** — 统一的按钮内边距
5. **font-weight: 500** — 编辑和删除按钮加粗强调
6. **图标字号 13px** — 通过 `:deep(.ant-btn-link .anticon)` 统一设置
7. **操作列宽度** — CRUD 标准为 160px，多操作时按需扩展（如用户管理 280px）

---

## Table Column Specification (表格列配置规范)

### Column Width Rules

| 列类型 | 推荐宽度 | 说明 |
|--------|---------|------|
| 编号/ID | 70-80px | 自动序号列默认 60px |
| 优先级/状态 | 45-80px | 枚举值短文本 |
| 标题/名称 | 120-300px | 弹性宽度，常用 ellipsis |
| 日期列 | 85-170px | 视格式而定 |
| 负责人 | 55px | 头像或短文本 |
| 操作列 | 160px | 2-3个操作为基准 |
| 操作列(多操作) | 200-280px | 4+个操作时扩展 |

### Alignment Rules

- 所有列（表头 + 数据）默认居中对齐
- 标题/名称列可左对齐（内容型长文本）
- 操作列始终居中

### Default Config

```ts
// OsTablePage 默认配置
{
  showIndex: true,        // 显示序号列
  indexWidth: 60,         // 序号列宽度
  size: 'middle',         // 表格尺寸
  rowKey: 'id',           // 行键
  showColumnSettings: true, // 启用列设置
  resizable: true,        // 启用列宽拖拽
  scroll: { x: 'max-content' }, // 横向滚动（列多时必需）
}
```

### Horizontal Scroll Rules

| 表格类型 | 滚动方案 | 说明 |
|---------|---------|------|
| **OsTablePage** | `:scroll="{ x: ... }"` | 传入 `x: 'max-content'`（弹性列宽）或 `x: <总宽px>`（固定列宽） |
| **自定义原生表格** | 容器 `overflow: auto` | 需自行实现横向滚动，无内置支持 |
| **原生 a-table** | `:scroll="{ x: ... }"` | 必须显式设置，否则小屏幕列被遮挡且无法滑动 |

**计算固定列宽总和:** `sum(所有 column.width) + 60(序号列) + 额外内边距`

**验证方法:** 在 ≤1280px 宽度下检查所有列是否可见且可滑动。

**⚠️ 防止滚动后右侧留白（操作列宽度不够）：**

当 `scroll.x` 设固定值但容器更宽时，表格被拉伸填满容器，而列宽总和不够 → 操作列右侧出现大片空白。

| 场景 | 现象 | 修复 |
|------|------|------|
| `scroll.x=1200`，容器 1400px | 表格拉至 1400px，列宽总和仅 1100px | → 右侧留白 300px |
| `scroll.x=1200`，容器 1000px | 表格 1200px，横向滚动正常 | ✅ 正常 |

**三种解法（优先级从高到低）：**

1. **`x: 'max-content'`（推荐）** — 表格宽度仅由列内容决定，不随容器拉伸，从根本上消除留白。
   ```html
   <OsTablePage :scroll="{ x: 'max-content' }" ... />
   ```

2. **`fixed: 'right'` + 匹配列宽** — 操作列固定贴右，其他列弹性填充。确保列宽总和 ≥ scroll.x。
   ```ts
   { title: '操作', key: 'action', width: 160, fixed: 'right' as const, align: 'center' as const }
   ```

3. **精确匹配** — 列宽总和精确等于 scroll.x 值（不推荐，维护成本高）。

**已有防护**: OsTablePage 已内置固定列白边修复 CSS：
```css
:deep(.ant-table-cell-fix-right.ant-table-cell-fix-right-last) {
  right: 0 !important;
  box-shadow: none !important;
}
```

### Search Area Pattern

```vue
<template #search="{ triggerSearch }">
  <a-form layout="inline" :model="queryForm">
    <a-form-item label="关键字">
      <a-input v-model:value="queryForm.keyword" placeholder="请输入" allow-clear
               style="width: 160px" @press-enter="triggerSearch" />
    </a-form-item>
    <a-form-item label="状态">
      <a-select v-model:value="queryForm.status" placeholder="全部" allow-clear
                style="width: 100px">
        <a-select-option v-for="opt in options" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item>
      <a-space>
        <a-button type="primary" @click="triggerSearch"><SearchOutlined />查询</a-button>
        <a-button @click="handleReset"><ReloadOutlined />重置</a-button>
      </a-space>
    </a-form-item>
  </a-form>
</template>
```

**Rules:**
- Input 宽度: 160px（短文本）, 200px（中等）, 240px（长文本）
- Select 宽度: 100px（状态等短枚举）, 120-140px（中等枚举）
- 查询按钮 `type="primary"`，重置按钮默认样式
- 始终用 `<SearchOutlined />` + 查询 / `<ReloadOutlined />` + 重置
- 两个按钮用 `<a-space>` 包裹

---

## Stat Cards — 统计指标卡片

### When to Use

页面顶部展示关键指标概览（总数、分类统计、覆盖率等），通常 3-4 张卡片横向排列。

### Standard Template

```vue
<template>
  <div class="stats-row">
    <!-- 紫色：总数/主指标 -->
    <a-card size="small" class="stat-card">
      <div class="stat-content">
        <div>
          <div class="stat-title">指标标题</div>
          <div class="stat-value stat-value-purple">{{ total }}</div>
          <div class="stat-label">辅助说明</div>
        </div>
        <div class="stat-icon stat-icon-purple">
          <AppstoreOutlined />
        </div>
      </div>
    </a-card>

    <!-- 橙色：高优/警告类指标 -->
    <a-card size="small" class="stat-card">
      <div class="stat-content">
        <div>
          <div class="stat-title">P0 指标</div>
          <div class="stat-value stat-value-orange">{{ p0Count }}</div>
          <div class="stat-label">高优先级</div>
        </div>
        <div class="stat-icon stat-icon-orange">
          <FlagOutlined />
        </div>
      </div>
    </a-card>

    <!-- 蓝色：中优/信息类指标 -->
    <a-card size="small" class="stat-card">
      <div class="stat-content">
        <div>
          <div class="stat-title">P1/P2 指标</div>
          <div class="stat-value stat-value-blue">{{ midCount }}</div>
          <div class="stat-label">中优先级</div>
        </div>
        <div class="stat-icon stat-icon-blue">
          <LinkOutlined />
        </div>
      </div>
    </a-card>

    <!-- 绿色：完成/正向指标 -->
    <a-card size="small" class="stat-card">
      <div class="stat-content">
        <div>
          <div class="stat-title">覆盖率</div>
          <div class="stat-value stat-value-green">{{ rate }}</div>
          <div class="stat-label">覆盖详情</div>
        </div>
        <div class="stat-icon stat-icon-green">
          <FileTextOutlined />
        </div>
      </div>
    </a-card>
  </div>
</template>

<style scoped>
.stats-row {
  display: flex;
  gap: var(--spacing-sm);
  justify-content: space-evenly;
  margin-bottom: var(--spacing-lg);
}
.stat-card {
  flex: 1;
  min-width: 0;
}
.stat-content {
  display: flex;
  justify-content: space-between;
}
.stat-icon {
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 20px;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}
.stat-title {
  font-size: 13px;
  color: var(--text-secondary);
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
  padding: var(--spacing-xs) 0;
}
.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
```

### Color Variant Rules

| 变体类名 | 语义 | 何时使用 |
|---------|------|---------|
| `.stat-value-purple` / `.stat-icon-purple` | 主品牌色 | 总数、主指标、项目数 |
| `.stat-value-orange` / `.stat-icon-orange` | 警示/高优 | P0、待处理、风险项、到期 |
| `.stat-value-blue` / `.stat-icon-blue` | 信息/中优 | P1/P2、进行中、链接类指标 |
| `.stat-value-green` / `.stat-icon-green` | 成功/正向 | 覆盖率、成功率、已完成 |

**Rules:**
- 每张卡片使用不同颜色，不要所有卡片同一颜色
- 图标必须有 44×44 固定尺寸 + `var(--radius-sm)` 圆角 + 淡色背景
- 数值 28px/600，标题 13px/辅助色，说明 12px/辅助色
- 图标字号 20px
- 卡片横向均分：`flex: 1` + `justify-content: space-evenly`

---

## Loading Patterns (数据加载模式)

### a-spin — Content Area Spinner

**Location**: Ant Design Vue `a-spin` (import from `ant-design-vue`)

Use when any content block (table, detail, tree) loads asynchronously.

#### Props (commonly used)

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `spinning` | `boolean` | `true` | Show spinner (bind to `loading` ref) |
| `tip` | `string` | — | Loading text below spinner |
| `delay` | `number` | — | Delay (ms) before showing spinner. **Always use 300** |
| `size` | `'small' \| 'default' \| 'large'` | `'default'` | Spinner size |

#### Standard Usage

```html
<a-spin :spinning="loading" tip="加载中..." :delay="300">
  <div class="content">
    <!-- loaded content -->
  </div>
</a-spin>
```

**Rules:**
- Always set `:delay="300"` to prevent flash on fast loads
- Always use `tip="加载中..."` (unified text)
- Empty/"暂无数据" shown only when `!loading && data.length === 0`
- Do NOT wrap OsTablePage with a-spin (OsTablePage handles loading internally)

---

### a-skeleton — Content Placeholder

**Location**: Ant Design Vue `a-skeleton` (import from `ant-design-vue`)

Use for detail pages, dashboard widgets, or any content area with known structure.

#### Props (commonly used)

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `active` | `boolean` | `false` | Show animation shimmer |
| `paragraph` | `object \| boolean` | `true` | Paragraph rows config: `{ rows, width }` |
| `title` | `boolean \| object` | `true` | Show title skeleton |
| `avatar` | `boolean \| object` | `false` | Show avatar skeleton |

#### Standard Usage

```html
<!-- Detail page skeleton -->
<template v-if="loading">
  <a-card><a-skeleton active :paragraph="{ rows: 6 }" /></a-card>
</template>

<template v-else>
  <a-card title="详情">
    <!-- actual content -->
  </a-card>
</template>
```

**Row guidelines:**
- Simple card/form: `{ rows: 3 }`
- Standard detail page: `{ rows: 6 }`
- Complex content (e.g., pipeline forms): `{ rows: 10 }`

---

### Table Loading — OsTablePage `:loading`

OsTablePage has built-in loading via the `:loading` prop, sourced from `useOsTablePage.loading`.

**Do NOT wrap OsTablePage with `a-spin`** — it creates duplicate spinners.

```html
<!-- ✅ Correct -->
<OsTablePage :loading="loading" :data-source="tableData" ... />

<!-- ❌ Wrong: duplicate spinner -->
<a-spin :spinning="loading">
  <OsTablePage :loading="loading" ... />
</a-spin>
```

---

### Custom Table Loading — `a-spin` wrapper

A hand-written table has no built-in loading. Wrap content with `a-spin` internally. Pass `pageLoading`:

```ts
const pageLoading = ref(true) // initially true → shows spinner immediately on mount
```

```html
<CustomTable :loading="pageLoading" :items="enrichedItems" />
```

Internal empty state shown as:
```html
<template v-if="!loading && items.length === 0">
  <td colspan="..." class="cell-empty">
    <span class="empty-text">暂无数据</span>
  </td>
</template>
```

---

### Form Submit Loading — `useOsModalForm.modalLoading`

OsModalForm's footer OK button automatically uses `:loading="modalLoading"`.

```ts
const { modalLoading, openCreate, handleOk } = useOsModalForm({
  createFn: createXxx,
  onSuccess: handleQuery,
})
```

```html
<OsModalForm :loading="modalLoading" ... />
```

---

### Button Loading — Inline Action Buttons

For standalone action buttons (export, sync, import):

```ts
const btnLoading = ref(false)
async function handleAction() {
  btnLoading.value = true
  try {
    // async action
  } finally {
    btnLoading.value = false
  }
}
```

```html
<!-- Ant Design button -->
<a-button :loading="btnLoading" @click="handleAction">导出</a-button>

<!-- Native button: disable + text change -->
<button class="btn btn-default" :disabled="btnLoading" @click="handleAction">
  {{ btnLoading ? '导出中...' : '导出' }}
</button>
```

---

### Loading State Decision Table

| Scenario | Component | Key Pattern |
|----------|-----------|-------------|
| CRUD table list | OsTablePage `:loading` | Built-in, from `useOsTablePage.loading` |
| Hand-written table | `a-spin` wrapper | Internal `a-spin`; empty state via `v-if="!loading && items.length === 0"` |
| Custom content block | `a-spin` | `:spinning` + `:delay="300"` + `tip="加载中..."` |
| Detail page | `a-skeleton` | `v-if="loading"` with `active` + paragraph rows |
| Form modal submit | `OsModalForm :loading` | From `useOsModalForm.modalLoading` |
| Inline action button | `:loading` / `:disabled` | Toggle text + disable during async |
