package de.cesr.crafty.react.decisions;

import de.cesr.crafty.react.data.AftReactParameters;

/**
 * One reactive pasture AFT's management in every decision unit, for the year last decided: its husbandry and
 * stocking rate, the production that follows, and what they cost.
 *
 * Arrays are indexed by unit (see {@link DecisionUnits}). {@link PastureDecisions} fills them each year, and
 * only the stocking rate carries over to the next year, as the starting point of the next year's step.
 * Everything else is worked out again each year.
 *
 * The stocking rate and its cost exist only when stocking is reactive. With stocking switched off, the
 * stocking rate is the model's: the AFT's {@code Pasture} production level (phase 4 plan, Q1). The husbandry
 * cost exists only when other intensity is reactive, because that is the only time react writes it
 * (otherwise the model's own cost files are used). Asking for something that was not worked out is an
 * error.
 *
 * The arrays are handed out as they are held: callers must not change them.
 */
public final class PastureManagement {

	/** How close to a bound a stocking rate must be to count as at it, in {@link #summary()}. */
	private static final double AT_BOUND = 1e-9;

	private final AftReactParameters aft;
	private final double stockingMinimum;
	private final double stockingMaximum;

	final double[] husbandry;
	final double[] production;
	/** Null when not worked out; see the class comment. */
	final double[] stocking;
	final double[] stockingCost;
	final double[] intensityCost;

	PastureManagement(AftReactParameters aft, int units, double stockingMinimum, double stockingMaximum,
			boolean withStocking, boolean withIntensityCost) {
		this.aft = aft;
		this.stockingMinimum = stockingMinimum;
		this.stockingMaximum = stockingMaximum;
		husbandry = new double[units];
		production = new double[units];
		stocking = withStocking ? new double[units] : null;
		stockingCost = withStocking ? new double[units] : null;
		intensityCost = withIntensityCost ? new double[units] : null;
	}

	/** The AFT's parameters and baselines. */
	public AftReactParameters aft() {
		return aft;
	}

	public String label() {
		return aft.label();
	}

	/**
	 * Whether react decides the stocking rate, which it does when stocking is reactive. When it does not, the
	 * model's {@code Pasture} production level is the AFT's stocking rate.
	 */
	public boolean decidesStocking() {
		return stocking != null;
	}

	/**
	 * Husbandry, the pasture AFT's other intensity: 0.5–1.75, following its capital, when other intensity is
	 * reactive; {@code Other_intensity} when it is not.
	 */
	public double[] husbandry() {
		return husbandry;
	}

	/**
	 * Production, t/ha, never below 0. Phase 5 writes this as the AFT's {@code _suit} capital. With stocking
	 * reactive it is NPP × husbandry × harvest × (1 − e^(−3.22 × stocking rate)). With stocking not reactive
	 * it leaves the stocking term out, NPP × husbandry × harvest, and the model's {@code Pasture} production
	 * level supplies the stocking.
	 */
	public double[] production() {
		return production;
	}

	/** The stocking rate, between {@code stocking.min} and {@code stocking.max}. Decided only when stocking is reactive. */
	public double[] stocking() {
		return worked(stocking, "the stocking rate is decided only when stocking is reactive; otherwise it is the"
				+ " model's Pasture production level");
	}

	/** The cost of stocking, $/ha: stocking rate × {@code Stocking}. Worked out only when stocking is reactive. */
	public double[] stockingCost() {
		return worked(stockingCost, "stocking costs are worked out only when stocking is reactive");
	}

	/**
	 * The cost of husbandry, $/ha: husbandry × the cost of the AFT's service in {@code global_costs.csv}.
	 * Worked out only when other intensity is reactive.
	 */
	public double[] intensityCost() {
		return worked(intensityCost, "husbandry costs are worked out only when other intensity is reactive");
	}

	private double[] worked(double[] values, String when) {
		if (values == null) {
			throw new IllegalStateException(aft.label() + ": " + when);
		}
		return values;
	}

	/**
	 * One line for the log (phase 4 plan, §4): the stocking rate (mean, min, max, and the share of units at
	 * each bound) when react decides it, the mean husbandry and the mean production. Means are over decision
	 * units, unweighted.
	 */
	public String summary() {
		String husbandryAndProduction = String.format("husbandry mean %.2f; production mean %.2f t/ha",
				mean(husbandry), mean(production));
		if (stocking == null) {
			return String.format("%s: stocking not reactive (the model's Pasture production level); %s, before"
					+ " stocking", aft.label(), husbandryAndProduction);
		}
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		int atMinimum = 0;
		int atMaximum = 0;
		for (double s : stocking) {
			min = Math.min(min, s);
			max = Math.max(max, s);
			if (Math.abs(s - stockingMinimum) < AT_BOUND) {
				atMinimum++;
			}
			if (Math.abs(s - stockingMaximum) < AT_BOUND) {
				atMaximum++;
			}
		}
		return String.format("%s: stocking mean %.2f, min %.2f, max %.2f, %.0f%% of units at %.2f and %.0f%% at %.2f;"
				+ " %s", aft.label(), mean(stocking), min, max, percent(atMinimum), stockingMinimum, percent(atMaximum),
				stockingMaximum, husbandryAndProduction);
	}

	private static double mean(double[] values) {
		double sum = 0;
		for (double value : values) {
			sum += value;
		}
		return sum / values.length;
	}

	private double percent(int count) {
		return 100.0 * count / husbandry.length;
	}
}
