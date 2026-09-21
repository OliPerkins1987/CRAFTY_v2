package de.cesr.crafty.react.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The LPJ-GUESS pixels react works on: those that contain at least one CRAFTY cell.
 *
 * A pixel is identified by its coordinates, rounded to {@value #DECIMALS} decimal places, so the same
 * pixel matches across files even if one writes {@code 17.75} and another {@code 17.7500001}. Nothing
 * assumes a grid size: 0.5° and 0.25° pixels work the same way.
 *
 * Each pixel also has an index {@code 0 … size()-1}, used to index react's arrays, given in the order
 * the pixels are first added (the order of {@code cell_key.csv}).
 */
public final class LpjGrid {

	/** Decimal places coordinates are rounded to before pixels are matched. */
	public static final int DECIMALS = 3;

	private static final double SCALE = Math.pow(10, DECIMALS);

	private final Map<String, Integer> indexByKey;
	private final double[] lons;
	private final double[] lats;

	private LpjGrid(Map<String, Integer> indexByKey, double[] lons, double[] lats) {
		this.indexByKey = indexByKey;
		this.lons = lons;
		this.lats = lats;
	}

	/**
	 * The key of the pixel at (lon, lat): both rounded to {@value #DECIMALS} decimal places.
	 *
	 * @throws IllegalArgumentException if the point is not on the globe (for example, Lon and Lat swapped)
	 */
	public static String pixelKey(double lon, double lat) {
		if (!(lon >= -180 && lon <= 180)) {
			throw new IllegalArgumentException("longitude " + lon + " is not between -180 and 180");
		}
		if (!(lat >= -90 && lat <= 90)) {
			throw new IllegalArgumentException("latitude " + lat + " is not between -90 and 90");
		}
		return Math.round(lon * SCALE) + "," + Math.round(lat * SCALE);
	}

	/** How many pixels the grid holds. */
	public int size() {
		return lons.length;
	}

	/** The index of the pixel at (lon, lat), or -1 if it is not in the grid. */
	public int indexOf(double lon, double lat) {
		Integer index = indexByKey.get(pixelKey(lon, lat));
		return index == null ? -1 : index;
	}

	/** A pixel's longitude, rounded. */
	public double lon(int index) {
		return lons[index];
	}

	/** A pixel's latitude, rounded. */
	public double lat(int index) {
		return lats[index];
	}

	/** A pixel as "(lon, lat)", for messages. */
	public String describe(int index) {
		return "(" + lons[index] + ", " + lats[index] + ")";
	}

	@Override
	public String toString() {
		return "LpjGrid[" + size() + " pixels]";
	}

	/** Builds a grid one pixel at a time; indexes follow the order pixels are first added. */
	public static final class Builder {
		private final Map<String, Integer> indexByKey = new HashMap<>();
		private final List<Double> lons = new ArrayList<>();
		private final List<Double> lats = new ArrayList<>();

		/** Adds a pixel if it is new, and returns its index either way. */
		public int add(double lon, double lat) {
			String key = pixelKey(lon, lat);
			Integer index = indexByKey.get(key);
			if (index == null) {
				index = lons.size();
				indexByKey.put(key, index);
				lons.add(Math.round(lon * SCALE) / SCALE);
				lats.add(Math.round(lat * SCALE) / SCALE);
			}
			return index;
		}

		public LpjGrid build() {
			return new LpjGrid(new HashMap<>(indexByKey), lons.stream().mapToDouble(Double::doubleValue).toArray(),
					lats.stream().mapToDouble(Double::doubleValue).toArray());
		}
	}
}
