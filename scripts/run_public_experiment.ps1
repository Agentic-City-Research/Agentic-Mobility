[CmdletBinding()]
param(
    [ValidateSet('hybrid', 'reference', 'fixed-routes')][string]$Mode = 'hybrid',
    [ValidateSet('llm', 'mnl', 'shortest', 'mock')][string]$Policy = 'mnl',
    [ValidatePattern('^(all|[0-9]+)$')][string]$LlmAgents = '0',
    [switch]$Manager,
    [string]$FixedTollPolicy,
    [ValidateRange(0, 2147483647)][int]$FixedTollFreeDays = 20,
    [ValidateRange(1, 2147483647)][int]$ManagerUpdateInterval = 30,
    [int]$ManagerMaxOutputTokens = 8192,
    [ValidateSet('basic', 'controlled')][string]$ManagerProfile = 'basic',
    [double]$ManagerMaxCorridorMinutes = 5,
    [double]$ManagerMaxStepMinutes = 2,
    [int]$ManagerMaxPricedCorridors = 1,
    [double]$ManagerMaxUnavoidableShare = 0.5,
    [int]$ManagerReviewDays = 7,
    [int]$ManagerAdaptationDays = 7,
    [int]$ManagerBreachDays = 3,
    [int]$ManagerCooldownDays = 7,
    [double]$ManagerDelayTolerance = 0.05,
    [double]$ManagerLocalTolerance = 0.10,
    [double]$ManagerNoiseMultiplier = 3,
    [double]$ManagerCompletionDrop = 0.01,
    [ValidateRange(0.0, 1.0)][double]$InformedShare = 0.5,
    [ValidateSet('controlled', 'all')][string]$InformationScope = 'controlled',
    [Nullable[long]]$InformationSeed,
    [ValidateRange(1, 2147483647)][int]$TollHistoryBinSeconds = 3600,
    [int]$Concurrency = 8,
    [int]$RepairAttempts = 3,
    [int]$TimeoutSeconds = 0,
    [int]$LlmStallSeconds = 0,
    [int]$MaxRequests = -1,
    [string]$Model,
    [string]$BaseUrl,
    [ValidateSet('auto', 'omit', 'enabled', 'disabled')][string]$Thinking,
    [Nullable[double]]$Temperature,
    [Nullable[double]]$TopP,
    [Nullable[int]]$GenerationSeed,
    [int]$MaxOutputTokens = 0,
    [int]$ReasonMaxChars = 240,
    [ValidateSet('max_tokens', 'max_completion_tokens')][string]$TokenLimitField,
    [ValidateSet('omit', 'json_object', 'json_schema')][string]$ResponseFormat,
    [string]$GenerationParams,
    [int]$HistoryWindow = 5,
    [ValidateSet('llm')][string]$MemoryMode = 'llm',
    [ValidateRange(1, 500)][int]$MemoryMaxChars = 500,
    [int]$MemoryMaxOutputTokens = 1024,
    [ValidateSet('legacy', 'input')][string]$PersonalProfiles = 'legacy',
    [Nullable[long]]$ProfileSeed,
    [string]$ProfileFile,
    [ValidateSet('original', 'reverse', 'shuffle')][string]$CandidateOrder = 'original',
    [long]$CandidateOrderSeed = 0,
    [long]$Seed = 4711,
    [string]$RunDate,
    [int]$Days = 100,
    [ValidateSet(100, 200)][int]$Demand = 200,
    [string]$Output,
    [ValidateSet('planned-arrival', 'planned-departure')][string]$CohortSelection = 'planned-arrival',
    [ValidateRange(1, 2147483647)][int]$PlannedArrivalBefore = 32400,
    [ValidateRange(0, 86400)][int]$DepartureBeforeSeconds = 5400,
    [ValidateRange(0, 86400)][int]$DepartureAfterSeconds = 1800,
    [ValidateRange(1, 86400)][int]$DepartureStepSeconds = 900,
    [int]$PeakStart = 25200,
    [int]$PeakEnd = 32400,
    [int]$AnalysisStart = 23400,
    [int]$AnalysisEnd = 39600,
    [int]$InnovationStopDay = -1,
    [switch]$DisableNativeTimeMutation,
    [ValidateRange(0, 2147483647)][int]$SamplePersons = 0,
    [ValidateScript({ $_ -gt 0 -and $_ -le 1 })][double]$SampleRate = 1.0,
    [Alias('SampleSeed')][long]$PopulationSeed = 4711,
    [int]$TripOutputInterval = 1,
    [int]$ContextSamples = 20,
    [ValidateRange(1, 2147483647)][int]$CandidateSearchLimit = 10000,
    [ValidateRange(4, 2147483647)][int]$CandidatePoolSize = 16,
    [ValidateScript({ $_ -ge 1 })][double]$CandidateMaxTimeRatio = 2.5,
    [ValidateScript({ $_ -ge 0 -and -not [double]::IsInfinity($_) })][double]$CandidateTimeSlackSeconds = 300,
    [ValidateScript({ $_ -ge 1 })][double]$CandidateMaxDistanceRatio = 2.5,
    [ValidateScript({ $_ -ge 0 -and -not [double]::IsInfinity($_) })][double]$CandidateDistanceSlackMeters = 2000,
    [ValidateScript({ $_ -ge 0 -and $_ -le 1 })][double]$CandidateMaxOverlap = 0.85,
    [string]$CandidateFile,
    [switch]$ValidateOnly,
    [string]$Java = 'java',
    [string]$Heap = '8g'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJar = Join-Path $taskRoot 'matsim-example-project-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run mvn package first to build the jar in the project root.' }
