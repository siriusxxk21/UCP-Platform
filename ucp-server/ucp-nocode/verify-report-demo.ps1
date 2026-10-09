param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$DemoFile = (Join-Path $PSScriptRoot '.work/report-demo.json'),
    [switch]$CheckWriteRefresh
)
# 可复用实际 HTTP 验收。只读固定编码演示应用和明确记录，导出文件存放在忽略目录。
# 权限撤回和真实多用户由集成测试覆盖；可选写后刷新只修改自身演示金额并在 finally 中恢复。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/prepare-company-demo.ps1" -BaseUrl $BaseUrl -AccessToken $AccessToken -FunctionsOnly
$demo = Get-Content -LiteralPath $DemoFile -Raw | ConvertFrom-Json
if (-not $demo.companyA) { throw 'Demo metadata is incomplete. Preserve the original prepare-report-demo output.' }
$app = Api GET "/nocode/application/get?id=$($demo.applicationId)" $null
if ($app.application.code -ne 'company_report_demo') { throw 'Only the reporting demonstration can be verified.' }
function Check($Actual, $Expected, [string]$Message) {
    if ([string]$Actual -cne [string]$Expected) { throw "$Message : expected=$Expected actual=$Actual" }
}
$results = @()
foreach ($scenario in @(
    @{ name = '甲公司全部月份'; company = $demo.companyA; count = 6; total = $demo.expectedCompanyATotal; groups = 3 },
    @{ name = '甲公司九月'; company = $demo.companyA; count = 2; total = $demo.expectedCompanyASeptember; groups = 1; from = '2026-09-01'; to = '2026-09-30' },
    @{ name = '乙公司全部月份'; company = $demo.companyB; count = 3; total = $demo.expectedCompanyBTotal; groups = 3 },
    @{ name = '无数据月份'; company = $demo.companyA; count = 0; total = '0'; groups = 0; from = '2026-01-01'; to = '2026-01-31' }
)) {
    $query = @{ applicationId = $demo.applicationId; reportId = 'report_summary'; equal = @{ ([string]$demo.companyFilter) = $scenario.company }; pageNo = 1; pageSize = 20 }
    if ($scenario.from) { $query.dateFrom = $scenario.from; $query.dateTo = $scenario.to }
    $r = Api POST '/nocode/runtime/report' $query
    Check $r.recordCount $scenario.count '统计记录数'
    Check $r.totals.amount $scenario.total '精确金额'
    Check $r.totalGroups $scenario.groups '月份分组'
    $detail = Api POST '/nocode/runtime/report-details' $query
    Check $detail.total $scenario.count '全部明细'
    $sum = [decimal]0
    foreach ($row in $detail.list) { $sum += [decimal]$row.values.($demo.amountFieldId) }
    if ($sum -ne [decimal]$scenario.total) { throw 'Drill total differs from aggregation.' }
    foreach ($group in $r.groups) {
        $query.group = @($group.keys)
        $rows = Api POST '/nocode/runtime/report-details' $query
        Check $rows.total $group.values.count '分组下钻记录数'
        if (@($rows.list | Where-Object { $_.id -notin $demo.flowRecordIds }).Count) { throw 'Unexpected non-demo row in drilldown.' }
    }
    $query.Remove('group')
    $results += @{ scenario = $scenario.name; total = $r.totals.amount; count = $r.recordCount; groups = $r.totalGroups; drill = 'PASS' }
    if ($scenario.name -eq '甲公司九月') { $exportQuery = $query.Clone() }
}
$exportPath = Join-Path $PSScriptRoot '.work/report-export.xlsx'
Invoke-WebRequest -Uri ($BaseUrl + '/nocode/runtime/report-export') -Method Post -Headers @{ Authorization = 'Bearer ' + $AccessToken } -ContentType 'application/json' -Body ($exportQuery | ConvertTo-Json -Depth 10) -OutFile $exportPath
# Excel 必须为真实压缩工作簿，并含服务器输出的精确金额字符串；不把业务错误 JSON 当作下载通过。
$archive = [IO.Compression.ZipFile]::OpenRead($exportPath)
try {
    $text = ''
    foreach ($name in @('xl/sharedStrings.xml', 'xl/worksheets/sheet1.xml')) {
        $entry = $archive.GetEntry($name)
        if (-not $entry) { continue }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $text += $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    if (-not $text.Contains('18500.30')) { throw 'Exact amount missing from Excel.' }
} finally { $archive.Dispose() }
$writeResult = 'NOT_REQUESTED'
if ($CheckWriteRefresh) {
    $id = [string]$demo.flowRecordIds[0]
    $path = "/nocode/runtime/get?applicationId=$($demo.applicationId)&objectId=$($demo.flowObjectId)&id=$id"
    $before = Api GET $path $null
    $model = Api GET "/nocode/runtime/model?applicationId=$($demo.applicationId)&objectId=$($demo.flowObjectId)" $null
    if ([string]$before.record.values.($model.object.titleFieldId) -notlike '报表验收 · *') { throw 'Refusing to modify a non-demo record.' }
    $original = [string]$before.record.values.($demo.amountFieldId)
    $changed = ([decimal]$original + 1).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
    $changedRow = $null
    try {
        $changedRow = Api POST '/nocode/runtime/save' @{ applicationId=$demo.applicationId; objectId=$demo.flowObjectId; id=$id; expectedRevision=$before.record.revision; values=@{ ([string]$demo.amountFieldId)=$changed } }
        $request = @{ applicationId=$demo.applicationId; reportId='report_overview'; equal=@{ ([string]$demo.companyFilter)=$demo.companyA } }
        $updated = Api POST '/nocode/runtime/report' $request
        Check $updated.totals.amount '39501.60' '写后刷新'
    } finally {
        if ($changedRow) {
            $null = Api POST '/nocode/runtime/save' @{ applicationId=$demo.applicationId; objectId=$demo.flowObjectId; id=$id; expectedRevision=$changedRow.record.revision; values=@{ ([string]$demo.amountFieldId)=$original } }
        }
    }
    Check (Api POST '/nocode/runtime/report' $request).totals.amount $demo.expectedCompanyATotal '恢复原金额'
    $writeResult = 'PASS_RESTORED'
}
$proof = @{ checkedAt = (Get-Date -Format o); applicationId = $demo.applicationId; scenarios = $results; export = 'PASS'; writeRefresh=$writeResult; exportBytes = (Get-Item -LiteralPath $exportPath).Length }
$proof | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $PSScriptRoot '.work/report-api-verification.json') -Encoding utf8
$proof | ConvertTo-Json -Depth 10
