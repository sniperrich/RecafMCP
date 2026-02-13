package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.bridge.WorkspaceRegistry;
import dev.recaf.mcp.util.ErrorMapper;
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
 * Handles workspace management: open JAR, close, list classes, get class info,
 * switch workspace, list workspaces.
 */
public class WorkspaceHandler {
	private static final Logger logger = Logging.get(WorkspaceHandler.class);

	private final WorkspaceManager workspaceManager;
	private final ResourceImporter resourceImporter;
	private final WorkspaceRegistry registry;

	public WorkspaceHandler(WorkspaceManager workspaceManager, ResourceImporter resourceImporter,
							WorkspaceRegistry registry) {
		this.workspaceManager = workspaceManager;
		this.resourceImporter = resourceImporter;
		this.registry = registry;
	}

	/**
	 * POST /workspace/open  { "path": "/path/to/file.jar" }
	 * Returns workspaceId for multi-workspace support.
	 */
	public void handleOpen(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String filePath = JsonUtil.getString(req, "path", null);

		if (filePath == null || filePath.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("path"));
			return;
		}

		try {
			Path path = Paths.get(filePath);
			WorkspaceResource resource = resourceImporter.importResource(path);
			Workspace workspace = new BasicWorkspace(resource);
			workspaceManager.setCurrent(workspace);

			// Register in multi-workspace registry
			String workspaceId = registry.register(filePath, workspace);

			int classCount = countClasses(workspace);
			JsonObject data = new JsonObject();
			data.addProperty("workspaceId", workspaceId);
			data.addProperty("path", filePath);
			data.addProperty("classCount", classCount);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Opened workspace: {} ({} classes, id={})", filePath, classCount, workspaceId);
		} catch (Exception e) {
			logger.error("Failed to open workspace from '{}'", filePath, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Open workspace", e));
		}
	}

