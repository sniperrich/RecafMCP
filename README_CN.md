# Recaf MCP 插件

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK 22+](https://img.shields.io/badge/JDK-22%2B-orange.svg)](https://openjdk.org/)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-green.svg)](https://modelcontextprotocol.io/)

让 AI 助手通过 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 操控 [Recaf 4.x](https://github.com/Col-E/Recaf)，直接在 AI 工作流中完成 Java 字节码的反编译、搜索、分析和重命名。

[English](README.md)

## 工作原理

插件采用双进程架构，将 AI 助手与 Recaf 的字节码分析引擎桥接起来：

```
┌─────────────────┐     STDIO / JSON-RPC     ┌─────────────────┐    HTTP :9847     ┌─────────────────┐
│   AI 助手        │ ◄──────────────────────► │   MCP Server    │ ◄────────────────► │  Recaf 插件      │
│ (Claude Code,   │                          │  (独立 JAR 进程)  │                    │ (Bridge Server) │
│  Cursor 等)     │                          └─────────────────┘                    └────────┬────────┘
└─────────────────┘                                                                          │
                                                                                    ┌────────┴────────┐
                                                                                    │   Recaf 4.x     │
                                                                                    │  (分析引擎)       │
                                                                                    └─────────────────┘
```

- **Recaf 插件（Bridge Server）**— 运行在 Recaf 进程内部，通过 CDI（Jakarta）注入 Recaf 服务，在 `localhost:9847` 暴露 HTTP 接口。
- **MCP Server** — 独立的 fat JAR，由 AI 客户端启动。通过 STDIO JSON-RPC 与 AI 通信，将工具调用通过 HTTP 转发给 Bridge Server。

之所以需要这种分离设计，是因为 Recaf 作为 JavaFX 桌面应用有自己的模块系统，而 MCP 协议要求 AI 客户端能通过 STDIO 启动和管理一个独立进程。

## MCP 工具列表

| 工具 | 说明 | 主要参数 |
|------|------|----------|
| `open_jar` | 打开 JAR、APK 或 class 文件进行分析 | `path` — 文件绝对路径 |
| `close_workspace` | 关闭当前工作区 | — |
| `list_classes` | 列出工作区中的所有类 | `filter`（可选）— 名称过滤，如 `com/example` 或 `Main` |
| `get_class_info` | 获取类详情：字段、方法、接口、注解 | `className` — 如 `com/example/Main` |
| `decompile_class` | 将类反编译为 Java 源码 | `className` — 如 `com/example/Main` |
| `search_code` | 搜索字符串、类/方法/字段引用或声明 | `query`、`type`（`string`/`class`/`method`/`field`/`declaration`）、`maxResults` |
| `get_call_graph` | 获取方法调用图（调用者和被调用者） | `className`、`methodName`（可选）、`depth`（默认 3） |
| `get_inheritance` | 获取继承层次（父类/子类） | `className`、`direction`（`both`/`parents`/`children`） |
| `rename_symbol` | 重命名类、字段或方法（自动更新所有引用） | `type`（`class`/`field`/`method`）、`oldName`、`newName`、`className`（字段/方法需要）、`descriptor`（可选） |
| `export_mappings` | 导出重命名映射到文件 | `format`（`TinyV1`/`SRG`/`Proguard`/...）、`outputPath` |

## MCP 资源

| URI | 说明 |
|-----|------|
| `recaf://workspace` | 当前工作区信息（JSON） |
| `recaf://classes` | 当前工作区的完整类列表（JSON） |

## 前置要求

- **JDK 22+** — Recaf 4.x 的硬性要求。确保 PATH 中的 `java` 指向 JDK 22 或更高版本。
- **Recaf 4.x** — 本插件基于快照版本 `d07958a5c7` 构建，构建系统会自动下载。

## 构建

```bash
git clone https://github.com/your-repo/recaf-mcp-plugin.git
cd recaf-mcp-plugin
./gradlew build
```

构建产出两个 JAR：

| 文件 | 用途 |
|------|------|
| `build/libs/recaf-mcp-plugin-1.0.0.jar` | Recaf 插件（加载到 Recaf 内部，运行 Bridge Server） |
| `build/mcp/recaf-mcp-server-1.0.0.jar` | MCP Server（独立 fat JAR，由 AI 客户端启动） |

## 安装与使用

### 第一步：启动 Recaf 并加载插件

**方式 A：从项目直接运行（推荐开发时使用）**

```bash
./gradlew runRecaf
```

这会自动构建插件并启动 Recaf，插件会被自动加载。

**方式 B：手动安装**

将 `build/libs/recaf-mcp-plugin-1.0.0.jar` 复制到 Recaf 的插件目录：

| 操作系统 | 插件目录 |
|---------|---------|
| macOS / Linux | `~/Recaf/plugins/` |
| Windows | `%APPDATA%/Recaf/plugins/` |

然后正常启动 Recaf。

**验证：** 在 Recaf 的 Logging 面板中查看以下输出：

```
========================================
  Recaf MCP Plugin enabled
  Bridge Server running on port 9847
========================================
```

### 第二步：配置 AI 客户端

#### Claude Code

在 `~/.claude.json` 中添加：

```json
{
  "mcpServers": {
    "recaf": {
      "command": "java",
      "args": ["-jar", "/你的绝对路径/build/mcp/recaf-mcp-server-1.0.0.jar"]
    }
  }
}
```

然后重启 Claude Code，`recaf` 相关工具会出现在工具列表中。

#### Cursor

在 MCP 配置中添加（Settings → MCP）：

```json
{
  "mcpServers": {
    "recaf": {
      "command": "java",
      "args": ["-jar", "/你的绝对路径/build/mcp/recaf-mcp-server-1.0.0.jar"]
    }
  }
}
```

#### 其他 MCP 兼容客户端

任何支持 MCP 协议的客户端都可以使用本插件。配置方式相同：通过 `java -jar` 以 STDIO 方式启动 MCP Server JAR。

### 第三步：开始使用

Recaf 和 AI 客户端都启动后，直接用自然语言交互：

```
打开 /path/to/target.jar，列出所有类
反编译 com/example/Main 这个类
搜索所有包含 "password" 的字符串
分析 com/example/Main 的调用图
把 com/example/a 重命名为 com/example/LoginManager
导出映射为 TinyV1 格式到 /tmp/mappings.tiny
```

## 使用场景示例

### 逆向分析混淆 JAR

```
1. "打开 /path/to/obfuscated.jar"
2. "列出所有类" — 了解包结构概览
3. "反编译 com/a/b/c" — 阅读反编译后的源码
4. "搜索包含 'http' 的字符串" — 找到网络请求端点
5. "获取 com/a/b/c 的方法 d 的调用图" — 理解控制流
6. "把 com/a/b/c 重命名为 com/app/NetworkManager" — 赋予有意义的名称
7. "导出映射为 TinyV1 格式到 ./mappings.tiny" — 保存工作成果
```

### 分析库依赖

```
1. "打开 /path/to/library.jar"
2. "搜索对 javax/crypto 的类引用" — 查找加密相关用法
3. "获取 com/lib/BaseHandler 的继承关系" — 查看类层次结构
4. "反编译 com/lib/impl/SecureHandler" — 检查具体实现
```

## 项目结构

```
src/main/java/dev/recaf/mcp/
├── RecafMcpPlugin.java                  # 插件入口 — CDI 注入 Recaf 服务
├── bridge/
│   ├── BridgeServer.java                # HTTP 服务器 :9847 — 路由请求到各处理器
│   └── handlers/
│       ├── WorkspaceHandler.java        # /workspace/* — 打开、关闭、列出类、类详情
│       ├── DecompileHandler.java        # /decompile — 反编译类到 Java 源码
│       ├── SearchHandler.java           # /search — 字符串、类、方法、字段、声明搜索
│       ├── AnalysisHandler.java         # /analysis/* — 调用图和继承层次分析
│       └── MappingHandler.java          # /mapping/* — 重命名符号和导出映射
├── server/
│   ├── RecafMcpServer.java              # MCP Server — STDIO JSON-RPC，工具/资源分发
│   └── BridgeClient.java               # HTTP 客户端 — 将 MCP 工具调用转发到 Bridge Server
└── util/
    └── JsonUtil.java                    # JSON 响应工具类
```

## Bridge HTTP API 参考

所有端点接受 POST 请求（JSON body），返回 JSON 响应。

| 端点 | 说明 |
|------|------|
| `GET /health` | 健康检查 — 返回 `{"status":"ok"}` |
| `POST /workspace/open` | 打开文件：`{"path": "/path/to/file.jar"}` |
| `POST /workspace/close` | 关闭工作区：`{}` |
| `GET /workspace/info` | 获取工作区信息 |
| `POST /workspace/classes` | 列出类：`{"filter": "可选过滤条件"}` |
| `POST /workspace/class-info` | 类详情：`{"className": "com/example/Main"}` |
| `POST /decompile` | 反编译：`{"className": "com/example/Main"}` |
| `POST /search` | 搜索：`{"query": "文本", "type": "string", "maxResults": 100}` |
| `POST /analysis/call-graph` | 调用图：`{"className": "...", "methodName": "...", "depth": 3}` |
| `POST /analysis/inheritance` | 继承关系：`{"className": "...", "direction": "both"}` |
| `POST /mapping/rename` | 重命名：`{"type": "class", "oldName": "...", "newName": "..."}` |
| `POST /mapping/export` | 导出映射：`{"format": "TinyV1", "outputPath": "/path/to/output"}` |

## 技术细节

| 项目 | 值 |
|------|------|
| MCP 协议版本 | `2024-11-05` |
| Bridge 端口 | `9847`（硬编码） |
| MCP Server 依赖 | 仅 Gson（无 MCP SDK — 轻量级自实现 JSON-RPC） |
| 反编译超时 | 30 秒 |
| 搜索默认最大结果数 | 100 |
| Java 工具链 | JDK 22+ |
| 构建系统 | Gradle + Shadow 插件（fat JAR 打包） |

## 常见问题

**MCP Server 报 "Connection refused" 错误**
- 确保 Recaf 已启动且 Bridge Server 在 9847 端口运行。
- 检查 Recaf 的 Logging 面板是否显示了启动横幅。

**AI 客户端中看不到 MCP 工具**
- 确认 `recaf-mcp-server-1.0.0.jar` 的路径正确且为绝对路径。
- 确保 `java` 指向 JDK 22+：运行 `java -version` 检查。
- 修改 MCP 配置后需要重启 AI 客户端。

**反编译返回空结果或报错**
- 确保已打开工作区（先使用 `open_jar`）。
- 检查类名格式：使用 `/` 分隔符（如 `com/example/Main`），而非 `.` 分隔符。

**构建失败**
- 确保已安装 JDK 22+。运行 `./gradlew -q javaToolchains` 查看已检测到的 JDK。
- 如果使用非默认 JDK，请配置 [Gradle 工具链](https://docs.gradle.org/current/userguide/toolchains.html)。

## License

MIT
