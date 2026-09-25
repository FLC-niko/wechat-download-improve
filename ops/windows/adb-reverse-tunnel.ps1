$ErrorActionPreference = 'Continue'

$adb = 'C:\platform-tools\adb.exe'
$ssh = 'C:\Windows\System32\OpenSSH\ssh.exe'
$identity = 'C:\ProgramData\TopviewAdbTunnel\id_ed25519'
$log = 'C:\ProgramData\TopviewAdbTunnel\adb-reverse-tunnel.log'
$server = 'topview@10.21.76.61'

function Write-TunnelLog([string] $message) {
    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -LiteralPath $log -Value "[$timestamp] $message"
}

Write-TunnelLog 'watchdog started'

while ($true) {
    & $adb start-server 2>&1 | ForEach-Object { Write-TunnelLog "adb: $_" }

    # The physical phone must appear only once through the Windows USB ADB server.
    & $adb disconnect '192.168.10.104:5555' 2>&1 | ForEach-Object { Write-TunnelLog "adb: $_" }

    Write-TunnelLog 'opening reverse tunnel'
    & $ssh `
        -i $identity `
        -o BatchMode=yes `
        -o ExitOnForwardFailure=yes `
        -o StrictHostKeyChecking=yes `
        -o ServerAliveInterval=30 `
        -o ServerAliveCountMax=3 `
        -N `
        -R '127.0.0.1:15037:127.0.0.1:5037' `
        $server 2>&1 | ForEach-Object { Write-TunnelLog "ssh: $_" }

    Write-TunnelLog "tunnel exited with code $LASTEXITCODE; retrying in 5 seconds"
    Start-Sleep -Seconds 5
}
