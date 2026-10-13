package de.cesr.crafty.core.dataLoader.land;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.utils.general.DeterministicAggregation;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

/**
 * Gives each cell its land area from the {@code Area} column of the GIS file, the same file
 * {@link GisLoader} reads the regions from. Only used when {@code use_cell_area} is on.
 *
 * A cell's production stays per unit area (t/ha, m3/ha, ...). Its area is used only where cells are
 * added up into supply ({@link Cell#currentSupply(int)}), so it must be in the unit production is per
 * (ha for per-hectare yields).
 *
 * The file is read again here, separately from {@link GisLoader}: GisLoader turns regionalisation off
 * with a "not found" warning on any NullPointerException or IOException, so keeping the two apart
 * means a bad area can never switch the regions off, and a region problem can never quietly leave
 * every cell with area 1. Only X, Y and Area are read, as text, and parsed here, so Tablesaw's guess
 * at a column's type never changes a value. Column names are matched ignoring case; quoted fields
 * and a byte-order mark are handled by Tablesaw.
 *
 * - No GIS file found: every cell has area 1, with a warning.
 * - A cell with no row: area 1, with a warning giving how many and the first few.
 * - Rows for cells that are not in the baseline map are ignored, as for capitals.
 * - The run stops ({@link IllegalArgumentException}) if the file cannot be read; has no X, Y or Area
 *   column; has an X or Y that is blank or not a number; has an Area that is blank, NA, not a number,
 *   infinite or not above 0; or has the same X,Y twice. Every row is checked, including rows for
 *   cells outside the baseline, and the whole file is checked before any cell is changed.
 */
public final class CellAreaLoader {

	private static final CustomLogger LOGGER = new CustomLogger(CellAreaLoader.class);
	private static final String X = "X";
	private static final String Y = "Y";
	private static final String AREA = "AREA";
	private static final Set<String> COLUMNS = Set.of(X, Y, AREA);
	private static final int MISSING_CELLS_SHOWN = 5;

	private CellAreaLoader() {
	}

	/** Sets the area of every cell in {@link CellsLoader#hashCell}, from the file GisLoader found. */
	public static void load() {
		Path file = GisLoader.getGisFile();
		if (file == null) {
			LOGGER.warn("use_cell_area is true but no GIS file was found (gis_path, or a file in a GIS folder):"
					+ " every cell has area 1");
			CellsLoader.hashCell.values().forEach(c -> c.setArea(1.0));
			return;
		}
		load(file, CellsLoader.hashCell);
	}

	/** Reads the Area column of {@code file} and sets the area of each of {@code cells}. */
	public static void load(Path file, Map<String, Cell> cells) {
		Map<String, Double> areas = read(file);

		List<Cell> missing = new ArrayList<>();
		double total = 0.0;
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (Cell c : DeterministicAggregation.cellsInStableOrder(cells.values())) {
			Double area = areas.get(key(c.getX(), c.getY()));
			if (area == null) {
				missing.add(c);
				area = 1.0;
			}
			c.setArea(area);
			total += area;
			min = Math.min(min, area);
			max = Math.max(max, area);
		}

		if (!missing.isEmpty()) {
			List<String> shown = new ArrayList<>();
			for (Cell c : missing.subList(0, Math.min(MISSING_CELLS_SHOWN, missing.size()))) {
				shown.add("(" + c.getX() + "," + c.getY() + ")");
			}
			LOGGER.warn(file + " has no row for " + missing.size() + " cell(s), which get area 1: "
					+ String.join(", ", shown) + (missing.size() > MISSING_CELLS_SHOWN ? ", ..." : ""));
		}
		LOGGER.info("Cell area from " + file + ": " + (cells.size() - missing.size()) + " of " + cells.size()
				+ " cells; total " + total + ", min " + (cells.isEmpty() ? 0 : min) + ", max "
				+ (cells.isEmpty() ? 0 : max));
	}

	/** Every row's area by "x,y", after checking the whole file. */
	private static Map<String, Double> read(Path file) {
		Table table;
		try {
			// Read only X, Y and Area, all as text; every other column is skipped.
			table = Table.read().usingOptions(CsvReadOptions.builder(file.toFile()).separator(',')
					.columnTypes(name -> COLUMNS.contains(normalise(name)) ? ColumnType.STRING : ColumnType.SKIP)
					.build());
		} catch (Exception e) {
			throw new IllegalArgumentException("use_cell_area is true but the GIS file " + file
					+ " could not be read: " + e.getMessage(), e);
		}

		Map<String, Integer> index = new HashMap<>();
		List<String> names = table.columnNames();
		for (int i = 0; i < names.size(); i++) {
			index.putIfAbsent(normalise(names.get(i)), i);
		}
		List<String> absent = new ArrayList<>();
		for (String column : List.of(X, Y, AREA)) {
			if (!index.containsKey(column)) {
				absent.add(column.equals(AREA) ? "Area" : column);
			}
		}
		if (!absent.isEmpty()) {
			throw new IllegalArgumentException("use_cell_area is true but the GIS file " + file
					+ " has no column(s) " + absent + ". Add an Area column (the area of each cell),"
					+ " or set use_cell_area to false");
		}
		int xCol = index.get(X);
		int yCol = index.get(Y);
		int areaCol = index.get(AREA);

		Map<String, Double> areas = new HashMap<>();
		Map<String, Integer> rowOf = new HashMap<>();
		for (int i = 0; i < table.rowCount(); i++) {
			int row = i + 1;
			int x = coordinate(file, row, "X", table.column(xCol).getString(i));
			int y = coordinate(file, row, "Y", table.column(yCol).getString(i));
			String cell = "cell (" + x + "," + y + ")";
			double area = number(file, row, "Area for " + cell, table.column(areaCol).getString(i));
			if (!Double.isFinite(area) || area <= 0) {
				throw new IllegalArgumentException(where(file, row) + "Area " + area + " for " + cell
						+ " must be a number above 0");
			}
			String key = key(x, y);
			Integer earlier = rowOf.putIfAbsent(key, row);
			if (earlier != null) {
				throw new IllegalArgumentException("The GIS file " + file + " has " + cell
						+ " twice, on data rows " + earlier + " and " + row);
			}
			areas.put(key, area);
		}
		return areas;
	}

	/** A grid coordinate, truncated to a whole number as the baseline loader does. */
	private static int coordinate(Path file, int row, String column, String text) {
		double value = number(file, row, column, text);
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(where(file, row) + column + " '" + text.trim()
					+ "' is not a number");
		}
		return (int) value;
	}

	private static double number(Path file, int row, String what, String text) {
		String value = text == null ? "" : text.trim();
		// Tablesaw reads NA (and other missing-value markers) as blank.
		if (value.isEmpty()) {
			throw new IllegalArgumentException(where(file, row) + what + " is blank or NA");
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(where(file, row) + what + " '" + value + "' is not a number");
		}
	}

	private static String where(Path file, int row) {
		return "The GIS file " + file + ", data row " + row + ": ";
	}

	private static String normalise(String columnName) {
		return columnName.replace("﻿", "").replace("\"", "").trim().toUpperCase(Locale.ROOT);
	}

	private static String key(int x, int y) {
		return x + "," + y;
	}
}
