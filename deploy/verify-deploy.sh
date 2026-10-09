#!/usr/bin/env bash
# 在临时目录中验证发布/备份/回退行为；不连接服务器，不执行真实 Java、Maven 或数据库操作。
set -Eeuo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ ${1:-} == --case ]]; then
    scenario=$2
    fixture=$3
    source "$SCRIPT_DIR/deploy.sh"
    BASE_DIR="$fixture/base"
    JAR_PATH="$fixture/live-app/os.jar"
    DIST_DIR="$fixture/live-web/dist"
    APP_WORK_DIR="$fixture/live-app"
    configure
    mkdir -p "$BUILD_ROOT" "$BACKUP_ROOT" "$LOG_DIR" "$RUN_DIR"
    RUN_ID="20260912-120000-$$"
    DEPLOY_LOG="$LOG_DIR/test.log"
    CUTOVER=0
    WAS_RUNNING=0
    NEW_STARTED=0
    BUILD_DIR=""
    BACKUP_DIR=""
    TEMP_PATHS=()
    trap on_exit EXIT
    trap 'on_error $?' ERR

    # 只替换外部进程和构建边界，真实执行文件备份、替换以及故障回退。
    read_pid() { MANAGED_PID=""; [[ ! -f "$fixture/process" ]] || MANAGED_PID=123; }
    port_free() { [[ "$scenario" != unmanaged-port ]]; }
    assert_no_unmanaged_java() { [[ "$scenario" != unmanaged-jar ]]; }
    stop_service() { printf 'stop\n' >> "$fixture/events"; rm -f "$fixture/process"; }
    start_service() {
        printf 'start:%s\n' "$(cat "$JAR_PATH")" >> "$fixture/events"
        touch "$fixture/process"
        if [[ "$scenario" == start-fail* && "$(cat "$JAR_PATH")" == new-jar ]]; then return 42; fi
    }
    build_release() {
        printf 'build\n' >> "$fixture/events"
        if [[ "$scenario" == build-fail ]]; then return 23; fi
        COMMIT=4f21ee77592057207d1096fe251e343aa587ca32
        BUILD_DIR=$(mktemp -d "$BUILD_ROOT/$RUN_ID.XXXXXX")
        BUILD_JAR="$BUILD_DIR/os.jar"
        BUILD_DIST="$BUILD_DIR/dist"
        mkdir -p "$BUILD_DIST/nocode-designer"
        printf new-jar > "$BUILD_JAR"
        printf new-index > "$BUILD_DIST/index.html"
        printf new-designer > "$BUILD_DIST/nocode-designer/index.html"
    }
    if [[ "$scenario" == backup-fail ]]; then
        backup_release() { return 31; }
    fi
    if [[ "$scenario" == dist-fail ]]; then
        # 第一次目录替换失败，第二次真实执行回退替换。
        eval "$(declare -f replace_dist | sed '1s/replace_dist/real_replace_dist/')"
        replace_dist() {
            if [[ ! -f "$fixture/dist-failed" ]]; then touch "$fixture/dist-failed"; return 44; fi
            real_replace_dist "$@"
        }
    fi
    deploy
    exit 0
fi

root=$(mktemp -d)
trap 'rm -rf -- "$root"' EXIT
bash -n "$SCRIPT_DIR/deploy.sh"
bash "$SCRIPT_DIR/deploy.sh" --help >/dev/null

