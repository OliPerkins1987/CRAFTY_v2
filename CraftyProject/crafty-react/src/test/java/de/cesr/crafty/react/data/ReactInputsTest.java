package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactInputsTest {

	@TempDir
	Path dir;

	private final ReactConfig config = ReactConfig.defaults();

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
	}

	private ReactInputs create(ReactToyData.Context context) {
		return ReactInputs.create(config, context.build());
	}

	@Test
	void creatingItRunsTheChecksAndKeepsWhatTheyLoaded() {
		ReactInputs inputs = create(ReactToyData.context(dir));

		assertEquals(3, inputs.checked().parameters().reactive().size());
		assertEquals(3, inputs.checked().cellKey().cellCount());
		assertNotNull(inputs.checked().irrigationCost());
		assertEquals(2020, inputs.context().firstYear(), "The context is kept as given");
	}

	@Test
	void aProjectThatCannotBeUsedStopsThereWithEveryProblem() {
		ReactInputException e = assertThrows(ReactInputException.class,
				() -> create(ReactToyData.context(dir).cell("9,9").withoutAft("AF")));

		assertTrue(e.getMessage().contains("2 problem(s)"), e.getMessage());
	}

	@Test
	void aYearIsLoadedOnceAndKept() {
		ReactInputs inputs = create(ReactToyData.context(dir));

		ReactYearData first = inputs.forYear(2020);
		ReactYearData again = inputs.forYear(2020);

		assertSame(first, again, "The same year is not read twice");
		assertEquals(2020, first.year());
	}

	@Test
	void theYearBeforeIsReleasedWhenTheNextIsAskedFor() throws IOException {
		ReactInputs inputs = create(ReactToyData.context(dir));
		ReactYearData first = inputs.forYear(2020);

		ReactYearData second = inputs.forYear(2021);

		assertNotSame(first, second);
		assertEquals(2021, second.year());
		assertSame(second, inputs.currentYear(), "Only the current year is held");
		// Going back re-reads the file rather than returning the old object.
		assertNotSame(first, inputs.forYear(2020));
		// And if that file has gone, going back fails rather than quietly using stale data.
		Files.delete(dir.resolve("worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_2021.csv"));
		assertThrows(ReactInputException.class, () -> inputs.forYear(2021));
	}

	@Test
	void aYearOutsideTheRunIsRefused() {
		ReactInputs inputs = create(ReactToyData.context(dir));

		ReactInputException e = assertThrows(ReactInputException.class, () -> inputs.forYear(2019));

		assertTrue(e.getMessage().contains("2020 to 2021"), e.getMessage());
	}

	@Test
	void withNoElementReactiveTheChecksStillRunButNoDataIsLoaded() throws IOException {
		ReactInputs inputs = create(ReactToyData.context(dir).off(ReactElement.values()));

		assertEquals(3, inputs.checked().parameters().reactive().size(), "The checks still ran");
		ReactYearData year = inputs.forYear(2020);
		assertTrue(year.crops().isEmpty());
		assertTrue(year.capitals().isEmpty());
	}
}
