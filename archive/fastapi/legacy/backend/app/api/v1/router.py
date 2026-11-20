"""汇总第一版业务路由占位。

TODO: 随功能实现逐步挂载子路由。
"""

from fastapi import APIRouter

from app.api.v1 import agent, assets, optimizer, programs, rules, skaters

api_router = APIRouter()
api_router.include_router(skaters.router)
api_router.include_router(assets.router)
api_router.include_router(rules.router)
api_router.include_router(optimizer.router)
api_router.include_router(programs.router)

api_router.include_router(agent.router)
