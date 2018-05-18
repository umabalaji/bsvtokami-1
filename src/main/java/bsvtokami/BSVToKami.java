package bsvtokami;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;


public class BSVToKami extends BSVBaseVisitor<String>
{
    private static Logger logger = Logger.getGlobal();
    public static String newline = System.getProperty("line.separator");

    private final File ofile;
    private PrintStream printstream;
    private final StaticAnalysis scopes;
    private SymbolTable scope;
    private String pkgName;
    private Package pkg;
    private ModuleDef moduleDef;
    private ArrayList<String> instances;
    private boolean actionContext;
    private boolean stmtEmitted;
    private boolean inModule;
    // for modules and rules
    private ArrayList<String> letBindings;
    private ArrayList<String> statements;

    BSVToKami(String pkgName, File ofile, StaticAnalysis scopes) {
        this.scopes = scopes;
        this.pkgName = pkgName;
        this.ofile = ofile;
        pkg = new Package(pkgName);
        actionContext = false;
        inModule = false;
        try {
            printstream = new PrintStream(ofile);
        } catch (FileNotFoundException ex) {
            logger.severe(ex.toString());
            printstream = null;
        }
    }

    @Override public String visitImportitem(BSVParser.ImportitemContext ctx) {
	printstream.println(String.format("Require Import %s.", ctx.pkgname.getText()));
	return null;
    }

    @Override
    public String visitPackagedef(BSVParser.PackagedefContext ctx) {
        logger.fine("Package " + pkgName);

        printstream.println("Require Import Bool String List Arith.");
        printstream.println("Require Import Kami.");
        printstream.println("Require Import Lib.Indexer.");
        printstream.println("Require Import Bsvtokami.");
        printstream.println("");
        printstream.println("Require Import FunctionalExtensionality.");
        printstream.println("");
        printstream.println("Set Implicit Arguments.");
        printstream.println("");
        printstream.println();

        scope = scopes.pushScope(ctx);

        if (ctx.packagedecl() != null) {
            if (!pkgName.equals(ctx.packagedecl().pkgname.getText())) {
                logger.fine("Expected " + pkgName + " found " + ctx.packagedecl().pkgname.getText());
            }
        }
        visitChildren(ctx);
        scopes.popScope();
        return null;
    }

    @Override public String visitInterfacedecl(BSVParser.InterfacedeclContext ctx) {
	// modules are represented by a string: the name of the instance
	String interfaceName = ctx.typedeftype().typeide().getText();
	BSVTypeVisitor typeVisitor = new BSVTypeVisitor(scopes);
	typeVisitor.pushScope(scope);
	BSVType interfaceType = typeVisitor.visit(ctx.typedeftype());
	printstream.println(String.format("(* * interface %s *)", interfaceType));

	TreeMap<String,BSVType> freeTypeVariables = interfaceType.getFreeVariables();

	StringBuilder paramsStringBuilder = new StringBuilder();
        for (Map.Entry<String,BSVType> entry: freeTypeVariables.entrySet()) {
	    BSVType freeType = entry.getValue();
	    logger.fine("Ifc decl: Free type variable " + freeType + (freeType.numeric ? " nat" : " interface type"));
	    paramsStringBuilder.append(String.format(" (%s : %s)",
						     entry.getKey(),
						     (freeType.numeric ? "nat" : "Kind")));
	}
	String paramsString = paramsStringBuilder.toString();

	printstream.println(String.format("Record %s%s := {", interfaceName, paramsString));
	printstream.println(String.format("    %s'interface: Modules;", interfaceName));
	for (BSVParser.InterfacememberdeclContext decl: ctx.interfacememberdecl()) {
	    if (decl.methodproto() != null) {
		printstream.println(String.format("    %s'%s : string;", interfaceName, decl.methodproto().name.getText()));
	    } else {
		String kamiType = bsvTypeToKami(decl.subinterfacedecl().bsvtype());
		assert kamiType != null;
		printstream.println(String.format("    %s'%s : %s;",
						  interfaceName, decl.subinterfacedecl().lowerCaseIdentifier().getText(), kamiType));
	    }
	}
	printstream.println(String.format("}."));
	printstream.println("");
	// for (BSVParser.InterfacememberdeclContext decl: ctx.interfacememberdecl()) {
	//     if (decl.methodproto() != null) {
	// 	BSVParser.MethodprotoformalsContext formals = decl.methodproto().methodprotoformals();
	// 	if (formals != null && formals.methodprotoformal().size() > 1) {
	// 	    ArrayList<String> fields = new ArrayList<>();
	// 	    for (BSVParser.MethodprotoformalContext formal: formals.methodprotoformal()) {
	// 		String formalName = formal.name.getText();
	// 		assert formal.bsvtype() != null;
	// 		String kamiType = bsvTypeToKami(formal.bsvtype());
	// 		fields.add(String.format("    \"%s\" :: %s", formalName, kamiType));
	// 	    }
	// 	    printstream.println(String.format("Notation %s'%s'args := STRUCT {", interfaceName, decl.methodproto().name.getText()));
	// 	    printstream.println(String.join(';' + newline, fields));
	// 	    printstream.println(String.format("}."));
	// 	}
	//     }
	// }
	return null;
    }

    @Override public String visitTypeclassinstance(BSVParser.TypeclassinstanceContext ctx) {
        //FIXME: overloading
        //scope = scopes.pushScope(ctx);
        //visitChildren(ctx);
        //scope = scopes.popScope();
        return null;
    }

    void declareSubstruct(ArrayList<String> members, String fieldPrefix,
                          BSVParser.SubstructContext substruct) {
        for (BSVParser.StructmemberContext member: substruct.structmember()) {
            assert member.subunion() == null;
            if (member.bsvtype() != null) {
                members.add(String.format("    \"%s$%s\" :: %s",
                                          fieldPrefix,
                                          member.lowerCaseIdentifier().getText(),
                                          bsvTypeToKami(member.bsvtype())));
            }
        }
    }

