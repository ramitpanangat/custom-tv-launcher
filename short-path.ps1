# Shared by the scripts next to it. This folder's path is long enough to hit Windows' 260-character
# limit inside the Python and Gradle tools, so they run through a drive letter mapped to it with `subst`.
# The mapping lasts until you sign out or restart; this reuses it or makes a new one.
function Get-ShortRoot {
    $here = (Resolve-Path $PSScriptRoot).Path.TrimEnd('\')
    foreach ($line in (subst)) {
        if ($line -match '^([A-Z]):\\: => (.+)$' -and $Matches[2].TrimEnd('\') -ieq $here) { return "$($Matches[1]):" }
    }
    $used = (Get-PSDrive -PSProvider FileSystem).Name
    $letter = 'T', 'W', 'Y', 'Q', 'K', 'J' | Where-Object { $used -notcontains $_ } | Select-Object -First 1
    if (-not $letter) { throw 'no free drive letter for subst' }
    subst "${letter}:" $here
    if ($LASTEXITCODE -ne 0) { throw "subst ${letter}: failed" }
    return "${letter}:"
}
