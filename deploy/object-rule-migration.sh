#!/usr/bin/env bash
# 对象规则迁移工具（表单「关联带入」→ 数据对象·数据联动，设计稿 9.5）的线上启动脚本。
#
# 用法：bash object-rule-migration.sh <外置配置目录> <子命令> [子命令参数...]
#   子命令：dry-run [--prefix P] [--out report.json]
#           apply    --report report.json --actor 用户ID [--out apply.json]
#           rollback --report report.json --actor 用户ID [--out rollback.json]
#           compare  --report report.json --actor 用户ID [--sample 50] [--out compare.json]
#           linkage-readonly --object-codes a,b --actor 用户ID [--dry-run] [--out linkage-readonly.json]
#                    （2026-09-29 只读口径：把列出对象上 readOnly=false 的数据联动改为只读、发布对象并同步引用它的应用）
#   报告与结果文件写在当前目录（--out 可改）。先 cd 到本次迁移的工作目录再执行。
# 例：
#   mkdir -p /opt/ucp-server/migration/object-rule && cd /opt/ucp-server/migration/object-rule
#   bash /opt/ucp-server/deploy/object-rule-migration.sh /opt/ucp-server/backend/config/ dry-run
#
# 环境变量（都有默认值）：
#   OS_JAR        正式后端包，默认 /opt/ucp-server/backend/os.jar（工具复用其中的全部业务依赖与配置）
#   TOOLS_JAR     工具包，默认与本脚本同目录的 ucp-nocode-tools.jar；与 OS_JAR 必须出自同一提交。构建（ucp-server 目录）：
#                   mvn -B -Dmaven.test.skip=true -pl ucp-server,ucp-nocode/ucp-nocode-tools -am clean package
#                   → ucp-nocode/ucp-nocode-tools/target/os.jar（各模块 finalName 都是 os，复制时改名为 ucp-nocode-tools.jar）
#                 只需这一个 jar，其余依赖全部来自 OS_JAR，不需要另补。
#   JAVA          默认 $JAVA_HOME/bin/java，否则 PATH 上的 java（须 JDK 21）
#   JAVA_OPTS     默认 "-Xmx1g"
#   SPRING_PROFILE 默认 os
#   APP_PORT      在线服务端口，默认 18080；apply / rollback / linkage-readonly（--dry-run 除外）前检查它没有在监听（停服窗口）
#   ALLOW_RUNNING_SERVICE=1  跳过上面的停服检查（只用于演练环境，线上不要设）
#
# 退出码：0 成功（compare 差异为 0）；1 compare 有差异；2 参数错误（未连库）；
#         3 执行失败（含工具启动自检失败）；64 脚本用法错误；65 停服检查未通过。
# 本脚本不读取、不打印任何密码：数据库与 Redis 连接全部来自外置配置目录，由 Spring 自己加载。
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OS_JAR=${OS_JAR:-/opt/ucp-server/backend/os.jar}
TOOLS_JAR=${TOOLS_JAR:-$script_dir/ucp-nocode-tools.jar}
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
JAVA_OPTS=${JAVA_OPTS:--Xmx1g}
SPRING_PROFILE=${SPRING_PROFILE:-os}
APP_PORT=${APP_PORT:-18080}

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }
fail() { echo "object-rule-migration: $1" >&2; exit "${2:-64}"; }

(($# >= 2)) || usage
config_dir=$1
command=$2
shift 2

case $command in
    dry-run | apply | rollback | compare | linkage-readonly) ;;
    *) fail "子命令只能是 dry-run、apply、rollback、compare、linkage-readonly：$command" ;;
esac
[[ $config_dir == /* && $config_dir != *[,\;]* ]] || fail "外置配置目录须是绝对路径，且不含逗号或分号：$config_dir"
[[ -d $config_dir && -r $config_dir ]] || fail "外置配置目录不存在或不可读：$config_dir"
[[ $SPRING_PROFILE =~ ^[a-zA-Z0-9_-]+$ ]] || fail "SPRING_PROFILE 只允许一个环境名：$SPRING_PROFILE"
[[ -f $OS_JAR ]] || fail "找不到正式后端包 OS_JAR=$OS_JAR"
[[ -f $TOOLS_JAR ]] || fail "找不到工具包 TOOLS_JAR=$TOOLS_JAR（构建方法见脚本头部）"
[[ $APP_PORT =~ ^[0-9]+$ ]] || fail "APP_PORT 必须是端口号：$APP_PORT"

# 第二道保险：写库的子命令只在停服窗口内执行（工具进程本身已不启动任何后台作业）。
# linkage-readonly 带 --dry-run 时只读，不做停服检查。
writes=0
case $command in
    apply | rollback) writes=1 ;;
    linkage-readonly)
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
# 包内位置标 optional：经 PropertiesLauncher 加载时 classpath:/config/ 不存在，不标会启动失败（冒烟实测）。
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
    -Dloader.main=com.lingan.ucp.nocode.tools.ObjectRuleMigrationTool \
    -cp "$OS_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
    "$command" "$@"
