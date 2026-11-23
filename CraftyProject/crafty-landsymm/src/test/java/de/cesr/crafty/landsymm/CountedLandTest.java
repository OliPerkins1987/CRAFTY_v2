package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.crafty.Aft;

class CountedLandTest {

	@TempDir
	Path tempDir;

	private Path metaData(String column, String... rows) throws Exception {
		StringBuilder text = new StringBuilder("Label,Type,Category," + column + ",Name\n");
		for (String row : rows) {
			text.append(row).append('\n');
		}
		return Files.writeString(tempDir.resolve("AFTsMetaData.csv"), text);
	}

	@Test
	void theServicesColumnGivesEachOwnersShareAndBlankIs0() throws Exception {
		Path file = metaData("biodiversity_counted_land", "Conservation_Nonex,AFT,Nonex,1,x",
				"AgriEcoC3C,AFT,Agri_Crops,0.2,x", "IntAgriC3C,AFT,Agri_Crops,,x", "UL,Mask,Uncategorized,1,x");

		CountedLand counted = CountedLand.read(file, "Biodiversity");

		assertEquals("biodiversity_counted_land", counted.column());
		assertEquals(1.0, counted.fraction(new Aft("Conservation_Nonex")), 0.0);
		assertEquals(0.2, counted.fraction(new Aft("AgriEcoC3C")), 0.0);
		assertEquals(0.0, counted.fraction(new Aft("IntAgriC3C")), 0.0, "blank");
		assertEquals(1.0, counted.fraction(new Aft("UL")), 0.0, "a mask row counts like any other");
		assertEquals(0.0, counted.fraction(new Aft("Abandoned")), 0.0, "CRAFTY's built-in Abandoned isn't listed");
		assertEquals(0.0, counted.fraction(null), 0.0, "no owner");
		assertEquals(Map.of("AgriEcoC3C", 0.2, "Conservation_Nonex", 1.0, "UL", 1.0), counted.counted());
	}

	@Test
	void aValueThatIsntBlankOrFrom0To1Stops() throws Exception {
		Path file = metaData("biodiversity_counted_land", "A,AFT,c,1.5,x", "B,AFT,c,-0.1,x", "C,AFT,c,lots,x",
				"D,AFT,c,NaN,x", "E,AFT,c,1,x");

		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> CountedLand.read(file, "Biodiversity"));

		assertEquals(4, e.getProblems().size(), e.getMessage());
		assertTrue(e.getProblems().get(0).contains("line 2 (A): biodiversity_counted_land must be blank or a number "
				+ "from 0 to 1, got \"1.5\""), e.getMessage());
		assertTrue(e.getProblems().get(3).contains("line 5 (D)"), e.getMessage());
	}

	@Test
	void aMissingColumnOrNothingCountingStops() throws Exception {
		Path noColumn = metaData("Pays_stock_cost", "A,AFT,c,1,x");
		CouplingInputException missing = assertThrows(CouplingInputException.class,
				() -> CountedLand.read(noColumn, "Biodiversity"));
		assertTrue(missing.getMessage().contains("has no biodiversity_counted_land column"), missing.getMessage());

		Path none = metaData("biodiversity_counted_land", "A,AFT,c,0,x", "B,AFT,c,,x");
		CouplingInputException nothing = assertThrows(CouplingInputException.class,
				() -> CountedLand.read(none, "Biodiversity"));
		assertTrue(nothing.getMessage().contains("no row has a biodiversity_counted_land above 0"), nothing.getMessage());
	}

	@Test
	void eachServiceHasItsOwnColumn() {
		assertEquals("carbon_counted_land", CountedLand.column("Carbon"));
	}
}
