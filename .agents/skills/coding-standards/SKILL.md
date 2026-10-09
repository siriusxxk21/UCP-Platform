---
name: coding-standards
description: ucp-platform 正式开发工程（ucp-server 后端 Java 21/Spring Boot/MyBatis-Plus，ucp-front 前端 Vue 3/TypeScript/Ant Design Vue）的编码规范、质量门禁与交付检查。新增或修改后端 Java、前端 Vue/TS 代码、SQL 迁移、测试，或做重构、修 bug、代码审查前先读本技能：分层与分包、复用底座、SQL 只放 mapper XML、@Resource 注入、BaseDO、领域枚举与错误码、中文注释、目录命名、OsTablePage/OsModalForm 复用、Flyway VNNN 迁移、格式与检查命令（format.mjs、check-quality.mjs、check:nocode、check-migrations.mjs）。触发词：编码、编码规范、代码规范、Java、Spring、MyBatis、Vue、TypeScript、迁移、格式化、重构、code review。UI 视觉细节另读 frontend-ui-standard。
---

# ucp-platform 编码规范

适用 `ucp-server`（后端）与 `ucp-front`（前端）的正式开发。本技能是写代码时的操作摘要；规则全文见 [项目开发约定](references/project-conventions.md)，构建、迁移及检查命令见本技能与配套 references。所有工程路径以仓库根目录为基准。

不适用于 HTML 原型与设计稿。UI 视觉、组件、CSS 细节优先读 `frontend-ui-standard`。

## 权威顺序

1. 用户当前指令
2. [项目开发约定](references/project-conventions.md)
3. 本技能及 references/（编码规则、工具命令与专项细则）
4. 当前源码、公共组件及工具实际实现（核对能力与 API）

## 五条铁律

1. **先复用底座，不建第二套。** 账号/组织/角色、权限、菜单路由、HTTP 客户端、页面组件（表格 `OsTablePage`、表单 `OsModalForm`）、流程、文件、消息、缓存、调度先查现有入口，记录“底座入口、缺口、最小扩展”。入口清单见 [底座复用](references/platform-reuse.md)。
2. **SQL 只在 mapper XML。** 唯一落点 `ucp-server/ucp-nocode/*/src/main/resources/mapper/**/*.xml`；禁止 Java 注解 SQL（@Select/@Insert/@Update/@Delete）与 SQL Provider（`node ucp-nocode/check-quality.mjs` 强制）。Java 只保留参数绑定、字段白名单、标识符校验。
3. **不改已执行的迁移。** `sql/postgresql/migrations/VNNN__*.sql` 一旦执行，文件名、字节、摘要锁定（`migrations.lock.json` 校验和）；修复必须追加新版本号；不重导出 public.sql 作为日常增量。
4. **兼容性红线。** 重构/整理不得改变 HTTP 路径与方法、业务参数与默认值、响应与错误、权限码、版本语义和存量 JSON；不批量重命名 JSON 字段；不新增第二套错误码体系或全局异常处理器。
5. **交付前真实运行验证。** 在当前开发环境（前端 5173 / 后端 8080，直接使用，不用沙箱）实际验证改动相关行为与失败路径；不沿用旧通过记录冒充新结果；只操作可准确识别的夹具，保留用户已有数据与未提交修改。

## 后端速查（ucp-server）

聚合模块 `ucp-nocode`，子模块 api / metadata / schema / application / report / work / runtime / workflow / web / tools。依赖方向单向：api ← metadata ← schema/application ← runtime ← workflow ← web，无反向依赖。

| 主题 | 必须遵守 |
|---|---|
| 分层 | Controller（参数、身份、权限入口、统一返回）→ Service 接口+Impl（业务规则、事务、跨模块编排）→ Mapper/DO（持久化）；禁止 Controller 直调 Mapper；非 Web 模块不得 import controller/web 包 |
| 分包 | Controller 统一在 `ucp-nocode-web`；业务模块为 `service/<业务>` + `dal/dataobject`、`dal/mapper`；ServiceImpl 必须有同包接口；禁止根级 `service`/`service.impl` 包；包路径与模块目录一致 |
| 注入 | 统一 `jakarta.annotation.Resource` 字段注入；业务依赖不用 `private final` 构造注入；装配后初始化（事务模板、JSON 读取器）用 `@PostConstruct` |
| DO | 继承 `BaseDO`，审计字段 creator/create_time/updater/update_time/deleted；自定义 SQL 同样维护审计字段与逻辑删除条件 |
| 枚举 | 固定业务取值定义在 `ucp-nocode-api` 领域枚举；代码用枚举判断；持久化稳定编码，禁止序号 |
| 错误 | `import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*` 抛 `invalid()/notFound()/duplicate()/conflict()/...`；提示用中文；不改全局异常处理器 |
| Controller | 每个 Mapping 配 `@Tag` + `@Operation`；权限走 `@PreAuthorize`（类级 `isAuthenticated()` + 方法级 `@nocodeAccess.*()`）；返回底座 `Result<T>`；严格接参沿用 `StrictRequestDecoder`（`requests.design(body, X.class)`），保持原错误行为 |
| 命令/DTO | 跨模块契约用 `ucp-nocode-api` 的 record；固定结构用显式类型，动态值才用 Map |
| 局部变量 | 禁止使用 Java `var`；局部变量必须声明明确类型，避免掩盖领域、事务和数据边界 |
| 注释 | 中文；类/公开契约/关键规则必写；说明事务边界、并发保护、字段删除语义、权限来源、特殊兼容原因；不写复述代码的注释 |
| 格式 | `node ucp-nocode/format.mjs`（`--check` 只检）；google-java-format 1.24.0 `--aosp`，4 空格；POM/XML 清晰缩进 |

