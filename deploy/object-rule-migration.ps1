<#
.SYNOPSIS
在当前开发工程运行对象规则迁移工具；默认 dry-run，不写业务数据。
.EXAMPLE
./deploy/object-rule-migration.ps1 dry-run -Prefix demo_ -Out ./report.json -Build
.EXAMPLE
./deploy/object-rule-migration.ps1 compare -Report ./report.json -Actor 10001
.EXAMPLE
./deploy/object-rule-migration.ps1 apply -Report ./report.json -Actor 10001
.EXAMPLE
./deploy/object-rule-migration.ps1 apply -Report ./report.json -Actor 10001 -SuspendApplications 17,18
.NOTES
复用正式启动包的依赖及当前 os-server/src/main/resources 配置，由 Spring Boot 装配数据库与 Redis。
清理选项默认值必须在 dry-run 时显式传 -ClearOptionDefaults，随后检查报告再 apply。
若结构变化要求暂停应用，须显式传 -SuspendApplications 确认报告内的应用 ID；修订改变即拒绝，成功后恢复本次暂停的应用，失败整笔回滚。
apply / rollback / 非演练 linkage-readonly 默认要求 8080 端口停止监听；端口不同时传 -AppPort。
工具不会停止服务，也不会自行执行 SQL 版本迁移；报告与执行结果默认写入调用者当前目录。
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('dry-run', 'apply', 'rollback', 'compare', 'linkage-readonly')]
    [string]$Command = 'dry-run',
    [string]$Prefix,
    [string]$Report,
    [long]$Actor,
    [int]$Sample = 50,
    [string[]]$ObjectCodes,
    [string[]]$SuspendApplications,
    [string]$Out,
    [switch]$DryRun,
    [switch]$ClearOptionDefaults,
    [switch]$Build,
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1, 65535)][int]$AppPort = 8080,
    [switch]$AllowRunningService
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskServerRoot = Join-Path $taskProjectRoot 'os-server'
$taskOsJar = Join-Path $taskServerRoot 'os-server/target/os.jar'
$taskToolsJar = Join-Path $taskServerRoot 'os-nocode/os-nocode-tools/target/os.jar'
$taskConfigRoot = Join-Path $taskServerRoot 'os-server/src/main/resources'
$taskWork = Join-Path $taskServerRoot 'os-nocode/.work/object-rule-migration'
$taskNio = Join-Path $taskServerRoot 'os-nocode/.work/nio'

if ($ClearOptionDefaults -and $Command -ne 'dry-run') {
    throw '-ClearOptionDefaults 仅用于 dry-run；执行时按已经检查的报告处理。'
}
if ($SuspendApplications -and $Command -ne 'apply') {
    throw '-SuspendApplications 仅用于 apply，且只能指定当前报告内的应用 ID。'
}
if ($DryRun -and $Command -ne 'linkage-readonly') {
    throw '-DryRun 仅用于 linkage-readonly；普通预览直接使用 dry-run 子命令。'
}
if ($Command -ne 'dry-run' -and $Actor -le 0) { throw '此子命令需要 -Actor 指定有权限的用户 ID。' }
if ($Command -in @('apply', 'rollback', 'compare') -and -not $Report) { throw '请用 -Report 指定已检查的迁移报告。' }
if ($Report) { $Report = (Resolve-Path -LiteralPath $Report).Path }
if ($Command -eq 'linkage-readonly' -and -not $ObjectCodes) { throw '请用 -ObjectCodes 指定需要处理的对象编码。' }
if ($Sample -lt 1) { throw '-Sample 必须大于 0。' }

$taskJava = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $taskJava -PathType Leaf)) { throw "找不到 Java：$taskJava" }
$taskJavaVersion = (& $taskJava -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $taskJavaVersion -notmatch 'version "21(?:\.|\")') {
    throw '对象规则迁移工具需要 JDK 21，请用 -JavaHome 指定 JDK 21 目录；脚本不会修改系统环境变量。'
}

