import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button, cn } from '../ui/core';
import { AlertCircle, CheckCircle2, ChevronDown } from 'lucide-react';
import { useLocale } from '../../i18n';

export function OutlineCardExtractDialog({
    open,
    onOpenChange,
    cards = [], // Array of generated cards
    existingCharacters = [], // Existing character names
    existingWorlds = [], // Existing world names
    onConfirm,
    isSubmitting = false
}) {
    const { t } = useLocale();

    // internal state for edits
    const [editedCards, setEditedCards] = useState([]);

    useEffect(() => {
        if (open && cards.length > 0) {
            setEditedCards(cards.map(c => ({
                ...c,
                // Default checked, but if strictly conflicting, could decide to uncheck
                selected: true,
                // Check conflicts
                isCharacterConflict: existingCharacters.includes(c.name),
                isWorldConflict: existingWorlds.includes(c.name)
            })));
        } else {
            setEditedCards([]);
        }
    }, [open, cards, existingCharacters, existingWorlds]);

    const handleUpdateField = (index, field, value) => {
        setEditedCards(prev => {
            const next = [...prev];
            next[index] = { ...next[index], [field]: value };
            return next;
        });
    };

    const handleConfirm = () => {
        const selectedCards = editedCards.filter(c => c.selected);
        onConfirm(selectedCards);
    };

    const hasAnyConflict = editedCards.some(c => c.isCharacterConflict || c.isWorldConflict);

    if (!open) return null;

    return createPortal(
        <>
            {/* Overlay */}
            <div className="fixed inset-0 bg-black/50 z-[100] flex items-center justify-center p-4">
                {/* Dialog Content */}
                <div className="bg-[var(--vscode-bg)] text-[var(--vscode-fg)] w-full max-w-4xl max-h-[90vh] flex flex-col rounded shadow-xl border border-[var(--vscode-sidebar-border)] z-[101]">
                    {/* Header */}
                    <div className="px-6 py-4 border-b border-[var(--vscode-sidebar-border)] flex items-center justify-between font-bold">
                        <span className="text-lg">提取名片 ({editedCards.filter(c => c.selected).length}/{editedCards.length})</span>
                    </div>

                    {/* Body */}
                    <div className="flex-1 overflow-y-auto p-6 space-y-4">
                        {hasAnyConflict && (
                            <div className="mb-4 flex items-start gap-2 bg-[var(--vscode-inputValidation-warningBackground,rgba(255,204,0,0.1))] text-[var(--vscode-inputValidation-warningForeground,#ffcc00)] p-3 rounded-md text-sm border border-[var(--vscode-inputValidation-warningBorder,rgba(255,204,0,0.4))]">
                                <AlertCircle className="w-5 h-5 flex-shrink-0" />
                                <p>部分卡片与已有卡片重名。选择“跳过”将取消导入，选择“更新”将覆盖原有卡片内容（默认更新）。</p>
                            </div>
                        )}

                        <div className="space-y-4">
                            {editedCards.map((card, idx) => {
                                const isExisting = card.card_type === 'character' ? card.isCharacterConflict : card.isWorldConflict;
                                const isCrossConflict = card.card_type === 'character' ? card.isWorldConflict : card.isCharacterConflict;

                                return (
                                    <div key={idx} className={cn(
                                        "p-4 rounded-lg border bg-[var(--vscode-editor-background)] transition-colors",
                                        !card.selected ? "opacity-60 border-[var(--vscode-sidebar-border)]" :
                                            isExisting ? "border-yellow-500/50 shadow-sm" :
                                                isCrossConflict ? "border-red-500/50 shadow-sm" :
                                                    "border-[var(--vscode-sidebar-border)] shadow-sm"
                                    )}>
                                        <div className="flex items-start gap-4">
                                            <div className="pt-1">
                                                <input
                                                    type="checkbox"
                                                    checked={card.selected}
                                                    onChange={e => handleUpdateField(idx, 'selected', e.target.checked)}
                                                    className="w-4 h-4 rounded accent-[var(--vscode-button-background)]"
                                                />
                                            </div>

                                            <div className="flex-1 space-y-3">
                                                {/* Header Row */}
                                                <div className="flex flex-wrap items-center gap-3">
                                                    <input
                                                        type="text"
                                                        value={card.name}
                                                        onChange={e => handleUpdateField(idx, 'name', e.target.value)}
                                                        className="font-bold bg-transparent border-b border-transparent hover:border-[var(--vscode-input-border)] focus:border-[var(--vscode-focusBorder)] focus:outline-none px-1 text-[var(--vscode-fg)] w-32"
                                                    />

                                                    <select
                                                        value={card.card_type}
                                                        onChange={e => handleUpdateField(idx, 'card_type', e.target.value)}
                                                        className="text-xs px-2 py-1 rounded bg-[var(--vscode-input-bg)] border border-[var(--vscode-input-border)] text-[var(--vscode-fg)] focus:outline-none focus:border-[var(--vscode-focusBorder)]"
                                                    >
                                                        <option value="character">人物角色</option>
                                                        <option value="world">世界观设定</option>
                                                    </select>

                                                    <div className="flex items-center gap-1">
                                                        <span className="text-xs text-[var(--vscode-fg-subtle)]">权重:</span>
                                                        <select
                                                            value={card.stars || 1}
                                                            onChange={e => handleUpdateField(idx, 'stars', parseInt(e.target.value, 10))}
                                                            className="text-xs px-1 py-1 rounded bg-[var(--vscode-input-bg)] border border-[var(--vscode-input-border)] text-[var(--vscode-fg)] focus:outline-none focus:border-[var(--vscode-focusBorder)]"
                                                        >
                                                            <option value={1}>1 星 (次要)</option>
                                                            <option value={2}>2 星 (关键)</option>
                                                            <option value={3}>3 星 (核心)</option>
                                                        </select>
                                                    </div>

                                                    {isExisting && (
                                                        <span className="text-[10px] px-2 py-0.5 rounded bg-yellow-500/20 text-yellow-600 font-medium">
                                                            同类型重名 (将更新)
                                                        </span>
                                                    )}
                                                    {isCrossConflict && (
                                                        <span className="text-[10px] px-2 py-0.5 rounded bg-red-500/20 text-red-600 font-medium">
                                                            ⚠️ 跨类型重名
                                                        </span>
                                                    )}
                                                </div>

                                                {/* Description Row */}
                                                <div>
                                                    <textarea
                                                        value={card.description}
                                                        onChange={e => handleUpdateField(idx, 'description', e.target.value)}
                                                        className="w-full h-20 p-2 text-sm rounded bg-[var(--vscode-input-bg)] border border-[var(--vscode-input-border)] text-[var(--vscode-fg)] focus:outline-none focus:border-[var(--vscode-focusBorder)] resize-y"
                                                        placeholder="描述..."
                                                    />
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </div>

                    {/* Footer */}
                    <div className="px-6 py-4 border-t border-[var(--vscode-widget-border)] flex justify-end gap-3 mt-auto">
                        <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={isSubmitting}>
                            取消
                        </Button>
                        <Button
                            onClick={handleConfirm}
                            disabled={isSubmitting || editedCards.filter(c => c.selected).length === 0}
                        >
                            {isSubmitting ? "正在导入..." : `导入选中的 ${editedCards.filter(c => c.selected).length} 项`}
                        </Button>
                    </div>
                </div>
            </div>
        </>,
        document.body
    );
}
