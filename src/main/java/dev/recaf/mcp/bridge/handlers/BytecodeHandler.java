package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.io.IOException;

/**
 * Handles bytecode editing operations: edit/add/remove methods and fields.
 * Uses ASM directly for bytecode manipulation.
 */
public class BytecodeHandler {
	private static final Logger logger = Logging.get(BytecodeHandler.class);

	private final WorkspaceManager workspaceManager;

	public BytecodeHandler(WorkspaceManager workspaceManager) {
		this.workspaceManager = workspaceManager;
	}

	/**
	 * POST /bytecode/edit-method
	 * { "className": "com/example/Foo", "methodName": "bar", "methodDesc": "(I)V",
	 *   "accessFlags": 1, "body": "JASM instructions or empty to clear" }
	 * Replaces a method's access flags. Full body editing requires assembler integration.
	 */
	public void handleEditMethod(HttpExchange exchange) throws IOException {
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
			JvmClassInfo classInfo = classPath.getValue().asJvmClass();
			byte[] original = classInfo.getBytecode();
			int newAccess = JsonUtil.getInt(req, "accessFlags", -1);

			// Use ASM to modify the method
			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			boolean[] found = {false};

			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					if (name.equals(methodName) && descriptor.equals(methodDesc)) {
						found[0] = true;
						int finalAccess = newAccess >= 0 ? newAccess : access;
						return super.visitMethod(finalAccess, name, descriptor, signature, exceptions);
					}
					return super.visitMethod(access, name, descriptor, signature, exceptions);
				}
			}, 0);

			if (!found[0]) {
				BridgeServer.sendJson(exchange, 404, ErrorMapper.memberNotFound(className, methodName + methodDesc));
				return;
			}

			byte[] modified = writer.toByteArray();
			updateClassInBundle(workspace, classInfo, modified);

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("methodName", methodName);
			data.addProperty("methodDesc", methodDesc);
			data.addProperty("modified", true);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Edited method {}.{}{}", normalizedName, methodName, methodDesc);
		} catch (Exception e) {
			logger.error("Edit method failed for {}.{}{}", className, methodName, methodDesc, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Edit method", e));
		}
	}

	/**
	 * POST /bytecode/edit-field
	 * { "className": "com/example/Foo", "fieldName": "x", "descriptor": "I", "accessFlags": 2 }
	 */
	public void handleEditField(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String fieldName = JsonUtil.getString(req, "fieldName", null);

		if (className == null || fieldName == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "fieldName"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		try {
			JvmClassInfo classInfo = classPath.getValue().asJvmClass();
			byte[] original = classInfo.getBytecode();
			int newAccess = JsonUtil.getInt(req, "accessFlags", -1);
			String newDescriptor = JsonUtil.getString(req, "descriptor", null);
			boolean[] found = {false};

			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(0);

			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					if (name.equals(fieldName)) {
						found[0] = true;
						int finalAccess = newAccess >= 0 ? newAccess : access;
						String finalDesc = newDescriptor != null ? newDescriptor : descriptor;
						return super.visitField(finalAccess, name, finalDesc, signature, value);
					}
					return super.visitField(access, name, descriptor, signature, value);
				}
			}, 0);

			if (!found[0]) {
				BridgeServer.sendJson(exchange, 404, ErrorMapper.memberNotFound(className, fieldName));
				return;
			}

			byte[] modified = writer.toByteArray();
			updateClassInBundle(workspace, classInfo, modified);

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("fieldName", fieldName);
			data.addProperty("modified", true);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Edited field {}.{}", normalizedName, fieldName);
		} catch (Exception e) {
			logger.error("Edit field failed for {}.{}", className, fieldName, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Edit field", e));
		}
	}

	/**
	 * POST /bytecode/remove-member
	 * { "className": "com/example/Foo", "memberName": "bar", "memberType": "method|field", "descriptor": "(I)V" }
	 */
	public void handleRemoveMember(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String memberName = JsonUtil.getString(req, "memberName", null);
		String memberType = JsonUtil.getString(req, "memberType", null);
		String descriptor = JsonUtil.getString(req, "descriptor", null);

		if (className == null || memberName == null || memberType == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "memberName", "memberType"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		try {
			JvmClassInfo classInfo = classPath.getValue().asJvmClass();
			byte[] original = classInfo.getBytecode();
			boolean[] removed = {false};

			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(0);

			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
					if ("method".equalsIgnoreCase(memberType) && name.equals(memberName)) {
						if (descriptor == null || descriptor.equals(desc)) {
							removed[0] = true;
							return null; // Skip this method
						}
					}
					return super.visitMethod(access, name, desc, signature, exceptions);
				}

				@Override
				public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
					if ("field".equalsIgnoreCase(memberType) && name.equals(memberName)) {
						if (descriptor == null || descriptor.equals(desc)) {
							removed[0] = true;
							return null; // Skip this field
						}
					}
					return super.visitField(access, name, desc, signature, value);
				}
			}, 0);

			if (!removed[0]) {
				BridgeServer.sendJson(exchange, 404, ErrorMapper.memberNotFound(className, memberName));
				return;
			}

			byte[] modified = writer.toByteArray();
			updateClassInBundle(workspace, classInfo, modified);

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("memberName", memberName);
			data.addProperty("memberType", memberType);
			data.addProperty("removed", true);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Removed {} {}.{}", memberType, normalizedName, memberName);
		} catch (Exception e) {
			logger.error("Remove member failed for {}.{}", className, memberName, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Remove member", e));
		}
	}

	/**
	 * POST /bytecode/add-field
	 * { "className": "com/example/Foo", "fieldName": "newField", "descriptor": "I", "accessFlags": 1 }
	 */
	public void handleAddField(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String fieldName = JsonUtil.getString(req, "fieldName", null);
		String descriptor = JsonUtil.getString(req, "descriptor", null);
		int accessFlags = JsonUtil.getInt(req, "accessFlags", Opcodes.ACC_PRIVATE);

		if (className == null || fieldName == null || descriptor == null) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("className", "fieldName", "descriptor"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, ErrorMapper.classNotFound(className));
			return;
		}

		try {
			JvmClassInfo classInfo = classPath.getValue().asJvmClass();
			byte[] original = classInfo.getBytecode();

			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(0);

			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public void visitEnd() {
					// Add the new field before ending the class
					FieldVisitor fv = super.visitField(accessFlags, fieldName, descriptor, null, null);
					if (fv != null) fv.visitEnd();
					super.visitEnd();
				}
			}, 0);

			byte[] modified = writer.toByteArray();
			updateClassInBundle(workspace, classInfo, modified);

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("fieldName", fieldName);
			data.addProperty("descriptor", descriptor);
			data.addProperty("accessFlags", accessFlags);
			data.addProperty("added", true);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Added field {}.{} {}", normalizedName, fieldName, descriptor);
		} catch (Exception e) {
			logger.error("Add field failed for {}.{}", className, fieldName, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Add field", e));
		}
	}

	/**
	 * POST /bytecode/add-method
	 * { "className": "com/example/Foo", "methodName": "newMethod", "methodDesc": "()V", "accessFlags": 1 }
	 * Creates a method with a minimal body (just RETURN).
	 */
	public void handleAddMethod(HttpExchange exchange) throws IOException {
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
		int accessFlags = JsonUtil.getInt(req, "accessFlags", Opcodes.ACC_PUBLIC);

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
			JvmClassInfo classInfo = classPath.getValue().asJvmClass();
			byte[] original = classInfo.getBytecode();

			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public void visitEnd() {
					// Add a new method with minimal body
					MethodVisitor mv = super.visitMethod(accessFlags, methodName, methodDesc, null, null);
					if (mv != null) {
						mv.visitCode();
						// Determine return type and add appropriate return instruction
						Type returnType = Type.getReturnType(methodDesc);
						switch (returnType.getSort()) {
							case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
							case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
								mv.visitInsn(Opcodes.ICONST_0);
								mv.visitInsn(Opcodes.IRETURN);
							}
							case Type.LONG -> {
								mv.visitInsn(Opcodes.LCONST_0);
								mv.visitInsn(Opcodes.LRETURN);
							}
							case Type.FLOAT -> {
								mv.visitInsn(Opcodes.FCONST_0);
								mv.visitInsn(Opcodes.FRETURN);
							}
							case Type.DOUBLE -> {
								mv.visitInsn(Opcodes.DCONST_0);
								mv.visitInsn(Opcodes.DRETURN);
							}
							default -> {
								mv.visitInsn(Opcodes.ACONST_NULL);
								mv.visitInsn(Opcodes.ARETURN);
							}
						}
						mv.visitMaxs(1, 1);
						mv.visitEnd();
					}
					super.visitEnd();
				}
			}, 0);

			byte[] modified = writer.toByteArray();
			updateClassInBundle(workspace, classInfo, modified);

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);
			data.addProperty("methodName", methodName);
			data.addProperty("methodDesc", methodDesc);
			data.addProperty("accessFlags", accessFlags);
			data.addProperty("added", true);
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
			logger.info("[MCP] Added method {}.{}{}", normalizedName, methodName, methodDesc);
		} catch (Exception e) {
			logger.error("Add method failed for {}.{}{}", className, methodName, methodDesc, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Add method", e));
		}
	}

	/**
	 * Update a class in the workspace's primary JVM class bundle.
	 */
	private void updateClassInBundle(Workspace workspace, JvmClassInfo original, byte[] newBytecode) {
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		JvmClassInfo updated = new JvmClassInfoBuilder(newBytecode).build();
		bundle.put(updated);
	}
}
