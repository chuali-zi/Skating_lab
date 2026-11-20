"""应用配置占位。

TODO: 从环境变量/.env 加载；补充对象存储、Redis、LLM 等配置段。
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql://postgres:skating@localhost:5432/skating_coach"
    redis_url: str = "redis://localhost:6379/0"
    s3_endpoint: str = "http://localhost:9000"
    s3_bucket: str = "skating-assets"
    text_model_alias: str | None = None
    vision_model_alias: str | None = None
    model_endpoint: str | None = None
    # TODO：凭据用专用秘密配置，调用前校验模型能力、预算与授权。
    debug: bool = True

    class Config:
        env_file = ".env"
        extra = "ignore"  # 骨架允许预留配置，尚未消费的项不影响健康检查。


settings = Settings()
