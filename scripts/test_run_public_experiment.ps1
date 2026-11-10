[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskTestRoot = Join-Path (Split-Path -Parent $PSScriptRoot) ('tmp/run-script-tests-' + [guid]::NewGuid().ToString('N'))
$taskScripts = Join-Path $taskTestRoot 'scripts'
[IO.Directory]::CreateDirectory($taskScripts) | Out-Null
$taskRunner = Join-Path $taskScripts 'run_public_experiment.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run_public_experiment.ps1') -Destination $taskRunner
$taskSourceJar = Join-Path $taskTestRoot 'matsim-example-project-0.0.1-SNAPSHOT.jar'
$taskFakeJava = Join-Path $taskTestRoot 'fake-java.ps1'
@'
param([Parameter(ValueFromRemainingArguments = $true)][object[]]$Arguments)
$runtimeJar = [string]$Arguments[2]
$sourceJar = Join-Path $PSScriptRoot 'matsim-example-project-0.0.1-SNAPSHOT.jar'
if ($runtimeJar -eq $sourceJar) { throw 'Java still uses the mutable build JAR' }
if ([IO.File]::ReadAllText($runtimeJar) -ne 'original-build') { throw 'Unexpected runtime bytes' }
[IO.File]::WriteAllText($sourceJar, 'rebuilt-while-running')
if ([IO.File]::ReadAllText($runtimeJar) -ne 'original-build') { throw 'Runtime changed after build' }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'runtime-path.txt'), $runtimeJar)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'arguments.json'), (ConvertTo-Json -InputObject @($Arguments)))
Write-Output 'stdout-marker'
Write-Error 'stderr-marker' -ErrorAction Continue
$global:LASTEXITCODE = [int][IO.File]::ReadAllText((Join-Path $PSScriptRoot 'exit-code.txt'))
'@ | Set-Content -LiteralPath $taskFakeJava
foreach ($taskExpectedCode in @(0, 7)) {
    [IO.File]::WriteAllText($taskSourceJar, 'original-build')
    [IO.File]::WriteAllText((Join-Path $taskTestRoot 'exit-code.txt'), [string]$taskExpectedCode)
    $taskOriginalLocation = (Get-Location).Path
    $taskFailure = $null
    try { & $taskRunner -Java $taskFakeJava -Days 1 -Seed $taskExpectedCode | Out-Null }
    catch { $taskFailure = $_ }
    if ($taskExpectedCode -eq 0 -and $null -ne $taskFailure) { throw $taskFailure }
    if ($taskExpectedCode -ne 0 -and ($null -eq $taskFailure -or $taskFailure.ToString() -notmatch "code $taskExpectedCode\. Full console log:")) {
        throw 'Failure exit code or log path was not preserved'
    }
    if ((Get-Location).Path -ne $taskOriginalLocation) { throw 'Working directory was not restored' }
    if ($ErrorActionPreference -ne 'Stop') { throw 'Error preference was not restored' }
    $taskRuntimePath = [IO.File]::ReadAllText((Join-Path $taskTestRoot 'runtime-path.txt'))
    if (Test-Path -LiteralPath (Split-Path -Parent $taskRuntimePath)) { throw 'Private runtime was not cleaned up' }
    $taskLogs = @(Get-ChildItem -LiteralPath (Join-Path $taskTestRoot 'output/_logs') -Filter "*-seed$taskExpectedCode-*.log")
    if ($taskLogs.Count -ne 1) { throw 'Expected one complete console log per invocation' }
    $taskLogText = Get-Content -LiteralPath $taskLogs[0].FullName -Raw
    if ($taskLogText -notmatch 'stdout-marker' -or $taskLogText -notmatch 'stderr-marker' -or $taskLogText -notmatch 'Runtime JAR SHA256:') {
        throw 'Console log lost stdout, stderr or build identity'
    }
}

