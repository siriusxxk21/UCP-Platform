# UCP-NG 数据库基线

当前项目使用本地 PostgreSQL 16 的 `ucp-ng`，连接由启动模块 `application-os.yml` 提供。

2026-10-09 按当前库重建版本线：旧 V001–V079 和对应手工升级、发布验证脚本已退役，不再用于此项目。原始内容可从 Git 历史或本机恢复备份找回，不能对新基线库执行旧升级包。

## 当前基线

- `postgresql/migrations/V001__ucp_ng_baseline.sql`：从当前库导出的完整 public 结构，包括平台、流程和现存业务表；不含数据、所有者、权限及迁移历史。
- `postgresql/migrations.lock.json`：锁定新 V001 的 SHA-256，之后已执行迁移仍禁止改写。
- `public.nocode_schema_history`：当前库只保留版本 `1` 的 `BASELINE` 记录，现有结构与业务数据不重放。新空库执行 V001 后记录为 SQL 版本 `001`，两者对应同一个起点。
- 后续变更从 `V002__description.sql` 起连续追加，仍通过现有 Flyway 工具显式执行，不随应用启动自动迁移。

## 结构与数据恢复

V001 可以在新空库中重建 public 结构，但不创建可登录、可使用的初始化业务环境。需要复制当前完整项目环境时，应向新空库恢复完整数据快照，再执行 V002 及后续增量；不能只运行 V001 代替数据恢复。

本机完整备份保存在忽略目录 `ucp-nocode/.work/baseline-20261009/`：

- `ucp-ng-before-baseline.dump`：重建前全库备份（包含旧迁移历史和历史审计 schema）。
- `ucp-ng-baseline.dump`：重建后的全库基线快照，后续复制环境应使用此文件。
- `retired-sql.tar.gz`：退役 SQL 与发布验证工具。
- `history-before.json`、`counts-before.json`、`manifest.json`：旧历史、数据行数及备份校验信息。

快照包含实际业务数据和配置，仅在本地保留，不提交到 Git。恢复目标必须是专用新空库；例如在本地容器中先创建新库，再使用 `docker exec -i pgvector-db pg_restore -U postgres --exit-on-error -d 新库名 < 快照路径`。恢复前核对 `manifest.json` 的 SHA-256。不要覆盖当前 `ucp-ng`。

## 检查与增量

在 `ucp-server` 下执行：

```sh
node sql/check-migrations.mjs
node --test sql/check-migrations.test.mjs sql/run-sql.test.mjs
```

现有 PowerShell 工具入口仍为：

```powershell
./ucp-nocode/run.ps1 info -Build
./ucp-nocode/run.ps1 migrate
./ucp-nocode/run.ps1 verify
```

首次切换基线必须 `-Build` 清理旧打包 SQL。已有旧版本历史的数据库不能直接接入新迁移目录，也不应自动 repair 或自动 baseline；应先备份并按专门方案接入。`migrate` 对无历史的非空库保持拒绝，避免误认旧数据库。

`postgresql/manual/server-upgrade/run-sql.sh` 保留为通用手工执行器，默认连接 `ucp-ng`；旧 `upgrade.sql` 已移除，必须显式提供待执行 SQL。历史 `migration_audit_20261007` schema 只随全库备份保留，不属于 public 迁移基线。
