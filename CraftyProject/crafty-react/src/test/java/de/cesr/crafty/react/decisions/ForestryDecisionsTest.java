package de.cesr.crafty.react.decisions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.react.data.ReactConfigLoader;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactStartupCheck;
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.science.ReactiveRotationStep;

/**
 * The forestry stage on the toy project. {@link ForestryDecisionsGoldenTest} checks it against R; these tests
 * check each rule on its own.
 *
 * The toy project with forestry ({@link ReactToyData#forestry}) has two reactive forestry AFTs: {@code IntBF},
 * Prospect with a threshold of 0.1 and an initial rotation of 50 years, and {@code ExtBF}, Capital following
 * {@code react_pop} (0.3 at pixel A, 0.7 at pixel B) with a sensitivity of 0.05 and an initial rotation of 100
 * years. Units are 0 = pixel A in the North, 1 = pixel A in the South and 2 = pixel B in the North. Pixel A's
 * yields (m³/ha/yr) rise and fall with the rotation; at the toy price of 100 $/m³ its best rotation is 70
 * years. Pixel B has no forest. A harvest of Hardwood costs 1750 $/ha.
 */
class ForestryDecisionsTest {

	/** The default rotations, which the toy project uses. */
	private static final List<Integer> GRID = List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
	private static final double HARVEST_COST = ReactToyData.HARDWOOD_HARVEST_COST;
	private static final String POPULATION = "react_pop";

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
		ReactToyData.forestry(dir);
	}

	/** One run of the forestry stage on the toy project. */
	private final class Run {
		final ReactInputs inputs;
		final DecisionUnits units;
		final ForestryDecisions forestry;
		ReactYearData data;

		Run(ReactToyData.Context context) {
			inputs = ReactInputs.create(ReactConfigLoader.load(dir), context.build());
			units = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
			forestry = ForestryDecisions.create(inputs, units);
		}

		Map<String, ForestryManagement> decide(int year) {
			data = inputs.forYear(year);
			return forestry.decide(data, prices(year));
		}

		YearPrices prices(int year) {
			return YearPrices.forYear(year, forestry.servicesNeedingPrices(), units, inputs.context().prices());
		}

		ForestryManagement intBF() {
			return forestry.managements().get("IntBF");
		}

		ForestryManagement extBF() {
			return forestry.managements().get("ExtBF");
		}

		/** A unit's economics, written out with the 32c record: what the stage should weigh. */
		ReactiveRotationStep.Economics place(int unit, double price) {
			int pixel = units.pixel(unit);
			return new ReactiveRotationStep.Economics(h -> data.forestryYield(h)[pixel], price, HARVEST_COST);
		}

		/** Prospect steps in one unit, written out with the 32c function. */
		int stepsByHand(int unit, int rotation, int steps, double price, double threshold) {
			ReactiveRotationStep.Economics place = place(unit, price);
			for (int step = 0; step < steps; step++) {
				rotation = ReactiveRotationStep.prospectStep(rotation, place, threshold, GRID);
			}
			return rotation;
		}

		/** ExtBF's rotation in a unit, written out with the 32c function. */
		int capitalByHand(int unit, double sensitivity) {
			return ReactiveRotationStep.capitalRotation(ReactToyData.EXT_BF_ROTATION, sensitivity,
					data.capital(POPULATION)[units.pixel(unit)], GRID);
		}

		double table(int unit, int rotation) {
			return data.forestryYield(rotation)[units.pixel(unit)];
		}
	}

	private Run run(ReactToyData.Context context) {
		return new Run(context);
	}

	private Run run() {
		return run(ReactToyData.context(dir).withForestry());
	}

	private void settings(String... lines) {
		ReactToyData.write(dir, ReactConfigLoader.LOCATION.toString(), lines);
	}

	/** The toy sheet with forestry, with the forestry rows replaced. */
	private void forestryRows(String intBF, String extBF) {
		String[] rows = ReactToyData.standardAndForestryRows();
		rows[rows.length - 2] = intBF;
		rows[rows.length - 1] = extBF;
		ReactToyData.parameters(dir, rows);
	}

	/** A forestry file for a year, in m³/ha/yr, one value per default rotation for each pixel. */
	private void forestryFile(int year, double[] pixelA, double[] pixelB) {
		StringBuilder header = new StringBuilder("x,y");
		StringBuilder rowA = new StringBuilder(ReactToyData.PIXEL_A);
		StringBuilder rowB = new StringBuilder(ReactToyData.PIXEL_B);
		for (int i = 0; i < GRID.size(); i++) {
			header.append(',').append(ReactStartupCheck.forestryColumn(GRID.get(i)));
			rowA.append(',').append(pixelA[i]);
			rowB.append(',').append(pixelB[i]);
		}
		ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/forestry/Suit_Forestry_" + year + ".csv",
				header.toString(), rowA.toString(), rowB.toString());
	}

	private static double mean(double[] values) {
		return Arrays.stream(values).sum() / values.length;
	}

	// ---- the loop ----

	@Test
	void everyReactiveForestryAftIsDecidedInSheetOrder() {
		Run run = run();

		assertEquals(List.of("IntBF", "ExtBF"), List.copyOf(run.decide(2020).keySet()),
				"the crops and pasture AFTs are their own stages', and AF is not reactive");
		assertEquals(3, run.intBF().rotation().length, "one value per unit");
	}

	@Test
	void theFirstYearIsSpunUpFromTheInitialRotation() {
		settings("spinup_iterations: 2");
		Run run = run();
		run.decide(2020);

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.stepsByHand(unit, 50, 2, 100, 0.1), run.intBF().rotation()[unit], "unit " + unit);
		}
		assertEquals(70, run.intBF().rotation()[0], "two steps from 50 years: 60, then 70");
	}

	@Test
	void withNoSpinUpTheFirstYearStaysAtTheInitialRotation() {
		settings("spinup_iterations: 0");
		Run run = run();
		run.decide(2020);

		assertArrayEquals(new int[] { 50, 50, 50 }, run.intBF().rotation());

		run.decide(2021);
		assertArrayEquals(new int[] { 60, 60, 60 }, run.intBF().rotation(), "the next year takes one step");
	}

	@Test
	void laterYearsTakeOneStepFromLastYearsRotation() {
		Run run = run(ReactToyData.context(dir).withForestry().prices((service, region, year) -> year == 2020 ? 100 : 10));
		run.decide(2020);
		int[] lastYear = run.intBF().rotation().clone();
		assertEquals(70, lastYear[0]);

		run.decide(2021);

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.stepsByHand(unit, lastYear[unit], 1, 10, 0.1), run.intBF().rotation()[unit],
					"unit " + unit + ": one step from 2020's rotation, not from 50");
		}
		assertEquals(80, run.intBF().rotation()[0], "at 10 $/m3 a longer rotation pays better");
	}

	@Test
	void theSameYearAgainChangesNothingAndAnEarlierYearIsAnError() {
		settings("spinup_iterations: 1");
		Run run = run();
		ReactYearData year2020 = run.inputs.forYear(2020);
		Map<String, ForestryManagement> first = run.forestry.decide(year2020, run.prices(2020));
		int[] rotation = run.intBF().rotation().clone();

		assertSame(first, run.forestry.decide(year2020, run.prices(2020)));
		assertArrayEquals(rotation, run.intBF().rotation(), "no second step");

		run.decide(2021);
		assertThrows(IllegalStateException.class, () -> run.forestry.decide(year2020, run.prices(2020)));
		assertEquals(2021, run.forestry.lastYear());
	}

	@Test
	void pricesOfAnotherYearAreAnError() {
		Run run = run();
		ReactYearData year2020 = run.inputs.forYear(2020);

		assertThrows(IllegalArgumentException.class, () -> run.forestry.decide(year2020, run.prices(2021)));
	}

	// ---- regions ----

	@Test
	void eachRegionIsPricedSeparately() {
		Run run = run(ReactToyData.context(dir).withForestry()
				.prices((service, region, year) -> region.equals("North") ? 100 : 10));
		run.decide(2020);
		int[] rotation = run.intBF().rotation();

		assertEquals(70, rotation[0], "pixel A in the North, at 100 $/m3");
		assertEquals(100, rotation[1], "pixel A in the South, at 10 $/m3: the harvest cost weighs more");
		assertEquals(run.stepsByHand(1, 50, 10, 10, 0.1), rotation[1]);
		assertEquals(run.extBF().rotation()[0], run.extBF().rotation()[1],
				"ExtBF follows a capital, not the price, so both of pixel A's units agree");
	}

	// ---- Capital ----

	@Test
	void capitalFollowsItsCapitalEachYearWithNothingCarried() {
		ReactToyData.write(dir, "worlds/react/capitals/ssp126/EU_capitals_ssp126_2021.csv",
				"Lon,Lat,react_GDP_50,react_GDP_100,react_pop", ReactToyData.PIXEL_A + ",0.893,0.447,1",
				ReactToyData.PIXEL_B + ",0.696,0.348,0.7");
		Run run = run();

		run.decide(2020);
		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.capitalByHand(unit, 0.05), run.extBF().rotation()[unit], "unit " + unit);
		}
		assertEquals(80, run.extBF().rotation()[0], "100 - 1000 x 0.05 x 0.3 = 85, which the float 0.3 puts nearer 80");

		run.decide(2021);
		assertEquals(50, run.extBF().rotation()[0], "100 - 1000 x 0.05 x 1: three places at once");
		assertEquals(run.capitalByHand(2, 0.05), run.extBF().rotation()[2]);
	}

	@Test
	void capitalIgnoresThePrice() {
		Run cheap = run(ReactToyData.context(dir).withForestry().prices((service, region, year) -> 0.01));
		Run dear = run(ReactToyData.context(dir).withForestry().prices((service, region, year) -> 1e6));
		cheap.decide(2020);
		dear.decide(2020);

		assertArrayEquals(cheap.extBF().rotation(), dear.extBF().rotation());
		assertArrayEquals(cheap.extBF().yield(), dear.extBF().yield(), 0);
	}

	@Test
	void aCapitalAftWithASensitivityOf0StaysAtItsInitialRotation() {
		// Forestry plan §1.2: a fixed rotation with react's yields.
		forestryRows(ReactToyData.FORESTRY_ROWS[0], "ExtBF,AFT,1,Hardwood,,,,,,,,0,Capital,react_pop");
		Run run = run();
		for (int year = 2020; year <= 2021; year++) {
			run.decide(year);
			assertArrayEquals(new int[] { 100, 100, 100 }, run.extBF().rotation(), "in " + year);
		}
		assertEquals(1.0, run.extBF().yield()[0], 1e-6, "pixel A at 100 years: 1 m3/ha/yr");
	}

	// ---- yield and cost ----

	@Test
	void theYieldIsTheTablesValueAtTheRotation() {
		Run run = run();
		run.decide(2020);

		for (ForestryManagement m : List.of(run.intBF(), run.extBF())) {
			for (int unit = 0; unit < 3; unit++) {
				assertEquals(run.table(unit, m.rotation()[unit]), m.yield()[unit], 0, m.label() + ", unit " + unit);
			}
		}
		assertEquals(70, run.intBF().rotation()[0]);
		assertEquals(1.15, run.intBF().yield()[0], 1e-6, "pixel A at 70 years: 1.15 m3/ha/yr");
	}

	@Test
	void theCostIsOneHarvestSpreadOverTheRotation() {
		Run run = run();
		run.decide(2020);

		for (ForestryManagement m : List.of(run.intBF(), run.extBF())) {
			for (int unit = 0; unit < 3; unit++) {
				assertEquals(HARVEST_COST / m.rotation()[unit], m.cost()[unit], 0, m.label() + ", unit " + unit);
			}
		}
	}

	@Test
	void withNoForestTheRotationLengthensToTheLongest() {
		Run run = run();
		run.decide(2020);

		assertEquals(100, run.intBF().rotation()[2], "pixel B: profit -C/H rises with H");
		assertEquals(0, run.intBF().yield()[2], 0);
		assertEquals(17.5, run.intBF().cost()[2], 1e-12);
	}

	@Test
	void aNegativeValueInTheForestryFilesIsHandedOverAs0() {
		// 32d plan, Q1. Pixel A has negative values at 80-100 years, an artefact.
		double[] pixelA = { 0.2, 0.5, 0.8, 1.0, 1.1, 1.15, 1.15, -0.1, -0.2, -0.3 };
		for (int year = 2020; year <= 2021; year++) {
			forestryFile(year, pixelA, new double[10]);
		}
		settings("spinup_iterations: 0");
		Run run = run(ReactToyData.context(dir).withForestry().aft("IntBF", 0, 90, false, false));

		run.decide(2020);
		assertEquals(80, run.extBF().rotation()[0], "ExtBF at pixel A");
		assertEquals(0, run.extBF().yield()[0], 0, "-0.1 m3/ha/yr held at 0");
		assertEquals(90, run.intBF().rotation()[0], "IntBF starts at 90 years");
		assertEquals(0, run.intBF().yield()[0], 0, "-0.2 m3/ha/yr held at 0");

		// The step weighs the values as they come: 80 years (-31.9 $/ha) beats 90 (-39.4) and 100 (-47.5). Held
		// at 0, the profits would be -21.9, -19.4 and -17.5, and it would lengthen instead.
		run.decide(2021);
		assertEquals(80, run.intBF().rotation()[0]);
	}

	// ---- which AFTs, and which prices ----

	@Test
	void withForestryOffThereIsNothingToDecide() {
		Run run = run(ReactToyData.context(dir).withForestry().off(ReactElement.FORESTRY));

		assertTrue(run.forestry.managements().isEmpty());
		assertTrue(run.forestry.servicesNeedingPrices().isEmpty());
		assertTrue(run.decide(2020).isEmpty(), "and no forestry data is loaded or needed");
	}

	@Test
	void onlyProspectAftsNeedAPrice() {
		assertEquals(Set.of("Hardwood"), run().forestry.servicesNeedingPrices());

		forestryRows("IntBF,AFT,0,Hardwood,,,,,,,,,,", ReactToyData.FORESTRY_ROWS[1]);
		Run capitalOnly = run();
		assertEquals(List.of("ExtBF"), List.copyOf(capitalOnly.forestry.managements().keySet()));
		assertEquals(Set.of(), capitalOnly.forestry.servicesNeedingPrices());
		capitalOnly.decide(2020);
		assertEquals(80, capitalOnly.extBF().rotation()[0]);
	}

	// ---- bounds, repeatability, the summary ----

	@Test
	void rotationsStayOnTheGridAndNothingIsNaN() {
		for (double price : new double[] { 1e6, 0.01 }) {
			Run run = run(ReactToyData.context(dir).withForestry().prices((service, region, year) -> price));
			for (int year = 2020; year <= 2021; year++) {
				for (ForestryManagement m : run.decide(year).values()) {
					for (int h : m.rotation()) {
						assertTrue(GRID.contains(h), price + " $/m3: rotation " + h);
					}
					for (double[] array : List.of(m.yield(), m.cost())) {
						for (double value : array) {
							assertTrue(Double.isFinite(value), price + " $/m3: " + value);
						}
					}
				}
			}
		}
	}

	@Test
	void twoRunsGiveTheSameAnswer() {
		Run one = run(ReactToyData.context(dir).withForestry().prices((s, region, y) -> region.equals("North") ? 100 : 10));
		Run two = run(ReactToyData.context(dir).withForestry().prices((s, region, y) -> region.equals("North") ? 100 : 10));
		for (int year = 2020; year <= 2021; year++) {
			one.decide(year);
			two.decide(year);
			for (String aft : List.of("IntBF", "ExtBF")) {
				ForestryManagement a = one.forestry.managements().get(aft);
				ForestryManagement b = two.forestry.managements().get(aft);
				assertArrayEquals(a.rotation(), b.rotation());
				assertArrayEquals(a.yield(), b.yield(), 0);
				assertArrayEquals(a.cost(), b.cost(), 0);
			}
		}
	}

	@Test
	void theSummaryDescribesEachAftsYear() {
		Run run = run();
		run.decide(2020);

		// Built with String.format, like the summary, so the decimal separator follows the machine's locale.
		// IntBF: 70, 70 and 100 years.
		String summary = run.intBF().summary();
		assertEquals(String.format("IntBF (Prospect): rotation mean %.1f years, min %d, max %d, %.0f%% of units at %d and"
				+ " %.0f%% at %d; yield mean %.2f m3/ha/yr; cost mean %.2f $/ha/yr", 80.0, 70, 100, 0.0, 10, 100.0 / 3, 100,
				mean(run.intBF().yield()), mean(run.intBF().cost())), summary);
		assertTrue(run.extBF().summary().startsWith("ExtBF (Capital, react_pop): rotation mean"), run.extBF().summary());
	}
}
