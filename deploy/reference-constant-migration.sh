#!/usr/bin/env bash
# 引用字段的条件固定值存量转换工具的线上启动脚本（业务方 2026-10-04：条件行原先给引用字段的是自由文本框，存了名称去比记录 ID）。
#
# 用法：bash reference-constant-migration.sh <外置配置目录> <子命令> [子命令参数...]
#   子命令：dry-run  [--out report.json]
#                    只读；逐个条件值出判定：CONVERT（名称恰好对上 1 条记录，将改成它的 ID）、NO_MATCH、AMBIGUOUS、TOO_MANY_ROWS、TARGET_UNAVAILABLE。
#                    不停服也能跑。
#           apply    --report report.json --actor 用户ID [--out apply.json]
#                    逐对象按报告重算比对，一致的 CONVERT 行改成记录 ID 并发布对象新版本；不一致的行跳过并记 CONFLICT。
#                    对象发布会触发应用自动跟随（开着自动跟随的应用由平台同步并发布）；仍固定旧版本的应用列在结果的 objects[].behind。
#           rollback --report apply.json --actor 用户ID [--out rollback.json]
#                    当前值仍是转换后的 ID 才改回原值并发布对象新版本；已被别人改过的行跳过并记 CONFLICT。
#   报告与结果文件写在当前目录（--out 可改）。先 cd 到本次转换的工作目录再执行。
# 例：
#   mkdir -p /opt/os-server/migration/reference-constant && cd /opt/os-server/migration/reference-constant
#   bash /opt/os-server/deploy/reference-constant-migration.sh /opt/os-server/backend/config/ dry-run
#
# 环境变量（都有默认值）：
#   OS_JAR        正式后端包，默认 /opt/os-server/backend/os.jar（工具复用其中的全部业务依赖与配置）
#   TOOLS_JAR     工具包，默认与本脚本同目录的 os-nocode-tools.jar；与 OS_JAR 必须出自同一提交。构建（os-server 目录）：
#                   mvn -B -Dmaven.test.skip=true -pl os-server,os-nocode/os-nocode-tools -am clean package
#                   → os-nocode/os-nocode-tools/target/os.jar（各模块 finalName 都是 os，复制时改名为 os-nocode-tools.jar）
#   JAVA          默认 $JAVA_HOME/bin/java，否则 PATH 上的 java（须 JDK 21）
#   JAVA_OPTS     默认 "-Xmx1g"
#   SPRING_PROFILE 默认 os
#   APP_PORT      在线服务端口，默认 18080；写库的子命令（apply / rollback）执行前检查它没有在监听（停服窗口）
#   ALLOW_RUNNING_SERVICE=1  跳过上面的停服检查（只用于演练环境，线上不要设）
#
# 退出码：0 成功；2 参数错误（未连库）；3 执行失败（含工具启动自检失败、有对象发布失败）；
#         64 脚本用法错误；65 停服检查未通过。
# 本脚本不读取、不打印任何密码：数据库与 Redis 连接全部来自外置配置目录，由 Spring 自己加载。
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OS_JAR=${OS_JAR:-/opt/os-server/backend/os.jar}
TOOLS_JAR=${TOOLS_JAR:-$script_dir/os-nocode-tools.jar}
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
JAVA_OPTS=${JAVA_OPTS:--Xmx1g}
SPRING_PROFILE=${SPRING_PROFILE:-os}
APP_PORT=${APP_PORT:-18080}

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }
fail() { echo "reference-constant-migration: $1" >&2; exit "${2:-64}"; }

(($# >= 2)) || usage
config_dir=$1
command=$2
shift 2

case $command in
    dry-run | apply | rollback) ;;
    *) fail "子命令只能是 dry-run、apply、rollback：$command" ;;
esac
[[ $config_dir == /* && $config_dir != *[,\;]* ]] || fail "外置配置目录须是绝对路径，且不含逗号或分号：$config_dir"
[[ -d $config_dir && -r $config_dir ]] || fail "外置配置目录不存在或不可读：$config_dir"
[[ $SPRING_PROFILE =~ ^[a-zA-Z0-9_-]+$ ]] || fail "SPRING_PROFILE 只允许一个环境名：$SPRING_PROFILE"
[[ -f $OS_JAR ]] || fail "找不到正式后端包 OS_JAR=$OS_JAR"
[[ -f $TOOLS_JAR ]] || fail "找不到工具包 TOOLS_JAR=$TOOLS_JAR（构建方法见脚本头部）"
[[ $APP_PORT =~ ^[0-9]+$ ]] || fail "APP_PORT 必须是端口号：$APP_PORT"

# 第二道保险：写库的子命令只在停服窗口内执行（工具进程本身已不启动任何后台作业）。
writes=0
case $command in
    apply | rollback) writes=1 ;;
esac
if ((writes)) && [[ ${ALLOW_RUNNING_SERVICE:-0} != 1 ]]; then
    command -v ss >/dev/null || fail "缺少 ss，无法做停服检查" 65
    if [[ -n $(ss -ltnH "sport = :$APP_PORT" 2>/dev/null) ]]; then
        fail "端口 $APP_PORT 仍在监听：$command 必须在停服窗口内执行，先停止在线服务" 65
    fi
fi

# 与线上启动（deploy.sh）相同的配置入口与 8 条启动开关（环境 + 7 条迁移保护）。
# 工具的 main 不把参数交给 Spring，所以用 -D 传；工具自己也会以最高优先级强制这 7 条保护开关。
# 包内位置标 optional：经 PropertiesLauncher 加载时 classpath:/config/ 不存在，不标会启动失败。
spring_props=(
    "-Dspring.config.location=optional:classpath:/,optional:classpath:/config/,file:${config_dir%/}/"
    "-Dspring.profiles.active=$SPRING_PROFILE"
    "-Dapp.database.init-enabled=false"
    "-Dapp.user-pinyin.backfill-enabled=false"
    "-Dspring.sql.init.mode=never"
    "-Dspring.flyway.enabled=false"
    "-Dspring.liquibase.enabled=false"
    "-Dspring.jpa.hibernate.ddl-auto=none"
    "-Dflowable.database-schema-update=false"
)

# shellcheck disable=SC2086 # JAVA_OPTS 按空格拆成多个 JVM 参数
exec "$JAVA" $JAVA_OPTS -Dfile.encoding=UTF-8 "${spring_props[@]}" \
    "-Dloader.path=$TOOLS_JAR" \
    -Dloader.main=com.richuang.os.nocode.tools.ReferenceConstantMigrationTool \
    -cp "$OS_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
    "$command" "$@"
