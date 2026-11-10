"""Program optimization endpoints (placeholder).

TODO: POST /jobs 提交生成任务（选手 + 目标赛事组别 + 节目类型 + 风险档位），
      GET /jobs/{id} 轮询状态；求解在 Celery worker 中执行并落库。
"""

from fastapi import APIRouter

router = APIRouter(prefix="/optimizer", tags=["optimizer"])