    @Override public String visitTypedefstruct(BSVParser.TypedefstructContext ctx) {
        //scope = scopes.pushScope(ctx);
        boolean wasInModule = inModule;
        inModule = true;

        String typeName = ctx.typedeftype().typeide().getText();
        System.err.println(String.format("BSVTOKAMI typedef struct %s\n", typeName));
        //assert ctx.typedeftype().typeformals() == null: "Typedef struct with type formals at " + StaticAnalysis.sourceLocation(ctx);
        String constructorParams = "";
        String params = "";
        if (ctx.typedeftype().typeformals() != null) {
            StringBuilder constructorParamsBuilder = new StringBuilder();
            StringBuilder paramsBuilder = new StringBuilder();
            for (BSVParser.TypeformalContext formal: ctx.typedeftype().typeformals().typeformal()) {
                String name = formal.typeide().getText();
                //assert formal.numeric != null : "Expecting numeric type parameter at " + StaticAnalysis.sourceLocation(formal);
                constructorParamsBuilder.append(String.format(" (%s : %s)", name,
							      ((formal.numeric != null)? "nat" : "Type")));
                paramsBuilder.append(String.format(" %s", name));
            }

            constructorParams = constructorParamsBuilder.toString();
            params = paramsBuilder.toString();
        }

        printstream.println(String.format("Definition %sFields%s := (STRUCT {", typeName, constructorParams));
        ArrayList<String> members = new ArrayList<>();
        for (BSVParser.StructmemberContext member: ctx.structmember()) {
            assert member.subunion() == null;
            if (member.bsvtype() != null) {
                members.add(String.format("    \"%s\" :: %s",
                                          member.lowerCaseIdentifier().getText(),
                                          bsvTypeToKami(member.bsvtype())));
            } else {
            }
        }
        printstream.print(String.join(";\n", members));
        printstream.println("}).");
        printstream.println(String.format("Definition %s %s := Struct (%sFields%s).", typeName, constructorParams, typeName, params));
        printstream.println("");

        //scope = scopes.popScope();
        inModule = wasInModule;
        return null;
    }

    @Override public String visitTypedefenum(BSVParser.TypedefenumContext ctx) {
        //scope = scopes.pushScope(ctx);
        boolean wasInModule = inModule;
        inModule = true;

        String typeName = ctx.upperCaseIdentifier().getText();
        System.err.println(String.format("BSVTOKAMI typedef enum %s\n", typeName));
        printstream.println(String.format("Definition %sFields := (STRUCT { \"$tag\" :: (Bit 8) }).", typeName));
        printstream.println(String.format("Definition %s := (Struct %sFields).", typeName, typeName));

        String typedefname = ctx.upperCaseIdentifier().getText();
        for (BSVParser.TypedefenumelementContext elt: ctx.typedefenumelement()) {
            String basetagname = elt.upperCaseIdentifier().getText();
            long tagCount = 1;
            boolean numbered = false;
            long tagFrom = 0;

            System.err.println(String.format("enum %s from %s to %s",
                                             basetagname,
                                             ((elt.from != null) ? elt.from.getText() : ""),
                                             ((elt.to != null) ? elt.to.getText() : "")));
            if (elt.from != null) {
                numbered = true;
                tagCount = Long.parseLong(elt.from.getText());
                if (elt.to != null) {
                    tagFrom = tagCount;
                    tagCount = Long.parseLong(elt.to.getText()) - tagFrom + 1;
                }
            }

            for (int i = 0; i < tagCount; i++) {
                String tagname = basetagname;
                if (numbered) {
                    tagname = String.format("%s%d", basetagname, tagFrom + i);
                }
                SymbolTableEntry entry = scope.lookup(tagname);
                assert entry != null;
                assert entry.value != null;
                IntValue tagValue = (IntValue)entry.value;
                assert tagValue != null;
                printstream.println(String.format("Notation %s := (STRUCT { \"$tag\" ::= $%d })%%kami_expr.",
                                                  tagname, tagValue.value));
            }
        }

        //scope = scopes.popScope();
        inModule = wasInModule;
        return null;
    }
    @Override public String visitTypedeftaggedunion(BSVParser.TypedeftaggedunionContext ctx) {
        //scope = scopes.pushScope(ctx);
        boolean wasInModule = inModule;
        inModule = true;

        String typeName = ctx.typedeftype().typeide().getText();
        String constructorParams = "";
        String params = "";
        if (ctx.typedeftype().typeformals() != null) {
            StringBuilder constructorParamsBuilder = new StringBuilder();
            StringBuilder paramsBuilder = new StringBuilder();
            for (BSVParser.TypeformalContext formal: ctx.typedeftype().typeformals().typeformal()) {
                String name = formal.typeide().getText();
                assert formal.numeric != null : "Expecting numeric type parameter at " + StaticAnalysis.sourceLocation(formal);
                constructorParamsBuilder.append(String.format(" (%s : nat)", name));
                paramsBuilder.append(String.format(" %s", name));
            }

            constructorParams = constructorParamsBuilder.toString();
            params = paramsBuilder.toString();
        }

        System.err.println(String.format("BSVTOKAMI typedef tagged union %s\n", typeName));

        printstream.println(String.format("Definition %sFields%s := (STRUCT {", typeName, constructorParams));
        ArrayList<String> members = new ArrayList<>();
        members.add(String.format("    \"$tag\" :: (Bit 8)"));
        for (BSVParser.UnionmemberContext member: ctx.unionmember()) {
            assert member.subunion() == null;
            if (member.bsvtype() != null) {
                members.add(String.format("    \"%s\" :: %s",
                                          member.upperCaseIdentifier().getText(),
                                          bsvTypeToKami(member.bsvtype())));
            } else if (member.substruct() != null) {
                String memberName = member.upperCaseIdentifier().getText();
                declareSubstruct(members, memberName, member.substruct());
            } else {
            }
        }
        printstream.print(String.join(";\n", members));
        printstream.println("}).");
        printstream.println(String.format("Definition %s%s := Struct (%sFields%s).", typeName, constructorParams, typeName, params));
        //scope = scopes.popScope();
        inModule = wasInModule;
        return null;
    }

