package de.cesr.crafty.react.decisions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.react.data.AftReactParameters;
import de.cesr.crafty.react.data.LpjgType;
import de.cesr.crafty.react.data.ReactBaseCosts;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.science.ReactiveRotationStep;

/**
 * CRAFTY-react's forestry stage. Each year, every reactive forestry AFT decides its rotation in every decision
 * unit and works out the yield and cost that follow. The results are held in one {@link ForestryManagement} per
 * AFT.
 *
 * <pre>
 * Capital:   H = the grid rotation nearest to H_b − 1000 × react_R_par × capital       afresh each year
 * Prospect:  H starts at H_b (first year decided) or at last year's H, then k steps,
 *            each pasture's stocking step on the grid, at the price in the unit's region
 * yield  = the forestry files' value at H, held at 0 or above                 m³/ha/yr
 * cost   = C / H                                                             $/ha/yr
 * </pre>
 * <ul>
 * <li><b>Spin-up.</b> k is {@code spinup_iterations} in the first year decided and 1 after that. A Capital AFT
 * takes no steps: it moves straight to its rotation each year, however far, and nothing is carried.</li>
 * <li><b>H_b</b> is the AFT's initial rotation, {@code Other_intensity} in years, which startup check 7 keeps on
 * the grid.</li>
 * <li><b>C</b>, the cost of one harvest, is the row of the AFT's service in {@code global_costs.csv} (check 8).
 * <b>The price</b> is the service's, in $/m³, in the unit's region; only Prospect AFTs use one.</li>
 * <li><b>Yield</b> is the AFT's suitability (F5 hands it over as its {@code _suit} capital). It is held at 0 or
 * above: a negative value in the forestry files is an artefact, and would only cause trouble in the model (32d
 * plan, Q1). The Prospect step still weighs the profit as it comes, as pasture's does.</li>
 * <li><b>No profit is kept.</b> The Prospect step weighs one only to choose the rotation; the model works out
 * its own from the yield and cost.</li>
 * <li>The forestry files hold one table for all wood, in m³/ha/yr (converted from LPJ-GUESS's carbon before the
 * run), so every forestry AFT reads the same yields.</li>
 * </ul>
 *
 * AFTs are decided in sheet order and units in order, on one thread, so a run can be repeated exactly.
 */
public final class ForestryDecisions {

	private final ReactConfig config;
	private final DecisionUnits units;
	private final ReactBaseCosts baseCosts;
	private final List<Integer> rotations;
	private final Map<String, ForestryManagement> managements;

	/** The last year decided, or null before the first. */
	private Integer lastYear;

	private ForestryDecisions(ReactConfig config, List<AftReactParameters> afts, ReactBaseCosts baseCosts,
			DecisionUnits units) {
		this.config = config;
		this.units = units;
		this.baseCosts = baseCosts;
		this.rotations = config.forestryRotations();

		Map<String, ForestryManagement> managements = new LinkedHashMap<>();
		for (AftReactParameters aft : afts) {
			managements.put(aft.label(), new ForestryManagement(aft, units.size(), rotations.get(0),
					rotations.get(rotations.size() - 1)));
		}
		this.managements = Collections.unmodifiableMap(managements);
	}

	/**
	 * The forestry stage for a run: every reactive forestry AFT, as long as forestry is switched on.
	 *
	 * With it off there are none, and forestry AFTs keep the suitabilities and intensity costs the model reads
	 * from its own files (phase 3 plan, Q6).
	 */
	public static ForestryDecisions create(ReactInputs inputs, DecisionUnits units) {
		List<AftReactParameters> afts = inputs.context().isReactive(ReactElement.FORESTRY)
				? inputs.checked().parameters().reactive(LpjgType.FORESTRY)
				: List.of();
		return new ForestryDecisions(inputs.config(), afts, inputs.checked().baseCosts(), units);
	}

	/**
	 * The services whose price the forestry stage needs each year: those of its Prospect AFTs. A Capital AFT
	 * follows a capital, so it needs none.
	 */
	public Set<String> servicesNeedingPrices() {
		Set<String> services = new LinkedHashSet<>();
		for (ForestryManagement management : managements.values()) {
			if (!management.aft().usesCapitalForRotation()) {
				services.add(management.aft().service());
			}
		}
		return services;
	}

	/**
	 * Decides a year. In the first year decided, Prospect AFTs are spun up from their initial rotation; in each
	 * later year they take one step from the year before. Asking for the year just decided again changes
	 * nothing.
	 *
	 * @param year   the year's data, as {@code ReactInputs.forYear} gives it
	 * @param prices the year's prices, for at least {@link #servicesNeedingPrices()}
	 * @return each AFT's management, by label, in sheet order
	 * @throws IllegalArgumentException if the prices are for another year
	 * @throws IllegalStateException    if the year is before the last year decided
	 */
	public Map<String, ForestryManagement> decide(ReactYearData year, YearPrices prices) {
		int y = year.year();
		if (prices.year() != y) {
			throw new IllegalArgumentException("CRAFTY-react: year " + y + " was given the prices of " + prices.year());
		}
		if (lastYear != null && y == lastYear) {
			return managements;
		}
		if (lastYear != null && y < lastYear) {
			throw new IllegalStateException(
					"CRAFTY-react decides years in order, but was asked for " + y + " after " + lastYear);
		}
		boolean firstYear = lastYear == null;
		int steps = firstYear ? config.spinupIterations() : 1;
		if (!managements.isEmpty()) {
			// One table for all wood: each rotation's yields, by pixel, in the order of the grid.
			float[][] table = new float[rotations.size()][];
			for (int i = 0; i < table.length; i++) {
				table[i] = year.forestryYield(rotations.get(i));
			}
			for (ForestryManagement management : managements.values()) {
				decide(management, table, year, prices, firstYear, steps);
			}
		}
		lastYear = y;
		return managements;
	}

	/** Each AFT's management for the last year decided, by label, in sheet order. */
	public Map<String, ForestryManagement> managements() {
		return managements;
	}

	/** The last year decided, or null before the first. */
	public Integer lastYear() {
		return lastYear;
	}

	/** One AFT, every unit. */
	private void decide(ForestryManagement m, float[][] table, ReactYearData year, YearPrices prices,
			boolean firstYear, int steps) {
		AftReactParameters aft = m.aft();
		double harvestCost = baseCosts.get(aft.service());
		float[] capital = aft.usesCapitalForRotation() ? year.capital(aft.rCapital()) : null;
		double[] price = capital == null ? prices.price(aft.service()) : null;

		for (int unit = 0; unit < units.size(); unit++) {
			int pixel = units.pixel(unit);
			int rotation;
			if (capital != null) {
				rotation = ReactiveRotationStep.capitalRotation(aft.initialRotation(), aft.rPar(), capital[pixel],
						rotations);
			} else {
				ReactiveRotationStep.Economics place = new ReactiveRotationStep.Economics(
						h -> table[rotations.indexOf(h)][pixel], price[unit], harvestCost);
				// The initial rotation is a whole number of years on the grid (startup check 7).
				rotation = firstYear ? (int) aft.initialRotation() : m.rotation[unit];
				for (int step = 0; step < steps; step++) {
					rotation = ReactiveRotationStep.prospectStep(rotation, place, aft.rPar(), rotations);
				}
			}
			m.rotation[unit] = rotation;
			double value = table[rotations.indexOf(rotation)][pixel];
			m.yield[unit] = Math.max(0, value);
			m.cost[unit] = ReactiveRotationStep.rotationCost(harvestCost, rotation);
		}
	}
}
