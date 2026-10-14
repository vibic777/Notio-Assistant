# D72: run by the installer after "Install" is pressed, before any file is touched. A running installed Notio holds its files, so the
# person is asked whether to close it; Yes asks Notio to end as by Exit of the tray (a running recording is saved), and a Notio that
# does not know that request (0.9.24 and older: no "ok") or does not end in time is ended the hard way (its data is written whole, D67).
# No goes on as before (Windows may then ask for a restart). A portable Notio has its own files and is left alone.
# Packed into the MSI as -EncodedCommand by the packageInstaller task; NOTIO_CLOSE_ANSWER=Yes / No answers without the box (tests).
$ErrorActionPreference = 'SilentlyContinue'
$running = @(Get-Process -Name Notio | Where-Object { $_.Path -and -not (Test-Path (Join-Path (Split-Path $_.Path) 'portable.txt')) })
if ($running.Count -eq 0) { exit 0 }

$answer = $env:NOTIO_CLOSE_ANSWER
if (-not $answer) {
    Add-Type -AssemblyName System.Windows.Forms
    $owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true }
    # In the language of this Windows when it is one of Notio's ten, in English otherwise (D80).
    $texts = @{
        en = "Notio is running. It has to be closed for the installation to go on without a restart.`n`nClose Notio now? A recording in progress will be saved."
        ru = "Notio запущен. Чтобы установка прошла без перезагрузки, его нужно закрыть.`n`nЗакрыть Notio сейчас? Идущая запись будет сохранена."
        be = "Notio запушчаны. Каб усталяванне прайшло без перазагрузкі, яго трэба закрыць.`n`nЗакрыць Notio зараз? Запіс, які ідзе, будзе захаваны."
        de = "Notio wird ausgeführt. Damit die Installation ohne Neustart abläuft, muss es geschlossen werden.`n`nNotio jetzt schließen? Eine laufende Aufnahme wird gespeichert."
        es = "Notio se está ejecutando. Para instalar sin reiniciar hay que cerrarlo.`n`n¿Cerrar Notio ahora? La grabación en curso se guardará."
        fr = "Notio est en cours d'exécution. Pour installer sans redémarrage, il faut le fermer.`n`nFermer Notio maintenant ? L'enregistrement en cours sera conservé."
        it = "Notio è in esecuzione. Per installare senza riavviare è necessario chiuderlo.`n`nChiudere Notio adesso? La registrazione in corso verrà salvata."
        ja = "Notio が実行中です。再起動せずにインストールするには、終了する必要があります。`n`n今すぐ Notio を終了しますか？ 進行中の録音は保存されます。"
        ko = "Notio가 실행 중입니다. 다시 시작하지 않고 설치하려면 닫아야 합니다.`n`n지금 Notio를 닫으시겠습니까? 진행 중인 녹음은 저장됩니다."
        zh = "Notio 正在运行。要在不重启的情况下完成安装，需要先将其关闭。`n`n现在关闭 Notio 吗？正在进行的录制将被保存。"
    }
    $text = $texts[(Get-UICulture).TwoLetterISOLanguageName]
    if (-not $text) { $text = $texts['en'] }
    $answer = [string][System.Windows.Forms.MessageBox]::Show($owner, $text, 'Notio', 'YesNo', 'Question')
    $owner.Dispose()
}
if ($answer -ne 'Yes') { exit 0 }

$asked = $false
$port = 0
# instance.port: the port, then the secret Notio wants with a request (0.9.26+; an older Notio has no second line and reads only the first).
$lines = @(Get-Content (Join-Path $env:LOCALAPPDATA 'Notio\instance.port') -TotalCount 2)
$token = if ($lines.Count -gt 1) { $lines[1].Trim() } else { '' }
if ($lines.Count -gt 0 -and [int]::TryParse($lines[0], [ref]$port)) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', $port)
        $client.ReceiveTimeout = 3000
        $stream = $client.GetStream()
        $bytes = [System.Text.Encoding]::ASCII.GetBytes("quit`n$token`n")
        $stream.Write($bytes, 0, $bytes.Length)
        # A Notio that knows the request answers "ok" and ends by itself; an older one says nothing.
        $asked = (New-Object System.IO.StreamReader($stream)).ReadLine() -eq 'ok'
        $client.Close()
    } catch { }
}
if ($asked) { foreach ($p in $running) { [void]$p.WaitForExit(25000) } }
foreach ($p in $running) { if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force } }
exit 0
