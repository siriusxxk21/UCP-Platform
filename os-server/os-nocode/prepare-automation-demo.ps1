param(
    [ValidateSet('Prepare', 'Configure', 'Verify', 'All')][string]$Mode = 'Prepare',
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$Output = (Join-Path $PSScriptRoot '.work/automation-demo.json')
)
# 本地可复用体验夹具：仅维护约定编码的专属对象、应用及当次标记记录，不复制真实财务数据。
$ErrorActionPreference = 'Stop'
if (([Uri]$BaseUrl).Host -notin @('127.0.0.1', 'localhost', '::1')) { throw '此体验工具仅允许本地开发环境。' }
if (-not $AccessToken) { throw '请通过 AccessToken 参数或 NOCODE_VERIFY_TOKEN 环境变量提供登录会话。' }
function Api([string]$Method, [string]$Path, $Body, [switch]$AllowError) {
    $request = @{Uri=$BaseUrl+$Path;Method=$Method;Headers=@{Authorization='Bearer '+$AccessToken};TimeoutSec=90}
    if ($null -ne $Body) { $request.ContentType='application/json'; $request.Body=ConvertTo-Json -InputObject $Body -Depth 60 -Compress }
    $response = Invoke-RestMethod @request
    if ($AllowError) { return $response }
    if ($null -eq $response -or $response.code -ne 0) { throw "$Path : $($response.msg)" }
    return $response.data
}
function Field([string]$Code, [string]$Name, [string]$Type, [bool]$Required=$false) {
    $value = @{key=$Code;code=$Code;name=$Name;type=$Type;required=$Required;unique=$false;sort=0}
    if ($Type -eq 'TEXT') { $value.length=200 }
    if ($Type -eq 'DECIMAL') { $value.precision=18; $value.scale=2 }
    return $value
}
function EnsureObject([string]$Code, [string]$Name, $Fields, $Options, $Relations) {
    $found = @((Api GET "/nocode/design/page?pageNo=1&pageSize=100&search=$Code" $null).list | Where-Object objectCode -eq $Code)
    if ($found.Count -gt 1) { throw '体验对象编码重复，停止操作。' }
    if ($found.Count) { $design = Api GET "/nocode/design/get?id=$($found[0].id)" $null }
    else {
        $design = Api POST '/nocode/design/save' @{
            draft=@{objectCode=$Code;objectName=$Name;tableName='biz_'+$Code;category='跨对象自动更新体验';titleFieldKey='title';fields=@($Fields);removedFieldIds=@()}
            settings=@{};fieldOptions=$Options;relations=@($Relations);indexes=@();details=@()
        }
    }
    $id = [string]$design.draft.id
    if ($null -eq $design.publishedVersion) {
        $plan = Api POST '/nocode/design/plan' @{id=$id;expectedLockVersion=$design.draft.lockVersion;reason='本地跨对象自动更新体验'}
        if (@($plan.checks | Where-Object blocking).Count) { throw ($plan.checks | ConvertTo-Json -Depth 10) }
        $null = Api POST '/nocode/design/execute' @{planId=$plan.id;reason='本地跨对象自动更新体验'}
    }
    return Api GET "/nocode/application/object-version?id=$id" $null
}
function FieldMap($Object) {
    $map = @{}
    foreach ($field in $Object.definition.fields) { $map[$field.code]=[string]$field.id }
    foreach ($relation in $Object.definition.relations) { $map[$relation.code]=[string]$relation.fieldId }
    return $map
}
function FormResource([string]$Id, [string]$Name, $Object) {
    $nodes = @($Object.definition.fields | ForEach-Object {
        @{id="${Id}_$($_.id)";type='FIELD';fieldId=[string]$_.id;children=@();presentation=@{label=$_.name}}
    })
    return @{id=$Id;kind='FORM';code=$Id;name=$Name;config=@{objectId=[string]$Object.objectId;nodes=$nodes;detailIds=@();options=@{layout='vertical';submitText='保存'}}}
}
function ViewResource([string]$Id, [string]$Name, $Object, [string]$Form, $Equal) {
    $formId=if ($Form) { $Form } else { $null }
    return @{id=$Id;kind='VIEW';code=$Id;name=$Name;config=@{objectId=[string]$Object.objectId;fieldIds=@($Object.definition.fields.id);equal=$Equal;descending=$true;pageSize=10;formId=$formId;list=@{queryFieldIds=@();advancedFieldIds=$null;columnWidths=@{};batchDelete=$false};interaction=@{buttons=@('CREATE','VIEW','UPDATE','DELETE');actionIds=@();editMode='DRAWER';detailMode='DRAWER'}}}
}
function SaveRecord([string]$ObjectId, $Values, $Previous=$null) {
    $body=@{applicationId=$script:demo.applicationId;objectId=$ObjectId;values=$Values;requestKey=[guid]::NewGuid().ToString()}
    if ($Previous) { $body.id=$Previous.id; $body.expectedRevision=$Previous.revision }
    return (Api POST '/nocode/runtime/save' $body).record
}
function GetRecord([string]$ObjectId, [string]$Id) {
    return (Api GET "/nocode/runtime/get?applicationId=$($script:demo.applicationId)&objectId=$ObjectId&id=$Id" $null).record
}
function PageRecords([string]$ObjectId, $Equal=@{}, [string]$ViewId=$null) {
    $query=@{applicationId=$script:demo.applicationId;objectId=$ObjectId;pageNo=1;pageSize=100;equal=$Equal}
    if ($ViewId) { $query.viewId=$ViewId }
    return Api POST '/nocode/runtime/page' $query
}
function DeleteRecord([string]$ObjectId, $Record) {
    $null=Api POST '/nocode/runtime/delete' @{applicationId=$script:demo.applicationId;objectId=$ObjectId;id=$Record.id;expectedRevision=$Record.revision}
}
function WriteManifest {
    $directory=Split-Path -Parent $Output
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $script:demo | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $Output -Encoding utf8
}
if ($Mode -in @('Prepare','All')) {
    $status=EnsureObject 'local_auto_status' '凭证状态（本地自动更新体验）' @((Field 'title' '状态名称' 'TEXT' $true)) @{} @()
    $funds=EnsureObject 'local_auto_funds' '资金流水（本地自动更新体验）' @(
        (Field 'title' '内容明细' 'TEXT' $true),(Field 'amount' '流水金额' 'DECIMAL'),
        (Field 'voucher_count' '有效凭证数' 'INTEGER'),(Field 'voucher_amount' '已登记金额' 'DECIMAL'),
        (Field 'last_note' '最近登记说明' 'TEXT')
    ) @{} @(@{code='voucher_status';name='凭证状态';kind='REFERENCE';targetObjectId=[string]$status.objectId;required=$false;onDelete='RESTRICT'})
    $voucher=EnsureObject 'local_auto_voucher' '凭证登记（本地自动更新体验）' @(
        (Field 'title' '凭证说明' 'TEXT' $true),(Field 'amount' '登记金额' 'DECIMAL'),(Field 'effective' '生效状态' 'SELECT' $true)
    ) @{effective=@{resolver='LOCAL_OPTIONS';defaultValue='valid';options=@(@{code='valid';label='有效';disabled=$false},@{code='void';label='作废';disabled=$false})}} @(
        @{code='funds';name='资金流水';kind='REFERENCE';targetObjectId=[string]$funds.objectId;required=$false;onDelete='RESTRICT'}
    )
    $appCode='local_cross_object_automation'
    $found=@((Api GET "/nocode/application/page?pageNo=1&pageSize=100&search=$appCode" $null).list | Where-Object code -eq $appCode)
    if ($found.Count -gt 1) { throw '体验应用编码重复，停止操作。' }
    if ($found.Count) { $app=Api GET "/nocode/application/get?id=$($found[0].id)" $null }
    else {
        $resources=@(
            (FormResource 'funds_form' '资金流水录入' $funds),(FormResource 'voucher_form' '凭证登记' $voucher),
            (ViewResource 'funds_view' '资金流水' $funds 'funds_form' @{}),
            (ViewResource 'voucher_view' '凭证登记' $voucher 'voucher_form' @{}),
            (ViewResource 'status_view' '凭证状态字典' $status $null @{}),
            @{id='funds_menu';kind='MENU';code='funds_menu';name='资金流水';config=@{targetId='funds_view'}},
            @{id='voucher_menu';kind='MENU';code='voucher_menu';name='凭证登记';config=@{targetId='voucher_view'}}
        )
        $refs=@($status,$funds,$voucher | ForEach-Object { @{objectId=[string]$_.objectId;versionNo=$_.versionNo;checksum=$_.checksum} })
        $app=Api POST '/nocode/application/save' @{code=$appCode;name='跨对象自动更新体验';category='本地功能体验';description='复现资金流水与凭证登记关系，仅包含本地演示数据。';definition=@{objects=$refs;resources=$resources}}
    }
    if ($null -eq $app.application.publishedVersion) { $app=Api POST '/nocode/application/publish' @{id=$app.application.id;expectedRevision=$app.application.revision;reason='本地自动更新体验初始化'} }
    $script:demo=@{applicationId=[string]$app.application.id;fundsObjectId=[string]$funds.objectId;voucherObjectId=[string]$voucher.objectId;statusObjectId=[string]$status.objectId;fundsFields=(FieldMap $funds);voucherFields=(FieldMap $voucher);statusFields=(FieldMap $status);relationId=[string]$voucher.definition.relations[0].id;runtimeUrl="http://127.0.0.1:5173/nocode-app/runtime?id=$($app.application.id)";workspaceUrl="http://127.0.0.1:5173/nocode-app/workspace?id=$($app.application.id)"}
    $statusRows=@((PageRecords $demo.statusObjectId).list)
    foreach ($entry in @(@{key='unregisteredId';name='未登记'},@{key='registeredId';name='已登记'})) {
        $row=@($statusRows | Where-Object { $_.values.($demo.statusFields.title) -eq $entry.name })
        if ($row.Count -gt 1) { throw '体验状态字典重复。' }
        if ($row.Count) { $demo[$entry.key]=[string]$row[0].id }
        else { $demo[$entry.key]=[string](SaveRecord $demo.statusObjectId @{$demo.statusFields.title=$entry.name}).id }
    }
    if ($app.application.publishedVersion -eq 1) {
        $old=@((PageRecords $demo.fundsObjectId @{$demo.fundsFields.title='本地自动更新体验-发布前存量流水'}).list)
        if ($old.Count -gt 1) { throw '发布前存量体验夹具重复。' }
        $historical=if ($old.Count) { $old[0] } else { SaveRecord $demo.fundsObjectId @{$demo.fundsFields.title='本地自动更新体验-发布前存量流水'} }
        $demo.historicalId=$historical.id
    }
    if (Test-Path -LiteralPath $Output) {
        $prior=Get-Content -LiteralPath $Output -Raw | ConvertFrom-Json -AsHashtable
        if ($prior.applicationId -eq $demo.applicationId) {
            foreach ($key in @('lastTest','historicalId','historicalVerified','publishedVersion')) {
                if ($prior.ContainsKey($key)) { $demo[$key]=$prior[$key] }
            }
        }
    }
    WriteManifest
} else { $script:demo=Get-Content -LiteralPath $Output -Raw | ConvertFrom-Json -AsHashtable }
if ($Mode -in @('Configure','All')) {
    $app=Api GET "/nocode/application/get?id=$($demo.applicationId)" $null
    if ($app.application.code -ne 'local_cross_object_automation') { throw '当前应用不属于本工具的体验夹具。' }
    $condition=@{logic='AND';conditions=@(@{fieldId=$demo.voucherFields.effective;operator='eq';value='valid';valueSource='CONSTANT'});groups=@()}
    $rule=@{objectId=$demo.voucherObjectId;targetObjectId=$demo.fundsObjectId;enabled=$true;mode='MAINTAIN';events=@('CREATE','UPDATE','DELETE');conditions=$condition;binding=@{relationId=$demo.relationId;direction='OUTGOING'};assignments=@(
        @{fieldId=$demo.fundsFields.voucher_status;kind='EXISTS';value=$demo.registeredId;emptyValue=$demo.unregisteredId},
        @{fieldId=$demo.fundsFields.voucher_count;kind='COUNT'},
        @{fieldId=$demo.fundsFields.voucher_amount;kind='SUM';sourceFieldId=$demo.voucherFields.amount}
    )}
    $event=@{objectId=$demo.voucherObjectId;targetObjectId=$demo.fundsObjectId;enabled=$true;mode='EVENT';events=@('CREATE','UPDATE');conditions=$condition;binding=@{relationId=$demo.relationId;direction='OUTGOING'};assignments=@(@{fieldId=$demo.fundsFields.last_note;kind='FIELD';sourceFieldId=$demo.voucherFields.title})}
    $additions=@(
        @{id='maintain_registration';kind='AUTOMATION';code='maintain_registration';name='按有效凭证维护流水登记状态';config=$rule},
        @{id='remember_voucher';kind='AUTOMATION';code='remember_voucher';name='保存凭证时记录登记说明';config=$event}
    )
    $funds=Api GET "/nocode/application/object-version?id=$($demo.fundsObjectId)" $null
    $additions+=ViewResource 'unregistered_view' '未登记流水' $funds 'funds_form' @{$demo.fundsFields.voucher_status=$demo.unregisteredId}
    $additions+=@{id='unregistered_menu';kind='MENU';code='unregistered_menu';name='未登记流水';config=@{targetId='unregistered_view'}}
    foreach ($resource in $additions) {
        $existing=@($app.draft.resources | Where-Object id -eq $resource.id)
        if ($existing.Count) { throw "体验资源 $($resource.name) 已存在；保留当前配置，请在界面检查或调整。" }
    }
    $app.draft.resources=@($app.draft.resources)+$additions
    # 使用维护后的普通状态字段限定候选；这是表单选择范围，不作为跨入口唯一登记约束。
    $voucherForm=@($app.draft.resources | Where-Object id -eq 'voucher_form')[0]
    $fundsNode=@($voucherForm.config.nodes | Where-Object fieldId -eq $demo.voucherFields.funds)[0]
    $fundsNode.presentation | Add-Member -NotePropertyName selection -NotePropertyValue @{appearance='SELECT';viewId='unregistered_view'} -Force
    $app=Api POST '/nocode/application/save' @{id=$app.application.id;expectedRevision=$app.application.revision;code=$app.application.code;name=$app.application.name;description=$app.application.description;category=$app.application.category;icon=$app.application.icon;definition=$app.draft}
    $app=Api POST '/nocode/application/publish' @{id=$app.application.id;expectedRevision=$app.application.revision;reason='启用本地通用自动更新体验'}
    $demo.publishedVersion=$app.application.publishedVersion
    WriteManifest
}
if ($Mode -in @('Verify','All')) {
    $tag='自动更新测试-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
    $checks=[System.Collections.Generic.List[object]]::new()
    function Check([string]$Name, [bool]$Passed) {
        $checks.Add(@{name=$Name;passed=$Passed})
        if (-not $Passed) { throw "验证失败：$Name" }
    }
    function State([string]$Id, [string]$Status, [int]$Count, [decimal]$Amount) {
        $r=GetRecord $demo.fundsObjectId $Id
        Check "流水 $Id 状态=$Status 数量=$Count 金额=$Amount" ([string]$r.values.($demo.fundsFields.voucher_status) -eq $Status -and $null -ne $r.values.($demo.fundsFields.voucher_count) -and $null -ne $r.values.($demo.fundsFields.voucher_amount) -and [decimal]$r.values.($demo.fundsFields.voucher_count) -eq $Count -and [decimal]$r.values.($demo.fundsFields.voucher_amount) -eq $Amount)
        return $r
    }
    $a=SaveRecord $demo.fundsObjectId @{$demo.fundsFields.title="$tag-A";$demo.fundsFields.amount=100}
    $b=SaveRecord $demo.fundsObjectId @{$demo.fundsFields.title="$tag-B";$demo.fundsFields.amount=200}
    $demo.lastTest=@{tag=$tag;fundsA=$a.id;fundsB=$b.id;checks=$checks}
    WriteManifest
    try {
        if ($demo.historicalId -and -not $demo.historicalVerified) {
            $historical=GetRecord $demo.fundsObjectId $demo.historicalId
            Check '发布规则不隐式回填存量' ($null -eq $historical.values.($demo.fundsFields.voucher_status))
            $null=SaveRecord $demo.fundsObjectId @{$demo.fundsFields.title='本地自动更新体验-存量已重算'} $historical
            $null=State $historical.id $demo.unregisteredId 0 0
            $demo.historicalVerified=$true
        }
        $null=State $a.id $demo.unregisteredId 0 0
        $null=State $b.id $demo.unregisteredId 0 0
        $v1=SaveRecord $demo.voucherObjectId @{$demo.voucherFields.title="$tag-凭证1";$demo.voucherFields.amount='12.34';$demo.voucherFields.effective='valid';$demo.voucherFields.funds=$a.id}
        $demo.lastTest.voucher1=$v1.id
        $a=State $a.id $demo.registeredId 1 12.34
        Check '事件赋值保存最近登记说明' ($a.values.($demo.fundsFields.last_note) -eq "$tag-凭证1")
        $v2=SaveRecord $demo.voucherObjectId @{$demo.voucherFields.title="$tag-凭证2";$demo.voucherFields.amount='7.66';$demo.voucherFields.effective='valid';$demo.voucherFields.funds=$a.id}
        $demo.lastTest.voucher2=$v2.id
        $null=State $a.id $demo.registeredId 2 20
        DeleteRecord $demo.voucherObjectId $v1
        $null=State $a.id $demo.registeredId 1 7.66
        $v2=SaveRecord $demo.voucherObjectId @{$demo.voucherFields.funds=$b.id} $v2
        $null=State $a.id $demo.unregisteredId 0 0
        $null=State $b.id $demo.registeredId 1 7.66
        $v2=SaveRecord $demo.voucherObjectId @{$demo.voucherFields.effective='void'} $v2
        $null=State $b.id $demo.unregisteredId 0 0
        $v2=SaveRecord $demo.voucherObjectId @{$demo.voucherFields.effective='valid';$demo.voucherFields.amount='8.88'} $v2
        $null=State $b.id $demo.registeredId 1 8.88
        DeleteRecord $demo.voucherObjectId $v2
        $null=State $b.id $demo.unregisteredId 0 0
        $lastRequest=@{applicationId=$demo.applicationId;objectId=$demo.voucherObjectId;requestKey=[guid]::NewGuid().ToString();values=@{$demo.voucherFields.title="$tag-保留凭证";$demo.voucherFields.amount='33.33';$demo.voucherFields.effective='valid';$demo.voucherFields.funds=$a.id}}
        $v3=(Api POST '/nocode/runtime/save' $lastRequest).record
        $demo.lastTest.retainedVoucher=$v3.id
        $a=State $a.id $demo.registeredId 1 33.33
        $replay=(Api POST '/nocode/runtime/save' $lastRequest).record
        $afterReplay=GetRecord $demo.fundsObjectId $a.id
        Check '同一请求重试不重复新增或更新流水' ($replay.id -eq $v3.id -and $afterReplay.revision -eq $a.revision)
        $blocked=Api POST '/nocode/runtime/save' @{applicationId=$demo.applicationId;objectId=$demo.fundsObjectId;id=$a.id;expectedRevision=$a.revision;values=@{$demo.fundsFields.voucher_status=$demo.unregisteredId}} -AllowError
        Check '系统维护字段拒绝手工覆盖' ($blocked.code -ne 0)
        $null=State $a.id $demo.registeredId 1 33.33
        $filtered=PageRecords $demo.fundsObjectId @{} 'unregistered_view'
        Check '普通字段固定视图筛出未登记并排除已登记' ($b.id -in @($filtered.list.id) -and $a.id -notin @($filtered.list.id))
        $candidates=Api POST '/nocode/runtime/selection' @{applicationId=$demo.applicationId;objectId=$demo.voucherObjectId;fieldId=$demo.voucherFields.funds;formId='voucher_form';creating=$true;formValues=@{};pageNo=1;pageSize=100;selected=@()}
        Check '凭证表单候选仅展示未登记流水' ($b.id -in @($candidates.options.value) -and $a.id -notin @($candidates.options.value))
        $demo.lastTest.success=$true
    } finally { WriteManifest }
}
$demo | ConvertTo-Json -Depth 60
