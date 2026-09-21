package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YearFileFinderTest {

	@TempDir
	Path dir;

	@Test
	void anyFileNameContainingTheYearIsFound() {
		Path capitals = ReactToyData.write(dir, "capitals/EU_capitals_ssp126_2020.csv", "Lon,Lat");
		Path crops = ReactToyData.write(dir, "crops/Suit_Agri_Crops_2020.csv", "Lon,Lat");

		assertEquals(capitals, YearFileFinder.find(dir.resolve("capitals"), 2020));
		assertEquals(crops, YearFileFinder.find(dir.resolve("crops"), 2020));
	}

	@Test
	void theYearMustBeANumberOnItsOwn() {
		assertTrue(YearFileFinder.nameHasYear("x2020y.csv", 2020));
		assertTrue(YearFileFinder.nameHasYear("2020.csv", 2020));
		assertTrue(YearFileFinder.nameHasYear("a_2020_b.csv", 2020));
		assertFalse(YearFileFinder.nameHasYear("data_20201.csv", 2020));
		assertFalse(YearFileFinder.nameHasYear("12020.csv", 2020));
		assertFalse(YearFileFinder.nameHasYear("data_2021.csv", 2020));
	}

	@Test
	void filesForOtherYearsAndNonCsvFilesAreIgnored() {
		Path wanted = ReactToyData.write(dir, "f/caps_2020.csv", "Lon,Lat");
		ReactToyData.write(dir, "f/caps_2021.csv", "Lon,Lat");
		ReactToyData.write(dir, "f/notes_2020.txt", "not data");

		assertEquals(wanted, YearFileFinder.find(dir.resolve("f"), 2020));
	}

	@Test
	void aYearWithNoFileIsAnError() {
		ReactToyData.write(dir, "f/caps_2021.csv", "Lon,Lat");

		ReactInputException e = assertThrows(ReactInputException.class, () -> YearFileFinder.find(dir.resolve("f"), 2020));

		assertTrue(e.getMessage().contains("No .csv file for 2020"), e.getMessage());
	}

	@Test
	void aYearWithTwoFilesIsAnErrorThatNamesBoth() {
		ReactToyData.write(dir, "f/caps_2020.csv", "Lon,Lat");
		ReactToyData.write(dir, "f/caps_2020_old.csv", "Lon,Lat");

		ReactInputException e = assertThrows(ReactInputException.class, () -> YearFileFinder.find(dir.resolve("f"), 2020));

		assertTrue(e.getMessage().contains("caps_2020.csv") && e.getMessage().contains("caps_2020_old.csv"),
				e.getMessage());
	}

	@Test
	void findAllReturnsEveryYearInOrder() {
		for (int year = 2020; year <= 2022; year++) {
			ReactToyData.write(dir, "f/caps_" + year + ".csv", "Lon,Lat");
		}

		Map<Integer, Path> files = YearFileFinder.findAll(dir.resolve("f"), 2020, 2022);

		assertEquals(List.of(2020, 2021, 2022), List.copyOf(files.keySet()));
		assertEquals(dir.resolve("f/caps_2021.csv"), files.get(2021));
	}

	@Test
	void findAllReportsEveryProblemInOneMessage() {
		ReactToyData.write(dir, "f/caps_2020.csv", "Lon,Lat");
		ReactToyData.write(dir, "f/caps_2022_a.csv", "Lon,Lat");
		ReactToyData.write(dir, "f/caps_2022_b.csv", "Lon,Lat");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> YearFileFinder.findAll(dir.resolve("f"), 2020, 2022));

		assertTrue(e.getMessage().contains("No .csv file for 2021"), e.getMessage());
		assertTrue(e.getMessage().contains("More than one .csv file for 2022"), e.getMessage());
	}

	@Test
	void filesForYearsTheRunDoesNotUseAreIgnored() {
		// They only warn (G12); the run carries on with the years it needs.
		for (int year = 2018; year <= 2022; year++) {
			ReactToyData.write(dir, "f/caps_" + year + ".csv", "Lon,Lat");
		}

		Map<Integer, Path> files = YearFileFinder.findAll(dir.resolve("f"), 2020, 2021);

		assertEquals(List.of(2020, 2021), List.copyOf(files.keySet()));
	}

	@Test
	void aMissingFolderIsAnError() {
		ReactInputException e = assertThrows(ReactInputException.class,
				() -> YearFileFinder.find(dir.resolve("nope"), 2020));

		assertTrue(e.getMessage().contains("Folder not found"), e.getMessage());
	}
}