$taskConfig = Join-Path $taskRoot 'scenarios/SiouxFalls-public/config.xml'
if ($Manager -and $FixedTollPolicy) { throw 'Choose either -Manager or -FixedTollPolicy, not both.' }
if ($FixedTollPolicy -and $Mode -ne 'hybrid') { throw '-FixedTollPolicy requires -Mode hybrid.' }
$taskSamplingSummary = $null
if ($PSBoundParameters.ContainsKey('SampleRate')) {
    if ($SamplePersons -ne 0) { throw 'Choose either -SampleRate or -SamplePersons, not both.' }
    $taskSamplingSummary = 'Population sample rate k (flow=k; storage=k^0.75): ' + $SampleRate.ToString([System.Globalization.CultureInfo]::InvariantCulture)
}
$taskArguments = @("-Xmx$Heap", '-cp', $taskJar, 'org.matsim.agentic.MorningExperiment',
    '--config', $taskConfig,
    '--mode', $Mode, '--policy', $Policy, '--llm-agents', $LlmAgents,
    '--concurrency', "$Concurrency", '--repair-attempts', "$RepairAttempts",
    '--history-window', "$HistoryWindow", '--reason-max-chars', "$ReasonMaxChars",
    '--memory-mode', $MemoryMode, '--memory-max-chars', "$MemoryMaxChars", '--memory-max-output-tokens', "$MemoryMaxOutputTokens",
    '--personal-profiles', $PersonalProfiles,
    '--informed-share', $InformedShare.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    '--information-scope', $InformationScope, '--toll-history-bin-seconds', "$TollHistoryBinSeconds",
    '--candidate-order', $CandidateOrder, '--candidate-order-seed', "$CandidateOrderSeed",
    '--seed', "$Seed", '--days', "$Days", '--demand', "$Demand",
    '--cohort-selection', $CohortSelection, '--planned-arrival-before', "$PlannedArrivalBefore",
    '--departure-before-seconds', "$DepartureBeforeSeconds", '--departure-after-seconds', "$DepartureAfterSeconds", '--departure-step-seconds', "$DepartureStepSeconds",
    '--peak-start', "$PeakStart", '--peak-end', "$PeakEnd",
    '--analysis-start', "$AnalysisStart", '--analysis-end', "$AnalysisEnd",
    '--sample-persons', "$SamplePersons", '--trip-output-interval', "$TripOutputInterval", '--context-samples', "$ContextSamples",
    '--candidate-search-limit', "$CandidateSearchLimit",
    '--candidate-pool-size', "$CandidatePoolSize",
    '--candidate-max-time-ratio', $CandidateMaxTimeRatio.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    '--candidate-time-slack-seconds', $CandidateTimeSlackSeconds.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    '--candidate-max-distance-ratio', $CandidateMaxDistanceRatio.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    '--candidate-distance-slack-meters', $CandidateDistanceSlackMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    '--candidate-max-overlap', $CandidateMaxOverlap.ToString([System.Globalization.CultureInfo]::InvariantCulture))
