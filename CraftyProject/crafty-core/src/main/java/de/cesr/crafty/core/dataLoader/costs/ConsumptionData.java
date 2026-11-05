package de.cesr.crafty.core.dataLoader.costs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * One region's domestic consumption of each service, by year. Transport charged by export share (transport
 * with spatial production costs) needs it: a region exports what it supplies above its consumption.
 *
 * The file is laid out like a demand file, with a Year column and one column per service:
 * <pre>
 * Year,C3cereals,C3rice,...
 * 2000,1866000,333000,...
 * </pre>
 * Rows are matched by their Year value, so they may come in any order, and years outside the run are
 * ignored. Only the services that pay transport are read. Each of them needs a column, with a number of 0
 * or more for every year of the run.
 *
 * Nothing here stops the run. Anything missing or invalid is listed in {@link #problems()}, so the caller can
 * report every region's problems at once.
 */
public class ConsumptionData {

	/** The folder of the consumption files: {@code <costs>/spatial/export/<scenario>/}. */
	public static final String FOLDER = "export";
	/** The column that holds each row's year. */
	public static final String YEAR_COLUMN = "Year";

	private final Map<Integer, double[]> byYear = new HashMap<>();
	private final List<String> problems = new ArrayList<>();

	/**
	 * Reads one region's file.
	 *
	 * @param file      the region's consumption file
	 * @param region    the region's name, for the problem messages
	 * @param services  every service, in {@code ServiceSet.getServicesList()} order. Each year's values are
	 *                  indexed the same way, with 0 for a service that does not pay transport.
	 * @param payers    whether a service pays transport; only those are read
	 * @param startYear the first year of the run
	 * @param endYear   the last year of the run
	 */
	public ConsumptionData(Path file, String region, List<String> services, Predicate<String> payers, int startYear,
			int endYear) {
		String prefix = "|" + region + "| " + file.getFileName() + ": ";
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			problems.add(prefix + "could not be read (" + e + ")");
			return;
		}
		if (lines.isEmpty() || lines.get(0).isBlank()) {
			problems.add(prefix + "is empty");
			return;
		}

		List<String> header = fields(lines.get(0).replace("﻿", ""));
		int yearColumn = -1;
		for (int j = 0; j < header.size(); j++) {
			if (header.get(j).equalsIgnoreCase(YEAR_COLUMN)) {
				yearColumn = j;
				break;
			}
		}
		if (yearColumn < 0) {
			problems.add(prefix + "has no " + YEAR_COLUMN + " column");
			return;
		}

		// The column of each service that pays transport, by its index in services; -1 if it has none.
		int[] column = new int[services.size()];
		for (int i = 0; i < services.size(); i++) {
			column[i] = -1;
			if (!payers.test(services.get(i))) {
				continue;
			}
			column[i] = header.indexOf(services.get(i));
			if (column[i] < 0) {
				problems.add(prefix + "has no column for " + services.get(i));
			}
		}

		// Per service, the run years with an empty or invalid value.
		Map<String, TreeSet<Integer>> empty = new TreeMap<>();
		Map<String, TreeSet<Integer>> invalid = new TreeMap<>();
		for (int line = 1; line < lines.size(); line++) {
			if (lines.get(line).isBlank()) {
				continue;
			}
			List<String> row = fields(lines.get(line));
			Integer year = year(cell(row, yearColumn));
			if (year == null) {
				problems.add(prefix + "line " + (line + 1) + ": '" + cell(row, yearColumn) + "' is not a year");
				continue;
			}
			if (year < startYear || year > endYear) {
				continue;
			}
			if (byYear.containsKey(year)) {
				problems.add(prefix + "has " + year + " twice");
				continue;
			}
			double[] values = new double[services.size()];
			for (int i = 0; i < services.size(); i++) {
				if (column[i] < 0) {
					continue;
				}
				String s = cell(row, column[i]);
				if (s.isEmpty()) {
					empty.computeIfAbsent(services.get(i), k -> new TreeSet<>()).add(year);
					continue;
				}
				double v = number(s);
				if (!(v >= 0) || Double.isInfinite(v)) {
					invalid.computeIfAbsent(services.get(i), k -> new TreeSet<>()).add(year);
					continue;
				}
				values[i] = v;
			}
			byYear.put(year, values);
		}

		// A run year with no row has no value for any service.
		for (int i = 0; i < services.size(); i++) {
			if (column[i] < 0) {
				continue;
			}
			for (int year = startYear; year <= endYear; year++) {
				if (!byYear.containsKey(year)) {
					empty.computeIfAbsent(services.get(i), k -> new TreeSet<>()).add(year);
				}
			}
		}
		empty.forEach((service, years) -> problems.add(prefix + "no consumption for " + service + " in "
				+ yearRanges(years)));
		invalid.forEach((service, years) -> problems.add(prefix + "consumption for " + service
				+ " is not a number of 0 or more in " + yearRanges(years)));
	}

	/**
	 * The year's consumption, indexed like the services this was read with (0 for a service that does not pay
	 * transport), or null if the file has no row for that year.
	 */
	public double[] get(int year) {
		return byYear.get(year);
	}

	/** Every run year read, with its consumption as {@link #get(int)} gives it. */
	public Map<Integer, double[]> byYear() {
		return Collections.unmodifiableMap(byYear);
	}

	/** Everything missing or invalid in the file, one message each; empty if it is complete. */
	public List<String> problems() {
		return Collections.unmodifiableList(problems);
	}

	/** A line's comma-separated fields, trimmed and without surrounding double quotes. */
	private static List<String> fields(String line) {
		List<String> out = new ArrayList<>();
		for (String f : line.split(",", -1)) {
			String s = f.trim();
			if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
				s = s.substring(1, s.length() - 1).trim();
			}
			out.add(s);
		}
		return out;
	}

	/** The field at j, or "" for a row too short to have it. */
	private static String cell(List<String> row, int j) {
		return j < row.size() ? row.get(j) : "";
	}

	/** A whole-number year (1990 or 1990.0), or null. */
	private static Integer year(String s) {
		double v = number(s);
		if (Double.isNaN(v) || Double.isInfinite(v) || v != Math.rint(v)) {
			return null;
		}
		return (int) v;
	}

	/** The number s holds, or NaN if it holds none. */
	private static double number(String s) {
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	/** Sorted years as ranges, e.g. "2001, 2003-2005". */
	static String yearRanges(TreeSet<Integer> years) {
		StringBuilder sb = new StringBuilder();
		Integer first = null;
		Integer last = null;
		for (int y : years) {
			if (last != null && y == last + 1) {
				last = y;
				continue;
			}
			appendRange(sb, first, last);
			first = y;
			last = y;
		}
		appendRange(sb, first, last);
		return sb.toString();
	}

	private static void appendRange(StringBuilder sb, Integer first, Integer last) {
		if (first == null) {
			return;
		}
		if (sb.length() > 0) {
			sb.append(", ");
		}
		sb.append(first.equals(last) ? String.valueOf(first) : first + "-" + last);
	}
}
