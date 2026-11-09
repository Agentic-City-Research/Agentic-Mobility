[CmdletBinding()]
param(
    [ValidateSet('Mnl', 'MnlManager', 'NativeManager')]
    [string]$Experiment = 'Mnl',
    [ValidateNotNullOrEmpty()][long[]]$Seeds = @(268, 4711, 9261),
    [ValidateRange(1, 2147483647)][int]$Days = 100,
    [ValidateScript({ $_ -gt 0 -and $_ -le 1 })][double]$SampleRate = 0.2,
    [long]$PopulationSeed = 6021,
    [long]$InformationSeed = 6021,
    [ValidateRange(0.0, 1.0)][double]$InformedShare = 0.5,
    [ValidateRange(1, 2147483647)][int]$ManagerUpdateInterval = 30,
    [ValidateRange(0, 2147483647)][int]$ManagerMaxRequests = 500,
    [string]$ManagerModel,
    [string]$ManagerBaseUrl,
    [ValidateSet('auto', 'omit', 'enabled', 'disabled')][string]$ManagerThinking,
    [ValidateSet('omit', 'json_object', 'json_schema')][string]$ManagerResponseFormat,
    [string]$CandidateFile,
    [string]$MnlParameters,
    [string]$OutputRoot = 'output/pilot',
    [string]$Java = 'java',
    [ValidatePattern('^[1-9][0-9]*[mMgG]$')][string]$Heap = '8g',
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'matsim-example-project-0.0.1-SNAPSHOT.jar'
$app = 'org.matsim.agentic.MorningExperiment'
$config = Join-Path $projectRoot 'scenarios/SiouxFalls-public/config.xml'
if (-not $MnlParameters) { $MnlParameters = Join-Path $projectRoot 'config/baselines/mnl-joint.example.json' }
foreach ($path in @($jar, $config)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
}
if ($Experiment -ne 'NativeManager' -and -not (Test-Path -LiteralPath $MnlParameters -PathType Leaf)) {
    throw "MNL parameter file not found: $MnlParameters"
}
$jar = (Resolve-Path -LiteralPath $jar).Path
$config = (Resolve-Path -LiteralPath $config).Path
if ($CandidateFile) {
    if (-not (Test-Path -LiteralPath $CandidateFile -PathType Leaf)) { throw "Candidate file not found: $CandidateFile" }
    $CandidateFile = (Resolve-Path -LiteralPath $CandidateFile).Path
}
if ($Experiment -ne 'NativeManager') { $MnlParameters = (Resolve-Path -LiteralPath $MnlParameters).Path }
if (@($Seeds | Select-Object -Unique).Count -ne $Seeds.Count) { throw 'Each seed must be unique.' }
if (-not [IO.Path]::IsPathRooted($OutputRoot)) { $OutputRoot = Join-Path $projectRoot $OutputRoot }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$label = switch ($Experiment) { 'Mnl' { 'mnl' } 'MnlManager' { 'mnl-manager' } 'NativeManager' { 'native-manager' } }

# Fail before the first run if any requested destination already exists.
foreach ($seed in $Seeds) {
    $name = "$label-seed-$seed"
    if ($ValidateOnly) { $name += '-validate' }
    $path = Join-Path $OutputRoot $name
    if (Test-Path -LiteralPath $path) { throw "Output already exists: $path. Choose another OutputRoot or seed." }
}

$rate = $SampleRate.ToString([Globalization.CultureInfo]::InvariantCulture)
$share = $InformedShare.ToString([Globalization.CultureInfo]::InvariantCulture)
$common = @(
    '--config', $config, '--mode', 'hybrid', '--policy', 'mnl',
    '--demand', '100', '--sample-rate', $rate, '--days', "$Days",
    '--population-seed', "$PopulationSeed", '--personal-profiles', 'legacy', '--profile-seed', "$PopulationSeed",
    '--information-scope', 'controlled', '--information-seed', "$InformationSeed", '--informed-share', $share,
    '--candidate-order', 'original', '--candidate-order-seed', '0',
    '--departure-before-seconds', '3600', '--departure-after-seconds', '1800', '--departure-step-seconds', '900',
    '--trip-output-interval', '1'
)
if ($CandidateFile) { $common += @('--candidate-file', $CandidateFile) }
if ($Experiment -eq 'NativeManager') { $common += @('--llm-agents', '0') }
else { $common += @('--llm-agents', 'all', '--mnl-parameters', $MnlParameters) }
if ($Experiment -ne 'Mnl') {
    $common += @('--manager', '--manager-profile', 'basic', '--manager-update-interval', "$ManagerUpdateInterval", '--max-requests', "$ManagerMaxRequests")
    if ($ManagerModel) { $common += @('--manager-model', $ManagerModel) }
    if ($ManagerBaseUrl) { $common += @('--manager-base-url', $ManagerBaseUrl) }
    if ($ManagerThinking) { $common += @('--manager-thinking', $ManagerThinking) }
    if ($ManagerResponseFormat) { $common += @('--manager-response-format', $ManagerResponseFormat) }
} else { $common += @('--max-requests', '0') }
if ($ValidateOnly) { $common += '--validate-only' }

Write-Host "Experiment: $Experiment; seeds: $($Seeds -join ', '); days: $Days; sample rate: $rate"
Write-Host "JAR SHA-256: $((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash)"
if ($CandidateFile) { Write-Host "Candidate SHA-256: $((Get-FileHash -LiteralPath $CandidateFile -Algorithm SHA256).Hash)" }
else { Write-Host 'No candidate file specified: each run will generate candidates; compare output candidate hashes for paired experiments.' }
if ($Experiment -ne 'NativeManager') {
    Write-Host "MNL parameters SHA-256: $((Get-FileHash -LiteralPath $MnlParameters -Algorithm SHA256).Hash)"
}

Push-Location $projectRoot
try {
    foreach ($seed in $Seeds) {
        $name = "$label-seed-$seed"
        if ($ValidateOnly) { $name += '-validate' }
        $output = Join-Path $OutputRoot $name
        $runArgs = @('--seed', "$seed")
        if ($Experiment -ne 'NativeManager') { $runArgs += @('--mnl-choice-seed', "$seed") }
        $runArgs += @('--output', $output)
        Write-Host "Starting $Experiment seed $seed -> $output"
        & $Java "-Xmx$Heap" -cp $jar $app @common @runArgs
        if ($LASTEXITCODE -ne 0) { throw "$Experiment run failed for seed $seed (exit code $LASTEXITCODE)." }
        $statusFile = Join-Path $output 'morning/status.json'
        if (-not (Test-Path -LiteralPath $statusFile -PathType Leaf)) { throw "Missing status for seed ${seed}: $statusFile" }
        $status = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($ValidateOnly) {
            if ($status.validated -ne $true -or $status.simulationRun -ne $false) { throw "Validation did not complete for seed $seed." }
        } elseif ($status.complete -ne $true -or $status.daysCompleted -ne $Days -or $status.unexpectedShutdown -eq $true) {
            throw "Incomplete run for seed ${seed}; inspect $statusFile."
        } elseif ($Experiment -eq 'Mnl' -and $status.apiCalls -ne 0) {
            throw "Unexpected model calls in MNL-only run for seed $seed; inspect $statusFile."
        }
        Write-Host "Finished seed $seed."
    }
} finally { Pop-Location }
