# 后端编码细则（ucp-server）

配套主技能见 [../SKILL.md](../SKILL.md)。开发约定见 [project-conventions.md](project-conventions.md)，复用入口见 [platform-reuse.md](platform-reuse.md)；命令见主技能。工程路径以仓库根目录为基准。

## 模块与依赖方向

| 模块 | 职责 |
|---|---|
| ucp-nocode-api | 稳定 ID、数据契约、领域枚举、错误码、跨模块 DTO |
| ucp-nocode-metadata | 对象/明细/关系/索引、版本、目录与纳管、生命周期 |
| ucp-nocode-schema | 结构编译、变更计划、事务发布与漂移同步 |
| ucp-nocode-application | 应用配置/快照、资源校验、成员授权、业务编号 |
| ucp-nocode-report | 数据集、报表授权、文件夹与仪表板生命周期 |
| ucp-nocode-work | 工作草稿、修订、提交材料与幂等持久化 |
| ucp-nocode-runtime | 固定版本业务读写、字段/关系权限、整单事务、动作 |
| ucp-nocode-workflow | 流程节点资源、任务草稿/材料、BPM 完成 Guard |
| ucp-nocode-web | 身份与权限适配、管理 API（启动模块依赖它） |
| ucp-nocode-tools | 迁移、数据库检查、导出、集成测试（不进入运行依赖） |

依赖方向单向：api ← metadata ← schema/application ← runtime ← workflow ← web，无反向依赖。跨 Maven 模块用 api DTO；业务模块不反向依赖 Web VO。

## 分层与分包

Controller 不在业务模块内，统一在 `ucp-nocode-web`；业务模块（runtime / metadata / application / schema / work / workflow）只含 `service` + `dal`：

```text
# 接入层（ucp-nocode-web）
ucp-nocode-web/src/main/java/com/lingan/ucp/nocode/controller/admin/
├─ ApplicationController.java / DataCenterController.java / ObjectDraftController.java   # 老一批：直接放 admin 下
├─ vo/ObjectImportRow.java            # 少量 Web 专用请求/响应类型
└─ task/ · work/ · workflow/          # 新一批：按业务子包

# 业务模块（以 runtime 为例，其余模块同构）
ucp-nocode-runtime/src/main/java/com/lingan/ucp/nocode/runtime/
├─ service/<业务>/                    # 现有：access、application、handling、history、record、report、selection、task、view、work
│   ├─ XxxService.java                # 接口：被 Controller 或其他模块调用的契约
│   ├─ XxxServiceImpl.java            # 实现：与接口同包成对，@Service
│   └─ package-info.java
└─ dal/
    ├─ dataobject/XxxDO.java          # 持久化实体：extends BaseDO
    ├─ mapper/XxxMapper.java          # MyBatis 接口（@Mapper）
    └─ query/ · support/              # 动态表查询条件/参数支持（runtime 等需要时）

ucp-nocode-runtime/src/main/resources/mapper/nocode/RecordMapper.xml    # SQL 唯一落点（mapper/nocode/）
```

- 链路示例：`ObjectDraftController（web/controller/admin）→ ObjectDraftService（metadata/service/object）→ ObjectDraftMapper（metadata/dal/mapper）→ resources/mapper/nocode/ObjectDraftMapper.xml`。
- 命名：`XxxController`、`XxxService` / `XxxServiceImpl`、`XxxMapper`、`XxxDO`。现有接口+实现在 `service/<业务>/` 同包成对（work 的 draft/event/submission，runtime 的 handling/history/task/work，application 的 published/task，workflow 的 material/task）。
- 何时要接口：对外提供契约（被 Controller 或其他模块调用）的服务用接口 + Impl；模块内部协作者（校验器、计算器、查询支持，如 `RecordQueryService`、`RecordCalculations`）可直接用具体类，不为形式强加接口；已有类按受影响职责逐步调整，不批量重构。
- 职责边界：Controller 只做参数、身份、权限入口、统一返回；Service 承担业务规则、事务、跨模块编排；Mapper/DO 只做持久化（PostgreSQL 锁、目录 SQL 在 Mapper/XML，外部值全部参数绑定）。禁止 Controller 直调 Mapper；非 Web 模块不得 import `controller` / `web` 包。
- 包纪律：包路径 = 目录 = 模块前缀 `com.lingan.ucp.nocode.<模块>`；业务类不得平铺在模块根包（仅 `@Configuration` 例外）；禁止 `service` 根包与集中式 `service.impl`；业务子包维护 `package-info.java`；跨 Maven 模块用 api DTO，业务模块不反向依赖 Web VO。
- 风格参照：system 模块 `UserController` / `AdminUserService` / `AdminUserServiceImpl` / `UserSaveReqVO`；无代码内部样板：WorkDraft、FlowTask 的 Controller/Service。

## 请求解码与 HTTP 契约

- 严格接参沿用 `StrictRequestDecoder`（`@Resource private StrictRequestDecoder requests;`），按接口选择 `requests.application/runtime/design/draft(body, X.class)`；未知属性、类型错误的提示保持原文案，不批量换成 Bean Validation。
- 已有接口的接受范围与错误响应是契约：逐接口迁移，不批量改变解析顺序或错误文案。
- 新增/修改 Controller：每个 Mapping 配 `@Tag`（类级）与 `@Operation(summary=...)`（方法级）；权限入口沿用类级 `@PreAuthorize("isAuthenticated()")` + 方法级 `@PreAuthorize("@nocodeAccess.xxx()")`；统一返回 `Result<T>`。

