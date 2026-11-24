/**
 * 哈利波特「魔法羊皮纸」主题静态图像与字体资产注册表
 *
 * RN 的 require() 必须使用静态字面量，集中在此管理，避免各页面散落相对路径。
 */
import type { ImageSourcePropType } from 'react-native';

export type HogwartsHouse =
  | 'classic'
  | 'gryffindor'
  | 'slytherin'
  | 'ravenclaw'
  | 'hufflepuff';

export const HOGWARTS_HOUSE_ORDER: readonly HogwartsHouse[] = [
  'classic',
  'gryffindor',
  'slytherin',
  'ravenclaw',
  'hufflepuff',
];

export interface HogwartsHouseMeta {
  id: HogwartsHouse;
  label: string;
  shortLabel: string;
  motto: string;
  crest: ImageSourcePropType;
  swatchPrimary: string;
  swatchAccent: string;
}

export const HOGWARTS_IMAGES = {
  heroLight: require('../../public/themes/hogwarts/hero-parchment-light.jpg') as ImageSourcePropType,
  heroDark: require('../../public/themes/hogwarts/hero-great-hall-dark.jpg') as ImageSourcePropType,
  aiMascot: require('../../public/themes/hogwarts/ai-wizard-luckycat.jpg') as ImageSourcePropType,
  emptyOwl: require('../../public/themes/hogwarts/empty-owl-ledger.jpg') as ImageSourcePropType,
  themeCover: require('../../public/themes/hogwarts/theme-cover-ledger.jpg') as ImageSourcePropType,
  parchmentTexture: require('../../public/themes/hogwarts/parchment-cream.jpg') as ImageSourcePropType,
  crestCrimsonLion: require('../../public/themes/hogwarts/crest-crimson-lion.jpg') as ImageSourcePropType,
  crestEmeraldSerpent: require('../../public/themes/hogwarts/crest-emerald-serpent.jpg') as ImageSourcePropType,
  crestSapphireEagle: require('../../public/themes/hogwarts/crest-sapphire-eagle.jpg') as ImageSourcePropType,
  crestAmberBadger: require('../../public/themes/hogwarts/crest-amber-badger.jpg') as ImageSourcePropType,
} as const;

export const HOGWARTS_HOUSES: Record<HogwartsHouse, HogwartsHouseMeta> = {
  classic: {
    id: 'classic',
    label: '霍格沃茨古金',
    shortLabel: '古金',
    motto: '羊皮纸与星光鎏金',
    crest: HOGWARTS_IMAGES.aiMascot,
    swatchPrimary: '#735318',
    swatchAccent: '#C9A227',
  },
  gryffindor: {
    id: 'gryffindor',
    label: '格兰芬多',
    shortLabel: '格兰芬多',
    motto: '绯红鎏金 · 勇气与火',
    crest: HOGWARTS_IMAGES.crestCrimsonLion,
    swatchPrimary: '#821B1F',
    swatchAccent: '#C9A227',
  },
  slytherin: {
    id: 'slytherin',
    label: '斯莱特林',
    shortLabel: '斯莱特林',
    motto: '祖母绿银 · 精明自制',
    crest: HOGWARTS_IMAGES.crestEmeraldSerpent,
    swatchPrimary: '#195434',
    swatchAccent: '#8D99AE',
  },
  ravenclaw: {
    id: 'ravenclaw',
    label: '拉文克劳',
    shortLabel: '拉文克劳',
    motto: '星夜蓝铜 · 智慧洞见',
    crest: HOGWARTS_IMAGES.crestSapphireEagle,
    swatchPrimary: '#1D3975',
    swatchAccent: '#B0793A',
  },
  hufflepuff: {
    id: 'hufflepuff',
    label: '赫奇帕奇',
    shortLabel: '赫奇帕奇',
    motto: '琥珀黑金 · 勤勉公正',
    crest: HOGWARTS_IMAGES.crestAmberBadger,
    swatchPrimary: '#7D510D',
    swatchAccent: '#D99B26',
  },
};

/**
 * 魔法羊皮纸主题自定义字体族（均位于 android/app/src/main/assets/fonts/，SIL OFL 1.1）
 * 注意：Android RN 对单字重 TTF 按文件名精确查找；使用自定义 fontFamily 时避免叠加不匹配的 fontWeight。
 */
export const HOGWARTS_FONTS = {
  display: 'ZCOOLXiaoWei-Regular',
  body: 'LXGWWenKaiLite-Regular',
  numeric: 'Cinzel-Variable',
  ornate: 'CinzelDecorative-Bold',
} as const;
