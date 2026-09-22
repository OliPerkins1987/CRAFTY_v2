package de.cesr.crafty.react.science;

import java.util.function.DoubleUnaryOperator;

/**
 * One step of a Prospect AFT's N decision at one pixel: a port of one pass of the loop in
 * {@code prospect_Nuse.R} (the {@code calib_HPC} copy). Phase 3 owns the loop: how many steps, where N
 * starts, and carrying N from year to year.
 *
 * At or above the anchor, the AFT asks whether going up to Nmax would pay; below it, whether going back
 * up to the anchor would. The return per extra kg of N, less its cost, is judged with prospect theory,
 * and N moves part of the way towards the bound in question: the fraction moved is
 * {@code (value × adjustmentScale)²}, and the direction comes from the gain or loss. N stays in
 * {@code [0, Nmax]}.
 *
 * There are two versions:
 * <ul>
 * <li>{@link #asInR}: exactly the calibration rule, kept so the golden test can check the port;</li>
 * <li>{@link #prospect}: the rule the model uses, which differs <b>only when N is at Nmax</b>. There R's
 * rule sees no return and no room to move, so N can never come down again. The fix judges the return on
 * the N above the anchor, and on a loss moves N back towards the anchor (phase 2 plan, Q-C).</li>
 * </ul>
 *
 * Units are real: yields in t/ha, N in kg/ha, price in $/t and the N cost in $/kg N. R works in kg/m²
 * and multiplies the return by 10; that {@code × 10} is dropped here.
 */
public final class ReactiveNitrogenStep {

	/**
	 * The prospect-theory settings, from {@code react_config.yaml}.
	 *
	 * @param alpha           curvature for gains ({@code prospect.alpha})
	 * @param beta            curvature for losses ({@code prospect.beta})
	 * @param lambda          loss aversion ({@code prospect.lambda})
	 * @param adjustmentScale R's {@code N_adj_scale} ({@code n_adjustment_scale})
	 */
	public record Settings(double alpha, double beta, double lambda, double adjustmentScale) {
	}

	private ReactiveNitrogenStep() {
	}

	/**
	 * One step, with the fix for N at Nmax. This is the rule the model uses.
	 *
	 * @param nitrogen  N now, kg/ha
	 * @param anchor    the AFT's {@code Nfert_rate}, kg/ha
	 * @param maximum   Nmax: {@code n_max_factor × Nfert_rate}, kg/ha
	 * @param yieldAt   the pixel's yield (t/ha) at a given N, with irrigation level and other intensity fixed
	 * @param price     the service's price, $/t
	 * @param nCost     the cost of N, $/kg ({@code Nfert} in {@code global_costs.csv})
	 * @param reference the AFT's {@code react_N_par}: the prospect reference point, $/kg N
	 * @return N after the step, kg/ha
	 */
	public static double prospect(double nitrogen, double anchor, double maximum, DoubleUnaryOperator yieldAt,
			double price, double nCost, double reference, Settings settings) {
		return step(nitrogen, anchor, maximum, yieldAt, price, nCost, reference, settings, true);
	}

	/** One step, exactly as the calibration code has it: N at Nmax never comes down. For the golden test. */
	public static double asInR(double nitrogen, double anchor, double maximum, DoubleUnaryOperator yieldAt,
			double price, double nCost, double reference, Settings settings) {
		return step(nitrogen, anchor, maximum, yieldAt, price, nCost, reference, settings, false);
	}

	private static double step(double nitrogen, double anchor, double maximum, DoubleUnaryOperator yieldAt,
			double price, double nCost, double reference, Settings settings, boolean fixAtMaximum) {
		boolean atOrAboveAnchor = nitrogen >= anchor;
		// R tests Ncur == Nmax exactly. That works because N only reaches Nmax through the clamp below,
		// which sets it to Nmax exactly.
		boolean atMaximum = nitrogen == maximum;

		// The return on each extra kg of N, t/ha per kg N.
		double marginal;
		if (atOrAboveAnchor) {
			if (atMaximum) {
				marginal = fixAtMaximum && maximum != anchor
						? (yieldAt.applyAsDouble(maximum) - yieldAt.applyAsDouble(anchor)) / (maximum - anchor)
						: 0;
			} else {
				marginal = (yieldAt.applyAsDouble(maximum) - yieldAt.applyAsDouble(nitrogen)) / (maximum - nitrogen);
			}
		} else {
			marginal = (yieldAt.applyAsDouble(anchor) - yieldAt.applyAsDouble(nitrogen)) / (anchor - nitrogen);
		}

		double value = ProspectTheory.value(marginal * price - nCost, reference, settings.alpha(), settings.beta(),
				settings.lambda());

		// How far N could move, and in which direction.
		double span;
		if (atOrAboveAnchor) {
			if (value >= 0) {
				span = maximum - nitrogen;
			} else {
				span = fixAtMaximum && atMaximum ? anchor - maximum : nitrogen - maximum;
			}
		} else {
			span = value >= 0 ? anchor - nitrogen : nitrogen - anchor;
		}

		double scaled = value * settings.adjustmentScale();
		double next = scaled * scaled * span + nitrogen;
		return next > maximum ? maximum : next < 0 ? 0 : next;
	}
}
