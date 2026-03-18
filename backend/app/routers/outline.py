# -*- coding: utf-8 -*-
"""
独立大纲路由 / Independent outline router.

Provides APIs for planning outlines that are independent from正文和摘要:
- 总纲 (master)
- 分卷纲 (volume)
- 章节细纲 (chapter)
"""

from typing import List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.dependencies import (
    get_draft_storage,
    get_outline_storage,
    get_volume_storage,
    get_card_storage,
    get_canon_storage,
)
from app.utils.chapter_id import ChapterIDValidator, normalize_chapter_id
from app.utils.language import normalize_language
from app.llm_gateway import get_gateway
from app.agents.archivist import ArchivistAgent
router = APIRouter(prefix="/projects/{project_id}/outline", tags=["outline"])
outline_storage = get_outline_storage()
volume_storage = get_volume_storage()
draft_storage = get_draft_storage()


class OutlineUpdateRequest(BaseModel):
    content: str = Field(default="", description="Outline content")


class OutlineGenerateRequest(BaseModel):
    chapter_id: str = Field(..., description="Target chapter ID")
    instruction: str = Field(..., description="User instruction for the outline")
    language: Optional[str] = Field(None, description="Language override: zh/en")

class MasterOutlineResponse(BaseModel):
    content: str = ""
    updated_at: Optional[str] = None


class VolumeOutlineResponse(BaseModel):
    volume_id: str
    title: str = ""
    content: str = ""
    updated_at: Optional[str] = None


class ChapterOutlineResponse(BaseModel):
    chapter_id: str
    volume_id: str
    title: str = ""
    content: str = ""
    updated_at: Optional[str] = None


class ProjectOutlineResponse(BaseModel):
    master: MasterOutlineResponse
    volumes: List[VolumeOutlineResponse] = Field(default_factory=list)
    chapters: List[ChapterOutlineResponse] = Field(default_factory=list)

@router.post("/generate")
async def generate_outline(project_id: str, request: OutlineGenerateRequest):
    """根据本章创作目标指令智能生成大纲"""
    chapter_id = normalize_chapter_id(request.chapter_id)
    instruction = (request.instruction or "").strip()
    if not instruction:
        raise HTTPException(status_code=400, detail="instruction is required")

    explicit_lang = normalize_language(request.language, default="")
    language = "zh"
    if explicit_lang in {"zh", "en"}:
        language = explicit_lang
    else:
        try:
            from pathlib import Path
            import yaml
            from app.config import settings
            project_yaml = Path(settings.data_dir) / project_id / "project.yaml"
            if project_yaml.exists():
                data = yaml.safe_load(project_yaml.read_text(encoding="utf-8")) or {}
                language = normalize_language(data.get("language"), default="zh")
        except Exception:
            pass

    gateway = get_gateway()
    archivist = ArchivistAgent(
        gateway=gateway,
        card_storage=get_card_storage(),
        canon_storage=get_canon_storage(),
        draft_storage=get_draft_storage(),
        language=language,
    )

    content = await archivist.generate_chapter_outline(
        project_id=project_id,
        chapter_id=chapter_id,
        instruction=instruction,
    )
    return {"content": content}


@router.get("", response_model=ProjectOutlineResponse)
async def get_project_outline(project_id: str):
    """Get independent outline payload for the whole project."""
    master_raw = await outline_storage.get_master_outline(project_id)
    master = MasterOutlineResponse(
        content=master_raw.get("content") or "",
        updated_at=master_raw.get("updated_at"),
    )

    volumes = await volume_storage.list_volumes(project_id)
    volume_items: List[VolumeOutlineResponse] = []
    for volume in volumes:
        item_raw = await outline_storage.get_volume_outline(project_id, volume.id)
        volume_items.append(
            VolumeOutlineResponse(
                volume_id=volume.id,
                title=volume.title or "",
                content=item_raw.get("content") or "",
                updated_at=item_raw.get("updated_at"),
            )
        )

    summaries = await draft_storage.list_chapter_summaries(project_id)
    title_map = {
        normalize_chapter_id(summary.chapter): summary.title or ""
        for summary in summaries
        if getattr(summary, "chapter", None)
    }
    volume_map = {
        normalize_chapter_id(summary.chapter): (summary.volume_id or ChapterIDValidator.extract_volume_id(summary.chapter) or "V1")
        for summary in summaries
        if getattr(summary, "chapter", None)
    }

    chapters = await draft_storage.list_chapters(project_id)
    chapter_items: List[ChapterOutlineResponse] = []
    for chapter in chapters:
        chapter_id = normalize_chapter_id(chapter)
        item_raw = await outline_storage.get_chapter_outline(project_id, chapter_id)
        chapter_items.append(
            ChapterOutlineResponse(
                chapter_id=chapter_id,
                volume_id=volume_map.get(chapter_id) or ChapterIDValidator.extract_volume_id(chapter_id) or "V1",
                title=title_map.get(chapter_id, ""),
                content=item_raw.get("content") or "",
                updated_at=item_raw.get("updated_at"),
            )
        )

    return ProjectOutlineResponse(
        master=master,
        volumes=volume_items,
        chapters=chapter_items,
    )


@router.put("/master", response_model=MasterOutlineResponse)
async def save_master_outline(project_id: str, body: OutlineUpdateRequest):
    """Save master outline."""
    saved = await outline_storage.save_master_outline(project_id, body.content or "")
    return MasterOutlineResponse(content=saved.get("content") or "", updated_at=saved.get("updated_at"))


@router.put("/volumes/{volume_id}", response_model=VolumeOutlineResponse)
async def save_volume_outline(project_id: str, volume_id: str, body: OutlineUpdateRequest):
    """Save volume outline."""
    volume = await volume_storage.get_volume(project_id, volume_id)
    if not volume:
        raise HTTPException(status_code=404, detail=f"Volume {volume_id} not found")

    saved = await outline_storage.save_volume_outline(project_id, volume.id, body.content or "")
    return VolumeOutlineResponse(
        volume_id=volume.id,
        title=volume.title or "",
        content=saved.get("content") or "",
        updated_at=saved.get("updated_at"),
    )


@router.put("/chapters/{chapter_id}", response_model=ChapterOutlineResponse)
async def save_chapter_outline(project_id: str, chapter_id: str, body: OutlineUpdateRequest):
    """Save chapter outline."""
    normalized = normalize_chapter_id(chapter_id)
    chapters = await draft_storage.list_chapters(project_id)
    chapter_set = {normalize_chapter_id(item) for item in chapters}
    if normalized not in chapter_set:
        raise HTTPException(status_code=404, detail=f"Chapter {normalized} not found")

    summary = await draft_storage.get_chapter_summary(project_id, normalized)
    volume_id = (
        (summary.volume_id if summary else None)
        or ChapterIDValidator.extract_volume_id(normalized)
        or "V1"
    )
    title = summary.title if summary and summary.title else ""

    saved = await outline_storage.save_chapter_outline(project_id, normalized, body.content or "")
    return ChapterOutlineResponse(
        chapter_id=normalized,
        volume_id=volume_id,
        title=title,
        content=saved.get("content") or "",
        updated_at=saved.get("updated_at"),
    )
