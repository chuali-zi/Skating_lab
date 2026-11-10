"""Multimodal asset endpoints (placeholder).

TODO: 上传（对象存储 + asset 元数据）、标注（asset_annotation）、
      ML 任务（ml_task）提交与状态查询。
"""

from fastapi import APIRouter

router = APIRouter(prefix="/assets", tags=["assets"])
