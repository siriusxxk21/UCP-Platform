# Design Tokens — Complete Reference

This document lists ALL Design Tokens used in this project. Tokens are defined in two places:

1. **`src/style.css`** — CSS custom properties (`:root` variables), used in both `<style>` and `<style scoped>` blocks
2. **`src/theme/antd-theme.ts`** — Ant Design Vue 4 `ThemeConfig`, injected via `a-config-provider` in `App.vue`

When both define the same concept, keep them in sync.

---

## Color Tokens

### Brand / Primary

| CSS Variable | antd-theme.ts Token | Value | Usage |
|-------------|---------------------|-------|-------|
| `var(--brand)` | `colorPrimary` | `#4338CA` | Primary actions, links, selected states |
| `var(--brand-hover)` | `colorPrimaryHover` | `#5B51D8` | Primary hover state |
| `var(--brand-active)` | `colorPrimaryActive` | `#4C42B8` | Primary active/pressed state |
| `var(--brand-dark)` | — | `#1E1B4B` | Table header text, dark backgrounds |
| `var(--brand-light)` | — | `#EEF2FF` | Primary light backgrounds (badge bg, selected row) |
| `var(--brand-table)` | — | `#F5F3FF` | Table header background |

### Functional / Status

| CSS Variable | antd-theme.ts Token | Value | Usage |
|-------------|---------------------|-------|-------|
| `var(--success)` | `colorSuccess` | `#10B981` | Success text, icons, borders |
| `var(--success-bg)` | — | `#ECFDF5` | Success background |
| `var(--warning)` | `colorWarning` | `#F59E0B` | Warning text, icons, borders |
| `var(--warning-bg)` | — | `#FFFBEB` | Warning background |
| `var(--error)` | `colorError` | `#EF4444` | Error text, icons, borders, danger buttons |
| `var(--error-bg)` | — | `#FEF2F2` | Error background |
| `var(--neutral)` | — | `#6B7280` | Neutral/grey text |
| `var(--neutral-bg)` | — | `#F3F4F6` | Neutral grey background, segmented control bg |

### Text

| CSS Variable | antd-theme.ts Token | Value | Usage |
|-------------|---------------------|-------|-------|
| `var(--text-primary)` | `colorText` | `#1F2937` | Primary body text |
| `var(--text-secondary)` | `colorTextSecondary` | `#6B7280` | Secondary text, form labels |
| `var(--text-tertiary)` | `colorTextTertiary` | `#9CA3AF` | Tertiary text, arrow icons, disabled text |
| `var(--text-placeholder)` | `colorTextPlaceholder` | `#9CA3AF` | Input/Select placeholder text |

### Border

| CSS Variable | antd-theme.ts Token | Value | Usage |
|-------------|---------------------|-------|-------|
| `var(--border)` | `colorBorder` / `colorBorderSecondary` | `#E5E7EB` | Default border for Input, Select, Card, Table |
| `var(--border-hover)` | — | `#C7D2FE` | Border color on hover (input/select focus ring) |

### Background

| CSS Variable | antd-theme.ts Token | Value | Usage |
|-------------|---------------------|-------|-------|
| `var(--bg-page)` | `colorBgLayout` | `#F5F6FA` | Page/shell background |
| `var(--color-bg-container)` | `colorBgContainer` | `#FFFFFF` | Card, Modal, Dropdown container background |
| — | `colorBgElevated` | `#FFFFFF` | Elevated surfaces (dropdown menus) |
| `var(--control-hover-bg)` | `controlItemBgHover` | `#F5F3FF` | Hover bg for select items, menu items |
| `var(--control-active-bg)` | `controlItemBgActive` | `#EEF2FF` | Selected bg for select items, menu items |

### Link

| antd-theme.ts Token | Value | Usage |
|---------------------|-------|-------|
| `colorLink` | `#1677ff` | Link color (antd default, kept for link buttons) |
| `colorLinkHover` | `#1677ff` | Link hover color |
| `colorLinkActive` | `#1677ff` | Link active color |

### Outline

| antd-theme.ts Token | Value | Usage |
|---------------------|-------|-------|
| `controlOutline` | `rgba(67, 56, 202, 0.12)` | Focus ring color for Input/Select |

### Operation Button Colors (Action Column)

表格操作列按钮采用「文字按钮 + 图标」模式，不同操作使用不同颜色区分：

| 操作 | Color | Code Pattern |
|------|-------|-------------|
| 编辑 (Edit) | `#4338CA` | `<a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500">` |
| 复制 (Copy) | `#059669` | `<a-button type="link" style="color: #059669; padding: 0 6px">` |
| 删除 (Delete) | danger (red) | `<a-button type="link" danger style="padding: 0 6px; font-weight: 500">` |
| 新建工作项 | `#D97706` | `<a-button type="link" style="color: #D97706; padding: 0 6px">` |
| 重置密码等中性操作 | `#6B7280` | `<a-button type="link" style="color: #6b7280; padding: 0 6px">` |

