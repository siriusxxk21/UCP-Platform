# Examples — Good vs Bad Patterns

This document shows concrete before/after examples for common UI patterns.

---

## Colors

### ❌ Bad: Hardcoded Hex Colors

```vue
<template>
  <div style="color: #6b7280; background: #f5f5f5; border: 1px solid #e5e7eb;">
    <span style="color: #ef4444;">Error message</span>
  </div>
</template>

<style scoped>
.header {
  color: #1f2937;
  background: #f3f4f6;
}
.success-text {
  color: #10b981;
}
</style>
```

### ✅ Good: CSS Variables + Semantic Colors

```vue
<template>
  <div class="info-box">
    <span class="error-text">Error message</span>
  </div>
</template>

<style scoped>
.info-box {
  color: var(--text-secondary);
  background: var(--control-active-bg);
  border: 1px solid var(--border);
}
.error-text {
  color: var(--error);
}
</style>
```

```vue
<!-- Even better: use Ant Design semantic components -->
<a-alert type="error" message="Error message" />
<a-tag color="error">Failed</a-tag>
<a-button danger>Delete</a-button>
```

---

## Font Sizes

### ❌ Bad: Values Outside Design Token System

```css
.page-title { font-size: 22px; }       /* ❌ not in the 6-tier token table */
.section-title { font-size: 18px; }    /* ❌ not in the 6-tier token table */
.hint-text { font-size: 15px; }        /* ❌ not in the 6-tier token table */
.small-label { font-size: 11px; }      /* ❌ not in the 6-tier token table */
```

### ✅ Good: Values From Design Token Table

```css
.page-title { font-size: 20px; }       /* ✅ 6-tier table: page-level heading */
.section-title { font-size: 16px; }    /* ✅ fontSizeLG: Dialog/Drawer title */
.hint-text { font-size: 14px; }       /* ✅ fontSize: body text */
.small-label { font-size: 12px; }      /* ✅ fontSizeSM: Tag, Tooltip */
```

```vue
<!-- Or use Ant Design Typography -->
<a-typography-title :level="3">Section Title</a-typography-title>
<a-typography-text type="secondary">Hint text</a-typography-text>
```

---

## Border Radius

### ❌ Bad: Values Outside Design Token Table

```css
.card { border-radius: 12px; }         /* ❌ not a token value */
.dialog { border-radius: 10px; }       /* ❌ not a token value */
.tag { border-radius: 16px; }          /* ❌ not a token value */
.button { border-radius: 3px; }        /* ❌ not a token value */
```

### ✅ Good: Values From Token Table

```css
.card { border-radius: var(--radius); }       /* ✅ 8px */
.dialog { border-radius: var(--radius); }     /* ✅ 8px */
.tag { border-radius: var(--radius-sm); }     /* ✅ 6px */
.button { border-radius: var(--radius-sm); }  /* ✅ 6px */
```

---

## Spacing

### ❌ Bad: Values Outside Design Token Table

```css
.section { margin-bottom: 28px; }      /* ❌ not on 4px grid / not a token */
.row { gap: 14px; }                    /* ❌ not on 4px grid / not a token */
.card-body { padding: 10px; }          /* ❌ not on 4px grid / not a token */
```

### ✅ Good: Values From Token Table

```css
.section { margin-bottom: var(--spacing-2xl); }   /* ✅ 32px */
.row { gap: var(--spacing-lg); }                   /* ✅ 16px */
.card-body { padding: var(--spacing-md); }         /* ✅ 12px */
```

---

## Button Heights

### ❌ Bad: Custom Heights Outside Token System

```css
.login-btn { height: 44px; }           /* ❌ not a token value */
.special-btn { height: 36px; }         /* ❌ not a token value */
```

### ✅ Good: Use Ant Design Size Props or Token Variables

```vue
<!-- Standard sizes built into Ant Design -->
<a-button>Small</a-button>
<a-button>Default</a-button>
<a-button size="large">Large</a-button>
```

```css
/* If you MUST override, use the token system */
.my-btn-sm { height: var(--control-height-sm); }
.my-btn { height: var(--control-height); }
.my-btn-lg { height: var(--control-height-lg); }
```

---

## Form Patterns

### ❌ Bad: Inline Styles + Custom Modal

```vue
<template>
  <a-modal v-model:open="visible" title="新增用户" @ok="handleOk">
    <a-form :model="form">
      <a-form-item label="用户名" style="margin-bottom: 10px;">
        <a-input v-model:value="form.name" style="height: 36px; border-radius: 8px;" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
```

### ✅ Good: OsModalForm + Design Tokens

