package de.cesr.crafty.core.dataLoader.afts;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.ToyData;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.dataLoader.ProjectLoader;
import de.cesr.crafty.core.updaters.Timestep;

class AftGiveInMatrixTest {

	// AFT1 -> AFT2 is 0.2, AFT1 -> AFT3 0.5 and AFT2 -> AFT1 0.1. The diagonal and AFT2 -> AFT3
	// are blank; AFT3 -> AFT1 is a typo, which reads as 0 like the other matrix files.
	private static final String MATRIX = """
			Owner,AFT1,AFT2,AFT3
			AFT1,,0.2,0.5
			AFT2,0.1,,
			AFT3,abc,0.0,
			""";

	@TempDir
	Path projectDir;

	private int startYearBackup, endYearBackup, currentYearBackup;
	private ArrayList<Path> allFilesBackup;
	private Aft aft1, aft2, aft3;

	@BeforeEach
	void setUp() {
		new ToyData().resetStaticState(projectDir); // AFT1-3; aft_behaviour_parameters_directory = projectDir/agents
		aft1 = AFTsLoader.getAftHash().get("AFT1");
		aft2 = AFTsLoader.getAftHash().get("AFT2");
		aft3 = AFTsLoader.getAftHash().get("AFT3");

		startYearBackup = Timestep.getStartYear();
		endYearBackup = Timestep.getEndtYear();
		currentYearBackup = Timestep.getCurrentYear();
		allFilesBackup = ProjectLoader.getAllfilesPathInData();
		Timestep.setStartYear(2020);
		Timestep.setEndtYear(2022);
		Timestep.setCurrentYear(2020);
		ConfigLoader.config.use_matrix_give_in = true;
	}

	@AfterEach
	void tearDown() {
		ConfigLoader.config.use_matrix_give_in = false;
		AftGiveInMatrix.initialize(); // switch off: clears the rows and files
		Timestep.setStartYear(startYearBackup);
		Timestep.setEndtYear(endYearBackup);
		Timestep.setCurrentYear(currentYearBackup);
		ProjectLoader.setAllfilesPathInData(allFilesBackup);
		CustomLogger.shutdownRunFileLoggers();
	}

	private Path behaviourDirectory() {
		return Path.of(ConfigLoader.config.aft_behaviour_parameters_directory);
	}

