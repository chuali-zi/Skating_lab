# FastAPI 源线归档

2026-11-20 起，教练产品的开发入口为本仓的 Android 客户端和 Java 服务端。FastAPI 成熟版的完整源代码保存在 Gitee，固定提交为 **75124952762a6620b6b8c400f9cefba908d4f870**。该提交的 Android 客户端、Python 服务、数据库迁移、事实验证器和历史测试都可以独立查看。本次迁移时保留了 Gitee 的分支及提交历史。[归档源码](https://gitee.com/chuali-zi/skating-coach/tree/75124952762a6620b6b8c400f9cefba908d4f870)

本目录的 legacy 子目录是先前 GitHub 主线携带的 44 份旧骨架文件，内容可用于考古。要复现当时完整运行的 FastAPI 版本，应取上面的 Gitee 固定提交。在独立目录执行下列命令可还原源码。

~~~powershell
git clone https://gitee.com/chuali-zi/skating-coach.git skating-fastapi-archive
git -C skating-fastapi-archive switch --detach 75124952762a6620b6b8c400f9cefba908d4f870
~~~

| 原有行为 | Java 主线入口 |
| --- | --- |
| 会话内列举任务、原始 MP4 上传、逐次同意外发帧 | VideoController、VideoJobs、Android「视频观察与复核」 |
| FFprobe 验证容器、轨道、时长、尺寸，FFmpeg 解码并提取实帧时间 | VideoSampler |
| 带序号和时间的 JPEG、真实 Responses 调用、枚举观察与待核事项 | FrameObserver、video-observations.schema.json |
| 排队、防重复、历史查看、私有帧、失效数据隔离、采纳／纠正／忽略 | VideoJobs、V4 数据库迁移、VideoEvidenceScreen |
| 将复核过的本段说明用于失误预案 | 带版本的 ReviewRef、PlanService、RecommendationService；Android 从选中观察进入失误情景 |
| 已保存的旧资料上传 | Java「复核视频」从资料原件创建新任务，需要再次取得帧外发同意 |
| 四份 ISU 规则原件、分值与解释检索 | Java expert 资源、ExpertCorpus、ExpertRetrieval；原件索引算法及采集记录保存在上述 Gitee 固定版本 |

归档涵盖源代码及验证用例。历史 Python 部署中的私人录像、账户、订阅凭据或数据库记录需遵循原部署的数据管理约定。Java 开发库保留本身已有的资料与用户归属。旧赛制优化实验仍可在 Gitee 查看，Java 当前的官方规则计分覆盖范围列在[专家调研与验证说明](../../docs/expert-knowledge-research.md)。