```vue
<template>
  <OsModalForm
    :open="modalOpen"
    :loading="modalLoading"
    :title="modalTitle"
    :form-data="formData"
    @ok="handleOk"
    @cancel="closeModal"
  >
    <template #formItems="{ formData: fd }">
      <a-form-item label="用户名" name="name" :rules="[{ required: true }]">
        <a-input v-model:value="fd.name" placeholder="请输入用户名" />
      </a-form-item>
    </template>
  </OsModalForm>
</template>
```

---

## Table Patterns

### ❌ Bad: Custom Table Implementation

```vue
<template>
  <div class="custom-table-wrapper">
    <div class="search-bar" style="padding: 10px; background: #fafbfc;">
      <input v-model="keyword" style="height: 34px; border-radius: 4px;" />
      <button style="height: 34px; background: #4338ca; color: #fff;">搜索</button>
    </div>
    <a-table :columns="cols" :data-source="data" :pagination="pagination" />
  </div>
</template>
```

### ✅ Good: OsTablePage Standard Pattern

```vue
<template>
  <OsTablePage
    :columns="columns"
    :data-source="tableData"
    :loading="loading"
    :pagination="pagination"
    :tag-map="tagMap"
    @change="handleTableChange"
    @selection-change="updateSelection"
  >
    <template #search="{ triggerSearch }">
      <a-form layout="inline" :model="queryForm">
        <a-form-item label="关键字">
          <a-input v-model:value="queryForm.keyword" placeholder="请输入" @press-enter="triggerSearch" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="triggerSearch"><SearchOutlined />查询</a-button>
          <a-button @click="handleReset"><ReloadOutlined />重置</a-button>
        </a-form-item>
      </a-form>
    </template>
  </OsTablePage>
</template>
```

---

## User Selection

### ❌ Bad: No Avatar, Raw Select

```vue
<a-select v-model:value="userId" placeholder="选择用户">
  <a-select-option v-for="u in users" :key="u.id" :value="u.id">
    {{ u.name }}
  </a-select-option>
</a-select>
```

### ✅ Good: MemberSelect with Avatar (Single-Select Dropdown)

```vue
<MemberSelect v-model:value="userId" :project-id="projectId" />
```

### ✅ Good: UserSelector with Avatar (Multi-Select / Modal)

```vue
<UserSelector v-model:value="userIds" mode="multiple" type="user" />
```

---

## `!important` Usage

### ❌ Bad: Relying on `!important` for Specificity

```css
.my-class {
  font-size: 13px !important;       /* ❌ just increase specificity properly */
  color: #262626 !important;        /* ❌ */
  background: #fafafa !important;   /* ❌ */
}
```

### ✅ Good: Proper Specificity or `:deep()` for Ant Design Override

```css
/* Use scoped + :deep() for Ant Design component overrides */
.my-wrapper :deep(.ant-table-thead > tr > th) {
  background: var(--brand-table);
}

/* Or use higher specificity without !important */
.my-page .my-section .ant-card-body {
  padding: var(--spacing-lg);
}
```

**`!important` is ONLY acceptable when:**
1. Overriding a third-party library's inline style
2. Accompanied by a `/* reason: ... */` comment explaining why

---

## Inline Styles

### ❌ Bad: Inline Styles for Layout

```vue
<div style="display: flex; gap: 12px; padding: 16px; background: #f9fafb; border-radius: 8px;">
  <span style="font-size: 14px; font-weight: 600; color: #1f2937;">Title</span>
  <span style="font-size: 12px; color: #9ca3af;">Subtitle</span>
</div>
```

### ✅ Good: CSS Class with Design Tokens

```vue
<div class="info-card">
  <span class="info-card-title">Title</span>
  <span class="info-card-subtitle">Subtitle</span>
</div>

<style scoped>
.info-card {
  display: flex;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background: var(--control-active-bg);
  border-radius: var(--radius);  /* 8px */
}
.info-card-title {
  font-size: 14px;    /* fontSize */
  font-weight: 600;
  color: var(--text-primary);
}
.info-card-subtitle {
  font-size: 12px;    /* fontSizeSM */
  color: var(--text-tertiary);
}
</style>
```

**Inline `style` is ONLY acceptable for truly dynamic values:**

```vue
<!-- ✅ OK: dynamic width -->
<div :style="{ width: progress + '%' }" class="progress-bar" />

<!-- ✅ OK: dynamic color from data -->
<a-tag :color="statusColor">Active</a-tag>
```

---

## Action Column (操作列)

### ❌ Bad: Primary Buttons or No Icons

```vue
<template #bodyCell="{ column, record }">
  <template v-if="column.key === 'action'">
    <a-space>
      <a-button size="small" @click="handleEdit(record)">编辑</a-button>
      <a-button size="small" danger @click="handleDelete(record.id)">删除</a-button>
    </a-space>
  </template>
</template>
```

