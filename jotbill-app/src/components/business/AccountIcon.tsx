/**
 * 账户图标组件 —— 银行/品牌位图优先，subtype 线性图标兜底，最后 emoji。
 *
 * 全应用所有展示账户的地方都应使用本组件，保证同一账户在各处图标一致。
 * 位图解析见 `constants/accountIcons.ts`（名称别名自动匹配 + `bank:`/`brand:` 显式指定）。
 */
import React from 'react';
import { Image, Text, View, type StyleProp, type ViewStyle } from 'react-native';
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

/** 账户 `icon` 字段存的是 emoji 时原样渲染（兼容旧数据）。 */
const isEmoji = (value?: string | null): boolean =>
  Boolean(value && !value.includes(':') && /\p{Extended_Pictographic}/u.test(value));

export interface AccountIconProps {
  /** 账户 icon 字段（可空） */
  icon?: string | null;
  /** 账户名称，用于银行别名自动匹配 */
  name?: string | null;
  /** 账户 subtype，位图未命中时的线性图标选择依据 */
  subtype?: AccountSubtypeLike;
  /** 位图/矢量尺寸 */
  size?: number;
  /** 线性图标颜色，默认主题描边色；品牌位图不受影响 */
  color?: string;
  /** 位图容器的圆角与边框（大图标位使用） */
  framed?: boolean;
  /** 容器样式（framed 时生效） */
  containerStyle?: StyleProp<ViewStyle>;
}

export default function AccountIcon({
  icon,
  name,
  subtype,
  size = 22,
  color,
  framed = false,
  containerStyle,
}: AccountIconProps) {
  const { colors } = useTheme();
  const match: AccountIconMatch | null = resolveAccountIcon(icon, name);

  const content = match ? (
    <Image
      source={match.source}
      style={{ width: size, height: size }}
      resizeMode="contain"
      accessibilityIgnoresInvertColors
    />
  ) : isEmoji(icon) ? (
    <Text style={{ fontSize: size * 0.9, lineHeight: size * 1.1 }}>{icon}</Text>
  ) : (
    (() => {
      const Icon = SUBTYPE_ICONS[subtype ?? 'other'] ?? CircleEllipsis;
      return <Icon size={size} strokeWidth={2.5} color={color ?? colors.stroke} />;
    })()
  );

  if (!framed) return content;

  return (
    <View
      style={[
        {
          width: size * 1.9,
          height: size * 1.9,
          borderRadius: size * 0.6,
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: colors.surface,
          borderWidth: 1.5,
          borderColor: colors.stroke,
        },
        containerStyle,
      ]}
    >
      {content}
    </View>
  );
}
