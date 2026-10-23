# Build the Windows Phone style launcher in tiles\ and install it on the TV as Home.
# The tile layout you made on the TV is kept across reinstalls.
#
# Turn USB debugging on on the TV first (Settings > Developer options).
# Usage: .\install-tiles.ps1 -TvHost <tv ip>
#        .\install-tiles.ps1 -TvHost <tv ip> -OldHome   # make the previous launcher (com.gathin.tvhome) Home again
#        .\install-tiles.ps1 -TvHost <tv ip> -NoHome    # install without pressing Home (someone is watching)
param(
    [string]$TvHost = $env:TV_HOST,
    [switch]$OldHome,
    [switch]$NoHome
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip>' }
. (Join-Path $PSScriptRoot 'short-path.ps1')
$root = Get-ShortRoot
$env:TV_HOST = $TvHost
$py = "$root\cleanyourtv\tools\venv\Scripts\python.exe"
$tv = "$root\cleanyourtv\tools\tv.py"

if ($OldHome) {
    'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity', 'input keyevent KEYCODE_HOME' |
        & $py -I $tv --batch
    return
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
Push-Location "$root\tiles"
try {
    # Gradle prints notes on stderr, which Windows PowerShell 5.1 would treat as an error.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat assembleRelease -q --console=plain 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'deprecat|Xlint' }
    $built = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
} finally { Pop-Location }
if ($built -ne 0) { throw 'Gradle build failed' }

# A small APK: the chunked push is reliable on this TV, where adb's own push stalls. It checks the md5 itself;
# a warning on stderr (such as a slow cleanup of its temporary files) is not a failure.
$ErrorActionPreference = 'Continue'
& $py -I "$root\cleanyourtv\tools\b64push.py" "$root\tiles\app\build\outputs\apk\release\app-release.apk" /data/local/tmp/tiles.apk 2>&1 |
    ForEach-Object { "$_" }
$pushed = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($pushed -ne 0) { throw 'APK upload failed' }
$out = @(
    'pm install -r /data/local/tmp/tiles.apk',
    'rm -f /data/local/tmp/tiles.apk',
    'pm grant com.tvtiles.home android.permission.ACCESS_FINE_LOCATION; echo location-granted',
    'cmd package set-home-activity --user 0 com.tvtiles.home/.MainActivity',
    $(if ($NoHome) { 'echo Home not pressed' } else { 'input keyevent KEYCODE_HOME' }),
    'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
) | & $py -I $tv --batch
$out
if ("$out" -notmatch 'com.tvtiles.home/') { throw 'Home is not the tiles launcher; see the output above' }
