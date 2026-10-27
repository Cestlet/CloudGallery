param(
    [string]$Adb = 'adb',
    [string]$Package = 'com.cloudgallery.app.mock',
    [string]$Device = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$shots = Join-Path $PSScriptRoot '../app/build/tmp/ui-v04'
New-Item -ItemType Directory -Force $shots | Out-Null
function Read-Ui {
    & $Adb -s $Device shell uiautomator dump /sdcard/gallery-ui-test.xml | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'UI dump failed' }
    [xml]$dump = (& $Adb -s $Device shell cat /sdcard/gallery-ui-test.xml) -join "`n"
    return $dump
}
function Tap-Node($Node) {
    if ($null -eq $Node) { throw 'UI target not found' }
    $bounds = @([regex]::Matches($Node.bounds, '\d+') | ForEach-Object { [int]$_.Value })
    & $Adb -s $Device shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2))
}
function Tap-Text([string]$Text) { Tap-Node ((Read-Ui).SelectSingleNode("//node[@text='$Text']")) }
function Tap-Description([string]$Description) { Tap-Node ((Read-Ui).SelectSingleNode("//node[@content-desc='$Description']")) }
function Assert-Node([string]$Query, [string]$Label) {
    if ($null -eq (Read-Ui).SelectSingleNode($Query)) { throw "FAIL: $Label" }
    Write-Output "PASS: $Label"
}
function Shot([string]$Name) {
    $null = Read-Ui
    & $Adb -s $Device shell screencap -p /sdcard/gallery-ui-test.png
    & $Adb -s $Device pull /sdcard/gallery-ui-test.png (Join-Path $shots "$Name.png") | Out-Null
}
& $Adb -s $Device shell input keyevent KEYCODE_WAKEUP
& $Adb -s $Device shell wm dismiss-keyguard
& $Adb -s $Device shell am force-stop $Package
& $Adb -s $Device shell am start -n "$Package/com.cloudgallery.app.MainActivity" | Out-Null
Start-Sleep -Seconds 3
Tap-Description '网格'
Assert-Node '//node[@content-desc="网格" and @checked="true"]' 'grid selection'
Shot 'grid'
$tile = (Read-Ui).SelectSingleNode('//node[contains(@content-desc,"，")]')
Tap-Node $tile
Assert-Node '//node[@content-desc="返回图库"]' 'open original viewer'
$ui = Read-Ui
if ($null -ne $ui.SelectSingleNode('//node[@text="重试"]')) { throw 'Viewer image failed' }
Assert-Node '//node[starts-with(@text,"1 / ")]' 'viewer initial position'
$display = & $Adb -s $Device shell wm size
$size = [regex]::Match(($display -join ''), '(\d+)x(\d+)')
$w = [int]$size.Groups[1].Value; $h = [int]$size.Groups[2].Value
& $Adb -s $Device shell input swipe ([int]($w*.85)) ([int]($h*.5)) ([int]($w*.15)) ([int]($h*.5)) 350
Assert-Node '//node[starts-with(@text,"2 / ")]' 'viewer swipe'
Shot 'viewer'
Tap-Description '返回图库'
Tap-Description '瀑布流'
Assert-Node '//node[@content-desc="瀑布流" and @checked="true"]' 'waterfall selection'
Shot 'waterfall'
Tap-Description '列表'
Assert-Node '//node[@content-desc="列表" and @checked="true"]' 'list selection'
Shot 'list'
& $Adb -s $Device shell am force-stop $Package
& $Adb -s $Device shell am start -n "$Package/com.cloudgallery.app.MainActivity" | Out-Null
Start-Sleep -Seconds 2
Assert-Node '//node[@content-desc="列表" and @checked="true"]' 'layout survives restart'
Tap-Text '相册'
Shot 'albums'
Tap-Node ((Read-Ui).SelectSingleNode('//node[contains(@content-desc,"封面")]'))
Assert-Node '//node[@content-desc="返回相册"]' 'album detail'
Tap-Description '返回相册'
Tap-Text '设置'
Shot 'settings'
Tap-Text '图库文件夹'
Assert-Node '//node[@text="使用此文件夹"]' 'folder selection still accessible'
Shot 'folders'
Tap-Text '取消'
Assert-Node '//node[@text="自动备份"]' 'folder cancel returns to settings'
Tap-Text '照片'
Tap-Description '网格'
Write-Output 'PASS: UI navigation completed; account and folder were not changed.'

