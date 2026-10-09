[CmdletBinding()]
param(
    [ValidateSet('catalog', 'verify')][string]$Command = 'verify',
    [string]$File
)
$ErrorActionPreference = 'Stop'
$releaseRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$releaseWork = Join-Path $releaseRoot 'ucp-nocode/.work/release-20261009'
New-Item -ItemType Directory -Path $releaseWork -Force | Out-Null
$releaseClasspath = @(
    (Join-Path $releaseRoot 'ucp-server/src/main/resources'),
    (Join-Path $releaseRoot 'ucp-nocode/ucp-nocode-tools/target/classes'),
    (Get-Content -LiteralPath (Join-Path $releaseRoot 'ucp-nocode/.work/classpath.txt') -Raw).Trim()
) -join [IO.Path]::PathSeparator
$releaseQuote = { param($Value) '"' + $Value.Replace('\', '/') + '"' }
# 参数文件属于忽略目录内的运行材料，避免 Windows 长 classpath 超过启动限制。
$compileArguments = @('-proc:none', '-encoding', 'UTF-8', '-cp', (& $releaseQuote $releaseClasspath), '-d',
    (& $releaseQuote $releaseWork), (& $releaseQuote (Join-Path $PSScriptRoot 'ManualUpgradeVerification.java')),
    (& $releaseQuote (Join-Path $PSScriptRoot 'ManualUpgrade20261009Verification.java')))
$compileFile = Join-Path $releaseWork 'javac.args'
[IO.File]::WriteAllLines($compileFile, $compileArguments, [Text.UTF8Encoding]::new($false))
& javac ('@' + $compileFile)
if ($LASTEXITCODE -ne 0) { throw '发布 SQL 验证工具编译失败' }
if (-not $File) {
    $File = if ($Command -eq 'catalog') { Join-Path $releaseWork 'catalog.json' }
        else { Join-Path $releaseRoot 'sql/postgresql/manual/upgrade_20261009_v052_v079.sql' }
}
$javaArguments = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-cp', (& $releaseQuote ($releaseWork + [IO.Path]::PathSeparator + $releaseClasspath)),
    'ManualUpgrade20261009Verification', $Command, (& $releaseQuote $File))
$javaFile = Join-Path $releaseWork 'java.args'
[IO.File]::WriteAllLines($javaFile, $javaArguments, [Text.UTF8Encoding]::new($false))
Push-Location $releaseRoot
try {
    & java ('@' + $javaFile)
    if ($LASTEXITCODE -ne 0) { throw "发布 SQL $Command 失败" }
} finally { Pop-Location }
