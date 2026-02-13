package dev.recaf.mcp.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple LCS-based unified diff algorithm.
 * Compares two texts line-by-line and produces unified diff output.
 */
public final class DiffUtil {

	private DiffUtil() {}

	/**
	 * Generate a unified diff between two texts.
	 *
	 * @param label1 Label for the first text (e.g. file name)
	 * @param label2 Label for the second text
	 * @param text1  First text
	 * @param text2  Second text
	 * @return Unified diff string
	 */
	public static String unifiedDiff(String label1, String label2, String text1, String text2) {
		return unifiedDiff(label1, label2, text1, text2, 3);
	}

	/**
	 * Generate a unified diff with configurable context lines.
	 */
	public static String unifiedDiff(String label1, String label2, String text1, String text2, int contextLines) {
		String[] lines1 = text1.split("\n", -1);
		String[] lines2 = text2.split("\n", -1);

		// Compute LCS table
		int[][] lcs = computeLCS(lines1, lines2);

		// Build edit script
		List<DiffLine> diffLines = buildDiffLines(lines1, lines2, lcs);

		// Generate unified diff hunks
		StringBuilder sb = new StringBuilder();
		sb.append("--- ").append(label1).append("\n");
		sb.append("+++ ").append(label2).append("\n");

		List<Hunk> hunks = buildHunks(diffLines, contextLines);
		for (Hunk hunk : hunks) {
			sb.append(hunk.toUnifiedFormat());
		}

		return sb.toString();
	}

	/**
	 * Count added lines in a diff.
	 */
	public static int countAdded(String diffText) {
		int count = 0;
		for (String line : diffText.split("\n")) {
			if (line.startsWith("+") && !line.startsWith("+++")) count++;
		}
		return count;
	}

	/**
	 * Count removed lines in a diff.
	 */
	public static int countRemoved(String diffText) {
		int count = 0;
		for (String line : diffText.split("\n")) {
			if (line.startsWith("-") && !line.startsWith("---")) count++;
		}
		return count;
	}

	// ==================== Internal ====================

	private static int[][] computeLCS(String[] a, String[] b) {
		int m = a.length, n = b.length;
		int[][] dp = new int[m + 1][n + 1];
		for (int i = m - 1; i >= 0; i--) {
			for (int j = n - 1; j >= 0; j--) {
				if (a[i].equals(b[j])) {
					dp[i][j] = dp[i + 1][j + 1] + 1;
				} else {
					dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
				}
			}
		}
		return dp;
	}

	private static List<DiffLine> buildDiffLines(String[] a, String[] b, int[][] lcs) {
		List<DiffLine> result = new ArrayList<>();
		int i = 0, j = 0;
		int lineA = 1, lineB = 1;

		while (i < a.length || j < b.length) {
			if (i < a.length && j < b.length && a[i].equals(b[j])) {
				result.add(new DiffLine(' ', a[i], lineA, lineB));
				i++; j++; lineA++; lineB++;
			} else if (j < b.length && (i >= a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
				result.add(new DiffLine('+', b[j], 0, lineB));
				j++; lineB++;
			} else if (i < a.length) {
				result.add(new DiffLine('-', a[i], lineA, 0));
				i++; lineA++;
			}
		}
		return result;
	}

	private static List<Hunk> buildHunks(List<DiffLine> diffLines, int contextLines) {
		List<Hunk> hunks = new ArrayList<>();
		if (diffLines.isEmpty()) return hunks;

		// Find change regions and group with context
		List<int[]> changeRanges = new ArrayList<>();
		for (int i = 0; i < diffLines.size(); i++) {
			if (diffLines.get(i).type != ' ') {
				int start = Math.max(0, i - contextLines);
				int end = Math.min(diffLines.size() - 1, i + contextLines);
				if (!changeRanges.isEmpty() && changeRanges.get(changeRanges.size() - 1)[1] >= start - 1) {
					changeRanges.get(changeRanges.size() - 1)[1] = end;
				} else {
					changeRanges.add(new int[]{start, end});
				}
			}
		}

		for (int[] range : changeRanges) {
			Hunk hunk = new Hunk();
			int startA = 0, startB = 0;
			boolean foundStart = false;

			for (int i = range[0]; i <= range[1]; i++) {
				DiffLine dl = diffLines.get(i);
				if (!foundStart) {
					startA = dl.type != '+' ? dl.lineA : 0;
					startB = dl.type != '-' ? dl.lineB : 0;
					if (startA == 0) startA = 1;
					if (startB == 0) startB = 1;
					foundStart = true;
				}
				hunk.lines.add(dl);
			}

			hunk.startA = startA;
			hunk.startB = startB;
			hunk.countA = (int) hunk.lines.stream().filter(l -> l.type != '+').count();
			hunk.countB = (int) hunk.lines.stream().filter(l -> l.type != '-').count();
			hunks.add(hunk);
		}

		return hunks;
	}

	private record DiffLine(char type, String content, int lineA, int lineB) {}

	private static class Hunk {
		int startA, startB, countA, countB;
		List<DiffLine> lines = new ArrayList<>();

		String toUnifiedFormat() {
			StringBuilder sb = new StringBuilder();
			sb.append("@@ -").append(startA).append(",").append(countA)
					.append(" +").append(startB).append(",").append(countB).append(" @@\n");
			for (DiffLine dl : lines) {
				sb.append(dl.type).append(dl.content).append("\n");
			}
			return sb.toString();
		}
	}
}
