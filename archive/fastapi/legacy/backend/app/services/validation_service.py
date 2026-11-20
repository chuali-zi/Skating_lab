"""独立复检工具接口；不把求解成功等同于规则覆盖完整。"""
from typing import Protocol


class ValidationService(Protocol):
    def validate(self, plan_id: int, frozen_context: dict) -> dict:
        """TODO：逐条检查、未覆盖条款、硬限制；写 program_constraint_check。"""
        ...
