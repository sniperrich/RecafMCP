# Recaf MCP 插件

[![Version](https://img.shields.io/badge/version-1.1.0-brightgreen.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK 22+](https://img.shields.io/badge/JDK-22%2B-orange.svg)](https://openjdk.org/)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-green.svg)](https://modelcontextprotocol.io/)
[![Tools](https://img.shields.io/badge/MCP_Tools-16-purple.svg)]()

让 AI 助手通过 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 操控 [Recaf 4.x](https://github.com/Col-E/Recaf)，直接在 AI 工作流中完成 Java 字节码的反编译、搜索、分析、字节码编辑、类对比和导出。

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

## MCP 工具列表（16 个）

### 工作区管理

| 工具 | 说明 | 主要参数 |
|------|------|----------|
| `open_jar` | 打开 JAR、APK 或 class 文件进行分析 | `path` — 文件绝对路径，返回 `workspaceId` |
| `close_workspace` | 关闭当前或指定工作区 | `workspaceId`（可选）— 按 ID 关闭 |
| `switch_workspace` | 切换到之前打开的工作区 | `workspaceId` — `open_jar` 返回的 ID |
| `list_workspaces` | 列出所有已注册的工作区 | — |
| `list_classes` | 列出类，支持 offset/limit 分页 | `filter`、`offset`、`limit` |
| `get_class_info` | 获取类详情：字段、方法、接口 | `className` |

### 分析

| 工具 | 说明 | 主要参数 |
|------|------|----------|
| `decompile_class` | 将类反编译为 Java 源码 | `className` |
| `search_code` | 搜索字符串、引用或声明 | `query`、`type`、`maxResults` |
| `get_call_graph` | 获取方法调用图（调用者和被调用者） | `className`、`methodName`、`depth` |
| `get_inheritance` | 获取继承层次（父类/子类） | `className`、`direction` |
| `diff_classes` | 对比两个类或类与源码 | `className1`、`className2` 或 `source` |

### 修改

| 工具 | 说明 | 主要参数 |
|------|------|----------|
| `rename_symbol` | 重命名类/字段/方法（自动更新所有引用） | `type`、`oldName`、`newName`、`className` |
| `edit_bytecode` | 添加/删除/修改方法和字段 | `className`、`operation` + 操作相关参数 |

### 导出

| 工具 | 说明 | 主要参数 |
|------|------|----------|
| `export_mappings` | 导出重命名映射到文件 | `format`、`outputPath` |
| `export_jar` | 导出工作区为 JAR 文件 | `outputPath` |
| `export_source` | 导出反编译源码到目录 | `outputDir`、`className`（可选） |

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
| `build/libs/recaf-mcp-plugin-1.1.0.jar` | Recaf 插件（加载到 Recaf 内部，运行 Bridge Server） |
| `build/mcp/recaf-mcp-server-1.1.0.jar` | MCP Server（独立 fat JAR，由 AI 客户端启动） |

## 安装与使用

### 第一步：启动 Recaf 并加载插件

**方式 A：从项目直接运行（推荐开发时使用）**

```bash
./gradlew runRecaf
```

这会自动构建插件并启动 Recaf，插件会被自动加载。

**方式 B：手动安装**

将 `build/libs/recaf-mcp-plugin-1.1.0.jar` 复制到 Recaf 的插件目录：

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
      "args": ["-jar", "/你的绝对路径/build/mcp/recaf-mcp-server-1.1.0.jar"]
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
      "args": ["-jar", "/你的绝对路径/build/mcp/recaf-mcp-server-1.1.0.jar"]
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
对比 com/example/A 和 com/example/B 两个类
删除 com/example/Foo 中的 unused 方法
导出修改后的 JAR 到 /tmp/output.jar
导出所有反编译源码到 /tmp/src
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
8. "导出修改后的 JAR 到 ./cleaned.jar" — 保存结果
```

### 多 JAR 对比

```
1. "打开 /path/to/v1.jar" — 打开第一个 JAR，返回 workspaceId
2. "打开 /path/to/v2.jar" — 打开第二个 JAR，返回 workspaceId
3. "列出所有工作区" — 查看两个工作区
4. "切换到工作区 v1-1" — 切换到第一个 JAR
5. "反编译 com/example/Main" — 获取 v1 源码
6. "切换到工作区 v2-2" — 切换到第二个 JAR
7. "对比 com/example/Main 和 v1 的源码" — 比较版本差异
```

### 字节码编辑

```
1. "打开 /path/to/target.jar"
2. "删除 com/app/Main 中的 checkLicense 方法"
3. "给 com/app/Config 添加一个 public boolean 类型的 debug 字段"
4. "导出修改后的 JAR 到 /tmp/patched.jar"
```

## 项目结构

```
src/main/java/dev/recaf/mcp/
├── RecafMcpPlugin.java                  # 插件入口 — CDI 注入 Recaf 服务
├── bridge/
│   ├── BridgeServer.java                # HTTP 服务器 :9847 — 路由请求到各处理器
│   ├── WorkspaceRegistry.java           # 多工作区注册表 — ID → Workspace 映射
│   └── handlers/
│       ├── WorkspaceHandler.java        # /workspace/* — 打开、关闭、切换、列表、类详情
│       ├── DecompileHandler.java        # /decompile — 反编译类到 Java 源码
│       ├── SearchHandler.java           # /search — 字符串、类、方法、字段、声明搜索
│       ├── AnalysisHandler.java         # /analysis/* — 调用图和继承层次分析
│       ├── MappingHandler.java          # /mapping/* — 重命名符号和导出映射
│       ├── BytecodeHandler.java         # /bytecode/* — 编辑/添加/删除方法和字段
│       ├── DiffHandler.java             # /diff — 对比两个类（unified diff）
│       └── ExportHandler.java           # /export/* — 导出 JAR 和反编译源码
├── server/
│   ├── RecafMcpServer.java              # MCP Server — STDIO JSON-RPC，16 个工具分发
│   └── BridgeClient.java               # HTTP 客户端 — 将 MCP 工具调用转发到 Bridge Server
└── util/
    ├── JsonUtil.java                    # JSON 响应工具类
    ├── ErrorMapper.java                 # 结构化错误码、消息和建议
    └── DiffUtil.java                    # 基于 LCS 的 unified diff 算法
```

## Bridge HTTP API 参考

所有端点接受 POST 请求（JSON body），返回 JSON 响应。

| 端点 | 说明 |
|------|------|
| `GET /health` | 健康检查 — 返回 `{"status":"ok"}` |
| `POST /workspace/open` | 打开文件：`{"path": "/path/to/file.jar"}` → 返回 `workspaceId` |
| `POST /workspace/close` | 关闭工作区：`{"workspaceId": "可选"}` |
| `GET /workspace/info` | 获取工作区信息 |
| `POST /workspace/classes` | 列出类：`{"filter": "可选", "offset": 0, "limit": 500}` |
| `POST /workspace/class-info` | 类详情：`{"className": "com/example/Main"}` |
| `POST /workspace/switch` | 切换工作区：`{"workspaceId": "xxx"}` |
| `GET /workspace/list-workspaces` | 列出所有已注册工作区 |
| `POST /decompile` | 反编译：`{"className": "com/example/Main"}` |
| `POST /search` | 搜索：`{"query": "文本", "type": "string", "maxResults": 100}` |
| `POST /analysis/call-graph` | 调用图：`{"className": "...", "methodName": "...", "depth": 3}` |
| `POST /analysis/inheritance` | 继承关系：`{"className": "...", "direction": "both"}` |
| `POST /mapping/rename` | 重命名：`{"type": "class", "oldName": "...", "newName": "..."}` |
| `POST /mapping/export` | 导出映射：`{"format": "TinyV1", "outputPath": "/path"}` |
| `POST /bytecode/edit-method` | 编辑方法：`{"className": "...", "methodName": "...", "methodDesc": "...", "accessFlags": 1}` |
| `POST /bytecode/edit-field` | 编辑字段：`{"className": "...", "fieldName": "...", "accessFlags": 2}` |
| `POST /bytecode/remove-member` | 删除成员：`{"className": "...", "memberName": "...", "memberType": "method"}` |
| `POST /bytecode/add-field` | 添加字段：`{"className": "...", "fieldName": "...", "descriptor": "I"}` |
| `POST /bytecode/add-method` | 添加方法：`{"className": "...", "methodName": "...", "methodDesc": "()V"}` |
| `POST /diff` | 类对比：`{"className1": "A", "className2": "B"}` 或 `{"className1": "A", "source": "..."}` |
| `POST /export/jar` | 导出 JAR：`{"outputPath": "/path/to/output.jar"}` |
| `POST /export/source` | 导出源码：`{"outputDir": "/path/to/src", "className": "可选"}` |

## 技术细节

| 项目 | 值 |
|------|------|
| MCP 协议版本 | `2024-11-05` |
| Bridge 端口 | `9847`（硬编码） |
| MCP Server 依赖 | 仅 Gson（无 MCP SDK — 轻量级自实现 JSON-RPC） |
| 反编译超时 | 30 秒 |
| 搜索默认最大结果数 | 100 |
| 类列表默认限制 | 500（支持 offset 分页） |
| Java 工具链 | JDK 22+ |
| 构建系统 | Gradle + Shadow 插件（fat JAR 打包） |
| MCP 工具总数 | 16 |

## 常见问题

**MCP Server 报 "Connection refused" 错误**
- 确保 Recaf 已启动且 Bridge Server 在 9847 端口运行。
- 检查 Recaf 的 Logging 面板是否显示了启动横幅。

**AI 客户端中看不到 MCP 工具**
- 确认 `recaf-mcp-server-1.1.0.jar` 的路径正确且为绝对路径。
- 确保 `java` 指向 JDK 22+：运行 `java -version` 检查。
- 修改 MCP 配置后需要重启 AI 客户端。

**反编译返回空结果或报错**
- 确保已打开工作区（先使用 `open_jar`）。
- 检查类名格式：使用 `/` 分隔符（如 `com/example/Main`），而非 `.` 分隔符。

**结构化错误响应**
- 所有错误现在包含 `code`、`message` 和 `suggestion` 字段。
- 常见错误码：`NO_WORKSPACE`、`CLASS_NOT_FOUND`、`MEMBER_NOT_FOUND`、`INVALID_PARAMS`、`DECOMPILE_TIMEOUT`。

**构建失败**
- 确保已安装 JDK 22+。运行 `./gradlew -q javaToolchains` 查看已检测到的 JDK。
- 如果使用非默认 JDK，请配置 [Gradle 工具链](https://docs.gradle.org/current/userguide/toolchains.html)。

## 更新日志

### v1.1.0

- **多工作区支持** — 同时打开多个 JAR，通过 `switch_workspace` 和 `list_workspaces` 切换
- **字节码编辑** — `edit_bytecode` 工具支持 5 种操作：edit_method、edit_field、remove_member、add_field、add_method（基于 ASM）
- **类对比** — `diff_classes` 工具生成两个反编译类或类与源码之间的 unified diff
- **导出功能** — `export_jar` 导出工作区（含修改）为 JAR；`export_source` 导出反编译源码到目录
- **分页支持** — `list_classes` 新增 `offset`/`limit` 参数，返回 `totalMatched`/`hasMore` 元数据
- **结构化错误** — 所有错误返回 `code`、`message` 和 `suggestion` 字段（如 `NO_WORKSPACE`、`CLASS_NOT_FOUND`）
- **错误检测** — MCP Server 在错误响应时设置 `isError: true`，便于 AI 客户端处理
- 工具数量：10 → 16

### v1.0.0

- 首次发布，包含 10 个 MCP 工具：open_jar、close_workspace、list_classes、get_class_info、decompile_class、search_code、get_call_graph、get_inheritance、rename_symbol、export_mappings

## License

MIT
