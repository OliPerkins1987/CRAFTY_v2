package de.cesr.crafty.react;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Cell;
import de.cesr.crafty.core.modelRunner.ModelRunner;
import de.cesr.crafty.core.updaters.AbstractUpdater;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.react.data.CoreFacts;
import de.cesr.crafty.react.data.ReactConfigLoader;
import de.cesr.crafty.react.data.ReactInputException;
import de.cesr.crafty.react.data.ReactInputs;
import de.cesr.crafty.react.data.ReactRunContext;
import de.cesr.crafty.react.data.ReactYearData;
import de.cesr.crafty.react.decisions.CropDecisions;
import de.cesr.crafty.react.decisions.CropManagement;
import de.cesr.crafty.react.decisions.DecisionUnits;
import de.cesr.crafty.react.decisions.ForestryDecisions;
import de.cesr.crafty.react.decisions.ForestryManagement;
import de.cesr.crafty.react.decisions.PastureDecisions;
import de.cesr.crafty.react.decisions.PastureManagement;
import de.cesr.crafty.react.decisions.YearPrices;
import de.cesr.crafty.react.output.ReactOutputs;
import de.cesr.crafty.react.science.CropSurfaces;

/**
 * The yearly CRAFTY-react step, and the one class crafty-core knows about.
 *
 * When reactive_afts is true, crafty-core creates this class by name
 * ({@link ModelRunner#REACTIVE_UPDATER_CLASS}) and runs it directly after
 * ProductionCostUpdater, every year and for year zero. By then the year's
 * capitals and cost files have been loaded into the cells.
 *
 * Each year it loads that year's data and runs react's stages on it, in order:
 * the cropland decisions ({@link CropDecisions}), the pasture decisions
 * ({@link PastureDecisions}), then the forestry decisions
 * ({@link ForestryDecisions}). It logs one line for the year and one per AFT,
 * and writes the inspection files switched on in react_config.yaml
 * ({@link ReactOutputs}).
 *
 * Then it hands the decisions to the model ({@link ReactHandover}): each
 * reactive AFT's {@code _suit} capital and the costs of the elements that are
 * on go straight into the cells, over what core has just loaded.
 */
public class ReactiveUpdater extends AbstractUpdater {

	private static final CustomLogger LOGGER = new CustomLogger(ReactiveUpdater.class);

	/** React's inputs: the checked project, and the year being simulated. */
	private final ReactInputs inputs;

	/** Where react makes its decisions: built once, from the cell key. */
	private final DecisionUnits units;

	/** The cropland stage, which carries each AFT's N from year to year. */
	private final CropDecisions crops;

	/** The pasture stage, which carries each AFT's stocking rate from year to year. */
	private final PastureDecisions pasture;

	/** The forestry stage, which carries each Prospect AFT's rotation from year to year. */
	private final ForestryDecisions forestry;

	/** The inspection files, as react_config.yaml asks for them. */
	private final ReactOutputs outputs;

	/** Where react's values go: the model's cells, each with its decision unit. */
	private final ReactHandover handover;

	/**
	 * The last year decided. The model skips this step in the first scheduled year, because it already ran
	 * for year zero; this also stops a repeated year being decided twice, so the spin-up happens once. A
	 * repeated year is still handed over again.
	 */
	private Integer lastYearDecided = null;

	/**
	 * The constructor crafty-core calls, at the end of {@code ModelRunner.start()}.
	 *
	 * It reads react's own settings, gathers what react needs from core, and runs the startup checks.
	 * Everything the checks need - services, AFT metadata, cells, the years, the spatial cost files - has
	 * been loaded by this point. A project react cannot use stops the run here, before any year is
	 * simulated. (The log files are not open yet, so that message reaches the console only.)
	 */
	public ReactiveUpdater() {
		this(createInputs());
	}

	/** With react's stages built from its inputs, handing over to the model's cells. */
	ReactiveUpdater(ReactInputs inputs) {
		this(inputs, CoreFacts.cells());
	}

	/** With react's stages built from its inputs, handing over to the cells given. */
	ReactiveUpdater(ReactInputs inputs, Map<String, Cell> cells) {
		this(inputs, DecisionUnits.build(inputs.checked().cellKey(), inputs.context().regions()), cells);
	}

	private ReactiveUpdater(ReactInputs inputs, DecisionUnits units, Map<String, Cell> cells) {
		this(inputs, units, CropDecisions.create(inputs, units), PastureDecisions.create(inputs, units),
				ForestryDecisions.create(inputs, units), cells);
	}

	/** With react's stages and cells passed in, so that a test can choose them. */
	ReactiveUpdater(ReactInputs inputs, DecisionUnits units, CropDecisions crops, PastureDecisions pasture,
			ForestryDecisions forestry, Map<String, Cell> cells) {
		this.inputs = inputs;
		this.units = units;
		this.crops = crops;
		this.pasture = pasture;
		this.forestry = forestry;
		this.outputs = ReactOutputs.create(inputs, units);
		this.handover = ReactHandover.build(cells, units);
	}

	/** Loads react's settings and checks the project, stopping the run if it cannot be used. */
	private static ReactInputs createInputs() {
		ReactRunContext context = CoreFacts.fromCore();
		try {
			return ReactInputs.create(ReactConfigLoader.load(context.projectPath()), context);
		} catch (ReactInputException e) {
			LOGGER.fatal(e.getMessage());
			return null; // not reached: LOGGER.fatal stops the run
		}
	}

