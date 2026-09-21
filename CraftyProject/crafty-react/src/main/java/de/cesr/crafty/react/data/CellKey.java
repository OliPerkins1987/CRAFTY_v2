package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The link between CRAFTY cells and LPJ-GUESS pixels, from {@code worlds/react/cell_key.csv}
 * ({@code X,Y} → {@code LPJ_cell_x,LPJ_cell_y}).
 *
 * The pixels named here are the only ones react works on: they make up its {@link LpjGrid}, numbered in
 * the order they first appear in this file, and rows for any other pixel in an LPJ-GUESS file are dropped.
 *
 * The {@code region} column gives each cell's CRAFTY region. React needs it because prices are per
 * region: a pixel's regions come from its cells, and a 0.5° pixel can straddle a border. Cells are identified the way core identifies
 * them, as {@code "x,y"}. Other columns (such as {@code ID}) are ignored.
 */
public final class CellKey {

	public static final String X = "X";
	public static final String Y = "Y";
	public static final String LON = "LPJ_cell_x";
	public static final String LAT = "LPJ_cell_y";
	/** The CRAFTY region the cell belongs to. Required whenever react runs. */
	public static final String REGION = "region";

	/** How many missing cells to name in a message before summarising. */
	private static final int CELLS_TO_NAME = 5;

	private final Path file;
	private final LpjGrid grid;
	private final Map<String, Integer> pixelByCell;
	private final List<List<String>> cellsByPixel;
	private final Map<String, String> regionByCell;

	private CellKey(Path file, LpjGrid grid, Map<String, Integer> pixelByCell, List<List<String>> cellsByPixel,
			Map<String, String> regionByCell) {
		this.file = file;
		this.grid = grid;
		this.pixelByCell = pixelByCell;
		this.cellsByPixel = cellsByPixel;
		this.regionByCell = regionByCell;
	}

	public static CellKey load(Path file) {
		ReactCsv.Table table = ReactCsv.readAll(file);
		table.requireColumns(X, Y, LON, LAT, REGION);
		if (table.rowCount() == 0) {
			throw new ReactInputException(file + " has no rows");
		}

		LpjGrid.Builder grid = new LpjGrid.Builder();
		Map<String, Integer> pixelByCell = new HashMap<>(table.rowCount() * 2);
		Map<String, String> regionByCell = new HashMap<>(table.rowCount() * 2);
		List<List<String>> cellsByPixel = new ArrayList<>();
		for (int row = 0; row < table.rowCount(); row++) {
			String where = file + ", line " + table.lineNumber(row) + ": ";
			String cell = cellId(wholeNumber(table, row, X), wholeNumber(table, row, Y));
			String region = table.get(row, REGION);
			if (region.isEmpty()) {
				throw new ReactInputException(where + REGION + " is blank");
			}
			regionByCell.put(cell, region);
			int pixel;
			try {
				pixel = grid.add(table.number(row, LON), table.number(row, LAT));
			} catch (IllegalArgumentException e) {
				throw new ReactInputException(where + e.getMessage());
			}
			if (pixelByCell.put(cell, pixel) != null) {
				throw new ReactInputException(where + "cell " + cell + " appears more than once");
			}
			if (pixel == cellsByPixel.size()) {
				cellsByPixel.add(new ArrayList<>());
			}
			cellsByPixel.get(pixel).add(cell);
		}
		cellsByPixel.replaceAll(Collections::unmodifiableList);
		return new CellKey(file, grid.build(), pixelByCell, cellsByPixel, regionByCell);
	}

	/** A cell's id as core writes it: {@code "x,y"}. */
	public static String cellId(int x, int y) {
		return x + "," + y;
	}

	/** The pixels that contain at least one CRAFTY cell. */
	public LpjGrid grid() {
		return grid;
	}

	/** The pixel index of a cell ({@code "x,y"}), or -1 if the key does not list the cell. */
	public int pixelOf(String cellId) {
		Integer pixel = pixelByCell.get(cellId);
		return pixel == null ? -1 : pixel;
	}

	/** The cells in a pixel, in the order the key lists them. */
	public List<String> cellsIn(int pixel) {
		return cellsByPixel.get(pixel);
	}

	public int cellCount() {
		return pixelByCell.size();
	}

	/** The CRAFTY region of a cell, as the key gives it, or null if the key does not list the cell. */
	public String regionOfCell(String cellId) {
		return regionByCell.get(cellId);
	}

	/** Every region named in the key. */
	public Set<String> regions() {
		return new TreeSet<>(regionByCell.values());
	}

	/**
	 * The regions of the cells in a pixel, in the order the key lists them. A pixel usually has one,
	 * but a 0.5° pixel can straddle a border; phase 3 decides per (pixel, region).
	 */
	public List<String> regionsIn(int pixel) {
		List<String> regions = new ArrayList<>();
		for (String cell : cellsIn(pixel)) {
			String region = regionByCell.get(cell);
			if (!regions.contains(region)) {
				regions.add(region);
			}
		}
		return regions;
	}

	/**
	 * The region holding most of a pixel's cells. Ties go to the first name alphabetically, so the
	 * answer never depends on the order of the file.
	 */
	public String dominantRegion(int pixel) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String cell : cellsIn(pixel)) {
			counts.merge(regionByCell.get(cell), 1, Integer::sum);
		}
		return counts.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
				.thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).map(Map.Entry::getKey).orElse(null);
	}

	/** How many pixels hold cells from more than one region. */
	public int pixelsSpanningRegions() {
		int spanning = 0;
		for (int pixel = 0; pixel < grid.size(); pixel++) {
			if (regionsIn(pixel).size() > 1) {
				spanning++;
			}
		}
		return spanning;
	}

	/**
	 * Stops with a message if any of the model's cells has no pixel in the key. Cells in the key but not
	 * in the model are fine: the key may cover a larger area.
	 */
	public void requireEveryCell(Collection<String> modelCellIds) {
		TreeSet<String> missing = new TreeSet<>();
		for (String cell : modelCellIds) {
			if (!pixelByCell.containsKey(cell)) {
				missing.add(cell);
			}
		}
		if (!missing.isEmpty()) {
			List<String> named = new ArrayList<>(missing).subList(0, Math.min(CELLS_TO_NAME, missing.size()));
			throw new ReactInputException(file + " has no LPJ-GUESS pixel for " + missing.size()
					+ " CRAFTY cell(s), e.g. " + named);
		}
	}

	private static int wholeNumber(ReactCsv.Table table, int row, String column) {
		double value = table.number(row, column);
		if (value != Math.rint(value)) {
			throw new ReactInputException(table.file() + ", line " + table.lineNumber(row) + ": column " + column
					+ " should be a whole number but is " + table.get(row, column));
		}
		return (int) value;
	}
}
