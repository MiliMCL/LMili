param()
$ErrorActionPreference = "Stop"
$m = "E:\Program Files\Tencent\AndrowsData\Mili\mili-server\src\minecraft\java"
$pdir = "E:\Program Files\Tencent\AndrowsData\Mili\mili-server\minecraft-patches\features"
$patches = Get-ChildItem $pdir -Filter *.patch | Sort-Object Name
$commits = @(git -C $m rev-list --reverse file..HEAD)
if ($patches.Count -ne $commits.Count) { throw "count mismatch: $($patches.Count) vs $($commits.Count)" }
$adapted = @("0070-", "0089-", "0102-", "0112-", "0118-", "0119-")
$utf8 = New-Object System.Text.UTF8Encoding($false)
for ($i = 0; $i -lt $patches.Count; $i++) {
    $p = $patches[$i]
    $isAdapted = $false
    foreach ($a in $adapted) { if ($p.Name.StartsWith($a)) { $isAdapted = $true; break } }
    if (-not $isAdapted) { continue }
    $commit = $commits[$i]
    $content = [System.IO.File]::ReadAllText($p.FullName)
    $idx = $content.IndexOf("diff --git")
    if ($idx -lt 0) { throw "no diff marker in $($p.Name)" }
    $header = ($content.Substring(0, $idx)) -replace "`r`n", "`n"
    $diff = (git -C $m diff "$commit^" $commit | Out-String) -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($p.FullName, $header + $diff, $utf8)
    Write-Output "rewrote $($p.Name) from $commit"
}
