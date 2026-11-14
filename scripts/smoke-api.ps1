param([string]$ApiBase='http://127.0.0.1:8080/api/v1')
$ErrorActionPreference='Stop'
$login=Invoke-RestMethod -Method Post -Uri "$ApiBase/dev/session"
$headers=@{Authorization="Bearer $($login.token)"}
function Invoke-TaskApi([string]$Method,[string]$Path,$Body=$null) {
    $arguments=@{Method=$Method;Uri="$ApiBase$Path";Headers=$headers}
    if($null -ne $Body){$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=$Body | ConvertTo-Json -Depth 30 -Compress}
    Invoke-RestMethod @arguments
}
$session=Invoke-TaskApi POST '/sessions'
$memory=Invoke-TaskApi POST '/memory' @{statement='联调测试：本次训练先求稳';kind='goal';scopeType='session';scopeId=$session.id;requestId=[Guid]::NewGuid().ToString()}
$edited=Invoke-TaskApi PATCH "/memory/$($memory.id)" @{statement='联调测试更正：本次比较两种安排';expectedRevision=$memory.revision}
if($edited.revision -ne 2){throw 'Memory revision did not advance.'}
$forgotten=Invoke-TaskApi DELETE "/memory/$($memory.id)?expectedRevision=$($edited.revision)"
if($forgotten.status -ne 'forgotten'){throw 'Memory deletion was not acknowledged.'}
$unlimited=@{maximum=$null;unrestricted=$true}
$fixtureRules=@{
    id='smoke-fixture';version='1';sourceReference='Developer fixture only, not an official competition'
    baseValues=@{'3S'=4.3;'3T'=4.2};scoreScale=2;roundingMode='HALF_UP';sequenceMultiplier=1
    allApplicableRulesRepresented=$true;unresolvedClauses=@()
    bonus=@{enabled=$true;startsAtSeconds=60;includeBoundary=$false;multiplier=1.1;eligibleTypes=@('COMBINATION');awardOrder='FIRST'}
    limits=@{elements=$unlimited;combinations=$unlimited;sequences=$unlimited;jumpsPerElement=$unlimited;jumpOccurrences=@{};jumpOccurrencesComplete=$true;requiredJumps=@()}
}
$element=@{id='smoke-combination';type='COMBINATION';jumps=@('3S','3T');plannedStartSeconds=90}
$evaluation=Invoke-TaskApi POST '/scoring/evaluate' @{elements=@($element);ruleSet=$fixtureRules}
if([decimal]$evaluation.evaluatedTotal -ne [decimal]9.35){throw 'Fixture BV calculation failed.'}
$plan=Invoke-TaskApi POST '/plans' @{title='开发联调样例（非正式比赛规则）';kind='MAIN';body=@{elements=@($element);ruleSet=$fixtureRules}}
$loaded=Invoke-TaskApi GET "/plans/$($plan.id)"
if($loaded.id -ne $plan.id){throw 'Plan persistence failed.'}
$run=Invoke-TaskApi POST '/recommendations' @{sessionId=$session.id;requestId=[Guid]::NewGuid().ToString();planId=$plan.id;expectedPlanRevision=$plan.revision;prompt='这次先求稳，解释编排取舍';competitionKey='isu-2026-27-singles'}
$catalog=Invoke-TaskApi GET '/knowledge/catalog'
[pscustomobject]@{MemoryCrud='passed';FixtureBV=$evaluation.evaluatedTotal;PlanPersistence='passed';RecommendationStatus=$run.status;CatalogEntries=$catalog.items.Count}
