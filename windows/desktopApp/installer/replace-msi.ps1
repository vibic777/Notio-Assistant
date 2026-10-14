# D80: the EXE installer of jpackage is a small launcher with the MSI inside it as a resource. jpackage builds that MSI itself, in
# one language; this puts the MSI of the packageInstaller task (all ten languages) in its place, so both installers are the same.
# Run by the packageInstallerExe task.
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string]$Msi
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class NotioResources {
    public delegate bool EnumTypes(IntPtr module, IntPtr type, IntPtr param);
    public delegate bool EnumNames(IntPtr module, IntPtr type, IntPtr name, IntPtr param);
    public delegate bool EnumLanguages(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr param);

    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32", SetLastError = true)] static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool EnumResourceNames(IntPtr module, IntPtr type, EnumNames callback, IntPtr param);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool EnumResourceLanguages(IntPtr module, IntPtr type, IntPtr name, EnumLanguages callback, IntPtr param);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr FindResourceEx(IntPtr module, IntPtr type, IntPtr name, ushort language);
    [DllImport("kernel32", SetLastError = true)] static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr BeginUpdateResource(string file, bool deleteExisting);
    [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool UpdateResource(IntPtr update, IntPtr type, string name, ushort language, byte[] data, uint size);
    [DllImport("kernel32", SetLastError = true)] static extern bool EndUpdateResource(IntPtr update, bool discard);

    static readonly IntPtr RcData = new IntPtr(10);

    /// The names and languages of the RCDATA resources with their sizes: "NAME|language|bytes".
    public static List<string> List(string file) {
        var found = new List<string>();
        IntPtr module = LoadLibraryEx(file, IntPtr.Zero, 0x22); // as a data file and an image resource
        if (module == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
        try {
            EnumResourceNames(module, RcData, (m, type, name, p) => {
                // A name is a string unless it is a number below 65536.
                string text = ((ulong)name.ToInt64() >> 16) == 0 ? "#" + name.ToInt64() : Marshal.PtrToStringUni(name);
                EnumResourceLanguages(m, type, name, (m2, t2, n2, language, p2) => {
                    IntPtr resource = FindResourceEx(m2, t2, n2, language);
                    found.Add(text + "|" + language + "|" + SizeofResource(m2, resource));
                    return true;
                }, IntPtr.Zero);
                return true;
            }, IntPtr.Zero);
        } finally { FreeLibrary(module); }
        return found;
    }

    public static void Replace(string file, string name, ushort language, byte[] data) {
        IntPtr update = BeginUpdateResource(file, false);
        if (update == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
        if (!UpdateResource(update, RcData, name, language, data, (uint)data.Length)) {
            int error = Marshal.GetLastWin32Error();
            EndUpdateResource(update, true);
            throw new System.ComponentModel.Win32Exception(error);
        }
        if (!EndUpdateResource(update, false)) throw new System.ComponentModel.Win32Exception();
    }
}
"@

$exePath = (Resolve-Path $Exe).Path
# jpackage leaves its installer read-only.
Set-ItemProperty -LiteralPath $exePath -Name IsReadOnly -Value $false
$msiBytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Msi).Path)
# The MSI is by far the largest RCDATA resource of the launcher: it is found by that, not by a name that may change with the JDK.
$resources = @([NotioResources]::List($exePath) | ForEach-Object { $p = $_.Split('|'); [pscustomobject]@{ Name = $p[0]; Language = [uint16]$p[1]; Size = [long]$p[2] } })
$inside = $resources | Sort-Object Size -Descending | Select-Object -First 1
if (-not $inside -or $inside.Size -lt 1MB -or $inside.Name.StartsWith('#')) { throw "no MSI found inside $Exe (resources: $($resources | ForEach-Object { $_.Name + ' ' + $_.Size }))" }
[NotioResources]::Replace($exePath, $inside.Name, $inside.Language, $msiBytes)
$after = @([NotioResources]::List($exePath) | Where-Object { $_.StartsWith($inside.Name + '|') })
if ($after.Count -ne 1 -or [long]$after[0].Split('|')[2] -ne $msiBytes.Length) { throw "the MSI inside $Exe was not replaced: $after" }
Write-Output "the MSI inside the EXE: resource $($inside.Name), $($inside.Size) -> $($msiBytes.Length) bytes"