$taskWrites = $Command -in @('apply', 'rollback') -or ($Command -eq 'linkage-readonly' -and -not $DryRun)
if ($taskWrites -and -not $AllowRunningService) {
    $taskListening = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if ($taskListening | Where-Object Port -eq $AppPort) {
        throw "端口 $AppPort 仍在监听；请在停服窗口执行 $Command，脚本不会自动停止服务。"
    }
}

if ($Build) {
    $taskPreviousJavaHome = $env:JAVA_HOME
    Push-Location $taskServerRoot
    try {
        # 只在当前构建进程中选择已经指定的 JDK，不改用户的系统环境。
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
        & mvn -B '-Dmaven.test.skip=true' -pl os-server,os-nocode/os-nocode-tools -am package
        if ($LASTEXITCODE -ne 0) { throw '对象规则迁移工具构建失败。' }
    } finally { $env:JAVA_HOME = $taskPreviousJavaHome; Pop-Location }
}
foreach ($taskRequired in @($taskOsJar, $taskToolsJar)) {
    if (-not (Test-Path -LiteralPath $taskRequired -PathType Leaf)) {
        throw "找不到构建产物 $taskRequired；首次运行请加 -Build。"
    }
}
if (-not $Out) { $Out = if ($Command -eq 'dry-run') { 'report.json' } else { "$Command.json" } }
$taskOutput = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
if (-not (Test-Path -LiteralPath (Split-Path -Parent $taskOutput) -PathType Container)) {
    throw "输出目录不存在：$taskOutput"
}
$taskToolArgs = @($Command, '--out', $taskOutput)
if ($Command -eq 'dry-run') {
    if ($Prefix) { $taskToolArgs += @('--prefix', $Prefix) }
    if ($ClearOptionDefaults) { $taskToolArgs += '--clear-option-defaults' }
} else {
    $taskToolArgs += @('--actor', $Actor.ToString())
    if ($Command -eq 'linkage-readonly') {
        $taskToolArgs += @('--object-codes', ($ObjectCodes -join ','))
        if ($DryRun) { $taskToolArgs += '--dry-run' }
    } else {
        $taskToolArgs += @('--report', $Report)
        if ($SuspendApplications) { $taskToolArgs += @('--suspend-applications', ($SuspendApplications -join ',')) }
        if ($Command -eq 'compare') { $taskToolArgs += @('--sample', $Sample.ToString()) }
    }
}

# PropertiesLauncher 复用正式包 classpath；外置源码配置与正常本地开发入口一致，绝不自行解析 YAML。
$taskConfigUri = ([Uri]([IO.Path]::GetFullPath($taskConfigRoot) + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
# 与本地服务入口一致，避开 Windows 长临时目录导致的 Java 21 NIO 套接字错误。
New-Item -ItemType Directory -Path $taskNio -Force | Out-Null
$taskJavaArgs = @('-Xmx1g', '-Dfile.encoding=UTF-8',
    "-Djdk.net.unixdomain.tmpdir=$($taskNio.Replace('\', '/'))",
    "-Dspring.config.additional-location=$taskConfigUri",
    "-Dloader.path=$($taskToolsJar.Replace('\', '/'))",
    '-Dloader.main=com.richuang.os.nocode.tools.ObjectRuleMigrationTool',
    '-cp', $taskOsJar, 'org.springframework.boot.loader.launch.PropertiesLauncher') + $taskToolArgs
New-Item -ItemType Directory -Path $taskWork -Force | Out-Null
$taskArgFile = Join-Path $taskWork 'run.args'
# Java 参数文件逐项引用，路径含空格时也无需拼接 shell 命令。
$taskQuotedArgs = $taskJavaArgs | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }
[IO.File]::WriteAllLines($taskArgFile, $taskQuotedArgs, [Text.UTF8Encoding]::new($false))
& $taskJava ('@' + $taskArgFile)
if ($LASTEXITCODE -ne 0) { throw "对象规则迁移工具退出码 $LASTEXITCODE；详见本次输出，未继续执行后续操作。" }
