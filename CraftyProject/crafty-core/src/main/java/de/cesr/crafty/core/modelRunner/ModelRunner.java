package de.cesr.crafty.core.modelRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.output.Listener;
import de.cesr.crafty.core.output.Tracker;
import de.cesr.crafty.core.updaters.AftsUpdater;
import de.cesr.crafty.core.updaters.CapitalUpdater;
import de.cesr.crafty.core.updaters.Capital_Degradation_Updater;
import de.cesr.crafty.core.updaters.CellBehaviourUpdater;
import de.cesr.crafty.core.updaters.FlagUpdater;
import de.cesr.crafty.core.updaters.ProductionCostUpdater;
import de.cesr.crafty.core.updaters.SubsidyUpdater;
import de.cesr.crafty.core.updaters.LandMaskUpdater;
import de.cesr.crafty.core.updaters.RegionsModelRunnerUpdater;
import de.cesr.crafty.core.updaters.ServicesUpdater;
import de.cesr.crafty.core.updaters.SupplyUpdater;
import de.cesr.crafty.core.updaters.Timestep;
import de.cesr.crafty.core.utils.file.PathTools;
import de.cesr.crafty.core.utils.graphics.ChartExporter;
import de.cesr.crafty.core.utils.analysis.LandscapeFragmentationListener;
/**
 * Main CRAFTY model runner.
 *
 * This class assembles and executes the full simulation workflow by:
 * - Initializing scenario context and core datasets (services, capitals, AFTs, cells).
 * - Building the ordered schedule of {@link de.cesr.crafty.core.modelRunner.ModelState} components
 *   that run at every simulation step/year.
 * - Running the step loop from {@link Timestep#getStartYear()} to {@link Timestep#getEndtYear()}.
 * - Optionally performing an initial demand–supply equilibrium calibration and exporting outputs.
 *
 * High-level lifecycle:
 * 1) {@link #start()}:
 *    - Sets scenario and loads service metadata.
 *    - Instantiates the main updaters/loaders (capitals, AFTs, cells) and performs a first capital update.
 *    - Builds the schedule in a fixed order that defines the model execution pipeline per year.
 *
 * 2) {@link #initialzeRun()}:
 *    - Creates/derives the output directory structure.
 *    - Optionally redirects logs to a file in the output folder.
 *    - Exports the resolved configuration snapshot to disk.
 *    - Optionally performs initial demand–supply equilibrium calibration.
 *
 3) {@link #run()}:
 *   - Executes {@link AbstractModelRunner#step()} once per year.
 *   - Exports post-run charts as PNG when configured.
 *
 * Scheduling / execution order:
 * The order in {@link #start()} is intentional. Each component may depend on outputs of earlier components,
 * e.g.:
 * - {@link ServicesUpdater} updates demands/weights/taxes (inputs to utility and competitiveness).
 * - {@link CapitalUpdater} and {@link Capital_Degradation_Updater} affect capitals used in productivity and competitiveness.
 * - {@link AftsUpdater} updates AFT production/behaviour and land taxes/subsidies.
 * - {@link SupplyUpdater} and {@link Listener} compute and persist outputs derived from current state.
 * - {@link RegionsModelRunnerUpdater} executes regional model logic for the current year.
 * - {@link Timestep} advances the simulation year counter.
 *
 * Initial demand–supply equilibrium calibration:
 * If enabled, {@link #demandEquilibrium()} computes a calibration factor per service (and per region),
 * based on baseline supply and initial demands, and then rescales demands by dividing by that factor.
 * Services with zero initial supply are tracked, and their calibration factors are replaced with the
 * across-region average for that service.
 *
 * Outputs:
 * - The model writes CSV outputs through {@link Listener} / {@link ListenerByRegion}
 *   and optionally supply tracking via {@link Tracker}.
 * - At the end of the run, charts may be exported to a "plots" folder using {@link ChartExporter}.
 *
 * Notes / assumptions:
 * - This runner keeps references to core components as static fields to allow access from other parts
 *   of the code base. This design assumes a single active run at a time.
 * - {@link #start()} clears and rebuilds the schedule explicitly via {@link #getScheduled()} to enforce
 *   deterministic execution order.
 */
