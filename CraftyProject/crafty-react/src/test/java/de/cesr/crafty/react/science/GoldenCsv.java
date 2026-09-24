package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Reads the expected values the R script wrote ({@code make_golden.R}, kept outside the repo in
 * {@code CRAFTY_PLUM/CRAFTY_dev/debugging}) and checks the Java against them.
 *
 * Each file sits in {@code src/test/resources/golden/}. Lines starting with {@code #} say where the file
 * came from; the first other line is the header; the {@code case} column names the row ("random", or the
 * edge case it was written for); numbers are written to 17 significant digits. A few files also have text
 * columns, such as an AFT or a region, read with {@link Row#text}.
 *
 * Public so that tests in other packages (the phase 3 decisions) can use it.
 */
public final class GoldenCsv {

	/** Allowed relative difference between R and Java. */
	public static final double RELATIVE = 1e-9;

	/** Allowed absolute difference, for values near 0. */
	public static final double ABSOLUTE = 1e-12;

	/** One row: its line in the file, its case name and its fields by column, as written. */
	public record Row(int line, String name, Map<String, String> fields) {

		/** A number column. */
		public double get(String column) {
			return Double.parseDouble(text(column));
		}

		/** A text column. */
		public String text(String column) {
			String value = fields.get(column);
			if (value == null) {
				throw new IllegalArgumentException("No column " + column + " in " + fields.keySet());
			}
			return value;
		}

		@Override
		public String toString() {
			return "line " + line + " (" + name + ")";
		}
	}

	private GoldenCsv() {
	}

	public static List<Row> read(String name) {
		String resource = "/golden/" + name + ".csv";
		InputStream stream = GoldenCsv.class.getResourceAsStream(resource);
		if (stream == null) {
			throw new IllegalStateException("Missing test resource " + resource);
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			List<Row> rows = new ArrayList<>();
			String[] header = null;
			String text;
			int line = 0;
			while ((text = reader.readLine()) != null) {
				line++;
				if (text.startsWith("#") || text.isBlank()) {
					continue;
				}
				String[] fields = text.split(",", -1);
				if (header == null) {
					header = fields;
					continue;
				}
				if (fields.length != header.length) {
					throw new IllegalStateException(resource + " line " + line + " has " + fields.length
							+ " fields; the header has " + header.length);
				}
				Map<String, String> values = new HashMap<>();
				String caseName = "";
				for (int i = 0; i < header.length; i++) {
					if (header[i].equals("case")) {
						caseName = fields[i];
					} else {
						values.put(header[i], fields[i]);
					}
				}
				rows.add(new Row(line, caseName, values));
			}
			if (rows.isEmpty()) {
				throw new IllegalStateException(resource + " has no rows");
			}
			return rows;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Checks one column of a file: for every row, the Java value must match the file's value to a relative
	 * {@value #RELATIVE} (absolute {@value #ABSOLUTE} near 0). Every row is checked, and a failure lists
	 * every row that differs, by line and case name, with R's value and the Java value.
	 */
	public static void check(String name, String column, ToDoubleFunction<Row> java) {
		List<Row> rows = read(name);
		List<String> failures = new ArrayList<>();
		for (Row row : rows) {
			double expected = row.get(column);
			double actual;
			try {
				actual = java.applyAsDouble(row);
			} catch (RuntimeException e) {
				failures.add(row + ": Java threw " + e);
				continue;
			}
			if (!close(expected, actual)) {
				failures.add(row + ": R " + expected + ", Java " + actual + ", difference " + (actual - expected));
			}
		}
		if (!failures.isEmpty()) {
			fail(name + ".csv, column " + column + ": " + failures.size() + " of " + rows.size()
					+ " rows differ from R\n  " + String.join("\n  ", failures));
		}
	}

	private static boolean close(double expected, double actual) {
		return Double.isFinite(actual) && Math.abs(actual - expected) <= ABSOLUTE + RELATIVE * Math.abs(expected);
	}
}
