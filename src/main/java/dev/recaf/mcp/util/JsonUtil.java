package dev.recaf.mcp.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Shared Gson utility for JSON serialization/deserialization across the plugin.
 */
public final class JsonUtil {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private JsonUtil() {}

	public static Gson gson() {
		return GSON;
	}

	public static String toJson(Object obj) {
		return GSON.toJson(obj);
	}

	public static <T> T fromJson(String json, Class<T> clazz) {
		return GSON.fromJson(json, clazz);
	}

	public static JsonObject parseObject(String json) {
		JsonElement el = JsonParser.parseString(json);
		return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
	}

	public static String getString(JsonObject obj, String key, String defaultValue) {
		if (obj.has(key) && !obj.get(key).isJsonNull()) {
			return obj.get(key).getAsString();
		}
		return defaultValue;
	}

	public static int getInt(JsonObject obj, String key, int defaultValue) {
		if (obj.has(key) && !obj.get(key).isJsonNull()) {
			return obj.get(key).getAsInt();
		}
		return defaultValue;
	}

	/**
	 * Build a simple JSON response with status and data.
	 */
	public static String successResponse(Object data) {
		JsonObject resp = new JsonObject();
		resp.addProperty("status", "ok");
		resp.add("data", GSON.toJsonTree(data));
		return GSON.toJson(resp);
	}

	public static String errorResponse(String message) {
		JsonObject resp = new JsonObject();
		resp.addProperty("status", "error");
		resp.addProperty("message", message);
		return GSON.toJson(resp);
	}
}
