"""智能体会话 API 占位，未来由服务层调用 orchestrator。

TODO：POST /sessions、GET /sessions/{session_id}、追加 messages、reviews 与 cancel。
关键抽取及定稿确认须检查授权、revision 和报告版本，使用原子事务。
"""
from fastapi import APIRouter

router = APIRouter(prefix="/agent", tags=["agent"])
