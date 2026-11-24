/**
 * 分类图标组件 - lucide 图标渲染 + emoji 回退
 * 默认分类的 icon 存 lucide 图标名（kebab-case），自定义分类/旧数据仍是 emoji：
 * 能匹配到映射就画矢量图标（统一 strokeWidth 2.5），否则按 emoji 文本渲染
 */
import React from 'react';
import { Text, type TextProps } from 'react-native';
import {
  Utensils,
  Bus,
  ShoppingBag,
  Gamepad2,
  Pill,
  BookOpen,
  House,
  CircleEllipsis,
  Banknote,
  Trophy,
  ChartLine,
  Laptop,
  CircleDollarSign,
  Smartphone,
  ShoppingBasket,
  PartyPopper,
  Plane,
  PawPrint,
  Package,
  Briefcase,
  TrendingUp,
  Gift,
  HandCoins,
  RotateCcw,
  Receipt,
  type LucideIcon,
} from 'lucide-react-native';
import { useTheme } from '../../providers';
import HogwartsIcon from '../icons/HogwartsIcon';
import { hasHogwartsIcon } from '../../constants/hogwartsIcons';

/** 默认分类的 lucide 图标名 → 组件映射（key 与数据库 icon 字段一致）。
 * 2026-10-27 起本地种子（DefaultDataInitializer）与后端种子共用本表：
 * 前 13 个来自后端种子（0a80e72），其余为本地特有分类新增。 */
export const CATEGORY_LUCIDE_ICONS: { [key: string]: LucideIcon } = {
  utensils: Utensils,
  bus: Bus,
  'shopping-bag': ShoppingBag,
  'gamepad-2': Gamepad2,
  pill: Pill,
  'book-open': BookOpen,
  house: House,
  'circle-ellipsis': CircleEllipsis,
  banknote: Banknote,
  trophy: Trophy,
  'chart-line': ChartLine,
  laptop: Laptop,
  'circle-dollar-sign': CircleDollarSign,
  smartphone: Smartphone,
  'shopping-basket': ShoppingBasket,
  'party-popper': PartyPopper,
  plane: Plane,
  'paw-print': PawPrint,
  package: Package,
  briefcase: Briefcase,
  'trending-up': TrendingUp,
  gift: Gift,
  'hand-coins': HandCoins,
  'rotate-ccw': RotateCcw,
  receipt: Receipt,
};

/** 是否为可渲染的 lucide 图标名；false = 按 emoji 文本处理 */
export const isLucideIconName = (icon?: string | null): boolean =>
  Boolean(icon && icon in CATEGORY_LUCIDE_ICONS);

/**
 * 是否可作为 emoji 文本渲染。纯 ASCII 标识符（如未登记的 lucide 名
 * `shopping-bag`）不当作图标字符 —— 否则界面上会出现"一行字母"。
 */
const isEmojiText = (value?: string | null): boolean =>
  Boolean(value && !/^[\x20-\x7E]+$/.test(value.trim()));

interface CategoryIconProps {
  icon?: string | null;
  /** 矢量图标尺寸；emoji 回退时以其为 fontSize */
  size?: number;
  strokeWidth?: number;
  /** 图标颜色，默认跟随主题描边色 */
  color?: string;
  /** icon 为空时显示的 emoji */
  fallback?: string;
  /** emoji 回退时套用的文本样式（矢量分支不适用） */
  emojiStyle?: TextProps['style'];
}

export default function CategoryIcon({
  icon,
  size = 22,
  strokeWidth,
  color,
  fallback = '📝',
  emojiStyle,
}: CategoryIconProps) {
  const { colors, isSoft, isHogwarts } = useTheme();
  const resolvedStroke = strokeWidth ?? (isSoft ? 2.1 : 2.5);
  const resolvedColor = color ?? (isSoft ? colors.textPrimary : colors.stroke);

  if (isHogwarts && hasHogwartsIcon(icon)) {
    return (
      <HogwartsIcon
        name={icon}
        size={size}
        color={color ?? colors.textPrimary}
      />
    );
  }
  const Icon = isLucideIconName(icon)
    ? CATEGORY_LUCIDE_ICONS[icon as string]
    : null;

  if (Icon) {
    return <Icon size={size} strokeWidth={resolvedStroke} color={resolvedColor} />;
  }
  return (
    <Text style={[{ fontSize: size }, emojiStyle]}>
      {isEmojiText(icon) ? icon : fallback}
    </Text>
  );
}
