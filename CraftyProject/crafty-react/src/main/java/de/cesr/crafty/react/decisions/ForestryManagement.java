package de.cesr.crafty.react.decisions;

import de.cesr.crafty.react.data.AftReactParameters;

/**
 * One reactive forestry AFT's management in every decision unit, for the year last decided: its rotation, and
 * the yield and cost that follow.
 *
 * Arrays are indexed by unit (see {@link DecisionUnits}). {@link ForestryDecisions} fills them each year. Only a
 * Prospect AFT's rotation carries over to the next year, as the starting point of the next year's step. A
 * Capital AFT's rotation, and everything else, is worked out again each year.
 *
 * No profit is kept. A Prospect AFT's step weighs one only to choose the rotation; the model works out its own
 * from the yield and cost F5 hands over.
 *
 * The arrays are handed out as they are held: callers must not change them.
 */
public final class ForestryManagement {

	private final AftReactParameters aft;
	private final int shortest;
	private final int longest;

	final int[] rotation;
	final double[] yield;
	final double[] cost;

	ForestryManagement(AftReactParameters aft, int units, int shortest, int longest) {
		this.aft = aft;
		this.shortest = shortest;
		this.longest = longest;
		rotation = new int[units];
		yield = new double[units];
		cost = new double[units];
	}

	/** The AFT's parameters and baselines. */
	public AftReactParameters aft() {
		return aft;
	}

	public String label() {
		return aft.label();
	}

	/** The rotation, years: one of {@code forestry.rotations}. */
	public int[] rotation() {
		return rotation;
	}

	/**
	 * The yield at the rotation, m³/ha/yr, never below 0 (32d plan, Q1): a negative value in the forestry files
	 * is held at 0. F5 hands this over as the AFT's {@code _suit} capital.
	 */
	public double[] yield() {
		return yield;
	}

	/**
	 * The rotation cost, $/ha/yr: the cost of one harvest (the service's row in {@code global_costs.csv}) spread
	 * over the rotation. F5 hands this over as the AFT's intensity cost.
	 */
	public double[] cost() {
		return cost;
	}

	/**
	 * One line for the log (forestry plan §5): the rotation (mean, min, max, and the share of units at each end
	 * of the grid), the mean yield and the mean cost. Means are over decision units, unweighted.
	 */
	public String summary() {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		double sum = 0;
		int atShortest = 0;
		int atLongest = 0;
		for (int h : rotation) {
			min = Math.min(min, h);
			max = Math.max(max, h);
			sum += h;
			if (h == shortest) {
				atShortest++;
			}
			if (h == longest) {
				atLongest++;
			}
		}
		String mode = aft.usesCapitalForRotation() ? "Capital, " + aft.rCapital() : "Prospect";
		return String.format("%s (%s): rotation mean %.1f years, min %d, max %d, %.0f%% of units at %d and %.0f%% at %d;"
				+ " yield mean %.2f m3/ha/yr; cost mean %.2f $/ha/yr", aft.label(), mode, sum / rotation.length, min, max,
				percent(atShortest), shortest, percent(atLongest), longest, mean(yield), mean(cost));
	}

	private static double mean(double[] values) {
		double sum = 0;
		for (double value : values) {
			sum += value;
		}
		return sum / values.length;
	}

	private double percent(int count) {
		return 100.0 * count / rotation.length;
	}
}
