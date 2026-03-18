/**
 * 文枢 WenShape - 深度上下文感知的智能体小说创作系统
 * WenShape - Deep Context-Aware Agent-Based Novel Writing System
 *
 * Copyright © 2025-2026 WenShape Team
 * License: PolyForm Noncommercial License 1.0.0
 *
 * 模块说明 / Module Description:
 *   资源管理器面板 - 项目结构浏览、章节管理、批量同步与结果校对
 *   Explorer panel for project structure, chapter management, batch sync, and analysis review.
 */

/**
 * 资源管理器面板 - 项目结构浏览与批量操作入口
 *
 * Main IDE explorer panel for browsing project structure, managing chapters and volumes,
 * syncing chapter analysis, and reviewing extracted facts and summaries before persisting.
 *
 * @component
 * @example
 * return (
 *   <ExplorerPanel className="custom-class" />
 * )
 *
 * @param {Object} props - Component props
 * @param {string} [props.className] - 自定义样式类名 / Additional CSS classes
 * @returns {JSX.Element} 资源管理器面板 / Explorer panel element
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import useSWR from 'swr';
import { useIDE } from '../../../context/IDEContext';
import { bindingsAPI, evidenceAPI, outlineAPI, sessionAPI, textChunksAPI } from '../../../api';
import AnalysisSyncDialog from '../AnalysisSyncDialog';
import AnalysisReviewDialog from '../../writing/AnalysisReviewDialog';
import VolumeManageDialog from '../VolumeManageDialog';
import VolumeTree from '../VolumeTree';
import { OutlineCardExtractDialog } from '../OutlineCardExtractDialog';
import { cardsAPI } from '../../../api';
import { Layers, RefreshCw, Plus, ArrowUpDown, Save, ChevronDown, ChevronRight, Wand2 } from 'lucide-react';
import { cn } from '../../ui/core';
import logger from '../../../utils/logger';
import { useLocale } from '../../../i18n';

const OUTLINE_PANEL_HEIGHT_KEY = 'wenshape_outline_panel_height';
const DEFAULT_OUTLINE_HEIGHT = 220;
const MIN_OUTLINE_HEIGHT = 140;
const MIN_TREE_HEIGHT = 160;

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const normalizeId = (value) => String(value || '').trim().toUpperCase();

const buildOutlinePreview = (value) => {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  if (!text) return '';
  return text.length > 28 ? `${text.slice(0, 28)}...` : text;
};

export default function ExplorerPanel({ className }) {
  const { t, locale } = useLocale();
  const requestLanguage = String(locale || '').toLowerCase().startsWith('en') ? 'en' : 'zh';
  const { state, dispatch } = useIDE();
  const projectId = state.activeProjectId;
  const selectedChapterId = state.activeDocument?.type === 'chapter' ? normalizeId(state.activeDocument?.id) : '';
  const [syncOpen, setSyncOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewItems, setReviewItems] = useState([]);
  const [reviewSaving, setReviewSaving] = useState(false);
  const [reviewError, setReviewError] = useState('');
  const [syncLoading, setSyncLoading] = useState(false);
  const [syncResults, setSyncResults] = useState([]);
  const [syncError, setSyncError] = useState('');
  const [indexRebuildLoading, setIndexRebuildLoading] = useState(false);
  const [indexRebuildError, setIndexRebuildError] = useState('');
  const [indexRebuildSuccess, setIndexRebuildSuccess] = useState(false);
  const [volumeManageOpen, setVolumeManageOpen] = useState(false);
  const [reorderMode, setReorderMode] = useState(false);
  const [outlineTarget, setOutlineTarget] = useState({ type: 'master', id: null });
  const [outlineDrafts, setOutlineDrafts] = useState({});
  const [outlineSaving, setOutlineSaving] = useState(false);
  const [outlineError, setOutlineError] = useState('');

  // -- AI 特性：大纲卡片提取 State --
  const [extractDialogOpen, setExtractDialogOpen] = useState(false);
  const [extractedCards, setExtractedCards] = useState([]);
  const [extractLoading, setExtractLoading] = useState(false);
  const [extractSubmitting, setExtractSubmitting] = useState(false);
  const [existingWorldNames, setExistingWorldNames] = useState([]);
  const [existingCharacterNames, setExistingCharacterNames] = useState([]);

  // -- AI 特性：依据指令生成大纲 --
  const [showGenerateBox, setShowGenerateBox] = useState(false);
  const [generateInstruction, setGenerateInstruction] = useState('');
  const [outlineGenerating, setOutlineGenerating] = useState(false);

  const [outlineExpandedVolumes, setOutlineExpandedVolumes] = useState(new Set());
  const [outlineHeight, setOutlineHeight] = useState(() => {
    const raw = window.localStorage.getItem(OUTLINE_PANEL_HEIGHT_KEY);
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? clamp(parsed, MIN_OUTLINE_HEIGHT, 600) : DEFAULT_OUTLINE_HEIGHT;
  });
  const panelBodyRef = useRef(null);

  const { data: outlineData, error: outlineLoadError, isLoading: outlineLoading, mutate: mutateOutline } = useSWR(
    projectId ? `/outline/${projectId}` : null,
    () => outlineAPI.get(projectId).then((res) => res.data),
    {
      revalidateOnFocus: true,
    }
  );

  const volumes = useMemo(() => (Array.isArray(outlineData?.volumes) ? outlineData.volumes : []), [outlineData]);
  const chapters = useMemo(() => (Array.isArray(outlineData?.chapters) ? outlineData.chapters : []), [outlineData]);

  const chapterGroups = useMemo(() => {
    const grouped = {};
    chapters.forEach((chapter) => {
      const volId = normalizeId(chapter?.volume_id || 'V1');
      if (!grouped[volId]) grouped[volId] = [];
      grouped[volId].push(chapter);
    });
    return grouped;
  }, [chapters]);

  useEffect(() => {
    if (!projectId) return;
    setOutlineDrafts({});
    setOutlineError('');
    setOutlineTarget({ type: 'master', id: null });
    setOutlineExpandedVolumes(new Set());
  }, [projectId]);

  useEffect(() => {
    if (!volumes.length) return;
    setOutlineExpandedVolumes((prev) => {
      if (prev.size > 0) return prev;
      return new Set(volumes.map((item) => item.volume_id));
    });
  }, [volumes]);

  useEffect(() => {
    if (selectedChapterId) {
      setOutlineTarget({ type: 'chapter', id: selectedChapterId });
      const matchedVolume = chapters.find((item) => normalizeId(item.chapter_id) === selectedChapterId)?.volume_id
        || selectedChapterId.match(/^V\d+/i)?.[0]
        || 'V1';
      setOutlineExpandedVolumes((prev) => {
        const next = new Set(prev);
        next.add(normalizeId(matchedVolume));
        return next;
      });
      return;
    }
    if (state.selectedVolumeId) {
      const volumeId = normalizeId(state.selectedVolumeId);
      setOutlineTarget({ type: 'volume', id: volumeId });
      setOutlineExpandedVolumes((prev) => {
        const next = new Set(prev);
        next.add(volumeId);
        return next;
      });
      return;
    }
    setOutlineTarget((prev) => (prev.type === 'master' ? prev : { type: 'master', id: null }));
  }, [selectedChapterId, state.selectedVolumeId, chapters]);

  useEffect(() => {
    if (outlineTarget.type === 'chapter' && outlineTarget.id) {
      const targetId = normalizeId(outlineTarget.id);
      const exists = chapters.some((item) => normalizeId(item.chapter_id) === targetId);
      if (exists) return;
      if (state.selectedVolumeId) {
        setOutlineTarget({ type: 'volume', id: normalizeId(state.selectedVolumeId) });
      } else {
        setOutlineTarget({ type: 'master', id: null });
      }
      return;
    }

    if (outlineTarget.type === 'volume' && outlineTarget.id) {
      const targetId = normalizeId(outlineTarget.id);
      const exists = volumes.some((item) => normalizeId(item.volume_id) === targetId);
      if (!exists) {
        setOutlineTarget({ type: 'master', id: null });
      }
    }
  }, [outlineTarget, chapters, volumes, state.selectedVolumeId]);

  const currentOutlineKey = useMemo(() => {
    if (outlineTarget.type === 'master') return 'master';
    if (!outlineTarget.id) return '';
    return `${outlineTarget.type}:${outlineTarget.id}`;
  }, [outlineTarget]);

  const currentOutlineSource = useMemo(() => {
    if (!outlineData) return { content: '' };
    if (outlineTarget.type === 'master') return outlineData.master || { content: '' };
    if (outlineTarget.type === 'volume') {
      const targetId = normalizeId(outlineTarget.id);
      const item = volumes.find((entry) => normalizeId(entry.volume_id) === targetId);
      return item || { content: '' };
    }
    if (outlineTarget.type === 'chapter') {
      const targetId = normalizeId(outlineTarget.id);
      const item = chapters.find((entry) => normalizeId(entry.chapter_id) === targetId);
      return item || { content: '' };
    }
    return { content: '' };
  }, [outlineData, outlineTarget, volumes, chapters]);

  const getDraftedOutlineContent = (type, id, baseContent = '') => {
    const key = type === 'master' ? 'master' : `${type}:${id}`;
    if (Object.prototype.hasOwnProperty.call(outlineDrafts, key)) {
      return outlineDrafts[key] ?? '';
    }
    return baseContent ?? '';
  };

  const currentOutlineContent = currentOutlineKey
    ? (outlineDrafts[currentOutlineKey] ?? currentOutlineSource.content ?? '')
    : '';
  const currentDirty = currentOutlineContent !== (currentOutlineSource.content ?? '');

  const outlineTargetLabel = useMemo(() => {
    if (outlineTarget.type === 'master') return t('panels.explorer.outlineGlobal');
    if (outlineTarget.type === 'volume') {
      const targetId = normalizeId(outlineTarget.id);
      const item = volumes.find((entry) => normalizeId(entry.volume_id) === targetId);
      if (!item) return `${t('panels.explorer.outlineVolumePrefix')} ${outlineTarget.id || ''}`;
      return `${t('panels.explorer.outlineVolumePrefix')} ${item.volume_id} · ${item.title || t('common.unnamed')}`;
    }
    if (outlineTarget.type === 'chapter') {
      const targetId = normalizeId(outlineTarget.id);
      const item = chapters.find((entry) => normalizeId(entry.chapter_id) === targetId);
      if (!item) return `${t('panels.explorer.outlineChapterPrefix')} ${outlineTarget.id || ''}`;
      return `${t('panels.explorer.outlineChapterPrefix')} ${item.chapter_id} · ${item.title || t('chapter.noTitle')}`;
    }
    return t('panels.explorer.outlineGlobal');
  }, [outlineTarget, volumes, chapters, t]);

  const toggleOutlineVolume = (rawVolumeId) => {
    const volumeId = normalizeId(rawVolumeId);
    setOutlineExpandedVolumes((prev) => {
      const next = new Set(prev);
      if (next.has(volumeId)) next.delete(volumeId);
      else next.add(volumeId);
      return next;
    });
  };

  const handleOutlineTextChange = (value) => {
    if (!currentOutlineKey) return;
    setOutlineDrafts((prev) => ({ ...prev, [currentOutlineKey]: value }));
  };

  const handleGenerateOutline = async () => {
    if (!generateInstruction.trim() || outlineTarget.type !== 'chapter' || !projectId || !outlineTarget.id) return;
    setOutlineGenerating(true);
    try {
      const payload = {
        chapter_id: outlineTarget.id,
        instruction: generateInstruction,
        language: requestLanguage
      };
      const resp = await outlineAPI.generate(projectId, payload);
      if (resp.data?.content) {
        handleOutlineTextChange(resp.data.content);
        setShowGenerateBox(false);
        setGenerateInstruction('');

        try {
          await outlineAPI.saveChapter(projectId, outlineTarget.id, { content: resp.data.content });
          setOutlineDrafts((prev) => {
            const next = { ...prev };
            delete next[`chapter:${outlineTarget.id}`];
            return next;
          });
          await mutateOutline();
          window.dispatchEvent(new CustomEvent('wenshape:outline-updated'));
        } catch (e) {
          console.error('Auto save outline failed:', e);
        }
      } else {
        throw new Error('未返回大纲内容');
      }
    } catch (err) {
      alert("生成失败: " + (err?.response?.data?.detail || err.message));
    } finally {
      setOutlineGenerating(false);
    }
  };

  const handleOutlineReset = () => {
    if (!currentOutlineKey) return;
    setOutlineDrafts((prev) => {
      const next = { ...prev };
      delete next[currentOutlineKey];
      return next;
    });
    setOutlineError('');
  };

  const saveCurrentOutline = async () => {
    if (!projectId || !currentOutlineKey || outlineSaving) {
      return { success: false, error: t('panels.explorer.outlineNoProject') };
    }

    if (!currentDirty) {
      return { success: true };
    }

    setOutlineSaving(true);
    setOutlineError('');
    try {
      const payload = { content: currentOutlineContent };
      if (outlineTarget.type === 'master') {
        await outlineAPI.saveMaster(projectId, payload);
      } else if (outlineTarget.type === 'volume' && outlineTarget.id) {
        await outlineAPI.saveVolume(projectId, outlineTarget.id, payload);
      } else if (outlineTarget.type === 'chapter' && outlineTarget.id) {
        await outlineAPI.saveChapter(projectId, outlineTarget.id, payload);
      }

      setOutlineDrafts((prev) => {
        const next = { ...prev };
        delete next[currentOutlineKey];
        return next;
      });
      await mutateOutline();
      window.dispatchEvent(new CustomEvent('wenshape:outline-updated'));
      return { success: true };
    } catch (err) {
      logger.error(err);
      const detail = err?.response?.data?.detail || err?.response?.data?.error;
      const message = detail || err?.message || t('panels.explorer.outlineSaveFailed');
      setOutlineError(message);
      return { success: false, error: message };
    } finally {
      setOutlineSaving(false);
    }
  };

  // --- AI 大纲特征：提取卡片功能 ---
  const handleExtractCards = async () => {
    if (!projectId) return;

    // 聚合当前大纲文本（master + 所有 volumes + chapters）
    let fullOutline = "";
    if (outlineData) {
      if (outlineData.master?.content) {
        fullOutline += "项目总纲：\n" + outlineData.master.content + "\n\n";
      }
      (outlineData.volumes || []).forEach(v => {
        if (v.content) fullOutline += `分卷[${v.title || v.volume_id}]大纲：\n` + v.content + "\n\n";
      });
      (outlineData.chapters || []).forEach(c => {
        if (c.content) fullOutline += `章节[${c.title || c.chapter_id}]大纲：\n` + c.content + "\n\n";
      });
    }

    if (fullOutline.length < 30) {
      alert("大纲内容不足，无法提取卡片。请先编写大纲内容！");
      return;
    }

    setExtractLoading(true);
    try {
      // 提取前，先获取已有卡片名单，用于检测冲突
      const [charsRes, worldsRes] = await Promise.all([
        cardsAPI.listCharactersIndex(projectId).catch(() => ({ data: [] })),
        cardsAPI.listWorldIndex(projectId).catch(() => ({ data: [] }))
      ]);
      const charNames = (charsRes.data || []).map(c => c.name);
      const worldNames = (worldsRes.data || []).map(c => c.name);
      setExistingCharacterNames(charNames);
      setExistingWorldNames(worldNames);

      const res = await cardsAPI.extractFromOutline(projectId, {
        outline_text: fullOutline,
        language: requestLanguage
      });

      const cards = res.data?.cards || [];
      if (cards.length > 0) {
        setExtractedCards(cards);
        setExtractDialogOpen(true);
      } else {
        alert("未能从大纲中提取出任何卡片。");
      }
    } catch (err) {
      logger.error(err);
      alert(err?.response?.data?.detail || "卡片提取失败: " + err.message);
    } finally {
      setExtractLoading(false);
    }
  };

  const handleConfirmExtract = async (selectedCards) => {
    if (!projectId || selectedCards.length === 0) return;
    setExtractSubmitting(true);
    try {
      let successCount = 0;
      for (const card of selectedCards) {
        if (card.card_type === 'world') {
          const isExist = existingWorldNames.includes(card.name);
          if (isExist) {
            await cardsAPI.updateWorld(projectId, card.name, card);
          } else {
            await cardsAPI.createWorld(projectId, card);
          }
        } else {
          const isExist = existingCharacterNames.includes(card.name);
          if (isExist) {
            await cardsAPI.updateCharacter(projectId, card.name, card);
          } else {
            await cardsAPI.createCharacter(projectId, card);
          }
        }
        successCount++;
      }
      setExtractDialogOpen(false);
      // 成功后弹窗自动关闭，若需要通知其他面板更新可触发事件或 mutate
    } catch (err) {
      logger.error("Error creating cards:", err);
      alert("部分卡片导入失败：" + err.message);
    } finally {
      setExtractSubmitting(false);
    }
  };
  // ------------------------------------

  useEffect(() => {
    const handleSaveRequest = async (event) => {
      const requestId = event?.detail?.requestId;
      const result = await saveCurrentOutline();
      window.dispatchEvent(new CustomEvent('wenshape:outline:save-current:result', {
        detail: {
          requestId,
          ...result,
        },
      }));
    };

    window.addEventListener('wenshape:outline:save-current', handleSaveRequest);
    return () => {
      window.removeEventListener('wenshape:outline:save-current', handleSaveRequest);
    };
  }, [saveCurrentOutline]);

  const startOutlineResize = useCallback((event) => {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = outlineHeight;
    const containerHeight = panelBodyRef.current?.clientHeight || 0;
    const maxHeight = Math.max(MIN_OUTLINE_HEIGHT, containerHeight - MIN_TREE_HEIGHT);

    const handleMouseMove = (moveEvent) => {
      const delta = startY - moveEvent.clientY;
      const next = clamp(startHeight + delta, MIN_OUTLINE_HEIGHT, maxHeight);
      setOutlineHeight(next);
    };

    const handleMouseUp = () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  }, [outlineHeight]);

  useEffect(() => {
    window.localStorage.setItem(OUTLINE_PANEL_HEIGHT_KEY, String(outlineHeight));
  }, [outlineHeight]);

  useEffect(() => {
    const clampHeightToContainer = () => {
      const containerHeight = panelBodyRef.current?.clientHeight || 0;
      if (!containerHeight) return;
      const maxHeight = Math.max(MIN_OUTLINE_HEIGHT, containerHeight - MIN_TREE_HEIGHT);
      setOutlineHeight((prev) => clamp(prev, MIN_OUTLINE_HEIGHT, maxHeight));
    };

    clampHeightToContainer();
    window.addEventListener('resize', clampHeightToContainer);
    return () => window.removeEventListener('resize', clampHeightToContainer);
  }, []);

  const handleSyncConfirm = async (selectedChapters) => {
    if (selectedChapters.length === 0 || !projectId) return;
    setSyncError('');
    setSyncResults([]);
    setSyncLoading(true);
    try {
      const res = await sessionAPI.analyzeSync(projectId, { language: requestLanguage, chapters: selectedChapters });
      const payload = Array.isArray(res.data)
        ? { success: true, results: res.data }
        : (res.data || {});
      if (!payload?.success) {
        throw new Error(payload?.error || payload?.detail || t('writingSession.syncFailed'));
      }
      const results = Array.isArray(payload?.results) ? payload.results : [];
      const analyses = results
        .filter((item) => item?.success && item?.analysis && item?.chapter)
        .map((item) => ({ chapter: item.chapter, analysis: item.analysis }));
      const bindingResults = await Promise.all(
        results.map(async (item) => {
          const chapter = item?.chapter;
          if (!chapter) return null;
          try {
            const bindingResp = await bindingsAPI.get(projectId, chapter);
            return { ...item, binding: bindingResp.data?.binding || null };
          } catch (error) {
            return {
              ...item,
              binding_error: error?.response?.data?.detail || error?.message || t('error.loadFailed'),
            };
          }
        })
      );
      setSyncResults(bindingResults.filter(Boolean));
      setReviewItems(analyses);
      if (analyses.length > 0) {
        setSyncOpen(false);
        setReviewError('');
        setReviewOpen(true);
      }
    } catch (err) {
      logger.error(err);
      const detail = err?.response?.data?.detail || err?.response?.data?.error;
      setSyncError(detail || err?.message || t('writingSession.syncFailed'));
    } finally {
      setSyncLoading(false);
    }
  };

  const handleRebuildBindings = async (selectedChapters) => {
    if (!projectId) return;
    setSyncError('');
    setSyncResults([]);
    setSyncLoading(true);
    try {
      const res = await bindingsAPI.rebuildBatch(projectId, {
        chapters: selectedChapters.length > 0 ? selectedChapters : undefined
      });
      if (!res.data?.success) {
        throw new Error(res.data?.error || t('error.loadFailed'));
      }
      const results = Array.isArray(res.data?.results) ? res.data.results : [];
      setSyncResults(results);
    } catch (err) {
      logger.error(err);
      setSyncError(err?.message || t('error.loadFailed'));
    } finally {
      setSyncLoading(false);
    }
  };

  const handleRebuildIndexes = async () => {
    if (!projectId) return;
    setIndexRebuildError('');
    setIndexRebuildSuccess(false);
    setIndexRebuildLoading(true);
    try {
      await evidenceAPI.rebuild(projectId);
      await textChunksAPI.rebuild(projectId);
      setIndexRebuildSuccess(true);
    } catch (err) {
      logger.error(err);
      const detail = err?.response?.data?.detail || err?.response?.data?.error;
      setIndexRebuildError(detail || err?.message || t('error.loadFailed'));
    } finally {
      setIndexRebuildLoading(false);
    }
  };

  const handleReviewSave = async (updatedAnalyses) => {
    setReviewSaving(true);
    setReviewError('');
    try {
      const resp = await sessionAPI.saveAnalysisBatch(projectId, {
        language: requestLanguage,
        items: updatedAnalyses,
        overwrite: true,
      });
      if (resp?.data?.success === false) {
        throw new Error(resp?.data?.error || resp?.data?.detail || t('error.saveFailed'));
      }
      setReviewOpen(false);
      setReviewItems([]);
    } catch (err) {
      logger.error(err);
      const detail = err?.response?.data?.detail || err?.response?.data?.error;
      const code = err?.code || err?.name;
      if (code === 'ECONNABORTED') {
        setReviewError(t('panels.explorer.saveTimeout'));
      } else {
        setReviewError(detail || err?.message || t('error.saveFailed'));
      }
    } finally {
      setReviewSaving(false);
    }
  };

  const handleChapterSelect = (chapter) => {
    dispatch({ type: 'SET_ACTIVE_DOCUMENT', payload: { ...chapter, type: 'chapter' } });
  };

  // 通用操作按钮组件
  const ActionButton = ({ onClick, icon: Icon, title }) => (
    <button
      onClick={onClick}
      title={title}
      aria-label={title}
      className={cn(
        "p-1 rounded-[2px] text-[var(--vscode-fg)] hover:bg-[var(--vscode-list-hover)] transition-none outline-none focus:ring-1 focus:ring-[var(--vscode-focus-border)]",
        "opacity-70 hover:opacity-100 focus:opacity-100",
        "flex items-center justify-center w-6 h-6"
      )}
    >
      <Icon size={14} strokeWidth={1.5} />
    </button>
  );

  return (
    <div className={cn('anti-theme explorer-panel flex flex-col h-full bg-[var(--vscode-bg)] text-[var(--vscode-fg)] select-none', className)}>
      {/* VS Code 风格工具栏 */}
      <div className="flex items-center h-[35px] px-4 font-sans text-[11px] font-bold tracking-wide text-[var(--vscode-fg-subtle)] uppercase bg-[var(--vscode-sidebar-bg)] border-b border-[var(--vscode-sidebar-border)]">
        <span>{t('panels.explorer.title')}</span>
        <div className="flex-1" />

        {/* 右侧工具按钮 */}
        <div className="flex items-center gap-0.5">
          <ActionButton
            onClick={() => setReorderMode((prev) => !prev)}
            icon={ArrowUpDown}
            title={reorderMode ? t('panels.explorer.exitReorder') : t('panels.explorer.reorderMode')}
          />
          <ActionButton
            onClick={() => dispatch({ type: 'OPEN_CREATE_CHAPTER_DIALOG', payload: { volumeId: state.selectedVolumeId } })}
            icon={Plus}
            title={t('panels.explorer.newChapter')}
          />
          <ActionButton
            onClick={() => {
              setSyncError('');
              setSyncResults([]);
              setIndexRebuildError('');
              setIndexRebuildSuccess(false);
              setSyncOpen(true);
            }}
            icon={RefreshCw}
            title={t('panels.explorer.syncAll')}
          />
          <ActionButton
            onClick={() => setVolumeManageOpen(true)}
            icon={Layers}
            title={t('panels.explorer.manageVolumes')}
          />
        </div>
      </div>

      <div ref={panelBodyRef} className="flex-1 overflow-hidden relative flex flex-col">
        <div
          className="relative min-h-[160px]"
          style={{ height: `calc(100% - ${outlineHeight}px)` }}
        >
          <div className="absolute inset-0 overflow-y-auto custom-scrollbar">
            <VolumeTree
              projectId={projectId}
              onChapterSelect={handleChapterSelect}
              selectedChapter={state.activeDocument?.id}
              reorderMode={reorderMode}
            />
          </div>
        </div>

        <div
          className="h-1 cursor-row-resize bg-[var(--vscode-sidebar-border)] hover:bg-[var(--vscode-focus-border)]"
          onMouseDown={startOutlineResize}
          title={t('panels.explorer.outlineResize')}
          aria-label={t('panels.explorer.outlineResize')}
        />

        <div
          className="border-t border-[var(--vscode-sidebar-border)] bg-[var(--vscode-sidebar-bg)] flex flex-col min-h-[140px]"
          style={{ height: outlineHeight }}
        >
          <div className="h-8 px-3 flex items-center justify-between border-b border-[var(--vscode-sidebar-border)] text-[11px] uppercase tracking-wide text-[var(--vscode-fg-subtle)] font-bold">
            <span>{t('panels.explorer.outlineTitle')}</span>
            <div className="flex items-center gap-2">
              {currentDirty && (
                <button
                  onClick={handleOutlineReset}
                  className="text-[10px] px-1.5 py-0.5 rounded border border-[var(--vscode-input-border)] hover:bg-[var(--vscode-list-hover)]"
                >
                  {t('panels.explorer.outlineReset')}
                </button>
              )}

              {/* ✨ AI 卡片提取按钮 */}
              <button
                onClick={handleExtractCards}
                disabled={!projectId || extractLoading}
                className={cn(
                  'inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded border border-brand-500/30 text-brand-600 hover:bg-brand-50 transition-colors',
                  (!projectId || extractLoading) && 'opacity-50 cursor-not-allowed'
                )}
                title="✨ 自动从当前大纲中提取主要角色和世界观名片"
              >
                <Wand2 size={11} className={extractLoading ? "animate-spin" : ""} />
                {extractLoading ? "提取中..." : "提取卡片"}
              </button>
              <button
                onClick={saveCurrentOutline}
                disabled={!projectId || outlineSaving || !currentDirty}
                className={cn(
                  'inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded border',
                  !projectId || outlineSaving || !currentDirty
                    ? 'opacity-40 cursor-not-allowed border-[var(--vscode-input-border)]'
                    : 'border-[var(--vscode-focus-border)] hover:bg-[var(--vscode-list-hover)]'
                )}
              >
                <Save size={11} />
                {outlineSaving ? t('common.processing') : t('common.save')}
              </button>
            </div>
          </div>

          {!projectId ? (
            <div className="flex-1 p-3 text-xs text-[var(--vscode-fg-subtle)]">{t('panels.explorer.outlineNoProject')}</div>
          ) : outlineLoading && !outlineData ? (
            <div className="flex-1 p-3 text-xs text-[var(--vscode-fg-subtle)]">{t('panels.explorer.outlineLoading')}</div>
          ) : outlineLoadError ? (
            <div className="flex-1 p-3 text-xs text-red-500">
              {outlineLoadError?.response?.data?.detail || outlineLoadError?.message || t('error.loadFailed')}
            </div>
          ) : (
            <>
              <div className="max-h-[40%] overflow-y-auto custom-scrollbar border-b border-[var(--vscode-sidebar-border)]">
                {(() => {
                  const masterPreview = buildOutlinePreview(getDraftedOutlineContent('master', null, outlineData?.master?.content));
                  return (
                    <button
                      onClick={() => setOutlineTarget({ type: 'master', id: null })}
                      className={cn(
                        'w-full text-left text-xs px-3 py-1.5 hover:bg-[var(--vscode-list-hover)]',
                        outlineTarget.type === 'master' && 'bg-[var(--vscode-list-active)]'
                      )}
                    >
                      <div className="truncate">{t('panels.explorer.outlineGlobal')}</div>
                      {masterPreview && (
                        <div className="mt-0.5 text-[10px] opacity-60 truncate">{masterPreview}</div>
                      )}
                    </button>
                  );
                })()}

                {volumes.map((volume) => {
                  const volumeId = normalizeId(volume.volume_id);
                  const expanded = outlineExpandedVolumes.has(volumeId);
                  const volumeChapters = chapterGroups[volumeId] || [];
                  const volumePreview = buildOutlinePreview(
                    getDraftedOutlineContent('volume', volumeId, volume.content)
                  );
                  return (
                    <div key={volumeId}>
                      <div className="flex items-center">
                        <button
                          onClick={() => toggleOutlineVolume(volumeId)}
                          className="p-1 ml-1 text-[var(--vscode-fg-subtle)] hover:text-[var(--vscode-fg)]"
                        >
                          {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        </button>
                        <button
                          onClick={() => setOutlineTarget({ type: 'volume', id: volumeId })}
                          className={cn(
                            'flex-1 text-left text-xs pr-2 py-1.5 hover:bg-[var(--vscode-list-hover)]',
                            outlineTarget.type === 'volume' && outlineTarget.id === volumeId && 'bg-[var(--vscode-list-active)]'
                          )}
                        >
                          <div className="truncate">{volumeId} · {volume.title || t('common.unnamed')}</div>
                          {volumePreview && (
                            <div className="mt-0.5 text-[10px] opacity-60 truncate">{volumePreview}</div>
                          )}
                        </button>
                      </div>

                      {expanded && volumeChapters.map((chapter) => {
                        const chapterId = normalizeId(chapter.chapter_id);
                        const chapterPreview = buildOutlinePreview(
                          getDraftedOutlineContent('chapter', chapterId, chapter.content)
                        );
                        return (
                          <button
                            key={chapterId}
                            onClick={() => setOutlineTarget({ type: 'chapter', id: chapterId })}
                            className={cn(
                              'w-full text-left text-xs pl-8 pr-2 py-1.5 hover:bg-[var(--vscode-list-hover)]',
                              outlineTarget.type === 'chapter' && outlineTarget.id === chapterId && 'bg-[var(--vscode-list-active)]'
                            )}
                          >
                            <div className="truncate">{chapterId} · {chapter.title || t('chapter.noTitle')}</div>
                            {chapterPreview && (
                              <div className="mt-0.5 text-[10px] opacity-60 truncate">{chapterPreview}</div>
                            )}
                          </button>
                        );
                      })}
                    </div>
                  );
                })}
              </div>

              <div className="flex-1 min-h-0 p-2 flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <span className="text-[11px] text-[var(--vscode-fg-subtle)]">{outlineTargetLabel}</span>
                  {outlineTarget.type === 'chapter' && (
                    <button
                      onClick={() => setShowGenerateBox(!showGenerateBox)}
                      className={cn(
                        "flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-[4px] border transition-colors",
                        showGenerateBox
                          ? "bg-[var(--vscode-list-active)] border-[var(--vscode-list-active)] text-[var(--vscode-list-active-fg)]"
                          : "bg-[var(--vscode-button-secondaryBackground)] hover:bg-[var(--vscode-button-secondaryHoverBackground)] text-[var(--vscode-button-secondaryForeground)] border-[var(--vscode-button-secondaryBackground)]"
                      )}
                      title="打开大纲生成指令框，依据指令重新起草细纲"
                    >
                      <Wand2 size={10} />
                      AI 智能起草
                    </button>
                  )}
                </div>
                {showGenerateBox && outlineTarget.type === 'chapter' && (
                  <div className="flex flex-col gap-1 border border-[var(--vscode-focus-border)] rounded-[4px] p-2 bg-[var(--vscode-editor-background)]">
                    <div className="text-[10px] text-[var(--vscode-fg-subtle)] pb-1">在此输入灵感诉求，AI 将自动头脑风暴并覆写当前章节的内容大纲：</div>
                    <textarea
                      value={generateInstruction}
                      onChange={(e) => setGenerateInstruction(e.target.value)}
                      placeholder="例如：本章主角去集市买剑，遇到反派挑衅，主角扮猪吃虎打脸..."
                      className="w-full text-xs min-h-[40px] resize-none bg-[var(--vscode-input-bg)] border border-[var(--vscode-input-border)] rounded-[2px] px-2 py-1 focus:outline-none text-[var(--vscode-fg)]"
                    />
                    <div className="flex justify-end pt-1">
                      <button
                        onClick={handleGenerateOutline}
                        disabled={outlineGenerating || !generateInstruction.trim()}
                        className="flex items-center gap-1 px-2 py-1 text-[10px] bg-[var(--vscode-button-background)] text-[var(--vscode-button-foreground)] hover:bg-[var(--vscode-button-hoverBackground)] disabled:opacity-50 rounded-[2px] transition-colors"
                      >
                        {outlineGenerating && <RefreshCw size={10} className="animate-spin" />}
                        {outlineGenerating ? '正在构思中...' : '提交生成'}
                      </button>
                    </div>
                  </div>
                )}
                <textarea
                  value={currentOutlineContent}
                  onChange={(event) => handleOutlineTextChange(event.target.value)}
                  placeholder={t('panels.explorer.outlineEditorPlaceholder')}
                  className="flex-1 min-h-0 w-full text-xs bg-[var(--vscode-input-bg)] border border-[var(--vscode-input-border)] rounded-[4px] px-2 py-1.5 text-[var(--vscode-fg)] focus:outline-none focus:border-[var(--vscode-focus-border)] resize-none"
                />
                {outlineError && (
                  <div className="text-[11px] text-red-500">{outlineError}</div>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      <AnalysisSyncDialog
        open={syncOpen}
        projectId={projectId}
        loading={syncLoading}
        results={syncResults}
        error={syncError}
        indexRebuildLoading={indexRebuildLoading}
        indexRebuildError={indexRebuildError}
        indexRebuildSuccess={indexRebuildSuccess}
        onClose={() => setSyncOpen(false)}
        onConfirm={handleSyncConfirm}
        onRebuild={handleRebuildBindings}
        onRebuildIndexes={handleRebuildIndexes}
      />

      <AnalysisReviewDialog
        open={reviewOpen}
        analyses={reviewItems}
        error={reviewError}
        onCancel={() => {
          setReviewOpen(false);
          setReviewItems([]);
          setReviewError('');
        }}
        onSave={handleReviewSave}
        saving={reviewSaving}
      />

      <VolumeManageDialog
        open={volumeManageOpen}
        projectId={projectId}
        onClose={() => setVolumeManageOpen(false)}
      />
      {/* AI 卡片批量提取结果弹窗 */}
      <OutlineCardExtractDialog
        open={extractDialogOpen}
        onOpenChange={setExtractDialogOpen}
        cards={extractedCards}
        existingCharacters={existingCharacterNames}
        existingWorlds={existingWorldNames}
        onConfirm={handleConfirmExtract}
        isSubmitting={extractSubmitting}
      />

    </div>
  );
}
