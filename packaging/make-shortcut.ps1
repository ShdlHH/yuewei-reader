param([string]$Version = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist"

if (-not $Version) {
    $Version = Get-ChildItem $dist -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName "阅微阅读器\阅微阅读器.exe")) } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1 -ExpandProperty Name
}
if (-not $Version) {
    throw "dist 下没有找到可用的版本目录"
}

$targetDir = Join-Path $dist "$Version\阅微阅读器"
$exe = Join-Path $targetDir "阅微阅读器.exe"
if (-not (Test-Path $exe)) {
    throw "找不到程序：$exe"
}

$link = Join-Path $dist "latest"
if (Test-Path $link) {
    cmd /c rmdir "$link" | Out-Null
}
cmd /c mklink /J "$link" "$targetDir" | Out-Null
if (-not (Test-Path $link)) {
    throw "创建 latest 目录联接失败"
}

$shortcutExe = Join-Path $link "阅微阅读器.exe"
$desktop = [Environment]::GetFolderPath("Desktop")
$shell = New-Object -ComObject WScript.Shell
$existing = Get-ChildItem $desktop -Filter *.lnk -ErrorAction SilentlyContinue |
    Where-Object { $shell.CreateShortcut($_.FullName).TargetPath -like "*阅微阅读器.exe" } |
    Select-Object -First 1
$lnkPath = if ($existing) { $existing.FullName } else { Join-Path $desktop "阅微阅读器.lnk" }

$shortcut = $shell.CreateShortcut($lnkPath)
$shortcut.TargetPath = $shortcutExe
$shortcut.WorkingDirectory = $link
$shortcut.IconLocation = "$exe,0"
$shortcut.Description = "阅微 · 小说阅读器"
$shortcut.Save()

Write-Output "version=$Version"
Write-Output "shortcut=$lnkPath"
Write-Output "target=$shortcutExe"
