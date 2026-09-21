package de.cesr.crafty.react.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reactive parameters sheet, {@code AFTs/react/Reactive_parameters.csv}: one row per AFT.
 *
 * <pre>
 * Label,react_isReactive,react_service,react_N_type,react_N_capital,react_N_par,react_I_eff,
 *      react_O_capital,react_O_par,react_S_par,react_R_par
 * </pre>
 *
 * The sheet holds only what makes an AFT move away from its baseline. The baselines come from core's
 * {@code AFTsMetaData.csv} through {@link ReactRunContext}: N from {@code Nfert_rate}, other intensity
 * from {@code Other_intensity}, and whether the AFT irrigates from {@code Irrigated}.
 *
 * Columns other than {@code Label} and the ten {@code react_} columns (for example {@code Type}) are
 * ignored. An unknown {@code react_} column is a problem, so that a mistyped column name is caught.
 *
 * Rows with {@code react_isReactive = 0} are not read further. For reactive rows, a value is only
 * <em>required</em> when its element is switched on, but every filled value must be a number and must
 * apply to the AFT's type, whatever the switches. The rules are in the 27b plan, §2–3.
 *
 * Problems are added to a list rather than thrown one at a time, so that every problem in the sheet is
 * reported together (see {@link ReactStartupCheck}).
 */
public final class ReactiveParameters {

	public static final String LABEL = "Label";
	public static final String IS_REACTIVE = "react_isReactive";
	public static final String SERVICE = "react_service";
	public static final String N_TYPE = "react_N_type";
	public static final String N_CAPITAL = "react_N_capital";
	public static final String N_PAR = "react_N_par";
	public static final String I_EFF = "react_I_eff";
	public static final String O_CAPITAL = "react_O_capital";
	public static final String O_PAR = "react_O_par";
	public static final String S_PAR = "react_S_par";
	public static final String R_PAR = "react_R_par";

	/** Every react_ column the sheet must have. */
	public static final List<String> REACT_COLUMNS = List.of(IS_REACTIVE, SERVICE, N_TYPE, N_CAPITAL, N_PAR, I_EFF,
			O_CAPITAL, O_PAR, S_PAR, R_PAR);

	private final Path file;
	private final List<String> labels;
	private final Map<String, AftReactParameters> reactive;

	private ReactiveParameters(Path file, List<String> labels, Map<String, AftReactParameters> reactive) {
		this.file = file;
		this.labels = labels;
		this.reactive = reactive;
	}

	/** Reads the sheet and throws one exception listing every problem, if there are any. */
	public static ReactiveParameters load(Path file, ReactServiceKey services, ReactRunContext context) {
		List<String> problems = new ArrayList<>();
		ReactiveParameters parameters = read(file, services, context, problems);
		if (!problems.isEmpty()) {
			throw new ReactInputException(String.join("\n", problems));
		}
		return parameters;
	}

	/**
	 * Reads the sheet, adding every problem found to {@code problems}. Rows with problems are left out
	 * of the result. Throws only if the sheet cannot be read at all or lacks a required column.
	 */
	public static ReactiveParameters read(Path file, ReactServiceKey services, ReactRunContext context,
			List<String> problems) {
		ReactCsv.Table table = ReactCsv.readAll(file);
		List<String> required = new ArrayList<>();
		required.add(LABEL);
		required.addAll(REACT_COLUMNS);
		table.requireColumns(required.toArray(new String[0]));
		for (String column : table.header()) {
			if (column.startsWith("react_") && !REACT_COLUMNS.contains(column)) {
				problems.add(file + ": column " + column + " is not a react column. The react columns are "
						+ REACT_COLUMNS);
			}
		}

		List<String> labels = new ArrayList<>();
		Map<String, AftReactParameters> reactive = new LinkedHashMap<>();
		for (int row = 0; row < table.rowCount(); row++) {
			Row r = new Row(table, row, problems);
			String label = r.text(LABEL);
			if (label.isEmpty()) {
				r.problem(LABEL + " is blank");
				continue;
			}
			if (labels.contains(label)) {
				r.problem("AFT " + label + " appears more than once");
				continue;
			}
			labels.add(label);

			String isReactive = r.text(IS_REACTIVE);
			if (isReactive.equals("0")) {
				continue;
			}
			if (!isReactive.equals("1")) {
				r.problem(IS_REACTIVE + " must be 0 or 1, not \"" + isReactive + "\"");
				continue;
			}
			AftReactParameters parameters = readReactiveRow(r, label, services, context);
			if (parameters != null && !r.hasProblems()) {
				reactive.put(label, parameters);
			}
		}
		return new ReactiveParameters(file, Collections.unmodifiableList(labels),
				Collections.unmodifiableMap(reactive));
	}

