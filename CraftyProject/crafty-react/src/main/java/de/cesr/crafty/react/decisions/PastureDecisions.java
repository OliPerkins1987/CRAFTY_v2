package de.cesr.crafty.react.decisions;

import java.util.Collections;
import java.util.EnumSet;
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
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.science.IntensityFunctions;
import de.cesr.crafty.react.science.ReactivePastureStep;

/**
 * CRAFTY-react's pasture stage. Each year, every reactive pasture AFT decides its husbandry and then its
 * stocking rate in every decision unit, in the brief's order (§3.2), and works out the production and costs
 * that follow. The results are held in one {@link PastureManagement} per AFT.
 *
 * <pre>
 * h = husbandry, fixed for the year
 * stocking reactive, k times:
 *     S = one stocking step, with h and the unit's price
 * production = NPP × h × harvest × (1 − e^(−3.22 × S))       stocking reactive
 *            = NPP × h × harvest                              stocking not reactive
 * production is held at 0 or above; then the costs
 * </pre>
 * <ul>
 * <li><b>Spin-up.</b> k is {@code spinup_iterations} in the first year decided and 1 after that. The stocking
 * rate starts at {@code stocking.initial} in the first year and at last year's rate after that.</li>
 * <li><b>Husbandry</b>, other intensity switched on: {@code animal.husbandry} of the AFT's
 * {@code react_O_capital} × {@code react_O_par}. Switched off: {@code Other_intensity}.</li>
 * <li><b>Stocking</b>, switched on: {@link ReactivePastureStep#stockingStep} at the price of the AFT's service
 * in the unit's region, with {@code react_S_par} as the threshold. The profit it weighs includes the husbandry
 * cost, husbandry × the cost of the AFT's service, whether or not other intensity is reactive (phase 4 plan,
 * Q4). Switched off: react decides no stocking rate. The model's {@code Pasture} production level is then the
 * AFT's stocking rate, so production leaves the stocking term out (Q1).</li>
 * <li><b>Production</b> is the AFT's suitability (phase 5 writes it as its {@code _suit} capital). It is held
 * at 0 or above, which matters where NPP is negative (Q5). The stocking step still weighs the profit as it
 * comes, as R's {@code prospect_stocking} does.</li>
 * <li><b>Costs</b>, $/ha, each only when its element is switched on: husbandry × the cost of the AFT's service,
 * and stocking rate × {@code Stocking} (Q3).</li>
 * </ul>
 *
 * AFTs are decided in sheet order and units in order, on one thread, so a run can be repeated exactly.
 */
public final class PastureDecisions {

	/** The elements that change pasture. Other intensity also changes crops. */
	public static final Set<ReactElement> PASTURE_ELEMENTS = Collections.unmodifiableSet(
			EnumSet.of(ReactElement.OTHER_INTENSITY, ReactElement.STOCKING));

	private final ReactConfig config;
	private final DecisionUnits units;
	private final ReactBaseCosts baseCosts;
	private final ReactivePastureStep.Settings settings;
	private final boolean otherIntensity;
	private final boolean stocking;
	private final Map<String, PastureManagement> managements;

	/** The last year decided, or null before the first. */
	private Integer lastYear;

	private PastureDecisions(ReactConfig config, ReactRunContext context, List<AftReactParameters> afts,
			ReactBaseCosts baseCosts, DecisionUnits units) {
		this.config = config;
		this.units = units;
		this.baseCosts = baseCosts;
		this.settings = new ReactivePastureStep.Settings(config.stockingHarvest(), config.stockingStep(),
				config.stockingMin(), config.stockingMax());
		this.otherIntensity = context.isReactive(ReactElement.OTHER_INTENSITY);
		this.stocking = context.isReactive(ReactElement.STOCKING);

		Map<String, PastureManagement> managements = new LinkedHashMap<>();
		for (AftReactParameters aft : afts) {
			managements.put(aft.label(), new PastureManagement(aft, units.size(), config.stockingMin(),
					config.stockingMax(), stocking, otherIntensity));
		}
		this.managements = Collections.unmodifiableMap(managements);
	}

