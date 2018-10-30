package uk.ac.bham.cs.schimp.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import explicit.DTMC;
import explicit.DTMCModelChecker;
import explicit.StateValues;
import parser.State;
import parser.Values;
import parser.VarList;
import parser.ast.Declaration;
import parser.ast.DeclarationInt;
import parser.ast.Expression;
import parser.type.Type;
import parser.type.TypeInt;
import prism.ModelGenerator;
import prism.ModelType;
import prism.Prism;
import prism.PrismException;
import prism.PrismLangException;

public class AttackerModelGenerator implements ModelGenerator {
	
	private Prism prism;
	
	// the indices of various pieces of information in the prism State object's variables array; cumulative elapsed time
	// and consumed power are only present as variables if stateTime and statePower respectively are set to true in the
	// call to the constructor, otherwise they will be omitted and their indices will be -1
	private int stateOutputIDIndex = 1;
	private int stateTimeIndex = -1;
	private int statePowerIndex = -1;
	
	// the names of the initial variables whose values are tracked in prism State objects
	private List<String> stateInitialVars;
	private int stateInitialVarsOffset = 2;
	
	// the prism State object that is currently being explored
	private State exploringState;
	
	// various information relating to the State objects that succeed the one currently being explored
	private List<State> succeedingStates;
	private List<Double> succeedingStateProbabilities;
	private int succeedingChoices;
	private int succeedingTransitions;
	
	// the names of the variables defined in each prism State object (some of which are hidden, some of which are
	// observable)
	private List<String> prismVarNames = new ArrayList<String>();
	private List<String> prismObservableVarNames = new ArrayList<String>();
	
	// the types of the variables defined in each prism State object
	private List<Type> prismVarTypes;
	
	// a cartesian product iterator for the possible values of initial variables recorded in prism State objects
	private VariableValueCartesianProduct varValueProduct;
	
	// a barebones prism State object that is used as the basis for creating phase-2 State objects
	private State emptyAttackerGuessedState;
	
	//==========================================================================
	
	public static AttackerModelGenerator fromSCHIMPModel(Prism prism, PRISMModelGenerator schimpModelGenerator) {
		return new AttackerModelGenerator(prism, schimpModelGenerator);
	}
	
	private AttackerModelGenerator(Prism prism, PRISMModelGenerator schimpModelGenerator) {
		this.prism = prism;
		
		stateInitialVars = schimpModelGenerator.stateInitialVariableNames();
		
		// the variables defined in each prism State object (some of which are carried over from the prism State objects
		// generated by the PRISMModelGenerator) are:
		int varIndex = 1; // 0 = "_phase", 1 = "_oid"; always present
		prismVarNames.add("_phase");
		prismObservableVarNames.add("_phase");
		// - the unique id representing the list of outputs observed from the schimp program (we don't actually need to
		//   know the exact contents of these lists, only which ones are used by each state)
		prismVarNames.add("_oid");
		prismObservableVarNames.add("_oid");
		// - the cumulative elapsed time of the schimp program (if present in PRISMModelGenerator states)
		if (schimpModelGenerator.stateHasTime()) {
			prismVarNames.add("_time");
			prismObservableVarNames.add("_time");
			stateTimeIndex = ++varIndex;
		} else {
			stateTimeIndex = -1;
		}
		// - the cumulative power consumption of the schimp program (if present in PRISMModelGenerator states)
		if (schimpModelGenerator.stateHasPower()) {
			prismVarNames.add("_power");
			prismObservableVarNames.add("_power");
			statePowerIndex = ++varIndex;
		} else {
			statePowerIndex = -1;
		}
		// - the names of the schimp program's initial variables recorded in the prism state, so they can be included
		//   in prism property queries
		prismVarNames.addAll(schimpModelGenerator.stateInitialVariableNames());
		stateInitialVarsOffset = ++varIndex;
		
		// these variables are all integers
		prismVarTypes = prismVarNames.stream()
			.map(v -> TypeInt.getInstance())
			.collect(Collectors.toList());
		
		varValueProduct = new VariableValueCartesianProduct(
			schimpModelGenerator.getProgram().getInitialCommands().stream()
				.filter(c -> stateInitialVars.indexOf(c.getVariableReference().getName()) != -1)
				.collect(Collectors.toList())
		);
		
		// the barebones State object for "_phase" = 2 contains the phase id 2 and -1 everywhere else; the indices
		// representing initial variables will be set when createStateFromAttackerGuesses() is called
		emptyAttackerGuessedState = new State(prismVarNames.size());
		emptyAttackerGuessedState.varValues = new Object[prismVarNames.size()];
		Arrays.fill(emptyAttackerGuessedState.varValues, -1);
		emptyAttackerGuessedState.varValues[0] = 2;
	}
	
