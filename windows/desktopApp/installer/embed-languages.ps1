# D80: puts the language transforms (<language number>.mst, made by torch.exe from the installer built in each language) into the
# base MSI and lists the languages in its summary. Windows Installer then shows the installer in the language of the person's
# Windows when it is one of them, and in the base language (English) otherwise. Run by the packageInstaller task.
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$Transforms,
    # The language numbers, the base language first: "1033,1049,..."
    [Parameter(Mandatory = $true)][string]$Languages
)
$ErrorActionPreference = 'Stop'

function Com($object, [string]$name, [string]$kind, $arguments) {
    # PowerShell wraps what a function returns; COM wants the object itself.
    $plain = [object[]]@($arguments | ForEach-Object { if ($_ -is [psobject]) { $_.PSObject.BaseObject } else { $_ } })
    if ($null -eq $arguments) { $plain = $null }
    $target = if ($object -is [psobject]) { $object.PSObject.BaseObject } else { $object }
    return $target.GetType().InvokeMember($name, [System.Reflection.BindingFlags]$kind, $null, $target, $plain)
}

$installer = New-Object -ComObject WindowsInstaller.Installer
# 1 = msiOpenDatabaseModeTransact
$database = Com $installer 'OpenDatabase' 'InvokeMethod' @((Resolve-Path $Msi).Path, 1)
$view = Com $database 'OpenView' 'InvokeMethod' @('SELECT `Name`, `Data` FROM _Storages')
$count = 0
foreach ($transform in Get-ChildItem -Path $Transforms -Filter *.mst) {
    $record = Com $installer 'CreateRecord' 'InvokeMethod' @(2)
    Com $record 'StringData' 'SetProperty' @(1, $transform.BaseName) | Out-Null
    Com $view 'Execute' 'InvokeMethod' @($record) | Out-Null
    Com $record 'SetStream' 'InvokeMethod' @(2, $transform.FullName) | Out-Null
    # 3 = msiViewModifyAssign: a new storage, or the one of that name replaced
    Com $view 'Modify' 'InvokeMethod' @(3, $record) | Out-Null
    $count++
}
Com $view 'Close' 'InvokeMethod' $null | Out-Null

# Summary property 7 (Template): "<platform>;<languages>".
$summary = Com $database 'SummaryInformation' 'GetProperty' @(1)
$template = [string](Com $summary 'Property' 'GetProperty' @(7))
$platform = $template.Split(';')[0]
Com $summary 'Property' 'SetProperty' @(7, "$platform;$Languages") | Out-Null
Com $summary 'Persist' 'InvokeMethod' $null | Out-Null
Com $database 'Commit' 'InvokeMethod' $null | Out-Null

foreach ($o in @($summary, $view, $database, $installer)) { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($o) }
[GC]::Collect()
[GC]::WaitForPendingFinalizers()
Write-Output "languages in the installer: $Languages ($count transforms)"
