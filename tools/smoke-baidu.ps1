param(
    [string]$Adb = 'adb',
    [string]$Device = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$package = 'com.cloudgallery.app.mock'
function Wait-Gallery([int]$Count, [string]$Status) {
    & $Adb -s $Device shell am force-stop $package | Out-Null
    & $Adb -s $Device shell am start -n "$package/com.cloudgallery.app.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        & $Adb -s $Device shell uiautomator dump /sdcard/baidu-smoke.xml | Out-Null
        $dump = & $Adb -s $Device shell cat /sdcard/baidu-smoke.xml
        if ($LASTEXITCODE -ne 0) { Start-Sleep -Seconds 1; continue }
        [xml]$ui = $dump -join "`n"
        $texts = @($ui.SelectNodes('//node') | ForEach-Object { $_.text })
        if (@($texts | Where-Object { $_ -like "$Count 张照片 *" }).Count -gt 0) {
            $settings = $ui.SelectSingleNode('//node[@text="设置"]')
            $bounds = @([regex]::Matches($settings.bounds, '\d+') | ForEach-Object { [int]$_.Value })
            & $Adb -s $Device shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2))
            & $Adb -s $Device shell uiautomator dump /sdcard/baidu-smoke.xml | Out-Null
            [xml]$settingsUi = (& $Adb -s $Device shell cat /sdcard/baidu-smoke.xml) -join "`n"
            if ($null -ne $settingsUi.SelectSingleNode("//node[@text='$Status']")) { Write-Output "PASS: $Count photos; $Status"; return }
            & $Adb -s $Device shell input keyevent KEYCODE_BACK
        }
        Start-Sleep -Seconds 1
    }
    throw "Expected state not found: $Count photos; $Status. Authorize the mock app and select /Photos first."
}
try {
    Invoke-RestMethod 'http://127.0.0.1:5246/fixture?mode=normal' | Out-Null
    Wait-Gallery 202 '同步完成'
    Invoke-RestMethod 'http://127.0.0.1:5246/fixture?mode=failure' | Out-Null
    Wait-Gallery 202 '暂时无法更新，已保留本地图库'
    Invoke-RestMethod 'http://127.0.0.1:5246/fixture?mode=removed' | Out-Null
    Wait-Gallery 200 '同步完成'
    Invoke-RestMethod 'http://127.0.0.1:5246/fixture?mode=normal' | Out-Null
    Wait-Gallery 202 '同步完成'
    $fixture = Invoke-RestMethod 'http://127.0.0.1:5246/fixture'
    if (@($fixture.counts.lists | Where-Object { $_.dir -eq '/Other' }).Count -gt 0) {
        throw 'Unexpected scans outside /Photos; restart the fixture before the test.'
    }
    Write-Output 'PASS: folder scope; account and folder restored across restarts.'
} finally {
    Invoke-RestMethod 'http://127.0.0.1:5246/fixture?mode=normal' | Out-Null
}


