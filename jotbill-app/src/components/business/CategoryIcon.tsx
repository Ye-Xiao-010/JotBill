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
  type LucideIcon,
} from 'lucide-react-native';
import { useTheme } from '../../providers';

/** 后端默认分类使用的 lucide 图标名 → 组件映射（key 与数据库 icon 字段一致） */
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
};

/** 是否为可渲染的 lucide 图标名；false = 按 emoji 文本处理 */
export const isLucideIconName = (icon?: string | null): boolean =>
  Boolean(icon && icon in CATEGORY_LUCIDE_ICONS);

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
  strokeWidth = 2.5,
  color,
  fallback = '📝',
  emojiStyle,
}: CategoryIconProps) {
  const { colors } = useTheme();
  const Icon = isLucideIconName(icon)
    ? CATEGORY_LUCIDE_ICONS[icon as string]
    : null;

  if (Icon) {
    return <Icon size={size} strokeWidth={strokeWidth} color={color ?? colors.stroke} />;
  }
  return (
    <Text style={[{ fontSize: size }, emojiStyle]}>{icon || fallback}</Text>
  );
}
