/**
 * SoftGradient - 基于 react-native-svg 的轻量渐变背景容器
 *
 * 复用项目已安装的 react-native-svg，无需引入新的原生依赖或 Jest mock。
 * 用于柔光主题的首页 Hero 卡、净资产主卡与圆形渐变 FAB。
 */
import React, { useId } from 'react';
import { View, StyleSheet, type StyleProp, type ViewStyle } from 'react-native';
import Svg, { Defs, LinearGradient, RadialGradient, Rect, Circle, Stop } from 'react-native-svg';

export interface SoftGradientProps {
  colors: readonly [string, string] | readonly [string, string, string];
  style?: StyleProp<ViewStyle>;
  children?: React.ReactNode;
  /** 可选柔和光晕装饰（Hero 主卡） */
  glow?: boolean;
}

export default function SoftGradient({
  colors,
  style,
  children,
  glow = false,
}: SoftGradientProps) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const gradId = `sg_${uid}`;
  const glowTopId = `gt_${uid}`;
  const glowBotId = `gb_${uid}`;

  const stops =
    colors.length === 3
      ? [
          { offset: '0%', color: colors[0] },
          { offset: '48%', color: colors[1] },
          { offset: '100%', color: colors[2] },
        ]
      : [
          { offset: '0%', color: colors[0] },
          { offset: '100%', color: colors[1] },
        ];

  return (
    <View style={[styles.container, style]}>
      <Svg
        style={StyleSheet.absoluteFill}
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        pointerEvents="none"
      >
        <Defs>
          <LinearGradient id={gradId} x1="0%" y1="0%" x2="100%" y2="100%">
            {stops.map((s) => (
              <Stop key={s.offset} offset={s.offset} stopColor={s.color} />
            ))}
          </LinearGradient>
          {glow ? (
            <>
              <RadialGradient id={glowTopId} cx="88%" cy="8%" r="55%">
                <Stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.22" />
                <Stop offset="100%" stopColor="#FFFFFF" stopOpacity="0" />
              </RadialGradient>
              <RadialGradient id={glowBotId} cx="8%" cy="94%" r="48%">
                <Stop offset="0%" stopColor="#FFC45C" stopOpacity="0.26" />
                <Stop offset="100%" stopColor="#FFC45C" stopOpacity="0" />
              </RadialGradient>
            </>
          ) : null}
        </Defs>
        <Rect x="0" y="0" width="100" height="100" fill={`url(#${gradId})`} />
        {glow ? (
          <>
            <Circle cx="88" cy="8" r="55" fill={`url(#${glowTopId})`} />
            <Circle cx="8" cy="94" r="48" fill={`url(#${glowBotId})`} />
          </>
        ) : null}
      </Svg>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
  },
});
