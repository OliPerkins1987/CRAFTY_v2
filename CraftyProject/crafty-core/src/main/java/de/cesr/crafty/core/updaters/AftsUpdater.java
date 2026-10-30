package de.cesr.crafty.core.updaters;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.ManagerTypes;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.dataLoader.CsvProcessors;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.afts.AftGiveInMatrix;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.utils.file.CsvTools;
import de.cesr.crafty.core.utils.file.PathTools;
import de.cesr.crafty.core.utils.general.Utils;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

/**
 * Scheduled updater responsible for time-varying AFT production and behaviour parameters.
 *
 * This updater is executed once per model step (typically per simulated year) and applies any parameter
 * changes defined in the input directories. It supports:
 * - Updating AFT production parameters (productivity levels and sensitivity matrix).
 * - Updating AFT behavioural parameters (give-in/give-up distributions, probabilities).
 * - Switching each AFT's give-in matrix row to the year's matrix ({@link AftGiveInMatrix}).
 *
 * Update logic:
 * - Parameter file paths are pre-resolved by {@link AFTsLoader} into maps keyed by AFT label and
 *   scenario/year selectors (e.g., "{scenario}|{year}" or "default_production|{year}").
 * - At each step, {@link #updateProduction(String)} checks whether a year-specific file exists for the
 *   current year and applies it to the active AFTs only.
 *
 * Input formats:
 * - Production files are read as a simple table; rows are services, and the "Production" column defines
 *   the productivity level for each service. The sensitivity matrix is loaded from the same file and
 *   populates {@code service -> (capital -> exponent)} in {@link Aft#getSensByService()}. The value in a
 *   service's *_trade column is also the AFT's transport cost multiplier for that service.
 * - Behaviour files are read as a key/value style CSV and populate giving-in/up distribution parameters
 *   and noise ranges.
 *
 * Notes:
 * - Production and sensitivity updates use the global service and capital lists ({@link ServiceSet} and
 *   {@link CapitalUpdater}).
 * - This updater expects model time to be configured via {@link Timestep} and scenario via {@link ProjectLoader}.
 */
/**
 * @author Mohamed Byari
 *
 */
public class AftsUpdater extends AbstractUpdater {
	private static final CustomLogger LOGGER = new CustomLogger(AftsUpdater.class);
	public AFTsLoader AFtsSet;

	public AftsUpdater() {
		AFtsSet = new AFTsLoader();
	}

	@Override
	public void toSchedule() {
		modelRunner.scheduleRepeating(this);
	}

	@Override
	public void step() {
		updatePorB("production");
		updatePorB("agents");
		AftGiveInMatrix.updateForYear(Timestep.getCurrentYear());
		updateAftCapitalAdjustmentValues();

	}

	private void updateAftCapitalAdjustmentValues() {
		// Clear capital adjustments for each AFT.
		// Find if there is any file for capital adjusments in YAML
		// if No,check the default
		// if No, ignore -> for all afts -> capital_adjustments=null
		// if yes, read the path and assosiete each AFt with the capitals adjsument
		AFTsLoader.getActivateAFTsHash().values().forEach(a -> {
			a.getCapital_adjustments().clear();
		});

		Path path = null;
		ArrayList<Path> directory;
		if (Paths.get(ConfigLoader.config.aft_capital_adjustments_directory).toFile().isDirectory()) {
			directory = PathTools.findAllFilePaths(Paths.get(ConfigLoader.config.aft_capital_adjustments_directory));
		} else {
			directory = PathTools.fileFilter("AFT_capital_adjustments", ProjectLoader.getScenario(),
					Timestep.getCurrentYear() + ".csv");
		}
		if (directory != null) {
			ArrayList<Path> ps = PathTools.fileFilter(directory, Timestep.getCurrentYear() + ".csv");
			if (ps != null && !ps.isEmpty()) {
				path = ps.get(0);
			}
		}
		if (path != null) {
			// Effectively-final copy so the warning inside the lambda below can name the file.
			final Path adjustmentsFile = path;
			Map<String, Map<String, String>> csv = CsvTools.csvToColumnRowValue(path);
			csv.forEach((aftName, capitals) -> {
				capitals.forEach((capitalName, value) -> {

					if (AFTsLoader.getActivateAFTsHash().keySet().contains(aftName)
							&& CapitalUpdater.getCapitalsList().contains(capitalName)) {
						AFTsLoader.getActivateAFTsHash().get(aftName).getCapital_adjustments().put(capitalName,
								Utils.sToD(value));
					} else {
						LOGGER.warn("Capital adjustment entry ignored: AFT '" + aftName + "' / capital '"
								+ capitalName + "' in " + adjustmentsFile
								+ " does not match any active AFT and known capital");
					}
				});
			});
			adjust_cell_capitals();
		}
	}

	public static void adjust_cell_capitals() {
		LOGGER.info("adjust_cell_capitals");
		CellsLoader.hashCell.values().parallelStream().forEach(c -> {
			Aft a = c.getOwner();
			if (a != null) {
				if (!a.getCapital_adjustments().isEmpty()) {
					CapitalUpdater.getCapitalsList().forEach(cn -> {
						if (a.getCapital_adjustments().get(cn) != null) {
							c.getCapitals().put(cn,
									Math.max(c.getCapitals().get(cn) * (1 + a.getCapital_adjustments().get(cn)), 0));
						}
					});
				}
			}
		});
	}