### 业务模块分层落点（Controller / Service / ServiceImpl / Mapper）

Controller 不在业务模块里，统一在 `ucp-nocode-web`；业务模块（runtime / metadata / application / schema / work / workflow）只有 `service` + `dal`：

```text
ucp-nocode-web/src/main/java/com/lingan/ucp/nocode/controller/admin/
├─ ObjectDraftController.java      # 老一批：直接放 admin 下
├─ vo/                             # Web 专用请求/响应类型（少量）
└─ task/ · work/ · workflow/       # 新一批：按业务子包

ucp-nocode-<模块>/src/main/java/com/lingan/ucp/nocode/<模块>/
├─ service/<业务>/
│   ├─ XxxService.java             # 接口：对外契约（Controller、其他模块调用）
│   ├─ XxxServiceImpl.java         # 实现：同包成对，@Service
│   └─ package-info.java
└─ dal/
    ├─ dataobject/XxxDO.java       # extends BaseDO
    ├─ mapper/XxxMapper.java       # @Mapper 接口
    └─ query/ · support/           # 动态查询支持（runtime 等需要时）

ucp-nocode-<模块>/src/main/resources/mapper/nocode/XxxMapper.xml    # SQL 唯一落点
```

| 规则 | 说明 |
|---|---|
| 包=目录=模块前缀 | `com.lingan.ucp.nocode.<模块>.<子包>`；业务类不得平铺模块根包（仅 `@Configuration` 例外） |
| 业务归包 | Service 必须放 `service/<业务>/`；禁止 `service` 根包与集中式 `service.impl` |
| 接口配对 | `XxxServiceImpl` 必须有同包 `XxxService` 接口；纯内部协作者（校验、计算、查询支持）可只写具体类 |
| 依赖方向 | Controller 只依赖 Service；禁止 Controller import 业务 Mapper；非 Web 模块不得 import controller/web 包 |
| 职责 | Controller=参数/身份/权限入口/统一返回；Service=业务规则/事务/跨模块编排；Mapper/DO=持久化 |

以上包结构规则由 `check-quality.mjs` 对 runtime / metadata / application / schema 强制，其余模块新代码按同标准；已有类按受影响职责逐步调整，不批量重构。

样板、注释矩阵与门禁细则 → [references/backend-java.md](references/backend-java.md)

## 前端速查（ucp-front）

| 主题 | 必须遵守 |
|---|---|
| 目录 | 页面 `src/views/<module>/`；共享组件 `src/components/`（PascalCase，如 `OsTablePage.vue`）；接口 `src/api/<module>/`；类型 `src/types/<module>/`；领域逻辑 `src/nocode/`（测试 `*.test.ts` 就近）；composable `src/composables/useXxx.ts` |
| 组件写法 | 单 `<script setup lang="ts">`；props 用类型式 `defineProps<{...}>()`；共享组件配 `withDefaults`；中文注释只写非显而易见的“为什么” |
| API 层 | 工厂模式 `createXxxApi(client)` + `export type XxxApi = ReturnType<typeof createXxxApi>`；通过 `useNocodePlatform()` 注入，不散建 axios 实例；请求封装 `src/utils/request.ts`（失败用 notification.error） |
| 权限 | `platform.hasPermission('nocode:object:query')` 控制前端显隐；服务端权限是最终边界 |
| 复用 | 列表页复用 `OsTablePage`+`useOsTablePage`，遵循 [表格与列表规范](references/table-pages.md)；表单容器用 `OsModalForm`+`useOsModalForm` |
| UI 规范 | 颜色/字号/间距走 Design Token；组件复用优先级与 CSS 规范读 `frontend-ui-standard` 技能 |
| 格式 | 沿用 `.prettierrc`：无分号、单引号、2 空格、printWidth 120、无尾逗号 |
| 检查 | `pnpm run check:nocode`（typecheck:nocode + test:nocode + lint:check:nocode + format:check:nocode，覆盖 nocode 范围）；`tsconfig.nocode.json` 为 strict |

