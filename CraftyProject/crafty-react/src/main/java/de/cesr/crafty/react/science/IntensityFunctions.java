package de.cesr.crafty.react.science;

/**
 * The management levels that follow a capital (normalised GDP) rather than price.
 *
 * Ports of {@code Eff_func} and {@code animal.husbandry} in {@code Yield_plains.R}, and of the
 * {@code "GDP"} branch of {@code cropland_nitrogen} in {@code agents_cropland.R}.
 */
public final class IntensityFunctions {

	/** Husbandry with no capital. */
	static final double HUSBANDRY_MIN = 0.5;

	/** Husbandry at full capital. */
	static final double HUSBANDRY_MAX = 1.75;

	private IntensityFunctions() {
	}

	/**
	 * A crops AFT's other intensity: {@code Eff_func}. Flat at the floor up to the threshold, then a
	 * straight line up to 1 at a capital of 1, and 1 above that.
	 *
	 * @param capital   the AFT's {@code react_O_capital} at this pixel
	 * @param floor     the AFT's {@code Other_intensity} from core, 0–1 (R's {@code y1})
	 * @param threshold the AFT's {@code react_O_par}, at least 0 and below 1 (R's {@code z1})
	 * @return a level between the floor and 1
	 */
	public static double effFunc(double capital, double floor, double threshold) {
		if (capital <= threshold) {
			return floor;
		}
		if (capital <= 1) {
			return floor + ((capital - threshold) * ((1 - floor) / (1 - threshold)));
		}
		return 1;
	}

	/**
	 * A pasture AFT's husbandry: {@code animal.husbandry}, from 0.5 with no capital to 1.75 at full capital,
	 * rising with the square root. R rescales {@code a + b√x} to that range, which cancels to the form here
	 * (R's own comment says so).
	 *
	 * @param x the capital times the AFT's {@code react_O_par}. Clamped to 0–1, so husbandry stays within
	 *          0.5–1.75 (R's check on this is commented out).
	 */
	public static double animalHusbandry(double x) {
		return HUSBANDRY_MIN + Math.sqrt(YieldResponse.clamp01(x)) * (HUSBANDRY_MAX - HUSBANDRY_MIN);
	}

	/**
	 * A Capital AFT's N: its minimum with no capital, rising in a straight line with the capital to its
	 * maximum, and held there: {@code minimum + (maximum − minimum) × min(1, capital × nPar)}, also kept from
	 * going below the minimum. N reaches the maximum where {@code capital × nPar = 1}, whatever the minimum.
	 * With a minimum of 0 this is R's {@code pmin(N_base, N_base × GDP × N_sense)}.
	 *
	 * @param minimum the AFT's {@code react_N_min}, kg N/ha (0 when blank), at most the maximum
	 * @param maximum the AFT's {@code Nfert_rate} from core, kg N/ha
	 * @param capital the AFT's {@code react_N_capital} at this pixel
	 * @param nPar    the AFT's {@code react_N_par}: the capital multiplier
	 * @return N, kg/ha, between the minimum and the maximum
	 */
	public static double capitalNitrogen(double minimum, double maximum, double capital, double nPar) {
		return minimum + (maximum - minimum) * YieldResponse.clamp01(capital * nPar);
	}
}
