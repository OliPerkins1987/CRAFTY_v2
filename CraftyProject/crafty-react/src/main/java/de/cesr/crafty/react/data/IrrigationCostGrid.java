package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.List;

/**
 * The relative cost of irrigation water in each pixel, from {@code Irrigation_cost.csv}
 * ({@code Lon,Lat,irrigation_cost}). It is an aridity index between 0 and 1 that scales the base water
 * price ({@code Water} in {@code global_costs.csv}):
 *
 * <pre>
 * irrigation cost ($/ha) = water applied (m³/ha) × index × Water ($/m³)
 * </pre>
 *
 * The file does not change with scenario or year, so it is read once.
 */
public final class IrrigationCostGrid {

	public static final String COLUMN = "irrigation_cost";

	private final float[] index;

	private IrrigationCostGrid(float[] index) {
		this.index = index;
	}

	public static IrrigationCostGrid load(Path file, LpjGrid grid) {
		float[] index = LpjFileReader.read(file, grid, List.of(COLUMN), 1.0)[0];
		for (int pixel = 0; pixel < index.length; pixel++) {
			if (index[pixel] < 0 || index[pixel] > 1) {
				throw new ReactInputException(file + ": " + COLUMN + " must be between 0 and 1, but pixel "
						+ grid.describe(pixel) + " has " + index[pixel]);
			}
		}
		return new IrrigationCostGrid(index);
	}

	/** The index for a pixel. */
	public double at(int pixel) {
		return index[pixel];
	}
}
