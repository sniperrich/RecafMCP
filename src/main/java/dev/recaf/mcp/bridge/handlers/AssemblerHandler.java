package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipeline;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles JASM assembler operations: disassemble class/method, assemble and apply.
 */
public class AssemblerHandler {
	private static final Logger logger = Logging.get(AssemblerHandler.class);

	private final WorkspaceManager workspaceManager;
	private final AssemblerPipelineManager assemblerPipelineManager;

	public AssemblerHandler(WorkspaceManager workspaceManager,
							AssemblerPipelineManager assemblerPipelineManager) {
		this.workspaceManager = workspaceManager;
		this.assemblerPipelineManager = assemblerPipelineManager;
	}

	/**
	 * POST /disassemble  { "className": "com/example/Foo", "maxChars": 120000 }
	 * Disassemble a class into JASM text.
	 */
	public void handleDisassemble(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		int maxChars = JsonUtil.getInt(req, "maxChars", 120000);

		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		try {
			JvmAssemblerPipeline pipeline = assemblerPipelineManager.newJvmAssemblerPipeline(workspace);
			String disassembly = pipeline.disassemble(classPath).get();

			boolean truncated = false;
			if (disassembly.length() > maxChars) {
				disassembly = disassembly.substring(0, maxChars);
				truncated = true;
			}

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("disassembly", disassembly);
			data.addProperty("truncated", truncated);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Disassembled class: {} ({} chars, truncated={})", normalizedName, disassembly.length(), truncated);
		} catch (Exception e) {
			logger.error("Disassemble failed for {}", className, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.ASSEMBLER_FAILED, "Disassemble failed: " + e.getMessage(),
					"Check that the class exists and is a valid JVM class."));
		}
	}

	/**
	 * POST /assemble  { "className": "com/example/Foo", "source": "..." }
	 * Assemble JASM source and apply to workspace.
	 */
	public void handleAssemble(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String source = JsonUtil.getString(req, "source", null);

		if (className == null || source == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "source"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		try {
			JvmAssemblerPipeline pipeline = assemblerPipelineManager.newJvmAssemblerPipeline(workspace);

			// Three-stage assembly: tokenize → fullParse → assembleAndWrap
			var tokenResult = pipeline.tokenize(source, "<mcp-input>");
			if (tokenResult.hasErr()) {
				BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
						ErrorMapper.ASSEMBLER_FAILED, "Tokenize failed: " + formatErrors(tokenResult.errors()),
						"Check the JASM syntax of the source."));
				return;
			}

			var parseResult = pipeline.fullParse(tokenResult.get());
			if (parseResult.hasErr()) {
				BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
						ErrorMapper.ASSEMBLER_FAILED, "Parse failed: " + formatErrors(parseResult.errors()),
						"Check the JASM syntax of the source."));
				return;
			}

			var assembleResult = pipeline.assembleAndWrap(parseResult.get(), classPath);
			if (assembleResult.hasErr()) {
				BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
						ErrorMapper.ASSEMBLER_FAILED, "Assembly failed: " + formatErrors(assembleResult.errors()),
						"Check the JASM source for errors."));
				return;
			}

			JvmClassInfo assembled = assembleResult.get();

			// Verify class name matches
			if (!assembled.getName().equals(normalizedName)) {
				BridgeServer.sendJson(exchange, 400, ErrorMapper.errorResponse(
						ErrorMapper.ASSEMBLER_FAILED,
						"Class name mismatch: expected '" + normalizedName + "' but assembled '" + assembled.getName() + "'",
						"Ensure the class name in the JASM source matches the className parameter."));
				return;
			}

			// Apply to workspace
			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(assembled);

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("assembled", true);
			data.addProperty("applied", true);
			data.addProperty("bytecodeSize", assembled.getBytecode().length);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Assembled and applied class: {} ({} bytes)", normalizedName, assembled.getBytecode().length);
		} catch (Exception e) {
			logger.error("Assemble failed for {}", className, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.ASSEMBLER_FAILED, "Assemble failed: " + e.getMessage(),
					"Check the JASM source for syntax errors."));
		}
	}

	/**
	 * POST /disassemble/method  { "className": "com/example/Foo", "methodName": "bar", "methodDesc": "(I)V", "maxChars": 120000 }
	 * Disassemble a single method into JASM text.
	 */
	public void handleMethodDisassemble(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String methodName = JsonUtil.getString(req, "methodName", null);
		String methodDesc = JsonUtil.getString(req, "methodDesc", null);
		int maxChars = JsonUtil.getInt(req, "maxChars", 120000);

		if (className == null || methodName == null || methodDesc == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "methodName", "methodDesc"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		try {
			ClassInfo classInfo = classPath.getValue();

			// Find the method member
			MethodMember targetMethod = null;
			for (MethodMember mm : classInfo.getMethods()) {
				if (mm.getName().equals(methodName) && mm.getDescriptor().equals(methodDesc)) {
					targetMethod = mm;
					break;
				}
			}

			if (targetMethod == null) {
				BridgeServer.sendJson(exchange, 404, ErrorMapper.memberNotFound(className, methodName + methodDesc));
				return;
			}

			// Create member path node for the method
			ClassMemberPathNode memberPath = classPath.child(targetMethod);

			JvmAssemblerPipeline pipeline = assemblerPipelineManager.newJvmAssemblerPipeline(workspace);
			String disassembly = pipeline.disassemble(memberPath).get();

			boolean truncated = false;
			if (disassembly.length() > maxChars) {
				disassembly = disassembly.substring(0, maxChars);
				truncated = true;
			}

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("methodName", methodName);
			data.addProperty("methodDesc", methodDesc);
			data.addProperty("disassembly", disassembly);
			data.addProperty("truncated", truncated);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Disassembled method {}.{}{} ({} chars)", normalizedName, methodName, methodDesc, disassembly.length());
		} catch (Exception e) {
			logger.error("Method disassemble failed for {}.{}{}", className, methodName, methodDesc, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.errorResponse(
					ErrorMapper.ASSEMBLER_FAILED, "Method disassemble failed: " + e.getMessage(),
					"Check that the method exists and is valid."));
		}
	}

	private static String formatErrors(List<?> errors) {
		if (errors == null || errors.isEmpty()) return "unknown error";
		return errors.stream()
				.map(Object::toString)
				.collect(Collectors.joining("; "));
	}
}
