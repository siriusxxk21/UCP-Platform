param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$AccessToken = $env:NOCODE_VERIFY_TOKEN,
    [Parameter(Mandatory)][string]$ActorId,
    [switch]$UseDatabaseFiles,
    [switch]$IncludeRelations,
    [string]$Output = (Join-Path $PSScriptRoot '.work/business-demo.json')
)
# 可复用演示初始化：仅创建约定编码的客户、订单、应用和审批模型。
# 已有资源直接复用，绝不覆盖用户改过的设计；凭据不写入输出文件。
$ErrorActionPreference = 'Stop'
if (-not $AccessToken) { throw 'Provide an authenticated AccessToken.' }
function Api([string]$Method, [string]$Path, $Body) {
    $args = @{Uri=$BaseUrl+$Path;Method=$Method;Headers=@{Authorization='Bearer '+$AccessToken};TimeoutSec=60}
    if ($null -ne $Body) { $args.ContentType='application/json';$args.Body=ConvertTo-Json -InputObject $Body -Depth 40 -Compress }
    $value=Invoke-RestMethod @args
    if ($null -eq $value -or $value.code -ne 0) { throw "$Path : $($value.msg)" }
    return $value.data
}
function Field([string]$Code,[string]$Name,[string]$Type,[bool]$Required=$false,[bool]$Unique=$false) {
    $field=@{key=$Code;code=$Code;name=$Name;type=$Type;required=$Required;unique=$Unique;sort=0}
    if ($Type -eq 'TEXT') { $field.length=160 }
    return $field
}
function Object([string]$Code,[string]$Name,$Fields,$Options,$Relations,$Details) {
    $found=@((Api GET "/nocode/design/page?pageNo=1&pageSize=100&search=$Code" $null).list | Where-Object objectCode -eq $Code)
    if ($found.Count -gt 1) { throw 'Duplicate demonstration object code' }
    if ($found.Count -eq 1) { $design=Api GET "/nocode/design/get?id=$($found[0].id)" $null }
    else {
        $design=Api POST '/nocode/design/save' @{
            draft=@{objectCode=$Code;objectName=$Name;tableName='biz_'+$Code;titleFieldKey='title';fields=@($Fields);removedFieldIds=@()}
            settings=@{};fieldOptions=$Options;relations=@($Relations);indexes=@();details=@($Details)
        }
    }
    $id=[string]$design.draft.id
    if ($null -eq $design.publishedVersion) {
        $plan=Api POST '/nocode/design/plan' @{id=$id;expectedLockVersion=$design.draft.lockVersion;reason='应用演示初始化'}
        if (@($plan.checks | Where-Object blocking).Count) { throw ($plan.checks | ConvertTo-Json -Depth 10) }
        $null=Api POST '/nocode/design/execute' @{planId=$plan.id;reason='应用演示初始化'}
    }
    return Api GET "/nocode/application/object-version?id=$id" $null
}
$customer=Object 'app_demo_customer' '演示客户' @((Field 'title' '客户名称' 'TEXT' $true),(Field 'email' '邮箱' 'TEXT')) @{} @() @()
$customerId=[string]$customer.objectId
$fields=@(
    (Field 'title' '订单名称' 'TEXT' $true),
    (Field 'order_number' '订单编号' 'TEXT' $true $true),
    (Field 'order_state' '处理状态' 'SELECT' $true),
    (Field 'handler' '经办人' 'USER'),
    (Field 'department' '所属部门' 'DEPARTMENT'),
    (Field 'attachments' '附件' 'ATTACHMENT')
)
$options=@{order_state=@{resolver='LOCAL_OPTIONS';defaultValue='open';options=@(@{code='open';label='待处理';disabled=$false},@{code='done';label='已完成';disabled=$false})}}
$relations=@(@{code='customer';name='客户';kind='REFERENCE';targetObjectId=$customerId;required=$false;onDelete='RESTRICT'})
$details=@(@{code='items';name='订单明细';tableName='biz_app_demo_items';state='ACTIVE';fields=@((Field 'item_name' '商品名称' 'TEXT' $true),(Field 'quantity' '数量' 'INTEGER' $true));fieldOptions=@{};indexes=@()})
$order=Object 'app_demo_order' '演示订单' $fields $options $relations $details
$orderId=[string]$order.objectId
$fieldIds=@{}
$order.definition.fields | ForEach-Object { $fieldIds[$_.code]=[string]$_.id }
$models=@(Api GET '/bpm/model/list?name=应用订单审批演示' $null | Where-Object key -eq 'nocode_demo_order_approval')
if (-not $models.Count) {
    $body=@{key='nocode_demo_order_approval';name='应用订单审批演示';type=20;formType=20;visible=$false;startUserIds=@();startDeptIds=@();managerUserIds=@($ActorId);allowCancelRunningProcess=$true;allowWithdrawTask=$false;autoApprovalType=0;
        formCustomCreatePath='/nocode-app/mine';formCustomViewPath='/nocode-app/process-record';
        simpleModel=@{id='StartUserNode';type=10;name='发起人';childNode=@{id='ApproveOrder';type=11;name='订单审批';candidateStrategy=30;candidateParam=$ActorId;approveType=1;approveMethod=1;assignStartUserHandlerType=1;signEnable=$false;reasonRequire=$false;childNode=@{id='EndEvent';type=1;name='结束'}}}}
    $modelId=Api POST '/bpm/model/create' $body
    $null=Api POST "/bpm/model/deploy?id=$modelId" $null
} else { $modelId=$models[0].id }
$model=Api GET "/bpm/model/get?id=$modelId" $null
$definitionId=$model.processDefinition.id
if (-not $definitionId) {
    $definition=Api GET '/bpm/process-definition/get?key=nocode_demo_order_approval' $null
    if (-not $definition.id) { throw 'Publish the demonstration process model first.' }
    $definitionId=$definition.id
}
$apps=@((Api GET '/nocode/application/page?pageNo=1&pageSize=100&search=business_demo' $null).list | Where-Object code -eq 'business_demo')
if (-not $apps.Count) {
    $resources=@(
        @{id='state_dictionary';kind='DICTIONARY';code='state_dictionary';name='处理状态';config=@{items=$options.order_state.options}},
        @{id='order_number';kind='NUMBER_RULE';code='order_number';name='订单编号';config=@{objectId=$orderId;fieldId=$fieldIds.order_number;prefix='ORD-';period='DAY';width=4}},
        @{id='complete_order';kind='ACTION';code='complete_order';name='标记完成';config=@{objectId=$orderId;kind='UPDATE_FIELDS';values=@{$fieldIds.order_state='done'}}},
        @{id='submit_approval';kind='ACTION';code='submit_approval';name='提交审批';config=@{objectId=$orderId;kind='START_PROCESS';processDefinitionId=$definitionId;variables=@{nc_title=$fieldIds.title}}},
        @{id='order_view';kind='VIEW';code='order_view';name='订单列表';config=@{objectId=$orderId;fieldIds=@($fieldIds.order_number,$fieldIds.title,$fieldIds.order_state,$fieldIds.handler,$fieldIds.department);equal=@{};descending=$true;pageSize=10;filterDictionaries=@{$fieldIds.order_state='state_dictionary'}}},
        @{id='customer_view';kind='VIEW';code='customer_view';name='客户列表';config=@{objectId=$customerId;fieldIds=@($customer.definition.fields.id);equal=@{};descending=$true;pageSize=10}},
        @{id='order_menu';kind='MENU';code='order_menu';name='订单管理';config=@{targetId='order_view'}},
        @{id='customer_menu';kind='MENU';code='customer_menu';name='客户管理';config=@{targetId='customer_view'}}
    )
    $refs=@(@{objectId=$orderId;versionNo=$order.versionNo;checksum=$order.checksum},@{objectId=$customerId;versionNo=$customer.versionNo;checksum=$customer.checksum})
    $app=Api POST '/nocode/application/save' @{code='business_demo';name='业务闭环演示';description='客户、订单、自动编号、字典、主从表单与底座审批的可复用演示';definition=@{objects=$refs;resources=$resources}}
    $app=Api POST '/nocode/application/publish' @{id=$app.application.id;expectedRevision=$app.application.revision;reason='业务闭环演示首次发布'}
    $appId=[string]$app.application.id
} else { $appId=[string]$apps[0].id }
$result=@{applicationId=$appId;orderObjectId=$orderId;customerObjectId=$customerId;processModelId=$modelId;processDefinitionId=$definitionId;fields=$fieldIds;detailId=$order.definition.details[0].id;runtimeUrl="http://127.0.0.1:5173/nocode-app/runtime?id=$appId"}
if (Test-Path -LiteralPath $Output) {
    $previous=Get-Content -LiteralPath $Output -Raw | ConvertFrom-Json
    if ($previous.fileStorage) { $result.fileStorage=$previous.fileStorage }
    if ($previous.relationDemo) { $result.relationDemo=$previous.relationDemo }
}
if ($IncludeRelations) {
    # 独立演示应用验证多对多和实时汇总，不升级/覆盖已有订单对象及其运行版本。
    $projectFields=@((Field 'title' '项目名称' 'TEXT' $true),(Field 'work_count' '工作项数' 'SUMMARY'),(Field 'work_total' '总工作量' 'SUMMARY'))
    $projectOptions=@{
        work_count=@{state='ACTIVE';expression='count(work)';resultType='INTEGER'}
        work_total=@{state='ACTIVE';expression='sum(work.quantity)';resultType='DECIMAL'}
    }
    $projectRelations=@(@{code='partners';name='合作客户';kind='MANY_TO_MANY';targetObjectId=$customerId;required=$false;onDelete='RESTRICT'})
    $projectDetails=@(@{code='work';name='工作项';tableName='biz_app_demo_project_work';state='ACTIVE';fields=@((Field 'title' '工作名称' 'TEXT' $true),(Field 'quantity' '工作量' 'INTEGER' $true));fieldOptions=@{};indexes=@()})
    $project=Object 'app_demo_project' '演示合作项目' $projectFields $projectOptions $projectRelations $projectDetails
    $projectId=[string]$project.objectId
    $relationApps=@((Api GET '/nocode/application/page?pageNo=1&pageSize=100&search=relations_demo' $null).list | Where-Object code -eq 'relations_demo')
    if (-not $relationApps.Count) {
        $resources=@(
            @{id='project_view';kind='VIEW';code='project_view';name='合作项目';config=@{objectId=$projectId;fieldIds=@($project.definition.fields.id);equal=@{};descending=$true;pageSize=10}},
            @{id='customer_view';kind='VIEW';code='customer_view';name='客户列表';config=@{objectId=$customerId;fieldIds=@($customer.definition.fields.id);equal=@{};descending=$true;pageSize=10}},
            @{id='project_menu';kind='MENU';code='project_menu';name='合作项目';config=@{targetId='project_view'}},
            @{id='customer_menu';kind='MENU';code='customer_menu';name='客户资料';config=@{targetId='customer_view'}}
        )
        $refs=@(@{objectId=$projectId;versionNo=$project.versionNo;checksum=$project.checksum},@{objectId=$customerId;versionNo=$customer.versionNo;checksum=$customer.checksum})
        $relationApp=Api POST '/nocode/application/save' @{code='relations_demo';name='关联与汇总演示';description='多对多客户选择、工作项明细和实时汇总，可用于发布恢复验收';definition=@{objects=$refs;resources=$resources}}
        $relationApp=Api POST '/nocode/application/publish' @{id=$relationApp.application.id;expectedRevision=$relationApp.application.revision;reason='关联与汇总演示首次发布'}
        $relationAppId=[string]$relationApp.application.id
    } else { $relationAppId=[string]$relationApps[0].id }
    $result.relationDemo=@{applicationId=$relationAppId;objectId=$projectId;relationId=[string]$project.definition.relations[0].id;detailId=[string]$project.definition.details[0].id;runtimeUrl="http://127.0.0.1:5173/nocode-app/runtime?id=$relationAppId"}
}
if ($UseDatabaseFiles) {
    # 外部对象存储不可达时，显式选择底座自带 DB 存储；复用同一个开发数据库。
    $fileConfigs=@((Api GET '/infra/file-config/page?pageNo=1&pageSize=100' $null).list)
    $fileName='OS 开发库文件存储'
    $fileConfig=@($fileConfigs | Where-Object name -eq $fileName)
    $domain=([Uri]$BaseUrl).GetLeftPart([UriPartial]::Authority)
    if (-not $fileConfig.Count) {
        $fileId=Api POST '/infra/file-config/create' @{name=$fileName;storage=1;config=@{domain=$domain};remark='复用底座 DBFileClient；开发环境外部 MinIO 不可达时使用，可切回原配置。'}
    } else {
        if ($fileConfig.Count -ne 1 -or $fileConfig[0].storage -ne 1 -or $fileConfig[0].config.domain -ne $domain) { throw 'Existing development file configuration differs; do not overwrite it.' }
        $fileId=$fileConfig[0].id
    }
    $master=@($fileConfigs | Where-Object master)
    if ($master.Count -eq 1 -and [string]$master[0].id -ne [string]$fileId) {
        $result.fileStorage=@{configId=[string]$fileId;previousMasterId=[string]$master[0].id}
        $null=Api PUT "/infra/file-config/update-master?id=$fileId" $null
    }
}
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Output -Encoding utf8
$result | ConvertTo-Json -Depth 20