assert_equal() { [[ "$1" == "$2" ]] || { printf 'FAIL: %s (got=%s expected=%s)\n' "$3" "$1" "$2" >&2; exit 1; }; }
for scenario in success success-stopped build-fail backup-fail start-fail start-fail-stopped dist-fail unmanaged-port unmanaged-jar missing-config; do
    fixture="$root/$scenario"
    mkdir -p "$fixture/live-app" "$fixture/live-web/dist"
    printf old-jar > "$fixture/live-app/os.jar"
    printf old-index > "$fixture/live-web/dist/index.html"
    printf obsolete > "$fixture/live-web/dist/old-asset.js"
    printf preserve-config > "$fixture/live-app/application.yml"
    if [[ "$scenario" != missing-config ]]; then
        mkdir -p "$fixture/live-app/config"
        printf preserve-external-config > "$fixture/live-app/config/application-os.yml"
    fi
    : > "$fixture/events"
    if [[ "$scenario" != unmanaged-port && "$scenario" != *-stopped ]]; then touch "$fixture/process"; fi
    set +e
    bash "$0" --case "$scenario" "$fixture" > "$fixture/output.log" 2>&1
    result=$?
    set -e
    if [[ "$scenario" == success* ]]; then
        assert_equal "$result" 0 "$scenario exit"
        assert_equal "$(cat "$fixture/live-app/os.jar")" new-jar 'JAR replaced'
        assert_equal "$(cat "$fixture/live-web/dist/index.html")" new-index 'dist replaced'
        [[ ! -e "$fixture/live-web/dist/old-asset.js" ]] || exit 1
    else
        [[ "$result" != 0 ]] || { cat "$fixture/output.log"; exit 1; }
        assert_equal "$(cat "$fixture/live-app/os.jar")" old-jar "$scenario JAR preserved/restored"
        assert_equal "$(cat "$fixture/live-web/dist/index.html")" old-index "$scenario dist preserved/restored"
        assert_equal "$(cat "$fixture/live-web/dist/old-asset.js")" obsolete "$scenario full dist preserved/restored"
    fi
    assert_equal "$(cat "$fixture/live-app/application.yml")" preserve-config 'external config preserved'
    if [[ "$scenario" != missing-config ]]; then
        assert_equal "$(cat "$fixture/live-app/config/application-os.yml")" preserve-external-config 'config directory preserved'
    fi
    if [[ "$scenario" == build-fail || "$scenario" == backup-fail || "$scenario" == unmanaged-* || "$scenario" == missing-config ]]; then
        [[ "$(cat "$fixture/events")" != *stop* ]] || exit 1
    else
        mapfile -t backups < <(find "$fixture/base/backups" -name dist.tar.gz)
        assert_equal "${#backups[@]}" 1 'one dated backup'
        assert_equal "$(tar -xOzf "${backups[0]}" ./index.html)" old-index 'backup is old dist'
        assert_equal "$(cat "$(dirname "${backups[0]}")/app.jar")" old-jar 'backup is old jar'
        if [[ "$scenario" == start-fail-stopped ]]; then
            [[ ! -f "$fixture/process" ]] || exit 1
            [[ "$(cat "$fixture/events")" != *start:old-jar* ]] || exit 1
        else
            [[ -f "$fixture/process" ]] || exit 1
        fi
    fi
    [[ -z "$(find "$fixture/base/build" -mindepth 1 -print -quit)" ]] || exit 1
    printf 'PASS: %s\n' "$scenario"
done

# 路径保护：dist 不能吞掉源代码、备份或 JAR；也不能配置成系统根目录。
for bad in / "$root/protected/base" "$root/protected/base/code" "$root/protected/live-app"; do
    set +e
    bash -c 'source "$1/deploy.sh"; BASE_DIR="$2/base"; JAR_PATH="$2/live-app/os.jar"; DIST_DIR="$3"; configure' _ "$SCRIPT_DIR" "$root/protected" "$bad" >/dev/null 2>&1
    result=$?
    set -e
    [[ "$result" != 0 ]] || { printf 'FAIL: unsafe path accepted: %s\n' "$bad"; exit 1; }
done
printf 'PASS: unsafe paths rejected\n'

# 配置必须从 config 加载，启动参数不得重新打开迁移；目录不存在时不静默回落。
(
    source "$SCRIPT_DIR/deploy.sh"
    BASE_DIR="$root/config-test/base"
    JAR_PATH="$root/config-test/backend/os.jar"
    DIST_DIR="$root/config-test/frontend"
    configure
    [[ "$CONFIG_DIR" == "$root/config-test/backend/config" ]] || exit 1
    [[ " ${DEPLOY_APP_ARGS[*]} " == *" --spring.config.location=classpath:/,classpath:/config/,file:$CONFIG_DIR/ "* ]] || exit 1
    for arg in --app.database.init-enabled=false --app.user-pinyin.backfill-enabled=false --spring.sql.init.mode=never --spring.flyway.enabled=false --spring.liquibase.enabled=false --spring.jpa.hibernate.ddl-auto=none --flowable.database-schema-update=false; do
        [[ " ${DEPLOY_APP_ARGS[*]} " == *" $arg "* ]] || exit 1
    done
    if check_config; then exit 1; fi
    mkdir -p "$CONFIG_DIR"
    printf 'server.port=18080\n' > "$CONFIG_DIR/application.properties"
    check_config
    APP_ARGS=(--flowable.database-schema-update=true)
    if configure; then exit 1; fi
)
printf 'PASS: explicit config and manual migration guards\n'

