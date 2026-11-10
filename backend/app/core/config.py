"""Application settings (placeholder).

TODO: 从环境变量/.env 加载；补充对象存储、Redis、LLM 等配置段。
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql://postgres:skating@localhost:5432/skating_coach"
    redis_url: str = "redis://localhost:6379/0"
    s3_endpoint: str = "http://localhost:9000"
    s3_bucket: str = "skating-assets"
    debug: bool = True

    class Config:
        env_file = ".env"


settings = Settings()
