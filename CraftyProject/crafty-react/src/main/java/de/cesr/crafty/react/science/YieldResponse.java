package de.cesr.crafty.react.science;

/**
 * The crop yield surface: how a pixel's yield responds to nitrogen, irrigation and other intensity.
 *
 * A port of PLUM functionality for building 3-d yield plains. LPJ-GUESS gives each
 * crop's yield at a few management levels (0, 200 and 1000 kg N/ha, rainfed and irrigated). Six
 * coefficients are fitted from those per pixel, and {@link #yield} then gives the yield at any N,
 * irrigation level and other intensity in between.
 *
 * Everything here is a pure function: numbers in, numbers out. Yields go in and come out in the same
 * units (t/ha in react), since the surface is linear in A, B, C and D.
 *
 * Two changes from the R, both settled in the phase 2 plan:
 * <ul>
 * <li>where both differences in a shape fit are exactly 0 (0/0), {@link #alpha} gives 0 and {@link #beta}
 * gives {@link #BETA_WHEN_FLAT}, instead of R's 57.56 and 27.63;</li>
 * <li>{@link #yield} clamps fertiliser ({@code N/1000}), irrigation level and other intensity to 0–1.</li>
 * </ul>
 */
public final class YieldResponse {

	/** The other-intensity (management) response's shape, as in R. */
	public static final double GAMMA = 3.22;

	/** R's {@code 1 / 0.9600449}, i.e. {@code 1 / (1 − e^(−3.22))}: makes the management term 1 at intensity 1. */
	static final double MANAGEMENT_NORMALISER = 0.9600449;

	/** N is given in kg/ha; the surface works in thousands of kg (R's {@code fert = N / 1000}). */
	static final double N_SCALE = 1000;

	/** {@code alpha_par}'s {@code scalar}: 1000 / 200, since the middle N level is 200 kg. */
	static final double ALPHA_SCALAR = 1000.0 / 200.0;

	/** {@code beta_par}'s {@code adjust}: its made-up middle point, 80% of the way from rainfed to irrigated. */
	static final double BETA_ADJUST = 0.8;

	/** {@code beta_par}'s {@code scalar}: the made-up middle point is at half the water. */
	static final double BETA_SCALAR = 2;

	/**
	 * β where rainfed and irrigated yields are equal at 0 N: the value every other pixel gets,
	 * {@code −ln(1 − 0.8) × 2 ≈ 3.2189}.
	 */
	public static final double BETA_WHEN_FLAT = -Math.log(1 - BETA_ADJUST) * BETA_SCALAR;

	private YieldResponse() {
	}

	// ---- coefficients ----

	/** Yield with no N and no irrigation: {@code A_par}. */
	public static double a(double y0) {
		return y0;
	}

	/** The most N can add, rainfed: {@code B_par}, {@code max(0, y1000 − y0)}. */
	public static double b(double y0, double y1000) {
		double gain = y1000 - y0;
		return gain < 0 ? 0 : gain;
	}

	/** The most irrigation can add at 0 N: {@code C_par}, {@code max(0, yi0 − y0)}. */
	public static double c(double y0, double yi0) {
		double gain = yi0 - y0;
		return gain < 0 ? 0 : gain;
	}

	/** The N × irrigation interaction: {@code D_par}, {@code yi1000 + y0 − y1000 − yi0}. May be negative. */
	public static double d(double y0, double y1000, double yi0, double yi1000) {
		return yi1000 + y0 - y1000 - yi0;
	}

	/**
	 * The shape of the N response: {@code alpha_par}. Fitted from how far the 200 kg N yield is along the
	 * way from the 0 N to the 1000 kg N yield.
	 *
	 * The clamps are R's, including the odd pair (a ratio above 0.99999999 is set to 0.9999999, so a
	 * ratio between the two passes through). A non-zero difference over a zero one gives ±∞ as in R,
	 * which the clamps turn into 0 or 0.9999999.
	 *
	 * @return 0 where both differences are exactly 0 (plan, Q-E): the N response is flat, so N does
	 *         nothing there. This also switches off the D term in {@link #yield}.
	 */
	public static double alpha(double y0, double y0200, double y1000) {
		double middle = y0200 - y0;
		double full = y1000 - y0;
		if (middle == 0 && full == 0) {
			return 0;
		}
		double ratio = middle / full;
		if (Double.isNaN(ratio)) {
			ratio = 0.99999;
		}
		if (ratio < 0) {
			ratio = 0;
		} else if (ratio > 0.99999999) {
			ratio = 0.9999999;
		}
		return -Math.log(1 - ratio) * ALPHA_SCALAR;
	}

	/**
	 * The shape of the irrigation response: {@code beta_par}.
	 *
	 * LPJ-GUESS irrigation is either on or off, so there is no middle level to fit from. {@code beta_par}
	 * makes one up, 80% of the way from the rainfed to the irrigated yield at half the water, so the ratio
	 * is always 0.8 and β is about 3.2189 in every pixel. It is ported as a fit all the same, so it keeps
	 * its link to the R.
	 *
	 * @return {@link #BETA_WHEN_FLAT} where the rainfed and irrigated yields at 0 N are exactly equal
	 *         (plan, Q-E)
	 */
	public static double beta(double y0, double yi0) {
		if (yi0 - y0 == 0) {
			return BETA_WHEN_FLAT;
		}
		double middle = y0 + (yi0 - y0) * BETA_ADJUST;
		double ratio = (middle - y0) / (yi0 - y0);
		if (Double.isNaN(ratio)) {
			ratio = 0.999999;
		}
		if (ratio < 0) {
			ratio = 0;
		} else if (ratio > 0.999999) {
			ratio = 0.99999;
		}
		return -Math.log(1 - ratio) * BETA_SCALAR;
	}

	// ---- the surface ----

	/**
	 * The yield at one pixel: {@code yield_response(..., mgmt_func = "PLUM")}.
	 *
	 * <pre>
	 * f = clamp(N / 1000, 0, 1),  i = clamp(irrigationLevel, 0, 1),  m = clamp(otherIntensity, 0, 1)
	 * yield = (A + B(1 − e^(−αf)) + C(1 − e^(−βi)) + D(1 − e^(−αf))(1 − e^(−βi)))
	 *         × (1 − e^(−γm)) × (1 + techChange × yearsSinceStart) / 0.9600449
	 * </pre>
	 *
	 * @param nitrogen        N applied, kg/ha
	 * @param irrigationLevel water applied ÷ water required, 0–1 (see {@link Irrigation#level})
	 * @param otherIntensity  other (management) intensity, 0–1
	 * @param techChange      yield change per year from technology: {@code yield_tech_change} in
	 *                        {@code react_config.yaml}
	 * @param yearsSinceStart years since the run's start year, so 0 in the first year
	 */
	public static double yield(double a, double b, double c, double d, double alpha, double beta, double nitrogen,
			double irrigationLevel, double otherIntensity, double techChange, double yearsSinceStart) {
		double f = clamp01(nitrogen / N_SCALE);
		double i = clamp01(irrigationLevel);
		double m = clamp01(otherIntensity);
		double nResponse = 1 - Math.exp(-alpha * f);
		double irrigationResponse = 1 - Math.exp(-beta * i);
		return (a + (b * nResponse + c * irrigationResponse + d * nResponse * irrigationResponse))
				* (1 - Math.exp(-GAMMA * m)) * (1 + (techChange * yearsSinceStart)) * (1 / MANAGEMENT_NORMALISER);
	}

	static double clamp01(double x) {
		return x < 0 ? 0 : x > 1 ? 1 : x;
	}
}
