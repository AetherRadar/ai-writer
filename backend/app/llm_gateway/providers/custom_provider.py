"""
Custom LLM Provider / 自定义模型适配器 (OpenAI 兼容)
"""

from typing import List, Dict, Any, Optional
from openai import AsyncOpenAI
from app.llm_gateway.providers.base import BaseLLMProvider


class CustomProvider(BaseLLMProvider):
    """Custom OpenAI-compatible API provider / 自定义 OpenAI 兼容 API 提供商"""
    
    def __init__(
        self,
        api_key: str,
        base_url: str,
        model: str,
        max_tokens: int = 8000,
        temperature: float = 0.7
    ):
        super().__init__(api_key, model, max_tokens, temperature)
        # Ensure base_url is valid, if empty default to None (which defaults to standard OpenAI)
        # But for 'custom', user likely provides a specific URL.
        # If user leaves it blank but uses 'custom', it behaves like standard OpenAI? 
        # Better to pass it explicitely.
        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url if base_url else None)
    
    async def chat(
        self,
        messages: List[Dict[str, str]],
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None
    ) -> Dict[str, Any]:
        """
        Send chat request to Custom Provider
        发送聊天请求到自定义提供商
        """
        response = await self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            temperature=temperature or self.temperature,
            max_tokens=max_tokens or self.max_tokens
        )
        
        if isinstance(response, str):
            # 某些模型（如 grok-4.1-thinking）即使非流式调用也返回 SSE 格式文本
            # 尝试解析 SSE 流 / Some models return SSE even for non-stream requests
            if "data: " in response:
                content = _parse_sse_content(response)
                if content:
                    return {
                        "content": content,
                        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
                        "model": self.model,
                        "finish_reason": "stop",
                    }
            raise RuntimeError(f"Custom LLM API returned text instead of JSON: {response}")
        
        if not hasattr(response, "choices") or not getattr(response, "choices"):
            raise RuntimeError(f"Custom LLM API returned invalid JSON object without choices: {response}")

        return {
            "content": response.choices[0].message.content,
            "usage": {
                "prompt_tokens": response.usage.prompt_tokens,
                "completion_tokens": response.usage.completion_tokens,
                "total_tokens": response.usage.total_tokens
            },
            "model": response.model,
            "finish_reason": response.choices[0].finish_reason
        }
    
    def get_provider_name(self) -> str:
        """Get provider name / 获取提供商名称"""
        return "custom"


def _parse_sse_content(sse_text: str) -> str:
    """
    解析 SSE 流文本，拼接所有 delta.content 字段为完整字符串。
    Parse SSE stream text and concatenate all delta.content fields.
    """
    import json
    parts = []
    for line in sse_text.splitlines():
        line = line.strip()
        if not line.startswith("data: "):
            continue
        payload = line[len("data: "):].strip()
        if payload == "[DONE]":
            break
        try:
            obj = json.loads(payload)
            choices = obj.get("choices") or []
            for choice in choices:
                delta = choice.get("delta") or {}
                chunk = delta.get("content") or ""
                if chunk:
                    parts.append(chunk)
        except (json.JSONDecodeError, KeyError):
            continue
    return "".join(parts)
