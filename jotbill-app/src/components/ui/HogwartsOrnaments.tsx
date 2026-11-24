/**
 * 哈利波特「魔法羊皮纸」轻量矢量装饰组件
 * 使用 react-native-svg 绘制，零位图体积，自动跟随主题鎏金色与浅/深模式。
 */
import React from 'react';
import { View, type ViewStyle } from 'react-native';
import Svg, { Circle, Line, Path } from 'react-native-svg';

export function HogwartsStarDivider({
  color,
  width = 200,
  style,
}: {
  color: string;
  width?: number;
  style?: ViewStyle;
}) {
  return (
    <View style={[{ alignItems: 'center' }, style]}>
      <Svg width={width} height={20} viewBox="0 0 480 40" fill="none">
        <Line x1="12" y1="20" x2="188" y2="20" stroke={color} strokeWidth="2" opacity={0.65} />
        <Line x1="292" y1="20" x2="468" y2="20" stroke={color} strokeWidth="2" opacity={0.65} />
        <Path
          d="M148 20 C162 11 178 11 196 20 C178 29 162 29 148 20 Z"
          stroke={color}
          strokeWidth="1.6"
        />
        <Path
          d="M332 20 C318 11 302 11 284 20 C302 29 318 29 332 20 Z"
          stroke={color}
          strokeWidth="1.6"
        />
        <Path
          d="M240 4 L244 16 L256 20 L244 24 L240 36 L236 24 L224 20 L236 16 Z"
          fill={color}
        />
        <Circle cx="12" cy="20" r="3" fill={color} />
        <Circle cx="468" cy="20" r="3" fill={color} />
      </Svg>
    </View>
  );
}

export function HogwartsWaxSeal({
  size = 32,
  style,
}: {
  size?: number;
  style?: ViewStyle;
}) {
  return (
    <View style={style}>
      <Svg width={size} height={size} viewBox="0 0 128 128" fill="none">
        <Path
          d="M64 8 C75 6 86 12 94 18 C103 24 114 31 117 42 C121 53 119 64 117 75 C115 86 112 99 102 106 C92 113 80 116 68 119 C56 122 43 118 33 112 C23 106 14 96 11 84 C8 72 9 59 12 47 C15 35 21 23 31 17 C41 11 53 10 64 8 Z"
          fill="#8B1E1E"
        />
        <Circle cx="64" cy="64" r="44" fill="#A02828" stroke="#631313" strokeWidth="3" />
        <Circle
          cx="64"
          cy="64"
          r="37"
          fill="none"
          stroke="#D9A84E"
          strokeWidth="1.8"
          strokeDasharray="4 4"
          opacity={0.85}
        />
        <Path
          d="M64 34 L69.5 55.5 L91 61 L69.5 66.5 L64 88 L58.5 66.5 L37 61 L58.5 55.5 Z"
          fill="#F2D791"
        />
        <Circle cx="64" cy="61" r="6" fill="#A02828" stroke="#F2D791" strokeWidth="2" />
      </Svg>
    </View>
  );
}
