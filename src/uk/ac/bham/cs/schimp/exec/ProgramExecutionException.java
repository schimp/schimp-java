package uk.ac.bham.cs.schimp.exec;

public class ProgramExecutionException extends RuntimeException {

	private static final long serialVersionUID = 5132256580361667218L;
	
	public ProgramExecutionException(String reason) {
		super(reason);
	}

}
