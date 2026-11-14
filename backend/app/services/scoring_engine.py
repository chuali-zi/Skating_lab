"""确定性评分工具占位，规则与分值以数据库冻结版本为准。

TODO：GOE 等级先转换为分值；场景概率求期望；总分中只扣一次跌倒扣分。
PCS 只含 composition、presentation、skating_skills，数值须有教练基线或校准来源。
缺失规则或能力输入时返回不可评分，不让 LLM 补分。
"""
from typing import Protocol


class ScoringEngine(Protocol):
    def score_program(self, plan_id: int, frozen_context: dict) -> dict:
        """返回逐元素结果、TES、三项 PCS、扣分、总分、来源及引擎版本。"""
        ...