if ($PSBoundParameters.ContainsKey('SampleRate')) { $taskArguments += @('--sample-rate', $SampleRate.ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
$taskArguments += @('--population-seed', "$PopulationSeed")
if ($InnovationStopDay -ge 0) { $taskArguments += @('--innovation-stop-day', "$InnovationStopDay") }
if ($DisableNativeTimeMutation) { $taskArguments += '--no-native-time-mutation' }
if ($Manager) {
    $taskArguments += @('--manager', '--manager-profile', $ManagerProfile, '--manager-update-interval', "$ManagerUpdateInterval", '--manager-max-output-tokens', "$ManagerMaxOutputTokens")
    $taskManagerOptions = [ordered]@{
        'max-corridor-minutes'=$ManagerMaxCorridorMinutes; 'max-step-minutes'=$ManagerMaxStepMinutes;
        'max-priced-corridors'=$ManagerMaxPricedCorridors; 'max-unavoidable-share'=$ManagerMaxUnavoidableShare;
        'review-days'=$ManagerReviewDays; 'adaptation-days'=$ManagerAdaptationDays; 'breach-days'=$ManagerBreachDays;
        'cooldown-days'=$ManagerCooldownDays; 'delay-tolerance'=$ManagerDelayTolerance; 'local-tolerance'=$ManagerLocalTolerance;
        'noise-multiplier'=$ManagerNoiseMultiplier; 'completion-drop'=$ManagerCompletionDrop
    }
    foreach ($taskKey in $taskManagerOptions.Keys) {
        if ($ManagerProfile -ne 'controlled') { continue }
        $taskArguments += @(('--manager-' + $taskKey), ([string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0}', $taskManagerOptions[$taskKey])))
    }
}
if ($FixedTollPolicy) { $taskArguments += @('--fixed-toll-policy', (Resolve-Path -LiteralPath $FixedTollPolicy).Path, '--fixed-toll-free-days', "$FixedTollFreeDays") }
if ($PSBoundParameters.ContainsKey('InformationSeed')) { $taskArguments += @('--information-seed', "$InformationSeed") }
if ($TimeoutSeconds -gt 0) { $taskArguments += @('--timeout-seconds', "$TimeoutSeconds") }
if ($LlmStallSeconds -gt 0) { $taskArguments += @('--llm-stall-seconds', "$LlmStallSeconds") }
if ($MaxRequests -ge 0) { $taskArguments += @('--max-requests', "$MaxRequests") }
if ($Model) { $taskArguments += @('--model', $Model) }
if ($BaseUrl) { $taskArguments += @('--base-url', $BaseUrl) }
if ($Thinking) { $taskArguments += @('--thinking', $Thinking) }
if ($PSBoundParameters.ContainsKey('Temperature')) { $taskArguments += @('--temperature', $Temperature.ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
if ($PSBoundParameters.ContainsKey('TopP')) { $taskArguments += @('--top-p', $TopP.ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
if ($PSBoundParameters.ContainsKey('GenerationSeed')) { $taskArguments += @('--generation-seed', "$GenerationSeed") }
if ($MaxOutputTokens -gt 0) { $taskArguments += @('--max-output-tokens', "$MaxOutputTokens") }
if ($TokenLimitField) { $taskArguments += @('--token-limit-field', $TokenLimitField) }
if ($ResponseFormat) { $taskArguments += @('--response-format', $ResponseFormat) }
if ($GenerationParams) { $taskArguments += @('--generation-params', $GenerationParams) }
if ($Output) { $taskArguments += @('--output', $Output) }
if ($RunDate) { $taskArguments += @('--run-date', $RunDate) }
if ($CandidateFile) { $taskArguments += @('--candidate-file', (Resolve-Path -LiteralPath $CandidateFile).Path) }
if ($ProfileFile) { $taskArguments += @('--profile-file', (Resolve-Path -LiteralPath $ProfileFile).Path) }
if ($PSBoundParameters.ContainsKey('ProfileSeed')) { $taskArguments += @('--profile-seed', "$ProfileSeed") }
if ($ValidateOnly) { $taskArguments += '--validate-only' }
$taskJavaCommand = Get-Command $Java -ErrorAction Stop
$taskRuntimeDir = Join-Path ([IO.Path]::GetTempPath()) ('matsim-public-' + [guid]::NewGuid().ToString('N'))
$taskRuntimeJar = Join-Path $taskRuntimeDir 'experiment.jar'
$taskLogDir = Join-Path $taskRoot 'output/_logs'
[IO.Directory]::CreateDirectory($taskLogDir) | Out-Null
$taskConsoleLog = Join-Path $taskLogDir (('public-{0}-seed{1}-{2}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Seed, [guid]::NewGuid().ToString('N')))
$taskLocationPushed = $false
try {
    [IO.Directory]::CreateDirectory($taskRuntimeDir) | Out-Null
    # A live JVM loads classes lazily. Maven may overwrite the source JAR during
    # a long simulation, so run from a private copy. Hold a read-only share while
    # copying to prevent a concurrent build from changing the bytes mid-copy.
    $taskJarSource = [IO.File]::Open($taskJar, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $taskJarDestination = [IO.File]::Open($taskRuntimeJar, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $taskJarSource.CopyTo($taskJarDestination) }
        finally { $taskJarDestination.Dispose() }
    } finally { $taskJarSource.Dispose() }
    $taskArguments[2] = $taskRuntimeJar
    $taskJarHash = (Get-FileHash -LiteralPath $taskRuntimeJar -Algorithm SHA256).Hash
    $taskLogHeader = @("Source JAR: $taskJar", "Runtime JAR SHA256: $taskJarHash", "Console log: $taskConsoleLog")
    if (-not $Output) { $taskLogHeader += ('Experiment outputs: ' + (Join-Path $taskRoot 'output') + ' (date and parameter names; repeats get -2, -3, ...)') }
    if ($taskSamplingSummary) { $taskLogHeader += $taskSamplingSummary }
    $taskLogHeader |
        Tee-Object -FilePath $taskConsoleLog -ErrorAction Stop | Out-Host
    Push-Location $taskRoot
    $taskLocationPushed = $true
    $taskPreviousErrorPreference = $ErrorActionPreference
    $taskPreviousNativeErrorPreference = $PSNativeCommandUseErrorActionPreference
    try {
        # Windows PowerShell represents redirected native stderr as error
        # records. Capture it without aborting before Java's exit code arrives.
        $ErrorActionPreference = 'Continue'
        $PSNativeCommandUseErrorActionPreference = $false
        & $taskJavaCommand @taskArguments 2>&1 | ForEach-Object { $_.ToString() } |
            Tee-Object -FilePath $taskConsoleLog -Append -ErrorAction Stop
        $taskExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $taskPreviousErrorPreference
        $PSNativeCommandUseErrorActionPreference = $taskPreviousNativeErrorPreference
    }
    if ($taskExitCode -ne 0) { throw "Experiment exited with code $taskExitCode. Full console log: $taskConsoleLog" }
} finally {
    if ($taskLocationPushed) { Pop-Location }
    if (Test-Path -LiteralPath $taskRuntimeJar) { Remove-Item -LiteralPath $taskRuntimeJar }
    if (Test-Path -LiteralPath $taskRuntimeDir) { Remove-Item -LiteralPath $taskRuntimeDir }
}
