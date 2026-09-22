package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

class ReactiveNitrogenStepTest {

	private static final ReactiveNitrogenStep.Settings SETTINGS = new ReactiveNitrogenStep.Settings(0.88, 0.88, 2.25, 0.15);

	// ---- against R (golden/nitrogen_step.csv) ----

	@Test
	void theCalibrationRuleMatchesR() {
		GoldenCsv.check("nitrogen_step", "N_R", row -> run(row, false));
	}

	@Test
	void theFixedRuleMatchesTheFixedR() {
		GoldenCsv.check("nitrogen_step", "N_fixed", row -> run(row, true));
	}

	/** Runs a golden row: 'steps' steps from nStart, on the pixel's yield surface. */
	private static double run(GoldenCsv.Row row, boolean fixed) {
		double y0 = row.get("y0"), y0200 = row.get("y0200"), y1000 = row.get("y1000");
		double yi0 = row.get("yi0"), yi1000 = row.get("yi1000");
		double a = YieldResponse.a(y0), b = YieldResponse.b(y0, y1000), c = YieldResponse.c(y0, yi0);
		double d = YieldResponse.d(y0, y1000, yi0, yi1000);
		double alpha = YieldResponse.alpha(y0, y0200, y1000), beta = YieldResponse.beta(y0, yi0);
		double level = row.get("level"), intensity = row.get("mgmt");
		DoubleUnaryOperator yieldAt = n -> YieldResponse.yield(a, b, c, d, alpha, beta, n, level, intensity, 0, 0);
		ReactiveNitrogenStep.Settings settings = new ReactiveNitrogenStep.Settings(row.get("pAlpha"), row.get("pBeta"),
				row.get("lambda"), row.get("scale"));

		double n = row.get("nStart");
		for (int step = 0; step < (int) row.get("steps"); step++) {
			n = fixed
					? ReactiveNitrogenStep.prospect(n, row.get("nBase"), row.get("nMax"), yieldAt, row.get("price"),
							row.get("nCost"), row.get("ref"), settings)
					: ReactiveNitrogenStep.asInR(n, row.get("nBase"), row.get("nMax"), yieldAt, row.get("price"),
							row.get("nCost"), row.get("ref"), settings);
		}
		return n;
	}

	// ---- the rules, stated directly ----

	/** Yield rises by 0.01 t/ha for each kg of N: a return of 0.01 t per kg N everywhere. */
	private static final DoubleUnaryOperator STRAIGHT_LINE = n -> 2 + 0.01 * n;

	@Test
	void aHandWorkedStepWithNoHiddenTimesTen() {
		// Return 0.01 t/kg x $200/t = $2/kg, less $1/kg for the N: a gain of 1, so the value is 1^0.88 = 1.
		// N moves (1 x 0.15)^2 = 2.25% of the way from 200 up to Nmax 300: 2.25 kg.
		assertEquals(202.25, ReactiveNitrogenStep.prospect(200, 200, 300, STRAIGHT_LINE, 200, 1, 0, SETTINGS), 1e-9);
		assertEquals(202.25, ReactiveNitrogenStep.asInR(200, 200, 300, STRAIGHT_LINE, 200, 1, 0, SETTINGS), 1e-9);
	}

	@Test
	void atNmaxAndPricesDropRsRuleIsStuckButTheFixMovesNBackTowardsTheAnchor() {
		// At Nmax the return on the N above the anchor is 0.01 t/kg x $50/t = $0.5/kg, less $1/kg: a loss.
		double stuck = ReactiveNitrogenStep.asInR(300, 200, 300, STRAIGHT_LINE, 50, 1, 0, SETTINGS);
		double fixed = ReactiveNitrogenStep.prospect(300, 200, 300, STRAIGHT_LINE, 50, 1, 0, SETTINGS);

		assertEquals(300, stuck, "R's rule sees no return and no room to move at Nmax");
		// value = -2.25 x 0.5^0.88; N moves (value x 0.15)^2 of the way from Nmax back to the anchor.
		double value = -2.25 * Math.pow(0.5, 0.88);
		assertEquals(300 - value * value * 0.0225 * 100, fixed, 1e-9);
		assertTrue(fixed < 300 && fixed > 200);
	}

	@Test
	void atNmaxAndStillProfitableNStaysUnderBothRules() {
		assertEquals(300, ReactiveNitrogenStep.asInR(300, 200, 300, STRAIGHT_LINE, 500, 1, 0, SETTINGS));
		assertEquals(300, ReactiveNitrogenStep.prospect(300, 200, 300, STRAIGHT_LINE, 500, 1, 0, SETTINGS));
	}

	@Test
	void awayFromNmaxTheTwoRulesAreTheSame() {
		DoubleUnaryOperator curve = n -> 2 + 4 * (1 - Math.exp(-3 * n / 1000));
		for (double n : new double[] { 0, 50, 199.9, 200, 250, 299.999 }) {
			for (double price : new double[] { 30, 300, 3000 }) {
				assertEquals(ReactiveNitrogenStep.asInR(n, 200, 300, curve, price, 1.08, 0.2, SETTINGS),
						ReactiveNitrogenStep.prospect(n, 200, 300, curve, price, 1.08, 0.2, SETTINGS),
						"N " + n + ", price " + price);
			}
		}
	}

	@Test
	void whenNmaxIsTheAnchorNStaysThere() {
		assertEquals(200, ReactiveNitrogenStep.prospect(200, 200, 200, STRAIGHT_LINE, 50, 1, 0, SETTINGS));
		assertEquals(200, ReactiveNitrogenStep.prospect(200, 200, 200, STRAIGHT_LINE, 5000, 1, 0, SETTINGS));
	}

	@Test
	void aLargeGainTakesNToNmaxExactlyAndALargeLossToZero() {
		assertEquals(300, ReactiveNitrogenStep.prospect(250, 200, 300, STRAIGHT_LINE, 100_000, 1, 0, SETTINGS),
				"clamped to Nmax, exactly, so the next step sees N == Nmax");
		assertEquals(0, ReactiveNitrogenStep.prospect(100, 200, 300, STRAIGHT_LINE, 0, 1000, 0, SETTINGS), 1e-15,
				"clamped to 0");
	}

	@Test
	void theReferencePointMustBeBeatenBeforeAReturnCountsAsAGain() {
		// The same $1/kg net return: a gain with reference 0, a loss with reference 1.5.
		assertTrue(ReactiveNitrogenStep.prospect(200, 200, 300, STRAIGHT_LINE, 200, 1, 0, SETTINGS) > 200);
		assertTrue(ReactiveNitrogenStep.prospect(200, 200, 300, STRAIGHT_LINE, 200, 1, 1.5, SETTINGS) < 200);
	}
}
