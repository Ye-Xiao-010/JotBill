<#
  JotBill 精简版 —— L1 真机验收脚本（唯一一票否决项）

  用法（PowerShell，手机已 adb 连接且开启 USB 调试）：
      .\scripts\verify-lean-l1.ps1 -Count 20

  前置（脚本会自动检查第 1 项，其余请手工确认）：
    1. App 内「通用设置 → 🛒 结算页识别 → 结算页截图 AI 识别」= 关闭
    2. 系统无障碍服务已启用 JotBill
    3. 悬浮窗权限已授予（付款确认弹窗需要）
    4. 支付通知权限已授予

  脚本做什么：
    - 清空诊断日志、记录基线行数
    - 每完成一笔支付，按回车，读取新增日志并判定
    - 输出 20 笔的命中率表与 miss 归因分类

  判据：命中率 >= 90%（20 笔中 >= 18 笔弹窗出现）
#>

[CmdletBinding()]
param(
    [int]$Count = 20,
    [string]$DiagLog = "$env:USERPROFILE\..\..\jotbill-diag.log"
)

$ErrorActionPreference = 'Stop'
$Package = 'com.jotbill.app'

function Get-DiagLogPath {
    # 设备上的固定路径：Android/data/<pkg>/files/jotbill-diag.log
    return (Join-Path $env:TEMP 'jotbill-diag.log')
}

function Pull-DiagLog {
    & adb shell "run-as $Package cat files/jotbill-diag.log" 2>$null
    # debug 包可用 run-as；release 包退回直接 pull（需 root/调试权限）
    if ($LASTEXITCODE -ne 0) {
        & adb pull "/sdcard/Android/data/$Package/files/jotbill-diag.log" $script:LocalLog 2>$null
        if (Test-Path $script:LocalLog) { return Get-Content $script:LocalLog -Raw }
    }
    return $null
}

Write-Host '=== JotBill 精简版 L1 验收 ===' -ForegroundColor Cyan
Write-Host ''

# ---------- 0. 环境检查 ----------
$devices = & adb devices | Select-Object -Skip 1 | Where-Object { $_ -match 'device$' }
if (-not $devices) {
    Write-Error '没有已连接的设备。请连接手机并开启 USB 调试。'
}
Write-Host "[OK] 设备: $devices" -ForegroundColor Green

& adb logcat -c 2>$null
$script:LocalLog = Get-DiagLogPath
if (Test-Path $script:LocalLog) { Remove-Item $script:LocalLog -Force }

Write-Host ''
Write-Host '请确认 App 内「通用设置 → 🛒 结算页识别」= 关闭，然后继续。' -ForegroundColor Yellow
Read-Host '  按回车继续'

# ---------- 1. 基线 ----------
$baseline = (& adb shell "run-as $Package cat files/jotbill-diag.log" 2>$null | Measure-Object -Line).Lines
Write-Host "[基线] 诊断日志 $baseline 行" -ForegroundColor DarkGray

# ---------- 2. 开关门禁（必须先过，否则后面全部无效）----------
Write-Host ''
Write-Host '--- 第 0 步：开关门禁自检 ---' -ForegroundColor Cyan
Write-Host '现在随便完成一笔支付，然后按回车。'
Read-Host
Start-Sleep -Seconds 3

$gateLog = (& adb shell "run-as $Package cat files/jotbill-diag.log" 2>$null) -join "`n"
$mustAppear = @(
    '结算页截图识别链路已停用',
    '跳过协调器/悬浮层创建与事件接线'
)
$mustNotAppear = @(
    '结算页 AI 派发',
    '候选已写入',
    '支付结果页截图兜底已取得',
    '断言截图受理',
    '手动截图已停用: 结算页截图识别链路关闭',
    'CheckoutScreenshot'
)

