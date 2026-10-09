---
name: frontend-ui-standard
description: >
  【前端 UI 开发强制规范】适用本 Vue 3 + Ant Design Vue 4 + TypeScript 项目。
  所有前端页面的新增、修改、Review、Bug 修复都必须先查阅此技能。
  Provides Design Token reference, component usage patterns, CSS coding standards, architecture
  rules, and a complete AI development workflow. Use when creating new pages, modifying existing
  UI, performing UI code review, fixing UI bugs, or when the user asks about styling conventions,
  design tokens, component reuse, or UI consistency. Triggers on keywords: 前端, frontend, UI,
  style, design, token, component, page, layout, theme, color, font, spacing, radius, button,
  table, form, dialog, drawer, card, review, fix, bug, 修复, 审查, 页面, 样式, 组件.
---

# UI Design System

## Repository Scope

本技能维护于仓库 `.agents/skills/frontend-ui-standard/`；本技能及配套文件中的 `src/`、`@/` 路径均相对于 `ucp-front/`。前后端编码与验证要求同时遵循 [coding-standards](../coding-standards/SKILL.md)。数据中心、应用中心与运行端列表优先遵循 [表格规范](../coding-standards/references/table-pages.md)。组件 props、事件和 token 当前值以 `ucp-front/` 中的实现为准；示例中的固定值用于说明已有设计规格，实际开发优先使用当前 token 和公共样式。

## Core Principle

**All UI values (colors, font sizes, spacing, radius, heights) MUST come from Design Tokens.
NEVER hardcode a specific px/color value directly in components.**

If a design requires a value not in the current Token system → extend the Token first,
then use the new Token. Do NOT bypass the system.

This project's Design System is built on:
- `src/theme/antd-theme.ts` — Ant Design Vue 4 Theme Tokens (injected via `a-config-provider`)
- `src/style.css` — Global CSS Variables (`:root`) and Ant Design component overrides

Full token table with values → [design-tokens.md](design-tokens.md)

---

## Decision Priority

When choosing how to implement any UI element, follow this priority chain
(from most preferred to last resort):

```
1. Existing public component   (OsTablePage, OsModalForm, UserSelector, etc.)
       ↓
2. Design Token               (CSS variable or antd-theme.ts token)
       ↓
3. Ant Design official API    (size="small", type="primary", color="success", etc.)
       ↓
4. Project utility class       (.os-* / .la-* classes from public components)
       ↓
5. Scoped custom CSS           (<style scoped> — only when above options insufficient)
       ↓
6. Inline style                (LAST RESORT — requires comment justification)
```

**Rule: Never skip a level without justification. Always start from the top.**

---

## AI Development Workflow

Every UI development task MUST follow this workflow:

```
Step 1 — SCAN existing pages
    Search src/views/ for pages similar to the target.
    Identify the nearest reference page.

Step 2 — SCAN existing components
    Search src/components/ for reusable components.
    Confirm no component already does the same job.

Step 3 — IDENTIFY Design Tokens
    Check which tokens apply. If a value is missing, extend the token first.

Step 4 — DETERMINE the page Pattern
    Map to a known pattern (CRUD Table / Form Dialog / Dashboard / Custom).

Step 5 — IMPLEMENT following the pattern
    Reuse the reference page structure. Modify ONLY business logic.
    Do NOT redesign the UI layout.

Step 6 — SELF-REVIEW
    Run the Code Review Checklist (see below).
    Verify: no hardcoded values, correct component choice, token usage.

Step 7 — OUTPUT result
    Confirm the page is consistent with existing pages of the same type.
```

When in doubt → open the nearest reference page and follow its structure exactly.

---

## Component Reuse Principle

**Before creating ANY new component or UI pattern:**

1. Scan these directories for existing implementations:
   - `src/components/` — public components
   - `src/views/*/components/` — module-local components
   - `src/composables/` — shared composables

2. If a functionally equivalent component exists → **MUST reuse it.**

3. **NEVER re-implement** these patterns from scratch:
   - Button, Table, Search Form, Dialog, Drawer, Upload, Select, Form,
     Pagination, Tree, TreeSelect, Breadcrumb, Tag, Tooltip, Popover

