/**
 * 统计报表主屏幕 - Neo-Brutalism 风格
 * 双 Tab 架构：资产 | 收支
 */
import React, { useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import { SegmentedControl } from '../../components/ui';
import AssetsTab from './AssetsTab';
import IncomeExpenseTab from './IncomeExpenseTab';
import SoftIncomeExpenseView from './SoftIncomeExpenseView';
import SoftAssetsView from './SoftAssetsView';

type TopTab = 'assets' | 'incomeExpense';

const TOP_TAB_OPTIONS = [
  { key: 'assets', label: '资产' },
  { key: 'incomeExpense', label: '收支' },
];

const SOFT_TOP_TAB_OPTIONS = [
  { key: 'incomeExpense', label: '收支' },
  { key: 'assets', label: '资产' },
];

export default function ReportsScreen() {
  const styles = useStyles(createStyles);
  const { isSoft } = useTheme();
  const [topTab, setTopTab] = useState<TopTab>('incomeExpense');

  if (isSoft) {
    return (
      <View style={styles.container}>
        <View style={styles.softHeader}>
          <Text style={styles.softTitle}>统计</Text>
          <View style={styles.softSegWrap}>
            <SegmentedControl
              size="small"
              options={SOFT_TOP_TAB_OPTIONS}
              selectedKey={topTab}
              onSelect={(key) => setTopTab(key as TopTab)}
            />
          </View>
        </View>
        <View style={styles.content}>
          {topTab === 'assets' ? <SoftAssetsView /> : <SoftIncomeExpenseView />}
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.tabBar}>
        <SegmentedControl
          options={TOP_TAB_OPTIONS}
          selectedKey={topTab}
          onSelect={(key) => setTopTab(key as TopTab)}
        />
      </View>

      <View style={styles.content}>
        {topTab === 'assets' ? <AssetsTab /> : <IncomeExpenseTab />}
      </View>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    softHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 20,
      paddingTop: spacing.md,
      paddingBottom: spacing.xs,
    },
    softTitle: {
      fontSize: 22,
      fontWeight: '700',
      color: colors.textPrimary,
      letterSpacing: -0.3,
    },
    softSegWrap: {
      width: 148,
    },
    tabBar: {
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.lg,
      paddingBottom: spacing.sm,
      backgroundColor: colors.background,
    },
    content: {
      flex: 1,
    },
  }),
  _colors: colors,
});
