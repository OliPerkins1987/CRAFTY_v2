package de.cesr.crafty.core.updaters;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.dataLoader.CsvKind;
import de.cesr.crafty.core.dataLoader.CsvProcessors;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.costs.GlobalCostData;
import de.cesr.crafty.core.utils.file.PathTools;

public class ProductionCostUpdater extends AbstractUpdater {
	private static final CustomLogger LOGGER = new CustomLogger(ProductionCostUpdater.class);

	private static GlobalCostData globalCostData;
	private static List<String> nfertAftLabels = Collections.synchronizedList(new ArrayList<>());
	private static List<String> irrigatedAftLabels = Collections.synchronizedList(new ArrayList<>());
	private static List<String> intensityAftLabels = Collections.synchronizedList(new ArrayList<>());

	private Map<Integer, Path> nfertCostPaths = new TreeMap<>();
	private Map<Integer, Path> irrigationCostPaths = new TreeMap<>();
	private Map<Integer, Path> intensityCostPaths = new TreeMap<>();

	public ProductionCostUpdater() {
		if (!ConfigLoader.isUseProductionCosts()) {
			LOGGER.info("Production costs disabled");
			return;
		}

		buildAftLists();
		loadGlobalCosts();

		if (!ConfigLoader.isSpatialProductionCosts()) {
			cacheGlobalNfertCosts();
			cacheGlobalIntensityCosts();
			loadStaticIrrigationCost();
		} else {
			buildSpatialPathMaps();
		}
	}

	/*
	 * CLEANUP: which AFTs pay which cost is now decided by each AFT's own data
	 * rather than by its category name. These lists used to be gated on the literal
	 * strings "Agri_Crops", "Uncategorized" and "Natural", which meant an AFT that
	 * both cropped and grazed could never receive a fertiliser cost - the category
	 * was the gate, so a mixed AFT had to pick a side. The costs are independent and
	 * additive, and nothing here stops one AFT appearing in several lists.
	 */
	private void buildAftLists() {
		nfertAftLabels.clear();
		irrigatedAftLabels.clear();
		intensityAftLabels.clear();

		AFTsLoader.getAftHash().forEach((label, aft) -> {
			if (!aft.isInteract()) return;
			if (aft.getNfertRate() > 0) {
				nfertAftLabels.add(label);
			}
			if (aft.isIrrigated()) {
				irrigatedAftLabels.add(label);
			}
			/*
			 * Intensity applies to every interacting AFT; no exclusion list is needed.
			 * In global mode the productivity-weighted sum is already zero for an AFT
			 * that produces nothing priced, and in spatial mode a column missing from
			 * the cost CSV is skipped. Note this is a behaviour change for AFTs that
			 * were previously excluded by category but do produce a priced service.
			 */
			intensityAftLabels.add(label);
		});
		LOGGER.info("Nfert AFTs: " + nfertAftLabels);
		LOGGER.info("Irrigated AFTs: " + irrigatedAftLabels);
		LOGGER.info("Intensity AFTs: " + intensityAftLabels);
	}

	private void loadGlobalCosts() {
		Path globalCostsCsv = findCostFile("global", "global_costs", ".csv");
		if (globalCostsCsv == null) {
			LOGGER.fatal("global_costs.csv not found but use_production_costs is true");
			return;
		}
		globalCostData = new GlobalCostData(globalCostsCsv);
	}

	private void cacheGlobalNfertCosts() {
		if (globalCostData == null) return;

		/*
		 * The "Nfert_rate column missing or all zero" fatal check used to live here.
		 * It is unreachable now that nfertAftLabels is built from the rates themselves:
		 * a non-empty list already guarantees at least one positive rate, and an empty
		 * one simply means no AFT declares that it fertilises.
		 */
		if (nfertAftLabels.isEmpty()) {
			LOGGER.info("No AFT declares a positive Nfert_rate; no fertiliser costs applied");
			return;
		}

		double unitCost = globalCostData.getNfertUnitCost();
		for (String label : nfertAftLabels) {
			Aft aft = AFTsLoader.getAftHash().get(label);
			if (aft != null) {
				aft.setNfertCostPerHa(unitCost * aft.getNfertRate());
				LOGGER.info("Global Nfert cost for " + label + ": " + aft.getNfertCostPerHa() + " $/ha");
			}
		}
	}

