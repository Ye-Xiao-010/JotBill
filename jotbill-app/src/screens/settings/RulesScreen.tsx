import React from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, ChevronLeft, ListFilter } from 'lucide-react-native';
import { nativeData, type Rule } from '../../native';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function RulesScreen() {
  const navigation = useNavigation();
  const styles = useStyles(createStyles);
  const query = useQuery({ queryKey: ['rules'], queryFn: () => nativeData.getRules() });
  const rules = query.data ?? [];

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>规则</Text>
        <ListFilter size={21} color={styles._colors.primary} />
      </View>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={query.isFetching} onRefresh={() => query.refetch()} />}
      >
        <View style={styles.notice}>
          <Text style={styles.noticeText}>规则数据已接入本地存储，编辑和批量重算将在规则阶段开放。</Text>
        </View>
        {rules.length === 0 ? (
          <View style={styles.emptyState}>
            <ListFilter size={40} color={styles._colors.textTertiary} />
            <Text style={styles.emptyTitle}>暂无规则</Text>
          </View>
        ) : (
          rules.map((rule) => <RuleCard key={rule.id} rule={rule} styles={styles} />)
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function RuleCard({ rule, styles }: { rule: Rule; styles: ReturnType<typeof createStyles> }) {
  return (
    <View style={styles.ruleCard}>
      <View style={styles.ruleHeader}>
        <Text style={styles.ruleName} numberOfLines={1}>{rule.name}</Text>
        <CheckCircle2 size={18} color={rule.isActive ? styles._colors.success : styles._colors.textTertiary} />
      </View>
      <Text style={styles.ruleText}>{rule.condition}: {rule.conditionValue}</Text>
      <Text style={styles.ruleText}>{rule.action}{rule.actionValue ? `: ${rule.actionValue}` : ''}</Text>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.md, paddingTop: spacing.xl, paddingBottom: spacing.md, backgroundColor: colors.surface, borderBottomWidth: borderWidth.medium, borderBottomColor: colors.stroke },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: { fontSize: 20, fontWeight: '800', color: colors.textPrimary },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    notice: { padding: spacing.md, backgroundColor: colors.primaryLight, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    noticeText: { fontSize: 13, lineHeight: 19, fontWeight: '700', color: colors.textPrimary },
    emptyState: { alignItems: 'center', paddingVertical: spacing.xl * 2, gap: spacing.md },
    emptyTitle: { fontSize: 18, fontWeight: '800', color: colors.textPrimary },
    ruleCard: { padding: spacing.md, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    ruleHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
    ruleName: { flex: 1, fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    ruleText: { marginTop: spacing.sm, fontSize: 13, color: colors.textSecondary },
  }),
  _colors: colors,
});
