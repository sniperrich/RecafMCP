package dev.recaf.mcp.server;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lightweight MCP Server for Recaf - runs as a standalone process.
 * Implements the MCP JSON-RPC protocol over STDIO directly (no SDK dependency).
 * Relays tool calls to the Recaf Bridge Server via HTTP.
 */
public class RecafMcpServer {

	private static final Gson GSON = new GsonBuilder().create();
	private final BridgeClient bridge;
	private final OutputStream rawOut;
	private final BufferedReader in;

	public RecafMcpServer() {
		this.bridge = new BridgeClient();
		this.rawOut = System.out;
		this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
	}

	public void start() {
		System.err.println("[MCP Server] Started, waiting for input...");
		try {
			String line;
			while ((line = in.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				System.err.println("[MCP Server] Received: " + line.substring(0, Math.min(line.length(), 200)));
				try {
					JsonObject request = JsonParser.parseString(line).getAsJsonObject();
					handleRequest(request);
				} catch (Exception e) {
					System.err.println("[MCP Server] Error: " + e.getMessage());
					e.printStackTrace(System.err);
				}
			}
		} catch (IOException e) {
			System.err.println("[MCP Server] STDIO error: " + e.getMessage());
		}
		System.err.println("[MCP Server] Input stream closed, exiting.");
	}

	private void handleRequest(JsonObject request) {
		String method = request.has("method") ? request.get("method").getAsString() : null;
		JsonElement idEl = request.get("id");

		// Notifications (no id) - just acknowledge
		if (idEl == null || idEl.isJsonNull()) return;

		switch (method) {
			case "initialize" -> sendResult(idEl, buildInitializeResult());
			case "tools/list" -> sendResult(idEl, buildToolsList());
			case "tools/call" -> handleToolCall(idEl, request.getAsJsonObject("params"));
			case "resources/list" -> sendResult(idEl, buildResourcesList());
			case "resources/read" -> handleResourceRead(idEl, request.getAsJsonObject("params"));
			case "ping" -> sendResult(idEl, new JsonObject());
			default -> sendError(idEl, -32601, "Method not found: " + method);
		}
	}

	// ==================== Initialize ====================

	private JsonObject buildInitializeResult() {
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", "2024-11-05");

		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		JsonObject resources = new JsonObject();
		resources.addProperty("subscribe", false);
		resources.addProperty("listChanged", false);
		capabilities.add("resources", resources);
		result.add("capabilities", capabilities);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "recaf-mcp");
		serverInfo.addProperty("version", "1.0.0");
		result.add("serverInfo", serverInfo);

		return result;
	}

	// ==================== Tools ====================

	private JsonObject buildToolsList() {
		JsonArray tools = new JsonArray();
		tools.add(toolDef("open_jar", "Open a JAR, APK, or class file in Recaf for analysis",
				requiredProps(prop("path", "string", "Absolute path to the JAR/APK/class file to open"))));
		tools.add(toolDef("close_workspace", "Close the currently open workspace in Recaf",
				new JsonObject()));
		tools.add(toolDef("list_classes", "List all classes in the current workspace, optionally filtered by name",
				optionalProps(prop("filter", "string", "Optional filter string to match class names (e.g. 'com/example' or 'Main')"))));
		tools.add(toolDef("get_class_info", "Get detailed class information including fields, methods, interfaces, and annotations",
				requiredProps(prop("className", "string", "Fully qualified class name (e.g. 'com/example/Main' or 'com.example.Main')"))));
		tools.add(toolDef("decompile_class", "Decompile a Java class to source code",
				requiredProps(prop("className", "string", "Fully qualified class name to decompile (e.g. 'com/example/Main' or 'com.example.Main')"))));
		tools.add(toolDef("search_code", "Search for strings, class/method/field references, or declarations in the workspace",
				searchSchema()));
		tools.add(toolDef("get_call_graph", "Get the call graph showing which methods call which, with callers and callees",
				callGraphSchema()));
		tools.add(toolDef("get_inheritance", "Get the inheritance hierarchy (parents and/or children) of a class",
				inheritanceSchema()));
		tools.add(toolDef("rename_symbol", "Rename a class, field, or method. Updates all references across the workspace",
				renameSchema()));
		tools.add(toolDef("export_mappings", "Export accumulated rename mappings to a file in the specified format",
				optionalProps(
						prop("format", "string", "Mapping format (e.g. 'TinyV1', 'SRG', 'Proguard'). Omit to list available formats"),
						prop("outputPath", "string", "Absolute path to write the mapping file to"))));

		JsonObject result = new JsonObject();
		result.add("tools", tools);
		return result;
	}

