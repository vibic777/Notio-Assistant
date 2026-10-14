# D133: the shortcut of the Start menu gets the name of the product in the language of this installer. jpackage writes the
# shortcut with the one name it was given ("Notio"); the name of the product is ours and comes from the language file
# (NotioProductName: "Notio by vibic", in Russian its own). This script copies it onto every shortcut of the installer, so the
# language transform made from this MSI carries it too. Run by the packageInstaller task for the base MSI and for the MSI of
# each language before its transform is made. No name is written in this file: it is read from the MSI itself.
param(
    [Parameter(Mandatory = $true)][string]$Msi
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

$view = Com $database 'OpenView' 'InvokeMethod' @("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductName'")
Com $view 'Execute' 'InvokeMethod' $null | Out-Null
$record = Com $view 'Fetch' 'InvokeMethod' $null
if ($null -eq $record) { throw "the installer has no ProductName" }
$name = [string](Com $record 'StringData' 'GetProperty' @(1))
Com $view 'Close' 'InvokeMethod' $null | Out-Null
if ([string]::IsNullOrWhiteSpace($name) -or $name.Contains("'") -or $name.Contains('|')) { throw "the name of the product cannot be the name of a shortcut: $name" }

# A file name of an MSI is "short|long": the short one must be 8.3 and plain.
$update = Com $database 'OpenView' 'InvokeMethod' @("UPDATE ``Shortcut`` SET ``Name`` = 'NOTIO|$name'")
Com $update 'Execute' 'InvokeMethod' $null | Out-Null
Com $update 'Close' 'InvokeMethod' $null | Out-Null

$count = 0
$check = Com $database 'OpenView' 'InvokeMethod' @('SELECT `Name` FROM `Shortcut`')
Com $check 'Execute' 'InvokeMethod' $null | Out-Null
while ($null -ne (Com $check 'Fetch' 'InvokeMethod' $null)) { $count++ }
Com $check 'Close' 'InvokeMethod' $null | Out-Null
Com $database 'Commit' 'InvokeMethod' $null | Out-Null

foreach ($o in @($check, $update, $view, $database, $installer)) { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($o) }
[GC]::Collect()
[GC]::WaitForPendingFinalizers()
if ($count -eq 0) { throw "the installer has no shortcut to name" }
Write-Output "shortcuts named: $count"
