"""运动特征抽取占位；结果先由 Agent 作为候选证据保存。

TODO：保留 task_id、资产与片段、工具版本、质量及不确定性；
关键观察人工确认后才能进入能力快照，不自动覆盖长期统计。
"""
from typing import Protocol


class CapabilityExtractor(Protocol):
    def extract(self, pose_result: object, skater_id: int, task_id: int) -> list[dict]:
        """返回测量及来源，不能将模型置信度视为动作成功率。"""
        ...
