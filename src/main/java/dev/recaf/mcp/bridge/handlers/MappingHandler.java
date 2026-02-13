package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Handles symbol renaming and mapping export requests.
 */
public class MappingHandler {
	private static final Logger logger = Logging.get(MappingHandler.class);

	private final WorkspaceManager workspaceManager;
	private final MappingApplierService mappingApplierService;
	private final MappingFormatManager mappingFormatManager;
	private final AggregateMappingManager aggregateMappingManager;

	public MappingHandler(WorkspaceManager workspaceManager,
						  MappingApplierService mappingApplierService,
						  MappingFormatManager mappingFormatManager,
						  AggregateMappingManager aggregateMappingManager) {
		this.workspaceManager = workspaceManager;
		this.mappingApplierService = mappingApplierService;
		this.mappingFormatManager = mappingFormatManager;
		this.aggregateMappingManager = aggregateMappingManager;
	}

	/**
	 * POST /mapping/rename  { "type": "class|field|method", "oldName": "a", "newName": "Example", "className": "com/example/Foo" }
	 */
	public void handleRename(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String type = JsonUtil.getString(req, "type", null);
		String oldName = JsonUtil.getString(req, "oldName", null);
		String newName = JsonUtil.getString(req, "newName", null);
		String className = JsonUtil.getString(req, "className", null);
		String descriptor = JsonUtil.getString(req, "descriptor", null);

		if (type == null || oldName == null || newName == null) {
			BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing required parameters: type, oldName, newName"));
			return;
		}

		try {
			IntermediateMappings mappings = new IntermediateMappings();

			switch (type.toLowerCase()) {
				case "class" -> {
					String normalizedOld = oldName.replace('.', '/');
					String normalizedNew = newName.replace('.', '/');
					mappings.addClass(normalizedOld, normalizedNew);
				}
				case "field" -> {
					if (className == null) {
						BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing 'className' for field rename"));
						return;
					}
					String normalizedClass = className.replace('.', '/');
					mappings.addField(normalizedClass, descriptor, oldName, newName);
				}
				case "method" -> {
					if (className == null) {
						BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing 'className' for method rename"));
						return;
					}
					String normalizedClass = className.replace('.', '/');
					mappings.addMethod(normalizedClass, descriptor, oldName, newName);
				}
				default -> {
					BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Invalid type: " + type + ". Use: class, field, method"));
					return;
				}
			}

			var applier = mappingApplierService.inCurrentWorkspace();
			if (applier == null) {
				BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("No workspace applier available"));
				return;
			}

			MappingResults results = applier.applyToPrimaryResource(mappings);
			results.apply();

			JsonObject data = new JsonObject();
			data.addProperty("type", type);
			data.addProperty("oldName", oldName);
			data.addProperty("newName", newName);
			data.addProperty("affectedClasses", results.getMappedClasses().size());
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Renamed {} '{}' -> '{}' ({} classes affected)", type, oldName, newName, results.getMappedClasses().size());
		} catch (Exception e) {
			logger.error("Rename failed: {} '{}' -> '{}'", type, oldName, newName, e);
			BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("Rename failed: " + e.getMessage()));
		}
	}

	/**
	 * POST /mapping/export  { "format": "TinyV1", "outputPath": "/path/to/output.tiny" }
	 */
	public void handleExport(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String format = JsonUtil.getString(req, "format", null);
		String outputPath = JsonUtil.getString(req, "outputPath", null);

		if (format == null || outputPath == null) {
			// List available formats if none specified
			if (format == null && outputPath == null) {
				Set<String> formats = mappingFormatManager.getMappingFileFormats();
				JsonObject data = new JsonObject();
				data.add("availableFormats", JsonUtil.gson().toJsonTree(formats));
				BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
				return;
			}
			BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing required parameters: format, outputPath"));
			return;
		}

		try {
			AggregatedMappings aggMappings = aggregateMappingManager.getAggregatedMappings();
			if (aggMappings == null) {
				BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No mappings available to export"));
				return;
			}

			MappingFileFormat fileFormat = mappingFormatManager.createFormatInstance(format);
			if (fileFormat == null) {
				Set<String> formats = mappingFormatManager.getMappingFileFormats();
				BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse(
						"Unknown format: " + format + ". Available: " + formats));
				return;
			}

			String exportText = fileFormat.exportText(aggMappings);
			Path path = Paths.get(outputPath);
			Files.writeString(path, exportText);

			JsonObject data = new JsonObject();
			data.addProperty("format", format);
			data.addProperty("outputPath", outputPath);
			data.addProperty("size", exportText.length());
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Exported mappings: format='{}', path='{}'", format, outputPath);
		} catch (Exception e) {
			logger.error("Mapping export failed", e);
			BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("Export failed: " + e.getMessage()));
		}
	}
}
