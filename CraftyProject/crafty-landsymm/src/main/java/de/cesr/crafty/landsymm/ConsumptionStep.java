package de.cesr.crafty.landsymm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;

/**
 * After SupplyUpdater: each region's consumption for transport export shares, from PLUM's domestic use in the year's
 * PLUM rows (48d PLAN section 4; the calculation is in {@link PlumConsumption}). It runs in every year, the start year
 * included, with plum_consumption on and transport charged by export share; otherwise it does nothing.
 *
 * The consumption goes to core through ProductionCostUpdater.setConsumption, for this year only: core reads only the
 * current year, and computes the export shares in the year's regional step, after this. Each region's supply of a
 * service is core's regional supply now, before competition. Every value is recorded in
 * plum_coupling/export_basis.csv; the shares themselves are in CRAFTY's own region output.
 */
final class ConsumptionStep extends CouplerStep {

	static final String EXPORT_BASIS = "export_basis.csv";
	static final List<String> EXPORT_BASIS_HEADER = List.of("Year", "Region", "Country", "Service", "PLUM_commodity",
			"PLUM_domestic_use", "CRAFTY_supply", "Consumption");

	private static final CustomLogger LOGGER = new CustomLogger(ConsumptionStep.class);

	private final PlumYear plumYear; // null when off
	private final ServiceCommodityMap services;
	private final RegionCountryKey key;
	private final CouplingOutputs outputs;
	private final ProductionStep.Regions regions;
	private final Consumer<Map<String, Map<Integer, double[]>>> sink;

	/**
	 * Starts export_basis.csv afresh.
	 *
	 * @param sink where the year's consumption goes: ProductionCostUpdater::setConsumption
	 */
	ConsumptionStep(PlumYear plumYear, ServiceCommodityMap services, RegionCountryKey key, CouplingOutputs outputs,
			ProductionStep.Regions regions, Consumer<Map<String, Map<Integer, double[]>>> sink) {
		this.plumYear = plumYear;
		this.services = services;
		this.key = key;
		this.outputs = outputs;
		this.regions = regions;
		this.sink = sink;
		outputs.start(EXPORT_BASIS, EXPORT_BASIS_HEADER);
	}

	private ConsumptionStep() {
		this.plumYear = null;
		this.services = null;
		this.key = null;
		this.outputs = null;
		this.regions = null;
		this.sink = null;
	}

	/** The step with plum_consumption off, transport not charged by export share, or in a calibration run. */
	static ConsumptionStep off() {
		return new ConsumptionStep();
	}

	@Override
	protected void yearStep(int year) {
		if (plumYear == null) {
			return;
		}
		PlumYearOutputs rows = plumYear.rows(year);
		Map<String, Map<String, Double>> supply = regions.coreSupply();
		List<String> craftyServices = ServiceSet.getServicesList();
		Map<String, Map<Integer, double[]>> byRegion = new TreeMap<>();
		List<List<String>> basis = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (String region : key.regions()) {
			String country = key.country(region);
			Map<String, Double> regionSupply = supply.get(region);
			if (regionSupply == null) {
				problems.add("Region " + region + " has no supply to split PLUM's domestic use by");
				continue;
			}
			List<PlumConsumption.ServiceConsumption> consumption;
			try {
				consumption = PlumConsumption.consumption(rows, country, services, regionSupply);
			} catch (CouplingInputException e) {
				problems.addAll(e.getProblems());
				continue;
			}
			byRegion.put(region, Map.of(year, PlumConsumption.asArray(consumption, craftyServices)));
			for (PlumConsumption.ServiceConsumption c : consumption) {
				basis.add(List.of(String.valueOf(year), region, country, c.service(), c.commodity(),
						ProductionFile.format(c.plumUse()), ProductionFile.format(c.supply()),
						ProductionFile.format(c.consumption())));
			}
		}
		if (!problems.isEmpty()) {
			throw new CouplingInputException(problems);
		}
		sink.accept(byRegion);
		outputs.append(EXPORT_BASIS, basis);
		LOGGER.info(year + ": consumption for export shares set from PLUM's " + rows.year() + " domestic use for "
				+ byRegion.size() + " regions");
	}
}
