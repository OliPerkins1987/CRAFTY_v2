package de.cesr.crafty.react.science;

/**
 * Pasture production, profit and one step of a pasture AFT's stocking decision at one pixel.
 *
 * Ports of the pasture yield in {@code agents_pasture.R} and of one pass of the loop in
 * {@code prospect_stocking_rate.R} (the {@code calib_HPC} copy). Phase 4 owns the loop: how many steps,
 * where the stocking rate starts, and carrying it from year to year.
 *
 * <b>Production is also the pasture AFT's suitability</b> ({@code <AFT>_suit}). Core's production level is
 * one number per AFT and cannot vary by pixel, so husbandry, harvest and the stocking rate all sit in it:
 * <pre>
 * production = NPP × husbandry × harvest × (1 − e^(−γ × stocking))
 * </pre>
 * {@code harvest} is the most of the NPP that can be taken off; the stocking rate sets how much of that is
 * actually taken, with diminishing returns, so profit has a best stocking rate inside the range.
 *
 * Units are real: NPP and production in t/ha, price in $/t, costs in $/ha per unit. R works in kg/m² and
 * multiplies NPP by 10; that {@code × 10} is dropped here.
 */
public final class ReactivePastureStep {

	/**
	 * R's absolute hurdle for stocking up: the gain must be more than this, $/ha, as well as beating the
	 * threshold.
	 */
	static final double STOCKING_UP_HURDLE = 1;

	/**
	 * The stocking settings, from {@code react_config.yaml}.
	 *
	 * @param harvest the most of the NPP that can be taken off, 0–1 ({@code stocking.harvest})
	 * @param step    how far one step moves the stocking rate ({@code stocking.step})
	 * @param minimum the lowest stocking rate ({@code stocking.min})
	 * @param maximum the highest stocking rate ({@code stocking.max})
	 */
	public record Settings(double harvest, double step, double minimum, double maximum) {
	}

	/**
	 * One pixel's pasture economics for one AFT, which don't change during its stocking steps.
	 *
	 * @param npp           pasture NPP, t/ha (may be negative)
	 * @param husbandry     the AFT's husbandry, 0.5–1.75 ({@link IntensityFunctions#animalHusbandry})
	 * @param price         the service's price, $/t
	 * @param stockingCost  cost per unit of stocking rate, $/ha ({@code Stocking} in {@code global_costs.csv})
	 * @param husbandryCost cost per unit of husbandry, $/ha
	 */
	public record Economics(double npp, double husbandry, double price, double stockingCost, double husbandryCost) {
	}

	private ReactivePastureStep() {
	}

	/** Production at a stocking rate, t/ha. This is the value written as the pasture AFT's suitability. */
	public static double production(double npp, double husbandry, double harvest, double stocking) {
		return npp * husbandry * harvest * (1 - Math.exp(-YieldResponse.GAMMA * stocking));
	}

	/** Profit at a stocking rate, $/ha: production × price − stocking × its cost − husbandry × its cost. */
	public static double profit(Economics pixel, double harvest, double stocking) {
		return (production(pixel.npp(), pixel.husbandry(), harvest, stocking) * pixel.price())
				- (stocking * pixel.stockingCost()) - pixel.husbandryCost() * pixel.husbandry();
	}

	/**
	 * One stocking step, as in {@code prospect_stocking}. The AFT compares one step up and one step down
	 * (each kept within the bounds) with staying put:
	 * <ul>
	 * <li>it steps up if that is the better of the two moves (up wins a tie), its profit beats today's by the
	 * threshold, {@code profit(up) > profit(now) × (1 + threshold)}, and the gain is more than $1/ha;</li>
	 * <li>it steps down if that is strictly the better move and its profit is higher than today's;</li>
	 * <li>otherwise it stays.</li>
	 * </ul>
	 *
	 * @param stocking  the stocking rate now
	 * @param threshold the AFT's {@code react_S_par}
	 * @return the stocking rate after the step
	 */
	public static double stockingStep(double stocking, Economics pixel, double threshold, Settings settings) {
		double up = Math.min(settings.maximum(), stocking + settings.step());
		double down = Math.max(settings.minimum(), stocking - settings.step());
		double profitUp = profit(pixel, settings.harvest(), up);
		double profitNow = profit(pixel, settings.harvest(), stocking);
		double profitDown = profit(pixel, settings.harvest(), down);

		// R: which.max(c(profitUp - profitNow, profitDown - profitNow)), which picks up on a tie.
		boolean upIsBetter = profitUp - profitNow >= profitDown - profitNow;
		if (profitUp > profitNow * (1 + threshold) && (profitUp - profitNow) > STOCKING_UP_HURDLE && upIsBetter) {
			return up;
		}
		if (profitDown > profitNow && !upIsBetter) {
			return down;
		}
		return stocking;
	}
}
