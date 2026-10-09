[CmdletBinding()]
param(
    [string[]]$Classes = @('TaskEntryIntegrationTest'),
    [string[]]$CleanupTaskEntryApplications,
    [switch]$CompileSelected,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
# 专项测试使用 reactor 构建的真实类路径，避免无关模块测试编译失败阻断本模块。
# 不替代全工程 Maven 测试；两者结果在交付记录中分别报告。
$taskServerRoot = Split-Path -Parent $PSScriptRoot
$taskClassPathFile = Join-Path $PSScriptRoot '.work/classpath.txt'
if ($Build -or -not (Test-Path -LiteralPath $taskClassPathFile)) {
    & (Join-Path $PSScriptRoot 'run.ps1') info -Build
    if ($LASTEXITCODE -ne 0) { throw '专项测试依赖构建失败' }
}
$taskRunnerRoot = Join-Path $PSScriptRoot '.work/test-runner'
New-Item -ItemType Directory -Path $taskRunnerRoot -Force | Out-Null
$taskConsole = Join-Path $taskRunnerRoot 'junit-platform-console-standalone-1.12.2.jar'
if (-not (Test-Path -LiteralPath $taskConsole)) {
    # 下载独立测试工具不应解析业务聚合的 BOM（非 reactor 模式无法解析本地版本）。
    $taskBootstrap = Join-Path $taskRunnerRoot 'tool-bootstrap.pom.xml'
    [IO.File]::WriteAllText($taskBootstrap, '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>local.tools</groupId><artifactId>test-runner</artifactId><version>1</version></project>', [Text.UTF8Encoding]::new($false))
    Push-Location $taskServerRoot
    try {
        & mvn -B -f $taskBootstrap org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy '-Dartifact=org.junit.platform:junit-platform-console-standalone:1.12.2' "-DoutputDirectory=$taskRunnerRoot"
        if ($LASTEXITCODE -ne 0) { throw 'JUnit 专项运行器准备失败' }
    } finally { Pop-Location }
}
$taskTestClasses = Join-Path $taskRunnerRoot 'classes'
if (Test-Path -LiteralPath $taskTestClasses) {
    $taskResolved = (Resolve-Path -LiteralPath $taskTestClasses).Path
    $taskAllowedRoot = [IO.Path]::GetFullPath($taskRunnerRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $taskResolved.StartsWith($taskAllowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw '测试产物路径不在允许目录内'
    }
    Remove-Item -LiteralPath $taskResolved -Recurse -Force
}
New-Item -ItemType Directory -Path $taskTestClasses -Force | Out-Null
$taskTools = Join-Path $PSScriptRoot 'ucp-nocode-tools'
$taskClasspath = @((Join-Path $taskServerRoot 'ucp-server/src/main/resources'),
    (Join-Path $taskTools 'src/test/resources'), (Join-Path $taskTools 'target/classes'),
    (Get-Content -LiteralPath $taskClassPathFile -Raw).Trim(), $taskTestClasses, $taskConsole) -join [IO.Path]::PathSeparator
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskTools 'src/test/java') -Recurse -File -Filter '*.java')
$taskSourceRoot = Join-Path $taskTools 'src/test/java'
if ($CompileSelected) {
    if ($CleanupTaskEntryApplications) { throw '定向编译不能用于夹具清理入口' }
    # 显式选择的源码及其源码依赖一起编译；不冒充全量测试源码检查。
    $taskSources = @($Classes | ForEach-Object {
        if ($_ -match '^[A-Za-z][A-Za-z0-9_]+$') {
            $taskSourceClass = 'com.lingan.ucp.nocode.tools.' + $_
        } elseif ($_ -match '^com\.lingan\.ucp\.nocode\.(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*Test$') {
            $taskSourceClass = $_
        } else { throw '定向编译请提供 tools 测试类名或无代码包内完整测试类名' }
        Get-Item -LiteralPath (Join-Path $taskSourceRoot ($taskSourceClass.Replace('.', '/') + '.java'))
    })
    Write-Output "仅编译指定的 $($taskSources.Count) 个测试入口及其源码依赖；不代表全量测试源码编译通过。"
}
$taskCompileArgs = @('-encoding', 'UTF-8', '-parameters', '-proc:none', '-cp', ('"' + $taskClasspath.Replace('\', '/') + '"'),
    '-d', ('"' + $taskTestClasses.Replace('\', '/') + '"'))
if ($CompileSelected) {
    $taskCompileArgs += @('-sourcepath', ('"' + $taskSourceRoot.Replace('\', '/') + '"'))
}
$taskCompileArgs += $taskSources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
$taskCompileFile = Join-Path $taskRunnerRoot 'compile.args'
[IO.File]::WriteAllLines($taskCompileFile, $taskCompileArgs, [Text.UTF8Encoding]::new($false))
& javac ('@' + $taskCompileFile)
if ($LASTEXITCODE -ne 0) { throw '无代码测试源码编译失败' }
if ($CleanupTaskEntryApplications) {
    $taskCleanupArgs = @('-cp', ('"' + $taskClasspath.Replace('\', '/') + '"'), 'com.lingan.ucp.nocode.tools.TaskEntryFixtureCleaner') + $CleanupTaskEntryApplications
    $taskCleanupFile = Join-Path $taskRunnerRoot 'cleanup.args'
    [IO.File]::WriteAllLines($taskCleanupFile, $taskCleanupArgs, [Text.UTF8Encoding]::new($false))
    & java ('@' + $taskCleanupFile)
    if ($LASTEXITCODE -ne 0) { throw '验收夹具清理未完成；禁止扩大范围重试' }
    return
}
$taskRunArgs = @('-cp', ('"' + $taskClasspath.Replace('\', '/') + '"'), 'org.junit.platform.console.ConsoleLauncher',
    'execute', '--disable-banner', '--disable-ansi-colors', '--details=summary', '--fail-if-no-tests', '--include-engine=junit-jupiter',
    ('--reports-dir="' + (Join-Path $taskRunnerRoot 'reports').Replace('\', '/') + '"'))
foreach ($taskClass in $Classes) {
    if ($taskClass -match '^[A-Za-z][A-Za-z0-9_]+$') {
        $taskQualifiedClass = 'com.lingan.ucp.nocode.tools.' + $taskClass
    } elseif ($taskClass -match '^com\.lingan\.ucp\.nocode\.(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*Test$') {
        # 包内纯函数测试可与被测类同包；兼容原 tools 简单类名用法。
        $taskQualifiedClass = $taskClass
    } else { throw '请提供 tools 测试类名，或 com.lingan.ucp.nocode 内的完整测试类名' }
    $taskRunArgs += '--select-class=' + $taskQualifiedClass
}
$taskRunFile = Join-Path $taskRunnerRoot 'run.args'
[IO.File]::WriteAllLines($taskRunFile, $taskRunArgs, [Text.UTF8Encoding]::new($false))
& java ('@' + $taskRunFile)
if ($LASTEXITCODE -ne 0) { throw '无代码专项测试未通过，详见本次 JUnit 报告' }
