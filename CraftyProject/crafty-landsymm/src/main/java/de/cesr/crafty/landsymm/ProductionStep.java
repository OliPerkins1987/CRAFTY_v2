package de.cesr.crafty.landsymm;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.updaters.RegionsModelRunnerUpdater;

/**
 * Writes CRAFTY's production for PLUM ({@code <crafty_production_path>/<year>/production.csv} and its done file) and
 * records it in plum_coupling/production_sent.csv.
 *
 * Where the supply comes from (48c PLAN 2.1):
 * <ul>
 * <li>a sequential scenario run, every year including the start year: the step runs after giving up and
 * competition. Those leave each region's supply only partly updated (competition updates it, giving up and the
 * takeover of unmanaged cells don't), so productivity is recalculated on this year's land use first;</li>
 * <li>otherwise: the cells as CRAFTY last computed them. That is every overlapping year, where the step comes right
 * after SupplyUpdater, and a calibration run, after initialzeRun. The coupler's sum must then equal core's own
 * regional supply, which catches the two drifting apart.</li>
 * </ul>
 * {@link #checkBaseline(int)} is the 1990 check: a scenario run's starting-map production against the calibration
 * run's. A difference is a warning, and the run carries on.
 */
final class ProductionStep extends CouplerStep {

	static final String PRODUCTION_SENT = "production_sent.csv";
	static final String BASELINE_CHECK = "baseline_check.csv";
	static final List<String> PRODUCTION_SENT_HEADER = List.of("Year", "Region", "Country", "Service",
			"PLUM_commodity", "CRAFTY_supply", "CRAFTY_self_consumed", "PLUM_quantity_factor", "PLUM_quantity");

	private static final CustomLogger LOGGER = new CustomLogger(ProductionStep.class);

	/** CRAFTY's regions: each one's cells, and the supply core last summed for it. */
	interface Regions {
		Map<String, Collection<Cell>> cells();

		Map<String, Map<String, Double>> coreSupply();

		/** The loaded model's regions, from RegionsModelRunnerUpdater. */
		static Regions ofModel() {
			return new Regions() {
				@Override
				public Map<String, Collection<Cell>> cells() {
					Map<String, Collection<Cell>> cells = new TreeMap<>();
					RegionsModelRunnerUpdater.regionsModelRunner.forEach((name, r) -> cells.put(name, r.R.getCells().values()));
					return cells;
				}

				@Override
				public Map<String, Map<String, Double>> coreSupply() {
					Map<String, Map<String, Double>> supply = new TreeMap<>();
					RegionsModelRunnerUpdater.regionsModelRunner.forEach((name, r) -> supply.put(name, r.getRegionalSupply()));
					return supply;
				}
			};
		}
	}

	private final ServiceCommodityMap services;
	private final List<String> marketServices;
	private final RegionCountryKey key;
	private final CouplingOutputs outputs;
	private final Regions regions;
	private final Path productionDir;
	private final Path calibrationProductionDir; // null in a calibration run
	private final boolean sequential;
	private final boolean calibrationRun;
	private final boolean useCellArea;

	/** Starts plum_coupling/production_sent.csv afresh. */
	ProductionStep(Config c, ServiceCommodityMap services, RegionCountryKey key, CouplingOutputs outputs,
			Regions regions) {
		this.services = services;
		this.marketServices = services.links().stream().map(ServiceCommodityMap.Link::service).toList();
		this.key = key;
		this.outputs = outputs;
		this.regions = regions;
		this.productionDir = Paths.get(c.crafty_production_path);
		this.calibrationRun = c.plum_calibration_run;
		this.calibrationProductionDir = calibrationRun ? null : Paths.get(c.calibration_production_path);
		this.sequential = "sequential".equals(c.plum_coupling_mode);
		this.useCellArea = c.use_cell_area;
		outputs.start(PRODUCTION_SENT, PRODUCTION_SENT_HEADER);
	}

