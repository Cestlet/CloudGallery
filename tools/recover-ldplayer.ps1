param(
    [Parameter(Mandatory = $true)]
    [string]$LdHome,
    [string]$VBoxHome = 'C:\Program Files\ldplayer9box'
)
$ErrorActionPreference = 'Stop'
$vbox = Join-Path $VBoxHome 'VBoxManage.exe'
$ld = Join-Path $LdHome 'ld.exe'
$adb = (Get-Command adb -ErrorAction Stop).Source
$network = 'LdNatNetwork0'
$info = & $vbox showvminfo leidian0 --machinereadable
if ($LASTEXITCODE -ne 0 -or $info -notcontains 'VMState="running"' -or $info -notcontains 'nat-network1="LdNatNetwork0"') {
    throw 'Start the original LDPlayer instance 0 before running this script.'
}
if (-not (Get-Process -Name VBoxNetNAT -ErrorAction SilentlyContinue)) {
    & $vbox natnetwork stop --netname $network
    $residual = Get-CimInstance Win32_Process -Filter "Name='VBoxNetDHCP.exe'" |
        Where-Object { $_.CommandLine -match '--comment LdNatNetwork0(?:\s|$)' }
    $residual | ForEach-Object { Stop-Process -Id $_.ProcessId }
    & $vbox natnetwork start --netname $network
    if ($LASTEXITCODE -ne 0) { throw 'Could not restart the original LDPlayer virtual network.' }
}
$addresses = & $ld -s 0 ip -4 addr show wlan0
$match = [regex]::Match(($addresses -join "`n"), 'inet (172\.16\.1\.\d+)/24')
if (-not $match.Success) { throw 'Could not confirm the original guest IP address.' }
$guestIp = $match.Groups[1].Value
$rules = (& $vbox list natnets) -join "`n"
$rule = "PixNexusDebug0:tcp:[127.0.0.1]:5555:[$guestIp]:5555"
if (-not $rules.Contains($rule)) {
    if ($rules.Contains('PixNexusDebug0:')) {
        & $vbox natnetwork modify --netname $network --port-forward-4 delete PixNexusDebug0
        if ($LASTEXITCODE -ne 0) { throw 'Could not update the recovery forwarding rule.' }
    } elseif (Get-NetTCPConnection -LocalPort 5555 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 5555 is in use; existing forwarding rules were left unchanged.'
    }
    & $vbox natnetwork modify --netname $network --port-forward-4 $rule
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the localhost forwarding rule.' }
}
& $ld -s 0 setprop service.adb.tcp.port 5555
& $ld -s 0 setprop ctl.start adbd
& $adb disconnect 127.0.0.1:5555
& $adb connect 127.0.0.1:5555
& $adb devices -l
