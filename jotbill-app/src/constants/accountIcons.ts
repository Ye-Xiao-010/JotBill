/**
 * 账户图标解析 —— 银行/品牌图标资产 + 名称自动匹配。
 *
 * 图标来源 `public/icons/accounts/`（512×512 透明底 PNG，源自 `icons/banks` 与
 * `icons/brand`）。RN 的 require 必须是静态字面量，故这里手工登记映射表；
 * 新增银行时同时补 PNG 与本表两个键。
 *
 * 匹配口径（与 `icons/banks/banks.json` 的 aliases 同源）：
 * 1. 账户 `icon` 字段形如 `bank:<code>` / `brand:<code>` ⇒ 直接命中（用户显式指定优先）；
 * 2. 账户名包含任一别名（如「招商银行」「招行」）⇒ 命中该银行；
 * 3. 名字含「微信 / 支付宝」⇒ 品牌图标；
 * 4. 都不中 ⇒ 由调用方按 subtype 回退 lucide 线性图标。
 */
import type { ImageSourcePropType } from 'react-native';
import banksJson from './banks.json';

type BankRecord = {
  code: string;
  name: string;
  shortName: string;
  aliases: string[];
};

export const BANKS: BankRecord[] = banksJson as BankRecord[];

/** 银行 code → 图标资源。键必须与 `public/icons/accounts/<code>.png` 一致。 */
const BANK_ICON_SOURCES: Record<string, ImageSourcePropType> = {
  icbc: require('../../public/icons/accounts/icbc.png'),
  ccb: require('../../public/icons/accounts/ccb.png'),
  abchina: require('../../public/icons/accounts/abchina.png'),
  boc: require('../../public/icons/accounts/boc.png'),
  bankcomm: require('../../public/icons/accounts/bankcomm.png'),
  psbc: require('../../public/icons/accounts/psbc.png'),
  cmbchina: require('../../public/icons/accounts/cmbchina.png'),
  citicbank: require('../../public/icons/accounts/citicbank.png'),
  spdb: require('../../public/icons/accounts/spdb.png'),
  cmbc: require('../../public/icons/accounts/cmbc.png'),
  cib: require('../../public/icons/accounts/cib.png'),
  cebbank: require('../../public/icons/accounts/cebbank.png'),
  hxb: require('../../public/icons/accounts/hxb.png'),
  cgbchina: require('../../public/icons/accounts/cgbchina.png'),
  pingan: require('../../public/icons/accounts/pingan.png'),
  cbhb: require('../../public/icons/accounts/cbhb.png'),
  hfbank: require('../../public/icons/accounts/hfbank.png'),
  czbank: require('../../public/icons/accounts/czbank.png'),
  bankofbeijing: require('../../public/icons/accounts/bankofbeijing.png'),
  bosc: require('../../public/icons/accounts/bosc.png'),
  jsbchina: require('../../public/icons/accounts/jsbchina.png'),
  nbcb: require('../../public/icons/accounts/nbcb.png'),
  njcb: require('../../public/icons/accounts/njcb.png'),
};

/** 品牌 code → 图标资源（微信 / 支付宝）。 */
const BRAND_ICON_SOURCES: Record<string, ImageSourcePropType> = {
  wechat: require('../../public/icons/accounts/wechat.png'),
  alipay: require('../../public/icons/accounts/alipay.png'),
};

/** 品牌名前缀匹配（账户名通常叫「微信零钱」「支付宝」）。 */
const BRAND_NAME_HINTS: Array<{ keyword: string; code: string }> = [
  { keyword: '微信', code: 'wechat' },
  { keyword: '支付宝', code: 'alipay' },
];

/** 名称别名匹配的最短长度：单字别名（如「中」）误匹配面太大，不参与包含匹配。 */
const MIN_ALIAS_LENGTH = 2;

export type AccountIconMatch =
  | { kind: 'bank'; code: string; source: ImageSourcePropType; shortName: string }
  | { kind: 'brand'; code: string; source: ImageSourcePropType };

function matchBankByName(name: string): AccountIconMatch | null {
  const trimmed = name.trim();
  if (!trimmed) return null;
  for (const bank of BANKS) {
    const candidates = [bank.name, bank.shortName, ...bank.aliases].filter(
      alias => alias.length >= MIN_ALIAS_LENGTH,
    );
    if (candidates.some(alias => trimmed.includes(alias))) {
      const source = BANK_ICON_SOURCES[bank.code];
      if (source) {
        return { kind: 'bank', code: bank.code, source, shortName: bank.shortName };
      }
    }
  }
  return null;
}

function matchBrandByName(name: string): AccountIconMatch | null {
  const trimmed = name.trim();
  if (!trimmed) return null;
  for (const brand of BRAND_NAME_HINTS) {
    if (trimmed.includes(brand.keyword)) {
      const source = BRAND_ICON_SOURCES[brand.code];
      if (source) return { kind: 'brand', code: brand.code, source };
    }
  }
  return null;
}

/**
 * 解析账户应显示的位图图标；无命中返回 null（调用方回退 lucide/emoji）。
 *
 * @param icon 账户的 icon 字段，支持 `bank:<code>` / `brand:<code>` 显式指定
 * @param name 账户名称，用于别名自动匹配
 */
export function resolveAccountIcon(
  icon: string | null | undefined,
  name: string | null | undefined,
): AccountIconMatch | null {
  const explicit = icon?.trim();
  if (explicit) {
    const [scope, code] = explicit.split(':', 2);
    if (scope === 'bank' && code && BANK_ICON_SOURCES[code]) {
      const bank = BANKS.find(item => item.code === code);
      return {
        kind: 'bank',
        code,
        source: BANK_ICON_SOURCES[code],
        shortName: bank?.shortName ?? code,
      };
    }
    if (scope === 'brand' && code && BRAND_ICON_SOURCES[code]) {
      return { kind: 'brand', code, source: BRAND_ICON_SOURCES[code] };
    }
  }
  const byName = name ? matchBankByName(name) ?? matchBrandByName(name) : null;
  return byName;
}

/** 空间紧张处的显示名：命中银行时用简称（「招商银行」→「招商银行」不变，
 *  「中国工商银行」→「工商银行」），其余用原名。 */
export function accountDisplayName(
  name: string,
  match: AccountIconMatch | null,
): string {
  return match?.kind === 'bank' ? match.shortName : name;
}
