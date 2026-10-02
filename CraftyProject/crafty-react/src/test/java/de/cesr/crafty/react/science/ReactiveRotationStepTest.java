package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReactiveRotationStepTest {

	/** The default grid, which the golden files use. */
	private static final List<Integer> GRID = List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);

	// ---- against R (golden/forestry_step.csv, golden/forestry_capital.csv) ----

	@Test
	void prospectStepsMatchR() {
		GoldenCsv.check("forestry_step", "H_R", r -> {
			int rotation = (int) r.get("hStart");
			for (int step = 0; step < (int) r.get("steps"); step++) {
				rotation = ReactiveRotationStep.prospectStep(rotation, economics(r), r.get("rPar"), GRID);
			}
			return rotation;
		});
	}

	@Test
	void rotationCostMatchesR() {
		GoldenCsv.check("forestry_step", "cost_R",
				r -> ReactiveRotationStep.rotationCost(r.get("harvestCost"), (int) r.get("H_R")));
	}

	@Test
	void profitMatchesR() {
		GoldenCsv.check("forestry_step", "profit_R", r -> ReactiveRotationStep.profit(economics(r), (int) r.get("H_R")));
	}

	@Test
	void capitalRotationsMatchR() {
		GoldenCsv.check("forestry_capital", "H_R",
				r -> ReactiveRotationStep.capitalRotation(r.get("baseline"), r.get("rPar"), r.get("capital"), GRID));
	}

	private static ReactiveRotationStep.Economics economics(GoldenCsv.Row r) {
		return new ReactiveRotationStep.Economics(rotation -> r.get("yield_" + rotation), r.get("price"),
				r.get("harvestCost"));
	}

	// ---- Prospect's step, stated directly ----

	/** A place with one yield per rotation of {@link #GRID}, shortest first. */
	private static ReactiveRotationStep.Economics place(double price, double harvestCost, double... yields) {
		return new ReactiveRotationStep.Economics(rotation -> yields[GRID.indexOf(rotation)], price, harvestCost);
	}

	private static int step(int rotation, ReactiveRotationStep.Economics place, double threshold) {
		return ReactiveRotationStep.prospectStep(rotation, place, threshold, GRID);
	}

	@Test
	void lengtheningNeedsOnlyMoreProfit() {
		// Price 1, no cost: profit is the yield. At 50 years, 40 gives 1 less and 60 gives 0.25 more.
		ReactiveRotationStep.Economics place = place(1, 0, 1, 2, 3, 4, 5, 5.25, 5.5, 5.75, 6, 6.25);
		assertEquals(60, step(50, place, 0));
		assertEquals(60, step(50, place, 100), "the threshold applies only to shortening");
	}

	@Test
	void shorteningNeedsTheThreshold() {
		// Profit now 100; shorter 120 (20% more); longer 90.
		ReactiveRotationStep.Economics place = place(1, 0, 120, 120, 120, 120, 100, 90, 90, 90, 90, 90);
		assertEquals(50, step(50, place, 0.5), "a 50% threshold is not beaten");
		assertEquals(40, step(50, place, 0.1));
	}

	@Test
	void shorteningNeedsAGainOfMoreThanADollarPerHectare() {
		assertEquals(50, step(50, place(1, 0, 5.75, 5.75, 5.75, 5.75, 5, 4, 4, 4, 4, 4), 0), "a gain of $0.75");
		assertEquals(50, step(50, place(1, 0, 6, 6, 6, 6, 5, 4, 4, 4, 4, 4), 0), "a gain of exactly $1");
		assertEquals(40, step(50, place(1, 0, 6.5, 6.5, 6.5, 6.5, 5, 4, 4, 4, 4, 4), 0), "a gain of $1.50");
	}

	@Test
	void aTieGoesToTheShorterWhichMustStillPay() {
		// Both neighbours gain 2: shorter wins the tie.
		ReactiveRotationStep.Economics equalGains = place(1, 0, 7, 7, 7, 7, 5, 7, 7, 7, 7, 7);
		assertEquals(40, step(50, equalGains, 0));
		assertEquals(50, step(50, equalGains, 1), "shorter won the tie but fails the threshold, so the rotation stays");
		// Flat profit: both neighbours gain 0; shorter wins the tie and fails the hurdle.
		assertEquals(50, step(50, place(100, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), 0));
	}

	@Test
	void theEndsOfTheGrid() {
		ReactiveRotationStep.Economics rising = place(10, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		ReactiveRotationStep.Economics falling = place(10, 0, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
		assertEquals(10, step(10, falling, 0), "can't shorten below the shortest");
		assertEquals(100, step(100, rising, 0), "can't lengthen beyond the longest");
		assertEquals(20, step(10, rising, 0));
		assertEquals(90, step(100, falling, 0));
	}

	@Test
	void whenLosingMoneyTheThresholdMakesNoDifference() {
		// Price 10, a harvest costs 6000: profit at 40, 50, 60 years is -50, -100, -100.
		ReactiveRotationStep.Economics place = place(10, 6000, 10, 10, 10, 10, 2, 0, 0, 0, 0, 0);
		assertEquals(-100, ReactiveRotationStep.profit(place, 50), 1e-12);
		assertEquals(40, step(50, place, 0));
		assertEquals(40, step(50, place, 5));
	}

	@Test
	void withNoForestAHarvestCostLengthensTheRotation() {
		ReactiveRotationStep.Economics noForest = place(100, 1750, new double[10]);
		assertEquals(20, step(10, noForest, 0), "profit -C/H rises with H");
		int rotation = 10;
		for (int i = 0; i < 9; i++) {
			rotation = step(rotation, noForest, 0);
		}
		assertEquals(100, rotation, "nine steps from the shortest to the longest");
		assertEquals(50, step(50, place(100, 0, new double[10]), 0), "with no cost every rotation earns 0");
	}

	@Test
	void settlesAtTheBestRotationFromEitherEnd() {
		// Price 100, a harvest costs 1000: profit is highest at 60 years.
		ReactiveRotationStep.Economics place = place(100, 1000, 1, 2, 3, 4, 5, 6, 5, 4, 3, 2);
		for (int start : List.of(10, 100)) {
			int rotation = start;
			for (int i = 0; i < 10; i++) {
				rotation = step(rotation, place, 0);
			}
			assertEquals(60, rotation, "from " + start);
		}
	}

	@Test
	void anUnevenGridMovesOnePlaceAtATime() {
		List<Integer> uneven = List.of(20, 40, 80);
		ReactiveRotationStep.Economics place = new ReactiveRotationStep.Economics(rotation -> rotation / 10.0, 10, 0);
		assertEquals(40, ReactiveRotationStep.prospectStep(20, place, 0, uneven));
		assertEquals(80, ReactiveRotationStep.prospectStep(40, place, 0, uneven));
		assertEquals(80, ReactiveRotationStep.prospectStep(80, place, 0, uneven));
	}

	@Test
	void aRotationOffTheGridIsAnError() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> step(35, place(1, 0, new double[10]), 0));
		assertTrue(e.getMessage().contains("35"), e.getMessage());
	}

	// ---- cost and profit ----

	@Test
	void theCostIsOneHarvestSpreadOverTheRotation() {
		assertEquals(35, ReactiveRotationStep.rotationCost(1750, 50), 1e-12);
		ReactiveRotationStep.Economics place = place(100, 1750, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1);
		assertEquals(2 * 100 - 35, ReactiveRotationStep.profit(place, 50), 1e-12);
		assertEquals(-35, ReactiveRotationStep.profit(place(100, 1750, new double[10]), 50), 1e-12, "no forest");
	}

	// ---- Capital's rotation ----

	private static int capital(double baseline, double sensitivity, double capital) {
		return ReactiveRotationStep.capitalRotation(baseline, sensitivity, capital, GRID);
	}

	@Test
	void aSensitivityOf0KeepsTheBaseline() {
		for (int baseline : GRID) {
			assertEquals(baseline, capital(baseline, 0, 0.7));
			assertEquals(baseline, capital(baseline, 0, 5));
		}
	}

	@Test
	void aSensitivityOfOneHundredthTakesTenYearsOffPerUnitOfCapital() {
		assertEquals(40, capital(50, 0.01, 1));
		assertEquals(30, capital(50, 0.01, 2));
		assertEquals(70, capital(50, -0.02, 1), "a negative sensitivity lengthens the rotation");
	}

	@Test
	void aTieGoesToTheShorter() {
		// 1000 x 0.045 rounds to exactly 45, so the target is exactly 55 years.
		assertEquals(50, capital(100, 0.045, 1));
	}

	@Test
	void beyondEitherEndGivesThatEnd() {
		assertEquals(10, capital(30, 0.1, 1), "a target of -70 years");
		assertEquals(100, capital(80, -0.1, 1), "a target of 180 years");
	}

	@Test
	void theForestryPlanRevision7Table() {
		// Baseline 100 years and sensitivity 0.09, which spans 100 to 10 years over capital 0 to 1.
		double[] capitals = { 0, 0.1, 0.2, 0.3, 0.4, 0.6, 0.8, 1 };
		int[] rotations = { 100, 90, 80, 70, 60, 50, 30, 10 };
		for (int i = 0; i < capitals.length; i++) {
			assertEquals(rotations[i], capital(100, 0.09, capitals[i]), "capital " + capitals[i]);
		}
	}

	@Test
	void anUnevenGridTakesTheNearestRotation() {
		List<Integer> uneven = List.of(20, 40, 80);
		assertEquals(80, ReactiveRotationStep.capitalRotation(61, 0, 0, uneven));
		assertEquals(40, ReactiveRotationStep.capitalRotation(60, 0, 0, uneven), "a tie goes to the shorter");
		assertEquals(40, ReactiveRotationStep.capitalRotation(59, 0, 0, uneven));
	}
}
