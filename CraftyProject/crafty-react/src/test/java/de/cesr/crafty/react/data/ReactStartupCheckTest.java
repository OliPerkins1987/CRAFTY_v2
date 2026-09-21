package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactStartupCheckTest {

	@TempDir
	Path dir;

	private final ReactConfig config = ReactConfig.defaults();

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
	}

	private ReactStartupCheck.Result run(ReactToyData.Context context) {
		return ReactStartupCheck.run(config, context.build());
	}

	/** Runs the checks expecting failure, and returns the message. */
	private String problems(ReactToyData.Context context) {
		return assertThrows(ReactInputException.class, () -> run(context)).getMessage();
	}

	private static void assertMentions(String message, String... fragments) {
		for (String fragment : fragments) {
			assertTrue(message.contains(fragment), "Expected \"" + fragment + "\" in:\n" + message);
		}
	}

	private void deleteFolder(String relative) throws IOException {
		try (Stream<Path> paths = Files.walk(dir.resolve(relative))) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	// ---- a good project ----

	@Test
	void theToyProjectPassesAndReturnsWhatWasLoaded() {
		ReactStartupCheck.Result result = run(ReactToyData.context(dir));

		assertEquals(List.of("IntC3C_irrig", "ExtC3C", "IntP"),
				result.parameters().reactive().stream().map(AftReactParameters::label).toList());
		assertEquals(3, result.cellKey().cellCount());
		assertEquals(0.5, result.baseCosts().get(ReactBaseCosts.WATER));
		assertNotNull(result.irrigationCost(), "Irrigation is reactive and IntC3C_irrig irrigates");
		assertEquals(Set.of(LpjgType.CROPS, LpjgType.PASTURE), result.suitabilityFiles().keySet());
		assertEquals(dir.resolve("worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_2021.csv"),
				result.suitabilityFiles().get(LpjgType.CROPS).get(2021));
		assertEquals(List.of(2020, 2021), List.copyOf(result.capitalsFiles().keySet()));
	}

	@Test
	void withNoElementReactiveNoYearFilesAreNeeded() throws IOException {
		deleteFolder("worlds/react/suitabilities");
		deleteFolder("worlds/react/capitals");
		deleteFolder("worlds/react/irrigation");

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).off(ReactElement.values()));

		assertTrue(result.suitabilityFiles().isEmpty());
		assertTrue(result.capitalsFiles().isEmpty());
		assertNull(result.irrigationCost());
	}

	// ---- check 1: year files ----

	@Test
	void aMissingYearFileIsReported() throws IOException {
		Files.delete(dir.resolve("worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_2021.csv"));
		Files.delete(dir.resolve("worlds/react/irrigation/ssp126/Runoff_2021.csv"));

		String message = problems(ReactToyData.context(dir));

		assertMentions(message, "No .csv file for 2021", "crops");
		assertMentions(message, "File not found", "Runoff_2021.csv");
	}

	@Test
	void irrigationFilesAreNeededWheneverAReactiveAftIrrigatesEvenWithIrrigationOff() throws IOException {
		// The switch decides whether react changes the water applied, not whether the AFT irrigates:
		// water applied is still min(runoff, demand), which sets the irrigation level in the yield.
		deleteFolder("worlds/react/irrigation");

		String message = problems(ReactToyData.context(dir).off(ReactElement.IRRIGATION));

		assertMentions(message, "Irrigation_demand_2020.csv");
		assertMentions(message, "Runoff_2020.csv");
		assertMentions(message, "Irrigation_cost.csv");
	}

	@Test
	void fertiliserReactiveWithIrrigationNotReactiveIsAllowedAndExplained() {
		// An odd pairing: react then reads irrigation demand at the baseline N, and the model charges its
		// own irrigation costs. The run goes ahead; the log says so. (Checked here by the run passing; the
		// wording lives in ReactStartupCheck.)
		ReactStartupCheck.Result result = run(ReactToyData.context(dir).off(ReactElement.IRRIGATION));

		assertEquals(List.of("IntC3C_irrig"),
				result.irrigatedCrops().stream().map(AftReactParameters::label).toList());
		assertNotNull(result.irrigationCost(), "The index is still loaded: water applied still limits the yield");
	}

	@Test
	void irrigationFilesAreNotNeededWhenNoReactiveAftIrrigates() throws IOException {
		// IntC3C_irrig is the only irrigated AFT in the toy project; core says it is not irrigated here.
		deleteFolder("worlds/react/irrigation");

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).aft("IntC3C_irrig", 200, 0.75, false, false)
				.off(ReactElement.IRRIGATION));

		assertNull(result.irrigationCost());
		assertTrue(result.irrigatedCrops().isEmpty());
	}

	// ---- check 2: the same AFTs in the sheet and in core ----

	@Test
	void theModelsOwnAbandonedAftNeedsNoRow() {
		// The model adds "Abandoned" itself (AFTsLoader), so it is not in AFTsMetaData.csv and the react
		// sheet is not expected to list it.
		ReactStartupCheck.Result result = run(ReactToyData.context(dir).aft(ReactStartupCheck.ABANDONED, 0, 1, false,
				false));

		assertEquals(3, result.parameters().reactive().size());
	}

	@Test
	void abandonedCannotBeReactive() {
		ReactToyData.parameters(dir, "IntC3C_irrig,AFT,1,C3cereals,Prospect,,0,0.9,react_GDP_100,0.2,,",
				"ExtC3C,AFT,1,C3cereals,Capital,react_GDP_50,12,,,,,", "IntP,AFT,1,Pasture,,,,,react_GDP_50,1,0,",
				"IntFodder,AFT,0,,,,,,,,,", "AF,AFT,0,Hardwood,,,,,,,,0.1", "Urban,Mask,0,,,,,,,,,",
				"Abandoned,AFT,1,C3cereals,Prospect,,0,,,,,");

		String message = problems(ReactToyData.context(dir).aft(ReactStartupCheck.ABANDONED, 200, 0.75, false, false));

		assertMentions(message, "Abandoned cannot be reactive");
	}

	// ---- check 12: regions ----

	@Test
	void aProjectWithManyRegionsMustUseRegionsTheModelKnows() {
		// The model has North and East; the key still says South.
		String message = problems(ReactToyData.context(dir).withoutRegion("South").cell("1,2", "East"));

		assertMentions(message, "region(s) [South] are not regions of this model");
	}

	@Test
	void aProjectWithOneRegionDoesNotHaveToMatchTheKey() {
		// Not regionalised: the model has a single region covering every cell, so the key's own regions
		// are not used and not checked. Nothing is reported per cell, however many cells there are.
		ReactStartupCheck.Result result = run(ReactToyData.context(dir).withoutRegion("South")
				.withoutRegion("North").cell("1,1", "EU").cellRegion("1,2", "EU").cellRegion("2,1", "EU"));

		assertEquals(Set.of("North", "South"), result.cellKey().regions(), "The key keeps its own regions");
	}

	@Test
	void theSheetAndCoreMustListTheSameAfts() {
		String message = problems(ReactToyData.context(dir).withoutAft("AF").aft("NewAFT", 0, 1, false, false));

		assertMentions(message, "AFT AF is not in the model's AFTsMetaData.csv");
		assertMentions(message, "has no row for AFT NewAFT");
	}

	// ---- checks 3 and 4, through the sheet ----

	@Test
	void sheetProblemsAreReportedToo() {
		ReactToyData.parameters(dir, "IntC3C_irrig,AFT,1,C3cereals,Prospect,,,0.9,react_GDP_100,0.2,,",
				"ExtC3C,AFT,1,C3cereals,Capital,react_GDP_50,12,,,,,", "IntP,AFT,1,Pasture,,,,,react_GDP_50,1,0,",
				"IntFodder,AFT,0,,,,,,,,,", "AF,AFT,1,Hardwood,,,,,,,,0.1", "Urban,Mask,0,,,,,,,,,");

		assertMentions(problems(ReactToyData.context(dir)), "(AF)", "forestry");
	}

	// ---- check 5: LPJ-GUESS columns in every year ----

	@Test
	void aColumnMissingFromASecondYearFileIsReported() {
		ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/crops/Suit_Agri_Crops_2021.csv",
				"Lon,Lat,CerealsC30,CerealsC30200,CerealsC31000,CerealsC3i0,CerealsC3i1000",
				ReactToyData.PIXEL_A + ",1,1,1,1,1", ReactToyData.PIXEL_B + ",1,1,1,1,1");
		ReactToyData.write(dir, "worlds/react/irrigation/ssp126/Irrigation_demand_2020.csv",
				"Lon,Lat,CerealsC3i0,CerealsC3i1000", ReactToyData.PIXEL_A + ",1,1", ReactToyData.PIXEL_B + ",1,1");
		ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/pasture/Suit_Agri_pastoral_2020.csv",
				"NPP3.Lon,NPP3.Lat,NPP3.Pasture_sum", ReactToyData.PIXEL_A + ",1", ReactToyData.PIXEL_B + ",1");

		String message = problems(ReactToyData.context(dir));

		assertMentions(message, "Suit_Agri_Crops_2021.csv is missing column(s) [CerealsC3i0200]");
		assertMentions(message, "Irrigation_demand_2020.csv is missing column(s) [CerealsC3i0200]");
		assertMentions(message, "Suit_Agri_pastoral_2020.csv is missing column(s) [Lon, Lat, Pasture_sum]");
	}

	// ---- check 6: capitals in every year ----

	@Test
	void aCapitalMissingFromAYearFileIsReported() {
		ReactToyData.write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_2021.csv",
				"Lon,Lat,React_GDP_50,react_GDP_100", ReactToyData.PIXEL_A + ",1,1", ReactToyData.PIXEL_B + ",1,1");

		assertMentions(problems(ReactToyData.context(dir)), "EU_capitals_ssp126_2021.csv is missing column(s) [react_GDP_50]");
	}

	@Test
	void onlyTheCapitalsOfSwitchedOnElementsAreNeeded() {
		// With only fertiliser on, react reads only the Capital AFT's N capital.
		ReactToyData.write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_2021.csv",
				"Lon,Lat,react_GDP_50", ReactToyData.PIXEL_A + ",1", ReactToyData.PIXEL_B + ",1");

		run(ReactToyData.context(dir).off(ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY, ReactElement.STOCKING));
	}

	// ---- check 8: base costs ----

	@Test
	void theBaseCostsTheSwitchedOnElementsNeedMustBePresent() {
		ReactToyData.write(dir, "costs/global/global_costs.csv", "Item,Cost", "Nfert,1.08", "C3cereals,50");

		String message = problems(ReactToyData.context(dir));

		assertMentions(message, "no row for Water", "irrigation is reactive");
		assertMentions(message, "no row for Stocking", "stocking is reactive");
		assertMentions(message, "no row for Pasture", "other intensity is reactive");
	}

	@Test
	void baseCostsForSwitchedOffElementsAreNotNeeded() {
		ReactToyData.write(dir, "costs/global/global_costs.csv", "Item,Cost", "Nfert,1.08");

		run(ReactToyData.context(dir).off(ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY, ReactElement.STOCKING));
	}

	// ---- check 9: cells ----

	@Test
	void aModelCellWithoutAPixelIsReported() {
		assertMentions(problems(ReactToyData.context(dir).cell("9,9")), "no LPJ-GUESS pixel for 1 CRAFTY cell(s)", "9,9");
	}

	// ---- check 11: core will apply what react writes ----

	@Test
	void aMissingCoreCostFileForAReactiveElementIsReported() {
		String message = problems(ReactToyData.context(dir).noCostFile(2021, ReactElement.STOCKING));

		assertMentions(message, "no spatial stocking_costs file for year(s) [2021]");
	}

	@Test
	void aMissingCoreCostFileForASwitchedOffElementIsFine() {
		run(ReactToyData.context(dir).noCostFile(2021, ReactElement.STOCKING).off(ReactElement.STOCKING));
	}

	@Test
	void aReactivePastureAftMustBeChargedStockingByTheModel() {
		String message = problems(ReactToyData.context(dir).aft("IntP", 0, 1.5, false, false));

		assertMentions(message, "IntP", "does not produce Pasture");
	}

	// ---- reporting ----

	@Test
	void everyProblemIsReportedInOneMessage() {
		String message = problems(ReactToyData.context(dir).cell("9,9").noCostFile(2020, ReactElement.FERTILISER)
				.aft("NewAFT", 0, 1, false, false));

		assertMentions(message, "3 problem(s)", "9,9", "Nfert_costs", "NewAFT");
	}
}
