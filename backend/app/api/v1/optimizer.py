"""旧编排入口兼容占位。

TODO：未来生成请求转交智能体会话服务，不直接执行求解器；
新客户端使用 /api/v1/agent/sessions。当前没有业务路由实现。
"""
from fastapi import APIRouter
router = APIRouter(prefix="/optimizer", tags=["optimizer"])
