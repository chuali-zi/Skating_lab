"""白名单工具接口占位；具体实现由 backend 与 ml 注入。

TODO：在运行时注册输入输出结构、授权范围、版本、超时与预算。
multimodal_observe：视觉语言语义观察；pose_analyze：可选运动测量。
capability_build：冻结画像、证据及统计关联；rule_search：库存规则检索。
constraint_solve：合法性工具；program_validate：独立复检与覆盖检查。
program_score：规则驱动评分；规则缺失时不能把未知分数填零。
"""
from typing import Protocol
from .contracts import ToolRequest, ToolResult


class Tool(Protocol):
    name: str
    version: str

    async def invoke(self, request: ToolRequest) -> ToolResult:
        """TODO：先登记调用，执行后保存状态、结果或错误。"""
        ...
