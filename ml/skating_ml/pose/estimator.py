"""Pose estimation interface (placeholder).

MVP 策略：MediaPipe Pose（离线、轻量、CPU 可跑）；后期可替换为训练模型。
输出约定：逐帧关键点写入 db 表 pose_frame_feature（JSONB）。
"""

from typing import Any, Protocol


class PoseResult(Protocol):
    frames: list[dict[str, Any]]  # [{frame_ts, keypoints, quality}]
    summary: dict[str, Any]       # 写入 ml_task_result.summary


class PoseEstimator(Protocol):
    # TODO: MediaPipe 实现
    def estimate(self, video_path: str) -> PoseResult: ...
