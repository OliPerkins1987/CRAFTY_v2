package de.cesr.crafty.react.decisions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.cesr.crafty.react.data.CellKey;
import de.cesr.crafty.react.data.ReactInputException;

/**
 * Where CRAFTY-react makes its decisions: each LPJ-GUESS pixel, once for every CRAFTY region its cells are
 * in. Each of these is a <em>decision unit</em>.
 *
 * Decisions are made on the LPJ-GUESS grid, but prices are set per region, and a pixel can straddle a
 * border. So a pixel with cells in two regions is decided twice, once at each region's prices, and each of
 * its cells takes the answer for its own region. Most pixels have one unit.
 * <ul>
 * <li>A cell's region is the one the cell key gives it ({@link CellKey#REGION}).</li>
 * <li>A model with a single region (CRAFTY only splits a project into regions when there is a demand file
 * per region) has one unit per pixel, all priced in that region. The key's regions are not used.</li>
 * </ul>
 *
 * Units are numbered pixel by pixel, in grid order, and within a pixel in the order the key first lists each
 * region, so the numbering depends only on the cell key. They are built once, when react starts, and do not
 * change during a run: what react carries from year to year (each AFT's N, and later its stocking rate) is
 * held in arrays indexed by unit.
 */
public final class DecisionUnits {

	private final CellKey key;
	private final int[] pixelOfUnit;
	private final String[] regionOfUnit;
	/** The units of pixel p are firstUnitOfPixel[p] up to, but not including, firstUnitOfPixel[p + 1]. */
	private final int[] firstUnitOfPixel;
	/** The model's only region, or null when the model has several. */
	private final String singleRegion;

	private DecisionUnits(CellKey key, int[] pixelOfUnit, String[] regionOfUnit, int[] firstUnitOfPixel,
			String singleRegion) {
		this.key = key;
		this.pixelOfUnit = pixelOfUnit;
		this.regionOfUnit = regionOfUnit;
		this.firstUnitOfPixel = firstUnitOfPixel;
		this.singleRegion = singleRegion;
	}

	/**
	 * Builds the units from the cell key.
	 *
	 * @param modelRegions the model's regions. With one, every pixel has a single unit priced there; with
	 *                     several, a pixel has a unit for each region its cells are in (the startup checks
	 *                     have made sure the key names only regions the model has)
	 * @throws ReactInputException if the model has no region, since nothing could then be priced
	 */
	public static DecisionUnits build(CellKey key, Set<String> modelRegions) {
		if (modelRegions.isEmpty()) {
			throw new ReactInputException("CRAFTY-react cannot price anything: the model has no region");
		}
		String singleRegion = modelRegions.size() == 1 ? modelRegions.iterator().next() : null;

		int pixels = key.grid().size();
		int[] firstUnitOfPixel = new int[pixels + 1];
		List<Integer> pixelOfUnit = new ArrayList<>();
		List<String> regionOfUnit = new ArrayList<>();
		for (int pixel = 0; pixel < pixels; pixel++) {
			firstUnitOfPixel[pixel] = pixelOfUnit.size();
			List<String> regions = singleRegion != null ? List.of(singleRegion) : key.regionsIn(pixel);
			for (String region : regions) {
				pixelOfUnit.add(pixel);
				regionOfUnit.add(region);
			}
		}
		firstUnitOfPixel[pixels] = pixelOfUnit.size();

		return new DecisionUnits(key, pixelOfUnit.stream().mapToInt(Integer::intValue).toArray(),
				regionOfUnit.toArray(new String[0]), firstUnitOfPixel, singleRegion);
	}

	/** How many units there are. */
	public int size() {
		return pixelOfUnit.length;
	}

	/** A unit's pixel: the index into every {@code ReactYearData} and {@code CropSurfaces} array. */
	public int pixel(int unit) {
		return pixelOfUnit[unit];
	}

	/** The region whose prices a unit uses. */
	public String region(int unit) {
		return regionOfUnit[unit];
	}

	/**
	 * The unit a CRAFTY cell takes its answers from: the cell's pixel, in the cell's region. Phase 5 uses
	 * this to hand react's values to the model's cells.
	 *
	 * @param cellId a cell as core names it, {@code "x,y"}
	 * @return the unit, or -1 if the cell key does not list the cell
	 */
	public int unitOf(String cellId) {
		int pixel = key.pixelOf(cellId);
		if (pixel < 0) {
			return -1;
		}
		int first = firstUnitOfPixel[pixel];
		if (singleRegion != null) {
			return first;
		}
		String region = key.regionOfCell(cellId);
		for (int unit = first; unit < firstUnitOfPixel[pixel + 1]; unit++) {
			if (regionOfUnit[unit].equals(region)) {
				return unit;
			}
		}
		// Cannot happen: the units were built from the regions of this pixel's cells.
		throw new IllegalStateException("Cell " + cellId + " has no unit for its region " + region);
	}

	@Override
	public String toString() {
		return "DecisionUnits[" + size() + " units in " + key.grid().size() + " pixels]";
	}
}
