package de.cesr.crafty.core.dataLoader.serivces;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Service;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.CsvProcessors;
import de.cesr.crafty.core.utils.file.PathTools;

/**
 * Global registry and loader for ecosystem services used by CRAFTY.
 *
 * This class provides a single authoritative list of service names ({@link #servicesList}) and associated
 * metadata that is required throughout the simulation. Service definitions are read from the services metadata
 * CSV referenced by {@link ProjectLoader#getServiceMetadata()}, and then materialised as {@link Service}
 * instances for each {@link de.cesr.crafty.core.crafty.Region} as well as for a world-aggregate container
 * ({@link #worldService}).
 *
 * Key responsibilities:
 *
 * - Loading the service labels and optional metadata:
 * {@link #loadServiceList()} reads the metadata file, selects the identifier column ("Label" if present,
 * otherwise "Name"), and populates {@link #servicesList}. If the metadata contains a "Category" column, a
 * service-to-category map is stored in {@link #categories}. If the metadata contains a "Penalise_Oversupply"
 * column, it is converted into a boolean flag per service in {@link #Penalise_Oversupply}; otherwise, all
 * services default to {@code true}. If it contains a "Market_good" column, Yes/No becomes a flag per service
 * in {@link #marketGood}; only market goods are self-consumed (use_self_consumption), which then needs the
 * column, with Yes or No for every service.
 *
 * - Creating service objects for regions and world:
 * {@link #initialseServices()} instantiates a {@link Service} for each service name in every region’s
 * {@code servicesHash}, and also in {@link #worldService}. This ensures consistent availability of service
 * objects before demand and weight updates are applied.
 *
 * - Regionalisation feasibility check:
 * {@link #isRegionalServicesExisting()} verifies that a demand file exists for each region (scenario-specific),
 * and disables regionalisation if any region is missing its required demand input. This protects the run from
 * partially-specified regional inputs.
 *
 * - Time-varying updates:
 * {@link #serviceupdater()} triggers the update pipeline for service demand and service weights.
 * It also aggregates regional demands into the world-level service demand container.
 *
 * Notes:
 * - All collections are static because services are global configuration/state shared across the whole run.
 * - {@link #NoInitialSupplyServices} is used as a runtime bookkeeping container (per region) to record services
 * that have zero baseline supply and therefore default calibration behaviour elsewhere in {@link RegionalModelRunner}.
 */

/**
 * @author Mohamed Byari
 *
 */

public class ServiceSet {
	private static final CustomLogger LOGGER = new CustomLogger(ServiceSet.class);
	private static List<String> servicesList = new ArrayList<>();
	private static ConcurrentHashMap<String, String> categories = new ConcurrentHashMap<>();// <serviceName,serviceCategories>
	private static ConcurrentHashMap<String, Boolean> Penalise_Oversupply = new ConcurrentHashMap<>();
	// Market_good per service: only market goods are self-consumed. Empty when Services.csv has no such column.
	private static ConcurrentHashMap<String, Boolean> marketGood = new ConcurrentHashMap<>();

	public static ConcurrentHashMap<String, Service> worldService = new ConcurrentHashMap<>();
	public static ConcurrentHashMap<String, List<String>> NoInitialSupplyServices = new ConcurrentHashMap<>();

	public static void initialseServices() {
		CellsLoader.regions.values().forEach(r -> {
			servicesList.forEach(n -> {
				r.getServicesHash().put(n, new Service(n));
			});
		});
		servicesList.forEach((ns) -> {
			worldService.put(ns, new Service(ns));
		});
	}

	public static void loadServiceList() {
		servicesList = Collections.synchronizedList(new ArrayList<>());

		Map<String, List<String>> csv = CsvProcessors.ReadAsaHash(ProjectLoader.getServiceMetadata());
		String label = csv.keySet().contains("Label") ? "Label" : "Name";
		servicesList = csv.get(label);
		if (csv.keySet().contains("Category")) {
			for (int i = 0; i < csv.get(label).size(); i++) {
				categories.put(csv.get(label).get(i), csv.get("Category").get(i));
			}
		}

		if (csv.keySet().contains("Penalise_Oversupply")) {
			for (int i = 0; i < csv.get(label).size(); i++) {
				Penalise_Oversupply.put(csv.get(label).get(i),
						!csv.get("Penalise_Oversupply").get(i).equalsIgnoreCase("No"));
			}
		} else {
			servicesList.forEach(serviceName -> Penalise_Oversupply.put(serviceName, true));
		}

		marketGood.clear();
		boolean checkMarketGood = ConfigLoader.config != null && ConfigLoader.config.use_self_consumption;
		if (csv.keySet().contains("Market_good")) {
			for (int i = 0; i < csv.get(label).size(); i++) {
				String raw = csv.get("Market_good").get(i);
				Boolean isMarketGood = parseMarketGood(raw);
				if (isMarketGood == null && checkMarketGood) {
					LOGGER.fatal("Service '" + csv.get(label).get(i) + "' has Market_good '"
							+ (raw == null ? "" : raw.trim()) + "' in Services.csv; it must be Yes or No");
				}
				marketGood.put(csv.get(label).get(i), Boolean.TRUE.equals(isMarketGood));
			}
		} else if (checkMarketGood) {
			LOGGER.fatal("use_self_consumption is true but 'Market_good' column is missing from Services.csv");
		}
		LOGGER.info("Services size=  " + servicesList.size() + " =>" + servicesList);
		LOGGER.info("Services Penalise_Oversupply =>  " + Penalise_Oversupply);
		if (!marketGood.isEmpty()) {
			LOGGER.info("Services Market_good =>  " + marketGood);
		}
	}

	/**
	 * One Services.csv Market_good cell. Kept separate because LOGGER.fatal exits the JVM and so cannot
	 * be reached from a test.
	 *
	 * @return TRUE for Yes, FALSE for No (any case, spaces ignored), null for anything else, blank included
	 */
	static Boolean parseMarketGood(String raw) {
		String val = raw == null ? "" : raw.trim();
		if (val.equalsIgnoreCase("Yes")) {
			return Boolean.TRUE;
		}
		if (val.equalsIgnoreCase("No")) {
			return Boolean.FALSE;
		}
		return null;
	}

	public static boolean isRegionalServicesExisting() {
		System.out.println("!-Regions-! " + CellsLoader.regions.keySet());
		for (String r : CellsLoader.regions.keySet()) {
			ArrayList<Path> paths = PathTools.fileFilter(ProjectLoader.getScenario(), PathTools.asFolder("demand"),
					"_" + r + ".csv");
			LOGGER.info("! " + r + " DEMAND PATH:--> " + paths);
			if (paths == null) {
				LOGGER.error("Demand file not fund, for Region |" + r + "|. Regionalisation Not Possible");
				return false;
			}
		}
		return true;
	}

	public static List<String> getServicesList() {
		return servicesList;
	}

	public static void serviceupdater() {
		ServiceDemandLoader.updateRegionsDemand();
		ServiceWeightLoader.updateRegionsWeight();
		ServiceDemandLoader.aggregateRegionalToWorldServiceDemand();
	}

	public static ConcurrentHashMap<String, Boolean> getPenalise_Oversupply() {
		return Penalise_Oversupply;
	}

	/** Whether a service is a market good (Market_good = Yes); false when Services.csv has no such column. */
	public static boolean isMarketGood(String serviceName) {
		return marketGood.getOrDefault(serviceName, false);
	}

	/** Market_good per service; empty when Services.csv has no Market_good column. */
	public static ConcurrentHashMap<String, Boolean> getMarketGood() {
		return marketGood;
	}

}