	private void handleToolCall(JsonElement id, JsonObject params) {
		String name = params.get("name").getAsString();
		JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

		String text;
		try {
			text = switch (name) {
				case "open_jar" -> bridge.extractData(bridge.post("/workspace/open",
						jsonBody("path", getString(args, "path"))));
				case "close_workspace" -> bridge.extractData(bridge.post("/workspace/close", "{}"));
				case "list_classes" -> {
					String filter = getString(args, "filter");
					String body = filter == null ? "{}" : jsonBody("filter", filter);
					yield bridge.extractData(bridge.post("/workspace/classes", body));
				}
				case "get_class_info" -> bridge.extractData(bridge.post("/workspace/class-info",
						jsonBody("className", getString(args, "className"))));
				case "decompile_class" -> bridge.extractData(bridge.post("/decompile",
						jsonBody("className", getString(args, "className"))));
				case "search_code" -> {
					JsonObject body = new JsonObject();
					body.addProperty("query", getString(args, "query"));
					body.addProperty("type", getStringOr(args, "type", "string"));
					body.addProperty("maxResults", getIntOr(args, "maxResults", 100));
					yield bridge.extractData(bridge.post("/search", GSON.toJson(body)));
				}
				case "get_call_graph" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					if (args.has("methodName")) body.addProperty("methodName", getString(args, "methodName"));
					body.addProperty("depth", getIntOr(args, "depth", 3));
					yield bridge.extractData(bridge.post("/analysis/call-graph", GSON.toJson(body)));
				}
				case "get_inheritance" -> {
					JsonObject body = new JsonObject();
					body.addProperty("className", getString(args, "className"));
					body.addProperty("direction", getStringOr(args, "direction", "both"));
					yield bridge.extractData(bridge.post("/analysis/inheritance", GSON.toJson(body)));
				}
				case "rename_symbol" -> {
					JsonObject body = new JsonObject();
					body.addProperty("type", getString(args, "type"));
					body.addProperty("oldName", getString(args, "oldName"));
					body.addProperty("newName", getString(args, "newName"));
					if (args.has("className")) body.addProperty("className", getString(args, "className"));
					if (args.has("descriptor")) body.addProperty("descriptor", getString(args, "descriptor"));
					yield bridge.extractData(bridge.post("/mapping/rename", GSON.toJson(body)));
				}
				case "export_mappings" -> {
					JsonObject body = new JsonObject();
					if (args.has("format")) body.addProperty("format", getString(args, "format"));
					if (args.has("outputPath")) body.addProperty("outputPath", getString(args, "outputPath"));
					yield bridge.extractData(bridge.post("/mapping/export", GSON.toJson(body)));
				}
				default -> "{\"error\":\"Unknown tool: " + name + "\"}";
			};
		} catch (Exception e) {
			text = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
		}

		// Build CallToolResult
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		JsonObject textContent = new JsonObject();
		textContent.addProperty("type", "text");
		textContent.addProperty("text", text);
		content.add(textContent);
		result.add("content", content);
		result.addProperty("isError", false);

