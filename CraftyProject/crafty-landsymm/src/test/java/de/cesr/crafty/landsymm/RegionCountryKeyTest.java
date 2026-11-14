package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionCountryKeyTest {

	@TempDir
	Path tempDir;

	private static final String HEADER = "\"X\",\"Y\",\"Region_Code\",\"area\",\"PLUM_country\"\n";

	private Path gis(String rows) throws IOException {
		Path file = tempDir.resolve("global_Regions.csv");
		Files.writeString(file, HEADER + rows);
		return file;
	}

	@Test
	void distinctPairsAreKeptBothWaysFromQuotedRows() throws IOException {
		Path file = gis("1375,85,\"Canada\",1061.8,\"Canada\"\n1376,85,\"Canada\",1061.8,\"Canada\"\n"
				+ "10,20,\"Italy_and_Malta\",500,\"Italy & Malta\"\n11,20,\"\",500,\"\"\n");

		RegionCountryKey key = RegionCountryKey.read(file);

		assertEquals(Set.of("Canada", "Italy_and_Malta"), key.regions());
		assertEquals("Italy & Malta", key.country("Italy_and_Malta"));
		assertEquals("Italy_and_Malta", key.region("Italy & Malta"));
		assertNull(key.country("Chile"));
		assertEquals(file, key.file());
	}

	@Test
	void aRegionWithTwoCountriesStopsTheRead() throws IOException {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> RegionCountryKey.read(gis("1,1,\"Sudan\",1,\"Sudan\"\n2,1,\"Sudan\",1,\"South Sudan\"\n")));

		assertTrue(e.getMessage().contains("region Sudan has more than one PLUM_country"), e.getMessage());
	}

	@Test
	void aCountryWithTwoRegionsStopsTheRead() throws IOException {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> RegionCountryKey.read(gis("1,1,\"China\",1,\"China\"\n2,1,\"Taiwan\",1,\"China\"\n")));

		assertTrue(e.getMessage().contains("PLUM country China is more than one region"), e.getMessage());
	}

	@Test
	void aRegionWithABlankCountryStopsTheRead() throws IOException {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> RegionCountryKey.read(gis("1,1,\"Chile\",1,\"Chile\"\n2,1,\"Peru\",1,\"\"\n")));

		assertTrue(e.getMessage().contains("blank PLUM_country") && e.getMessage().contains("Peru"), e.getMessage());
	}

	@Test
	void aMissingColumnStopsTheRead() throws IOException {
		Path file = tempDir.resolve("old_Regions.csv");
		Files.writeString(file, "\"X\",\"Y\",\"Region_Code\"\n1,1,\"Chile\"\n");

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> RegionCountryKey.read(file));

		assertTrue(e.getMessage().contains("PLUM_country"), e.getMessage());
	}

	@Test
	void aCountryNameWithACommaOrAQuoteStopsTheRead() throws IOException {
		CouplingInputException e = assertThrows(CouplingInputException.class, () -> RegionCountryKey
				.read(gis("1,1,\"Korea\",1,\"Korea, Republic of\"\n2,1,\"Cote\",1,\"C\"\"ote\"\n3,1,\"Chile\",1,\"Chile\"\n")));

		assertEquals(1, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("comma or a double quote"), e.getMessage());
		assertTrue(e.getMessage().contains("[C\"ote, Korea, Republic of]"), e.getMessage());
	}

	@Test
	void aFileWithNoRegionsStopsTheRead() throws IOException {
		assertThrows(CouplingInputException.class, () -> RegionCountryKey.read(gis("1,1,\"\",1,\"\"\n")));
	}
}
