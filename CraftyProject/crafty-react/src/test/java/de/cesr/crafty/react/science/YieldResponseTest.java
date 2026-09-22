package de.cesr.crafty.react.science;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class YieldResponseTest {

	// ---- against R (golden/coefficients.csv, golden/yield.csv) ----

	@Test
	void aMatchesR() {
		GoldenCsv.check("coefficients", "A", r -> YieldResponse.a(r.get("y0")));
	}

	@Test
	void bMatchesR() {
		GoldenCsv.check("coefficients", "B", r -> YieldResponse.b(r.get("y0"), r.get("y1000")));
	}

	@Test
	void cMatchesR() {
		GoldenCsv.check("coefficients", "C", r -> YieldResponse.c(r.get("y0"), r.get("yi0")));
	}

	@Test
	void dMatchesR() {
		GoldenCsv.check("coefficients", "D",
				r -> YieldResponse.d(r.get("y0"), r.get("y1000"), r.get("yi0"), r.get("yi1000")));
	}

	@Test
	void alphaMatchesR() {
		GoldenCsv.check("coefficients", "alpha", r -> YieldResponse.alpha(r.get("y0"), r.get("y0200"), r.get("y1000")));
	}

	@Test
	void betaMatchesR() {
		GoldenCsv.check("coefficients", "beta", r -> YieldResponse.beta(r.get("y0"), r.get("yi0")));
	}

	@Test
	void yieldMatchesR() {
		GoldenCsv.check("yield", "yield",
				r -> YieldResponse.yield(r.get("A"), r.get("B"), r.get("C"), r.get("D"), r.get("alpha"), r.get("beta"),
						r.get("N"), r.get("level"), r.get("mgmt"), r.get("tech"), r.get("ts")));
	}

	// ---- the rules, stated directly ----

	@Test
	void betaIsTheSameInEveryPixelBecauseItsMiddlePointIsMadeUp() {
		double expected = -Math.log(0.2) * 2;
		assertEquals(expected, YieldResponse.BETA_WHEN_FLAT, 1e-15);
		assertEquals(expected, YieldResponse.beta(1.0, 3.0), 1e-12);
		assertEquals(expected, YieldResponse.beta(0.0, 12.5), 1e-12);
		assertEquals(expected, YieldResponse.beta(5.0, 2.0), 1e-12, "Also when irrigation lowers the yield");
		assertEquals(expected, YieldResponse.beta(4.0, 4.0), 1e-15, "0/0: the same value, not R's 27.63");
	}

	@Test
	void alphaIsZeroWhereTheNResponseIsFlat() {
		assertEquals(0, YieldResponse.alpha(2, 2, 2), "0/0: not R's 57.56");
		assertEquals(0, YieldResponse.alpha(0, 0, 0));
	}

	@Test
	void alphaKeepsRsClampsForOtherOddYields() {
		double top = -Math.log(1 - 0.9999999) * 5;
		assertEquals(top, YieldResponse.alpha(2, 2.5, 2), 1e-9, "x/0 positive is +infinity, clamped");
		// A ratio clamped to 0 gives -ln(1) = -0.0, hence the delta.
		assertEquals(0, YieldResponse.alpha(2, 1.5, 2), 1e-15, "x/0 negative is -infinity, clamped to 0");
		assertEquals(0, YieldResponse.alpha(2, 1.8, 5), 1e-15, "200 kg N below 0 N");
		assertEquals(top, YieldResponse.alpha(2, 5.5, 5), 1e-9, "200 kg N above 1000 kg N");
		assertEquals(-Math.log(1 - 0.5) * 5, YieldResponse.alpha(0, 0.5, 1), 1e-12, "halfway at 200 kg N");
	}

	@Test
	void theSurfaceGoesThroughTheLpjGuessYields() {
		double y0 = 2, y0200 = 3.5, y1000 = 5, yi0 = 4, yi1000 = 9;
		double a = YieldResponse.a(y0), b = YieldResponse.b(y0, y1000), c = YieldResponse.c(y0, yi0);
		double d = YieldResponse.d(y0, y1000, yi0, yi1000);
		double alpha = YieldResponse.alpha(y0, y0200, y1000), beta = YieldResponse.beta(y0, yi0);

		// Within R's 7-figure management normaliser (see fullOtherIntensityIsNormalisedToOne):
		// at N 0, rainfed, full intensity, no tech change, the yield is A;
		assertEquals(y0, YieldResponse.yield(a, b, c, d, alpha, beta, 0, 0, 1, 0, 0), 1e-6);
		// at 200 kg N, rainfed, it is the 200 kg N yield, since alpha was fitted from it;
		assertEquals(y0200, YieldResponse.yield(a, b, c, d, alpha, beta, 200, 0, 1, 0, 0), 1e-6);
		// at 1000 kg N, irrigated, it is close to the irrigated 1000 kg N yield (the curves only approach
		// their tops, so not exactly).
		assertEquals(yi1000, YieldResponse.yield(a, b, c, d, alpha, beta, 1000, 1, 1, 0, 0), 0.5);
	}

	@Test
	void inputsAreClampedToZeroToOne() {
		double[] c = { 2, 3, 2, 1, 1.5, YieldResponse.BETA_WHEN_FLAT };
		assertEquals(surface(c, 1000, 1, 1), surface(c, 1500, 1, 1), "N above 1000 kg adds nothing");
		assertEquals(surface(c, 0, 0.5, 0.7), surface(c, -50, 0.5, 0.7), "negative N is 0");
		assertEquals(surface(c, 200, 1, 0.7), surface(c, 200, 1.4, 0.7), "irrigation level above 1 is 1");
		assertEquals(surface(c, 200, 0, 0.7), surface(c, 200, -0.2, 0.7), "irrigation level below 0 is 0");
		assertEquals(surface(c, 200, 1, 1), surface(c, 200, 1, 1.3), "other intensity above 1 is 1");
		assertEquals(0, surface(c, 200, 1, -0.1), 1e-15, "other intensity below 0 is 0, and so is the yield");
	}

	@Test
	void fullOtherIntensityIsNormalisedToOne() {
		// (1 - e^-3.22) / 0.9600449 is 1 to R's 7 significant figures.
		assertEquals(1, (1 - Math.exp(-YieldResponse.GAMMA)) / YieldResponse.MANAGEMENT_NORMALISER, 1e-7);
	}

	@Test
	void techChangeScalesTheYieldByYearsSinceStart() {
		double[] c = { 2, 3, 2, 1, 1.5, YieldResponse.BETA_WHEN_FLAT };
		double base = surface(c, 200, 1, 1);
		assertEquals(base, YieldResponse.yield(c[0], c[1], c[2], c[3], c[4], c[5], 200, 1, 1, 0.01, 0),
				"no change in the start year");
		assertEquals(base * 1.1, YieldResponse.yield(c[0], c[1], c[2], c[3], c[4], c[5], 200, 1, 1, 0.01, 10), 1e-12);
	}

	@Test
	void alphaZeroSwitchesOffTheNTerms() {
		// With alpha 0 (a flat rainfed N response), N changes nothing, including through D.
		double[] c = { 2, 0, 1, 2, 0, YieldResponse.BETA_WHEN_FLAT };
		assertEquals(surface(c, 0, 1, 1), surface(c, 1000, 1, 1));
	}

	private static double surface(double[] c, double n, double level, double intensity) {
		return YieldResponse.yield(c[0], c[1], c[2], c[3], c[4], c[5], n, level, intensity, 0, 0);
	}
}