4. If extending an existing component → extend in-place, do not fork.

5. If creating a genuinely new reusable pattern → place it in `src/components/`
   and update this Skill (see "Skill Maintenance" below).

---

## Architecture Specification

Follow this layered architecture for all pages:

```
View (page .vue)
    ↓  calls
Composable / Hook (use*.ts)
    ↓  calls
API Layer (src/api/*)
    ↓  renders
Component (reusable .vue)
```

**Rules:**
- **View layer**: Template + page-specific layout only. No raw API calls, no heavy business logic.
- **Composable layer**: State management, data fetching orchestration, business logic.
  Every CRUD page MUST use `useOsTablePage` + `useOsModalForm`.
- **API layer**: Pure HTTP functions. No UI state, no component imports.
- **Component layer**: Reusable, props-driven, no direct API calls.

**Anti-patterns to avoid:**
- Writing `fetch()` / `axios` directly in a page component
- Putting all form logic inside a Dialog without a composable
- Scattering API calls across template event handlers
- Duplicating the same business logic in multiple pages

---

## Unified Page Layout

All CRUD management pages MUST follow this vertical structure:

```
Page Container
  ├── Search Area         (Card: search form + query/reset buttons)
  ├── Toolbar             (Create button, import/export, column settings)
  ├── Batch Action Bar    (shown when rows selected)
  ├── Table Card          (data table + pagination)
  │     └── Actions Column (edit, delete, detail — fixed: 'right')
  └── Form Modal/Drawer   (OsModalForm: create/edit)
```

This structure is already implemented by `OsTablePage` — just use it.

**Search Area Control Width (搜索区控件宽度统一规范):**

All search area `a-input` and `a-select` MUST use uniform width `style="width: 180px"`.
Do NOT mix different widths (e.g. one input at 160px and one select at 100px) —
the visual inconsistency looks broken.

```html
<!-- ✅ Correct: all controls 180px, consistent -->
<a-input v-model:value="queryForm.name" placeholder="请输入名称" allow-clear style="width: 180px" />
<a-select v-model:value="queryForm.status" placeholder="全部" allow-clear style="width: 180px">...</a-select>

<!-- 🚫 Wrong: mixed widths, looks broken -->
<a-input v-model:value="queryForm.name" style="width: 160px" />
<a-select v-model:value="queryForm.status" style="width: 100px" />
```

**Search buttons** MUST include icons: `<SearchOutlined />查询` + `<ReloadOutlined />重置`.

**Dashboard pages** use: Card Grid + ECharts / Chart components.

**Custom pages** (Kanban, WBS, Gantt): maintain the same overall page shell
(Topbar from Layout + Breadcrumb + Content area), but content layout is custom.

---

## AI Page Development Principle

When developing a new page:

1. **Scan existing pages first** — find the most similar existing page
2. **Copy its structure** — same file layout, same component choices, same composable usage
3. **Modify ONLY business logic** — data model, API calls, column definitions, form fields
4. **Keep the UI identical** — same spacing, same button positions, same search layout
5. **Do NOT "improve" the design** — consistency beats individual optimization

A new CRUD page should look indistinguishable from existing CRUD pages
except for the data it displays.

---

## Design Token Quick Reference

**All values and their hex/px equivalents are maintained in [design-tokens.md](design-tokens.md).**
This section shows what tokens exist and when to use them.

### Color Tokens (use `var(--token)`, never hex)

| Token | Usage |
|-------|-------|
| `--brand` / `--brand-hover` / `--brand-active` | Primary actions, links, selected states |
| `--brand-light` | Primary light backgrounds |
| `--brand-dark` / `--brand-table` | Table header text and background |
| `--text-primary` / `--text-secondary` / `--text-tertiary` | Text hierarchy |
| `--text-placeholder` | Input/Select placeholder |
| `--border` / `--border-hover` | Component borders, focus rings |
| `--bg-page` | Page/shell background |
| `--success` / `--warning` / `--error` | Semantic status colors |
| `--success-bg` / `--warning-bg` / `--error-bg` | Semantic status backgrounds |
| `--neutral` / `--neutral-bg` | Neutral grey text and backgrounds |
| `--control-hover-bg` / `--control-active-bg` | Menu/Select hover and selected states |

