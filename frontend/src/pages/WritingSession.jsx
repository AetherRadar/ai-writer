/**
 * 文枢 WenShape - 深度上下文感知的智能体小说创作系统
 * WenShape - Deep Context-Aware Agent-Based Novel Writing System
 *
 * Copyright © 2025-2026 WenShape Team
 * License: PolyForm Noncommercial License 1.0.0
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import useSWR, { mutate as mutateSWR } from 'swr';
import { motion, AnimatePresence } from 'framer-motion';
import { useParams, useNavigate } from 'react-router-dom';
import { sessionAPI, createWebSocket, draftsAPI, cardsAPI, projectsAPI, volumesAPI, memoryPackAPI, outlineAPI } from '../api';
import { Button, Input, cn } from '../components/ui/core';
import AgentsPanel from '../components/ide/panels/AgentsPanel';
import AgentStatusPanel from '../components/ide/AgentStatusPanel';
import { X, Loader2, Save, Zap, Maximize2, Minimize2, Wand2 } from 'lucide-react';
import { ChapterCreateDialog } from '../components/project/ChapterCreateDialog';
import { IDELayout } from '../components/ide/IDELayout';
import { IDEProvider } from '../context/IDEContext';
import { useIDE } from '../context/IDEContext';
import AnalysisReviewDialog from '../components/writing/AnalysisReviewDialog';
import PreWritingQuestionsDialog from '../components/PreWritingQuestionsDialog';
import StreamingDraftView from '../components/writing/StreamingDraftView';
import { buildLineDiff, applyDiffOpsWithDecisions } from '../lib/diffUtils';
import DiffReviewView from '../components/ide/DiffReviewView';
import SaveMenu from '../components/writing/SaveMenu';
import FanfictionView from './FanfictionView';
import logger from '../utils/logger';
import { useLocale } from '../i18n';
import {
    fetchChapterContent,
    countWords,
    escapeRegExp,
    getSelectionStats,
    normalizeStars,
    parseListInput,
    formatListInput,
    formatRulesInput,
    hasDeletionIntent,
    stabilizeRevisionTail,
} from '../utils/writingSessionHelpers';

/**
 * WritingSessionContent - 写作会话主流程组件
 *
 * 统一的写作 IDE 界面，集成 AI 写作、编辑、分析等功能。
 * 使用 IDE Layout 提供三段式布局（活动栏、左侧面板、编辑区、右侧面板、底部状态栏）。
 *
 * 主要功能：
 * - 实时 WebSocket 连接管理和消息处理
 * - 章节内容编辑和版本管理
 * - AI 驱动的写作、编辑、分析建议
 * - 交互式对话和反馈流程
 * - 草稿保存和历史记录
 *
 * @component
 * @param {boolean} [isEmbedded=false] - 是否为嵌入模式（默认完整模式）
 * @returns {JSX.Element} 写作会话主界面
 */