(
    source "$SCRIPT_DIR/deploy.sh"
    TARGET_COMMIT=""
    parse_args
    [[ "$ACTION" == deploy && -z "$TARGET_COMMIT" ]] || exit 1
    TARGET_COMMIT=abcdef1234567
    parse_args deploy
    [[ "$TARGET_COMMIT" == abcdef1234567 ]] || exit 1
    parse_args deploy ABCDEF123456789
    [[ "$ACTION" == deploy && "$TARGET_COMMIT" == ABCDEF123456789 ]] || exit 1
    parse_args check 1234567
    [[ "$ACTION" == check && "$TARGET_COMMIT" == 1234567 ]] || exit 1
    for invalid in HEAD dev 'HEAD~1' '--help' 123456 '' '1234567;echo unsafe'; do
        if parse_args deploy "$invalid"; then exit 1; fi
    done
    if parse_args start 1234567; then exit 1; fi
    if parse_args deploy 1234567 extra; then exit 1; fi
)
printf 'PASS: commit argument validation and override priority\n'

# 使用本地临时 Git 仓库验证从旧 single-branch main 切换 dev；不连接 GitHub。
(
    source "$SCRIPT_DIR/deploy.sh"
    export GIT_TERMINAL_PROMPT=0
    REPO_URL="$root/git-source"
    CODE_DIR="$root/git-code"
    BRANCH=dev
    git init -q -b main "$REPO_URL"
    git -C "$REPO_URL" config user.name deploy-test
    git -C "$REPO_URL" config user.email deploy-test@example.invalid
    printf main > "$REPO_URL/marker"
    git -C "$REPO_URL" add marker
    git -C "$REPO_URL" commit -qm main
    git -C "$REPO_URL" checkout -qb dev
    printf dev > "$REPO_URL/marker"
    git -C "$REPO_URL" commit -qam dev
    git clone -q --branch main --single-branch "$REPO_URL" "$CODE_DIR"
    # Git for Windows 会将 /tmp 路径规范为盘符路径；按实际 origin 比较。
    REPO_URL=$(git -C "$CODE_DIR" remote get-url origin)
    sync_source
    [[ "$(cat "$CODE_DIR/marker")" == dev ]] || exit 1
    [[ "$COMMIT" == "$(git -C "$REPO_URL" rev-parse dev)" ]] || exit 1
    old_commit=$COMMIT
    printf dev-new > "$REPO_URL/marker"
    git -C "$REPO_URL" commit -qam dev-new
    sync_source
    [[ "$(cat "$CODE_DIR/marker")" == dev-new ]] || exit 1
    latest_commit=$COMMIT
    for target in "$old_commit" "${old_commit:0:7}" "${old_commit^^}"; do
        TARGET_COMMIT=$target
        sync_source
        [[ "$COMMIT" == "$old_commit" && "$REMOTE_COMMIT" == "$latest_commit" ]] || exit 1
        [[ "$(git -C "$CODE_DIR" rev-parse HEAD)" == "$latest_commit" ]] || exit 1
        # 验证构建归档内容真是旧提交，而不是留在最新版本的工作区。
        [[ "$(git -C "$CODE_DIR" archive "$COMMIT" | tar -xOf - marker)" == dev ]] || exit 1
    done
    TARGET_COMMIT=""
    sync_source
    [[ "$COMMIT" == "$latest_commit" ]] || exit 1
    if (TARGET_COMMIT=0000000000000000000000000000000000000000; resolve_commit); then exit 1; fi
    blob=$(git -C "$CODE_DIR" rev-parse HEAD:marker)
    if (TARGET_COMMIT=$blob; resolve_commit); then exit 1; fi
    # 另一个分支的提交即使已缓存，也不能被指定 dev 部署。
    git -C "$REPO_URL" checkout -qb other main
    printf other > "$REPO_URL/marker"
    git -C "$REPO_URL" commit -qam other
    other_commit=$(git -C "$REPO_URL" rev-parse HEAD)
    git -C "$CODE_DIR" fetch -q origin refs/heads/other:refs/remotes/origin/other
    if (TARGET_COMMIT=$other_commit; resolve_commit); then exit 1; fi
    git -C "$REPO_URL" checkout -q dev
    # 构造歧义解析结果，避免依赖随机生成真实 SHA 前缀碰撞。
    (
        git() {
            if [[ "${4:-}" == --disambiguate=* ]]; then printf '%s\n' "$old_commit" "$latest_commit"; else command git "$@"; fi
        }
        TARGET_COMMIT=${old_commit:0:7}
        if resolve_commit; then exit 1; fi
    )
    printf user-content > "$CODE_DIR/local-untracked"
    # 与正式运行一样启用 errexit，避免 if function 的 Bash 语义屏蔽中途失败。
    if bash -c 'set -e; source "$1/deploy.sh"; CODE_DIR="$2"; REPO_URL="$3"; BRANCH=dev; sync_source' _ "$SCRIPT_DIR" "$CODE_DIR" "$REPO_URL"; then exit 1; fi
    [[ "$(cat "$CODE_DIR/local-untracked")" == user-content ]] || exit 1
    mv "$CODE_DIR/local-untracked" "$root/preserved-untracked"
    git -C "$CODE_DIR" config user.name deploy-test
    git -C "$CODE_DIR" config user.email deploy-test@example.invalid
    printf local-ahead > "$CODE_DIR/marker"
    git -C "$CODE_DIR" commit -qam local-ahead
    if bash -c 'set -e; source "$1/deploy.sh"; CODE_DIR="$2"; REPO_URL="$3"; BRANCH=dev; sync_source' _ "$SCRIPT_DIR" "$CODE_DIR" "$REPO_URL"; then exit 1; fi
    [[ "$(cat "$CODE_DIR/marker")" == local-ahead ]] || exit 1
)
printf 'PASS: main-to-dev, latest/pinned archive, SHA validation, dirty worktree and unpushed commit guards\n'

