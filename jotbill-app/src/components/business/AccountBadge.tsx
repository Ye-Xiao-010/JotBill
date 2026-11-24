/**
 * 账户彩色微标组件 —— 展示扣款/入账账户的真实图标、简称与尾号。
 *
 * 用于首页近期交易与统计页账单明细，复用全局 `<AccountIcon />` 保证各页面图标一致，
 * 并通过颜色、资产实线 / 负债虚线边框与主题圆角/字体在 6 套主题 × 浅深模式下保持清晰区分。
 */
import React from 'react';
import {
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { useTheme } from '../../providers';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import {
  formatAccountBadgeParts,
  resolveAccountBadgeStyle,
  type AccountBadgeInput,
} from '../../constants/accountStyles';
import AccountIcon from './AccountIcon';

export interface AccountBadgeProps {
  account: AccountBadgeInput;
  /** 紧凑模式：用于统计页分类下钻等较窄容器 */
  compact?: boolean;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}

export default function AccountBadge({
  account,
  compact = false,
  style,
  testID,
}: AccountBadgeProps) {
  const { shape, isDark, isSoft, isHogwarts, styleVariant } = useTheme();
  const badgeStyle = resolveAccountBadgeStyle(account, {
    variant: styleVariant,
    isDark,
  });
  const parts = formatAccountBadgeParts(account);

  const iconSize = compact ? 11 : 13;
  const radius = isSoft ? shape.radius.pill : shape.radius.tag;
  const fontSize = compact ? 10 : 10.5;
  const lineHeight = compact ? 13 : 14;
  const fontWeight = isHogwarts ? undefined : isSoft ? '600' : '700';

  return (
    <View
      testID={testID}
      accessibilityLabel={`扣款账户 ${parts.fullLabel}`}
      style={[
        styles.badge,
        compact ? styles.badgeCompact : styles.badgeRegular,
        {
          borderRadius: radius,
          backgroundColor: badgeStyle.backgroundColor,
          borderColor: badgeStyle.borderColor,
          borderStyle: badgeStyle.borderStyle,
        },
        style,
      ]}
    >
      <View
        style={[
          styles.iconWrap,
          badgeStyle.bitmapBackdropColor
            ? [
                styles.bitmapBackdrop,
                {
                  width: iconSize + 3,
                  height: iconSize + 3,
                  backgroundColor: badgeStyle.bitmapBackdropColor,
                },
              ]
            : null,
        ]}
      >
        <AccountIcon
          icon={account.icon}
          name={account.name}
          subtype={account.subtype}
          size={iconSize}
          color={badgeStyle.textColor}
        />
      </View>

      <Text
        style={[
          styles.name,
          {
            color: badgeStyle.textColor,
            fontSize,
            lineHeight,
            fontWeight,
            fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
          },
        ]}
        numberOfLines={1}
        ellipsizeMode="tail"
      >
        {parts.name}
      </Text>

      {parts.tail ? (
        <Text
          style={[
            styles.tail,
            {
              color: badgeStyle.textColor,
              fontSize,
              lineHeight,
              fontWeight,
              fontFamily: isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
            },
          ]}
          numberOfLines={1}
        >
          {`· ${parts.tail}`}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    alignSelf: 'flex-end',
  },
  badgeRegular: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    gap: 4,
    maxWidth: 148,
  },
  badgeCompact: {
    paddingHorizontal: 5,
    paddingVertical: 1.5,
    gap: 3,
    maxWidth: 124,
  },
  iconWrap: {
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  },
  bitmapBackdrop: {
    borderRadius: 4,
  },
  name: {
    flexShrink: 1,
  },
  tail: {
    flexShrink: 0,
    fontVariant: ['tabular-nums'],
  },
});
