import React from 'react';
import { Text, TouchableOpacity } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { ThemeProvider } from '../src/providers/ThemeProvider';
import { CategoryRuleLearningModal } from '../src/components/modals/CategoryRuleLearningModal';
import type { CategoryRuleSuggestion } from '../src/native';

function collectTexts(renderer: ReactTestRenderer.ReactTestRenderer): string[] {
  return renderer.root.findAllByType(Text).map(node => {
    const children = node.props.children;
    if (Array.isArray(children)) {
      return children.map(c => (c == null ? '' : String(c))).join('');
    }
    return children == null ? '' : String(children);
  });
}

async function renderInTheme(
  node: React.ReactElement,
  themeName: 'brutal' | 'soft' | 'hogwarts' = 'soft',
  mode: 'light' | 'dark' = 'light',
) {
  await AsyncStorage.setItem('@app_theme_name', themeName);
  await AsyncStorage.setItem('@app_theme_mode', mode);
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<ThemeProvider>{node}</ThemeProvider>);
  });
  const unmount = async () => {
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
    });
  };
  return { renderer, unmount };
}

describe('CategoryRuleLearningModal 渲染与交互', () => {
  test('新建模式（存在历史账单 + 锁定提示）展示 3 个按钮并触发正确回调', async () => {
    const onConfirm = jest.fn();
    const onDismiss = jest.fn();
    const suggestion: CategoryRuleSuggestion = {
      status: 'create',
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      categoryName: '咖啡',
      matchingHistoricalCount: 3,
      lockedSkippedCount: 1,
      suggestedPriority: 100,
    };

    const { renderer, unmount } = await renderInTheme(
      <CategoryRuleLearningModal
        visible
        suggestion={suggestion}
        categoryName="咖啡"
        categoryIcon="coffee"
        onConfirm={onConfirm}
        onDismiss={onDismiss}
      />,
      'soft',
      'light',
    );

    const texts = collectTexts(renderer);
    expect(texts).toContain('记住这个分类习惯？');
    expect(texts).toContain('瑞幸咖啡');
    expect(texts).toContain('咖啡');
    expect(texts).toContain('记住并更新历史 3 笔');
    expect(texts).toContain('仅记住，以后自动归类');
    expect(texts).toContain('暂不需要');
    expect(texts.some(t => t.includes('另有 1 笔你手动改过分类的历史账单将保持不变'))).toBe(true);

    const buttons = renderer.root.findAllByType(TouchableOpacity);
    const applyAllBtn = buttons.find(b => b.props.accessibilityLabel === '记住并更新历史 3 笔');
    const futureOnlyBtn = buttons.find(b => b.props.accessibilityLabel === '仅记住，以后自动归类');
    const dismissBtn = buttons.find(b => b.props.accessibilityLabel === '暂不需要');

    await ReactTestRenderer.act(async () => {
      applyAllBtn!.props.onPress();
      futureOnlyBtn!.props.onPress();
      dismissBtn!.props.onPress();
    });

    expect(onConfirm).toHaveBeenNthCalledWith(1, true);
    expect(onConfirm).toHaveBeenNthCalledWith(2, false);
    expect(onDismiss).toHaveBeenCalledTimes(1);
    await unmount();
  });

  test('新建模式无历史账单时只展示 2 个按钮，并兼容 brutal / hogwarts 主题', async () => {
    const onConfirm = jest.fn();
    const suggestion: CategoryRuleSuggestion = {
      status: 'create',
      merchant: '星巴克',
      categoryId: 'cat-coffee',
      categoryName: '咖啡',
      matchingHistoricalCount: 0,
      lockedSkippedCount: 0,
      suggestedPriority: 100,
    };

    for (const theme of ['brutal', 'hogwarts'] as const) {
      const { renderer, unmount } = await renderInTheme(
        <CategoryRuleLearningModal
          visible
          suggestion={suggestion}
          categoryName="咖啡"
          onConfirm={onConfirm}
          onDismiss={jest.fn()}
        />,
        theme,
        'dark',
      );
      const texts = collectTexts(renderer);
      expect(texts).toContain('以后自动归为「咖啡」');
      expect(texts).toContain('暂不需要');
      expect(texts).not.toContain('仅记住，以后自动归类');
      await unmount();
    }
  });

  test('更新模式展示原分类说明与更新文案', async () => {
    const suggestion: CategoryRuleSuggestion = {
      status: 'update',
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      categoryName: '咖啡',
      existingRuleId: 'rule-1',
      existingCategoryId: 'cat-food',
      existingCategoryName: '餐饮',
      matchingHistoricalCount: 2,
      lockedSkippedCount: 0,
      suggestedPriority: 80,
    };

    const { renderer, unmount } = await renderInTheme(
      <CategoryRuleLearningModal
        visible
        suggestion={suggestion}
        categoryName="咖啡"
        onConfirm={jest.fn()}
        onDismiss={jest.fn()}
      />,
      'soft',
      'dark',
    );

    const texts = collectTexts(renderer);
    expect(texts).toContain('更新自动分类规则？');
    expect(texts.some(t => t.includes('原规则会将「瑞幸咖啡」归为「餐饮」'))).toBe(true);
    expect(texts).toContain('更新规则并同步历史 2 笔');
    expect(texts).toContain('仅更新规则，不动历史');
    await unmount();
  });
});