**分隔符规范**:
- 使用 `|` 字符作为分隔符，颜色 `#d1d5db`，padding `0 6px`，user-select `none`
- 分隔符包裹在 `<span class="action-sep">|</span>` 中

**操作列容器规范**:
```html
<div class="action-cell">
  <a-button type="link" style="color: #4338CA; padding: 0 6px; font-weight: 500"><EditOutlined />编辑</a-button>
  <span class="action-sep">|</span>
  <a-button type="link" danger style="padding: 0 6px; font-weight: 500"><DeleteOutlined />删除</a-button>
</div>
```

```css
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
```

### Status / Type Color Mapping

统计指标颜色（workspace / 模块统计卡片）：

| Metric | Color |
|--------|-------|
| 待处理 | `#D97706` |
| 今日到期 | `#F59E0B` |
| 已逾期 | `#EF4444` |
| 项目数 | `#4338CA` |

### 统计卡片多色变体 (Stat Card Color Variants)

页面顶部的统计指标卡片使用不同颜色区分不同指标类型。每种卡片由**数值色** + **图标色** + **图标淡底色**组成：

| 变体 | 数值/图标色 | 图标背景色 | 适用场景 |
|------|-----------|-----------|---------|
| **Purple（紫色）** | `var(--brand)` / `#4338CA` | `rgba(67, 56, 202, 0.08)` | 总数、主指标、项目数 |
| **Orange（橙色）** | `#D97706` | `rgba(217, 119, 6, 0.08)` | P0/高优、待处理、风险项 |
| **Blue（蓝色）** | `#1677ff` | `rgba(22, 119, 255, 0.08)` | P1/P2/中优、进行中、链接类型 |
| **Green（绿色）** | `#059669` | `rgba(5, 150, 105, 0.08)` | 覆盖率、成功率、已完成 |

**CSS 类名规范**：`.stat-value-{variant}` 控制数值颜色，`.stat-icon-{variant}` 控制图标颜色和背景。

```css
.stat-value-purple { color: var(--brand); }
.stat-icon-purple { color: var(--brand); background: rgba(67, 56, 202, 0.08); }

.stat-value-orange { color: #D97706; }
.stat-icon-orange { color: #D97706; background: rgba(217, 119, 6, 0.08); }

.stat-value-blue { color: #1677ff; }
.stat-icon-blue { color: #1677ff; background: rgba(22, 119, 255, 0.08); }

.stat-value-green { color: #059669; }
.stat-icon-green { color: #059669; background: rgba(5, 150, 105, 0.08); }
```

---

## Typography Tokens

### Font Size (8-tier system)

| CSS Variable | antd-theme.ts Token | Value | Where to Use |
|-------------|---------------------|-------|-------------|
| *(not defined)* | `fontSizeSM` | **10px** | Reserve for ultra-small badges only |
| *(not defined)* | `fontSizeSM` | **12px** | Tag, Tooltip, Popover, filter label, meta text |
| *(not defined)* | — | **13px** | **Base font size**: Button text, Form label, Input, Select, Placeholder, Dropdown items, table body |
| *(not defined)* | `fontSize` | **14px** | Table body text (`--table-body-font-size`), Menu items, Page body text, list item title, card body |
| `--table-header-font-size` | — | **15px** | Table header text (`ant-table-thead > tr > th`) — CSS variable defined in `style.css :root` |
| *(not defined)* | `fontSizeLG` | **16px** | Modal/Drawer title, Card title, Section heading, stat bar number |
| *(not defined)* | — | **20px** | Medium stat icons, page-level heading |
| *(not defined)* | — | **28px** | Large stat numbers, KPI cards |

**All font sizes must come from this table. If a design requires a size not listed → add a new tier to the token system first, then use the new token. Never use ad-hoc values.**

**Table-specific CSS variables** (defined in `style.css :root`, shared by OsTablePage and custom tables):
- `--table-header-font-size: 15px` — table header cells
- `--table-body-font-size: 14px` — table body cells
- `--table-font-sm: 13px` — table compact mode / metadata

### Font Weight (3-tier system)

| Value | antd-theme.ts Token | Where to Use |
|-------|---------------------|-------------|
| **400** | *(default)* | Regular body text, table body, descriptions, placeholder |
| **500** | *(default)* | Buttons, form labels, tags, menu items, medium emphasis |
| **600** | *(default)* | Table headers, card titles, drawer/modal titles, stats numbers |

**All font-weights must come from this table. 400 / 500 / 600 only.**

### Line Height

| Value | antd-theme.ts Token | Where to Use |
|-------|---------------------|-------------|
| `1` | — | Buttons (single line, vertically centered) |
| `1.4` | — | Dropdown items, compact lists |
| `1.5` | `lineHeight` | Body text, form items, table cells (default) |
| `1.7` | — | Long-form text, readme/docs rendering |

### Font Family

| CSS | antd-theme.ts | Value |
|-----|--------------|-------|
| `font-family` in `html, body` | `fontFamily` | System font stack (PingFang SC, Microsoft YaHei, etc.) |

