# 前端编码细则（os-front）

配套主技能见 [../SKILL.md](../SKILL.md)。UI 视觉/组件/CSS 细则读 `frontend-ui-standard` 技能；表格页规范见 [table-pages.md](table-pages.md)。本页 `src/` 路径相对于仓库内 `os-front/`。

## 技术栈与工程约束

Vue 3 + TypeScript + Ant Design Vue 4 + Vite；pnpm 10.34.1（`packageManager` 固定）、Node 24.15.0（`.node-version` + `.npmrc engine-strict`）。路径别名 `@/` → `src/`。

## 目录职责

| 目录 | 职责 |
|---|---|
| `src/views/<module>/` | 页面；nocode 页面在 `src/views/nocode/`（object、application、table、record-history、task-center） |
| `src/components/` | 共享公共组件（PascalCase）：`os-table-page/OsTablePage.vue`、`os-modal-form/OsModalForm.vue`、`UserSelector/`、`MemberSelect.vue` 等 |
| `src/api/<module>/` | HTTP 接口工厂（如 `src/api/nocode/object.ts`） |
| `src/types/<module>/` | DTO 与类型定义 |
| `src/nocode/` | nocode 领域逻辑（不放 .vue；`*.test.ts` 就近） |
| `src/composables/` | `useXxx.ts` 组合式函数（`useOsTablePage`、`useOsModalForm`…） |
| `src/stores/` | Pinia setup store（`user`、`navigationTabs`、`realtime`…） |
| `src/utils/` | `request.ts`（axios 封装）与其他工具 |
| `src/router/`、`src/layouts/`、`src/theme/`、`src/styles/` | 路由、布局、主题 token、全局样式 |

## 组件与命名

- 单 `<script setup lang="ts">`；props 用类型式 `defineProps<{...}>()`；共享组件配 `withDefaults`；事件用 `defineEmits`。
- 命名：共享组件文件 PascalCase；页面目录/文件 kebab-case（`views/nocode/task-center/index.vue`）；composable `useXxx.ts`；测试 `*.test.ts` 与源码同目录。
- 中文注释：只为非显而易见的“为什么”写一行（兼容分支、异步时序、权限来源），不复述代码。

## API 层与平台注入

- 工厂模式（`src/api/nocode/object.ts`）：

```ts
export function createObjectApi(client: NocodeHttpClient) {
  return {
    page: (params: ObjectQuery) => client.get<ObjectPage>('/nocode/object/page', { params }),
    // ...
  }
}
export type ObjectApi = ReturnType<typeof createObjectApi>
```

- 客户端由 `src/utils/request.ts` 提供（返回 `Promise<T>`，非 AxiosResponse），在 `main.ts` 统一装配；页面通过 `const platform = useNocodePlatform()` 获取 `platform.dataCenter` 等接口，不直接 import axios 散建请求。
- 反馈：操作结果用 `message.success/error`（ant-design-vue）；请求失败统一走 `request.ts` 的 `notification.error`（可关闭，避免长期遮挡操作）。

## 页面模式（列表页）

- 列表页优先 `OsTablePage` + `useOsTablePage`（搜索卡片、列设置、列宽拖拽、固定操作列、分页约定见 [table-pages.md](table-pages.md)）；表单容器优先 `OsModalForm` + `useOsModalForm`。
- 异步加载防乱序：用请求序号守卫（`if (number === requestNumber)`）或 latest-wins；错误经 `errorMessage(cause)` 转中文提示。
- 权限显隐：`computed(() => platform.hasPermission('nocode:object:query'))`；服务端校验是最终边界。

## 测试

- 单测：vitest；`vitest.nocode.config.ts` 覆盖 `src/nocode/**/*.test.ts` 与 `tests/unit/nocode-transport.test.ts`。
- 断言真实组件/纯函数行为，不断言源码字符串；模拟请求替换 `request.defaults.adapter`，模块边界用 `vi.mock`。
- 回归用例应先在旧实现上失败、再在新实现通过，避免“假通过”。
- E2E：`node tools/nocode-e2e/verify.mjs`（真实浏览器；先确保 5173/8080 可达；证据写入 `.work/nocode-e2e/<时间戳>/`）。

## 格式与检查

- Prettier（`.prettierrc`）：无分号、单引号、2 空格、无尾逗号、printWidth 120、arrowParens avoid。
- `pnpm run check:nocode` = `typecheck:nocode`（vue-tsc -p tsconfig.nocode.json）+ `test:nocode` + `lint:check:nocode`（ESLint）+ `format:check:nocode`（Prettier）；nocode 范围为 `src/api/nocode`、`src/types/nocode`、`src/nocode`、`src/views/nocode`、`tests/unit/nocode-transport.test.ts`。
- 全工程范围另有 `lint:check`、`format:check`、`typecheck:bpm` 等；按改动范围执行。
- `tsconfig.nocode.json` 开启 `strict`：nocode 新代码保持类型完整；ESLint 对 `any`、`console` 等为警告级别，仍应尽量避免。
- ESLint 配置为 antfu preset（`eslint.config.js`）+ nocode 范围覆盖（`eslint.nocode.config.js`，对齐 Prettier、检查 TS/Vue 正确性与未使用代码）。
