package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Handles export operations: export workspace as JAR or decompiled source.
 */
public class ExportHandler {
	private static final Logger logger = Logging.get(ExportHandler.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;

	public ExportHandler(WorkspaceManager workspaceManager, DecompilerManager decompilerManager) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	/**
	 * POST /export/jar  { "outputPath": "/path/to/output.jar" }
	 */
	public void handleExportJar(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String outputPath = JsonUtil.getString(req, "outputPath", null);

		if (outputPath == null || outputPath.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("outputPath"));
			return;
		}

		try {
			WorkspaceResource primary = workspace.getPrimaryResource();
			JvmClassBundle classBundle = primary.getJvmClassBundle();
			FileBundle fileBundle = primary.getFileBundle();

			Path outPath = Paths.get(outputPath);
			// Ensure parent directory exists
			if (outPath.getParent() != null) {
				Files.createDirectories(outPath.getParent());
			}

			int classCount = 0;
			int fileCount = 0;

			try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outPath))) {
				// Write class entries
				for (JvmClassInfo classInfo : classBundle) {
					String entryName = classInfo.getName() + ".class";
					jos.putNextEntry(new JarEntry(entryName));
					jos.write(classInfo.getBytecode());
					jos.closeEntry();
					classCount++;
				}

				// Write file entries (resources)
				for (FileInfo fileInfo : fileBundle) {
					String entryName = fileInfo.getName();
					jos.putNextEntry(new JarEntry(entryName));
					jos.write(fileInfo.getRawContent());
					jos.closeEntry();
					fileCount++;
				}
			}

			long sizeBytes = Files.size(outPath);

			JsonObject data = new JsonObject();
			data.addProperty("outputPath", outputPath);
			data.addProperty("classCount", classCount);
			data.addProperty("fileCount", fileCount);
			data.addProperty("sizeBytes", sizeBytes);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Exported JAR: {} ({} classes, {} files, {} bytes)",
					outputPath, classCount, fileCount, sizeBytes);
		} catch (Exception e) {
			logger.error("Export JAR failed: {}", outputPath, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Export JAR", e));
		}
	}

	/**
	 * POST /export/source  { "outputDir": "/path/to/src", "className": "com/example/Foo" }
	 * className is optional: if provided, exports only that class; otherwise exports all.
	 */
	public void handleExportSource(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String outputDir = JsonUtil.getString(req, "outputDir", null);
		String className = JsonUtil.getString(req, "className", null);

		if (outputDir == null || outputDir.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("outputDir"));
			return;
		}

		try {
			Path outDir = Paths.get(outputDir);
			Files.createDirectories(outDir);

			JvmClassBundle classBundle = workspace.getPrimaryResource().getJvmClassBundle();
			List<JvmClassInfo> targets = new ArrayList<>();

			if (className != null && !className.isBlank()) {
				// Export single class
				String normalizedName = className.replace('.', '/');
				var classPath = workspace.findJvmClass(normalizedName);
				if (classPath == null) {
					BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
					return;
				}
				targets.add(classPath.getValue().asJvmClass());
			} else {
				// Export all classes
				for (JvmClassInfo ci : classBundle) {
					targets.add(ci);
				}
			}

			int exported = 0;
			JsonArray errors = new JsonArray();

			for (JvmClassInfo classInfo : targets) {
				try {
					DecompileResult result = decompilerManager.decompile(workspace, classInfo)
							.get(30, TimeUnit.SECONDS);

					String source = result.getText();
					if (source == null) {
						errors.add("Failed to decompile: " + classInfo.getName());
						continue;
					}

					// Write to outputDir/com/example/Foo.java
					String relativePath = classInfo.getName() + ".java";
					Path filePath = outDir.resolve(relativePath);
					if (filePath.getParent() != null) {
						Files.createDirectories(filePath.getParent());
					}
					Files.writeString(filePath, source);
					exported++;
				} catch (Exception e) {
					errors.add("Error decompiling " + classInfo.getName() + ": " + e.getMessage());
				}
			}

			JsonObject data = new JsonObject();
			data.addProperty("outputDir", outputDir);
			data.addProperty("classesExported", exported);
			if (errors.size() > 0) {
				data.add("errors", errors);
			}
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Exported source: {} classes to {}", exported, outputDir);
		} catch (Exception e) {
			logger.error("Export source failed: {}", outputDir, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Export source", e));
		}
	}
}
