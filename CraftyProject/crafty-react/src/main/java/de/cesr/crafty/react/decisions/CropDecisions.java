package de.cesr.crafty.react.decisions;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.react.data.AftReactParameters;
import de.cesr.crafty.react.data.IrrigationCostGrid;
import de.cesr.crafty.react.data.LpjgType;
import de.cesr.crafty.react.data.NitrogenMode;
import de.cesr.crafty.react.data.ReactBaseCosts;
import de.cesr.crafty.react.data.ReactConfig;
import de.cesr.crafty.react.data.ReactElement;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.science.CropSurfaces;
import de.cesr.crafty.react.science.IntensityFunctions;
import de.cesr.crafty.react.science.Irrigation;
import de.cesr.crafty.react.science.ReactiveNitrogenStep;

/**
 * CRAFTY-react's cropland stage. Each year, every reactive crops AFT decides its other intensity,
 * irrigation and N in every decision unit, in the brief's order (§3.1), and works out the yield and costs
 * that follow. The results are held in one {@link CropManagement} per AFT.
 *
 * <pre>
 * O = other intensity, fixed for the year
 * Prospect N, k times:
 *     level = irrigation level at the current N        (rainfed AFTs: 0)
 *     N     = one N step, with that level and O
 * at the final N: water applied, irrigation level, yield and costs
 * </pre>
 * <ul>
 * <li><b>Spin-up.</b> k is {@code spinup_iterations} in the first year decided and 1 after that. N starts
 * at the AFT's {@code Nfert_rate} in the first year and at last year's N after that. The anchor
 * ({@code Nfert_rate}) and Nmax ({@code n_max_factor × Nfert_rate}) never move.</li>
 * <li><b>Other intensity</b>, switched on: {@code Eff_func} of the AFT's {@code react_O_capital}, with
 * {@code Other_intensity} as the floor and {@code react_O_par} as the threshold. With the element off, or
 * with no {@code react_O_capital}: {@code Other_intensity}.</li>
 * <li><b>Irrigation</b> (AFTs with {@code Irrigated = 1}), switched on: water demand at the current N, and
 * efficiency {@code react_I_eff}. Switched off: demand at {@code Nfert_rate}, and efficiency 1. Either way
 * the water applied is what is required, but no more than the runoff, and the level is applied ÷
 * required.</li>
 * <li><b>Fertiliser</b>, switched on: a Prospect AFT takes {@link ReactiveNitrogenStep#prospect} steps at
 * its service's price in the unit's region; a Capital AFT's N is
 * {@link IntensityFunctions#capitalNitrogen}, which needs no steps. Switched off: {@code Nfert_rate}.</li>
 * <li><b>Everything reported</b> (water, level, yield, costs) is worked out at the final N (phase 3 plan,
 * Q3). The yield includes the technology term, with {@code ts} = years since the first model year; the
 * N step sees the same yields.</li>
 * <li><b>Costs</b>, $/ha, each only when its element is switched on: N × {@code Nfert}; water applied ×
 * the pixel's irrigation cost index × {@code Water}; other intensity × the cost of the AFT's service.</li>
 * </ul>
 *
 * Within one N step the irrigation level is held fixed, as R's {@code prospect_Nuse} holds it: the step
 * does not foresee that more N needs more water. The N decision also weighs N's return against N's cost
 * only, not against the extra water.
 *
 * AFTs are decided in sheet order and units in order, on one thread, so a run can be repeated exactly.
 */
public final class CropDecisions {

	/** The elements that change crops. Other intensity also changes pasture husbandry. */
	public static final Set<ReactElement> CROP_ELEMENTS = Collections.unmodifiableSet(
			EnumSet.of(ReactElement.FERTILISER, ReactElement.IRRIGATION, ReactElement.OTHER_INTENSITY));

	private final ReactConfig config;
	private final ReactRunContext context;
	private final DecisionUnits units;
	private final IrrigationCostGrid irrigationCost;
	private final ReactBaseCosts baseCosts;
	private final ReactiveNitrogenStep.Settings nitrogenSettings;
	private final boolean fertiliser;
	private final boolean irrigation;
	private final boolean otherIntensity;
	private final Map<String, CropManagement> managements;