	/**
	 * Whether productivity is recalculated before the sum: in a sequential scenario run, every year, because the step
	 * runs after that year's giving up and competition. Not in overlapping mode (right after SupplyUpdater) or in a
	 * calibration run (after initialzeRun), where the drift check runs instead.
	 */
	static boolean recalculates(boolean sequential, boolean calibrationRun) {
		return sequential && !calibrationRun;
	}

	@Override
	protected void yearStep(int year) {
		boolean recalculate = recalculates(sequential, calibrationRun);
		long started = System.nanoTime();
		Map<String, SupplyPass.RegionSupply> supply = supply(recalculate);
		double seconds = (System.nanoTime() - started) / 1e9;

		Map<String, Map<String, Double>> production = ProductionFile.aggregate(SupplyPass.supplyOnly(supply), services,
				key);
		Path file = ProductionFile.write(productionDir, year, production);
		outputs.append(PRODUCTION_SENT, productionSentRows(year, supply));
		LOGGER.info(String.format(Locale.ROOT, "Production for %d written to %s: %d countries, supply %s in %.1f s", year,
				file, production.size(), recalculate ? "recalculated on this year's land use" : "as CRAFTY computed it",
				seconds));
	}

	/** Each region's supply now: recalculated on the current owners, or as CRAFTY computed it and checked against it. */
	private Map<String, SupplyPass.RegionSupply> supply(boolean recalculate) {
		Map<String, SupplyPass.RegionSupply> supply = SupplyPass.sum(regions.cells(), marketServices, recalculate,
				useCellArea);
		if (!recalculate) {
			List<String> drift = SupplyPass.driftProblems(supply, regions.coreSupply());
			if (!drift.isEmpty()) {
				throw new CouplingInputException(drift);
			}
		}
		return supply;
	}

	private List<List<String>> productionSentRows(int year, Map<String, SupplyPass.RegionSupply> supply) {
		List<List<String>> rows = new ArrayList<>();
		supply.forEach((region, s) -> {
			for (ServiceCommodityMap.Link link : services.links()) {
				double sent = s.supply().get(link.service());
				double gross = s.gross().get(link.service());
				rows.add(List.of(String.valueOf(year), region, key.country(region), link.service(), link.commodity(),
						ProductionFile.format(sent), ProductionFile.format(gross - sent),
						ProductionFile.format(link.quantityFactor()), ProductionFile.format(sent * link.quantityFactor())));
			}
		});
		return rows;
	}

	/**
	 * The 1990 check, in a scenario run: the starting map's production against the calibration run's. MainLandSymm
	 * calls it right after initialzeRun(), before the first year, which is the state a calibration run writes its
	 * production in. So the same CRAFTY on the same inputs gives identical files, whatever the coupling mode.
	 * Writes plum_coupling/baseline_check.csv; a difference is a warning only.
	 */
	void checkBaseline(int year) {
		if (calibrationProductionDir == null) {
			throw new IllegalStateException("A calibration run has no calibration run to compare with");
		}
		Map<String, Map<String, Double>> production = ProductionFile.aggregate(SupplyPass.supplyOnly(supply(false)),
				services, key);
		Path calibrationFile = calibrationProductionDir.resolve(String.valueOf(year)).resolve(ProductionFile.FILE);
		ProductionFile.Comparison comparison = ProductionFile.compare(ProductionFile.read(calibrationFile), production);
		comparison.writeCsv(outputs.file(BASELINE_CHECK));
		if (comparison.identical()) {
			LOGGER.info("The " + year + " starting map's production is identical to the calibration run's ("
					+ calibrationFile + ")");
		} else if (comparison.differing() == 0) {
			LOGGER.info("The " + year + " starting map's production is within 0.1% of the calibration run's ("
					+ calibrationFile
					+ ") in every country and commodity");
		} else {
			LOGGER.warn(comparison.warning(year, calibrationFile) + "\n  Every pair is in "
					+ outputs.file(BASELINE_CHECK));
		}
	}
}
