# -*- coding: utf-8 -*-
"""
文枢 WenShape - 深度上下文感知的智能体小说创作系统
WenShape - Deep Context-Aware Agent-Based Novel Writing System

Copyright © 2025-2026 WenShape Team
License: PolyForm Noncommercial License 1.0.0

模块说明 / Module Description:
  档案员智能体 - 管理事实表、生成场景简要和章节摘要。
  Archivist Agent responsible for canon management, scene brief generation, and chapter summaries.
"""

import asyncio
import re
from typing import Any, Dict, List, Optional, Tuple

from app.agents.base import BaseAgent
from app.agents._fanfiction_mixin import FanfictionMixin
from app.agents._summary_mixin import SummaryMixin
from app.schemas.draft import SceneBrief, CardProposal
from app.schemas.card import StyleCard
from app.prompts import (
    get_archivist_system_prompt,
    archivist_style_profile_prompt,
)
from app.config import config
from app.dependencies import get_outline_storage
from app.utils.chapter_id import ChapterIDValidator, normalize_chapter_id
from app.utils.dynamic_ranges import get_chapter_window, get_previous_chapters_limit
from app.utils.logger import get_logger
from app.utils.stopwords import get_stopwords

logger = get_logger(__name__)


class ArchivistAgent(FanfictionMixin, SummaryMixin, BaseAgent):
    """
    档案员智能体 - 维护小说世界观和事实表

    Manages canonical facts, character profiles, and world-building information.
    Generates scene briefs that guide writing and detects new setting elements.
    Ensures all generated content aligns with established story canon.

    Attributes:
        MAX_CHARACTERS: Maximum characters to include in scene brief.
        MAX_WORLD_CONSTRAINTS: Maximum world constraints to include.
        MAX_FACTS: Maximum facts to include per chapter context.
    """

    _archivist_cfg = config.get("archivist", {})
    MAX_CHARACTERS = int(_archivist_cfg.get("max_characters", 5))
    MAX_WORLD_CONSTRAINTS = int(_archivist_cfg.get("max_world_constraints", 5))
    MAX_FACTS = int(_archivist_cfg.get("max_facts", 5))

    @staticmethod
    def _get_chapter_window(window_type: str, total_chapters: int = 0) -> int:
        """
        获取章节窗口大小 - 使用共享的动态范围计算器

        Get chapter window size using shared dynamic range calculator.
        Allows flexible history window based on project size.

        Args:
            window_type: Type of window ("fact", "summary", etc.).
            total_chapters: Total number of chapters in project (for context).

        Returns:
            Window size (number of chapters to include).
        """
        return get_chapter_window(window_type, total_chapters)

    @property
    def STOPWORDS(self) -> set:
        """获取中文停用词集合 - 用于关键词提取"""
        return get_stopwords()

    # Regex patterns for fact quality analysis
    _SIMPLE_RELATION_FACT_RE = re.compile(
        r"^(.{1,12})是(.{1,16})的(母亲|父亲|儿子|女儿|哥哥|姐姐|弟弟|妹妹|妻子|丈夫|恋人|朋友|同学|老师|学生|主人|仆人)[。.!?？]*$"
    )
    _SIMPLE_RELATION_FACT_RE_EN = re.compile(
        r"^(.+?) is (.+?)'?s? (mother|father|son|daughter|brother|sister|wife|husband|lover|friend|classmate|teacher|student|master|servant)[.!?]*$",
        re.IGNORECASE,
    )
    # Keywords indicating high-value facts for ranking
    _FACT_DENSITY_HINTS = (
        "规则", "禁忌", "代价", "必须", "不允许", "禁止", "承诺", "约定", "隐瞒", "秘密", "交易", "交换", "契约",
        "决定", "发现", "暴露", "背叛", "威胁", "受伤", "病", "死亡", "失踪", "获得", "丢失", "准备", "购买",
        "居住", "搬", "上学", "教育", "监护", "占有", "依赖", "恐惧", "愧疚", "同情", "惆怅",
    )
    _FACT_DENSITY_HINTS_EN = (
        "rule", "taboo", "cost", "must", "forbidden", "promise", "agreement", "secret", "deal",
        "betrayal", "threat", "injured", "dead", "missing", "obtained", "lost", "fear", "guilt",
        "decided", "discovered", "revealed", "contract", "obligation", "cannot", "prohibited",
        "owns", "lives", "moved", "dependent", "responsible",
    )

    def _normalize_fact_statement(self, statement: str) -> str:
        """规范化事实陈述 - 用于去重"""
        text = str(statement or "").strip()
        text = re.sub(r"\s+", "", text)
        text = text.strip("。．.！!?？")
        return text

    def _is_simple_relation_fact(self, statement: str) -> bool:
        """检测是否为简单关系事实 - 仅包含亲属关系"""
        text = self._normalize_fact_statement(statement)
        if self.language == "en":
            return bool(self._SIMPLE_RELATION_FACT_RE_EN.match(text))
        return bool(self._SIMPLE_RELATION_FACT_RE.match(text))

    def _score_fact_statement(self, statement: str) -> float:
        """
        评分事实陈述 - 评估信息价值

        Score a fact statement based on content complexity and information density.
        Higher scores indicate more valuable/complex facts.

        Args:
            statement: Fact statement text.

        Returns:
            Score between 0.0 and 5.0+ indicating fact value.
        """
        text = str(statement or "").strip()
        if not text:
            return 0.0

        score = 0.0
        # Length-based scoring: longer statements tend to be more specific
        score += min(len(text) / 18.0, 2.0)
        # Complexity indicators: punctuation marks suggest multiple clauses
        if any(p in text for p in ("，", "；", "：", "（", "）", "(", ")")):
            score += 0.7
        # Numeric data often indicates specific, verifiable facts
        if re.search(r"\d", text):
            score += 0.3
        # Presence of density hints (rules, secrets, decisions, etc.)
        hints = self._FACT_DENSITY_HINTS_EN if self.language == "en" else self._FACT_DENSITY_HINTS
        if any(h in text.lower() for h in hints):
            score += 0.8
        # Penalize simple relation facts (lower information value)
        if self._is_simple_relation_fact(text):
            score -= 0.6
        return score

    def _select_high_value_facts(
        self,
        candidates: List[Tuple[str, float]],
        existing_statements: Optional[List[str]] = None,
        limit: int = 5,
    ) -> List[Tuple[str, float]]:
        """
        选择高价值事实 - 去重、评分、排序

        Select highest-value facts from candidates, avoiding duplicates.
        Prioritizes complex facts over simple relations.

        Args:
            candidates: List of (statement, confidence) tuples.
            existing_statements: Statements to avoid duplicating.
            limit: Maximum facts to return.

        Returns:
            List of (statement, confidence) tuples, sorted by value.
        """
        existing_norm = {self._normalize_fact_statement(s) for s in (existing_statements or []) if str(s or "").strip()}

        uniq: List[Tuple[str, float]] = []
        seen = set(existing_norm)
        for raw_statement, confidence in candidates or []:
            statement = str(raw_statement or "").strip()
            if not statement:
                continue
            normalized = self._normalize_fact_statement(statement)
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            uniq.append((statement, float(confidence)))

        scored = [
            {
                "statement": statement,
                "confidence": max(0.0, min(1.0, float(confidence))),
                "score": self._score_fact_statement(statement),
                "simple_relation": self._is_simple_relation_fact(statement),
            }
            for statement, confidence in uniq
            if len(str(statement or "").strip()) >= 6
        ]
        scored.sort(key=lambda x: (-x["score"], -len(x["statement"]), x["statement"]))

        primary = [item for item in scored if not item["simple_relation"]]
        secondary = [item for item in scored if item["simple_relation"]]

        selected: List[Dict[str, Any]] = []
        for item in primary:
            selected.append(item)
            if len(selected) >= int(limit):
                break

        if len(selected) < int(limit):
            max_rel = 1 if any(not s["simple_relation"] for s in selected) else int(limit)
            rel_used = 0
            for item in secondary:
                if rel_used >= max_rel:
                    break
                selected.append(item)
                rel_used += 1
                if len(selected) >= int(limit):
                    break

        return [(item["statement"], item["confidence"]) for item in selected[: int(limit)]]

    def get_agent_name(self) -> str:
        """获取智能体标识 - 返回 'archivist'"""
        return "archivist"

    def get_system_prompt(self) -> str:
        """获取系统提示词 - 档案员专用"""
        return get_archivist_system_prompt(language=self.language)

    async def execute(self, project_id: str, chapter: str, context: Dict[str, Any]) -> Dict[str, Any]:
        """
        执行档案员任务 - 生成场景简要

        Main entry point for scene brief generation. Collects all relevant context
        (facts, characters, world-building, timeline) and generates a structured
        scene brief to guide the writer.

        Args:
            project_id: Project identifier.
            chapter: Chapter identifier.
            context: Context dict with chapter_title, chapter_goal, characters, etc.

        Returns:
            Dict with success status, scene_brief object, and conflicts list.
        """
        style_card = await self.card_storage.get_style_card(project_id)

        chapter_id = normalize_chapter_id(chapter) or chapter
        chapter_title = context.get("chapter_title", "")
        chapter_goal = context.get("chapter_goal", "")

        instruction_text = " ".join([chapter_title, chapter_goal])
        instruction_characters = await self._extract_mentions_from_texts(
            project_id=project_id,
            texts=[instruction_text],
            card_type="character",
        )
        instruction_worlds = await self._extract_mentions_from_texts(
            project_id=project_id,
            texts=[instruction_text],
            card_type="world",
        )

        # ============================================================================
        # Calculate dynamic chapter windows / 计算动态章节窗口
        # ============================================================================
        # Get total chapters for dynamic range calculation
        all_chapters = await self.draft_storage.list_chapters(project_id)
        total_chapters = len(all_chapters)
        dynamic_limit = get_previous_chapters_limit(total_chapters)

        recent_chapters = await self._get_previous_chapters(project_id, chapter_id, limit=dynamic_limit)
        fact_window = self._get_chapter_window("fact", len(recent_chapters))
        recent_fact_chapters = recent_chapters[-fact_window:] if fact_window < len(recent_chapters) else recent_chapters
        summary_chapters = recent_chapters[:-fact_window] if fact_window < len(recent_chapters) else []

        try:
            from app.services.chapter_binding_service import chapter_binding_service
            seed_names = list(dict.fromkeys([*instruction_characters, *instruction_worlds]))
            bound_chapters = await chapter_binding_service.get_chapters_for_entities(
                project_id,
                seed_names,
                limit=4,
            )
            if bound_chapters:
                recent_fact_chapters = bound_chapters
        except Exception as exc:
            logger.debug("Chapter binding lookup failed, using recent chapters: %s", exc)

        # ============================================================================
        # Build context blocks / 构建上下文块
        # ============================================================================
        summary_blocks = await self._build_summary_blocks(project_id, summary_chapters)
        timeline_events = await self.canon_storage.get_timeline_events_near_chapter(
            project_id=project_id,
            chapter=chapter_id,
            window=3,
            max_events=10,
        )

        keywords = self._extract_keywords(" ".join([instruction_text] + summary_blocks))

        chapter_texts = await self._load_chapter_texts(project_id, recent_fact_chapters)
        mentioned_characters = await self._extract_mentions_from_texts(
            project_id=project_id,
            texts=chapter_texts,
            card_type="character",
        )
        mentioned_worlds = await self._extract_mentions_from_texts(
            project_id=project_id,
            texts=chapter_texts,
            card_type="world",
        )

        character_names = context.get("characters", []) or []
        selected_character_names = self._merge_unique(
            mentioned_characters,
            instruction_characters,
            character_names,
        )
        characters = await self._build_character_context(project_id, selected_character_names)

        world_names = self._merge_unique(mentioned_worlds, instruction_worlds)
        world_constraints = await self._build_world_constraints_from_names(project_id, world_names)

        facts = await self._collect_facts_for_chapters(project_id, recent_fact_chapters)
        extra_facts = await self._select_facts_by_instruction(
            project_id=project_id,
            keywords=keywords,
            exclude_ids={fact.get("id") for fact in facts if fact.get("id")},
            max_extra=5,
        )
        facts.extend(extra_facts)

        style_reminder = self._build_style_reminder(style_card)
        timeline_context = self._build_timeline_context_from_summaries(
            chapter_goal=chapter_goal,
            summaries=summary_blocks,
            fallback_events=timeline_events,
        )

        scene_brief = SceneBrief(
            chapter=chapter_id,
            title=chapter_title or f"Chapter {chapter_id}",
            goal=chapter_goal,
            characters=characters,
            timeline_context=timeline_context,
            world_constraints=world_constraints,
            facts=[fact.get("statement") for fact in facts if fact.get("statement")],
            style_reminder=style_reminder,
            forbidden=[],
        )

        await self.draft_storage.save_scene_brief(project_id, chapter_id, scene_brief)

        return {
            "success": True,
            "scene_brief": scene_brief,
            "conflicts": [],
        }

    async def _build_character_context(self, project_id: str, names: List[str]) -> List[Dict[str, str]]:
        characters = []
        for name in names:
            card = await self.card_storage.get_character_card(project_id, name)
            if not card:
                continue
            traits = card.description
            characters.append(
                {
                    "name": card.name,
                    "relevant_traits": traits,
                }
            )
        return characters

    def _build_timeline_context_from_summaries(
        self,
        chapter_goal: str,
        summaries: List[str],
        fallback_events: List[Any],
    ) -> Dict[str, str]:



        before = summaries[-1] if summaries else ""
        if not before and fallback_events:
            event = fallback_events[-1]
            before = f"{event.time}: {event.event} @ {event.location}"
        

        
        return {
            "before": before,
            "current": chapter_goal,
            "after": "",
        }

    def _extract_participants(self, events: List[Any]) -> List[str]:
        names = []
        for event in events:
            participants = getattr(event, "participants", []) or []
            for name in participants:
                if name and name not in names:
                    names.append(name)
        return names

    async def _build_world_constraints(self, project_id: str, limit: int) -> List[str]:
        constraints = []
        names = await self.card_storage.list_world_cards(project_id)
        for name in names[:limit]:
            card = await self.card_storage.get_world_card(project_id, name)
            if not card:
                continue
            if card.description:
                constraints.append(f"{card.name}: {card.description}")
        return constraints

    def _extract_keywords(self, text: str) -> List[str]:
        if not text:
            return []
        candidates = re.findall(r"[A-Za-z0-9]{2,}|[\u4e00-\u9fff]{2,}", text)
        keywords = []
        for token in candidates:
            if token in self.STOPWORDS:
                continue
            if token not in keywords:
                keywords.append(token)
        return keywords

    def _score_text_match(self, text: str, keywords: List[str]) -> int:
        if not text or not keywords:
            return 0
        score = 0
        for kw in keywords:
            if kw and kw in text:
                score += 1
        return score

    async def _get_previous_chapters(
        self,
        project_id: str,
        current_chapter: str,
        limit: int,
    ) -> List[str]:
        limit = max(int(limit or 0), 0)
        if limit <= 0:
            return []

        chapters = await self.draft_storage.list_chapters(project_id)
        if not chapters:
            return []

        canonical_current = str(current_chapter or "").strip()
        if canonical_current in chapters:
            index = chapters.index(canonical_current)
            return chapters[max(0, index - limit) : index]

        # 当前章节尚未创建：退化为权重比较，但保持 chapters 的既有顺序（包含自定义排序）。
        try:
            current_weight = ChapterIDValidator.calculate_weight(canonical_current)
        except Exception:
            return chapters[max(0, len(chapters) - limit) :]
        if current_weight <= 0:
            return chapters[max(0, len(chapters) - limit) :]
        previous = [ch for ch in chapters if ChapterIDValidator.calculate_weight(ch) < current_weight]
        return previous[max(0, len(previous) - limit) :]

    async def _build_summary_blocks(self, project_id: str, chapters: List[str]) -> List[str]:
        blocks: List[str] = []
        for ch in chapters:
            summary = await self.draft_storage.get_chapter_summary(project_id, ch)
            if not summary:
                continue
            title = summary.title or ch
            brief = summary.brief_summary or ""
            blocks.append(f"{ch}: {title}\n{brief}".strip())
        return blocks

    async def _load_chapter_texts(self, project_id: str, chapters: List[str]) -> List[str]:
        async def _load_one(ch: str) -> str:
            final = await self.draft_storage.get_final_draft(project_id, ch)
            if final:
                return final
            versions = await self.draft_storage.list_draft_versions(project_id, ch)
            if not versions:
                return ""
            draft = await self.draft_storage.get_draft(project_id, ch, versions[-1])
            return draft.content if draft else ""

        return list(await asyncio.gather(*[_load_one(ch) for ch in chapters]))

    async def _extract_mentions_from_texts(
        self,
        project_id: str,
        texts: List[str],
        card_type: str,
    ) -> List[str]:
        names = []
        if card_type == "character":
            names = await self.card_storage.list_character_cards(project_id)
        elif card_type == "world":
            names = await self.card_storage.list_world_cards(project_id)
        if not names:
            return []

        mentioned = []
        for name in names:
            for text in texts:
                if name and text and name in text:
                    mentioned.append(name)
                    break
        return mentioned

    def _merge_unique(self, *groups: List[str]) -> List[str]:
        merged: List[str] = []
        for group in groups:
            for name in group or []:
                if name and name not in merged:
                    merged.append(name)
        return merged

    async def _build_world_constraints_from_names(
        self,
        project_id: str,
        names: List[str],
    ) -> List[str]:
        constraints = []
        for name in names:
            card = await self.card_storage.get_world_card(project_id, name)
            if not card:
                continue
            description = card.description or ""
            constraints.append(f"{card.name}: {description}".strip())
        return constraints

    async def _collect_facts_for_chapters(
        self,
        project_id: str,
        chapters: List[str],
    ) -> List[Dict[str, Any]]:
        if not chapters:
            return []
        chapter_set = {normalize_chapter_id(ch) for ch in chapters if ch}
        facts = await self.canon_storage.get_all_facts_raw(project_id)
        selected = []
        for fact in facts:
            raw_chapter = fact.get("introduced_in") or fact.get("source") or ""
            fact_chapter = normalize_chapter_id(raw_chapter)
            if fact_chapter in chapter_set:
                selected.append(fact)
        return selected

    async def _select_facts_by_instruction(
        self,
        project_id: str,
        keywords: List[str],
        exclude_ids: set,
        max_extra: int,
    ) -> List[Dict[str, Any]]:
        if not keywords:
            return []
        facts = await self.canon_storage.get_all_facts_raw(project_id)
        scored: List[Tuple[int, Dict[str, Any]]] = []
        for fact in facts:
            if fact.get("id") in exclude_ids:
                continue
            statement = str(fact.get("statement") or fact.get("content") or "")
            score = self._score_text_match(statement, keywords)
            if score > 0:
                scored.append((score, fact))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [fact for _, fact in scored[:max_extra]]

    async def _select_character_names(
        self,
        project_id: str,
        chapter_id: str,
        keywords: List[str],
        explicit_names: List[str],
        timeline_events: List[Any],
    ) -> List[str]:
        names = await self.card_storage.list_character_cards(project_id)
        name_scores: Dict[str, int] = {}
        explicit_set = [n for n in explicit_names if n]
        for name in explicit_set:
            name_scores[name] = name_scores.get(name, 0) + 100

        for event in timeline_events or []:
            for name in getattr(event, "participants", []) or []:
                if name:
                    name_scores[name] = name_scores.get(name, 0) + 10

        for name in names:
            score = 0
            if name in keywords:
                score += 5
            score += self._score_text_match(name, keywords)
            if score > 0:
                name_scores[name] = max(name_scores.get(name, 0), score)

        ranked = sorted(name_scores.items(), key=lambda x: x[1], reverse=True)
        selected = [name for name, _ in ranked][: self.MAX_CHARACTERS]
        return selected

    async def _select_relevant_facts(
        self,
        project_id: str,
        chapter_id: str,
        keywords: List[str],
    ) -> List[Dict[str, Any]]:
        all_facts = await self.canon_storage.get_all_facts_raw(project_id)
        if not all_facts:
            return []

        parsed_current = ChapterIDValidator.parse(chapter_id)
        previous_same_volume: Optional[str] = None
        if parsed_current and parsed_current.get("volume") and parsed_current.get("chapter") is not None:
            chapters = await self.draft_storage.list_chapters(project_id)
            candidates = []
            for ch in chapters:
                parsed = ChapterIDValidator.parse(ch)
                if not parsed or parsed.get("volume") != parsed_current.get("volume"):
                    continue
                if parsed.get("chapter", 0) < parsed_current.get("chapter", 0):
                    candidates.append((parsed.get("chapter", 0), ch))
            if candidates:
                previous_same_volume = max(candidates, key=lambda x: x[0])[1]

        selected: List[Dict[str, Any]] = []
        remaining = []

        for fact in all_facts:
            fact_chapter = normalize_chapter_id(
                fact.get("introduced_in") or fact.get("source") or ""
            )
            if previous_same_volume and fact_chapter == previous_same_volume:
                selected.append(fact)
            else:
                remaining.append(fact)

        if len(selected) < self.MAX_FACTS:
            scored: List[Tuple[int, Dict[str, Any]]] = []
            for fact in remaining:
                statement = str(fact.get("statement") or fact.get("content") or "")
                fact_chapter = normalize_chapter_id(
                    fact.get("introduced_in") or fact.get("source") or ""
                )
                dist = ChapterIDValidator.calculate_distance(chapter_id, fact_chapter) if fact_chapter else 999
                recency = max(0, 10 - min(dist, 10))
                match = self._score_text_match(statement, keywords) * 2
                score = recency + match
                if score > 0:
                    scored.append((score, fact))
            scored.sort(key=lambda x: x[0], reverse=True)
            for _, fact in scored:
                if len(selected) >= self.MAX_FACTS:
                    break
                selected.append(fact)

        return selected[: self.MAX_FACTS]

    async def _select_world_constraints(
        self,
        project_id: str,
        keywords: List[str],
        facts: List[Dict[str, Any]],
        summaries: List[str],
    ) -> List[str]:
        names = await self.card_storage.list_world_cards(project_id)
        if not names:
            return []

        facts_text = " ".join([str(f.get("statement") or "") for f in facts])
        summary_text = " ".join(summaries)
        combined = " ".join([facts_text, summary_text])
        combined_keywords = list(dict.fromkeys(keywords + self._extract_keywords(combined)))

        scored: List[Tuple[int, str]] = []
        for name in names:
            card = await self.card_storage.get_world_card(project_id, name)
            if not card:
                continue
            text = f"{card.name} {card.description or ''}"
            score = 0
            if card.name and card.name in combined:
                score += 5
            score += self._score_text_match(text, combined_keywords)
            if score > 0:
                scored.append((score, f"{card.name}: {card.description or ''}".strip()))

        scored.sort(key=lambda x: x[0], reverse=True)
        return [item for _, item in scored][: self.MAX_WORLD_CONSTRAINTS]

    def _build_style_reminder(self, style_card: Optional[StyleCard]) -> str:
        if not style_card:
            return ""
        style_text = getattr(style_card, "style", "") or ""
        return style_text.strip()



    async def detect_setting_changes(self, draft_content: str, existing_card_names: List[str]) -> List[CardProposal]:
        """Detect potential new setting cards with pure heuristics (no LLM)."""
        existing = {name for name in (existing_card_names or []) if name}
        proposals: List[CardProposal] = []

        text = draft_content or ""
        if not text.strip():
            return proposals

        sentences = self._split_sentences(text)

        world_candidates = self._extract_world_candidates(text)
        character_candidates = self._extract_character_candidates(text)

        proposals.extend(
            self._build_card_proposals(
                candidates=world_candidates,
                card_type="World",
                existing=existing,
                sentences=sentences,
                min_count=2,
            )
        )
        proposals.extend(
            self._build_card_proposals(
                candidates=character_candidates,
                card_type="Character",
                existing=existing,
                sentences=sentences,
                min_count=1,
            )
        )

        return proposals

    def _split_sentences(self, text: str) -> List[str]:
        parts = re.split(r"[。！？\n]", text)
        return [p.strip() for p in parts if p.strip()]

    def _extract_world_candidates(self, text: str) -> Dict[str, int]:
        suffixes = "帮|派|门|宗|城|山|谷|镇|村|府|馆|寺|庙|观|宫|殿|岛|关|寨|营|会|国|州|郡|湾|湖|河"
        pattern = re.compile(rf"([\u4e00-\u9fff]{{2,8}}(?:{suffixes}))")
        counts: Dict[str, int] = {}
        for match in pattern.findall(text):
            counts[match] = counts.get(match, 0) + 1
        return counts

    def _extract_character_candidates(self, text: str) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        if not text:
            return counts

        say_pattern = re.compile(r"([\u4e00-\u9fff]{2,3})(?:\s*)(?:说道|问道|答道|笑道|喝道|低声道|沉声道|道)")
        action_verbs = "走|看|望|想|叹|笑|皱|点头|摇头|转身|停下|沉默|开口|伸手|拔剑|抬眼"
        action_pattern = re.compile(rf"([\u4e00-\u9fff]{{2,3}})(?:\s*)(?:{action_verbs})")

        for match in say_pattern.findall(text):
            if match in self.STOPWORDS:
                continue
            counts[match] = counts.get(match, 0) + 2

        for match in action_pattern.findall(text):
            if match in self.STOPWORDS:
                continue
            counts[match] = counts.get(match, 0) + 1

        return counts

    def _build_card_proposals(
        self,
        candidates: Dict[str, int],
        card_type: str,
        existing: set,
        sentences: List[str],
        min_count: int = 2,
    ) -> List[CardProposal]:
        proposals: List[CardProposal] = []
        for name, count in candidates.items():
            if not name or name in existing:
                continue
            if count < min_count:
                continue
            source_sentence = ""
            for sent in sentences:
                if name in sent:
                    source_sentence = sent
                    break
            if not source_sentence:
                continue
            confidence = min(0.9, 0.5 + 0.1 * min(count, 4))
            proposals.append(
                CardProposal(
                    name=name,
                    type=card_type,
                    description=source_sentence,
                    rationale=f"在本章中多次出现（{count} 次），具备可复用设定价值。",
                    source_text=source_sentence,
                    confidence=confidence,
                )
            )
        return proposals

    def _sample_text_for_style_profile(self, sample_text: str, max_chars: int = 20000) -> str:
        """
        采样文风提炼用的文本片段。

        目的：
        - 避免超长正文导致中段信息被截断
        - 让文风提炼同时“看到”开头/中段/结尾，提升稳定性
        """
        text = str(sample_text or "").strip()
        if not text:
            return ""
        if max_chars <= 0 or len(text) <= max_chars:
            return text

        head_len = int(max_chars * 0.35)
        tail_len = int(max_chars * 0.35)
        mid_len = max_chars - head_len - tail_len

        head = text[:head_len]
        tail = text[-tail_len:] if tail_len > 0 else ""

        mid_start = max(0, (len(text) // 2) - (mid_len // 2))
        mid = text[mid_start : mid_start + mid_len] if mid_len > 0 else ""

        parts = [p for p in [head, mid, tail] if p]
        return "\n\n……\n\n".join(parts)

    async def extract_style_profile(self, sample_text: str) -> str:
        """Extract writing style guidance from sample text."""
        provider = self.gateway.get_provider_for_agent(self.get_agent_name())
        if provider == "mock":
            return ""

        sampled = self._sample_text_for_style_profile(sample_text, max_chars=20000)
        prompt = archivist_style_profile_prompt(sample_text=sampled, language=self.language)
        messages = self.build_messages(
            system_prompt=prompt.system,
            user_prompt=prompt.user,
            context_items=None,
        )
        response = await self.call_llm(messages)
        return str(response or "").strip()

    # ------------------------------------------------------------------
    # AI 卡片生成 / AI Card Generation
    # ------------------------------------------------------------------

    async def generate_card_description(
        self,
        name: str,
        card_type: str,
        style_hint: str = "",
        note: str = "",
        existing_characters: Optional[List[str]] = None,
    ) -> str:
        """根据名字和约束，AI 生成单张卡片的描述。

        Generate a description for a single card (character or world)
        based on name, card type, style hint, and optional note.

        Args:
            name: 卡片名字 / Card name.
            card_type: "character" 或 "world" / Card type.
            style_hint: 文风提示 / Writing style hint.
            note: 补充说明 / Supplementary note.
            existing_characters: 已有角色名列表，用于约束 AI 不重复 / Existing character names.

        Returns:
            生成的描述文本 / Generated description text.
        """
        lang = self.language or "zh"
        type_label = "人物角色" if card_type == "character" else "世界观设定"
        existing_block = ""
        if existing_characters:
            existing_block = "\n\n已有角色（不得与下列名字的设定相矛盾）：\n" + "、".join(existing_characters[:30])

        style_block = f"\n\n文风要求：{style_hint}" if style_hint else ""
        note_block = f"\n\n补充说明：{note}" if note else ""

        if lang == "en":
            system = (
                "You are a creative writing assistant. Generate a concise, vivid description "
                "for a novel card entry. Output ONLY the description text, no labels or formatting."
            )
            user = (
                f"Generate a description for a {type_label} card named \"{name}\"."
                f"{style_block}{note_block}{existing_block}\n\n"
                "Requirements:\n"
                "- 80-300 words\n"
                "- Describe personality, background, appearance (for character) or "
                "rules, atmosphere, significance (for world)\n"
                "- Stay consistent with any existing characters listed above\n"
                "- Output description text only"
            )
        else:
            system = (
                "你是一位专业的小说创作助手。请为卡片库生成简洁、生动的描述。"
                "只输出描述正文，不要标题、标签或格式符号。"
            )
            user = (
                f"请为「{name}」生成一段{type_label}卡片描述。"
                f"{style_block}{note_block}{existing_block}\n\n"
                "要求：\n"
                "- 150-400字\n"
                "- 角色卡：涵盖性格、背景、外貌特征；世界观卡：涵盖概念定义、规则、氛围意义\n"
                "- 不得与上方已有角色的设定相矛盾\n"
                "- 只输出描述正文"
            )

        messages = self.build_messages(
            system_prompt=system,
            user_prompt=user,
            context_items=None,
        )
        response = await self.call_llm(messages)
        return str(response or "").strip()

    async def generate_chapter_outline(self, project_id: str, chapter_id: str, instruction: str) -> str:
        """
        根据创作指令和现有大纲/摘要推导本章结构大纲
        """
        outline_storage = get_outline_storage()
        
        master = await outline_storage.get_master_outline(project_id)
        master_content = master.get("content", "") if master else ""
        
        volume_id = ChapterIDValidator.extract_volume_id(chapter_id) or "V1"
        volume = await outline_storage.get_volume_outline(project_id, volume_id)
        volume_content = volume.get("content", "") if volume else ""
        
        chapters = await self._get_previous_chapters(project_id, chapter_id, limit=3)
        recent_summaries = await self._build_summary_blocks(project_id, chapters)

        sections = []
        if master_content:
            sections.append(f"[总纲 / Master Outline]\n{master_content}")
        if volume_content:
            sections.append(f"[{volume_id} 分卷纲 / Volume Outline]\n{volume_content}")
        if recent_summaries:
            sections.append("[前情提要 / Recent Events]\n" + "\n".join(recent_summaries))

        sections.append(f"[本章创作目标 / Chapter Goal for '{chapter_id}']\n{instruction}")

        body = "\n\n".join(sections)
        
        if self.language == "en":
            system = "You are a professional novel structural outliner. Follow user instructions to generate a chapter outline based on context."
            user = f"{body}\n\nPlease generate a detailed structural outline for this chapter based on the context and chapter goal above. Output points or steps on how the plot will progress in this chapter. Focus on story progression. Do not output actual novel text, just the structure."
        else:
            system = "你是一位专业的小说结构大纲师，请根据用户的给定上下文和创作目标，构思精炼的章节大纲。"
            user = f"{body}\n\n请根据以上上下文和本章创作目标，构思并输出一份此章节的详细结构大纲。你需要以步骤或要点的形式说明本章剧情将如何推进，以及核心事件的内容。请只输出大纲结构内容，尽量简明扼要，不要输出具体的正文文字内容。"

        messages = self.build_messages(
            system_prompt=system,
            user_prompt=user,
            context_items=None,
        )
        response = await self.call_llm(messages)
        return str(response or "").strip()

    async def extract_cards_from_outline(
        self,
        outline_text: str,
        max_cards: int = 20,
    ) -> List[Dict[str, Any]]:
        """从大纲文本中提取角色卡和世界观卡列表。

        Extract character and world cards from outline text.
        Results are deduplicated by name and filtered for quality.

        Args:
            outline_text: 大纲文本（可包含总纲、分卷纲、章节细纲） / Outline text.
            max_cards: 最大返回卡片数（默认20）/ Maximum cards to return.

        Returns:
            卡片字典列表，每项含 card_type/name/description/aliases/category/rules/stars
        """
        import json as _json

        lang = self.language or "zh"

        if lang == "en":
            system = (
                "You are a fiction editor. Extract all notable characters and world-building elements "
                "from the provided outline. Output a JSON array only, no markdown fences.\n\n"
                "Each item must follow this schema exactly:\n"
                '{"card_type": "character"|"world", "name": "...", "description": "...", '
                '"aliases": [], "category": "", "rules": [], "stars": 1|2|3}\n\n'
                "Rules:\n"
                "- card_type: character for persons, world for places/factions/concepts/items\n"
                "- description: 50-300 words, meaningful content only\n"
                "- stars: 3=protagonist/core setting, 2=supporting, 1=minor\n"
                "- aliases: alternative names for the same entity\n"
                "- category: for world cards only (e.g. location, faction, concept, item)\n"
                "- rules: for world cards only, list of rules/constraints\n"
                "- Deduplicate by name\n"
                f"- Return at most {max_cards} items\n"
                "- Output valid JSON array only"
            )
            user = f"Outline:\n\n{outline_text[:8000]}"
        else:
            system = (
                "你是一位专业的小说编辑。从下方大纲中提取所有值得建档的角色和世界观元素，"
                "输出 JSON 数组，不要 markdown 代码块。\n\n"
                "每项严格遵守以下结构：\n"
                '{"card_type": "character"|"world", "name": "...", "description": "...", '
                '"aliases": [], "category": "", "rules": [], "stars": 1|2|3}\n\n'
                "规则：\n"
                "- card_type：人物用 character，地点/势力/概念/物品用 world\n"
                "- description：50-300字，有实质内容\n"
                "- stars：3=主角/核心设定，2=重要配角/重要设定，1=次要角色/背景设定\n"
                "- aliases：该实体的别名列表（可为空数组）\n"
                "- category：仅世界观卡填写（如：地点、势力、功法、物品、概念）\n"
                "- rules：仅世界观卡填写，该设定的规则约束列表（可为空数组）\n"
                "- 按名字去重\n"
                f"- 最多返回 {max_cards} 项\n"
                "- 只输出合法 JSON 数组"
            )
            user = f"大纲内容：\n\n{outline_text[:8000]}"

        messages = self.build_messages(
            system_prompt=system,
            user_prompt=user,
            context_items=None,
        )
        response = await self.call_llm(messages)
        raw = str(response or "").strip()

        # 解析 JSON
        try:
            # 去掉可能残留的 markdown 代码块
            if raw.startswith("```"):
                raw = re.sub(r"^```[a-z]*\n?", "", raw)
                raw = re.sub(r"\n?```$", "", raw).strip()
            cards = _json.loads(raw)
            if not isinstance(cards, list):
                cards = []
        except Exception:
            logger.warning("extract_cards_from_outline: JSON parse failed, raw=%s", raw[:200])
            cards = []

        # 过滤和后处理
        seen_names: set = set()
        result = []
        for item in cards:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "").strip()
            description = str(item.get("description") or "").strip()
            card_type = str(item.get("card_type") or "character")
            if card_type not in ("character", "world"):
                card_type = "character"

            # 跳过无效项
            if not name:
                continue
            # 质量过滤：描述长度不足
            if len(description) < 15:
                continue
            # 去重（按名字，大小写不敏感）
            name_key = name.lower()
            if name_key in seen_names:
                continue
            seen_names.add(name_key)

            # 星级约束
            stars = item.get("stars")
            try:
                stars = int(stars)
                if stars not in (1, 2, 3):
                    stars = 1
            except (TypeError, ValueError):
                stars = 1

            aliases = item.get("aliases") or []
            if not isinstance(aliases, list):
                aliases = []
            aliases = [str(a).strip() for a in aliases if str(a).strip()]

            entry: Dict[str, Any] = {
                "card_type": card_type,
                "name": name,
                "description": description,
                "aliases": aliases,
                "stars": stars,
            }
            if card_type == "world":
                entry["category"] = str(item.get("category") or "").strip()
                rules = item.get("rules") or []
                if not isinstance(rules, list):
                    rules = []
                entry["rules"] = [str(r).strip() for r in rules if str(r).strip()]

            result.append(entry)
            if len(result) >= max_cards:
                break

        return result
