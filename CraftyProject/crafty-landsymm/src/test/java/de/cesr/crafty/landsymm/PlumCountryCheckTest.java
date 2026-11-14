package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlumCountryCheckTest {

	@TempDir
	Path tempDir;

	private RegionCountryKey key(String... regionCountry) throws Exception {
		StringBuilder text = new StringBuilder("\"X\",\"Y\",\"Region_Code\",\"PLUM_country\"\n");
		for (int i = 0; i < regionCountry.length; i += 2) {
			text.append(i).append(",1,\"").append(regionCountry[i]).append("\",\"").append(regionCountry[i + 1])
					.append("\"\n");
		}
		Path gis = tempDir.resolve("global_Regions.csv");
		Files.writeString(gis, text);
		return RegionCountryKey.read(gis);
	}

	@Test
	void theSameCountriesPass() throws Exception {
		PlumYearOutputs rows = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2030);

		assertEquals(List.of(), PlumCountryCheck.problems(rows, ProductionFileTest.threeCountryKey(tempDir)));
	}

	@Test
	void aCraftyCountryMissingFromPlumAndAPlumCountryCraftyLacksAreBothNamed() throws Exception {
		PlumYearOutputs rows = PlumYearOutputs.read(PlumYearOutputsTest.all1990Extract(), 2030);

		List<String> problems = PlumCountryCheck.problems(rows,
				key("Tanzania", "Tanzania", "Argentina", "Argentina", "UK", "United Kingdom"));

		assertEquals(2, problems.size(), String.join("\n", problems));
		assertTrue(problems.get(0).contains("has countries CRAFTY's region key doesn't: [Senegal Gambia & Cabo Verde]"),
				problems.get(0));
		assertTrue(problems.get(1).contains("has no rows for CRAFTY's countries [United Kingdom]"), problems.get(1));
		assertTrue(problems.get(1).contains("countries_excluded.csv"), problems.get(1));
	}
}
