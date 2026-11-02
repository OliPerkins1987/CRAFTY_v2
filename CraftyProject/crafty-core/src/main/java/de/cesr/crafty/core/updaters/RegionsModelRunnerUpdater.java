package de.cesr.crafty.core.updaters;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.RegionalModelRunner;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.afts.AftCategorised;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;

/**
 * Scheduler component responsible for advancing the regional sub-models each simulation tick.
 *
 * This updater owns a {@code RegionalModelRunner} instance per {@link de.cesr.crafty.core.crafty.Region}.
 * At construction time, it creates and stores these runners in {@link #regionsModelRunner}, using the
 * region names currently available in {@link CellsLoader#regions}. With use_price_feedback and
 * global_price_feedback over 2 or more regions, it then gives every runner the same price multiplier map,
 * so there is one world price (see {@link RegionalModelRunner#useGlobalPrice}).
 *
 * On every tick/year ({@link #step()}):
 * - It calls {@link RegionalModelRunner#step(int)} for each region using the current simulation year
 *   from {@link Timestep#getCurrentYear()}.
 * - It refreshes global and per-region AFT occupancy counters via
 *   {@link AFTsLoader#hashAgentNbrRegions()} and {@link AFTsLoader#hashAgentNbr()} so other components
 *   (e.g., listeners, taxes/subsidies, calibration, reporting) can use up-to-date distributions.
 *
 * Notes / assumptions:
 * - This class assumes {@link CellsLoader#regions} has already been initialized before instantiation.
 *   If regions are rebuilt at runtime, {@link #regionsModelRunner} would need to be rebuilt accordingly.
 * - Runners are stored in a {@link ConcurrentHashMap} to allow safe concurrent reads; the updater itself
 *   iterates sequentially, while each {@link RegionalModelRunner} may internally use parallel operations.
 */

/**
 * @author Mohamed Byari
 *
 */

public class RegionsModelRunnerUpdater extends AbstractUpdater {
	private static final CustomLogger LOGGER = new CustomLogger(RegionsModelRunnerUpdater.class);

	public static ConcurrentHashMap<String, RegionalModelRunner> regionsModelRunner;

	private Runnable step = this::doStep;

	public RegionsModelRunnerUpdater() {
		regionsModelRunner = new ConcurrentHashMap<>();
		CellsLoader.regions.keySet().forEach(regionName -> {
			regionsModelRunner.put(regionName, new RegionalModelRunner(regionName));
		});
		shareGlobalPrice();
	}

	/**
	 * global_price_feedback: with 2 or more regions, every runner gets the same price multiplier map and the
	 * same set of services already warned about, so price feedback sets one world price that every region
	 * reads. With one region, that region's demand and supply are the world's, so it keeps its own.
	 */
	private static void shareGlobalPrice() {
		if (ConfigLoader.config == null || !ConfigLoader.config.use_price_feedback
				|| !ConfigLoader.config.global_price_feedback) {
			return;
		}
		if (regionsModelRunner.size() < 2) {
			LOGGER.info("global_price_feedback is on, but the model has one region, so price feedback uses that "
					+ "region's demand and supply");
			return;
		}
		ConcurrentHashMap<String, Double> sharedPriceMultiplier = new ConcurrentHashMap<>();
		Set<String> sharedZeroDemandWarned = ConcurrentHashMap.newKeySet();
		regionsModelRunner.values()
				.forEach(runner -> runner.useGlobalPrice(sharedPriceMultiplier, sharedZeroDemandWarned));
		LOGGER.info("Price feedback is global: one multiplier per service from world demand and supply over "
				+ regionsModelRunner.size() + " regions");
	}

	@Override
	public void toSchedule() {
		modelRunner.scheduleRepeating(this);
	}

	@Override
	public void step() {
		boolean behaviourModelActive = AftCategorised.useCategorisationGivIn
				&& CellBehaviourUpdater.behaviourUsed;
		String parameterYear = CellBehaviourUpdater.getLoadedParameterYear() == null ? "none"
				: CellBehaviourUpdater.getLoadedParameterYear().toString();
		LOGGER.info("Behaviour model active: " + behaviourModelActive + " (simulation year "
				+ Timestep.getCurrentYear() + ", parameter year " + parameterYear + ").");
		step.run();
		AFTsLoader.hashAgentNbrRegions();
		AFTsLoader.hashAgentNbr();
	}

	private void doStep() {
		regionsModelRunner.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue)
				.forEach(RegionalRunner -> {
			RegionalRunner.step();
		});
	}

	public void setStep(Runnable step) {
		this.step = (step != null) ? step : this::doStep;
	}
//			RegionalRunner.setCellsUtilities(() -> {
//			System.out.println("change the cells utilities ");
//		});
}
