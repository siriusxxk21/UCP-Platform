param([switch]$Check)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$formatterDir = Join-Path $PSScriptRoot '.work/formatter'
$formatterJar = Join-Path $formatterDir 'google-java-format-1.24.0-all-deps.jar'
if (-not (Test-Path -LiteralPath $formatterJar)) {
    Push-Location $serverRoot
    try {
        # 固定开发工具版本；不会添加到后端运行依赖。
        & mvn -B -N dependency:copy '-Dartifact=com.google.googlejavaformat:google-java-format:1.24.0:jar:all-deps' "-DoutputDirectory=$formatterDir"
        if ($LASTEXITCODE -ne 0) { throw 'Formatter preparation failed' }
    } finally { Pop-Location }
}
# 文件清单和平台路径规则统一由 Node 入口维护，避免两套扫描逻辑漂移。
$formatArgs = @()
if ($Check) { $formatArgs += '--check' }
& node (Join-Path $PSScriptRoot 'format.mjs') @formatArgs
if ($LASTEXITCODE -ne 0) { throw 'Nocode Java format check failed' }