    @Override public String visitModuledef(BSVParser.ModuledefContext ctx) {
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
	letBindings = new ArrayList<>();
	statements = new ArrayList<>();

        for (BSVParser.AttributeinstanceContext attrinstance: ctx.attributeinstance()) {
            for (BSVParser.AttrspecContext attr: attrinstance.attrspec()) {
                if (attr.identifier().getText().equals("nogen"))
                return null;
            }
        }
        instances = new ArrayList<>();
        scope = scopes.pushScope(ctx);

        BSVParser.ModuleprotoContext moduleproto = ctx.moduleproto();
        String moduleName = moduleproto.name.getText();
	String interfaceName = (moduleName.startsWith("mk") ? moduleName.substring(2) : moduleName);
	BSVTypeVisitor typeVisitor = new BSVTypeVisitor(scopes);
	typeVisitor.pushScope(scope);
	BSVType moduleType = typeVisitor.visit(ctx.moduleproto());
	TreeMap<String,BSVType> freeTypeVariables = moduleType.getFreeVariables();
	logger.fine(String.format("module %s type %s free vars %d",
				  moduleName, moduleType, freeTypeVariables.size()));
	BSVType interfaceType;
	if (ctx.moduleproto().moduleinterface != null) {
	    // FIXME
	    interfaceType = typeVisitor.visit(ctx.moduleproto().moduleinterface);
	    assert interfaceType != null;
	} else {
	    // FIXME also
	    interfaceType = typeVisitor.visit(ctx.moduleproto().methodprotoformals().methodprotoformal(0));
	    assert interfaceType != null;
	}
        interfaceType = typeVisitor.dereferenceTypedef(interfaceType);
	interfaceName = interfaceType.name;
        String sectionName = "Section'" + moduleName;

	moduleDef = new ModuleDef(moduleName);
        pkg.addStatement(moduleDef);
        InstanceNameVisitor inv = new InstanceNameVisitor(scope);
        inv.visit(ctx);

        logger.fine("module " + moduleName);
	printstream.println("Module " + moduleName + ".");
        printstream.println("    Section " + sectionName + ".");
        for (Map.Entry<String,BSVType> entry: freeTypeVariables.entrySet()) {
	    BSVType freeType = entry.getValue();
	    boolean isNumeric = freeType.numeric;
	    // FIXME: heuristic
	    if (freeType.name.endsWith("sz"))
		isNumeric = true;
	    logger.fine("Module def: Free type variable " + freeType + (isNumeric ? " numeric" : " interface type"));

	    printstream.println(String.format("    Variable %s : %s.",
					      entry.getKey(),
					      (isNumeric ? "nat" : "Kind")));
	}

        printstream.println("    Variable instancePrefix: string.");

        if (moduleproto.methodprotoformals() != null) {
            for (BSVParser.MethodprotoformalContext formal : moduleproto.methodprotoformals().methodprotoformal()) {
		String typeName = bsvTypeToKami(formal.bsvtype());
                if (formal.name != null)
                    printstream.println(String.format("    Variable %s: %s.", formal.name.getText(), typeName));
            }
        }

	// after module proto has been emitted, then assert inModule to translate to Kami types rather than Gallina types
        boolean wasInModule = inModule;
        inModule = true;

        for (Map.Entry<String,TreeSet<SymbolTableEntry>> entry: inv.methodsUsed.entrySet()) {
            String instanceName = entry.getKey();
            TreeSet<SymbolTableEntry> methods = entry.getValue();
            for (SymbolTableEntry methodEntry: methods) {
                String method = methodEntry.name;
                BSVType methodType = methodEntry.type;
		if (methodType.name.equals("Function"))  {
		    assert methodType.params.size() == 2: "Unhandled method " + method + " has type " + methodType + " from interface " + ((methodEntry.parent != null) ? methodEntry.parent.name : "<unknown>");
		    BSVType argType = methodType.params.get(0);
		    BSVType returnType = methodType.params.get(1);
		    SymbolTableEntry methodInterfaceEntry = methodEntry.parent;
		    assert methodInterfaceEntry != null;
		    String methodInterfaceName = methodInterfaceEntry.name;
		    printstream.println(String.format("    Let %1$s%2$s := MethodSig (%5$s'%2$s %1$s) (%3$s) : %4$s.",
						      instanceName, method, bsvTypeToKami(argType), bsvTypeToKami(returnType), methodInterfaceName));
		} else {
		    printstream.println(String.format("(* FIXME: interface %s subinterface %s *)", methodEntry.parent.name, method));
		}
            }
        }

        String stmtPrefix = "    ";
        for (BSVParser.ModulestmtContext modulestmt: ctx.modulestmt()) {
            printstream.print(stmtPrefix);
            visit(modulestmt);
        }
	if (letBindings.size() > 0) {
	    for (String letBinding: letBindings) {
		printstream.println(String.format("       Let %s.", letBinding));
	    }
	}
        printstream.println("    Definition " + moduleName + "Module :=");
	printstream.println("        (BKMODULE {");
	if (statements.size() > 0) {
	    String sep = "    ";
	    for (String statement: statements) {
		printstream.println(String.format("       %s%s", sep, statement));
		sep = "with ";
	    }
	}
        printstream.print("    })");
	printstream.println(". (* " + ctx.moduleproto().name.getText() + " *)" + "\n");

        if (instances.size() > 0)
            printstream.println(String.format("    Definition %sInstances := (%s)%%kami.",
                                             moduleName,
                                             String.join("\n            ++ ", instances)));

	SymbolTableEntry interfaceEntry = scope.lookupType(interfaceName);
	assert interfaceEntry != null: "No symbol table entry for interface " + interfaceName + " at location " + StaticAnalysis.sourceLocation(ctx);
        assert interfaceEntry.mappings != null: "No interface mappings for " + interfaceName + " at location " + StaticAnalysis.sourceLocation(ctx);

	StringBuilder methodNames = new StringBuilder();
        for (Map.Entry<String,SymbolTableEntry> iterator: interfaceEntry.mappings.bindings.entrySet()) {
            String methodName = iterator.getKey();
	    methodNames.append(String.format(" (instancePrefix--\"%s\")", methodName));
	}

        printstream.print(String.format("    Definition %1$s := Build_%2$s ", moduleName, interfaceName));
        for (Map.Entry<String,BSVType> entry: freeTypeVariables.entrySet()) {
	    printstream.print(String.format("%s ", entry.getKey()));
	}
        if (instances.size() > 0)
            printstream.print(String.format("(%1$sInstances ++ ",
                                            moduleName));

        printstream.print(String.format("%1$sModule%%kami", moduleName));
        if (instances.size() > 0)
	    printstream.print(")");
	printstream.print(methodNames.toString());
	printstream.println(".");

        printstream.println("    End " + sectionName + ".");
        printstream.println("End " + moduleName + ".");
	printstream.println("");
        scope = scopes.popScope();
        moduleDef = null;
        logger.fine("endmodule : " + moduleName);
        inModule = wasInModule;

	letBindings = parentLetBindings;
	statements  = parentStatements;
        return null;
    }

    BSVParser.CallexprContext getCall(ParserRuleContext ctx) {
        return new CallVisitor().visit(ctx);
    }

