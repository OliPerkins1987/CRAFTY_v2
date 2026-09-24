package de.cesr.crafty.react.decisions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
import de.cesr.crafty.react.science.CropSurfaces;
import de.cesr.crafty.react.science.IntensityFunctions;
import de.cesr.crafty.react.science.Irrigation;
import de.cesr.crafty.react.science.ReactiveNitrogenStep;

/**
 * The cropland stage on the toy project. {@link CropDecisionsGoldenTest} checks it against R; these tests
 * check each rule on its own.
 *
 * The toy project has two reactive crops AFTs on {@code CerealsC3}: {@code IntC3C_irrig} (Prospect,
 * irrigated with efficiency 0.9, anchor 200, other intensity following {@code react_GDP_100} from a floor
 * of 0.75) and {@code ExtC3C} (Capital N following {@code react_GDP_50}, rainfed, anchor 100, other
 * intensity fixed at 0.4). Its units are 0 = pixel A in the North, 1 = pixel A in the South and 2 =
 * pixel B in the North. Pixel A has plenty of runoff; pixel B is short of water.
 */
class CropDecisionsTest {

	private static final String CROP = "CerealsC3";
	private static final ReactiveNitrogenStep.Settings DEFAULT_SETTINGS = new ReactiveNitrogenStep.Settings(0.88, 0.88,
			2.25, 0.15);

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		ReactToyData.project(dir);
	}

	/** One run of the cropland stage on the toy project. */
	private final class Run {
		final ReactInputs inputs;
		final DecisionUnits units;
		final CropDecisions crops;
		ReactYearData data;
		CropSurfaces surfaces;

		Run(ReactToyData.Context context) {
			inputs = ReactInputs.create(ReactConfigLoader.load(dir), context.build());
			units = DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions());
			crops = CropDecisions.create(inputs, units);
		}

		Map<String, CropManagement> decide(int year) {
			data = inputs.forYear(year);
			surfaces = CropSurfaces.fit(data);
			return crops.decide(data, surfaces, prices(year));
		}

		YearPrices prices(int year) {
			return YearPrices.forYear(year, crops.servicesNeedingPrices(), units, inputs.context().prices());
		}

		CropManagement irrigated() {
			return crops.managements().get("IntC3C_irrig");
		}

		CropManagement capital() {
			return crops.managements().get("ExtC3C");
		}

		/**
		 * One O → I → N iteration of IntC3C_irrig in one unit, written out with the phase 2 functions: what
		 * the cropland stage should do, with every element switched on and the default settings.
		 */
		double stepByHand(int unit, double nitrogen, double price) {
			int pixel = units.pixel(unit);
			double o = IntensityFunctions.effFunc(data.capital("react_GDP_100")[pixel], 0.75, 0.2);
			double required = Irrigation.required(surfaces.waterDemand(CROP).waterDemand(pixel, nitrogen), 0.9);
			double level = Irrigation.level(Irrigation.applied(required, data.runoff()[pixel]), required);
			double yearsSinceStart = data.year() - ReactToyData.FIRST_YEAR;
			CropSurfaces.Surface surface = surfaces.crop(CROP);
			return ReactiveNitrogenStep.prospect(nitrogen, 200, 300,
					n -> surface.yield(pixel, n, level, o, 0, yearsSinceStart), price, 1.08, 0, DEFAULT_SETTINGS);
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

	// ---- the loop ----

	@Test
	void everyReactiveCropsAftIsDecidedInSheetOrder() {
		Run run = run();

		assertEquals(List.of("IntC3C_irrig", "ExtC3C"), List.copyOf(run.decide(2020).keySet()),
				"IntP is pasture and IntFodder is not reactive");
		assertEquals(3, run.irrigated().nitrogen().length, "one value per unit");
		assertEquals(300, run.irrigated().nitrogenMaximum(), "n_max_factor 1.5 x Nfert_rate 200");
	}

	@Test
	void theFirstYearIsSpunUpFromTheAnchor() {
		// At 300 $/t N climbs part of the way each step, rather than jumping straight to Nmax.
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 300));
		run.decide(2020);

		for (int unit = 0; unit < 3; unit++) {
			double expected = 200;
			for (int step = 0; step < 5; step++) {
				expected = run.stepByHand(unit, expected, 300);
			}
			assertEquals(expected, run.irrigated().nitrogen()[unit], 1e-12, "unit " + unit + ": 5 steps from 200");
		}
		double n = run.irrigated().nitrogen()[0];
		assertTrue(n > 200 && n < 300, "the price moved N part of the way to Nmax: " + n);
	}

	@Test
	void withNoSpinUpTheFirstYearStaysAtTheAnchor() {
		settings("spinup_iterations: 0");
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 300));
		run.decide(2020);

		assertArrayEquals(new double[] { 200, 200, 200 }, run.irrigated().nitrogen(), 0);

		run.decide(2021);
		assertEquals(run.stepByHand(0, 200, 300), run.irrigated().nitrogen()[0], 1e-12,
				"the next year takes one step");
	}

	@Test
	void laterYearsTakeOneStepFromLastYearsN() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> year == 2020 ? 300 : 40));
		run.decide(2020);
		double[] lastYear = run.irrigated().nitrogen().clone();

		run.decide(2021);

		for (int unit = 0; unit < 3; unit++) {
			assertEquals(run.stepByHand(unit, lastYear[unit], 40), run.irrigated().nitrogen()[unit], 1e-12,
					"unit " + unit + ": one step from 2020's N, not from the anchor");
		}
	}

	@Test
	void theSameYearAgainChangesNothingAndAnEarlierYearIsAnError() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 1500));
		ReactYearData year2020 = run.inputs.forYear(2020);
		CropSurfaces surfaces2020 = CropSurfaces.fit(year2020);
		Map<String, CropManagement> first = run.crops.decide(year2020, surfaces2020, run.prices(2020));
		double[] nitrogen = run.irrigated().nitrogen().clone();

		assertSame(first, run.crops.decide(year2020, surfaces2020, run.prices(2020)));
		assertArrayEquals(nitrogen, run.irrigated().nitrogen(), 0, "no second step");

		run.decide(2021);
		assertThrows(IllegalStateException.class, () -> run.crops.decide(year2020, surfaces2020, run.prices(2020)));
		assertEquals(2021, run.crops.lastYear());
	}

	@Test
	void surfacesOrPricesOfAnotherYearAreAnError() {
		Run run = run();
		ReactYearData year2020 = run.inputs.forYear(2020);
		CropSurfaces surfaces2020 = CropSurfaces.fit(year2020);

		assertThrows(IllegalArgumentException.class, () -> run.crops.decide(year2020, surfaces2020, run.prices(2021)));
	}

	// ---- regions ----

	@Test
	void eachRegionIsPricedSeparately() {
		Run run = run(ReactToyData.context(dir)
				.prices((service, region, year) -> region.equals("North") ? 3000 : 30));
		run.decide(2020);
		double[] n = run.irrigated().nitrogen();

		assertTrue(n[0] > 200, "pixel A in the North, at 3000 $/t: N rises, " + n[0]);
		assertTrue(n[1] < 200, "pixel A in the South, at 30 $/t: N falls, " + n[1]);
		double south = 200;
		for (int step = 0; step < 5; step++) {
			south = run.stepByHand(1, south, 30);
		}
		assertEquals(south, n[1], 1e-12);
		assertEquals(run.capital().nitrogen()[0], run.capital().nitrogen()[1], 0,
				"a Capital AFT does not use the price, so both of pixel A's units agree");
	}

	// ---- each element ----

	@Test
	void otherIntensityFollowsItsCapitalByHand() {
		Run run = run();
		run.decide(2020);

		// Eff_func(capital, floor 0.75, threshold 0.2): 0.75 + (capital - 0.2) x 0.25 / 0.8
		double[] o = run.irrigated().otherIntensity();
		assertEquals(0.8271875, o[0], 1e-6, "pixel A, react_GDP_100 0.447");
		assertEquals(0.8271875, o[1], 1e-6, "pixel A, the other region");
		assertEquals(0.79625, o[2], 1e-6, "pixel B, react_GDP_100 0.348");
		assertArrayEquals(new double[] { 0.4, 0.4, 0.4 }, run.capital().otherIntensity(), 0,
				"ExtC3C has no react_O_capital, so it stays at Other_intensity");
	}

	@Test
	void aCapitalAftsNFollowsItsCapitalByHand() {
		String[] rows = ReactToyData.STANDARD_ROWS.clone();
		rows[1] = "ExtC3C,AFT,1,C3cereals,Capital,react_GDP_50,1,,,,,";
		ReactToyData.parameters(dir, rows);
		Run run = run();
		run.decide(2020);

		// Nfert_rate 100 x min(1, react_GDP_50 x 1)
		double[] n = run.capital().nitrogen();
		assertEquals(89.3, n[0], 1e-4, "pixel A, react_GDP_50 0.893");
		assertEquals(69.6, n[2], 1e-4, "pixel B, react_GDP_50 0.696");
		assertEquals(69.6 * 1.08, run.capital().nitrogenCost()[2], 1e-4);
	}

	@Test
	void waterIsCappedByRunoffByHand() {
		Run run = run();
		run.decide(2020);
		CropManagement m = run.irrigated();

		assertEquals(1, m.irrigationLevel()[0], 0, "pixel A: runoff 7199 m3/ha is plenty");
		assertEquals(1096, m.waterApplied()[2], 1e-9, "pixel B: all the runoff, 109.6 mm");
		assertTrue(m.irrigationLevel()[2] < 0.3, "pixel B needs at least 3647 / 0.9 m3/ha");
		assertEquals(1096 * 0.79 * 0.5, m.irrigationCost()[2], 1e-4, "water x cost index 0.79 x Water 0.5");
		assertArrayEquals(new double[] { 0, 0, 0 }, run.capital().irrigationLevel(), 0, "ExtC3C is rainfed");
		assertArrayEquals(new double[] { 0, 0, 0 }, run.capital().waterApplied(), 0);
		assertThrows(IllegalStateException.class, () -> run.capital().irrigationCost());
	}

	@Test
	void waterAndYieldAreWorkedOutAtTheFinalN() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 1500));
		run.decide(2020);
		CropManagement m = run.irrigated();

		for (int unit = 0; unit < 3; unit++) {
			int pixel = run.units.pixel(unit);
			double required = Irrigation.required(run.surfaces.waterDemand(CROP).waterDemand(pixel, m.nitrogen()[unit]),
					0.9);
			double applied = Irrigation.applied(required, run.data.runoff()[pixel]);
			assertEquals(applied, m.waterApplied()[unit], 0, "unit " + unit);
			assertEquals(Irrigation.level(applied, required), m.irrigationLevel()[unit], 0);
			assertEquals(run.surfaces.crop(CROP).yield(pixel, m.nitrogen()[unit], m.irrigationLevel()[unit],
					m.otherIntensity()[unit], 0, 0), m.yield()[unit], 0);
		}
	}

	@Test
	void costsFollowTheirFormulas() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 1500));
		run.decide(2020);

		for (CropManagement m : run.crops.managements().values()) {
			for (int unit = 0; unit < 3; unit++) {
				assertEquals(m.nitrogen()[unit] * 1.08, m.nitrogenCost()[unit], 0, m.label());
				assertEquals(m.otherIntensity()[unit] * 50, m.intensityCost()[unit], 0, m.label());
			}
		}
		CropManagement m = run.irrigated();
		for (int unit = 0; unit < 3; unit++) {
			double index = run.inputs.checked().irrigationCost().at(run.units.pixel(unit));
			assertEquals(m.waterApplied()[unit] * index * 0.5, m.irrigationCost()[unit], 0);
		}
	}

	@Test
	void withFertiliserOffNStaysAtTheAnchor() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.FERTILISER).prices((s, r, y) -> 3000));

		assertEquals(Set.of(), run.crops.servicesNeedingPrices());
		run.decide(2020);
		assertArrayEquals(new double[] { 200, 200, 200 }, run.irrigated().nitrogen(), 0);
		assertArrayEquals(new double[] { 100, 100, 100 }, run.capital().nitrogen(), 0);
		assertThrows(IllegalStateException.class, () -> run.irrigated().nitrogenCost());
	}

	@Test
	void withIrrigationOffWaterDemandIsReadAtTheAnchorWithFullEfficiency() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.IRRIGATION).prices((s, r, y) -> 1500));
		run.decide(2020);
		CropManagement m = run.irrigated();
		int pixelA = run.units.pixel(0);

		assertNotEquals(200, m.nitrogen()[0], "N still moves");
		assertEquals(run.surfaces.waterDemand(CROP).waterDemand(pixelA, 200), m.waterApplied()[0], 0,
				"pixel A: demand at Nfert_rate, efficiency 1");
		assertEquals(1096, m.waterApplied()[2], 1e-9, "pixel B: still capped by runoff");
		assertThrows(IllegalStateException.class, m::irrigationCost);
	}

	@Test
	void withOtherIntensityOffItStaysAtOtherIntensity() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.OTHER_INTENSITY));
		run.decide(2020);

		assertArrayEquals(new double[] { 0.75, 0.75, 0.75 }, run.irrigated().otherIntensity(), 0);
		assertThrows(IllegalStateException.class, () -> run.irrigated().intensityCost());
	}

	@Test
	void withNothingReactiveThereIsNothingToDecide() {
		Run run = run(ReactToyData.context(dir).off(ReactElement.values()));

		assertTrue(run.decide(2020).isEmpty());
		assertTrue(run.crops.servicesNeedingPrices().isEmpty());
	}

	@Test
	void onlyProspectAftsNeedAPrice() {
		assertEquals(Set.of("C3cereals"), run().crops.servicesNeedingPrices());
	}

	@Test
	void withOnlyStockingOnThereIsNothingToDecide() {
		// Phase 3 plan, Q6: each switch changes only its own land use.
		Run run = run(ReactToyData.context(dir).off(ReactElement.FERTILISER, ReactElement.IRRIGATION,
				ReactElement.OTHER_INTENSITY));

		assertTrue(run.crops.managements().isEmpty());
		assertTrue(run.decide(2020).isEmpty());
	}

	@Test
	void anyOneCropElementBringsTheCropsIn() {
		for (ReactElement element : CropDecisions.CROP_ELEMENTS) {
			ReactToyData.Context context = ReactToyData.context(dir);
			for (ReactElement other : ReactElement.values()) {
				if (other != element) {
					context.off(other);
				}
			}
			assertEquals(List.of("IntC3C_irrig", "ExtC3C"), List.copyOf(run(context).crops.managements().keySet()),
					"only " + element + " on");
		}
	}

	// ---- the summary for the log (Q5) ----

	@Test
	void theSummaryDescribesEachAftsYear() {
		Run run = run(ReactToyData.context(dir).prices((service, region, year) -> 1e6));
		run.decide(2020);

		// Built with String.format, like the summary, so the decimal separator follows the machine's locale.
		String irrigated = run.irrigated().summary();
		assertTrue(irrigated.startsWith(String.format("IntC3C_irrig: N mean %.1f, min %.1f, max %.1f kg/ha (Nmax %.1f)",
				300.0, 300.0, 300.0, 300.0)), irrigated);
		assertTrue(irrigated.contains("0% of units at 0 and 100% at Nmax"), irrigated);
		assertTrue(irrigated.contains("irrigation level mean"), irrigated);

		String capital = run.capital().summary();
		assertTrue(capital.startsWith(String.format("ExtC3C: N mean %.1f, min %.1f, max %.1f kg/ha (Nmax %.1f)", 100.0,
				100.0, 100.0, 150.0)), capital);
		assertTrue(capital.contains("0% of units at 0 and 0% at Nmax"), capital);
		assertTrue(capital.endsWith("rainfed"), capital);
	}

	@Test
	void theSummaryMeansAreOverUnits() {
		Run run = run();
		run.decide(2020);
		CropManagement m = run.irrigated();
		double meanYield = (m.yield()[0] + m.yield()[1] + m.yield()[2]) / 3;
		double meanLevel = (m.irrigationLevel()[0] + m.irrigationLevel()[1] + m.irrigationLevel()[2]) / 3;

		assertTrue(m.summary().contains(String.format("yield mean %.2f t/ha", meanYield)), m.summary());
		assertTrue(m.summary().endsWith(String.format("irrigation level mean %.2f", meanLevel)), m.summary());
	}

	// ---- bounds, the tech term, repeatability ----

	@Test
	void nStaysBetweenZeroAndNmaxAndNothingIsNaN() {
		for (double price : new double[] { 1e6, 0.01 }) {
			Run run = run(ReactToyData.context(dir).prices((service, region, year) -> price));
			for (int year = 2020; year <= 2021; year++) {
				for (CropManagement m : run.decide(year).values()) {
					for (double[] values : List.of(m.nitrogen(), m.otherIntensity(), m.waterApplied(),
							m.irrigationLevel(), m.yield(), m.nitrogenCost(), m.intensityCost())) {
						for (double value : values) {
							assertTrue(Double.isFinite(value), m.label() + " " + value);
						}
					}
					for (double n : m.nitrogen()) {
						assertTrue(n >= 0 && n <= m.nitrogenMaximum(), m.label() + " N " + n);
					}
				}
				if (price == 1e6) {
					assertArrayEquals(new double[] { 300, 300, 300 }, run.irrigated().nitrogen(), 0,
							"a huge price takes N to Nmax, where it stays");
				}
			}
		}
	}

	@Test
	void theTechTermCountsYearsFromTheFirstYear() {
		settings("yield_tech_change: 0.02");
		Run run = run();
		for (int year = 2020; year <= 2021; year++) {
			run.decide(year);
			CropManagement m = run.irrigated();
			for (int unit = 0; unit < 3; unit++) {
				assertEquals(run.surfaces.crop(CROP).yield(run.units.pixel(unit), m.nitrogen()[unit],
						m.irrigationLevel()[unit], m.otherIntensity()[unit], 0.02, year - 2020), m.yield()[unit], 0,
						year + " unit " + unit);
			}
		}
	}

	@Test
	void twoRunsGiveTheSameAnswer() {
		Run one = run(ReactToyData.context(dir).prices((service, region, year) -> region.equals("North") ? 900 : 60));
		Run two = run(ReactToyData.context(dir).prices((service, region, year) -> region.equals("North") ? 900 : 60));
		for (int year = 2020; year <= 2021; year++) {
			one.decide(year);
			two.decide(year);
			for (String label : List.of("IntC3C_irrig", "ExtC3C")) {
				CropManagement a = one.crops.managements().get(label);
				CropManagement b = two.crops.managements().get(label);
				assertArrayEquals(a.nitrogen(), b.nitrogen(), 0);
				assertArrayEquals(a.otherIntensity(), b.otherIntensity(), 0);
				assertArrayEquals(a.waterApplied(), b.waterApplied(), 0);
				assertArrayEquals(a.yield(), b.yield(), 0);
			}
		}
	}
}
