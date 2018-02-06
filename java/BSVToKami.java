import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.io.*;
import java.util.*;

class CallVisitor extends BSVBaseVisitor<BSVParser.CallexprContext> {
    @Override public BSVParser.CallexprContext visitCallexpr(BSVParser.CallexprContext ctx) { return ctx; }
}
class InstanceNameVisitor extends BSVBaseVisitor<String> {
    private SymbolTable scope;
    public TreeMap<String,TreeSet<String>> methodsUsed;
    InstanceNameVisitor(SymbolTable scope) {
        this.scope = scope;
        methodsUsed = new TreeMap<>();
    }
    @Override public String visitOperatorExpr(BSVParser.OperatorExprContext ctx) {
        String instanceName = visit(ctx.binopexpr());
        System.err.println("visitOperatorExpr " + ctx.getRuleIndex() + " " + ctx.getText() + " " + instanceName);
        return instanceName;
    }
    @Override public String visitBinopexpr(BSVParser.BinopexprContext ctx) {
        String instanceName = null;
        if (ctx.unopexpr() != null) {
            instanceName = visit(ctx.unopexpr());
        }
        System.err.println("visitBinopexpr " + ctx.getRuleIndex() + " " + ctx.getText() + " " + instanceName);
        return instanceName;
    }
    @Override public String visitUnopexpr(BSVParser.UnopexprContext ctx) {
        String instanceName = null;
        if (ctx.op == null)
            instanceName = visit(ctx.exprprimary());
        System.err.println("visitUnopexpr " + ctx.getRuleIndex() + " " + ctx.getText() + " " + instanceName);
        return instanceName;
    }
    @Override public String visitCallexpr(BSVParser.CallexprContext ctx) {
        return visit(ctx.fcn);
    }
    @Override public String visitFieldexpr(BSVParser.FieldexprContext ctx) {
        String instanceName = visit(ctx.exprprimary());
        if (instanceName != null) {
            String fieldName = ctx.exprfield.getText();
            String methodName = String.format("%s.%s", instanceName, fieldName);
            System.err.println("methodName " + methodName);
            if (!methodsUsed.containsKey(instanceName))
                methodsUsed.put(instanceName, new TreeSet<String>());
            methodsUsed.get(instanceName).add(fieldName);
            return methodName;
        }
        return null;
    }
    @Override public String visitVarexpr(BSVParser.VarexprContext ctx) {
        if (ctx.anyidentifier() != null) {
            String varName = ctx.anyidentifier().getText();
            SymbolTableEntry entry = scope.lookup(varName);
            if (entry != null) {
                if (entry.instanceName != null) {
                    System.err.println(String.format("Instancename %s -> %s", varName, entry.instanceName));
                    return entry.instanceName;
                }
            } else {
                System.err.println(String.format("No symbol table entry for %s", varName));
            }
        }
        return null;
    }
}

class RegReadVisitor extends BSVBaseVisitor<Void> {
    public TreeMap<String,BSVType> regs = new TreeMap<>();
    final SymbolTable scope;
    RegReadVisitor(SymbolTable scope) {
        this.scope = scope;
    }

    @Override public Void visitVarexpr(BSVParser.VarexprContext ctx) {
        if (ctx.anyidentifier() != null) {
            String varName = ctx.anyidentifier().getText();
            if (scope.containsKey(varName)) {
                SymbolTableEntry entry = scope.lookup(varName);
                if (entry.type.name.startsWith("Reg")) {
                    regs.put(varName, entry.type.params.get(0));
                }
            }
        }
        return null;
    }
}


public class BSVToKami extends BSVBaseVisitor<Void>
{
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
    BSVToKami(String pkgName, File ofile, StaticAnalysis scopes) {
        this.scopes = scopes;
        this.pkgName = pkgName;
	this.ofile = ofile;
        pkg = new Package(pkgName);
        actionContext = false;
	try {
	    printstream = new PrintStream(ofile);
	} catch (FileNotFoundException ex) {
	    System.err.println(ex);
	    printstream = null;
	}
    }