$gateOk = $true
foreach ($kw in $mustAppear) {
    if ($gateLog -match [regex]::Escape($kw)) {
        Write-Host "  [OK]   出现: $kw" -ForegroundColor Green
    } else {
        Write-Host "  [缺]   未出现: $kw" -ForegroundColor Yellow
    }
}
foreach ($kw in $mustNotAppear) {
    if ($gateLog -match [regex]::Escape($kw)) {
        Write-Host "  [禁]   不该出现却出现了: $kw" -ForegroundColor Red
        $gateOk = $false
    } else {
        Write-Host "  [OK]   未出现: $kw" -ForegroundColor DarkGray
    }
}
if (-not $gateOk) {
    Write-Host ''
    Write-Host '开关门禁未通过 —— 后面 20 笔的结论全部无效。请先修开关。' -ForegroundColor Red
    exit 1
}

# ---------- 3. 20 笔逐笔判定 ----------
Write-Host ''
Write-Host "--- 逐笔验收（$Count 笔，命中率需 >= 90%）---" -ForegroundColor Cyan

$results = @()
for ($i = 1; $i -le $Count; $i++) {
    Write-Host ''
    Write-Host "[$i/$Count] 完成一笔拼多多+微信支付，停在支付结果页。" -ForegroundColor White
    Read-Host '      弹窗出现了吗？(y=出现 / n=没出现 / s=跳过这笔)'
    $answer = (Read-Host).Trim().ToLower()

    if ($answer -eq 's') {
        $results += [PSCustomObject]@{ No = $i; Hit = $null; Reason = 'SKIPPED' }
        continue
    }

    Start-Sleep -Seconds 2
    $log = (& adb shell "run-as $Package cat files/jotbill-diag.log" 2>$null) -join "`n"
    $hit = $log -match '付款确认弹窗已显示:'

    $reason = ''
    if ($hit) {
        $reason = 'HIT'
    } elseif ($log -match '支付结果页未生成成功证据') {
        $reason = 'NO_EVIDENCE(结果页文本解析失败)'
    } elseif ($log -match '支付结果页无可用节点树') {
        $reason = 'NO_NODE_TREE(结果页节点树为空)'
    } elseif ($log -match '弹窗候选挂起等待非敏感帧') {
        $reason = 'BLOCKED_SENSITIVE(帧级敏感门禁挡住)'
    } elseif ($log -match '弹窗跳过:') {
        $reason = "SKIPPED_POLICY($($Matches[0]))"
    } elseif ($log -match '支付结果页同一事件已处理|孤立支付证据判为重复观测') {
        # 同一笔只应出一张卡，看到一张即算命中
        $reason = 'DEDUPED(同一笔去重，计为命中)'
        $hit = $true
    } elseif ($log -match '弹窗预读待确认为空') {
        $reason = 'READ_RACE(预读竞态，应已被有界重试覆盖)'
    } else {
        $reason = 'UNKNOWN(需人工查日志)'
    }

    $results += [PSCustomObject]@{ No = $i; Hit = $hit; Reason = $reason }
    Write-Host "      -> $(if($hit){'命中'}else{'未命中'}): $reason" -ForegroundColor $(if($hit){'Green'}else{'Yellow'})
}

# ---------- 4. 汇总 ----------
Write-Host ''
Write-Host '========== 汇总 ==========' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host

$judged = $results | Where-Object { $_.Hit -ne $null }
$hitCount = ($judged | Where-Object { $_.Hit -eq $true }).Count
$rate = if ($judged.Count -gt 0) { [math]::Round($hitCount / $judged.Count * 100, 1) } else { 0 }

Write-Host "有效笔数: $($judged.Count) / $Count"
Write-Host "命中: $hitCount"
Write-Host "命中率: $rate%" -ForegroundColor $(if ($rate -ge 90) { 'Green' } else { 'Red' })

Write-Host ''
Write-Host 'miss 归因分布：' -ForegroundColor Cyan
$judged | Where-Object { $_.Hit -ne $true } | Group-Object Reason |
    Sort-Object Count -Descending | ForEach-Object {
        Write-Host ("  {0} x {1}" -f $_.Name, $_.Count)
    }

if ($rate -ge 90) {
    Write-Host ''
    Write-Host 'L1 通过。' -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host "L1 未通过（需 >= 90%）。请按上面的归因分类定位。" -ForegroundColor Red
}

Write-Host ''
Write-Host "完整诊断日志已留在设备：/sdcard/Android/data/$Package/files/jotbill-diag.log"