		sendResult(id, result);
	}

	// ==================== Resources ====================

	private JsonObject buildResourcesList() {
		JsonArray resources = new JsonArray();

		JsonObject ws = new JsonObject();
		ws.addProperty("uri", "recaf://workspace");
		ws.addProperty("name", "Current Workspace");
		ws.addProperty("description", "Information about the currently open workspace in Recaf");
		ws.addProperty("mimeType", "application/json");
		resources.add(ws);

		JsonObject cls = new JsonObject();
		cls.addProperty("uri", "recaf://classes");
		cls.addProperty("name", "Class List");
		cls.addProperty("description", "List of all classes in the current workspace");
		cls.addProperty("mimeType", "application/json");
		resources.add(cls);

		JsonObject result = new JsonObject();
		result.add("resources", resources);
		return result;
	}

	private void handleResourceRead(JsonElement id, JsonObject params) {
		String uri = params.get("uri").getAsString();
		String data;
		try {
			data = switch (uri) {
				case "recaf://workspace" -> bridge.extractData(bridge.get("/workspace/info"));
				case "recaf://classes" -> bridge.extractData(bridge.post("/workspace/classes", "{}"));
				default -> "{\"error\":\"Unknown resource: " + uri + "\"}";
			};
		} catch (Exception e) {
			data = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
		}

		JsonObject result = new JsonObject();
		JsonArray contents = new JsonArray();
		JsonObject item = new JsonObject();
		item.addProperty("uri", uri);
		item.addProperty("mimeType", "application/json");
		item.addProperty("text", data);
		contents.add(item);
		result.add("contents", contents);

		sendResult(id, result);
	}

	// ==================== JSON-RPC Helpers ====================

	private void sendResult(JsonElement id, JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		writeLine(GSON.toJson(response));
	}

	private void sendError(JsonElement id, int code, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		response.add("error", error);
		writeLine(GSON.toJson(response));
	}

	private synchronized void writeLine(String json) {
		try {
			byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
			rawOut.write(bytes);
			rawOut.flush();
			System.err.println("[MCP Server] Sent: " + json.substring(0, Math.min(json.length(), 200)));
		} catch (IOException e) {
			System.err.println("[MCP Server] Write error: " + e.getMessage());
		}
	}

	// ==================== Schema Helpers ====================

	private static JsonObject toolDef(String name, String description, JsonObject inputSchema) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		inputSchema.addProperty("type", "object");
		tool.add("inputSchema", inputSchema);
		return tool;
	}

	private static JsonObject prop(String name, String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	private static JsonObject requiredProps(JsonObject... props) {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		for (JsonObject p : props) {
			String name = p.get("name").getAsString();
			JsonObject propDef = new JsonObject();
			propDef.addProperty("type", p.get("type").getAsString());
			propDef.addProperty("description", p.get("description").getAsString());
			properties.add(name, propDef);
			required.add(name);
		}
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}

	private static JsonObject optionalProps(JsonObject... props) {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		for (JsonObject p : props) {
			String name = p.get("name").getAsString();
			JsonObject propDef = new JsonObject();
			propDef.addProperty("type", p.get("type").getAsString());
			propDef.addProperty("description", p.get("description").getAsString());
			properties.add(name, propDef);
		}
		schema.add("properties", properties);
		return schema;
	}

	private static JsonObject searchSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		JsonObject query = new JsonObject();
		query.addProperty("type", "string");
		query.addProperty("description", "Search query string");
		properties.add("query", query);
		JsonObject type = new JsonObject();
		type.addProperty("type", "string");
		type.addProperty("description", "Type of search: 'string', 'class', 'method', 'field', 'declaration'");
		JsonArray enumVals = new JsonArray();
		enumVals.add("string"); enumVals.add("class"); enumVals.add("method"); enumVals.add("field"); enumVals.add("declaration");
		type.add("enum", enumVals);
		properties.add("type", type);
		JsonObject maxResults = new JsonObject();
		maxResults.addProperty("type", "integer");
		maxResults.addProperty("description", "Maximum number of results to return (default: 100)");
		properties.add("maxResults", maxResults);
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("query");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject callGraphSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		properties.add("methodName", typedProp("string", "Optional method name to focus on"));
		properties.add("depth", typedProp("integer", "Maximum depth of call graph traversal (default: 3)"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject inheritanceSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.add("className", typedProp("string", "Fully qualified class name (e.g. 'com/example/Main')"));
		JsonObject dir = typedProp("string", "Direction: 'both', 'parents', or 'children' (default: 'both')");
		JsonArray enumVals = new JsonArray();
		enumVals.add("both"); enumVals.add("parents"); enumVals.add("children");
		dir.add("enum", enumVals);
		properties.add("direction", dir);
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("className");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject renameSchema() {
		JsonObject schema = new JsonObject();
		JsonObject properties = new JsonObject();
		JsonObject type = typedProp("string", "Type of symbol to rename");
		JsonArray enumVals = new JsonArray();
		enumVals.add("class"); enumVals.add("field"); enumVals.add("method");
		type.add("enum", enumVals);
		properties.add("type", type);
		properties.add("oldName", typedProp("string", "Current name of the symbol"));
		properties.add("newName", typedProp("string", "New name for the symbol"));
		properties.add("className", typedProp("string", "Owning class name (required for field/method renames)"));
		properties.add("descriptor", typedProp("string", "Optional descriptor for field/method disambiguation"));
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("type"); required.add("oldName"); required.add("newName");
		schema.add("required", required);
		return schema;
	}

	private static JsonObject typedProp(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	// ==================== Arg Helpers ====================

	private static String getString(JsonObject obj, String key) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
	}

	private static String getStringOr(JsonObject obj, String key, String def) {
		String v = getString(obj, key);
		return v != null ? v : def;
	}

	private static int getIntOr(JsonObject obj, String key, int def) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : def;
	}

	private static String escapeJson(String s) {
		if (s == null) return "null";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String jsonBody(String key, String value) {
		return "{\"" + key + "\":\"" + escapeJson(value) + "\"}";
	}

	// ==================== Main ====================

	public static void main(String[] args) {
		new RecafMcpServer().start();
	}
}
