package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LpjGridTest {

	@Test
	void pixelsAreNumberedInTheOrderTheyAreFirstAdded() {
		LpjGrid.Builder builder = new LpjGrid.Builder();

		assertEquals(0, builder.add(-91.25, 17.75));
		assertEquals(1, builder.add(-121.75, 37.25));
		assertEquals(0, builder.add(-91.25, 17.75), "A pixel added again keeps its index");
		LpjGrid grid = builder.build();

		assertEquals(2, grid.size());
		assertEquals(0, grid.indexOf(-91.25, 17.75));
		assertEquals(1, grid.indexOf(-121.75, 37.25));
		assertEquals(-121.75, grid.lon(1));
		assertEquals(37.25, grid.lat(1));
		assertEquals("(-91.25, 17.75)", grid.describe(0));
	}

	@Test
	void coordinatesMatchAfterRoundingToThreeDecimalPlaces() {
		LpjGrid.Builder builder = new LpjGrid.Builder();
		builder.add(-91.25, 17.75);
		LpjGrid grid = builder.build();

		assertEquals(0, grid.indexOf(-91.2500001, 17.7499999), "Tiny differences between files are ignored");
		assertEquals(0, grid.indexOf(-91.2504, 17.7504));
		assertEquals(-1, grid.indexOf(-91.251, 17.75), "A difference in the third decimal place is a different pixel");
	}

	@Test
	void finerGridsWorkTheSameWay() {
		// 0.25 degree centres end in .125/.375/.625/.875; 0.125 degree centres need four decimals but
		// are still 0.125 apart, far more than the rounding.
		LpjGrid.Builder builder = new LpjGrid.Builder();
		double[] quarter = { 10.125, 10.375, 10.625, 10.875 };
		double[] eighth = { 20.0625, 20.1875 };
		for (double lon : quarter) {
			builder.add(lon, -45.125);
		}
		for (double lon : eighth) {
			builder.add(lon, -45.125);
		}
		LpjGrid grid = builder.build();

		assertEquals(6, grid.size());
		for (int i = 0; i < quarter.length; i++) {
			assertEquals(i, grid.indexOf(quarter[i], -45.125));
		}
		assertNotEquals(grid.indexOf(eighth[0], -45.125), grid.indexOf(eighth[1], -45.125));
	}

	@Test
	void theSignOfZeroDoesNotMatter() {
		assertEquals(LpjGrid.pixelKey(0.0, 0.0), LpjGrid.pixelKey(-0.0, -0.0));
	}

	@Test
	void aPointOffTheGlobeIsRefused() {
		// Most likely Lon and Lat swapped, or the wrong column read.
		assertThrows(IllegalArgumentException.class, () -> LpjGrid.pixelKey(17.75, -91.25));
		assertThrows(IllegalArgumentException.class, () -> LpjGrid.pixelKey(180.25, 0.25));
		assertThrows(IllegalArgumentException.class, () -> LpjGrid.pixelKey(Double.NaN, 0.25));
	}

	@Test
	void aPixelOutsideTheGridHasNoIndex() {
		LpjGrid.Builder builder = new LpjGrid.Builder();
		builder.add(0.25, 0.25);

		assertEquals(-1, builder.build().indexOf(0.75, 0.25));
	}
}