	/**
	 * POST /workspace/close  { "workspaceId": "optional-id" }
	 * Supports closing by ID or closing current.
	 */
	public void handleClose(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = body.isBlank() ? new JsonObject() : JsonUtil.parseObject(body);
		String workspaceId = JsonUtil.getString(req, "workspaceId", null);

		if (workspaceId != null) {
			// Close specific workspace by ID
			Workspace ws = registry.get(workspaceId);
			if (ws == null) {
				BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
						ErrorMapper.INVALID_PARAMS, "Workspace not found: " + workspaceId,
						"Use 'list_workspaces' to see available workspace IDs."));
				return;
			}
			// If it's the current workspace, close via manager
			Workspace current = workspaceManager.getCurrent();
			if (current != null && current == ws) {
				workspaceManager.closeCurrent();
			}
			registry.remove(workspaceId);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse("Workspace closed: " + workspaceId));
			logger.info("[MCP] Workspace closed: {}", workspaceId);
		} else {
			// Close current workspace
			Workspace current = workspaceManager.getCurrent();
			if (current == null) {
				BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse("No workspace was open"));
				return;
			}
			// Remove from registry if present
			String id = registry.findId(current);
			if (id != null) registry.remove(id);
			workspaceManager.closeCurrent();
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse("Workspace closed"));
			logger.info("[MCP] Workspace closed");
		}
	}

	/**
	 * GET /workspace/info
	 */
	public void handleInfo(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		JsonObject data = new JsonObject();
		data.addProperty("classCount", countClasses(workspace));
		data.addProperty("fileCount", countFiles(workspace));

		WorkspaceResource primary = workspace.getPrimaryResource();
		String resourceId = primary.getClass().getSimpleName();
		data.addProperty("primaryResource", resourceId);
		data.addProperty("supportingResources", workspace.getSupportingResources().size());

		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	/**
	 * GET/POST /workspace/classes  { "filter": "com/example", "offset": 0, "limit": 200 }
	 * Supports offset-based pagination.
	 */
	public void handleListClasses(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = body.isBlank() ? new JsonObject() : JsonUtil.parseObject(body);
		String filter = JsonUtil.getString(req, "filter", null);
		int offset = JsonUtil.getInt(req, "offset", 0);
		int limit = JsonUtil.getInt(req, "limit", 500);

		// Collect all matching class names first
		List<String> allMatched = new ArrayList<>();
		var primaryBundle = workspace.getPrimaryResource().getJvmClassBundle();
		for (var classInfo : primaryBundle) {
			String name = classInfo.getName();
			if (filter != null && !filter.isBlank()) {
				String normalizedFilter = filter.replace('.', '/');
				if (!name.contains(normalizedFilter)) continue;
			}
			allMatched.add(name);
		}

		int totalMatched = allMatched.size();
		// Apply pagination
		int fromIndex = Math.min(offset, totalMatched);
		int toIndex = Math.min(fromIndex + limit, totalMatched);
		List<String> page = allMatched.subList(fromIndex, toIndex);

		JsonObject data = new JsonObject();
		data.addProperty("totalClasses", primaryBundle.size());
		data.addProperty("totalMatched", totalMatched);
		data.addProperty("offset", fromIndex);
		data.addProperty("returnedCount", page.size());
		data.addProperty("hasMore", toIndex < totalMatched);
		data.add("classes", JsonUtil.gson().toJsonTree(page));
		logger.info("[MCP] Listed classes: {}/{} (filter='{}', offset={}, limit={})",
				page.size(), totalMatched, filter, offset, limit);
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	/**
	 * POST /workspace/class-info  { "className": "com/example/Foo" }
	 */
	public void handleClassInfo(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);

		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		ClassInfo classInfo = classPath.getValue();
		JsonObject data = new JsonObject();
		data.addProperty("name", classInfo.getName());
		data.addProperty("superName", classInfo.getSuperName());
		data.addProperty("accessFlags", classInfo.getAccess());

		List<String> interfaces = new ArrayList<>(classInfo.getInterfaces());
		data.add("interfaces", JsonUtil.gson().toJsonTree(interfaces));

		List<JsonObject> fields = new ArrayList<>();
		for (FieldMember field : classInfo.getFields()) {
			JsonObject f = new JsonObject();
			f.addProperty("name", field.getName());
			f.addProperty("descriptor", field.getDescriptor());
			f.addProperty("accessFlags", field.getAccess());
			fields.add(f);
		}
		data.add("fields", JsonUtil.gson().toJsonTree(fields));

		List<JsonObject> methods = new ArrayList<>();
		for (MethodMember method : classInfo.getMethods()) {
			JsonObject m = new JsonObject();
			m.addProperty("name", method.getName());
			m.addProperty("descriptor", method.getDescriptor());
			m.addProperty("accessFlags", method.getAccess());
			methods.add(m);
		}
		data.add("methods", JsonUtil.gson().toJsonTree(methods));

		if (classInfo.isJvmClass()) {
			JvmClassInfo jvmInfo = classInfo.asJvmClass();
			data.addProperty("version", jvmInfo.getVersion());
		}

		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	/**
	 * POST /workspace/switch  { "workspaceId": "xxx" }
	 */
	public void handleSwitch(HttpExchange exchange) throws IOException {
		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String workspaceId = JsonUtil.getString(req, "workspaceId", null);

		if (workspaceId == null || workspaceId.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("workspaceId"));
			return;
		}

		Workspace ws = registry.get(workspaceId);
		if (ws == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS, "Workspace not found: " + workspaceId,
					"Use 'list_workspaces' to see available workspace IDs."));
			return;
		}

		workspaceManager.setCurrent(ws);
		JsonObject data = new JsonObject();
		data.addProperty("workspaceId", workspaceId);
		data.addProperty("path", registry.getPath(workspaceId));
		data.addProperty("classCount", countClasses(ws));
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		logger.info("[MCP] Switched to workspace: {}", workspaceId);
	}

	/**
	 * GET /workspace/list-workspaces
	 */
	public void handleListWorkspaces(HttpExchange exchange) throws IOException {
		Workspace current = workspaceManager.getCurrent();
		List<JsonObject> workspaces = new ArrayList<>();

		for (Map.Entry<String, Workspace> entry : registry.getAll().entrySet()) {
			String id = entry.getKey();
			Workspace ws = entry.getValue();
			JsonObject item = new JsonObject();
			item.addProperty("workspaceId", id);
			item.addProperty("path", registry.getPath(id));
			item.addProperty("classCount", countClasses(ws));
			item.addProperty("isCurrent", current != null && current == ws);
			workspaces.add(item);
		}

		JsonObject data = new JsonObject();
		data.addProperty("count", workspaces.size());
		data.add("workspaces", JsonUtil.gson().toJsonTree(workspaces));
		BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
	}

	private int countClasses(Workspace workspace) {
		return workspace.getPrimaryResource().getJvmClassBundle().size();
	}

	private int countFiles(Workspace workspace) {
		return workspace.getPrimaryResource().getFileBundle().size();
	}
}
