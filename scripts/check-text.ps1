$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskUtf8 = [Text.UTF8Encoding]::new($false, $true)
$taskFailures = [Collections.Generic.List[string]]::new()
$taskFiles = & git -C $taskRoot ls-files --cached --others --exclude-standard
foreach ($taskRelative in $taskFiles) {
    if ($taskRelative -match '\.(jar|aar|apk|png|jpg|jks|keystore)$') { continue }
    $taskAbsolute = Join-Path $taskRoot $taskRelative
    if (-not (Test-Path -LiteralPath $taskAbsolute -PathType Leaf)) { continue }
    $taskBytes = [IO.File]::ReadAllBytes($taskAbsolute)
    if ($taskBytes.Length -ge 3 -and $taskBytes[0] -eq 239 -and $taskBytes[1] -eq 187 -and $taskBytes[2] -eq 191) {
        $taskFailures.Add("$taskRelative : UTF-8 BOM")
    }
    try {
        $taskText = $taskUtf8.GetString($taskBytes)
        if ($taskText.Contains("`r")) { $taskFailures.Add("$taskRelative : 非 LF 换行") }
    } catch [Text.DecoderFallbackException] { $taskFailures.Add("$taskRelative : 非 UTF-8") }
}
if ($taskFailures.Count -gt 0) { $taskFailures | Write-Output; exit 1 }
Write-Output "已检查 $($taskFiles.Count) 个受版本管理或未忽略的文件：文本为 UTF-8 无 BOM、LF。"
