# 推送到 GitHub 的辅助脚本（带结果提示，不闪退）
# 用法：右键 -> 使用 PowerShell 运行，按提示粘贴 token

$ErrorActionPreference = "Continue"
Set-Location "D:\WORKBUDDY\hotel_requisition_project\android-app"

Write-Host "================ 推送 GitHub ================" -ForegroundColor Cyan
Write-Host "当前目录: $(Get-Location)" -ForegroundColor Gray

# 1. 确认本地提交存在
$log = git log --oneline -1 2>&1
Write-Host "`n本地最新提交: $log" -ForegroundColor Gray

# 2. 读取 token（隐藏输入）
Write-Host "`n请粘贴 GitHub Token（ghp_ 开头，粘贴后按回车）:" -ForegroundColor Yellow
$token = Read-Host "Token"

if ([string]::IsNullOrWhiteSpace($token) -or -not $token.StartsWith("ghp_")) {
    Write-Host "`n[失败] Token 格式不对，应以 ghp_ 开头。" -ForegroundColor Red
    Write-Host "请重新运行脚本并完整粘贴 token。" -ForegroundColor Red
    Read-Host "`n按回车退出"
    exit 1
}

# 3. 临时把 token 写进远程地址
$repoUrl = "https://292029840-star:${token}@github.com/292029840-star/wanlongzhou-supply.git"
git remote set-url origin $repoUrl
Write-Host "`n[1/3] 已临时写入 token 到远程地址" -ForegroundColor Gray

# 4. 推送（先清掉可能干扰的代理环境变量，防止走坏代理）
Write-Host "[2/3] 正在推送..." -ForegroundColor Gray
$env:HTTP_PROXY = ""; $env:HTTPS_PROXY = ""
$env:http_proxy = ""; $env:https_proxy = ""; $env:ALL_PROXY = ""
git -c http.proxy= -c https.proxy= push -u origin main 2>&1 | Out-Host
git -c http.proxy= -c https.proxy= push origin v1.1 2>&1 | Out-Host
$pushExit = $LASTEXITCODE

# 5. 无论成败，先清掉 token
git remote set-url origin "https://github.com/292028640-star/wanlongzhou-supply.git"
Write-Host "[3/3] 已清除本地 token，恢复安全地址" -ForegroundColor Gray

# 6. 结果判断
if ($pushExit -eq 0) {
    Write-Host "`n================ 推送成功 ================" -ForegroundColor Green
    Write-Host "现在可以去 GitHub 仓库页面看代码了：" -ForegroundColor Green
    Write-Host "https://github.com/292028640-star/wanlongzhou-supply" -ForegroundColor Cyan
} else {
    Write-Host "`n================ 推送失败 ================" -ForegroundColor Red
    Write-Host "退出码: $pushExit" -ForegroundColor Red
    Write-Host "把上面红色的报错信息截图发给 AI 帮你分析。" -ForegroundColor Red
}

Read-Host "`n按回车退出"
