package dev.recaf.mcp.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.recaf.mcp.bridge.handlers.*;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * HTTP Bridge Server that exposes Recaf services over localhost HTTP.
 * The MCP Server (separate process) communicates with this bridge via HTTP.
 */
public class BridgeServer {
	private static final Logger logger = Logging.get(BridgeServer.class);
	private static final int DEFAULT_PORT = 9847;

	private HttpServer server;
	private final int port;

	// Recaf services
	private final WorkspaceManager workspaceManager;
	private final ResourceImporter resourceImporter;
	private final DecompilerManager decompilerManager;
	private final SearchService searchService;
	private final StringPredicateProvider stringPredicateProvider;
	private final CallGraphService callGraphService;
	private final InheritanceGraphService inheritanceGraphService;
	private final MappingApplierService mappingApplierService;
	private final MappingFormatManager mappingFormatManager;
	private final AggregateMappingManager aggregateMappingManager;

	public BridgeServer(WorkspaceManager workspaceManager,
						ResourceImporter resourceImporter,
						DecompilerManager decompilerManager,
						SearchService searchService,
						StringPredicateProvider stringPredicateProvider,
						CallGraphService callGraphService,
						InheritanceGraphService inheritanceGraphService,
						MappingApplierService mappingApplierService,
						MappingFormatManager mappingFormatManager,
						AggregateMappingManager aggregateMappingManager) {
		this(DEFAULT_PORT, workspaceManager, resourceImporter, decompilerManager,
				searchService, stringPredicateProvider, callGraphService,
				inheritanceGraphService, mappingApplierService, mappingFormatManager,
				aggregateMappingManager);
	}

	public BridgeServer(int port,
						WorkspaceManager workspaceManager,
						ResourceImporter resourceImporter,
						DecompilerManager decompilerManager,
						SearchService searchService,
						StringPredicateProvider stringPredicateProvider,
						CallGraphService callGraphService,
						InheritanceGraphService inheritanceGraphService,
						MappingApplierService mappingApplierService,
						MappingFormatManager mappingFormatManager,
						AggregateMappingManager aggregateMappingManager) {
		this.port = port;
		this.workspaceManager = workspaceManager;
		this.resourceImporter = resourceImporter;
		this.decompilerManager = decompilerManager;
		this.searchService = searchService;
		this.stringPredicateProvider = stringPredicateProvider;
		this.callGraphService = callGraphService;
		this.inheritanceGraphService = inheritanceGraphService;
		this.mappingApplierService = mappingApplierService;
		this.mappingFormatManager = mappingFormatManager;
		this.aggregateMappingManager = aggregateMappingManager;
	}

	public void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.setExecutor(Executors.newFixedThreadPool(4));

		// Health check
		server.createContext("/health", wrapHandler(exchange -> {
			sendJson(exchange, 200, "{\"status\":\"ok\"}");
		}));

		// Workspace endpoints
		WorkspaceHandler wsHandler = new WorkspaceHandler(workspaceManager, resourceImporter);
		server.createContext("/workspace/open", wrapHandler(wsHandler::handleOpen));
		server.createContext("/workspace/close", wrapHandler(wsHandler::handleClose));
		server.createContext("/workspace/info", wrapHandler(wsHandler::handleInfo));
		server.createContext("/workspace/classes", wrapHandler(wsHandler::handleListClasses));
		server.createContext("/workspace/class-info", wrapHandler(wsHandler::handleClassInfo));

		// Decompile endpoints
		DecompileHandler decompHandler = new DecompileHandler(workspaceManager, decompilerManager);
		server.createContext("/decompile", wrapHandler(decompHandler::handle));

		// Search endpoints
		SearchHandler searchHandler = new SearchHandler(workspaceManager, searchService, stringPredicateProvider);
		server.createContext("/search", wrapHandler(searchHandler::handle));

		// Analysis endpoints
		AnalysisHandler analysisHandler = new AnalysisHandler(workspaceManager, callGraphService, inheritanceGraphService);
		server.createContext("/analysis/call-graph", wrapHandler(analysisHandler::handleCallGraph));
		server.createContext("/analysis/inheritance", wrapHandler(analysisHandler::handleInheritance));

		// Mapping endpoints
		MappingHandler mappingHandler = new MappingHandler(workspaceManager, mappingApplierService,
				mappingFormatManager, aggregateMappingManager);
		server.createContext("/mapping/rename", wrapHandler(mappingHandler::handleRename));
		server.createContext("/mapping/export", wrapHandler(mappingHandler::handleExport));

		server.start();
		logger.info("MCP Bridge Server started on port {}", port);
	}

	public void stop() {
		if (server != null) {
			server.stop(1);
			logger.info("MCP Bridge Server stopped");
		}
	}

	/**
	 * Wraps a handler with request logging and error handling.
	 */
	private HttpHandler wrapHandler(HttpHandler handler) {
		return exchange -> {
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			logger.info("[MCP Bridge] {} {}", method, path);
			exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
			try {
				long start = System.currentTimeMillis();
				handler.handle(exchange);
				long elapsed = System.currentTimeMillis() - start;
				logger.info("[MCP Bridge] {} {} completed in {}ms", method, path, elapsed);
			} catch (Exception e) {
				logger.error("[MCP Bridge] {} {} failed: {}", method, path, e.getMessage(), e);
				sendJson(exchange, 500, JsonUtil.errorResponse(e.getMessage()));
			}
		};
	}

	/**
	 * Read the request body as a string.
	 */
	public static String readBody(HttpExchange exchange) throws IOException {
		return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
	}

	/**
	 * Send a JSON response.
	 */
	public static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}
