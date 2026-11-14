package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradeCapitalMapTest {

	@TempDir
	Path tempDir;

	/** A cut of the global Capitals.csv with the PLUM_name column filled for the 9 trade capitals. */
	static Path capitals() throws URISyntaxException {
		return Paths.get(TradeCapitalMapTest.class.getResource("/capitals/Capitals.csv").toURI());
	}

	/** The fixture with pairs of (find, replace) applied. */
	private Path capitalsWith(String... findReplace) throws IOException, URISyntaxException {
		String text = Files.readString(capitals(), StandardCharsets.UTF_8);
		for (int i = 0; i < findReplace.length; i += 2) {
			assertTrue(text.contains(findReplace[i]), "fixture has: " + findReplace[i]);
			text = text.replace(findReplace[i], findReplace[i + 1]);
		}
		Path file = tempDir.resolve("Capitals.csv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void theNineTradeCapitalsAreReadInFileOrder() throws Exception {
		TradeCapitalMap map = TradeCapitalMap.read(capitals());

		assertEquals(List.of("c3_cereals_trade", "c3_fruits_veg_trade", "c4_crops_trade", "n_fix_oil_crops_trade",
				"oil_crops_trade", "pulses_trade", "rice_trade", "starchy_roots_trade", "sugar_trade"),
				List.copyOf(map.cropByCapital().keySet()));
		assertEquals("wheat", map.cropByCapital().get("c3_cereals_trade"));
		assertEquals("starchyRoots", map.cropByCapital().get("starchy_roots_trade"));
		assertEquals(List.of(), map.untagged());
	}

	@Test
	void tradeCropsArePlumsCropsLessPastureWoodAndEnergyCrops() {
		assertEquals(List.of("wheat", "maize", "rice", "oilcropsNFix", "oilcropsOther", "pulses", "starchyRoots",
				"fruitveg", "sugar"), TradeCapitalMap.TRADE_CROPS);
	}

	@Test
	void aMissingColumnStopsTheRead() throws Exception {
		Path file = tempDir.resolve("Capitals.csv");
		Files.writeString(file, "Label,Name,Type,Description\nc3_cereals_trade,x,Capital,x\n");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> TradeCapitalMap.read(file));

		assertTrue(e.getMessage().contains("PLUM_name"), e.getMessage());
	}

	@Test
	void everyBadRowIsListedTogether() throws Exception {
		Path file = capitalsWith("Calculated per country from HILDA+,", "Calculated per country from HILDA+,rice",
				"C3 pulses,pulses", "C3 pulses,pasture", "C3 sugar,sugar", "C3 sugar,wheat");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> TradeCapitalMap.read(file));

		assertEquals(3, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("(pulses_trade): PLUM_name \"pasture\" is not a crop"), e.getMessage());
		assertTrue(e.getMessage().contains("(sugar_trade): names wheat, which c3_cereals_trade already follows"),
				e.getMessage());
		assertTrue(e.getMessage().contains("(biodiversity_suit): has a PLUM_name but its Type is Suitability"),
				e.getMessage());
	}

	@Test
	void aTradeCapitalWithoutAPlumNameIsListedAsUntagged() throws Exception {
		TradeCapitalMap map = TradeCapitalMap.read(capitalsWith("C3 rice,rice", "C3 rice,"));

		assertEquals(8, map.cropByCapital().size());
		assertEquals(List.of("rice_trade"), map.untagged());
	}

	@Test
	void noPlumNameAtAllStopsTheRead() throws Exception {
		Path file = tempDir.resolve("Capitals.csv");
		Files.writeString(file, "Label,Name,Type,Description,PLUM_name\nHealth,x,Capital,x,\n");

		assertTrue(assertThrows(CouplingInputException.class, () -> TradeCapitalMap.read(file)).getMessage()
				.contains("no capital has a PLUM_name"));
	}
}