/**
 * @author Mohamed Byari
 *
 */

public class ModelRunner extends AbstractModelRunner {

    private static final CustomLogger LOGGER = new CustomLogger(ModelRunner.class);

    /**
     * CRAFTY-react's yearly step, in the crafty-react module. It is named rather than
     * imported: crafty-react depends on crafty-core, so it is built after core and
     * core's source cannot refer to its classes.
     */
    public static final String REACTIVE_UPDATER_CLASS = "de.cesr.crafty.react.ReactiveUpdater";

    public static CellsLoader cellsSet;
    public static CapitalUpdater capitalUpdater;
    public static AftsUpdater aftsUpdater;
    public static ProductionCostUpdater productionCostUpdater;
    public static SubsidyUpdater subsidyUpdater;
    static Capital_Degradation_Updater capital_Degradation_Updater;
    public RegionsModelRunnerUpdater regionsModelRunnerUpdater;
    private FlagUpdater flagUpdater;
    private CellBehaviourUpdater cellBehaviourUpdater;
    private LandMaskUpdater landMaskUpdater;
    private final List<ModelState> initialStateUpdaters = new ArrayList<>();
    private boolean initialStatePrepared;

    public void start() {

        ServiceSet.loadServiceList();
        capitalUpdater = new CapitalUpdater();
        aftsUpdater = new AftsUpdater();
        cellsSet = new CellsLoader();
        capitalUpdater.step();
        productionCostUpdater = new ProductionCostUpdater();
        subsidyUpdater = new SubsidyUpdater();
        capital_Degradation_Updater = new Capital_Degradation_Updater();
        regionsModelRunnerUpdater = new RegionsModelRunnerUpdater();
        flagUpdater = new FlagUpdater();
        cellBehaviourUpdater = new CellBehaviourUpdater();
        landMaskUpdater = new LandMaskUpdater();
        // This list is both the set of year-zero updaters and the order
        // prepareInitialState() runs them in.
        initialStateUpdaters.clear();
        initialStateUpdaters.add(flagUpdater);
        initialStateUpdaters.add(capitalUpdater);
        initialStateUpdaters.add(productionCostUpdater);
        initialStateUpdaters.add(subsidyUpdater);
        initialStateUpdaters.add(aftsUpdater);
        initialStateUpdaters.add(cellBehaviourUpdater);
        initialStateUpdaters.add(landMaskUpdater);
        initialStateUpdaters.add(capital_Degradation_Updater);
        initialStatePrepared = false;
        getScheduled().clear();
        getScheduled().add(flagUpdater);
        getScheduled().add(new ServicesUpdater());
        getScheduled().add(capitalUpdater);
        getScheduled().add(productionCostUpdater);
        getScheduled().add(subsidyUpdater);
        getScheduled().add(aftsUpdater);
        getScheduled().add(cellBehaviourUpdater);
        getScheduled().add(landMaskUpdater);
        getScheduled().add(capital_Degradation_Updater);
        getScheduled().add(new SupplyUpdater());
        getScheduled().add(new Listener());
        if (ConfigLoader.config.generate_land_fragmentation_output) {
            getScheduled().add(new LandscapeFragmentationListener());
        }
        getScheduled().add(new Tracker());
        getScheduled().add(regionsModelRunnerUpdater);
        getScheduled().add(new Timestep());

        if (ConfigLoader.isReactiveAfts()) {
            addBeforeCapitalUpdater(loadReactiveUpdater());
        }
    }

