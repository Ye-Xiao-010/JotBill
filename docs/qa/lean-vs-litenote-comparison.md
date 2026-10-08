# 精简版 vs 上游 LiteNote 逐项比对

> 比对对象：<https://github.com/GystuG/LiteNote>（MIT，80 star / 11 fork，
> 默认分支 `main`，最后 push 2026-05-04）
> 基线：LiteNote 的 `litenote-mobile-app/` 与本仓库 `jotbill-app/`
> 本仓库分支：`feat/lean-version`（未合并 main）

结论先说：**精简版没有在"做得好的地方"退步，反而补齐了上游自己都没接线的部分。
但确实丢了一条上游已接线的功能线（应用内升级），并存在一处"迁移引入的回归"。**

---

## 一、上游有、精简版没有的 —— 逐条判定

| 项 | 上游（LiteNote） | 精简版 | 判定 |
|---|---|---|---|
| **应用内升级** `检测升级` + `<UpdateModal>` | `SettingsScreen` 里 `useAppUpdate({autoCheck:false})` 已接线，"关于"分组有"检测升级"入口 | 代码**全部保留**，但 `UpdateModal.tsx` **零挂载**、`useAppUpdate` 只被 barrel 引用；且 `.env.development` 里 `UPDATE_MANIFEST_URL` 为空 | ⚠️ **真的丢了**。但即便接上也会失败（无 manifest 地址）⇒ 属于**缺基础设施**，不是被精简砍掉 |
| 版本号显示 | 页脚 `版本 {currentVersion}` | 只有 `JotBill` + 版权 | ⚠️ 小损失，原生已有版本号，可低成本补 |
| `退出登录` 按钮 | 有 | 无 | ✅ 预期内（无账号体系） |
| 账户体系 `AuthProvider` / `LoginScreen` / `EditProfileScreen` / `nativeAuth` / `types/user` | 有 | 无 | ✅ 预期内（本地优先） |
| 后端 API 层 `services/api/*`（10 个）/ `http.ts` / `config/api.ts` / `constants/api.ts` | 有（NestJS + Prisma） | 无 | ✅ 预期内（Room 本地化，这是精简版的核心） |
| `PrivacyPolicyScreen` / `UserAgreementScreen` | 有（`dadb031` 删除） | 无 | ⚠️ 见 §五，**值得重新考虑** |
| `screens/auth/*` 其余 | — | — | ✅ 预期内 |

### 上游自己就有的孤儿（我们删掉**不算**损失）

`AppNavigator.tsx` 上游只注册了 9 个页面：`Dashboard` / `Reports` / `AISessions` /
`Settings` / `CreateBill` / `AllBills` / **`Statistics`** / `Categories` / `BillDetail`。

以下文件在上游仓库里存在但**根本没有任何入口**：

- `screens/reports/CategoryAnalysisScreen.tsx`（分类分析）
- `screens/reports/TrendAnalysisScreen.tsx`（趋势分析）
- `components/business/BudgetCard.tsx`
- `screens/BudgetScreen.tsx`（26KB）
- `screens/FinancialGoalScreen.tsx`（25KB）
- 以及无 deep link 时的 `screens/finance/StatisticsScreen.tsx`

→ 所以 `dadb031` 删掉 `CategoryAnalysisScreen` / `TrendAnalysisScreen`、
`bc1588e` 删掉 `StatisticsScreen` + `useStatistics`，**都没有减少任何用户可达的能力**。

**反向亮点**：精简版把上游没接线的 `BudgetScreen` 和 `FinancialGoalScreen`
**接进了路由**（`AppNavigator` 已注册，设置页有"预算管理""财务目标"两个入口）。
这两块上游是纯死代码。

---

## 二、精简版相对上游**新增**的（上游完全没有）

- **整个原生桥接层**：Room 18 张表 / 18 个 entity / 12 个 Bridge Module。
  上游是"RN 调 NestJS 后端调 PostgreSQL"，精简版把后端整段去掉、数据落到本地。
- **待确认中心** `ReviewScreen.tsx`（53KB）+ `PendingConfirmService` 统一入账入口
- **9 个新设置页**：账户管理 / AI 服务商 / 运行状态 / 数据管理 / 规则 /
  导入 / 无障碍权限 / 通知权限 / 待确认中心
