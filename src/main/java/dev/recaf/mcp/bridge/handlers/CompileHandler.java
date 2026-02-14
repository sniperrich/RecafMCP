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
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.compile.CompileMap;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArguments;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.io.IOException;
import java.util.List;

/**
 * Handles Java source compilation via Recaf's JavacCompiler service.
 */
public class CompileHandler {
	private static final Logger logger = Logging.get(CompileHandler.class);

	private final WorkspaceManager workspaceManager;
	private final JavacCompiler javacCompiler;

	public CompileHandler(WorkspaceManager workspaceManager, JavacCompiler javacCompiler) {
		this.workspaceManager = workspaceManager;
		this.javacCompiler = javacCompiler;
	}

	/**
	 * POST /compile  { "className": "com.example.Foo", "source": "...", "targetVersion": 17, "debug": true }
	 * Compile Java source and apply to workspace.
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String source = JsonUtil.getString(req, "source", null);
		int targetVersion = JsonUtil.getInt(req, "targetVersion", -1);
		boolean debug = req.has("debug") ? req.get("debug").getAsBoolean() : true;

		if (className == null || source == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "source"));
			return;
		}

		// Normalize class name: accept both dots and slashes, JavacCompiler expects dots
		String dotName = className.replace('/', '.');
		String slashName = className.replace('.', '/');

		try {
			// Build compiler arguments
			JavacArgumentsBuilder builder = new JavacArgumentsBuilder()
					.withClassName(dotName)
					.withClassSource(source);

			if (targetVersion > 0) {
				builder.withVersionTarget(targetVersion);
			}

			if (debug) {
				builder.withDebugVariables(true)
						.withDebugLineNumbers(true)
						.withDebugSourceName(true);
			}

			JavacArguments arguments = builder.build();

			// Compile
			CompilerResult result = javacCompiler.compile(arguments, workspace, null);

			if (!result.wasSuccess()) {
				// Return compilation errors
				List<CompilerDiagnostic> diagnostics = result.getDiagnostics();
				JsonObject data = new JsonObject();
				data.addProperty("compiled", false);
				JsonArray diagArray = new JsonArray();
				if (diagnostics != null) {
					for (CompilerDiagnostic diag : diagnostics) {
						JsonObject d = new JsonObject();
						d.addProperty("line", diag.line());
						d.addProperty("column", diag.column());
						d.addProperty("message", diag.message());
						d.addProperty("level", diag.level().name());
						diagArray.add(d);
					}
				}
				data.add("diagnostics", diagArray);
				BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
				logger.info("[MCP] Compilation failed for {}: {} diagnostics", dotName,
						diagnostics != null ? diagnostics.size() : 0);
				return;
			}

			// Get compiled bytecode and apply to workspace
			CompileMap compilations = result.getCompilations();
			byte[] compiledBytecode = compilations != null ? compilations.get(dotName) : null;
			if (compiledBytecode == null) {
				BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
						ErrorMapper.COMPILE_FAILED,
						"Compilation succeeded but no bytecode produced for: " + dotName,
						"This may indicate an inner class or naming issue."));
				return;
			}

			JvmClassInfo newClassInfo = new JvmClassInfoBuilder(compiledBytecode).build();
			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(newClassInfo);

			JsonObject data = new JsonObject();
			data.addProperty("compiled", true);
			data.addProperty("applied", true);
			data.addProperty("className", slashName);
			data.addProperty("bytecodeSize", compiledBytecode.length);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Compiled and applied: {} ({} bytes)", slashName, compiledBytecode.length);
		} catch (Exception e) {
			logger.error("Compilation failed for {}", className, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.COMPILE_FAILED, "Compilation failed: " + e.getMessage(),
					"Check the Java source for errors and ensure the compiler is available."));
		}
	}
}
