package de.cesr.crafty.landsymm;

import java.util.List;

/**
 * A coupling input or set-up that the coupler cannot use. It carries every problem found, so they can be reported
 * together; {@link MainLandSymm} and the coupler's steps turn it into one fatal log message, which stops the run.
 */
public class CouplingInputException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final List<String> problems;

	public CouplingInputException(String problem) {
		this(List.of(problem));
	}

	public CouplingInputException(List<String> problems) {
		super(String.join("\n", problems));
		this.problems = List.copyOf(problems);
	}

	/** Each problem on its own, in the order found. */
	public List<String> getProblems() {
		return problems;
	}
}
