"""ISU rules library endpoints (placeholder).

TODO: 规则集版本（ruleset_version）切换、元素字典/基础分（element_level_value）、
      well-balanced 条款与参数（ruleset_parameter）管理。规则即数据，全部读库。
"""

from fastapi import APIRouter

router = APIRouter(prefix="/rules", tags=["rules"])