# PID 行为单独模拟 /proc 边界，验证失效 PID 不发信号、身份不符拒绝停止。
(
    source "$SCRIPT_DIR/deploy.sh"
    PID_FILE="$root/pid-check"
    JAR_PATH="$root/app.jar"
    JAVA_BIN=/usr/bin/java
    test_stamp=100
    identity=valid
    signals=""
    cat() {
        if [[ "$1" == /proc/sys/kernel/random/boot_id ]]; then printf boot-current; else command cat "$@"; fi
    }
    process_stamp() { [[ -n "$test_stamp" ]] || return 1; printf '%s' "$test_stamp"; }
    matches_java() { [[ "$identity" == valid ]]; }
    kill() { signals+="$1:$2"; test_stamp=""; }
    printf '%s\n' 123 99 boot-current "$JAR_PATH" > "$PID_FILE"
    read_pid
    [[ ! -e "$PID_FILE" && -z "$MANAGED_PID" && -z "$signals" ]] || exit 1
    printf '%s\n' 123 100 boot-previous "$JAR_PATH" > "$PID_FILE"
    read_pid
    [[ ! -e "$PID_FILE" && -z "$signals" ]] || exit 1
    printf '%s\n' 123 100 boot-current "$JAR_PATH" > "$PID_FILE"
    identity=invalid
    if stop_service; then exit 1; fi
    [[ -e "$PID_FILE" && -z "$signals" ]] || exit 1
    identity=valid
    stop_service
    [[ "$signals" == '-TERM:123' && ! -e "$PID_FILE" ]] || exit 1
)
printf 'PASS: stale/reused PID, changed boot, identity mismatch and graceful stop\n'
printf 'All deployment verification scenarios passed.\n'
