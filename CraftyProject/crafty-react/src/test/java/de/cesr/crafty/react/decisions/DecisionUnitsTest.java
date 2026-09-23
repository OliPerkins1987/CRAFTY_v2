package de.cesr.crafty.react.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.react.data.CellKey;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactToyData;

/**
 * The toy key has cells (1,1) North and (1,2) South in pixel A, which straddles a border, and cell (2,1)
 * North in pixel B.
 */
class DecisionUnitsTest {

	@TempDir
	Path dir;

	private CellKey key;
	private int pixelA;
	private int pixelB;

	@BeforeEach
	void setUp() {
		key = CellKey.load(ReactToyData.cellKey(dir));
		pixelA = key.grid().indexOf(-91.25, 17.75);
		pixelB = key.grid().indexOf(-121.75, 37.25);
	}

	private static List<Integer> pixels(DecisionUnits units) {
		List<Integer> pixels = new ArrayList<>();
		for (int unit = 0; unit < units.size(); unit++) {
			pixels.add(units.pixel(unit));
		}
		return pixels;
	}

	private static List<String> regions(DecisionUnits units) {
		List<String> regions = new ArrayList<>();
		for (int unit = 0; unit < units.size(); unit++) {
			regions.add(units.region(unit));
		}
		return regions;
	}

	@Test
	void aPixelStraddlingABorderHasAUnitForEachRegion() {
		DecisionUnits units = DecisionUnits.build(key, Set.of("North", "South"));

		assertEquals(3, units.size());
		assertEquals(List.of(pixelA, pixelA, pixelB), pixels(units));
		assertEquals(List.of("North", "South", "North"), regions(units));
	}

	@Test
	void eachCellTakesTheUnitForItsPixelAndRegion() {
		DecisionUnits units = DecisionUnits.build(key, Set.of("North", "South"));

		assertEquals(0, units.unitOf("1,1"), "pixel A, North");
		assertEquals(1, units.unitOf("1,2"), "pixel A, South");
		assertEquals(2, units.unitOf("2,1"), "pixel B, North");
		assertEquals(-1, units.unitOf("9,9"), "a cell the key does not list");
	}

	@Test
	void inASingleRegionModelEachPixelHasOneUnitPricedInThatRegion() {
		DecisionUnits units = DecisionUnits.build(key, Set.of("World"));

		assertEquals(2, units.size());
		assertEquals(List.of(pixelA, pixelB), pixels(units));
		assertEquals(List.of("World", "World"), regions(units), "The key's own regions are not used");
		assertEquals(0, units.unitOf("1,1"));
		assertEquals(0, units.unitOf("1,2"), "Both of pixel A's cells share its one unit");
		assertEquals(1, units.unitOf("2,1"));
		assertEquals(-1, units.unitOf("9,9"));
	}

	@Test
	void unitsFollowTheOrderOfTheKeyNotTheRegionNames() {
		Path file = ReactToyData.write(dir, "key.csv", "X,Y,LPJ_cell_x,LPJ_cell_y,region",
				"1,1," + ReactToyData.PIXEL_B + ",South",
				"2,1," + ReactToyData.PIXEL_A + ",North",
				"3,1," + ReactToyData.PIXEL_B + ",North",
				"4,1," + ReactToyData.PIXEL_B + ",South");

		DecisionUnits units = DecisionUnits.build(CellKey.load(file), Set.of("North", "South"));

		// Pixel B comes first in the file (pixel 0), and its first cell is in the South.
		assertEquals(List.of(0, 0, 1), pixels(units));
		assertEquals(List.of("South", "North", "North"), regions(units));
		assertEquals(0, units.unitOf("1,1"));
		assertEquals(0, units.unitOf("4,1"), "A later cell joins the unit for its region");
		assertEquals(1, units.unitOf("3,1"));
		assertEquals(2, units.unitOf("2,1"));
	}

	@Test
	void aModelWithNoRegionIsAnError() {
		ReactInputException e = assertThrows(ReactInputException.class, () -> DecisionUnits.build(key, Set.of()));

		assertTrue(e.getMessage().contains("no region"), e.getMessage());
	}
}
