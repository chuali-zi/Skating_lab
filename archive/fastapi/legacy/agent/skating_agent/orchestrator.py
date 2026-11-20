"""LLM 总控入口，必需核心；本文件不实现运行时。

TODO：加载会话与预算，调用模型规划下一步，调度子角色和白名单工具；
先落候选证据再等待人工确认，冻结画像后规划、求解、评分、复检和解释。
TODO：有限修复、结构输出验证、取消竞态、租约恢复及原子额度预留。
所有步骤经 MemoryStore 留痕；缺失模型配置时报告不可用，不静默替换为纯求解器。
"""
from typing import Protocol

from .contracts import ReviewCommand, SessionContext


class Orchestrator(Protocol):
    async def run(self, session_id: int) -> SessionContext:
        """推进至人工确认、完成或失败等可持久化边界。"""
        ...

    async def resume(self, review: ReviewCommand) -> SessionContext:
        """提交审核事务，校验 revision 后恢复；修改内容须再次确认。"""
        ...

    async def cancel(self, session_id: int, expected_revision: int) -> None:
        """记录取消并阻止后续定稿，保留调用及已消费额度。"""
        ...
