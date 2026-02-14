package dev.recaf.mcp.util;

import com.google.gson.JsonObject;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.TimeoutException;

/**
 * Structured error mapping: error codes + friendly messages + suggestions.
 */
public final class ErrorMapper {

	// Error codes
	public static final String NO_WORKSPACE = "NO_WORKSPACE";
	public static final String CLASS_NOT_FOUND = "CLASS_NOT_FOUND";
	public static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
	public static final String INVALID_PARAMS = "INVALID_PARAMS";
	public static final String DECOMPILE_FAILED = "DECOMPILE_FAILED";
	public static final String DECOMPILE_TIMEOUT = "DECOMPILE_TIMEOUT";
	public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
	public static final String ASSEMBLER_FAILED = "ASSEMBLER_FAILED";
	public static final String COMPILE_FAILED = "COMPILE_FAILED";
	public static final String COMPILER_UNAVAILABLE = "COMPILER_UNAVAILABLE";
	public static final String PATCH_FAILED = "PATCH_FAILED";
	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private ErrorMapper() {}

	/**
	 * Build a structured error response JSON string.
	 */
	public static String errorResponse(String code, String message, String suggestion) {
		JsonObject resp = new JsonObject();
		resp.addProperty("status", "error");
		resp.addProperty("code", code);
		resp.addProperty("message", message);
		if (suggestion != null) {
			resp.addProperty("suggestion", suggestion);
		}
		return JsonUtil.gson().toJson(resp);
	}

	/**
	 * Map an exception to a structured error response based on the operation context.
	 */
	public static String mapException(String operation, Exception e) {
		if (e instanceof TimeoutException) {
			return errorResponse(DECOMPILE_TIMEOUT,
					operation + " timed out",
					"The operation exceeded the time limit. Try a simpler target or increase timeout.");
		}
		if (e instanceof FileNotFoundException || e instanceof NoSuchFileException) {
			return errorResponse(FILE_NOT_FOUND,
					operation + " failed: file not found — " + e.getMessage(),
					"Check that the file path is correct and the file exists.");
		}
		if (e instanceof IllegalArgumentException) {
			return errorResponse(INVALID_PARAMS,
					operation + " failed: " + e.getMessage(),
					"Check the parameter values and try again.");
		}
		return errorResponse(INTERNAL_ERROR,
				operation + " failed: " + e.getMessage(),
				"This is an unexpected error. Check Recaf logs for details.");
	}

	// ==================== Convenience methods ====================

	public static String noWorkspace() {
		return errorResponse(NO_WORKSPACE,
				"No workspace is currently open",
				"Use the 'open_jar' tool to open a JAR/APK/class file first.");
	}

	public static String classNotFound(String className) {
		return errorResponse(CLASS_NOT_FOUND,
				"Class not found: " + className,
				"Use 'list_classes' to see available classes. Use '/' separators (e.g. 'com/example/Main').");
	}

	public static String memberNotFound(String className, String memberName) {
		return errorResponse(MEMBER_NOT_FOUND,
				"Member '" + memberName + "' not found in class " + className,
				"Use 'get_class_info' to see available fields and methods.");
	}

	public static String missingParam(String... names) {
		String joined = String.join(", ", names);
		return errorResponse(INVALID_PARAMS,
				"Missing required parameter(s): " + joined,
				"Provide the required parameters and try again.");
	}
}