	private void cacheGlobalIntensityCosts() {
		if (globalCostData == null) return;
		Map<String, Double> serviceCosts = globalCostData.getIntensityCosts();

		for (String label : intensityAftLabels) {
			Aft aft = AFTsLoader.getAftHash().get(label);
			if (aft == null) continue;
			double totalCost = 0.0;
			for (Map.Entry<String, Double> entry : serviceCosts.entrySet()) {
				String serviceName = entry.getKey();
				double serviceCost = entry.getValue();
				double prodLevel = aft.getProductivityLevel().getOrDefault(serviceName, 0.0);
				totalCost += prodLevel * serviceCost;
			}
			/*
			 * Other_intensity scales the productivity-weighted sum, so two AFTs
			 * producing the same service at the same level can still differ in what
			 * their management costs. It defaults to 1.0, so this is a no-op for any
			 * project that does not populate the column.
			 */
			double scaledCost = totalCost * aft.getOtherIntensity();
			aft.setIntensityCostPerHa(scaledCost);
			LOGGER.info("Global intensity cost for " + label + ": " + scaledCost + " $/ha (unscaled "
					+ totalCost + " x Other_intensity " + aft.getOtherIntensity() + ")");
		}
	}

	private void loadStaticIrrigationCost() {
		Path irrigationCsv = findCostFile("spatial", "irrigation", "irrigation_cost", ".csv");
		if (irrigationCsv == null) {
			LOGGER.fatal("irrigation_cost.csv not found but use_production_costs is true (global mode)");
			return;
		}
		LOGGER.info("Loading static irrigation costs from: " + irrigationCsv);
		CsvProcessors.processCSV(irrigationCsv, CsvKind.IRRIGATION_COST);
	}

	private void buildSpatialPathMaps() {
		String scenario = ProjectLoader.getScenario();
		for (int year = Timestep.getStartYear(); year <= Timestep.getEndtYear(); year++) {
			Path nfert = findCostFile("spatial", "Nfert", scenario, "Nfert_costs_" + year, ".csv");
			if (nfert == null) {
				LOGGER.fatal("Spatial Nfert_costs CSV not found for year " + year);
				return;
			}
			nfertCostPaths.put(year, nfert);

			Path irrigation = findCostFile("spatial", "irrigation", scenario, "Irrigation_costs_" + year, ".csv");
			if (irrigation == null) {
				LOGGER.fatal("Spatial Irrigation_costs CSV not found for year " + year);
				return;
			}
			irrigationCostPaths.put(year, irrigation);

			Path intensity = findCostFile("spatial", "intensity", scenario, "Intensity_costs_" + year, ".csv");
			if (intensity == null) {
				LOGGER.fatal("Spatial Intensity_costs CSV not found for year " + year);
				return;
			}
			intensityCostPaths.put(year, intensity);
		}
	}

	@Override
	public void step() {
		if (!ConfigLoader.isUseProductionCosts()) return;
		if (!ConfigLoader.isSpatialProductionCosts()) return;

		int year = Timestep.getCurrentYear();

		Path nfertPath = nfertCostPaths.get(year);
		if (nfertPath != null) {
			LOGGER.info("Loading spatial Nfert costs for year " + year);
			CsvProcessors.processCSV(nfertPath, CsvKind.NFERT_COST);
		}

		Path irrigationPath = irrigationCostPaths.get(year);
		if (irrigationPath != null) {
			LOGGER.info("Loading spatial irrigation costs for year " + year);
			CsvProcessors.processCSV(irrigationPath, CsvKind.IRRIGATION_COST);
		}

		Path intensityPath = intensityCostPaths.get(year);
		if (intensityPath != null) {
			LOGGER.info("Loading spatial intensity costs for year " + year);
			CsvProcessors.processCSV(intensityPath, CsvKind.INTENSITY_COST);
		}
	}

	@Override
	public void toSchedule() {
		modelRunner.scheduleRepeating(this);
	}

	private Path findCostFile(String... conditions) {
		String costsDir = ConfigLoader.config.costs_directory;
		ArrayList<Path> results;
		if (costsDir != null && !costsDir.isEmpty()) {
			ArrayList<Path> ps = PathTools.findAllFilePaths(Paths.get(costsDir));
			results = PathTools.fileFilter(ps, conditions);
		} else {
			results = PathTools.fileFilter(conditions);
		}
		if (results == null || results.isEmpty()) {
			return null;
		}
		return results.get(0);
	}

	public static GlobalCostData getGlobalCostData() {
		return globalCostData;
	}

	public static List<String> getNfertAftLabels() {
		return nfertAftLabels;
	}

	public static List<String> getIrrigatedAftLabels() {
		return irrigatedAftLabels;
	}

	public static List<String> getIntensityAftLabels() {
		return intensityAftLabels;
	}
}