	public int getStateOutputIDIndex() {
		return stateOutputIDIndex;
	}
	
	public boolean stateHasTime() {
		return stateTimeIndex != -1;
	}
	
	public int getStateTimeIndex() {
		return stateTimeIndex;
	}
	
	public boolean stateHasPower() {
		return statePowerIndex != -1;
	}
	
	public int getStatePowerIndex() {
		return statePowerIndex;
	}
	
	public List<String> stateInitialVariableNames() {
		return stateInitialVars;
	}
	
	public int getStateInitialVariablesOffset() {
		return stateInitialVarsOffset;
	}
	
	//==========================================================================
	// the attacker's ability is modelled as a partially-observable markov decision process
	
	@Override
	public ModelType getModelType() {
		return ModelType.POMDP;
	}
	
	//==========================================================================
	// the prism State objects created by this model generator contain the following variables (all integers):
	// - "_phase": an integer describing the current phase:
	//             - 0 if this is the initial state
	//             - 1 if the attacker is yet to guess the value of each secret variable being tracked
	//             - 2 if the attacker has guessed the value of each secret variable being tracked
	// - "_oid": a unique id representing a (stringified) list of outputs observed during the schimp program's execution
	// - "_time": the elapsed time of the schis yet toimp program (if stateTime is true)
	// - "_power": the power consumption of the schimp program (if statePower is true)
	// - "i1".."in": one variable representing each initial variable declared in the schimp program whose value is
	//               recorded in the prism State object, in the order in which the initial variables were declared in
	//               the schimp program; the purpose of this variable depends on whether the attacker has guessed the
	//               value of each secret variable yet (see "_guessed"):
	//               - if "_guessed" = 0, each variable contains the value of the secret variable at the point at which
	//                 it was declared in the program
	//               - if "_guessed" = 1, each variable contains the value 0 if the attacker's guess for the variable's
	//                 value was incorrect, or 1 if it was correct
	
	@Override
	public int getNumVars() {
		return prismVarTypes.size();
	}
	
	@Override
	public List<String> getVarNames() {
		return prismVarNames;
	}

	@Override
	public List<Type> getVarTypes() {
		return prismVarTypes;
	}
	
	@Override
	public List<String> getObservableVars() {
		return prismObservableVarNames;
	}
	
	@Override
	public int getVarIndex(String name) {
		return getVarNames().indexOf(name);
	}

	@Override
	public String getVarName(int i) {
		return getVarNames().get(i);
	}
	
	@Override
	public VarList createVarList() {
		VarList varList = new VarList();
		try {
			varList.addVar(new Declaration("_phase", new DeclarationInt(Expression.Int(0), Expression.Int(2))), 0, null);
			varList.addVar(new Declaration("_oid", new DeclarationInt(Expression.Int(-1), Expression.Int(Integer.MAX_VALUE))), 0, null);
			if (stateTimeIndex != -1) varList.addVar(new Declaration("_time", new DeclarationInt(Expression.Int(-1), Expression.Int(Integer.MAX_VALUE))), 0, null);
			if (statePowerIndex != -1) varList.addVar(new Declaration("_power", new DeclarationInt(Expression.Int(-1), Expression.Int(Integer.MAX_VALUE))), 0, null);
			for (int i = stateInitialVarsOffset; i < prismVarNames.size(); i++) {
				varList.addVar(new Declaration(prismVarNames.get(i), new DeclarationInt(Expression.Int(Integer.MIN_VALUE), Expression.Int(Integer.MAX_VALUE))), 0, null);
			}
		} catch (PrismLangException e) {}
		return varList;
	}
	
