/**
 * 哈利波特「魔法羊皮纸」矢量图标组件
 * 基于 react-native-svg 渲染 35 枚 512×512 单色路径，任意字号无锯齿且支持动态着色。
 */
import React from 'react';
import Svg, { Path } from 'react-native-svg';
import { HOGWARTS_ICON_PATHS } from '../../constants/hogwartsIcons';

export interface HogwartsIconProps {
  name: string;
  size?: number;
  color: string;
}

export default function HogwartsIcon({ name, size = 22, color }: HogwartsIconProps) {
  const paths = HOGWARTS_ICON_PATHS[name];
  if (!paths || paths.length === 0) {
    return null;
  }
  return (
    <Svg width={size} height={size} viewBox="0 0 512 512">
      {paths.map((d, index) => (
        <Path key={index} d={d} fill={color} />
      ))}
    </Svg>
  );
}
