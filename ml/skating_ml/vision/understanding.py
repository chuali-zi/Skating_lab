"""首版必需的视觉语言接口，占位且不下载权重。

TODO：按授权读取媒体片段，保存抽帧策略及真实输入范围；
输出观察、时间段、置信度、冲突与待确认项，由 Agent 写证据并等待人工复核。
"""
from typing import Protocol


class VisionUnderstanding(Protocol):
    async def observe(self, session_id: int, session_asset_id: int, media_manifest: dict) -> dict:
        """返回观察和调用元信息；照片不能推断完整动作成功率。"""
        ...
