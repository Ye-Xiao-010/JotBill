/**
 * AI 聊天会话列表屏幕
 */
import React, { useState, useCallback } from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  RefreshControl,
} from 'react-native';
import { MessageCircle, Plus, Trash2, ChevronRight, AlertTriangle } from 'lucide-react-native';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { useAlert } from '../../providers';
import { ThemeColors } from '../../theme/colors';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useChatSessions } from '../../hooks/useChatSessions';
import { aiService } from '../../services/ai';
import type { ChatSession } from '../../types/ai';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../../theme';

export default function AIChatSessionsScreen() {
  const styles = useStyles(createStyles);
  const navigation = useNavigation();
  const { alert, confirm } = useAlert();
  const [refreshing, setRefreshing] = useState(false);

  const {
    sessions,
    setSessions,
    isLoading,
    isLoadingMore,
    error,
    loadMore,
    reload,
  } = useChatSessions();

  // 每次重新聚焦都重拉首页：对话可能在别的页面（AIChat）新增/置顶/删除，
  // 本地 state 不会自动同步。reload 失败时不阻塞进入列表（保留旧数据）。
  useFocusEffect(
    useCallback(() => {
      reload();
    }, [reload]),
  );

  const handleRefresh = () => {
    setRefreshing(true);
    reload();
    // reload 内部驱动 loading 态，这里只负责收起下拉指示器
    setTimeout(() => setRefreshing(false), 600);
  };

  const handleNewChat = () => {
    navigation.navigate('AIChat', {});
  };

  const handleOpenSession = (sessionId: string) => {
    navigation.navigate('AIChat', { sessionId });
  };

  const handleDeleteSession = (sessionId: string) => {
    confirm(
      '删除会话',
      '确定要删除这个会话吗？删除后无法恢复。',
      async () => {
        try {
          await aiService.deleteChatSession(sessionId);
          setSessions(prev => prev.filter(s => s.id !== sessionId));
        } catch (deleteError) {
          // ⚠️ 此前固定回"删除失败，请重试"，把原生给出的真实原因（会话不存在、
          // 数据库忙、外键约束…）全丢了，用户只能反复重试同一个必然失败的操作。
          const message =
            deleteError instanceof Error ? deleteError.message : '删除失败，请重试';
          alert('删除失败', message);
        }
      },
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  const formatTime = (dateStr: string) => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes}分钟前`;
    if (hours < 24) return `${hours}小时前`;
    if (days < 7) return `${days}天前`;
    return `${date.getMonth() + 1}/${date.getDate()}`;
  };

  const renderSession = ({ item }: { item: ChatSession }) => (
    <TouchableOpacity
      style={styles.sessionCard}
      onPress={() => handleOpenSession(item.id)}
      activeOpacity={0.7}
    >
      <View style={styles.sessionIcon}>
        <MessageCircle size={20} color={styles._colors.primary} />
      </View>
      <View style={styles.sessionInfo}>
        <Text style={styles.sessionTitle} numberOfLines={1}>
          {item.title || '新对话'}
        </Text>
        <Text style={styles.sessionMeta}>
          {formatTime(item.updatedAt)}
          {item._count?.messages ? ` · ${item._count.messages} 条消息` : ''}
        </Text>
      </View>
      <View style={styles.sessionActions}>
        <TouchableOpacity
          style={styles.deleteButton}
          onPress={() => handleDeleteSession(item.id)}
          hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
        >
          <Trash2 size={16} color={styles._colors.textTertiary} />
        </TouchableOpacity>
        <ChevronRight size={18} color={styles._colors.textTertiary} />
      </View>
    </TouchableOpacity>
  );

  const renderEmpty = () => (
    <View style={styles.emptyContainer}>
      <MessageCircle size={48} color={styles._colors.textTertiary} />
      <Text style={styles.emptyText}>还没有对话记录</Text>
      <Text style={styles.emptyHint}>点击右上角开始新对话</Text>
    </View>
  );

  /**
   * ⚠️ 加载失败**绝不能**复用"还没有对话记录"。
   * 两者在界面上只差一句话，但对用户是相反的结论：
   * 一个是"你还没聊过"，一个是"你的聊天记录读不出来了"。
   * 尤其本项目的数据管理文案明确说过"清除本地数据会清掉 AI 对话历史"，
   * 于是"被清了"和"读失败"会渲染成同一句话，无法区分。
   */
  const renderLoadError = () => {
    const message = error instanceof Error ? error.message : String(error ?? '未知错误');
    return (
      <View style={styles.emptyContainer}>
        <AlertTriangle size={48} color={styles._colors.error} />
        <Text style={[styles.emptyText, styles.errorTitle]}>对话记录加载失败</Text>
        <Text style={styles.emptyHint}>{message}</Text>
        <Text style={styles.errorHint}>这不代表记录已被删除。</Text>
        <TouchableOpacity style={styles.retryButton} onPress={reload} activeOpacity={0.7}>
          <Text style={styles.retryButtonText}>重试</Text>
        </TouchableOpacity>
      </View>
    );
  };

  if (isLoading) {
    return (
      <SafeAreaView style={[styles.container, styles.centerContent]} edges={['top', 'bottom']}>
        <ActivityIndicator size="large" color={styles._colors.primary} />
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      {/* 头部 */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>对话记录</Text>
        <TouchableOpacity
          style={styles.newChatButton}
          onPress={handleNewChat}
          activeOpacity={0.7}
        >
          <Plus size={20} color="#FFFFFF" />
          <Text style={styles.newChatButtonText}>新对话</Text>
        </TouchableOpacity>
      </View>

      {/* 会话列表 */}
      <FlatList
        data={sessions}
        renderItem={renderSession}
        keyExtractor={item => item.id.toString()}
        contentContainerStyle={sessions.length === 0 ? styles.emptyList : styles.listContent}
        ListEmptyComponent={error ? renderLoadError : renderEmpty}
        onEndReached={loadMore}
        onEndReachedThreshold={0.4}
        ListFooterComponent={
          isLoadingMore ? (
            <View style={styles.footerLoading}>
              <ActivityIndicator color={styles._colors.primary} />
            </View>
          ) : null
        }
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={handleRefresh}
            colors={[styles._colors.primary]}
          />
        }
      />
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    centerContent: {
      justifyContent: 'center',
      alignItems: 'center',
    },
    header: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      backgroundColor: colors.surface,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    headerTitle: {
      fontSize: 18,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    newChatButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      backgroundColor: colors.primary,
      paddingVertical: spacing.xs + 2,
      paddingHorizontal: spacing.md,
      borderRadius: borderRadius.button,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    newChatButtonText: {
      fontSize: 14,
      color: '#FFFFFF',
      fontWeight: '700',
    },
    listContent: {
      padding: spacing.lg,
    },
    emptyList: {
      flex: 1,
    },
    footerLoading: {
      paddingVertical: spacing.lg,
    },
    errorTitle: {
      color: colors.error,
    },
    errorHint: {
      marginTop: spacing.xs,
      fontSize: 13,
      fontWeight: '700',
      color: colors.error,
    },
    retryButton: {
      marginTop: spacing.lg,
      backgroundColor: colors.primary,
      borderRadius: borderRadius.button,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.sm,
    },
    retryButtonText: {
      fontSize: 14,
      fontWeight: '800',
      color: '#FFFFFF',
    },
    sessionCard: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.md,
      marginBottom: spacing.sm,
    },
    sessionIcon: {
      width: 40,
      height: 40,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: spacing.md,
    },
    sessionInfo: {
      flex: 1,
    },
    sessionTitle: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
      marginBottom: 2,
    },
    sessionMeta: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: typography.fontFamily.mono,
      color: colors.textTertiary,
    },
    sessionActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    deleteButton: {
      padding: spacing.xs,
    },
    emptyContainer: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingBottom: 60,
    },
    emptyText: {
      fontSize: 16,
      fontWeight: '700',
      color: colors.textSecondary,
      marginTop: spacing.lg,
    },
    emptyHint: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textTertiary,
      marginTop: spacing.xs,
    },
  }),
  _colors: colors,
});
