package de.cesr.crafty.react.data;

/**
 * A problem with one of CRAFTY-react's input files: missing, unreadable, or holding values react cannot
 * use. The message names the file and says what is wrong, so it can be shown to the user as it is.
 *
 * The loaders throw this rather than stopping the run themselves, so that a caller can gather every
 * problem and report them together, and so that tests can check each problem.
 */
public class ReactInputException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ReactInputException(String message) {
		super(message);
	}

	public ReactInputException(String message, Throwable cause) {
		super(message, cause);
	}
}
