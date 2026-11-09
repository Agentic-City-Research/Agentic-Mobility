param(
    [ValidateSet('mock','api')][string]$Mode = 'mock',
    [ValidateSet('b1','memory','all')][string]$Experiment = 'all',
    [string]$Model = 'deepseek-flash',
    [string]$BaseUrl = 'https://api.deepseek.com',
    [int]$MaxRequests = 200,
    [string]$Output = '',
    [string]$SourceRun = ''
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $ProjectRoot
if ($Mode -eq 'api' -and [string]::IsNullOrWhiteSpace($env:AGENTIC_API_KEY)) {
    throw 'Set AGENTIC_API_KEY in this PowerShell process before using -Mode api.'
}
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = "experiments/v9-pilot-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
if (Test-Path -LiteralPath $Output) { throw "Output already exists: $Output" }
& .\mvnw.cmd -q -DskipTests compile
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$arguments = @('--mode', $Mode, '--experiment', $Experiment, '--output', $Output,
    '--model', $Model, '--base-url', $BaseUrl, '--max-requests', $MaxRequests.ToString())
if (-not [string]::IsNullOrWhiteSpace($SourceRun)) { $arguments += @('--source-run', $SourceRun) }
Write-Host "Output: $Output  Mode: $Mode  Experiment: $Experiment"
& java --enable-native-access=ALL-UNNAMED -Xmx4g -cp 'target/classes;matsim-example-project-0.0.1-SNAPSHOT.jar' `
    org.matsim.agentic.V9ReasonablenessExperiments @arguments
exit $LASTEXITCODE