# One sampling argument reaches Java unchanged; invalid combinations stop first.
foreach ($taskSelection in @('planned-arrival', 'planned-departure')) {
    [IO.File]::WriteAllText($taskSourceJar, 'original-build')
    [IO.File]::WriteAllText((Join-Path $taskTestRoot 'exit-code.txt'), '0')
    & $taskRunner -Java $taskFakeJava -Days 1 -CohortSelection $taskSelection -PlannedArrivalBefore 30000 | Out-Null
    $taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
    if ($taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--cohort-selection') + 1] -ne $taskSelection -or
        $taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--planned-arrival-before') + 1] -ne '30000') {
        throw 'Cohort schedule selection was not forwarded'
    }
}
foreach ($taskRate in @(0.1, 0.25, 1.0)) {
    [IO.File]::WriteAllText($taskSourceJar, 'original-build')
    [IO.File]::WriteAllText((Join-Path $taskTestRoot 'exit-code.txt'), '0')
    & $taskRunner -Java $taskFakeJava -Days 1 -Demand 100 -SampleRate $taskRate -SampleSeed 20261008 -Seed 9261 | Out-Null
    $taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
    $taskSampleIndex = [Array]::IndexOf($taskCapturedArguments, '--sample-rate')
    if ($taskSampleIndex -lt 0 -or $taskCapturedArguments[$taskSampleIndex + 1] -ne $taskRate.ToString([System.Globalization.CultureInfo]::InvariantCulture)) {
        throw 'SampleRate was not forwarded with an invariant decimal separator'
    }
    $taskSampleSeedIndex = [Array]::IndexOf($taskCapturedArguments, '--population-seed')
    if ($taskSampleSeedIndex -lt 0 -or $taskCapturedArguments[$taskSampleSeedIndex + 1] -ne '20261008' -or
        $taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--seed') + 1] -ne '9261') {
        throw 'Independent sampling and simulation seeds were not forwarded'
    }
    if ($taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--sample-persons') + 1] -ne '0') {
        throw 'SampleRate also applied diagnostic population sampling'
    }
}
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
[IO.File]::WriteAllText((Join-Path $taskTestRoot 'exit-code.txt'), '0')
& $taskRunner -Java $taskFakeJava -Days 1 -Manager | Out-Null
$taskBasicArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
foreach ($taskPair in @(@('--manager-profile','basic'),@('--history-window','5'),@('--traveler-reflection-interval','5'),@('--reason-max-chars','120'),@('--max-output-tokens','1024'),@('--manager-memory-max-output-tokens','2048'),@('--departure-before-seconds','3600'),@('--departure-after-seconds','1800'),@('--departure-step-seconds','900'))) {
    $taskIndex = [Array]::IndexOf($taskBasicArguments, $taskPair[0])
    if ($taskIndex -lt 0 -or $taskBasicArguments[$taskIndex + 1] -ne $taskPair[1]) { throw ('Basic protocol was not forwarded: ' + $taskPair[0]) }
}
if ($taskBasicArguments -contains '--manager-max-step-minutes' -or $taskBasicArguments -contains '--manager-completion-drop') {
    throw 'Basic silently received legacy strategy controls'
}
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
& $taskRunner -Java $taskFakeJava -Days 1 -Manager -ManagerProfile controlled -ManagerUpdateInterval 7 -ManagerMemoryMaxOutputTokens 3072 -ManagerMaxCorridorMinutes 4.5 -ManagerCompletionDrop 0.02 | Out-Null
$taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
if ($taskCapturedArguments -notcontains '--manager' -or $taskCapturedArguments -contains '--manager-feedback-mode') {
    throw 'Manager must use the combined information without a feedback-mode selector'
}
if ($taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--manager-update-interval') + 1] -ne '7') {
    throw 'ManagerUpdateInterval was not forwarded'
}
foreach ($taskPair in @(@('--manager-max-corridor-minutes','4.5'),@('--manager-memory-max-output-tokens','3072'),@('--manager-max-step-minutes','2'),@('--manager-completion-drop','0.02'),@('--manager-review-days','7'))) {
    $taskIndex = [Array]::IndexOf($taskCapturedArguments, $taskPair[0])
    if ($taskIndex -lt 0 -or $taskCapturedArguments[$taskIndex + 1] -ne $taskPair[1]) { throw ('Manager control was not forwarded: ' + $taskPair[0]) }
}
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
& $taskRunner -Java $taskFakeJava -Days 1 -Policy llm -LlmAgents 1 -TravelerReflectionInterval 1 -TravelerSemanticReview -SemanticReviewMaxOutputTokens 1536 | Out-Null
$taskReviewArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
if ($taskReviewArguments -notcontains '--traveler-semantic-review' -or
    $taskReviewArguments[[Array]::IndexOf($taskReviewArguments, '--semantic-review-max-output-tokens') + 1] -ne '1536' -or
    $taskReviewArguments[[Array]::IndexOf($taskReviewArguments, '--history-window') + 1] -ne '1') {
    throw 'Optional traveler semantic review was not forwarded'
}
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
& $taskRunner -Java $taskFakeJava -Days 1 -TravelerReflectionInterval 1 -HistoryWindow 5 | Out-Null
$taskCustomHistory = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
if ($taskCustomHistory[[Array]::IndexOf($taskCustomHistory, '--history-window') + 1] -ne '5') { throw 'Explicit history override not forwarded' }
$taskFixedFile = Join-Path $taskTestRoot 'fixed.json'
[IO.File]::WriteAllText($taskFixedFile, '{"rules":[]}')
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
& $taskRunner -Java $taskFakeJava -Days 22 -FixedTollPolicy $taskFixedFile -FixedTollFreeDays 0 | Out-Null
$taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
if ($taskCapturedArguments -contains '--manager' -or $taskCapturedArguments -notcontains '--fixed-toll-policy') {
    throw 'Fixed pricing must not activate a manager'
}
if ($taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--fixed-toll-policy') + 1] -ne $taskFixedFile -or
    $taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--fixed-toll-free-days') + 1] -ne '0') {
    throw 'Fixed policy path/warmup were not forwarded'
}
if ($taskCapturedArguments[[Array]::IndexOf($taskCapturedArguments, '--memory-mode') + 1] -ne 'llm') {
    throw 'The runner must forward llm as its only memory mode'
}
# Candidate settings must reach the runner together, including the absolute slacks.
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
& $taskRunner -Java $taskFakeJava -Days 1 -CandidatePoolSize 20 -CandidateSearchLimit 5000 `
    -CandidateMaxTimeRatio 2 -CandidateTimeSlackSeconds 180 `
    -CandidateMaxDistanceRatio 2.2 -CandidateDistanceSlackMeters 1000 -CandidateMaxOverlap 0.8 | Out-Null
$taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
$taskCandidateExpected = @{
    '--candidate-pool-size' = '20'; '--candidate-search-limit' = '5000'
    '--candidate-max-time-ratio' = '2'; '--candidate-time-slack-seconds' = '180'
    '--candidate-max-distance-ratio' = '2.2'; '--candidate-distance-slack-meters' = '1000'
    '--candidate-max-overlap' = '0.8'
}
foreach ($taskEntry in $taskCandidateExpected.GetEnumerator()) {
    $taskIndex = [Array]::IndexOf($taskCapturedArguments, $taskEntry.Key)
    if ($taskIndex -lt 0 -or $taskCapturedArguments[$taskIndex + 1] -ne $taskEntry.Value) {
        throw "Candidate option not forwarded: $($taskEntry.Key)"
    }
}
foreach ($taskOptions in @(
    @{ MemoryMode = 'none' },
    @{ HistoryWindow = 0 }, @{ HistoryWindow = 6 }, @{ TravelerReflectionInterval = 2 },
    @{ CohortSelection = 'actual-arrival' }, @{ PlannedArrivalBefore = 0 },
    @{ SampleRate = 0 }, @{ SampleRate = -0.1 }, @{ SampleRate = 1.1 },
    @{ SampleRate = [double]::NaN }, @{ SampleRate = [double]::PositiveInfinity },
    @{ SampleRate = 0.1; SamplePersons = 2 }, @{ Manager = $true; ManagerUpdateInterval = 0 },
    @{ Manager = $true; FixedTollPolicy = $taskFixedFile }, @{ FixedTollPolicy = $taskFixedFile; FixedTollFreeDays = -1 },
    @{ Mode = 'reference' }, @{ Mode = 'fixed-routes' }, @{ FixedTollPolicy = (Join-Path $taskTestRoot 'missing.json') }
    @{ CandidatePoolSize = 3 }, @{ CandidateSearchLimit = 0 },
    @{ CandidateMaxTimeRatio = [double]::NaN }, @{ CandidateMaxDistanceRatio = 0.9 },
    @{ CandidateTimeSlackSeconds = -1 }, @{ CandidateTimeSlackSeconds = [double]::PositiveInfinity },
    @{ CandidateDistanceSlackMeters = [double]::NaN }, @{ CandidateMaxOverlap = 1.01 }
)) {
    $taskCapturedPath = Join-Path $taskTestRoot 'arguments.json'
    Remove-Item -LiteralPath $taskCapturedPath -ErrorAction SilentlyContinue
    $taskRejected = $false
    try { & $taskRunner -Java $taskFakeJava -Days 1 @taskOptions | Out-Null }
    catch { $taskRejected = $true }
    if (-not $taskRejected -or (Test-Path -LiteralPath $taskCapturedPath)) { throw 'Invalid sample options reached Java' }
}
foreach ($taskDisableMutation in @($false, $true)) {
    [IO.File]::WriteAllText($taskSourceJar, 'original-build')
    [IO.File]::WriteAllText((Join-Path $taskTestRoot 'exit-code.txt'), '0')
    & $taskRunner -Java $taskFakeJava -Days 1 -DisableNativeTimeMutation:$taskDisableMutation | Out-Null
    $taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
    if (($taskCapturedArguments -contains '--no-native-time-mutation') -ne $taskDisableMutation) {
        throw 'Native time-mutation switch was not forwarded correctly'
    }
}
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
[IO.File]::WriteAllText((Join-Path $taskTestRoot 'exit-code.txt'), '0')
& $taskRunner -Java $taskFakeJava -Days 1 -Policy llm -LlmAgents 1 -Manager `
    -Model qwen-fixture -BaseUrl 'http://qwen.internal:8002/v1' -ResponseFormat json_schema `
    -GenerationParams '{"chat_template_kwargs":{"enable_thinking":false}}' `
    -ManagerModel deepseek-fixture -ManagerBaseUrl 'https://manager.example/v1' `
    -ManagerThinking disabled -ManagerResponseFormat json_object -ManagerGenerationParams '{}' `
    -ManagerTokenLimitField max_tokens -ManagerTimeoutSeconds 180 -ManagerTemperature 0.2 `
    -ManagerTopP 0.9 -ManagerGenerationSeed 9261 | Out-Null
$taskRoleArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
foreach ($taskPair in @(
    @('--model','qwen-fixture'), @('--base-url','http://qwen.internal:8002/v1'), @('--response-format','json_schema'),
    @('--generation-params','{"chat_template_kwargs":{"enable_thinking":false}}'),
    @('--manager-model','deepseek-fixture'), @('--manager-base-url','https://manager.example/v1'),
    @('--manager-thinking','disabled'), @('--manager-response-format','json_object'), @('--manager-generation-params','{}'),
    @('--manager-token-limit-field','max_tokens'), @('--manager-timeout-seconds','180'),
    @('--manager-temperature','0.2'), @('--manager-top-p','0.9'), @('--manager-generation-seed','9261')
)) {
    $taskIndex = [Array]::IndexOf($taskRoleArguments, $taskPair[0])
    if ($taskIndex -lt 0 -or $taskRoleArguments[$taskIndex + 1] -ne $taskPair[1]) {
        throw ('Independent role model option was not forwarded: ' + $taskPair[0])
    }
}
$taskMnlParameters = Join-Path $taskTestRoot 'mnl-parameters.json'
[IO.File]::WriteAllText($taskMnlParameters, '{}')
[IO.File]::WriteAllText($taskSourceJar, 'original-build')
& $taskRunner -Java $taskFakeJava -Days 1 -Policy mnl -LlmAgents all -MnlParameters $taskMnlParameters -MnlChoiceSeed 137 | Out-Null
$taskCapturedArguments = Get-Content -LiteralPath (Join-Path $taskTestRoot 'arguments.json') -Raw | ConvertFrom-Json
foreach ($taskPair in @(@('--mnl-parameters',$taskMnlParameters),@('--mnl-choice-seed','137'))) {
    $taskIndex = [Array]::IndexOf($taskCapturedArguments, $taskPair[0])
    if ($taskIndex -lt 0 -or $taskCapturedArguments[$taskIndex + 1] -ne $taskPair[1]) { throw ('MNL option was not forwarded: ' + $taskPair[0]) }
}
Write-Output "PASS: private JAR isolation, console capture, exit codes and cleanup; role model, MNL, sampling, manager, fixed-pricing and candidate forwarding. Fixtures: $taskTestRoot"
