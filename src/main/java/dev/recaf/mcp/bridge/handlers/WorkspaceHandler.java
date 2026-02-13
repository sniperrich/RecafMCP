package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Handles workspace management: open JAR, close, list classes, get class info.
 */
public class WorkspaceHandler {
	private static final Logger logger = Logging.get(WorkspaceHandler.class);

	private final WorkspaceManager workspaceManager;
	private final ResourceImporter resourceImporter;

	public WorkspaceHandler(WorkspaceManager workspaceManager, ResourceImporter resourceImporter) {
		this.workspaceManager = workspaceManager;
		this.resourceImporter = resourceImporter;
	}

	/**
	 * POST /workspace/open  { "path": "/path/to/file.jar" }
	 */
	public void handleOpen(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String filePath = JsonUtil.getString(req, "path", null);

		if (filePath == null || filePath.isBlank()) {
			BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing 'path' parameter"));
			return;
		}

		try {
			Path path = Paths.get(filePath);
			WorkspaceResource resource = resourceImporter.importResource(path);
			Workspace workspace = new BasicWorkspace(resource);
			workspaceManager.setCurrent(workspace);

			int classCount = countClasses(workspace);
			JsonObject data = new JsonObject();
			data.addProperty("path", filePath);
			data.addProperty("classCount", classCount);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Opened workspace: {} ({} classes)", filePath, classCount);
		} catch (Exception e) {
			logger.error("Failed to open workspace from '{}'", filePath, e);
			BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("Failed to open: " + e.getMessage()));
		}
	}

	/**
	 * POST /workspace/close
	 */
	public void handleClose(HttpExchange exchange) throws IOException {
		Workspace current = workspaceManager.getCurrent();
		if (current == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse("No workspace was open"));
			return;
		}
		workspaceManager.closeCurrent();
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse("Workspace closed"));
		logger.info("[MCP] Workspace closed");
	}

	/**
	 * GET /workspace/info
	 */
	public void handleInfo(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		JsonObject data = new JsonObject();
		data.addProperty("classCount", countClasses(workspace));
		data.addProperty("fileCount", countFiles(workspace));

		// Primary resource info
		WorkspaceResource primary = workspace.getPrimaryResource();
		String resourceId = primary.getClass().getSimpleName();
		data.addProperty("primaryResource", resourceId);
		data.addProperty("supportingResources", workspace.getSupportingResources().size());

		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	/**
	 * GET/POST /workspace/classes  { "filter": "com/example", "limit": 200 }
	 */
	public void handleListClasses(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = body.isBlank() ? new JsonObject() : JsonUtil.parseObject(body);
		String filter = JsonUtil.getString(req, "filter", null);
		int limit = JsonUtil.getInt(req, "limit", 500);

		// Only list classes from the primary resource (not runtime/supporting)
		List<String> classNames = new ArrayList<>();
		var primaryBundle = workspace.getPrimaryResource().getJvmClassBundle();
		for (var classInfo : primaryBundle) {
			String name = classInfo.getName();
			if (filter != null && !filter.isBlank()) {
				String normalizedFilter = filter.replace('.', '/');
				if (!name.contains(normalizedFilter)) continue;
			}
			classNames.add(name);
			if (classNames.size() >= limit) break;
		}

		int totalCount = primaryBundle.size();
		JsonObject data = new JsonObject();
		data.addProperty("totalClasses", totalCount);
		data.addProperty("returnedCount", classNames.size());
		if (classNames.size() < totalCount) {
			data.addProperty("truncated", true);
			data.addProperty("limit", limit);
		}
		data.add("classes", JsonUtil.gson().toJsonTree(classNames));
		logger.info("[MCP] Listed classes: {}/{} (filter='{}')", classNames.size(), totalCount, filter);
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	/**
	 * POST /workspace/class-info  { "className": "com/example/Foo" }
	 */
	public void handleClassInfo(HttpExchange exchange) throws IOException {
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
		ClassPathNode classPath = workspace.findClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, JsonUtil.errorResponse("Class not found: " + className));
			return;
		}

		ClassInfo classInfo = classPath.getValue();
		JsonObject data = new JsonObject();
		data.addProperty("name", classInfo.getName());
		data.addProperty("superName", classInfo.getSuperName());
		data.addProperty("accessFlags", classInfo.getAccess());

		// Interfaces
		List<String> interfaces = new ArrayList<>(classInfo.getInterfaces());
		data.add("interfaces", JsonUtil.gson().toJsonTree(interfaces));

		// Fields
		List<JsonObject> fields = new ArrayList<>();
		for (FieldMember field : classInfo.getFields()) {
			JsonObject f = new JsonObject();
			f.addProperty("name", field.getName());
			f.addProperty("descriptor", field.getDescriptor());
			f.addProperty("accessFlags", field.getAccess());
			fields.add(f);
		}
		data.add("fields", JsonUtil.gson().toJsonTree(fields));

		// Methods
		List<JsonObject> methods = new ArrayList<>();
		for (MethodMember method : classInfo.getMethods()) {
			JsonObject m = new JsonObject();
			m.addProperty("name", method.getName());
			m.addProperty("descriptor", method.getDescriptor());
			m.addProperty("accessFlags", method.getAccess());
			methods.add(m);
		}
		data.add("methods", JsonUtil.gson().toJsonTree(methods));

		// JVM-specific info
		if (classInfo.isJvmClass()) {
			JvmClassInfo jvmInfo = classInfo.asJvmClass();
			data.addProperty("version", jvmInfo.getVersion());
		}

		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	private int countClasses(Workspace workspace) {
		return workspace.getPrimaryResource().getJvmClassBundle().size();
	}

	private int countFiles(Workspace workspace) {
		return workspace.getPrimaryResource().getFileBundle().size();
	}
}
