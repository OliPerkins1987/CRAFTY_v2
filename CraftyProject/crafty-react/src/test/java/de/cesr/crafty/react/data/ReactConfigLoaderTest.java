package de.cesr.crafty.react.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactConfigLoaderTest {

	@TempDir
	Path project;

	private Path writeConfig(String... lines) {
		return ReactToyData.write(project, "AFTs/react/react_config.yaml", lines);
	}

	private ReactInputException problemsWith(String... lines) {
		writeConfig(lines);
		return assertThrows(ReactInputException.class, () -> ReactConfigLoader.load(project));
	}

	// ---- defaults ----

	@Test
	void withNoFileEverySettingIsTheDefault() {
		ReactConfig config = ReactConfigLoader.load(project);

		assertEquals(ReactConfig.defaults().toString(), config.toString());
		assertEquals(5, config.spinupIterations());
		assertEquals(1.5, config.nMaxFactor());
		assertEquals(0.15, config.nAdjustmentScale());
		assertEquals(0.88, config.prospectAlpha());
		assertEquals(0.88, config.prospectBeta());
		assertEquals(2.25, config.prospectLambda());
		assertEquals(0.5, config.stockingInitial());
		assertEquals(0.05, config.stockingStep());
		assertEquals(0.05, config.stockingMin());
		assertEquals(1.0, config.stockingMax());
		assertEquals(0.5, config.stockingHarvest());
		assertEquals(0.0, config.yieldTechChange(), "No technology change unless the file sets one");
		assertEquals(10, config.yieldFileUnits().toTonnesPerHectare());
		assertEquals(10, config.irrigationFileUnits().toCubicMetresPerHectare());
	}

	@Test
	void anEmptyFileMeansEveryDefault() {
		writeConfig("# nothing set");

		assertEquals(ReactConfig.defaults().toString(), ReactConfigLoader.load(project).toString());
	}

	@Test
	void theStubGivenToUsersHoldsExactlyTheDefaults() throws IOException {
		// src/test/resources/react/react_config_stub.yaml is the stub handed to users. If a default
		// changes, the stub must change with it.
		Path file = project.resolve(ReactConfigLoader.LOCATION);
		Files.createDirectories(file.getParent());
		try (InputStream stub = getClass().getResourceAsStream("/react/react_config_stub.yaml")) {
			Files.copy(stub, file);
		}

		assertEquals(ReactConfig.defaults().toString(), ReactConfigLoader.load(project).toString());
	}

	// ---- reading values ----

	@Test
	void settingsInTheFileReplaceTheDefaultsAndTheRestStay() {
		writeConfig(
				"inputs:",
				"  capitals: data/caps/{scenario}",
				"  yield_file_units: t_per_ha",
				"  irrigation_file_units: m3_per_ha",
				"spinup_iterations: 10",
				"n_max_factor: 2",
				"prospect:",
				"  lambda: 3",
				"stocking:",
				"  max: 0.9",
				"  harvest: 0.4",
				"yield_tech_change: 0.01");

		ReactConfig config = ReactConfigLoader.load(project);

		assertEquals(0.01, config.yieldTechChange());
		assertEquals(project.resolve("data/caps/ssp126"), config.capitalsFolder(project, "ssp126"));
		assertEquals(1, config.yieldFileUnits().toTonnesPerHectare());
		assertEquals(1, config.irrigationFileUnits().toCubicMetresPerHectare());
		assertEquals(10, config.spinupIterations());
		assertEquals(2.0, config.nMaxFactor(), "A whole number is fine where a number is expected");
		assertEquals(3.0, config.prospectLambda());
		assertEquals(0.88, config.prospectAlpha(), "Unset keys in a section keep their defaults");
		assertEquals(0.9, config.stockingMax());
		assertEquals(0.4, config.stockingHarvest());
		assertEquals(project.resolve("worlds/react/cell_key.csv"), config.cellKeyFile(project));
	}

	@Test
	void placeholdersAreFilledIn() {
		ReactConfig config = ReactConfigLoader.load(project);

		assertEquals(project.resolve("worlds/react/suitabilities/ssp126/crops"),
				config.suitabilityFolder(project, "ssp126", LpjgType.CROPS));
		assertEquals(project.resolve("worlds/react/suitabilities/ssp126/pasture"),
				config.suitabilityFolder(project, "ssp126", LpjgType.PASTURE));
		assertEquals(project.resolve("worlds/react/capitals/ssp126"), config.capitalsFolder(project, "ssp126"));
		assertEquals(project.resolve("worlds/react/irrigation/ssp126/Irrigation_demand_2020.csv"),
				config.irrigationWaterDemandFile(project, "ssp126", 2020));
		assertEquals(project.resolve("worlds/react/irrigation/ssp126/Runoff_2030.csv"),
				config.runoffFile(project, "ssp126", 2030));
		assertEquals(project.resolve("AFTs/react/Reactive_parameters.csv"), config.parametersFile(project));
		assertEquals(project.resolve("costs/global/global_costs.csv"), config.baseCostsFile(project));
		assertEquals(project.resolve("worlds/react/irrigation/Irrigation_cost.csv"), config.irrigationCostFile(project));
	}

	// ---- problems ----

	@Test
	void unknownKeysAtAnyLevelAreAllReported() {
		ReactInputException e = problemsWith(
				"spinup_iteration: 5",
				"inputs:",
				"  pasture_prefix: NPP3.",
				"prospect:",
				"  gamma: 1",
				"  reference: 0.33");

		assertTrue(e.getMessage().contains("unknown setting spinup_iteration"), e.getMessage());
		assertTrue(e.getMessage().contains("unknown setting inputs.pasture_prefix"), e.getMessage());
		assertTrue(e.getMessage().contains("unknown setting prospect.gamma"), e.getMessage());
		assertTrue(e.getMessage().contains("unknown setting prospect.reference"),
				"The reference point is each AFT's react_N_par, not a setting: " + e.getMessage());
	}

	@Test
	void valuesOfTheWrongKindAreReported() {
		ReactInputException e = problemsWith(
				"spinup_iterations: 2.5",
				"n_max_factor: lots",
				"inputs:",
				"  cell_key: 42",
				"stocking: 0.5");

		assertTrue(e.getMessage().contains("spinup_iterations must be a whole number"), e.getMessage());
		assertTrue(e.getMessage().contains("n_max_factor must be a number"), e.getMessage());
		assertTrue(e.getMessage().contains("inputs.cell_key must be text"), e.getMessage());
		assertTrue(e.getMessage().contains("stocking must hold key: value settings"), e.getMessage());
	}

	@Test
	void unitsMustBeOneOfTheNamedChoices() {
		ReactInputException e = problemsWith("inputs:", "  yield_file_units: kg/m2", "  irrigation_file_units: MM");

		assertTrue(e.getMessage().contains("yield_file_units must be one of [kg_per_m2, t_per_ha]"), e.getMessage());
		assertTrue(e.getMessage().contains("irrigation_file_units must be one of [mm, m3_per_ha]"), e.getMessage());
	}

	@Test
	void templatesMayOnlyUseTheirOwnPlaceholders() {
		ReactInputException e = problemsWith(
				"inputs:",
				"  cell_key: worlds/{scenario}/cell_key.csv",
				"  capitals: worlds/react/capitals/{scenario}/{year}",
				"  runoff: worlds/react/irrigation/{scn}/Runoff_{year}.csv");

		assertTrue(e.getMessage().contains("inputs.cell_key cannot use {scenario}"), e.getMessage());
		assertTrue(e.getMessage().contains("inputs.capitals cannot use {year}"), e.getMessage());
		assertTrue(e.getMessage().contains("inputs.runoff cannot use {scn}"), e.getMessage());
	}

	@Test
	void suitabilitiesMustBeSplitByType() {
		ReactInputException e = problemsWith("inputs:", "  suitabilities: worlds/react/suitabilities/{scenario}");

		assertTrue(e.getMessage().contains("inputs.suitabilities must contain {type}"), e.getMessage());
	}

	@Test
	void namedPerYearFilesMustContainTheYear() {
		ReactInputException e = problemsWith("inputs:", "  runoff: worlds/react/irrigation/Runoff.csv");

		assertTrue(e.getMessage().contains("inputs.runoff must contain {year}"), e.getMessage());
	}

	@Test
	void outOfRangeSettingsAreReported() {
		ReactInputException e = problemsWith(
				"spinup_iterations: -1",
				"n_max_factor: 0.9",
				"n_adjustment_scale: 0",
				"prospect:",
				"  alpha: 1.2",
				"  lambda: 0",
				"stocking:",
				"  initial: 1.5",
				"  step: 0",
				"  harvest: 0",
				"yield_tech_change: -1");

		String message = e.getMessage();
		assertTrue(message.contains("spinup_iterations must be 0 or more"), message);
		assertTrue(message.contains("n_max_factor must be at least 1"), message);
		assertTrue(message.contains("n_adjustment_scale must be above 0"), message);
		assertTrue(message.contains("prospect.alpha"), message);
		assertTrue(message.contains("prospect.lambda"), message);
		assertTrue(message.contains("stocking.step"), message);
		assertTrue(message.contains("0 < min <= initial <= max"), message);
		assertTrue(message.contains("yield_tech_change must be above -1"), message);
		assertTrue(message.contains("stocking.harvest must be above 0 and at most 1"), message);
	}

	@Test
	void aRepeatedKeyIsAnError() {
		ReactInputException e = problemsWith("spinup_iterations: 5", "spinup_iterations: 6");

		assertTrue(e.getMessage().contains("could not be read"), e.getMessage());
	}

	@Test
	void aFileThatIsNotKeyValueSettingsIsAnError() {
		ReactInputException e = problemsWith("- just", "- a list");

		assertTrue(e.getMessage().contains("key: value"), e.getMessage());
	}
}
