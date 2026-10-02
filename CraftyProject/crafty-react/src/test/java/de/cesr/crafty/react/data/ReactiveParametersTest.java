package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactiveParametersTest {

	@TempDir
	Path dir;

	private ReactServiceKey services;
	private List<String> problems;

	@BeforeEach
	void setUp() {
		services = ReactServiceKey.load(ReactToyData.services(dir));
	}

	/** Writes the standard rows plus any extra rows, and reads them with the given context. */
	private ReactiveParameters read(ReactToyData.Context context, String... extraRows) {
		List<String> rows = new ArrayList<>(List.of(ReactToyData.STANDARD_ROWS));
		rows.addAll(List.of(extraRows));
		return readRows(context, rows.toArray(new String[0]));
	}

	private ReactiveParameters readRows(ReactToyData.Context context, String... rows) {
		Path sheet = ReactToyData.parameters(dir, rows);
		problems = new ArrayList<>();
		return ReactiveParameters.read(sheet, services, context.build(), problems);
	}

	private ReactToyData.Context context() {
		return ReactToyData.context(dir).aft("X", 200, 0.75, false, false);
	}

	private void assertNoProblems() {
		assertTrue(problems.isEmpty(), "Unexpected problems: " + problems);
	}

	/** Asserts that one problem mentions every fragment. */
	private void assertProblem(String... fragments) {
		assertTrue(problems.stream().anyMatch(p -> List.of(fragments).stream().allMatch(p::contains)),
				"No problem mentions " + List.of(fragments) + " in " + problems);
	}

	// ---- the standard sheet ----

	@Test
	void theStandardSheetReadsWithBaselinesFromCore() {
		ReactiveParameters parameters = read(context());

		assertNoProblems();
		assertEquals(List.of("IntC3C_irrig", "ExtC3C", "IntP", "IntFodder", "AF", "Urban"), parameters.labels(),
				"Every row, reactive or not, in sheet order");
		assertEquals(List.of("IntC3C_irrig", "ExtC3C", "IntP"),
				parameters.reactive().stream().map(AftReactParameters::label).toList());

		AftReactParameters intensive = parameters.get("IntC3C_irrig");
		assertEquals(LpjgType.CROPS, intensive.type());
		assertEquals("CerealsC3", intensive.lpjgName());
		assertEquals(NitrogenMode.PROSPECT, intensive.nMode());
		assertEquals(0.0, intensive.nPar());
		assertEquals(0.9, intensive.iEff());
		assertTrue(intensive.isIrrigated(), "Irrigated comes from core");
		assertEquals(200, intensive.nitrogenBaseline(), "The N baseline is core's Nfert_rate");
		assertEquals(0.75, intensive.otherIntensityBaseline(), "The other-intensity baseline is core's Other_intensity");
		assertTrue(intensive.hasCapitalDrivenOtherIntensity());

		AftReactParameters extensive = parameters.get("ExtC3C");
		assertTrue(extensive.usesCapitalForN());
		assertEquals("react_GDP_50", extensive.nCapital());
		assertEquals(12.0, extensive.nPar());
		assertFalse(extensive.hasCapitalDrivenOtherIntensity(), "Both O columns blank: other intensity stays at baseline");

		AftReactParameters pasture = parameters.get("IntP");
		assertTrue(pasture.isPasture());
		assertEquals("Pasture_sum", pasture.lpjgName());
		assertNull(pasture.nMode());
		assertEquals(1.0, pasture.oPar());
		assertEquals(0.0, pasture.sPar());

		assertFalse(parameters.isReactive("IntFodder"));
		assertEquals(Set.of("C3cereals", "Pasture"), parameters.servicesInUse());
		assertEquals(List.of("react_GDP_100", "react_GDP_50"),
				List.copyOf(parameters.capitalsNamed(EnumSet.allOf(ReactElement.class))));
		assertEquals(List.of("react_GDP_50"),
				List.copyOf(parameters.capitalsNamed(EnumSet.of(ReactElement.FERTILISER))),
				"With only fertiliser on, only the Capital AFT's N capital is read");
	}

	@Test
	void blankProspectNParAndBlankSParMeanZero() {
		ReactiveParameters parameters = readRows(context().aft("X", 200, 0.75, false, false),
				"X,AFT,1,C3cereals,Prospect,,,,,,,", "IntP,AFT,1,Pasture,,,,,react_GDP_50,1,,");

		assertEquals(0.0, parameters.get("X").nPar());
		assertEquals(0.0, parameters.get("IntP").sPar());
	}

	// ---- layout ----

	@Test
	void anUnknownReactColumnIsReported() {
		ReactToyData.write(dir, "AFTs/react/Reactive_parameters.csv",
				ReactToyData.PARAMETERS_HEADER + ",react_N_anchr", "IntFodder,AFT,0" + ",".repeat(12));
		problems = new ArrayList<>();

		ReactiveParameters.read(dir.resolve("AFTs/react/Reactive_parameters.csv"), services, context().build(), problems);

		assertProblem("react_N_anchr", "not a react column");
	}

	@Test
	void aMissingReactColumnStopsReadingAtOnce() {
		Path sheet = ReactToyData.write(dir, "AFTs/react/Reactive_parameters.csv", "Label,react_isReactive", "X,0");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactiveParameters.read(sheet, services, context().build(), new ArrayList<>()));

		assertTrue(e.getMessage().contains("react_service"), e.getMessage());
	}

	@Test
	void aSheetWithoutTheForestryColumnsStopsReadingNamingThem() {
		// The sheet as it was before forestry: react_R_par was already there.
		Path sheet = ReactToyData.write(dir, "AFTs/react/Reactive_parameters.csv", "Label,react_isReactive,react_service,"
				+ "react_N_type,react_N_capital,react_N_par,react_I_eff,react_O_capital,react_O_par,react_S_par,react_R_par");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactiveParameters.read(sheet, services, context().build(), new ArrayList<>()));

		assertTrue(e.getMessage().contains("[react_R_type, react_R_capital]"), e.getMessage());
	}

	@Test
	void labelsMustBeUniqueAndPresent() {
		readRows(context(), "X,AFT,0,,,,,,,,,", "X,AFT,0,,,,,,,,,", ",AFT,0,,,,,,,,,");

		assertProblem("X appears more than once");
		assertProblem("Label is blank");
	}

	@Test
	void isReactiveMustBeZeroOrOne() {
		readRows(context(), "X,AFT,yes,C3cereals,Prospect,,,,,,,");

		assertProblem("react_isReactive must be 0 or 1");
	}

	@Test
	void aNonReactiveRowMayHoldAnything() {
		ReactiveParameters parameters = readRows(context(), "X,AFT,0,nonsense,Prospekt,,abc,7,,9,,");

		assertNoProblems();
		assertFalse(parameters.isReactive("X"));
		assertEquals(List.of("X"), parameters.labels());
	}

	@Test
	void textInANumberCellIsReportedEvenWhenItsElementIsOff() {
		readRows(context().off(ReactElement.IRRIGATION), "X,AFT,1,C3cereals,Prospect,,,abc,,,,");

		assertProblem("(X)", "react_I_eff should be a number");
	}

	// ---- services ----

	@Test
	void theServiceMustBeKnownAndTyped() {
		readRows(context().aft("A", 1, 0.5, false, false).aft("B", 1, 0.5, false, false)
				.aft("C", 1, 0.5, false, false),
				"A,AFT,1,,Prospect,,,,,,,",
				"B,AFT,1,C4crops,Prospect,,,,,,,",
				"C,AFT,1,Carbon,,,,,,,,");

		assertProblem("(A)", "react_service is blank");
		assertProblem("(B)", "C4crops is not in");
		assertProblem("(C)", "Carbon has no LPJG_type");
	}

	@Test
	void theServiceMustBeOneOfTheModelsServices() {
		read(context().withoutService("Pasture"));

		assertProblem("(IntP)", "Pasture is not one of the model's services");
	}

	@Test
	void anAftUnknownToCoreIsLeftForTheAftListCheck() {
		ReactiveParameters parameters = readRows(context(), "Y,AFT,1,C3cereals,Prospect,,,,,,,");

		assertNoProblems();
		assertFalse(parameters.isReactive("Y"));
	}

	// ---- fertiliser ----

	@Test
	void nTypeIgnoresCase() {
		ReactiveParameters parameters = readRows(context().aft("Y", 60, 0.4, false, false),
				"X,AFT,1,C3cereals,prospect,,,,,,,", "Y,AFT,1,C3cereals,CAPITAL,react_GDP_50,12,,,,,");

		assertNoProblems();
		assertEquals(NitrogenMode.PROSPECT, parameters.get("X").nMode());
		assertEquals(NitrogenMode.CAPITAL, parameters.get("Y").nMode());
	}

	@Test
	void anUnknownNTypeIsReported() {
		readRows(context(), "X,AFT,1,C3cereals,Prospekt,,,,,,,");

		assertProblem("(X)", "must be Prospect or Capital");
	}

	@Test
	void nTypeIsRequiredOnlyWhenFertiliserIsReactive() {
		readRows(context(), "X,AFT,1,C3cereals,,,,,,,,");
		assertProblem("(X)", "react_N_type is required");

		readRows(context().off(ReactElement.FERTILISER), "X,AFT,1,C3cereals,,,,,,,,");
		assertNoProblems();
	}

	@Test
	void nfertRateMustBeAboveZeroWhenFertiliserIsReactive() {
		readRows(context().aft("X", 0, 0.75, false, false), "X,AFT,1,C3cereals,Prospect,,,,,,,");
		assertProblem("(X)", "Nfert_rate", "must be above 0");

		readRows(context().aft("X", 0, 0.75, false, false).off(ReactElement.FERTILISER),
				"X,AFT,1,C3cereals,Prospect,,,,,,,");
		assertNoProblems();
	}

	@Test
	void aNegativeNfertRateIsAlwaysReported() {
		readRows(context().aft("X", -1, 0.75, false, false).off(ReactElement.FERTILISER),
				"X,AFT,1,C3cereals,,,,,,,,");

		assertProblem("(X)", "Nfert_rate", "negative");
	}

	@Test
	void aCapitalAftNeedsItsCapitalAndMultiplierWhenFertiliserIsReactive() {
		readRows(context(), "X,AFT,1,C3cereals,Capital,,,,,,,");
		assertProblem("(X)", "react_N_capital is required");
		assertProblem("(X)", "react_N_par is required");

		readRows(context().off(ReactElement.FERTILISER), "X,AFT,1,C3cereals,Capital,,,,,,,");
		assertNoProblems();
	}

	@Test
	void nCapitalOnAProspectAftIsAlwaysASlip() {
		readRows(context().off(ReactElement.FERTILISER), "X,AFT,1,C3cereals,Prospect,react_GDP_50,,,,,,");

		assertProblem("(X)", "react_N_capital does not apply to a Prospect AFT");
	}

	@Test
	void nParRangesDependOnTheMode() {
		readRows(context().aft("Y", 60, 0.4, false, false),
				"X,AFT,1,C3cereals,Prospect,,-0.01,,,,,", "Y,AFT,1,C3cereals,Capital,react_GDP_50,0,,,,,");

		assertProblem("(X)", "inertia threshold for a Prospect AFT) must be 0 or more");
		assertProblem("(Y)", "capital multiplier) must be above 0");
	}

	// ---- irrigation ----

	@Test
	void anIrrigatedAftNeedsIEffWhenIrrigationIsReactive() {
		readRows(context().aft("X", 200, 0.75, true, false), "X,AFT,1,C3cereals,Prospect,,,,,,,");
		assertProblem("(X)", "Irrigated = 1", "react_I_eff is required");

		readRows(context().aft("X", 200, 0.75, true, false).off(ReactElement.IRRIGATION),
				"X,AFT,1,C3cereals,Prospect,,,,,,,");
		assertNoProblems();
	}

	@Test
	void iEffOnAnAftThatIsNotIrrigatedStopsTheRunWhenIrrigationIsReactive() {
		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,0.9,,,,");
		assertProblem("(X)", "react_I_eff is filled", "Irrigated = 0");

		readRows(context().off(ReactElement.IRRIGATION), "X,AFT,1,C3cereals,Prospect,,,0.9,,,,");
		assertNoProblems();
	}

	@Test
	void iEffMustBeAboveZeroAndAtMostOne() {
		ReactToyData.Context irrigated = context().aft("X", 200, 0.75, true, false);
		readRows(irrigated, "X,AFT,1,C3cereals,Prospect,,,1,,,,");
		assertNoProblems();

		readRows(irrigated, "X,AFT,1,C3cereals,Prospect,,,0,,,,");
		assertProblem("(X)", "react_I_eff must be above 0 and at most 1");

		readRows(irrigated, "X,AFT,1,C3cereals,Prospect,,,1.1,,,,");
		assertProblem("(X)", "react_I_eff must be above 0 and at most 1");
	}

	// ---- other intensity ----

	@Test
	void cropsFillBothOtherIntensityColumnsOrNeither() {
		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,,react_GDP_100,,,");
		assertProblem("(X)", "fill both react_O_capital and react_O_par");

		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,,,0.2,,");
		assertProblem("(X)", "fill both react_O_capital and react_O_par");
	}

	@Test
	void cropsOParMustBeAtLeastZeroAndBelowOne() {
		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,,react_GDP_100,0.999,,");
		assertNoProblems();

		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,,react_GDP_100,1,,");
		assertProblem("(X)", "must be at least 0 and below 1");

		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,,react_GDP_100,-0.1,,");
		assertProblem("(X)", "must be at least 0 and below 1");
	}

	@Test
	void cropsOtherIntensityBaselineMustBeBetweenZeroAndOne() {
		readRows(context().aft("X", 200, 1.0, false, false), "X,AFT,1,C3cereals,Prospect,,,,,,,");
		assertNoProblems();

		readRows(context().aft("X", 200, 1.2, false, false), "X,AFT,1,C3cereals,Prospect,,,,,,,");
		assertProblem("(X)", "Other_intensity", "between 0 and 1");
	}

	// ---- pasture ----

	@Test
	void nitrogenAndIrrigationColumnsDoNotApplyToPasture() {
		readRows(context(), "IntP,AFT,1,Pasture,Prospect,,0,0.9,react_GDP_50,1,0,");

		assertProblem("(IntP)", "react_N_type does not apply to a pasture AFT");
		assertProblem("(IntP)", "react_N_par does not apply to a pasture AFT");
		assertProblem("(IntP)", "react_I_eff does not apply to a pasture AFT");
	}

	@Test
	void sParDoesNotApplyToCrops() {
		readRows(context(), "X,AFT,1,C3cereals,Prospect,,,,,,0.1,");

		assertProblem("(X)", "react_S_par does not apply to a crops AFT");
	}

	@Test
	void pastureNeedsItsOtherIntensityColumnsOnlyWhenOtherIntensityIsReactive() {
		readRows(context(), "IntP,AFT,1,Pasture,,,,,,,0,");
		assertProblem("(IntP)", "react_O_capital is required");
		assertProblem("(IntP)", "react_O_par is required");

		readRows(context().off(ReactElement.OTHER_INTENSITY), "IntP,AFT,1,Pasture,,,,,,,0,");
		assertNoProblems();
	}

	@Test
	void pastureRanges() {
		readRows(context(), "IntP,AFT,1,Pasture,,,,,react_GDP_50,-1,-0.1,");

		assertProblem("(IntP)", "react_O_par must be 0 or more");
		assertProblem("(IntP)", "react_S_par must be 0 or more");
	}

	@Test
	void pastureOtherIntensityBaselineMustBeAboveZero() {
		readRows(context().aft("IntP", 0, 0, false, true), "IntP,AFT,1,Pasture,,,,,react_GDP_50,1,0,");

		assertProblem("(IntP)", "Other_intensity", "must be above 0");
	}

	// ---- forestry ----
	// Rows are written with all 14 fields; the last three are react_R_par, react_R_type, react_R_capital.

	/** The standard context, plus the two toy forestry AFTs, with forestry on. */
	private ReactToyData.Context forestryContext() {
		return context().withForestry();
	}

	@Test
	void forestryRowsAreReadWithTheInitialRotationFromCore() {
		ReactiveParameters parameters = read(forestryContext(), ReactToyData.FORESTRY_ROWS);

		assertNoProblems();
		AftReactParameters prospect = parameters.get("IntBF");
		assertEquals(LpjgType.FORESTRY, prospect.type());
		assertTrue(prospect.isForestry());
		assertEquals(RotationMode.PROSPECT, prospect.rMode());
		assertFalse(prospect.usesCapitalForRotation());
		assertNull(prospect.rCapital());
		assertEquals(0.1, prospect.rPar());
		assertEquals(50, prospect.initialRotation(), "Other_intensity holds a forestry AFT's initial rotation, in years");
		assertNull(prospect.nMode());

		AftReactParameters capital = parameters.get("ExtBF");
		assertTrue(capital.usesCapitalForRotation());
		assertEquals("react_pop", capital.rCapital());
		assertEquals(0.05, capital.rPar());
		assertEquals(100, capital.initialRotation());

		assertEquals(Set.of("C3cereals", "Pasture", "Hardwood"), parameters.servicesInUse());
	}

	@Test
	void rTypeIgnoresCaseAndAnUnknownOneIsReported() {
		ReactiveParameters parameters = readRows(forestryContext(), "IntBF,AFT,1,Hardwood,,,,,,,,,prospect,",
				"ExtBF,AFT,1,Hardwood,,,,,,,,0.05,CAPITAL,react_pop");

		assertNoProblems();
		assertEquals(RotationMode.PROSPECT, parameters.get("IntBF").rMode());
		assertEquals(RotationMode.CAPITAL, parameters.get("ExtBF").rMode());

		readRows(forestryContext(), "IntBF,AFT,1,Hardwood,,,,,,,,,Prospekt,");
		assertProblem("(IntBF)", "react_R_type must be Prospect or Capital, not \"Prospekt\"");
	}

	@Test
	void rTypeIsRequiredOnlyWhenForestryIsReactive() {
		readRows(forestryContext(), "IntBF,AFT,1,Hardwood,,,,,,,,,,");
		assertProblem("(IntBF)", "react_R_type is required for a forestry AFT when forestry is reactive");

		ReactiveParameters parameters = readRows(forestryContext().off(ReactElement.FORESTRY),
				"IntBF,AFT,1,Hardwood,,,,,,,,,,");
		assertNoProblems();
		assertTrue(parameters.isReactive("IntBF"));
		assertNull(parameters.get("IntBF").rMode());
	}

	@Test
	void aCapitalAftNeedsItsCapitalAndParWhenForestryIsReactive() {
		readRows(forestryContext(), "ExtBF,AFT,1,Hardwood,,,,,,,,,Capital,");
		assertProblem("(ExtBF)", "react_R_capital is required for a Capital AFT when forestry is reactive");
		assertProblem("(ExtBF)", "react_R_par is required for a Capital AFT when forestry is reactive");

		readRows(forestryContext().off(ReactElement.FORESTRY), "ExtBF,AFT,1,Hardwood,,,,,,,,,Capital,");
		assertNoProblems();
	}

	@Test
	void aCapitalAftsParMayBeZeroOrNegative() {
		ReactiveParameters parameters = readRows(forestryContext(), "IntBF,AFT,1,Hardwood,,,,,,,,0,Capital,react_pop",
				"ExtBF,AFT,1,Hardwood,,,,,,,,-0.02,Capital,react_pop");

		assertNoProblems();
		assertEquals(0.0, parameters.get("IntBF").rPar(), "0 keeps the initial rotation");
		assertEquals(-0.02, parameters.get("ExtBF").rPar(), "a negative sensitivity lengthens the rotation");
	}

	@Test
	void rCapitalOnAProspectAftIsAlwaysASlip() {
		readRows(forestryContext().off(ReactElement.FORESTRY), "IntBF,AFT,1,Hardwood,,,,,,,,,Prospect,react_pop");

		assertProblem("(IntBF)", "react_R_capital does not apply to a Prospect AFT");
	}

	@Test
	void aProspectAftsThresholdIsZeroWhenBlankAndMayNotBeNegative() {
		ReactiveParameters parameters = readRows(forestryContext(), "IntBF,AFT,1,Hardwood,,,,,,,,,Prospect,");
		assertNoProblems();
		assertEquals(0.0, parameters.get("IntBF").rPar());

		readRows(forestryContext(), "IntBF,AFT,1,Hardwood,,,,,,,,-0.1,Prospect,");
		assertProblem("(IntBF)", "react_R_par (the status-quo threshold for a Prospect AFT) must be 0 or more, not -0.1");
	}

	@Test
	void cropAndPastureColumnsDoNotApplyToForestry() {
		readRows(forestryContext(),
				"IntBF,AFT,1,Hardwood,Prospect,react_GDP_50,1,0.9,react_GDP_50,0.2,0.1,,Prospect,");

		for (String column : List.of("react_N_type", "react_N_capital", "react_N_par", "react_I_eff", "react_O_capital",
				"react_O_par", "react_S_par")) {
			assertProblem("(IntBF)", column + " does not apply to a forestry AFT; leave it blank");
		}
	}

	@Test
	void forestryColumnsDoNotApplyToCropsOrPasture() {
		readRows(forestryContext(), "X,AFT,1,C3cereals,Prospect,,,,,,,0.1,Prospect,react_pop",
				"IntP,AFT,1,Pasture,,,,,react_GDP_50,1,0,0.1,Capital,react_pop");

		for (String column : List.of("react_R_type", "react_R_capital", "react_R_par")) {
			assertProblem("(X)", column + " does not apply to a crops AFT; leave it blank");
			assertProblem("(IntP)", column + " does not apply to a pasture AFT; leave it blank");
		}
	}

	@Test
	void aForestryAftsInitialRotationMustBeAboveZero() {
		readRows(forestryContext().aft("IntBF", 0, 0, false, false), "IntBF,AFT,1,Hardwood,,,,,,,,,Prospect,");

		assertProblem("(IntBF)", "Other_intensity", "initial rotation length in years", "must be above 0, not 0.0");
	}

	@Test
	void theRotationCapitalIsReadOnlyWithForestryOnAndOnlyForACapitalAft() {
		ReactiveParameters parameters = read(forestryContext(), ReactToyData.FORESTRY_ROWS);

		assertEquals(List.of("react_GDP_100", "react_GDP_50", "react_pop"),
				List.copyOf(parameters.capitalsNamed(EnumSet.allOf(ReactElement.class))));
		assertEquals(List.of("react_pop"), List.copyOf(parameters.capitalsNamed(EnumSet.of(ReactElement.FORESTRY))),
				"Only the Capital AFT's; the Prospect AFT reads none");
		assertEquals(List.of("react_GDP_100", "react_GDP_50"),
				List.copyOf(parameters.capitalsNamed(EnumSet.complementOf(EnumSet.of(ReactElement.FORESTRY)))));
	}

	// ---- reporting ----

	@Test
	void loadReportsEveryProblemInOneException() {
		Path sheet = ReactToyData.parameters(dir, "X,AFT,1,C3cereals,Prospekt,,,,,,0.1,", "Y,AFT,2,,,,,,,,,");

		ReactInputException e = assertThrows(ReactInputException.class,
				() -> ReactiveParameters.load(sheet, services, context().aft("Y", 1, 0.5, false, false).build()));

		assertTrue(e.getMessage().contains("Prospect or Capital"), e.getMessage());
		assertTrue(e.getMessage().contains("react_S_par does not apply"), e.getMessage());
		assertTrue(e.getMessage().contains("react_isReactive must be 0 or 1"), e.getMessage());
		assertTrue(e.getMessage().contains("line 2") && e.getMessage().contains("line 3"), e.getMessage());
	}
}