### Font Size Tokens

| Token | Usage |
|-------|-------|
| 10px (fontSizeSM) | Ultra-small badges only |
| 12px (fontSizeSM) | Tag, Tooltip, Popover, code, filter labels, meta text |
| 13px | **Base font size**: Button text, Form label, Input, Select, Placeholder, Dropdown |
| 14px (fontSize) | Table body (`--table-body-font-size`), Menu, Page body, list item title |
| 15px (`--table-header-font-size`) | Table header text |
| 16px (fontSizeLG) | Dialog/Drawer title, Card title, Section heading, stat numbers |
| 20px | Stat icons, page-level heading |
| 28px | KPI stat numbers (large) |

### Font Weight Tokens

| Value | Usage |
|-------|-------|
| 400 | Body text, descriptions, placeholder, secondary info |
| 500 | Button, Form label, Tag, menu item, list item title, **action button text** |
| 600 | Table header, Card title, Dialog title, stats numbers, **emphasis** |

### Border Radius Tokens

| Token | Usage |
|-------|-------|
| `borderRadiusSM` (4px) | Checkbox, small Tag (antd theme token, no CSS variable) |
| `--radius-sm` / `borderRadius` (6px) | Button, Input, Select, Popover, Tooltip, Tag |
| `--radius` / `borderRadiusLG` (8px) | Card, Modal, Drawer, Dropdown, Table |

### Spacing Tokens (4px grid)

| Token | Usage |
|-------|-------|
| `--spacing-xs` (4px) | Icon gap, tight inline, filter pill gap |
| `--spacing-sm` (8px) | Form inline gap, search area gap, button group gap |
| `--spacing-md` (12px) | Section gap, card-table gap, stats strip gap |
| `--spacing-lg` (16px) | Form-item margin, card padding, grid gap, section gap |
| `--spacing-xl` (24px) | Modal body padding, large section spacing |
| `--spacing-2xl` (32px) | Page-level section gap |

### Control Height Tokens

| Token | Usage |
|-------|-------|
| `--control-height-sm` / `controlHeightSM` (28px) | Small controls |
| `--control-height` / `controlHeight` (32px) | Default controls |
| `--control-height-lg` / `controlHeightLG` (40px) | Large controls |

### Table-Specific CSS Variables

| Variable | Value | Defined In |
|----------|-------|-----------|
| `--table-header-font-size` | 15px | `style.css :root` |
| `--table-body-font-size` | 14px | `style.css :root` |
| `--table-font-sm` | 13px | `style.css :root` |

These are shared by **OsTablePage** (Ant Design a-table) and custom native tables.
Changing these variables updates both component types globally.

---

## Component Usage Patterns

### 1. CRUD Management Pages → `OsTablePage` + `useOsTablePage`

Any page with: search + table + pagination + CRUD operations.

```ts
// Standard pattern
import OsTablePage from '@/components/ucp-table-page/OsTablePage.vue'
import { useOsTablePage } from '@/composables/useOsTablePage'

const { loading, tableData, pagination, queryForm, handleQuery, handleReset, ... } =
  useOsTablePage({ fetchFn: getXxxList, defaultQuery: () => ({ ... }) })
```

**Built-in features**: Search area, advanced search, column settings, resizable columns,
batch selection, import/export, tag rendering, auto-index. Full API → [component-patterns.md](component-patterns.md).

### 2. Form Modal/Drawer → `OsModalForm` + `useOsModalForm`

Any form in a modal, drawer, or fullscreen container.

```vue
<OsModalForm :open="modalOpen" :loading="modalLoading" :title="modalTitle"
             :form-data="formData" @ok="handleOk" @cancel="closeModal">
  <template #formItems="{ formData }">
    <a-form-item label="名称" name="name">
      <a-input v-model:value="formData.name" />
    </a-form-item>
  </template>
</OsModalForm>
```

**Built-in features**: Three display modes (modal/drawer/fullscreen), resizable,
custom title bar with mode-switch, unified right-aligned footer.

### 3. User/Dept/Role/Org Selection

