package dev.recaf.mcp;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import dev.recaf.mcp.bridge.BridgeServer;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;

/**
 * Recaf MCP Plugin - Exposes Recaf services via an HTTP bridge server
 * so that an external MCP Server process can relay AI tool calls to Recaf.
 */
@Dependent
@PluginInformation(id = "dev.recaf.mcp.recaf-mcp-plugin", version = "1.0.0", name = "Recaf MCP Plugin", description = "MCP bridge plugin that exposes Recaf services to AI assistants via the Model Context Protocol")
public class RecafMcpPlugin implements Plugin {
	private static final Logger logger = Logging.get(RecafMcpPlugin.class);

	private final BridgeServer bridgeServer;

	@Inject
	public RecafMcpPlugin(WorkspaceManager workspaceManager,
						  ResourceImporter resourceImporter,
						  DecompilerManager decompilerManager,
						  SearchService searchService,
						  StringPredicateProvider stringPredicateProvider,
						  CallGraphService callGraphService,
						  InheritanceGraphService inheritanceGraphService,
						  MappingApplierService mappingApplierService,
						  MappingFormatManager mappingFormatManager,
						  AggregateMappingManager aggregateMappingManager) {
		this.bridgeServer = new BridgeServer(
				workspaceManager, resourceImporter, decompilerManager,
				searchService, stringPredicateProvider, callGraphService,
				inheritanceGraphService, mappingApplierService, mappingFormatManager,
				aggregateMappingManager
		);
	}

	@Override
	public void onEnable() {
		// Ensure Recaf's logging UI captures INFO level messages from our plugin
		Logging.setInterceptLevel(Level.INFO);

		try {
			bridgeServer.start();
			logger.info("========================================");
			logger.info("  Recaf MCP Plugin enabled");
			logger.info("  Bridge Server running on port 9847");
			logger.info("========================================");
		} catch (Exception e) {
			logger.error("Failed to start MCP Bridge Server", e);
		}
	}

	@Override
	public void onDisable() {
		bridgeServer.stop();
		logger.info("Recaf MCP Plugin disabled - Bridge Server stopped");
	}
}
