package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads columns from an LPJ-GUESS grid file ({@code Lon,Lat,...}, or {@code x,y,...} as terra writes it)
 * into arrays indexed by the pixels of an {@link LpjGrid}.
 *
 * Rows for pixels outside the grid are skipped without being parsed. Every pixel in the grid must
 * appear exactly once. Values are multiplied by {@code factor} as they are read, which is how units are
 * converted (for example kg/m² to t/ha is × 10), and stored as {@code float}.
 */
public final class LpjFileReader {

	public static final String LON = "Lon";
	public static final String LAT = "Lat";
	public static final String X = "x";
	public static final String Y = "y";

	/** Added to a missing-columns message when a file has neither pair of coordinates. */
	public static final String OR_X_AND_Y = " (or x and y instead of Lon and Lat)";

	/** How many missing pixels to name in a message before summarising. */
	private static final int PIXELS_TO_NAME = 5;

	private LpjFileReader() {
	}

	/**
	 * @return one array per requested column, in the requested order; each holds a value for every
	 *         pixel in the grid
	 */
	public static float[][] read(Path file, LpjGrid grid, List<String> columns, double factor) {
		List<String> coordinates = coordinateColumns(ReactCsv.readHeader(file));
		if (coordinates == null) {
			throw new ReactInputException(file + " is missing column(s) " + List.of(LON, LAT) + OR_X_AND_Y);
		}
		List<String> wanted = new ArrayList<>(coordinates);
		wanted.addAll(columns);

		float[][] values = new float[columns.size()][grid.size()];
		boolean[] seen = new boolean[grid.size()];

		ReactCsv.forEachRow(file, wanted, (line, row) -> {
			int pixel = pixelOf(file, line, grid, coordinates, row[0], row[1]);
			if (pixel < 0) {
				return;
			}
			if (seen[pixel]) {
				throw new ReactInputException(file + ", line " + line + ": pixel " + grid.describe(pixel)
						+ " appears more than once");
			}
			seen[pixel] = true;
			for (int c = 0; c < columns.size(); c++) {
				values[c][pixel] = (float) (ReactCsv.parseNumber(file, line, columns.get(c), row[c + 2]) * factor);
			}
		});

		requireEveryPixel(file, grid, seen);
		return values;
	}

	/**
	 * The coordinate columns a header uses: {@code Lon} and {@code Lat} if it has both, otherwise {@code x}
	 * and {@code y} if it has both; null if it has neither pair.
	 */
	public static List<String> coordinateColumns(List<String> header) {
		if (header.contains(LON) && header.contains(LAT)) {
			return List.of(LON, LAT);
		}
		if (header.contains(X) && header.contains(Y)) {
			return List.of(X, Y);
		}
		return null;
	}

	private static int pixelOf(Path file, int line, LpjGrid grid, List<String> coordinates, String lonText,
			String latText) {
		double lon = ReactCsv.parseNumber(file, line, coordinates.get(0), lonText);
		double lat = ReactCsv.parseNumber(file, line, coordinates.get(1), latText);
		try {
			return grid.indexOf(lon, lat);
		} catch (IllegalArgumentException e) {
			throw new ReactInputException(file + ", line " + line + ": " + e.getMessage());
		}
	}

	private static void requireEveryPixel(Path file, LpjGrid grid, boolean[] seen) {
		List<String> missing = new ArrayList<>();
		int count = 0;
		for (int pixel = 0; pixel < seen.length; pixel++) {
			if (!seen[pixel]) {
				count++;
				if (missing.size() < PIXELS_TO_NAME) {
					missing.add(grid.describe(pixel));
				}
			}
		}
		if (count > 0) {
			throw new ReactInputException(file + " has no row for " + count + " pixel(s) that contain CRAFTY cells, e.g. "
					+ String.join(", ", missing));
		}
	}
}
