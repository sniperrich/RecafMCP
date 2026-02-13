# Recaf MCP Plugin

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK 22+](https://img.shields.io/badge/JDK-22%2B-orange.svg)](https://openjdk.org/)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-green.svg)](https://modelcontextprotocol.io/)

Enable AI assistants to control [Recaf 4.x](https://github.com/Col-E/Recaf) through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) — decompile, search, analyze, and rename Java bytecode directly from your AI workflow.

[中文文档](README_CN.md)

## How It Works

The plugin uses a dual-process architecture to bridge AI assistants with Recaf's powerful bytecode analysis engine:

```
┌─────────────────┐     STDIO / JSON-RPC     ┌─────────────────┐    HTTP :9847     ┌─────────────────┐
│  AI Assistant    │ ◄──────────────────────► │   MCP Server    │ ◄────────────────► │  Recaf Plugin   │
│ (Claude Code,   │                          │ (Standalone JAR) │                    │ (Bridge Server) │
│  Cursor, etc.)  │                          └─────────────────┘                    └────────┬────────┘
└─────────────────┘                                                                          │
                                                                                    ┌────────┴────────┐
                                                                                    │   Recaf 4.x     │
                                                                                    │ (Analysis Engine)│
                                                                                    └─────────────────┘
```

- **Recaf Plugin (Bridge Server)** — Runs inside Recaf's process. Injects Recaf services via CDI (Jakarta) and exposes them as HTTP endpoints on `localhost:9847`.
- **MCP Server** — A standalone fat JAR launched by the AI client. Communicates with the AI via STDIO JSON-RPC and forwards tool calls to the Bridge Server over HTTP.

This separation is necessary because Recaf runs as a JavaFX desktop application with its own module system, while MCP requires a STDIO-based process that the AI client can spawn and manage.

## Available MCP Tools

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `open_jar` | Open a JAR, APK, or class file for analysis | `path` — absolute file path |
| `close_workspace` | Close the currently open workspace | — |
| `list_classes` | List all classes in the workspace | `filter` (optional) — name filter, e.g. `com/example` or `Main` |
| `get_class_info` | Get class details: fields, methods, interfaces, annotations | `className` — e.g. `com/example/Main` |
| `decompile_class` | Decompile a class to Java source code | `className` — e.g. `com/example/Main` |
| `search_code` | Search for strings, class/method/field references, or declarations | `query`, `type` (`string`/`class`/`method`/`field`/`declaration`), `maxResults` |
| `get_call_graph` | Get method call graph (callers and callees) | `className`, `methodName` (optional), `depth` (default: 3) |
| `get_inheritance` | Get inheritance hierarchy (parents/children) | `className`, `direction` (`both`/`parents`/`children`) |
| `rename_symbol` | Rename a class, field, or method (updates all references) | `type` (`class`/`field`/`method`), `oldName`, `newName`, `className` (for field/method), `descriptor` (optional) |
| `export_mappings` | Export rename mappings to a file | `format` (`TinyV1`/`SRG`/`Proguard`/...), `outputPath` |

## MCP Resources

| URI | Description |
|-----|-------------|
| `recaf://workspace` | Current workspace information (JSON) |
| `recaf://classes` | Full class list of the current workspace (JSON) |

## Prerequisites

- **JDK 22+** — Required by Recaf 4.x. Make sure `java` on your PATH points to JDK 22 or higher.
- **Recaf 4.x** — This plugin is built against snapshot `d07958a5c7`. The build system will download it automatically.

## Build

```bash
git clone https://github.com/your-repo/recaf-mcp-plugin.git
cd recaf-mcp-plugin
./gradlew build
```

This produces two JARs:

| File | Purpose |
|------|---------|
| `build/libs/recaf-mcp-plugin-1.0.0.jar` | Recaf plugin (loads inside Recaf, runs the Bridge Server) |
| `build/mcp/recaf-mcp-server-1.0.0.jar` | MCP Server (standalone fat JAR, launched by AI client) |

## Setup & Usage

### Step 1: Start Recaf with the Plugin

**Option A: Run directly from the project (recommended for development)**

```bash
./gradlew runRecaf
```

This builds the plugin and launches Recaf with it auto-loaded.

**Option B: Manual installation**

Copy `build/libs/recaf-mcp-plugin-1.0.0.jar` to Recaf's plugin directory:

| OS | Plugin Directory |
|----|-----------------|
| macOS / Linux | `~/Recaf/plugins/` |
| Windows | `%APPDATA%/Recaf/plugins/` |

Then start Recaf normally.

**Verify:** Look for this in Recaf's Logging panel:

```
========================================
  Recaf MCP Plugin enabled
  Bridge Server running on port 9847
========================================
```

### Step 2: Configure Your AI Client

#### Claude Code

Add to `~/.claude.json`:

```json
{
  "mcpServers": {
    "recaf": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/build/mcp/recaf-mcp-server-1.0.0.jar"]
    }
  }
}
```

Then restart Claude Code. The `recaf` tools will appear in your tool list.

#### Cursor

Add to your MCP configuration (Settings → MCP):

```json
{
  "mcpServers": {
    "recaf": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/build/mcp/recaf-mcp-server-1.0.0.jar"]
    }
  }
}
```

#### Other MCP-Compatible Clients

Any client that supports the MCP protocol can use this plugin. Configure it to spawn the MCP Server JAR via `java -jar` over STDIO.

### Step 3: Start Using

Once both Recaf and your AI client are running, you can interact naturally:

```
Open /path/to/target.jar and list all classes
Decompile the com/example/Main class
Search for all strings containing "password"
Show me the call graph for com/example/Main
Rename com/example/a to com/example/LoginManager
Export the mappings as TinyV1 to /tmp/mappings.tiny
```

## Example Workflows

### Reverse Engineering an Obfuscated JAR

```
1. "Open /path/to/obfuscated.jar"
2. "List all classes" — get an overview of the package structure
3. "Decompile com/a/b/c" — read the decompiled source
4. "Search for strings containing 'http'" — find network endpoints
5. "Get the call graph for com/a/b/c method d" — understand control flow
6. "Rename com/a/b/c to com/app/NetworkManager" — give it a meaningful name
7. "Export mappings as TinyV1 to ./mappings.tiny" — save your work
```

### Analyzing Library Dependencies

```
1. "Open /path/to/library.jar"
2. "Search for class references to javax/crypto" — find crypto usage
3. "Get inheritance of com/lib/BaseHandler" — see the class hierarchy
4. "Decompile com/lib/impl/SecureHandler" — inspect implementation details
```

## Project Structure

```
src/main/java/dev/recaf/mcp/
├── RecafMcpPlugin.java                  # Plugin entry point — CDI injection of Recaf services
├── bridge/
│   ├── BridgeServer.java                # HTTP server on :9847 — routes requests to handlers
│   └── handlers/
│       ├── WorkspaceHandler.java        # /workspace/* — open, close, list classes, class info
│       ├── DecompileHandler.java        # /decompile — decompile class to Java source
│       ├── SearchHandler.java           # /search — string, class, method, field, declaration search
│       ├── AnalysisHandler.java         # /analysis/* — call graph & inheritance hierarchy
│       └── MappingHandler.java          # /mapping/* — rename symbols & export mappings
├── server/
│   ├── RecafMcpServer.java              # MCP Server — STDIO JSON-RPC, tool/resource dispatch
│   └── BridgeClient.java               # HTTP client — forwards MCP tool calls to Bridge Server
└── util/
    └── JsonUtil.java                    # JSON response helpers
```

## Bridge HTTP API Reference

All endpoints accept POST with JSON body and return JSON responses.

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Health check — returns `{"status":"ok"}` |
| `POST /workspace/open` | Open a file: `{"path": "/path/to/file.jar"}` |
| `POST /workspace/close` | Close workspace: `{}` |
| `GET /workspace/info` | Get workspace info |
| `POST /workspace/classes` | List classes: `{"filter": "optional"}` |
| `POST /workspace/class-info` | Class details: `{"className": "com/example/Main"}` |
| `POST /decompile` | Decompile: `{"className": "com/example/Main"}` |
| `POST /search` | Search: `{"query": "text", "type": "string", "maxResults": 100}` |
| `POST /analysis/call-graph` | Call graph: `{"className": "...", "methodName": "...", "depth": 3}` |
| `POST /analysis/inheritance` | Inheritance: `{"className": "...", "direction": "both"}` |
| `POST /mapping/rename` | Rename: `{"type": "class", "oldName": "...", "newName": "..."}` |
| `POST /mapping/export` | Export: `{"format": "TinyV1", "outputPath": "/path/to/output"}` |

## Technical Details

| Item | Value |
|------|-------|
| MCP Protocol Version | `2024-11-05` |
| Bridge Port | `9847` (hardcoded) |
| MCP Server Dependencies | Gson only (no MCP SDK — lightweight custom JSON-RPC implementation) |
| Decompile Timeout | 30 seconds |
| Default Max Search Results | 100 |
| Java Toolchain | JDK 22+ |
| Build System | Gradle with Shadow plugin for fat JAR |

## Troubleshooting

**"Connection refused" errors from MCP Server**
- Make sure Recaf is running and the Bridge Server is active on port 9847.
- Check Recaf's Logging panel for the startup banner.

**MCP tools not appearing in AI client**
- Verify the path to `recaf-mcp-server-1.0.0.jar` is correct and absolute.
- Make sure `java` points to JDK 22+: run `java -version` to check.
- Restart your AI client after updating the MCP configuration.

**Decompilation returns empty or errors**
- Ensure a workspace is open (use `open_jar` first).
- Check the class name format: use `/` separators (e.g. `com/example/Main`), not `.` separators.

**Build fails**
- Ensure JDK 22+ is installed. Run `./gradlew -q javaToolchains` to see detected JDKs.
- If using a non-default JDK, configure a [Gradle toolchain](https://docs.gradle.org/current/userguide/toolchains.html).

## License

MIT
