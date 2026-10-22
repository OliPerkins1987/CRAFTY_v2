package de.cesr.crafty.core.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private String originalConfigPath;
    private Config originalConfig;

    private Config invokeLoadConfig()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = ConfigLoader.class.getDeclaredMethod("loadConfig");
        m.setAccessible(true);
        return (Config) m.invoke(null);
    }

    @AfterEach
    void restoreStatics() {
        // Restore any previous state so other tests are not affected
        ConfigLoader.configPath = originalConfigPath;
        ConfigLoader.config = originalConfig;
    }

    @Test
    void loadConfigShouldUseAbsoluteFilePathWhenConfigPathExists() throws Exception {
        // Backup previous state
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        // Arrange: create a temporary YAML file
        Path configFile = tempDir.resolve("config.yaml");
        // The content can't be empty; loadConfig handles null -> new Config()
//        Files.writeString(configFile, "");

        ConfigLoader.configPath = configFile.toString();

        // Act
        Config cfg = invokeLoadConfig();

        // Assert
        assertNotNull(cfg, "Config loaded from an existing configPath should not be null");
    }

    @Test
    void loadConfigShouldReadSelectionModeAndRandomSeed() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("seed-config.yaml");
        Files.writeString(configFile, "cell_selection: random\nrandom_seed: 987654321\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("random", cfg.cell_selection);
        assertEquals(987654321L, cfg.random_seed);
    }

    @Test
    void legacyRankSeedIdShouldMapToCellSelection() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("legacy-rank-seed.yaml");
        Files.writeString(configFile, "seedID: rank\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("rank", cfg.cell_selection);
        assertEquals(1L, cfg.random_seed);
    }

    @Test
    void legacyNumericSeedIdShouldMapToRandomSelectionAndSeed() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("legacy-numeric-seed.yaml");
        Files.writeString(configFile, "seedID: 1234\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("random", cfg.cell_selection);
        assertEquals(1234L, cfg.random_seed);
    }

    @Test
    void loadConfigShouldReadCanonicalBritishBehaviourKeys() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("behaviour-config.yaml");
        Files.writeString(configFile, """
                aft_behaviour_parameters_directory: /aft
                category_give_in_distributions_directory: /categories
                cell_behaviour_parameters_directory: /cells
                use_category_based_give_in: false
                use_cell_behaviour_model: false
                cell_behaviour_logistic_steepness: 4.5
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("/aft", cfg.aft_behaviour_parameters_directory);
        assertEquals("/categories", cfg.category_give_in_distributions_directory);
        assertEquals("/cells", cfg.cell_behaviour_parameters_directory);
        assertFalse(cfg.use_category_based_give_in);
        assertFalse(cfg.use_cell_behaviour_model);
        assertEquals(4.5, cfg.cell_behaviour_logistic_steepness);
    }

    @Test
    void loadConfigShouldMapLegacyBehaviourKeysToCanonicalFields() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("legacy-behaviour-config.yaml");
        Files.writeString(configFile, """
                aft_behevoir_directory: /legacy-aft
                categories_givingInDistribution: /legacy-categories
                Behevoir_Cells_directory: /legacy-cells
                use_AFTs_categories_GiveIn: false
                steepness_logistic_eq: 3.0
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("/legacy-aft", cfg.aft_behaviour_parameters_directory);
        assertEquals("/legacy-categories", cfg.category_give_in_distributions_directory);
        assertEquals("/legacy-cells", cfg.cell_behaviour_parameters_directory);
        assertFalse(cfg.use_category_based_give_in);
        assertEquals(3.0, cfg.cell_behaviour_logistic_steepness);
    }

    @Test
    void canonicalBehaviourKeyShouldWinOverLegacyAlias() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("mixed-behaviour-config.yaml");
        Files.writeString(configFile, """
                aft_behaviour_parameters_directory: /canonical
                aft_behevoir_directory: /legacy
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("/canonical", cfg.aft_behaviour_parameters_directory);
    }

    @Test
    void removedKeysShouldBeIgnoredWithoutInvalidatingConfig() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("removed-tax-config.yaml");
        Files.writeString(configFile, """
                service_taxes_and_subsidies_path: /services
                land_taxes_subsidies_path: /land
                consider_taxes_and_subsidies: true
                mutate_on_competition_win: true
                mutation_interval: 5
                generate_chart_plots_pdf: true
                generate_map_plots_tif: true
                random_seed: 42
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals(42L, cfg.random_seed);
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("service_taxes_and_subsidies_path"));
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("land_taxes_and_subsidies_path"));
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("consider_taxes_and_subsidies"));
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("mutate_on_competition_win"));
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("mutation_interval"));
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("generate_chart_plots_pdf"));
        assertThrows(NoSuchFieldException.class,
                () -> Config.class.getField("generate_map_plots_tif"));
    }

    @Test
    void allPublicConfigFieldsShouldUseCanonicalSnakeCase() {
        for (Field field : Config.class.getFields()) {
            assertTrue(field.getName().matches("[a-z][a-z0-9_]*"),
                    () -> "Non-canonical Config field: " + field.getName());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyLegacyAliasShouldTargetAnExistingCanonicalField() throws Exception {
        Field aliasesField = ConfigLoader.class.getDeclaredField("LEGACY_KEYS");
        aliasesField.setAccessible(true);
        Map<String, String> aliases = (Map<String, String>) aliasesField.get(null);

        assertFalse(aliases.isEmpty());
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            Field canonicalField = Config.class.getField(alias.getValue());
            assertNotNull(canonicalField,
                    () -> "Alias target does not exist: " + alias.getKey() + " -> " + alias.getValue());
        }
    }

    @Test
    void loadConfigShouldMapRepresentativeLegacyKeysFromEverySection() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        Path configFile = tempDir.resolve("legacy-full-config.yaml");
        Files.writeString(configFile, """
                metaData_directory: /metadata
                BASELINE_path: /baseline.csv
                regionalization: true
                MostCompetitorAFTProbability: 0.75
                neighbor_radius: 4
                participating_cells_percentage: 0.12
                land_abandonment_percentage: 0.08
                takeOverUnmanageCells_percentage: 0.6
                Output_path: /output
                generate_charts_plots_PNG: true
                LOGGER_trace: true
                LLM_model_name: legacy-model
                COUPLED_WITH_PLUM: true
                plumOutPutPath: /plum
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals("/metadata", cfg.metadata_directory);
        assertEquals("/baseline.csv", cfg.baseline_path);
        assertTrue(cfg.regionalisation);
        assertEquals(0.75, cfg.most_competitive_aft_probability);
        assertEquals(4, cfg.neighbour_radius);
        assertEquals(0.12, cfg.participating_cell_fraction);
        assertEquals(0.08, cfg.land_abandonment_fraction);
        assertEquals(0.6, cfg.unmanaged_cell_takeover_fraction);
        assertEquals("/output", cfg.output_path);
        assertTrue(cfg.generate_chart_plots_png);
        assertTrue(cfg.logger_trace);
        assertEquals("legacy-model", cfg.llm_model_name);
        assertTrue(cfg.coupled_with_plum);
        assertEquals("/plum", cfg.plum_output_path);
    }

    @Test
    void loadConfigShouldFallbackToDefaultConfigWhenFileIsMissing() throws Exception {
        // Backup previous state
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        // Arrange: point to a non-existing file
        Path missing = tempDir.resolve("does_not_exist.yaml");
        ConfigLoader.configPath = missing.toString();

        // Act
        Config cfg = invokeLoadConfig();

        // Assert
        assertNotNull(cfg, "When config file is missing, a default Config should be returned");
    }

    @Test
    void initShouldSetGlobalConfigNonNullEvenWithoutExplicitConfigPath() {
        // Backup previous state
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        ConfigLoader.configPath = null;
        ConfigLoader.config = null;

        // Act
        ConfigLoader.init();

        // Assert
        assertNotNull(ConfigLoader.config,
                "ConfigLoader.init() should always set a non-null global Config instance");
    }

    // =====================================================================
    // Cleanup plan step 0.2 - config loading failure modes.
    //
    // These pin down bug B1, which was the worst bug found in the review: a
    // single unrecognised key in config.yaml made the loader throw away the
    // WHOLE configuration and continue on defaults. A run could silently use
    // the wrong random seed, the wrong input paths and the wrong model
    // switches, and the only clue was one line on stdout.
    //
    // The trap was not just typos. The four chart/map synchronisation
    // settings are static fields on Config, and SnakeYAML cannot bind static
    // fields, so even these documented, canonical keys triggered the reset.
    // =====================================================================

    /**
     * The four synchronisation fields are static, so a value written by one
     * test would leak into the next one. Save and restore them.
     */
    private Boolean chartSyncBackup;
    private Integer chartSyncGapBackup;
    private Boolean mapSyncBackup;
    private Integer mapSyncGapBackup;

    private void backupSynchronisationFields() {
        chartSyncBackup = Config.chart_synchronisation;
        chartSyncGapBackup = Config.chart_synchronisation_gap;
        mapSyncBackup = Config.map_synchronisation;
        mapSyncGapBackup = Config.map_synchronisation_gap;
    }

    @AfterEach
    void restoreSynchronisationFields() {
        if (chartSyncBackup != null) {
            Config.chart_synchronisation = chartSyncBackup;
            Config.chart_synchronisation_gap = chartSyncGapBackup;
            Config.map_synchronisation = mapSyncBackup;
            Config.map_synchronisation_gap = mapSyncGapBackup;
            chartSyncBackup = null;
        }
    }

    /** Unwraps the reflective call so we can assert on the real failure. */
    private Throwable loadConfigExpectingFailure(Path configFile) {
        ConfigLoader.configPath = configFile.toString();
        InvocationTargetException wrapper = assertThrows(InvocationTargetException.class,
                this::invokeLoadConfig,
                "Expected loading this configuration to fail");
        return wrapper.getCause();
    }

    @Test
    void unknownKeyShouldStopTheRunAndNameTheOffendingKey() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        // "random_sed" is a plausible typo for "random_seed".
        Path configFile = tempDir.resolve("typo-config.yaml");
        Files.writeString(configFile, "random_seed: 42\nrandom_sed: 99\n");

        Throwable failure = loadConfigExpectingFailure(configFile);

        assertInstanceOf(IllegalArgumentException.class, failure,
                "An unrecognised key should be reported as a configuration error");
        assertTrue(failure.getMessage().contains("random_sed"),
                "The error must name the offending key so it can be found and fixed. Got: "
                        + failure.getMessage());
    }

    @Test
    void unknownKeyMustNotSilentlyFallBackToADefaultConfiguration() throws Exception {
        // This is the exact shape of bug B1. Before the fix this call returned
        // a brand new Config, so random_seed came back as 1 rather than 42 and
        // every other setting in the file was quietly discarded. Failing loudly
        // is the correct behaviour: continuing with the wrong seed and the
        // wrong paths produces results that look plausible but are not the run
        // that was asked for.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("b1-regression-config.yaml");
        Files.writeString(configFile, """
                random_seed: 42
                neighbour_radius: 5
                participating_cell_fraction: 0.5
                not_a_real_crafty_option: true
                """);

        Throwable failure = loadConfigExpectingFailure(configFile);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("not_a_real_crafty_option"),
                "The error must name the unknown key. Got: " + failure.getMessage());
    }

    @Test
    void synchronisationKeysShouldLoadWithoutDiscardingTheRestOfTheFile() throws Exception {
        // The regression test for the worst form of B1: these four keys are
        // documented and canonical, but because they are static fields the
        // whole file used to be thrown away when any of them appeared.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("sync-config.yaml");
        Files.writeString(configFile, """
                random_seed: 42
                neighbour_radius: 5
                chart_synchronisation: false
                chart_synchronisation_gap: 3
                map_synchronisation: false
                map_synchronisation_gap: 7
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        // The static keys are applied...
        assertFalse(Config.chart_synchronisation);
        assertEquals(3, Config.chart_synchronisation_gap);
        assertFalse(Config.map_synchronisation);
        assertEquals(7, Config.map_synchronisation_gap);

        // ...and, crucially, the ordinary settings around them survived.
        assertEquals(42L, cfg.random_seed,
                "Settings alongside a synchronisation key must not be discarded (bug B1)");
        assertEquals(5, cfg.neighbour_radius,
                "Settings alongside a synchronisation key must not be discarded (bug B1)");
    }

    @Test
    void legacySynchronisationSpellingShouldStillBeAccepted() throws Exception {
        // The American spellings are in the legacy alias table, so they must
        // survive both the alias rewrite and the static-field binding.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("legacy-sync-config.yaml");
        Files.writeString(configFile, """
                random_seed: 7
                chart_synchronization: false
                map_synchronization_gap: 4
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertFalse(Config.chart_synchronisation);
        assertEquals(4, Config.map_synchronisation_gap);
        assertEquals(7L, cfg.random_seed);
    }

    @Test
    void nonNumericValueForASynchronisationGapShouldBeReportedAgainstThatKey() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("bad-sync-value-config.yaml");
        Files.writeString(configFile, "chart_synchronisation_gap: not_a_number\n");

        Throwable failure = loadConfigExpectingFailure(configFile);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("chart_synchronisation_gap"),
                "A bad value must be reported against the key it came from. Got: " + failure.getMessage());
    }

    @Test
    void aValidConfigurationShouldStillLoadEveryOrdinaryKey() throws Exception {
        // Guards the other half of the fix: tightening up unknown keys must not
        // make ordinary, valid configurations any harder to load.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("valid-config.yaml");
        Files.writeString(configFile, """
                random_seed: 123
                cell_selection: random
                neighbour_radius: 3
                participating_cell_fraction: 0.25
                land_abandonment_fraction: 0.05
                use_twinned_afts: true
                output_folder_name: goldenrun
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertEquals(123L, cfg.random_seed);
        assertEquals("random", cfg.cell_selection);
        assertEquals(3, cfg.neighbour_radius);
        assertEquals(0.25, cfg.participating_cell_fraction);
        assertEquals(0.05, cfg.land_abandonment_fraction);
        assertTrue(cfg.use_twinned_afts);
        assertEquals("goldenrun", cfg.output_folder_name);
    }

    // =========================================================
    // CRAFTY-react switches
    // =========================================================

    private static final List<String> REACTIVE_ELEMENTS = List.of("reactive_fertilizer", "reactive_irrigation",
            "reactive_other_intensity", "reactive_stocking", "reactive_forestry");

    /** A default Config with the named boolean switches turned on. */
    private static Config configWithSwitchesOn(String... fieldNames) throws Exception {
        Config c = new Config();
        for (String name : fieldNames) {
            Config.class.getField(name).setBoolean(c, true);
        }
        return c;
    }

    @Test
    void reactiveSwitchesShouldAllDefaultToFalse() {
        Config c = new Config();

        assertFalse(c.reactive_afts);
        assertFalse(c.reactive_fertilizer);
        assertFalse(c.reactive_irrigation);
        assertFalse(c.reactive_other_intensity);
        assertFalse(c.reactive_stocking);
        assertFalse(c.reactive_forestry);
        assertTrue(ConfigLoader.enabledReactiveElements(c).isEmpty());
        assertNull(ConfigLoader.reactiveConfigError(c), "The default configuration must be valid");
    }

    @Test
    void reactiveSwitchesShouldLoadFromYaml() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("reactive-config.yaml");
        Files.writeString(configFile, """
                reactive_afts: true
                reactive_fertilizer: true
                reactive_irrigation: true
                reactive_other_intensity: true
                reactive_stocking: true
                reactive_forestry: true
                """);
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertTrue(cfg.reactive_afts);
        assertTrue(cfg.reactive_fertilizer);
        assertTrue(cfg.reactive_irrigation);
        assertTrue(cfg.reactive_other_intensity);
        assertTrue(cfg.reactive_stocking);
        assertTrue(cfg.reactive_forestry);
    }

    @Test
    void theRetiredOverwriteSettingIsNowAnUnknownKey() throws Exception {
        // CRAFTY-react hands its values to the model in memory (phase 5), so the setting that chose how its
        // files were handed over was removed. A configuration that still sets it stops, naming it.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("retired-config.yaml");
        Files.writeString(configFile, "reactive_afts: true\nreactive_overwrite_inputs: false\n");

        Throwable failure = loadConfigExpectingFailure(configFile);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("reactive_overwrite_inputs"), failure.getMessage());
    }

    @Test
    void aReactiveElementWithoutTheMasterSwitchShouldStopTheRun() throws Exception {
        for (String element : REACTIVE_ELEMENTS) {
            String error = ConfigLoader.reactiveConfigError(configWithSwitchesOn(element));

            assertNotNull(error, element + " on with reactive_afts off must be rejected");
            assertTrue(error.contains(element), "The message must name the offending switch. Got: " + error);
            assertTrue(error.contains("reactive_afts"), "The message must say how to fix it. Got: " + error);
        }
    }

    @Test
    void theMasterSwitchWithSpatialCostsShouldBeValid() throws Exception {
        Config c = configWithSwitchesOn("reactive_afts", "use_production_costs", "spatial_production_costs");

        assertNull(ConfigLoader.reactiveConfigError(c));
        assertTrue(ConfigLoader.enabledReactiveElements(c).isEmpty());
    }

    @Test
    void elementsWithTheMasterSwitchShouldBeValidAndReportedInAFixedOrder() throws Exception {
        // Switched on in reverse, to show the reported order does not depend on it.
        Config c = configWithSwitchesOn("reactive_afts", "use_production_costs", "spatial_production_costs",
                "reactive_forestry", "reactive_stocking", "reactive_other_intensity", "reactive_irrigation",
                "reactive_fertilizer");

        assertNull(ConfigLoader.reactiveConfigError(c));
        assertEquals(REACTIVE_ELEMENTS, ConfigLoader.enabledReactiveElements(c));
    }

    @Test
    void reactWithoutSpatialProductionCostsShouldStopTheRun() throws Exception {
        // React puts its costs into the cells' spatial costs; without both switches the model would not use them.
        for (Config c : List.of(configWithSwitchesOn("reactive_afts"),
                configWithSwitchesOn("reactive_afts", "use_production_costs"))) {
            String error = ConfigLoader.reactiveConfigError(c);

            assertNotNull(error, "React without spatial production costs must be rejected");
            assertTrue(error.contains("use_production_costs") && error.contains("spatial_production_costs"),
                    "The message must name both switches. Got: " + error);
        }
    }

    @Test
    void elementAccessorsShouldRequireTheMasterSwitch() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;

        ConfigLoader.config = null;
        assertFalse(ConfigLoader.isReactiveAfts(), "No config loaded means CRAFTY-react is off");
        assertFalse(ConfigLoader.isReactiveFertilizer());

        // Every element on, master off: an invalid state that validation would stop,
        // but the accessors must still not report the elements as on.
        ConfigLoader.config = configWithSwitchesOn(REACTIVE_ELEMENTS.toArray(new String[0]));
        assertFalse(ConfigLoader.isReactiveAfts());
        assertFalse(ConfigLoader.isReactiveFertilizer());
        assertFalse(ConfigLoader.isReactiveIrrigation());
        assertFalse(ConfigLoader.isReactiveOtherIntensity());
        assertFalse(ConfigLoader.isReactiveStocking());
        assertFalse(ConfigLoader.isReactiveForestry());

        ConfigLoader.config.reactive_afts = true;
        assertTrue(ConfigLoader.isReactiveAfts());
        assertTrue(ConfigLoader.isReactiveFertilizer());
        assertTrue(ConfigLoader.isReactiveIrrigation());
        assertTrue(ConfigLoader.isReactiveOtherIntensity());
        assertTrue(ConfigLoader.isReactiveStocking());
        assertTrue(ConfigLoader.isReactiveForestry());
    }

    @Test
    void theBritishSpellingOfFertiliserShouldBeAccepted() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("british-reactive.yaml");
        Files.writeString(configFile, "reactive_afts: true\nreactive_fertiliser: true\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertTrue(cfg.reactive_fertilizer, "reactive_fertiliser must switch on the same setting as reactive_fertilizer");
    }

    @Test
    void settingBothSpellingsOfFertiliserShouldStopTheRun() throws Exception {
        // Otherwise one of the two values would be silently ignored.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("both-spellings.yaml");
        Files.writeString(configFile, "reactive_afts: true\nreactive_fertilizer: false\nreactive_fertiliser: true\n");

        Throwable failure = loadConfigExpectingFailure(configFile);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("reactive_fertilizer")
                && failure.getMessage().contains("reactive_fertiliser"),
                "Both spellings must be named. Got: " + failure.getMessage());
    }

    @Test
    void aMisspeltReactiveSwitchShouldStillStopTheRun() throws Exception {
        // Accepting two spellings must not open the door to any spelling.
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("misspelt-reactive.yaml");
        Files.writeString(configFile, "reactive_afts: true\nreactive_fertlizer: true\n");

        Throwable failure = loadConfigExpectingFailure(configFile);

        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("reactive_fertlizer"),
                "The unknown key must be named. Got: " + failure.getMessage());
    }

    // =========================================================
    // Cell area
    // =========================================================

    @Test
    void cellAreaShouldBeOffByDefault() {
        assertFalse(new Config().use_cell_area);
    }

    @Test
    void cellAreaShouldLoadFromYaml() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("cell-area-config.yaml");
        Files.writeString(configFile, "use_cell_area: true\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertTrue(cfg.use_cell_area);
    }

    // =========================================================
    // Conversion cost
    // =========================================================

    @Test
    void conversionCostSwitchedOffShouldNeverStopOrWarn() throws Exception {
        assertFalse(new Config().use_conversion_cost, "Conversion cost must be off by default");

        // With the switch off, neither the utility mode nor the competition path matters.
        for (Config c : List.of(new Config(), configWithSwitchesOn("use_explicit_price_utility"),
                configWithSwitchesOn("use_price_only_utility"))) {
            assertNull(ConfigLoader.conversionCostConfigError(c));
            assertNull(ConfigLoader.conversionCostConfigWarning(c));
        }
    }

    @Test
    void conversionCostWithoutExplicitPriceUtilityShouldStopTheRun() throws Exception {
        // The other utility modes have no costs, so a $/ha cost would mean nothing there.
        for (Config c : List.of(configWithSwitchesOn("use_conversion_cost"),
                configWithSwitchesOn("use_conversion_cost", "use_price_only_utility"),
                configWithSwitchesOn("use_conversion_cost", "use_normalised_price_competition"))) {
            String error = ConfigLoader.conversionCostConfigError(c);

            assertNotNull(error, "Conversion cost without explicit-price utility must be rejected");
            assertTrue(error.contains("use_explicit_price_utility"), "The message must say how to fix it. Got: " + error);
        }
    }

    @Test
    void conversionCostShouldNotNeedProductionCosts() throws Exception {
        // A conversion cost with no other production costs is a valid set-up.
        Config c = configWithSwitchesOn("use_conversion_cost", "use_explicit_price_utility");

        assertFalse(c.use_production_costs);
        assertNull(ConfigLoader.conversionCostConfigError(c));
    }

    @Test
    void conversionCostWithoutNormalisedPriceCompetitionShouldWarnButNotStop() throws Exception {
        Config c = configWithSwitchesOn("use_conversion_cost", "use_explicit_price_utility");

        assertNull(ConfigLoader.conversionCostConfigError(c), "This set-up must not stop the run");
        String warning = ConfigLoader.conversionCostConfigWarning(c);
        assertNotNull(warning, "Conversion cost is designed for normalised-price competition");
        assertTrue(warning.contains("use_normalised_price_competition"),
                "The warning must name the switch. Got: " + warning);
    }

    @Test
    void conversionCostWithNormalisedPriceCompetitionShouldNotWarn() throws Exception {
        Config c = configWithSwitchesOn("use_conversion_cost", "use_explicit_price_utility",
                "use_normalised_price_competition");

        assertNull(ConfigLoader.conversionCostConfigError(c));
        assertNull(ConfigLoader.conversionCostConfigWarning(c));
    }

    @Test
    void conversionCostShouldLoadFromYaml() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("conversion-cost-config.yaml");
        Files.writeString(configFile, "use_explicit_price_utility: true\nuse_conversion_cost: true\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertTrue(cfg.use_conversion_cost);
    }

    // =========================================================
    // Matrix give-in
    // =========================================================

    @Test
    void matrixGiveInShouldBeOffByDefault() {
        assertFalse(new Config().use_matrix_give_in, "Matrix give-in must be off by default");
        assertNull(ConfigLoader.matrixGiveInConfigError(new Config()));
        assertNull(ConfigLoader.matrixGiveInConfigWarning(new Config()), "No warning with the switch off");
    }

    @Test
    void matrixGiveInShouldWarnThatBlankCellsHaveNoNoise() throws Exception {
        Config c = configWithSwitchesOn("use_matrix_give_in");
        c.use_category_based_give_in = false;

        assertEquals("matrix give in is on: noise will not be used for NA values in the give in matrix",
                ConfigLoader.matrixGiveInConfigWarning(c));
    }

    @Test
    void matrixGiveInWithCategoryGiveInShouldStopTheRun() throws Exception {
        // use_category_based_give_in defaults to true, so switching on the matrix alone hits this.
        Config c = configWithSwitchesOn("use_matrix_give_in");

        assertTrue(c.use_category_based_give_in);
        String error = ConfigLoader.matrixGiveInConfigError(c);
        assertNotNull(error, "The two give-in sources must not both be on");
        assertTrue(error.contains("use_category_based_give_in: false"), "The message must say how to fix it. Got: " + error);
    }

    @Test
    void matrixGiveInWithoutCategoryGiveInShouldNotStop() throws Exception {
        Config c = configWithSwitchesOn("use_matrix_give_in");
        c.use_category_based_give_in = false;

        assertNull(ConfigLoader.matrixGiveInConfigError(c));
    }

    @Test
    void categoryGiveInAloneShouldNotStop() {
        Config c = new Config();
        c.use_category_based_give_in = true;

        assertNull(ConfigLoader.matrixGiveInConfigError(c));
    }

    @Test
    void matrixGiveInShouldLoadFromYaml() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("matrix-give-in-config.yaml");
        Files.writeString(configFile, "use_category_based_give_in: false\nuse_matrix_give_in: true\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertTrue(cfg.use_matrix_give_in);
        assertFalse(cfg.use_category_based_give_in);
    }

    // =========================================================
    // Price feedback
    // =========================================================

    /** Price feedback on in the set-up it assumes: explicit price, production costs, initial equilibrium. */
    private static Config priceFeedbackConfig() throws Exception {
        return configWithSwitchesOn("use_price_feedback", "use_explicit_price_utility", "use_production_costs");
    }

    @Test
    void priceFeedbackShouldBeOffByDefault() {
        Config c = new Config();

        assertFalse(c.use_price_feedback, "Price feedback must be off by default");
        assertEquals(0.03, c.price_feedback_strength, "The default strength is PLUM's value");
        assertNull(ConfigLoader.priceFeedbackConfigError(c));
        assertTrue(ConfigLoader.priceFeedbackConfigWarnings(c).isEmpty(), "No warning with the switch off");
    }

    @Test
    void priceFeedbackSwitchedOffShouldNeverStopOrWarn() throws Exception {
        // With the switch off, neither the utility mode nor the strength matters.
        for (Config c : List.of(configWithSwitchesOn("use_price_only_utility"),
                configWithSwitchesOn("use_explicit_price_utility"))) {
            c.price_feedback_strength = -1.0;
            c.initial_demand_supply_equilibrium = false;

            assertNull(ConfigLoader.priceFeedbackConfigError(c));
            assertTrue(ConfigLoader.priceFeedbackConfigWarnings(c).isEmpty());
        }
    }

    @Test
    void priceFeedbackInTheSetUpItAssumesShouldNeitherStopNorWarn() throws Exception {
        Config c = priceFeedbackConfig();

        assertTrue(c.initial_demand_supply_equilibrium);
        assertNull(ConfigLoader.priceFeedbackConfigError(c));
        assertTrue(ConfigLoader.priceFeedbackConfigWarnings(c).isEmpty());
    }

    @Test
    void priceFeedbackWithoutExplicitPriceUtilityShouldStopTheRun() throws Exception {
        // Only price-explicit utility uses a price, so there is nothing to scale in the marginal modes.
        for (Config c : List.of(configWithSwitchesOn("use_price_feedback"),
                configWithSwitchesOn("use_price_feedback", "use_production_costs", "use_cell_level_taxes"))) {
            String error = ConfigLoader.priceFeedbackConfigError(c);

            assertNotNull(error, "Price feedback without explicit-price utility must be rejected");
            assertTrue(error.contains("use_explicit_price_utility: true"),
                    "The message must say how to fix it. Got: " + error);
        }
    }

    @Test
    void priceFeedbackWithPriceOnlyUtilityShouldStopTheRun() throws Exception {
        // In the PLUM price-ratio mode, PLUM is the market.
        String error = ConfigLoader.priceFeedbackConfigError(
                configWithSwitchesOn("use_price_feedback", "use_price_only_utility"));

        assertNotNull(error, "Price feedback in the PLUM price-ratio mode must be rejected");
        assertTrue(error.contains("use_price_only_utility"), "The message must name the mode. Got: " + error);
    }

    @Test
    void priceFeedbackStrengthMustBeAFiniteNumberOfAtLeastZero() throws Exception {
        for (double bad : new double[] { -0.1, Double.NaN, Double.POSITIVE_INFINITY }) {
            Config c = priceFeedbackConfig();
            c.price_feedback_strength = bad;

            String error = ConfigLoader.priceFeedbackConfigError(c);
            assertNotNull(error, "Strength " + bad + " must be rejected");
            assertTrue(error.contains("price_feedback_strength"), "The message must name the option. Got: " + error);
        }

        // 0 is allowed: it switches the feedback off without switching the option off.
        Config zero = priceFeedbackConfig();
        zero.price_feedback_strength = 0.0;
        assertNull(ConfigLoader.priceFeedbackConfigError(zero));
    }

    @Test
    void priceFeedbackWithoutProductionCostsShouldWarnButNotStop() throws Exception {
        Config c = configWithSwitchesOn("use_price_feedback", "use_explicit_price_utility");

        assertNull(ConfigLoader.priceFeedbackConfigError(c), "This set-up must not stop the run");
        List<String> warnings = ConfigLoader.priceFeedbackConfigWarnings(c);
        assertEquals(1, warnings.size(), "Got: " + warnings);
        assertTrue(warnings.get(0).contains("use_production_costs"), "The warning must name the switch. Got: " + warnings);
        assertTrue(warnings.get(0).contains("give up"), "The warning must say what is lost. Got: " + warnings);
    }

    @Test
    void priceFeedbackWithoutInitialEquilibriumShouldWarnButNotStop() throws Exception {
        Config c = priceFeedbackConfig();
        c.initial_demand_supply_equilibrium = false;

        assertNull(ConfigLoader.priceFeedbackConfigError(c), "This set-up must not stop the run");
        List<String> warnings = ConfigLoader.priceFeedbackConfigWarnings(c);
        assertEquals(1, warnings.size(), "Got: " + warnings);
        assertTrue(warnings.get(0).contains("initial_demand_supply_equilibrium"),
                "The warning must name the switch. Got: " + warnings);
    }

    @Test
    void priceFeedbackShouldLoadFromYaml() throws Exception {
        originalConfigPath = ConfigLoader.configPath;
        originalConfig = ConfigLoader.config;
        backupSynchronisationFields();

        Path configFile = tempDir.resolve("price-feedback-config.yaml");
        Files.writeString(configFile,
                "use_explicit_price_utility: true\nuse_price_feedback: true\nprice_feedback_strength: 0.5\n");
        ConfigLoader.configPath = configFile.toString();

        Config cfg = invokeLoadConfig();

        assertTrue(cfg.use_price_feedback);
        assertEquals(0.5, cfg.price_feedback_strength);
    }
}