function WritingSessionContent({ isEmbedded = false }) {
    const { t, locale } = useLocale();
    const requestLanguage = locale === 'en-US' ? 'en' : 'zh';
    const normalizeChapterKey = useCallback((value) => String(value || '').trim().toUpperCase(), []);
    const normalizeVolumeKey = useCallback((value) => String(value || '').trim().toUpperCase(), []);
    const { projectId } = useParams();
    const navigate = useNavigate();
    const { state, dispatch } = useIDE();
    const [draftAutoSaveIndicator, setDraftAutoSaveIndicator] = useState('');

    const [isRewriting, setIsRewriting] = useState(false);
    const mainEditorRef = useRef(null);

    // Card Gen UI state
    const [cardGenStyle, setCardGenStyle] = useState('');
    const [cardGenNote, setCardGenNote] = useState('');
    const [cardGenLoading, setCardGenLoading] = useState(false);

    // ========================================================================
    // 项目和会话基本信息 / Project and Session Information
    // ========================================================================
    // 项目数据状态 / Project data from API
    const [project, setProject] = useState(null);
    const writingLanguage = project?.language === 'en' ? 'en' : 'zh';
    const prevProjectIdRef = useRef(null);

    useEffect(() => {
        if (projectId) {
            projectsAPI.get(projectId).then(res => setProject(res.data));
            dispatch({ type: 'SET_PROJECT_ID', payload: projectId });
            localStorage.setItem('wenshape_last_project', projectId);
        }
    }, [projectId, dispatch]);

    // 项目切换时清理所有会话状态，防止数据污染
    // 使用 useRef 判断 projectId 是否真正变化，避免不必要的清理
    useEffect(() => {
        if (prevProjectIdRef.current && prevProjectIdRef.current !== projectId) {
            // 项目真正切换了：清理所有写作会话状态
            setDiffReview(null);
            setDiffDecisions({});
            setCurrentDraft(null);
            setManualContent('');
            setManualContentByChapter({});
            setMessagesByChapter({});
            setProgressEventsByChapter({});
            setDraftV1(null);
            setSceneBrief(null);
            setFeedback('');
            setChapterInfo({ chapter: null, chapter_title: null, content: null });
            setStatus('idle');
            setSelectionInfo({ start: 0, end: 0, text: '' });
            setAttachedSelection(null);
            setEditScope('document');
            setAiLockedChapter(null);
            if (streamingRef.current?.timer) {
                streamingRef.current.timer();
            }
            streamingRef.current = null;
            setStreamingState({ active: false, progress: 0, current: 0, total: 0 });
        }
        prevProjectIdRef.current = projectId;
    }, [projectId]);



    // UI State
    const [sidebarOpen, setSidebarOpen] = useState(true);
    const [showStartModal, setShowStartModal] = useState(true);
    const [showChapterDialog, setShowChapterDialog] = useState(false);
    const [chapters, setChapters] = useState([]);

    // Save/Analyze UI
    const [isSaving, setIsSaving] = useState(false);
    const [analysisDialogOpen, setAnalysisDialogOpen] = useState(false);
    const [analysisItems, setAnalysisItems] = useState([]);
    const [analysisLoading, setAnalysisLoading] = useState(false);
    const [analysisSaving, setAnalysisSaving] = useState(false);

    // Proposal State
    const [proposals, setProposals] = useState([]);
    const [rejectedItems, setRejectedItems] = useState([]);

    // Logic State
    const [status, setStatus] = useState('idle'); // idle, starting, editing, waiting_feedback, completed
    const [messagesByChapter, setMessagesByChapter] = useState({});
    const [progressEventsByChapter, setProgressEventsByChapter] = useState({});
    const [currentDraft, setCurrentDraft] = useState(null);
    const [manualContent, setManualContent] = useState(''); // Textarea content
    const [manualContentByChapter, setManualContentByChapter] = useState({});
    const [selectionInfo, setSelectionInfo] = useState({ start: 0, end: 0, text: '' });
    const [attachedSelection, setAttachedSelection] = useState(null); // { start, end, text }
    const [editScope, setEditScope] = useState('document'); // document | selection
    const [sceneBrief, setSceneBrief] = useState(null);
    const [draftV1, setDraftV1] = useState(null);
    const [feedback, setFeedback] = useState('');
    const [diffReview, setDiffReview] = useState(null);
    const [diffDecisions, setDiffDecisions] = useState({});
    const lastFeedbackRef = useRef('');
    const lastGeneratedByChapterRef = useRef({});
    const streamBufferByChapterRef = useRef({});
    const streamTextByChapterRef = useRef({});
    const streamFlushRafByChapterRef = useRef({});
    const serverStreamActiveRef = useRef(false);
    const serverStreamUsedRef = useRef(false);
    const streamingChapterKeyRef = useRef(null);

    const [showPreWriteDialog, setShowPreWriteDialog] = useState(false);
    const [preWriteQuestions, setPreWriteQuestions] = useState([]);
    const [pendingStartPayload, setPendingStartPayload] = useState(null);

    const manualContentByChapterRef = useRef(manualContentByChapter);
    useEffect(() => {
        manualContentByChapterRef.current = manualContentByChapter;
    }, [manualContentByChapter]);

    // AI 锁定章：写作/编辑进行中时，右侧面板锁死在该章节（中央可切换查看/手改其他章节）
    const [aiLockedChapter, setAiLockedChapter] = useState(null);
    const aiLockedChapterRef = useRef(aiLockedChapter);
    useEffect(() => {
        aiLockedChapterRef.current = aiLockedChapter;
    }, [aiLockedChapter]);

    // 轻提示（不打断、不强跳转）
    const [notice, setNotice] = useState(null);
    const noticeTimerRef = useRef(null);
    const pushNotice = useCallback((text) => {
        if (!text) return;
        const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        setNotice({ id, text: String(text) });
        if (noticeTimerRef.current) window.clearTimeout(noticeTimerRef.current);
        noticeTimerRef.current = window.setTimeout(() => setNotice(null), 2600);
    }, []);
    useEffect(() => {
        return () => {
            if (noticeTimerRef.current) window.clearTimeout(noticeTimerRef.current);
        };
    }, []);

    // WebSocket
    const wsRef = useRef(null);
    const traceWsRef = useRef(null);
    const wsStatusRef = useRef('disconnected');
    const [isGenerating, setIsGenerating] = useState(false);
    const streamingRef = useRef(null);
    const [streamingState, setStreamingState] = useState({
        active: false,
        progress: 0,
        current: 0,
        total: 0
    });

    // Trace Events for AgentTimeline
    const [traceEvents, setTraceEvents] = useState([]);
    const [agentTraces, setAgentTraces] = useState([]);

    // Chapter Info
    const [chapterInfo, setChapterInfo] = useState({
        chapter: null,
        chapter_title: null,
        content: null,
    });

    const NO_CHAPTER_KEY = '__no_chapter__';
    const activeChapterKey = chapterInfo.chapter ? String(chapterInfo.chapter) : NO_CHAPTER_KEY;

    const activeChapterKeyRef = useRef(activeChapterKey);
    useEffect(() => {
        activeChapterKeyRef.current = activeChapterKey;
    }, [activeChapterKey]);


    // Draft version state
    const [currentDraftVersion, setCurrentDraftVersion] = useState('v1');

    // Agent mode (for AgentStatusPanel)
    const [agentMode, setAgentMode] = useState('create'); // 'outline' | 'create' | 'edit'
    const [contextDebugByChapter, setContextDebugByChapter] = useState({});
    const [editContextMode, setEditContextMode] = useState('quick'); // quick | full
    const [outlineActionBusy, setOutlineActionBusy] = useState(false);
    const [agentInputSeed, setAgentInputSeed] = useState(null);
    const [chapterCanvasMode, setChapterCanvasMode] = useState('draft'); // 'draft' | 'outline'
    const [outlineEditorScope, setOutlineEditorScope] = useState('chapter'); // 'master' | 'volume' | 'chapter'
    const [outlineContentByScope, setOutlineContentByScope] = useState({
        master: '',
        volume: '',
        chapter: '',
    });
    const [outlineSavedByScope, setOutlineSavedByScope] = useState({
        master: '',
        volume: '',
        chapter: '',
    });
    const [outlineScopeIds, setOutlineScopeIds] = useState({
        master: null,
        volume: null,
        chapter: null,
    });
    const [chapterOutlineLoading, setChapterOutlineLoading] = useState(false);
    const [chapterOutlineError, setChapterOutlineError] = useState('');

    const agentBusy =
        Boolean(aiLockedChapter) &&
        (Boolean(diffReview) ||
            showPreWriteDialog ||
            status === 'starting' ||
            status === 'waiting_user_input' ||
            isGenerating ||
            streamingState.active);

    const agentChapterKey = agentBusy
        ? String(aiLockedChapter)
        : activeChapterKey;

    const isStreamingForActiveChapter =
        streamingState.active && streamingChapterKeyRef.current === activeChapterKey;

    const isDiffReviewForActiveChapter =
        Boolean(diffReview) && String(diffReview?.chapterKey || '') === activeChapterKey;

    const lockedOnActiveChapter =
        agentBusy && String(aiLockedChapter || '') === activeChapterKey;

    const canUseWriter = countWords(
        agentBusy
            ? (manualContentByChapter[String(aiLockedChapter || '')] ?? '')
            : manualContent,
        writingLanguage
    ) === 0;

    const messages = messagesByChapter[agentChapterKey] || [];
    const progressEvents = progressEventsByChapter[agentChapterKey] || [];
    const contextDebug = contextDebugByChapter[agentChapterKey] || null;

    useEffect(() => {
        if (!isGenerating && !canUseWriter && agentMode === 'create') {
            setAgentMode('edit');
        }
    }, [canUseWriter, agentMode, isGenerating]);

    useEffect(() => {
        if (agentMode !== 'edit') return;
        if (!attachedSelection?.text?.trim()) {
            if (editScope === 'selection') setEditScope('document');
            return;
        }
        if (editScope === 'document') setEditScope('selection');
    }, [agentMode, attachedSelection, editScope]);

    useEffect(() => {
        if (!aiLockedChapter) return;
        if (agentBusy) return;
        setAiLockedChapter(null);
    }, [aiLockedChapter, agentBusy]);

    useEffect(() => {
        if (!projectId) return;

        const wsController = createWebSocket(
            projectId,
            (data) => {
                const wsChapterKey = data?.chapter ? String(data.chapter) : NO_CHAPTER_KEY;
                if (data.type === 'start_ack') {
                    appendProgressEvent({ stage: 'session_start', message: t('writingSession.sessionStarted') }, wsChapterKey);
                    setChapterCanvasMode('draft');
                }
                if (data.type === 'stream_start') {
                    if (wsChapterKey && wsChapterKey !== NO_CHAPTER_KEY) {
                        setAiLockedChapter(wsChapterKey);
                    }
                    streamingChapterKeyRef.current = wsChapterKey;
                    setChapterCanvasMode('draft');
                    stopStreaming();
                    clearDiffReview();
                    serverStreamActiveRef.current = true;
                    serverStreamUsedRef.current = true;
                    streamBufferByChapterRef.current[wsChapterKey] = '';
                    streamTextByChapterRef.current[wsChapterKey] = '';
                    if (streamFlushRafByChapterRef.current[wsChapterKey]) {
                        window.cancelAnimationFrame(streamFlushRafByChapterRef.current[wsChapterKey]);
                        streamFlushRafByChapterRef.current[wsChapterKey] = null;
                    }
                    lastGeneratedByChapterRef.current[wsChapterKey] = true;
                    setManualContentByChapter((prev) => ({ ...(prev || {}), [wsChapterKey]: '' }));
                    if (activeChapterKeyRef.current === wsChapterKey) {
                        setManualContent('');
                    }
                    setIsGenerating(true);
                    setStreamingState({
                        active: true,
                        progress: 0,
                        current: 0,
                        total: data.total || 0
                    });
                }
                if (data.type === 'token' && typeof data.content === 'string') {
                    if (!serverStreamActiveRef.current) {
                        return;
                    }
                    streamBufferByChapterRef.current[wsChapterKey] =
                        (streamBufferByChapterRef.current[wsChapterKey] || '') + data.content;
                    if (!streamFlushRafByChapterRef.current[wsChapterKey]) {
                        streamFlushRafByChapterRef.current[wsChapterKey] = window.requestAnimationFrame(() => {
                            const buffered = streamBufferByChapterRef.current[wsChapterKey] || '';
                            const nextText = (streamTextByChapterRef.current[wsChapterKey] || '') + buffered;
                            streamTextByChapterRef.current[wsChapterKey] = nextText;
                            streamBufferByChapterRef.current[wsChapterKey] = '';
                            setManualContentByChapter((prev) => ({ ...(prev || {}), [wsChapterKey]: nextText }));
                            if (activeChapterKeyRef.current === wsChapterKey) {
                                setManualContent(nextText);
                            }
                            const current = nextText.length;
                            setStreamingState((prev) => ({
                                ...prev,
                                current,
                                progress: prev.total ? Math.round((current / prev.total) * 100) : prev.progress
                            }));
                            streamFlushRafByChapterRef.current[wsChapterKey] = null;
                        });
                    }
                }
                if (data.type === 'stream_end') {
                    if (streamFlushRafByChapterRef.current[wsChapterKey]) {
                        window.cancelAnimationFrame(streamFlushRafByChapterRef.current[wsChapterKey]);
                        streamFlushRafByChapterRef.current[wsChapterKey] = null;
                    }
                    const buffered = streamBufferByChapterRef.current[wsChapterKey] || '';
                    const combined = (streamTextByChapterRef.current[wsChapterKey] || '') + buffered;
                    streamTextByChapterRef.current[wsChapterKey] = combined;
                    streamBufferByChapterRef.current[wsChapterKey] = '';
                    const finalText = data.draft?.content || combined;
                    serverStreamActiveRef.current = false;
                    streamingChapterKeyRef.current = null;
                    setManualContentByChapter((prev) => ({ ...(prev || {}), [wsChapterKey]: finalText }));
                    if (activeChapterKeyRef.current === wsChapterKey) {
                        setManualContent(finalText);
                    }
                    setStreamingState({
                        active: false,
                        progress: 100,
                        current: finalText.length,
                        total: finalText.length
                    });
                    setIsGenerating(false);
                    if (activeChapterKeyRef.current === wsChapterKey) {
                        dispatch({ type: 'SET_WORD_COUNT', payload: countWords(finalText, writingLanguage) });
                        dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
                    } else {
                        pushNotice(t('writingSession.chapterDone').replace('{n}', wsChapterKey));
                    }
                    if (data.draft) {
                        setCurrentDraft(data.draft);
                        setCurrentDraftVersion(data.draft.version || currentDraftVersion);
                    }
                    if (data.proposals) {
                        setProposals(data.proposals);
                    }
                    setStatus('waiting_feedback');
                    addMessage('assistant', t('writingSession.draftGenerated'), wsChapterKey);
                }
                if (data.type === 'scene_brief') handleSceneBrief(data.data, wsChapterKey);
                if (data.type === 'draft_v1') handleDraftV1(data.data, wsChapterKey);
                if (data.type === 'final_draft') handleFinalDraft(data.data, wsChapterKey);
                if (data.type === 'error') addMessage('error', data.message, wsChapterKey);

                // Handle backend status updates (progress)
                if (data.status && data.message) {
                    if (data.stage) {
                        const event = {
                            id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                            timestamp: data.timestamp || Date.now(),
                            stage: data.stage,
                            round: data.round,
                            message: data.message,
                            queries: data.queries || [],
                            hits: data.hits,
                            top_sources: data.top_sources || [],
                            stop_reason: data.stop_reason,
                            note: data.note
                        };
                        appendProgressEvent(event, wsChapterKey);
                    } else {
                        appendProgressEvent({ stage: 'system', message: data.message, note: data.note }, wsChapterKey);
                    }
                }
            },
            {
                onStatus: (status) => {
                    if (wsStatusRef.current !== status) {
                        if (status === 'reconnecting') {
                            appendProgressEvent({ stage: 'connection', message: t('writingSession.connectionReconnecting') }, NO_CHAPTER_KEY);
                        }
                        if (status === 'connected' && wsStatusRef.current === 'reconnecting') {
                            appendProgressEvent({ stage: 'connection', message: t('writingSession.connectionRestored') }, NO_CHAPTER_KEY);
                        }
                        if (status === 'disconnected') {
                            appendProgressEvent({ stage: 'connection', message: t('writingSession.connectionLost') }, NO_CHAPTER_KEY);
                        }
                    }

                    wsStatusRef.current = status;
                }
            }
        );

        wsRef.current = wsController;

        // Connect to Trace WebSocket for AgentTimeline
        const wsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
        const wsHost = window.location.host;
        const traceWs = new WebSocket(`${wsProtocol}://${wsHost}/ws/trace`);

        traceWs.onmessage = (event) => {
            const data = JSON.parse(event.data);
            if (data.type === 'trace_event' && data.payload) {
                setTraceEvents(prev => [...prev.slice(-99), data.payload]); // Keep last 100 events
            }
            if (data.type === 'agent_trace_update' && data.payload) {
                setAgentTraces(prev => {
                    const existing = prev.findIndex(t => t.agent_name === data.payload.agent_name);
                    if (existing >= 0) {
                        const updated = [...prev];
                        updated[existing] = data.payload;
                        return updated;
                    }
                    return [...prev, data.payload];
                });
            }
        };

        traceWsRef.current = traceWs;

        return () => {
            if (wsController) wsController.close();
            if (traceWs) traceWs.close();
        };
    }, [projectId]);

    // Card State
    const [activeCard, setActiveCard] = useState(null);
    const [cardForm, setCardForm] = useState({
        name: '',
        description: '',
        aliases: '',
        stars: 1,
        category: '',
        rules: '',
        immutable: 'unset'
    });

    // SWR for Chapter Content
    const { data: loadedContent, mutate: mutateChapter } = useSWR(
        chapterInfo.chapter ? ['chapter', projectId, chapterInfo.chapter] : null,
        fetchChapterContent,
        {
            revalidateOnFocus: false,
            dedupingInterval: 60000, // Cache for 1 minute before checking again
            keepPreviousData: false // Don't show previous chapter data while loading (we handle this with manualContent update)
        }
    );

    const { data: volumes = [] } = useSWR(
        projectId ? ['volumes', projectId] : null,
        () => volumesAPI.list(projectId).then(res => res.data),
        { revalidateOnFocus: false }
    );

    const memoryPackChapter = agentBusy ? aiLockedChapter : chapterInfo.chapter;
    const { data: memoryPackStatus } = useSWR(
        projectId && memoryPackChapter ? ['memory-pack', projectId, memoryPackChapter] : null,
        () => memoryPackAPI.getStatus(projectId, memoryPackChapter).then(res => res.data),
        { revalidateOnFocus: false, refreshInterval: 5000 }
    );

    // Sync SWR data to manualContent
    useEffect(() => {
        if (loadedContent === undefined || state.unsavedChanges) {
            return;
        }
        if (isStreamingForActiveChapter || lockedOnActiveChapter || isDiffReviewForActiveChapter) {
            return;
        }

        const chapterKey = activeChapterKey;
        if (chapterKey === NO_CHAPTER_KEY) return;

        const lastGeneratedForChapter = Boolean(lastGeneratedByChapterRef.current?.[chapterKey]);
        if (lastGeneratedForChapter && manualContent && !(loadedContent || '').trim()) {
            return;
        }

        setManualContentByChapter((prev) => ({ ...(prev || {}), [chapterKey]: loadedContent }));
        setManualContent(loadedContent);
        dispatch({ type: 'SET_WORD_COUNT', payload: countWords(loadedContent, writingLanguage) });
        dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
        lastGeneratedByChapterRef.current[chapterKey] = false;
        // Only center cursor if we just switched chapters (optional optimization)
        // dispatch({ type: 'SET_CURSOR_POSITION', payload: { line: 1, column: 1 } });
    }, [
        loadedContent,
        dispatch,
        manualContent,
        state.unsavedChanges,
        activeChapterKey,
        NO_CHAPTER_KEY,
        isStreamingForActiveChapter,
        lockedOnActiveChapter,
        isDiffReviewForActiveChapter,
    ]);

    useEffect(() => {
        loadChapters();
    }, [projectId]);

    useEffect(() => {
        let active = true;
        const loadTitle = async () => {
            if (!projectId || !chapterInfo.chapter) return;
            if (chapterInfo.chapter_title && chapterInfo.chapter_title.trim()) return;
            try {
                const summaryResp = await draftsAPI.getSummary(projectId, chapterInfo.chapter);
                const summary = summaryResp.data || {};
                const title = summary.title || summary.chapter_title || '';
                if (active && title) {
                    setChapterInfo((prev) => ({ ...prev, chapter_title: title }));
                }
            } catch (e) {
                // ignore missing summary
            }
        };
        loadTitle();
        return () => {
            active = false;
        };
    }, [projectId, chapterInfo.chapter, chapterInfo.chapter_title]);

    useEffect(() => {
        let active = true;
        const loadChapterOutline = async () => {
            if (!projectId) {
                setOutlineContentByScope({ master: '', volume: '', chapter: '' });
                setOutlineSavedByScope({ master: '', volume: '', chapter: '' });
                setOutlineScopeIds({ master: null, volume: null, chapter: null });
                setChapterOutlineError('');
                return;
            }
            setChapterOutlineLoading(true);
            setChapterOutlineError('');
            try {
                const chapterKey = normalizeChapterKey(chapterInfo.chapter);
                const resp = await outlineAPI.get(projectId);
                const payload = resp?.data || {};

                const masterContent = String(payload?.master?.content || '');
                if (!chapterInfo.chapter) {
                    if (!active) return;
                    setOutlineEditorScope('master');
                    setOutlineScopeIds({ master: 'MASTER', volume: null, chapter: null });
                    setOutlineContentByScope({ master: masterContent, volume: '', chapter: '' });
                    setOutlineSavedByScope({ master: masterContent, volume: '', chapter: '' });
                    return;
                }

                const chapterItem = (payload?.chapters || []).find((item) => normalizeChapterKey(item?.chapter_id) === chapterKey);
                const inferredVolume = normalizeVolumeKey(
                    chapterItem?.volume_id
                    || (chapterKey.match(/^V\d+/i)?.[0])
                    || state.selectedVolumeId
                    || 'V1'
                );
                const volumeItem = (payload?.volumes || []).find((item) => normalizeVolumeKey(item?.volume_id) === inferredVolume);
                const volumeContent = String(volumeItem?.content || '');
                const chapterContent = String(chapterItem?.content || '');

                if (!active) return;
                setOutlineEditorScope('chapter');
                setOutlineScopeIds({
                    master: 'MASTER',
                    volume: inferredVolume,
                    chapter: chapterKey,
                });
                setOutlineContentByScope({
                    master: masterContent,
                    volume: volumeContent,
                    chapter: chapterContent,
                });
                setOutlineSavedByScope({
                    master: masterContent,
                    volume: volumeContent,
                    chapter: chapterContent,
                });
            } catch (e) {
                if (!active) return;
                setChapterOutlineError(e?.response?.data?.detail || e?.message || t('error.loadFailed'));
            } finally {
                if (active) {
                    setChapterOutlineLoading(false);
                }
            }
        };
        loadChapterOutline();
        const handleOutlineUpdated = () => { if (active) loadChapterOutline(); };
        window.addEventListener('wenshape:outline-updated', handleOutlineUpdated);
        return () => {
            active = false;
            window.removeEventListener('wenshape:outline-updated', handleOutlineUpdated);
        };
    }, [projectId, chapterInfo.chapter, state.selectedVolumeId, normalizeChapterKey, normalizeVolumeKey, t]);

    // 监听 Context 中的 Dialog 状态
    useEffect(() => {
        if (state.createChapterDialogOpen !== showChapterDialog) {
            setShowChapterDialog(state.createChapterDialogOpen);
        }
    }, [state.createChapterDialogOpen]);

    const loadChapters = async () => {
        try {
            const resp = await draftsAPI.listChapters(projectId);
            const list = resp.data || [];
            setChapters(list);
        } catch (e) {
            logger.error('Failed to load chapters:', e);
        }
    };

    const handleChapterSelect = async (chapter, presetTitle = '') => {
        const nextChapterKey = chapter ? String(chapter) : NO_CHAPTER_KEY;
        const lockedKey = aiLockedChapterRef.current ? String(aiLockedChapterRef.current) : null;
        const preserveAgent = Boolean(lockedKey) && agentBusy;

        // 缓存当前章节内容，避免切章丢失
        if (chapterInfo.chapter) {
            const currentKey = String(chapterInfo.chapter);
            setManualContentByChapter((prev) => ({ ...(prev || {}), [currentKey]: manualContent }));
        }

        // 非写作/编辑进行中：切章时清理流式与差异态
        if (!preserveAgent) {
            stopStreaming();
            clearDiffReview();
            setStatus('editing');
        } else if (lockedKey && nextChapterKey !== lockedKey) {
            pushNotice(t('writingSession.chapterLockedNotice').replace('{n}', lockedKey).replace('{m}', nextChapterKey));
        }

        // Just set the chapter, let SWR handle fetching
        setChapterInfo({ chapter, chapter_title: presetTitle || '', content: '' }); // content will be filled by SWR
        setSelectionInfo({ start: 0, end: 0, text: '' });
        setAttachedSelection(null);
        setEditScope('document');

        // 优先使用本地缓存，减少切章时的"空白闪烁"
        if (nextChapterKey && nextChapterKey !== NO_CHAPTER_KEY) {
            const cached = manualContentByChapterRef.current?.[nextChapterKey];
            if (typeof cached === 'string') {
                setManualContent(cached);
                dispatch({ type: 'SET_WORD_COUNT', payload: countWords(cached, writingLanguage) });
                dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
            } else {
                setManualContent('');
                dispatch({ type: 'SET_WORD_COUNT', payload: 0 });
                dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
            }
        }
        try {
            const summaryResp = await draftsAPI.getSummary(projectId, chapter);
            const summary = summaryResp.data || {};
            const normalizedChapter = summary.chapter || chapter;
            const title = summary.title || summary.chapter_title || '';
            setChapterInfo((prev) => ({
                ...prev,
                chapter: normalizedChapter,
                chapter_title: title || prev.chapter_title || ''
            }));
            if (normalizedChapter !== chapter) {
                dispatch({
                    type: 'SET_ACTIVE_DOCUMENT',
                    payload: { type: 'chapter', id: normalizedChapter, title: title || presetTitle || '' }
                });
            }
        } catch (e) {
            // Summary may not exist yet.
        }
    };

    const handleChapterCreate = async (chapterData) => {
        // Handle object from ChapterCreateDialog or direct arguments
        const chapterNum = typeof chapterData === 'object' ? chapterData.id : chapterData;
        const chapterTitle = typeof chapterData === 'object' ? chapterData.title : arguments[1];

        // Persist the new chapter immediately
        setIsSaving(true);
        let normalizedChapter = chapterNum;
        try {
            const resp = await draftsAPI.updateContent(projectId, chapterNum, {
                content: '',
                title: chapterTitle
            });
            normalizedChapter = resp.data?.chapter || chapterNum;
            addMessage('system', t('writingSession.chapterCreated').replace('{id}', normalizedChapter), normalizedChapter);
            dispatch({
                type: 'SET_ACTIVE_DOCUMENT',
                payload: { type: 'chapter', id: normalizedChapter, title: chapterTitle || '' }
            });
            await mutateSWR(`/drafts/${projectId}/chapters`);
            await mutateSWR(`/drafts/${projectId}/summaries`);
            await mutateSWR(`/outline/${projectId}`);
        } catch (e) {
            addMessage('error', t('writingSession.chapterCreateFailed') + e.message);
        } finally {
            setIsSaving(false);
        }

        setChapterInfo({ chapter: normalizedChapter, chapter_title: chapterTitle, content: '' });
        setManualContent('');
        stopStreaming();
        clearDiffReview();
        setAgentMode('outline');
        setChapterCanvasMode('outline');
        if (state.activeActivity !== 'explorer') {
            dispatch({ type: 'SET_ACTIVE_PANEL', payload: 'explorer' });
        } else if (!state.sidePanelVisible) {
            dispatch({ type: 'TOGGLE_LEFT_PANEL' });
        }
        setShowChapterDialog(false);
        setStatus('idle');
        await loadChapters();
    };

    const addMessage = useCallback((type, content, chapterOverride = null) => {
        const key = chapterOverride ? String(chapterOverride) : activeChapterKey;
        if (!key || key === NO_CHAPTER_KEY) {
            return;
        }
        setMessagesByChapter((prev) => {
            const next = { ...(prev || {}) };
            const existing = Array.isArray(next[key]) ? next[key] : [];
            next[key] = [...existing, { type, content, time: new Date() }].slice(-200);
            return next;
        });
    }, [activeChapterKey, NO_CHAPTER_KEY]);

    const appendProgressEvent = useCallback((partial, chapterOverride = null) => {
        const key = chapterOverride ? String(chapterOverride) : activeChapterKey;
        if (!key || key === NO_CHAPTER_KEY) {
            return;
        }
        const event = {
            id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            timestamp: Date.now(),
            ...partial
        };
        setProgressEventsByChapter((prev) => {
            const next = { ...(prev || {}) };
            const existing = Array.isArray(next[key]) ? next[key] : [];
            next[key] = [...existing.slice(-199), event];
            return next;
        });
    }, [activeChapterKey, NO_CHAPTER_KEY]);

    // Auto Save（类似 VSCode：检测到变更后自动保存）
    const autosaveTimerRef = useRef(null);
    const autosaveInFlightRef = useRef(false);
    const autosaveLastPayloadRef = useRef({ chapter: null, content: null, title: null });

    useEffect(() => {
        if (!state.unsavedChanges) return;
        if (!projectId || !chapterInfo.chapter) return;
        if (isStreamingForActiveChapter || lockedOnActiveChapter || isDiffReviewForActiveChapter) return;

        const nextContent = String(manualContent || '');
        const nextTitle = String(chapterInfo.chapter_title || '').trim() || null;

        const last = autosaveLastPayloadRef.current || {};
        const sameChapter = String(last.chapter || '') === String(chapterInfo.chapter);
        const sameContent = sameChapter && String(last.content || '') === nextContent;
        const sameTitle = sameChapter && (last.title || null) === nextTitle;
        if (sameContent && sameTitle) return;

        if (autosaveTimerRef.current) {
            window.clearTimeout(autosaveTimerRef.current);
        }

        autosaveTimerRef.current = window.setTimeout(async () => {
            if (autosaveInFlightRef.current) return;
            autosaveInFlightRef.current = true;
            try {
                const payload = { content: nextContent };
                if (nextTitle) payload.title = nextTitle;

                const resp = await draftsAPI.autosaveContent(projectId, chapterInfo.chapter, payload);
                if (resp.data?.success) {
                    autosaveLastPayloadRef.current = { chapter: chapterInfo.chapter, content: nextContent, title: nextTitle };
                    await mutateChapter(nextContent, false);
                    if (nextTitle) {
                        await mutateSWR(`/drafts/${projectId}/summaries`);
                        await mutateSWR(`/outline/${projectId}`);
                    }
                    dispatch({ type: 'SET_AUTOSAVED' });
                }
            } catch (e) {
                dispatch({ type: 'SET_UNSAVED' });
                addMessage('error', t('writingSession.autoSaveFailed') + (e.response?.data?.detail || e.message));
            } finally {
                autosaveInFlightRef.current = false;
            }
        }, 1200);

        return () => {
            if (autosaveTimerRef.current) {
                window.clearTimeout(autosaveTimerRef.current);
                autosaveTimerRef.current = null;
            }
        };
    }, [
        state.unsavedChanges,
        projectId,
        chapterInfo.chapter,
        chapterInfo.chapter_title,
        manualContent,
        isStreamingForActiveChapter,
        lockedOnActiveChapter,
        isDiffReviewForActiveChapter,
        mutateChapter,
        dispatch,
        addMessage,
    ]);

    const clearDiffReview = useCallback(() => {
        setDiffReview(null);
        setDiffDecisions({});
    }, []);

    const stopStreaming = useCallback(() => {
        if (streamingRef.current?.timer) {
            streamingRef.current.timer();
        }
        streamingRef.current = null;
        setStreamingState({
            active: false,
            progress: 0,
            current: 0,
            total: 0
        });
    }, []);

    // 当资源管理器清空/删除当前章节时，主动回到空态，避免编辑区残留旧章节内容
    useEffect(() => {
        if (state.activeDocument) return;
        stopStreaming();
        clearDiffReview();
        setActiveCard(null);
        setChapterInfo({ chapter: null, chapter_title: null, content: null });
        setManualContent('');
        setStatus('idle');
    }, [clearDiffReview, state.activeDocument, stopStreaming]);

    const startStreamingDraft = useCallback((targetText, options = {}) => {
        const { onComplete, chapterKey } = options;
        const resolvedChapterKey = chapterKey ? String(chapterKey) : activeChapterKeyRef.current;
        stopStreaming();
        streamingChapterKeyRef.current = resolvedChapterKey;

        const safeText = targetText || '';
        if (!safeText) {
            setManualContentByChapter((prev) => ({ ...(prev || {}), [resolvedChapterKey]: '' }));
            if (activeChapterKeyRef.current === resolvedChapterKey) {
                setManualContent('');
            }
            setIsGenerating(false);
            streamingChapterKeyRef.current = null;
            onComplete?.();
            return;
        }

        dispatch({ type: 'SET_UNSAVED' });
        setIsGenerating(true);
        const total = safeText.length;
        const charsPerSecond = Math.min(420, Math.max(180, Math.round(total / 3)));
        let index = 0;
        let lastTs = performance.now();
        let rafId = null;

        setManualContentByChapter((prev) => ({ ...(prev || {}), [resolvedChapterKey]: '' }));
        if (activeChapterKeyRef.current === resolvedChapterKey) {
            setManualContent('');
        }
        setStreamingState({
            active: true,
            progress: 0,
            current: 0,
            total
        });
        lastGeneratedByChapterRef.current[resolvedChapterKey] = true;

        const initialBurst = Math.min(total, Math.max(12, Math.floor(total * 0.03)));
        if (initialBurst > 0) {
            index = initialBurst;
            const burstText = safeText.slice(0, index);
            setManualContentByChapter((prev) => ({ ...(prev || {}), [resolvedChapterKey]: burstText }));
            if (activeChapterKeyRef.current === resolvedChapterKey) {
                setManualContent(burstText);
            }
            setStreamingState({
                active: index < total,
                progress: Math.round((index / total) * 100),
                current: index,
                total
            });
        }

        const tick = (ts) => {
            const delta = Math.max(0, ts - lastTs);
            const increment = Math.max(1, Math.floor((delta / 1000) * charsPerSecond));
            index = Math.min(total, index + increment);
            lastTs = ts;

            const partial = safeText.slice(0, index);
            setManualContentByChapter((prev) => ({ ...(prev || {}), [resolvedChapterKey]: partial }));
            if (activeChapterKeyRef.current === resolvedChapterKey) {
                setManualContent(partial);
            }
            setStreamingState({
                active: index < total,
                progress: Math.round((index / total) * 100),
                current: index,
                total
            });

            if (index >= total) {
                streamingRef.current = null;
                setIsGenerating(false);
                streamingChapterKeyRef.current = null;
                if (activeChapterKeyRef.current === resolvedChapterKey) {
                    dispatch({ type: 'SET_WORD_COUNT', payload: countWords(safeText, writingLanguage) });
                    dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
                } else {
                    pushNotice(t('writingSession.chapterDone').replace('{n}', resolvedChapterKey));
                }
                onComplete?.();
                return;
            }

            rafId = window.requestAnimationFrame(tick);
        };

        rafId = window.requestAnimationFrame(tick);
        streamingRef.current = {
            timer: () => {
                if (rafId) window.cancelAnimationFrame(rafId);
            }
        };
    }, [dispatch, stopStreaming, pushNotice]);

    useEffect(() => {
        return () => {
            stopStreaming();
        };
    }, [stopStreaming]);

    // 监听 Context 中的文档选择（章节或卡片）
    useEffect(() => {
        if (!state.activeDocument) return;

        if (state.activeDocument.type === 'chapter' && state.activeDocument.id) {
            setActiveCard(null); // Clear card state
            const presetTitle =
                state.activeDocument.data?.title ||
                state.activeDocument.data?.chapter_title ||
                state.activeDocument.title ||
                state.activeDocument.chapter_title ||
                '';
            handleChapterSelect(state.activeDocument.id, presetTitle);
        } else if (['character', 'world'].includes(state.activeDocument.type)) {
            // Switch to Card Mode
            stopStreaming();
            clearDiffReview();
            setChapterInfo({ chapter: null, chapter_title: null, content: null });

            // Initial setup with basic info
            const cardData = state.activeDocument.data || { name: state.activeDocument.id };
            const originalName = state.activeDocument.id || cardData.name || '';
            const isNew = Boolean(state.activeDocument.isNew || cardData.isNew || !originalName);
            setActiveCard({
                ...cardData,
                type: state.activeDocument.type,
                isNew,
                originalName
            });
            setCardForm({
                name: cardData.name || '',
                description: '',
                aliases: formatListInput(cardData.aliases),
                stars: normalizeStars(cardData.stars),
                category: cardData.category || '',
                rules: formatRulesInput(cardData.rules),
                immutable: cardData.immutable === true ? 'true' : cardData.immutable === false ? 'false' : 'unset'
            });
            setStatus('card_editing');

            // Fetch full details
            const fetchCardDetails = async () => {
                try {
                    let resp;
                    if (state.activeDocument.type === 'character') {
                        resp = await cardsAPI.getCharacter(projectId, state.activeDocument.id);
                    } else {
                        resp = await cardsAPI.getWorld(projectId, state.activeDocument.id);
                    }
                    const fullData = resp?.data || {};
                    setCardForm({
                        name: fullData.name || cardData.name || '',
                        description: fullData.description || '',
                        aliases: formatListInput(fullData.aliases),
                        stars: normalizeStars(fullData.stars),
                        category: fullData.category || '',
                        rules: formatRulesInput(fullData.rules),
                        immutable: fullData.immutable === true ? 'true' : fullData.immutable === false ? 'false' : 'unset'
                    });
                } catch (e) {
                    logger.error("Failed to fetch card details", e);
                    addMessage('error', t('writingSession.loadCardFailed') + e.message);
                }
            };

            if (state.activeDocument.id) {
                fetchCardDetails();
            }
        }
    }, [state.activeDocument, stopStreaming, clearDiffReview, projectId]);

    // Handlers
    const handleStart = async (chapter, mode, instruction = null) => {
        if (!chapter) {
            alert(t('writingSession.selectChapterFirst'));
            return;
        }
        const chapterKey = String(chapter);
        setAiLockedChapter(chapterKey);
        setManualContentByChapter((prev) => {
            const next = { ...(prev || {}) };
            if (next[chapterKey] === undefined) {
                next[chapterKey] = manualContent;
            }
            return next;
        });

        stopStreaming();
        clearDiffReview();
        serverStreamActiveRef.current = false;
        serverStreamUsedRef.current = false;
        setStatus('starting');
        setIsGenerating(true);
        setContextDebugByChapter((prev) => ({ ...(prev || {}), [chapterKey]: null }));
        setProgressEventsByChapter((prev) => ({ ...(prev || {}), [chapterKey]: [] }));

        setAgentMode('create');
        appendProgressEvent({ stage: 'session_start', message: t('writingSession.preparingContext') }, chapterKey);

        try {
            const payload = {
                language: requestLanguage,
                chapter: String(chapter),
                chapter_title: chapterInfo.chapter_title || t('writingSession.chapterFallback').replace('{n}', chapter),
                chapter_goal: instruction || 'Auto-generation based on context',
                target_word_count: 3000
            };

            const resp = await sessionAPI.start(projectId, payload);
            const result = resp.data;

            if (!result.success) {
                throw new Error(result.error || t('writingSession.sessionStartFailed'));
            }
            if (result.status === 'waiting_user_input' && result.questions?.length) {
                if (result.scene_brief) {
                    setSceneBrief(result.scene_brief);
                    appendProgressEvent({ stage: 'scene_brief', message: t('writingSession.sceneBriefGenerated'), payload: result.scene_brief }, chapterKey);
                }
                setContextDebugByChapter((prev) => ({ ...(prev || {}), [chapterKey]: result.context_debug || null }));
                setPreWriteQuestions(result.questions);
                setPendingStartPayload(payload);
                setShowPreWriteDialog(true);
                setStatus('waiting_user_input');
                setIsGenerating(false);
                return;
            }

            if (result.scene_brief) {
                setSceneBrief(result.scene_brief);
                appendProgressEvent({ stage: 'scene_brief', message: t('writingSession.sceneBriefGenerated'), payload: result.scene_brief }, chapterKey);
            }
            setContextDebugByChapter((prev) => ({ ...(prev || {}), [chapterKey]: result.context_debug || null }));

            if (result.draft_v1) {
                setDraftV1(result.draft_v1);
            }

            const finalDraft = result.draft_v2 || result.draft_v1;
            const shouldUseHttpDraft = !serverStreamActiveRef.current && !serverStreamUsedRef.current;
            if (finalDraft && shouldUseHttpDraft) {
                setCurrentDraft(finalDraft);
                setCurrentDraftVersion(result.draft_v2 ? 'v2' : 'v1');
                startStreamingDraft(finalDraft.content || '', { chapterKey });
            } else if (shouldUseHttpDraft) {
                setIsGenerating(false);
            }

            if (result.proposals) {
                setProposals(result.proposals);
            }

            setStatus('waiting_feedback');
            if (!serverStreamActiveRef.current && !serverStreamUsedRef.current) {
                addMessage('assistant', t('writingSession.draftGenerated'), chapterKey);
            }
        } catch (e) {
            addMessage('error', t('writingSession.startFailed') + e.message, chapterKey);
            setStatus('idle');
            setIsGenerating(false);
        }
    };

    const handlePreWriteConfirm = async (answers) => {
        if (!pendingStartPayload) return;
        const startPayload = pendingStartPayload;
        const chapterKey = startPayload?.chapter ? String(startPayload.chapter) : activeChapterKey;
        if (chapterKey && chapterKey !== NO_CHAPTER_KEY) {
            setAiLockedChapter(chapterKey);
        }
        setShowPreWriteDialog(false);
        stopStreaming();
        clearDiffReview();
        serverStreamActiveRef.current = false;
        serverStreamUsedRef.current = false;
        setIsGenerating(true);

        try {
            const resp = await sessionAPI.answerQuestions(projectId, {
                ...startPayload,
                answers
            });
            const result = resp.data;

            if (!result.success) {
                throw new Error(result.error || t('writingSession.answerFailed'));
            }

            if (result.status === 'waiting_user_input' && result.questions?.length) {
                setContextDebugByChapter((prev) => ({ ...(prev || {}), [chapterKey]: result.context_debug || null }));
                if (result.scene_brief) {
                    setSceneBrief(result.scene_brief);
                    appendProgressEvent({ stage: 'scene_brief', message: t('writingSession.sceneBriefGenerated'), payload: result.scene_brief }, chapterKey);
                }
                setPreWriteQuestions(result.questions);
                setPendingStartPayload(startPayload);
                setShowPreWriteDialog(true);
                setStatus('waiting_user_input');
                setIsGenerating(false);
                return;
            }

            if (result.scene_brief) {
                setSceneBrief(result.scene_brief);
                appendProgressEvent({ stage: 'scene_brief', message: t('writingSession.sceneBriefGenerated'), payload: result.scene_brief }, chapterKey);
            }
            setContextDebugByChapter((prev) => ({ ...(prev || {}), [chapterKey]: result.context_debug || null }));
            if (result.draft_v1) {
                setDraftV1(result.draft_v1);
            }

            const finalDraft = result.draft_v2 || result.draft_v1;
            const shouldUseHttpDraft = !serverStreamActiveRef.current && !serverStreamUsedRef.current;
            if (finalDraft && shouldUseHttpDraft) {
                setCurrentDraft(finalDraft);
                setCurrentDraftVersion(result.draft_v2 ? 'v2' : 'v1');
                startStreamingDraft(finalDraft.content || '', { chapterKey });
            } else if (shouldUseHttpDraft) {
                setIsGenerating(false);
            }

            if (result.proposals) {
                setProposals(result.proposals);
            }

            setStatus('waiting_feedback');
            if (!serverStreamActiveRef.current && !serverStreamUsedRef.current) {
                addMessage('assistant', t('writingSession.draftGenerated'), chapterKey);
            }
            setPendingStartPayload(null);
        } catch (e) {
            addMessage('error', t('writingSession.generateFailed') + e.message, chapterKey);
            setStatus('idle');
            setIsGenerating(false);
        }
    };

    const handlePreWriteSkip = () => {
        handlePreWriteConfirm([]);
    };

    const handleSceneBrief = (data, chapterOverride = null) => {
        setSceneBrief(data);
        appendProgressEvent({ stage: 'scene_brief', message: t('writingSession.sceneBriefGenerated'), payload: data }, chapterOverride);
    };

    const handleDraftV1 = (data, chapterOverride = null) => {
        if (serverStreamActiveRef.current || serverStreamUsedRef.current) {
            return;
        }
        setDraftV1(data);
        clearDiffReview();
        const chapterKey = chapterOverride ? String(chapterOverride) : activeChapterKeyRef.current;
        if (chapterKey && chapterKey !== NO_CHAPTER_KEY) {
            setAiLockedChapter(chapterKey);
        }
        startStreamingDraft(data.content || '', {
            chapterKey,
        });
        setStatus('waiting_feedback');
        addMessage('assistant', t('writingSession.draftGenerated'), chapterOverride);
    };

    const handleFinalDraft = (data, chapterOverride = null) => {
        if (serverStreamActiveRef.current || serverStreamUsedRef.current) {
            return;
        }
        setCurrentDraft(data);
        clearDiffReview();
        const chapterKey = chapterOverride ? String(chapterOverride) : activeChapterKeyRef.current;
        if (chapterKey && chapterKey !== NO_CHAPTER_KEY) {
            setAiLockedChapter(chapterKey);
        }
        startStreamingDraft(data.content || '', {
            chapterKey,
        });
        setStatus('completed');
        addMessage('assistant', t('writingSession.finalDraftDone'), chapterOverride);
    };

    const handleSubmitFeedback = async (feedbackOverride) => {
        const textToSubmit = typeof feedbackOverride === 'string' ? feedbackOverride : feedback;
        if (!textToSubmit?.trim()) return;

        try {
            const normalizeLineEndings = (text) => String(text || '').replace(/\r\n/g, '\n');
            const baseContent = normalizeLineEndings(manualContent);
            const chapterKey = chapterInfo.chapter ? String(chapterInfo.chapter) : activeChapterKey;
            if (chapterKey && chapterKey !== NO_CHAPTER_KEY) {
                setAiLockedChapter(chapterKey);
            }
            setIsGenerating(true);
            setStatus('editing');

            setAgentMode('edit');

            stopStreaming();
            clearDiffReview();
            lastFeedbackRef.current = textToSubmit;

            addMessage('user', t('writingSession.editInstruction') + textToSubmit);
            appendProgressEvent({ stage: 'edit_suggest', message: t('writingSession.generatingDiff') });
            setFeedback('');

            const payload = {
                chapter: chapterInfo.chapter ? String(chapterInfo.chapter) : null,
                content: baseContent,
                instruction: textToSubmit,
                context_mode: editContextMode,
            };

            if (editScope === 'selection' && attachedSelection?.text?.trim()) {
                const baseSelection = attachedSelection?.text?.trim() ? attachedSelection : null;
                if (baseSelection) {
                    const selectionText = String(baseSelection.text || '');
                    const selectionStart = Math.max(0, Math.min(Number(baseSelection.start || 0), baseContent.length));
                    const selectionEnd = Math.max(0, Math.min(Number(baseSelection.end || 0), baseContent.length));
                    payload.selection_text = selectionText;
                    payload.selection_start = Math.min(selectionStart, selectionEnd);
                    payload.selection_end = Math.max(selectionStart, selectionEnd);
                }
            }

            const resp = await sessionAPI.suggestEdit(projectId, payload);

            const result = resp.data;
            if (result.success) {
                let nextContent = normalizeLineEndings(result.revised_content);
                const tailFix = stabilizeRevisionTail(baseContent, nextContent, textToSubmit);
                if (tailFix.applied) {
                    nextContent = normalizeLineEndings(tailFix.text);
                    addMessage('system', t('writingSession.diffTruncationWarning'));
                }

                const diff = buildLineDiff(baseContent, nextContent, { contextLines: 2 });
                const hasChanges = Boolean((diff.stats?.additions || 0) + (diff.stats?.deletions || 0));

                if (!hasChanges) {
                    throw new Error(t('writingSession.diffGenerateFailed'));
                }

                appendProgressEvent({
                    stage: 'edit_suggest_done',
                    message: t('writingSession.diffGenerated').replace('{add}', diff.stats.additions || 0).replace('{del}', diff.stats.deletions || 0)
                });

                const hunksWithReason = (diff.hunks || []).map((hunk) => ({
                    ...hunk,
                    reason: lastFeedbackRef.current || t('writingSession.diffReason')
                }));
                const initialDecisions = hunksWithReason.reduce((acc, hunk) => {
                    acc[hunk.id] = 'accepted';
                    return acc;
                }, {});
                setDiffDecisions(initialDecisions);
                setDiffReview({
                    ...diff,
                    hunks: hunksWithReason,
                    originalContent: baseContent,
                    revisedContent: nextContent,
                    chapterKey,
                });
                setStatus('waiting_feedback');
                addMessage('assistant', t('writingSession.diffReady'));
            } else {
                throw new Error(result.error || 'Edit failed');
            }

            setIsGenerating(false);
        } catch (e) {
            addMessage('error', t('writingSession.editFailed') + e.message);
            setIsGenerating(false);
            setStatus('waiting_feedback');
        }
    };

    const handleAgentModeChange = useCallback((nextMode) => {
        setAgentMode(nextMode);
        if (nextMode === 'outline') {
            if (state.activeActivity !== 'explorer') {
                dispatch({ type: 'SET_ACTIVE_PANEL', payload: 'explorer' });
            } else if (!state.sidePanelVisible) {
                dispatch({ type: 'TOGGLE_LEFT_PANEL' });
            }
        }
    }, [dispatch, state.activeActivity, state.sidePanelVisible]);

    const handleChapterCanvasModeChange = useCallback((nextMode) => {
        setChapterCanvasMode(nextMode);
        setChapterOutlineError('');
        if (nextMode === 'outline') {
            handleAgentModeChange('outline');
        } else if (agentMode === 'outline') {
            setAgentMode('create');
        }
    }, [handleAgentModeChange, agentMode]);

    // ==== AI Card Generation ====
    const handleGenerateCardDescription = useCallback(async () => {
        if (!cardForm.name || !projectId) return;
        setCardGenLoading(true);
        try {
            const params = {
                card_id: cardForm.id || `temp_${Date.now()}`,
                name: cardForm.name,
                category: cardForm.category || 'general',
                style_prompt: cardGenStyle,
                note: cardGenNote,
                use_outline: true, // Let backend pull active outline contents if needed
            };
            const response = await cardsAPI.generateDescription(projectId, params);
            if (response.data && response.data.description) {
                setCardForm(prev => ({ ...prev, description: response.data.description }));
                addMessage('assistant', `✨ 已提取大纲及设定，成功为【${cardForm.name}】生成了描述。`);
            }
        } catch (err) {
            console.error("Card gen failed:", err);
            addMessage('error', '生成描述失败: ' + (err.response?.data?.detail || err.message));
        } finally {
            setCardGenLoading(false);
        }
    }, [projectId, cardForm, cardGenStyle, cardGenNote, addMessage, cardsAPI]);

    const handleRewriteSelection = async () => {
        if (!selectionInfo || !selectionInfo.text) {
            addMessage('system', t('writingSession.noSelectionForRewrite') || '请先在正文中选中需要口语化润色的段落');
            return;
        }

        const { start, end, text } = selectionInfo;

        setIsRewriting(true);
        try {
            const resp = await draftsAPI.rewriteText(projectId, text);
            if (resp.data.success && resp.data.rewritten) {
                const rewritten = resp.data.rewritten;

                // 为了保留原生的 Command/Ctrl + Z 撤销栈，使用 document.execCommand
                if (mainEditorRef.current) {
                    mainEditorRef.current.focus();
                    mainEditorRef.current.setSelectionRange(start, end);
                    // 插入时也会自动触发 onChange 处理其它状态的同步
                    const success = document.execCommand('insertText', false, rewritten);

                    if (!success) {
                        // 回退到基于 React State 的赋值 (此时可能丢失一次 undo)
                        const currentText = manualContent;
                        const newText = currentText.substring(0, start) + rewritten + currentText.substring(end);

                        // 强制触发原生 input 事件供 react 绑定捕获以规避 state 不同步问题
                        const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                        nativeInputValueSetter.call(mainEditorRef.current, newText);
                        mainEditorRef.current.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                }

                // Clear selection
                setSelectionInfo(null);
                dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });

                addMessage('assistant', '✨ ' + (t('writingSession.rewriteComplete') || '口语化重写已完成！一段充满网感的新文本已经替换了你的选区。'));
            }
        } catch (e) {
            console.error('Rewrite error:', e);
            addMessage('error', (t('writingSession.rewriteFailed') || '重写请求失败: ') + e.message);
        } finally {
            setIsRewriting(false);
        }
    };

    const saveOutlineByScope = useCallback(async (scope = outlineEditorScope) => {
        if (!projectId) {
            return { success: false, error: t('writingSession.selectChapterFirst') };
        }

        const scopedContent = String(outlineContentByScope?.[scope] || '');
        const chapterKey = normalizeChapterKey(chapterInfo.chapter);
        const volumeId = normalizeVolumeKey(outlineScopeIds?.volume || chapterKey.match(/^V\d+/i)?.[0] || 'V1');

        if (scope === 'chapter' && !chapterInfo.chapter) {
            return { success: false, error: t('writingSession.selectChapterFirst') };
        }

        try {
            if (scope === 'master') {
                await outlineAPI.saveMaster(projectId, { content: scopedContent });
            } else if (scope === 'volume') {
                await outlineAPI.saveVolume(projectId, volumeId, { content: scopedContent });
            } else {
                await outlineAPI.saveChapter(projectId, chapterKey, { content: scopedContent });
            }
            await mutateSWR(`/outline/${projectId}`);
            setOutlineSavedByScope((prev) => ({
                ...prev,
                [scope]: scopedContent,
            }));
            setChapterOutlineError('');
            return { success: true };
        } catch (e) {
            const errorMsg = e?.response?.data?.detail || e?.message || t('writingSession.outlineSaveFailed');
            setChapterOutlineError(errorMsg);
            return { success: false, error: errorMsg };
        }
    }, [projectId, outlineEditorScope, outlineContentByScope, chapterInfo.chapter, outlineScopeIds?.volume, normalizeChapterKey, normalizeVolumeKey, t]);

    const requestOutlineSave = useCallback(async () => {
        const requestId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        return await new Promise((resolve) => {
            let settled = false;

            const cleanup = () => {
                window.removeEventListener('wenshape:outline:save-current:result', onResult);
                if (timer) {
                    window.clearTimeout(timer);
                }
            };

            const onResult = (event) => {
                const detail = event?.detail || {};
                if (detail.requestId !== requestId) return;
                settled = true;
                cleanup();
                resolve({
                    success: Boolean(detail.success),
                    error: detail.error || '',
                });
            };

            const timer = window.setTimeout(() => {
                if (settled) return;
                cleanup();
                resolve({ success: false, error: t('writingSession.outlineSaveTimeout') });
            }, 2200);

            window.addEventListener('wenshape:outline:save-current:result', onResult);
            window.dispatchEvent(new CustomEvent('wenshape:outline:save-current', {
                detail: { requestId },
            }));
        });
    }, [t]);

    const buildOutlinePrompt = useCallback((outlinePayload, chapterId) => {
        const chapter = String(chapterId || '').trim().toUpperCase();
        const chapterItem = (outlinePayload?.chapters || []).find((item) => String(item?.chapter_id || '').toUpperCase() === chapter);
        const volumeId = chapterItem?.volume_id || (chapter.match(/^V\d+/i)?.[0] || 'V1').toUpperCase();
        const volumeItem = (outlinePayload?.volumes || []).find((item) => String(item?.volume_id || '').toUpperCase() === volumeId);

        const master = String(outlinePayload?.master?.content || '').trim();
        const volume = String(volumeItem?.content || '').trim();
        const chapterOutline = String(chapterItem?.content || '').trim();

        const sections = [];
        if (master) sections.push(`[总纲]\n${master}`);
        if (volume) sections.push(`[分卷纲 ${volumeId}]\n${volume}`);
        if (chapterOutline) sections.push(`[章节细纲 ${chapter}]\n${chapterOutline}`);

        const body = sections.length > 0 ? sections.join('\n\n') : t('writingSession.outlineEmptyFallback');

        return `${body}\n\n[写作任务]\n请严格依据以上大纲，创作本章正文（章节：${chapter}）。先保证剧情推进与设定一致，再保证文风与节奏。`;
    }, [t]);

    const outlineScopeLabelMap = {
        master: t('writingSession.outlineScopeMaster'),
        volume: t('writingSession.outlineScopeVolume').replace('{id}', String(outlineScopeIds?.volume || 'V1')),
        chapter: t('writingSession.outlineScopeChapter').replace('{id}', String(outlineScopeIds?.chapter || normalizeChapterKey(chapterInfo.chapter) || '')),
    };
    const currentOutlineContent = String(outlineContentByScope?.[outlineEditorScope] || '');
    const currentOutlineSavedContent = String(outlineSavedByScope?.[outlineEditorScope] || '');
    const outlineScopePlaceholder = outlineEditorScope === 'master'
        ? t('writingSession.outlineMasterPlaceholder')
        : (outlineEditorScope === 'volume'
            ? t('writingSession.outlineVolumePlaceholder').replace('{id}', String(outlineScopeIds?.volume || 'V1'))
            : t('writingSession.outlineChapterPlaceholder').replace('{id}', String(outlineScopeIds?.chapter || normalizeChapterKey(chapterInfo.chapter) || '')));

    const handleOutlineSaveAction = useCallback(async () => {
        handleAgentModeChange('outline');
        setOutlineActionBusy(true);
        try {
            const saved = chapterCanvasMode === 'outline'
                ? await saveOutlineByScope(outlineEditorScope)
                : await requestOutlineSave();
            if (!saved.success) {
                addMessage('error', t('writingSession.outlineSaveFailed') + (saved.error ? `: ${saved.error}` : ''));
                return;
            }
            addMessage('system', t('writingSession.outlineSaved'));
        } finally {
            setOutlineActionBusy(false);
        }
    }, [handleAgentModeChange, requestOutlineSave, saveOutlineByScope, outlineEditorScope, chapterCanvasMode, addMessage, t]);

    const handleOutlineInjectAction = useCallback(async () => {
        if (!chapterInfo.chapter) {
            addMessage('system', t('writingSession.selectChapterFirst'));
            return;
        }
        handleAgentModeChange('outline');
        setOutlineActionBusy(true);
        try {
            const saved = chapterCanvasMode === 'outline'
                ? await saveOutlineByScope(outlineEditorScope)
                : await requestOutlineSave();
            if (!saved.success) {
                addMessage('error', t('writingSession.outlineSaveFailed') + (saved.error ? `: ${saved.error}` : ''));
                return;
            }

            const outlineResp = await outlineAPI.get(projectId);
            const prompt = buildOutlinePrompt(outlineResp?.data || {}, chapterInfo.chapter);

            setAgentInputSeed({ id: Date.now(), text: prompt });
            handleAgentModeChange('create');
            addMessage('system', t('writingSession.outlineInjected').replace('{id}', String(chapterInfo.chapter)));
        } catch (e) {
            addMessage('error', t('writingSession.outlineInjectFailed') + (e?.response?.data?.detail || e?.message || ''));
        } finally {
            setOutlineActionBusy(false);
        }
    }, [chapterInfo.chapter, handleAgentModeChange, requestOutlineSave, saveOutlineByScope, outlineEditorScope, chapterCanvasMode, projectId, buildOutlinePrompt, addMessage, t]);

    const handleAcceptAllDiff = () => {
        if (!diffReview) return;
        const nextContent = diffReview.revisedContent || '';
        if ((loadedContent ?? '') !== nextContent) {
            dispatch({ type: 'SET_UNSAVED' });
        }
        setManualContent(nextContent);
        if (diffReview.chapterKey) {
            const key = String(diffReview.chapterKey);
            setManualContentByChapter((prev) => ({ ...(prev || {}), [key]: nextContent }));
        }
        dispatch({ type: 'SET_WORD_COUNT', payload: countWords(nextContent, writingLanguage) });
        dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
        clearDiffReview();
    };

    const handleRejectAllDiff = () => {
        if (!diffReview) return;
        const nextContent = diffReview.originalContent || '';
        if ((loadedContent ?? '') !== nextContent) {
            dispatch({ type: 'SET_UNSAVED' });
        }
        setManualContent(nextContent);
        if (diffReview.chapterKey) {
            const key = String(diffReview.chapterKey);
            setManualContentByChapter((prev) => ({ ...(prev || {}), [key]: nextContent }));
        }
        dispatch({ type: 'SET_WORD_COUNT', payload: countWords(nextContent, writingLanguage) });
        dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
        clearDiffReview();
    };

    const handleAcceptDiffHunk = (hunkId) => {
        setDiffDecisions((prev) => {
            const next = { ...(prev || {}) };
            const current = next[hunkId];
            next[hunkId] = current === 'accepted' ? 'pending' : 'accepted';
            return next;
        });
    };

    const handleRejectDiffHunk = (hunkId) => {
        setDiffDecisions((prev) => {
            const next = { ...(prev || {}) };
            const current = next[hunkId];
            next[hunkId] = current === 'rejected' ? 'pending' : 'rejected';
            return next;
        });
    };

    const handleApplySelectedDiff = () => {
        if (!diffReview) return;
        const originalLines = diffReview.originalLines || (diffReview.originalContent || '').split('\n');
        const ops = diffReview.ops || [];
        const hasDecisions = Object.keys(diffDecisions || {}).length > 0;
        const nextContent = hasDecisions
            ? applyDiffOpsWithDecisions(originalLines, ops, diffDecisions)
            : (diffReview.revisedContent || '');
        if ((loadedContent ?? '') !== nextContent) {
            dispatch({ type: 'SET_UNSAVED' });
        }
        setManualContent(nextContent);
        if (diffReview.chapterKey) {
            const key = String(diffReview.chapterKey);
            setManualContentByChapter((prev) => ({ ...(prev || {}), [key]: nextContent }));
        }
        dispatch({ type: 'SET_WORD_COUNT', payload: countWords(nextContent, writingLanguage) });
        dispatch({ type: 'SET_SELECTION_COUNT', payload: 0 });
        clearDiffReview();
    };

    const saveDraftContent = async () => {
        if (!chapterInfo.chapter) return { success: false };
        const trimmedTitle = String(chapterInfo.chapter_title || '').trim();
        const payload = { content: manualContent };
        if (trimmedTitle) {
            payload.title = trimmedTitle;
        }
        const resp = await draftsAPI.updateContent(projectId, chapterInfo.chapter, payload);
        if (resp.data?.success) {
            const normalizedChapter = resp.data?.chapter || chapterInfo.chapter;
            if (normalizedChapter && normalizedChapter !== chapterInfo.chapter) {
                setChapterInfo((prev) => ({ ...prev, chapter: normalizedChapter }));
                dispatch({ type: 'SET_ACTIVE_DOCUMENT', payload: { type: 'chapter', id: normalizedChapter } });
                await loadChapters();
            }
            if (typeof resp.data?.title === 'string' && resp.data.title.trim()) {
                setChapterInfo((prev) => ({ ...prev, chapter_title: resp.data.title }));
            }
            await mutateSWR(`/drafts/${projectId}/summaries`);
            await mutateSWR(`/outline/${projectId}`);
            dispatch({ type: 'SET_SAVED' });
            mutateChapter(manualContent, false);
        }
        return resp.data;
    };

    const handleManualSave = async () => {
        if (!chapterInfo.chapter) return;
        setIsSaving(true);
        try {
            const result = await saveDraftContent();
            if (result?.success) {
                addMessage('system', '\u8349\u7a3f\u5df2\u4fdd\u5b58');
            }
        } catch (e) {
            addMessage('error', '\u4fdd\u5b58\u5931\u8d25: ' + e.message);
        } finally {
            setIsSaving(false);
        }
    };

    const handleAnalyzeAndSave = async () => {
        if (!chapterInfo.chapter) return;
        setAnalysisLoading(true);
        try {
            const saved = await saveDraftContent();
            if (!saved?.success) {
                throw new Error(saved?.message || '\u4fdd\u5b58\u5931\u8d25');
            }
            const normalizedChapter = saved?.chapter || chapterInfo.chapter;
            const resp = await sessionAPI.analyze(projectId, {
                language: requestLanguage,
                chapter: normalizedChapter,
                content: manualContent,
                chapter_title: chapterInfo.chapter_title || '',
            });
            if (resp.data?.success) {
                setAnalysisItems([{ chapter: normalizedChapter, analysis: resp.data.analysis || {} }]);
                setAnalysisDialogOpen(true);
                addMessage('system', '\u5206\u6790\u5b8c\u6210，\u8bf7\u786e\u8ba4\u5e76\u4fdd\u5b58\u3002');
            } else {
                throw new Error(resp.data?.error || '\u5206\u6790\u5931\u8d25');
            }
        } catch (e) {
            addMessage('error', '\u5206\u6790\u5931\u8d25: ' + e.message);
        } finally {
            setAnalysisLoading(false);
        }
    };

    const handleSaveAnalysis = async (payload) => {
        setAnalysisSaving(true);
        try {
            if (Array.isArray(payload)) {
                const resp = await sessionAPI.saveAnalysisBatch(projectId, {
                    language: requestLanguage,
                    items: payload,
                    overwrite: true,
                });
                if (!resp.data?.success) {
                    throw new Error(resp.data?.error || '\u5206\u6790\u5931\u8d25');
                }
            } else if (chapterInfo.chapter) {
                const resp = await sessionAPI.saveAnalysis(projectId, {
                    language: requestLanguage,
                    chapter: chapterInfo.chapter,
                    analysis: payload,
                    overwrite: true,
                });
                if (!resp.data?.success) {
                    throw new Error(resp.data?.error || '\u5206\u6790\u5931\u8d25');
                }
            }
            addMessage('system', '\u5206\u6790\u4fdd\u5b58\u5b8c\u6210');
            setAnalysisDialogOpen(false);
            setAnalysisItems([]);
        } catch (e) {
            addMessage('error', '\u4fdd\u5b58\u5931\u8d25: ' + e.message);
        } finally {
            setAnalysisSaving(false);
        }
    };

    // Phase 4.3: Handle user answer for AskUser
    // Card Handlers
    const handleCardSave = async () => {
        if (!activeCard) return;
        setIsSaving(true);
        try {
            const name = (cardForm.name || '').trim();
            if (!name) {
                throw new Error(t('writingSession.cardNameRequired'));
            }
            const stars = normalizeStars(cardForm.stars);
            const aliases = parseListInput(cardForm.aliases);
            if (activeCard.type === 'character') {
                const payload = {
                    name,
                    description: cardForm.description || '',
                    aliases,
                    stars
                };
                if (activeCard.isNew || !activeCard.originalName) {
                    await cardsAPI.createCharacter(projectId, payload);
                } else if (activeCard.originalName !== name) {
                    await cardsAPI.createCharacter(projectId, payload);
                    await cardsAPI.deleteCharacter(projectId, activeCard.originalName);
                } else {
                    await cardsAPI.updateCharacter(projectId, activeCard.originalName, payload);
                }
            } else {
                const rules = parseListInput(cardForm.rules);
                const immutableValue =
                    cardForm.immutable === 'true' ? true : cardForm.immutable === 'false' ? false : undefined;
                const payload = {
                    name,
                    description: cardForm.description || '',
                    aliases,
                    category: (cardForm.category || '').trim(),
                    rules,
                    stars
                };
                if (immutableValue !== undefined) {
                    payload.immutable = immutableValue;
                }
                if (activeCard.isNew || !activeCard.originalName) {
                    await cardsAPI.createWorld(projectId, payload);
                } else if (activeCard.originalName !== name) {
                    await cardsAPI.createWorld(projectId, payload);
                    await cardsAPI.deleteWorld(projectId, activeCard.originalName);
                } else {
                    await cardsAPI.updateWorld(projectId, activeCard.originalName, payload);
                }
            }
            try {
                const refreshed = activeCard.type === 'character'
                    ? await cardsAPI.getCharacter(projectId, name)
                    : await cardsAPI.getWorld(projectId, name);
                const refreshedData = refreshed?.data;
                if (refreshedData?.name) {
                    setActiveCard({
                        ...refreshedData,
                        type: activeCard.type,
                        isNew: false,
                        originalName: refreshedData.name,
                    });
                    setCardForm({
                        name: refreshedData.name || '',
                        description: refreshedData.description || '',
                        aliases: formatListInput(refreshedData.aliases),
                        stars: normalizeStars(refreshedData.stars),
                        category: refreshedData.category || '',
                        rules: formatRulesInput(refreshedData.rules),
                        immutable: refreshedData.immutable === true ? 'true' : refreshedData.immutable === false ? 'false' : 'unset'
                    });
                }
            } catch (error) {
                logger.error('Failed to refresh card data', error);
            }
            addMessage('system', t('writingSession.cardUpdated'));
            dispatch({ type: 'SET_SAVED' });
        } catch (e) {
            const detail = e?.response?.data?.detail || e?.response?.data?.error;
            addMessage('error', t('writingSession.cardSaveFailed') + (detail || e.message));
        } finally {
            setIsSaving(false);
        }
    };

    const renderMainContent = () => {
        if (state.activeActivity === 'fanfiction') {
            return (
                <FanfictionView
                    embedded
                    onClose={() => dispatch({ type: 'SET_ACTIVE_PANEL', payload: 'explorer' })}
                />
            );
        }
        return (
            <AnimatePresence mode="wait">
                {status === 'card_editing' && activeCard ? (
                    <motion.div
                        key="card-editor"
                        initial={{ opacity: 0, scale: 0.98, y: 10 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.98, y: -10 }}
                        transition={{ duration: 0.3, ease: "easeOut" }}
                        className="h-full flex flex-col max-w-3xl mx-auto w-full pt-4"
                    >
                        <div className="flex items-center justify-between mb-6 pb-4 border-b border-border">
                            <div className="flex items-center gap-3">
                                <div className="p-2 bg-primary/10 rounded-lg text-primary">
                                    {activeCard.type === 'character' ? <div className="i-lucide-user" /> : <div className="i-lucide-globe" />}
                                    {activeCard.type === 'character' ? '👤' : '🌍'}
                                </div>
                                <div>
                                    <p className="text-xs text-ink-400 font-mono uppercase tracking-wider">{activeCard.type === 'character' ? t('writingSession.cardTypeChar') : t('writingSession.cardTypeWorld')}</p>
                                </div>
                            </div>
                            <button
                                onClick={() => {
                                    setStatus('idle');
                                    setActiveCard(null);
                                }}
                                className="p-2 hover:bg-ink-100 rounded-lg transition-colors text-ink-400 hover:text-ink-700"
                                title={t('writingSession.closeCardEdit')}
                            >
                                <X size={20} />
                            </button>
                        </div>

                        <div className="space-y-6 flex-1 overflow-y-auto px-1 pb-20">
                            {/* Common: Name */}
                            <div className="space-y-1">
                                <label className="text-xs font-bold text-ink-500 tracking-wider">{t('card.fieldName')}</label>
                                <Input
                                    value={cardForm.name}
                                    onChange={e => setCardForm(prev => ({ ...prev, name: e.target.value }))}
                                    className="font-serif text-lg bg-[var(--vscode-input-bg)] font-bold"
                                />
                            </div>

                            <div className="space-y-1">
                                <label className="text-xs font-bold text-ink-500 tracking-wider">{t('card.fieldStars')}</label>
                                <select
                                    value={cardForm.stars}
                                    onChange={e => setCardForm(prev => ({ ...prev, stars: normalizeStars(e.target.value) }))}
                                    className="w-full h-10 px-3 rounded-[6px] border border-[var(--vscode-input-border)] bg-[var(--vscode-input-bg)] text-sm focus:ring-1 focus:ring-[var(--vscode-focus-border)]"
                                >
                                    <option value={3}>{t('card.stars3')}</option>
                                    <option value={2}>{t('card.stars2')}</option>
                                    <option value={1}>{t('card.stars1')}</option>
                                </select>
                            </div>

                            <div className="space-y-1">
                                <label className="text-xs font-bold text-ink-500 tracking-wider">{t('card.fieldAliases')}</label>
                                <Input
                                    value={cardForm.aliases || ''}
                                    onChange={e => setCardForm(prev => ({ ...prev, aliases: e.target.value }))}
                                    placeholder={t('card.fieldAliasesPlaceholder')}
                                    className="bg-[var(--vscode-input-bg)]"
                                />
                            </div>

                            {activeCard.type === 'world' && (
                                <>
                                    <div className="space-y-1">
                                        <label className="text-xs font-bold text-ink-500 tracking-wider">{t('card.fieldCategory')}</label>
                                        <Input
                                            value={cardForm.category || ''}
                                            onChange={e => setCardForm(prev => ({ ...prev, category: e.target.value }))}
                                            placeholder={t('card.categoryPlaceholder')}
                                            className="bg-[var(--vscode-input-bg)]"
                                        />
                                    </div>
                                    <div className="space-y-1">
                                        <label className="text-xs font-bold text-ink-500 tracking-wider">{t('card.fieldRules')}</label>
                                        <textarea
                                            className="w-full min-h-[140px] p-3 rounded-[6px] border border-[var(--vscode-input-border)] bg-[var(--vscode-input-bg)] text-sm focus:ring-1 focus:ring-[var(--vscode-focus-border)] resize-none overflow-hidden"
                                            value={cardForm.rules || ''}
                                            onChange={e => {
                                                setCardForm(prev => ({ ...prev, rules: e.target.value }));
                                                e.target.style.height = 'auto';
                                                e.target.style.height = e.target.scrollHeight + 'px';
                                            }}
                                            onFocus={e => {
                                                e.target.style.height = 'auto';
                                                e.target.style.height = e.target.scrollHeight + 'px';
                                            }}
                                            placeholder={t('card.rulesPlaceholder')}
                                        />
                                    </div>
                                    <div className="space-y-1">
                                        <label className="text-xs font-bold text-ink-500 tracking-wider">{t('card.fieldImmutable')}</label>
                                        <select
                                            value={cardForm.immutable}
                                            onChange={e => setCardForm(prev => ({ ...prev, immutable: e.target.value }))}
                                            className="w-full h-10 px-3 rounded-[6px] border border-[var(--vscode-input-border)] bg-[var(--vscode-input-bg)] text-sm focus:ring-1 focus:ring-[var(--vscode-focus-border)]"
                                        >
                                            <option value="unset">{t('card.immutableUnset')}</option>
                                            <option value="true">{t('card.immutableTrue')}</option>
                                            <option value="false">{t('card.immutableFalse')}</option>
                                        </select>
                                    </div>
                                </>
                            )}

                            {/* Card Description */}
                            <div className="space-y-1">
                                <div className="flex items-center justify-between mb-2">
                                    <label className="text-xs font-bold text-ink-500 tracking-wider">
                                        {t('card.fieldDescription')}
                                    </label>

                                    {/* AI 生成工具条 */}
                                    {cardForm.name && cardForm.name.trim() !== '' && (
                                        <div className="flex items-center gap-2">
                                            <input
                                                type="text"
                                                placeholder="文风提示(可选)"
                                                value={cardGenStyle}
                                                onChange={e => setCardGenStyle(e.target.value)}
                                                className="w-28 text-xs p-1 rounded border border-border bg-bg-alt focus:outline-none focus:border-brand-500"
                                            />
                                            <input
                                                type="text"
                                                placeholder="补充说明(可选)"
                                                value={cardGenNote}
                                                onChange={e => setCardGenNote(e.target.value)}
                                                className="w-28 text-xs p-1 rounded border border-border bg-bg-alt focus:outline-none focus:border-brand-500"
                                            />
                                            <Button
                                                variant="ghost"
                                                size="sm"
                                                className="h-6 text-xs px-2 text-brand-600 hover:text-brand-700 hover:bg-brand-50 flex items-center gap-1"
                                                onClick={handleGenerateCardDescription}
                                                disabled={cardGenLoading}
                                                title="AI 自动生成描述（基于设定的名字和可用大纲）"
                                            >
                                                {cardGenLoading ? (
                                                    <Loader2 className="w-3 h-3 animate-spin" />
                                                ) : (
                                                    <span className="text-[10px]">✨ AI 生成</span>
                                                )}
                                            </Button>
                                        </div>
                                    )}
                                </div>
                                <textarea
                                    className="w-full min-h-[200px] p-3 rounded-[6px] border border-[var(--vscode-input-border)] bg-[var(--vscode-input-bg)] text-sm focus:ring-1 focus:ring-[var(--vscode-focus-border)] resize-none overflow-hidden"
                                    value={cardForm.description || ''}
                                    onChange={e => {
                                        setCardForm(prev => ({ ...prev, description: e.target.value }));
                                        e.target.style.height = 'auto';
                                        e.target.style.height = e.target.scrollHeight + 'px';
                                    }}
                                    onFocus={e => {
                                        e.target.style.height = 'auto';
                                        e.target.style.height = e.target.scrollHeight + 'px';
                                    }}
                                    placeholder={t('card.charDescPlaceholder')}
                                />
                            </div>

                        </div>
                    </motion.div>
                ) : !chapterInfo.chapter ? (
                    <motion.div
                        key="empty-state"
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        className="h-[60vh] flex items-center justify-center"
                    >
                        <div className="text-center">
                            <div className="flex flex-col items-center gap-2 mb-4">
                                <span className="brand-logo text-4xl text-ink-900/40">写作agent</span>
                            </div>
                            <p className="text-sm text-ink-500">
                                {t('writingSession.selectResourceHint')}
                            </p>
                        </div>
                    </motion.div>
                ) : (
                    <motion.div
                        key="chapter-editor"
                        initial={{ opacity: 0, y: 10 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: -10 }}
                        transition={{ duration: 0.3 }}
                        className="h-full flex flex-col relative"
                    >
                        <div className="mb-4 pb-3 border-b border-border flex flex-wrap items-center gap-3">
                            <span className="text-[11px] font-mono text-ink-500 uppercase tracking-wider">{chapterInfo.chapter}</span>
                            <input
                                className="flex-1 min-w-[200px] bg-transparent text-2xl font-serif font-bold text-ink-900 outline-none placeholder:text-ink-300"
                                value={chapterInfo.chapter_title || ''}
                                onChange={(e) => {
                                    setChapterInfo((prev) => ({ ...prev, chapter_title: e.target.value }));
                                    dispatch({ type: 'SET_UNSAVED' });
                                }}
                                placeholder={t('writingSession.chapterTitlePlaceholder')}
                                disabled={!chapterInfo.chapter}
                            />
                        </div>
                        <div className="mb-3 flex items-center gap-2">
                            <button
                                type="button"
                                onClick={() => handleChapterCanvasModeChange('draft')}
                                className={cn(
                                    'px-2.5 h-7 text-[11px] rounded-[6px] border transition-colors',
                                    chapterCanvasMode === 'draft'
                                        ? 'bg-[var(--vscode-list-active)] text-[var(--vscode-list-active-fg)] border-[var(--vscode-input-border)]'
                                        : 'bg-[var(--vscode-input-bg)] text-[var(--vscode-fg)] border-[var(--vscode-sidebar-border)] hover:border-[var(--vscode-focus-border)]'
                                )}
                            >
                                {t('writingSession.editorLayerDraft')}
                            </button>
                            <button
                                type="button"
                                onClick={() => handleChapterCanvasModeChange('outline')}
                                className={cn(
                                    'px-2.5 h-7 text-[11px] rounded-[6px] border transition-colors',
                                    chapterCanvasMode === 'outline'
                                        ? 'bg-[var(--vscode-list-active)] text-[var(--vscode-list-active-fg)] border-[var(--vscode-input-border)]'
                                        : 'bg-[var(--vscode-input-bg)] text-[var(--vscode-fg)] border-[var(--vscode-sidebar-border)] hover:border-[var(--vscode-focus-border)]'
                                )}
                            >
                                {t('writingSession.editorLayerOutline')}
                            </button>
                        </div>
                        <div className="flex-1 overflow-hidden bg-[var(--vscode-bg)] border-t border-[var(--vscode-sidebar-border)]">
                            {chapterCanvasMode === 'outline' ? (
                                <div className="h-full p-4">
                                    <div className="mb-3 flex flex-wrap items-center gap-2">
                                        <button
                                            type="button"
                                            onClick={() => setOutlineEditorScope('master')}
                                            className={cn(
                                                'px-2.5 h-7 text-[11px] rounded-[6px] border transition-colors',
                                                outlineEditorScope === 'master'
                                                    ? 'bg-[var(--vscode-list-active)] text-[var(--vscode-list-active-fg)] border-[var(--vscode-input-border)]'
                                                    : 'bg-[var(--vscode-input-bg)] text-[var(--vscode-fg)] border-[var(--vscode-sidebar-border)] hover:border-[var(--vscode-focus-border)]'
                                            )}
                                        >
                                            {outlineScopeLabelMap.master}
                                        </button>
                                        <button
                                            type="button"
                                            onClick={() => setOutlineEditorScope('volume')}
                                            className={cn(
                                                'px-2.5 h-7 text-[11px] rounded-[6px] border transition-colors',
                                                outlineEditorScope === 'volume'
                                                    ? 'bg-[var(--vscode-list-active)] text-[var(--vscode-list-active-fg)] border-[var(--vscode-input-border)]'
                                                    : 'bg-[var(--vscode-input-bg)] text-[var(--vscode-fg)] border-[var(--vscode-sidebar-border)] hover:border-[var(--vscode-focus-border)]'
                                            )}
                                        >
                                            {outlineScopeLabelMap.volume}
                                        </button>
                                        <button
                                            type="button"
                                            onClick={() => setOutlineEditorScope('chapter')}
                                            className={cn(
                                                'px-2.5 h-7 text-[11px] rounded-[6px] border transition-colors',
                                                outlineEditorScope === 'chapter'
                                                    ? 'bg-[var(--vscode-list-active)] text-[var(--vscode-list-active-fg)] border-[var(--vscode-input-border)]'
                                                    : 'bg-[var(--vscode-input-bg)] text-[var(--vscode-fg)] border-[var(--vscode-sidebar-border)] hover:border-[var(--vscode-focus-border)]'
                                            )}
                                        >
                                            {outlineScopeLabelMap.chapter}
                                        </button>
                                    </div>
                                    <textarea
                                        className="h-full w-full resize-none border border-[var(--vscode-input-border)] rounded-[6px] bg-[var(--vscode-input-bg)] p-4 text-sm text-[var(--vscode-fg)] leading-relaxed focus:outline-none focus:border-[var(--vscode-focus-border)] overflow-y-auto editor-scrollbar"
                                        value={currentOutlineContent}
                                        onChange={(e) => {
                                            const next = e.target.value;
                                            setOutlineContentByScope((prev) => ({
                                                ...prev,
                                                [outlineEditorScope]: next,
                                            }));
                                        }}
                                        placeholder={outlineScopePlaceholder}
                                        disabled={!chapterInfo.chapter || chapterOutlineLoading}
                                        spellCheck={false}
                                    />
                                    {chapterOutlineLoading ? (
                                        <div className="mt-2 text-[11px] text-[var(--vscode-fg-subtle)]">{t('common.loading')}</div>
                                    ) : null}
                                    {chapterOutlineError ? (
                                        <div className="mt-2 text-[11px] text-red-500">{chapterOutlineError}</div>
                                    ) : null}
                                    {!chapterOutlineError && !chapterOutlineLoading && currentOutlineContent !== currentOutlineSavedContent ? (
                                        <div className="mt-2 text-[11px] text-[var(--vscode-fg-subtle)]">
                                            {t('writingSession.outlineUnsavedHint').replace('{scope}', outlineScopeLabelMap[outlineEditorScope] || '')}
                                        </div>
                                    ) : null}
                                </div>
                            ) : isDiffReviewForActiveChapter ? (
                                <DiffReviewView
                                    ops={diffReview.ops}
                                    hunks={diffReview.hunks}
                                    stats={diffReview.stats}
                                    decisions={diffDecisions}
                                    onAcceptHunk={handleAcceptDiffHunk}
                                    onRejectHunk={handleRejectDiffHunk}
                                    originalVersion={t('writingSession.currentText')}
                                    revisedVersion={t('writingSession.revisedText')}
                                />
                            ) : isStreamingForActiveChapter ? (
                                <StreamingDraftView
                                    content={manualContent}
                                    active={isStreamingForActiveChapter}
                                    className="h-full"
                                />
                            ) : (
                                <div className="h-full w-full relative flex flex-col">
                                    <div className="flex-none p-1 shrink-0 bg-bg/50 border-b border-border flex justify-between items-center z-10">
                                        <div className="flex gap-2 text-xs">
                                            {/* De-AI Rewrite Button, only shows when text is selected */}
                                            {selectionInfo && selectionInfo.text && (
                                                <button
                                                    onClick={handleRewriteSelection}
                                                    onMouseDown={(e) => e.preventDefault()}
                                                    disabled={isRewriting}
                                                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-md bg-brand-50 hover:bg-brand-100 text-brand-700 font-medium transition-colors border border-brand-200 shadow-sm"
                                                    title="将选中的文本进行去机器味的混沌增益口语化重构"
                                                >
                                                    <Wand2 size={14} className={isRewriting ? "animate-pulse" : ""} />
                                                    {isRewriting ? '正在口语化...' : '🪄 口语化重写 (去 AI 味)'}
                                                </button>
                                            )}
                                        </div>
                                    </div>
                                    <textarea
                                        ref={mainEditorRef}
                                        className="flex-1 w-full resize-none border-none outline-none bg-transparent p-6 text-base font-serif text-ink-900 leading-relaxed focus:ring-0 placeholder:text-ink-300 overflow-y-auto editor-scrollbar min-h-0"
                                        value={manualContent}
                                        onChange={(e) => {
                                            const nextValue = e.target.value;
                                            setManualContent(nextValue);
                                            if (chapterInfo.chapter) {
                                                const key = String(chapterInfo.chapter);
                                                setManualContentByChapter((prev) => ({ ...(prev || {}), [key]: nextValue }));
                                            }
                                            dispatch({ type: 'SET_WORD_COUNT', payload: countWords(nextValue, writingLanguage) });
                                            const stats = getSelectionStats(nextValue, e.target.selectionStart, e.target.selectionEnd, writingLanguage);
                                            dispatch({ type: 'SET_SELECTION_COUNT', payload: stats.selectionCount });
                                            setSelectionInfo({
                                                start: stats.selectionStart,
                                                end: stats.selectionEnd,
                                                text: stats.selectionText || '',
                                            });
                                            const lines = stats.cursorText.split('\n');
                                            dispatch({
                                                type: 'SET_CURSOR_POSITION',
                                                payload: {
                                                    line: lines.length,
                                                    column: lines[lines.length - 1].length + 1
                                                }
                                            });
                                            dispatch({ type: 'SET_UNSAVED' });
                                        }}
                                        onSelect={(e) => {
                                            const stats = getSelectionStats(e.target.value, e.target.selectionStart, e.target.selectionEnd, writingLanguage);
                                            dispatch({ type: 'SET_SELECTION_COUNT', payload: stats.selectionCount });
                                            setSelectionInfo({
                                                start: stats.selectionStart,
                                                end: stats.selectionEnd,
                                                text: stats.selectionText || '',
                                            });
                                            const lines = stats.cursorText.split('\n');
                                            dispatch({
                                                type: 'SET_CURSOR_POSITION',
                                                payload: {
                                                    line: lines.length,
                                                    column: lines[lines.length - 1].length + 1
                                                }
                                            });
                                        }}
                                        placeholder={t('writingSession.writePlaceholder')}
                                        disabled={!chapterInfo.chapter || lockedOnActiveChapter}
                                        spellCheck={false}
                                    />
                                </div>
                            )}
                        </div>

                    </motion.div>
                )}
            </AnimatePresence>
        );
    };

    const rightPanelContent = (
        <AgentsPanel traceEvents={traceEvents} agentTraces={agentTraces}>
            <AgentStatusPanel
                mode={agentMode}
                onModeChange={handleAgentModeChange}
                createDisabled={!canUseWriter}
                externalInputSeed={agentInputSeed}
                inputDisabled={agentBusy && String(aiLockedChapter || '') !== activeChapterKey}
                inputDisabledReason={
                    agentBusy && String(aiLockedChapter || '') !== activeChapterKey
                        ? t('writingSession.aiLockedHint').replace('{n}', String(aiLockedChapter))
                        : ''
                }
                selectionCandidateSummary={
                    agentMode === 'edit' && selectionInfo?.text?.trim()
                        ? t('writingSession.selectionPending').replace('{n}', countWords(selectionInfo.text, writingLanguage))
                        : ''
                }
                selectionAttachedSummary={
                    agentMode === 'edit' && attachedSelection?.text?.trim()
                        ? t('writingSession.selectionAdded').replace('{n}', countWords(attachedSelection.text, writingLanguage))
                        : ''
                }
                selectionCandidateDifferent={
                    Boolean(selectionInfo?.text?.trim()) &&
                    Boolean(attachedSelection?.text?.trim()) &&
                    (selectionInfo.start !== attachedSelection.start ||
                        selectionInfo.end !== attachedSelection.end ||
                        selectionInfo.text !== attachedSelection.text)
                }
                onAttachSelection={() => {
                    if (!selectionInfo?.text?.trim()) return;
                    setAttachedSelection({
                        start: selectionInfo.start,
                        end: selectionInfo.end,
                        text: selectionInfo.text,
                    });
                    setEditScope('selection');
                }}
                onClearAttachedSelection={() => {
                    setAttachedSelection(null);
                    setEditScope('document');
                }}
                editScope={editScope}
                onEditScopeChange={setEditScope}
                contextDebug={contextDebug}
                progressEvents={progressEvents}
                messages={messages}
                memoryPackStatus={memoryPackStatus}
                activeChapter={agentBusy ? aiLockedChapter : chapterInfo.chapter}
                editContextMode={editContextMode}
                onEditContextModeChange={setEditContextMode}
                diffReview={diffReview && String(diffReview?.chapterKey || '') === agentChapterKey ? diffReview : null}
                diffDecisions={diffDecisions}
                onAcceptAllDiff={handleAcceptAllDiff}
                onRejectAllDiff={handleRejectAllDiff}
                onApplySelectedDiff={handleApplySelectedDiff}
                onSubmit={(text) => {
                    if (!chapterInfo.chapter) {
                        addMessage('system', t('writingSession.pleaseSelectChapter'));
                        return;
                    }

                    if (agentMode === 'outline') {
                        handleAgentModeChange('outline');
                        addMessage('system', t('agentPanel.outlineInputHint'));
                        return;
                    }

                    if (agentMode === 'create') {
                        if (!canUseWriter) {
                            addMessage('system', t('writingSession.chapterNotWritable'));
                            setAgentMode('edit');
                            return;
                        }
                        addMessage('user', text);
                        handleStart(chapterInfo.chapter, 'deep', text);
                        return;
                    }

                    handleSubmitFeedback(text);
                }}
            />
        </AgentsPanel>
    );



    const saveBusy = isSaving || analysisLoading || analysisSaving;
    const showSaveAction = (chapterInfo.chapter || status === 'card_editing' || agentMode === 'outline') && !lockedOnActiveChapter;
    const saveAction = showSaveAction ? (
        status === 'card_editing' ? (
            <Button
                onClick={handleCardSave}
                disabled={isSaving}
                className="shadow-sm"
                size="sm"
            >
                {isSaving ? '\u4fdd\u5b58\u4e2d...' : '\u4fdd\u5b58'}
            </Button>
        ) : agentMode === 'outline' ? (
            <div className="flex items-center gap-2">
                <Button
                    onClick={handleOutlineSaveAction}
                    disabled={outlineActionBusy}
                    size="sm"
                    variant="outline"
                >
                    {outlineActionBusy ? t('common.processing') : t('writingSession.saveOutline')}
                </Button>
                <Button
                    onClick={handleOutlineInjectAction}
                    disabled={outlineActionBusy || !chapterInfo.chapter}
                    size="sm"
                >
                    {outlineActionBusy ? t('common.processing') : t('writingSession.extractOutlineToPrompt')}
                </Button>
            </div>
        ) : (
            <SaveMenu
                disabled={!chapterInfo.chapter || saveBusy}
                busy={saveBusy}
                onSaveOnly={handleManualSave}
                onAnalyzeSave={handleAnalyzeAndSave}
            />
        )
    ) : null;

    const titleBarProps = {
        projectName: project?.name,
        rightActions: saveAction,
        // Show Card Name in Title if card editing
        chapterTitle: status === 'card_editing'
            ? cardForm.name
            : (chapterInfo.chapter ? (chapterInfo.chapter_title || t('writingSession.chapterFallback').replace('{n}', chapterInfo.chapter)) : null),
        aiHint: agentBusy && aiLockedChapter ? t('writingSession.aiLockedStatusHint').replace('{n}', String(aiLockedChapter)) : null,
    };

    return (
        <IDELayout rightPanelContent={rightPanelContent} titleBarProps={titleBarProps}>
            <div className="w-full h-full px-8 py-6">
                {renderMainContent()}
            </div>

            {notice ? (
                <div
                    key={notice.id}
                    className="fixed bottom-4 right-4 z-[60] max-w-[420px] rounded-[6px] border border-[var(--vscode-sidebar-border)] bg-[var(--vscode-input-bg)] px-3 py-2 text-xs text-[var(--vscode-fg)] shadow-md"
                >
                    {notice.text}
                </div>
            ) : null}


            <ChapterCreateDialog
                open={showChapterDialog}
                onClose={() => {
                    setShowChapterDialog(false);
                    dispatch({ type: 'CLOSE_CREATE_CHAPTER_DIALOG' });
                }}
                onConfirm={handleChapterCreate}
                existingChapters={chapters.map(c => ({ id: c, title: '' }))}
                volumes={volumes}
                defaultVolumeId={state.selectedVolumeId || 'V1'}
            />

            <PreWritingQuestionsDialog
                open={showPreWriteDialog}
                questions={preWriteQuestions}
                onConfirm={handlePreWriteConfirm}
                onSkip={handlePreWriteSkip}
            />

            <AnalysisReviewDialog
                open={analysisDialogOpen}
                analyses={analysisItems}
                onCancel={() => {
                    setAnalysisDialogOpen(false);
                    setAnalysisItems([]);
                }}
                onSave={handleSaveAnalysis}
                saving={analysisSaving}
            />

        </IDELayout >
    );
}

/**
 * WritingSession - 写作会话入口
 * 提供 IDE 上下文并渲染主容器。
 */
export default function WritingSession(props) {
    const { projectId } = useParams();
    return (
        <IDEProvider projectId={projectId}>
            <WritingSessionContent {...props} />
        </IDEProvider>
    );
}