## 依赖注入与初始化

- 统一 `jakarta.annotation.Resource` 字段注入（与底座一致）；不用 `private final` 构造注入业务依赖；`@Bean` 方法参数由 Spring 装配属正常写法。
- 装配后创建的事务模板、专用 JSON 读取器等本地配置用 `@PostConstruct` 初始化；不修改底座共享实例。
- 枚举值、不可变值对象、本地缓存的 `final` 按不可变语义保留，不算依赖注入。

## DO 与 SQL

- DO 样板：`@Data @EqualsAndHashCode(callSuper = true) @TableName("nocode_xxx") extends BaseDO`。
- SQL 唯一落点 `src/main/resources/mapper/**/*.xml`（MyBatis 配置 `mapper-locations: classpath*:/mapper/**/*.xml`）；XML 头 + namespace 指向 Mapper 全限定名；跨 Mapper 复用片段用 `<include refid="全限定Mapper.fragment">`。
- 禁止注解 SQL 与 Provider（`check-quality.mjs` 强制）；既有注解/Provider 随重构逐项迁入 XML，锁、缓存、主键回填、INSERT RETURNING 等执行语义必须保持。
- Java 侧只保留：参数、字段白名单、标识符校验（如 `RuntimeSqlParameters`）。
- 调整 SQL 时对照运行 `MapperXmlCompatibilityTest`、`ProviderXmlCompatibilityTest`（校验固定原始语句、绑定值与执行选项）。

## 枚举与错误码

- 状态、字段类型、关系类型、删除策略、分类、发布结果等固定业务取值统一定义在 `ucp-nocode-api` 领域枚举；业务代码用枚举判断/转换，禁止散落字符串魔法值；接口与库中保存稳定编码，禁止枚举序号。
- 错误：`com.lingan.ucp.nocode.api.NocodeErrorCodes`（invalid、notFound、duplicate、conflict、foreignField、unsupportedState…），静态导入后 `throw invalid("中文提示")`；错误码从 1_050_000_001 起。不新增第二套错误码体系。

## 注释

为类/公开契约/关键业务规则写中文注释，重点说明：

| 位置 | 应说明 |
|---|---|
| 类/接口 | 职责、调用边界、依赖前置条件 |
| 公开方法 | 业务动作、参数/返回语义、权限执行层、版本与失败约定 |
| DTO/record 字段 | ID 所属域、单位、可空性、缺省语义、集合省略与清空的区别 |
| 事务/并发段落 | 谁开事务、锁顺序、回滚范围、重试与幂等原因 |
| 特殊兼容分支 | 历史协议/存量数据约束、保留原因 |

不写复述代码字面意思的注释；不补虚构作者、日期；保留已有高价值注释（如保存点与锁顺序说明）。

## 局部变量类型

- 后端 Java 禁止使用局部变量类型推断 `var`；所有局部变量必须声明明确类型。
- 该规则适用于生产代码与测试代码，以便代码审查时能直接识别领域对象、集合元素及数据库/事务相关值的类型。

## 格式与门禁

- 格式化：`node ucp-nocode/format.mjs`（应用）/ `--check`（只检）/ `--list`；工具固定 google-java-format 1.24.0 `--aosp`，4 空格（`.editorconfig`：`[*.{java,xml}] indent_size=4`、utf-8、final newline）。
- PowerShell 兼容入口：`./ucp-nocode/format.ps1` / `./ucp-nocode/format.ps1 -Check`（内部委托同一 format.mjs）。
- 首次缺 JAR 时按提示执行：`mvn -B -N dependency:copy '-Dartifact=com.google.googlejavaformat:google-java-format:1.24.0:jar:all-deps' '-DoutputDirectory=ucp-nocode/.work/formatter'`。
- `node ucp-nocode/check-quality.mjs` 检查：禁止注解 SQL/Provider；包↔目录↔模块前缀一致；禁止根级 `service`/`service.impl` 包；ServiceImpl 必须有同包接口；每个 Mapping 有 `@Tag`+`@Operation`；禁止 Controller→Mapper import；禁止非 Web 模块 import controller/web 包。包结构与接口配对规则当前对 runtime/metadata/application/schema 四个已整理模块强制，其余模块新代码按同标准执行。
- 不把逐行花括号风格另立为与 AOSP 冲突的规则；不格式化已执行的迁移 SQL。

## 测试

- 测试集中在 `ucp-nocode-tools/src/test/java/com/lingan/ucp/nocode/tools/`（`*Test` / `*IntegrationTest`，JUnit 5 + AssertJ）；集成测试经 `NocodeIntegrationSupport` 复用启动模块 Spring 配置与开发库装配，不另写数据源解析。
- 命令见主技能“测试与验证命令”；全量构建 `mvn -B -Dmaven.test.skip=false -DskipTests=false package`（根 POM 默认跳过测试，`ucp-nocode` 聚合重新开启）。
- 只清理自身可准确识别的夹具；不清空开发数据或用户体验数据。

## 重构约束

- 保持 Maven 模块边界与依赖方向；不新增模块；不批量重命名（含 JSON 字段、HTTP 路径）。
- 不做“只为目录形式”的重构；已有类按受影响职责逐步调整；不能仅将类改名为 `ServiceImpl` 就算完成。
- 不能以格式/编译通过替代行为回归；回归断言应先在旧实现上失败。