    /**
     * Creates CRAFTY-react's yearly step, stopping the run if the crafty-react
     * module is not on the classpath.
     */
    private static ModelState loadReactiveUpdater() {
        try {
            return createStepByClassName(REACTIVE_UPDATER_CLASS);
        } catch (ClassNotFoundException e) {
            LOGGER.fatal("reactive_afts is true but the crafty-react module is not on the classpath, so CRAFTY-react "
                    + "cannot run. Run CRAFTY from the crafty-react jar, or set reactive_afts: false.");
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOGGER.fatal("Could not create CRAFTY-react's step " + REACTIVE_UPDATER_CLASS + ": " + e);
        }
        return null;
    }

    /**
     * Creates a step from its class name, found when the model runs rather than when
     * core is compiled. The class must be a ModelState with a no-argument constructor.
     */
    static ModelState createStepByClassName(String className) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        if (!ModelState.class.isAssignableFrom(type)) {
            throw new ClassCastException(className + " is not a ModelState");
        }
        return (ModelState) type.getDeclaredConstructor().newInstance();
    }

    /**
     * Runs the step directly before CapitalUpdater, both every year and in the
     * year-zero initial state, so it can write each year's capitals and cost files
     * before they are read. The same instance goes in both lists, which is what lets
     * the first yearly step skip it along with the other year-zero updaters.
     */
    void addBeforeCapitalUpdater(ModelState step) {
        getScheduled().add(getScheduled().indexOf(capitalUpdater), step);
        initialStateUpdaters.add(initialStateUpdaters.indexOf(capitalUpdater), step);
    }

    public void initialzeRun() {

        PathTools.writeFile(ConfigLoader.config.output_folder_name + File.separator + "config.txt",
                Listener.exportConfigurationFile(), false);

        prepareInitialState();
        RegionsModelRunnerUpdater.regionsModelRunner.values().forEach(r -> r.regionalSupply());

        if (ConfigLoader.config.initial_demand_supply_equilibrium) {
            InitialDSEquilibriumManager.demandEquilibrium();
        }
        initialStatePrepared = true;
    }

    /**
     * Applies every year-zero input that can affect service supply before the
     * initial demand calibration is calculated.
     */
    private void prepareInitialState() {
        for (ModelState updater : initialStateUpdaters) {
            updater.step();
        }
    }

    /**
     * The year-zero input updaters have already run during initialization. Skip
     * them once so masks, adjustments and external hooks are not applied twice.
     */
    @Override
    public void step() {
        if (!initialStatePrepared) {
            super.step();
            return;
        }

        List<ModelState> fullSchedule = new ArrayList<>(getScheduled());
        getScheduled().removeAll(initialStateUpdaters);
        try {
            super.step();
            initialStatePrepared = false;
        } finally {
            getScheduled().clear();
            getScheduled().addAll(fullSchedule);
        }
    }

    public void run() {
        try {
            for (int i = Timestep.getStartYear(); i <= Timestep.getEndtYear(); i++) {
                step();
            }

            Listener.flushFinalYearLandUseCounter();
            exportChartsPlots();
        } finally {
            CustomLogger.shutdownRunFileLoggers();
            LogManager.shutdown();
        }
    }

    public static void exportChartsPlots() {
        if (ConfigLoader.config.generate_chart_plots_png) {
            String path = PathTools.makeDirectory(ConfigLoader.config.output_folder_name + File.separator + "plots");
            Listener.servicedemandHash.forEach((serviceName, serviceHash) -> {
                ChartExporter.createAndSaveChartAsPNG(serviceHash, Timestep.getStartYear(), serviceName,
                        path + File.separator + serviceName);
            });
            Map<String, String> hashColors = new HashMap<>();
            AFTsLoader.getAftHash().forEach((name, aft) -> {
                hashColors.put(name, aft.getColor());
            });

            ChartExporter.createAndSaveChartAsPNG(Listener.compositionAftHash, hashColors, Timestep.getStartYear(),
                    "LandUseTrends", path + File.separator + "Land_use_trends");
        }
    }

}