### ❌ Bad: Custom CSS Borders as Separators

```vue
<a-button type="link" class="action-btn">编辑</a-button>
<a-button type="link" class="action-btn bordered">删除</a-button>

<style>
.action-btn.bordered { border-left: 1px solid #e5e7eb; padding-left: 8px; }
</style>
```

### ✅ Good: Standard Action Column Pattern

```vue
<template #bodyCell="{ column, record }">
  <template v-if="column.key === 'action'">
    <div class="action-cell">
      <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500" @click="handleEdit(record)">
        <EditOutlined />编辑
      </a-button>
      <span class="action-sep">|</span>
      <a-popconfirm title="确认删除？" @confirm="handleDelete(record.id)">
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
:deep(.ant-btn-link) {
  font-size: var(--table-body-font-size, 14px);
  height: auto;
}
:deep(.ant-btn-link .anticon) {
  font-size: 13px;
}
</style>
```

---

## Table Column Alignment (表格列对齐)

### ❌ Bad: Default (left-aligned) with No Alignment Specified

```ts
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80 },
  { title: 'Status', dataIndex: 'status', width: 100 },
  { title: 'Action', key: 'action', width: 160, fixed: 'right' },
]
// Missing: align, causing inconsistent rendering across different data lengths
```

### ✅ Good: Explicit Center Alignment + Title Left Alignment

```ts
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80, align: 'center' },
  { title: 'Title', dataIndex: 'title', width: 300, align: 'left', ellipsis: true },
  { title: 'Status', dataIndex: 'status', width: 80, align: 'center' },
  { title: 'Action', key: 'action', width: 160, fixed: 'right', align: 'center' },
]
```

---

## Search Area (搜索区域)

### ❌ Bad: Inconsistent Widths, No Icons, Wrong Button Types

```vue
<template #search>
  <a-form layout="inline">
    <a-form-item label="名称">
      <a-input v-model:value="queryForm.name" style="width: 100%" />
    </a-form-item>
    <a-form-item>
      <a-button type="default" @click="handleQuery">搜索</a-button>
      <a-button type="text" @click="handleReset">清空</a-button>
    </a-form-item>
  </a-form>
</template>
```

### ✅ Good: Standard Pattern

```vue
<template #search="{ triggerSearch }">
  <a-form layout="inline" :model="queryForm">
    <a-form-item label="用户名">
      <a-input v-model:value="queryForm.username" placeholder="请输入用户名" allow-clear
               style="width: 160px" @press-enter="triggerSearch" />
    </a-form-item>
    <a-form-item label="状态">
      <a-select v-model:value="queryForm.status" placeholder="全部" allow-clear
                style="width: 100px">
        <a-select-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </a-select-option>
      </a-select>
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
```

---

## Stat Cards (统计卡片)

### ❌ Bad: Inline Styles with Hardcoded Colors

```vue
<template>
  <div class="stats-row">
    <a-card size="small">
      <div class="stat-value" style="color: #4338ca">{{ total }}</div>
      <div class="stat-icon" style="color: #4338ca; background: rgba(67,56,202,0.08)">
        <Icon />
      </div>
    </a-card>
    <a-card size="small">
      <div class="stat-value" style="color: #d97706">{{ p0 }}</div>
      <div class="stat-icon" style="color: #d97706; background: rgba(217,119,6,0.08)">
        <Icon />
      </div>
    </a-card>
  </div>
</template>
```

Issues: 硬编码颜色分散在模板中，每种卡片颜色变体重复书写 rgba 值，无法复用、难以统一调整。

### ✅ Good: CSS Class Variants + Design Tokens

```vue
<template>
  <div class="stats-row">
    <!-- 每个卡片通过 class 变体指定颜色 -->
    <a-card size="small" class="stat-card">
      <div class="stat-content">
        <div>
          <div class="stat-title">用例总数</div>
          <div class="stat-value stat-value-purple">{{ total }}</div>
          <div class="stat-label">本月 + {{ monthNew }}</div>
        </div>
        <div class="stat-icon stat-icon-purple">
          <AppstoreOutlined />
        </div>
      </div>
    </a-card>

    <a-card size="small" class="stat-card">
      <div class="stat-content">
        <div>
          <div class="stat-title">P0 用例</div>
          <div class="stat-value stat-value-orange">{{ p0Count }}</div>
          <div class="stat-label">冒烟 / 核心路径</div>
        </div>
        <div class="stat-icon stat-icon-orange">
          <FlagOutlined />
        </div>
      </div>
    </a-card>
  </div>
</template>

<style scoped>
/* 基础结构 */
.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
  padding: var(--spacing-xs) 0;
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

/* 颜色变体 — 集中定义，一处修改全局生效 */
.stat-value-purple { color: var(--brand); }
.stat-icon-purple { color: var(--brand); background: rgba(67, 56, 202, 0.08); }

.stat-value-orange { color: #D97706; }
.stat-icon-orange { color: #D97706; background: rgba(217, 119, 6, 0.08); }

.stat-value-blue { color: #1677ff; }
.stat-icon-blue { color: #1677ff; background: rgba(22, 119, 255, 0.08); }

.stat-value-green { color: #059669; }
.stat-icon-green { color: #059669; background: rgba(5, 150, 105, 0.08); }
</style>
```