| Scenario | Component |
|----------|-----------|
| Multi-select, modal picker, tree picker, dept/role/org | **UserSelector** |
| Single-select dropdown for project members | **MemberSelect** |

**Rule: ALL user/dept selection MUST show avatar.** Both components support this.

See full API and decision tree → [component-patterns.md](component-patterns.md).

### 4. Business Detail/Form

业务详情与表单先检查当前 `src/components/` 和对应模块的 `components/`；共享表单容器复用 `OsModalForm`，保持现有权限、发布版本和保存语义。

### 5. Action Column Pattern (操作列)

All table action columns MUST follow this pattern:

```html
<div class="action-cell">
  <!-- 编辑：品牌紫色 #4338CA, font-weight: 500 -->
  <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500">
    <EditOutlined />编辑
  </a-button>
  <span class="action-sep">|</span>
  <!-- 删除：danger prop, font-weight: 500 -->
  <a-button type="link" danger style="padding: 0 6px; font-weight: 500">
    <DeleteOutlined />删除
  </a-button>
</div>
```

**Button Color Map:**
- 编辑 → `#4338CA` (brand purple) + `font-weight: 500`
- 复制 → `#059669` (green)
- 删除 → `danger` prop (red) + `font-weight: 500`
- 新建/新增 → `#D97706` (amber)
- 中性操作 → `#6B7280` (gray)

**Separator:** `<span class="action-sep">|</span>` with `color: #d1d5db; padding: 0 6px`

**Action column width:** 160px (standard), 200-280px (many operations)

See full patterns → [component-patterns.md](component-patterns.md)

### 6. Table Column Configuration

| Column Type | Recommended Width |
|------------|------------------|
| ID/Code | 70-80px |
| Priority/Status | 45-80px |
| Title/Name | 120-300px |
| Date | 85-170px |
| Assignee | 55px |
| Actions | 160px (standard) |

**Column Alignment Rules (by data type):**

| Data Type | Align | Examples | Reason |
|-----------|-------|----------|--------|
| **Long text** | `left` | 标题、名称、描述、审批建议 | Human eye reads left-to-right, best for scanning |
| **Short ID/Code** | `center` | 编号、编码、ID | Uniform short fields, centered looks cleaner |
| **Tag/Badge** | `center` | 状态、优先级、严重程度、类型 | Tags naturally look best centered |
| **Date/Time** | `center` | 创建时间、发起时间、结束时间 | Fixed-format short fields |
| **Person/Avatar** | `center` | 负责人、报告人、审批人 | Avatar+name combo centered is neat |
| **Number** | `center` | 完成率、用例数、耗时 | Numerical comparison, centered is acceptable |
| **Actions** | `center` | 操作列 | Button group always centered |

**Implementation:**
- Set `align` explicitly on every column (OsTablePage defaults to `center` if omitted)
- Use `align: 'left' as const` for text columns, `align: 'center' as const` for all others
- **Do NOT** use `:deep(.ant-table-thead > tr > th) { text-align: center; }` or `:deep(.ant-table-tbody > tr > td) { text-align: center; }` — these override per-column settings
- For resizable tables, keep `:deep(.la-resizable-th-content) { justify-content: center; }`
- **Action column must reserve sufficient width** (standard 160px, complex 200-280px) — otherwise horizontal scroll may leave white space on the right side when fixed columns cannot fill the viewport
- Always set `resizable` and `showColumnSettings` on OsTablePage
- Use `tagMap` for enum columns instead of custom rendering

**Horizontal Scroll (MUST for multi-column tables):**
- **All tables with total column width > container width MUST enable horizontal scrolling.**
- For **OsTablePage**: pass `:scroll="{ x: ... }"` — the prop is forwarded to `a-table`.
  - **首选 `x: 'max-content'`**，表格宽度仅由内容决定，不随容器拉伸，自然避免留白，小屏上自动出现横向滚动条。
  - Use `x: <total_width>` (px) only if a specific fixed width is required — sum all column widths + 60 (index) + padding.
  - ⚠️ **Do NOT omit `:scroll` on tables with action columns** — on small screens (<1280px) the action column will be squeezed and unusable without horizontal scrolling.
  - Reference: BPM module tables use `x: 1200~2160` depending on column count.
