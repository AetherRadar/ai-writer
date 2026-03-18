# -*- coding: utf-8 -*-
"""
文枢 WenShape - 深度上下文感知的智能体小说创作系统
WenShape - Deep Context-Aware Agent-Based Novel Writing System

Copyright © 2025-2026 WenShape Team
License: PolyForm Noncommercial License 1.0.0

模块说明 / Module Description:
  上下文选择引擎 - 智能选择相关上下文项
  Context Selection Engine - Intelligently selects relevant context items for LLM calls
  Supports both deterministic selection (critical items) and retrieval-based selection
  (ranked by relevance using embeddings or BM25).
"""

from typing import List, Optional, Dict, Any
import re
import math
from .models import ContextItem, ContextPriority, ContextType
from .text_tokenizer import calculate_overlap_score, calculate_bm25_score
from app.utils.logger import get_logger

logger = get_logger(__name__)


class ContextSelectEngine:
    """
    上下文选择引擎 - 为LLM调用选择最相关的上下文项

    Selects relevant context items for writing agents based on query relevance.
    Supports both deterministic selection (always include critical items like style cards)
    and retrieval-based selection (rank by relevance using embeddings or keyword matching).

    Attributes:
        embeddings (Optional): 嵌入服务实例 / Optional embeddings service for semantic ranking.
        MAX_CANDIDATES_PER_TYPE (int): 每种类型最大候选数量 / Max candidates per item type.
    """

    def __init__(self, embeddings_service=None):
        """
        初始化上下文选择引擎 / Initialize the context selection engine.

        Args:
            embeddings_service: 可选的嵌入服务 / Optional embeddings service for semantic similarity.
        """
        self.embeddings = embeddings_service

    # ========================================================================
    # 确定性选择：必须加载的关键项 / Deterministic Selection: Critical items
    # ========================================================================

    async def deterministic_select(self, project_id: str, agent_name: str, storage: Any) -> List[ContextItem]:
        """
        确定性选择 - 加载特定智能体必须使用的项 / Deterministic selection for critical items.

        Always loads critical items (like style cards) that should be included
        regardless of query relevance. Maintains consistent voice and style.

        Args:
            project_id: 项目ID / Project identifier.
            agent_name: 智能体名称 / Agent name (archivist, writer, editor).
            storage: 统一存储适配器 / Unified storage adapter.

        Returns:
            关键上下文项列表 / List of critical ContextItems.
        """
        items = []
        always_load_map = {
            "archivist": ["style_card"],
            "writer": ["style_card", "scene_brief"],
            "editor": ["style_card"],
        }

        item_types = always_load_map.get(agent_name, [])
        for item_type in item_types:
            item = await self._load_item(project_id, item_type, storage)
            if item:
                item.priority = ContextPriority.CRITICAL
                items.append(item)
        return items

    async def _load_item(self, project_id: str, item_type: str, storage: Any) -> Optional[ContextItem]:
        """
        加载单个上下文项（如风格卡片） / Load a single context item (e.g., style card).

        Args:
            project_id: 项目ID / Project identifier.
            item_type: 项目类型 / Item type (style_card, scene_brief, etc).
            storage: 统一存储适配器 / Unified storage adapter.

        Returns:
            上下文项或None / ContextItem or None if not found.
        """
        try:
            if item_type == "style_card":
                card = await storage.get_style_card(project_id)
                if card:
                    content = self._format_card(card)
                    if self._looks_like_rewrite_prompt(content):
                        # 防止把“去 AI 重写提示词”当作风格卡注入正文，导致强烈 AI 味/长句/总结腔等副作用。
                        logger.warning("Style card looks like a rewrite prompt; skipping injection for safety.")
                        return None
                    return ContextItem(
                        id="style_card",
                        type=ContextType.STYLE_CARD,
                        content=content,
                        priority=ContextPriority.CRITICAL,
                    )
        except Exception as exc:
            logger.warning("Error loading %s: %s", item_type, exc)
        return None

    def _format_card(self, card: Dict[str, Any]) -> str:
        """
        格式化卡片为可读字符串 / Format card dict as readable string.

        Args:
            card: 卡片数据 / Card data dict or object.

        Returns:
            格式化的字符串 / Formatted string representation.
        """
        if hasattr(card, "model_dump"):
            try:
                payload = card.model_dump(exclude_none=True)
                if isinstance(payload, dict):
                    return "\n".join(f"{k}: {v}" for k, v in payload.items() if v)
            except Exception:
                pass
        if isinstance(card, dict):
            return "\n".join(f"{k}: {v}" for k, v in card.items() if v)
        return str(card)

    def _looks_like_rewrite_prompt(self, text: str) -> bool:
        """
        Heuristic guard: some users accidentally paste a de-AI rewrite prompt into `cards/style.yaml`.
        That content is toxic for drafting (it forces long sentences, meta instructions, and detector talk).

        We keep this narrow to avoid false positives for legitimate "avoid AI taste" style notes.
        """
        raw = str(text or "").strip()
        if not raw:
            return False

        # Normalize for easier matching across whitespace / punctuation variants.
        compact = re.sub(r"\s+", "", raw).lower()

        # Strong markers of a rewrite/humanize prompt (not a prose style handbook).
        strong_markers = [
            "chaoticspokenflow",
            "一逗到底",
            "极致拟人化",
            "混沌增益",
            "strictprohibitions",
            "executionsteps",
        ]
        if any(m.lower() in compact for m in strong_markers if m):
            return True

        # AI detector evasion phrasing: keep it specific ("AI 检测") not generic "AI 味".
        if "ai检测" in compact or "ai检测工具" in compact:
            return True
        if "规避" in raw and ("检测" in raw or "ai检测" in compact):
            return True

        return False

    # ========================================================================
    # 检索式选择：基于查询的相关性排序 / Retrieval Selection: Query-based ranking
    # ========================================================================

    # Maximum candidates to load per item type to prevent memory bloat
    # 每种类型最大候选加载数量，防止内存膨胀
    MAX_CANDIDATES_PER_TYPE = 50

    async def retrieval_select(
        self,
        project_id: str,
        query: str,
        item_types: List[str],
        storage: Any,
        top_k: int = 5,
    ) -> List[ContextItem]:
        """
        检索式选择 - 基于查询相关性排序项目 / Retrieval-based selection ranked by query relevance.

        Loads candidates from each item type, computes relevance scores using embeddings
        or keyword matching, and returns top-k most relevant items.

        Args:
            project_id: 项目ID / Project identifier.
            query: 搜索查询文本 / Search query text.
            item_types: 要搜索的项目类型列表 / Item types to search (character, world, fact, text_chunk).
            storage: 统一存储适配器 / Unified storage adapter.
            top_k: 返回的最大项目数 / Maximum items to return (default 5).

        Returns:
            按相关性排序的上下文项列表 / List of ContextItems sorted by relevance.
        """
        query = str(query or "").strip()
        if not query:
            return []

        top_k = max(int(top_k or 0), 0)
        if top_k <= 0:
            return []

        item_types = [str(t or "").strip().lower() for t in (item_types or []) if str(t or "").strip()]
        if not item_types:
            return []

        candidates: List[ContextItem] = []

        def score_text(text: str) -> float:
            text = str(text or "").strip()
            if not text:
                return 0.0
            try:
                overlap = calculate_overlap_score(query, text)
            except Exception:
                overlap = 0.0
            try:
                bm25 = calculate_bm25_score(query, text)
            except Exception:
                bm25 = 0.0
            # Hybrid lexical score: overlap provides robustness for short queries,
            # bm25 stabilizes for longer contexts.
            return float(overlap) * 0.35 + float(bm25) * 0.65

        # Character cards / 角色卡
        if "character" in item_types:
            try:
                names = await storage.list_character_cards(project_id)
            except Exception as exc:
                logger.warning("Failed to list character cards: %s", exc)
                names = []
            for name in (names or [])[: self.MAX_CANDIDATES_PER_TYPE]:
                try:
                    card = await storage.get_character_card(project_id, name)
                except Exception:
                    card = None
                if not card:
                    continue
                content = self._format_card(card)
                s = score_text(content)
                if s <= 0:
                    continue
                candidates.append(
                    ContextItem(
                        id=f"char_{name}",
                        type=ContextType.CHARACTER_CARD,
                        content=content,
                        priority=ContextPriority.MEDIUM,
                        relevance_score=s,
                        metadata={"name": name},
                    )
                )

        # World cards / 世界观卡
        if "world" in item_types:
            try:
                names = await storage.list_world_cards(project_id)
            except Exception as exc:
                logger.warning("Failed to list world cards: %s", exc)
                names = []
            for name in (names or [])[: self.MAX_CANDIDATES_PER_TYPE]:
                try:
                    card = await storage.get_world_card(project_id, name)
                except Exception:
                    card = None
                if not card:
                    continue
                content = self._format_card(card)
                s = score_text(content)
                if s <= 0:
                    continue
                candidates.append(
                    ContextItem(
                        id=f"world_{name}",
                        type=ContextType.WORLD_CARD,
                        content=content,
                        priority=ContextPriority.MEDIUM,
                        relevance_score=s,
                        metadata={"name": name},
                    )
                )

        # Canon facts / 事实
        if "fact" in item_types:
            try:
                facts = await storage.get_all_facts(project_id)
            except Exception as exc:
                logger.warning("Failed to load facts: %s", exc)
                facts = []
            for idx, fact in enumerate((facts or [])[: self.MAX_CANDIDATES_PER_TYPE]):
                try:
                    statement = str(getattr(fact, "statement", "") or "").strip()
                    fact_id = str(getattr(fact, "id", "") or "").strip() or f"F{idx + 1:04d}"
                    introduced_in = str(getattr(fact, "introduced_in", "") or "").strip()
                except Exception:
                    continue
                if not statement:
                    continue
                s = score_text(statement)
                if s <= 0:
                    continue
                candidates.append(
                    ContextItem(
                        id=fact_id,
                        type=ContextType.FACT,
                        content=statement,
                        priority=ContextPriority.MEDIUM,
                        relevance_score=s,
                        metadata={"introduced_in": introduced_in},
                    )
                )

        # Text chunks / 正文片段
        if "text_chunk" in item_types:
            try:
                chunks = await storage.search_text_chunks(project_id, query, limit=self.MAX_CANDIDATES_PER_TYPE)
            except Exception as exc:
                logger.warning("Failed to search text chunks: %s", exc)
                chunks = []
            for idx, chunk in enumerate(chunks or []):
                if not isinstance(chunk, dict):
                    continue
                text = str(chunk.get("text") or "").strip()
                if not text:
                    continue
                s = score_text(text)
                if s <= 0:
                    continue
                candidates.append(
                    ContextItem(
                        id=f"text_{idx}",
                        type=ContextType.TEXT_CHUNK,
                        content=text,
                        priority=ContextPriority.LOW,
                        relevance_score=s,
                        metadata={"source": chunk.get("source") or {}, "chapter": chunk.get("chapter")},
                    )
                )

        if not candidates:
            return []

        candidates.sort(key=lambda item: float(item.relevance_score or 0.0), reverse=True)
        return candidates[:top_k]
