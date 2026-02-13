package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Handles class decompilation requests.
 */
public class DecompileHandler {
	private static final Logger logger = Logging.get(DecompileHandler.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;

	public DecompileHandler(WorkspaceManager workspaceManager, DecompilerManager decompilerManager) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	/**
	 * POST /decompile  { "className": "com/example/Foo" }
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);

		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing 'className' parameter"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, JsonUtil.errorResponse("Class not found: " + className));
			return;
		}

		JvmClassInfo classInfo = classPath.getValue().asJvmClass();
		logger.info("[MCP] Decompiling class: {}", normalizedName);

		try {
			DecompileResult result = decompilerManager.decompile(workspace, classInfo)
					.get(30, TimeUnit.SECONDS);

			JsonObject data = new JsonObject();
			data.addProperty("className", classInfo.getName());

			if (result.getText() != null) {
				data.addProperty("source", result.getText());
				data.addProperty("decompiler", decompilerManager.getTargetJvmDecompiler().getName());
			} else {
				data.addProperty("source", "// Decompilation failed - no output");
				if (result.getException() != null) {
					data.addProperty("error", result.getException().getMessage());
				}
			}

			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("Decompilation failed for '{}'", className, e);
			BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("Decompilation failed: " + e.getMessage()));
		}
	}
}