    @Override
    public Void visitPackagedef(BSVParser.PackagedefContext ctx) {
        System.err.println("Package " + pkgName);

        printstream.println("Require Import Bool String List.");
        printstream.println("Require Import Lib.CommonTactics Lib.ilist Lib.Word.");
        printstream.println("Require Import Lib.Struct Lib.FMap Lib.StringEq Lib.Indexer.");
        printstream.println("");
        printstream.println("Require Import Kami.Syntax Kami.Notations Kami.Semantics Kami.Wf.");
        printstream.println("Require Import Kami.Inline Kami.InlineFacts.");
        printstream.println("Require Import Kami.RefinementFacts Kami.Decomposition.");
        printstream.println("Require Import Kami.Tactics.");
        printstream.println("");
        printstream.println("Require Import FunctionalExtensionality.");
        printstream.println("");
        printstream.println("Set Implicit Arguments.");
        printstream.println("");

        if (ctx.packagedecl() != null) {
            if (!pkgName.equals(ctx.packagedecl().pkgname)) {
                System.err.println("Expected " + pkgName + " found " + ctx.packagedecl().pkgname);
            }
        }
        return visitChildren(ctx);
    }

    @Override public Void visitTypeclassinstance(BSVParser.TypeclassinstanceContext ctx) {
        scope = scopes.getScope(ctx);
        visitChildren(ctx);
        scope = scope.parent;
        return null;
    }

    @Override public Void visitModuledef(BSVParser.ModuledefContext ctx) {
        for (BSVParser.AttributeinstanceContext attrinstance: ctx.attributeinstance()) {
            for (BSVParser.AttrspecContext attr: attrinstance.attrspec()) {
                if (attr.identifier().getText().equals("nogen"))
                return null;
            }
        }
        instances = new ArrayList<>();
        scope = scopes.getScope(ctx);
        String moduleName = ctx.moduleproto().name.getText();
        String sectionName = moduleName.startsWith("mk") ? moduleName.substring(2) : moduleName;
        moduleDef = new ModuleDef(moduleName);
        pkg.addStatement(moduleDef);
        InstanceNameVisitor inv = new InstanceNameVisitor(scope);
        inv.visit(ctx);

        System.err.println("module " + moduleName);
        printstream.println("Section " + sectionName + ".");
        printstream.println("    Variable moduleName: string.");
        printstream.println("    Local Notation \"^ s\" := (moduleName -- s) (at level 0).");

        for (Map.Entry<String,TreeSet<String>> entry: inv.methodsUsed.entrySet()) {
            String instanceName = entry.getKey();
            TreeSet<String> methods = entry.getValue();
            for (String method: methods) {
                printstream.println(String.format("    Definition %1$s%2$s := MethodSig (^\"%1$s\"--\"%2$s\") () : Void.",
                                                 instanceName, method));
            }
        }

        printstream.println("    Definition " + moduleName + "Module := MODULE {" + "\n");
        String stmtPrefix = "    ";
        for (BSVParser.ModulestmtContext modulestmt: ctx.modulestmt()) {
	    stmtEmitted = true;
	    printstream.print(stmtPrefix);
            visit(modulestmt);
            if (stmtEmitted)
		stmtPrefix = "    with ";
        }
        printstream.println("    }. (*" + ctx.moduleproto().name.getText() + " *)" + "\n");

        if (instances.size() > 0)
            printstream.println(String.format("    Definition %sToplevel := (%s)%%kami.",
                                             moduleName,
                                             String.join("\n            ++ ", instances)));

        printstream.println("End " + sectionName + ".");
        scope = scope.parent;
        moduleDef = null;
        System.err.println("endmodule : " + moduleName);
        return null;
    }

    BSVParser.CallexprContext getCall(ParserRuleContext ctx) {
        return new CallVisitor().visit(ctx);
    }