	/** The last year decided, or null before the first. */
	private Integer lastYear;

	private CropDecisions(ReactConfig config, ReactRunContext context, List<AftReactParameters> afts,
			IrrigationCostGrid irrigationCost, ReactBaseCosts baseCosts, DecisionUnits units) {
		this.config = config;
		this.context = context;
		this.units = units;
		this.irrigationCost = irrigationCost;
		this.baseCosts = baseCosts;
		this.nitrogenSettings = new ReactiveNitrogenStep.Settings(config.prospectAlpha(), config.prospectBeta(),
				config.prospectLambda(), config.nAdjustmentScale());
		this.fertiliser = context.isReactive(ReactElement.FERTILISER);
		this.irrigation = context.isReactive(ReactElement.IRRIGATION);
		this.otherIntensity = context.isReactive(ReactElement.OTHER_INTENSITY);

		Map<String, CropManagement> managements = new LinkedHashMap<>();
		for (AftReactParameters aft : afts) {
			managements.put(aft.label(), new CropManagement(aft, config.nMaxFactor() * aft.nitrogenBaseline(),
					units.size(), fertiliser, irrigation && aft.isIrrigated(), otherIntensity));
		}
		this.managements = Collections.unmodifiableMap(managements);
	}

	/**
	 * The cropland stage for a run: every reactive crops AFT, as long as a crop element is switched on.
	 *
	 * With no crop element on (only stocking, say) there are none. Each switch changes only its own land
	 * use, so crop suitabilities then keep their values from the input capitals (phase 3 plan, Q6).
	 */
	public static CropDecisions create(ReactInputs inputs, DecisionUnits units) {
		ReactRunContext context = inputs.context();
		List<AftReactParameters> afts = CROP_ELEMENTS.stream().anyMatch(context::isReactive)
				? inputs.checked().parameters().reactive(LpjgType.CROPS)
				: List.of();
		return new CropDecisions(inputs.config(), context, afts, inputs.checked().irrigationCost(),
				inputs.checked().baseCosts(), units);
	}

	/**
	 * The services whose price the cropland stage needs each year: those of Prospect AFTs, when fertiliser is
	 * reactive. Nothing else in cropland uses a price.
	 */
	public Set<String> servicesNeedingPrices() {
		Set<String> services = new LinkedHashSet<>();
		if (fertiliser) {
			for (CropManagement management : managements.values()) {
				if (management.aft().nMode() == NitrogenMode.PROSPECT) {
					services.add(management.aft().service());
				}
			}
		}
		return services;
	}

