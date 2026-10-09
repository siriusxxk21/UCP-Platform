param(
    [Parameter(Mandatory)][string]$ApplicationId,
    [Parameter(Mandatory)][string]$ObjectId,
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$Output = (Join-Path $PSScriptRoot '.work/application-http-results.json')
)
# 可复用运行验收：指定拥有文本标题和整数明细的验收对象，只清理本轮创建的记录和账号。
$ErrorActionPreference = 'Stop'
if (-not $AccessToken) { throw 'Provide an authenticated AccessToken.' }
$runId = [guid]::NewGuid().ToString('N').Substring(0, 12)
$checks = [Collections.Generic.List[object]]::new()
$ownedRecord = $null
$userId = $null
$memberRecord = $null
$policyModified = $false
$completed = $false
function Api([string]$Method, [string]$Path, $Body, [string]$Token = $AccessToken) {
    $headers = @{}
    if ($Token) { $headers.Authorization = 'Bearer ' + $Token }
    $args = @{Uri=$BaseUrl+$Path; Method=$Method; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=30}
    if ($null -ne $Body) { $args.ContentType='application/json'; $args.Body=ConvertTo-Json -InputObject $Body -Depth 30 -Compress }
    $response = Invoke-WebRequest @args
    try { $value=$response.Content | ConvertFrom-Json } catch { $value=$null }
    return @{http=[int]$response.StatusCode; code=$value.code; data=$value.data; message=$value.msg}
}
function Good($Response) {
    if ($Response.code -ne 0) { throw "HTTP=$($Response.http), code=$($Response.code), $($Response.message)" }
    return $Response.data
}
function Check([string]$Name, [bool]$Pass) {
    $checks.Add(@{name=$Name; passed=$Pass})
    if (-not $Pass) { throw "Check failed: $Name" }
}
try {
    $query=@{applicationId=$ApplicationId; objectId=$ObjectId; pageNo=1; pageSize=20; descending=$false}
    $model = Good (Api GET "/nocode/runtime/model?applicationId=$ApplicationId&objectId=$ObjectId" $null)
    $title=@($model.object.fields | Where-Object { $_.id -eq $model.object.titleFieldId -and $_.type -eq 'TEXT' })
    if ($title.Count -ne 1 -or -not $model.writable) { throw 'Use a writable acceptance object with a TEXT title.' }
    $child=@($model.object.details | Where-Object state -eq 'ACTIVE')[0]
    $quantity=@($child.fields | Where-Object type -eq 'INTEGER')[0]
    if (-not $quantity) { throw 'Use an acceptance object with an INTEGER internal-detail field.' }
    $draft=@{applicationId=$ApplicationId; objectId=$ObjectId; id=$null; expectedRevision=$null; values=@{}; details=@{}}
    $draft.values[$title[0].id]="HTTP验收$runId"
    $draft.details[$child.id]=@(@{id=$null;revision=$null;values=@{ $quantity.id='2' }})
    $saved=Good (Api POST '/nocode/runtime/save' $draft)
    $ownedRecord=$saved.record
    Check 'HTTP main/detail transaction saved' ($saved.details.($child.id)[0].values.($quantity.id) -eq '2')
    $read=Good (Api GET "/nocode/runtime/get?applicationId=$ApplicationId&objectId=$ObjectId&id=$($ownedRecord.id)" $null)
    Check 'Stable IDs and revisions round trip through HTTP' ($read.record.id -eq $ownedRecord.id -and $read.record.revision -eq $ownedRecord.revision)
    $draft.id=$ownedRecord.id; $draft.expectedRevision=$ownedRecord.revision
    $draft.values[$title[0].id]="HTTP修改$runId"
    $draft.details[$child.id]=@(@{id=$saved.details.($child.id)[0].id;revision=$saved.details.($child.id)[0].revision;values=@{$quantity.id='4'}})
    $changed=Good (Api POST '/nocode/runtime/save' $draft)
    $ownedRecord=$changed.record
    Check 'HTTP edits preserve internal-detail identity' ($changed.details.($child.id)[0].id -eq $saved.details.($child.id)[0].id)
    $stale=Api POST '/nocode/runtime/save' $draft
    Check 'Stale revision rejected' ($stale.code -ne 0)
    $draft.expectedRevision=$ownedRecord.revision
    $draft.actorId='1'
    Check 'Caller cannot inject actor identity' ((Api POST '/nocode/runtime/save' $draft).code -ne 0)
    $draft.Remove('actorId')
    $draft.values['creator']='someone-else'
    Check 'Caller cannot write an undeclared audit field' ((Api POST '/nocode/runtime/save' $draft).code -ne 0)
    $draft.values.Remove('creator')
    foreach ($path in @("/nocode/runtime/application?id=$ApplicationId", "/nocode/runtime/model?applicationId=$ApplicationId&objectId=$ObjectId", "/nocode/runtime/get?applicationId=$ApplicationId&objectId=$ObjectId&id=$($ownedRecord.id)")) {
        $denied=Api GET $path $null ''
        Check ('Anonymous GET rejected '+$path.Split('?')[0]) ($denied.http -eq 401 -or $denied.code -eq 401)
    }
    foreach ($path in @('/nocode/runtime/page','/nocode/runtime/save','/nocode/runtime/delete')) {
        $denied=Api POST $path @{} ''
        Check ('Anonymous POST rejected '+$path) ($denied.http -eq 401 -or $denied.code -eq 401)
    }
    $password='Nc1'+[guid]::NewGuid().ToString('N').Substring(0,12)
    $username='nca'+$runId
    $userId=Good (Api POST '/system/user/create' @{username=$username;nickname='应用权限验收';password=$password;remark="应用验收$runId";postIds=@();sex=0})
    $auth=Good (Api POST '/system/auth/login' @{username=$username;password=$password} '')
    if (-not $auth.accessToken -and $auth.passwordChangeToken) {
        $auth=Good (Api PUT '/system/auth/change-required-password' @{passwordChangeToken=$auth.passwordChangeToken;newPassword='Nc2'+[guid]::NewGuid().ToString('N').Substring(0,12)} '')
    }
    if (-not $auth.accessToken) { throw 'Test account did not obtain a normal login session.' }
    foreach ($path in @('/nocode/runtime/page','/nocode/runtime/save')) {
        $body=if ($path.EndsWith('/page')) {$query} else {$draft}
        Check ('Unassigned user rejected '+$path) ((Api POST $path $body $auth.accessToken).code -ne 0)
    }
    $after=Good (Api GET "/nocode/runtime/get?applicationId=$ApplicationId&objectId=$ObjectId&id=$($ownedRecord.id)" $null)
    Check 'Denied requests leave the record unchanged' ($after.record.revision -eq $ownedRecord.revision)
    $policy = Good (Api GET "/nocode/application/authorization?id=$ApplicationId" $null)
    $grant=@{objectId=$ObjectId;actions=@('READ','CREATE','UPDATE','DELETE');scope='OWN';readFields=@($title[0].id);writeFields=@($title[0].id);readDetails=@($child.id);writeDetails=@($child.id)}
    $member=@{principalKind='USER';principalId=[string]$userId;objects=@($grant)}
    $null=Good (Api POST '/nocode/application/authorization' @{applicationId=$ApplicationId;expectedRevision=$policy.revision;members=@($policy.members)+@($member)})
    $policyModified=$true
    $mine=Good (Api GET '/nocode/runtime/mine' $null $auth.accessToken)
    Check 'Assigned member discovers the published application without design rights' (@($mine | Where-Object id -eq $ApplicationId).Count -eq 1)
    Check 'Member cannot read application authorization' ((Api GET "/nocode/application/authorization?id=$ApplicationId" $null $auth.accessToken).code -ne 0)
    Check 'OWN scope does not expose owner records or counts' ((Good (Api POST '/nocode/runtime/page' $query $auth.accessToken)).total -eq 0)
    Check 'Member cannot open another creator record by ID' ((Api GET "/nocode/runtime/get?applicationId=$ApplicationId&objectId=$ObjectId&id=$($ownedRecord.id)" $null $auth.accessToken).code -ne 0)
    Check 'Member cannot update another creator record' ((Api POST '/nocode/runtime/save' $draft $auth.accessToken).code -ne 0)
    $memberDraft=@{applicationId=$ApplicationId;objectId=$ObjectId;id=$null;expectedRevision=$null;values=@{$title[0].id="成员验收$runId"};details=@{$child.id=@(@{id=$null;revision=$null;values=@{$quantity.id='3'}})}}
    $memberSaved=Good (Api POST '/nocode/runtime/save' $memberDraft $auth.accessToken)
    $memberRecord=$memberSaved.record
    Check 'Member creates authorized main and detail records' ($memberSaved.details.($child.id)[0].values.($quantity.id) -eq '3')
    Check 'OWN count includes only the current member record' ((Good (Api POST '/nocode/runtime/page' $query $auth.accessToken)).total -eq 1)
    $currentPolicy=Good (Api GET "/nocode/application/authorization?id=$ApplicationId" $null)
    $null=Good (Api POST '/nocode/application/authorization' @{applicationId=$ApplicationId;expectedRevision=$currentPolicy.revision;members=@($currentPolicy.members | Where-Object { -not ($_.principalKind -eq 'USER' -and $_.principalId -eq [string]$userId) })})
    $policyModified=$false
    Check 'Revocation immediately denies an existing session' ((Api POST '/nocode/runtime/page' $query $auth.accessToken).code -ne 0)
    Check 'Revoked application disappears from member portal' (@((Good (Api GET '/nocode/runtime/mine' $null $auth.accessToken)) | Where-Object id -eq $ApplicationId).Count -eq 0)
    $null=Good (Api POST '/nocode/runtime/delete' @{applicationId=$ApplicationId;objectId=$ObjectId;id=$ownedRecord.id;expectedRevision=$ownedRecord.revision})
    $deletedId=$ownedRecord.id; $ownedRecord=$null
    Check 'Deleted record is unavailable through runtime' ((Api GET "/nocode/runtime/get?applicationId=$ApplicationId&objectId=$ObjectId&id=$deletedId" $null).code -ne 0)
    $completed=$true
} finally {
    $cleanup=@()
    if ($policyModified -and $userId) {
        $currentPolicy=Good (Api GET "/nocode/application/authorization?id=$ApplicationId" $null)
        $cleanup+=(Api POST '/nocode/application/authorization' @{applicationId=$ApplicationId;expectedRevision=$currentPolicy.revision;members=@($currentPolicy.members | Where-Object { -not ($_.principalKind -eq 'USER' -and $_.principalId -eq [string]$userId) })}).code
    }
    if ($memberRecord) { $cleanup+=(Api POST '/nocode/runtime/delete' @{applicationId=$ApplicationId;objectId=$ObjectId;id=$memberRecord.id;expectedRevision=$memberRecord.revision}).code }
    if ($ownedRecord) { $cleanup+=(Api POST '/nocode/runtime/delete' @{applicationId=$ApplicationId;objectId=$ObjectId;id=$ownedRecord.id;expectedRevision=$ownedRecord.revision}).code }
    if ($userId) { $cleanup+=(Api DELETE "/system/user/delete?id=$userId" $null).code }
    $report=@{capturedAt=(Get-Date).ToString('o');applicationId=$ApplicationId;objectId=$ObjectId;runId=$runId;completed=$completed;checks=$checks.ToArray();passed=@($checks | Where-Object passed).Count;failed=@($checks | Where-Object {-not $_.passed}).Count;cleanupCodes=$cleanup}
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Output -Encoding utf8
    Write-Output "Application HTTP checks: $($report.passed) passed, $($report.failed) failed."
}
