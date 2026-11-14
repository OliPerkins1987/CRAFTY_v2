package de.cesr.crafty.landsymm;

/**
 * The trade-capital rule: capital = 1 / (1 + exp(-slope x (ratio - midpoint))), where ratio is the region's
 * self-sufficiency in the capital's crop, PLUM's production / domestic use (from Trade_weights_v3.R). With the
 * defaults (slope 6, midpoint 1), self-sufficiency gives 0.5, producing twice the domestic use 0.998, and half of it
 * 0.047. A region that exports everything it produces gets 1; one that produces none gets 1 / (1 + exp(slope x
 * midpoint)).
 *
 * The slope and midpoint are config keys (trade_capital_slope, trade_capital_midpoint) so the rule can be
 * calibrated without a rebuild. It ignores last year's value.
 */
final class SelfSufficiencyLogisticRule implements TradeCapitalRule {

	private final double slope;
	private final double midpoint;

	SelfSufficiencyLogisticRule(double slope, double midpoint) {
		this.slope = slope;
		this.midpoint = midpoint;
	}

	@Override
	public double next(Inputs in) {
		return value(in.selfSufficiency());
	}

	/** The capital for a self-sufficiency ratio. */
	double value(double ratio) {
		if (ratio == Double.POSITIVE_INFINITY) {
			return 1.0;
		}
		return 1.0 / (1.0 + Math.exp(-slope * (ratio - midpoint)));
	}

	double slope() {
		return slope;
	}

	double midpoint() {
		return midpoint;
	}
}