    @Override public Void visitVarBinding(BSVParser.VarBindingContext ctx) {
        BSVParser.BsvtypeContext t = ctx.t;
        for (BSVParser.VarinitContext varinit: ctx.varinit()) {
            String varName = varinit.var.getText();
            SymbolTableEntry entry = scope.lookup(varName);
            printstream.print("        Let " + varName + ": " + bsvTypeToKami(t));
            BSVParser.ExpressionContext rhs = varinit.rhs;
            if (rhs != null) {
                printstream.print(" = ");
                visit(rhs);
            }
            printstream.println(". (* varbinding *)");
        }
        return null;
    }
    @Override public Void visitLetBinding(BSVParser.LetBindingContext ctx) {
        BSVParser.ExpressionContext rhs = ctx.rhs;
        for (BSVParser.LowerCaseIdentifierContext ident: ctx.lowerCaseIdentifier()) {
            String varName = ident.getText();
            SymbolTableEntry entry = scope.lookup(varName);
            printstream.print("        Let " + varName + ": " + bsvTypeToKami(entry.type) + " ");
        }
        if (ctx.op != null) {
            printstream.print("    " + ctx.op.getText());
            visit(ctx.rhs);
        }
        printstream.println(".");
        return null ;
    }
    @Override public Void visitActionBinding(BSVParser.ActionBindingContext ctx) {
        String typeName = ctx.t.getText();
        String varName = ctx.var.getText();
        BSVParser.ExpressionContext rhs = ctx.rhs;
        SymbolTableEntry entry = scope.lookup(varName);
        BSVType bsvtype = entry.type;
        InstanceNameVisitor inv = new InstanceNameVisitor(scope);
        String calleeInstanceName = inv.visit(ctx.rhs);
        if (calleeInstanceName != null && actionContext)
            calleeInstanceName = calleeInstanceName.replace(".", "");

        if (typeName.startsWith("Reg")) {
            BSVType paramtype = bsvtype.params.get(0);
            printstream.print("Register ^\"" + varName + "\" : " + bsvTypeToKami(paramtype)
                             + " <- ");

            BSVParser.CallexprContext call = getCall(ctx.rhs);
            if (call != null && call.fcn != null && call.fcn.getText().equals("mkReg")) {
                printstream.print("$" + call.expression().get(0).getText());
            } else {
                visit(ctx.rhs);
            }

            printstream.println("");
        } else if (calleeInstanceName != null) {
            printstream.println(String.format("        Call %s <- %s(); (* method call 1 *)", varName, calleeInstanceName));
        } else if (!actionContext) {
            printstream.println(String.format("        (* instantiate %s *)", varName));
	    stmtEmitted = false;

            String instanceName = String.format("%s", varName); //FIXME concat methodName
            entry.instanceName = instanceName;

            BSVParser.CallexprContext call = getCall(ctx.rhs);
            instances.add(String.format("%s(\"%s\")",
					call.fcn.getText(),
					instanceName));
        } else {
            printstream.print(String.format("        Call %s <- %s(", varName, calleeInstanceName));
            System.err.println("generic call " + ctx.rhs.getRuleIndex() + " " + ctx.rhs.getText());
            BSVParser.CallexprContext call = getCall(ctx.rhs);
            String sep = "";
            for (BSVParser.ExpressionContext expr: call.expression()) {
                printstream.print(sep);
                visit(expr);
                sep = ", ";
            }
            printstream.println("); (* method call 2 *)");
        }
        return null;
    }

    @Override public Void visitRuledef(BSVParser.RuledefContext ruledef) {
        boolean outerContext = actionContext;
        actionContext = true;
        scope = scopes.getScope(ruledef);
        String ruleName = ruledef.name.getText();
        RuleDef ruleDef = new RuleDef(ruleName);
        BSVParser.RulecondContext rulecond = ruledef.rulecond();
        moduleDef.addRule(ruleDef);

        printstream.println("Rule ^\"" + ruleName + "\" :=");
        RegReadVisitor regReadVisitor = new RegReadVisitor(scope);
        if (rulecond != null) regReadVisitor.visit(rulecond);
        for (BSVParser.StmtContext stmt: ruledef.stmt()) {
            regReadVisitor.visit(stmt);
        }
        for (Map.Entry<String,BSVType> entry: regReadVisitor.regs.entrySet()) {
            String regName = entry.getKey();
            printstream.println("        Read " + regName + "_v : " + bsvTypeToKami(entry.getValue()) + " <- ^\"" + regName + "\";");
        }

        if (rulecond != null) {
            printstream.print("        Assert(");
            visit(rulecond);
            printstream.println(");");
        }
        for (BSVParser.StmtContext stmt: ruledef.stmt()) {
            visit(stmt);
        }
        printstream.println("        Retv (* rule " + ruledef.name.getText() + " *)" + "\n");
        scope = scope.parent;
        actionContext = outerContext;
        return null;
    }