    @Override public String visitVarBinding(BSVParser.VarBindingContext ctx) {
        BSVParser.BsvtypeContext t = ctx.t;
	if (statements == null) {
	    logger.fine("Visiting var binding but not collecting statements at " + StaticAnalysis.sourceLocation(ctx));
	    return "";
	}
	BSVTypeVisitor typeVisitor = new BSVTypeVisitor(scopes);
	typeVisitor.pushScope(scope);
	
        for (BSVParser.VarinitContext varinit: ctx.varinit()) {
	    StringBuilder statement = new StringBuilder();
            String varName = varinit.var.getText();
            assert scope != null : "No scope to evaluate var binding " + ctx.getText();
            SymbolTableEntry varEntry = scope.lookup(varName);
            BSVParser.ExpressionContext rhs = varinit.rhs;
	    assert varEntry != null : "No var entry for " + varName + " at " + StaticAnalysis.sourceLocation(ctx);
	    BSVType varType = varEntry.type;
            if (rhs != null) {
                BSVParser.CallexprContext call = getCall(rhs);
                if (call != null) {
		    String functionName = "";
		    if (call != null)
			functionName = call.fcn.getText();

		    System.err.println(String.format("var binding functionName=%s at %s",
						     functionName,
						     StaticAnalysis.sourceLocation(ctx)));

		    if (functionName.equals("truncate")) {
			List<BSVParser.ExpressionContext> args = call.expression();
			BSVType arg0Type = typeVisitor.visit(args.get(0));
			String varWidth = bsvTypeSize(varType);
			String lsbWidth = bsvTypeSize(arg0Type);
			String msbWidth = String.format("(%s - %s)", varWidth, lsbWidth);;
			statement.append(String.format("LET %s : %s <- (UniBit (Trunc %s %s) %s)",
						       varName,
						       bsvTypeToKami(t),
						       lsbWidth,
						       msbWidth,
						       visit(args.get(0))));
		    } else if (functionName.equals("truncateLSB")) {
			List<BSVParser.ExpressionContext> args = call.expression();
			BSVType arg0Type = typeVisitor.visit(args.get(0));
			String varWidth = bsvTypeSize(varType);
			String lsbWidth = bsvTypeSize(arg0Type);
			String msbWidth = String.format("(%s - %s)", varWidth, lsbWidth);;
			statement.append(String.format("LET %s : %s <- (UniBit (TruncLsb %s %s) %s)",
						       varName,
						       bsvTypeToKami(t),
						       lsbWidth,
						       msbWidth,
						       visit(args.get(0))));
		    } else {
			statement.append(String.format("LET %s <- ", varName, visit(rhs)));
		    }
                } else {
                    statement.append(String.format("        LET %s : %s <- ", varName, bsvTypeToKami(t)));
		    statement.append(visit(rhs));
                }
            } else {
                System.err.println("No rhs for " + ctx.getText() + " at " + StaticAnalysis.sourceLocation(ctx));
                statement.append(String.format("        LET %s : %s", varName, bsvTypeToKami(t)));
            }
	    statements.add(statement.toString());
        }
	return null;
    }
    @Override public String visitLetBinding(BSVParser.LetBindingContext ctx) {
        BSVParser.ExpressionContext rhs = ctx.rhs;
        BSVParser.CallexprContext call = getCall(rhs);
        assert ctx.lowerCaseIdentifier().size() == 1;
	StringBuilder statement = new StringBuilder();
	statement.append(String.format("        %s ", (call != null) ? "Call" : "LET"));
        for (BSVParser.LowerCaseIdentifierContext ident: ctx.lowerCaseIdentifier()) {
            String varName = ident.getText();
            SymbolTableEntry entry = scope.lookup(varName);
            assert entry != null : String.format("No entry for %s at %s",
                                                 varName, StaticAnalysis.sourceLocation(ctx));
            statement.append(String.format("%s : %s", varName, bsvTypeToKami(entry.type)));
        }

        if (ctx.op != null) {
            statement.append(String.format(" %s ", (call != null) ? "<-" : ctx.op.getText()));
	    statement.append(visit(ctx.rhs));
	}
        statements.add(statement.toString());
	return null;
    }
    @Override public String visitActionBinding(BSVParser.ActionBindingContext ctx) {
        String typeName = ctx.t.getText();
        String varName = ctx.var.getText();
        BSVParser.ExpressionContext rhs = ctx.rhs;
        SymbolTableEntry entry = scope.lookup(varName);
        assert entry != null: "Null var name in " + ctx.getText();
        BSVType bsvtype = entry.type;
        InstanceNameVisitor inv = new InstanceNameVisitor(scope);
        String calleeInstanceName = inv.visit(ctx.rhs);
        if (calleeInstanceName != null && actionContext)
            calleeInstanceName = calleeInstanceName.replace(".", "");

	StringBuilder statement = new StringBuilder();

        if (typeName.startsWith("Reg")) {
            BSVType paramtype = bsvtype.params.get(0);
	    letBindings.add(String.format("%s : string := instancePrefix--\"%s\"", varName, varName));
            statement.append("Register " + varName + " : " + bsvTypeToKami(paramtype)
                             + " <- ");

            BSVParser.CallexprContext call = getCall(ctx.rhs);
	    if (call != null)
		logger.fine("Register " + call.getText() + " fcn " + ((call.fcn != null) ? call.fcn.getText() : "")
			    + " at " + StaticAnalysis.sourceLocation(call));

            if (call != null && call.fcn != null && call.fcn.getText().equals("mkReg")) {
		logger.fine("mkReg " + call.expression().get(0).getText());
                statement.append("$" + call.expression().get(0).getText());
	    } else if (call != null && call.fcn != null && call.fcn.getText().equals("mkRegU")) {
		logger.fine("mkRegU");
                statement.append("Default");
            } else {
                statement.append(visit(ctx.rhs));
            }

        } else if (calleeInstanceName != null && actionContext) {
            statement.append(String.format("        Call %s <- %s()", varName, calleeInstanceName));
        } else if (!actionContext) {
            BSVParser.CallexprContext call = getCall(ctx.rhs);
	    String interfaceName = "FIXME'InterfaceName";
	    assert call != null && call.fcn != null: "Something wrong with " + ctx.rhs.getText() + " at " + StaticAnalysis.sourceLocation(ctx.rhs);
            letBindings.add(String.format("%s := %s (instancePrefix--\"%s\")", varName, call.fcn.getText(), varName));
            statement.append(String.format("(BKMod (%s'instance %s :: nil))", interfaceName, varName));

            String instanceName = String.format("%s", varName); //FIXME concat methodName
            entry.instanceName = instanceName;

            //instances.add(String.format("%s(\"%s\")", call.fcn.getText(), instanceName));
        } else {
            statement.append(String.format("        Call %s <- %s(", varName, calleeInstanceName));
            logger.fine("generic call " + ctx.rhs.getRuleIndex() + " " + ctx.rhs.getText());
            BSVParser.CallexprContext call = getCall(ctx.rhs);
            String sep = "";
            for (BSVParser.ExpressionContext expr: call.expression()) {
                statement.append(sep);
                statement.append(visit(expr));
                sep = ", ";
            }
            statement.append(")");
        }
	statements.add(statement.toString());
	return null;
    }

    @Override public String visitRuledef(BSVParser.RuledefContext ruledef) {
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
	letBindings = new ArrayList<>();
	statements = new ArrayList<>();

        boolean outerContext = actionContext;
        actionContext = true;
        scope = scopes.pushScope(ruledef);
        String ruleName = ruledef.name.getText();
        RuleDef ruleDef = new RuleDef(ruleName);
        BSVParser.RulecondContext rulecond = ruledef.rulecond();
        moduleDef.addRule(ruleDef);

        RegReadVisitor regReadVisitor = new RegReadVisitor(scope);
        if (rulecond != null) regReadVisitor.visit(rulecond);
        for (BSVParser.StmtContext stmt: ruledef.rulebody().stmt()) {
            regReadVisitor.visit(stmt);
        }

        for (BSVParser.StmtContext stmt: ruledef.rulebody().stmt()) {
            visit(stmt);
        }

	StringBuilder statement = new StringBuilder();
        statement.append("Rule instancePrefix--\"" + ruleName + "\" :=\n");
        for (Map.Entry<String,BSVType> entry: regReadVisitor.regs.entrySet()) {
            String regName = entry.getKey();
            statement.append("        Read " + regName + "_v : " + bsvTypeToKami(entry.getValue()) + " <- " + regName + ";\n");
        }

        if (rulecond != null) {
            statement.append("        Assert(" + visit(rulecond) + ");\n");
        }
	if (letBindings.size() > 0) {
	    statement.append("       (");
	    for (String ruleLetBinding: letBindings) {
		statement.append(String.format("       let %s in\n", ruleLetBinding));
	    }
	}
	if (statements.size() > 0) {
	    for (String ruleStatement: statements) {
		statement.append(String.format("       %s;", ruleStatement));
		statement.append(newline);
	    }
	}
        statement.append("        Retv (* rule " + ruledef.name.getText() + " *)");
        scope = scopes.popScope();
        actionContext = outerContext;

	letBindings = parentLetBindings;
	statements  = parentStatements;
	statements.add(statement.toString());

        return null;
    }

