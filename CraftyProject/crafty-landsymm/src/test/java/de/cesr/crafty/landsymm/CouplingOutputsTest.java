package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.ConfigLoader;

class CouplingOutputsTest {

	@TempDir
	Path tempDir;

	@Test
	void theFolderIsPlumCouplingInsideCraftysOutputFolder() {
		CouplingOutputs outputs = CouplingOutputs.inOutputFolder(tempDir.resolve("output").toString());

		assertEquals(tempDir.resolve("output").resolve("plum_coupling"), outputs.folder());
		assertTrue(Files.isDirectory(outputs.folder()));
	}

	@Test
	void aStartedFileHasOnlyItsHeaderEvenInAReusedFolder() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		Files.writeString(outputs.file("x.csv"), "A,B\n1,2\n3,4\n");

		outputs.start("x.csv", List.of("A", "B"));

		assertEquals("A,B\n", Files.readString(outputs.file("x.csv")));
	}

	@Test
	void rowsAreAppendedYearByYear() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		outputs.start("x.csv", List.of("Year", "Value"));

		outputs.append("x.csv", List.of(List.of("1990", "1.0")));
		outputs.append("x.csv", List.of(List.of("1991", "2.0"), List.of("1991", "3.0")));

		assertEquals(List.of("Year,Value", "1990,1.0", "1991,2.0", "1991,3.0"), Files.readAllLines(outputs.file("x.csv")));
	}

	@Test
	void valuesWithACommaOrAQuoteAreQuoted() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		outputs.start("x.csv", List.of("A", "B", "C"));

		outputs.append("x.csv", List.of(List.of("Korea, Republic of", "a \"b\"", "Italy & Malta")));

		assertEquals("\"Korea, Republic of\",\"a \"\"b\"\"\",Italy & Malta", Files.readAllLines(outputs.file("x.csv")).get(1));
		assertEquals(List.of("Korea, Republic of", "a \"b\"", "Italy & Malta"),
				CsvReader.split(Files.readAllLines(outputs.file("x.csv")).get(1)), "the coupler's reader reads it back");
	}

	@Test
	void appendingToAFileNotStartedOrWithTheWrongCountFails() {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);
		assertThrows(IllegalStateException.class, () -> outputs.append("x.csv", List.of(List.of("1"))));

		outputs.start("x.csv", List.of("A", "B"));
		assertThrows(IllegalStateException.class, () -> outputs.append("x.csv", List.of(List.of("1"))));
	}

	@Test
	void everyYearlyFileIsWrittenByDefault() {
		CouplingOutputs outputs = new CouplingOutputs(tempDir);

		assertEquals(Set.of(), outputs.switchedOff());
		for (String name : ConfigLoader.PLUM_COUPLING_OUTPUTS) {
			assertTrue(outputs.isWritten(name + ".csv"), name);
		}
	}

	@Test
	void aSwitchedOffFileIsNeverWrittenAndAnOldCopyIsRemoved() throws Exception {
		CouplingOutputs outputs = new CouplingOutputs(tempDir, List.of("prices_received", "trade_capitals"));
		Files.writeString(outputs.file(ProductionStep.PRODUCTION_SENT), "an old run's file\n");

		outputs.start(ProductionStep.PRODUCTION_SENT, List.of("Year", "Value"));
		outputs.append(ProductionStep.PRODUCTION_SENT, List.of(List.of("1990", "1.0"), List.of("too", "many", "values")));
		outputs.start(PlumInputStep.PRICES_RECEIVED, List.of("Year", "Value"));
		outputs.append(PlumInputStep.PRICES_RECEIVED, List.of(List.of("1990", "2.0")));

		assertFalse(Files.exists(outputs.file(ProductionStep.PRODUCTION_SENT)));
		assertEquals(List.of("Year,Value", "1990,2.0"), Files.readAllLines(outputs.file(PlumInputStep.PRICES_RECEIVED)));
		assertEquals(Set.of("export_basis", "production_sent", "ungrazed_grass"), outputs.switchedOff());
	}

	@Test
	void theFilesWrittenOnceHaveNoSwitch() {
		CouplingOutputs outputs = new CouplingOutputs(tempDir, List.of());

		assertTrue(outputs.isWritten("price_factors.csv"));
		assertTrue(outputs.isWritten(ProductionStep.BASELINE_CHECK));
		assertFalse(outputs.isWritten(ConsumptionStep.EXPORT_BASIS));
		assertFalse(outputs.isWritten(TradeCapitalStep.TRADE_CAPITALS));
		assertFalse(outputs.isWritten(PlumInputStep.UNGRAZED_GRASS));
	}

	@Test
	void theSwitchesAreTheStepsOwnFileNames() {
		assertEquals(Set.copyOf(ConfigLoader.PLUM_COUPLING_OUTPUTS),
				Set.of(PlumInputStep.PRICES_RECEIVED, ProductionStep.PRODUCTION_SENT, ConsumptionStep.EXPORT_BASIS,
						TradeCapitalStep.TRADE_CAPITALS, PlumInputStep.UNGRAZED_GRASS).stream()
						.map(n -> n.substring(0, n.length() - ".csv".length())).collect(Collectors.toSet()),
				"a renamed file would otherwise lose its switch");
	}
}
