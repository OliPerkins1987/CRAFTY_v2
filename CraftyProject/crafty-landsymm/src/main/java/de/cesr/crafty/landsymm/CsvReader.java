package de.cesr.crafty.landsymm;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The coupler's CSV reader, for CRAFTY's metadata and GIS files and PLUM's outputs.
 *
 * Values are kept exactly as written, as trimmed text, until the caller asks for a number. Headers and values may be
 * quoted ({@code "Region_Code"}); a field in double quotes may contain commas, and {@code ""} inside one is a quote.
 * Header names are trimmed, so PLUM's {@code Year, Country, Crop} gives {@code Country} and {@code Crop}. A
 * byte-order mark and Windows line endings are accepted, and blank lines are skipped. Every row must have as many
 * fields as the header.
 *
 * Two ways to read: {@link #readAll(Path)} for small files, and {@link #forEachRow(Path, List, RowHandler)} for the
 * large ones (the GIS file, PLUM's cumulative outputs), which hands over one line at a time, with only the requested
 * columns, so nothing else is kept.
 *
 * Problems are reported as {@link CouplingInputException}s naming the file and line.
 */
public final class CsvReader {

	/**
	 * Receives one row: its line number in the file, and the requested columns' values in the requested order. The
	 * same array is reused for every row, so copy any values that must be kept.
	 */
	@FunctionalInterface
	public interface RowHandler {
		void row(int lineNumber, String[] values);
	}

	private static final char BYTE_ORDER_MARK = '﻿';

	private CsvReader() {
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
			throw new CouplingInputException("Could not read " + file + ": " + e.getMessage());
		}
	}

	/**
	 * Reads a file line by line, passing the values of the requested columns to the handler. Stops with a
	 * {@link CouplingInputException} if a column is missing or a row has the wrong number of fields.
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
				String[] row = fields(file, lineNumber, line, header.size());
				for (int i = 0; i < positions.length; i++) {
					values[i] = row[positions[i]];
				}
				handler.row(lineNumber, values);
			}
		} catch (IOException e) {
			throw new CouplingInputException("Could not read " + file + ": " + e.getMessage());
		}
	}

	/** The column names in the first line of a file. */
	public static List<String> readHeader(Path file) {
		try (BufferedReader reader = open(file)) {
			return headerOf(file, reader);
		} catch (IOException e) {
			throw new CouplingInputException("Could not read " + file + ": " + e.getMessage());
		}
	}

	/**
	 * A number from a file: finite, or NaN when written as {@code NaN} (as PLUM writes unused values). Anything else,
	 * blank included, stops with a message naming the file, line and column.
	 */
	public static double parseNumber(Path file, int lineNumber, String column, String text) {
		String trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty()) {
			throw new CouplingInputException(file + ", line " + lineNumber + ": " + column + " is blank");
		}
		try {
			return Double.parseDouble(trimmed);
		} catch (NumberFormatException e) {
			throw new CouplingInputException(file + ", line " + lineNumber + ": " + column
					+ " should be a number but is \"" + trimmed + "\"");
		}
	}

	private static BufferedReader open(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new CouplingInputException(file + " not found");
		}
		return Files.newBufferedReader(file, StandardCharsets.UTF_8);
	}

	private static List<String> headerOf(Path file, BufferedReader reader) throws IOException {
		String line = reader.readLine();
		if (line == null || line.isBlank()) {
			throw new CouplingInputException(file + " is empty");
		}
		if (line.charAt(0) == BYTE_ORDER_MARK) {
			line = line.substring(1);
		}
		List<String> header = split(line);
		Map<String, Integer> seen = new HashMap<>();
		for (int i = 0; i < header.size(); i++) {
			String name = header.get(i);
			if (name.isEmpty()) {
				throw new CouplingInputException(file + ": header column " + (i + 1) + " has no name");
			}
			if (seen.put(name, i) != null) {
				throw new CouplingInputException(file + ": header has column " + name + " twice");
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
			throw new CouplingInputException(file + " is missing column(s) " + missing);
		}
		return positions;
	}

	private static String[] fields(Path file, int lineNumber, String line, int expected) {
		List<String> fields = split(line);
		if (fields.size() != expected) {
			throw new CouplingInputException(file + ", line " + lineNumber + ": has " + fields.size()
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

	/** A whole file read by {@link #readAll(Path)}: the header and the rows, with their line numbers. */
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

		public int size() {
			return rows.size();
		}

		/** The row's value in a column, or null if the file has no such column. */
		public String get(int row, String column) {
			int position = header.indexOf(column);
			return position < 0 ? null : rows.get(row)[position];
		}

		/** The row's line number in the file, for messages. */
		public int lineNumber(int row) {
			return lineNumbers.get(row);
		}
	}
}