	@Override
	public boolean containsUnboundedVariables() {
		// prism States don't contain any unbounded variables
		return false;
	}

	@Override
	public Values getConstantValues() {
		// prism States don't contain any constant values
		return new Values();
	}
	
	@Override
	public void setSomeUndefinedConstants(Values someValues) throws PrismException {
		// prism States don't contain any constant values
		if (someValues != null && someValues.getNumValues() > 0) {
			throw new PrismException("This model has no constants to set");
		}
	}
	
	//==========================================================================
	// labels are not used
	
	@Override
	public int getNumLabels() {
		return 0;
	}
	
	@Override
	public List<String> getLabelNames() {
		return Collections.<String>emptyList();
	}
	
	@Override
	public int getLabelIndex(String name) {
		return -1;
	}

	@Override
	public String getLabelName(int i) throws PrismException {
		throw new PrismException("Label number \"" + i + "\" not defined");
	}
	
	@Override
	public boolean isLabelTrue(String label) throws PrismException {
		throw new PrismException("Label \"" + label + "\" not defined");
	}

	@Override
	public boolean isLabelTrue(int i) throws PrismException {
		throw new PrismException("Label number \"" + i + "\" not defined");
	}
	
	//==========================================================================
	
	@Override
	public boolean hasSingleInitialState() throws PrismException {
		return true;
	}
	
	@Override
	public State getInitialState() throws PrismException {
		State state = new State(prismVarTypes.size());
		
		// "_phase" is always 0, indicating that this is the initial state; all other variable values are meaningless,
		// but must be non-null to avoid NullPointerExceptions in prism code
		state.setValue(0, 0);
		Arrays.fill(state.varValues, 1, state.varValues.length, -1);
		
		return state;
	}
	
	@Override
	public List<State> getInitialStates() throws PrismException {
		return Collections.singletonList(getInitialState());
	}
	
	@Override
	public State getExploreState() {
		return exploringState;
	}
	