- **备份与恢复**（上游无）
- 能力开关与精简形态由 `BuildConfig.LEAN_DEFAULT` 决定
- 热更新 + Sentry + Keystore + APK 签名校验的一整套加固

文件体积上也能看出是"加了东西"而非"抄完就删"：

| 文件 | 上游 | 精简版 |
|---|---|---|
| `AIChatScreen.tsx` | 88,749 | 105,006 |
| `DashboardScreen.tsx` | 26,411 | 29,982 |
| `CategoryManageScreen.tsx` | 11,314 | 18,754 |
| `CreateBillScreen.tsx` | 14,960 | 31,215 |
| `DailyView.tsx` | 5,919 | 9,092 |
| `GeneralSettingsScreen.tsx` | 44,159 | 54,057 |

---

## 三、四个缺陷已确认是**从上游原样继承**，不是精简版自创

逐字节比对上游源码后确认：

| 缺陷 | 上游原文 | 精简版修法 |
|---|---|---|
| `useToast` 是局部 `useState`、无 Provider | `useToast.ts` 全文 71 行，与我们修复前的版本**完全一致** | 提升为 Context 单例 + `ToastProvider` |
| `LineChart` 单数据点 NaN | `x = padding.left + (chartWidth / (data.length - 1)) * index`，一字不差 | 分母改 `Math.max(len-1, 1)` |
| `useIncomeExpenseData` 无 `isError`、`useDailyBills` 写死 `limit: 100` 且只取 `items` | 一字不差 | 补 `isError`/`error`，返回 `total`/`hasMore` |
| `SettingsScreen` 的"关于应用"是 `onPress: () => {}` | 一字不差（上游也是死按钮） | **尚未修，见 §四** |

> 顺带：上游 `useIncomeExpenseData` 里 `parseInt(t.month)` 是错的
> （`t.month` 形如 `"2026-01"`，`parseInt` 得到 `2026`），精简版已改为 `getMonthNumber()`。

---

## 四、**唯一的迁移引入回归**：资产趋势的锚定公式

这是本次比对最值得记的一条。

上游 `useAssetsData.ts`：

```ts
const netWorth = (totalQuery.data?.totalIncome ?? 0) - (totalQuery.data?.totalExpense ?? 0);
//                                                       ↑ 全部来自账单统计
const value = netWorth - totalNetInRange + cumulative;
//        等价于 Σ(所有收入-支出) - Σ(区间内收支) + Σ(区间内累计) = 区间期初累计
```

**上游这个公式是自洽的** —— `netWorth` 本身就是账单口径，恒等式成立。

离线迁移时我们把 `netWorth` 换成了 `AccountDao.getNetWorth()`：

```sql
-- AccountDao.kt:36-45
SELECT SUM(CASE WHEN kind='asset' THEN current_balance_minor ELSE 0 END) AS totalAssets,
       SUM(CASE WHEN kind='liability' THEN ABS(current_balance_minor) ELSE 0 END) AS totalLiabilities
FROM accounts WHERE is_active = 1
```

这是**账户余额**口径，含 `initial_balance_minor`（开户初始余额）与
`balance_calibrations`（余额校准）—— **这些从不对应任何账单**，
恒等式被打破，整条曲线被平移。

期初 5 万、近 12 月净流入 3 千的用户，曲线第一个点是 **-47000** 而不是 0；
而同页上方明写"净资产（账户余额）¥5.3 万" —— 两个数字互相矛盾。

**教训**：迁移时换了数据源，必须同时检查所有**依赖该数据源口径的公式**。
只换 `queryFn` 不换下游算式，是这类回归的典型成因。

修法（`a025529`）：`AssetsTab` 保留"净资产（账户余额）"这个**更有用**的口径，
趋势图改为诚实的**收支净额累计**，标题同步改成"收支净额累计"。

### 附：账户余额这条链本身是可靠的（已核）

既然"净资产（账户余额）"现在是资产页的头号数字，核了一遍它的写入路径：

- `AccountDao.getNetWorth()` 只汇总 `is_active = 1` 的账户
- `applyBalanceDelta`（`LedgerRepository.kt:794-857`）用 `checkedAdd` / `checkedMultiply`
  防溢出，且 `accountId` 为空时对 `transfer`/`repayment` 硬拒
