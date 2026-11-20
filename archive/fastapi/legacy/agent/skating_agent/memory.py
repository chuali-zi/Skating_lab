"""PostgreSQL 记忆与轨迹写入入口；均为接口，尚无存储实现。

TODO：复合外键之外的授权、规则版本、同选手血缘与确认门禁须在事务检查。
输出及轨迹按追加处理，敏感载荷脱敏；网络调用不得占用数据库事务。
"""
from typing import Any, Protocol
from .contracts import ReviewCommand, SessionContext, ToolRequest, ToolResult


class MemoryStore(Protocol):
    async def load_session(self, session_id: int) -> SessionContext: ...

    async def append_message(self, session_id: int, content: dict[str, Any]) -> int: ...

    async def begin_tool_call(self, request: ToolRequest) -> int: ...

    async def finish_tool_call(self, result: ToolResult) -> None: ...

    async def append_llm_output(self, session_id: int, output: dict[str, Any]) -> int:
        """写入 agent_llm_output，包含部署别名、结构化输出、验证状态及用量。"""
        ...

    async def append_trace(
        self, session_id: int, event_type: str, decision_summary: str,
        tool_call_id: int | None = None, llm_output_id: int | None = None,
    ) -> int: ...

    async def append_evidence(self, session_id: int, evidence: dict[str, Any]) -> int:
        """写入 agent_evidence 候选，初始状态 pending，保留来源和生产调用。"""
        ...

    async def link_plan_evidence(
        self, session_id: int, plan_id: int, evidence_id: int,
        usage_kind: str, influence_summary: str,
    ) -> None: ...

    async def apply_review(self, command: ReviewCommand) -> SessionContext:
        """原子写复核、状态及审计；检查权限与 revision，修改不等于确认。"""
        ...

    async def freeze_snapshot(
        self, session_id: int, expected_revision: int,
        snapshot_id: int, evidence_ids: tuple[int, ...], stat_ids: tuple[int, ...],
    ) -> None:
        """校验同选手及确认状态，写画像血缘并冻结会话输入。"""
        ...