	/**
	 * The pasture stage for a run: every reactive pasture AFT, as long as a pasture element is switched on.
	 *
	 * With neither on (only fertiliser or irrigation, say) there are none. Each switch changes only its own
	 * land use, so pasture suitabilities then keep their values from the input capitals (phase 3 plan, Q6).
	 */
	public static PastureDecisions create(ReactInputs inputs, DecisionUnits units) {
		ReactRunContext context = inputs.context();
		List<AftReactParameters> afts = PASTURE_ELEMENTS.stream().anyMatch(context::isReactive)
				? inputs.checked().parameters().reactive(LpjgType.PASTURE)
				: List.of();
		return new PastureDecisions(inputs.config(), context, afts, inputs.checked().baseCosts(), units);
	}

	/**
	 * The services whose price the pasture stage needs each year: those of its AFTs, when stocking is
	 * reactive. Husbandry follows a capital, so nothing else in pasture uses a price.
	 */
	public Set<String> servicesNeedingPrices() {
		Set<String> services = new LinkedHashSet<>();
		if (stocking) {
			for (PastureManagement management : managements.values()) {
				services.add(management.aft().service());
			}
		}
		return services;
	}

	/**
	 * Decides a year. The first year decided is spun up; each later year takes one step from the year
	 * before. Asking for the year just decided again changes nothing.
	 *
	 * @param year   the year's data, as {@code ReactInputs.forYear} gives it
	 * @param prices the year's prices, for at least {@link #servicesNeedingPrices()}
	 * @return each AFT's management, by label, in sheet order
	 * @throws IllegalArgumentException if the prices are for another year
	 * @throws IllegalStateException    if the year is before the last year decided
	 */
	public Map<String, PastureManagement> decide(ReactYearData year, YearPrices prices) {
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
		for (PastureManagement management : managements.values()) {
			decide(management, year, prices, firstYear, steps);
		}
		lastYear = y;
		return managements;
	}

	/** Each AFT's management for the last year decided, by label, in sheet order. */
	public Map<String, PastureManagement> managements() {
		return managements;
	}

	/** The last year decided, or null before the first. */
	public Integer lastYear() {
		return lastYear;
	}

	/** One AFT, every unit. */
	private void decide(PastureManagement m, ReactYearData year, YearPrices prices, boolean firstYear, int steps) {
		AftReactParameters aft = m.aft();
		float[] npp = year.pasture(aft.lpjgName());
		float[] oCapital = otherIntensity ? year.capital(aft.oCapital()) : null;
		double[] price = stocking ? prices.price(aft.service()) : null;
		double stockingPrice = stocking ? baseCosts.get(ReactBaseCosts.STOCKING) : 0;
		// The husbandry cost, and inside the stocking decision's profit whatever the other-intensity switch
		// (Q4). The startup checks make sure the row is there whenever either element is on.
		double husbandryPrice = baseCosts.get(aft.service());
		double harvest = settings.harvest();

		for (int unit = 0; unit < units.size(); unit++) {
			int pixel = units.pixel(unit);
			double h = oCapital == null ? aft.otherIntensityBaseline()
					: IntensityFunctions.animalHusbandry(oCapital[pixel] * aft.oPar());

			double production;
			if (m.stocking != null) {
				double s = firstYear ? config.stockingInitial() : m.stocking[unit];
				ReactivePastureStep.Economics economics = new ReactivePastureStep.Economics(npp[pixel], h,
						price[unit], stockingPrice, husbandryPrice);
				for (int step = 0; step < steps; step++) {
					s = ReactivePastureStep.stockingStep(s, economics, aft.sPar(), settings);
				}
				m.stocking[unit] = s;
				m.stockingCost[unit] = s * stockingPrice;
				production = ReactivePastureStep.production(npp[pixel], h, harvest, s);
			} else {
				// The model's Pasture production level supplies the stocking (Q1).
				production = npp[pixel] * h * harvest;
			}
			m.husbandry[unit] = h;
			m.production[unit] = Math.max(0, production);
			if (m.intensityCost != null) {
				m.intensityCost[unit] = h * husbandryPrice;
			}
		}
	}
}
