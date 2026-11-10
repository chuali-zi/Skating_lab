"""Agent 调用的约束工具端口，不是系统总控。

TODO：根据冻结规则编译已支持的约束；返回覆盖缺口、求解状态及候选修复。
CP-SAT 或同类工具只保证已编码约束；未知条款与未知求解状态不得视作通过。
工具评分使用规则数据，软偏好来自 Agent 的个性化构思；结果须独立复检。
"""
from typing import Protocol


class OptimizerService(Protocol):
    def solve(self, session_id: int, candidate_plan: dict, frozen_context: dict) -> dict:
        """返回求解状态、规则覆盖、可行序列及配置；不擅自发布方案。"""
        ...