	@Override
	public void exploreState(State exploreState) throws PrismException {
		exploringState = exploreState;
		
		// this state's set of succeeding states is determined by the phase:
		switch ((int)exploringState.varValues[0]) {
			// - if _phase = 0, this is the initial state; the succeeding states are the terminating states of the
			//   schimp program from the model built by the PRISMModelGenerator
			case 0:
				DTMCModelChecker modelChecker = new DTMCModelChecker(prism);
				// TODO: don't hardcode time horizon to 30 here
				StateValues steadyState = modelChecker.doTransient((DTMC)prism.getBuiltModelExplicit(), 30);
				
				// iterate over each of the terminating states in the generated prism model, and create new states for
				// this model based on them; the variables in each state are the same with the exception of the first,
				// which is "_phase" (always 1) rather than "_cid" (which isn't necessary in the attacker model)
				PRISMStateMap stateMap = new PRISMStateMap();
				List<State> states = prism.getBuiltModelExplicit().getStatesList();
				double[] stateProbabilities = steadyState.getDoubleArray();
				int stateLength = states.get(0).varValues.length;
				int stateCommonSubsetLength = states.get(0).varValues.length - 1;
				for (int i = 0; i < stateProbabilities.length; i++) {
					if (stateProbabilities[i] > 0) {
						// TODO: correctly deal with the case in which self-loop states from the model are actually
						// non-terminating infinite-loop states
						//System.out.println(i + ": terminating or infinite loop state");
						State s = new State(stateLength);
						s.setValue(0, 1); // "_phase" = 1
						System.arraycopy(states.get(i).varValues, 1, s.varValues, 1, stateCommonSubsetLength);
						stateMap.add(s, stateProbabilities[i]);
					}
				}
				
				/*
				List<State> termStates = stateMap.getStates();
				for (int j = 0; j < termStates.size(); j++) {
					System.out.println(termStates.get(j).toString() + " -> " + stateMap.getProbabilities().get(j));
				}
				*/
				
				succeedingStates = stateMap.getStates();
				succeedingStateProbabilities = stateMap.getProbabilities();
				succeedingChoices = 1;
				succeedingTransitions = succeedingStates.size();
				break;
			// - if _phase = 1, this is a schimp terminating state; the non-probabilistic choices leaving this state are
			//   the attacker's possible guesses for each of the initial variables, and the succeeding states contain
			//   the success of these guesses
			case 1:
				succeedingStates = null;
				succeedingStateProbabilities = null;
				succeedingChoices = varValueProduct.size();
				succeedingTransitions = 1;
				break;
			// - if _phase = 2, the attacker has finished guessing; nothing more needs to be done
			case 2:
				succeedingStates = Collections.singletonList(new State(exploreState));
				succeedingStateProbabilities = Collections.singletonList(1.0);
				succeedingChoices = 1;
				succeedingTransitions = 1;
				break;
		}
	}
	
	private State createStateFromAttackerGuesses(State state, VariableScopeFrame guesses) {
		State newState = new State(emptyAttackerGuessedState);
		
		for (int i = stateInitialVarsOffset; i < prismVarNames.size(); i++) {
			try {
				newState.setValue(i,
					guesses.evaluate(prismVarNames.get(i)).toFraction().intValue() == (int)state.varValues[i] ?
					1 : // correct guess for the value of this initial variable
					0   // incorrect guess for the value of this initial variable
				);
			} catch (ProgramExecutionException e) {
				// evaluate() throws a ProgramExecutionException if the given string is not a defined variable name, but
				// this should never happen here
			}
		}
		
		return newState;
	}
	
	//==========================================================================
	// non-deterministic choice is only used in transition from phase 1 to phase 2, when the schimp program has
	// terminated; in this situation, the non-deterministic choice is the attacker's guess for the value of each initial
	// variable recorded in the prism State object, and the action label for each choice is a stringified
	// VariableScopeFrame representing the attacker's guesses made in that choice
	
	@Override
	public int getNumChoices() throws PrismException {
		return succeedingChoices;
	}
	
	@Override
	public Object getChoiceAction(int i) throws PrismException {
		return (int)exploringState.varValues[0] == 1 ?
			varValueProduct.get(i).toShortString() :
			null;
	}
	
	//==========================================================================
	
	@Override
	public int getNumTransitions() throws PrismException {
		return succeedingTransitions;
	}
	
	@Override
	public int getNumTransitions(int i) throws PrismException {
		// this has been precomputed in exploreState(), so the value of i has no special significance here
		return succeedingTransitions;
	}
	
	@Override
	public State computeTransitionTarget(int i, int offset) throws PrismException {
		return (int)exploringState.varValues[0] == 1 ?
			createStateFromAttackerGuesses(exploringState, varValueProduct.get(i)) :
			succeedingStates.get(offset);
	}
	
	@Override
	public double getTransitionProbability(int i, int offset) throws PrismException {
		return (int)exploringState.varValues[0] == 1 ?
			1.0 :
			succeedingStateProbabilities.get(offset);
	}

	@Override
	public Object getTransitionAction(int i) throws PrismException {
		return (int)exploringState.varValues[0] == 1 ?
			varValueProduct.get(i).toShortString() :
			null;
	}

	@Override
	public Object getTransitionAction(int i, int offset) throws PrismException {
		return getTransitionAction(i);
	}

}
