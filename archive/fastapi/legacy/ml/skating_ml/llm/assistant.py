"""自述理解适配接口；整体规划与解释由 agent 包负责。

TODO：抽取事实、偏好与冲突，返回来源引用及待确认项；禁止直接形成医疗结论。
此模块为必需智能体链路的输入适配，不是后期可选的解释插件。
"""
from typing import Protocol


class TextUnderstanding(Protocol):
    async def extract(self, session_id: int, message_id: int, raw_text: str) -> dict: ...
