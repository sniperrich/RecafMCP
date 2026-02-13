# Recaf MCP Plugin

[![Version](https://img.shields.io/badge/version-1.1.0-brightgreen.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK 22+](https://img.shields.io/badge/JDK-22%2B-orange.svg)](https://openjdk.org/)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-green.svg)](https://modelcontextprotocol.io/)
[![Tools](https://img.shields.io/badge/MCP_Tools-16-purple.svg)]()

Enable AI assistants to control [Recaf 4.x](https://github.com/Col-E/Recaf) through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) — decompile, search, analyze, edit bytecode, diff classes, and export Java bytecode directly from your AI workflow.

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

## Available MCP Tools (16)

### Workspace Management

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `open_jar` | Open a JAR, APK, or class file for analysis | `path` — absolute file path. Returns `workspaceId`. |
| `close_workspace` | Close the current or a specific workspace | `workspaceId` (optional) — close by ID |
| `switch_workspace` | Switch to a previously opened workspace | `workspaceId` — ID returned by `open_jar` |
| `list_workspaces` | List all registered workspaces | — |
| `list_classes` | List classes with offset/limit pagination | `filter`, `offset`, `limit` |
| `get_class_info` | Get class details: fields, methods, interfaces | `className` |

### Analysis

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `decompile_class` | Decompile a class to Java source code | `className` |
| `search_code` | Search strings, references, or declarations | `query`, `type`, `maxResults` |
| `get_call_graph` | Get method call graph (callers and callees) | `className`, `methodName`, `depth` |
| `get_inheritance` | Get inheritance hierarchy (parents/children) | `className`, `direction` |
| `diff_classes` | Compare two classes or class vs. source code | `className1`, `className2` or `source` |

### Modification

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `rename_symbol` | Rename class/field/method (updates all refs) | `type`, `oldName`, `newName`, `className` |
| `edit_bytecode` | Add/remove/modify methods and fields | `className`, `operation`, + operation-specific params |

### Export

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `export_mappings` | Export rename mappings to a file | `format`, `outputPath` |
| `export_jar` | Export workspace as a JAR file | `outputPath` |
| `export_source` | Export decompiled source to a directory | `outputDir`, `className` (optional) |

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
| `build/libs/recaf-mcp-plugin-1.1.0.jar` | Recaf plugin (loads inside Recaf, runs the Bridge Server) |
| `build/mcp/recaf-mcp-server-1.1.0.jar` | MCP Server (standalone fat JAR, launched by AI client) |

## Setup & Usage

### Step 1: Start Recaf with the Plugin

**Option A: Run directly from the project (recommended for development)**

```bash
./gradlew runRecaf
```

This builds the plugin and launches Recaf with it auto-loaded.

**Option B: Manual installation**

Copy `build/libs/recaf-mcp-plugin-1.1.0.jar` to Recaf's plugin directory:

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
      "args": ["-jar", "/absolute/path/to/build/mcp/recaf-mcp-server-1.1.0.jar"]
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
      "args": ["-jar", "/absolute/path/to/build/mcp/recaf-mcp-server-1.1.0.jar"]
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
Compare com/example/A with com/example/B
Remove the method "unused" from com/example/Foo
Export the modified JAR to /tmp/output.jar
Export all decompiled source to /tmp/src
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
8. "Export the modified JAR to ./cleaned.jar" — save the result
```

### Multi-JAR Comparison

```
1. "Open /path/to/v1.jar" — opens first JAR, returns workspaceId
2. "Open /path/to/v2.jar" — opens second JAR, returns workspaceId
3. "List workspaces" — see both workspaces
4. "Switch to workspace v1-1" — switch to first JAR
5. "Decompile com/example/Main" — get v1 source
6. "Switch to workspace v2-2" — switch to second JAR
7. "Diff com/example/Main against the v1 source" — compare versions
```

### Bytecode Editing

```
1. "Open /path/to/target.jar"
2. "Remove the method 'checkLicense' from com/app/Main"
3. "Add a public field 'debug' of type boolean to com/app/Config"
4. "Export the modified JAR to /tmp/patched.jar"
```

## Project Structure

```
src/main/java/dev/recaf/mcp/
├── RecafMcpPlugin.java                  # Plugin entry point — CDI injection of Recaf services
├── bridge/
│   ├── BridgeServer.java                # HTTP server on :9847 — routes requests to handlers
│   ├── WorkspaceRegistry.java           # Multi-workspace registry — ID → Workspace mapping
│   └── handlers/
│       ├── WorkspaceHandler.java        # /workspace/* — open, close, switch, list, classes, info
│       ├── DecompileHandler.java        # /decompile — decompile class to Java source
│       ├── SearchHandler.java           # /search — string, class, method, field, declaration search
│       ├── AnalysisHandler.java         # /analysis/* — call graph & inheritance hierarchy
│       ├── MappingHandler.java          # /mapping/* — rename symbols & export mappings
│       ├── BytecodeHandler.java         # /bytecode/* — edit/add/remove methods & fields
│       ├── DiffHandler.java             # /diff — compare two classes (unified diff)
│       └── ExportHandler.java           # /export/* — export JAR & decompiled source
├── server/
│   ├── RecafMcpServer.java              # MCP Server — STDIO JSON-RPC, 16 tools dispatch
│   └── BridgeClient.java               # HTTP client — forwards MCP tool calls to Bridge Server
└── util/
    ├── JsonUtil.java                    # JSON response helpers
    ├── ErrorMapper.java                 # Structured error codes, messages & suggestions
    └── DiffUtil.java                    # LCS-based unified diff algorithm
```

## Bridge HTTP API Reference

All endpoints accept POST with JSON body and return JSON responses.

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Health check — returns `{"status":"ok"}` |
| `POST /workspace/open` | Open a file: `{"path": "/path/to/file.jar"}` → returns `workspaceId` |
| `POST /workspace/close` | Close workspace: `{"workspaceId": "optional"}` |
| `GET /workspace/info` | Get workspace info |
| `POST /workspace/classes` | List classes: `{"filter": "opt", "offset": 0, "limit": 500}` |
| `POST /workspace/class-info` | Class details: `{"className": "com/example/Main"}` |
| `POST /workspace/switch` | Switch workspace: `{"workspaceId": "xxx"}` |
| `GET /workspace/list-workspaces` | List all registered workspaces |
| `POST /decompile` | Decompile: `{"className": "com/example/Main"}` |
| `POST /search` | Search: `{"query": "text", "type": "string", "maxResults": 100}` |
| `POST /analysis/call-graph` | Call graph: `{"className": "...", "methodName": "...", "depth": 3}` |
| `POST /analysis/inheritance` | Inheritance: `{"className": "...", "direction": "both"}` |
| `POST /mapping/rename` | Rename: `{"type": "class", "oldName": "...", "newName": "..."}` |
| `POST /mapping/export` | Export mappings: `{"format": "TinyV1", "outputPath": "/path"}` |
| `POST /bytecode/edit-method` | Edit method: `{"className": "...", "methodName": "...", "methodDesc": "...", "accessFlags": 1}` |
| `POST /bytecode/edit-field` | Edit field: `{"className": "...", "fieldName": "...", "accessFlags": 2}` |
| `POST /bytecode/remove-member` | Remove member: `{"className": "...", "memberName": "...", "memberType": "method"}` |
| `POST /bytecode/add-field` | Add field: `{"className": "...", "fieldName": "...", "descriptor": "I"}` |
| `POST /bytecode/add-method` | Add method: `{"className": "...", "methodName": "...", "methodDesc": "()V"}` |
| `POST /diff` | Diff classes: `{"className1": "A", "className2": "B"}` or `{"className1": "A", "source": "..."}` |
| `POST /export/jar` | Export JAR: `{"outputPath": "/path/to/output.jar"}` |
| `POST /export/source` | Export source: `{"outputDir": "/path/to/src", "className": "optional"}` |

## Technical Details

| Item | Value |
|------|-------|
| MCP Protocol Version | `2024-11-05` |
| Bridge Port | `9847` (hardcoded) |
| MCP Server Dependencies | Gson only (no MCP SDK — lightweight custom JSON-RPC implementation) |
| Decompile Timeout | 30 seconds |
| Default Max Search Results | 100 |
| Default Class List Limit | 500 (with offset pagination) |
| Java Toolchain | JDK 22+ |
| Build System | Gradle with Shadow plugin for fat JAR |
| Total MCP Tools | 16 |

## Troubleshooting

**"Connection refused" errors from MCP Server**
- Make sure Recaf is running and the Bridge Server is active on port 9847.
- Check Recaf's Logging panel for the startup banner.

**MCP tools not appearing in AI client**
- Verify the path to `recaf-mcp-server-1.1.0.jar` is correct and absolute.
- Make sure `java` points to JDK 22+: run `java -version` to check.
- Restart your AI client after updating the MCP configuration.

**Decompilation returns empty or errors**
- Ensure a workspace is open (use `open_jar` first).
- Check the class name format: use `/` separators (e.g. `com/example/Main`), not `.` separators.

**Structured error responses**
- All errors now include `code`, `message`, and `suggestion` fields.
- Common codes: `NO_WORKSPACE`, `CLASS_NOT_FOUND`, `MEMBER_NOT_FOUND`, `INVALID_PARAMS`, `DECOMPILE_TIMEOUT`.

**Build fails**
- Ensure JDK 22+ is installed. Run `./gradlew -q javaToolchains` to see detected JDKs.
- If using a non-default JDK, configure a [Gradle toolchain](https://docs.gradle.org/current/userguide/toolchains.html).

## Changelog

### v1.1.0

- **Multi-workspace support** — open multiple JARs simultaneously, switch between them with `switch_workspace` and `list_workspaces`
- **Bytecode editing** — `edit_bytecode` tool with 5 operations: edit_method, edit_field, remove_member, add_field, add_method (ASM-based)
- **Class diff** — `diff_classes` tool produces unified diff between two decompiled classes or class vs. provided source
- **Export** — `export_jar` exports the workspace (with modifications) as a JAR; `export_source` exports decompiled source to a directory
- **Pagination** — `list_classes` now supports `offset`/`limit` with `totalMatched`/`hasMore` metadata
- **Structured errors** — all errors return `code`, `message`, and `suggestion` fields (e.g. `NO_WORKSPACE`, `CLASS_NOT_FOUND`)
- **Error detection** — MCP Server now sets `isError: true` on error responses for better AI client handling
- Tool count: 10 → 16

### v1.0.0

- Initial release with 10 MCP tools: open_jar, close_workspace, list_classes, get_class_info, decompile_class, search_code, get_call_graph, get_inheritance, rename_symbol, export_mappings

## License

MIT
