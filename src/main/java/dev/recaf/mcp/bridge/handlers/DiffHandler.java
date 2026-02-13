package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.DiffUtil;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Handles class diff requests: compare two decompiled classes or a class against provided source.
 */
public class DiffHandler {
	private static final Logger logger = Logging.get(DiffHandler.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;

	public DiffHandler(WorkspaceManager workspaceManager, DecompilerManager decompilerManager) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	/**
	 * POST /diff
	 * Mode 1: { "className1": "com/example/A", "className2": "com/example/B" }
	 * Mode 2: { "className1": "com/example/A", "source": "provided source code" }
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className1 = JsonUtil.getString(req, "className1", null);
		String className2 = JsonUtil.getString(req, "className2", null);
		String providedSource = JsonUtil.getString(req, "source", null);

		if (className1 == null || className1.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className1"));
			return;
		}

		if (className2 == null && providedSource == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS,
					"Either 'className2' or 'source' must be provided",
					"Provide className2 to compare two classes, or source to compare against custom text."));
			return;
		}

		try {
			// Decompile first class
			String source1 = decompileClass(workspace, className1);
			if (source1 == null) {
				BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className1));
				return;
			}

			String source2;
			String label2;
			if (className2 != null) {
				source2 = decompileClass(workspace, className2);
				if (source2 == null) {
					BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className2));
					return;
				}
				label2 = className2.replace('.', '/');
			} else {
				source2 = providedSource;
				label2 = "provided-source";
			}

			String label1 = className1.replace('.', '/');
			String diff = DiffUtil.unifiedDiff(label1, label2, source1, source2);
			boolean identical = source1.equals(source2);

			JsonObject data = new JsonObject();
			data.addProperty("diff", diff);
			data.addProperty("linesAdded", DiffUtil.countAdded(diff));
			data.addProperty("linesRemoved", DiffUtil.countRemoved(diff));
			data.addProperty("identical", identical);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Diff: {} vs {} (identical={})", label1, label2, identical);
		} catch (Exception e) {
			logger.error("Diff failed for '{}'", className1, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Diff classes", e));
		}
	}

	private String decompileClass(Workspace workspace, String className) throws Exception {
		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) return null;

		JvmClassInfo classInfo = classPath.getValue().asJvmClass();
		DecompileResult result = decompilerManager.decompile(workspace, classInfo)
				.get(30, TimeUnit.SECONDS);
		return result.getText();
	}
}
