# Reusable download measurement harness.
#
# Searches for a fixed query, downloads ONE row, and prints the phase lines. Written as a
# script because the A/B testing for the client-chain regression needs the same sequence
# several times, and repeating it inline is both slow to write and easy to get subtly wrong
# (a stale uiautomator dump silently reads the previous screen).
param(
    [string]$Query = "Keane",
    [int]$WaitSeconds = 100,
    [switch]$NoClearLog
)

$adb = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe'

& $adb shell am force-stop com.cartify.app | Out-Null
Start-Sleep -Seconds 2
if (-not $NoClearLog) { & $adb logcat -c }
& $adb shell am start -n com.cartify.app/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9

function TapByResource([string]$rid) {
    & $adb shell uiautomator dump /sdcard/_dl.xml 2>&1 | Out-Null
    $x = & $adb shell cat /sdcard/_dl.xml
    $m = [regex]::Match($x, '<node[^>]*resource-id="com\.cartify\.app:id/' + $rid + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) {
        $m = [regex]::Match($x, '<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="com\.cartify\.app:id/' + $rid + '"')
    }
    if (-not $m.Success) { return $false }
    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $cx $cy
    return $true
}

# Focus the search box, replace any text, and submit.
if (-not (TapByResource 'searchInput')) { Write-Output "FAIL: no searchInput"; exit 1 }
Start-Sleep -Milliseconds 700
& $adb shell input keyevent 123
1..24 | ForEach-Object { & $adb shell input keyevent 67 }
& $adb shell input text $Query
& $adb shell input keyevent 66
Start-Sleep -Seconds 20

# Download the first row's button.
& $adb shell uiautomator dump /sdcard/_dl2.xml 2>&1 | Out-Null
$x2 = & $adb shell cat /sdcard/_dl2.xml
$d = [regex]::Match($x2, '<node[^>]*content-desc="Download"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $d.Success) {
    $d = [regex]::Match($x2, '<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="Download"')
}
if (-not $d.Success) { Write-Output "FAIL: no Download button for '$Query'"; exit 1 }
$dx = [int](([int]$d.Groups[1].Value + [int]$d.Groups[3].Value) / 2)
$dy = [int](([int]$d.Groups[2].Value + [int]$d.Groups[4].Value) / 2)
& $adb shell input tap $dx $dy

Start-Sleep -Seconds $WaitSeconds

Write-Output "=== $Query ==="
& $adb logcat -d -s Cartify 2>$null |
    Select-String 'phase |yt-dlp:|queue done' |
    ForEach-Object { $_.Line -replace '^.*Cartify\s*:\s*', '' }
$fatal = (& $adb logcat -d 2>$null | Select-String 'FATAL EXCEPTION').Count
Write-Output "FATAL=$fatal"
