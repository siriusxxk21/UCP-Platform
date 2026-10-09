param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$TokenFile,
    [string]$Output = (Join-Path $PSScriptRoot '.work/http-results.json')
)
$ErrorActionPreference = 'Stop'
$adminToken = $AccessToken
if (-not $adminToken -and $TokenFile) { $adminToken = (Get-Content -LiteralPath $TokenFile -Raw | ConvertFrom-Json).accessToken }
if (-not $adminToken) { throw 'Provide AccessToken or NOCODE_VERIFY_TOKEN from a normal authenticated session.' }
$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$checks = [Collections.Generic.List[object]]::new()
$roleId = $null
$userId = $null
$completed = $false
function Call-Api([string]$Method, [string]$Path, $Body, [string]$Token = $adminToken) {
    $headers = @{}
    if ($Token) { $headers.Authorization = 'Bearer ' + $Token }
    $args = @{ Uri = $BaseUrl + $Path; Method = $Method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 20 }
    if ($null -ne $Body) { $args.ContentType = 'application/json'; $args.Body = ConvertTo-Json -InputObject $Body -Depth 20 -Compress }
    $response = Invoke-WebRequest @args
    try { $value = $response.Content | ConvertFrom-Json } catch { $value = $null }
    return @{ status = [int]$response.StatusCode; code = $value.code; data = $value.data; message = $value.msg }
}
function Require-Success($Response, [string]$Step) {
    if ($Response.code -ne 0) { throw "$Step failed: HTTP=$($Response.status), code=$($Response.code), $($Response.message)" }
    return $Response.data
}
function Check([string]$Name, [bool]$Passed) {
    $checks.Add(@{ name = $Name; passed = $Passed })
    if (-not $Passed) { throw "Check failed: $Name" }
}
try {
    $identity = Require-Success (Call-Api GET '/system/auth/get-permission-info' $null) 'admin identity'
    Check 'Real admin login and identity' ($null -ne $identity.user.id)
    $menus = Require-Success (Call-Api GET '/system/menu/list' $null) 'system menus'
    $queryMenu = @($menus | Where-Object permission -eq 'nocode:object:query')
    $allPermissions = @($menus | Where-Object permission -like 'nocode:object:*' | ForEach-Object { $_.id })
    Check 'Data center object permissions loaded by current system' ($queryMenu.Count -eq 1 -and $allPermissions.Count -ge 7)
    $proxy = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/system/auth/get-permission-info' -Headers @{Authorization='Bearer '+$adminToken} -TimeoutSec 20
    Check 'Frontend proxy reaches the same authenticated backend' ($proxy.code -eq 0 -and [string]$proxy.data.user.id -eq [string]$identity.user.id)
    foreach ($request in @(@('GET','/nocode/object/page'),@('GET','/nocode/object/get?id=1'),@('POST','/nocode/object/create'),@('PUT','/nocode/object/save-draft'))) {
        $body = if ($request[0] -in @('POST','PUT')) { @{} } else { $null }
        $response = Call-Api $request[0] $request[1] $body ''
        Check ("Anonymous rejected: "+$request[0]+' '+$request[1]) ($response.status -eq 401 -or $response.code -eq 401)
    }
    $payload = @{
        id=$null; expectedLockVersion=$null; objectCode="verify_b1_$runId"; objectName='B1 联调验证';
        description='临时接口验收对象'; tableName="biz_verify_b1_$runId"; titleFieldKey='new-title'; removedFieldIds=@();
        fields=@(@{key='new-title'; id=$null; code='name'; name='名称'; type='TEXT'; length=200; precision=$null; scale=$null; required=$true; unique=$false; sort=0})
    }
    $object = Require-Success (Call-Api POST '/nocode/object/create' $payload) 'create draft'
    Check 'Create draft with string IDs and DRAFT state' ($object.id -is [string] -and $object.state -eq 'DRAFT' -and $object.lockVersion -eq 0)
    $loaded = Require-Success (Call-Api GET ("/nocode/object/get?id="+$object.id) $null) 'load draft'
    Check 'Database-backed read returns stable title field' ($loaded.titleFieldId -eq $object.titleFieldId -and $loaded.fields.Count -eq 1)
    $duplicate = Call-Api POST '/nocode/object/create' $payload
    Check 'Duplicate object rejected through HTTP' ($duplicate.code -eq 1050000003)
    $roleId = Require-Success (Call-Api POST '/system/role/create' @{name="B1验收$runId";code="nocode_verify_$runId";sort=999;status=0;remark='B1 临时权限验收'}) 'create test role'
    $initialPassword = 'Nc1' + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $username = 'ncv' + $runId
    $userId = Require-Success (Call-Api POST '/system/user/create' @{username=$username;nickname='B1 临时验收';password=$initialPassword;remark="nocode_verify_$runId";postIds=@();sex=0}) 'create test user'
    $null = Require-Success (Call-Api POST '/system/permission/assign-user-role' @{userId=$userId;roleIds=@($roleId)}) 'assign test role'
    $readMenus = @($queryMenu[0].id, $queryMenu[0].parentId)
    $null = Require-Success (Call-Api POST '/system/permission/assign-role-menu' @{roleId=$roleId;menuIds=$readMenus}) 'assign read permission'
    $testAuth = Require-Success (Call-Api POST '/system/auth/login' @{username=$username;password=$initialPassword} '') 'test user normal login'
    if (-not $testAuth.accessToken -and $testAuth.passwordChangeToken) {
        $newPassword = 'Nc2' + [guid]::NewGuid().ToString('N').Substring(0, 12)
        $testAuth = Require-Success (Call-Api PUT '/system/auth/change-required-password' @{passwordChangeToken=$testAuth.passwordChangeToken;newPassword=$newPassword} '') 'normal required password change'
    }
    Check 'Test user obtained a normal authenticated session' (-not [string]::IsNullOrWhiteSpace($testAuth.accessToken))
    $readerToken = $testAuth.accessToken
    $read = Call-Api GET ("/nocode/object/get?id="+$object.id) $null $readerToken
    Check 'Read-only role can read definitions' ($read.code -eq 0)
    $update = @{
        id=$object.id; expectedLockVersion=$object.lockVersion; objectCode=$object.objectCode; objectName='接口修改后';
        description=$object.description; tableName=$object.tableName; titleFieldKey=$object.titleFieldId; fields=@(); removedFieldIds=@()
    }
    foreach($request in @(@('POST','/nocode/object/create'),@('PUT','/nocode/object/save-draft'))) {
        $body = if ($request[0] -eq 'POST') { $payload } else { $update }
        $response = Call-Api $request[0] $request[1] $body $readerToken
        Check ('Read-only write rejected: '+$request[0]) ($response.status -eq 403 -or $response.code -eq 403)
    }
    foreach ($request in @(@('POST','/nocode/design/save'), @('POST','/nocode/design/plan'), @('POST','/nocode/design/execute'), @('POST','/nocode/design/disable'), @('POST','/nocode/table/adopt'), @('GET','/nocode/table/preview?schema=public&name=system_user'))) {
        $body = if ($request[0] -eq 'POST') { @{ id=$object.id; draft=$payload } } else { $null }
        if ($request[1] -eq '/nocode/design/save') { $body = @{ draft=$payload } }
        $response = Call-Api $request[0] $request[1] $body $readerToken
        Check ('Read-only data center operation rejected: ' + $request[1]) ($response.status -eq 403 -or $response.code -eq 403)
    }
    $catalog = Require-Success (Call-Api GET '/nocode/table/page?pageNo=1&pageSize=10' $null) 'table catalog'
    Check 'Catalog hides base and workflow system tables by default' (@($catalog.list | Where-Object system).Count -eq 0)
    $protectedPreview = Call-Api GET '/nocode/table/preview?schema=public&name=system_user' $null
    Check 'Even admin cannot preview system rows through data center' ($protectedPreview.code -ne 0)
    $protectedAdoption = Call-Api GET '/nocode/table/preflight?schema=public&name=system_user' $null
    Check 'System table cannot be adopted as a business object' ($protectedAdoption.code -ne 0 -or -not $protectedAdoption.data.allowed)
    $advanced = Require-Success (Call-Api GET ("/nocode/design/get?id="+$object.id) $null) 'advanced design read'
    Check 'Advanced design exposes base settings and same stable object identity' ($advanced.draft.id -eq $object.id -and $advanced.source -eq 'GENERATED')
    # 仅授予建模和目录查询权限，证明首次绑定明细不能借保存设计绕过纳管权限。
    $modelingCodes = @('nocode:object:query','nocode:object:create','nocode:object:update','nocode:table:query')
    $modelingMenus = @($menus | Where-Object { $_.permission -in $modelingCodes })
    Check 'Restricted modeling permissions exist' ($modelingMenus.Count -eq $modelingCodes.Count)
    $null = Require-Success (Call-Api POST '/system/permission/assign-role-menu' @{roleId=$roleId;menuIds=@($modelingMenus.id)+@($queryMenu[0].parentId)}) 'grant modeling without adoption'
    $deniedBinding = @{
        draft=$update; settings=$advanced.settings; fieldOptions=$advanced.fieldOptions; relations=@(); indexes=@();
        details=@(@{id=$null;code='items';name='权限验收明细';tableName="biz_verify_b1_child_$runId";state='ACTIVE';fields=@();fieldOptions=@{};indexes=@();
            binding=@{source='ADOPTED';schemaName='public';keyColumn='id';parentColumn='parent_id';structureMode='RETAIN';readOnly=$true;repairBaseFields=$false;fingerprint='permission-check'}})
    }
    $bindResponse = Call-Api POST '/nocode/design/save' $deniedBinding $readerToken
    Check 'Modeling without adoption cannot bind an existing detail' ($bindResponse.status -eq 403 -or $bindResponse.code -eq 403)
    $preflightResponse = Call-Api GET '/nocode/table/preflight?schema=public&name=permission_check' $null $readerToken
    Check 'Adoption preflight uses the same restricted grant' ($preflightResponse.status -eq 403 -or $preflightResponse.code -eq 403)
    $afterDeniedBinding = Require-Success (Call-Api GET ("/nocode/design/get?id="+$object.id) $null) 'read after denied binding'
    Check 'Denied binding leaves draft and details unchanged' ($afterDeniedBinding.draft.lockVersion -eq 0 -and $afterDeniedBinding.details.Count -eq 0)
    $null = Require-Success (Call-Api POST '/system/permission/assign-role-menu' @{roleId=$roleId;menuIds=@($allPermissions)+@($queryMenu[0].parentId)}) 'enable editor role'
    $changed = Require-Success (Call-Api PUT '/nocode/object/save-draft' $update $readerToken) 'editor update'
    Check 'Existing session uses newly granted permission and increments revision' ($changed.lockVersion -eq 1 -and $changed.fields.Count -eq 1)
    $stale = Call-Api PUT '/nocode/object/save-draft' $update $readerToken
    Check 'Stale HTTP update rejected' ($stale.code -eq 1050000004)
    $update.expectedLockVersion = $changed.lockVersion
    $update.actorId = '1'
    $injection = Call-Api PUT '/nocode/object/save-draft' $update
    Check 'Protected extra request property rejected' ($injection.code -eq 1050000001)
    $update.Remove('actorId')
    $unknownQuery = Call-Api GET '/nocode/object/page?sql=select' $null
    Check 'Unknown query parameter rejected' ($unknownQuery.code -eq 1050000001)
    $null = Require-Success (Call-Api POST '/system/permission/assign-role-menu' @{roleId=$roleId;menuIds=@()}) 'revoke all object permissions'
    $revokedRead = Call-Api GET ("/nocode/object/get?id="+$object.id) $null $readerToken
    $revokedWrite = Call-Api PUT '/nocode/object/save-draft' $update $readerToken
    Check 'Revoked role cannot read or write using existing session' (($revokedRead.status -eq 403 -or $revokedRead.code -eq 403) -and ($revokedWrite.status -eq 403 -or $revokedWrite.code -eq 403))
    $final = Require-Success (Call-Api GET ("/nocode/object/get?id="+$object.id) $null) 'read after denied writes'
    Check 'Rejected writes leave persisted revision unchanged' ($final.lockVersion -eq 1)
    $users = Call-Api GET '/system/user/page?pageNo=1&pageSize=1' $null
    $bpm = Call-Api GET '/bpm/category/page?pageNo=1&pageSize=1' $null
    Check 'Existing system user and BPM category queries remain available' ($users.code -eq 0 -and $bpm.code -eq 0)
    $null = Require-Success (Call-Api POST '/nocode/design/delete' @{id=$final.id;expectedLockVersion=$final.lockVersion;reason='清理本轮接口验收对象'}) 'remove temporary object'
    $completed = $true
} finally {
    $cleanup = @()
    if ($userId) { $cleanup += (Call-Api DELETE ("/system/user/delete?id="+$userId) $null).code }
    if ($roleId) { $cleanup += (Call-Api DELETE ("/system/role/delete?id="+$roleId) $null).code }
    $report = @{ capturedAt=(Get-Date).ToString('o'); runId=$runId; completed=$completed; checks=$checks.ToArray(); passed=@($checks | Where-Object passed).Count; failed=@($checks | Where-Object {-not $_.passed}).Count; testAccountCleanupCodes=$cleanup; temporaryObjectCode="verify_b1_$runId" }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Output -Encoding utf8
    Write-Output ("HTTP checks passed="+$report.passed+"; failed="+$report.failed+"; runId="+$runId)
}
