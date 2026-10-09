param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$ActorId = '1',
    [string]$OperationsOutput = (Join-Path $PSScriptRoot '.work/company-operations-demo.json')
)
# 可复用的完整搭建验收。独立稳定编码，不覆盖既有 company_page_demo 或用户之后修改的配置。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/prepare-company-demo.ps1" -BaseUrl $BaseUrl -AccessToken $AccessToken -FunctionsOnly
$fileField = Field 'files' '公司资料附件'; $fileField.type = 'ATTACHMENT'; $fileField.length = $null
$company = EnsureObject 'ac_ops_company' '公司' @((Field 'title' '公司名称' $true), (Field 'code' '统一社会信用代码'), (Field 'contact' '联系人'), (Field 'phone' '联系电话'), $fileField) @()
$account = EnsureObject 'ac_ops_account' '银行账户' @((Field 'title' '账户名称' $true), (Field 'bank' '开户行' $true), (Field 'number' '账号')) @(@{ code = 'company'; name = '所属公司'; kind = 'REFERENCE'; targetObjectId = $company.objectId; required = $true; onDelete = 'RESTRICT' })
$amountField = Field 'amount' '发生金额' $true; $amountField.type = 'DECIMAL'; $amountField.length = $null; $amountField.precision = 18; $amountField.scale = 2
$dateField = Field 'booked' '记账日期' $true; $dateField.type = 'DATE'; $dateField.length = $null
$flow = EnsureObject 'ac_ops_flow' '资金流水' @((Field 'title' '交易摘要' $true), $amountField, $dateField) @(@{ code = 'account'; name = '所属账户'; kind = 'REFERENCE'; targetObjectId = $account.objectId; required = $true; onDelete = 'RESTRICT' })

