"""FastAPI application entry (placeholder).

TODO: app factory、CORS、异常处理、鉴权中间件、路由挂载完善。
"""

from fastapi import FastAPI

from app.api.v1.router import api_router

app = FastAPI(
    title="Skating Coach API",
    description="花样滑冰 AI 教练 / 个性化动作编排系统（骨架占位）",
    version="0.1.0",
)

app.include_router(api_router, prefix="/api")


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
