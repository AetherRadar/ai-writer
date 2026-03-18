/**
 * 文枢 WenShape - 深度上下文感知的智能体小说创作系统
 * WenShape - Deep Context-Aware Agent-Based Novel Writing System
 *
 * Copyright © 2025-2026 WenShape Team
 * License: PolyForm Noncommercial License 1.0.0
 */

import React, { useEffect } from 'react';
import { useIDE } from '../../context/IDEContext';
import { ActivityBar } from './ActivityBar';
import { SidePanel } from './SidePanel';
import { StatusBar } from './StatusBar';
import { TitleBar } from './TitleBar';

/**
 * IDELayout - 写作 IDE 主布局组件
 *
 * 提供类似 VS Code 的三段式布局：顶部标题栏、中央编辑区+左侧面板+右侧面板、底部状态栏。
 * 支持禅模式（Zen Mode）隐藏 UI，支持键盘快捷键切换面板。
 *
 * @component
 * @param {JSX.Element} children - 中央编辑区内容
 * @param {JSX.Element} [rightPanelContent] - 右侧面板内容（可选）
 * @param {Object} [titleBarProps={}] - 传递给顶部栏的属性
 * @returns {JSX.Element} 完整的 IDE 布局
 *
 * 快捷键：
 * - Ctrl/Cmd + B: 切换左侧面板可见性
 * - Ctrl/Cmd + \\: 切换禅模式
 */
export function IDELayout({ children, rightPanelContent, titleBarProps = {} }) {
    const { state, dispatch } = useIDE();

    // ========================================================================
    // 键盘快捷键处理 / Keyboard Shortcuts
    // ========================================================================
    // Ctrl/Cmd+B: 切换左侧面板 / Toggle left panel
    // Ctrl/Cmd+\\: 切换禅模式 / Toggle zen mode
    useEffect(() => {
        const handleKeyDown = (e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'b') {
                e.preventDefault();
                dispatch({ type: 'TOGGLE_LEFT_PANEL' });
            }
            if ((e.metaKey || e.ctrlKey) && e.key === '\\') {
                e.preventDefault();
                dispatch({ type: 'TOGGLE_ZEN_MODE' });
            }
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [dispatch]);

    return (
        <div className="anti-theme h-screen w-full flex flex-col bg-[var(--vscode-bg)] text-[var(--vscode-fg)] overflow-hidden">
            {/* ========================================================================
                顶部标题栏 / Title Bar
                ======================================================================== */}
            {!state.zenMode && <TitleBar {...titleBarProps} />}

            {/* ========================================================================
                主体编辑区 / Main Editor Area
                ======================================================================== */}
            <div className="flex-1 flex overflow-hidden min-h-0">
                {/* 活动栏（固定左侧） - Activity Bar (Fixed Left) */}
                {!state.zenMode && <ActivityBar />}

                {/* 左侧面板（可调整大小/可折叠） - Left Panel (Resizable/Collapsible) */}
                {!state.zenMode && state.sidePanelVisible && <SidePanel />}

                {/* 中央编辑器区域 - Central Editor Area */}
                <main className="flex-1 overflow-y-auto bg-[var(--vscode-bg)] min-w-0">
                    {children}
                </main>

                {/* 右侧 AI 侧边栏 - Right Panel (AI Sidebar) */}
                {!state.zenMode && state.rightPanelVisible && (
                    <div
                        className="border-l border-[var(--vscode-sidebar-border)] flex-shrink-0 bg-[var(--vscode-sidebar-bg)] overflow-hidden flex flex-col relative group"
                        style={{ width: state.rightPanelWidth }}
                    >
                        {/* Width Resize Handle (Right Panel) */}
                        <div
                            className="absolute left-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/30 transition-colors z-50"
                            onMouseDown={(e) => {
                                e.preventDefault();
                                const startX = e.pageX;
                                const startWidth = state.rightPanelWidth;

                                const handleMouseMove = (moveEvent) => {
                                    const delta = moveEvent.pageX - startX;
                                    // Dragging left increases width; dragging right decreases width.
                                    const newWidth = startWidth - delta;
                                    dispatch({ type: 'SET_PANEL_WIDTH', panel: 'right', width: newWidth });
                                };

                                const handleMouseUp = () => {
                                    document.removeEventListener('mousemove', handleMouseMove);
                                    document.removeEventListener('mouseup', handleMouseUp);
                                };

                                document.addEventListener('mousemove', handleMouseMove);
                                document.addEventListener('mouseup', handleMouseUp);
                            }}
                            title="Drag to resize"
                            aria-label="Resize right panel"
                        />
                        {rightPanelContent}
                    </div>
                )}
            </div>

            {/* ========================================================================
                底部状态栏 / Status Bar
                ======================================================================== */}
            <StatusBar />
        </div>
    );
}
