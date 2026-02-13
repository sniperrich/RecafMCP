package dev.recaf.mcp.bridge.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import dev.recaf.mcp.bridge.BridgeServer;
import dev.recaf.mcp.util.ErrorMapper;
import dev.recaf.mcp.util.JsonUtil;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.*;

/**
 * Handles search requests: string search, class/method/field reference search.
 */
public class SearchHandler {
	private static final Logger logger = Logging.get(SearchHandler.class);

	private final WorkspaceManager workspaceManager;
	private final SearchService searchService;
	private final StringPredicateProvider stringPredicateProvider;

	public SearchHandler(WorkspaceManager workspaceManager,
						 SearchService searchService,
						 StringPredicateProvider stringPredicateProvider) {
		this.workspaceManager = workspaceManager;
		this.searchService = searchService;
		this.stringPredicateProvider = stringPredicateProvider;
	}

	/**
	 * POST /search  { "query": "hello", "type": "string|class|method|field|declaration" }
	 */
	public void handle(HttpExchange exchange) throws IOException {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			BridgeServer.sendJson(exchange, 200, ErrorMapper.noWorkspace());
			return;
		}

		String body = BridgeServer.readBody(exchange);
		JsonObject req = JsonUtil.parseObject(body);
		String query = JsonUtil.getString(req, "query", null);
		String type = JsonUtil.getString(req, "type", "string");

		if (query == null || query.isBlank()) {
			BridgeServer.sendJson(exchange, 400, ErrorMapper.missingParam("query"));
			return;
		}

		try {
			Results results;
			String normalizedQuery = query.replace('.', '/');
			logger.info("[MCP] Searching: query='{}', type='{}'", query, type);

			switch (type.toLowerCase()) {
				case "class" -> {
					results = searchService.search(workspace, new ReferenceQuery(
							stringPredicateProvider.newContainsPredicate(normalizedQuery),
							null, null));
				}
				case "method" -> {
					results = searchService.search(workspace, new ReferenceQuery(
							null,
							stringPredicateProvider.newContainsPredicate(query),
							null));
				}
				case "field" -> {
					results = searchService.search(workspace, new ReferenceQuery(
							null,
							stringPredicateProvider.newContainsPredicate(query),
							null));
				}
				case "declaration" -> {
					results = searchService.search(workspace, new DeclarationQuery(
							stringPredicateProvider.newContainsPredicate(normalizedQuery),
							stringPredicateProvider.newContainsPredicate(query),
							null));
				}
				default -> {
					results = searchService.search(workspace, new StringQuery(
							stringPredicateProvider.newContainsPredicate(query)));
				}
			}

			List<JsonObject> resultList = new ArrayList<>();
			int count = 0;
			int maxResults = JsonUtil.getInt(req, "maxResults", 100);

			for (Result<?> result : results) {
				if (count >= maxResults) break;

				JsonObject item = new JsonObject();
				var path = result.getPath();
				ClassInfo classValue = path.getValueOfType(ClassInfo.class);
				if (classValue != null) {
					item.addProperty("class", classValue.getName());
				}
				ClassMember memberValue = path.getValueOfType(ClassMember.class);
				if (memberValue != null) {
					item.addProperty("member", memberValue.getName());
					item.addProperty("descriptor", memberValue.getDescriptor());
				}
				item.addProperty("pathType", path.getClass().getSimpleName());
				resultList.add(item);
				count++;
			}

			JsonObject data = new JsonObject();
			data.addProperty("query", query);
			data.addProperty("type", type);
			data.addProperty("count", resultList.size());
			data.add("results", JsonUtil.gson().toJsonTree(resultList));
			BridgeServer.sendJson(exchange, 200, JsonUtil.successResponse(data));
		} catch (Exception e) {
			logger.error("Search failed for query '{}' type '{}'", query, type, e);
			BridgeServer.sendJson(exchange, 500, ErrorMapper.mapException("Search", e));
		}
	}
}
