"""跨模块结构契约占位；标识符与数据库保持一致。"""

from dataclasses import dataclass, field
from typing import Any, Literal

SessionStatus = Literal[
    "created", "understanding", "awaiting_confirmation", "planning", "validating",
    "awaiting_finalization", "completed", "failed", "cancelled",
]
AgentRole = Literal[
    "orchestrator", "multimodal_understanding", "capability_profile",
    "rule_retrieval", "choreography_planning", "explanation",
]


@dataclass(frozen=True)
class SessionContext:
    session_id: int
    revision: int
    skater_id: int
    ruleset_version_id: int
    program_type_id: int
    capability_snapshot_id: int | None
    status: SessionStatus
    input_snapshot: dict[str, Any] = field(default_factory=dict)
    config_snapshot: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ToolRequest:
    session_id: int
    tool_name: str
    tool_version: str
    idempotency_key: str
    attempt_no: int
    input_payload: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    tool_call_id: int
    status: Literal["succeeded", "failed", "cancelled"]
    output_payload: dict[str, Any] | None
    error_code: str | None = None


@dataclass(frozen=True)
class AgentResult:
    role: AgentRole
    structured_output: dict[str, Any]
    evidence_ids: tuple[int, ...]
    needs_confirmation: bool


@dataclass(frozen=True)
class ReviewCommand:
    session_id: int
    expected_revision: int
    reviewer_user_id: int
    decision: Literal["confirmed", "rejected", "changes_requested"]
    reason: str
    evidence_id: int | None = None
    plan_id: int | None = None
    proposed_changes: dict[str, Any] = field(default_factory=dict)
    # TODO：验证审核对象恰好一种；校验用户授权与目标版本，不能仅依赖类型提示。
