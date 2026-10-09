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
    - 定位诊断日志并记录基线行数
    - 先做一次开关门禁自检（必须过，否则后面全部无效）
    - 每完成一笔支付，轮询读取**新增**日志行并判定
    - 输出 N 笔的命中率表与 miss 归因分类

  判据：命中率 >= 90%（默认 20 笔中 >= 18 笔弹窗出现）

  ------------------------------------------------------------------
  本文件必须存为 **UTF-8 with BOM**。
  Windows PowerShell 5.1 读取无 BOM 的 .ps1 时按 ANSI(GBK) 解释，
  脚本里的中文判据字面量会变成乱码，导致所有 `-match` 恒为 False。
  改完请用 AST parser 验一遍（见 docs/worklog 同日记录）。
  ------------------------------------------------------------------
#>

[CmdletBinding()]
param(
    [int]$Count = 20
)

$ErrorActionPreference = 'Stop'
$Package = 'com.jotbill.app'

# 诊断日志的真实落盘位置是**外部私有目录**（JotBillLog 用
# context.getExternalFilesDir(null)，见 utils/JotBillLog.kt:79），
# 不是 app 内部 files/。早期脚本按内部路径 `run-as cat files/...` 读，
# 那个文件根本不存在 —— 每次都读到空串。
$script:DiagLogDevice = "/sdcard/Android/data/$Package/files/jotbill-diag.log"
$script:LocalLog = Join-Path $env:TEMP 'jotbill-diag.log'
$script:LineCursor = 0

function Get-DiagLogPath {
    return $script:LocalLog
}

<#
  读取诊断日志，返回行数组（读不到返回 $null）。

  两个必须做对的地方 —— 都曾让 L1 得出错误结论：

  1. **走 adb pull 落盘，再 Get-Content -Encoding UTF8**，
     不能用 `adb shell cat` 的管道输出。
     adb 输出是 UTF-8，PowerShell 5.1 管道按控制台 OEM 代码页（本机 GBK）
     解码，中文全成乱码；`-match '付款确认弹窗已显示:'` 恒为 False，
     与日志里有没有那一行无关。实测：设备端 `grep -c` 得 1，
     管道读回来 match 却是 False。

  2. **返回行数组而不是拼成整串**。
     逐笔判定要靠行数做增量切片；匹配整份历史日志会把
     "以前某笔成功过" 误判成 "这一笔也成功"，直接伪造出命中率。
#>
<#
  在 $ErrorActionPreference = 'Stop' 的脚本里跑 adb，必须先用它包一层。

  adb 会把进度和告警写到 stderr（`1 file pulled, 0 skipped`），
  PowerShell 5.1 把它包成 NativeCommandError；在 Stop 下这是**终止性错误**，
  脚本会在第一次读日志时就抛异常退出。
  实测 `2>$null` 和 `2>&1 | Out-Null` 都拦不住 —— 必须在调用期间把
  ErrorActionPreference 降下来。（这里赋值在函数作用域内，不影响调用方。）
#>
function Invoke-AdbQuiet {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $ErrorActionPreference = 'Continue'
    & adb @AdbArgs 2>&1 | Out-Null
}

