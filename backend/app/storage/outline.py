# -*- coding: utf-8 -*-
"""
独立大纲存储 / Independent outline storage.

Stores planning outlines that are independent from chapter body text and AI summaries:
- master outline (总纲)
- volume outlines (分卷纲)
- chapter outlines (章节细纲)
"""

from datetime import datetime
from pathlib import Path
from typing import Dict, Optional

from app.storage.base import BaseStorage
from app.utils.chapter_id import normalize_chapter_id
from app.utils.path_safety import sanitize_id


class OutlineStorage(BaseStorage):
    """File-based storage for independent project outlines."""

    async def get_master_outline(self, project_id: str) -> Dict[str, Optional[str]]:
        """Read master outline (总纲)."""
        return await self._read_outline_file(self._master_file(project_id))

    async def save_master_outline(self, project_id: str, content: str) -> Dict[str, Optional[str]]:
        """Save master outline (总纲)."""
        return await self._write_outline_file(self._master_file(project_id), content)

    async def get_volume_outline(self, project_id: str, volume_id: str) -> Dict[str, Optional[str]]:
        """Read a volume outline (分卷纲)."""
        normalized = self._normalize_volume_id(volume_id)
        return await self._read_outline_file(self._volume_file(project_id, normalized))

    async def save_volume_outline(self, project_id: str, volume_id: str, content: str) -> Dict[str, Optional[str]]:
        """Save a volume outline (分卷纲)."""
        normalized = self._normalize_volume_id(volume_id)
        return await self._write_outline_file(self._volume_file(project_id, normalized), content)

    async def get_chapter_outline(self, project_id: str, chapter_id: str) -> Dict[str, Optional[str]]:
        """Read a chapter outline (章节细纲)."""
        normalized = self._normalize_chapter_id(chapter_id)
        return await self._read_outline_file(self._chapter_file(project_id, normalized))

    async def save_chapter_outline(self, project_id: str, chapter_id: str, content: str) -> Dict[str, Optional[str]]:
        """Save a chapter outline (章节细纲)."""
        normalized = self._normalize_chapter_id(chapter_id)
        return await self._write_outline_file(self._chapter_file(project_id, normalized), content)

    async def _read_outline_file(self, file_path: Path) -> Dict[str, Optional[str]]:
        if not file_path.exists():
            return {"content": "", "updated_at": None}

        data = await self.read_yaml(file_path)
        if isinstance(data, dict):
            content = data.get("content", "")
            updated_at = data.get("updated_at")
            return {
                "content": str(content or ""),
                "updated_at": str(updated_at) if updated_at else None,
            }

        # Backward compatibility: plain YAML scalar
        return {"content": str(data or ""), "updated_at": None}

    async def _write_outline_file(self, file_path: Path, content: str) -> Dict[str, Optional[str]]:
        text = str(content or "")

        # Treat empty content as clear/delete
        if not text.strip():
            if file_path.exists():
                file_path.unlink(missing_ok=True)
            return {"content": "", "updated_at": None}

        payload = {
            "content": text,
            "updated_at": datetime.now().isoformat(),
        }
        await self.write_yaml(file_path, payload)
        return payload

    def _outline_dir(self, project_id: str) -> Path:
        return self.get_project_path(project_id) / "outline"

    def _master_file(self, project_id: str) -> Path:
        return self._outline_dir(project_id) / "master.yaml"

    def _volume_file(self, project_id: str, volume_id: str) -> Path:
        safe_id = sanitize_id(volume_id)
        return self._outline_dir(project_id) / "volumes" / f"{safe_id}.yaml"

    def _chapter_file(self, project_id: str, chapter_id: str) -> Path:
        safe_id = sanitize_id(chapter_id)
        return self._outline_dir(project_id) / "chapters" / f"{safe_id}.yaml"

    def _normalize_volume_id(self, volume_id: str) -> str:
        raw = str(volume_id or "").strip().upper()
        if not raw:
            raise ValueError("Volume ID is required")
        return raw

    def _normalize_chapter_id(self, chapter_id: str) -> str:
        raw = str(chapter_id or "").strip()
        normalized = normalize_chapter_id(raw)
        if not normalized:
            raise ValueError("Chapter ID is required")
        return normalized
