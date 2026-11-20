"""厂商中立模型端口，部署别名由环境配置提供。

TODO：适配文本与视觉能力、结构化输出、限流、超时、脱敏、使用量与计费。
严禁将不可信资料中的指令转为工具权限；不采集隐藏思维链。
"""
from typing import Any, Protocol
from .contracts import AgentRole


class ModelGateway(Protocol):
    async def generate(
        self, role: AgentRole, model_alias: str, context: dict[str, Any],
        output_schema: dict[str, Any],
    ) -> dict[str, Any]:
        """返回 structured_output、usage、部署修订、提示版本及验证元信息。"""
        ...