The `style.css` body definition and `antd-theme.ts` `fontFamily` should stay in sync.

---

## Border Radius Tokens (3-tier system)

| CSS Variable | antd-theme.ts Token | Value | Where to Use |
|-------------|---------------------|-------|-------------|
| — | `borderRadiusSM` | **4px** | Checkbox, small Tag, small internal elements |
| `var(--radius-sm)` | `borderRadius` | **6px** | Button, Input, Select, DatePicker, Popover, Tooltip, Tag |
| `var(--radius)` | `borderRadiusLG` | **8px** | Card, Modal, Drawer, Dropdown, Table container |

**All border-radius values must come from this table. If a design requires a value not listed → extend the token first.**

---

## Spacing Tokens (4px grid)

| CSS Variable | Value | Where to Use |
|-------------|-------|-------------|
| `--spacing-xs` | **4px** | Icon gap, tight inline spacing, segmented control padding |
| `--spacing-sm` | **8px** | Form inline gap, button group gap, search area item gap, toolbar gap |
| `--spacing-md` | **12px** | Table-container gap, saved-search tag gap, advanced-search margin |
| `--spacing-lg` | **16px** | Form-item bottom margin, card body gap/padding, grid gap, section gap |
| `--spacing-xl` | **24px** | Modal body padding, large section spacing |
| `--spacing-2xl` | **32px** | Page-level section bottom margin, large gaps |

**All spacing values (margin, padding, gap) must be on the 4px grid. Values outside this grid require extending the token system first.**

---

## Control Height Tokens (3-tier system)

| CSS Variable | antd-theme.ts Token | Value | Where to Use |
|-------------|---------------------|-------|-------------|
| `var(--control-height-sm)` | `controlHeightSM` | **28px** | Small Input, small Select (.ant-select-sm), small Button |
| `var(--control-height)` | `controlHeight` | **32px** | Default Input, Select, DatePicker, Button |
| `var(--control-height-lg)` | `controlHeightLG` | **40px** | Large Input, large Select (.ant-select-lg), large Button |

**All control heights must come from this table. Use Ant Design size props when possible (`size="small"` / default / `size="large"`).**

---

## Shadow Tokens

| CSS Variable | antd-theme.ts Token | Value | Where to Use |
|-------------|---------------------|-------|-------------|
| `var(--shadow-sm)` | `boxShadow` | `0 1px 3px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)` | Card default, subtle elevation |
| `var(--shadow-md)` | `boxShadowSecondary` | `0 4px 12px rgba(0,0,0,0.06)` | Card hover, hover states |
| `var(--shadow-popup)` | — | `0 10px 28px rgba(15,23,42,0.10), 0 4px 10px rgba(15,23,42,0.06)` | Dropdown, Select popup, Popover |

---

## Animation / Transition Tokens

| Value | Where to Use | Defined In |
|-------|-------------|-----------|
| `0.15s ease` | Quick micro-interactions (hover on list items) | OsTablePage |
| `0.2s ease` | Standard transitions (button hover, card hover, border-color) | style.css (global) |
| `0.25s ease` | Slightly slower (login form input focus) | login page |
| `0.3s ease` | Slower transitions (login button) | login page |

**Standard: Use `0.2s ease` for most transitions. Only use other values with explicit justification.**

---

## Per-Component antd-theme.ts Overrides

These are defined in `src/theme/antd-theme.ts` under the `components` key:

| Component | Token | Value | Purpose |
|-----------|-------|-------|---------|
| Card | `borderRadiusLG` | `8` | Card corner radius |
| Card | `paddingLG` | `24` | Card body padding |
| Card | `colorBorder` | `#E5E7EB` | Card border color |
| Button | `borderRadius` | `6` | Button corner radius |
| Button | `controlHeight` | `32` | Button default height |
| Button | `controlHeightLG` | `40` | Large button height |
| Button | `controlHeightSM` | `24` | Small button height |
| Button | `colorPrimaryHover` | `#5B51D8` | Primary button hover |
| Button | `colorLinkHover` | `#4338CA` | Link button hover |
| Input | `borderRadius` | `6` | Input corner radius |
| Input | `controlHeight` | `32` | Input default height |
| Input | `colorBorder` | `#E5E7EB` | Input border color |
| Select | `borderRadius` | `6` | Select corner radius |
| Select | `controlHeight` | `32` | Select default height |
| Table | `borderRadiusLG` | `8` | Table corner radius |
| Menu | `colorItemBgHover` | `rgba(67,56,202,0.06)` | Menu item hover bg |
| Menu | `colorItemBgSelected` | `rgba(67,56,202,0.1)` | Menu item selected bg |
| Menu | `colorItemTextSelected` | `#4338CA` | Menu item selected text |
| Modal | `borderRadiusLG` | `8` | Modal corner radius |
| Tag | `borderRadiusSM` | `4` | Tag corner radius (overridden to 12px in style.css) |

仪表板指标值使用 `--font-size-report-metric: 28px`，在全局 style.css 定义；卡片容器复用 `--color-bg-container`。
