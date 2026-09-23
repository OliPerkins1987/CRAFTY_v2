package de.cesr.crafty.react.science;

/**
 * Irrigation: how much water a crop needs at a given N, how much it gets, the irrigation level that goes
 * into the yield surface, and what the water costs.
 *
 * There is no R original. The rules were settled in the phase 1 and phase 2 plans:
 * <ul>
 * <li>water needed rises with N along the same saturating curve as the yield N response, fitted from the
 * demand at 0, 200 and 1000 kg N with {@link YieldResponse#alpha};</li>
 * <li>water applied is what is needed, divided by the AFT's efficiency, but no more than the runoff;</li>
 * <li>the irrigation level is water applied ÷ water required, and 1 when nothing is required;</li>
 * <li>the cost is water applied × the pixel's irrigation cost index × the base water price.</li>
 * </ul>
 * Water is in m³/ha throughout.
 */
public final class Irrigation {

	private Irrigation() {
	}

	/** The shape of the demand's response to N: the same fit, and the same clamps, as the yield's alpha. */
	public static double waterDemandAlpha(double d0, double d0200, double d1000) {
		return YieldResponse.alpha(d0, d0200, d1000);
	}

	/**
	 * Water the crop needs, m³/ha: {@code D0 + (D1000 − D0)(1 − e^(−alphaD × clamp(N/1000, 0, 1)))}.
	 *
	 * @param nitrogen N applied, kg/ha. Which N is phase 3's choice: the AFT's current N when irrigation
	 *                 reacts, its {@code Nfert_rate} when it does not.
	 */
	public static double waterDemand(double d0, double d1000, double alphaD, double nitrogen) {
		return d0 + (d1000 - d0) * (1 - Math.exp(-alphaD * YieldResponse.clamp01(nitrogen / YieldResponse.N_SCALE)));
	}

	/**
	 * Water the AFT would draw to meet the demand, m³/ha: {@code waterDemand / efficiency}.
	 *
	 * @param efficiency the AFT's {@code react_I_eff} when irrigation reacts, 1 when it does not
	 */
	public static double required(double waterDemand, double efficiency) {
		return waterDemand / efficiency;
	}

	/** Water actually applied, m³/ha: what is required, but no more than the runoff. */
	public static double applied(double required, double runoff) {
		return Math.max(0, Math.min(required, runoff));
	}

	/** The irrigation level for the yield surface: applied ÷ required, 0–1. 1 when nothing is required. */
	public static double level(double applied, double required) {
		return required <= 0 ? 1 : YieldResponse.clamp01(applied / required);
	}

	/**
	 * What the water costs, $/ha.
	 *
	 * @param costIndex  the pixel's {@code irrigation_cost}, an aridity index 0–1
	 * @param waterPrice {@code Water} in {@code global_costs.csv}, $/m³
	 */
	public static double cost(double applied, double costIndex, double waterPrice) {
		return applied * costIndex * waterPrice;
	}
}
