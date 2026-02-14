package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.patch.PatchApplier;
import software.coley.recaf.services.workspace.patch.PatchFeedback;
import software.coley.recaf.services.workspace.patch.PatchProvider;
import software.coley.recaf.services.workspace.patch.model.WorkspacePatch;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles workspace patch operations: create and apply patches.
 */
public class PatchHandler {
	private static final Logger logger = Logging.get(PatchHandler.class);

	private final WorkspaceManager workspaceManager;
	private final PatchProvider patchProvider;
	private final PatchApplier patchApplier;

	public PatchHandler(WorkspaceManager workspaceManager,
						PatchProvider patchProvider,
						PatchApplier patchApplier) {
		this.workspaceManager = workspaceManager;
		this.patchProvider = patchProvider;
		this.patchApplier = patchApplier;
	}

	/**
	 * POST /patch  { "action": "create" } or { "action": "apply", "patchJson": "..." }
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String action = JsonUtil.getString(req, "action", null);

		if (action == null || action.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("action"));
			return;
		}

		switch (action.toLowerCase()) {
			case "create" -> handleCreate(exchange, workspace);
			case "apply" -> handleApply(exchange, workspace, req);
			default -> BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
					ErrorMapper.INVALID_PARAMS,
					"Unknown patch action: " + action,
					"Use 'create' to create a patch or 'apply' to apply one."));
		}
	}

	private void handleCreate(HttpExchange exchange, Workspace workspace) throws IOException {
		try {
			WorkspacePatch patch = patchProvider.createPatch(workspace);
			String patchJson = patchProvider.serializePatch(patch);

			int assemblerPatches = patch.jvmAssemblerPatches() != null ? patch.jvmAssemblerPatches().size() : 0;
			int textFilePatches = patch.textFilePatches() != null ? patch.textFilePatches().size() : 0;
			int removals = patch.removals() != null ? patch.removals().size() : 0;

			JsonObject data = new JsonObject();
			data.addProperty("created", true);
			data.addProperty("assemblerPatches", assemblerPatches);
			data.addProperty("textFilePatches", textFilePatches);
			data.addProperty("removals", removals);
			data.addProperty("patchJson", patchJson);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Created patch: {} assembler, {} text, {} removals",
					assemblerPatches, textFilePatches, removals);
		} catch (Exception e) {
			logger.error("Patch create failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.PATCH_FAILED, "Failed to create patch: " + e.getMessage(),
					"Ensure the workspace has modifications to patch."));
		}
	}

	private void handleApply(HttpExchange exchange, Workspace workspace, JsonObject req) throws IOException {
		String patchJson = JsonUtil.getString(req, "patchJson", null);
		if (patchJson == null || patchJson.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("patchJson"));
			return;
		}

		try {
			WorkspacePatch patch = patchProvider.deserializePatch(workspace, patchJson);

			List<String> errors = new ArrayList<>();
			PatchFeedback feedback = new PatchFeedback() {
				@Override
				public void onAssemblerErrorsObserved(java.util.List<me.darknet.assembler.error.Error> errs) {
					for (var err : errs) {
						errors.add("Assembler error: " + err.toString());
					}
				}

				@Override
				public void onIncompletePathObserved(software.coley.recaf.path.PathNode<?> path) {
					errors.add("Incomplete path: " + path.toString());
				}
			};
			boolean success = patchApplier.apply(patch, feedback);

			JsonObject data = new JsonObject();
			data.addProperty("applied", success);
			if (!errors.isEmpty()) {
				JsonArray errArray = new JsonArray();
				for (String err : errors) {
					errArray.add(err);
				}
				data.add("errors", errArray);
			}
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Applied patch: success={}, errors={}", success, errors.size());
		} catch (Exception e) {
			logger.error("Patch apply failed", e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.PATCH_FAILED, "Failed to apply patch: " + e.getMessage(),
					"Check that the patch JSON is valid and matches the current workspace."));
		}
	}
}
