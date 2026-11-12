package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlumYearOutputsTest {

	@TempDir
	Path tempDir;

	/**
	 * all1990's prices.txt rows for 2029-2030, and its domestic.txt and wood.txt rows for 2029-2030 for three
	 * countries (Senegal Gambia & Cabo Verde, Tanzania, Argentina), with the headers exactly as PLUM writes them.
	 */
	static Path all1990Extract() throws URISyntaxException {
		return Paths.get(PlumYearOutputsTest.class.getResource("/plum_all1990_extract").toURI());
	}

	/** A copy of the extract in the temporary folder, to break. */
	private Path copyOfExtract() throws IOException, URISyntaxException {
		Path copy = tempDir.resolve("plum");
		Files.createDirectories(copy);
		for (String f : new String[] { "prices.txt", "domestic.txt", "wood.txt" }) {
			Files.copy(all1990Extract().resolve(f), copy.resolve(f), StandardCopyOption.REPLACE_EXISTING);
		}
		return copy;
	}

	@Test
	void readsOnlyTheYearAsked() throws Exception {
		PlumYearOutputs y2029 = PlumYearOutputs.read(all1990Extract(), 2029);
		PlumYearOutputs y2030 = PlumYearOutputs.read(all1990Extract(), 2030);

		assertEquals(2029, y2029.year());
		assertEquals(0.10112894, y2029.price("wheat"));
		assertEquals(0.13263045, y2029.price("ruminants"));
		assertEquals(0.02986134, y2029.price("wood"));
		assertNotEquals(y2029.price("wheat"), y2030.price("wheat"));
		assertTrue(Double.isNaN(y2029.price("barley")), "no such row");
		assertEquals(Set.of("Argentina", "Senegal Gambia & Cabo Verde", "Tanzania"), y2029.countries());
	}

	@Test
	void readsDomesticRowsUnderTrimmedHeaders() throws Exception {
		PlumYearOutputs y = PlumYearOutputs.read(all1990Extract(), 2029);

		PlumYearOutputs.Trade wheat = y.trade("Tanzania", "wheat");
		assertEquals(0.1113, wheat.production());
		assertEquals(0.0612, wheat.netImports());
		assertEquals(0.1319, wheat.importPrice());
		assertEquals(0.1013, wheat.exportPrice());
		assertEquals(5.8947, y.domestic("Tanzania", "pasture").ruminantFeed(), "grass grazed");
		assertEquals(-27.3611, y.trade("Argentina", "ruminants").netImports());
		assertNull(y.domestic("Chile", "wheat"));
	}

	@Test
	void woodTradeComesFromWoodTxt() throws Exception {
		PlumYearOutputs y = PlumYearOutputs.read(all1990Extract(), 2029);

		PlumYearOutputs.Trade wood = y.trade("Senegal Gambia & Cabo Verde", "wood");
		assertEquals(5.9479, wood.production());
		assertEquals(0.0476, wood.netImports());
		assertEquals(0.0433, wood.importPrice());
		assertEquals(0.0299, wood.exportPrice());
		assertEquals(0.7539, y.wood("Senegal Gambia & Cabo Verde").roundwoodDemand());
		assertEquals(5.2416, y.wood("Senegal Gambia & Cabo Verde").woodfuelDemand());
	}

	@Test
	void aYearWithNoRowsStopsTheReadForEveryFile() throws Exception {
		CouplingInputException e = assertThrows(CouplingInputException.class,
				() -> PlumYearOutputs.read(all1990Extract(), 2031));

		assertEquals(3, e.getProblems().size(), e.getMessage());
		assertTrue(e.getMessage().contains("prices.txt has no rows for 2031"), e.getMessage());
	}

	@Test
	void aMissingFileStopsTheRead() throws Exception {
		Path copy = copyOfExtract();
		Files.delete(copy.resolve("wood.txt"));

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> PlumYearOutputs.read(copy, 2029));

		assertTrue(e.getMessage().contains("wood.txt not found"), e.getMessage());
	}

	@Test
	void aRowAppearingTwiceInTheYearStopsTheRead() throws Exception {
		Path copy = copyOfExtract();
		Files.writeString(copy.resolve("domestic.txt"),
				"2029,Tanzania,wheat,NaN,0.1113,0.0000,0.1319,0.1013,0.1122,0.0612,0.0081,0.0000,0.0000,0.0000,0.0000\n",
				java.nio.file.StandardOpenOption.APPEND);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> PlumYearOutputs.read(copy, 2029));

		assertTrue(e.getMessage().contains("Tanzania, wheat appears twice in 2029"), e.getMessage());
	}

	@Test
	void aValueThatIsNotANumberStopsTheRead() throws Exception {
		Path copy = copyOfExtract();
		String text = Files.readString(copy.resolve("prices.txt"))
				.replace("2029,wheat,145.9,152.3,0.10112894,", "2029,wheat,145.9,152.3,cheap,");
		Files.writeString(copy.resolve("prices.txt"), text);

		CouplingInputException e = assertThrows(CouplingInputException.class, () -> PlumYearOutputs.read(copy, 2029));

		assertTrue(e.getMessage().contains("New export price") && e.getMessage().contains("cheap"), e.getMessage());
	}

	@Test
	void naNValuesAreKeptForTheUserToCheck() throws Exception {
		Path copy = copyOfExtract();
		String text = Files.readString(copy.resolve("prices.txt"))
				.replace("2029,wheat,145.9,152.3,0.10112894,", "2029,wheat,145.9,152.3,NaN,");
		Files.writeString(copy.resolve("prices.txt"), text);

		assertTrue(Double.isNaN(PlumYearOutputs.read(copy, 2029).price("wheat")));
	}
}
