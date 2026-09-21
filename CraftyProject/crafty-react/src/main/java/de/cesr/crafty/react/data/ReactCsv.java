package de.cesr.crafty.react.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CSV reader for every react input file.
 *
 * Values are kept exactly as written (as trimmed text) until the caller asks for a number. This matters
 * for the parameters sheet, where a blank cell means something: core's {@code CsvProcessors.ReadAsaHash}
 * guesses column types, so it turns {@code 200} into {@code "200.0"} and a blank into {@code "NaN"} or
 * {@code ""} depending on the rest of the column.
 *
 * Two ways to read:
 * <ul>
 * <li>{@link #readAll(Path)} for small files (parameters, services, costs, cell key);</li>
 * <li>{@link #forEachRow(Path, List, RowHandler)} for the large LPJ-GUESS files: one line at a time,
 * handing over only the requested columns, so nothing else is kept.</li>
 * </ul>
 * Headers may be quoted ({@code "Lon","Lat"}); a byte-order mark and Windows line endings are accepted;
 * blank lines are skipped. Every row must have as many fields as the header.
 */
public final class ReactCsv {

	/**
	 * Receives one row: its line number in the file, and the requested columns' values, in the requested
	 * order. The same array is reused for every row, so copy any values that must be kept.
	 */
	@FunctionalInterface
	public interface RowHandler {
		void row(int lineNumber, String[] values);
	}

	private static final char BYTE_ORDER_MARK = '﻿';

	private ReactCsv() {
	}

	/** The column names in the first line of a file. */
	public static List<String> readHeader(Path file) {
		try (BufferedReader reader = open(file)) {
			return headerOf(file, reader);
		} catch (IOException e) {
			throw new ReactInputException("Could not read " + file + ": " + e.getMessage(), e);
		}
	}

	/** Reads a whole file into memory. Meant for small files. */
	public static Table readAll(Path file) {
		try (BufferedReader reader = open(file)) {
			List<String> header = headerOf(file, reader);
			List<String[]> rows = new ArrayList<>();
			List<Integer> lineNumbers = new ArrayList<>();
			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				rows.add(fields(file, lineNumber, line, header.size()));
				lineNumbers.add(lineNumber);
			}
			return new Table(file, header, rows, lineNumbers);
		} catch (IOException e) {
			throw new ReactInputException("Could not read " + file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Reads a file line by line, passing the values of the requested columns to the handler. Stops with
	 * one message naming every requested column the file does not have.
	 */
	public static void forEachRow(Path file, List<String> columns, RowHandler handler) {
		try (BufferedReader reader = open(file)) {
			List<String> header = headerOf(file, reader);
			int[] positions = positionsOf(file, header, columns);
			String[] values = new String[columns.size()];
			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				String[] fields = fields(file, lineNumber, line, header.size());
				for (int i = 0; i < positions.length; i++) {
					values[i] = fields[positions[i]];
				}
				handler.row(lineNumber, values);
			}
		} catch (IOException e) {
			throw new ReactInputException("Could not read " + file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * A cell as a number. Blank cells, {@code NA}, {@code NaN} and infinities are refused, with the file,
	 * line and column in the message.
	 */
	public static double parseNumber(Path file, int lineNumber, String column, String text) {
		String trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty()) {
			throw new ReactInputException(file + ", line " + lineNumber + ": column " + column + " is blank");
		}
		double value;
		try {
			value = Double.parseDouble(trimmed);
		} catch (NumberFormatException e) {
			throw new ReactInputException(file + ", line " + lineNumber + ": column " + column
					+ " should be a number but is \"" + trimmed + "\"");
		}
		if (!Double.isFinite(value)) {
			throw new ReactInputException(file + ", line " + lineNumber + ": column " + column
					+ " should be a number but is \"" + trimmed + "\"");
		}
		return value;
	}

	private static BufferedReader open(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new ReactInputException("File not found: " + file);
		}
		return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
	}

	private static List<String> headerOf(Path file, BufferedReader reader) throws IOException {
		String line = reader.readLine();
		if (line == null || line.isBlank()) {
			throw new ReactInputException(file + " is empty; it needs a header line");
		}
		if (line.charAt(0) == BYTE_ORDER_MARK) {
			line = line.substring(1);
		}
		List<String> header = split(line);
		Map<String, Integer> seen = new HashMap<>();
		for (int i = 0; i < header.size(); i++) {
			String name = header.get(i);
			if (name.isEmpty()) {
				throw new ReactInputException(file + ": header column " + (i + 1) + " has no name");
			}
			if (seen.put(name, i) != null) {
				throw new ReactInputException(file + ": header has column " + name + " twice");
			}
		}
		return Collections.unmodifiableList(header);
	}

	private static int[] positionsOf(Path file, List<String> header, List<String> columns) {
		int[] positions = new int[columns.size()];
		List<String> missing = new ArrayList<>();
		for (int i = 0; i < columns.size(); i++) {
			positions[i] = header.indexOf(columns.get(i));
			if (positions[i] < 0) {
				missing.add(columns.get(i));
			}
		}
		if (!missing.isEmpty()) {
			throw new ReactInputException(file + " is missing column(s) " + missing);
		}
		return positions;
	}

	private static String[] fields(Path file, int lineNumber, String line, int expected) {
		List<String> fields = split(line);
		if (fields.size() != expected) {
			throw new ReactInputException(file + ", line " + lineNumber + ": has " + fields.size()
					+ " values but the header has " + expected + " columns");
		}
		return fields.toArray(new String[0]);
	}

	/** Splits one line into trimmed fields. A field in double quotes may contain commas; "" is a quote. */
	static List<String> split(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						field.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					field.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				fields.add(field.toString().trim());
				field.setLength(0);
			} else {
				field.append(c);
			}
		}
		fields.add(field.toString().trim());
		return fields;
	}

	/** A small file held in memory. Values are trimmed text; a blank cell is "". */
	public static final class Table {
		private final Path file;
		private final List<String> header;
		private final List<String[]> rows;
		private final List<Integer> lineNumbers;

		Table(Path file, List<String> header, List<String[]> rows, List<Integer> lineNumbers) {
			this.file = file;
			this.header = header;
			this.rows = rows;
			this.lineNumbers = lineNumbers;
		}

		public Path file() {
			return file;
		}

		public List<String> header() {
			return header;
		}

		public boolean hasColumn(String column) {
			return header.contains(column);
		}

		/** Stops with one message naming every listed column the file does not have. */
		public void requireColumns(String... columns) {
			List<String> missing = new ArrayList<>();
			for (String column : columns) {
				if (!hasColumn(column)) {
					missing.add(column);
				}
			}
			if (!missing.isEmpty()) {
				throw new ReactInputException(file + " is missing column(s) " + missing);
			}
		}

		public int rowCount() {
			return rows.size();
		}

		/** The value in a row and column. The column must exist. */
		public String get(int row, String column) {
			int position = header.indexOf(column);
			if (position < 0) {
				throw new ReactInputException(file + " is missing column " + column);
			}
			return rows.get(row)[position];
		}

		/** The line in the file that a row came from, for messages. */
		public int lineNumber(int row) {
			return lineNumbers.get(row);
		}

		/** A cell as a number; see {@link ReactCsv#parseNumber}. */
		public double number(int row, String column) {
			return parseNumber(file, lineNumber(row), column, get(row, column));
		}
	}
}
