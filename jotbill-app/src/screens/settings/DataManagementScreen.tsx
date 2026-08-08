import React, { useState } from 'react';
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQueryClient } from '@tanstack/react-query';
import { Archive, ChevronLeft, Download, Trash2 } from 'lucide-react-native';
import { nativeBackup } from '../../native';
import { invalidateCache } from '../../lib/queryClient';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

const isExportCancelled = (error: unknown): boolean =>
  typeof error === 'object' &&
  error !== null &&
  'code' in error &&
  (error as { code?: unknown }).code === 'EXPORT_CANCELLED';

export default function DataManagementScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const [exporting, setExporting] = useState(false);
  const [clearing, setClearing] = useState(false);

  const exportCsv = async () => {
    setExporting(true);
    try {
      const result = await nativeBackup.exportData('csv');
      Alert.alert('导出完成', 'CSV 文件已保存到你选择的位置。', [{ text: '知道了' }]);
      return result;
    } catch (error) {
      if (isExportCancelled(error)) return;
      Alert.alert('导出失败', error instanceof Error ? error.message : '无法导出数据');
    } finally {
      setExporting(false);
    }
  };

  const clearData = () => {
    Alert.alert('清除本地数据', '这会删除本机账单、账户、分类、预算和目标，且不能撤销。请先导出 CSV。', [
      { text: '取消', style: 'cancel' },
      {
        text: '继续清除',
        style: 'destructive',
        onPress: async () => {
          setClearing(true);
          try {
            await nativeBackup.clearData();
            invalidateCache.all();
            queryClient.clear();
            Alert.alert('已清除', '本地数据已删除。');
          } catch (error) {
            Alert.alert('清除失败', error instanceof Error ? error.message : '无法清除数据');
          } finally {
            setClearing(false);
          }
        },
      },
    ]);
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>数据管理</Text>
        <Archive size={21} color={styles._colors.primary} />
      </View>
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        <View style={styles.infoCard}>
          <Archive size={24} color={styles._colors.primary} />
          <View style={styles.infoMain}>
            <Text style={styles.infoTitle}>本地数据</Text>
            <Text style={styles.infoText}>导出使用 Android 文件选择器，数据不会上传到服务器。</Text>
          </View>
        </View>
        <TouchableOpacity style={styles.actionCard} onPress={exportCsv} disabled={exporting}>
          {exporting ? <ActivityIndicator color={styles._colors.primary} /> : <Download size={24} color={styles._colors.primary} />}
          <View style={styles.actionMain}>
            <Text style={styles.actionTitle}>导出 CSV</Text>
            <Text style={styles.actionText}>导出账单明细，便于本地留存或迁移。</Text>
          </View>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.actionCard, styles.dangerCard]} onPress={clearData} disabled={clearing}>
          {clearing ? <ActivityIndicator color={styles._colors.error} /> : <Trash2 size={24} color={styles._colors.error} />}
          <View style={styles.actionMain}>
            <Text style={styles.dangerTitle}>清除本地数据</Text>
            <Text style={styles.actionText}>删除本机业务数据，操作前请先导出。</Text>
          </View>
        </TouchableOpacity>
      </ScrollView>
    </SafeAreaView>
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
    infoCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.primaryLight, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    infoMain: { flex: 1 },
    infoTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    infoText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    actionCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    dangerCard: { borderColor: colors.error },
    actionMain: { flex: 1 },
    actionTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    dangerTitle: { fontSize: 16, fontWeight: '800', color: colors.error },
    actionText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
  }),
  _colors: colors,
});