    @Override public String visitFunctiondef(BSVParser.FunctiondefContext ctx) {
        ArrayList<String> parentLetBindings = letBindings;
        ArrayList<String> parentStatements = statements;
        letBindings = new ArrayList<>();
        statements = new ArrayList<>();
        scope = scopes.pushScope(ctx);

        BSVParser.FunctionprotoContext functionproto = ctx.functionproto();
        printstream.print(String.format("Definition %s", functionproto.name.getText()));
        if (functionproto.methodprotoformals() != null) {
            for (BSVParser.MethodprotoformalContext formal: functionproto.methodprotoformals().methodprotoformal()) {
                BSVType bsvtype = StaticAnalysis.getBsvType(formal);
                String formalName = StaticAnalysis.getFormalName(formal);

                printstream.print(String.format(" (%s: %s)", formalName, bsvTypeToKami(bsvtype)));
            }
        }
        String returntype = (functionproto.bsvtype() != null) ? bsvTypeToKami(functionproto.bsvtype()) : "";
        printstream.println(String.format(": %s := ", returntype));

        RegReadVisitor regReadVisitor = new RegReadVisitor(scope);
        if (ctx.expression() != null) {
            printstream.print("    ");
            regReadVisitor.visit(ctx.expression());

        if (ctx.expression() != null)
            printstream.println(visit(ctx.expression()));
        } else {

            for (Map.Entry<String,BSVType> entry: regReadVisitor.regs.entrySet()) {
                String regName = entry.getKey();
                printstream.println("        Read " + regName + "_v : " + bsvTypeToKami(entry.getValue()) + " <- \"" + regName + "\";");
            }
            for (BSVParser.StmtContext stmt: ctx.stmt())
                regReadVisitor.visit(stmt);
            for (BSVParser.StmtContext stmt: ctx.stmt())
                visit(stmt);

	    assert(letBindings.size() == 0);
	    for (String statement: statements)
		printstream.println(String.format("        %s%s", statement, newline));

            if (returntype.equals("Action") || returntype.equals("Void"))
                printstream.println("        Retv");
        }
        printstream.println(".");
        printstream.println("");

        letBindings = parentLetBindings;
        statements  = parentStatements;
        scope = scopes.popScope();
        return null;
    }

    @Override public String visitMethoddef(BSVParser.MethoddefContext ctx) {
        boolean outerContext = actionContext;
        actionContext = true;
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
        scope = scopes.pushScope(ctx);

	letBindings = new ArrayList<>();
	statements = new ArrayList<>();
	StringBuilder statement = new StringBuilder();

        String methodName = ctx.name.getText();
	String methodSuffix = "";
	String noParams = " ()";
        if (ctx.methodformals() != null) {
	    int numArgs = ctx.methodformals().methodformal().size();
	    if (numArgs > 1)
		methodSuffix = String.format("%d", numArgs);
	    noParams = "";
	}
	
        statement.append(String.format("Method%s instancePrefix--\"%s\"%s", methodSuffix, methodName, noParams));
        if (ctx.methodformals() != null) {
            for (BSVParser.MethodformalContext formal: ctx.methodformals().methodformal()) {
                BSVParser.BsvtypeContext bsvtype = formal.bsvtype();
                String varName = formal.name.getText();
                statement.append(String.format(" (%s : %s)", varName, bsvTypeToKami(bsvtype)));
            }
        }
        String returntype = (ctx.bsvtype() != null) ? bsvTypeToKami(ctx.bsvtype()) : "";
        statement.append(" : " + returntype + " :=");
	statement.append(newline);
        RegReadVisitor regReadVisitor = new RegReadVisitor(scope);
        for (BSVParser.StmtContext stmt: ctx.stmt())
            regReadVisitor.visit(stmt);
        if (ctx.expression() != null)
            regReadVisitor.visit(ctx.expression());

        for (Map.Entry<String,BSVType> entry: regReadVisitor.regs.entrySet()) {
            String regName = entry.getKey();
            statement.append("        Read " + regName + "_v : " + bsvTypeToKami(entry.getValue()) + " <- \"" + regName + "\";");
        }
        for (BSVParser.StmtContext stmt: ctx.stmt())
            visit(stmt);
	statement.append(String.join(";" + newline, statements));
        if (ctx.expression() != null) {
            statement.append(visit(ctx.expression()));
	}

        if (returntype.equals("Action") || returntype.equals("Void")) {
	    statement.append(";");
	    statement.append(newline);
            statement.append("        Retv");
	}
	statement.append(newline);

        actionContext = outerContext;

	letBindings = parentLetBindings;
	statements  = parentStatements;
	statements.add(statement.toString());
        scope = scopes.popScope();
        return null;
    }

    @Override public String visitRegwrite(BSVParser.RegwriteContext regwrite) {
	StringBuilder statement = new StringBuilder();
        statement.append("        Write ");
        statement.append(visit(regwrite.lhs));
        String regName = regwrite.lhs.getText();
        SymbolTableEntry entry = scope.lookup(regName);
        if (entry != null) {
            statement.append(" : ");
            statement.append(bsvTypeToKami(entry.type.params.get(0)));
        }
        statement.append(" <- ");
        statement.append(visit(regwrite.rhs));

	statements.add(statement.toString());
        return null;
    }

    @Override public String visitStmt(BSVParser.StmtContext ctx) {
	if (ctx.expression() != null) {
	    statements.add(visit(ctx.expression()));
	} else {
	    visitChildren(ctx);
	}
	return null;
    }

    @Override public String visitVarassign(BSVParser.VarassignContext ctx) {
	StringBuilder statement = new StringBuilder();
        statement.append("        Assign ");
        boolean multi = ctx.lvalue().size() > 1;
        int count = 0;
        if (multi) statement.append("{ ");
        for (BSVParser.LvalueContext lvalue: ctx.lvalue()) {
            if (multi && count > 0) statement.append(", ");
            statement.append(lvalue.getText());
            count++;
        }
        if (multi) statement.append(" }");
	statement.append(" " + ctx.op.getText() + " ");
        statement.append(visit(ctx.expression()));

	statements.add(statement.toString());
	return null;
    }