	@Override
	public void toSchedule() {
		modelRunner.scheduleRepeating(this);
	}

	/**
	 * Decides the year, once, and hands it to the model. The handover happens every time, because core
	 * reloads the cells' capitals and costs each year before this step.
	 */
	@Override
	public void step() {
		int year = Timestep.getCurrentYear();
		if (lastYearDecided == null || lastYearDecided != year) {
			try {
				decideYear(year);
			} catch (ReactInputException e) {
				LOGGER.fatal(e.getMessage());
			}
			lastYearDecided = year;
		}
		handOver(year);
	}

	/**
	 * Puts the last year decided into the model's cells, with one log line. With nothing decided (no element
	 * on), nothing is handed over.
	 */
	void handOver(int year) {
		Collection<CropManagement> cropsDecided = crops.managements().values();
		Collection<PastureManagement> pastureDecided = pasture.managements().values();
		Collection<ForestryManagement> forestryDecided = forestry.managements().values();
		if (cropsDecided.isEmpty() && pastureDecided.isEmpty() && forestryDecided.isEmpty()) {
			return;
		}
		long start = System.nanoTime();
		ReactHandover.Counts counts = handover.write(cropsDecided, pastureDecided, forestryDecided);
		LOGGER.info(String.format("CRAFTY-react handed year %d to the model in %.2f s: %d _suit capitals and %d cost"
				+ " columns in %d cells", year, (System.nanoTime() - start) / 1e9, counts.suitCapitals(),
				counts.costColumns(), counts.cells()));
	}

	/**
	 * Loads a year (the year before is released) and runs react's stages on it: the cropland decisions, the
	 * pasture decisions, then the forestry decisions. A stage with no AFT to decide (none of its elements on)
	 * is skipped; with none to decide, only the data is loaded.
	 *
	 * @throws ReactInputException if the year cannot be decided, for example when a service has no price
	 */
	void decideYear(int year) {
		ReactYearData data = inputs.forYear(year);
		boolean decideCrops = !crops.managements().isEmpty();
		boolean decidePasture = !pasture.managements().isEmpty();
		boolean decideForestry = !forestry.managements().isEmpty();
		if (!decideCrops && !decidePasture && !decideForestry) {
			return;
		}
		long start = System.nanoTime();
		// The stages decide the same years, so any one of them says whether this is the spin-up.
		Integer lastYearDecided = decideCrops ? crops.lastYear()
				: decidePasture ? pasture.lastYear() : forestry.lastYear();
		int steps = lastYearDecided == null ? inputs.config().spinupIterations() : 1;
		CropSurfaces surfaces = null;
		String fitted = "";
		if (decideCrops) {
			surfaces = CropSurfaces.fit(data);
			fitted = String.format("surfaces fitted in %.2f s; ", (System.nanoTime() - start) / 1e9);
		}
		// One look-up a year, for the services the three stages need.
		Set<String> services = new LinkedHashSet<>(crops.servicesNeedingPrices());
		services.addAll(pasture.servicesNeedingPrices());
		services.addAll(forestry.servicesNeedingPrices());
		YearPrices prices = YearPrices.forYear(year, services, units, inputs.context().prices());
		Map<String, CropManagement> cropsDecided = decideCrops ? crops.decide(data, surfaces, prices) : Map.of();
		Map<String, PastureManagement> pastureDecided = decidePasture ? pasture.decide(data, prices) : Map.of();
		Map<String, ForestryManagement> forestryDecided = decideForestry ? forestry.decide(data, prices) : Map.of();

		LOGGER.info(String.format("CRAFTY-react decided year %d in %.2f s (%s%d %s): %d crops AFT(s), %d pasture"
				+ " AFT(s) and %d forestry AFT(s) in %d decision units", year, (System.nanoTime() - start) / 1e9, fitted,
				steps, steps == 1 ? "step" : "spin-up steps", cropsDecided.size(), pastureDecided.size(),
				forestryDecided.size(), units.size()));
		for (CropManagement management : cropsDecided.values()) {
			LOGGER.info("CRAFTY-react " + year + " " + management.summary());
		}
		for (PastureManagement management : pastureDecided.values()) {
			LOGGER.info("CRAFTY-react " + year + " " + management.summary());
		}
		for (ForestryManagement management : forestryDecided.values()) {
			LOGGER.info("CRAFTY-react " + year + " " + management.summary());
		}

		if (outputs.writes(year)) {
			long writing = System.nanoTime();
			List<Path> files = outputs.write(data, surfaces, prices, cropsDecided.values(), pastureDecided.values(),
					forestryDecided.values());
			LOGGER.info(String.format("CRAFTY-react wrote %d inspection file(s) for %d to %s in %.2f s", files.size(),
					year, outputs.folder(), (System.nanoTime() - writing) / 1e9));
		}
	}

	/** React's inputs, once the checks have passed. */
	public ReactInputs getInputs() {
		return inputs;
	}

	/** The cropland stage, holding each crops AFT's management for the last year decided. */
	public CropDecisions getCropDecisions() {
		return crops;
	}

	/** The pasture stage, holding each pasture AFT's management for the last year decided. */
	public PastureDecisions getPastureDecisions() {
		return pasture;
	}

	/** The forestry stage, holding each forestry AFT's management for the last year decided. */
	public ForestryDecisions getForestryDecisions() {
		return forestry;
	}
}
