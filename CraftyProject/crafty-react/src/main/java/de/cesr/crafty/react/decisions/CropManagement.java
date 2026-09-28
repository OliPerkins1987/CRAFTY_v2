package de.cesr.crafty.react.decisions;

import de.cesr.crafty.react.data.AftReactParameters;

/**
 * One reactive crops AFT's management in every decision unit, for the year last decided: its N, other
 * intensity, water applied and irrigation level, the yield that follows, and what they cost.
 *
 * Arrays are indexed by unit (see {@link DecisionUnits}). {@link CropDecisions} fills them each year, and
 * only N carries over to the next year, as the starting point of the next year's step. Everything else is
 * worked out again each year.
 *
 * A cost is only worked out when its element is switched on, because that is the only time react writes
 * it (otherwise the model's own cost files are used), and the only time the base cost it needs is
 * guaranteed to be in {@code global_costs.csv}. Asking for a cost that was not worked out is an error.
 *
 * The arrays are handed out as they are held: callers must not change them.
 */
public final class CropManagement {

	private final AftReactParameters aft;
	private final double nitrogenMaximum;

	final double[] nitrogen;
	final double[] otherIntensity;
	final double[] waterApplied;
	final double[] irrigationLevel;
	final double[] yield;
	/** Null when not worked out; see the class comment. */
	final double[] nitrogenCost;
	final double[] irrigationCost;
	final double[] intensityCost;

	CropManagement(AftReactParameters aft, double nitrogenMaximum, int units, boolean withNitrogenCost,
			boolean withIrrigationCost, boolean withIntensityCost) {
		this.aft = aft;
		this.nitrogenMaximum = nitrogenMaximum;
		nitrogen = new double[units];
		otherIntensity = new double[units];
		waterApplied = new double[units];
		irrigationLevel = new double[units];
		yield = new double[units];
		nitrogenCost = withNitrogenCost ? new double[units] : null;
		irrigationCost = withIrrigationCost ? new double[units] : null;
		intensityCost = withIntensityCost ? new double[units] : null;
	}

	/** The AFT's parameters and baselines. */
	public AftReactParameters aft() {
		return aft;
	}

	public String label() {
		return aft.label();
	}

	/** Nmax, kg/ha: {@code n_max_factor × Nfert_rate}. */
	public double nitrogenMaximum() {
		return nitrogenMaximum;
	}

	/** N, kg/ha, between 0 and Nmax. */
	public double[] nitrogen() {
		return nitrogen;
	}

	/** Other intensity, 0–1: the management term of the yield surface. */
	public double[] otherIntensity() {
		return otherIntensity;
	}

	/** Water applied, m³/ha. 0 for an AFT that does not irrigate. */
	public double[] waterApplied() {
		return waterApplied;
	}

	/** The irrigation level in the yield surface: water applied ÷ water required, 0–1. 0 for a rainfed AFT. */
	public double[] irrigationLevel() {
		return irrigationLevel;
	}

	/** The yield of the AFT's service, t/ha. Phase 5 writes this as the AFT's {@code _suit} capital. */
	public double[] yield() {
		return yield;
	}

	/** The cost of N, $/ha: N × {@code Nfert}. Worked out only when fertiliser is reactive. */
	public double[] nitrogenCost() {
		return worked(nitrogenCost, "N costs are worked out only when fertiliser is reactive");
	}

	/**
	 * The cost of water, $/ha: water applied × the pixel's irrigation cost index × {@code Water}. Worked out
	 * only for an AFT that irrigates, when irrigation is reactive.
	 */
	public double[] irrigationCost() {
		return worked(irrigationCost, "irrigation costs are worked out only for an AFT that irrigates, when"
				+ " irrigation is reactive");
	}

	/**
	 * The cost of other intensity, $/ha: other intensity × the cost of the AFT's service in
	 * {@code global_costs.csv}. Worked out only when other intensity is reactive.
	 */
	public double[] intensityCost() {
		return worked(intensityCost, "other-intensity costs are worked out only when other intensity is reactive");
	}

	/** Whether N costs were worked out: fertiliser is reactive. */
	public boolean hasNitrogenCost() {
		return nitrogenCost != null;
	}

	/** Whether water costs were worked out: the AFT irrigates and irrigation is reactive. */
	public boolean hasIrrigationCost() {
		return irrigationCost != null;
	}

	/** Whether other-intensity costs were worked out: other intensity is reactive. */
	public boolean hasIntensityCost() {
		return intensityCost != null;
	}

	private double[] worked(double[] cost, String when) {
		if (cost == null) {
			throw new IllegalStateException(aft.label() + ": " + when);
		}
		return cost;
	}

	/**
	 * One line for the log (phase 3 plan, Q5): N (mean, min, max, and the share of units at 0 and at
	 * Nmax), the mean yield and the mean irrigation level. Means are over decision units, unweighted.
	 */
	public String summary() {
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		int atZero = 0;
		int atMaximum = 0;
		for (double n : nitrogen) {
			min = Math.min(min, n);
			max = Math.max(max, n);
			if (n == 0) {
				atZero++;
			}
			if (n == nitrogenMaximum) {
				atMaximum++;
			}
		}
		String water = aft.isIrrigated() ? String.format("irrigation level mean %.2f", mean(irrigationLevel))
				: "rainfed";
		return String.format("%s: N mean %.1f, min %.1f, max %.1f kg/ha (Nmax %.1f), %.0f%% of units at 0 and %.0f%%"
				+ " at Nmax; yield mean %.2f t/ha; %s", aft.label(), mean(nitrogen), min, max, nitrogenMaximum,
				percent(atZero), percent(atMaximum), mean(yield), water);
	}

	private static double mean(double[] values) {
		double sum = 0;
		for (double value : values) {
			sum += value;
		}
		return sum / values.length;
	}

	private double percent(int count) {
		return 100.0 * count / nitrogen.length;
	}
}
