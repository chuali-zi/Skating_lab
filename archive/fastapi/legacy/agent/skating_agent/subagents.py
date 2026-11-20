"""逻辑子智能体接口；角色可复用部署，无需独立服务。

multimodal_understanding：调用视觉语言能力，按需辅以姿态工具。
capability_profile：整理已确认事实、统计与限制，不编造样本成功率。
rule_retrieval：按规则版本检索库存原文及结构化条款，报告覆盖缺口。
choreography_planning：个性化构思与权衡，向工具提交候选及软偏好。
explanation：只根据已校验结果说明选择理由与引用，不制造分数。
"""
from typing import Protocol
from .contracts import AgentResult, AgentRole, SessionContext


class SubAgent(Protocol):
    role: AgentRole

    async def run(self, context: SessionContext) -> AgentResult:
        """TODO：结构验证、证据引用检查与模型输出入库。"""
        ...
