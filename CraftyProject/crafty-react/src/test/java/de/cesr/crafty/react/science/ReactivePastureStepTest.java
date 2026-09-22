package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReactivePastureStepTest {

	private static final ReactivePastureStep.Settings SETTINGS = new ReactivePastureStep.Settings(0.5, 0.05, 0.05, 1.0);

	// ---- against R (golden/pasture.csv, golden/stocking_step.csv) ----

	@Test
	void productionMatchesR() {
		GoldenCsv.check("pasture", "production", r -> ReactivePastureStep.production(r.get("npp"), r.get("husbandry"),
				r.get("harvest"), r.get("stocking")));
	}

	@Test
	void profitMatchesR() {
		GoldenCsv.check("pasture", "profit", r -> ReactivePastureStep.profit(economics(r), r.get("harvest"), r.get("stocking")));
	}

	@Test
	void stockingStepsMatchR() {
		GoldenCsv.check("stocking_step", "S_R", r -> {
			ReactivePastureStep.Settings settings = new ReactivePastureStep.Settings(r.get("harvest"), r.get("step"), r.get("min"),
					r.get("max"));
			double s = r.get("sStart");
			for (int step = 0; step < (int) r.get("steps"); step++) {
				s = ReactivePastureStep.stockingStep(s, economics(r), r.get("sPar"), settings);
			}
			return s;
		});
	}

	private static ReactivePastureStep.Economics economics(GoldenCsv.Row r) {
		return new ReactivePastureStep.Economics(r.get("npp"), r.get("husbandry"), r.get("price"), r.get("stockingCost"),
				r.get("husbandryCost"));
	}

	// ---- the rules, stated directly ----

	@Test
	void productionIsZeroWithNoStockingAndApproachesTheHarvestCap() {
		assertEquals(0, ReactivePastureStep.production(10, 1.2, 0.5, 0), 1e-15);
		double cap = 10 * 1.2 * 0.5;
		assertEquals(cap * (1 - Math.exp(-3.22)), ReactivePastureStep.production(10, 1.2, 0.5, 1), 1e-12);
		assertTrue(ReactivePastureStep.production(10, 1.2, 0.5, 1) < cap);
	}

	@Test
	void stockingSettlesNextToTheBestRateFromEitherSide() {
		// Best rate where the marginal revenue meets the stocking cost:
		// NPP x h x H x price x gamma x e^(-gamma S) = cost, so S* = ln(10 x 1 x 0.5 x 150 x 3.22 / 500) / 3.22.
		ReactivePastureStep.Economics pixel = new ReactivePastureStep.Economics(10, 1, 150, 500, 50);
		double best = Math.log(10 * 0.5 * 150 * YieldResponse.GAMMA / 500) / YieldResponse.GAMMA;

		assertEquals(best, settle(0.05, pixel, 0), SETTINGS.step(), "climbing from the minimum");
		assertEquals(best, settle(1.0, pixel, 0), SETTINGS.step(), "falling from the maximum");
	}

	private static double settle(double start, ReactivePastureStep.Economics pixel, double threshold) {
		double s = start;
		for (int step = 0; step < 100; step++) {
			s = ReactivePastureStep.stockingStep(s, pixel, threshold, SETTINGS);
		}
		return s;
	}

	@Test
	void stepsStayWithinTheBounds() {
		ReactivePastureStep.Economics rich = new ReactivePastureStep.Economics(15, 1.75, 1000, 10, 0);
		ReactivePastureStep.Economics barren = new ReactivePastureStep.Economics(0, 1, 150, 500, 50);
		assertEquals(1.0, ReactivePastureStep.stockingStep(1.0, rich, 0, SETTINGS));
		assertEquals(0.05, ReactivePastureStep.stockingStep(0.05, barren, 0, SETTINGS));
	}

	@Test
	void aGainOfADollarPerHectareOrLessIsNotEnoughToStockUp() {
		// Almost no grass and no costs: stocking up gains a few cents a hectare.
		ReactivePastureStep.Economics pixel = new ReactivePastureStep.Economics(0.01, 1, 100, 0, 0);
		assertTrue(ReactivePastureStep.profit(pixel, 0.5, 0.55) > ReactivePastureStep.profit(pixel, 0.5, 0.5));
		assertEquals(0.5, ReactivePastureStep.stockingStep(0.5, pixel, 0, SETTINGS));
	}

	@Test
	void aTieBetweenUpAndDownGoesToUpButMustStillPay() {
		// No grass and no costs: every stocking rate earns 0, so up and down tie. R picks up, which then
		// fails its own test, so the rate stays.
		ReactivePastureStep.Economics pixel = new ReactivePastureStep.Economics(0, 1, 150, 0, 0);
		assertEquals(0.5, ReactivePastureStep.stockingStep(0.5, pixel, 0, SETTINGS));
	}

	@Test
	void theThresholdHoldsBackStockingUpWhenProfitIsPositive() {
		ReactivePastureStep.Economics pixel = new ReactivePastureStep.Economics(10, 1, 150, 500, 50);
		assertEquals(0.35, ReactivePastureStep.stockingStep(0.3, pixel, 0, SETTINGS), 1e-12);
		assertEquals(0.3, ReactivePastureStep.stockingStep(0.3, pixel, 0.5, SETTINGS), "a 50% threshold is not beaten");
	}

	@Test
	void whenProfitIsNegativeTheThresholdMakesNoDifference() {
		// R's rule, kept as it is (plan F2): profit(up) > profit(now) x (1 + threshold) is easy to beat when
		// profit(now) is negative, and the $1 hurdle already requires profit(up) > profit(now).
		ReactivePastureStep.Economics pixel = new ReactivePastureStep.Economics(10, 1, 150, 500, 2000);
		assertTrue(ReactivePastureStep.profit(pixel, 0.5, 0.3) < 0);
		assertEquals(ReactivePastureStep.stockingStep(0.3, pixel, 0, SETTINGS), ReactivePastureStep.stockingStep(0.3, pixel, 10, SETTINGS));
	}
}
