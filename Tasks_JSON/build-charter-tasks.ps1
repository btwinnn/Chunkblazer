# build-charter-tasks.ps1
# ---------------------------------------------------------------------------
# Charter chunks are authored as one file per port under Charter_Tasks_Folder
# (bare chunk objects). Neither the plugin loader (expects a single wrapped
# {"Charter_Tasks":[...]} file) nor the Go server catalog (same shape) can read
# those bare files directly, so this script aggregates the folder into the one
# loadable Charter_Tasks.json and drops a copy everywhere it needs to live:
#   - plugin resources  (bundled into the RuneLite plugin)
#   - server data       (embedded into the Go catalog so charter tasks score)
#
# It also normalizes on the way out, without touching the source files:
#   - ensures "Friendly_Name" (capital F) — the key the Go catalog reads for
#     region names; the per-port files use lowercase "friendly_name".
#   - tags every chunk "chunk_type": "CHARTER" so future features can find them.
#
# Re-run after editing any per-port file. Called from sync-to-runelite.bat.
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
$root      = Split-Path -Parent $MyInvocation.MyCommand.Path          # ...\Tasks_JSON
$srcFolder = Join-Path $root 'Charter_Tasks_Folder'
$outName   = 'Charter_Tasks.json'

$pluginDest = 'C:\Chunkblazer\src\main\resources\net\runelite\client\plugins\chunkblazer\' + $outName
$serverDest = 'C:\Chunkblazer-Server\internal\tasks\data\' + $outName

$chunks = @()
foreach ($f in Get-ChildItem $srcFolder -Filter *.json | Sort-Object Name) {
    $c = Get-Content $f.FullName -Raw | ConvertFrom-Json

    # Server reads Friendly_Name (capital). Mirror from friendly_name if needed.
    $name = if ($c.PSObject.Properties.Name -contains 'Friendly_Name' -and $c.Friendly_Name) { $c.Friendly_Name }
            elseif ($c.PSObject.Properties.Name -contains 'friendly_name' -and $c.friendly_name) { $c.friendly_name }
            else { $null }
    if ($name) { $c | Add-Member -NotePropertyName 'Friendly_Name' -NotePropertyValue $name -Force }

    # Tag as a charter chunk so future code can enumerate them generically.
    $c | Add-Member -NotePropertyName 'chunk_type' -NotePropertyValue 'CHARTER' -Force

    $chunks += $c
}

$wrapper = [PSCustomObject]@{ Charter_Tasks = @($chunks) }
$json    = $wrapper | ConvertTo-Json -Depth 40

$enc = New-Object System.Text.UTF8Encoding($false)   # UTF-8, NO BOM (Gson/Go json choke on a BOM)
[System.IO.File]::WriteAllText($pluginDest, $json, $enc)
Write-Output ("Wrote {0} chunk(s) -> {1}" -f $chunks.Count, $pluginDest)
[System.IO.File]::WriteAllText($serverDest, $json, $enc)
Write-Output ("Wrote {0} chunk(s) -> {1}" -f $chunks.Count, $serverDest)

# NOTE: charter ports are made free + auto-open by SEEDING them into the
# player's unlocked set at startup (ChunkBlazerPlugin.ensureCharterChunksUnlocked,
# keyed off "chunk_type":"CHARTER"), NOT via Free_Chunks.json. Seeding is what
# also gets their tasks rolled — a free-by-rule region never has tasks rolled.
# So this script intentionally does not touch Free_Chunks.json.
