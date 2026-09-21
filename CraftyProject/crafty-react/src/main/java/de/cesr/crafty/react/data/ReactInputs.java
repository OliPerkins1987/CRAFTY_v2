package de.cesr.crafty.react.data;

/**
 * Everything CRAFTY-react has loaded: the startup checks' result, which does not change during a run,
 * and the current year's data.
 *
 * One year is held at a time. {@link #forYear(int)} loads a year the first time it is asked for and
 * drops the year before, which keeps memory flat however long the run is. Asking for the same year again
 * does not re-read the files.
 *
 * It belongs to the {@link de.cesr.crafty.react.ReactiveUpdater} that created it, rather than being
 * shared through a static field, so a second run in the same session starts clean.
 */
public final class ReactInputs {

	private final ReactConfig config;
	private final ReactRunContext context;
	private final ReactStartupCheck.Result checked;

	private ReactYearData current;

	private ReactInputs(ReactConfig config, ReactRunContext context, ReactStartupCheck.Result checked) {
		this.config = config;
		this.context = context;
		this.checked = checked;
	}

	/**
	 * Runs the startup checks and keeps what they loaded.
	 *
	 * @throws ReactInputException listing every problem, if the project cannot be used
	 */
	public static ReactInputs create(ReactConfig config, ReactRunContext context) {
		return new ReactInputs(config, context, ReactStartupCheck.run(config, context));
	}

	/** What the startup checks loaded: parameters, service key, base costs, cell key, and the year files. */
	public ReactStartupCheck.Result checked() {
		return checked;
	}

	/** What react knows about the model. */
	public ReactRunContext context() {
		return context;
	}

	/**
	 * One year's data, loaded on first use. The previous year is dropped, so only one year is held.
	 * With no element reactive, react changes nothing and the year holds no data.
	 *
	 * @throws ReactInputException if the year is outside the run, since only the run's years were checked
	 */
	public ReactYearData forYear(int year) {
		if (current != null && current.year() == year) {
			return current;
		}
		if (year < context.firstYear() || year > context.lastYear()) {
			throw new ReactInputException("CRAFTY-react has no data for year " + year + ": the run is "
					+ context.firstYear() + " to " + context.lastYear() + ", and only those years were checked");
		}
		current = null; // release the previous year before reading the next
		current = context.anyReactive() ? ReactYearData.load(config, context, checked, year) : ReactYearData.empty(year);
		return current;
	}

	/** The year held, or null before the first {@link #forYear(int)}. Only one year is ever held. */
	public ReactYearData currentYear() {
		return current;
	}
}
