package dev.recaf.mcp.bridge;

import software.coley.recaf.workspace.model.Workspace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-workspace registry: stores ID → Workspace mappings.
 * Allows multiple JARs to be opened simultaneously and switched between.
 */
public class WorkspaceRegistry {
	private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
	private final Map<String, String> paths = new ConcurrentHashMap<>();
	private final AtomicInteger counter = new AtomicInteger(0);

	/**
	 * Generate a unique workspace ID based on the file name.
	 */
	public String generateId(String filePath) {
		String fileName = Paths.get(filePath).getFileName().toString();
		// Remove extension
		int dot = fileName.lastIndexOf('.');
		String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
		// Sanitize: keep only alphanumeric, dash, underscore
		baseName = baseName.replaceAll("[^a-zA-Z0-9_-]", "_");
		int seq = counter.incrementAndGet();
		return baseName + "-" + seq;
	}

	/**
	 * Register a workspace and return its ID.
	 */
	public String register(String filePath, Workspace workspace) {
		String id = generateId(filePath);
		workspaces.put(id, workspace);
		paths.put(id, filePath);
		return id;
	}

	/**
	 * Get a workspace by ID.
	 */
	public Workspace get(String id) {
		return workspaces.get(id);
	}

	/**
	 * Get the file path for a workspace ID.
	 */
	public String getPath(String id) {
		return paths.get(id);
	}

	/**
	 * Remove a workspace by ID.
	 */
	public Workspace remove(String id) {
		paths.remove(id);
		return workspaces.remove(id);
	}

	/**
	 * Find the ID for a given workspace instance.
	 */
	public String findId(Workspace workspace) {
		for (Map.Entry<String, Workspace> entry : workspaces.entrySet()) {
			if (entry.getValue() == workspace) {
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Get all registered workspaces.
	 */
	public Map<String, Workspace> getAll() {
		return Collections.unmodifiableMap(workspaces);
	}

	/**
	 * Clear all registered workspaces.
	 */
	public void clear() {
		workspaces.clear();
		paths.clear();
	}

	/**
	 * Get the number of registered workspaces.
	 */
	public int size() {
		return workspaces.size();
	}
}
