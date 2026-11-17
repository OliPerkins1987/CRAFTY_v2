package de.cesr.crafty.landsymm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class RunConfigWriterTest {

	private static Map<String, String> values(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	void yamlLinesTheLauncherSetsAreCommentedOutAndItsValuesGoAtTheEnd() {
		String base = "project_path: \"C:/p\"\nuse_price_feedback: true   # standalone\ncell_output_columns:\n  - x\n";

		RunConfigWriter.Copy copy = RunConfigWriter.yaml(base, values("use_price_feedback", "false"), Map.of(),
				List.of("a header"), "base");

		assertEquals(List.of(), copy.problems());
		assertEquals(List.of("use_price_feedback: true   # standalone"), copy.replaced());
		assertEquals("# a header\n"
				+ "project_path: \"C:/p\"\n"
				+ "# [launcher] use_price_feedback: true   # standalone\n"
				+ "cell_output_columns:\n"
				+ "  - x\n"
				+ "\n"
				+ "# ---- Set by LandSymmLauncher ----\n"
				+ "use_price_feedback: false\n", copy.text());
		assertEquals(Boolean.FALSE, CraftyRunConfig.parse(copy.text()).get("use_price_feedback"));
	}

	@Test
	void anOldNameForAKeyIsCommentedOutToo() {
		RunConfigWriter.Copy copy = RunConfigWriter.yaml("Output_path: \"old\"\n",
				values("output_path", "\"new\""), Map.of("Output_path", "output_path"), List.of(), "base");

		assertTrue(copy.text().contains("# [launcher] Output_path: \"old\""), copy.text());
		assertFalse(CraftyRunConfig.parse(copy.text()).containsKey("Output_path"));
	}

	@Test
	void onlyTopLevelKeysAreReplaced() {
		RunConfigWriter.Copy copy = RunConfigWriter.yaml("r_script_runner:\n  output_path: \"x\"\n# output_path: y\n",
				values("output_path", "\"z\""), Map.of(), List.of(), "base");

		assertEquals(List.of(), copy.replaced());
		assertTrue(copy.text().startsWith("r_script_runner:\n  output_path: \"x\"\n# output_path: y\n"), copy.text());
	}

	@Test
	void aKeyTheLauncherSetsMustBeOnOneLine() {
		for (String base : List.of("plum_coupling_outputs:\n  - prices_received\n",
				"plum_coupling_outputs: [prices_received,\n  export_basis]\n", "output_path: |\n  x\n",
				"output_path:   # a comment only\n  x\n")) {
			String key = base.substring(0, base.indexOf(':'));
			RunConfigWriter.Copy copy = RunConfigWriter.yaml(base, values(key, "x"), Map.of(), List.of(), "base");
			assertEquals(1, copy.problems().size(), base);
			assertTrue(copy.problems().get(0).contains("over more than one line"), copy.problems().get(0));
		}
		RunConfigWriter.Copy oneLine = RunConfigWriter.yaml("plum_coupling_outputs: [prices_received]  # [x]\n",
				values("plum_coupling_outputs", "[]"), Map.of(), List.of(), "base");
		assertEquals(List.of(), oneLine.problems());
	}

	@Test
	void windowsLineEndingsAreKept() {
		RunConfigWriter.Copy copy = RunConfigWriter.yaml("a: 1\r\nb: 2\r\n", values("a", "3"), Map.of(), List.of("h"),
				"base");

		assertEquals("# h\r\n# [launcher] a: 1\r\nb: 2\r\n\r\n# ---- Set by LandSymmLauncher ----\r\na: 3\r\n",
				copy.text());
	}

	@Test
	void propertiesLinesAreCommentedOutAndTheLastValueWins() {
		String base = "BASE_YEAR=1990\n  END_TIMESTEP = 15\nIS_CALIBRATION_RUN:true\n# END_TIMESTEP=99\n";

		RunConfigWriter.Copy copy = RunConfigWriter.properties(base,
				values("END_TIMESTEP", "3", "IS_CALIBRATION_RUN", "false", "OUTPUT_DIR", "C:/runs/x/plum/output"),
				List.of(), "base");

		assertEquals(List.of("  END_TIMESTEP = 15", "IS_CALIBRATION_RUN:true"), copy.replaced());
		Properties p = PlumRunConfig.load(copy.text());
		assertEquals("3", p.getProperty("END_TIMESTEP"));
		assertEquals("false", p.getProperty("IS_CALIBRATION_RUN"));
		assertEquals("1990", p.getProperty("BASE_YEAR"));
		assertEquals("C:/runs/x/plum/output", p.getProperty("OUTPUT_DIR"));
		assertTrue(copy.text().contains("# END_TIMESTEP=99\n"), "comments are left alone");
	}

	@Test
	void aContinuedPropertiesLineIsntSplit() {
		RunConfigWriter.Copy continued = RunConfigWriter.properties("END_TIMESTEP=1\\\n5\n", values("END_TIMESTEP", "3"),
				List.of(), "base");
		assertEquals(1, continued.problems().size());

		RunConfigWriter.Copy partOfAnother = RunConfigWriter.properties("NOTES=a \\\nEND_TIMESTEP=b\n",
				values("END_TIMESTEP", "3"), List.of(), "base");
		assertEquals(List.of(), partOfAnother.replaced(), "the second line belongs to NOTES");
		assertEquals("a END_TIMESTEP=b", PlumRunConfig.load(partOfAnother.text()).getProperty("NOTES"));
	}

	@Test
	void propertiesValuesAreEscapedSoPlumReadsThemBack() {
		String odd = " C:\\Users\\Ölive\\runs";

		RunConfigWriter.Copy copy = RunConfigWriter.properties("", values("OUTPUT_DIR", odd), List.of(), "base");

		assertEquals(odd, PlumRunConfig.load(copy.text()).getProperty("OUTPUT_DIR"));
	}

	@Test
	void yamlValues() {
		assertEquals("true", RunConfigWriter.yamlValue(true));
		assertEquals("2030", RunConfigWriter.yamlValue(2030));
		assertEquals("4.5", RunConfigWriter.yamlValue(4.5));
		assertEquals("[a, b]", RunConfigWriter.yamlValue(List.of("a", "b")));
		assertEquals("[]", RunConfigWriter.yamlValue(List.of()));
		assertEquals("\"C:/a \\\"b\\\" \\\\c\"", RunConfigWriter.yamlValue("C:/a \"b\" \\c"));
		assertEquals("C:/a \"b\" \\c", CraftyRunConfig.parse("x: " + RunConfigWriter.yamlValue("C:/a \"b\" \\c")).get("x"));
	}
}
