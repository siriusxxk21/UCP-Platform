param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$SourceApplicationId = '786',
    [string]$DemoOutput = (Join-Path $PSScriptRoot '.work/report-demo.json')
)
# 可复用经营统计验收：沿用原对象和共享授权，只创建固定编码的独立应用及“报表验收”记录。
# 不覆盖源应用、已有已发布报表草稿或用户业务数据；令牌不写文件。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/prepare-company-demo.ps1" -BaseUrl $BaseUrl -AccessToken $AccessToken -FunctionsOnly
$found = @((Api GET '/nocode/application/page?pageNo=1&pageSize=100&search=company_report_demo' $null).list | Where-Object code -eq 'company_report_demo')
if ($found.Count -gt 1) { throw 'Duplicate reporting demo.' }
if ($found.Count -and $null -ne $found[0].publishedVersion) {
    if (Test-Path -LiteralPath $DemoOutput) {
        $previous = Get-Content -LiteralPath $DemoOutput -Raw | ConvertFrom-Json
        if ([string]$previous.applicationId -eq [string]$found[0].id -and $previous.companyA) {
            return ($previous | ConvertTo-Json -Depth 10)
        }
    }
    $result = @{ applicationId = [string]$found[0].id; reused = $true; runtimeUrl = "http://127.0.0.1:5173/nocode-app/runtime?id=$($found[0].id)" }
    # 元数据丢失时不覆盖用户已发布配置，也不以不完整结果覆盖原验收记录。
    return $result
}
$source = Api GET "/nocode/application/get?id=$SourceApplicationId" $null
if ($source.application.code -ne 'company_operations_demo') { throw 'Source must be the identifiable company operations demonstration.' }
$definition = $source.draft | ConvertTo-Json -Depth 70 | ConvertFrom-Json -AsHashtable
$objects = @{}
foreach ($ref in $definition.objects) { $objects[$ref.objectId] = Api GET "/nocode/application/object-version?id=$($ref.objectId)&versionNo=$($ref.versionNo)" $null }
$company = @($objects.Values | Where-Object { $_.definition.objectCode -eq 'ac_ops_company' })[0]
$account = @($objects.Values | Where-Object { $_.definition.objectCode -eq 'ac_ops_account' })[0]
$flow = @($objects.Values | Where-Object { $_.definition.objectCode -eq 'ac_ops_flow' })[0]
if (-not $company -or -not $account -or -not $flow) { throw 'Missing company/account/flow references.' }
$flowRelation = $flow.definition.relations | Where-Object targetObjectId -eq $account.objectId
$accountRelation = $account.definition.relations | Where-Object targetObjectId -eq $company.objectId
$amountId = [string]($flow.definition.fields | Where-Object code -eq 'amount').id
$dateId = [string]($flow.definition.fields | Where-Object code -eq 'booked').id
$companyFilter = "$($flowRelation.id):$($accountRelation.fieldId)"
$companyPath = "$($flowRelation.id)/$($accountRelation.id)"
$metrics = @(
    @{ id = 'amount'; name = '净发生金额'; operation = 'SUM'; fieldId = $amountId },
    @{ id = 'count'; name = '流水笔数'; operation = 'COUNT'; fieldId = $null },
    @{ id = 'average'; name = '平均发生金额'; operation = 'AVG'; fieldId = $amountId }
)
function Report([string]$Id, [string]$Name, [string]$Display, $Dimensions, $Metrics, [string]$Sort = '') {
    $config = @{ objectId = $flow.objectId; dimensions = @($Dimensions); metrics = @($Metrics); equal = @{}
        filterFieldIds = @($companyFilter); dateFieldId = $dateId; timeZone = 'Asia/Shanghai'; display = $Display
        sortMetricId = $(if ($Sort) { $Sort } else { $null }); descending = [bool]$Sort; limit = 30; detailViewId = 'flow_view' }
    return Resource $Id 'REPORT' $Name $config
}
$companyDimension = @{ fieldId = $company.definition.titleFieldId; relationPath = $companyPath; bucket = 'VALUE' }
$monthDimension = @{ fieldId = $dateId; relationPath = $null; bucket = 'MONTH' }
$reports = @(
    (Report 'report_overview' '经营概况' 'METRIC' @() $metrics),
    (Report 'report_month' '月度发生金额' 'LINE' @($monthDimension) @($metrics[0])),
    (Report 'report_company' '各公司发生金额' 'BAR' @($companyDimension) @($metrics[0]) 'amount'),
    (Report 'report_distribution' '流水笔数占比' 'PIE' @($companyDimension) @($metrics[1]) 'count'),
    (Report 'report_summary' '公司月度汇总' 'TABLE' @($companyDimension, $monthDimension) $metrics)
)
function ReportNode([string]$Id, [string]$Title) { $n = Node ('block_' + $Id) 'REPORT' @() $Title; $n.resourceId = $Id; return $n }
$title = Node 'analysis_title' 'HEADING' @() '公司经营分析'; $title.display = @{ headingLevel = 2 }; $title.style = @{ marginBottom = 16; color = '#172554' }
$hint = Node 'analysis_hint' 'ALERT' @() '按公司和日期统一分析资金流水。收入为正、支出为负；点击图表或指标查看同口径明细。'; $hint.display = @{ alertType = 'INFO' }; $hint.style = @{ marginBottom = 16 }
$columns = @()
foreach ($r in @($reports[1], $reports[2])) { $n = Node ('column_' + $r.id) 'COLUMN' @((ReportNode $r.id $r.name)); $n.span = 12; $columns += $n }
$page = Resource 'analysis_page' 'PAGE' '公司经营分析' @{
    protocolVersion = 2; nodes = @($title, $hint, (ReportNode $reports[0].id $reports[0].name),
        (Node 'analysis_charts' 'ROW' $columns), (ReportNode $reports[3].id $reports[3].name), (ReportNode $reports[4].id $reports[4].name))
    filters = @(
        @{ id = 'analysis_company'; name = '公司'; objectId = $account.objectId; fieldId = $accountRelation.fieldId; dateRange = $false
            targets = @{ report_overview = $companyFilter; report_month = $companyFilter; report_company = $companyFilter; report_distribution = $companyFilter; report_summary = $companyFilter } },
        @{ id = 'analysis_dates'; name = '日期'; objectId = $flow.objectId; fieldId = $dateId; dateRange = $true
            targets = @{ report_overview = $dateId; report_month = $dateId; report_company = $dateId; report_distribution = $dateId; report_summary = $dateId } }
    )
}
$resources = @((Resource 'analysis' 'MENU' '经营分析' @{ targetId = 'analysis_page' }), $page) + $reports + @($definition.resources)
$save = @{ code = 'company_report_demo'; name = '公司经营管理 · 统计报表验收'; description = '单事实表统计、两段单值关联、统一筛选、精确金额、分组下钻及实时授权'; definition = @{ objects = $definition.objects; resources = $resources } }
if ($found.Count) { $save.id = [string]$found[0].id; $save.expectedRevision = $found[0].revision }
$app = Api POST '/nocode/application/save' $save
$appId = [string]$app.application.id
foreach ($ref in $definition.objects) {
    $grants = @(Api GET "/nocode/object-sharing/list?objectId=$($ref.objectId)" $null)
    $sourceGrant = @($grants | Where-Object { $_.applicationId -eq $SourceApplicationId -and $_.permission })
    if ($sourceGrant.Count -ne 1) { throw 'Source sharing missing.' }
    $existing = @($grants | Where-Object applicationId -eq $appId)
    if (-not $existing.Count) {
        $null = Api POST '/nocode/object-sharing/save' @{ objectId = $ref.objectId; applicationId = $appId; expectedRevision = 0; reason = '统计验收应用沿用源对象共享上限'; permission = $sourceGrant[0].permission }
    }
}
$null = Api POST '/nocode/application/publish' @{ id = $appId; expectedRevision = $app.application.revision; reason = '经营统计首次验收' }
function EnsureReportRecord($Object, [string]$Title, $Extra) {
    $rows = Api POST '/nocode/runtime/page' @{ applicationId = $appId; objectId = $Object.objectId; pageNo = 1; pageSize = 20; equal = @{ ([string]$Object.definition.titleFieldId) = $Title }; descending = $false }
    if ($rows.total -gt 1) { throw "Duplicate demo record: $Title" }
    if ($rows.total) { return $rows.list[0] }
    $values = @{ ([string]$Object.definition.titleFieldId) = $Title }; foreach ($key in $Extra.Keys) { $values[$key] = $Extra[$key] }
    return (Api POST '/nocode/runtime/save' @{ applicationId = $appId; objectId = $Object.objectId; values = $values }).record
}
$a = EnsureReportRecord $company '报表验收甲公司' @{}
$b = EnsureReportRecord $company '报表验收乙公司' @{}
$bankField = [string]($account.definition.fields | Where-Object code -eq 'bank').id
$bankA = EnsureReportRecord $account '报表验收甲公司基本账户' @{ ([string]$accountRelation.fieldId) = $a.id; $bankField = '验收银行' }
$bankA2 = EnsureReportRecord $account '报表验收甲公司结算账户' @{ ([string]$accountRelation.fieldId) = $a.id; $bankField = '验收银行' }
$bankB = EnsureReportRecord $account '报表验收乙公司基本账户' @{ ([string]$accountRelation.fieldId) = $b.id; $bankField = '验收银行' }
$flows = @()
foreach ($item in @(
    @('甲公司七月收入', $bankA.id, '10000.10', '2026-07-05'), @('甲公司七月支出', $bankA2.id, '-2000.00', '2026-07-20'),
    @('甲公司八月收入', $bankA.id, '18000.20', '2026-08-05'), @('甲公司八月支出', $bankA2.id, '-5000.00', '2026-08-20'),
    @('甲公司九月收入', $bankA.id, '25000.30', '2026-09-01'), @('甲公司九月支出', $bankA2.id, '-6500.00', '2026-09-05'),
    @('乙公司七月收入', $bankB.id, '6000.00', '2026-07-08'), @('乙公司八月收入', $bankB.id, '9000.00', '2026-08-08'),
    @('乙公司九月支出', $bankB.id, '-1200.50', '2026-09-03')
)) {
    $flows += EnsureReportRecord $flow ('报表验收 · ' + $item[0]) @{ ([string]$flowRelation.fieldId) = $item[1]; $amountId = $item[2]; $dateId = $item[3] }
}
$result = @{ applicationId = $appId; sourceApplicationId = $SourceApplicationId; companyA = $a.id; companyB = $b.id
    companyFilter = $companyFilter; amountFieldId = $amountId; dateFieldId = $dateId; flowObjectId = $flow.objectId; flowRecordIds = @($flows.id)
    expectedCompanyATotal = '39500.60'; expectedCompanyASeptember = '18500.30'; expectedCompanyBTotal = '13799.50'
    runtimeUrl = "http://127.0.0.1:5173/nocode-app/runtime?id=$appId" }
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $DemoOutput -Encoding utf8
$result | ConvertTo-Json -Depth 10
