param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$Output = (Join-Path $PSScriptRoot '.work/company-demo.json'),
    [switch]$FunctionsOnly
)
# 可复用的公司详情组合验收夹具。仅创建固定演示编码，不覆盖已有设计、授权或业务记录。
$ErrorActionPreference = 'Stop'
if (-not $AccessToken) { throw 'Provide an authenticated AccessToken.' }
function Api([string]$Method, [string]$Path, $Body) {
    $request = @{ Uri = $BaseUrl + $Path; Method = $Method; Headers = @{ Authorization = 'Bearer ' + $AccessToken }; TimeoutSec = 60 }
    if ($null -ne $Body) { $request.ContentType = 'application/json'; $request.Body = ConvertTo-Json -InputObject $Body -Depth 60 -Compress }
    $response = Invoke-RestMethod @request
    if ($response.code -ne 0) { throw "$Path : $($response.msg)" }
    return $response.data
}
function Field([string]$Code, [string]$Name, [bool]$Required = $false) {
    return @{ key = $Code; code = $Code; name = $Name; type = 'TEXT'; length = 160; required = $Required; unique = $false; sort = 0 }
}
function EnsureObject([string]$Code, [string]$Name, $Fields, $Relations) {
    $found = @((Api GET "/nocode/design/page?pageNo=1&pageSize=100&search=$Code" $null).list | Where-Object objectCode -eq $Code)
    if ($found.Count -gt 1) { throw 'Duplicate demonstration object code.' }
    if ($found.Count) { $design = Api GET "/nocode/design/get?id=$($found[0].id)" $null }
    else {
        $design = Api POST '/nocode/design/save' @{
            draft = @{ objectCode = $Code; objectName = $Name; tableName = 'biz_' + $Code; titleFieldKey = 'title'; fields = @($Fields); removedFieldIds = @() }
            settings = @{}; fieldOptions = @{}; relations = @($Relations); indexes = @(); details = @()
        }
    }
    if ($null -eq $design.publishedVersion) {
        $plan = Api POST '/nocode/design/plan' @{ id = $design.draft.id; expectedLockVersion = $design.draft.lockVersion; reason = '公司页面组合演示初始化' }
        if (@($plan.checks | Where-Object blocking).Count) { throw 'Object publish has blocking checks.' }
        $null = Api POST '/nocode/design/execute' @{ planId = $plan.id; reason = '公司页面组合演示初始化' }
    }
    return Api GET "/nocode/application/object-version?id=$($design.draft.id)" $null
}
function Node([string]$Id, [string]$Type, $Children = @(), [string]$Text = '') {
    return @{ id = $Id; type = $Type; children = @($Children); text = $Text }
}
function Resource([string]$Id, [string]$Kind, [string]$Name, $Config) {
    return @{ id = $Id; code = $Id; kind = $Kind; name = $Name; config = $Config }
}
if ($FunctionsOnly) { return }
$company = EnsureObject 'ac_company_demo' '演示公司' @((Field 'title' '公司名称' $true), (Field 'code' '公司编码'), (Field 'contact' '联系人'), (Field 'phone' '联系电话')) @()
$account = EnsureObject 'ac_account_demo' '演示银行账户' @((Field 'title' '账户名称' $true), (Field 'bank' '开户行'), (Field 'number' '账号')) @(@{ code = 'company'; name = '所属公司'; kind = 'REFERENCE'; targetObjectId = $company.objectId; required = $true; onDelete = 'RESTRICT' })
$companyId = [string]$company.objectId
$accountId = [string]$account.objectId
$apps = @((Api GET '/nocode/application/page?pageNo=1&pageSize=100&search=company_page_demo' $null).list | Where-Object code -eq 'company_page_demo')
if (-not $apps.Count) {
    $forms = @()
    foreach ($item in @(@{ object = $company; id = 'company_form'; name = '公司资料' }, @{ object = $account; id = 'account_form'; name = '账户资料' })) {
        $fieldNodes = @($item.object.definition.fields | ForEach-Object {
            $n = Node ('field_' + $_.id) 'FIELD'; $n.fieldId = $_.id; $n
        })
        $forms += Resource $item.id 'FORM' $item.name @{ objectId = $item.object.objectId; nodes = @((Node ($item.id + '_card') 'CARD' $fieldNodes $item.name)); detailIds = @(); options = @{ layout = 'vertical'; submitText = '保存资料' } }
    }
    $detail = Node 'company_detail' 'DETAIL'; $detail.resourceId = 'company_form'
    $related = Node 'company_accounts' 'RELATED' @() '银行账户'
    $related.resourceId = 'account_view'; $related.binding = @{ relationId = $account.definition.relations[0].id; direction = 'INCOMING' }
    $tabs = Node 'company_tabs' 'TABS' @((Node 'basic_tab' 'TAB' @($detail) '基本信息'), (Node 'finance_tab' 'TAB' @($related) '财务'))
    $resources = $forms + @(
        (Resource 'account_view' 'VIEW' '银行账户' @{ objectId = $accountId; fieldIds = @($account.definition.fields.id); equal = @{}; descending = $true; pageSize = 10; formId = 'account_form' }),
        (Resource 'company_detail_page' 'PAGE' '公司详情' @{ nodes = @($tabs); contextObjectId = $companyId; protocolVersion = 2 }),
        (Resource 'company_view' 'VIEW' '公司档案' @{ objectId = $companyId; fieldIds = @($company.definition.fields.id); equal = @{}; descending = $false; pageSize = 10; formId = 'company_form'; detailPageId = 'company_detail_page' }),
        (Resource 'company_menu' 'MENU' '公司管理' @{ targetId = 'company_view' }),
        (Resource 'account_menu' 'MENU' '账户管理' @{ targetId = 'account_view' })
    )
    $refs = @($company, $account) | ForEach-Object { @{ objectId = $_.objectId; versionNo = $_.versionNo; checksum = $_.checksum } }
    $app = Api POST '/nocode/application/save' @{ code = 'company_page_demo'; name = '公司管理演示'; description = '可复用验收：详情页签、相关账户、当前公司绑定和共享数据保护'; definition = @{ objects = @($refs); resources = $resources } }
} else { $app = Api GET "/nocode/application/get?id=$($apps[0].id)" $null }
$appId = [string]$app.application.id
foreach ($item in @($company, $account)) {
    $grants = @(Api GET "/nocode/object-sharing/list?objectId=$($item.objectId)" $null)
    if (-not @($grants | Where-Object applicationId -eq $appId).Count) {
        $null = Api POST '/nocode/object-sharing/save' @{ objectId = $item.objectId; applicationId = $appId; expectedRevision = 0; reason = '授权专用公司演示应用'; permission = @{
            objectId = $item.objectId; scope = 'ALL'; actions = @('READ', 'CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT')
            readFields = @($item.definition.fields.id); writeFields = @($item.definition.fields.id); readDetails = @(); writeDetails = @(); readRelations = @(); writeRelations = @()
        } }
    }
}
if ($null -eq $app.application.publishedVersion) {
    $app = Api POST '/nocode/application/publish' @{ id = $appId; expectedRevision = $app.application.revision; reason = '公司页面组合首次发布' }
}
function EnsureRecord($Object, [string]$Title, $Extra) {
    $titleId = [string]$Object.definition.titleFieldId
    $rows = Api POST '/nocode/runtime/page' @{ applicationId = $appId; objectId = $Object.objectId; pageNo = 1; pageSize = 20; equal = @{ $titleId = $Title }; descending = $false }
    if ($rows.total -gt 0) { return $rows.list[0] }
    $values = @{ $titleId = $Title }; foreach ($key in $Extra.Keys) { $values[$key] = $Extra[$key] }
    return (Api POST '/nocode/runtime/save' @{ applicationId = $appId; objectId = $Object.objectId; values = $values }).record
}
$companyA = EnsureRecord $company '验收甲公司' @{}
$companyB = EnsureRecord $company '验收乙公司' @{}
$companyField = [string]$account.definition.relations[0].fieldId
$null = EnsureRecord $account '甲公司基本账户' @{ $companyField = $companyA.id }
$null = EnsureRecord $account '乙公司基本账户' @{ $companyField = $companyB.id }
$result = @{ applicationId = $appId; companyObjectId = $companyId; accountObjectId = $accountId; companyA = $companyA.id; companyB = $companyB.id; companyFieldId = $companyField; runtimeUrl = "http://127.0.0.1:5173/nocode-app/runtime?id=$appId" }
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Output -Encoding utf8
$result
