# 首批专业知识来源与接入记录

核对日期：2026-11-14。本文记录实际找到的来源、用途与访问边界；它不是已完成规则审核或教练效果评测的声明。来源目录已写入 Java 数据库迁移，可通过 `GET /api/v1/knowledge/catalog` 查看。

## 已登记的八个来源

| 来源 | 用途 | 本轮处理 |
| --- | --- | --- |
| [ISU Sports Rules 2026](https://www.isu.org/en/isu-sports-rules/figure-skating-rule) | 节目、计分与技术规则原文；需结合比赛和组别 | 记录 2026-06-12 生效版本的官方 PDF 地址 |
| [ISU Communication 2786](https://isu-d8g8b4b7ece7aphs.a03.azurefd.net/isudamcontainer/CMS/Corporate-Site/Governance/Transparency/ISU-Communications/2786-SinglesPairs-SOV-2026-27-content-26-04-28-final-1777993235-6713.pdf) | 2026/27 单人滑、双人滑分值表 | 保存来源，避免沿用旧种子分值；尚未宣布其全部表格已结构化核验 |
| [Single Skating Technical Panel Handbook 2026/27](https://www.isu.org/dc/isu-sports-rules/figure-skating-rule/handbooks-single-and-pair-skating) | 动作识别、技术判定与术语解释 | 登记官方手册 PDF，与规则原文保持不同资料角色 |
| 同一官方入口的 Referees & Judges Handbook 2026/27 | 裁判与裁判长的评分说明 | 登记对应官方 PDF；不能据此直接预测个人 GOE |
| [CFSA 2026/27 全国锦标赛竞赛规程](https://www.cfsa.com.cn/file/path?path=ueditor%2Fjsp%2Fupload%2Ffile%2F20261019%2F1792407518700024605.pdf) | 国内赛事的实际规程样本 | 单独标记为具体赛事，不能自动套到其他比赛 |
| [ISU eRink 教练教育入口](https://elearning.isu.org/course/index.php?categoryid=4) | 寻找教学、技术更新和教练教育课程 | 只登记公开入口；未登录状态的课程内容未获取，不绕过访问控制 |
| [Skate Canada Long-Term Development](https://skatecanada.ca/community/athletes/long-term-development/) | 个体成长、阶段目标及训练资源入口 | 核对公开介绍；详细技术战术视频入口可能需要会员 |
| [U.S. Figure Skating Coach Education](https://usfigureskating.org/sports/2025/8/18/coach-education.aspx) | 教练培训和继续教育来源 | 登记教育入口；不把课程目录冒充已经获取的教学内容 |

规则和裁判手册能提供精确术语与边界，个性化训练建议还需要有适用情境的教练案例、选手真实训练反馈和可获授权的教学资料。公共资料的内容数量不能代替这部分证据。

## 补充的原始研究线索

1. [Yamaguchi 与 Sakurai，2025：六种跳跃起跳角动量比较](https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2025.1597598/full)。原文研究了七位具备较高技能水平的日本女性选手完成的双周跳，可用于理解不同跳种的起跳机制。文章为 CC BY 开放获取，需要保留作者和出版来源。该样本不能直接转换成所有选手的通用训练阈值或个人成功概率。
2. [2025：青少年不同周数跳跃的下肢生物力学差异](https://www.frontiersin.org/journals/bioengineering-and-biotechnology/articles/10.3389/fbioe.2025.1606817/full)。已找到原始研究入口，涉及 Waltz 与 Single Axel 等任务；作为后续按人群、动作、测量方法整理证据的候选。本轮没有将实验结果硬编码为推荐规则。

对研究资料，应同时记录人群、动作、实验条件、研究结果与适用限制。描述相关性、群体差异或实验趋势时，不能默认为用户自己的能力事实。特别是疲劳，不从一篇研究提取一个统一秒数并强加给所有选手。

## 已实现的入库路径

1. 数据库迁移保存公开来源目录和我们编写的简短用途说明。目录条目均设置 `readyForScoring=false`，不能直接作为完整比赛规则调用计算引擎。
2. `scripts/import-reference.ps1` 从目录读取已核对的官方 PDF 地址，下载后经已授权 API 上传；输出文件哈希和资料 ID，不输出会话令牌。
3. 私有存储保留原始字节；Java/Tika 后台解析文本与页边界，数据库存储定位块和关键词索引。
4. 实际 PDF 的表格、脚注关联和视觉信息仍需进一步核对，故标记 `partial / LAYOUT_REVIEW_REQUIRED`，不是“已经完整理解”。无文本资料保留为 `awaiting_visual`。
5. 配置云端 Embedding 后可生成向量；未配置时使用关键词检索，并在接口中明确 `mode=lexical`。没有生成模拟向量。
6. 本地检索验证使用真实导入资料；原件、提取全文和本地数据库卷不提交到 Git。仓库只保存程序、来源目录、迁移和我们自己的说明。

## 本机导入验证

2026-11-14 已通过实际 API 导入三份官方文件，保存在当前开发选手的私有资料中，关联测试范围 `isu-2026-27-singles`：

| 文件 | 定位文本块 | SHA-256 |
| --- | ---: | --- |
| ISU Sports Rules 2026 | 202 | `ed8fdf773f352d01dbc02afd0f894d6144d9a502355d9e48ac3ad19865e23172` |
| ISU Communication 2786 | 33 | `d4caab8afdc3add558d2e64d0e05b6cc62833150672879ab39d5b4e07f8d7c24` |
| Single Skating Technical Panel Handbook 2026/27 | 67 | `ba0036cb34f17daabc7b83671e883e6c6e3484a3bb5a6dd53486920c461678a4` |

查询 `jump combination` 已返回带原文页码的相关结果。该结果只证明下载、存储、解析、权限范围和关键词检索链路可工作；没有宣称规则抽取、自动裁判或云端教练已经验证完成。

当前 `isu-2026-27-singles` 是导入资料的筛选键，不是已经确认了男女、成年/青年、短节目/自由滑全部条件的可执行比赛配置。正式规则包仍需要逐项核对适用性、例外和分值计算逻辑。
