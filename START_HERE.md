# PackFailover — Codex handoff

Copy `AGENTS.md` to the root of the PackFailover Git repository and `docs/ARCHITECTURE.md` into `docs/`. Start Codex from the repository root.

First prompt (paste into Codex):

请先完整阅读根目录 AGENTS.md 和 docs/ARCHITECTURE.md。项目目标固定为 Paper 26.2、Java 25。先总结系统边界、六个核心模块、状态机和最高风险点，然后只执行阶段 0 PoC：确认 PacketEvents 资源包下发数据、Paper 状态事件、相同 UUID + SHA-1 + 不同 URL 的重试行为。验证阶段先不要开发完整 HTTP 服务和多插件适配器。请先检查官方 API 及依赖版本，写出实施计划后再开始编码。完成时运行 ./gradlew build，生成 docs/PROTOCOL-POC.md，并列出尚待真实 Java 客户端验证的项目。不要操作正式服 Docker/FRP 配置。