    @Override public Void visitMethoddef(BSVParser.MethoddefContext ctx) {
        boolean outerContext = actionContext;
        actionContext = true;

        SymbolTable methodScope = scopes.getScope(ctx);
        String methodName = ctx.name.getText();
        printstream.print("Method ^\"" + methodName + "\" (");
        if (ctx.methodformals() != null) {
            String sep = "";
            for (BSVParser.MethodformalContext formal: ctx.methodformals().methodformal()) {
                BSVParser.BsvtypeContext bsvtype = formal.bsvtype();
                String varName = formal.name.getText();
                printstream.print(sep + varName + ": " + bsvTypeToKami(bsvtype));
                sep = ", ";
            }
        }
        String returntype = (ctx.bsvtype() != null) ? bsvTypeToKami(ctx.bsvtype()) : "";
        printstream.println(") : " + returntype + " := ");
        RegReadVisitor regReadVisitor = new RegReadVisitor(scope);
        for (BSVParser.StmtContext stmt: ctx.stmt())
            regReadVisitor.visit(stmt);
        if (ctx.expression() != null)
            regReadVisitor.visit(ctx.expression());

        for (Map.Entry<String,BSVType> entry: regReadVisitor.regs.entrySet()) {
            String regName = entry.getKey();
            printstream.println("        Read " + regName + "_v : " + bsvTypeToKami(entry.getValue()) + " <- \"" + regName + "\";");
        }
        for (BSVParser.StmtContext stmt: ctx.stmt())
            visit(stmt);
        if (ctx.expression() != null)
            visit(ctx.expression());

        if (returntype.equals("Action") || returntype.equals("Void"))
            printstream.println("        Retv");
        printstream.println("");
        actionContext = outerContext;
        return null;
    }

    @Override public Void visitRegwrite(BSVParser.RegwriteContext regwrite) {
        printstream.print("        Write ^\"");
        visit(regwrite.lhs);
        printstream.print("\"");
        String regName = regwrite.lhs.getText();
        SymbolTableEntry entry = scope.lookup(regName);
        if (entry != null) {
            printstream.print(" : ");
            printstream.print(bsvTypeToKami(entry.type.params.get(0)));
        }
        printstream.print(" <- ");
        visit(regwrite.rhs);
        printstream.println(";");
        return null;
    }
    @Override public Void visitVarassign(BSVParser.VarassignContext ctx) {
        printstream.print("        Assign ");
        boolean multi = ctx.lvalue().size() > 1;
        int count = 0;
        if (multi) printstream.print("{ ");
        for (BSVParser.LvalueContext lvalue: ctx.lvalue()) {
            if (multi && count > 0) printstream.print(", ");
            printstream.print(lvalue.getText());
            count++;
        }
        if (multi) printstream.print(" }");
        printstream.print(" " + ctx.op.getText() + " ");
        visit(ctx.expression());
        printstream.println(";");
        return null;
    }

    @Override
    public Void visitIfstmt(BSVParser.IfstmtContext ctx) {
        printstream.print("        (If ");
        visit(ctx.condpredicate());
        printstream.println("");
        printstream.print("        then ");
        visit(ctx.stmt(0));
        printstream.print("        Retv;");
        if (ctx.stmt(1) != null) {
            printstream.println("");
            printstream.print("        else ");
            visit(ctx.stmt(1));
            printstream.print("        Retv;");
        }
        printstream.println(")");
        return null;
    }
    @Override public Void visitCasestmt(BSVParser.CasestmtContext ctx) {
        visitChildren(ctx);
        return null;
    }
    @Override
    public Void visitPattern(BSVParser.PatternContext ctx) {
        //FIXME
        printstream.print("$" + ctx.getText());
        return null;
    }

