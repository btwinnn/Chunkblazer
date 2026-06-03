# ===========================================================================
# drift-check.ps1
#
# Read-only check that the ChunkBlazer copy bundled in the runelite fork
# matches the canonical source in this repo. The fork (C:\runelite) is what
# IntelliJ compiles and runs, but its chunkblazer\ tree is UNTRACKED working
# files synced from here by sync-to-runelite.bat. Edit canonical, forget to
# sync, and you silently test stale code -- this catches that.
#
# Compares by SHA256 content hash, so it is immune to timestamp/size quirks.
# Nothing is written or copied. Exit 0 = in sync, 1 = drift, 2 = error.
# ===========================================================================

$ErrorActionPreference = 'Stop'

$pairs = @(
    @{ Name = 'Java (src\main\java -- tests excluded)';
       Src  = 'C:\Chunkblazer\src\main\java\net\runelite\client\plugins\chunkblazer';
       Dest = 'C:\runelite\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer' },
    @{ Name = 'Resources (incl. gpu\runelite\*.glsl shaders)';
       Src  = 'C:\Chunkblazer\src\main\resources\net\runelite\client\plugins\chunkblazer';
       Dest = 'C:\runelite\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer' }
)

function Get-Manifest($root) {
    $map = @{}
    $base = (Resolve-Path $root).Path.TrimEnd('\')
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        Get-ChildItem -Path $root -Recurse -Force | Where-Object { -not $_.PSIsContainer } | ForEach-Object {
            $rel = $_.FullName.Substring($base.Length + 1)
            $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
            $map[$rel] = [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-','')
        }
    } finally {
        $sha.Dispose()
    }
    return $map
}

$drift = $false
$err   = $false

foreach ($p in $pairs) {
    Write-Host ""
    Write-Host ("=== {0} ===" -f $p.Name)

    if (-not (Test-Path $p.Src))  { Write-Host "  ERROR: canonical path missing: $($p.Src)"; $err = $true; continue }
    if (-not (Test-Path $p.Dest)) { Write-Host "  DRIFT: fork path missing (never synced?): $($p.Dest)"; $drift = $true; continue }

    $src  = Get-Manifest $p.Src
    $dest = Get-Manifest $p.Dest

    $changed   = @()
    $onlyCanon = @()
    $onlyFork  = @()

    foreach ($k in $src.Keys) {
        if ($dest.ContainsKey($k)) { if ($src[$k] -ne $dest[$k]) { $changed += $k } }
        else { $onlyCanon += $k }
    }
    foreach ($k in $dest.Keys) { if (-not $src.ContainsKey($k)) { $onlyFork += $k } }

    if ($changed.Count + $onlyCanon.Count + $onlyFork.Count -eq 0) {
        Write-Host "  in sync ($($src.Count) files)."
    } else {
        $drift = $true
        foreach ($f in $changed)   { Write-Host "  CHANGED         $f" }
        foreach ($f in $onlyCanon) { Write-Host "  ONLY IN CANON   $f   (fork is missing your edit)" }
        foreach ($f in $onlyFork)  { Write-Host "  ONLY IN FORK    $f   (stale / hand-edited in fork)" }
    }
}

Write-Host ""
Write-Host "==========================================================================="
if ($err)   { Write-Host "RESULT: ERROR -- a canonical path is missing; check the paths in this script."; exit 2 }
if ($drift) { Write-Host "RESULT: DRIFT DETECTED -- run sync-to-runelite.bat before building, or you'll test stale code."; exit 1 }
Write-Host "RESULT: IN SYNC -- the fork matches canonical. Safe to build/run."
exit 0
