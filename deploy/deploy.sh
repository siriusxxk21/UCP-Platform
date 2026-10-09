#!/usr/bin/env bash
# Linux 一键部署。上传到 /opt/ucp-server/deploy/deploy.sh 后执行。
set -Eeuo pipefail

# ==================== 用户配置 ====================
BASE_DIR="/opt/ucp-server"
REPO_URL="${OS_REPO_URL:-git@github.com:siriusxxk21/---.git}"
BRANCH="${OS_BRANCH:-dev}"
JAR_PATH="/opt/ucp-server/backend/os.jar"
DIST_DIR="/opt/ucp-server/frontend" # 此目录本身就是 dist 的内容，不再追加 /dist
APP_PORT=${OS_APP_PORT:-18080}     # 与 config 中的端口一致；不覆盖应用监听配置
APP_WORK_DIR=""                   # 留空使用 JAR 所在目录
CONFIG_DIR=""                     # 留空使用 APP_WORK_DIR/config；不创建或覆盖配置
SPRING_PROFILE="${OS_SPRING_PROFILE:-os}"
JAVA_OPTS=()                      # 例如 ("-Xms512m" "-Xmx2g" "-Duser.timezone=Asia/Shanghai")
APP_ARGS=()                       # 额外业务参数；环境名请改 SPRING_PROFILE，不覆盖配置/迁移保护参数
HEALTH_URL=""                     # 可选：确实返回 HTTP 200 的就绪接口，不填则核对 PID、端口和稳定存活
START_TIMEOUT=300
STOP_TIMEOUT=60
STABLE_SECONDS=15
# 此脚本只发布代码。数据库增量按每次交付的 SQL 单独执行，不自动导入全量或增量 SQL。
# ==================================================

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { log "错误：$*" >&2; return 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"; }
within() { [[ "$1" == "$2" || "$1" == "$2/"* ]]; }

usage() {
    cat <<'EOF'
用法：bash /opt/ucp-server/deploy/deploy.sh [check|deploy|status|stop|start]
  check   检查路径、外置配置、构建工具、端口和 Git 读取权限；不构建、不启停、不连接数据库
  deploy  拉取指定分支、构建、日期备份、停止、替换、启动；失败自动恢复旧包（默认）
  status  查看脚本管理的 Java 进程
  stop    核对 PID 身份后停止 Java
  start   启动当前 JAR 并保存 PID，不拉代码、不构建
已按 backend/os.jar、backend/config、frontend 配置；首次部署前手动停止旧服务。
EOF
}

configure() {
    [[ -n "$JAR_PATH" && -n "$DIST_DIR" ]] || die "请先填写 JAR_PATH 和 DIST_DIR" || return 1
    local value
    for value in "$BASE_DIR" "$JAR_PATH" "$DIST_DIR"; do
        [[ "$value" == /* && "$value" != *$'\n'* ]] || die "目录必须是绝对路径且不能含换行" || return 1
    done
    [[ ! -L "$JAR_PATH" && ! -L "$DIST_DIR" ]] || die "发布目标不能是符号链接，请填写其真实路径" || return 1
    BASE_DIR=$(realpath -m -- "$BASE_DIR")
    JAR_PATH=$(realpath -m -- "$JAR_PATH")
    DIST_DIR=$(realpath -m -- "$DIST_DIR")
    CODE_DIR="$BASE_DIR/code"
    BACKUP_ROOT="$BASE_DIR/backups"
    BUILD_ROOT="$BASE_DIR/build"
    RUN_DIR="$BASE_DIR/run"
    LOG_DIR="$BASE_DIR/logs"
    PID_FILE="$RUN_DIR/server.pid"
    APP_WORK_DIR=${APP_WORK_DIR:-$(dirname -- "$JAR_PATH")}
    [[ "$APP_WORK_DIR" == /* ]] || die "APP_WORK_DIR 必须是绝对路径" || return 1
    APP_WORK_DIR=$(realpath -m -- "$APP_WORK_DIR")
    CONFIG_DIR=${CONFIG_DIR:-$APP_WORK_DIR/config}
    [[ "$CONFIG_DIR" == /* && "$CONFIG_DIR" != *$'\n'* && "$CONFIG_DIR" != *','* && "$CONFIG_DIR" != *';'* ]] || die "CONFIG_DIR 必须是绝对路径，不能含换行、逗号或分号" || return 1
    CONFIG_DIR=$(realpath -m -- "$CONFIG_DIR")
    [[ "$SPRING_PROFILE" =~ ^[a-zA-Z0-9_-]+$ ]] || die "SPRING_PROFILE 只允许一个明确的环境名" || return 1
    [[ -n "$BRANCH" && "$BRANCH" != -* && "$BRANCH" != *$'\n'* ]] || die "分支名称无效" || return 1
    [[ "$JAR_PATH" == *.jar ]] || die "JAR_PATH 必须指向 .jar 文件" || return 1
    for value in / /opt /usr /usr/share /var /var/www /etc /root /home /tmp "$BASE_DIR"; do
        [[ "$DIST_DIR" != "$value" && "$BASE_DIR" != / ]] || die "拒绝使用过宽的发布目录：$value" || return 1
    done
    # 发布目录不能覆盖源码、备份、日志、PID 或运行配置所在的目录。
    for value in "$CODE_DIR" "$BACKUP_ROOT" "$BUILD_ROOT" "$RUN_DIR" "$LOG_DIR"; do
        if within "$DIST_DIR" "$value" || within "$value" "$DIST_DIR" || within "$JAR_PATH" "$value"; then
            die "发布路径与脚本管理目录重叠：$value"; return 1
        fi
    done
    if within "$JAR_PATH" "$DIST_DIR" || within "$APP_WORK_DIR" "$DIST_DIR" || within "$CONFIG_DIR" "$DIST_DIR" || within "$DIST_DIR" "$CONFIG_DIR"; then
        die "dist 目录不能包含 JAR 或 Java 工作目录"; return 1
    fi
    [[ ! -e "$DIST_DIR" || -d "$DIST_DIR" ]] || die "DIST_DIR 不是目录" || return 1
    [[ ! -e "$JAR_PATH" || -f "$JAR_PATH" ]] || die "JAR_PATH 不是普通文件" || return 1
    for value in "$APP_PORT" "$START_TIMEOUT" "$STOP_TIMEOUT" "$STABLE_SECONDS"; do
        [[ "$value" =~ ^[1-9][0-9]*$ ]] || die "端口和超时时间必须是正整数" || return 1
    done
    (( APP_PORT <= 65535 && START_TIMEOUT > STABLE_SECONDS )) || die "端口或启动超时配置无效" || return 1
    # 通过 Spring 自身加载配置；脚本不解析 YAML，不接触数据库凭据。
    # 保留包内公共默认配置，外置文件只从指定 config 目录读取。
    DEPLOY_APP_ARGS=(
        "--spring.config.location=classpath:/,classpath:/config/,file:$CONFIG_DIR/"
        "--spring.profiles.active=$SPRING_PROFILE"
        "--app.database.init-enabled=false"
        "--app.user-pinyin.backfill-enabled=false"
        "--spring.sql.init.mode=never"
        "--spring.flyway.enabled=false"
        "--spring.liquibase.enabled=false"
        "--spring.jpa.hibernate.ddl-auto=none"
        "--flowable.database-schema-update=false"
    )
    for value in "${APP_ARGS[@]}"; do
        case "$value" in
            --spring.config.*|--spring.profiles.active*|--app.database.init-enabled*|--app.user-pinyin.backfill-enabled*|--spring.sql.init.*|--spring.flyway.*|--spring.liquibase.*|--spring.jpa.hibernate.ddl-auto*|--flowable.database-schema-update*)
                die "APP_ARGS 不得覆盖外置配置路径或手动 SQL 保护参数：${value%%=*}"; return 1;;
        esac
    done
}

check_config() {
    [[ -d "$CONFIG_DIR" && -r "$CONFIG_DIR" && -x "$CONFIG_DIR" ]] || die "缺少可读取的外置配置目录：$CONFIG_DIR" || return 1
    local file found=0
    for file in "$CONFIG_DIR/application.yml" "$CONFIG_DIR/application.yaml" "$CONFIG_DIR/application.properties" \
        "$CONFIG_DIR/application-$SPRING_PROFILE.yml" "$CONFIG_DIR/application-$SPRING_PROFILE.yaml" "$CONFIG_DIR/application-$SPRING_PROFILE.properties"; do
        if [[ -s "$file" && -r "$file" ]]; then found=1; fi
    done
    (( found )) || die "config 内没有可读取的 application 或 application-$SPRING_PROFILE 配置文件" || return 1
    log "使用外置配置：$CONFIG_DIR；profile=$SPRING_PROFILE；启动时自动建表/迁移已关闭"
}

preflight() {
    (( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) )) || die "需要 Bash 4.4 或更高版本" || return 1
    [[ "$(uname -s)" == Linux ]] || die "部署脚本仅在 Linux 服务器运行" || return 1
    (( EUID == 0 )) || die "请以 root 运行，以便可靠核对端口和进程身份" || return 1
    local tool
    for tool in realpath flock ss nohup java readlink date mkdir cp mv tar mktemp chmod rm sleep; do need "$tool" || return 1; done
    [[ -z "$HEALTH_URL" ]] || need curl || return 1
    JAVA_BIN=$(readlink -f -- "$(command -v java)")
    [[ -r /proc/sys/kernel/random/boot_id ]] || die "无法读取 Linux /proc 进程身份" || return 1
    configure || return 1
}

init_run() {
    umask 077
    mkdir -p -- "$RUN_DIR" "$LOG_DIR" "$BACKUP_ROOT" "$BUILD_ROOT"
    exec 9>"$RUN_DIR/deploy.lock"
    flock -n 9 || die "已有部署、启动或停止操作正在运行" || return 1
    RUN_ID="$(date '+%Y%m%d-%H%M%S')-$$"
    DEPLOY_LOG="$LOG_DIR/deploy-$RUN_ID.log"
    exec > >(tee -a "$DEPLOY_LOG") 2>&1
    CUTOVER=0
    WAS_RUNNING=0
    NEW_STARTED=0
    BUILD_DIR=""
    BACKUP_DIR=""
    TEMP_PATHS=()
    trap on_exit EXIT
    trap 'on_error $?' ERR
    trap 'on_error 130' INT
    trap 'on_error 143' TERM
}

# /proc/stat 的 starttime 加 boot_id，避免旧 PID 文件误杀重用同一 PID 的进程。
process_stamp() {
    local stat rest
    [[ "$1" =~ ^[1-9][0-9]*$ && -r "/proc/$1/stat" ]] || return 1
    stat=$(cat "/proc/$1/stat") || return 1
    rest=${stat##*) }
    local fields=()
    read -r -a fields <<< "$rest"
    [[ "${fields[0]:-Z}" != Z && "${fields[19]:-}" =~ ^[0-9]+$ ]] || return 1
    printf '%s' "${fields[19]}"
}

matches_java() {
    local pid=$1 arg previous=""
    [[ "$(readlink -f -- "/proc/$pid/exe")" == "$JAVA_BIN" ]] || return 1
    while IFS= read -r -d '' arg; do
        [[ "$previous" == -jar && "$arg" == "$JAR_PATH" ]] && return 0
        previous=$arg
    done < "/proc/$pid/cmdline"
    return 1
}

read_pid() {
    MANAGED_PID=""
    [[ -f "$PID_FILE" ]] || return 0
    local saved=() stamp boot
    mapfile -t saved < "$PID_FILE"
    [[ ${#saved[@]} == 4 && "${saved[0]}" =~ ^[1-9][0-9]*$ && "${saved[1]}" =~ ^[0-9]+$ ]] || die "PID 文件格式异常：$PID_FILE" || return 1
    boot=$(cat /proc/sys/kernel/random/boot_id)
    stamp=$(process_stamp "${saved[0]}") || stamp=""
    if [[ "$boot" != "${saved[2]}" || "$stamp" != "${saved[1]}" ]]; then
        log "清除已失效的 PID 记录（不向对应进程发送信号）"
        rm -f -- "$PID_FILE"
        return 0
    fi
    [[ "${saved[3]}" == "$JAR_PATH" ]] && matches_java "${saved[0]}" || die "PID 对应的进程与配置不符，拒绝停止；请检查 $PID_FILE" || return 1
    MANAGED_PID=${saved[0]}
}

port_free() { [[ -z "$(ss -H -ltn "sport = :$APP_PORT")" ]]; }
owns_port() { [[ "$(ss -H -ltnp "sport = :$APP_PORT")" == *"pid=$1,"* ]]; }

# 首次部署还没有 PID 文件；识别同一 JAR 的旧手动启动进程，但不接管或杀掉它。
# 包括 cd backend 后 java -jar os.jar 尚未监听端口的情况。
assert_no_unmanaged_java() {
    local cmdline pid exe arg previous candidate cwd
    for cmdline in /proc/[0-9]*/cmdline; do
        pid=${cmdline#/proc/}; pid=${pid%/cmdline}
        [[ "$pid" != "${MANAGED_PID:-}" && -r "$cmdline" ]] || continue
        exe=$(readlink -f -- "/proc/$pid/exe" 2>/dev/null) || continue
        [[ "${exe##*/}" == java ]] || continue
        previous=""
        while IFS= read -r -d '' arg; do
            if [[ "$previous" == -jar ]]; then
                candidate=$arg
                if [[ "$candidate" != /* ]]; then
                    cwd=$(readlink -f -- "/proc/$pid/cwd" 2>/dev/null) || break
                    candidate="$cwd/$candidate"
                fi
                candidate=$(realpath -m -- "$candidate") || break
                [[ "$candidate" != "$JAR_PATH" ]] || { die "同一 JAR 仍被旧程序运行，PID=$pid；请手动停止原服务及自动拉起它的管理器"; return 1; }
                break
            fi
            previous=$arg
        done < "$cmdline"
    done
}

stop_service() {
    read_pid || return 1
    [[ -n "$MANAGED_PID" ]] || return 0
    local pid=$MANAGED_PID deadline=$((SECONDS + STOP_TIMEOUT))
    log "正常停止 Java，PID=$pid"
    kill -TERM "$pid" || return 1
    while (( SECONDS < deadline )); do
        read_pid || return 1
        [[ -n "$MANAGED_PID" ]] || return 0
        sleep 1
    done
    # 强制结束前再次核对身份。
    read_pid || return 1
    if [[ -n "$MANAGED_PID" ]]; then
        log "等待 ${STOP_TIMEOUT}s 后仍未退出，强制结束已核对的 PID=$MANAGED_PID"
        kill -KILL "$MANAGED_PID" || return 1
        for ((deadline=0; deadline<10; deadline++)); do
            sleep 1
            read_pid || return 1
            [[ -n "$MANAGED_PID" ]] || return 0
        done
        die "进程未能退出，停止替换文件"; return 1
    fi
}

check_ready() {
    local pid=$1 deadline=$((SECONDS + START_TIMEOUT)) stable=0 code
    while (( SECONDS < deadline )); do
        read_pid || return 1
        [[ "$MANAGED_PID" == "$pid" ]] || die "Java 已提前退出，见 $JAVA_LOG" || return 1
        if owns_port "$pid"; then
            code=200
            if [[ -n "$HEALTH_URL" ]]; then
                code=$(curl --noproxy '*' --silent --output /dev/null --write-out '%{http_code}' --connect-timeout 2 --max-time 3 "$HEALTH_URL") || code=000
            fi
            if [[ "$code" == 200 ]]; then
                stable=$((stable + 1))
                (( stable < STABLE_SECONDS )) || return 0
            else stable=0; fi
        else stable=0; fi
        sleep 1
    done
    die "启动检查超时，见 $JAVA_LOG"
}

start_service() {
    check_config || return 1
    read_pid || return 1
    [[ -z "$MANAGED_PID" ]] || die "Java 已运行，PID=$MANAGED_PID" || return 1
    port_free || die "端口 $APP_PORT 被其他进程占用；首次部署请先手动停旧服务" || return 1
    assert_no_unmanaged_java || return 1
    [[ -s "$JAR_PATH" && -d "$APP_WORK_DIR" ]] || die "JAR 或工作目录不存在" || return 1
    JAVA_LOG="$LOG_DIR/java-$(date '+%Y%m%d-%H%M%S')-$$.log"
    local pid stamp boot attempt
    : > "$PID_FILE.tmp" || return 1
    (
        cd -- "$APP_WORK_DIR" || exit 1
        exec nohup "$JAVA_BIN" "${JAVA_OPTS[@]}" -jar "$JAR_PATH" "${DEPLOY_APP_ARGS[@]}" "${APP_ARGS[@]}"
    ) </dev/null >>"$JAVA_LOG" 2>&1 9>&- &
    pid=$!
    # 等待子进程 exec 为 Java 后才持久化身份；记录失败时不会凭端口杀进程。
    for ((attempt=0; attempt<50; attempt++)); do
        if matches_java "$pid"; then break; fi
        kill -0 "$pid" 2>/dev/null || die "Java 启动失败，见 $JAVA_LOG" || return 1
        sleep 0.1
    done
    matches_java "$pid" || die "无法确认新 Java 进程身份，见 $JAVA_LOG" || return 1
    stamp=$(process_stamp "$pid") || return 1
    boot=$(cat /proc/sys/kernel/random/boot_id) || return 1
    printf '%s\n' "$pid" "$stamp" "$boot" "$JAR_PATH" > "$PID_FILE.tmp" || return 1
    mv -f -- "$PID_FILE.tmp" "$PID_FILE" || return 1
    NEW_STARTED=1
    log "Java 已启动，PID=$pid；日志：$JAVA_LOG"
    check_ready "$pid"
}

check_build_tools() {
    local tool version
    for tool in git ssh mvn node npm pnpm jar; do need "$tool"; done
    version=$("$JAVA_BIN" -version 2>&1)
    [[ "$version" =~ version\ \"21[.\"] ]] || die "请使用 JDK 21"
    # Maven 使用 JAVA_HOME；强制与运行时选定的 Java 对齐，避免 PATH 为 21、Maven 却使用 17。
    export JAVA_HOME
    JAVA_HOME=$(dirname -- "$(dirname -- "$JAVA_BIN")")
    [[ -x "$JAVA_HOME/bin/javac" ]] || die "需要完整 JDK，不能仅安装 JRE"
    log "构建工具版本"
    mvn -version
    node --version
    npm --version
    pnpm --version
    # 当前锁文件内 pnpm 11 依赖要求 >=22.13，Vite 7 也不支持旧版 Node。
    node -e 'const [a,b]=process.versions.node.split(".").map(Number); if(a<22 || (a===22 && b<13)) { console.error("需要 Node.js >=22.13（建议使用已安装的 22/24 LTS）"); process.exit(1); }'
    export GIT_TERMINAL_PROMPT=0
    export GIT_SSH_COMMAND="${GIT_SSH_COMMAND:-ssh -o BatchMode=yes -o StrictHostKeyChecking=yes}"
}

check_deployment() {
    check_config
    check_build_tools
    read_pid
    assert_no_unmanaged_java
    if [[ -z "$MANAGED_PID" ]]; then port_free || die "端口 $APP_PORT 被未管理的程序占用"; fi
    log "验证 Git 仓库分支的读取权限"
    git ls-remote --exit-code "$REPO_URL" "refs/heads/$BRANCH"
    log "部署前检查通过；未构建、未启停、未连接数据库。请确认 config 中的数据库为 os-newserver。"
}

sync_source() {
    git check-ref-format --branch "$BRANCH" >/dev/null
    if [[ ! -e "$CODE_DIR" ]]; then
        git clone --branch "$BRANCH" --single-branch -- "$REPO_URL" "$CODE_DIR"
    else
        [[ -d "$CODE_DIR/.git" ]] || die "$CODE_DIR 不是 Git 仓库"
        [[ -z "$(git -C "$CODE_DIR" status --porcelain)" ]] || die "code 有本地修改，请先处理；脚本不会 reset --hard"
        [[ "$(git -C "$CODE_DIR" remote get-url origin)" == "$REPO_URL" ]] || die "code 的 origin 与 REPO_URL 不一致"
        git -C "$CODE_DIR" fetch origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
        if git -C "$CODE_DIR" show-ref --verify --quiet "refs/heads/$BRANCH"; then
            git -C "$CODE_DIR" checkout "$BRANCH"
        else
            git -C "$CODE_DIR" checkout -b "$BRANCH" "refs/remotes/origin/$BRANCH"
        fi
        git -C "$CODE_DIR" merge --ff-only "refs/remotes/origin/$BRANCH"
        [[ "$(git -C "$CODE_DIR" rev-parse HEAD)" == "$(git -C "$CODE_DIR" rev-parse "refs/remotes/origin/$BRANCH")" ]] || die "本地分支含未推送提交，拒绝部署"
    fi
    COMMIT=$(git -C "$CODE_DIR" rev-parse HEAD)
}

build_release() {
    check_build_tools
    sync_source
    BUILD_DIR=$(mktemp -d "$BUILD_ROOT/$RUN_ID.XXXXXX")
    # 在独立目录构建，不让 Vite 生成文件污染 code；打包内容固定到本次提交。
    git -C "$CODE_DIR" archive "$COMMIT" | tar -xf - -C "$BUILD_DIR"
    local server="$BUILD_DIR/richuang-os/ucp-server" front="$BUILD_DIR/richuang-os/ucp-front"
    [[ -f "$server/pom.xml" && -f "$front/pnpm-lock.yaml" ]] || die "项目目录不完整"
    if [[ -f "$server/sql/check-migrations.mjs" ]]; then
        log "仅校验迁移文件目录和锁清单，不连接数据库、不执行 SQL"
        node "$server/sql/check-migrations.mjs"
    fi
    log "构建后端，提交 $COMMIT"
    (cd "$server" && mvn -B -Dmaven.test.skip=true -pl ucp-server -am clean package)
    BUILD_JAR="$server/ucp-server/target/os.jar"
    [[ -s "$BUILD_JAR" ]] || die "未找到 Spring Boot JAR：$BUILD_JAR"
    jar tf "$BUILD_JAR" > "$BUILD_DIR/jar-entries.txt"
    grep -q '^BOOT-INF/classes/' "$BUILD_DIR/jar-entries.txt" || die "产物不是可执行 Spring Boot JAR"
    log "构建前端和独立页面设计器"
    (
        cd "$front"
        export VITE_APP_COMMIT="${COMMIT:0:7}"
        export VITE_APP_COMMIT_TIME
        VITE_APP_COMMIT_TIME=$(git -C "$CODE_DIR" log -1 --format=%cI "$COMMIT")
        pnpm install --frozen-lockfile --prod=false
        pnpm run build
    )
    BUILD_DIST="$front/dist"
    [[ -s "$BUILD_DIST/index.html" && -s "$BUILD_DIST/nocode-designer/index.html" && -s "$BUILD_DIST/nocode-designer/canvas.html" ]] || die "前端产物或独立设计器不完整"
}

backup_release() {
    BACKUP_DIR="$BACKUP_ROOT/$(date '+%Y-%m-%d')/$RUN_ID-${COMMIT:0:7}"
    mkdir -p -- "$BACKUP_DIR"
    HAD_JAR=0
    HAD_DIST=0
    if [[ -f "$JAR_PATH" ]]; then cp -p -- "$JAR_PATH" "$BACKUP_DIR/app.jar"; HAD_JAR=1; fi
    if [[ -d "$DIST_DIR" ]]; then
        tar -czf "$BACKUP_DIR/dist.tar.gz" -C "$DIST_DIR" .
        tar -tzf "$BACKUP_DIR/dist.tar.gz" >/dev/null
        HAD_DIST=1
    fi
    printf 'target_commit=%s\njar_path=%s\ndist_dir=%s\n' "$COMMIT" "$JAR_PATH" "$DIST_DIR" > "$BACKUP_DIR/release.txt"
    log "旧包备份完成：$BACKUP_DIR"
}

stage_files() {
    mkdir -p -- "$(dirname -- "$JAR_PATH")" "$(dirname -- "$DIST_DIR")" "$APP_WORK_DIR"
    STAGED_JAR=$(mktemp "$(dirname -- "$JAR_PATH")/.os-jar-$RUN_ID.XXXXXX")
    TEMP_PATHS+=("$STAGED_JAR")
    cp -- "$BUILD_JAR" "$STAGED_JAR"
    chmod 640 "$STAGED_JAR"
    STAGED_DIST=$(mktemp -d "$(dirname -- "$DIST_DIR")/.os-dist-$RUN_ID.XXXXXX")
    TEMP_PATHS+=("$STAGED_DIST")
    cp -a -- "$BUILD_DIST/." "$STAGED_DIST/"
    chmod -R a+rX "$STAGED_DIST"
}

replace_dist() {
    local source=$1 old=""
    if [[ -d "$DIST_DIR" ]]; then
        old=$(mktemp -d "$(dirname -- "$DIST_DIR")/.os-old-dist-$RUN_ID.XXXXXX") || return 1
        rmdir -- "$old" || return 1
        TEMP_PATHS+=("$old")
        mv -T -- "$DIST_DIR" "$old" || return 1
    fi
    if ! mv -T -- "$source" "$DIST_DIR"; then
        if [[ -n "$old" ]]; then mv -T -- "$old" "$DIST_DIR" || return 1; fi
        return 1
    fi
}

rollback() {
    log "发布未完成，开始恢复：$BACKUP_DIR"
    stop_service || return 1
    port_free || die "仍有未管理的进程占用端口，停止恢复文件，请手动核查" || return 1
    local jar_temp dist_temp
    if (( HAD_JAR )); then
        jar_temp=$(mktemp "$(dirname -- "$JAR_PATH")/.os-rollback-jar-$RUN_ID.XXXXXX") || return 1
        TEMP_PATHS+=("$jar_temp")
        cp -p -- "$BACKUP_DIR/app.jar" "$jar_temp" || return 1
        mv -f -- "$jar_temp" "$JAR_PATH" || return 1
    else rm -f -- "$JAR_PATH" || return 1; fi
    if (( HAD_DIST )); then
        dist_temp=$(mktemp -d "$(dirname -- "$DIST_DIR")/.os-rollback-dist-$RUN_ID.XXXXXX") || return 1
        TEMP_PATHS+=("$dist_temp")
        tar -xzf "$BACKUP_DIR/dist.tar.gz" -C "$dist_temp" || return 1
        replace_dist "$dist_temp" || return 1
    elif [[ -d "$DIST_DIR" ]]; then
        dist_temp=$(mktemp -d "$(dirname -- "$DIST_DIR")/.os-failed-dist-$RUN_ID.XXXXXX") || return 1
        rmdir -- "$dist_temp" || return 1
        TEMP_PATHS+=("$dist_temp")
        mv -T -- "$DIST_DIR" "$dist_temp" || return 1
    fi
    if (( HAD_JAR && WAS_RUNNING )); then start_service || return 1; fi
    log "旧包已恢复；若部署前服务已停止则保持停止；脚本没有执行数据库迁移"
}

on_error() {
    local rc=$1
    trap - ERR INT TERM
    set +e
    log "操作失败，退出码 $rc；部署日志：$DEPLOY_LOG"
    if (( CUTOVER )); then
        if ! rollback; then log "自动恢复未完成，请查看日志和备份：$BACKUP_DIR"; fi
    elif (( NEW_STARTED )); then
        stop_service || log "启动失败后的进程清理未完成，请检查 PID 和日志"
    fi
    exit "$rc"
}

on_exit() {
    local rc=$? path
    trap - EXIT
    set +e
    # 仅删除本次 mktemp 生成并登记的临时路径，日期备份和运行日志始终保留。
    for path in "${TEMP_PATHS[@]}"; do
        [[ -n "$path" && "$path" != / && "$path" != "$JAR_PATH" && "$path" != "$DIST_DIR" ]] && rm -rf -- "$path"
    done
    if [[ -n "$BUILD_DIR" && "$BUILD_DIR" == "$BUILD_ROOT/$RUN_ID."* ]]; then rm -rf -- "$BUILD_DIR"; fi
    exit "$rc"
}

deploy() {
    check_config
    read_pid
    WAS_RUNNING=0
    [[ -z "$MANAGED_PID" ]] || WAS_RUNNING=1
    assert_no_unmanaged_java
    if [[ -z "$MANAGED_PID" ]]; then
        port_free || die "端口 $APP_PORT 已占用但没有本脚本的 PID；请先手动停旧服务"
    fi
    build_release
    stage_files
    backup_release
    CUTOVER=1
    stop_service
    port_free || die "停止后端后端口仍被占用"
    mv -f -- "$STAGED_JAR" "$JAR_PATH"
    start_service
    # 后端通过检查后再切换完整 dist。普通目录切换有短暂间隔，不承诺零停机。
    replace_dist "$STAGED_DIST"
    CUTOVER=0
    log "部署成功：$BRANCH @ $COMMIT"
    log "JAR：$JAR_PATH；前端：$DIST_DIR；备份：$BACKUP_DIR"
}

main() {
    local action=${1:-deploy}
    case "$action" in -h|--help|help) usage; return 0;; check|deploy|start|stop|status) ;; *) usage; return 2;; esac
    preflight
    need tee
    need cat
    need grep
    need rmdir
    init_run
    case "$action" in
        check) check_deployment;;
        deploy) deploy;;
        start) start_service;;
        stop) stop_service; log "停止操作完成";;
        status) read_pid; if [[ -n "$MANAGED_PID" ]]; then log "运行中：PID=$MANAGED_PID，JAR=$JAR_PATH"; else log "没有本脚本管理的运行进程"; fi;;
    esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
