"""Program plan endpoints (placeholder).

TODO: 方案与元素序列查询、得分拆解（program_score_breakdown）、
      约束检查报告（program_constraint_check）、人工微调 + 实时复检、
      版本链（parent_plan_id）与多方案对比、导出。
"""

from fastapi import APIRouter

router = APIRouter(prefix="/programs", tags=["programs"])
