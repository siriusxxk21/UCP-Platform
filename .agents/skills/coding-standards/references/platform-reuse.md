# 底座复用与无代码命名规范

无代码平台作为 OS 底座上的业务系统开发。它新增对象、字段、结构版本、页面定义、发布及运行规则；底座继续承担公共设施。其他业务模块同样遵循这个原则。

## 开发顺序

每个功能在编码前完成三项判断：底座的可用入口是什么，业务缺口是什么，最小扩展放在哪里。已有能力直接调用；需要差异时优先配置、适配或扩展点；确实缺少公共能力时放回相应底座模块，并说明受益方和兼容性。无代码模块只保存无代码领域规则。

早期设计中的完整权限、身份、消息、文件和流程扩展表是逻辑候选，不是重复建库清单。是否新增以及保存哪些字段，必须经过底座映射后决定。

## 当前可复用入口

| 能力 | 已有入口 | 无代码职责 |
|---|---|---|
| 持久化、分页、事务 | os-spring-boot-starter-mybatis、BaseMapperX、PageResult、现有事务管理器 | Mapper/DO 与领域校验；不自建连接和分页设施 |
| 当前数据库元数据 | MyBatis starter 的 DatabaseMetadataReader / PostgreSQL Mapper | 通过统一契约读取真实结构，不在对象 Mapper 混放目录 SQL |
| 登录、身份、组织、权限 | 当前 SecurityFrameworkUtils、PermissionCommonApi；system 的 AdminUserApi、DeptApi、PermissionApi | 注册业务权限码，按当前身份校验；不新增账号体系 |
| 响应、异常 | Result、ServiceException、现有全局异常处理器 | 领域错误码和清晰提示 |
| 前端请求和页面 | 现有认证请求客户端、路由、主题，os-table-page / os-modal-form 等公共组件 | 业务表单、字段编辑和设计器；先评估公共组件能力 |
| 文件 | os-module-infra 的 FileApi | 对象/记录附件关联和业务约束 |
| 消息 | system 的 NotifyMessageSendApi / SmsSendApi / MailSendApi；os-module-msg | 业务消息规则和模板引用，按当前通道与接口接入 |
| 流程 | os-module-bpm 的 BpmProcessDefinitionApi / BpmProcessInstanceApi / BpmProcessTaskApi | 对象、页面与流程的关联扩展；不再建流程执行引擎 |
| 通用操作日志 | system 的 OperateLogApi 和现有日志基础设施 | 通用操作日志复用底座；nocode_operation_log 仅保留对象修订的事务性领域留痕，不作为另一套通用日志中心 |
| 字典、配置、缓存、调度 | DictDataApi、ConfigApi、已有 Redis / job starter | 先核实适用性；应用专属版本语义有差异时只补必要领域配置 |

入口清单表明底座已有能力，不表示文件、消息、流程等后续无代码功能已经全部接通。新增批次需核实具体接口和权限语义。

数据中心、应用中心的管理表格统一参考系统管理的用户管理，具体组件配置、布局、操作列和分页要求见[数据中心与应用中心表格规范](table-pages.md)。

## 命名

```text
os-server/
  os-nocode/                  POM 聚合模块
    os-nocode-api/
    os-nocode-metadata/
    os-nocode-web/
    os-nocode-tools/
```

Maven artifactId 与目录同名。Java 包仍为 `com.richuang.os.nocode`；API `/nocode/**`、权限 `nocode:*`、前端 `src/views/nocode` 保持领域含义，不用 Maven 名称改写 URL。

| 原名称 | 当前名称 |
|---|---|
| lc_object | nocode_object |
| lc_object_version | nocode_object_version |
| lc_object_table | nocode_object_table |
| lc_field | nocode_field |
| aud_operation_log | nocode_operation_log |
| nocode_schema_history | nocode_schema_history |
| lc_stable_id_seq 和各表 identity 序列 | nocode_stable_id_seq 和 nocode_*_id_seq |
| 新生成业务表（DEC-20260907-06） | biz_demo_company |

新建无代码静态表一律 `nocode_*`；新生成业务主表及内部明细表使用 `biz_<业务标识>`，最多 63 字符，后缀以小写字母开头，仅含小写字母、数字、下划线。新关联表采用 `biz_r_<对象ID>_<关系ID>`。默认物理名由对象编码建议，例如 `object_gs` → `biz_object_gs`；自动对象编码最多 59 字符。已登记旧表名、底座、Flowable 和外部纳管表保留原名；已有 `nocode_data_r_` 关系表继续识别和读写。
