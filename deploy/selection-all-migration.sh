#!/usr/bin/env bash
# 授权清单转「全部」的存量转换工具的线上启动脚本。
#
# 用法：bash selection-all-migration.sh <外置配置目录> <子命令> [子命令参数...]
#   子命令：dry-run       [--out report.json]
#                         只读；逐行计划 + 不变式结果。不停服也能跑。
#           apply         --report report.json --actor 用户ID [--out selection-all-apply.json]
#                         按 dry-run 的报告写库；库里现状与报告不一致的行跳过并记 CONFLICT。
#                         同目录另出给业务方看的 selection-all-留底.md。
#           rollback      --report selection-all-apply.json --actor 用户ID [--out rollback.json]
#                         把 apply（或 expand-all）写过的行恢复成转换前；现状已被别人改过的行跳过并记 CONFLICT。
#           expand-all    --actor 用户ID [--dry-run] [--out expand-all.json]
#                         把库里所有「全部」按当时的全集展开成显式清单。只在要回滚到不认识「全部」的旧代码之前用。
#           follow-behind --actor 用户ID [--dry-run] [--out follow.json]
#                         对所有开着自动跟随且落后的应用各补一次跟随（会产生应用新版本）。上线当天要不要跑由负责人决定。
#   报告与结果文件写在当前目录（--out 可改）。先 cd 到本次转换的工作目录再执行。
# 例：
#   mkdir -p /opt/os-server/migration/selection-all && cd /opt/os-server/migration/selection-all
#   bash /opt/os-server/deploy/selection-all-migration.sh /opt/os-server/backend/config/ dry-run
#
# 环境变量（都有默认值）：
#   OS_JAR        正式后端包，默认 /opt/os-server/backend/os.jar（工具复用其中的全部业务依赖与配置）
#   TOOLS_JAR     工具包，默认与本脚本同目录的 os-nocode-tools.jar；与 OS_JAR 必须出自同一提交。构建（os-server 目录）：
#                   mvn -B -Dmaven.test.skip=true -pl os-server,os-nocode/os-nocode-tools -am clean package
#                   → os-nocode/os-nocode-tools/target/os.jar（各模块 finalName 都是 os，复制时改名为 os-nocode-tools.jar）
#   JAVA          默认 $JAVA_HOME/bin/java，否则 PATH 上的 java（须 JDK 21）
#   JAVA_OPTS     默认 "-Xmx1g"
#   SPRING_PROFILE 默认 os
#   APP_PORT      在线服务端口，默认 18080；写库的子命令（apply / rollback，以及不带 --dry-run 的 expand-all / follow-behind）
#                 执行前检查它没有在监听（停服窗口）
#   ALLOW_RUNNING_SERVICE=1  跳过上面的停服检查（只用于演练环境，线上不要设）
#
# 时区：判断「哪些项是这份授权保存之后才加的」要把授权表的保存时间（不带时区的墙上时间）按数据库会话时区换算。
#   本脚本与在线服务用同一份外置配置、同一台机器启动，JVM 时区因此一致；报告头会写出会话时区与 JVM 时区，执行 apply 前先核对。
#
# 退出码：0 成功；2 参数错误（未连库）；3 执行失败（含工具启动自检失败、不变式不成立）；
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
fail() { echo "selection-all-migration: $1" >&2; exit "${2:-64}"; }

(($# >= 2)) || usage
config_dir=$1
command=$2
shift 2

case $command in
    dry-run | apply | rollback | expand-all | follow-behind) ;;
    *) fail "子命令只能是 dry-run、apply、rollback、expand-all、follow-behind：$command" ;;
esac
[[ $config_dir == /* && $config_dir != *[,\;]* ]] || fail "外置配置目录须是绝对路径，且不含逗号或分号：$config_dir"
[[ -d $config_dir && -r $config_dir ]] || fail "外置配置目录不存在或不可读：$config_dir"
[[ $SPRING_PROFILE =~ ^[a-zA-Z0-9_-]+$ ]] || fail "SPRING_PROFILE 只允许一个环境名：$SPRING_PROFILE"
[[ -f $OS_JAR ]] || fail "找不到正式后端包 OS_JAR=$OS_JAR"
[[ -f $TOOLS_JAR ]] || fail "找不到工具包 TOOLS_JAR=$TOOLS_JAR（构建方法见脚本头部）"
[[ $APP_PORT =~ ^[0-9]+$ ]] || fail "APP_PORT 必须是端口号：$APP_PORT"

# 第二道保险：写库的子命令只在停服窗口内执行（工具进程本身已不启动任何后台作业）。
# expand-all / follow-behind 带 --dry-run 时只读，不做停服检查。
writes=0
case $command in
    apply | rollback) writes=1 ;;
    expand-all | follow-behind)
        writes=1
        for arg in "$@"; do
            if [[ $arg == --dry-run ]]; then writes=0; fi
        done
        ;;
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
    -Dloader.main=com.richuang.os.nocode.tools.SelectionAllMigrationTool \
    -cp "$OS_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
    "$command" "$@"
