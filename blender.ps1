param(
    [string]$cmd,
    [int]$ConnectTimeoutMs = 3000,
    [int]$ReadTimeoutMs = 120000
)

$ErrorActionPreference = 'Stop'
if (-not $cmd) { $cmd = Read-Host '> ' }

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$c = New-Object System.Net.Sockets.TcpClient
$s = $null
$w = $null
try {
    $pending = $c.BeginConnect('127.0.0.1', 9877, $null, $null)
    if (-not $pending.AsyncWaitHandle.WaitOne($ConnectTimeoutMs)) {
        throw "Timed out connecting to Blender CLI on 127.0.0.1:9877"
    }
    $c.EndConnect($pending)
    $s = $c.GetStream()
    $s.ReadTimeout = $ReadTimeoutMs
    $w = New-Object System.IO.StreamWriter($s)
    $w.WriteLine($cmd)
    $w.Flush()

    $data = New-Object System.Text.StringBuilder
    $buf = New-Object byte[] 4096
    while ($true) {
        $n = $s.Read($buf, 0, $buf.Length)
        if ($n -eq 0) { break }
        $text = [System.Text.Encoding]::UTF8.GetString($buf, 0, $n)
        [void]$data.Append($text)
        if ($data.ToString().EndsWith("`n`n")) { break }
    }
    $data.ToString().TrimEnd("`r", "`n")
}
finally {
    if ($w) { $w.Dispose() }
    if ($s) { $s.Dispose() }
    $c.Dispose()
}
