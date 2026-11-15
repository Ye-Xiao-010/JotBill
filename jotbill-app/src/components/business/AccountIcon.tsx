/**
 * 账户图标组件 —— 银行/品牌位图优先，subtype 线性图标兜底，最后 emoji。
 *
 * 全应用所有展示账户的地方都应使用本组件，保证同一账户在各处图标一致。
 * 位图解析见 `constants/accountIcons.ts`（名称别名自动匹配 + `bank:`/`brand:` 显式指定）。
 */
import React from 'react';
import { Image, Text } from 'react-native';
import {
  Banknote,
  CircleEllipsis,
  CreditCard,
  HandCoins,
  Landmark,
  Wallet,
  type LucideIcon,
} from 'lucide-react-native';
import { useTheme } from '../../providers';
import { resolveAccountIcon, type AccountIconMatch } from '../../constants/accountIcons';

type AccountSubtypeLike =
  | 'cash'
  | 'e_wallet'
  | 'bank_debit'
  | 'credit_card'
  | 'consumer_loan'
  | 'other'
  | string;

const SUBTYPE_ICONS: Record<string, LucideIcon> = {
  cash: Banknote,
  e_wallet: Wallet,
  bank_debit: Landmark,
  credit_card: CreditCard,
  consumer_loan: HandCoins,
  other: CircleEllipsis,
};

/**
 * 账户 `icon` 字段的旧数据是 emoji（如 💵）；显式图标用 `bank:` / `brand:` 前缀区分。
 * 不用 Unicode Property regex（`\p{...}`）判断 —— Hermes 对它的支持不稳，
 * 而账户图标只有「前缀式」与「旧 emoji」两种形态，判前缀足够。
 */
const isLegacyTextIcon = (value?: string | null): boolean =>
  Boolean(value && !value.includes(':'));

export interface AccountIconProps {
  /** 账户 icon 字段（可空） */
  icon?: string | null;
  /** 账户名称，用于银行别名自动匹配 */
  name?: string | null;
  /** 账户 subtype，位图未命中时的线性图标选择依据 */
  subtype?: AccountSubtypeLike;
  /** 位图/矢量尺寸 */
  size?: number;
  /** 线性图标颜色，默认主题描边色；品牌/银行位图不受影响 */
  color?: string;
}

export default function AccountIcon({
  icon,
  name,
  subtype,
  size = 22,
  color,
}: AccountIconProps) {
  const { colors } = useTheme();
  const match: AccountIconMatch | null = resolveAccountIcon(icon, name);

  if (match) {
    return (
      <Image
        source={match.source}
        style={{ width: size, height: size }}
        resizeMode="contain"
        accessibilityIgnoresInvertColors
      />
    );
  }

  if (isLegacyTextIcon(icon)) {
    return (
      <Text style={{ fontSize: size * 0.9, lineHeight: size * 1.1 }}>{icon}</Text>
    );
  }

  const Icon = SUBTYPE_ICONS[subtype ?? 'other'] ?? CircleEllipsis;
  return <Icon size={size} strokeWidth={2.5} color={color ?? colors.stroke} />;
}
