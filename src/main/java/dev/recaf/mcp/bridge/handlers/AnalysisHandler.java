package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.ClassMethodsContainer;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.*;

/**
 * Handles call graph and inheritance analysis requests.
 */
public class AnalysisHandler {
	private static final Logger logger = Logging.get(AnalysisHandler.class);

	private final WorkspaceManager workspaceManager;
	private final CallGraphService callGraphService;
	private final InheritanceGraphService inheritanceGraphService;

	public AnalysisHandler(WorkspaceManager workspaceManager,
						   CallGraphService callGraphService,
						   InheritanceGraphService inheritanceGraphService) {
		this.workspaceManager = workspaceManager;
		this.callGraphService = callGraphService;
		this.inheritanceGraphService = inheritanceGraphService;
	}

	/**
	 * POST /analysis/call-graph  { "className": "com/example/Foo", "methodName": "main", "depth": 3 }
	 */
	public void handleCallGraph(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String methodName = JsonUtil.getString(req, "methodName", null);
		int depth = JsonUtil.getInt(req, "depth", 3);

		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing 'className' parameter"));
			return;
		}

		String normalizedName = className.replace('.', '/');
		ClassPathNode classPath = workspace.findJvmClass(normalizedName);
		if (classPath == null) {
			BridgeServer.sendJson(exchange, 404, JsonUtil.errorResponse("Class not found: " + className));
			return;
		}

		try {
			CallGraph graph = callGraphService.getCurrentWorkspaceCallGraph();
			if (graph == null) {
				graph = callGraphService.newCallGraph(workspace);
				graph.initialize();
			}

			JvmClassInfo classInfo = classPath.getValue().asJvmClass();
			ClassMethodsContainer container = graph.getClassMethodsContainer(classInfo);

			if (container == null) {
				BridgeServer.sendJson(exchange, 404, JsonUtil.errorResponse("No call graph data for class: " + className));
				return;
			}

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);

			List<JsonObject> methodGraphs = new ArrayList<>();

			if (methodName != null && !methodName.isBlank()) {
				// Find specific method(s) matching the name
				for (MethodVertex vertex : container.getVertices()) {
					if (vertex.getMethod().name().equals(methodName)) {
						methodGraphs.add(buildMethodGraph(vertex, depth, new HashSet<>()));
					}
				}
			} else {
				// All methods in the class
				for (MethodVertex vertex : container.getVertices()) {
					methodGraphs.add(buildMethodGraph(vertex, depth, new HashSet<>()));
				}
			}

			data.add("methods", JsonUtil.gson().toJsonTree(methodGraphs));
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("Call graph analysis failed for '{}'", className, e);
			BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("Call graph analysis failed: " + e.getMessage()));
		}
	}

	private JsonObject buildMethodGraph(MethodVertex vertex, int depth, Set<String> visited) {
		MethodRef ref = vertex.getMethod();
		String key = ref.owner() + "." + ref.name() + ref.desc();

		JsonObject node = new JsonObject();
		node.addProperty("owner", ref.owner());
		node.addProperty("name", ref.name());
		node.addProperty("descriptor", ref.desc());

		if (depth <= 0 || visited.contains(key)) {
			node.addProperty("truncated", true);
			return node;
		}

		visited.add(key);

		// Outgoing calls
		List<JsonObject> calls = new ArrayList<>();
		for (MethodVertex callee : vertex.getCalls()) {
			calls.add(buildMethodGraph(callee, depth - 1, visited));
		}
		node.add("calls", JsonUtil.gson().toJsonTree(calls));

		// Incoming callers
		List<JsonObject> callers = new ArrayList<>();
		for (MethodVertex caller : vertex.getCallers()) {
			MethodRef callerRef = caller.getMethod();
			JsonObject callerNode = new JsonObject();
			callerNode.addProperty("owner", callerRef.owner());
			callerNode.addProperty("name", callerRef.name());
			callerNode.addProperty("descriptor", callerRef.desc());
			callers.add(callerNode);
		}
		node.add("callers", JsonUtil.gson().toJsonTree(callers));

		visited.remove(key);
		return node;
	}

	/**
	 * POST /analysis/inheritance  { "className": "com/example/Foo", "direction": "both|parents|children" }
	 */
	public void handleInheritance(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, JsonUtil.errorResponse("No workspace is open"));
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String className = JsonUtil.getString(req, "className", null);
		String direction = JsonUtil.getString(req, "direction", "both");

		if (className == null || className.isBlank()) {
			BridgeServer.sendJson(exchange, 400, JsonUtil.errorResponse("Missing 'className' parameter"));
			return;
		}

		String normalizedName = className.replace('.', '/');

		try {
			InheritanceGraph graph = inheritanceGraphService.getCurrentWorkspaceInheritanceGraph();
			if (graph == null) {
				graph = inheritanceGraphService.newInheritanceGraph(workspace);
			}

			InheritanceVertex vertex = graph.getVertex(normalizedName);
			if (vertex == null) {
				BridgeServer.sendJson(exchange, 404, JsonUtil.errorResponse("Class not found in inheritance graph: " + className));
				return;
			}

			JsonObject data = new JsonObject();
			data.addProperty("className", normalizedName);

			if ("parents".equalsIgnoreCase(direction) || "both".equalsIgnoreCase(direction)) {
				List<String> parents = new ArrayList<>();
				for (InheritanceVertex parent : vertex.getAllParents()) {
					parents.add(parent.getName());
				}
				data.add("parents", JsonUtil.gson().toJsonTree(parents));
			}

			if ("children".equalsIgnoreCase(direction) || "both".equalsIgnoreCase(direction)) {
				List<String> children = new ArrayList<>();
				for (InheritanceVertex child : vertex.getAllChildren()) {
					children.add(child.getName());
				}
				data.add("children", JsonUtil.gson().toJsonTree(children));
			}

			// Direct parents/children for clarity
			List<String> directParents = new ArrayList<>();
			for (InheritanceVertex parent : vertex.getParents()) {
				directParents.add(parent.getName());
			}
			data.add("directParents", JsonUtil.gson().toJsonTree(directParents));

			List<String> directChildren = new ArrayList<>();
			for (InheritanceVertex child : vertex.getChildren()) {
				directChildren.add(child.getName());
			}
			data.add("directChildren", JsonUtil.gson().toJsonTree(directChildren));

			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("Inheritance analysis failed for '{}'", className, e);
			BridgeServer.sendJson(exchange, 500, JsonUtil.errorResponse("Inheritance analysis failed: " + e.getMessage()));
		}
	}
}