    @Override
    public String visitIfstmt(BSVParser.IfstmtContext ctx) {
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
	letBindings = new ArrayList<>();
	statements = new ArrayList<>();

        visit(ctx.stmt(0));
	assert(letBindings.size() == 0);

	StringBuilder statement = new StringBuilder();
        statement.append("        If ");
        statement.append(visit(ctx.expression()));
        statement.append(newline);
        statement.append("        then ");
	for (String substatement: statements)
	    statement.append(String.format("        %s;%s", substatement, newline));
        statement.append("        Retv");
        if (ctx.stmt(1) != null) {
            statement.append(newline);
            statement.append("        else ");
	    letBindings = new ArrayList<>();
	    statements = new ArrayList<>();
            visit(ctx.stmt(1));
	    assert(letBindings.size() == 0);
	    for (String substatement: statements)
		statement.append(String.format("        %s;%s", substatement, newline));
            statement.append("        Retv;");
        }

	letBindings = parentLetBindings;
	statements  = parentStatements;
	if (statements == null)
	    System.err.println("Not gathering statements at " + StaticAnalysis.sourceLocation(ctx));

	statements.add(statement.toString());
	return null;
    }
    String destructurePattern(BSVParser.PatternContext pattern, String match, String tagName) {
        if (pattern.taggedunionpattern() != null) {
            BSVParser.TaggedunionpatternContext taggedunionpattern = pattern.taggedunionpattern();
            if (taggedunionpattern.pattern() != null)
                return destructurePattern(taggedunionpattern.pattern(),
					  match,
					  taggedunionpattern.tag.getText());
	    else
		return "";
        } else if (pattern.structpattern() != null) {
            BSVParser.StructpatternContext structpattern = pattern.structpattern();
            tagName = structpattern.tag.getText();
            SymbolTableEntry tagEntry = scope.lookup(tagName);
            assert tagEntry != null;
            BSVType tagType = tagEntry.type;
	    StringBuilder patternString = new StringBuilder();
            for (int i = 0; i < structpattern.pattern().size(); i++) {
                String fieldName = structpattern.lowerCaseIdentifier(i).getText();
                BSVParser.PatternContext fieldPattern = structpattern.pattern(i);
                patternString.append(destructurePattern(fieldPattern, String.format("(#%s!%sFields@.\"%s%s%s\")", match,
                                                                                    bsvTypeToKami(tagType),
                                                                                    ((tagName != null) ? tagName : ""),
                                                                                    ((tagName != null) ? "$" : ""),
                                                                                    fieldName),
                                                        null));
            }
	    return patternString.toString();
        } else if (pattern.lowerCaseIdentifier() != null) {
            return String.format("              LET %s <- %s;%s",
                                 pattern.lowerCaseIdentifier().getText(),
                                 match,
                                 newline);
        }
	return "";
    }

    @Override public String visitCaseexpr(BSVParser.CaseexprContext ctx) {
        System.err.println("visitCaseexpr");
        return null;
    }
    @Override public String visitCasestmt(BSVParser.CasestmtContext ctx) {
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
	letBindings = new ArrayList<>();
	statements = new ArrayList<>();

        int branchnum = 0;
        logger.fine("visitCasestmt " + ctx.getText());
	StringBuilder statement = new StringBuilder();
        for (BSVParser.CasestmtpatitemContext patitem: ctx.casestmtpatitem()) {
            BSVParser.PatternContext pattern = patitem.pattern();
            BSVParser.StructpatternContext structpattern = pattern.structpattern();
            BSVParser.TaggedunionpatternContext taggedunionpattern = pattern.taggedunionpattern();
            String tagName;
	    if (structpattern != null)
		tagName = structpattern.tag.getText();
	    else if (taggedunionpattern != null)
		tagName = taggedunionpattern.tag.getText();
	    else
		// FIXME
		tagName = pattern.getText();
            SymbolTableEntry tagEntry = scope.lookup(tagName);
            assert tagEntry != null: "No pattern tag entry for " + tagName + " at " + StaticAnalysis.sourceLocation(pattern);
            BSVType tagType = tagEntry.type;
            assert tagEntry.value != null : String.format("Missing value for tag %s", tagName);
            IntValue tagValue = (IntValue)tagEntry.value;
            statement.append("    If (");
            statement.append(visit(ctx.expression()));
	    statement.append(String.format("!%sFields@.\"$tag\"", tagType.name));
            statement.append(" == ");
            statement.append(String.format("$%d", tagValue.value));
            statement.append(") then");
	    statement.append(newline);
            statement.append(destructurePattern(pattern, ctx.expression().getText(), null));
            assert patitem.patterncond().expression().size() == 0;

	    letBindings = new ArrayList<>();
	    statements = new ArrayList<>();
            visit(patitem.stmt());
	    assert(letBindings.size() == 0);
            for (String substatement: statements) {
                statement.append(substatement);
                statement.append(";");
                statement.append(newline);
            }

            statement.append("        Retv");
	    statement.append(newline);
            statement.append("    else");
	    statement.append(newline);
        }
        statement.append("        Retv");

	letBindings = parentLetBindings;
	statements  = parentStatements;
        statements.add(statement.toString());
	return null;
    }
    @Override
    public String visitPattern(BSVParser.PatternContext ctx) {
        //FIXME
        return ("$" + ctx.getText());
    }

