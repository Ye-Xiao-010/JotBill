/**
 * AssetCompositionChart 与分类缓存失效测试
 *
 * 覆盖：
 * 1. 默认分类带 lucide 英文 icon 时，图例与扇区中心仅显示中文分类名、不显示英文；
 * 2. 用户新增自定义分类后直接按数据库返回名称动态匹配显示，金额 ≥1 万用「万」；
 * 3. 无分类账单兜底显示「未分类」；
 * 4. 分类增删改后 invalidateCache.categories() 同步失效资产构成相关查询。
 */
import React from 'react';
import { Text, View } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { ThemeProvider } from '../src/providers/ThemeProvider';
import AssetCompositionChart from '../src/components/reports/AssetCompositionChart';
import { invalidateCache, queryClient } from '../src/lib/queryClient';
import type { ReportCategoryStatistic } from '../src/utils/nativeStatistics';

interface MockPieSlice {
  onPress?: () => void;
}

interface MockPieChartProps {
  data: MockPieSlice[];
  centerLabelComponent?: () => React.ReactNode;
}

const capturedPieProps: MockPieChartProps[] = [];

jest.mock('react-native-gifted-charts', () => {
  const ReactActual = require('react');
  const { View: RNView } = require('react-native');
  return {
    PieChart: (props: MockPieChartProps) => {
      capturedPieProps.push(props);
      return ReactActual.createElement(
        RNView,
        { testID: 'mock-pie-chart' },
        props.centerLabelComponent ? props.centerLabelComponent() : null,
      );
    },
  };
});

function collectTexts(renderer: ReactTestRenderer.ReactTestRenderer): string[] {
  return renderer.root
    .findAllByType(Text)
    .map((node) => {
      const children = node.props.children;
      if (Array.isArray(children)) {
        return children.map((c) => (c == null ? '' : String(c))).join('');
      }
      return children == null ? '' : String(children);
    });
}

async function renderChart(
  expenseCategoryStats: ReportCategoryStatistic[],
  incomeCategoryStats: ReportCategoryStatistic[] = [],
) {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <ThemeProvider>
        <View>
          <AssetCompositionChart
            expenseCategoryStats={expenseCategoryStats}
            incomeCategoryStats={incomeCategoryStats}
            dateRange="2025.12 - 2026.11"
          />
        </View>
      </ThemeProvider>,
    );
  });
  return renderer;
}

describe('AssetCompositionChart', () => {
  beforeEach(() => {
    capturedPieProps.length = 0;
    jest.restoreAllMocks();
  });

  it('默认分类仅显示中文名称，不渲染英文图标名（含点击扇区中心）', async () => {
    const renderer = await renderChart([
      {
        categoryId: 'default-category-expense-food',
        categoryName: '餐饮',
        categoryIcon: 'utensils',
        amount: 600,
        percentage: 60,
        count: 5,
      },
      {
        categoryId: 'default-category-expense-shopping',
        categoryName: '购物',
        categoryIcon: 'shopping-bag',
        amount: 400,
        percentage: 40,
        count: 2,
      },
    ]);

    const initialTexts = collectTexts(renderer);
    expect(initialTexts).toContain('餐饮');
    expect(initialTexts).toContain('购物');
    expect(initialTexts.join(' ')).not.toMatch(/[a-zA-Z]/);

    // 点击第一个扇区，验证饼图中心同样只显示金额与中文分类名
    expect(capturedPieProps.length).toBeGreaterThan(0);
    await ReactTestRenderer.act(async () => {
      capturedPieProps[0].data[0].onPress?.();
    });

    const focusedTexts = collectTexts(renderer);
    expect(focusedTexts.filter((t) => t === '餐饮').length).toBe(2);
    expect(focusedTexts.join(' ')).not.toMatch(/[a-zA-Z]/);
  });

  it('用户新增自定义分类动态匹配显示，且大额使用中文单位「万」', async () => {
    const renderer = await renderChart(
      [
        {
          categoryId: 'custom-uuid-coffee',
          categoryName: '手冲咖啡',
          categoryIcon: '🍔',
          amount: 15000,
          percentage: 75,
          count: 10,
        },
        {
          categoryId: 'custom-uuid-fitness',
          categoryName: '健身私教',
          categoryIcon: '🏋️',
          amount: 5000,
          percentage: 25,
          count: 3,
        },
      ],
      [
        {
          categoryId: 'custom-uuid-side-income',
          categoryName: '副业稿费',
          categoryIcon: '💰',
          amount: 20000,
          percentage: 100,
          count: 1,
        },
      ],
    );

    const texts = collectTexts(renderer);
    expect(texts).toContain('手冲咖啡');
    expect(texts).toContain('健身私教');
    expect(texts).toContain('副业稿费');
    expect(texts).toContain('¥1.5万');
    expect(texts).toContain('¥2.0万');
    expect(texts.join(' ')).not.toMatch(/[a-zA-Z]/);
    expect(texts).not.toContain('🍔');
    expect(texts).not.toContain('🏋️');
    expect(texts).not.toContain('💰');
  });

  it('无分类账单（categoryName 为空）兜底显示「未分类」', async () => {
    const renderer = await renderChart([
      {
        categoryId: '',
        categoryName: '   ',
        categoryIcon: undefined,
        amount: 88,
        percentage: 100,
        count: 1,
      },
    ]);

    const texts = collectTexts(renderer);
    expect(texts).toContain('未分类');
    expect(texts.join(' ')).not.toMatch(/[a-zA-Z]/);
  });
});

describe('invalidateCache.categories', () => {
  it('分类变更时同步失效资产类型构成查询缓存', () => {
    const spy = jest.spyOn(queryClient, 'invalidateQueries').mockResolvedValue();

    invalidateCache.categories();

    const invalidatedKeys = spy.mock.calls.map((call) => call[0]?.queryKey);
    expect(invalidatedKeys).toContainEqual(['categories']);
    expect(invalidatedKeys).toContainEqual(['asset-trend']);
    expect(invalidatedKeys).toContainEqual(['total-assets']);
    expect(invalidatedKeys).toContainEqual(['statistics']);
  });
});
