<#
.SYNOPSIS
    Decode and filter a Minecraft log file.

.DESCRIPTION
    Minecraft writes logs through log4j using the platform charset, which is GBK on a zh-CN
    Windows install. Reading that file with PowerShell's own decoding (or printing it straight to
    the console) turns every Chinese character into mojibake, which makes grepping the log useless.

    This decodes the log to UTF-8 first and writes it to a temporary file, so the result can be
    read or searched normally.

.PARAMETER Path
    The log file, or the game folder (in which case logs\latest.log is used).

.PARAMETER Level
    Keep only these levels, e.g. -Level WARN,ERROR

.PARAMETER Pattern
    Keep only lines matching this regular expression.

.PARAMETER Tail
    Keep only the last N lines.

.PARAMETER Out
    Where to write the UTF-8 copy. Defaults to a file in the temp folder.

.EXAMPLE
    .\tools\log.ps1 -Path C:\MC\o12\.minecraft -Level WARN,ERROR

.EXAMPLE
    .\tools\log.ps1 -Path C:\MC\o12\.minecraft -Pattern 'SimpleLan|serving on'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [string]$Path,

    [ValidateSet('WARN', 'ERROR', 'FATAL')]
    [string[]]$Level,

    [string]$Pattern,

    [ValidateRange(0, [int]::MaxValue)]
    [int]$Tail = 0,

    [string]$Out
)

$ErrorActionPreference = 'Stop'

if (Test-Path -LiteralPath $Path -PathType Container) {
    $Path = Join-Path $Path 'logs\latest.log'
}
if (-not (Test-Path -LiteralPath $Path)) {
    throw "No such log file: $Path"
}

# Read through a shared handle: while the game is running it keeps the log open exclusively, so a
# plain ReadAllBytes fails with a sharing violation.
$stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete)
try {
    $bytes = New-Object byte[] $stream.Length
    $read = 0
    while ($read -lt $bytes.Length) {
        $count = $stream.Read($bytes, $read, $bytes.Length - $read)
        if ($count -le 0) {
            break
        }
        $read += $count
    }
} finally {
    $stream.Dispose()
}

$text = [System.Text.Encoding]::GetEncoding(936).GetString($bytes)
$lines = $text -split "\r?\n"

if ($Level) {
    $levels = ($Level | ForEach-Object { "/$_]" }) -join '|'
    $lines = $lines | Where-Object { $_ -match $levels }
}
if ($Pattern) {
    $lines = $lines | Where-Object { $_ -match $Pattern }
}
if ($Tail -gt 0) {
    $lines = $lines | Select-Object -Last $Tail
}
# A filter that matches nothing yields $null, which WriteAllLines rejects.
$lines = @($lines)

if (-not $Out) {
    $Out = Join-Path ([System.IO.Path]::GetTempPath()) 'minecraft-log-utf8.txt'
}
[System.IO.File]::WriteAllLines($Out, $lines, (New-Object System.Text.UTF8Encoding $false))

Write-Host "$Path -> $Out ($($lines.Count) lines)"