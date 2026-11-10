"""Optimizer service (interface placeholder).

编排优化（见 docs/PRD.md §9）：
    - 决策变量 x[p,k]：槽位 p 是否选候选 k（元素×级别×联合跳结构模板）
    - 硬约束：well_balanced_rule 数据行编译为计数/必备/禁止约束 + 伤病禁忌过滤
    - 目标：max E[TES] + PCS_est - λ * Risk
    - 求解器：OR-Tools CP-SAT；Top-K 通过 no-good 约束重复求解
    - 输出：program_plan / program_element_item / program_score_breakdown /
            program_constraint_check

TODO: 候选集构建（能力下限过滤）、约束编译、目标装配、求解调用与结果落库、
      beam search 降级实现。
"""

from typing import Protocol


class OptimizerService(Protocol):
    def generate(self, skater_id: int, competition_category_id: int | None,
                 program_type_id: int, risk_level: str, top_k: int = 3) -> list[int]:
        """生成 Top-K 方案，返回 program_plan id 列表。"""