	/**
	 * Decides a year. The first year decided is spun up; each later year takes one step from the year
	 * before. Asking for the year just decided again changes nothing.
	 *
	 * @param year     the year's data, as {@code ReactInputs.forYear} gives it
	 * @param surfaces the year's fitted surfaces, from {@code CropSurfaces.fit(year)}
	 * @param prices   the year's prices, for at least {@link #servicesNeedingPrices()}
	 * @return each AFT's management, by label, in sheet order
	 * @throws IllegalArgumentException if the surfaces or prices are for another year
	 * @throws IllegalStateException    if the year is before the last year decided
	 */
	public Map<String, CropManagement> decide(ReactYearData year, CropSurfaces surfaces, YearPrices prices) {
		int y = year.year();
		if (surfaces.year() != y || prices.year() != y) {
			throw new IllegalArgumentException("CRAFTY-react: year " + y + " was given the surfaces of "
					+ surfaces.year() + " and the prices of " + prices.year());
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
		double yearsSinceStart = y - context.firstYear();
		for (CropManagement management : managements.values()) {
			decide(management, year, surfaces, prices, firstYear, steps, yearsSinceStart);
		}
		lastYear = y;
		return managements;
	}

	/** Each AFT's management for the last year decided, by label, in sheet order. */
	public Map<String, CropManagement> managements() {
		return managements;
	}

	/** The last year decided, or null before the first. */
	public Integer lastYear() {
		return lastYear;
	}

	/** One AFT, every unit. */
	private void decide(CropManagement m, ReactYearData year, CropSurfaces surfaces, YearPrices prices,
			boolean firstYear, int steps, double yearsSinceStart) {
		AftReactParameters aft = m.aft();
		double tech = config.yieldTechChange();
		double anchor = aft.nitrogenBaseline();
		double maximum = m.nitrogenMaximum();
		double floor = aft.otherIntensityBaseline();
		CropSurfaces.Surface surface = surfaces.crop(aft.lpjgName());

		float[] oCapital = otherIntensity && aft.hasCapitalDrivenOtherIntensity() ? year.capital(aft.oCapital()) : null;

		boolean irrigated = aft.isIrrigated();
		CropSurfaces.WaterDemand waterDemand = irrigated ? surfaces.waterDemand(aft.lpjgName()) : null;
		float[] runoff = irrigated ? year.runoff() : null;
		double efficiency = irrigated && irrigation ? aft.iEff() : 1;

		NitrogenMode mode = fertiliser ? aft.nMode() : null;
		float[] nCapital = mode == NitrogenMode.CAPITAL ? year.capital(aft.nCapital()) : null;
		double[] price = mode == NitrogenMode.PROSPECT ? prices.price(aft.service()) : null;

		double nitrogenPrice = m.nitrogenCost != null ? baseCosts.get(ReactBaseCosts.NFERT) : 0;
		double waterPrice = m.irrigationCost != null ? baseCosts.get(ReactBaseCosts.WATER) : 0;
		double servicePrice = m.intensityCost != null ? baseCosts.get(aft.service()) : 0;

		for (int unit = 0; unit < units.size(); unit++) {
			int pixel = units.pixel(unit);
			double o = oCapital == null ? floor : IntensityFunctions.effFunc(oCapital[pixel], floor, aft.oPar());

			double n;
			if (mode == NitrogenMode.PROSPECT) {
				n = firstYear ? anchor : m.nitrogen[unit];
				for (int step = 0; step < steps; step++) {
					double level = irrigated
							? water(waterDemand, runoff[pixel], pixel, irrigation ? n : anchor, efficiency).level()
							: 0;
					n = ReactiveNitrogenStep.prospect(n, anchor, maximum,
							nitrogen -> surface.yield(pixel, nitrogen, level, o, tech, yearsSinceStart), price[unit],
							nitrogenPrice, aft.nPar(), nitrogenSettings);
				}
			} else if (mode == NitrogenMode.CAPITAL) {
				n = IntensityFunctions.capitalNitrogen(anchor, nCapital[pixel], aft.nPar());
			} else {
				n = anchor;
			}

			// Everything reported is worked out at the final N.
			double applied = 0;
			double level = 0;
			if (irrigated) {
				Water water = water(waterDemand, runoff[pixel], pixel, irrigation ? n : anchor, efficiency);
				applied = water.applied();
				level = water.level();
			}
			m.nitrogen[unit] = n;
			m.otherIntensity[unit] = o;
			m.waterApplied[unit] = applied;
			m.irrigationLevel[unit] = level;
			m.yield[unit] = surface.yield(pixel, n, level, o, tech, yearsSinceStart);
			if (m.nitrogenCost != null) {
				m.nitrogenCost[unit] = n * nitrogenPrice;
			}
			if (m.irrigationCost != null) {
				m.irrigationCost[unit] = Irrigation.cost(applied, irrigationCost.at(pixel), waterPrice);
			}
			if (m.intensityCost != null) {
				m.intensityCost[unit] = o * servicePrice;
			}
		}
	}

	/** Water at one pixel: how much is applied, m³/ha, and the irrigation level, 0–1. */
	private record Water(double applied, double level) {
	}

	/**
	 * @param nitrogen the N the demand is read at: the current N, or {@code Nfert_rate} when irrigation is
	 *                 not reactive
	 */
	private static Water water(CropSurfaces.WaterDemand waterDemand, double runoff, int pixel, double nitrogen,
			double efficiency) {
		double required = Irrigation.required(waterDemand.waterDemand(pixel, nitrogen), efficiency);
		double applied = Irrigation.applied(required, runoff);
		return new Water(applied, Irrigation.level(applied, required));
	}
}
