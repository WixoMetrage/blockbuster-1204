# Recupere les logs utiles de l'instance de test dans build/diag/<horodatage>/
# pour analyse : latest.log, dernier crash-report, video.log (+ dernier log
# d'encodeur par prise si video.encoder_log est actif).
#
# Usage : powershell -NoProfile -ExecutionPolicy Bypass -File tools/collect-logs.ps1 [-Instance <dossier>]
# Par defaut, l'instance est lue dans local.properties (instance.dir).

param([string]$Instance)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $Instance) {
    $props = Join-Path $root 'local.properties'
    if (Test-Path $props) {
        $line = Get-Content $props | Where-Object { $_ -match '^\s*instance\.dir\s*=' } | Select-Object -First 1
        if ($line) { $Instance = ($line -split '=', 2)[1].Trim() }
    }
}

if (-not $Instance) { throw "instance.dir introuvable : passe -Instance ou renseigne local.properties" }

$game = @('minecraft', '.minecraft') | ForEach-Object { Join-Path $Instance $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $game) { throw "Aucun dossier minecraft/ dans $Instance" }

$dest = Join-Path $root ("build\diag\" + (Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'))
New-Item -ItemType Directory -Force $dest | Out-Null

function Grab([System.IO.FileInfo]$file, [string]$label) {
    if ($null -eq $file) { Write-Output ("{0,-14} : absent" -f $label); return }
    Copy-Item $file.FullName $dest
    Write-Output ("{0,-14} : {1} ({2:N0} Ko, {3:yyyy-MM-dd HH:mm:ss})" -f $label, $file.Name, ($file.Length / 1KB), $file.LastWriteTime)
}

function Newest([string]$dir, [string]$filter) {
    if (-not (Test-Path $dir)) { return $null }
    Get-ChildItem $dir -Filter $filter -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

# Dossier des videos : video.export_path s'il est renseigne, sinon config/blockbuster/movies.
$movies = Join-Path $game 'config\blockbuster\movies'
$configJson = Join-Path $game 'config\blockbuster\config.json'
if (Test-Path $configJson) {
    $m = [regex]::Match((Get-Content $configJson -Raw), '"export_path"\s*:\s*"((?:[^"\\]|\\.)*)"')
    if ($m.Success -and $m.Groups[1].Value.Trim()) { $movies = $m.Groups[1].Value.Replace('\\', '\').Trim() }
}

Write-Output "Instance : $game"
Grab (Get-Item (Join-Path $game 'logs\latest.log') -ErrorAction SilentlyContinue) 'latest.log'
Grab (Newest (Join-Path $game 'crash-reports') '*.txt') 'crash-report'
Grab (Get-Item (Join-Path $movies 'video.log') -ErrorAction SilentlyContinue) 'video.log'
$takeLog = $null
if (Test-Path $movies) {
    $takeLog = Get-ChildItem $movies -Filter '*.log' -File | Where-Object { $_.Name -ne 'video.log' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
Grab $takeLog 'log de prise'
Write-Output "Copie dans : $dest"
