[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputFile,
    [Parameter(Mandatory)][string]$Output,
    [string]$ModelsFile,
    [string]$Model,
    [string]$BaseUrl,
    [ValidateSet('auto', 'omit', 'enabled', 'disabled')][string]$Thinking,
    [string]$Orders = 'original,reverse',
    [long]$OrderSeed = 0,
    [int]$Repetitions = 1,
    [int]$Limit = 100,
    [int]$MaxRequests = 25000,
    [int]$MaxOutputTokens = 0,
    [int]$TimeoutSeconds = 300,
    [switch]$Mock,
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJar = Join-Path $taskRoot 'matsim-example-project-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run mvn package first.' }
$taskArgs = @('-cp', $taskJar, 'org.matsim.agentic.MorningReplay',
    '--input', (Resolve-Path -LiteralPath $InputFile).Path, '--output', [IO.Path]::GetFullPath($Output),
    '--orders', $Orders, '--order-seed', "$OrderSeed", '--repetitions', "$Repetitions", '--limit', "$Limit",
    '--max-requests', "$MaxRequests", '--timeout-seconds', "$TimeoutSeconds")
if ($ModelsFile) { $taskArgs += @('--models', (Resolve-Path -LiteralPath $ModelsFile).Path) }
if ($Model) { $taskArgs += @('--model', $Model) }
if ($BaseUrl) { $taskArgs += @('--base-url', $BaseUrl) }
if ($Thinking) { $taskArgs += @('--thinking', $Thinking) }
if ($MaxOutputTokens -gt 0) { $taskArgs += @('--max-output-tokens', "$MaxOutputTokens") }
if ($Mock) { $taskArgs += '--mock' }
& $Java @taskArgs
if ($LASTEXITCODE -ne 0) { throw "Replay exited with code $LASTEXITCODE." }
