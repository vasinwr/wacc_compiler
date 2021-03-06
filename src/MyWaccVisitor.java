import SemanticAnalyser.*;
import backEnd.*;
import antlr.WaccParser;
import antlr.WaccParser.ExprContext;
import antlr.WaccParser.FuncContext;
import antlr.WaccParser.Func_ifContext;
import antlr.WaccParser.Func_standardContext;
import antlr.WaccParser.ParamContext;
import antlr.WaccParserBaseVisitor;

import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.TerminalNode;
//import sun.jvm.hotspot.debugger.cdbg.Sym;




import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyWaccVisitor extends WaccParserBaseVisitor<Info> {
    SymbolTable currentTable = new SymbolTable(null);
    
    List<Instruction> instrList = new ArrayList<Instruction>();
	List<Instruction> functList = new ArrayList<Instruction>();
    List<Instruction> currentList;

	List<Instruction> header = new ArrayList<Instruction>();
    List<Instruction> footer = new ArrayList<Instruction>();
    InstrBuilder ib = new InstrBuilder();
    ErrorMessager err = new ErrorMessager();
    int stackTotal = 0;
    int msgCount = 15;
    int regCount = 4;
    Map<String, Integer> stackMap = new HashMap<String, Integer>();
	Map<String, Integer> currentStackMap = stackMap;


	Map<String, Integer> paramOffsetMap = new HashMap<String, Integer>();
	Integer paramSizeCount = -999;

	protected int ifCount = -1;

	protected int whileCount = -1;

	boolean fstVisited = false ;
	
	boolean prints = false;
	final String filename;

	int funcCallOffset = 0;

	int freepairs = 0;
	int newpairs = 0;

	// for control flow analysis 
	protected boolean controlFlowTrue = false;
	protected boolean controlFlowFalse = false;
	protected boolean infiniteLoop = false;
	//---------------

	public MyWaccVisitor(String filename) {
		
		this.filename = filename;
		
	}

	@Override
    public Info visitStat_stat(@NotNull WaccParser.Stat_statContext ctx) {

        WaccParser.StatContext first = ctx.stat(0); 
        WaccParser.StatContext second = ctx.stat(1); 
        visit(first);
        visit(second); 
        ctx.typename = second.typename;

    	return null;

    }

    @Override 
    public Info visitStat_assign(@NotNull WaccParser.Stat_assignContext ctx) {
    	if (prints) System.out.println("visitStat_assign");
        WaccParser.Assign_lhsContext lhs = ctx.assign_lhs();
        WaccParser.Assign_rhsContext rhs = ctx.assign_rhs();
        
        visit(rhs);
        Info left = visit(lhs);    

        if(lhs.typename == null){
          if (prints) System.out.println("assign to unknown");
          System.exit(200);
        }
        if(rhs.typename == null){
          if (prints) System.out.println("assigning unknown");
          System.exit(200);
        }
        if (prints) System.out.println("lhs typename " + lhs.typename);
        if (prints) System.out.println("rhs typename " + rhs.typename);

        if(rhs.typename instanceof NULL){
        	currentList.add(new Instruction("LDR r" + regCount + ", [sp]\n"));
        }
        if ((!SharedMethods.assignCompat(lhs.typename, rhs.typename)))  {
            if (prints) System.out.println("HERE");
        	System.exit(200);
        }
        
        if (left != null) {
        	String store = ((typeSize(rhs.typename) == 1)? "STRB" : "STR");
        	currentList.add(ib.instr().withString(store + " r" + regCount + ", [sp").withVar(left.stringinfo).withString("]").build());

        }
    	return null;
    }

    @Override 
    public Info visitStat_declare(@NotNull WaccParser.Stat_declareContext ctx) {
    	if (prints) System.out.println("visitStat_declare");
  	
      WaccParser.Assign_rhsContext rhs = ctx.assign_rhs();
      visit(rhs);
      if (prints) System.out.println("SEP: ");
      visit(ctx.type());

      if (prints) System.out.println("After visit declare lhs and rhs");
      if (prints) System.out.println("declare rhs: " + rhs.typename);
      if (prints) System.out.println("declare lhs: " + ctx.type().typename);
      
      //---------------------- catching declaration of array with empty array ie int[] x = [] should be fine
      
      if(ctx.type().typename instanceof ARRAY_TYPE) {
        if(rhs.typename instanceof ARRAY_TYPE) {
          if(((ARRAY_TYPE)rhs.typename).TYPE() == null){
            VARIABLE var = new VARIABLE(rhs.typename);
            currentTable.add(ctx.ident().getText(), var);
            return null;
          }
        }
      }
      //------------------------
      
      if(rhs.typename == null) {
    	  rhs.typename = new NULL();
      }
      
      if(!SharedMethods.assignCompat(ctx.type().typename, rhs.typename)) {
 //   	  throw new Error("Different type");
      	  System.exit(200);
      }

      if (currentTable.lookup(ctx.ident().getText()) != null) {
 //   	  throw new Error("Variable already declared");
    	  if (prints) System.out.println("var already declared: " + ctx.ident().getText());
      	  System.exit(200);
      }

      VARIABLE var = new VARIABLE(rhs.typename);
      currentTable.add(ctx.ident().getText(), var);
      int i = typeSize(ctx.type().typename);
      PositionFragment position= new PositionFragment(i);
      stackTotal += i;
      if (i == 1) {
    	  currentList.add(new Instruction(Arrays.asList(new StringFragment("STRB r"+ regCount + ", [sp"), position, new StringFragment("]\n")), new VariableFragment(ctx.ident().getText()), position));
      } else {
    	 currentList.add(new Instruction(Arrays.asList(new StringFragment("STR r" + regCount + ", [sp"), position, new StringFragment("]\n")), new VariableFragment(ctx.ident().getText()), position));
      }
      
      if (rhs.typename instanceof NULL) {
          currentList.add(new Instruction("LDR r"+ regCount + (fstVisited ? ", [sp, #4]\n" : ", [sp]\n")));
          fstVisited = false;
      }
      
      
      if (rhs.typename instanceof NULL && ctx.type().typename instanceof PAIR_TYPE) {
    	  newpairs++;
    	  System.out.println("LHS IS NULL RHS IS PAIRR");
      }
      
  	  return null;
    }

    int typeSize(TYPE t) {
		if (t instanceof INT || t instanceof PAIR_TYPE || t instanceof STRING || t instanceof ARRAY_TYPE) {
			return 4;
		}
		if (t instanceof CHAR || t instanceof BOOL) {
			return 1;
		}
		if (prints) System.out.println("failed to get typeSize");;
		return 4; //helps in a few cases where rhs pair is null, not sure if this will mess with other things
	}

	@Override
    public Info visitFunc_standard(@NotNull WaccParser.Func_standardContext ctx) {
		// backend: currentList is set to functList at visitProgram
		if (prints) System.out.println("visitFunc_std");
		///IDENTIFIER id = currentTable.lookupAllFunc(ctx.ident().getText());
		///if(((FUNCTION) id).symtab != null) System.exit(200);

		visit(ctx.type());

		TYPE returntypename = ctx.type().typename;

		SymbolTable newST = new SymbolTable(currentTable);


		ctx.funObj = new FUNCTION(returntypename);
		///currentTable.funcadd(ctx.ident().getText(), ctx.funObj);
		ctx.funObj.symtab = newST;
		currentTable = newST;

		String paramTypesString = "";
		if(ctx.param_list() != null){

			Info paramlistTypes = visit(ctx.param_list());
			paramTypesString = paramlistTypes.type;

			List <ParamContext> params = ctx.param_list().param();
			for(ParamContext p : params){
				ctx.funObj.formals.add(p.paramObj);
			}
			if (prints) System.out.println("Before stat");
		}

		// overloading func: after visiting paramlist, then addfunc (id + types) to the table //
		// since we are in symtab of this function, we need to look up all from the encSymtab
		String funcNameWithTypes = ctx.ident().getText() + paramTypesString;
		System.out.println(funcNameWithTypes);
		IDENTIFIER id = currentTable.encSymTable.lookupAllFunc(funcNameWithTypes);
		//System.out.println(id);
		if(((FUNCTION) id).symtab != null) System.exit(200);
		currentTable.encSymTable.funcadd(funcNameWithTypes, ctx.funObj);

		if (!(ctx.stat() == null)){
			visit(ctx.stat());
		}
		visit(ctx.stat_return());

		//backend
		wrapFunctInstr(funcNameWithTypes);
		//

		if(!SharedMethods.assignCompat(ctx.stat_return().typename, returntypename)) {

			System.exit(200);
		}

		currentTable = currentTable.encSymTable;
		return null;
    }


	private void wrapFunctInstr(String funcName){
		int tempTotal = stackTotal;
		makeFuncReady();

		String functionStr = "";
		Iterator<Instruction> it = functList.iterator();
		while(it.hasNext()){
			Instruction each = it.next();
			if(!(each instanceof Instruction_Function)) {
				functionStr += each.toString();
				it.remove();
			}
		}
		if(tempTotal > 0){
			functionStr = "SUB sp, sp, #" + tempTotal + "\n" + functionStr;
		}
		functionStr = "f_" + funcName + ":\n" + "PUSH {lr}\n" + functionStr + "POP {pc}\n.ltorg\n";
		currentList.add(new Instruction_Function(functionStr));
		currentStackMap.clear();
		paramOffsetMap.clear();
	}


	private void makeFuncReady(){
		Set<String> keys = paramOffsetMap.keySet();
		for(String eachKeys : keys){
			currentStackMap.put(eachKeys, paramOffsetMap.get(eachKeys) + stackTotal);
		}

		currentStackMap.put("total", stackTotal);
		for(Instruction instr: functList) {
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos()) {
				instr.varsToPos(currentStackMap, 0);
			}
		}
		currentStackMap.remove("total");
	}
    
    @Override
    public Info visitFunc_if(@NotNull WaccParser.Func_ifContext ctx) {
		//currentList is now set to functList by visitProgram
    	if (prints) System.out.println("visitFunc_if");
		///IDENTIFIER id = currentTable.lookupAllFunc(ctx.ident().getText());
		///if(((FUNCTION) id).symtab != null) System.exit(200);

		visit(ctx.type());

		TYPE returntypename = ctx.type().typename;

		SymbolTable newST = new SymbolTable(currentTable);
		


		ctx.funObj = new FUNCTION(returntypename);
		///currentTable.funcadd(ctx.ident().getText(), ctx.funObj);
		ctx.funObj.symtab = newST;
		currentTable = newST;

		String paramTypesString = "";
		if(ctx.param_list() != null){
			Info paramlistTypes = visit(ctx.param_list());
			paramTypesString = paramlistTypes.type;

			List <ParamContext> params = ctx.param_list().param();
			for(ParamContext p : params){
				ctx.funObj.formals.add(p.paramObj);
			}
			if (prints) System.out.println("Before stat");
		}

		// overloading func: after visiting paramlist, then addfunc (id + types) to the table //
		// since we are in symtab of this function, we need to look up all from the encSymtab
		String funcNameWithTypes = ctx.ident().getText() + paramTypesString;
		IDENTIFIER id = currentTable.encSymTable.lookupAllFunc(funcNameWithTypes);
		if(((FUNCTION) id).symtab != null) System.exit(200);
		currentTable.encSymTable.funcadd(funcNameWithTypes, ctx.funObj);
		
		if (ctx.stat() != null) {
			visit(ctx.stat());
		}
		visit(ctx.if_layers());

		//backend
		wrapFunctInstr(funcNameWithTypes);
		//
		
		if (!SharedMethods.assignCompat(ctx.if_layers().typename, returntypename)){
        	System.exit(200);
		}

		currentTable = currentTable.encSymTable;
		return null;
    }
    
	@Override public Info visitLayer_s_s(@NotNull WaccParser.Layer_s_sContext ctx) {
		if (prints) System.out.println("visitLayer_s_s");
		//backend
		ifCount++ ;
		//
		if (prints) System.out.println("visitStat_if");
		visit(ctx.expr());
		if (prints) System.out.println("expr = "+ ctx.expr().toString());

		currentTable = new SymbolTable(currentTable);

		//backend - after visit expr
		int currentIfLable = ifCount * 2;
		currentList.add(new Instruction("CMP r" + regCount + ", #0\n"));
		currentList.add(new Instruction("BEQ L" + (currentIfLable) + "\n"));
		//

		if(!(SharedMethods.assignCompat(ctx.expr().typename, new BOOL()))){
			System.out.print("if condition is not of type bool");
			System.exit(200);
		}

		//backend - before visit then-stat
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//

		if (ctx.stat(0) != null) {
			visit(ctx.stat(0));
		}
		visit(ctx.stat_return(0));

		//backend - after visit first stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("B L" + (currentIfLable+1) + "\n"));
		//


		//backend - before visit else-stat
		currentList.add(new Instruction("L"+ currentIfLable +":\n"));
		encStackCount = stackTotal;
		stackTotal = 0;
		encStackMap = currentStackMap;
		scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		encInstr = currentList;
		scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//
		if (ctx.stat(1) != null) {
			visit(ctx.stat(1));
		}
		visit(ctx.stat_return(1));

		//backend - after visit else-stat
		//locating variables from outer scope correctly when there is a change in stack pointer

		hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);
		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("L"+ (currentIfLable+1) +":\n"));

		//

		if (!SharedMethods.assignCompat(ctx.stat_return(0).typename, ctx.stat_return(1).typename)){
        	System.exit(200);
		}
		ctx.typename = ctx.stat_return(0).typename;

		currentTable = currentTable.encSymTable;
		return null;
	}
	
	@Override public Info visitLayer_i_i(@NotNull WaccParser.Layer_i_iContext ctx) {
		if (prints) System.out.println("visitLayer_i_i");

		//backend
		ifCount++ ;
		//

		if (prints) System.out.println("visitStat_if");
		visit(ctx.expr());
		if (prints) System.out.println("expr = "+ ctx.expr().toString());

		//backend - after visit expr
		int currentIfLable = ifCount * 2;
		currentList.add(new Instruction("CMP r" + regCount + ", #0\n"));
		currentList.add(new Instruction("BEQ L" + (currentIfLable) + "\n"));
		//

		currentTable = new SymbolTable(currentTable);


		//backend - before visit then-stat
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//

		if (ctx.stat(0) != null) {
			visit(ctx.stat(0));
		}
		visit(ctx.if_layers(0));

		//backend - after visit first/then stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("B L" + (currentIfLable+1) + "\n"));
		//

		//backend - before visit else-stat
		currentList.add(new Instruction("L"+ currentIfLable +":\n"));
		encStackCount = stackTotal;
		stackTotal = 0;
		encStackMap = currentStackMap;
		scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		encInstr = currentList;
		scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//
		if (ctx.stat(1) != null) {
			visit(ctx.stat(1));
		}
		visit(ctx.if_layers(1));

		//backend - after visit else-stat
		//locating variables from outer scope correctly when there is a change in stack pointer

		hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);
		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("L"+ (currentIfLable+1) +":\n"));

		//

		if (!SharedMethods.assignCompat(ctx.if_layers(0).typename, ctx.if_layers(1).typename)){
        	System.exit(200);
		}
		ctx.typename = ctx.if_layers(0).typename;

		currentTable = currentTable.encSymTable;
		return null;
	}
	
	@Override public Info visitLayer_s_i(@NotNull WaccParser.Layer_s_iContext ctx) {
		if (prints) System.out.println("visitLayer_s_i");

		//backend
		ifCount++ ;
		//

		if (prints) System.out.println("visitStat_if");
		visit(ctx.expr());
		if (prints) System.out.println("expr = "+ ctx.expr().toString());

		//backend - after visit expr
		int currentIfLable = ifCount * 2;
		currentList.add(new Instruction("CMP r" + regCount + ", #0\n"));
		currentList.add(new Instruction("BEQ L" + (currentIfLable) + "\n"));
		//

		currentTable = new SymbolTable(currentTable);

		//backend - before visit then-stat
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//
		if (ctx.stat(0) != null) {
			visit(ctx.stat(0));
		}
		visit(ctx.stat_return());

		//backend - after visit first stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("B L" + (currentIfLable+1) + "\n"));
		//

		//backend - before visit else-stat
		currentList.add(new Instruction("L"+ currentIfLable +":\n"));
		encStackCount = stackTotal;
		stackTotal = 0;
		encStackMap = currentStackMap;
		scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		encInstr = currentList;
		scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//
		if (ctx.stat(1) != null) {
			visit(ctx.stat(1));
		}
		visit(ctx.if_layers());
		//backend - after visit else-stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);
		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("L"+ (currentIfLable+1) +":\n"));

		//


		if (!SharedMethods.assignCompat(ctx.if_layers().typename, ctx.stat_return().typename)){
        	System.exit(200);
		}
		ctx.typename = ctx.stat_return().typename;

		currentTable = currentTable.encSymTable;
		return null;
	}

	@Override public Info visitLayer_i_s(@NotNull WaccParser.Layer_i_sContext ctx) {
		if (prints) System.out.println("visitLayer_s_i");

		//backend
		ifCount++ ;
		//

		if (prints) System.out.println("visitStat_if");
		visit(ctx.expr());
		if (prints) System.out.println("expr = "+ ctx.expr().toString());

		//backend - after visit expr
		int currentIfLable = ifCount * 2;
		currentList.add(new Instruction("CMP r" + regCount + ", #0\n"));
		currentList.add(new Instruction("BEQ L" + (currentIfLable) + "\n"));
		//

		currentTable = new SymbolTable(currentTable);


		//backend - before visit then-stat
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//
		if (ctx.stat(0) != null) {
			visit(ctx.stat(0));
		}
		visit(ctx.if_layers());
		//backend - after visit first stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("B L" + (currentIfLable+1) + "\n"));
		//

		//backend - before visit else-stat
		currentList.add(new Instruction("L"+ currentIfLable +":\n"));
		encStackCount = stackTotal;
		stackTotal = 0;
		encStackMap = currentStackMap;
		scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		encInstr = currentList;
		scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//
		if (ctx.stat(1) != null) {
			visit(ctx.stat(1));
		}
		visit(ctx.stat_return());
		//backend - after visit else-stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);
		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("L"+ (currentIfLable+1) +":\n"));
		//

		if (!SharedMethods.assignCompat(ctx.if_layers().typename, ctx.stat_return().typename)){
			System.exit(200);
		}
		ctx.typename = ctx.stat_return().typename;

		currentTable = currentTable.encSymTable;
		return null;
	}


	@Override public Info visitAssign_rhs_call(@NotNull WaccParser.Assign_rhs_callContext ctx) {
    	if (prints) System.out.println("visitAssign_rhs_call");
		List<ExprContext> actuals = ctx.arg_list().expr();

		//ofV func overload: create a dummy list
		List<Instruction> realList = currentList;
		List<Instruction> dummyList = new ArrayList<Instruction>();
		currentList = dummyList;
		// visit all parameters (exprs)
		String listTypesString = "";
		for (ExprContext each : actuals){
			visit(each);
			String exprType = each.typename.toString();
			listTypesString += "_" + exprType;
		}
		currentList = realList;
		// ofV func overload: discard dummy list
		System.out.println(listTypesString);
		String funcname = ctx.ident().getText() + listTypesString;
		System.out.println(funcname);
		IDENTIFIER F = currentTable.lookupAllFunc(funcname);

		if (F == null) {
			System.out.print("111");
        	System.exit(200);
		}
		if (!(F instanceof FUNCTION)) {
			System.out.print("222");
			System.exit(200);
		}
		if (((FUNCTION) F).formals.size() != actuals.size()) {
			System.out.print("333");
        	System.exit(200);//throw new Error ("wrong number of parameters");
		}

		//backend
		int argSizeCount = 0;
		for(int i = actuals.size() -1 ; i >= 0; i--){
			ExprContext each = actuals.get(i);
			visit(each);
			argSizeCount += typeSize(each.typename);
			//frontend
			if (!SharedMethods.assignCompat(((FUNCTION) F).formals.get(i).TYPE(), each.typename)){
				System.exit(200);//throw new Error("type of func param " + i + " incompatible with declaration");
			}
			//
			String store = (typeSize(each.typename) == 1)? "STRB" : "STR";
			currentList.add(new Instruction(store + " r" + regCount + ", [sp, #-" + typeSize(each.typename) + "]!\n"));
			funcCallOffset += argSizeCount;
		}

		funcCallOffset = 0;

		FUNCTION fun = (FUNCTION) F;
		ctx.typename = fun.returntype;

		currentList.add(new Instruction("BL f_" + funcname + "\n"));
		currentList.add(new Instruction("ADD sp, sp, #"+ argSizeCount + "\n"));
		currentList.add(new Instruction("MOV r" + regCount + ", r0\n"));
		//
		return null;
	}

	@Override public Info visitAssign_rhs_call_empty(@NotNull WaccParser.Assign_rhs_call_emptyContext ctx) {
    	if (prints) System.out.println("visitAssign_rhs_call_empty");
		String funcname = ctx.ident().getText();
		IDENTIFIER F = currentTable.lookupAllFunc(funcname);

		if (F == null) {
        	System.exit(200);//throw new Error("unknown function" + funcname);
		}
		if (!(F instanceof FUNCTION)) {
        	System.exit(200);//throw new Error(funcname + "is not a function");
		}
		if (((FUNCTION) F).formals.size() != 0) {
        	System.exit(200);//throw new Error ("wrong number of parameters");
		}

		FUNCTION fun = (FUNCTION) F;
		ctx.typename = fun.returntype;

		//backend
		currentList.add(new Instruction("BL f_" + ctx.ident().getText() + "\n"));
		currentList.add(new Instruction("MOV r" + regCount + ",r0\n"));
		//
		return null;
	}


	@Override public Info visitPair_liter(@NotNull WaccParser.Pair_literContext ctx) { 
    	if (prints) System.out.println("visitPair_liter");

		return null;
	}

	@Override public Info visitParam(@NotNull WaccParser.ParamContext ctx) {
    	if (prints) System.out.println("visitParam");
		visit(ctx.type());

		String param_name = ctx.ident().getText();
		PARAM p = new PARAM(ctx.type().typename);
		if(currentTable.lookup(param_name) == null){
			currentTable.add(param_name, p);
		}else{
			System.out.println("parameters have same name : " + param_name );
			System.exit(200);
		}
		ctx.paramObj = p;

		//backend
		Integer param_size = typeSize(ctx.type().typename);
		paramOffsetMap.put(param_name, paramSizeCount);
		paramSizeCount += param_size;
		//

		return new Info("param").setType(ctx.type().typename.toString());
	}

	/*@Override public Info visitStat_if(@NotNull WaccParser.Stat_ifContext ctx) {
		//backend
		ifCount++ ;
		//

    	if (prints) System.out.println("visitStat_if");
		visit(ctx.expr());
		if (prints) System.out.println("expr = "+ ctx.expr().getText());

		//backend - after visit expr
		int currentIfLable = ifCount * 2;
		currentList.add(new Instruction("CMP r" + regCount + ", #0\n"));
		currentList.add(new Instruction("BEQ L" + (currentIfLable) + "\n"));
		//

		if(!(SharedMethods.assignCompat(ctx.expr().typename, new BOOL()))){
			System.out.print("if condition is not of type bool");
			System.exit(200);
		}

		//backend - before visit then-stat
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//

		currentTable = new SymbolTable(currentTable);
		visit(ctx.stat(0));
		currentTable = currentTable.encSymTable;

		//backend - after visit first stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			//if (instr.isScoped()){
			//	instr.addScopeDepth(hasDeclared);
			//}
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("B L" + (currentIfLable+1) + "\n"));
		//


		//backend - before visit else-stat
		currentList.add(new Instruction("L"+ currentIfLable +":\n"));
		encStackCount = stackTotal;
		stackTotal = 0;
		encStackMap = currentStackMap;
		scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		encInstr = currentList;
		scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//


		currentTable = new SymbolTable(currentTable);
		visit(ctx.stat(1));
		currentTable = currentTable.encSymTable;

		//backend - after visit else-stat
		//locating variables from outer scope correctly when there is a change in stack pointer

		hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);
		for(Instruction instr: currentList) {
			//if (instr.isScoped()){
			//	instr.addScopeDepth(hasDeclared);
			//}
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;

		currentList.add(new Instruction("L"+ (currentIfLable+1) +":\n"));

		//

		ctx.typename = ctx.stat(0).typename;
		return null;
	}*/
	@Override public Info visitStat_if(@NotNull WaccParser.Stat_ifContext ctx) {
		//backend
		ifCount++ ;
		//
		//EXTENSION - short circuiting
		if (ctx.expr().getText().equals(new String("true"))) {
			if (prints) System.out.println("control flow true");
			controlFlowTrue = true;
		}
		
		if (ctx.expr().getText().equals(new String("false"))) {
			if (prints) System.out.println("control flow false");
			controlFlowFalse = true;
		}

    	if (prints) System.out.println("visitStat_if");
		visit(ctx.expr());
		if (prints) System.out.println("expr = "+ ctx.expr().getText());
		
		
		
		
		
		//---------------------------------------------------

		//backend - after visit expr
		int currentIfLable = ifCount * 2;
		
		if (!(controlFlowTrue || controlFlowFalse)) { //no control flow breaks
			currentList.add(new Instruction("CMP r" + regCount + ", #0\n"));
			currentList.add(new Instruction("BEQ L" + (currentIfLable) + "\n"));
		}
		//

		if(!(SharedMethods.assignCompat(ctx.expr().typename, new BOOL()))){
			System.out.print("if condition is not of type bool");
			System.exit(200);
		}

		//backend - before visit then-stat
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//

		currentTable = new SymbolTable(currentTable);
		visit(ctx.stat(0));
		currentTable = currentTable.encSymTable;

		//backend - after visit first stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		
		for(Instruction instr: currentList) {
			//if (instr.isScoped()){
			//	instr.addScopeDepth(hasDeclared);
			//}
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		

			//adding to encInstrList
			if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
			
			if (!controlFlowFalse) { //the expr is true and we dont need the if then part
				for(Instruction in : currentList){
					//for newly created scopedInstruction
					if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
					encInstr.add(in);
				}
			}
			
			if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
			currentList = encInstr;
			currentStackMap = encStackMap;
			stackTotal = encStackCount;
		

		if (!(controlFlowTrue || controlFlowFalse)) {
			currentList.add(new Instruction("B L" + (currentIfLable+1) + "\n"));
		

		//backend - before visit else-stat
		
		currentList.add(new Instruction("L"+ currentIfLable +":\n"));
		}
		encStackCount = stackTotal;
		stackTotal = 0;
		encStackMap = currentStackMap;
		scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		encInstr = currentList;
		scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//


		currentTable = new SymbolTable(currentTable);
		visit(ctx.stat(1));
		currentTable = currentTable.encSymTable;

		//backend - after visit else-stat
		//locating variables from outer scope correctly when there is a change in stack pointer

		hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);
		for(Instruction instr: currentList) {
			//if (instr.isScoped()){
			//	instr.addScopeDepth(hasDeclared);
			//}
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		
		if (!controlFlowTrue) { //expr is true so no need else branch
			//adding to encInstrList
			if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
			for(Instruction in : currentList){
				//for newly created scopedInstruction
				if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
				encInstr.add(in);
			}
		}
			if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
			currentList = encInstr;
			currentStackMap = encStackMap;
			stackTotal = encStackCount;
		
		if (!(controlFlowTrue || controlFlowFalse)) {
			currentList.add(new Instruction("L"+ (currentIfLable+1) +":\n"));
		}
		//

		ctx.typename = ctx.stat(0).typename;
		controlFlowTrue = false;
		controlFlowFalse = false;

		return null;
	}

	@Override public Info visitStat_read(@NotNull WaccParser.Stat_readContext ctx) { 
    	if (prints) System.out.println("visitStat_read");
//    	definedRead[4] = true; //inRead
		visit(ctx.assign_lhs());
		if(ctx.assign_lhs().typename instanceof NULL) {
			return null;
		}

		if((!(ctx.assign_lhs().typename instanceof INT)) &&
		(!(ctx.assign_lhs().typename instanceof CHAR)))
        	System.exit(200);
		VariableFragment v = new VariableFragment(((WaccParser.Assign_lhs_identContext) ctx.assign_lhs()).ident().getText());
		currentList.add(new Instruction(Arrays.asList(new StringFragment("ADD r" + regCount + ", sp"), v, new StringFragment("\n")), v));
		currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
		if(ctx.assign_lhs().typename instanceof INT) {
			currentList.add(new Instruction("BL p_read_int\n"));

			err.pReadInt();
		} else
		if(ctx.assign_lhs().typename instanceof CHAR) {
			currentList.add(new Instruction("BL p_read_char\n"));

			err.pReadChar();
		}
		
//    	definedRead[4] = false;

		return null;
	}

	@Override
	public Info visitType_pairtype(@NotNull WaccParser.Type_pairtypeContext ctx) { 
    	if (prints) System.out.println("visitType_pairtype");
		visit(ctx.pair_type());
		ctx.typename = ctx.pair_type().typename;
		return null;
	}
	
	@Override 
	public Info visitType_arraytype(@NotNull WaccParser.Type_arraytypeContext ctx) { 
    	if (prints) System.out.println("visitType_arraytype");
		  visit(ctx.array_type());
    	ctx.typename = ctx.array_type().typename;

		return null;
	}
	
	@Override
	public Info visitType_basetype(@NotNull WaccParser.Type_basetypeContext ctx) {
    	if (prints) System.out.println("visitType_basetype");
		visit(ctx.base_type());
		ctx.typename = ctx.base_type().typename;
		
		return null;
	}

	@Override public Info visitStat_exit(@NotNull WaccParser.Stat_exitContext ctx) {
    	if (prints) System.out.println("visitStat_exit");
		visit(ctx.expr());

		//backend
		currentList.add(new Instruction("MOV r0, r" + regCount + "\nBL exit\n"));
		//

		ctx.typename = ctx.expr().typename;
		if(!SharedMethods.assignCompat(ctx.typename, new INT())) {
			System.exit(200);
		}
		
		return null; 
	}

	public Info visitUnary_oper(@NotNull WaccParser.Unary_operContext ctx) { 
    	if (prints) System.out.println("visitUnary_oper");
		return visitChildren(ctx);
	}

	@Override public Info visitStat_while(@NotNull WaccParser.Stat_whileContext ctx) { 
	   	if (prints) System.out.println("visitStat_while");

    	whileCount++;
    	Instruction BLOinstr = new Instruction("B LW" + (whileCount * 2) + "\n");
    	currentList.add(BLOinstr);
    	currentList.add(new Instruction("LW" + ((whileCount * 2) + 1) + ":\n"));
    	int encStackTotal = stackTotal;
//    	List<Instruction> encWhileList = new ArrayList<Instruction>();
    	stackTotal = 0;

    	
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		int encWhileCount = whileCount;
		
		visit(ctx.stat());
		
		//--------------------------
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackTotal;
		whileCount = encWhileCount;
		//----------------------------

		currentList.add(new Instruction("LW" + (whileCount * 2) + ":\n"));
		visit(ctx.expr());
		Instruction compareAndEqual = new Instruction("CMP r" + regCount + ", #1\nBEQ LW" + ((whileCount * 2) + 1) + "\n");
		currentList.add(compareAndEqual);
    	
		if(!(SharedMethods.assignCompat(ctx.expr().typename, new BOOL()))){
			System.out.print("if condition is not of type bool");
			System.exit(200);
		}
		return null; 
	}
	
	

	@Override public Info visitIdent(@NotNull WaccParser.IdentContext ctx) {


		if (prints) System.out.println("visitIdent");
		if(ctx.getText().equals("null")) {
			ctx.typename = new NULL();
			currentList.add(new Instruction("LDR r" + regCount + ", =0\n"));
			return null;
		}
		IDENTIFIER id = currentTable.lookupAll(ctx.getText());
		if(id == null) if (prints) System.out.println("visitIndent: LHS IS NULLLLL");	//REMOVE
		if(id instanceof VARIABLE){
			ctx.typename = ((VARIABLE) id).TYPE();
		}
		System.out.println("here");
		return new Info(ctx.getText());
	
	}
	
	@Override public Info visitAssign_lhs_ident(@NotNull WaccParser.Assign_lhs_identContext ctx) { 
    	if (prints) System.out.println("visitAssign_lhs_ident");

		IDENTIFIER id = currentTable.lookupAll(ctx.getText());


		if(id == null) if (prints) System.out.println("visitAssign_lhs_indent: LHS IS NULLLLL");	////REMOVE
		if(id instanceof VARIABLE){

			ctx.typename = ((VARIABLE) id).TYPE();
		}

		return new Info(ctx.ident().getText());
}
	
	@Override public Info visitAssign_lhs_array(@NotNull WaccParser.Assign_lhs_arrayContext ctx) {
		
    	if (prints) System.out.println("visitAssign_lhs_array");
    	Info i = visit(ctx.array_elem()); 
    	regCount++;

    	VariableFragment v  = new VariableFragment(i.stringinfo);

    	Pattern p = Pattern.compile("\\[(.*?)\\]");
    	Matcher m = p.matcher(ctx.array_elem().getText());
    	
    	//for 1 dimen array
    	m.find();
    	String index = m.group(1);
    	
    	currentList.add(new Instruction(Arrays.asList(new StringFragment("ADD r" + (regCount) + ", sp"), v, new StringFragment("\n")), v));
    	regCount++;

    	//if array index is a variable index will be empty eg. a[i]
    	if (!isAnum(index)) {
    		currentList.add(new Instruction("LDR r" + regCount + ", [sp]\n"));

    	} else {
    		currentList.add(new Instruction("LDR r" + regCount + ", =" + index + '\n'));
    	}
    	
    	currentList.add(new Instruction("LDR r" + (regCount -1) + ", [r" + (regCount -1) + "] \n"));
    	currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
    	currentList.add(new Instruction("MOV r1, r" + (regCount -1) + "\n"));
    	currentList.add(new Instruction("BL p_check_array_bounds \n"));
    	currentList.add(new Instruction("ADD r" + (regCount -1) + ", r" + (regCount -1) + ", #4 \n"));
    	currentList.add(new Instruction("ADD r" + (regCount -1) + ", r" + (regCount -1) + ", r" + regCount +
    			(typeSize(ctx.array_elem().typename) == 1 ? "\n" : ", LSL #2 \n"))); 
    	
    	currentList.add(new Instruction((typeSize(ctx.array_elem().typename) == 1 ? "STRB" : "STR") + " r4, [r" + (regCount -1) + "] \n"));
    	regCount = regCount -2;
    	
    	//add error msg
    	err.pArray();

    	//-------
    	
    	ctx.typename = ctx.array_elem().typename;
    	

    	
		return null;
	}
	
	@Override 
	public Info visitAssign_lhs_pair(@NotNull WaccParser.Assign_lhs_pairContext ctx) { 
    	if (prints) System.out.println("visitAssign_lhs_pair");
    	regCount++;
		visit(ctx.pair_elem());
		ctx.typename = ctx.pair_elem().typename;
		//for snd on lhs
		regCount--;
		currentList.add(new Instruction( (typeSize(ctx.typename) == 1 ? "STRB r" : "STR r") + regCount + ", [r" + (regCount + 1) + "]\n"));
		//for fst rhs
		//currentList.add(new Instruction("STR r" + (regCount -1) + ", [r" + regCount + "]\n"));
		return null;
	}

	@Override public Info visitStat_return(@NotNull WaccParser.Stat_returnContext ctx) { 
    	if (prints) System.out.println("visitStat_return");
		visit(ctx.expr());
		if (ctx.RETURN() != null) {
			assert ctx.EXIT() == null;
			ctx.typename = ctx.expr().typename;
		}
		else {
			assert ctx.EXIT() != null;
			ctx.typename = new NULL();
		}
		
		if(currentTable.encSymTable == null) {
			System.exit(200);
		}

		currentList.add(new Instruction("MOV r0, r4\n"));
		VariableFragment total = new VariableFragment("total");
		currentList.add(new Instruction_Return(Arrays.asList(new StringFragment("ADD sp, sp"), total, new StringFragment("\n")), total));
		currentList.add(new Instruction("POP {pc}\n"));
		return null; 
		
	}

	@Override 
	public Info visitArray_type_array(@NotNull WaccParser.Array_type_arrayContext ctx) { 
    	if (prints) System.out.println("visitArray_type_array");
		visit(ctx.array_type());
		
		ctx.typename = new ARRAY_TYPE(ctx.array_type().typename);

		return null;
	}
	
	@Override 
	public Info visitArray_type_base(@NotNull WaccParser.Array_type_baseContext ctx) { 
    	if (prints) System.out.println("visitArray_type_base");
		visit(ctx.base_type());

		
		ctx.typename = new ARRAY_TYPE(ctx.base_type().typename);
		return null;
	}
	
	@Override 
	public Info visitArray_type_pair(@NotNull WaccParser.Array_type_pairContext ctx) { 
    	if (prints) System.out.println("visitArray_type_pair");
		visit(ctx.pair_type());

		
		ctx.typename = new ARRAY_TYPE(ctx.pair_type().typename);
		return null;
	}

	
	@Override 
	public Info visitPair_elem_fst(@NotNull WaccParser.Pair_elem_fstContext ctx) { 
		if (prints) System.out.println("visitPair_elem_fst");

		visit(ctx.expr());
		
		currentList.add(new Instruction("MOV r0, r" + regCount + "\nBL p_check_null_pointer\n"));
		currentList.add(new Instruction("LDR r" + regCount + ", [r" + regCount + "]\n"));
		err.pNullPointer();

		if(ctx.expr().typename instanceof NULL) {
			ctx.typename = new NULL();
			return null;
		}
		fstVisited  = true;
		PAIR_TYPE pair = (PAIR_TYPE) ctx.expr().typename;
		ctx.typename = pair.firstType();
		return null; 
	}
	
	@Override 
	public Info visitPair_elem_snd(@NotNull WaccParser.Pair_elem_sndContext ctx) {
		if (prints) System.out.println("visitPair_elem_snd");
		visit(ctx.expr());

		if(ctx.expr().typename instanceof NULL) {
			ctx.typename = new NULL();
		} else {
			
			PAIR_TYPE pair = (PAIR_TYPE) ctx.expr().typename;
			ctx.typename = pair.secondType();
		}
		currentList.add(new Instruction("MOV r0, r" + regCount + "\nBL p_check_null_pointer\n"));
		currentList.add(new Instruction("LDR r" + regCount + ", [r" + regCount + ", #4]\n"));
		
		err.pNullPointer();
		return null; 
	}
	
	//assign rhs ------------------------
	
	@Override 
	public Info visitAssign_rhs_newpair(@NotNull WaccParser.Assign_rhs_newpairContext ctx) { 
    	if (prints) System.out.println("visitAssign_rhs_newpair");
    	newpairs++;
    	
    	currentList.add(new Instruction(("LDR r0, =8" + '\n' +
    			"BL malloc \n" + "MOV r" + regCount + ", r0 \n")));
    	regCount++;
    	
		visit(ctx.expr(0));
		if(ctx.expr(0).typename == null) {
			ctx.expr(0).typename = new NULL();
		}
		
		int size = typeSize(ctx.expr(0).typename);
		currentList.add(new Instruction("LDR r0, =" + size + "\n" + "BL malloc \n"));
		currentList.add(new Instruction((size == 1 ? "STRB " : "STR ") + "r5" + ", [r0]\n" + "STR r0, [r4]\n"));

		visit(ctx.expr(1));
		if(ctx.expr(1).typename == null) {
			ctx.expr(1).typename = new NULL();
		}
		int size2 = typeSize(ctx.expr(1).typename);
		currentList.add(new Instruction("LDR r0, =" + size2 + "\n" + "BL malloc \n"));
		currentList.add(new Instruction((size2 == 1 ? "STRB " : "STR ") + "r5" + ", [r0]\n"
				+ "STR r0, [r4, #4]\n"));

		
		
		regCount--;
		ctx.typename = new PAIR_TYPE(ctx.expr(0).typename, ctx.expr(1).typename);
		return null;
	}
	
	@Override public Info visitAssign_rhs_expr(@NotNull WaccParser.Assign_rhs_exprContext ctx) { 
    	if (prints) System.out.println("visitAssign_rhs_expr");
		visit(ctx.expr());
		ctx.typename = ctx.expr().typename;
		return null;
	}
	
	@Override public Info visitAssign_rhs_ar_liter(@NotNull WaccParser.Assign_rhs_ar_literContext ctx) { 
		if (prints) System.out.println("visitAssign_rhs_ar_liter");
		visit(ctx.array_liter());
		ctx.typename = new ARRAY_TYPE(ctx.array_liter().typename);
		
		//getting total space, each array elem = 4, and 1*4 space for array.length
		int arrSize = ctx.array_liter().expr().size();
		int spaceForArr = 4;
		if (ctx.array_liter().expr(0) != null ) {
			spaceForArr = typeSize(ctx.array_liter().expr(0).typename) * arrSize + 4;
		} else {
			stackTotal += 4;
		}
		int addAtIndex = currentList.size() - 2 * ctx.array_liter().expr().size();
		currentList.add(addAtIndex , new Instruction(("LDR r0, =" + spaceForArr + '\n' +
				"BL malloc \n" + "MOV r" + regCount + ", r0 \n")));
		regCount++;
				
		
		currentList.add(new Instruction(("LDR r" + regCount + ", =" + arrSize + '\n' + "STR r" + regCount + ", [r" + (regCount -1) + "] \n")));
		regCount--; 
		return null;
	}
	
	@Override public Info visitAssign_rhs_pair_elem(@NotNull WaccParser.Assign_rhs_pair_elemContext ctx) { 
    	if (prints) System.out.println("visitAssign_rhs_pair_elem");
		visit(ctx.pair_elem());
		ctx.typename = ctx.pair_elem().typename;
		
		//this is in case of snd at rhs
		currentList.add(new Instruction( (typeSize(ctx.typename) == 1 ? "LDRSB r" : "LDR r") + regCount + ", [r" + regCount + "]\n"));
		

		return null;
	}
	
	//-------------------------------------------------
	@Override public Info visitStat_skip(@NotNull WaccParser.Stat_skipContext ctx) {
    	if (prints) System.out.println("visitStat_skip");
		ctx.typename = null;
		return null; 
	}
	
	@Override 
	public Info visitBase_type_int(@NotNull WaccParser.Base_type_intContext ctx) { 
    	if (prints) System.out.println("visitBase_type_int");
    	ctx.typename = new INT();
		return null;
	}
	
	@Override 
	public Info visitBase_type_char(@NotNull WaccParser.Base_type_charContext ctx) {
    	if (prints) System.out.println("visitBase_type_char");
		ctx.typename = new CHAR();
		return null; 
	}
	
	@Override 
	public Info visitBase_type_string(@NotNull WaccParser.Base_type_stringContext ctx) { 
    	if (prints) System.out.println("visitBase_type_string");
		ctx.typename = new STRING();
		return null;
	}
	
	@Override 
	public Info visitBase_type_bool(@NotNull WaccParser.Base_type_boolContext ctx) { 
    	if (prints) System.out.println("visitBase_type_bool");
		ctx.typename = new BOOL();
		return null;
	}

	@Override public Info visitPair_type(@NotNull WaccParser.Pair_typeContext ctx) {
    	if (prints) System.out.println("visitPair_type");
    	if (prints) System.out.println("Pair elem1: " + ctx.pair_elem_type(0).typename);
    	if (prints) System.out.println("Pair elem2: " + ctx.pair_elem_type(1).typename);
		visit(ctx.pair_elem_type(0));
		visit(ctx.pair_elem_type(1));

		ctx.typename = new PAIR_TYPE(ctx.pair_elem_type(0).typename, ctx.pair_elem_type(1).typename);
		return null;
	}

	@Override public Info visitParam_list(@NotNull WaccParser.Param_listContext ctx) {
		//backend
		paramSizeCount = 4;
		//

		String typeList = "";
		List<ParamContext> pctx = ctx.param();
		for (ParamContext p : pctx){
			Info x = visit(p);
			typeList += "_" + x.type;
		}

		return new Info("paramList").setType(typeList);
	}

	@Override public Info visitStat_begin_end(@NotNull WaccParser.Stat_begin_endContext ctx) {
		if (prints) System.out.println("visitStat_begin_end");
		SymbolTable table = new SymbolTable(currentTable);
		currentTable = table;

		//backend - before visit scoped stat => creating all new lists and map for new scope
		int encStackCount = stackTotal;
		stackTotal = 0;
		Map<String, Integer> encStackMap = currentStackMap;
		Map<String,Integer> scopedStackMap = new HashMap<>();
		currentStackMap = scopedStackMap;
		List<Instruction> encInstr = currentList;
		List<Instruction> scopedInstr = new ArrayList<Instruction>();
		currentList = scopedInstr;
		//

		visit(ctx.stat());

		//backend - after visited first stat
		//locating variables from outer scope correctly when there is a change in stack pointer
		int hasDeclared = stackTotal;
		currentStackMap.put("total", stackTotal);

		for(Instruction instr: currentList) {
			if (instr instanceof Instruction_Return){
				// to add to stackCount and propagate the instruction up 1 layer, to keep accumulating stackCount to do ADD sp sp correctly
				((Instruction_Return) instr).addStackCount(currentStackMap.get("total"));
			}
			if (instr.toDeclare()) {
				stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
			}
			if (instr.needsVarPos() && !(instr instanceof Instruction_Return)) {
				// variable total is propagated up the if scopes
				instr.varsToPos(currentStackMap, hasDeclared);
			}
		}
		//adding to encInstrList
		if(hasDeclared > 0) encInstr.add(new Instruction("SUB sp, sp, #" + hasDeclared + "\n"));
		for(Instruction in : currentList){
			//for newly created scopedInstruction
			if(in.isScoped() && (in.scopeDepth() == 0)) in.addScopeDepth(hasDeclared);
			encInstr.add(in);
		}
		if(hasDeclared > 0) encInstr.add(new Instruction("ADD sp, sp, #" + hasDeclared + "\n"));
		currentList = encInstr;
		currentStackMap = encStackMap;
		stackTotal = encStackCount;
		//

		currentTable = table.encSymTable;
		return null; 
	}

	@Override public Info visitStat_free(@NotNull WaccParser.Stat_freeContext ctx) {
		if (prints) System.out.println("visitStat_free");
		
		visit(ctx.expr());
		freepairs++;
		if (freepairs > newpairs) {
			currentList.add(new Instruction("MOV r0, #134\nBL exit\n"));
		}
//		} else {
		err.pFreepair();
//			footer.add(new Instruction("p_throw_runtime_pair_error:\nBL p_print_string\nMOV r0, #-1\nBL exit\n"));
//		}
		
		currentList.add(new Instruction("MOV r0, r" + regCount + "\nBL p_free_pair\n"));
		

		
		ctx.typename = ctx.expr().typename;
		if(ctx.typename instanceof NULL) {

			return null;
		}
		if (!(ctx.typename instanceof PAIR_TYPE) && !(ctx.typename instanceof ARRAY_TYPE)){

        	System.exit(200);//throw new Error("Cannot free TYPE " + ctx.typename.toString() + ", ARRAY_TYPE or PAIR_TYPE expected.");
		}

		return null; 
	}

	@Override public Info visitArg_list(@NotNull WaccParser.Arg_listContext ctx) {
		List<ExprContext> list = ctx.expr();
		for (ExprContext e : list){
			visit(e);
		}
		return null; 
	}

	@Override public Info visitArray_elem(@NotNull WaccParser.Array_elemContext ctx) { 
		VARIABLE array_or_string = (VARIABLE) currentTable.lookupAll(ctx.ident().getText());
		
		if(array_or_string.TYPE() instanceof STRING){
		  if (prints) System.out.println("ITS A STRING");
		  ctx.typename = new CHAR();
		}
		else{
		  ctx.typename = ((ARRAY_TYPE)array_or_string.TYPE()).TYPE();
		}
		return new Info(ctx.ident().getText());
	}

	@Override public Info visitProgram(@NotNull WaccParser.ProgramContext ctx) { 

		List<FuncContext> list = ctx.func();
		for(FuncContext func : list) {
			try {
				Func_standardContext f = (Func_standardContext) func;
				if (prints) System.out.println("IDENT: " + f.ident().getText());
				visit(f.type());
				FUNCTION newFunc = new FUNCTION(f.type().typename);
				String paramTypesString = "";
				if(f.param_list() != null){
					currentTable = new SymbolTable(currentTable);
					Info paramListInfo = visit(f.param_list());
					paramTypesString = paramListInfo.type;
					currentTable = currentTable.encSymTable;
					
					List <ParamContext> params = f.param_list().param();
					for(ParamContext p : params){
						newFunc.formals.add(p.paramObj);
					}
				}
				currentTable.funcadd(f.ident().getText() + paramTypesString, newFunc);
			} catch (ClassCastException e) {
				Func_ifContext f = (Func_ifContext) func;currentTable = new SymbolTable(currentTable);
				if (prints) System.out.println("IDENT: " + f.ident().getText());
				visit(f.type());
				FUNCTION newFunc = new FUNCTION(f.type().typename);
				String paramTypesString = "";
				if(f.param_list() != null){
					currentTable = new SymbolTable(currentTable);
					Info paramListInfo = visit(f.param_list());
					paramTypesString = paramListInfo.type;
					currentTable = currentTable.encSymTable;
					
					List <ParamContext> params = f.param_list().param();
					for(ParamContext p : params){
						newFunc.formals.add(p.paramObj);
					}
				}
				currentTable.funcadd(f.ident().getText() + paramTypesString, newFunc);
			}
			

			
		}
		List<FuncContext> funcCon = ctx.func();

		//switch to putting code into functList
		currentList = functList;
		for(FuncContext each : funcCon){
			visit(each);
		}

		//switch to putting code into instrList
		currentList = instrList;
		currentList.add(new Instruction("main:\nPUSH {lr}\n"));
		VariableFragment total = new VariableFragment("total");
		Instruction instr = new Instruction(Arrays.asList(new StringFragment("SUB sp, sp"), total, new StringFragment("\n")), total);
		currentList.add(instr);

		visit(ctx.stat());

	
		printInstructions();
		return null; 
	}

	
	private void printInstructions() {
		PrintWriter writer = null;
		try {
			writer = new PrintWriter(filename, "UTF-8");
		} catch (FileNotFoundException e) {
			System.out.println("file not found");
		} catch (UnsupportedEncodingException e) {
			System.out.println("UnsupportedEncodingException");
		}
		err.addErrorMessages(header, footer);
		if (header.size() > 0) {
			header.add(0, new Instruction(".data\n\n"));
		}
		header.add(new Instruction(".text\n\n.global main\n"));
		for(Instruction instr: header) {
			System.out.print(instr);
			writer.print(instr);
		}
		System.out.println();
		writer.println();

		for(Instruction funcInstr : functList){
			System.out.print(funcInstr);
			writer.print(funcInstr);
		}
		
		if (stackTotal == 0/* && whileCount < 0*/) {
			instrList.remove(1);
		} else {
			currentStackMap.put("total", (stackTotal > 1024)? 1024: stackTotal);
//			if(whileCount < 0){
			instrList.add(new Instruction("ADD sp, sp, #" + ((stackTotal > 1024) ? 1024 : stackTotal) + "\n"));
			if (stackTotal > 1024) {
				instrList.add(new Instruction("ADD sp, sp, #" + (stackTotal - 1024) + "\n"));
				instrList.add(2, new Instruction("SUB sp, sp, #" + (stackTotal - 1024) + "\n"));
			}
//			} 
		}
		currentList.add(new Instruction("LDR r0, =0\nPOP {pc}\n.ltorg\n"));
		
		//extension - control flow analysis for while
		if (infiniteLoop) {
			System.out.println("infinite loop");
			currentList.remove(currentList.size() - 1);
			currentList.remove(currentList.size() - 1);

		}
		if(Optimise.optimise) {
			instrList = Optimise.loadAndStore(instrList, currentStackMap, stackTotal);
		}
		for(Instruction instr: instrList) {
			if(!Optimise.optimise) {
				if (instr.toDeclare()) {
					stackTotal = instr.allocateStackPos(stackTotal, currentStackMap);
				}
			}
			if (instr.needsVarPos()) {
				instr.varsToPos(currentStackMap, 0);
			}
			System.out.print(instr);
			writer.print(instr);
		}
		System.out.println();
		writer.println();
		for(Instruction instr: footer) {
			System.out.print(instr);
			writer.print(instr);
		}
		System.out.println();
		writer.println();
		writer.close();
	}

	@Override 
	public Info visitPair_elem_base_type(@NotNull WaccParser.Pair_elem_base_typeContext ctx) { 
		if (prints) System.out.println("visitPair_elem_base_type");
		visit(ctx.base_type());
		ctx.typename = ctx.base_type().typename;
		return null;
	}
	
	@Override 
	public Info visitPair_elem_array_type(@NotNull WaccParser.Pair_elem_array_typeContext ctx) { 
		if (prints) System.out.println("visitPair_elem_array_type");
		visit(ctx.array_type());
		ctx.typename = ctx.array_type().typename;
		return null;
	}
	
	@Override 
	public Info visitPair(@NotNull WaccParser.PairContext ctx) { 
		if (prints) System.out.println("visitPair");
		ctx.typename = new PAIR_TYPE(new NULL(), new NULL());  //<----not sure
		return null;
	}
	
	@Override public Info visitArray_liter(@NotNull WaccParser.Array_literContext ctx) {
		if (prints) System.out.println("visitArray_liter");
		List<ExprContext> list = ctx.expr();

		if (list.isEmpty()){
			ctx.typename = null;
		} else {
			
			int count = 0;
			for (ExprContext e : list){
				regCount++;
				visit(e);
				regCount--;
				
				if(!SharedMethods.assignCompat(ctx.expr().get(0).typename, e.typename)){
		        	System.exit(200);//throw new Error("Array elem not the same type.");
				}
				regCount++;
				if (typeSize(e.typename) == 1) {
					currentList.add(new Instruction(("STRB r" + regCount + ", [r" + (regCount -1) + ", #" + (count +4)+"] \n")));
				} else {
					currentList.add(new Instruction(("STR r" + regCount + ", [r" + (regCount -1) + ", #" + (4*count +4)+"] \n")));
				}
				regCount--;
				count++;
			}
			ctx.typename = ctx.expr().get(0).typename;
			
		}
		return null;
		
	}
	

	@Override public Info visitStat_print(@NotNull WaccParser.Stat_printContext ctx) {
		if (prints) System.out.println("visitStat_print");
//		inPrint = true;
		visit(ctx.expr());
		if(ctx.expr().typename == null) {
			System.exit(200);
		}
		
		checkPrintFunc(ctx.expr().typename);
//		inPrint = false;

		return null; 
	}
	
	private void checkPrintFunc(TYPE typename) {
		//back end
		if (prints) System.out.println("checkPrintFunc");

		if(typename instanceof STRING) {
			currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
			currentList.add(new Instruction("BL p_print_string\n"));

			err.pString();
		} else
		if(typename instanceof BOOL) {
			currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
			currentList.add(new Instruction("BL p_print_bool\n"));

			err.pBool();
		} else
		if(typename instanceof CHAR) { 
			currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
			currentList.add(new Instruction("BL putchar\n"));

		} else
		if(typename instanceof INT) {
			currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
			currentList.add(new Instruction("BL p_print_int\n"));

			err.pPrintInt();
		} else
		if(typename instanceof ARRAY_TYPE) {
			if(((ARRAY_TYPE)typename).TYPE() instanceof CHAR) {
				currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
				currentList.add(new Instruction("BL p_print_string\n"));
				err.pString();
			} else {
				currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
				currentList.add(new Instruction("BL p_print_reference\n"));
				err.pRef();
			}
		} else
		if(typename instanceof PAIR_TYPE ||
//				typename instanceof ARRAY_TYPE ||
				typename instanceof NULL) {
			currentList.add(new Instruction("MOV r0, r" + regCount + "\n"));
			currentList.add(new Instruction("BL p_print_reference\n"));

			err.pRef();
		}
	}
	
	@Override public Info visitInt_liter(@NotNull WaccParser.Int_literContext ctx) { 
		List<TerminalNode> list = ctx.INTEGER();
		Iterator<TerminalNode> it = list.iterator();
		String number = "";
		if (ctx.int_sign() != null) {
			number = number + "-";
		}
		while (it.hasNext()) {
			number = number + it.next().getText();
		}

		try {
			return new Info(Integer.parseInt(number));
		} catch (NumberFormatException e) {
			if (prints) System.out.println("Number exceed limit");
			System.exit(100);
		}
		return null;
	}

	@Override public Info visitStat_println(@NotNull WaccParser.Stat_printlnContext ctx) {
		if (prints) System.out.println("visitStat_println");
//		inPrint = true;

		visit(ctx.expr());
		if(ctx.expr().typename == null) {
			System.exit(200);
		}
		//back-end
		checkPrintFunc(ctx.expr().typename);
		err.pLn();
		currentList.add(new Instruction("BL p_print_ln\n"));

		return null;
	}
	
	@Override 
	public Info visitExpr_int(@NotNull WaccParser.Expr_intContext ctx) { 
		if (prints) System.out.println("visitExpr_int");
		Info i = visit(ctx.int_liter());
		currentList.add(new Instruction("LDR r" + regCount + ", =" + i.int_value + "\n"));
		ctx.typename = new INT();

		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override 
	public Info visitExpr_bool(@NotNull WaccParser.Expr_boolContext ctx) {
		if (prints) System.out.println("visitExpr_bool");
		ctx.typename = new BOOL();
		int i = 0;
		if (ctx.bool_liter().TRUE() != null) {
			i = 1;
		}

		if (!(controlFlowTrue || controlFlowFalse)) {
			currentList.add(new Instruction("MOV r" + regCount +", #" + i + "\n"));
		}

		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override 
	public Info visitExpr_char(@NotNull WaccParser.Expr_charContext ctx) { 
		if (prints) System.out.println("visitExpr_char");
		ctx.typename = new CHAR();
		String text = ctx.char_liter().CHARACTER().getText();
		
		

		if(text.length() > 3) {
			if(text.charAt(2) == '\'') {
				currentList.add(new Instruction("MOV r" + regCount +", #" + text + "\n"));
				return null;
			}
			System.out.println(text.length());
			text = text.replace("\\n", "\n");
			text = text.replace("\\0", "\0");
			text = text.replace("\\b", "\b");
			text = text.replace("\\t", "\t");
			text = text.replace("\\f", "\f");
			text = text.replace("\\r", "\"");
			char c = text.charAt(1);
			int ascii = (int) c;
			currentList.add(new Instruction("MOV r" + regCount +", #" + ((ascii > 13)? "\'" + text.charAt(2) + "\'": String.valueOf(ascii)) + "\n"));
		} else {
//		currentList.add(new Instruction("MOV r" + regCount +", #" + ctx.char_liter().CHARACTER().getText() + "\n"));
			currentList.add(new Instruction("MOV r" + regCount +", #" + text + "\n"));
		}
		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override 
	public Info visitExpr_str(@NotNull WaccParser.Expr_strContext ctx) { 
		if (prints) System.out.println("visitExpr_str");
		ctx.typename = new STRING();
		
		String s = ctx.str_liter().STR().getText();
		int count = s.length() - s.replace("\\", "").length();
		header.add(new Instruction("msg_" + msgCount + ":\n.word " + (s.length()-2-count) + "\n.ascii " + s + "\n"));
		currentList.add(new Instruction("LDR r"+ regCount + ", =msg_" + msgCount + "\n"));
		msgCount++;
		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override public Info visitExpr_ident(@NotNull WaccParser.Expr_identContext ctx) {
		if (prints) System.out.println("visitExpr_ident");
		visit(ctx.ident());

		ctx.typename = ctx.ident().typename;
		String id = ctx.ident().getText();

		if(ctx.typename == null || ctx.typename instanceof NULL) {
			return null;
		}
		//also check if the ident has been declared

		if (currentTable.lookupAll(id) == null) System.exit(200);//throw new Error(id + "has not been declared");
		// do we have static variabsle in Wacc language. ^this would not support static var usage in stat in function declaration


		//System.out.println("!!!!!!!!!!!!!" + ctx.ident().getText() + "//funcOffset: " + funcCallOffset);
//		VariableFragment v = new VariableFragment(ctx.ident().getText(), funcCallOffset);
		//CHECK : bug in functionmanyarguments.wacc -> ref compiler line 122
//		currentList.add(new Instruction(Arrays.asList(new StringFragment(( typeSize(ctx.typename) == 1 ? "LDRSB r" : "LDR r") + regCount  + ", [sp"), v, new StringFragment("]\n")), v));
		currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.typename), regCount, ctx.ident().getText(), funcCallOffset).build());
		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override public Info visitExpr_pair(@NotNull WaccParser.Expr_pairContext ctx) { 
		ctx.typename = new PAIR_TYPE();

		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override public Info visitExpr_array_elem(@NotNull WaccParser.Expr_array_elemContext ctx) {

		if (prints) System.out.println("visitExpr_array_elem");
		Info i = visit(ctx.array_elem().ident());
		
		int arrayElemDepth = (ctx.array_elem().expr()).size();
		ARRAY_TYPE ar = (ARRAY_TYPE) ctx.array_elem().ident().typename;
		TYPE t = ar.TYPE();
		for(int d = arrayElemDepth; d > 1; d--){
			t = ((ARRAY_TYPE) t).TYPE();
		}
		
		ctx.typename = t;

    	VariableFragment v  = new VariableFragment(i.stringinfo);

    	Pattern p = Pattern.compile("\\[(.*?)\\]");
    	Matcher m = p.matcher(ctx.array_elem().getText());
    	m.find();
    	String index1 = m.group(1);
    	
    	currentList.add(new Instruction(Arrays.asList(new StringFragment("ADD r" + (regCount) + ", sp"), v, new StringFragment("\n")), v));
    	
    	//if array index is a variable index will be empty eg. a[i]
    	if (!isAnum(index1)) {
    		currentList.add(new Instruction("LDR r" + (regCount + 1) + ", [sp]\n"));

    	} else {
    		currentList.add(new Instruction("LDR r" + (regCount + 1) + ", =" + index1 + '\n'));
    	}
		
    	currentList.add(new Instruction("LDR r" + (regCount) + ", [r" + (regCount) + "] \n"));
    	currentList.add(new Instruction("MOV r0, r" + (1 + regCount) + "\n"));
    	currentList.add(new Instruction("MOV r1, r" + regCount + "\n"));
    	currentList.add(new Instruction("BL p_check_array_bounds\n"));
    	
    	regCount++;
    	currentList.add(new Instruction("ADD r" + (regCount -1) + ", r" + (regCount -1) + ", #4 \n"));
    	currentList.add(new Instruction("ADD r" + (regCount -1) + ", r" + (regCount -1) +
    			", r" + regCount  + ( typeSize(t) == 1 ? '\n' : ", LSL #2 \n")));
    	
    	if (!m.find()) {
    		currentList.add(new Instruction(((typeSize(t) == 4)? "LDR" : "LDRSB") + " r4, [r" + (regCount -1) + "] \n"));
    	} else {
    		//nested array
        	String index2 = m.group(1);
    		regCount--;
    		currentList.add(new Instruction("LDR r" + (regCount + 1) + ", =" + index2 + '\n'));
    		currentList.add(new Instruction(((typeSize(t) == 4)? "LDR" : "LDRSB") + " r" + (regCount) + ", [r" + (regCount) + "] \n"));
        	currentList.add(new Instruction("MOV r0, r" + (1 + regCount) + "\n"));
        	currentList.add(new Instruction("MOV r1, r" + regCount + "\n"));
        	currentList.add(new Instruction("BL p_check_array_bounds \n"));
        	regCount++;
        	currentList.add(new Instruction("ADD r" + (regCount -1) + ", r" + (regCount -1) + ", #4 \n"));
        	currentList.add(new Instruction("ADD r" + (regCount -1) + ", r" + (regCount -1) +
        			", r" + regCount  + (typeSize(t) == 1 ?  '\n' : ", LSL #2 \n")));
        	currentList.add(new Instruction(((typeSize(t) == 4)? "LDR" : "LDRSB") + " r4, [r" + (regCount -1) + "] \n"));
    	}
		
    	regCount--;
		//add error msg
    	err.pArray();

		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override public Info visitExpr_binary(@NotNull WaccParser.Expr_binaryContext ctx) {
		if (prints) System.out.println("visitExpr_binary");
		Info b = visit(ctx.bin_bool());
		ctx.typename = ctx.bin_bool().returntype;
		//return new Info("argument").setType(ctx.typename.toString());
		return b;
	}
	
	@Override public Info visitExpr_bin_bool(@NotNull WaccParser.Expr_bin_boolContext ctx) {
		if (prints) System.out.println("visitExpr_bin_bool_bool");
		Info one = visit(ctx.bin_bool(0));
		if (one.type.equals("reg")) {
			regCount ++;
			System.out.println("5");
		} else {assert false;}
		Info two = visit(ctx.bin_bool(1));
		if (two.type.equals("reg"))  {
			regCount ++;
			System.out.println("6");
		} else {assert false;}
		
		ctx.returntype = new BOOL();
		ctx.argtype = new BOOL();
		if(!SharedMethods.assignCompat(ctx.bin_bool(0).returntype, ctx.argtype)) {
			System.exit(200);
		}
		if(!SharedMethods.assignCompat(ctx.bin_bool(1).returntype, ctx.argtype)) {
			System.exit(200);
		}
		
		if (ctx.AND() != null) {
			currentList.add(ib.instr().triop("AND", regCount-2, regCount-1).build());
//			currentList.add(new Instruction("AND r" + (regCount - 2) + ", r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
		}
		if (ctx.OR() != null) {
			currentList.add(ib.instr().triop("ORR", regCount-2, regCount-1).build());
//			currentList.add(new Instruction("ORR r" + (regCount - 2) + ", r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
		}
		regCount = regCount -2;
		return new Info("r" + regCount).setType("reg"); 
	}
	
	@Override public Info visitExpr_bin_bool_bool(@NotNull WaccParser.Expr_bin_bool_boolContext ctx) {
		if (prints) System.out.println("visitExpr_bin_bool_bool");
		Info b = visit(ctx.bin_bool());
		if (b.type.equals("reg")) {
			regCount ++;
			System.out.println("5");
		}
		Info m = visit(ctx.math());
		if (m.type.equals("reg"))  {
			regCount ++;
			System.out.println("6");
		}
		
		ctx.returntype = new BOOL();
		ctx.argtype = new BOOL();
		if(!SharedMethods.assignCompat(ctx.bin_bool().returntype, ctx.argtype)) {
			System.exit(200);
		}
		if(!SharedMethods.assignCompat(ctx.math().returntype, ctx.argtype)) {
			System.exit(200);
		}
		
		if (b.type.equals("bool")) {
			currentList.add(new Instruction("MOV r" + regCount + ", #" + (b.b_value ? 1 : 0) + "\n"));
			regCount ++;
			System.out.println("1");
		} else if (b.type.equals("var")){
//			VariableFragment v  = new VariableFragment(b.stringinfo, funcCallOffset);
//			String load = (typeSize(ctx.bin_bool().returntype) == 4)? "LDR" : "LDRSB";
//			currentList.add(new Instruction(Arrays.asList(new StringFragment(load + " r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.bin_bool().returntype), regCount, b.stringinfo, funcCallOffset).build());
			regCount++;
			System.out.println("2");
		}
		if(m.type.equals("bool")) {
			currentList.add(new Instruction("MOV r" + regCount + ", #" + (m.b_value ? 1 : 0) + "\n"));
			regCount ++;
			System.out.println("3");
		} else if (m.type.equals("var")){
//			VariableFragment v  = new VariableFragment(m.stringinfo, funcCallOffset);
//			String load = (typeSize(ctx.math().returntype) == 4)? "LDR" : "LDRSB";
//			currentList.add(new Instruction(Arrays.asList(new StringFragment(load + " r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.math().returntype), regCount, m.stringinfo, funcCallOffset).build());
			regCount++;
			System.out.println("4");
		}
		
		if (ctx.AND() != null) {
			currentList.add(ib.instr().triop("AND", regCount-2, regCount-1).build());
//			currentList.add(new Instruction("AND r" + (regCount - 2) + ", r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
		}
		if (ctx.OR() != null) {
			currentList.add(ib.instr().triop("ORR", regCount-2, regCount-1).build());
//			currentList.add(new Instruction("ORR r" + (regCount - 2) + ", r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
		}
		regCount = regCount -2;
		return new Info("r" + regCount).setType("reg"); 
	}
	
	@Override 
	public Info visitExpr_bin_bool_math_eq(@NotNull WaccParser.Expr_bin_bool_math_eqContext ctx) { 
		if (prints) System.out.println("visitExpr_bin_bool_math_eq");
		Info one = visit(ctx.math(0));
		if (one.type.equals("reg")) {
			regCount ++;
			System.out.println("5");
		}
		Info two = visit(ctx.math(1));
		if (two.type.equals("reg"))  {
			regCount ++;
			System.out.println("6");
		}

		ctx.returntype = new BOOL();
		ctx.argtype = new EQUALITY();
		
		if(!SharedMethods.assignCompat(ctx.math(0).returntype, ctx.math(1).returntype)) {
			System.exit(200);
		}

		if(!ctx.argtype.getClass().isAssignableFrom(ctx.math(0).returntype.getClass())) {

			System.exit(200);
		}
		
		if(ctx.math(0).returntype instanceof NULL || ctx.math(1).returntype instanceof NULL) {
			regCount++;
//			VariableFragment v  = new VariableFragment(one.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount, one.stringinfo, funcCallOffset).build());
			regCount++;
			
			currentList.add(new Instruction("CMP r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
			
			if (ctx.IS_EQUAL() != null) {
				currentList.add(ib.instr().movop("EQ", regCount-2, 1).build());
				currentList.add(ib.instr().movop("NE", regCount-2, 0).build());
//				currentList.add(new Instruction("MOVEQ r" + (regCount - 2) + ", #1\nMOVNE r" + (regCount - 2) + ", #0\n"));
			}
			if (ctx.NOT_EQUAL() != null) {
				currentList.add(ib.instr().movop("NE", regCount-2, 1).build());
				currentList.add(ib.instr().movop("EQ", regCount-2, 0).build());
//				currentList.add(new Instruction("MOVNE r" + (regCount - 2) + ", #1\nMOVEQ r" + (regCount - 2) + ", #0\n"));
			}
			regCount = regCount -2;
			
//			//back end while
//			if(inWhile) {
//				currentList.add(new Instruction("CMP r" + regCount + ", #1\nBEQ L" + (whileCount + 1) + "\n"));
//			}
			return new Info("r" + regCount).setType("reg"); 
		}
		
		if (one.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + one.int_value + "\n"));
			regCount ++;
			System.out.println("1");
		} else if (one.type.equals("bool")) {
			currentList.add(new Instruction("MOV r" + regCount + ", #" + (one.b_value ? 1 : 0) + "\n"));
			regCount ++;
			System.out.println("1");
		} else if (one.type.equals("char")) {
			currentList.add(new Instruction("MOV r" + regCount + ", #" + one.stringinfo + "\n"));
			regCount ++;
			System.out.println("1");
		} else if (one.type.equals("var")){
			
//			VariableFragment v  = new VariableFragment(one.stringinfo, funcCallOffset);
//			String load = (typeSize(ctx.math(0).returntype) == 4)? "LDR" : "LDRSB";
//			currentList.add(new Instruction(Arrays.asList(new StringFragment(load + " r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.math(0).returntype), regCount, one.stringinfo, funcCallOffset).build());
			regCount++;
			System.out.println("2");
		}
		if (two.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + two.int_value + "\n"));
			regCount++;
			System.out.println("3");
		} else if(two.type.equals("bool")) {
			currentList.add(new Instruction("MOV r" + regCount + ", #" + (two.b_value ? 1 : 0) + "\n"));
			regCount ++;
			System.out.println("3");
		} else if(two.type.equals("char")) {
			if(two.stringinfo.length() > 3) {
				two.stringinfo = two.stringinfo.replace("\\n", "\n");
				two.stringinfo = two.stringinfo.replace("\\0", "\0");
				two.stringinfo = two.stringinfo.replace("\\b", "\b");
				two.stringinfo = two.stringinfo.replace("\\t", "\t");
				two.stringinfo = two.stringinfo.replace("\\f", "\f");
				two.stringinfo = two.stringinfo.replace("\\r", "\"");
				char c = two.stringinfo.charAt(1);
				int ascii = (int) c;
				currentList.add(new Instruction("MOV r" + regCount + ", #"  + ((ascii > 13)? "\'" + two.stringinfo.charAt(2) + "\'": String.valueOf(ascii)) + "\n"));
			} else {
				currentList.add(new Instruction("MOV r" + regCount + ", #" + two.stringinfo + "\n"));
			}
			regCount ++;
			System.out.println("3");
		} else if (two.type.equals("var")){
//			VariableFragment v  = new VariableFragment(two.stringinfo, funcCallOffset);
//			String load = (typeSize(ctx.math(1).returntype) == 4)? "LDR" : "LDRSB";
//			currentList.add(new Instruction(Arrays.asList(new StringFragment(load + " r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.math(1).returntype), regCount, two.stringinfo, funcCallOffset).build());
			regCount++;
			System.out.println("4");
		}
		
		currentList.add(new Instruction("CMP r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
		if (ctx.IS_EQUAL() != null) {
			currentList.add(ib.instr().movop("EQ", regCount-2, 1).build());
			currentList.add(ib.instr().movop("NE", regCount-2, 0).build());
//			currentList.add(new Instruction("MOVEQ r" + (regCount - 2) + ", #1\nMOVNE r" + (regCount - 2) + ", #0\n"));
		}
		if (ctx.NOT_EQUAL() != null) {
			currentList.add(ib.instr().movop("NE", regCount-2, 1).build());
			currentList.add(ib.instr().movop("EQ", regCount-2, 0).build());
//			currentList.add(new Instruction("MOVNE r" + (regCount - 2) + ", #1\nMOVEQ r" + (regCount - 2) + ", #0\n"));
		}
		regCount = regCount -2;
		
//		//back end while
//		if(inWhile) {
//			currentList.add(new Instruction("CMP r" + regCount + ", #1\nBEQ L" + (whileCount + 1) + "\n"));
//		}
		return new Info("r" + regCount).setType("reg"); 
	}

	@Override 
	public Info visitExpr_bin_bool_math_moreless(@NotNull WaccParser.Expr_bin_bool_math_morelessContext ctx) { 
		if (prints) System.out.println("visitExpr_bin_bool_math_moreless");
		Info one = visit(ctx.math(0));
		if (one.type.equals("reg")) {
			regCount ++;
			System.out.println("5");
		}
		Info two = visit(ctx.math(1));
		if (two.type.equals("reg"))  {
			regCount ++;
			System.out.println("6");
		}

		if(ctx.math(0).returntype instanceof PAIR_TYPE || 
				ctx.math(0).returntype instanceof ARRAY_TYPE ||
				ctx.math(1).returntype instanceof PAIR_TYPE ||
				ctx.math(1).returntype instanceof ARRAY_TYPE) {
			System.exit(200);
		}
				
		
		ctx.returntype = new BOOL();
		ctx.argtype = new EQUALITY();
		
		if(!SharedMethods.assignCompat(ctx.math(0).returntype, ctx.math(1).returntype)) {
			System.exit(200);
		}

		if(!ctx.argtype.getClass().isAssignableFrom(ctx.math(0).returntype.getClass())) {

			System.exit(200);
		}
		
		if (one.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + one.int_value + "\n"));
			regCount ++;
			System.out.println("1");
		} else if (one.type.equals("var")){
//			VariableFragment v  = new VariableFragment(one.stringinfo, funcCallOffset);
//			String load = (typeSize(ctx.math(0).returntype) == 4)? "LDR" : "LDRSB";
//			currentList.add(new Instruction(Arrays.asList(new StringFragment(load + " r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.math(0).returntype), regCount, one.stringinfo, funcCallOffset).build());
			regCount++;
			System.out.println("2");
		}
		if (two.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + two.int_value + "\n"));
			regCount++;
			System.out.println("3");
		} else if (two.type.equals("var")){
//			VariableFragment v  = new VariableFragment(two.stringinfo, funcCallOffset);
//			String load = (typeSize(ctx.math(1).returntype) == 4)? "LDR" : "LDRSB";
//			currentList.add(new Instruction(Arrays.asList(new StringFragment(load + " r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrsbVarOffset(typeSize(ctx.math(1).returntype), regCount, two.stringinfo, funcCallOffset).build());
			regCount++;
			System.out.println("4");
		}
		
		currentList.add(new Instruction("CMP r" + (regCount - 2) + ", r" + (regCount - 1) + "\n"));
		if (ctx.LESS() != null) {
			currentList.add(ib.instr().movop("LT", regCount-2, 1).build());
			currentList.add(ib.instr().movop("GE", regCount-2, 0).build());
//			currentList.add(new Instruction("MOVLT r" + (regCount - 2) + ", #1\nMOVGE r" + (regCount - 2) + ", #0\n"));
		}
		if (ctx.LESS_EQUAL() != null) {
			currentList.add(ib.instr().movop("LE", regCount-2, 1).build());
			currentList.add(ib.instr().movop("GT", regCount-2, 0).build());
//			currentList.add(new Instruction("MOVLE r" + (regCount - 2) + ", #1\nMOVGT r" + (regCount - 2) + ", #0\n"));
		}
		if (ctx.GREATER_EQUAL() != null) {
			currentList.add(ib.instr().movop("GE", regCount-2, 1).build());
			currentList.add(ib.instr().movop("LT", regCount-2, 0).build());
//			currentList.add(new Instruction("MOVGE r" + (regCount - 2) + ", #1\nMOVLT r" + (regCount - 2) + ", #0\n"));
		}
		if (ctx.GREATER() != null) {
			currentList.add(ib.instr().movop("GT", regCount-2, 1).build());
			currentList.add(ib.instr().movop("LE", regCount-2, 0).build());
//			currentList.add(new Instruction("MOVGT r" + (regCount - 2) + ", #1\nMOVLE r" + (regCount - 2) + ", #0\n"));
		}
		regCount = regCount -2;
		
//		if(inWhile) {
//			currentList.add(new Instruction("CMP r" + regCount + ", #1\nBEQ L" + (whileCount + 1) + "\n"));
//		}
		return new Info("r" + regCount).setType("reg"); 
	}

	
	@Override public Info visitExpr_bin_math(@NotNull WaccParser.Expr_bin_mathContext ctx) {
		if (prints) System.out.println("visitExpr_bin_math");
		Info i = visit(ctx.math());
		ctx.returntype = ctx.math().returntype;
		return i; 
	}
	
	@Override public Info visitExpr_bin_math_math(@NotNull WaccParser.Expr_bin_math_mathContext ctx) {
		if (prints) System.out.println("visitExpr_bin_math_math");
		visit(ctx.math());
		Info a = visit(ctx.plusminus());
		ctx.returntype = new INT();
		ctx.argtype = new INT();
		if(!SharedMethods.assignCompat(ctx.math().returntype, ctx.argtype)) {
			System.exit(200);
		}
		if(!SharedMethods.assignCompat(ctx.plusminus().returntype, ctx.argtype)) {
			System.exit(200);
		}
		
		if (a.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + (regCount + 1) + ", =" + a.int_value + "\n"));
		} else if (a.type.equals("reg")) {
			//do nothing
		} else {
			assert a.type.equals("var");
//			VariableFragment v  = new VariableFragment(a.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + (regCount + 1) + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount +1, a.stringinfo, funcCallOffset).build());
		}
		if (ctx.PLUS() != null) {
			err.pOverflow();
			currentList.add(ib.instr().triop("ADDS", regCount, regCount+1).build());
			currentList.add(new Instruction("BLVS p_throw_overflow_error\n"));
			}
		if (ctx.MINUS() != null) {
			err.pOverflow();
			currentList.add(ib.instr().triop("SUBS", regCount, regCount+1).build());
			currentList.add(new Instruction("BLVS p_throw_overflow_error\n"));
		}
		
		return (new Info("r" + regCount)).setType("reg"); 
	}
	
	@Override public Info visitExpr_bin_math_plusminus(@NotNull WaccParser.Expr_bin_math_plusminusContext ctx) {
		if (prints) System.out.println("visitExpr_bin_math_plusminus");
		Info one = visit(ctx.plusminus(0));
		if (one.type.equals("reg")) {regCount++;}
		Info two = visit(ctx.plusminus(1));
		if (two.type.equals("reg")) {regCount++;}
		ctx.returntype = new INT();
		ctx.argtype = new INT();
		if(!SharedMethods.assignCompat(ctx.plusminus(0).returntype, ctx.argtype)) {
			if (prints) System.out.println("got " +  ctx.plusminus(0).returntype);
			System.exit(200);
		}
		if (prints) System.out.println("HERE: " + ctx.plusminus(1).returntype);
		if(!SharedMethods.assignCompat(ctx.plusminus(1).returntype, ctx.argtype)) {
			System.exit(200);
		}
		
		
		if (one.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + one.int_value + "\n"));
			regCount ++;
		} else if (one.type.equals("reg")) {
			//do nothing
		} else {
			assert one.type.equals("var");
//			VariableFragment v  = new VariableFragment(one.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount, one.stringinfo, funcCallOffset).build());
			regCount ++;
		}
		if (two.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + two.int_value + "\n"));
			regCount ++;
		} else if (two.type.equals("reg")) {
			//do nothing
		} else {
			assert two.type.equals("var");
//			VariableFragment v  = new VariableFragment(two.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount, two.stringinfo, funcCallOffset).build());
			regCount ++;
		}
		if (ctx.PLUS() != null) {
			err.pOverflow();
			currentList.add(ib.instr().triop("ADDS", regCount-2, regCount-1).build());
			currentList.add(new Instruction("BLVS p_throw_overflow_error\n"));
			}
		if (ctx.MINUS() != null) {
			err.pOverflow();
			currentList.add(ib.instr().triop("SUBS", regCount-2, regCount-1).build());
			currentList.add(new Instruction("BLVS p_throw_overflow_error\n"));
		}
		
		regCount = regCount -2;
		return (new Info("r" + regCount)).setType("reg"); 
	}
	
	@Override public Info visitExpr_bin_plusminus(@NotNull WaccParser.Expr_bin_plusminusContext ctx) {
		if (prints) System.out.println("visitExpr_bin_plusminus");
		Info i = visit(ctx.plusminus());
		ctx.returntype = ctx.plusminus().returntype;
		return i; 
	}
	
	@Override public Info visitExpr_bin_plus_plus(@NotNull WaccParser.Expr_bin_plus_plusContext ctx) {
		if (prints) System.out.println("visitExpr_bin_math_math");
		visit(ctx.plusminus());
		Info a = visit(ctx.atom());
		ctx.returntype = new INT();
		ctx.argtype = new INT();
		if(!SharedMethods.assignCompat(ctx.plusminus().returntype, ctx.argtype)) {
			System.exit(200);
		}
		if(!SharedMethods.assignCompat(ctx.atom().typename, ctx.argtype)) {
			System.exit(200);
		}
		
		if (a.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + (regCount + 1) + ", =" + a.int_value + "\n"));
		} else if (a.type.equals("reg")) {
			//do nothing
		} else {
			assert a.type.equals("var");
//			VariableFragment v  = new VariableFragment(a.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + (regCount + 1) + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount +1, a.stringinfo, funcCallOffset).build());
		}
		if (ctx.MULTIPLY() != null) {
			err.pOverflow();
			currentList.add(ib.instr().qop("SMULL", regCount, regCount+1, regCount, regCount+1).build());
			currentList.add(new Instruction("CMP r" + (regCount + 1) + ", r" + regCount + ", ASR #31\nBLNE p_throw_overflow_error\n"));
		}
		if (ctx.DIVIDE() != null) {
			err.pDivZero();
			currentList.add(ib.instr().movReg(0, regCount).build());
			currentList.add(ib.instr().movReg(1, regCount+1).build());
			currentList.add(new Instruction("BL p_check_divide_by_zero\nBL __aeabi_idiv\n"));
			currentList.add(ib.instr().movReg(regCount, 0).build());
//			currentList.add(new Instruction("MOV r0, r" + regCount + "\nMOV r1, r" + (regCount + 1) + "\nBL p_check_divide_by_zero\nBL __aeabi_idiv\nMOV r" + regCount + ", r0\n"));
		}
		if (ctx.MOD() != null) {
			err.pDivZero();
			currentList.add(ib.instr().movReg(0, regCount).build());
			currentList.add(ib.instr().movReg(1, regCount+1).build());
			currentList.add(new Instruction("BL p_check_divide_by_zero\nBL __aeabi_idivmod\n"));
			currentList.add(ib.instr().movReg(regCount, 1).build());
//			currentList.add(new Instruction("MOV r0, r" + regCount + "\nMOV r1, r" + (regCount + 1) + "\nBL p_check_divide_by_zero\nBL __aeabi_idivmod\nMOV r" + regCount + ", r1\n"));
		}
		
		return (new Info("r" + regCount)).setType("reg"); 
	}

	
	@Override public Info visitExpr_bin_plus_atom(@NotNull WaccParser.Expr_bin_plus_atomContext ctx) {
		if (prints) System.out.println("visitExpr_bin_plus_atom");
		Info one = visit(ctx.atom(0));
		Info two = visit(ctx.atom(1));
		ctx.returntype = new INT();
		ctx.argtype = new INT();
		if(!SharedMethods.assignCompat(ctx.atom(0).typename, ctx.argtype)) {
			if (prints) System.out.println("got " +  ctx.atom(0).typename);
			System.exit(200);
		}
		if (prints) System.out.println("HERE2: " + ctx.atom(1).typename);
		if(!SharedMethods.assignCompat(ctx.atom(1).typename, ctx.argtype)) {
			System.exit(200);
		}
		
		
		if (one.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + regCount + ", =" + one.int_value + "\n"));
		} else if (one.type.equals("reg")) {
			//do nothing
		} else {
			assert one.type.equals("var");
//			VariableFragment v  = new VariableFragment(one.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + regCount + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount, one.stringinfo, funcCallOffset).build());
		}
		if (two.type.equals("int")) {
			currentList.add(new Instruction("LDR r" + (regCount + 1) + ", =" + two.int_value + "\n"));
		} else if (two.type.equals("reg")) {
			//do nothing
		} else {
			assert two.type.equals("var");
//			VariableFragment v  = new VariableFragment(two.stringinfo, funcCallOffset);
//			currentList.add(new Instruction(Arrays.asList(new StringFragment("LDR r" + (regCount + 1) + ", [sp"), v, new StringFragment("]\n")), v));
			currentList.add(ib.instr().ldrVarOffset(regCount +1, two.stringinfo, funcCallOffset).build());
		}
		if (ctx.MULTIPLY() != null) {
			err.pOverflow();
			currentList.add(ib.instr().qop("SMULL", regCount, regCount+1, regCount, regCount+1).build());
			currentList.add(new Instruction("CMP r" + (regCount + 1) + ", r" + regCount + ", ASR #31\nBLNE p_throw_overflow_error\n"));
		}
		if (ctx.DIVIDE() != null) {
			err.pDivZero();
			currentList.add(ib.instr().movReg(0, regCount).build());
			currentList.add(ib.instr().movReg(1, regCount+1).build());
			currentList.add(new Instruction("BL p_check_divide_by_zero\nBL __aeabi_idiv\n"));
			currentList.add(ib.instr().movReg(regCount, 0).build());
//			currentList.add(new Instruction("MOV r0, r" + regCount + "\nMOV r1, r" + (regCount + 1) + "\nBL p_check_divide_by_zero\nBL __aeabi_idiv\nMOV r" + regCount + ", r0\n"));
		}
		if (ctx.MOD() != null) {
			err.pDivZero();
			currentList.add(ib.instr().movReg(0, regCount).build());
			currentList.add(ib.instr().movReg(1, regCount+1).build());
			currentList.add(new Instruction("BL p_check_divide_by_zero\nBL __aeabi_idivmod\n"));
			currentList.add(ib.instr().movReg(regCount, 1).build());
//			currentList.add(new Instruction("MOV r0, r" + regCount + "\nMOV r1, r" + (regCount + 1) + "\nBL p_check_divide_by_zero\nBL __aeabi_idivmod\nMOV r" + regCount + ", r1\n"));
		}
				
		return (new Info("r" + regCount)).setType("reg"); 
	}
	
	@Override public Info visitExpr_bin_atom(@NotNull WaccParser.Expr_bin_atomContext ctx) {
		if (prints) System.out.println("visitExpr_bin_atom");
		Info i = visit(ctx.atom());
		ctx.returntype = ctx.atom().typename;
		return i; 
	}
	
	@Override public Info visitAtom_int(@NotNull WaccParser.Atom_intContext ctx) {
		ctx.typename = new INT();
		return visit(ctx.int_liter());
	}
	
	@Override public Info visitAtom_char(@NotNull WaccParser.Atom_charContext ctx) {
		ctx.typename = new CHAR();
		return (new Info(ctx.char_liter().CHARACTER().getText())).setType("char");
	}
	
	@Override public Info visitAtom_bool(@NotNull WaccParser.Atom_boolContext ctx) {
		ctx.typename = new BOOL();
		boolean b = false;
		if (ctx.bool_liter().TRUE() != null) {
			b = true;
		}
		return new Info(b);
	}
	
	@Override public Info visitAtom_ident(@NotNull WaccParser.Atom_identContext ctx) {
		visit(ctx.ident());
		ctx.typename = ctx.ident().typename;
		return (new Info(ctx.ident().VARIABLE().getText())).setType("var");
	}
	
	@Override public Info visitAtom_brackets(@NotNull WaccParser.Atom_bracketsContext ctx) {
		Info i = visit(ctx.expr());
		ctx.typename = ctx.expr().typename;
		if (i != null && i.type != null) {
			return i;
		}
		return (new Info("")).setType("reg");
	}
	
	@Override public Info visitAtom_unary(@NotNull WaccParser.Atom_unaryContext ctx) {
		if (prints) System.out.println("visitAtom_unary");
		visit(ctx.expr());
		visit(ctx.unary_oper());
		if(ctx.expr().typename == null) {
			System.exit(200);
		}

		if(!((ctx.unary_oper().argtype.getClass()) == (ctx.expr().typename.getClass()))){
			System.exit(200);
		}
	
		ctx.typename = ctx.unary_oper().returntype;
		if (ctx.typename instanceof INT || ctx.typename instanceof BOOL || ctx.typename instanceof CHAR) {
			return new Info("unary").setType("reg");
		}
		return null;
	}
	
	
	
	@Override public Info visitExpr_unary(@NotNull WaccParser.Expr_unaryContext ctx) {
		if (prints) System.out.println("visitExpr_unary");
		visit(ctx.expr());
		visit(ctx.unary_oper());
		if(ctx.expr().typename == null) {
			System.exit(200);
		}

		if(!((ctx.unary_oper().argtype.getClass()) == (ctx.expr().typename.getClass()))){
			System.exit(200);
		}
	
		ctx.typename = ctx.unary_oper().returntype;
		//return new Info("argument").setType(ctx.typename.toString());
		return null;
	}
	
	@Override public Info visitUnary_not(@NotNull WaccParser.Unary_notContext ctx) { 
		if (prints) System.out.println("Unary_not");

		ctx.argtype = new BOOL(); ctx.returntype = new BOOL(); 
		currentList.add(new Instruction("EOR r" + regCount + ", r" + regCount + ", #1\n"));
		return null; }

	
	@Override public Info visitUnary_minus(@NotNull WaccParser.Unary_minusContext ctx) { 
		if (prints) System.out.println("Unary_minus");
		ctx.argtype = new INT(); ctx.returntype = new INT();
		err.pOverflow();
		currentList.add(new Instruction("RSBS r" + regCount + ", r" + regCount + ", #0\n"));
		currentList.add(new Instruction("BLVS p_throw_overflow_error\n"));
		return null; }
	
	@Override public Info visitUnary_len(@NotNull WaccParser.Unary_lenContext ctx) {
		if (prints) System.out.println("Unary_len");
		if (prints) System.out.println("argtype before " + ctx.argtype);
		currentList.add(new Instruction("LDR r" + regCount + ", [r" + regCount + "]\n"));

		ctx.argtype = new ARRAY_TYPE(new NULL()); ctx.returntype = new INT(); return null; }
	
	@Override public Info visitUnary_chr(@NotNull WaccParser.Unary_chrContext ctx) { 
		if (prints) System.out.println("Unary_chr");
		ctx.argtype = new INT(); ctx.returntype = new CHAR(); return null; }
	
	@Override public Info visitUnary_ord(@NotNull WaccParser.Unary_ordContext ctx) { 
		if (prints) System.out.println("Unary_ord");
		ctx.argtype = new CHAR(); ctx.returntype = new INT(); return null; }

	@Override public Info visitExpr_brackets(@NotNull WaccParser.Expr_bracketsContext ctx) {
		if (prints) System.out.println("Unary_brackets");
		visit(ctx.expr());
		ctx.typename = ctx.expr().typename;
		return new Info("argument").setType(ctx.typename.toString());
	}
	
	private boolean isAnum(String s) {
		try {
			Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return false;
		}
		return true;
	}

}
