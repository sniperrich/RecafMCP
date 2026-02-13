package dev.recaf.mcp.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client that communicates with the Recaf Bridge Server.
 * Used by the MCP Server process to relay tool calls to Recaf.
 */
public class BridgeClient {
	private final String baseUrl;
	private final HttpClient httpClient;

	public BridgeClient(String host, int port) {
		this.baseUrl = "http://" + host + ":" + port;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	public BridgeClient() {
		this("127.0.0.1", 9847);
	}

	/**
	 * Send a POST request with JSON body to the bridge.
	 */
	public String post(String path, String jsonBody) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.timeout(Duration.ofSeconds(60))
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return response.body();
	}

	/**
	 * Send a GET request to the bridge.
	 */
	public String get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.GET()
				.timeout(Duration.ofSeconds(30))
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return response.body();
	}

	/**
	 * Check if the bridge is reachable.
	 */
	public boolean isAvailable() {
		try {
			String resp = get("/health");
			JsonObject obj = JsonParser.parseString(resp).getAsJsonObject();
			return "ok".equals(obj.get("status").getAsString());
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Extract the "data" field from a bridge response, or throw if error.
	 */
	public String extractData(String response) {
		JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
		String status = obj.has("status") ? obj.get("status").getAsString() : "unknown";
		if ("error".equals(status)) {
			String msg = obj.has("message") ? obj.get("message").getAsString() : "Unknown bridge error";
			return "{\"error\": \"" + msg.replace("\"", "\\\"") + "\"}";
		}
		if (obj.has("data")) {
			return obj.get("data").toString();
		}
		return response;
	}
}
