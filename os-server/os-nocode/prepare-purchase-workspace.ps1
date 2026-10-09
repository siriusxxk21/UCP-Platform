param(
    [ValidateSet('Configure', 'Seed', 'Verify')][string]$Mode = 'Configure',
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [string]$Output = (Join-Path $PSScriptRoot '.work/purchase-workspace.json')
)
# 本地采购场景配置工具；只维护清单中登记的专属资源，保留同名或同编码的既有业务。
$ErrorActionPreference = 'Stop'
if (([Uri]$BaseUrl).Host -notin @('127.0.0.1', 'localhost', '::1')) { throw '仅允许配置本地开发环境。' }
if (-not $AccessToken) { throw '请通过 AccessToken 或 NOCODE_VERIFY_TOKEN 提供登录会话。' }
$script:workspace = if (Test-Path -LiteralPath $Output) { Get-Content -LiteralPath $Output -Raw | ConvertFrom-Json -AsHashtable } else { @{} }
function Remember {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Output) -Force | Out-Null
    $script:workspace | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $Output -Encoding utf8
}
function Api([string]$Method, [string]$Path, $Body) {
    $request = @{Uri=$BaseUrl+$Path;Method=$Method;Headers=@{Authorization='Bearer '+$AccessToken};TimeoutSec=60}
    if ($null -ne $Body) { $request.ContentType='application/json'; $request.Body=$Body | ConvertTo-Json -Depth 60 -Compress }
    $response=Invoke-RestMethod @request
    if ($response.code -ne 0) { throw "$Path : $($response.msg)" }
    return $response.data
}
function Catalog([string]$Path) {
    $rows=@(); $page=1
    do { $result=Api GET ($Path+"?pageNo=$page&pageSize=100") $null; $rows+=@($result.list); $page++ } while ($rows.Count -lt $result.total -and @($result.list).Count -gt 0)
    return $rows
}
function Field([string]$Code, [string]$Name, [string]$Type='TEXT', [bool]$Required=$false, [bool]$Unique=$false) {
    $f=@{key=$Code;code=$Code;name=$Name;type=$Type;required=$Required;unique=$Unique;sort=0}
    if ($Type -eq 'TEXT') { $f.length=200 }
    return $f
}
function Object([string]$Key, [string]$Code, [string]$Name, $Fields, $Options, $Relations) {
    $found=@((Catalog '/nocode/design/page') | Where-Object objectCode -eq $Code)
    if ($found.Count -gt 1 -or ($found.Count -eq 1 -and $workspace[$Key] -ne $found[0].id)) { throw "对象编码 $Code 已被其他业务使用。" }
    if ($found.Count) { $design=Api GET "/nocode/design/get?id=$($found[0].id)" $null }
    else {
        $design=Api POST '/nocode/design/save' @{draft=@{objectCode=$Code;objectName=$Name;tableName='biz_'+$Code;category='采购管理';titleFieldKey='title';fields=@($Fields);removedFieldIds=@()};settings=@{};fieldOptions=$Options;relations=@($Relations);indexes=@();details=@()}
        $workspace[$Key]=[string]$design.draft.id; Remember
    }
    if ($null -eq $design.publishedVersion) {
        $plan=Api POST '/nocode/design/plan' @{id=$design.draft.id;expectedLockVersion=$design.draft.lockVersion;reason='配置采购到货业务'}
        if (@($plan.checks | Where-Object blocking).Count) { throw '对象发布存在阻断项，请检查数据中心。' }
        $null=Api POST '/nocode/design/execute' @{planId=$plan.id;reason='配置采购到货业务'}
    }
    return Api GET "/nocode/application/object-version?id=$($design.draft.id)" $null
}
function Fields($Object) {
    $map=@{}; foreach ($field in $Object.definition.fields) { $map[$field.code]=[string]$field.id }
    foreach ($relation in $Object.definition.relations) { $map[$relation.code]=[string]$relation.fieldId }
    return $map
}
function Form([string]$Id, [string]$Name, $Object) {
    $nodes=@($Object.definition.fields | ForEach-Object { @{id="${Id}_$($_.id)";type='FIELD';fieldId=[string]$_.id;children=@();presentation=@{label=$_.name}} })
    return @{id=$Id;kind='FORM';code=$Id;name=$Name;config=@{objectId=[string]$Object.objectId;nodes=$nodes;detailIds=@();options=@{layout='vertical';submitText='保存'}}}
}
function View([string]$Id, [string]$Name, $Object, [string]$FormId, $Columns, $Equal=@{}, $Actions=@()) {
    return @{id=$Id;kind='VIEW';code=$Id;name=$Name;config=@{objectId=[string]$Object.objectId;fieldIds=@($Columns);equal=$Equal;descending=$false;pageSize=10;formId=$FormId;list=@{queryFieldIds=@();advancedFieldIds=$null;columnWidths=@{};batchDelete=$false};interaction=@{buttons=@('CREATE','VIEW','UPDATE','DELETE');actionIds=@($Actions);editMode='DRAWER';detailMode='DRAWER'}}}
}
function Save([string]$ObjectId, $Values, $Previous=$null) {
    $body=@{applicationId=$workspace.applicationId;objectId=$ObjectId;values=$Values;requestKey=[guid]::NewGuid().ToString()}
    if ($Previous) { $body.id=$Previous.id; $body.expectedRevision=$Previous.revision }
    return (Api POST '/nocode/runtime/save' $body).record
}
function Record([string]$ObjectId, [string]$Id) { return (Api GET "/nocode/runtime/get?applicationId=$($workspace.applicationId)&objectId=$ObjectId&id=$Id" $null).record }
function Action([string]$Id, $Record) { return (Api POST '/nocode/runtime/action' @{applicationId=$workspace.applicationId;objectId=$workspace.receiptObjectId;actionId=$Id;recordId=$Record.id;expectedRevision=$Record.revision}).record }
if ($Mode -ne 'Configure') {
    if (-not $workspace.applicationId) { throw '请先配置采购应用。' }
    $current=Api GET "/nocode/application/get?id=$($workspace.applicationId)" $null
    if ($current.application.code -ne 'purchase_receiving' -or $workspace.orderObjectId -notin @($current.draft.objects.objectId) -or $workspace.receiptObjectId -notin @($current.draft.objects.objectId)) { throw '当前资源与本工具创建的采购应用不一致。' }
    $workspace.publishedVersion=$current.application.publishedVersion
    $workspace.applicationRevision=$current.application.revision
    Remember
}
if ($Mode -eq 'Configure') {
    if ($workspace.applicationId) { throw '采购应用已经配置，请在业务动作界面调整，避免覆盖当前草稿。' }
    $order=Object 'orderObjectId' 'purchase_order' '采购订单' @(
        (Field 'title' '采购主题' 'TEXT' $true), (Field 'order_no' '采购单号' 'TEXT' $true $true),
        (Field 'item_name' '采购物品' 'TEXT' $true), (Field 'specification' '规格型号'), (Field 'supplier' '供应商'),
        (Field 'order_date' '采购日期' 'DATE'), (Field 'quantity' '采购数量' 'INTEGER' $true), (Field 'unit' '单位'),
        (Field 'arrival_status' '到货情况' 'SELECT'), (Field 'received_quantity' '累计到货数量' 'INTEGER'),
        (Field 'receipt_count' '有效到货批次' 'INTEGER'), (Field 'last_note' '最近到货说明' 'TEXTAREA')
    ) @{arrival_status=@{resolver='LOCAL_OPTIONS';options=@(@{code='none';label='未到货';disabled=$false},@{code='received';label='已有到货';disabled=$false})}} @()
    $receipt=Object 'receiptObjectId' 'goods_receipt' '到货登记' @(
        (Field 'title' '到货单号' 'TEXT' $true $true), (Field 'receipt_date' '到货日期' 'DATE' $true),
        (Field 'quantity' '本次到货数量' 'INTEGER' $true), (Field 'receiver' '收货人'),
        (Field 'status' '登记状态' 'SELECT' $true), (Field 'note' '到货说明' 'TEXTAREA')
    ) @{status=@{resolver='LOCAL_OPTIONS';defaultValue='valid';options=@(@{code='valid';label='有效';disabled=$false},@{code='void';label='作废';disabled=$false})}} @(@{code='purchase_order';name='采购订单';kind='REFERENCE';targetObjectId=[string]$order.objectId;required=$true;onDelete='RESTRICT'})
    $workspace.orderFields=Fields $order; $workspace.receiptFields=Fields $receipt; $workspace.relationId=[string]$receipt.definition.relations[0].id
    Remember
    $of=$workspace.orderFields; $rf=$workspace.receiptFields
    $condition=@{logic='AND';conditions=@(@{fieldId=$rf.status;operator='eq';value='valid';valueSource='CONSTANT'});groups=@()}
    $maintain=@{objectId=[string]$receipt.objectId;targetObjectId=[string]$order.objectId;enabled=$true;mode='MAINTAIN';events=@('CREATE','UPDATE','DELETE');conditions=$condition;binding=@{relationId=$workspace.relationId;direction='OUTGOING'};assignments=@(
        @{fieldId=$of.arrival_status;kind='EXISTS';value='received';emptyValue='none'},@{fieldId=$of.received_quantity;kind='SUM';sourceFieldId=$rf.quantity},@{fieldId=$of.receipt_count;kind='COUNT'}
    )}
    $remember=@{objectId=[string]$receipt.objectId;targetObjectId=[string]$order.objectId;enabled=$true;mode='EVENT';events=@('CREATE','UPDATE');conditions=$condition;binding=@{relationId=$workspace.relationId;direction='OUTGOING'};assignments=@(@{fieldId=$of.last_note;kind='FIELD';sourceFieldId=$rf.note})}
    $receiptForm=Form 'receipt_form' '到货登记' $receipt
    # 分批到货必须仍能选择已有到货的订单，不能用“未到货订单”作为候选限定范围。
    $receiptForm.config.nodes=@($receiptForm.config.nodes | Sort-Object { if ($_.fieldId -eq $rf.purchase_order) {0} else {1} })
    ($receiptForm.config.nodes | Where-Object fieldId -eq $rf.purchase_order).presentation.selection=@{appearance='SELECT';viewId='orders_view'}
    $columns=@($of.title,$of.order_no,$of.quantity,$of.unit,$of.received_quantity,$of.receipt_count,$of.arrival_status)
    $resources=@(
        (Form 'order_form' '采购订单' $order), $receiptForm,
        (View 'orders_view' '采购订单' $order 'order_form' $columns),
        (View 'unreceived_view' '未到货订单' $order 'order_form' $columns @{$of.arrival_status='none'}),
        (View 'receipts_view' '到货登记' $receipt 'receipt_form' @($rf.title,$rf.purchase_order,$rf.receipt_date,$rf.quantity,$rf.status,$rf.note) @{} @('void_receipt','restore_receipt')),
        @{id='orders_menu';kind='MENU';code='orders_menu';name='采购订单';config=@{targetId='orders_view'}},
        @{id='receipts_menu';kind='MENU';code='receipts_menu';name='到货登记';config=@{targetId='receipts_view'}},
        @{id='unreceived_menu';kind='MENU';code='unreceived_menu';name='未到货订单';config=@{targetId='unreceived_view'}},
        @{id='void_receipt';kind='ACTION';code='void_receipt';name='作废到货登记';config=@{objectId=[string]$receipt.objectId;kind='UPDATE_FIELDS';values=@{$rf.status='void'};variables=@{};processDefinitionId=$null}},
        @{id='restore_receipt';kind='ACTION';code='restore_receipt';name='恢复有效';config=@{objectId=[string]$receipt.objectId;kind='UPDATE_FIELDS';values=@{$rf.status='valid'};variables=@{};processDefinitionId=$null}},
        @{id='maintain_arrivals';kind='AUTOMATION';code='maintain_arrivals';name='维护采购订单到货情况';config=$maintain},
        @{id='remember_arrival';kind='AUTOMATION';code='remember_arrival';name='更新采购订单最近到货说明';config=$remember}
    )
    if (@((Catalog '/nocode/application/page') | Where-Object code -eq 'purchase_receiving').Count) { throw '应用编码 purchase_receiving 已使用，保留既有应用。' }
    $app=Api POST '/nocode/application/save' @{code='purchase_receiving';name='采购到货管理';category='采购管理';description='采购订单与分批到货登记，按有效到货记录维护订单到货情况和累计数量。';definition=@{objects=@($order,$receipt | ForEach-Object { @{objectId=[string]$_.objectId;versionNo=$_.versionNo;checksum=$_.checksum} });resources=$resources}}
    $workspace.applicationId=[string]$app.application.id; Remember
    $app=Api POST '/nocode/application/publish' @{id=$app.application.id;expectedRevision=$app.application.revision;reason='启用采购订单与分批到货业务'}
    $workspace.publishedVersion=$app.application.publishedVersion
    $workspace.runtimeUrl="http://127.0.0.1:5173/nocode-app/runtime?id=$($workspace.applicationId)"
    $workspace.workspaceUrl="http://127.0.0.1:5173/nocode-app/workspace?id=$($workspace.applicationId)"
    Remember
}
if ($Mode -eq 'Seed') {
    if (-not $workspace.applicationId) { throw '请先配置采购应用。' }
    if ($workspace.records) { throw '业务数据已建立，保留当前数据，请从页面继续操作。' }
    $of=$workspace.orderFields; $rf=$workspace.receiptFields
    $workspace.records=@{}; Remember
    $a=Save $workspace.orderObjectId @{$of.title='行政办公椅采购';$of.order_no='CG20260923001';$of.item_name='人体工学办公椅';$of.specification='黑色网布／可升降';$of.supplier='青禾办公家具';$of.order_date='2026-09-20';$of.quantity=100;$of.unit='把'}
    $workspace.records.chairs=$a.id; Remember
    $b=Save $workspace.orderObjectId @{$of.title='档案室文件柜采购';$of.order_no='CG20260923002';$of.item_name='钢制文件柜';$of.specification='双门／灰白色';$of.supplier='青禾办公家具';$of.order_date='2026-09-23';$of.quantity=6;$of.unit='个'}
    $workspace.records.cabinets=$b.id; Remember
    $r1=Save $workspace.receiptObjectId @{$rf.title='DH20260923001';$rf.purchase_order=$a.id;$rf.receipt_date='2026-09-22';$rf.quantity=40;$rf.receiver='李明';$rf.status='valid';$rf.note='首批40把办公椅已到货，外包装完好。'}
    $workspace.records.firstReceipt=$r1.id; Remember
    $r2=Save $workspace.receiptObjectId @{$rf.title='DH20260923002';$rf.purchase_order=$a.id;$rf.receipt_date='2026-09-23';$rf.quantity=60;$rf.receiver='李明';$rf.status='valid';$rf.note='第二批60把办公椅已到货，完成本次交付。'}
    $workspace.records.secondReceipt=$r2.id; Remember
}
if ($Mode -eq 'Verify') {
    $of=$workspace.orderFields; $rf=$workspace.receiptFields
    $checks=[System.Collections.Generic.List[object]]::new()
    function Check([string]$Name,[bool]$Passed) { $checks.Add(@{name=$Name;passed=$Passed}); if (-not $Passed) {throw "验证失败：$Name"} }
    function State([string]$Id,[string]$Status,[int]$Count,[int]$Quantity) {
        $row=Record $workspace.orderObjectId $Id
        Check "订单$Id 到货情况=$Status 批次=$Count 数量=$Quantity" ($row.values.($of.arrival_status) -eq $Status -and $null -ne $row.values.($of.receipt_count) -and [int]$row.values.($of.receipt_count) -eq $Count -and $null -ne $row.values.($of.received_quantity) -and [int]$row.values.($of.received_quantity) -eq $Quantity)
        return $row
    }
    $r1=Record $workspace.receiptObjectId $workspace.records.firstReceipt
    $r2=Record $workspace.receiptObjectId $workspace.records.secondReceipt
    try {
        $a=State $workspace.records.chairs 'received' 2 100
        $null=State $workspace.records.cabinets 'none' 0 0
        Check '有效到货保存后更新最近到货说明' ($a.values.($of.last_note) -eq $r2.values.($rf.note))
        $r2=Action 'void_receipt' $r2
        $null=State $workspace.records.chairs 'received' 1 40
        $r1=Action 'void_receipt' $r1
        $null=State $workspace.records.chairs 'none' 0 0
        $r1=Action 'restore_receipt' $r1
        $null=State $workspace.records.chairs 'received' 1 40
        $r2=Action 'restore_receipt' $r2
        $null=State $workspace.records.chairs 'received' 2 100
        $candidates=Api POST '/nocode/runtime/selection' @{applicationId=$workspace.applicationId;objectId=$workspace.receiptObjectId;fieldId=$rf.purchase_order;formId='receipt_form';creating=$true;formValues=@{};pageNo=1;pageSize=100;selected=@()}
        Check '已有到货订单仍可登记下一批到货' ($workspace.records.chairs -in @($candidates.options.value))
        $filtered=Api POST '/nocode/runtime/page' @{applicationId=$workspace.applicationId;objectId=$workspace.orderObjectId;viewId='unreceived_view';pageNo=1;pageSize=100;equal=@{}}
        Check '未到货订单视图只显示未到货采购单' ($workspace.records.cabinets -in @($filtered.list.id) -and $workspace.records.chairs -notin @($filtered.list.id))
        $workspace.verification=@{passed=$true;verifiedAt=(Get-Date -Format o);checks=$checks}
    } finally {
        # 验收只作废/恢复本工具建立的两条记录，结束后保留40+60的正常业务状态。
        foreach ($id in @($workspace.records.firstReceipt,$workspace.records.secondReceipt)) {
            $current=Record $workspace.receiptObjectId $id
            if ($current.values.($rf.status) -eq 'void') { $null=Action 'restore_receipt' $current }
        }
        Remember
    }
}
$workspace | ConvertTo-Json -Depth 60
