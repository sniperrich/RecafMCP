# Recaf MCP Plugin

让 AI 助手通过 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 操控 [Recaf 4.x](https://github.com/Col-E/Recaf)，实现 Java 字节码的反编译、搜索、分析和重命名等操作。

## 架构

```
┌─────────────────┐     STDIO/JSON-RPC      ┌─────────────────┐     HTTP :9847      ┌─────────────────┐
│   AI Assistant   │ ◄──────────────────────► │   MCP Server    │ ◄─────────────────► │  Recaf Plugin   │
│ (Claude Code等)  │                          │ (独立 Java 进程) │                     │ (Bridge Server) │
└─────────────────┘                          └─────────────────┘                     └────────┬────────┘
                                                                                              │
                                                                                     ┌───────┴────────┐
                                                                                     │   Recaf 4.x    │
                                                                                     │ (反编译/分析引擎) │
                                                                                     └────────────────┘
```

双进程设计：
- **Recaf 插件** — 运行在 Recaf 内部，通过 CDI 注入 Recaf 服务，在 `localhost:9847` 暴露 HTTP API
- **MCP Server** — 独立 JAR 进程，通过 STDIO 与 AI 通信，将工具调用转发到 Bridge HTTP API

## 提供的 MCP Tools

| Tool | 说明 |
|------|------|
| `open_jar` | 打开 JAR/APK/class 文件 |
| `close_workspace` | 关闭当前工作区 |
| `list_classes` | 列出所有类（支持过滤） |
| `get_class_info` | 获取类详情（字段、方法、注解、接口） |
| `decompile_class` | 反编译类到 Java 源码 |
| `search_code` | 搜索字符串/类引用/方法引用/字段引用/声明 |
| `get_call_graph` | 获取方法调用图（caller/callee） |
| `get_inheritance` | 获取继承层次（父类/子类） |
| `rename_symbol` | 重命名类/字段/方法（自动更新所有引用） |
| `export_mappings` | 导出重命名映射（TinyV1/SRG/Proguard） |

## 前置要求

- JDK 22+
- Recaf 4.x（snapshot `d07958a5c7`）

## 构建

```bash
./gradlew build
```

产出两个 JAR：
- `build/libs/recaf-mcp-plugin-workspace-1.0.0.jar` — Recaf 插件
- `build/mcp/recaf-mcp-server-1.0.0.jar` — MCP Server（Shadow fat JAR）

## 使用

### 1. 启动 Recaf

```bash
# 方式一：从项目直接运行（自动加载插件）
./gradlew runRecaf

# 方式二：手动安装插件
# 将 build/libs/*.jar 复制到 Recaf 插件目录：
#   macOS/Linux: $HOME/Recaf/plugins/
#   Windows:     %APPDATA%/Recaf/plugins/
```

启动后在 Recaf 的 Logging 窗口应看到：

```
========================================
  Recaf MCP Plugin enabled
  Bridge Server running on port 9847
========================================
```

### 2. 配置 AI 客户端

以 Claude Code 为例，在 `~/.claude.json` 中添加：

```json
{
  "mcpServers": {
    "recaf": {
      "command": "java",
      "args": ["-jar", "/你的路径/build/mcp/recaf-mcp-server-1.0.0.jar"]
    }
  }
}
```

然后重启 Claude Code，即可使用 `recaf` 相关工具。

### 3. 开始使用

在 AI 对话中直接说：

```
打开 /path/to/target.jar，列出所有类
反编译 com/example/Main 这个类
搜索所有包含 "password" 的字符串
分析 com/example/Main 的调用图
把 com/example/a 重命名为 com/example/LoginManager
导出映射为 TinyV1 格式到 /tmp/mappings.tiny
```

## 项目结构

```
src/main/java/dev/recaf/mcp/
├── RecafMcpPlugin.java              # 插件入口，注入 Recaf 服务
├── bridge/
│   ├── BridgeServer.java            # HTTP API 服务器 (:9847)
│   └── handlers/
│       ├── WorkspaceHandler.java    # 工作区管理
│       ├── DecompileHandler.java    # 反编译
│       ├── SearchHandler.java       # 代码搜索
│       ├── AnalysisHandler.java     # 调用图 & 继承分析
│       └── MappingHandler.java      # 重命名 & 映射导出
├── server/
│   ├── RecafMcpServer.java          # MCP Server (STDIO JSON-RPC)
│   └── BridgeClient.java           # HTTP 客户端
└── util/
    └── JsonUtil.java                # JSON 工具
```

## 技术细节

- MCP 协议版本：`2024-11-05`
- Bridge 端口：`9847`（硬编码）
- MCP Server 自实现轻量 JSON-RPC，无 SDK 依赖，仅依赖 Gson
- 反编译超时：30 秒
- 搜索默认最大结果数：100

## License

MIT