- For **custom native tables**: wrap the table in a container with `overflow-x: auto`.
  - Ensure no ancestor constrains it with `overflow: hidden`.
- For **raw `a-table`**: always set `:scroll="{ x: 'max-content' }"` or a fixed pixel value.
- **Verify on narrow viewports** (≤1280px) when adding/removing columns.

**⚠️ 防止滚动后右侧留白：**
- **核心矛盾**: `scroll.x` 设固定值 < 容器宽度时，表格被拉伸至容器宽度，但列宽总和不够 → 操作列右侧出现空白。
- **推荐方案**: 优先用 `x: 'max-content'`，表格宽度仅由内容决定，不随容器拉伸，自然避免留白。
- **固定 px 方案**: 如必须用固定 px，确保 `sum(列宽) ≥ scroll.x`，并把操作列设为 `fixed: 'right'` 让它始终贴右。
- **操作列宽度**: 设 `fixed: 'right'` 后，操作列不再参与弹性分配，标准 160px 足够；如需更多操作按钮则 200-280px。
- **OsTablePage 已有固定列白边修复**: `.ant-table-cell-fix-right-last { right: 0 !important; box-shadow: none !important; }`。

### 7. Loading States (数据加载 Loading)

All data-fetching states MUST provide visible feedback. Use the correct loading pattern for each scenario.

#### Loading Mode Decision Tree

```
Data is loading?
├── Full page / large content area?
│   ├── Table list (OsTablePage / custom native table)?
│   │   └── Use :loading prop — built-in Spin overlay
│   ├── Custom table or content block?
│   │   └── Wrap with <a-spin :spinning="loading" :delay="300">
│   └── Detail / Dashboard page?
│       └── Use <a-skeleton> with paragraph rows
├── Form submit / Modal confirm?
│   └── Use :loading / :confirm-loading prop on the button
└── Inline button action (export, import, delete)?
    └── Use :loading prop on the button, disable other buttons
```

#### Pattern 1: Table Loading (OsTablePage)

OsTablePage has built-in loading support via the `:loading` prop from `useOsTablePage`:

```ts
const { loading, tableData, handleQuery } = useOsTablePage({ fetchFn: getXxxList, ... })
```

```html
<OsTablePage :loading="loading" :data-source="tableData" ... />
```

**No extra a-spin wrapping needed** — OsTablePage internally passes `loading` to Ant Design's `a-table`.

#### Pattern 2: Custom Table Loading

For a hand-written table (no built-in loading), wrap the content with `a-spin` internally and drive it with a `pageLoading` ref:

```ts
const pageLoading = ref(true) // starts as true, shows spinner immediately
async function loadData() {
  pageLoading.value = true
  try {
    // fetch data...
  } finally {
    pageLoading.value = false
  }
}
```

```html
<CustomTable :loading="pageLoading" :items="items" />
```

Internal implementation pattern:
```html
<a-spin :spinning="loading" tip="加载中..." :delay="300">
  <!-- table content -->
  <template v-if="!loading && items.length === 0">
    <span class="empty-text">暂无数据</span>
  </template>
</a-spin>
```

#### Pattern 3: a-spin for Arbitrary Content

Use `a-spin` to wrap any content block that loads asynchronously:

```html
<a-spin :spinning="loading" tip="加载中..." :delay="300">
  <div class="content-area">
    <!-- loaded content -->
  </div>
  <!-- Empty state: show after loading completes -->
  <a-empty v-if="!loading && items.length === 0" description="暂无数据" />
</a-spin>
```

**Key parameters:**
- `:spinning` — `ref<boolean>`, controls spinner visibility
- `tip` — Chinese text, always use `"加载中..."`
- `:delay` — `300` (ms), prevents spinner flash on fast loads (<300ms requests)

#### Pattern 4: Skeleton Loading (Detail Pages)

For detail/dashboard pages where content structure is known, use skeleton placeholders:

```html
<!-- Skeleton: visible while loading -->
<template v-if="loading">
  <a-card><a-skeleton active :paragraph="{ rows: 6 }" /></a-card>
</template>

<!-- Content: visible after loading -->
<template v-else>
  <a-card title="详情">
    <!-- actual content -->
  </a-card>
</template>
```