	private static Path write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		return Files.writeString(file, content);
	}

	// ── Finding the files ───────────────────────────────────────────────

	@Test
	void findMatrixFiles_picksTheStaticAndYearFiles_byExactName() {
		Path staticFile = Path.of("b", "Aft_give_in_matrix.csv");
		Path year2021 = Path.of("b", "Aft_give_in_matrix_2021.csv");
		List<Path> candidates = List.of(staticFile, year2021, Path.of("b", "Aft_give_in_matrix_1999.csv"),
				Path.of("b", "old_Aft_give_in_matrix.csv"), Path.of("b", "Aft_give_in_matrix_2021.csv.bak"),
				Path.of("b", "AftParams_AFT1.csv"));

		AftGiveInMatrix.MatrixFiles files = AftGiveInMatrix.findMatrixFiles(candidates, 2020, 2022);

		assertEquals(staticFile, files.staticMatrix());
		assertEquals(Map.of(2021, year2021), files.byYear(), "Years outside the run and other names are ignored");
		assertTrue(files.problems().isEmpty(), "Got: " + files.problems());
	}

	@Test
	void findMatrixFiles_withoutAStaticMatrix_needsOneForEveryYear() {
		List<Path> someYears = List.of(Path.of("Aft_give_in_matrix_2020.csv"), Path.of("Aft_give_in_matrix_2022.csv"));
		AftGiveInMatrix.MatrixFiles gap = AftGiveInMatrix.findMatrixFiles(someYears, 2020, 2022);
		assertEquals(1, gap.problems().size());
		assertTrue(gap.problems().get(0).contains("[2021]"), "The missing year must be named. Got: " + gap.problems());

		List<Path> everyYear = new ArrayList<>(someYears);
		everyYear.add(Path.of("Aft_give_in_matrix_2021.csv"));
		AftGiveInMatrix.MatrixFiles full = AftGiveInMatrix.findMatrixFiles(everyYear, 2020, 2022);
		assertTrue(full.problems().isEmpty(), "Every year has its own matrix, so no static one is needed");
		assertNull(full.staticMatrix());

		assertFalse(AftGiveInMatrix.findMatrixFiles(List.of(), 2020, 2022).problems().isEmpty(),
				"No matrix at all must stop the run");
	}

	@Test
	void findMatrixFiles_reportsAFileNameFoundTwice() {
		List<Path> candidates = List.of(Path.of("x", "Aft_give_in_matrix.csv"), Path.of("y", "Aft_give_in_matrix.csv"),
				Path.of("x", "Aft_give_in_matrix_2021.csv"), Path.of("y", "Aft_give_in_matrix_2021.csv"));

		List<String> problems = AftGiveInMatrix.findMatrixFiles(candidates, 2020, 2022).problems();

		assertEquals(2, problems.size(), "Got: " + problems);
	}

	// ── Loading the rows ────────────────────────────────────────────────

	@Test
	void initialize_putsEachOwnersRowOnItsAft_fromTheConfiguredDirectory() throws IOException {
		write(behaviourDirectory().resolve("Aft_give_in_matrix.csv"), MATRIX);

		AftGiveInMatrix.initialize();

		assertEquals(0.2, aft1.getGiveInRow().get("AFT2"));
		assertEquals(0.5, aft1.getGiveInRow().get("AFT3"));
		assertEquals(0.1, aft2.getGiveInRow().get("AFT1"));
		assertEquals(0.0, aft3.getGiveInRow().get("AFT2"));
		assertEquals(0.0, aft3.getGiveInRow().get("AFT1"), "A typo reads as 0");
		assertNull(aft1.getGiveInRow().get("AFT1"), "A blank cell is left out");
		assertNull(aft2.getGiveInRow().get("AFT3"), "A blank cell is left out, so it falls back to the mean");
	}

	@Test
	void initialize_usesAgentsDefaultBehaviour_whenNoDirectoryIsSet() throws IOException {
		ConfigLoader.config.aft_behaviour_parameters_directory = "";
		Path wanted = write(projectDir.resolve("AFTs/agents/default_behaviour/Aft_give_in_matrix.csv"), MATRIX);
		Path elsewhere = write(projectDir.resolve("AFTs/agents/other/Aft_give_in_matrix.csv"),
				"Owner,AFT1,AFT2,AFT3\nAFT1,,9,9\n");
		ProjectLoader.setAllfilesPathInData(new ArrayList<>(List.of(wanted, elsewhere)));

		AftGiveInMatrix.initialize();

		assertEquals(0.2, aft1.getGiveInRow().get("AFT2"), "Only agents/default_behaviour is looked in");
	}

	@Test
	void updateForYear_usesTheYearsOwnMatrix_otherwiseTheStaticOne() throws IOException {
		write(behaviourDirectory().resolve("Aft_give_in_matrix.csv"), MATRIX);
		write(behaviourDirectory().resolve("Aft_give_in_matrix_2021.csv"), "Owner,AFT1,AFT2,AFT3\nAFT1,,0.9,\n");

		AftGiveInMatrix.initialize();
		assertEquals(0.2, aft1.getGiveInRow().get("AFT2"), "2020 has no matrix of its own");

		AftGiveInMatrix.updateForYear(2021);
		assertEquals(0.9, aft1.getGiveInRow().get("AFT2"));
		assertNull(aft1.getGiveInRow().get("AFT3"), "Rows are replaced, not merged with the static matrix");
		assertTrue(aft2.getGiveInRow().isEmpty(), "AFT2 has no row in the 2021 matrix");

		AftGiveInMatrix.updateForYear(2022);
		assertEquals(0.2, aft1.getGiveInRow().get("AFT2"), "2022 goes back to the static matrix");
	}

	@Test
	void applyRows_givesEveryAftARow_andIgnoresLabelsThatAreNotAfts() {
		AftGiveInMatrix.applyRows(Map.of("AFT1|AFT2", 0.3, "GHOST|AFT1", 0.4, "AFT1|GHOST", 0.5),
				AFTsLoader.getAftHash().values(), Path.of("test.csv"));

		assertEquals(0.3, aft1.getGiveInRow().get("AFT2"));
		assertFalse(aft1.getGiveInRow().containsKey("GHOST"));
		assertNotNull(aft3.getGiveInRow(), "An AFT missing from the matrix still gets a row");
		assertTrue(aft3.getGiveInRow().isEmpty(), "and every pair in it falls back to the mean");
	}

	@Test
	void initialize_withTheSwitchOff_clearsTheRows() throws IOException {
		write(behaviourDirectory().resolve("Aft_give_in_matrix.csv"), MATRIX);
		AftGiveInMatrix.initialize();
		assertNotNull(aft1.getGiveInRow());

		ConfigLoader.config.use_matrix_give_in = false;
		AftGiveInMatrix.initialize();
		AftGiveInMatrix.updateForYear(2020);

		assertNull(aft1.getGiveInRow());
		assertNull(aft2.getGiveInRow());
		assertNull(aft3.getGiveInRow());
	}
}
