[CmdletBinding()]
param(
    [ValidateSet('info', 'migrate', 'verify', 'dump', 'dump-all')][string]$Command = 'verify',
    [string]$Output,
    [string]$PgBin = 'E:/Program Files/PostgreSQL/17/bin',
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$taskWork = Join-Path $PSScriptRoot '.work'
New-Item -ItemType Directory -Path $taskWork -Force | Out-Null
$classpathFile = Join-Path $taskWork 'classpath.txt'
if ($Build -or -not (Test-Path -LiteralPath $classpathFile)) {
    Push-Location $serverRoot
    try {
        # 仅清理工具产物，避免已移动或重新编号的未执行 SQL 残留在 classpath。
        & mvn -B -pl os-nocode/os-nocode-tools clean
        if ($LASTEXITCODE -ne 0) { throw 'Nocode tools clean failed' }
        & mvn -B '-Dmaven.test.skip=true' -pl os-nocode/os-nocode-tools -am compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=test'
        if ($LASTEXITCODE -ne 0) { throw 'Nocode build failed' }
    } finally { Pop-Location }
}
# 使用正式启动模块的原始配置资源，由 Spring Boot 处理 profile 和环境变量。
# 工具不维护自己的 application*.yml，也不接收独立数据库配置路径。
$serverResources = Join-Path $serverRoot 'os-server/src/main/resources'
$taskClasspath = @($serverResources, (Join-Path $PSScriptRoot 'os-nocode-tools/target/classes'), (Get-Content -LiteralPath $classpathFile -Raw).Trim()) -join [IO.Path]::PathSeparator
$javaArgs = @('-cp', ('"' + $taskClasspath.Replace('\', '/') + '"'), 'com.richuang.os.nocode.tools.NocodeDatabaseTool', $Command)
if ($Command -in @('dump', 'dump-all')) {
    if (-not $Output) {
        $dumpName = if ($Command -eq 'dump-all') { 'database-full.sql' } else { 'public.sql' }
        $Output = Join-Path (Join-Path $serverRoot 'sql/full') $dumpName
    }
    $javaArgs += @(('"' + $Output.Replace('\', '/') + '"'), ('"' + $PgBin.Replace('\', '/') + '"'))
}
$argFile = Join-Path $taskWork 'database-tool.args'
[IO.File]::WriteAllLines($argFile, $javaArgs, [Text.UTF8Encoding]::new($false))
& java ('@' + $argFile)
if ($LASTEXITCODE -ne 0) { throw "Nocode database $Command failed" }