function Get-DiagLogLines {
    Remove-Item $script:LocalLog -Force -ErrorAction SilentlyContinue
    Invoke-AdbQuiet pull $script:DiagLogDevice $script:LocalLog
    if (-not (Test-Path $script:LocalLog)) {
        # 回退：极少数定制系统不给外部目录读权限时，改走内部 files。
        # 仍须落盘再读，否则同样踩编码坑。
        $ErrorActionPreference = 'Continue'
        cmd /c "adb exec-out run-as $Package cat files/jotbill-diag.log > `"$script:LocalLog`" 2>nul" 2>&1 | Out-Null
        if (-not (Test-Path $script:LocalLog)) { return $null }
    }
    $lines = @(Get-Content $script:LocalLog -Encoding UTF8 -ErrorAction SilentlyContinue)
    if ($lines.Count -eq 0) { return $null }
    return $lines
}

<#
  取相对上次游标新增的行，并推进游标。
  日志满 10MB 会轮转到 jotbill-diag.log.1（见 JotBillLog.kt:151），
  轮转后当前文件行数小于游标，此时重置游标并把当前全部行当新增。
#>
function Take-DiagLogDelta {
    $lines = Get-DiagLogLines
    if ($null -eq $lines) { return $null }
    if ($lines.Count -lt $script:LineCursor) { $script:LineCursor = 0 }
    # ★ 用 `,@()` 而不是 `@()`：PowerShell 会把空数组在管道里展开成"什么都没有"，
    #   调用方拿到的是 $null 而不是空数组。返回类型不稳定是后续改动的坑。
    if ($lines.Count -eq $script:LineCursor) { return , @() }
    $delta = $lines[$script:LineCursor..($lines.Count - 1)]
    $script:LineCursor = $lines.Count
    return $delta
}

<#
  轮询等待"付款确认弹窗已显示:"出现，返回窗口内累积的全部新增行。
  弹窗从"支付结果页事件"到"卡片上屏"有延迟，小米/HyperOS 上尤其慢；
  原来固定 Start-Sleep 2 秒，慢机型会被系统性误判成 miss。
#>
function Wait-DiagPopupHit {
    param([int]$TimeoutSec = 20)
    $hitKeyword = '付款确认弹窗已显示:'
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $accumulated = @()
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $slice = Take-DiagLogDelta
        if ($null -eq $slice -or $slice.Count -eq 0) { continue }
        $accumulated += $slice
        if (($accumulated -join "`n") -match [regex]::Escape($hitKeyword)) { return $accumulated }
    }
    # 同上：空累积要用 `,@()` 才不会被展开成 $null
    return , $accumulated
}

Write-Host '=== JotBill 精简版 L1 验收 ===' -ForegroundColor Cyan
Write-Host ''

# ---------- 0. 环境检查 ----------
# adb 首次拉起 daemon 时会把 "* daemon not running; starting now" 写到 stderr，
# 在 Stop 下同样会终止脚本，所以这里也要降 ErrorActionPreference。
$ErrorActionPreference = 'Continue'
$devices = & adb devices 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'device$' }
$ErrorActionPreference = 'Stop'
if (-not $devices) {
    Write-Error '没有已连接的设备。请连接手机并开启 USB 调试。'
}
Write-Host "[OK] 设备: $devices" -ForegroundColor Green

Invoke-AdbQuiet logcat -c
Write-Host "[OK] 诊断日志: $script:DiagLogDevice" -ForegroundColor Green

Write-Host ''
Write-Host '请确认 App 内「通用设置 → 🛒 结算页识别」= 关闭，然后继续。' -ForegroundColor Yellow
Read-Host '  按回车继续'

# ---------- 1. 基线 ----------
# 日志读不到就必须在这里停。继续跑只会得到"空串里没有禁用词 → 门禁假通过"，
# 再把每一笔都判成 UNKNOWN —— 结论看着像"命中率 0%"，实际是脚本瞎了。
$baseline = Get-DiagLogLines
if ($null -eq $baseline) {
    Write-Host ''
    Write-Host '读不到诊断日志，无法验收。' -ForegroundColor Red
    Write-Host "  · 目标路径: $script:DiagLogDevice" -ForegroundColor Red
    Write-Host '  · App 至少要冷启动过一次（日志是启动时建的）' -ForegroundColor Red
    Write-Host '  · adb devices 要显示 device 而不是 unauthorized' -ForegroundColor Red
    exit 1
}
$script:LineCursor = $baseline.Count
Write-Host "[基线] 诊断日志 $script:LineCursor 行（后续只判定新增行）" -ForegroundColor DarkGray

# ---------- 2. 开关门禁（必须先过，否则后面全部无效）----------
Write-Host ''
Write-Host '--- 第 0 步：开关门禁自检 ---' -ForegroundColor Cyan
Write-Host '现在随便完成一笔支付，然后按回车。' 
Read-Host
Start-Sleep -Seconds 3

