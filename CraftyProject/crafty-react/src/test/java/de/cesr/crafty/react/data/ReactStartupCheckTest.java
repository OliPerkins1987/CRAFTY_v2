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

		assertMentions(problems(ReactToyData.context(dir).on(ReactElement.FORESTRY)), "(AF)",
				"react_R_type is required for a forestry AFT when forestry is reactive");
	}

	// ---- forestry (32a): the sheet's forestry AFTs and the switch ----

	@Test
	void forestryAftsWithForestryOnPass() {
		ReactToyData.forestry(dir);

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).withForestry());

		assertEquals(List.of("IntC3C_irrig", "ExtC3C", "IntP", "IntBF", "ExtBF"),
				result.parameters().reactive().stream().map(AftReactParameters::label).toList());
		assertEquals(Set.of(LpjgType.CROPS, LpjgType.PASTURE, LpjgType.FORESTRY), result.suitabilityFiles().keySet());
		assertEquals(dir.resolve("worlds/react/suitabilities/ssp126/forestry/Suit_Forestry_2021.csv"),
				result.suitabilityFiles().get(LpjgType.FORESTRY).get(2021));
		assertEquals(List.of(), result.warnings());
	}

	@Test
	void aCapitalForestryAftsCapitalMustBeInEveryYearOnlyWithForestryOn() {
		ReactToyData.forestry(dir);
		ReactToyData.write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_2021.csv",
				"Lon,Lat,react_GDP_50,react_GDP_100", ReactToyData.PIXEL_A + ",1,1", ReactToyData.PIXEL_B + ",1,1");

		assertMentions(problems(ReactToyData.context(dir).withForestry()),
				"EU_capitals_ssp126_2021.csv is missing column(s) [react_pop]");
		run(ReactToyData.context(dir).withForestry().off(ReactElement.FORESTRY));
	}

	@Test
	void reactiveForestryAftsWithForestryOffAreWarnedAbout() {
		// Only the sheet: no forestry files and no Hardwood row in global_costs.csv. With forestry off neither
		// is needed, and other intensity (on) asks only for the crops and pasture AFTs' services.
		ReactToyData.parameters(dir, ReactToyData.standardAndForestryRows());

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).withForestry().off(ReactElement.FORESTRY));

		assertEquals(List.of("CRAFTY-react: [IntBF, ExtBF] are reactive forestry AFTs, but reactive_forestry is off,"
				+ " so they keep the model's own suitabilities and intensity costs"), result.warnings());
		assertEquals(Set.of(LpjgType.CROPS, LpjgType.PASTURE), result.suitabilityFiles().keySet());
	}

	@Test
	void forestryOnWithNoReactiveForestryAftIsNoProblemAndNoWarning() {
		// AF names Hardwood but isn't reactive. One info line says forestry changes nothing.
		ReactStartupCheck.Result result = run(ReactToyData.context(dir).on(ReactElement.FORESTRY));

		assertEquals(3, result.parameters().reactive().size());
		assertEquals(List.of(), result.warnings());
	}

	// ---- forestry (32b): its files, the initial rotation and the cost of a harvest ----

	private static final String FORESTRY_FOLDER = "worlds/react/suitabilities/ssp126/forestry/";

	/** A forestry file's header, with these coordinate columns and every default rotation's column. */
	private static String forestryHeader(String coordinates) {
		StringBuilder header = new StringBuilder(coordinates);
		ReactConfig.DEFAULT_ROTATIONS.forEach(rotation -> header.append(',').append(ReactStartupCheck.forestryColumn(rotation)));
		return header.toString();
	}

	@Test
	void aMissingForestryYearOrColumnIsReported() throws IOException {
		// A missing year stops the folder's check before its columns are read, as for crops and pasture.
		ReactToyData.forestry(dir);
		Files.delete(dir.resolve(FORESTRY_FOLDER + "Suit_Forestry_2021.csv"));
		assertMentions(problems(ReactToyData.context(dir).withForestry()), "No .csv file for 2021", "forestry");

		ReactToyData.forestry(dir);
		ReactToyData.write(dir, FORESTRY_FOLDER + "Suit_Forestry_2020.csv", "\"x\",\"y\",\"harvest_age_10\"",
				ReactToyData.PIXEL_A + ",1", ReactToyData.PIXEL_B + ",1");
		assertMentions(problems(ReactToyData.context(dir).withForestry()),
				"Suit_Forestry_2020.csv is missing column(s) [harvest_age_20, harvest_age_30,");
	}

	@Test
	void aForestryFileWithNeitherPairOfCoordinatesIsReported() {
		ReactToyData.forestry(dir);
		ReactToyData.write(dir, FORESTRY_FOLDER + "Suit_Forestry_2020.csv", forestryHeader("long,lat"),
				ReactToyData.PIXEL_A + ",1,1,1,1,1,1,1,1,1,1");

		assertMentions(problems(ReactToyData.context(dir).withForestry()),
				"Suit_Forestry_2020.csv is missing column(s) [Lon, Lat] (or x and y instead of Lon and Lat)");
	}

	@Test
	void anInitialRotationOffTheGridIsReportedOnlyWithForestryOn() {
		ReactToyData.forestry(dir);

		String message = problems(ReactToyData.context(dir).withForestry().aft("IntBF", 0, 0.03, false, false)
				.aft("ExtBF", 0, 1.0, false, false));

		assertMentions(message, "AFTsMetaData.csv: Other_intensity of IntBF is its initial rotation in years, and must be"
				+ " one of forestry.rotations [10, 20, 30, 40, 50, 60, 70, 80, 90, 100], not 0.03");
		assertMentions(message, "Other_intensity of ExtBF", "not 1.0 (core reads a blank Other_intensity as 1.0)");
		run(ReactToyData.context(dir).withForestry().aft("IntBF", 0, 0.03, false, false).off(ReactElement.FORESTRY));
	}

	@Test
	void theInitialRotationMustBeOnTheGridInUse() {
		ReactToyData.forestry(dir);
		ReactToyData.write(dir, "AFTs/react/react_config.yaml", "forestry:", "  rotations: [10, 50]");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactStartupCheck.run(ReactConfigLoader.load(dir), ReactToyData.context(dir).withForestry().build()));

		assertMentions(e.getMessage(), "Other_intensity of ExtBF", "forestry.rotations [10, 50], not 100.0");
		assertTrue(!e.getMessage().contains("of IntBF"), e.getMessage());
	}

	@Test
	void withForestryOnTheCostOfAHarvestIsNeeded() {
		ReactToyData.forestry(dir);
		ReactToyData.globalCosts(dir); // the standard file, with no Hardwood row

		assertMentions(problems(ReactToyData.context(dir).withForestry()), "no row for Hardwood",
				"forestry is reactive, and IntBF's rotation cost is the cost of one harvest of Hardwood");
	}

	// ---- each land use's files only when it is in use (32b) ----

	@Test
	void aForestryOnlyRunNeedsNoCropPastureOrIrrigationFiles() throws IOException {
		ReactToyData.forestry(dir);
		deleteFolder("worlds/react/suitabilities/ssp126/crops");
		deleteFolder("worlds/react/suitabilities/ssp126/pasture");
		deleteFolder("worlds/react/irrigation");

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).withForestry().off(ReactElement.FERTILISER,
				ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY, ReactElement.STOCKING));

		assertEquals(Set.of(LpjgType.FORESTRY), result.suitabilityFiles().keySet());
		assertNull(result.irrigationCost());
		assertTrue(result.irrigatedCrops().isEmpty());
	}

	@Test
	void withOnlyStockingOnNoCropOrIrrigationFileIsNeeded() throws IOException {
		deleteFolder("worlds/react/suitabilities/ssp126/crops");
		deleteFolder("worlds/react/irrigation");

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).off(ReactElement.FERTILISER,
				ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY));

		assertEquals(Set.of(LpjgType.PASTURE), result.suitabilityFiles().keySet());
		assertNull(result.irrigationCost());
		assertTrue(result.irrigatedCrops().isEmpty());
	}

	@Test
	void withOnlyCropElementsOnNoPastureFileIsNeeded() throws IOException {
		deleteFolder("worlds/react/suitabilities/ssp126/pasture");

		ReactStartupCheck.Result result = run(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY,
				ReactElement.STOCKING));

		assertEquals(Set.of(LpjgType.CROPS), result.suitabilityFiles().keySet());
		assertNotNull(result.irrigationCost());
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

	@Test
	void withStockingOnThePastureServicesCostIsNeededEvenWithOtherIntensityOff() {
		// The stocking decision weighs husbandry x the service's cost whatever the other-intensity switch
		// (phase 4 plan, Q4).
		ReactToyData.write(dir, "costs/global/global_costs.csv", "Item,Cost", "Nfert,1.08", "Water,0.5", "Stocking,500",
				"C3cereals,50");

		String message = problems(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY));

		assertMentions(message, "no row for Pasture", "IntP's stocking decision weighs its husbandry");
	}

	// ---- check 9: cells ----

	@Test
	void aModelCellWithoutAPixelIsReported() {
		assertMentions(problems(ReactToyData.context(dir).cell("9,9")), "no LPJ-GUESS pixel for 1 CRAFTY cell(s)", "9,9");
	}

	// ---- check 13: the _suit capitals react hands over ----

	@Test
	void aMissingSuitCapitalStopsTheRun() {
		String message = problems(ReactToyData.context(dir).withoutCapital("IntP_suit").withoutCapital("ExtC3C_suit"));

		assertMentions(message, "no capital(s) [ExtC3C_suit, IntP_suit]", "Capitals.csv");
	}

	@Test
	void aSuitCapitalTypedCapitalIsFineWhenProductionAndCompetitivenessAreNotSeparate() {
		// With separate_production_competitiveness off (core's default), a capital's type makes no difference.
		ReactStartupCheck.Result result = run(ReactToyData.context(dir).capital("IntC3C_irrig_suit", false));

		assertEquals(List.of(), result.warnings());
	}

	@Test
	void aSuitCapitalTypedCapitalIsWarnedAboutWhenProductionIsSeparate() {
		// Production then counts only Suitability capitals, so react's yield would drive competitiveness only.
		// A warning, not a stop: it may be intended. IntFodder isn't reactive, so its _suit isn't mentioned.
		ReactStartupCheck.Result result = run(ReactToyData.context(dir).separateProductionCompetitiveness(true)
				.capital("IntC3C_irrig_suit", false).capital("IntFodder_suit", false));

		assertEquals(List.of("CRAFTY-react: capital(s) [IntC3C_irrig_suit] are typed Capital in Capitals.csv and"
				+ " separate_production_competitiveness is on, so react's yield or production counts towards those"
				+ " AFTs' competitiveness but not their production. Type them Suitability if it should count towards"
				+ " both"), result.warnings());
	}

	@Test
	void onlyTheSuitCapitalsReactHandsOverAreChecked() {
		// Only stocking on: crops suitabilities are not handed over (phase 3 plan, Q6), and IntFodder is not
		// reactive, so none of these is needed.
		run(ReactToyData.context(dir).off(ReactElement.FERTILISER, ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY)
				.withoutCapital("IntC3C_irrig_suit").withoutCapital("ExtC3C_suit").withoutCapital("IntFodder_suit"));
		// With every element off, nothing is handed over.
		run(ReactToyData.context(dir).off(ReactElement.values()).withoutCapital("IntP_suit")
				.withoutCapital("IntC3C_irrig_suit"));

		assertMentions(problems(ReactToyData.context(dir).off(ReactElement.FERTILISER, ReactElement.IRRIGATION,
				ReactElement.OTHER_INTENSITY).withoutCapital("IntP_suit")), "IntP_suit");
	}

	// ---- warning: costs the model charges that nothing gives ----

	/** A cost file for both toy years. */
	private ReactToyData.Context withCostFile(ReactToyData.Context context, ReactElement element, String... lines) {
		Path file = ReactToyData.write(dir, "costs/spatial/" + element.costFile() + ".csv", lines);
		return context.costFile(2020, element, file).costFile(2021, element, file);
	}

	@Test
	void aBlankCostFileWarnsForTheChargedAftsReactDoesNotCover() {
		ReactToyData.Context context = ReactToyData.context(dir).charged(ReactElement.OTHER_INTENSITY, "IntC3C_irrig",
				"ExtC3C", "IntP", "IntFodder", "AF");

		List<String> warnings = run(withCostFile(context, ReactElement.OTHER_INTENSITY, "X,Y")).warnings();

		assertEquals(List.of("CRAFTY-react: Intensity_costs has no column (or no rows) for IntFodder, AF in 2020-2021,"
				+ " and react does not hand over their other intensity costs, so the model charges them 0"), warnings);
	}

	@Test
	void aFileCoversTheAftsItHasColumnsForMatchedAsCoreMatchesThem() {
		ReactToyData.Context context = ReactToyData.context(dir).charged(ReactElement.OTHER_INTENSITY, "IntFodder", "AF");

		List<String> warnings = run(withCostFile(context, ReactElement.OTHER_INTENSITY, "\"X\",\"Y\", \"intfodder\" ",
				"1,1,5")).warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "for AF in 2020-2021");
		assertTrue(!warnings.get(0).contains("IntFodder"), warnings.get(0));
	}

	@Test
	void aFileWithNoRowsCoversNothing() {
		ReactToyData.Context context = ReactToyData.context(dir).charged(ReactElement.OTHER_INTENSITY, "IntFodder", "AF");

		List<String> warnings = run(withCostFile(context, ReactElement.OTHER_INTENSITY, "X,Y,IntFodder,AF", "")).warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "for IntFodder, AF in 2020-2021");
	}

	@Test
	void whenReactHandsOverEveryChargedAftABlankFileGivesNoWarning() {
		ReactToyData.Context context = ReactToyData.context(dir).charged(ReactElement.FERTILISER, "IntC3C_irrig", "ExtC3C")
				.charged(ReactElement.IRRIGATION, "IntC3C_irrig").charged(ReactElement.STOCKING, "IntP");
		context = withCostFile(context, ReactElement.FERTILISER, "X,Y");
		context = withCostFile(context, ReactElement.IRRIGATION, "X,Y");
		context = withCostFile(context, ReactElement.STOCKING, "X,Y");

		assertEquals(List.of(), run(context).warnings());
	}

	@Test
	void anElementSwitchedOffIsNotHandedOver() {
		ReactToyData.Context context = ReactToyData.context(dir).off(ReactElement.FERTILISER)
				.charged(ReactElement.FERTILISER, "IntC3C_irrig", "ExtC3C");

		List<String> warnings = run(withCostFile(context, ReactElement.FERTILISER, "X,Y")).warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "Nfert_costs", "for IntC3C_irrig, ExtC3C in 2020-2021", "their fertiliser costs");
	}

	@Test
	void theSharedIrrigationColumnCoversEveryIrrigatedAft() {
		ReactToyData.Context context = ReactToyData.context(dir).off(ReactElement.IRRIGATION)
				.charged(ReactElement.IRRIGATION, "IntC3C_irrig", "IntFodder");

		assertEquals(List.of(), run(withCostFile(context, ReactElement.IRRIGATION, "X,Y,irrigation_cost", "1,1,0.3"))
				.warnings());
		assertEquals(1, run(withCostFile(context, ReactElement.IRRIGATION, "X,Y", "1,1")).warnings().size());
	}

	@Test
	void theWarningGroupsAftsByTheYearsTheyAreMissing() {
		Path withAf = ReactToyData.write(dir, "costs/spatial/Intensity_costs_2020.csv", "X,Y,AF", "1,1,5");
		Path blank = ReactToyData.write(dir, "costs/spatial/Intensity_costs_2021.csv", "X,Y");

		List<String> warnings = run(ReactToyData.context(dir).charged(ReactElement.OTHER_INTENSITY, "IntFodder", "AF")
				.costFile(2020, ReactElement.OTHER_INTENSITY, withAf).costFile(2021, ReactElement.OTHER_INTENSITY, blank))
				.warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "for IntFodder in 2020-2021; AF in 2021,");
	}

	// ---- warning: capitals the model has that nothing gives ----

	/** A capitals file of the model's, for both toy years. */
	private ReactToyData.Context withCapitalsFile(ReactToyData.Context context, String... lines) {
		Path file = ReactToyData.write(dir, "worlds/capitals/ssp126/capitals_all_years.csv", lines);
		return context.capitalsFile(2020, file).capitalsFile(2021, file);
	}

	@Test
	void aCapitalsFileMissingAColumnWarnsForTheCapitalsReactDoesNotHandOver() {
		// The reactive AFTs' _suit columns can be left out: react hands them over. Columns match as in core.
		List<String> warnings = run(withCapitalsFile(ReactToyData.context(dir), "\"X\",\"Y\", intfodder_suit ,\"AF_SUIT\"",
				"1,1,0.5,0.5")).warnings();

		assertEquals(List.of("CRAFTY-react: the model's capitals file has no column (or no rows) for Urban_suit in"
				+ " 2020-2021, and react does not hand them over, so the model gives them 0"), warnings);
	}

	@Test
	void aCapitalsFileWithEveryColumnReactDoesNotHandOverGivesNoWarning() {
		assertEquals(List.of(), run(withCapitalsFile(ReactToyData.context(dir), "X,Y,IntFodder_suit,AF_suit,Urban_suit",
				"1,1,1,1,1")).warnings());
	}

	@Test
	void aCapitalsFileWithNoRowsCoversNothing() {
		List<String> warnings = run(withCapitalsFile(ReactToyData.context(dir), "X,Y,IntFodder_suit,AF_suit,Urban_suit"))
				.warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "for IntFodder_suit, AF_suit, Urban_suit in 2020-2021,");
	}

	@Test
	void aSuitReactDoesNotHandOverMustComeFromTheFile() {
		// Only stocking on: react hands over IntP's _suit, but not the crops AFTs' (phase 3 plan, Q6).
		ReactToyData.Context context = ReactToyData.context(dir).off(ReactElement.FERTILISER, ReactElement.IRRIGATION,
				ReactElement.OTHER_INTENSITY);

		List<String> warnings = run(withCapitalsFile(context, "X,Y,IntFodder_suit,AF_suit,Urban_suit", "1,1,1,1,1"))
				.warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "for IntC3C_irrig_suit, ExtC3C_suit in 2020-2021,");
		assertTrue(!warnings.get(0).contains("IntP_suit"), warnings.get(0));
	}

	@Test
	void everyCapitalIsCheckedAndAYearWithNoFileIsSkipped() {
		// Core stops the run itself when a year has no capitals file.
		Path file = ReactToyData.write(dir, "worlds/capitals/ssp126/capitals_2020.csv",
				"X,Y,IntFodder_suit,AF_suit,Urban_suit", "1,1,1,1,1");

		List<String> warnings = run(ReactToyData.context(dir).capital("react_GDP_50", false).capitalsFile(2020, file))
				.warnings();

		assertEquals(1, warnings.size(), warnings.toString());
		assertMentions(warnings.get(0), "for react_GDP_50 in 2020,");
	}

	@Test
	void yearsAreWrittenAsRanges() {
		assertEquals("2020-2022, 2025, 2030-2031", ReactStartupCheck.yearRanges(List.of(2020, 2021, 2022, 2025, 2030, 2031)));
		assertEquals("2020", ReactStartupCheck.yearRanges(List.of(2020)));
	}

	// ---- reporting ----

	@Test
	void everyProblemIsReportedInOneMessage() {
		ReactToyData.write(dir, "costs/global/global_costs.csv", "Item,Cost", "Nfert,1.08", "Stocking,500", "C3cereals,50",
				"Pasture,50");

		String message = problems(ReactToyData.context(dir).cell("9,9").aft("NewAFT", 0, 1, false, false));

		assertMentions(message, "3 problem(s)", "9,9", "no row for Water", "NewAFT");
	}
}
