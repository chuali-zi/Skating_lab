"""LLM assistant (placeholder).

边界（PRD §9.3）：只做解释与抽取辅助，不参与编排求解；
任何抽取结果必须经人工确认才入库。
"""

from typing import Protocol


class LlmAssistant(Protocol):
    # TODO: 可插拔后端（本地小模型或云端 API）
    def explain_plan(self, plan_id: int) -> str:
        """把得分拆解与约束报告讲成选手能听懂的话。"""

    def extract_from_text(self, raw_text: str) -> dict:
        """自述文本 -> 结构化能力/伤病线索（供人工确认）。"""