    @Override
    public Void visitCondpredicate(BSVParser.CondpredicateContext ctx) {
        visit(ctx.expression());
        // if (ctx.pattern() != null) {
        //     printstream.print(" matches ");
        //     visit(ctx.pattern());
        // }
        if (ctx.condpredicate() != null) {
            printstream.print(" && ");
            visit(ctx.condpredicate());
        }
        return null;
    }
    @Override public Void visitBinopexpr(BSVParser.BinopexprContext expr) {
        if (expr.right != null) {
            printstream.print("(");
            if (expr.left != null)
                visit(expr.left);
            if (expr.op != null) {
                printstream.print(" " + expr.op.getText() + " ");
            }

            visit(expr.right);
            printstream.print(")");
        } else {
            visitChildren(expr);
        }
        return null;
    }
    @Override public Void visitUnopexpr(BSVParser.UnopexprContext ctx) {
        if (ctx.op != null) {
            printstream.print(ctx.op.getText());
        }
        return visitChildren(ctx);
    }
    @Override public Void visitIntliteral(BSVParser.IntliteralContext ctx) {
        printstream.print("$" + ctx.IntLiteral().getText());
        return null;
    }
    @Override public Void visitRealliteral(BSVParser.RealliteralContext ctx) {
        printstream.print("$" + ctx.RealLiteral().getText());
        return null;
    }
    @Override public Void visitReturnexpr(BSVParser.ReturnexprContext ctx) {
        printstream.print("        Ret ");
        visit(ctx.expression());
        printstream.println("");
        return null;
    }
    @Override public Void visitVarexpr(BSVParser.VarexprContext ctx) {
        if (ctx.anyidentifier() != null) {
            String varName = ctx.anyidentifier().getText();
            System.err.println("var " + varName + " scope " + scope);
            if (scope.containsKey(varName)) {
                SymbolTableEntry entry = scope.lookup(varName);
                System.err.println("found binding " + varName + " " + entry.type);
                if (entry.type.name.startsWith("Reg"))
                    printstream.print("#" + varName + "_v");
                else
                    printstream.print("#" + varName);
            } else {
                printstream.print("#" + varName);
            }
        }
        return null;
    }
    @Override public Void visitArraysub(BSVParser.ArraysubContext ctx) {
        visit(ctx.array);
        printstream.print("[");
        visit(ctx.expression(0));
        if (ctx.expression(1) != null) {
            printstream.print(" : ");
            visit(ctx.expression(1));
        }
        printstream.print("]");
        return null;
    }
    @Override public Void visitLvalue(BSVParser.LvalueContext ctx) {
        if (ctx.lvalue() != null) {
            visit(ctx.lvalue());
        }
        if (ctx.index != null) {
            printstream.print("[");
            visit(ctx.index);
            printstream.print("]");
        } else if (ctx.msb != null) {
            printstream.print("[");
            visit(ctx.msb);
            printstream.print(", ");
            visit(ctx.lsb);
            printstream.print("]");
        } else if (ctx.lowerCaseIdentifier() != null) {
            if (ctx.lvalue() != null)
                printstream.print(".");
            printstream.print(ctx.lowerCaseIdentifier().getText());
        }
        return null;
    }

    @Override public Void visitCallexpr(BSVParser.CallexprContext ctx) {
        InstanceNameVisitor inv = new InstanceNameVisitor(scope);
        String methodName = inv.visit(ctx.fcn);
        methodName = methodName.replace(".", "");
        if (methodName != null) {
            printstream.print(String.format("        Call %s(", methodName));
            String sep = "";
            for (BSVParser.ExpressionContext expr: ctx.expression()) {
                printstream.print(sep);
                visit(expr);
                sep = ", ";
            }
            printstream.println("); (* method call expr *)");
        } else {
            System.err.println(String.format("How to call action function {%s}", ctx.fcn.getText()));
        }
        return null;
    }

    public String bsvTypeToKami(BSVType t) {
        return bsvTypeToKami(t, 0);
    }
    public String bsvTypeToKami(BSVType t, int level) {
        if (t == null)
            return "<nulltype>";
        t = t.prune();
        String kamitype = t.name;
        if (kamitype.equals("Action"))
            kamitype = "Void";
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
            String kamitype = t.typeide().getText();
            if (kamitype.equals("Action"))
                kamitype = "Void";
            for (BSVParser.BsvtypeContext p: t.bsvtype())
                kamitype += " " + bsvTypeToKami(p, 1);
            if (level > 0)
                kamitype = String.format("(%s)", kamitype);
            return kamitype;
        } else if (t.typenat() != null) {
            return t.getText();
        } else {
            return "<functionproto>";
        }
    }
}