**Skeleton row guidelines:**
- Simple card/form: `:paragraph="{ rows: 3 }"`
- Standard detail: `:paragraph="{ rows: 6 }"`
- Complex content: `:paragraph="{ rows: 10 }"`

#### Pattern 5: Form Submit Loading

Form submit buttons use `:loading` from `useOsModalForm`:

```ts
const { modalLoading } = useOsModalForm({ createFn, updateFn, ... })
```

```html
<OsModalForm :loading="modalLoading" ... >
  <!-- form items -->
</OsModalForm>
```

Or manually on a button:
```html
<a-button type="primary" :loading="submitting" @click="handleSubmit">提交</a-button>
```

#### Pattern 6: Inline Button Loading

For standalone action buttons (export, sync, refresh):

```ts
const exportLoading = ref(false)
async function handleExport() {
  exportLoading.value = true
  try {
    // export logic...
  } finally {
    exportLoading.value = false
  }
}
```

```html
<button class="btn btn-default" :disabled="exportLoading" @click="handleExport">
  {{ exportLoading ? '导出中...' : '导出' }}
</button>
```

#### Loading Rules Summary

| Rule | Detail |
|------|--------|
| **Always show loading** | No data fetch without visual feedback |
| **`:delay="300"`** | Prevents spinner flash on fast loads |
| **tip="加载中..."** | Unified Chinese text for all spinners |
| **Empty after load** | Show empty/"暂无数据" only when `!loading && empty` |
| **No duplicate spinners** | OsTablePage already handles loading — don't wrap with extra a-spin |
| **Skeleton for detail** | Detail pages use skeleton, tables use spinner |
| **Button loading disables** | Loading buttons must be disabled to prevent double-submit |

---

## CSS Coding Standards

### ✅ Correct

```css
/* Use CSS variables for ALL design values */
color: var(--text-primary);
background: var(--brand-light);
border: 1px solid var(--border);
border-radius: var(--radius-sm);  /* --radius-sm = 6px */
padding: var(--spacing-lg);
font-size: 13px;  /* only the 6 standard sizes from the token table */

/* Use Ant Design semantic props */
<a-tag color="success">Active</a-tag>
<a-button type="primary">Submit</a-button>
<a-button danger>Delete</a-button>
```

### 🚫 Wrong

```css
/* Hardcoded hex colors */
color: #1f2937;
background: #f5f5f5;

/* Values not from Design Tokens */
font-size: 15px;
border-radius: 10px;
margin: 14px;
height: 36px;
font-weight: 650;

/* !important without comment justification */
font-size: 13px !important;

/* Inline styles for layout/spacing/color */
<div style="margin-top: 10px; color: #6b7280;">
```

**All sizing, spacing, color values MUST come from the Token tables above.**
If you need a value not listed → extend the Token first, then use the new Token.

### Inline Style Exception

Inline `style` ONLY allowed for:
- Dynamic computed values (e.g., `:style="{ width: progress + '%' }"`)
- Must add a `<!-- reason: ... -->` comment

---

## CSS Naming Convention

Use **prefix-based naming** to avoid collisions and improve scanability:

| Prefix | Scope | Example |
|--------|-------|---------|
| `page-` | Page-level layout | `.page-container`, `.page-header` |
| `search-` | Search area | `.search-form`, `.search-advanced` |
| `toolbar-` | Toolbar area | `.toolbar-actions`, `.toolbar-filter` |
| `table-` | Table wrapper | `.table-card`, `.table-empty` |
| `card-` | Card component | `.card-header`, `.card-body` |
| `dialog-` | Dialog/Modal | `.dialog-form`, `.dialog-footer` |
| `os-` | Os-prefix public components | `.os-table-page`, `.os-modal-form` |

**Avoid**: `.main`, `.box`, `.content`, `.wrapper`, `.container`, `.item`
— these are too generic and cause collisions.

Prefer scoped `<style scoped>` over global styles.

---

## Theming & Dark Mode Readiness

All colors MUST use CSS variables. Hardcoding blocks theme switching.