	private static AftReactParameters readReactiveRow(Row r, String label, ReactServiceKey services,
			ReactRunContext context) {
		String service = r.text(SERVICE);
		if (service.isEmpty()) {
			r.problem(SERVICE + " is blank, but the AFT is reactive");
			return null;
		}
		if (!services.contains(service)) {
			r.problem(SERVICE + " " + service + " is not in " + services.file());
			return null;
		}
		if (!context.services().contains(service)) {
			r.problem(SERVICE + " " + service + " is not one of the model's services");
		}
		LpjgType type = services.lpjgType(service);
		if (type == null) {
			r.problem("service " + service + " has no LPJG_type in " + services.file()
					+ ", so react has no LPJ-GUESS input for it");
			return null;
		}
		if (type == LpjgType.FORESTRY) {
			r.problem("service " + service + " is forestry, which is not implemented yet; set " + IS_REACTIVE + " = 0");
			return null;
		}
		ReactRunContext.AftBaseline baseline = context.afts().get(label);
		if (baseline == null) {
			// Reported by the AFT-list check in ReactStartupCheck.
			return null;
		}

		String nTypeText = r.text(N_TYPE);
		NitrogenMode nMode = null;
		if (!nTypeText.isEmpty()) {
			nMode = NitrogenMode.fromLabel(nTypeText);
			if (nMode == null) {
				r.problem(N_TYPE + " must be Prospect or Capital, not \"" + nTypeText + "\"");
			}
		}
		String nCapital = r.textOrNull(N_CAPITAL);
		Double nPar = r.number(N_PAR);
		Double iEff = r.number(I_EFF);
		String oCapital = r.textOrNull(O_CAPITAL);
		Double oPar = r.number(O_PAR);
		Double sPar = r.number(S_PAR);
		Double rPar = r.number(R_PAR);

		boolean fertiliser = context.isReactive(ReactElement.FERTILISER);
		boolean irrigation = context.isReactive(ReactElement.IRRIGATION);
		boolean otherIntensity = context.isReactive(ReactElement.OTHER_INTENSITY);

		if (baseline.nfertRate() < 0) {
			r.problem("Nfert_rate in AFTsMetaData.csv is negative (" + baseline.nfertRate() + ")");
		}

		if (type == LpjgType.PASTURE) {
			for (String column : List.of(N_TYPE, N_CAPITAL, N_PAR, I_EFF)) {
				if (!r.text(column).isEmpty()) {
					r.problem(column + " does not apply to a pasture AFT; leave it blank");
				}
			}
			nMode = null;
			if (otherIntensity) {
				r.require(O_CAPITAL, "a pasture AFT when other intensity is reactive");
				r.require(O_PAR, "a pasture AFT when other intensity is reactive");
			}
			if (oPar != null && oPar < 0) {
				r.problem(O_PAR + " must be 0 or more for a pasture AFT, not " + oPar);
			}
			if (sPar == null) {
				sPar = 0.0;
			} else if (sPar < 0) {
				r.problem(S_PAR + " must be 0 or more, not " + sPar);
			}
			if (!(baseline.otherIntensity() > 0)) {
				r.problem("Other_intensity in AFTsMetaData.csv must be above 0 for a reactive pasture AFT, not "
						+ baseline.otherIntensity());
			}
		} else {
			if (!r.text(S_PAR).isEmpty()) {
				r.problem(S_PAR + " does not apply to a crops AFT; leave it blank");
			}

			// Fertiliser.
			if (nMode == NitrogenMode.PROSPECT && nCapital != null) {
				r.problem(N_CAPITAL + " does not apply to a Prospect AFT; leave it blank");
			}
			if (fertiliser) {
				if (nTypeText.isEmpty()) {
					r.require(N_TYPE, "a crops AFT when fertiliser is reactive");
				}
				if (!(baseline.nfertRate() > 0)) {
					r.problem("Nfert_rate in AFTsMetaData.csv must be above 0 when fertiliser is reactive: it is the N"
							+ " baseline, and core only charges N costs to AFTs with Nfert_rate > 0");
				}
				if (nMode == NitrogenMode.CAPITAL) {
					r.require(N_CAPITAL, "a Capital AFT when fertiliser is reactive");
					r.require(N_PAR, "a Capital AFT when fertiliser is reactive");
				}
			}
			if (nMode == NitrogenMode.PROSPECT) {
				if (nPar == null) {
					nPar = 0.0;
				} else if (nPar < 0) {
					r.problem(N_PAR + " (the inertia threshold for a Prospect AFT) must be 0 or more, not " + nPar);
				}
			}
			if (nMode == NitrogenMode.CAPITAL && nPar != null && nPar <= 0) {
				r.problem(N_PAR + " (the capital multiplier) must be above 0, not " + nPar);
			}

			// Irrigation.
			if (iEff != null && !(iEff > 0 && iEff <= 1)) {
				r.problem(I_EFF + " must be above 0 and at most 1, not " + iEff);
			}
			if (irrigation) {
				if (baseline.irrigated() && iEff == null) {
					r.problem("the AFT is irrigated (Irrigated = 1 in AFTsMetaData.csv), so " + I_EFF
							+ " is required when irrigation is reactive");
				}
				if (!baseline.irrigated() && iEff != null) {
					r.problem(I_EFF + " is filled, but the AFT is not irrigated (Irrigated = 0 in AFTsMetaData.csv)");
				}
			}

			// Other intensity.
			if ((oCapital == null) != (r.text(O_PAR).isEmpty())) {
				r.problem("fill both " + O_CAPITAL + " and " + O_PAR + ", or leave both blank (blank: other intensity"
						+ " stays at Other_intensity)");
			}
			if (oPar != null && !(oPar >= 0 && oPar < 1)) {
				r.problem(O_PAR + " (the Eff_func threshold) must be at least 0 and below 1 for a crops AFT, not " + oPar);
			}
			if (!(baseline.otherIntensity() >= 0 && baseline.otherIntensity() <= 1)) {
				r.problem("Other_intensity in AFTsMetaData.csv must be between 0 and 1 for a reactive crops AFT, not "
						+ baseline.otherIntensity());
			}
		}

		return new AftReactParameters(label, service, type, services.lpjgName(service), nMode, nCapital, nPar, iEff,
				oCapital, oPar, sPar, rPar, baseline);
	}