# mustAppear 查**全量**日志：这几行只在无障碍服务连接时打一次，
# 不可能每笔都出现。mustNotAppear 同时查全量与本笔新增，
# 后者才是"这一笔没有走被关掉的链路"的直接证据。
$gateAll = (Get-DiagLogLines) -join "`n"
$gateNew = (Take-DiagLogDelta) -join "`n"
if ([string]::IsNullOrWhiteSpace($gateAll)) {
    Write-Host ''
    Write-Host '诊断日志读空了 —— 门禁无法判定，退出。' -ForegroundColor Red
    exit 1
}

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
Write-Host '  [应出现 · 全量日志]' -ForegroundColor DarkGray
foreach ($kw in $mustAppear) {
    if ($gateAll -match [regex]::Escape($kw)) {
        Write-Host "  [OK]   出现: $kw" -ForegroundColor Green
    } else {
        Write-Host "  [缺]   未出现: $kw" -ForegroundColor Red
        # ★ 原实现在这里只打印不改 $gateOk，空日志下门禁会"假通过"。
        $gateOk = $false
    }
}
Write-Host '  [不应出现 · 全量日志 + 本笔新增]' -ForegroundColor DarkGray
foreach ($kw in $mustNotAppear) {
    if ($gateAll -match [regex]::Escape($kw)) {
        Write-Host "  [禁]   全量日志里出现了: $kw" -ForegroundColor Red
        $gateOk = $false
    } elseif ($gateNew -match [regex]::Escape($kw)) {
        Write-Host "  [禁]   本笔新增里出现了: $kw" -ForegroundColor Red
        $gateOk = $false
    } else {
        Write-Host "  [OK]   未出现: $kw" -ForegroundColor DarkGray
    }
}
if (-not $gateOk) {
    Write-Host ''
    Write-Host '开关门禁未通过 —— 后面各笔的结论全部无效。请先修开关。' -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host '门禁通过（截图链已停用，支付结果页文本解析保留）。' -ForegroundColor Green

# ---------- 3. 逐笔判定 ----------
Write-Host ''
Write-Host "--- 逐笔验收（$Count 笔，命中率需 >= 90%）---" -ForegroundColor Cyan
Write-Host '每笔请：完成支付后停在支付结果页，等弹窗出现或明确没出现，再按提示输入。' -ForegroundColor DarkGray

$results = @()
for ($i = 1; $i -le $Count; $i++) {
    Write-Host ''
    Write-Host "[$i/$Count] 完成一笔拼多多+微信支付，停在支付结果页。" -ForegroundColor White
    $answer = (Read-Host '      弹窗出现了吗？(y=出现 / n=没出现 / s=跳过这笔)').Trim().ToLower()

    if ($answer -eq 's') {
        $results += [PSCustomObject]@{ No = $i; Hit = $null; Reason = 'SKIPPED' }
        # 跳过的这笔不能留在游标后面，否则它的日志会被算进下一笔。
        Take-DiagLogDelta | Out-Null
        continue
    }

    # 无论用户答什么，都以日志为准重新取这段窗口的新增行。
    $delta = Wait-DiagPopupHit -TimeoutSec 20
    $log = ($delta -join "`n")
    $hit = ($log -match '付款确认弹窗已显示:')

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

if ($judged.Count -eq 0) {
    Write-Host ''
    Write-Host '没有任何有效笔次 —— 不构成通过或未通过的结论。' -ForegroundColor Red
} elseif ($rate -ge 90) {
    Write-Host ''
    Write-Host 'L1 通过。' -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host 'L1 未通过（需 >= 90%）。请按上面的归因分类定位。' -ForegroundColor Red
}

Write-Host ''
Write-Host "完整诊断日志仍在设备：$script:DiagLogDevice"
Write-Host "本地副本：$script:LocalLog"