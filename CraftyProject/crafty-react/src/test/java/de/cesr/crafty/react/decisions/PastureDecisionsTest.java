package de.cesr.crafty.react.decisions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import de.cesr.crafty.react.data.ReactToyData;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.science.IntensityFunctions;
import de.cesr.crafty.react.science.ReactivePastureStep;

/**
 * The pasture stage on the toy project. {@link PastureDecisionsGoldenTest} checks it against R; these tests
 * check each rule on its own.
 *
 * The toy project has one reactive pasture AFT, {@code IntP}: husbandry follows {@code react_GDP_50} with
 * {@code react_O_par} 1, its stocking threshold {@code react_S_par} is 0, and its {@code Other_intensity} is
 * 1.5. Its units are 0 = pixel A in the North, 1 = pixel A in the South and 2 = pixel B in the North. Pixel A
 * has rich grass (NPP 12.32 t/ha), pixel B thin grass (3.36 t/ha). Stocking costs 500 $/ha per unit of
 * stocking rate, and husbandry 50 $/ha per unit (the Pasture row of global_costs.csv).
 */
class PastureDecisionsTest {

	private static final String NPP = "Pasture_sum";
	private static final double HARVEST = 0.5;
	private static final double STOCKING_COST = 500;
	private static final double HUSBANDRY_COST = 50;
	private static final ReactivePastureStep.Settings SETTINGS = new ReactivePastureStep.Settings(HARVEST, 0.05, 0.05,
			1.0);

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
	}

	/** One run of the pasture stage on the toy project. */
	private final class Run {
		final ReactInputs inputs;
		final DecisionUnits units;
		final PastureDecisions pasture;
		ReactYearData data;

		Run(ReactToyData.Context context) {
			inputs = ReactInputs.create(ReactConfigLoader.load(dir), context.build());
			units = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
			pasture = PastureDecisions.create(inputs, units);
		}

		Map<String, PastureManagement> decide(int year) {
			data = inputs.forYear(year);
			return pasture.decide(data, prices(year));
		}

		YearPrices prices(int year) {
			return YearPrices.forYear(year, pasture.servicesNeedingPrices(), units, inputs.context().prices());
		}

		PastureManagement intP() {
			return pasture.managements().get("IntP");
		}

		int spinUp() {
			return inputs.config().spinupIterations();
		}

		double npp(int unit) {
			return data.pasture(NPP)[units.pixel(unit)];
		}

		/** IntP's husbandry in a unit, written out with the phase 2 function. */
		double husbandryByHand(int unit) {
			return IntensityFunctions.animalHusbandry(data.capital("react_GDP_50")[units.pixel(unit)] * 1);
		}

		/** Stocking steps in one unit, written out with the phase 2 function: what the stage should do. */
		double stepsByHand(int unit, double stocking, int steps, double husbandry, double price, double threshold,
				double husbandryCost) {
			ReactivePastureStep.Economics economics = new ReactivePastureStep.Economics(npp(unit), husbandry, price,
					STOCKING_COST, husbandryCost);
			for (int step = 0; step < steps; step++) {
				stocking = ReactivePastureStep.stockingStep(stocking, economics, threshold, SETTINGS);
			}
			return stocking;
		}
	}

	private Run run(ReactToyData.Context context) {
		return new Run(context);
	}

	private Run run() {
		return run(ReactToyData.context(dir));
	}

	private void settings(String... lines) {
		ReactToyData.write(dir, ReactConfigLoader.LOCATION.toString(), lines);
	}

	/** The toy sheet, with IntP's row replaced. */
	private void intPRow(String row) {
		String[] rows = ReactToyData.STANDARD_ROWS.clone();
		rows[2] = row;
		ReactToyData.parameters(dir, rows);
	}

	private static double mean(double[] values) {
		return Arrays.stream(values).sum() / values.length;
	}

	// ---- the loop ----

	@Test
	void everyReactivePastureAftIsDecidedInSheetOrder() {
		String[] rows = Arrays.copyOf(ReactToyData.STANDARD_ROWS, ReactToyData.STANDARD_ROWS.length + 1);
		rows[rows.length - 1] = "ExtP,AFT,1,Pasture,,,,,react_GDP_50,0.5,0.2,";
		ReactToyData.parameters(dir, rows);
		Run run = run(ReactToyData.context(dir).aft("ExtP", 0, 0.8, false, true));

		assertEquals(List.of("IntP", "ExtP"), List.copyOf(run.decide(2020).keySet()),
				"the crops AFTs are the crop stage's, and IntFodder is not reactive");
		assertEquals(3, run.intP().stocking().length, "one value per unit");
	}

	@Test
	void theFirstYearIsSpunUpFromTheInitialRate() {
		settings("spinup_iterations: 4");
		Run run = run();
		run.decide(2020);

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.stepsByHand(unit, 0.5, 4, run.husbandryByHand(unit), 100, 0, HUSBANDRY_COST),
					run.intP().stocking()[unit], 0, "unit " + unit + ": 4 steps from 0.5");
		}
		assertTrue(run.intP().stocking()[2] < 0.5, "pixel B's thin grass: stocking falls, " + run.intP().stocking()[2]);
	}

	@Test
	void withNoSpinUpTheFirstYearStaysAtTheInitialRate() {
		settings("spinup_iterations: 0");
		Run run = run();
		run.decide(2020);

		assertArrayEquals(new double[] { 0.5, 0.5, 0.5 }, run.intP().stocking(), 0);

		run.decide(2021);
		assertEquals(run.stepsByHand(2, 0.5, 1, run.husbandryByHand(2), 100, 0, HUSBANDRY_COST),
				run.intP().stocking()[2], 0, "the next year takes one step");
		assertEquals(0.45, run.intP().stocking()[2], 1e-12);
	}

	@Test
	void laterYearsTakeOneStepFromLastYearsRate() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> year == 2020 ? 100 : 400));
		run.decide(2020);
		double[] lastYear = run.intP().stocking().clone();

		run.decide(2021);

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.stepsByHand(unit, lastYear[unit], 1, run.husbandryByHand(unit), 400, 0, HUSBANDRY_COST),
					run.intP().stocking()[unit], 0, "unit " + unit + ": one step from 2020's rate, not from 0.5");
		}
	}

	@Test
	void theSameYearAgainChangesNothingAndAnEarlierYearIsAnError() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 300));
		ReactYearData year2020 = run.inputs.forYear(2020);
		Map<String, PastureManagement> first = run.pasture.decide(year2020, run.prices(2020));
		double[] stocking = run.intP().stocking().clone();

		assertSame(first, run.pasture.decide(year2020, run.prices(2020)));
		assertArrayEquals(stocking, run.intP().stocking(), 0, "no second step");

		run.decide(2021);
		assertThrows(IllegalStateException.class, () -> run.pasture.decide(year2020, run.prices(2020)));
		assertEquals(2021, run.pasture.lastYear());
	}

	@Test
	void pricesOfAnotherYearAreAnError() {
		Run run = run();
		ReactYearData year2020 = run.inputs.forYear(2020);

		assertThrows(IllegalArgumentException.class, () -> run.pasture.decide(year2020, run.prices(2021)));
	}

	// ---- regions ----

	@Test
	void eachRegionIsPricedSeparately() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> region.equals("North") ? 400 : 20));
		run.decide(2020);
		double[] s = run.intP().stocking();

		assertTrue(s[0] > 0.5, "pixel A in the North, at 400 $/t: stocking rises, " + s[0]);
		assertTrue(s[1] < 0.5, "pixel A in the South, at 20 $/t: stocking falls, " + s[1]);
		assertEquals(run.stepsByHand(1, 0.5, run.spinUp(), run.husbandryByHand(1), 20, 0, HUSBANDRY_COST), s[1], 0);
		assertEquals(run.intP().husbandry()[0], run.intP().husbandry()[1], 0,
				"husbandry follows a capital, not the price, so both of pixel A's units agree");
	}

	// ---- husbandry ----

	@Test
	void husbandryFollowsItsCapitalByHand() {
		Run run = run();
		run.decide(2020);

		// animal.husbandry(react_GDP_50 x 1) = 0.5 + 1.25 x sqrt(capital)
		double[] h = run.intP().husbandry();
		assertEquals(0.5 + 1.25 * Math.sqrt(0.893), h[0], 1e-6, "pixel A, react_GDP_50 0.893");
		assertEquals(h[0], h[1], 0, "pixel A, the other region");
		assertEquals(0.5 + 1.25 * Math.sqrt(0.696), h[2], 1e-6, "pixel B, react_GDP_50 0.696");
		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.husbandryByHand(unit), h[unit], 0);
		}
	}

	@Test
	void husbandryIsFlatWithAnOParOfZero() {
		intPRow("IntP,AFT,1,Pasture,,,,,react_GDP_50,0,0,");
		Run run = run();
		run.decide(2020);

		assertArrayEquals(new double[] { 0.5, 0.5, 0.5 }, run.intP().husbandry(), 0);
	}

	@Test
	void withOtherIntensityOffHusbandryIsOtherIntensity() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY));
		run.decide(2020);

		assertArrayEquals(new double[] { 1.5, 1.5, 1.5 }, run.intP().husbandry(), 0);
		assertThrows(IllegalStateException.class, () -> run.intP().intensityCost());
	}

	@Test
	void withOtherIntensityOffTheStockingStepStillWeighsTheHusbandryCost() {
		// Phase 4 plan, Q4: the profit includes husbandry x the service's cost whatever the other-intensity
		// switch. Here husbandry is Other_intensity, 1.5, so the cost is 75 $/ha. It matters only through the
		// threshold: at pixel B, from 0.2 at 200 $/t with a threshold of 0.15, stepping up clears the
		// threshold with the cost counted (78.67 > 1.15 x 64.30) and not without it (153.67 < 1.15 x 139.30).
		settings("spinup_iterations: 1", "stocking:", "  initial: 0.2");
		intPRow("IntP,AFT,1,Pasture,,,,,react_GDP_50,1,0.15,");
		Run run = run(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY).prices((s, r, y) -> 200));
		run.decide(2020);

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.stepsByHand(unit, 0.2, 1, 1.5, 200, 0.15, HUSBANDRY_COST), run.intP().stocking()[unit], 0,
					"unit " + unit);
		}
		assertEquals(0.25, run.intP().stocking()[2], 1e-12, "pixel B steps up");
		assertEquals(0.2, run.stepsByHand(2, 0.2, 1, 1.5, 200, 0.15, 0), 0, "without the husbandry cost it would stay");
	}

	// ---- stocking switched off (Q1) ----

	@Test
	void withStockingOffReactDecidesNoStockingRate() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.STOCKING));
		assertEquals(Set.of(), run.pasture.servicesNeedingPrices(), "no stocking decision, so no price");
		run.decide(2020);
		PastureManagement m = run.intP();

		assertFalse(m.decidesStocking());
		assertThrows(IllegalStateException.class, m::stocking);
		assertThrows(IllegalStateException.class, m::stockingCost);
		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.npp(unit) * m.husbandry()[unit] * HARVEST, m.production()[unit], 0,
					"unit " + unit + ": NPP x husbandry x harvest; the model's Pasture production level is the stocking");
		}
	}

	// ---- production and costs ----

	@Test
	void productionFollowsItsFormula() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 300));
		run.decide(2020);
		PastureManagement m = run.intP();

		assertTrue(m.decidesStocking());
		for (int unit = 0; unit < 3; unit++) {
			assertEquals(ReactivePastureStep.production(run.npp(unit), m.husbandry()[unit], HARVEST, m.stocking()[unit]),
					m.production()[unit], 0, "unit " + unit);
			assertTrue(m.production()[unit] > 0);
		}
	}

	@Test
	void negativeNppGivesNoProductionAndTheLowestStockingRate() {
		// LPJ-GUESS NPP can be below 0. Production, the future _suit, is held at 0 (phase 4 plan, Q5).
		settings("spinup_iterations: 10");
		for (int year = 2020; year <= 2021; year++) {
			ReactToyData.write(dir, "worlds/react/suitabilities/ssp126/pasture/Suit_Agri_pastoral_" + year + ".csv",
					"Lon,Lat,Pasture_sum", ReactToyData.PIXEL_A + ",1.232", ReactToyData.PIXEL_B + ",-0.05");
		}
		Run stocked = run();
		stocked.decide(2020);
		assertEquals(0, stocked.intP().production()[2], 0, "pixel B, stocking on");
		assertEquals(0.05, stocked.intP().stocking()[2], 1e-12, "more stocking only loses more");
		assertTrue(stocked.intP().production()[0] > 0, "pixel A is not affected");

		Run unstocked = run(ReactToyData.context(dir).off(ReactElement.STOCKING));
		unstocked.decide(2020);
		assertEquals(0, unstocked.intP().production()[2], 0, "pixel B, stocking off");
	}

	@Test
	void costsFollowTheirFormulas() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 300));
		run.decide(2020);
		PastureManagement m = run.intP();

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(m.husbandry()[unit] * HUSBANDRY_COST, m.intensityCost()[unit], 0, "husbandry x the Pasture row");
			assertEquals(m.stocking()[unit] * STOCKING_COST, m.stockingCost()[unit], 0, "stocking rate x Stocking");
		}
	}

	// ---- which AFTs, and which prices ----

	@Test
	void withNothingReactiveThereIsNothingToDecide() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.values()));

		assertTrue(run.decide(2020).isEmpty());
		assertTrue(run.pasture.servicesNeedingPrices().isEmpty());
	}

	@Test
	void withOnlyFertiliserAndIrrigationOnThereIsNothingToDecide() {
		// Phase 3 plan, Q6: each switch changes only its own land use.
		Run run = run(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY, ReactElement.STOCKING));

		assertTrue(run.pasture.managements().isEmpty());
		assertTrue(run.decide(2020).isEmpty());
	}

	@Test
	void eitherPastureElementBringsThePastureIn() {
		for (ReactElement element : PastureDecisions.PASTURE_ELEMENTS) {
			ReactToyData.Context context = ReactToyData.context(dir);
			for (ReactElement other : ReactElement.values()) {
				if (other != element) {
					context.off(other);
				}
			}
			assertEquals(List.of("IntP"), List.copyOf(run(context).pasture.managements().keySet()),
					"only " + element + " on");
		}
	}

	@Test
	void onlyStockingNeedsAPrice() {
		assertEquals(Set.of("Pasture"), run().pasture.servicesNeedingPrices());
		assertEquals(Set.of(), run(ReactToyData.context(dir).off(ReactElement.STOCKING)).pasture.servicesNeedingPrices());
	}

	// ---- bounds, repeatability, the summary ----

	@Test
	void stockingStaysWithinItsBoundsAndNothingIsNaN() {
		settings("spinup_iterations: 10");
		for (double price : new double[] { 1e6, 0.01 }) {
			Run run = run(ReactToyData.context(dir).prices((service, region, year) -> price));
			double bound = price == 1e6 ? 1.0 : 0.05;
			for (int year = 2020; year <= 2021; year++) {
				PastureManagement m = run.decide(year).get("IntP");
				for (double[] values : List.of(m.stocking(), m.husbandry(), m.production(), m.stockingCost(),
						m.intensityCost())) {
					for (double value : values) {
						assertTrue(Double.isFinite(value), price + " $/t: " + value);
					}
				}
				for (double s : m.stocking()) {
					assertTrue(s >= 0.05 && s <= 1.0, price + " $/t: stocking " + s);
					assertEquals(bound, s, 1e-12, price + " $/t takes stocking to its bound, where it stays");
				}
			}
		}
	}

	@Test
	void twoRunsGiveTheSameAnswer() {
		Run one = run(ReactToyData.context(dir).prices((service, region, year) -> region.equals("North") ? 250 : 60));
		Run two = run(ReactToyData.context(dir).prices((service, region, year) -> region.equals("North") ? 250 : 60));
		for (int year = 2020; year <= 2021; year++) {
			one.decide(year);
			two.decide(year);
			assertArrayEquals(one.intP().stocking(), two.intP().stocking(), 0);
			assertArrayEquals(one.intP().husbandry(), two.intP().husbandry(), 0);
			assertArrayEquals(one.intP().production(), two.intP().production(), 0);
		}
	}

	@Test
	void theSummaryDescribesEachAftsYear() {
		settings("spinup_iterations: 10");
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 1e6));
		run.decide(2020);

		// Built with String.format, like the summary, so the decimal separator follows the machine's locale.
		String summary = run.intP().summary();
		assertTrue(summary.startsWith(String.format("IntP: stocking mean %.2f, min %.2f, max %.2f, 0%% of units at %.2f"
				+ " and 100%% at %.2f; husbandry mean %.2f", 1.0, 1.0, 1.0, 0.05, 1.0, mean(run.intP().husbandry()))),
				summary);
		assertTrue(summary.endsWith(String.format("production mean %.2f t/ha", mean(run.intP().production()))), summary);
	}

	@Test
	void theSummarySaysWhenStockingIsTheModels() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.STOCKING));
		run.decide(2020);

		String summary = run.intP().summary();
		assertTrue(summary.startsWith("IntP: stocking not reactive (the model's Pasture production level)"), summary);
		assertTrue(summary.contains(String.format("husbandry mean %.2f", mean(run.intP().husbandry()))), summary);
	}
}
