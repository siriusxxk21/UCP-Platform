param([switch]$Build, [switch]$Restart)
$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $PSScriptRoot
$taskWork = Join-Path $PSScriptRoot '.work'
New-Item -ItemType Directory -Path $taskWork -Force | Out-Null
$cpFile = Join-Path $taskWork 'server-classpath.txt'
$pidFile = Join-Path $taskWork 'server.pid'
if (Test-Path -LiteralPath $pidFile) {
    $previousId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $previous = Get-CimInstance Win32_Process -Filter "ProcessId=$previousId" -ErrorAction SilentlyContinue
    if ($previous) {
        $expectedArgs = Join-Path $taskWork 'server.args'
        if ($previous.Name -ne 'java.exe' -or -not $previous.CommandLine.Contains($expectedArgs)) {
            throw 'PID file points to another process; stop the intended server manually.'
        }
        if (-not $Restart) { throw 'Server is already running. Use -Restart to reload the compiled code.' }
        Stop-Process -Id $previousId
        Wait-Process -Id $previousId -Timeout 15 -ErrorAction SilentlyContinue
    }
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is in use; stop the existing backend before starting this one.'
}
# 先停止已确认的旧服务，再重建 target/classes，避免运行中的类加载遇到半成品。
if ($Build -or -not (Test-Path -LiteralPath $cpFile)) {
    Push-Location $serverRoot
    try {
        & mvn -B '-Dmaven.test.skip=true' -pl os-server -am compile dependency:build-classpath "-Dmdep.outputFile=$cpFile" '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Server build failed' }
    } finally { Pop-Location }
}
# Windows packaged-app temp paths may fail Java 21 Unix-domain selector sockets.
$nioTemp = Join-Path $taskWork 'nio'
New-Item -ItemType Directory -Path $nioTemp -Force | Out-Null
$cp = (Join-Path $serverRoot 'os-server/target/classes') + ';' + (Get-Content -LiteralPath $cpFile -Raw).Trim()
$argsPath = Join-Path $taskWork 'server.args'
$javaArgs = @(
    ('"-Djdk.net.unixdomain.tmpdir=' + $nioTemp.Replace('\','/') + '"'),
    '-Dspring.devtools.restart.enabled=false', '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
    '-cp', ('"' + $cp.Replace('\','/') + '"'), 'com.richuang.os.OSServerApplication', '--spring.profiles.active=os'
)
[IO.File]::WriteAllLines($argsPath, $javaArgs, [Text.UTF8Encoding]::new($false))
$process = Start-Process -FilePath (Get-Command java).Source -ArgumentList ('@"' + $argsPath + '"') -WorkingDirectory $serverRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskWork 'server.stdout.log') -RedirectStandardError (Join-Path $taskWork 'server.stderr.log') -PassThru
[IO.File]::WriteAllText($pidFile, [string]$process.Id)
Write-Output "Server launched, PID=$($process.Id). Check .work/server.stdout.log for Started OSServerApplication."
