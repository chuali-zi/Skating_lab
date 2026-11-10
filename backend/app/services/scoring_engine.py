"""Scoring engine (interface placeholder).

期望得分模型（见 docs/PRD.md §8.1）：
    E[score] = p * (BV + g_success) + (1 - p) * (BV_fail + g_fail) - p_fall * fall_deduction
所有 BV / GOE 比例 / PCS 因子 / 扣分参数一律从规则库读取（ruleset_version 绑定），不写死。

TODO: 实现元素级期望分、TES 汇总、PCS 估算（MVP: 艺术基线 + 覆盖多样性奖励）、
      +REP 重复打折、结果写入 program_score_breakdown。
"""

from typing import Protocol


class ScoringEngine(Protocol):
    def score_element(self, element_id: int, level_code: str, success_prob: float) -> float:
        """单元素期望得分。"""

    def score_program(self, plan_id: int) -> dict:
        """整方案拆解：TES / PCS / 扣分 / 总分。"""
