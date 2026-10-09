# 数据库迁移与工具（os-server/sql）

配套主技能见 [../SKILL.md](../SKILL.md)。此文件包含迁移与工具执行规范；工程路径以仓库根目录为基准。

## 目录与命名

- 唯一手写 SQL 目录为 `os-server/sql/`；PostgreSQL 增量放 `sql/postgresql/migrations/`。
- 命名 `VNNN__description.sql`：版本三位起连续递增，描述小写蛇形、字母开头（正则 `^V([0-9]{3,})__[a-z][a-z0-9_]*\.sql$`）；所有模块共用连续编号，从 V001 起（取号前以目录实际最大编号为准）。
- 历史表 `public.nocode_schema_history`；不启用 Flyway clean，不自动 repair，不随服务启动自动迁移。
- 迁移文件头注释建议写明：用途、前置条件、验证方式。

## 不可改写（校验和锁定）

- 已执行迁移的文件名、字节、摘要经 `migrations.lock.json`（sha256）锁定；`check-migrations.mjs` 会报“已封存迁移被删除、重命名或修改”。
- 修复只能追加新版本号；不格式化已执行迁移；不另建迁移器、不在模块内复制 SQL。
- 手工修复与其他数据库方言放非自动扫描目录。

## 表/对象前缀

- 平台元数据表、序列、索引：`nocode_` 前缀。
- 新生成业务主表/内部明细/关联表：`biz_`（关联表 `biz_r_<对象ID>_<关系ID>`）；自动对象编码 ≤59 字符。
- 底座（system/infra/bpm/Flowable）与纳管既有表保留原物理名；旧前缀已登记表兼容保留，新建不得使用。

## 命令（均在 `os-server` 根目录执行）

```bash
node sql/check-migrations.mjs        # 编号连续、命名、UTF-8、非空、校验和（SQL 交付前必跑）
node --test sql/check-migrations.test.mjs   # 验证检查器自身
./os-nocode/run.ps1 info -Build      # 只读：列出版本与待执行状态（首次/工具变更时 -Build）
./os-nocode/run.ps1 migrate          # 执行待应用迁移（仅在数据库升级被授权时）
./os-nocode/run.ps1 verify           # 只读核对目标库版本
./os-nocode/run.ps1 dump             # 更新 public 快照 sql/full/public.sql（仅需更新快照时）
```

- 禁止使用旧构建产物执行新迁移；工具构建的 Maven clean 只清理编译输出，不是数据库 clean。
- 动态业务对象发布与普通业务数据不从 `public.sql` 机械转成平台升级；全量快照仅用于恢复。

## 工具与测试边界

- 工具/集成测试通过 Spring Boot 加载启动模块配置（`NocodeToolContext`），复用底座装配的数据源与事务管理器；不手写 YAML 解析、不 new 数据源、不接受独立数据库配置文件。
- 独立迁移/导出工具与测试夹具可使用必要的底层数据库接口，但不得搬进业务服务。
- 原生 `pg_dump` 从已装配连接池取连接参数；凭据不写入命令行或脚本。

## 一次性材料清理

- 清理本次编写且不可复用的一次性脚本与临时凭据；可复用的构建、迁移、导出、格式化、验证工具保留。
- 运行日志、PID、恢复备份按用途保留在忽略目录（`.work/`），不能只依赖 .gitignore 代替清理。
