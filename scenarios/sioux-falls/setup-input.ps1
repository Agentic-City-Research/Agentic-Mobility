param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDirectory
)

$ErrorActionPreference = "Stop"
$source = [System.IO.Path]::GetFullPath($SourceDirectory)
$scenarioDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$destination = Join-Path $scenarioDirectory "input"
$requiredFiles = @(
    "Siouxfalls_network_PT.xml",
    "Siouxfalls_population.xml.gz",
    "Siouxfalls_facilities.xml.gz",
    "Siouxfalls_transitSchedule.xml",
    "Siouxfalls_vehicles.xml"
)

if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "Scenario source directory not found: $source"
}
foreach ($name in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $name) -PathType Leaf)) {
        throw "Required Sioux Falls file not found: $name"
    }
}

New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach ($name in $requiredFiles) {
    Copy-Item -LiteralPath (Join-Path $source $name) `
        -Destination (Join-Path $destination $name) -Force
}
if (Test-Path -LiteralPath (Join-Path $source "README.txt")) {
    Copy-Item -LiteralPath (Join-Path $source "README.txt") `
        -Destination (Join-Path $destination "README.txt") -Force
}

Write-Host "Sioux Falls input is ready at $destination"
