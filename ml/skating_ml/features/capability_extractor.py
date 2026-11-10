"""Capability feature extractor (placeholder).

职责：PoseResult / 人工标注 → metric（air_time / spin_speed / jump_height ...）
     → capability_evidence（带 confidence，source_type='ml_task'）。
"""

from typing import Protocol


class CapabilityExtractor(Protocol):
    # TODO: 从关键点时间序列估计特征并生成证据记录
    def extract(self, pose_result: object, skater_id: int, task_id: int) -> list[dict]:
        """返回待写入 capability_evidence 的记录列表。"""
