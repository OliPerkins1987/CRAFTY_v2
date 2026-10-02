package de.cesr.crafty.react.science;

import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * Profit, rotation cost and the rotation decisions of a forestry AFT at one place: one step of a Prospect
 * AFT, and a Capital AFT's rotation. The forestry decisions own the loop: how many steps, where the rotation
 * starts, and carrying it from year to year.
 *
 * <b>The rotation</b> is the stand's age at harvest, H years, on the grid {@code forestry.rotations} (default
 * 10, 20, …, 100). LPJ-GUESS yields are processed to give one yield per rotation: 
 * the carbon at harvest spread over the rotation, as
 * an evenly aged forest with 1/H of it cut each year. So the yield is already per year and is not divided by
 * H again. A forestry AFT's intensity is its harvest rate, 1/H, so its intensity cost is the harvest rate ×
 * the cost of one harvest:
 * <pre>
 * cost(H)   = C / H                          $/ha/yr
 * profit(H) = yield(H) × price − C / H       $/ha/yr
 * </pre>
 *
 * <b>Prospect</b> AFTs follow profit with pasture's stocking step on the grid, "up" (the more intensive move)
 * being one step shorter, a more frequent harvest: see {@link #prospectStep}. <b>Capital</b> AFTs take their
 * rotation from a capital, afresh each year: see {@link #capitalRotation}.
 *
 * Units are real: yields in t/ha/yr, price in $/t, the cost of one harvest in $/ha. R's
 * {@code Evaluate_AFT_Forestry.R} multiplies the kg/m² yields by 10 when it prices them; here that × 10 is
 * done when the files are read. The R version of these rules is {@code forestry_rules.R}, kept with the
 * golden scripts in {@code CRAFTY_PLUM/CRAFTY_dev/debugging}.
 */
public final class ReactiveRotationStep {

	/**
	 * One place's forestry economics for one AFT, which don't change during its steps.
	 *
	 * @param yieldAt     the yield at a rotation (years, on the grid), t/ha/yr
	 * @param price       the service's price, $/t
	 * @param harvestCost the cost of one harvest, $/ha (the service's row in {@code global_costs.csv})
	 */
	public record Economics(IntToDoubleFunction yieldAt, double price, double harvestCost) {
	}

	private ReactiveRotationStep() {
	}

	/**
	 * The rotation cost, $/ha/yr: the cost of one harvest spread over the rotation. This is the forestry AFT's
	 * intensity cost.
	 */
	public static double rotationCost(double harvestCost, int rotation) {
		return harvestCost / rotation;
	}

	/** Profit at a rotation, $/ha/yr: yield × price − the rotation cost. */
	public static double profit(Economics place, int rotation) {
		return place.yieldAt().applyAsDouble(rotation) * place.price() - rotationCost(place.harvestCost(), rotation);
	}

	/**
	 * One step of a Prospect AFT's rotation: {@link ReactivePastureStep#stockingStep} on the grid, with one
	 * place shorter for "up" and one place longer for "down". At an end of the grid the missing neighbour is
	 * today's rotation. The AFT compares the two moves with staying put:
	 * <ul>
	 * <li>it shortens if that is the better of the two moves (shorter wins a tie), its profit beats today's by
	 * the threshold, {@code profit(shorter) > profit(now) × (1 + threshold)}, and the gain is more than
	 * $1/ha;</li>
	 * <li>it lengthens if that is strictly the better move and its profit is higher than today's;</li>
	 * <li>otherwise it stays.</li>
	 * </ul>
	 * So a Prospect AFT is quicker to lengthen its rotation than to shorten it, as a pasture AFT is quicker to
	 * destock than to stock up. When it is losing money, today's profit × (1 + threshold) is lower still, so
	 * only the $1/ha holds a shortening back. With no forest (every yield 0) profit is −C/H, which rises with
	 * H, so the rotation lengthens to the longest.
	 *
	 * @param rotation  the rotation now, years; one of {@code rotations}
	 * @param threshold the AFT's {@code react_R_par} (0 or more)
	 * @param rotations the grid, {@code forestry.rotations}, increasing; a step moves one place along it
	 * @return the rotation after the step, years
	 * @throws IllegalArgumentException if {@code rotation} is not on the grid
	 */
	public static int prospectStep(int rotation, Economics place, double threshold, List<Integer> rotations) {
		int now = rotations.indexOf(rotation);
		if (now < 0) {
			throw new IllegalArgumentException("A rotation of " + rotation + " years is not on the grid " + rotations);
		}
		int shorter = rotations.get(Math.max(now - 1, 0));
		int longer = rotations.get(Math.min(now + 1, rotations.size() - 1));
		double profitShorter = profit(place, shorter);
		double profitNow = profit(place, rotation);
		double profitLonger = profit(place, longer);

		// As pasture's step and R: which.max(c(gain shorter, gain longer)), which picks shorter on a tie.
		boolean shorterIsBetter = profitShorter - profitNow >= profitLonger - profitNow;
		if (profitShorter > profitNow * (1 + threshold)
				&& (profitShorter - profitNow) > ReactivePastureStep.STOCKING_UP_HURDLE && shorterIsBetter) {
			return shorter;
		}
		if (profitLonger > profitNow && !shorterIsBetter) {
			return longer;
		}
		return rotation;
	}

	/**
	 * A Capital AFT's rotation, from its capital: the grid rotation nearest to
	 * <pre>
	 * baseline − 1000 × sensitivity × capital      years
	 * </pre>
	 * {@code sensitivity × 1000} is the years taken off the rotation per unit of capital (0.01 → 10 years);
	 * 0 keeps the baseline, and a negative sensitivity lengthens the rotation as the capital rises. Beyond
	 * either end of the grid it gives that end. Exactly halfway between two rotations it gives the shorter, as
	 * a tie in {@link #prospectStep} does.
	 *
	 * This is the arithmetic of R's Population rule (grid column 11 − 100 × rate, i.e. 110 − 1000 × rate
	 * years) on the AFT's own baseline.
	 *
	 * @param baseline    the AFT's initial rotation, years ({@code Other_intensity})
	 * @param sensitivity the AFT's {@code react_R_par}
	 * @param capital     the capital named in {@code react_R_capital}, at this place
	 * @param rotations   the grid, {@code forestry.rotations}, increasing
	 * @return the rotation, years
	 */
	public static int capitalRotation(double baseline, double sensitivity, double capital, List<Integer> rotations) {
		double target = baseline - 1000 * sensitivity * capital;
		int nearest = rotations.get(0);
		for (int rotation : rotations) {
			// Only a strictly nearer rotation replaces the one found, so a tie keeps the shorter.
			if (Math.abs(rotation - target) < Math.abs(nearest - target)) {
				nearest = rotation;
			}
		}
		return nearest;
	}
}
