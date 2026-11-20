"""可选姿态与运动分析工具，辅助视觉语言理解。

TODO：按素材质量选择实现，输出写入 pose_frame_feature 与 ml_task_result。
单目视角缺少标定时不宣称精确绝对速度或高度；拒绝输出不可靠测量。
"""
from typing import Any, Protocol


class PoseResult(Protocol):
    frames: list[dict[str, Any]]
    summary: dict[str, Any]


class PoseEstimator(Protocol):
    def estimate(self, video_path: str) -> PoseResult: ...
