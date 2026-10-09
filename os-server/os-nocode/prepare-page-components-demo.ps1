param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$SourceApplicationId = '786',
    [string]$DemoOutput = (Join-Path $PSScriptRoot '.work/page-components-demo.json')
)
# 可重复执行的页面组件验收应用。复用示例对象与真实授权上限，不覆盖源应用或已存在的演示草稿。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/prepare-company-demo.ps1" -BaseUrl $BaseUrl -AccessToken $AccessToken -FunctionsOnly
$demoApps = @((Api GET '/nocode/application/page?pageNo=1&pageSize=100&search=page_components_demo' $null).list | Where-Object code -eq 'page_components_demo')
if ($demoApps.Count -gt 1) { throw 'Duplicate demonstration application code.' }
if ($demoApps.Count) {
    $result = @{ applicationId = [string]$demoApps[0].id; runtimeUrl = "http://127.0.0.1:5173/nocode-app/runtime?id=$($demoApps[0].id)"; reused = $true }
    $result | ConvertTo-Json | Set-Content -LiteralPath $DemoOutput -Encoding utf8
    $result | ConvertTo-Json
    return
}
$source = Api GET "/nocode/application/get?id=$SourceApplicationId" $null
if ($source.application.code -ne 'company_operations_demo') { throw 'Source must be the identifiable company operations demonstration.' }
$definition = $source.draft | ConvertTo-Json -Depth 60 | ConvertFrom-Json -AsHashtable
$resources = @($definition.resources)
$companyPage = $resources | Where-Object id -eq 'company_page'
$accountPage = $resources | Where-Object id -eq 'account_page'
if (-not $companyPage -or -not $accountPage) { throw 'Source application lacks the expected company/account pages.' }
function Button([string]$Id, [string]$Text, [string]$Kind, [string]$Target = '', [string]$ResourceId = '', [string]$Appearance = 'DEFAULT') {
    $n = Node $Id 'BUTTON' @() $Text
    $n.display = @{ buttonType = $Appearance }
    $n.action = @{ kind = $Kind }
    if ($Target) { $n.action.targetNodeId = $Target }
    if ($ResourceId) { $n.action.resourceId = $ResourceId }
    return $n
}
function Heading([string]$Id, [string]$Text) {
    $n = Node $Id 'HEADING' @() $Text; $n.display = @{ headingLevel = 2 }; $n.style = @{ color = '#172554'; marginBottom = 16 }; return $n
}
function Toolbar([string]$Id, $Children) {
    $n = Node $Id 'FLEX' $Children; $n.style = @{ gap = 8; marginBottom = 16; direction = 'ROW'; align = 'START' }; return $n
}
$companyToolbar = Toolbar 'company_toolbar' @(
    (Button 'company_edit' '编辑公司' 'EDIT' 'company_info'),
    (Button 'company_add_account' '新增银行账户' 'CREATE' 'company_accounts' '' 'PRIMARY'),
    (Button 'company_refresh_accounts' '刷新账户列表' 'REFRESH' 'company_accounts'),
    (Button 'company_view' '查看公司资料' 'VIEW' 'company_info'),
    (Button 'company_open_documents' '资料与审批' 'OPEN_PAGE' '' 'company_documents_page'),
    (Button 'company_submit' '提交资料审核' 'EXECUTE_ACTION' '' 'submit_company_review')
)
$companyToolbar.children[3].action.openMode = 'MODAL'
$companyToolbar.children[5].action.confirmText = '确认提交当前公司资料审核？审批期间将按平台规则锁定记录。'
$notice = Node 'company_hint' 'ALERT' @() '资料、账户与审批围绕当前公司组织；银行账户仍独立保存。'
$notice.display = @{ alertType = 'INFO' }; $notice.style = @{ marginBottom = 16 }
$companyPage.config.nodes = @((Heading 'company_heading' '公司经营档案'), $companyToolbar, $notice) + @($companyPage.config.nodes)
$accountPage.config.nodes = @((Heading 'account_heading' '账户与收支'), (Toolbar 'account_toolbar' @(
    (Button 'account_add_flow' '新增资金流水' 'CREATE' 'account_transactions' '' 'PRIMARY'),
    (Button 'account_refresh_flow' '刷新流水' 'REFRESH' 'account_transactions')
))) + @($accountPage.config.nodes)
$files = Node 'documents_files' 'ATTACHMENTS' @() '公司资料'; $files.resourceId = 'company_form'
$processes = Node 'documents_processes' 'PROCESSES' @() '资料审核记录'; $processes.resourceId = 'company_form'
$resources += Resource 'company_documents_page' 'PAGE' '资料与审批' @{ contextObjectId = $companyPage.config.contextObjectId; protocolVersion = 2; nodes = @($files, $processes) }
$guide = Node 'guide_text' 'TEXT' @() '从公司档案进入详情，查看关联账户；账户页查看收支流水。新增按钮自动沿用所属关系。文件和审批通过底座能力处理。'
$guideCard = Node 'guide_card' 'CARD' @((Heading 'guide_title' '经营管理使用说明'), $guide) '使用说明'; $guideCard.style = @{ padding = 24; radius = 12; background = '#f5f4ff' }
$resources += Resource 'guide_page' 'PAGE' '使用说明' @{ protocolVersion = 2; nodes = @($guideCard) }
$homeList = Node 'home_companies' 'VIEW' @() '公司档案'; $homeList.resourceId = 'company_view'
$homeHint = Node 'home_hint' 'ALERT' @() '选择公司进入经营档案，集中处理基本资料、银行账户、资金流水、文件与审批。'
$homeHint.display = @{ alertType = 'INFO' }; $homeHint.style = @{ marginBottom = 16 }
$metrics = @()
foreach ($item in @(@('company', 'company_view', '公司数量'), @('account', 'account_view', '账户数量'), @('flow', 'flow_view', '流水数量'))) {
    $metric = Node ($item[0] + '_metric') 'METRIC' @() $item[2]; $metric.resourceId = $item[1]
    $column = Node ($item[0] + '_column') 'COLUMN' @($metric); $column.span = 8; $metrics += $column
}
$space = Node 'home_space' 'SPACER'; $space.style = @{ minHeight = 16 }
$homeResource = Resource 'operations_home' 'PAGE' '经营工作台' @{ protocolVersion = 2; nodes = @(
    (Heading 'home_title' '公司经营工作台'),
    (Toolbar 'home_toolbar' @((Button 'home_create' '新建公司' 'CREATE' 'home_companies' '' 'PRIMARY'), (Button 'home_accounts' '账户管理' 'NAVIGATE' '' 'accounts'), (Button 'home_guide' '使用说明' 'OPEN_PAGE' '' 'guide_page'), (Button 'home_refresh' '刷新工作台' 'REFRESH'))),
    $homeHint, (Node 'home_metrics' 'ROW' $metrics), $space, $homeList
) }
$resources = @((Resource 'home' 'MENU' '经营工作台' @{ targetId = 'operations_home' }), $homeResource) + $resources
$app = Api POST '/nocode/application/save' @{ code = 'page_components_demo'; name = '公司经营管理 · 页面组件验收'; description = '布局、基础内容、业务区块和受控按钮的独立验收应用'; definition = @{ objects = $definition.objects; resources = $resources } }
$appId = [string]$app.application.id
foreach ($reference in $definition.objects) {
    $grant = @(Api GET "/nocode/object-sharing/list?objectId=$($reference.objectId)" $null | Where-Object { $_.applicationId -eq $SourceApplicationId -and $_.permission })
    if ($grant.Count -ne 1) { throw "Source sharing grant missing for $($reference.objectId)." }
    $null = Api POST '/nocode/object-sharing/save' @{ objectId = $reference.objectId; applicationId = $appId; expectedRevision = 0; reason = '独立页面组件演示，沿用源示例授权上限'; permission = $grant[0].permission }
}
$null = Api POST '/nocode/application/publish' @{ id = $appId; expectedRevision = $app.application.revision; reason = '页面基础组件与动作验收' }
$result = @{ applicationId = $appId; runtimeUrl = "http://127.0.0.1:5173/nocode-app/runtime?id=$appId"; sourceApplicationId = $SourceApplicationId }
$result | ConvertTo-Json | Set-Content -LiteralPath $DemoOutput -Encoding utf8
$result | ConvertTo-Json