    @Override public String visitForstmt(BSVParser.ForstmtContext ctx) {
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
        scope = scopes.pushScope(ctx);

	logger.fine(String.format("For stmt at %s", StaticAnalysis.sourceLocation(ctx)));

        BSVParser.FornewinitContext init = ctx.forinit().fornewinit();
        assert init != null : "Only supports new-style for loop init";
        String iterationVar = init.var.getText();
        SymbolTableEntry iterationVarEntry = scope.lookup(iterationVar);
        assert iterationVarEntry != null;
        BSVType iterationVarType = iterationVarEntry.type;
        assert iterationVarType != null;
        assert iterationVarType.name.equals("Integer"): "Iteration var must be an Integer";

        BSVParser.ExpressionContext testExpr = ctx.fortest().expression();
        BSVParser.OperatorexprContext operatorExpr = (testExpr instanceof BSVParser.OperatorexprContext) ? (BSVParser.OperatorexprContext)testExpr : null;
        BSVParser.BinopexprContext binop = operatorExpr.binopexpr();
        assert binop != null;
        assert binop.left != null;
        assert binop.left.getText().equals(iterationVar);
        assert binop.op.getText().equals("<"): "Unimplemented for loop condition " + testExpr.getText() + " at " + StaticAnalysis.sourceLocation(testExpr);
        String limitVar = binop.right.getText();

	StringBuilder statement = new StringBuilder();
        statement.append("    (BKBlock");
	statement.append(newline);
        statement.append(String.format("      (let limit : nat := %s", limitVar));
	statement.append(newline);
        statement.append(String.format("       in let instancePrefix : string := instancePrefix--\"%s\"", iterationVar));
	statement.append(newline);
        statement.append("      in ((fix loopM' (m: nat): InBKModule :=");
	statement.append(newline);
        statement.append("        match m with");
	statement.append(newline);
        statement.append("        | 0 => NilInBKModule");
	statement.append(newline);
        statement.append("        | S m' =>");
	statement.append(newline);
        statement.append(String.format("          let %s := limit - m", iterationVar));
	statement.append(newline);
        statement.append(String.format("          in let instancePrefix := instancePrefix--(toBinaryString %s)", iterationVar));
	statement.append(newline);
        statement.append("          in ConsInBKModule");
	statement.append(newline);

	letBindings = new ArrayList<>();
	statements = new ArrayList<>();
        visit(ctx.stmt());
	assert(letBindings.size() == 0);
	for (String substatement: statements) {
	    statement.append(substatement);
	    statement.append(newline);
	}

        statement.append("        (loopM' m')");
	statement.append(newline);
        statement.append("        end)");
	statement.append(newline);
        statement.append(String.format("        %s)))", limitVar));

	letBindings = parentLetBindings;
	statements  = parentStatements;
        scope = scopes.popScope();

	statements.add(statement.toString());
        return null;
    }

    @Override public String visitBinopexpr(BSVParser.BinopexprContext expr) {
	StringBuilder expression = new StringBuilder();
        if (expr.right != null) {
            expression.append("(");
            if (!inModule) {
                if (expr.op != null) {
                    String op = expr.op.getText();
                    if (op.equals("<"))
                        op = "bitlt";
                    expression.append(op);
                }
                expression.append(" ");
            }
            if (expr.left != null)
                expression.append(visit(expr.left));
            if (inModule) {
                expression.append(" ");
                expression.append(expr.op.getText());
                expression.append(" ");
            } else {
                expression.append(" ");
            }
            expression.append(visit(expr.right));
            expression.append(")");
        } else {
            expression.append(visit(expr.unopexpr()));
        }
        return expression.toString();
    }
    @Override public String visitUnopexpr(BSVParser.UnopexprContext ctx) {
	StringBuilder expression = new StringBuilder();
        if (ctx.op != null) {
            expression.append(ctx.op.getText());
        }
	expression.append(visit(ctx.exprprimary()));
	return expression.toString();
    }

    @Override public String visitBitconcat(BSVParser.BitconcatContext ctx) {
	assert ctx.expression().size() == 2 : "Bit concat more than 2";
	BSVTypeVisitor typeVisitor = new BSVTypeVisitor(scopes);
	typeVisitor.pushScope(scope);
	BSVParser.ExpressionContext arg0 = ctx.expression(0);
	BSVParser.ExpressionContext arg1 = ctx.expression(1);
	BSVType arg0Type = typeVisitor.visit(arg0);
	BSVType arg1Type = typeVisitor.visit(arg1);
	return String.format("(BinBit (Concat %s %s) %s %s)",
			     bsvTypeSize(arg0Type),
			     bsvTypeSize(arg1Type),
			     visit(arg0),
			     visit(arg1)
			     );
    }

    @Override public String visitStructexpr(BSVParser.StructexprContext ctx) {
	StringBuilder expression = new StringBuilder();
        expression.append("STRUCT { ");
        int i = 0;
        for (BSVParser.MemberbindContext memberbind : ctx.memberbinds().memberbind()) {
            expression.append(String.format("\"%s\" ::= ",
                                            memberbind.field.getText()));
            expression.append(visit(memberbind.expression()));
            expression.append(((i == ctx.memberbinds().memberbind().size() - 1) ? " " : "; "));
            i++;
        }
        expression.append(" }");
        return expression.toString();
    }
    @Override public String visitTaggedunionexpr(BSVParser.TaggedunionexprContext ctx) {
	StringBuilder expression = new StringBuilder();
        expression.append("STRUCT { ");
        String tagName = ctx.tag.getText();
        SymbolTableEntry tagEntry = scope.lookup(tagName);
        assert tagEntry != null;
        BSVType tagtype = tagEntry.type;
        assert tagEntry.value != null : String.format("Missing value for tag %s", tagName);
        IntValue tagValue = (IntValue)tagEntry.value;
        SymbolTableEntry typedefEntry = scope.lookupType(tagtype.name);
        assert typedefEntry != null: String.format("tagged union tag %s expr %s type %s at %s", tagName, ctx.getText(), tagtype, StaticAnalysis.sourceLocation(ctx));
        ArrayList<String> visitedFields = new ArrayList<>();

        expression.append(String.format(" \"$tag\" ::= $%d", tagValue.value));

        visitedFields.add("$tag");
        for (Map.Entry<String,SymbolTableEntry> iterator: typedefEntry.mappings.bindings.entrySet()) {
            String fieldName = iterator.getKey();

            if (ctx.exprprimary() != null) {
                if (!visitedFields.contains(tagName)) {
                    expression.append(String.format("; \"%s\" ::= ", tagName));
                    expression.append(visit(ctx.exprprimary()));
                    visitedFields.add(tagName);
                }
            } else if (ctx.memberbinds() != null) {
                int i = 0;
                for (BSVParser.MemberbindContext memberbind : ctx.memberbinds().memberbind()) {
                    String memberfieldname = String.format("%s$%s", tagName, memberbind.field.getText());
                    if (fieldName.equals(memberfieldname) && !visitedFields.contains(memberfieldname)) {
                        visitedFields.add(memberfieldname);
                        expression.append(String.format("; \"%s\" ::= ", memberfieldname));
                        expression.append(visit(memberbind.expression()));
                        i++;
                    }
                }
            }
            if (!visitedFields.contains(fieldName)) {
                expression.append(String.format("; \"%s\" ::= $0", fieldName));
            }
        }
        expression.append(" }");
        return expression.toString();
    }
    @Override public String visitIntliteral(BSVParser.IntliteralContext ctx) {
        return ("$" + ctx.IntLiteral().getText());
    }
    @Override public String visitRealliteral(BSVParser.RealliteralContext ctx) {
        return ("$" + ctx.RealLiteral().getText());
    }
    @Override public String visitReturnexpr(BSVParser.ReturnexprContext ctx) {
	StringBuilder expression = new StringBuilder();
        expression.append("        Ret ");
        expression.append(visit(ctx.expression()));
        return expression.toString();
    }
    @Override public String visitVarexpr(BSVParser.VarexprContext ctx) {
	StringBuilder expression = new StringBuilder();
        if (ctx.anyidentifier() != null) {
            String varName = ctx.anyidentifier().getText();
            logger.fine("var " + varName + " scope " + scope);
            if (scope.containsKey(varName)) {
                SymbolTableEntry entry = scope.lookup(varName);
		String prefix = "#";
		if (entry.type.name.equals("Integer"))
		    prefix = "$";
                logger.fine("found binding " + varName + " " + entry.type);
                if (entry.type.name.startsWith("Reg"))
                    expression.append(prefix + varName + "_v");
                else
                    expression.append(prefix + varName);
            } else {
                expression.append("#" + varName);
            }
        }
        return expression.toString();
    }
    @Override public String visitArraysub(BSVParser.ArraysubContext ctx) {
	StringBuilder expression = new StringBuilder();
        expression.append(visit(ctx.array));
        expression.append("[");
        expression.append(visit(ctx.expression(0)));
        if (ctx.expression(1) != null) {
            expression.append(" : ");
            expression.append(visit(ctx.expression(1)));
        }
        expression.append("]");
        return expression.toString();
    }
    @Override public String visitLvalue(BSVParser.LvalueContext ctx) {
	StringBuilder expression = new StringBuilder();
        if (ctx.lvalue() != null) {
            expression.append(visit(ctx.lvalue()));
        }
        if (ctx.index != null) {
            expression.append("[");
            expression.append(visit(ctx.index));
            expression.append("]");
        } else if (ctx.msb != null) {
            expression.append("[");
            expression.append(visit(ctx.msb));
            expression.append(", ");
            expression.append(visit(ctx.lsb));
            expression.append("]");
        } else if (ctx.lowerCaseIdentifier() != null) {
            if (ctx.lvalue() != null)
                expression.append(".");
            expression.append(ctx.lowerCaseIdentifier().getText());
        }
        return expression.toString();
    }