$models = @(Api GET '/bpm/model/list?name=公司资料审批验收' $null | Where-Object key -eq 'nocode_company_operations_review')
if ($models.Count -gt 1) { throw 'Duplicate demonstration process model.' }
if ($models.Count) { $modelId = [string]$models[0].id }
else {
    $modelId = Api POST '/bpm/model/create' @{
        key = 'nocode_company_operations_review'; name = '公司资料审批验收'; type = 20; formType = 20; visible = $false
        startUserIds = @(); startDeptIds = @(); managerUserIds = @($ActorId); allowCancelRunningProcess = $true; allowWithdrawTask = $false; autoApprovalType = 0
        formCustomCreatePath = '/nocode-app/mine'; formCustomViewPath = '/nocode-app/process-record'
        simpleModel = @{ id = 'StartUserNode'; type = 10; name = '发起人'; childNode = @{ id = 'ApproveCompany'; type = 11; name = '公司资料审核'; candidateStrategy = 30; candidateParam = $ActorId; approveType = 1; approveMethod = 1; assignStartUserHandlerType = 1; signEnable = $false; reasonRequire = $false; childNode = @{ id = 'EndEvent'; type = 1; name = '结束' } } }
    }
    $null = Api POST "/bpm/model/deploy?id=$modelId" $null
}
$process = Api GET "/bpm/model/get?id=$modelId" $null
$definitionId = $process.processDefinition.id
if (-not $definitionId) { $definitionId = (Api GET '/bpm/process-definition/get?key=nocode_company_operations_review' $null).id }
if (-not $definitionId) { throw 'The demonstration process must have a published definition.' }
$apps = @((Api GET '/nocode/application/page?pageNo=1&pageSize=100&search=company_operations_demo' $null).list | Where-Object code -eq 'company_operations_demo')
if ($apps.Count -gt 1) { throw 'Duplicate demonstration application.' }
function FormResource($Object, [string]$Id) {
    $primary = @(); $secondary = @()
    foreach ($f in $Object.definition.fields) {
        $n = Node ('field_' + $f.id) 'FIELD'; $n.fieldId = $f.id
        if ($f.code -eq 'title' -or $Object.definition.relations.fieldId -contains $f.id) { $primary += $n } else { $secondary += $n }
    }
    $tabs = Node ($Id + '_tabs') 'TABS' @((Node ($Id + '_basic') 'TAB' $primary '基本信息'), (Node ($Id + '_more') 'TAB' $secondary '补充资料'))
    return Resource $Id 'FORM' ($Object.definition.objectName + '表单') @{ objectId = $Object.objectId; nodes = @($tabs); detailIds = @(); options = @{ layout = 'vertical'; submitText = '保存资料' } }
}
function Related([string]$Id, [string]$View, $Relation, [string]$Title) {
    $node = Node $Id 'RELATED' @() $Title; $node.resourceId = $View; $node.binding = @{ relationId = $Relation.id; direction = 'INCOMING' }; return $node
}
function ViewResource($Object, [string]$Id, [string]$Form, [string]$Page = '') {
    $config = @{ objectId = $Object.objectId; fieldIds = @($Object.definition.fields | Where-Object type -ne 'ATTACHMENT' | ForEach-Object id); equal = @{}; descending = $false; pageSize = 10; formId = $Form
        interaction = @{ buttons = @('CREATE', 'IMPORT', 'EXPORT', 'VIEW', 'UPDATE', 'DELETE'); actionIds = @(); editMode = 'DRAWER'; detailMode = 'DRAWER' } }
    if ($Page) { $config.detailPageId = $Page }
    return Resource $Id 'VIEW' ($Object.definition.objectName + '管理') $config
}
if (-not $apps.Count) {
    $companyDetail = Node 'company_info' 'DETAIL' @() '基本信息'; $companyDetail.resourceId = 'company_form'
    $files = Node 'company_files' 'ATTACHMENTS' @() '公司资料'; $files.resourceId = 'company_form'
    $approvals = Node 'company_approvals' 'PROCESSES' @() '公司资料审批'; $approvals.resourceId = 'company_form'
    $companyTabs = Node 'company_tabs' 'TABS' @(
        (Node 'company_basic' 'TAB' @($companyDetail) '基本信息'),
        (Node 'company_finance' 'TAB' @((Related 'company_accounts' 'account_view' $account.definition.relations[0] '银行账户')) '财务'),
        (Node 'company_documents' 'TAB' @($files) '文档与文件'),
        (Node 'company_processes' 'TAB' @($approvals) '审批记录'))
    $accountDetail = Node 'account_info' 'DETAIL' @() '账户资料'; $accountDetail.resourceId = 'account_form'
    $accountTabs = Node 'account_tabs' 'TABS' @((Node 'account_basic' 'TAB' @($accountDetail) '基本信息'),
        (Node 'account_flows' 'TAB' @((Related 'account_transactions' 'flow_view' $flow.definition.relations[0] '资金流水')) '资金流水'))
    $companyView = ViewResource $company 'company_view' 'company_form' 'company_page'
    $companyView.config.interaction.actionIds = @('submit_company_review')
    $resources = @(
        (FormResource $company 'company_form'), (FormResource $account 'account_form'), (FormResource $flow 'flow_form'),
        $companyView, (ViewResource $account 'account_view' 'account_form' 'account_page'), (ViewResource $flow 'flow_view' 'flow_form'),
        (Resource 'company_page' 'PAGE' '公司详情' @{ nodes = @($companyTabs); contextObjectId = $company.objectId; protocolVersion = 2 }),
        (Resource 'account_page' 'PAGE' '账户详情' @{ nodes = @($accountTabs); contextObjectId = $account.objectId; protocolVersion = 2 }),
        (Resource 'submit_company_review' 'ACTION' '提交资料审核' @{ objectId = $company.objectId; kind = 'START_PROCESS'; processDefinitionId = $definitionId; variables = @{ nc_title = $company.definition.titleFieldId } }),
        (Resource 'companies' 'MENU' '公司管理' @{ targetId = 'company_view' }), (Resource 'accounts' 'MENU' '账户管理' @{ targetId = 'account_view' }), (Resource 'flows' 'MENU' '流水管理' @{ targetId = 'flow_view' })
    )
    $refs = @($company, $account, $flow) | ForEach-Object { @{ objectId = $_.objectId; versionNo = $_.versionNo; checksum = $_.checksum } }
    $app = Api POST '/nocode/application/save' @{ code = 'company_operations_demo'; name = '公司经营管理'; description = '通用配置验收：公司、账户、流水、附件、审批和页面操作'; definition = @{ objects = @($refs); resources = $resources } }
} else { $app = Api GET "/nocode/application/get?id=$($apps[0].id)" $null }
$appId = [string]$app.application.id
foreach ($item in @($company, $account, $flow)) {
    $grants = @(Api GET "/nocode/object-sharing/list?objectId=$($item.objectId)" $null)
    if (-not @($grants | Where-Object applicationId -eq $appId).Count) {
        $null = Api POST '/nocode/object-sharing/save' @{ objectId = $item.objectId; applicationId = $appId; expectedRevision = 0; reason = '授权独立经营管理验收应用'; permission = @{
            objectId = $item.objectId; scope = 'ALL'; actions = @('READ', 'CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT', 'START_PROCESS')
            readFields = @($item.definition.fields.id); writeFields = @($item.definition.fields.id); readDetails = @(); writeDetails = @(); readRelations = @(); writeRelations = @()
        } }
    }
}
if ($null -eq $app.application.publishedVersion) { $app = Api POST '/nocode/application/publish' @{ id = $appId; expectedRevision = $app.application.revision; reason = '公司经营管理完整验收初始化' } }
function EnsureRecord($Object, [string]$Title, $Extra) {
    $titleId = [string]$Object.definition.titleFieldId
    $rows = Api POST '/nocode/runtime/page' @{ applicationId = $appId; objectId = $Object.objectId; pageNo = 1; pageSize = 20; equal = @{ $titleId = $Title }; descending = $false }
    if ($rows.total -gt 0) { return $rows.list[0] }
    $values = @{ $titleId = $Title }; foreach ($key in $Extra.Keys) { $values[$key] = $Extra[$key] }
    return (Api POST '/nocode/runtime/save' @{ applicationId = $appId; objectId = $Object.objectId; values = $values }).record
}
$companyA = EnsureRecord $company '经营验收甲公司' @{}
$companyB = EnsureRecord $company '经营验收乙公司' @{}
$bankField = [string]($account.definition.fields | Where-Object code -eq 'bank').id
$accountA = EnsureRecord $account '甲公司基本账户' @{ ([string]$account.definition.relations[0].fieldId) = $companyA.id; $bankField = '验收银行甲支行' }
$accountB = EnsureRecord $account '乙公司基本账户' @{ ([string]$account.definition.relations[0].fieldId) = $companyB.id; $bankField = '验收银行乙支行' }
$amountId = [string]($flow.definition.fields | Where-Object code -eq 'amount').id
$dateId = [string]($flow.definition.fields | Where-Object code -eq 'booked').id
$null = EnsureRecord $flow '甲公司服务款到账' @{ ([string]$flow.definition.relations[0].fieldId) = $accountA.id; $amountId = '12000.50'; $dateId = '2026-09-06' }
$null = EnsureRecord $flow '乙公司办公款支出' @{ ([string]$flow.definition.relations[0].fieldId) = $accountB.id; $amountId = '-3800.00'; $dateId = '2026-09-06' }
$result = @{ applicationId = $appId; companyObjectId = $company.objectId; accountObjectId = $account.objectId; flowObjectId = $flow.objectId; companyA = $companyA.id; companyB = $companyB.id; accountA = $accountA.id; accountB = $accountB.id; processDefinitionId = $definitionId; processModelId = $modelId; runtimeUrl = "http://127.0.0.1:5173/nocode-app/runtime?id=$appId" }
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OperationsOutput -Encoding utf8
$result | ConvertTo-Json -Depth 10
