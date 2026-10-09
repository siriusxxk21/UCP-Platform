param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$Output = (Join-Path $PSScriptRoot '.work/nc-review-20260909/fixture.json')
)
# 复现问题表的独立验收应用。只初始化固定编码的专用对象，不覆盖已有应用和业务数据。
$ErrorActionPreference = 'Stop'
$issueOutput = $Output
. "$PSScriptRoot/prepare-company-demo.ps1" -BaseUrl $BaseUrl -AccessToken $AccessToken -FunctionsOnly
$Output = $issueOutput
$code = 'qa_nc_review_0909'
$existing = @((Api GET "/nocode/application/page?pageNo=1&pageSize=100&search=$code" $null).list | Where-Object code -eq $code)
if ($existing.Count) {
    if (Test-Path -LiteralPath $Output) { Get-Content -LiteralPath $Output -Raw; return }
    throw 'Existing regression application has no fixture manifest; inspect instead of overwriting.'
}
$fields = @()
$options = @{}
foreach ($item in @(
    @('title', '验收名称', 'TEXT'), @('code', '验收编码', 'TEXT'),
    @('status', '经营状态', 'SELECT'), @('tags', '公司标签', 'MULTI_SELECT'),
    @('readonly_choice', '只读默认选择', 'SELECT'), @('post', '岗位单选', 'SELECT'),
    @('posts', '岗位多选', 'MULTI_SELECT'), @('user_group', '用户组', 'SELECT'),
    @('departments', '部门多选', 'MULTI_SELECT'), @('owner', '负责人', 'USER'),
    @('active', '是否有效', 'BOOLEAN'), @('amount', '金额', 'DECIMAL'),
    @('rich', '富文本', 'RICH_TEXT'), @('region', '地区', 'REGION'),
    @('cascade', '业务层级', 'CASCADE'), @('files', '附件', 'ATTACHMENT')
)) {
    $f = @{ key=$item[0];code=$item[0];name=$item[1];type=$item[2];sort=$fields.Count;required=($item[0] -eq 'title');unique=($item[0] -eq 'code') }
    if ($item[2] -eq 'TEXT') { $f.length=160 }
    if ($item[2] -eq 'DECIMAL') { $f.precision=18; $f.scale=4 }
    $fields += $f
    $options[$item[0]] = @{classification='NORMAL';state='ACTIVE';options=@();resolver='NONE'}
}
$options.code.pattern = '^QA-[0-9]+$'
$options.status.options = @(@{code='active';label='正常经营';disabled=$false},@{code='closed';label='已关闭';disabled=$false})
$options.status.defaultValue = 'active'
$options.tags.options = @(@{code='key';label='重点客户';disabled=$false},@{code='overseas';label='海外客户';disabled=$false})
$options.readonly_choice.options = @(@{code='first';label='第一项';disabled=$false},@{code='second';label='第二项';disabled=$false})
$options.readonly_choice.defaultValue = 'first'
$options.region.options = @(@{code='south';label='华南';disabled=$false},@{code='north';label='华北';disabled=$false})
$options.cascade.options = @(@{code='parent';label='总公司';disabled=$false},@{code='branch';label='分公司';disabled=$false})
foreach ($pair in @(@('post','POST'),@('posts','POST'),@('user_group','USER_GROUP'),@('departments','DEPARTMENT'))) {
    $options[$pair[0]].selection = @{kind='DIRECTORY';directory=$pair[1];rootIds=@();includeDescendants=$false;organizationTypes=@();defaultMode='NONE'}
}
$found = @((Api GET "/nocode/design/page?pageNo=1&pageSize=100&search=$code" $null).list | Where-Object objectCode -eq $code)
if ($found.Count) { $design = Api GET "/nocode/design/get?id=$($found[0].id)" $null }
else {
    $design = Api POST '/nocode/design/save' @{draft=@{objectCode=$code;objectName='问题复验专用对象0909';tableName="biz_$code";titleFieldKey='title';fields=$fields;removedFieldIds=@()};settings=@{};fieldOptions=$options;relations=@();indexes=@();details=@()}
}
if ($null -eq $design.publishedVersion) {
    $plan = Api POST '/nocode/design/plan' @{id=$design.draft.id;expectedLockVersion=$design.draft.lockVersion;reason='问题复验专用对象'}
    if (@($plan.checks | Where-Object blocking).Count) { throw ($plan.checks | ConvertTo-Json -Depth 10) }
    $null = Api POST '/nocode/design/execute' @{planId=$plan.id;reason='问题复验专用对象'}
}
$object = Api GET "/nocode/application/object-version?id=$($design.draft.id)" $null
$ids = @{}; foreach ($f in $object.definition.fields) { $ids[$f.code] = [string]$f.id }
$nodes = @($object.definition.fields | ForEach-Object {
    $n = Node ('field_' + $_.id) 'FIELD'; $n.fieldId = $_.id
    if ($_.code -eq 'readonly_choice') { $n.presentation=@{readOnly=$true;selection=@{appearance='SELECT';rootIds=$null;defaultValue='second'}} }
    if ($_.code -eq 'departments') { $n.presentation=@{selection=@{appearance='MODAL';rootIds=@()}} }
    $n
})
$form = Resource 'regression_form' 'FORM' '复验表单' @{objectId=$object.objectId;nodes=$nodes;detailIds=@();options=@{layout='vertical';submitText='保存'}}
$view = Resource 'regression_view' 'VIEW' '复验列表' @{objectId=$object.objectId;fieldIds=@($object.definition.fields.id);equal=@{};sortFieldId=$ids.code;descending=$true;pageSize=10;formId=$form.id;list=@{queryFieldIds=@($ids.title,$ids.status);advancedFieldIds=$null;columnWidths=@{};batchDelete=$false}}
$report = Resource 'regression_report' 'REPORT' '复验统计' @{objectId=$object.objectId;dimensions=@();metrics=@(@{id='count';name='记录数';operation='COUNT';fieldId=$null});equal=@{};filterFieldIds=@($ids.status);timeZone='Asia/Shanghai';display='METRIC';descending=$false;limit=20;detailViewId=$view.id}
$rn = Node 'report_block' 'REPORT'; $rn.resourceId=$report.id
$page = Resource 'regression_page' 'PAGE' '统计页面' @{protocolVersion=2;nodes=@($rn)}
$app = Api POST '/nocode/application/save' @{code=$code;name='问题复验专用应用0909';description='NC 问题逐项复现和回归，仅包含专用测试数据';definition=@{objects=@(@{objectId=$object.objectId;versionNo=$object.versionNo;checksum=$object.checksum});resources=@($form,$view,$report,$page,(Resource 'menu_records' 'MENU' '复验列表' @{targetId=$view.id}),(Resource 'menu_report' 'MENU' '复验统计' @{targetId=$page.id}))}}
$appId = [string]$app.application.id
$null = Api POST '/nocode/object-sharing/save' @{objectId=$object.objectId;applicationId=$appId;expectedRevision=0;reason='仅向本轮专用应用授权专用验收对象';permission=@{objectId=$object.objectId;scope='ALL';actions=@('READ','CREATE','UPDATE','DELETE','IMPORT','EXPORT');readFields=@($object.definition.fields.id);writeFields=@($object.definition.fields.id);readDetails=@();writeDetails=@();readRelations=@();writeRelations=@()}}
$null = Api POST '/nocode/application/publish' @{id=$appId;expectedRevision=$app.application.revision;reason='问题复验基线'}
$records=@()
foreach ($item in @(@('复验甲','QA-101','0.4'),@('复验乙','QA-102','0.2'))) {
    $records += (Api POST '/nocode/runtime/save' @{applicationId=$appId;objectId=$object.objectId;values=@{($ids.title)=$item[0];($ids.code)=$item[1];($ids.amount)=$item[2];($ids.status)='active';($ids.tags)=@('key','overseas');($ids.active)=$true;($ids.cascade)=@('parent','branch');($ids.region)=@('south');($ids.rich)='<b>复验富文本</b>';($ids.owner)='1'}}).record.id
}
$result=@{applicationId=$appId;objectId=$object.objectId;fields=$ids;recordIds=$records;runtimeUrl="http://127.0.0.1:5173/nocode-app/runtime?id=$appId"}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Output) | Out-Null
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Output -Encoding utf8
$result | ConvertTo-Json -Depth 12
