package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactBaseCostsTest {

	@TempDir
	Path dir;

	@Test
	void theBaseUnitCostsAndServiceCostsAreRead() {
		ReactBaseCosts costs = ReactBaseCosts.load(ReactToyData.globalCosts(dir));

		assertEquals(1.08, costs.get(ReactBaseCosts.NFERT));
		assertEquals(0.5, costs.get(ReactBaseCosts.WATER), "React keeps the Water row, which core skips");
		assertEquals(500, costs.get(ReactBaseCosts.STOCKING));
		assertEquals(50, costs.get("Pasture"));
		assertEquals(0, costs.get("Carbon"));
	}

	@Test
	void missingListsTheAbsentItemsInTheOrderGiven() {
		ReactBaseCosts costs = ReactBaseCosts.load(ReactToyData.globalCosts(dir));

		assertEquals(List.of("C4crops", "Hardwood"),
				costs.missing(List.of("Nfert", "C4crops", "Water", "Hardwood")));
	}

	@Test
	void aFileWithoutWaterStillLoads() {
		// Water is only needed when irrigation is switched on; that check belongs to the caller.
		Path file = ReactToyData.write(dir, "g.csv", "Item,Cost", "Nfert,1.08", "Stocking,500");

		ReactBaseCosts costs = ReactBaseCosts.load(file);

		assertFalse(costs.has(ReactBaseCosts.WATER));
		ReactInputException e = assertThrows(ReactInputException.class, () -> costs.get(ReactBaseCosts.WATER));
		assertTrue(e.getMessage().contains("Water"), e.getMessage());
	}

	@Test
	void aNegativeCostIsAnError() {
		Path file = ReactToyData.write(dir, "g.csv", "Item,Cost", "Nfert,-1");

		ReactInputException e = assertThrows(ReactInputException.class, () -> ReactBaseCosts.load(file));

		assertTrue(e.getMessage().contains("negative") && e.getMessage().contains("line 2"), e.getMessage());
	}

	@Test
	void aBlankCostIsAnErrorNotZero() {
		Path file = ReactToyData.write(dir, "g.csv", "Item,Cost", "Water,");

		assertThrows(ReactInputException.class, () -> ReactBaseCosts.load(file));
	}

	@Test
	void aRepeatedItemIsAnError() {
		Path file = ReactToyData.write(dir, "g.csv", "Item,Cost", "Water,0.5", "Water,0.6");

		ReactInputException e = assertThrows(ReactInputException.class, () -> ReactBaseCosts.load(file));

		assertTrue(e.getMessage().contains("more than once"), e.getMessage());
	}

	@Test
	void theItemAndCostColumnsAreRequired() {
		Path file = ReactToyData.write(dir, "g.csv", "Name,Value", "Water,0.5");

		assertThrows(ReactInputException.class, () -> ReactBaseCosts.load(file));
	}
}