	public Path file() {
		return file;
	}

	/** Every label in the sheet, reactive or not, in sheet order. */
	public List<String> labels() {
		return labels;
	}

	/** The reactive AFTs, in sheet order. */
	public Collection<AftReactParameters> reactive() {
		return reactive.values();
	}

	/** The reactive AFTs of one type, in sheet order. */
	public List<AftReactParameters> reactive(LpjgType type) {
		return reactive.values().stream().filter(p -> p.type() == type).toList();
	}

	/** A reactive AFT's parameters, or null if the AFT is not reactive. */
	public AftReactParameters get(String label) {
		return reactive.get(label);
	}

	public boolean isReactive(String label) {
		return reactive.containsKey(label);
	}

	/** The services the reactive AFTs use, in sheet order. */
	public Set<String> servicesInUse() {
		Set<String> services = new LinkedHashSet<>();
		reactive.values().forEach(p -> services.add(p.service()));
		return services;
	}

	/** The capitals the reactive AFTs read for the switched-on elements, in sheet order. */
	public Set<String> capitalsNamed(Set<ReactElement> reactiveElements) {
		Set<String> capitals = new LinkedHashSet<>();
		reactive.values().forEach(p -> capitals.addAll(p.capitalsNamed(reactiveElements)));
		return capitals;
	}

	/** One row of the sheet, with its problems recorded against its line and label. */
	private static final class Row {
		private final ReactCsv.Table table;
		private final int row;
		private final List<String> problems;
		private int count;

		Row(ReactCsv.Table table, int row, List<String> problems) {
			this.table = table;
			this.row = row;
			this.problems = problems;
		}

		String text(String column) {
			return table.get(row, column);
		}

		String textOrNull(String column) {
			String text = text(column);
			return text.isEmpty() ? null : text;
		}

		/** A number, or null if blank. Text that is not a number is a problem, whatever the switches. */
		Double number(String column) {
			String text = text(column);
			if (text.isEmpty()) {
				return null;
			}
			try {
				return table.number(row, column);
			} catch (ReactInputException e) {
				problem(column + " should be a number but is \"" + text + "\"");
				return null;
			}
		}

		void require(String column, String when) {
			if (text(column).isEmpty()) {
				problem(column + " is required for " + when);
			}
		}

		void problem(String message) {
			String label = table.get(row, LABEL);
			problems.add(table.file() + ", line " + table.lineNumber(row) + (label.isEmpty() ? "" : " (" + label + ")")
					+ ": " + message);
			count++;
		}

		boolean hasProblems() {
			return count > 0;
		}
	}
}