- `createBill` 走 `requireActiveAccounts = true`（:120-127）—— **普通收支也要求账户**
- `updateBillWithBalanceUpdate` 先反向冲销旧值再施加新值（:252-253）
- `mergeIntoExistingBill`（去重合并路径）复用上面的 update，净效果为零

→ 余额维护这一层没有发现漏更新或溢出风险。

---

## 五、精确死代码清单（脚本核过导入图，非猜测）

### 5.1 应用内升级整条链（约 24.6 KB JS + 1 个原生模块）

| 文件 | 大小 | 状态 |
|---|---|---|
| `src/hooks/useAppUpdate.ts` | 7,180 | 只被 `hooks/index.ts` 导出，无人 import |
| `src/components/UpdateModal.tsx` | 4,502 | **零 importer** |
| `src/services/update/client.ts` | 9,454 | 只被 `useAppUpdate` + 1 个测试引用 |
| `src/services/update/appVersion.ts` | 2,334 | 只被上面两个引用 |
| `src/native/installApk.ts` | 1,292 | 只被 `useAppUpdate` 引用 |
| `android/.../utils/InstallApkModule.kt` | — | 仅 JS 调用可达 |

外加 `.env.development` 里 **`UPDATE_MANIFEST_URL` 为空** ⇒ 接上按钮也必然失败。

### 5.2 热更新"下载/应用"链（约 14 KB JS + 部分原生）

| 文件 | 大小 | 状态 |
|---|---|---|
| `src/hooks/useHotUpdate.ts` | 3,749 | 只被 barrel 导出，无人 import |
| `src/services/update/hotUpdate.ts` | 1,115 | 只被上面 + `services/hotUpdate` 引用 |
| `src/services/hotUpdate/index.ts` | 9,045 | **只有 `markBundleLoaded` 被 `App.tsx:13` 引用**，其余 checkUpdate/download/apply 全死 |
| `android/.../hotupdate/HotUpdateModule.kt` | — | 只有 `markBundleApplied` 活着 |

⚠️ 注意：`markBundleLoaded` 那个"崩溃计数复位"是**活的**（`App.tsx:31` 启动即调），
不能因为下载链死了就把它一起删 —— 那会让成功运行的 bundle 被误判为崩溃而回退。

### 5.3 其他孤儿

`src/utils/format.ts`（4,846）、`src/utils/url.ts`（1,106，账号头像用，账号体系已删）、
`src/constants/app.ts`（1,752）、`src/stores/index.ts`（242）、
`src/types/budget.ts`（900）、`src/types/financial-goal.ts`（801）、
`src/types/models.ts`（1,377）、`src/services/index.ts`（175）

### 5.4 上游也有的死 UI（继承而来）

- `SettingsScreen` 的"关于应用" → `onPress: () => {}`，**点了没反应**
- `SettingsScreen` 页脚无版本号（上游有 `版本 {currentVersion}`）

---

## 六、建议（按性价比排序，均未执行）

1. **修"关于应用"死按钮**（10 分钟）—— 上游就有，但用户会当成 App 坏了。
   至少给"关于应用"补真实内容（版本号、许可证、本地存储说明），
   顺带把上游丢掉的 `版本 {currentVersion}` 补回页脚。
2. **应用内升级：接线 or 删干净，二选一**（不可维持现状）。
   现状是"代码全在 + 按钮没有 + 配置为空"，三者都处于半成品状态。
   - 接线：需要一个 `UPDATE_MANIFEST_URL`（哪怕是自建的静态 JSON + 对象存储）
   - 删干净：删 §5.1 全部 + `InstallApkModule`，保留热更新崩溃计数复位
3. **重新考虑隐私政策页**（合规，不是功能）。
   精简版会把支付截图发给第三方 AI（OpenAI / DeepSeek / Qwen），
   `PrivacyPolicyScreen` 是在设置里唯一的对外披露位置，`dadb031` 把它当孤儿删了
   （确实无引用，但无引用 ≠ 不该存在）。若要恢复，放进 `GeneralSettingsScreen` 底部即可。
4. **§5.3 那批小孤儿**可一并清掉（约 11 KB），但收益很小。