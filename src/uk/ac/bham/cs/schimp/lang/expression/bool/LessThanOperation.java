package uk.ac.bham.cs.schimp.lang.expression.bool;

import parser.State;
import uk.ac.bham.cs.schimp.lang.expression.arith.ArithmeticExpression;
import uk.ac.bham.cs.schimp.source.SyntaxCheckContext;
import uk.ac.bham.cs.schimp.source.SyntaxException;

public class LessThanOperation extends BooleanExpression {
	
	private ArithmeticExpression left;
	private ArithmeticExpression right;
	
	public LessThanOperation(ArithmeticExpression left, ArithmeticExpression right) {
		super();
		this.left = left;
		this.right = right;
	}
	
	@Override
	public void check(SyntaxCheckContext context) throws SyntaxException {
		left.check(context);
		right.check(context);
	}
	
	@Override
	public BooleanConstant evaluate(State state) {
		return new BooleanConstant(left.evaluate(state).toInteger() < right.evaluate(state).toInteger());
	}
	
	public String toString(int indent) {
		return indentation(indent) + "(" + left.toString() + " < " + right.toString() + ")";
	}

	public String toSourceString(int indent) {
		return indentation(indent) + "(" + left.toSourceString() + " < " + right.toSourceString() + ")";
	}

}
