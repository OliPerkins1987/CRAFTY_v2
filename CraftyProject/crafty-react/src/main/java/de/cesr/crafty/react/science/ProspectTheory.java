package de.cesr.crafty.react.science;

/**
 * The prospect-theory value function: how much a gain or loss is felt to be worth. A port of
 * {@code prospect_value} in {@code AFT_funcs.R}.
 *
 * Gains are measured from a reference point. Above it the value grows more slowly than the gain (risk
 * averse); below it losses are felt more strongly than gains of the same size (loss averse, by
 * {@code lambda}).
 */
public final class ProspectTheory {

	private ProspectTheory() {
	}

	/**
	 * <pre>
	 * d = x − reference;   d ≥ 0 → d^alpha;   d &lt; 0 → −lambda × (−d)^beta
	 * </pre>
	 *
	 * @param x         the outcome, e.g. the return on one more kg of N less its cost, $/kg N
	 * @param reference the reference point: a Prospect AFT's {@code react_N_par}, in the same units as x
	 * @param alpha     curvature for gains ({@code prospect.alpha}, 0.88)
	 * @param beta      curvature for losses ({@code prospect.beta}, 0.88)
	 * @param lambda    loss aversion ({@code prospect.lambda}, 2.25)
	 */
	public static double value(double x, double reference, double alpha, double beta, double lambda) {
		double delta = x - reference;
		return delta >= 0 ? Math.pow(delta, alpha) : -lambda * Math.pow(-delta, beta);
	}
}
