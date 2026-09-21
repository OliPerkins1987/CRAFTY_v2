package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IrrigationCostGridTest {

	@TempDir
	Path dir;

	private LpjGrid grid;

	@BeforeEach
	void setUp() {
		grid = CellKey.load(ReactToyData.cellKey(dir)).grid();
	}

	@Test
	void theIndexIsReadForEachPixelWithoutConversion() {
		Path file = ReactToyData.write(dir, "Irrigation_cost.csv", "\"Lon\",\"Lat\",\"irrigation_cost\"",
				ReactToyData.PIXEL_A + ",0.489673912525177",
				ReactToyData.PIXEL_B + ",1",
				ReactToyData.PIXEL_NO_CELLS + ",0.409");

		IrrigationCostGrid costs = IrrigationCostGrid.load(file, grid);

		assertEquals(0.48967, costs.at(grid.indexOf(-91.25, 17.75)), 1e-5);
		assertEquals(1.0, costs.at(grid.indexOf(-121.75, 37.25)));
	}

	@Test
	void theEndsOfTheRangeAreAllowed() {
		Path file = ReactToyData.write(dir, "Irrigation_cost.csv", "Lon,Lat,irrigation_cost",
				ReactToyData.PIXEL_A + ",0", ReactToyData.PIXEL_B + ",1");

		IrrigationCostGrid costs = IrrigationCostGrid.load(file, grid);

		assertEquals(0.0, costs.at(grid.indexOf(-91.25, 17.75)));
	}

	@Test
	void anIndexOutsideZeroToOneIsAnError() {
		Path file = ReactToyData.write(dir, "Irrigation_cost.csv", "Lon,Lat,irrigation_cost",
				ReactToyData.PIXEL_A + ",0.5", ReactToyData.PIXEL_B + ",1.2");

		ReactInputException e = assertThrows(ReactInputException.class, () -> IrrigationCostGrid.load(file, grid));

		assertTrue(e.getMessage().contains("between 0 and 1") && e.getMessage().contains("(-121.75, 37.25)"),
				e.getMessage());
	}

	@Test
	void aMissingPixelIsAnError() {
		Path file = ReactToyData.write(dir, "Irrigation_cost.csv", "Lon,Lat,irrigation_cost",
				ReactToyData.PIXEL_A + ",0.5");

		assertThrows(ReactInputException.class, () -> IrrigationCostGrid.load(file, grid));
	}
}