### ❌ Bad: Inconsistent Number Sizes

```css
.stat-num { font-size: 24px; font-weight: bold; }
.stat-num-large { font-size: 32px; }
```

### ✅ Good: From Design Token System

```css
/* Standard stat numbers: 16px/600 */
.stat-num { font-size: 16px; font-weight: 600; color: var(--text-primary); }

/* KPI card numbers: 28px/600 */
.kpi-num { font-size: 28px; font-weight: 600; line-height: 1; }

/* Stat icon: 20px */
.stat-icon { font-size: 20px; }
```

---

## Loading States (数据加载)

### ❌ Bad: No Loading Feedback

```vue
<script setup lang="ts">
const items = ref([])

onMounted(async () => {
  items.value = await fetchItems() // ❌ blank page until data arrives
})
</script>

<template>
  <div class="list">
    <div v-for="item in items" :key="item.id">{{ item.name }}</div>
    <a-empty v-if="items.length === 0" />
  </div>
</template>
```

### ❌ Bad: Missing `:delay` Cause Spinner Flash

```html
<!-- ❌ Every fast request shows a flash of spinner -->
<a-spin :spinning="loading" tip="加载中...">
  <OsTablePage :loading="loading" :data-source="tableData" ... />
</a-spin>
```
Two issues: (1) no `:delay="300"` causes flicker, (2) OsTablePage already handles loading internally.

### ❌ Bad: Empty State While Loading

```html
<a-spin :spinning="loading">
  <div v-if="loading">Loading...</div>
  <a-empty v-else-if="items.length === 0" description="暂无数据" />
  <div v-else v-for="item in items">...</div>
</a-spin>
```
`a-spin` already shows a spinner — the inner `v-if="loading"` is redundant.

### ✅ Good: Table Loading with Load Delay

```vue
<script setup lang="ts">
import { useOsTablePage } from '@/composables/useOsTablePage'

const { loading, tableData, handleQuery } = useOsTablePage({
  fetchFn: getXxxList,
  defaultQuery: () => ({ ... }),
})
</script>

<template>
  <!-- OsTablePage handles loading internally — no extra a-spin needed -->
  <OsTablePage
    :loading="loading"
    :data-source="tableData"
    ...
  />
</template>
```

### ✅ Good: Content Area with a-spin

```vue
<script setup lang="ts">
const loading = ref(false)
const treeData = ref([])

async function loadTree() {
  loading.value = true
  try {
    treeData.value = await fetchTree()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <a-spin :spinning="loading" tip="加载中..." :delay="300">
    <a-tree v-if="!loading && treeData.length > 0" :tree-data="treeData" ... />
    <a-empty v-else-if="!loading" description="暂无数据" />
  </a-spin>
</template>
```

### ✅ Good: Detail Page Skeleton

```vue
<template>
  <!-- Skeleton while loading -->
  <template v-if="loading">
    <a-card>
      <a-skeleton active :paragraph="{ rows: 6 }" />
    </a-card>
  </template>

  <!-- Content after loading -->
  <template v-else>
    <a-card title="用户详情">
      <a-descriptions :column="2">
        <a-descriptions-item label="用户名">{{ detail.username }}</a-descriptions-item>
        <a-descriptions-item label="邮箱">{{ detail.email }}</a-descriptions-item>
        <a-descriptions-item label="角色">{{ detail.roleName }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ detail.statusName }}</a-descriptions-item>
      </a-descriptions>
    </a-card>
  </template>
</template>
```

### ✅ Good: Button Loading with Disable

```vue
<script setup lang="ts">
const exportLoading = ref(false)

async function handleExport() {
  exportLoading.value = true
  try {
    const blob = await exportData()
    downloadBlob(blob)
    message.success('导出成功')
  } catch {
    message.error('导出失败')
  } finally {
    exportLoading.value = false
  }
}
</script>

<template>
  <button
    class="btn btn-default"
    :disabled="exportLoading"
    @click="handleExport"
  >
    <ExportOutlined class="icon-sm" />
    {{ exportLoading ? '导出中...' : '导出' }}
  </button>
</template>
```
