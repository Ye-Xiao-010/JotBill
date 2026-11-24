/**
 * QueryErrorBanner - 查询失败提示条 - Neo-Brutalism 风格
 *
 * ⚠️ 为什么需要它：`EmptyState` 表达的是"确实没有数据"，
 * 而查询失败时页面此前一律渲染空态 —— 数据库桥接异常、SQL 抛错、
 * 原生 Promise reject，用户看到的是"当天暂无记录""净资产 ¥0.00"，
 * 也就是**故障被伪装成了事实**。
 *
 * 这比崩溃更危险：用户据此认为"我今天没花钱"，而真实原因可能是读取失败。
 * 且不可自愈（下拉刷新也是同一条失败路径），用户无从察觉。
 *
 * 有 error 就渲染失败态 + 重试按钮；没有则不渲染任何东西，
 * 调用方无需写条件分支。
 */
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderWidth } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import BrutalPressable from './BrutalPressable';

export interface QueryErrorBannerProps {
  /** 查询错误对象；falsy 时整个组件返回 null。 */
  error: unknown;
  /** 错误标题，默认"数据加载失败"。 */
  title?: string;
  /** 错误原始信息。会自动做长度截断，避免原生堆栈撑爆布局。 */
  message?: string;
  /** 重试回调；提供时显示按钮。 */
  onRetry?: () => void;
  retryLabel?: string;
  /** 额外补充说明（例如"这不代表你真的没有记录"）。 */
  hint?: string;
  /** 覆盖默认外边距（内嵌到已有 padding 容器里时使用）。 */
  style?: object;
}

const MAX_MESSAGE_LENGTH = 120;

/** 把任意 throw 出来的值压成一行可读文本。 */
function describeError(error: unknown): string {
  let raw: string;
  if (error instanceof Error) {
    raw = error.message;
  } else if (typeof error === 'string') {
    raw = error;
  } else if (error && typeof error === 'object') {
    const candidate = error as { message?: unknown; code?: unknown };
    raw =
      typeof candidate.message === 'string'
        ? candidate.message
        : typeof candidate.code === 'string'
          ? candidate.code
          : JSON.stringify(error);
  } else {
    raw = String(error);
  }
  // 换行会把固定高度的警示条撑破，压成单行
  const singleLine = raw.replace(/\s+/g, ' ').trim();
  if (!singleLine) return '未知错误';
  return singleLine.length > MAX_MESSAGE_LENGTH
    ? `${singleLine.slice(0, MAX_MESSAGE_LENGTH)}…`
    : singleLine;
}

const QueryErrorBanner: React.FC<QueryErrorBannerProps> = ({
  error,
  title = '数据加载失败',
  message,
  onRetry,
  retryLabel = '重试',
  hint,
  style,
}) => {
  const styles = useStyles(createStyles);

  if (!error) return null;

  return (
    <View style={[styles.container, style]}>
      <View style={styles.header}>
        <Text style={styles.icon}>⚠️</Text>
        <Text style={styles.title}>{title}</Text>
      </View>
      <Text style={styles.message}>{message ?? describeError(error)}</Text>
      {hint ? <Text style={styles.hint}>{hint}</Text> : null}
      {onRetry ? (
        <BrutalPressable
          onPress={onRetry}
          style={styles.button}
          shadowOffset={2}
          shadowColor={styles._colors.stroke}
        >
          <Text style={styles.buttonText}>{retryLabel}</Text>
        </BrutalPressable>
      ) : null}
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      margin: spacing.lg,
      marginBottom: 0,
      padding: spacing.md,
      backgroundColor: shape.isSoft ? colors.expenseLight : colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: colors.error,
      ...(shape.isHogwarts ? shape.elevation.card : {}),
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    icon: {
      fontSize: 16,
    },
    title: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: shape.isSoft ? colors.expenseStrong : colors.error,
      flex: 1,
    },
    message: {
      marginTop: spacing.sm,
      fontSize: 12,
      lineHeight: 17,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    hint: {
      marginTop: spacing.xs,
      fontSize: 12,
      lineHeight: 17,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.expenseStrong : colors.error,
    },
    button: {
      alignSelf: 'flex-start',
      marginTop: spacing.md,
      backgroundColor: colors.primary,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
    },
    buttonText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.onPrimary,
    },
  }),
  _colors: colors,
});

export default QueryErrorBanner;