目录样例、API 工厂样板与测试约定 → [references/frontend-vue.md](references/frontend-vue.md)

## 数据库迁移速查

- 当前项目数据库为本地 `ucp-ng`。2026-10-09 经用户明确授权，以当前库重新建立 `V001__ucp_ng_baseline.sql`，旧 V001–V079 已退役；后续增量从 V002 开始。当前库标记为 Flyway BASELINE 1，禁止再次重置或重放旧版本。结构基线不含数据，复制完整环境须恢复本地完整快照，见 `ucp-server/sql/README.md`。

- 唯一目录 `ucp-server/sql/`；增量放 `sql/postgresql/migrations/`，命名 `VNNN__小写蛇形描述.sql`，从 V001 全局连续递增（取号前以目录实际最大编号为准）。
- 表前缀：平台元数据 `nocode_`，新生成业务主表/明细/关联表 `biz_`；底座、Flowable 与纳管旧表保留原名。
- 迁移历史 `public.nocode_schema_history`；不启用 Flyway clean、不自动 repair、不随服务启动执行迁移。
- 交付顺序（`ucp-server` 根目录）：`node sql/check-migrations.mjs` → `./ucp-nocode/run.ps1 info -Build` → `./ucp-nocode/run.ps1 migrate`（仅在数据库升级被授权时）→ `./ucp-nocode/run.ps1 verify`。

细节与命令表 → [references/database-migration.md](references/database-migration.md)

## 测试与验证命令

后端（`ucp-server` 根目录执行）：

```bash
node ucp-nocode/check-quality.mjs        # 架构门禁：注解 SQL/Provider、分包配对、@Tag/@Operation、依赖方向
node ucp-nocode/format.mjs --check       # Java 格式检查
node --test ucp-nocode/format-tests.mjs
node --test ucp-nocode/package-layout-tests.mjs

# 定向集成测试（测试集中在 ucp-nocode-tools，连真实开发库，只清理自有夹具）
mvn -B -pl ucp-nocode/ucp-nocode-tools -am test '-Dmaven.test.skip=false' '-Dtest=ObjectDraftIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
./ucp-nocode/test.ps1 -Classes ObjectDraftIntegrationTest -Build   # 定向入口
```

前端（`ucp-front` 目录执行）：

```bash
pnpm run check:nocode      # 类型 + 单测 + ESLint + Prettier（nocode 范围，交付前必跑）
pnpm run test:nocode       # vitest 单测
node tools/nocode-e2e/verify.mjs   # 真实浏览器 E2E（要求 5173/8080 已启动）
```

必要开发工具保留在 `ucp-server/ucp-nocode/`：`format.ps1`（格式化）、`run.ps1`（数据库工具）、`start-server.ps1`（Windows 服务启动）、`test.ps1`（专项集成测试）。历史 `prepare-*` 演示初始化与独立 PowerShell 手工验收脚本已清理，不再作为验证入口。

前端保留 `tools/*-regression/*.test.ts`、对应 Vitest 配置及正式 E2E 入口依赖；流程测试共享夹具位于 `tools/workflow-regression/fixtures/`。历史体验数据初始化、独立预览工程和组件探针不作为正式测试保留；新增临时复验脚本应在验收完成后删除，可复用的断言优先纳入现有测试入口。

## 交付检查清单

- [ ] 改动相关行为已在当前开发环境实际运行（含关键失败路径），记录了真实结果与未完成边界
- [ ] 按改动范围跑过：`format.mjs` / `check-quality.mjs` / `check:nocode` / `check-migrations.mjs`
- [ ] 未改变 HTTP 契约、权限码、版本语义与存量数据兼容性；未建第二套基础设施
- [ ] 迁移未改写已执行文件；新迁移编号连续且通过 check-migrations
- [ ] 只动可识别夹具；一次性脚本已删除，可复用工具保留；运行时材料不混入源码
- [ ] 开发约定、组件或工具用法变化时，相应 skill 与 references 已同步

## References

- [references/backend-java.md](references/backend-java.md) — 后端分层样板、注释矩阵、门禁细则、测试与兼容约束
- [references/frontend-vue.md](references/frontend-vue.md) — 前端目录、API 工厂、平台 DI、测试与检查细节
- [references/database-migration.md](references/database-migration.md) — 迁移流程、校验与工具命令

- [references/project-conventions.md](references/project-conventions.md) — 项目开发约定与数据、环境边界
- [references/platform-reuse.md](references/platform-reuse.md) — 可复用底座与命名约定
- [references/table-pages.md](references/table-pages.md) — 管理表格与运行端列表规范