    @Override public String visitCallexpr(BSVParser.CallexprContext ctx) {
        InstanceNameVisitor inv = new InstanceNameVisitor(scope);
        String methodName = inv.visit(ctx.fcn);
        if (methodName == null)
            methodName = "FIXME$" + ctx.fcn.getText();
        assert methodName != null : "No methodName for " + ctx.fcn.getText();
        methodName = methodName.replace(".", "");
	StringBuilder statement = new StringBuilder();
        if (methodName != null) {
            // "Call" is up where the binding is, hopefully
            statement.append(String.format(" %s(", methodName));
            String sep = "";
            for (BSVParser.ExpressionContext expr: ctx.expression()) {
                statement.append(sep);
                statement.append(visit(expr));
                sep = ", ";
            }
            statement.append(")");
        } else {
            logger.fine(String.format("How to call action function {%s}", ctx.fcn.getText()));
        }
        return statement.toString();
    }

    @Override public String visitBeginendblock(BSVParser.BeginendblockContext ctx) {
	ArrayList<String> parentLetBindings = letBindings;
	ArrayList<String> parentStatements = statements;
	// rule context
        scope = scopes.pushScope(ctx);

	letBindings = new ArrayList<>();
	statements = new ArrayList<>();
        for (BSVParser.StmtContext stmt: ctx.stmt()) {
            stmtEmitted = true;
            visit(stmt);
        }
	StringBuilder statement = new StringBuilder();
	if (letBindings.size() != 0) {
	    statement.append("        (BKBlock (");
	    statement.append(newline);
	    for (String binding: letBindings) {
		statement.append("        let ");
		statement.append(binding);
		statement.append(" in");
		statement.append(newline);
	    }
	}

	statement.append("        BKSTMTS {");
	statement.append(newline);
	String separator = "";
	String terminator = (actionContext) ? ";" : "";
	for (String substatement: statements) {
	    statement.append(String.format("        %s%s%s%s", separator, substatement, terminator, newline));
	    if (!actionContext)
		separator = "with ";
	}

	if (letBindings.size() != 0) {
	    statement.append("        }");
	    statement.append("))");
	}

        scope = scopes.popScope();
	letBindings = parentLetBindings;
	statements  = parentStatements;
	statements.add(statement.toString());
        return null;
    }

    public String bsvTypeToKami(BSVType t) {
        return bsvTypeToKami(t, 0);
    }
    public String bsvTypeToKami(String t) {
	String kamitype = t;
        if (kamitype.equals("Action"))
            kamitype = "Void";
        if (kamitype.equals("Integer"))
            kamitype = "nat";
            if (kamitype.equals("Bit") && !inModule)
                kamitype = "word";
            else if (kamitype.equals("Bool") && !inModule)
                kamitype = "bool";
            else if (kamitype.equals("Integer"))
                kamitype = "nat";
            else if (kamitype.equals("Action"))
                kamitype = "Void";
	return kamitype;
    }
    public String bsvTypeToKami(BSVType t, int level) {
        if (t == null)
            return "<nulltype>";
        t = t.prune();
        String kamitype = bsvTypeToKami(t.name);
        for (BSVType p: t.params)
            kamitype += " " + bsvTypeToKami(p);
        if (level > 0)
            kamitype = String.format("&%s)", kamitype);
        return kamitype;
    }
    public String bsvTypeToKami(BSVParser.BsvtypeContext t) {
        return bsvTypeToKami(t, 0);
    }
    public String bsvTypeToKami(BSVParser.BsvtypeContext t, int level) {
        if (t == null)
            return "<nulltype>";
        if (t.typeide() != null) {
            String kamitype = bsvTypeToKami(t.typeide().getText());
            for (BSVParser.BsvtypeContext p: t.bsvtype())
                kamitype += " " + bsvTypeToKami(p, 1);
            if (t.bsvtype().size() > 0)
                kamitype = String.format("(%s)", kamitype);
            return kamitype;
        } else if (t.typenat() != null) {
            return t.getText();
        } else {
            return "<functionproto>";
        }
    }

    String bsvTypeSize(BSVType bsvtype) {
	if (bsvtype.name.equals("Reg")) {
	    assert bsvtype.params != null;
	    assert bsvtype.params.size() == 1;
	    return bsvTypeSize(bsvtype.params.get(0));
	} else if (bsvtype.name.equals("TAdd")) {
	    return String.format("%s + %s",
				 bsvTypeSize(bsvtype.params.get(0)),
				 bsvTypeSize(bsvtype.params.get(1)));
	} else if (bsvtype.name.equals("TSub")) {
	    return String.format("%s - %s",
				 bsvTypeSize(bsvtype.params.get(0)),
				 bsvTypeSize(bsvtype.params.get(1)));
	}
	assert (bsvtype.name.equals("Bit")) : "Unable to calculate size of " + bsvtype;
	assert bsvtype.params != null;
	assert bsvtype.params.size() == 1;
	return bsvtype.params.get(0).toString();
    }

    protected String aggregateResult(String aggregate, String nextResult)
    {
	if (!(aggregate instanceof String) && !(nextResult instanceof String))
	    return null;
	if (aggregate == null)
	    return nextResult;
	if (nextResult == null)
	    return aggregate;
	return aggregate + nextResult;
    }
}