```css
/* Current + future-ready pattern */
:root {
  --bg-page: #F5F6FA;
  --text-primary: #1F2937;
}
[data-theme="dark"] {
  --bg-page: #1a1a2e;
  --text-primary: #e5e7eb;
}
```

---

## Design System Evolution

When a new UI pattern emerges (Timeline, Gantt chart, Kanban variant, new widget):

1. **Do NOT** copy-paste code between pages.
2. **Extract** the reusable part into `src/components/`.
3. **Document** the new component in [component-patterns.md](component-patterns.md).
4. **Add** any new Design Tokens to [design-tokens.md](design-tokens.md).
5. **Update** this SKILL.md if the pattern is reusable across modules.

This keeps the Design System alive and prevents divergence.

---

## Skill Maintenance

This Skill must stay in sync with the codebase. Update it after:

| Trigger | Action |
|---------|--------|
| New public component created | Add to [component-patterns.md](component-patterns.md) |
| New Design Token added | Add to [design-tokens.md](design-tokens.md) |
| Token value changed | Update [design-tokens.md](design-tokens.md) only (not SKILL.md) |
| New page pattern established | Add to Component Usage Patterns above |
| Theme system upgraded | Update Theming section |
| Major UI refactor completed | Review all sections for accuracy |

**Rule: Skill updates are part of the Definition of Done for any UI infrastructure change.**

---

## UI Code Review Checklist

When reviewing any UI code, verify ALL of the following:

- [ ] No hardcoded hex/rgb colors (use CSS variables or antd-theme tokens)
- [ ] All font-size values come from the 8-tier token table (10/12/13/14/15/16/20/28)
- [ ] All border-radius values come from the 3-tier token table (4/6/8)
- [ ] All spacing values (margin/padding/gap) are on the 4px grid
- [ ] All control heights come from the 3-tier token table (28/32/40)
- [ ] All font-weight values are 400, 500, or 600
- [ ] No `!important` without `/* reason: ... */` comment
- [ ] No inline styles for colors/spacing/layout
- [ ] CRUD pages use `OsTablePage` + `useOsTablePage`
- [ ] Form modals/drawers use `OsModalForm`
- [ ] User/dept selection uses `UserSelector` or `MemberSelect` with avatar
- [ ] New components checked against existing components (no duplicates)
- [ ] Page follows the Unified Page Layout structure
- [ ] Architecture follows View → Composable → API → Component layers
- [ ] CSS class names use prefix convention (not `.main` / `.box` / `.wrapper`)
- [ ] Scoped styles preferred over global styles
- [ ] `:deep()` nesting ≤ 3 levels
- [ ] **Action column uses `a-button type="link"` + icon, not `type="primary"`**
- [ ] **Action buttons separated by `<span class="action-sep">|</span>`**
- [ ] **Edit button color `#4338CA`, delete uses `danger` prop**
- [ ] **Action column link button icon font-size is 13px**
- [ ] **Table columns use data-type-aware alignment: text→left, tags/dates/actions→center**
- [ ] **Search area: query button `type="primary"`, reset button default**
- [ ] **Search area: `<SearchOutlined />查询` + `<ReloadOutlined />重置`**
- [ ] **Search area: all `a-input` and `a-select` use uniform `style="width: 180px"` (no mixed widths)**
- [ ] **Tables with action columns MUST set `:scroll="{ x: 'max-content' }"` (never omit on action-column tables)**
- [ ] **All data-fetching code paths have loading state (no silent loading)**
- [ ] **`a-spin` uses `:delay="300"` and `tip="加载中..."`**
- [ ] **Empty state only shown when `!loading && data.length === 0`**
- [ ] **OsTablePage uses `:loading` prop, NOT wrapped with extra a-spin**
- [ ] **Detail pages use skeleton (`a-skeleton`), not spinner**
- [ ] **Loading buttons are disabled with `:disabled` or `:loading` to prevent double-submit**

---

## Additional Resources

- [design-tokens.md](design-tokens.md) — Complete token table with all values
- [component-patterns.md](component-patterns.md) — Full component API reference
- [examples.md](examples.md) — Good vs bad code examples