	private void updatePorB(String pORb) {
		AFTsLoader.getActivateAFTsHash().forEach((aftName, aft) -> {
			if (aft.getType() != ManagerTypes.Abandoned) {
				Path p = AFTsLoader.getPath(pORb, aftName);
				if (p != null) {
					if (pORb.equals("production")) {
						updateAFTProduction(aft, p);
					} else {
						updateAFTBehaviour(aft, p);
					}
					LOGGER.info("Update " + pORb + " parameters for AFT: " + aftName + " using: " + p);
				} else {
					LOGGER.trace("AFT " + pORb + " parameters not updated (no folder found) for: " + aftName);
				}
			}
		});
	}

	public static void updateAFTProduction(Aft a, Path path) {
		String[][] m = CsvTools.csvReader(path);
		for (int i = 0; i < m.length; i++) {
			if (!m[i][0].isBlank()) {
				if (ServiceSet.getServicesList().contains(m[i][0])) {
					a.getProductivityLevel().put(m[i][0], Utils.sToD(m[i][Utils.indexof("Production", m[0])]));
				} else {
					LOGGER.warn("(" + m[i][0] + ")  is not exist in Services List, will be ignored");
				}
			}
		}
		updateSensitivty(a, path);
		updateTradeFactors(a);
	}

	/** The suffix that marks a trade capital, e.g. {@code c3_cereals_trade}. */
	public static final String TRADE_SUFFIX = "_trade";

	/*
	 * Each AFT's transport cost multiplier per service, from the *_trade capital on that service's row.
	 * The capital names don't follow the service names (c3_cereals_trade for C3cereals), so the factor
	 * is whichever *_trade column is non-zero on the row. More than one is ambiguous, and stops the run
	 * wherever the factor is charged on (see tradeFactorIsCharged).
	 */
	static void updateTradeFactors(Aft a) {
		List<String> services = ServiceSet.getServicesList();
		double[] factors = new double[services.size()];
		for (int i = 0; i < services.size(); i++) {
			String service = services.get(i);
			Map<String, Double> exps = a.getSensByService().get(service);
			List<String> trade = nonZeroTradeCapitals(exps);
			if (trade.size() > 1 && tradeFactorIsCharged(service)) {
				LOGGER.fatal("AFT '" + a.getLabel() + "' has more than one non-zero trade capital " + trade
						+ " for '" + service + "' in its production file; transport costs need one");
			}
			for (String capital : trade) {
				factors[i] += exps.get(capital);
			}
		}
		a.setTradeFactors(factors);
	}

	/**
	 * Whether a service's trade factor is charged on: with use_transport_costs on, global production costs
	 * (with spatial ones, transport is charged on each region's export share instead) and a service that
	 * pays transport.
	 */
	static boolean tradeFactorIsCharged(String service) {
		return ConfigLoader.isUseTransportCosts() && !ConfigLoader.isTransportByExportShare()
				&& ProductionCostUpdater.paysTransport(service);
	}

	/** The *_trade capitals with a non-zero value in one service's sensitivities, sorted; empty for none. */
	static List<String> nonZeroTradeCapitals(Map<String, Double> exps) {
		List<String> trade = new ArrayList<>();
		if (exps == null) {
			return trade;
		}
		exps.forEach((capital, value) -> {
			if (capital.endsWith(TRADE_SUFFIX) && value != 0.0) {
				trade.add(capital);
			}
		});
		trade.sort(null);
		return trade;
	}

	private static void updateSensitivty(Aft a, Path path) {
//		LOGGER.info("updateSensitivty for: " + a.getLabel() + " using : " + path);
		try {
			CsvReadOptions options = CsvReadOptions.builder(path.toFile()).separator(',').build();
			Table T = Table.read().usingOptions(options);
			// Optional: assume first column contains service names
			var serviceCol = T.stringColumn(0);

			ServiceSet.getServicesList().forEach(Sn -> {
				int row = serviceCol.indexOf(Sn);
				if (row < 0) {
					System.out.println("Service not found in CSV: " + Sn);
					return;
				}

				a.getSensByService().putIfAbsent(Sn, new ConcurrentHashMap<>());

				CapitalUpdater.getCapitalsList().forEach(Cn -> {
					Object s = T.column(Cn).get(row);

					if (s instanceof Number n) {
						a.getSensByService().get(Sn).put(Cn, n.doubleValue());
					} else {
						System.out.println("ERROR reading Sensitivity value for service=" + Sn + " capital=" + Cn
								+ " (value=" + s + ", type=" + (s == null ? "null" : s.getClass().getName()) + ")");
					}
				});
			});

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void updateAFTBehaviour(Aft a, Path path) {
		Map<String, List<String>> reder = CsvProcessors.ReadAsaHash(path);
		a.setGiveInMean(Utils.sToD(reder.get("givingInDistributionMean").get(0)));
		a.setGiveUpMean(Utils.sToD(reder.get("givingUpDistributionMean").get(0)));
		a.setGiveInSD(Utils.sToD(reder.get("givingInDistributionSD").get(0)));
		a.setGiveUpSD(Utils.sToD(reder.get("givingUpDistributionSD").get(0)));
//		a.setServiceLevelNoiseMin(Utils.sToD(reder.get("serviceLevelNoiseMin").get(0)));
//		a.setServiceLevelNoiseMax(Utils.sToD(reder.get("serviceLevelNoiseMax").get(0)));
		a.setGiveUpProbabilty(Utils.sToD(reder.get("givingUpProb").get(0)));
	}

}
