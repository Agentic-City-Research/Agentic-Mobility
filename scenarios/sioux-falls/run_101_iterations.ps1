param(
    [ValidateRange(0, 84110)]
    [int]$AgentCount = 0,
    [ValidateRange(0.000001, 1.0)]
    [double]$AgentShare = 0.01,
    [ValidateRange(1, 64)]
    [int]$MaxConcurrency = 5,
    [string]$Model = "deepseek-chat",
    [string]$OutputDirectory = "output\sioux-falls-101",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$scenarioDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $scenarioDirectory "..\..")
)
$jarPath = Join-Path $repoDirectory "target\agentic-matsim-0.2.0-SNAPSHOT.jar"
$configPath = Join-Path $scenarioDirectory "config.xml"
$resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
}
else {
    Join-Path $repoDirectory $OutputDirectory
}

if ([string]::IsNullOrWhiteSpace($env:AGENTIC_API_KEY)) {
    throw "AGENTIC_API_KEY is not set. Use a fresh PowerShell session and never save the key in this repository."
}
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "JAR not found. Run .\mvnw.cmd clean package from $repoDirectory first."
}
if ((Test-Path -LiteralPath $resolvedOutput) -and -not $Force) {
    throw "Output exists: $resolvedOutput. Choose another directory or pass -Force."
}

$env:AGENTIC_PROVIDER = "openai-compatible"
$env:AGENTIC_MODEL = $Model
if ($AgentCount -gt 0) {
    $resolvedAgentCount = $AgentCount
    $env:LLM_AGENT_COUNT = [string]$AgentCount
    Remove-Item Env:LLM_AGENT_SHARE -ErrorAction SilentlyContinue
}
else {
    $resolvedAgentCount = [int][Math]::Round(84110 * $AgentShare)
    Remove-Item Env:LLM_AGENT_COUNT -ErrorAction SilentlyContinue
    $env:LLM_AGENT_SHARE = $AgentShare.ToString(
        [System.Globalization.CultureInfo]::InvariantCulture
    )
}
$env:LLM_MAX_CONCURRENCY = [string]$MaxConcurrency

Write-Host "Starting iterations 0..100 with $resolvedAgentCount controlled agents."
Write-Host "Expected provider calls: $($resolvedAgentCount * 101)."
Push-Location $repoDirectory
try {
    & java -jar $jarPath --config $configPath --output $resolvedOutput
    if ($LASTEXITCODE -ne 0) {
        throw "MATSim exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
