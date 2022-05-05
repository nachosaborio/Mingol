package Evaluador;

import Parser.Block;
import Parser.Booleano;
import Parser.Decimal;
import Parser.Etiqueta;
import Parser.Expression;
import Parser.ExpressionStatement;
import Parser.Goto;
import Parser.Identifier;
import Parser.If;
import Parser.Infix;
import Parser.Integral;
import Parser.Interfaces.ASTNode;
import Parser.LetStatement;
import Parser.Prefix;
import Parser.Print;
import Parser.Program;
import Parser.Statement;
import Parser.StringLiteral;
import java.util.ArrayList;

public class Evaluator {

    public static boolean hasErrors = false;
    public static final Logico TRUE = new Logico(true);
    public static final Logico FALSE = new Logico(false);
    public static final Nulo NULL = new Nulo();
    private static String TypeMismatchError = "\tError 301: Discrepancia de tipos:";
    private static String UnknownPrefixOperation = "\tError 302: Operador inválido:";
    private static String UnknownIdentifier = "\tError 303: Identificador no encontrado:";
    private static String RepeatedIdentificator = "\tError 304: Identificador ya declarado:";
    private static String AlreadyBegin = "\tError 305: No puede haber más de una sentencia BEGIN por archivo.";
    private static String AlreadyEnded = "\tError 305: No puede haber más de una sentencia END por archivo.";
    private static String NotBegined = "\tError 307: No se encontró una sentencia BEGIN.";
    private static String NotEnded = "\tError 308: No se encontró una sentencia END.";

    public static Objeto Evaluate(ASTNode node, Environment env) {
        String className = node.getClass().getSimpleName();

        switch (className) {
            case "Program":
                Program program = (Program) node;
                Objeto evaluado = EvaluateProgram(program, env);
                if(env.get("BEGIN") == null){
                    return NewError(NotBegined, new String[]{});
                }
                if(env.get("END") == null){
                    return NewError(NotEnded, new String[]{});
                }
                return evaluado;
            case "Statement":
                Statement statement = (Statement) node;
                return EvaluateStatement(statement, env);
            case "ExpressionStatement":
                ExpressionStatement expression = (ExpressionStatement) node;

                assert expression.getExpression() != null :
                        "La expresion es nula";
                return Evaluate(expression.getExpression(), env);
            case "Integral":
                Integral integral = (Integral) node;
                assert integral != null :
                        "El entero es nulo";
                return new Entero(integral.getValue());
            case "Decimal":
                Decimal real = (Decimal) node;
                assert real != null :
                        "El real es nulo";
                return new Real(real.getValue());
            case "Booleano":
                Booleano logico = (Booleano) node;
                assert logico != null :
                        "El booleano es nulo";
                return ToBooleanObject(logico.getValue());
            case "Prefix":
                Prefix prefix = (Prefix) node;
                assert prefix.getRight() != null :
                        "La derecha es nula";
                Objeto right = Evaluate(prefix.getRight(), env);
                assert right != null :
                        "Right es nulo";
                return EvaluatePrefixExpression(prefix.getOperator(), right);
            case "Infix":
                Infix infix = (Infix) node;
                assert infix.getLeft() != null :
                        "La izquierda es nula";
                assert infix.getRight() != null :
                        "La derecha es nula";
                Objeto left = Evaluate(infix.getLeft(), env);
                Objeto iright = Evaluate(infix.getRight(), env);
                
                assert left != null :
                        "La izquierda es nula";
                assert iright != null :
                        "La derecha es nula";
                return EvaluateInfixExpression(infix.getOperator(), left, iright);
            case "Block":
                Block block = (Block) node;
                return EvaluateStatements(block.getStatements(), env);
            case "If":
                If si = (If) node;
                return EvaluateIfExpression(si, env);
            case "LetStatement":
                LetStatement let = (LetStatement) node;
                assert let != null :
                        "El identificador es nulo";
                Objeto value = Evaluate(let.getValue(), env);
                assert let.getName() != null :
                        "El nombre del identificador es nulo";
                if (env.get(let.getName().getValue()) == null) {
                    env.put(let.getName().getValue(), value);
                } else {
                    return NewError(RepeatedIdentificator,
                            new String[]{let.getName().getValue()});
                }
                break;
            case "Identifier":
                Identifier identifier = (Identifier) node;
                return EvaluateIdentifier(identifier, env);
            case "Call":
                break;
            case "StringLiteral":
                StringLiteral str = (StringLiteral) node;
                return new Cadena(str.getValue());
            case "Etiqueta":
                Etiqueta etiqueta = (Etiqueta) node;
                if (env.get(etiqueta.getValue()) == null) {
                    env.put(etiqueta.getValue(), "");
                } else {
                    return NewError(RepeatedIdentificator,
                            new String[]{etiqueta.getValue()});
                }
                break;
            case "Goto":
                Goto gouto = (Goto) node;
                if(env.get(gouto.getIdentificador().TokenLiteral()) == null) {
                    return NewError(UnknownIdentifier,
                            new String[]{gouto.getIdentificador().toString()});
                }
                break;
            case "Print":
                Print print = (Print) node;
                if(print.getIdentificadores().size() >0){
                    for(Expression identificador : print.getIdentificadores()){
                        if(env.get(identificador.TokenLiteral()) == null){
                            return NewError(UnknownIdentifier,
                            new String[]{identificador.TokenLiteral()});
                        }
                    }
                }
        }
        return null;
    }

    private static Objeto EvaluateBangOperatorExpression(Objeto right) {
        if (right == TRUE) {
            return FALSE;
        } else if (right == FALSE) {
            return TRUE;
        } else if (right == NULL) {
            return TRUE;
        } else {
            return FALSE;
        }
    }

    private static Objeto EvaluateIdentifier(Identifier identifier, Environment env) {
        Objeto result = (Objeto) env.get(identifier.getValue());
        if (result == null) {
            return NewError(UnknownIdentifier, new String[]{identifier.getValue()});
        }
        return result;
    }

    private static Objeto EvaluateIfExpression(If si, Environment env) {
        assert si != null :
                "El if es nulo";
        Objeto condition = Evaluate(si.getCondition(), env);
        assert condition != null :
                "La condicion es nula";

        if (IsTruthy(condition)) {
            assert si.getConsequence() != null :
                    "La consecuencia es null";
            return Evaluate(si.getConsequence(), env);
        } else if (si.getAlternative() != null) {
            return Evaluate(si.getAlternative(), env);
        } else {
            return NULL;
        }
    }

    private static Objeto EvaluateInfixExpression(String operator, Objeto left, Objeto right) {
        if (left.Type() == ObjectType.INTEGERS && right.Type() == ObjectType.INTEGERS) {
            return EvaluateIntegerInfixExpression(operator, left, right);
        } else if (operator.equals("=") || operator.equals("EQ")) {
            return ToBooleanObject(left == right);
        } else if (operator.equals("/=") || operator.equals("NE")) {
            return ToBooleanObject(left != right);
        } else if (left.Type() != right.Type()) {
            return NewError(TypeMismatchError, new String[]{left.Type().toString(), operator, right.Type().toString()});
        } else {
            return NewError(UnknownPrefixOperation, new String[]{left.Type().toString(), operator, right.Type().toString()});
        }
    }

    private static Objeto EvaluateIntegerInfixExpression(String operator, Objeto left, Objeto right) {
        int leftValue = ((Entero) left).getValue();
        int rigthValue = ((Entero) right).getValue();

        switch (operator) {
            case "+":
                return new Entero(leftValue + rigthValue);
            case "-":
                return new Entero(leftValue - rigthValue);
            case "*":
                return new Entero(leftValue * rigthValue);
            case "/":
                return new Entero(leftValue / rigthValue);
            case "LT":
            case "<":
                return ToBooleanObject(leftValue < rigthValue);
            case "GT":
            case ">":
                return ToBooleanObject(leftValue > rigthValue);
            case "EQ":
            case "=":
                return ToBooleanObject(leftValue == rigthValue);
            case "NE":
            case "/=":
                return ToBooleanObject(leftValue != rigthValue);
            case "GE":
            case ">=":
                return ToBooleanObject(leftValue >= rigthValue);
            case "LE":
            case "<=":
                return ToBooleanObject(leftValue <= rigthValue);
            default:
                return NewError(UnknownPrefixOperation, new String[]{left.Type().toString(), operator, right.Type().toString()});
        }
    }

    private static Objeto EvaluateMinusOperatorExpression(Objeto right) {
        if (!(right instanceof Entero)) {
            return NewError(UnknownPrefixOperation, new String[]{"-", right.Type().toString()});
        } else {
            Entero entero = (Entero) right;
            return new Entero(-entero.getValue());
        }
    }

    private static Objeto EvaluatePrefixExpression(String operator, Objeto right) {

        switch (operator) {
            case "!":
                return EvaluateBangOperatorExpression(right);
            case "-":
                return EvaluateMinusOperatorExpression(right);
            default:
                return NewError(UnknownPrefixOperation, new String[]{operator, right.Type().toString()});
        }
    }

    private static Objeto EvaluateProgram(Program program, Environment env) {
        Objeto result = null;
        for (Statement statement : program.statements) {
            result = Evaluate(statement, env);
            if (result instanceof Errado) {
                return result;
            }
        }
        return result;
    }

    private static Objeto EvaluateStatement(Statement statement, Environment env) {
        switch (statement.TokenLiteral()) {
            case "BEGIN":
                if (env.get("BEGIN") == null) {
                    env.put("BEGIN", "BEGIN");
                } else {
                    return NewError(AlreadyBegin, new String[]{});
                }
                break;
            case "END":
                if (env.get("END") == null) {
                    env.put("END", "END");
                } else {
                    return NewError(AlreadyEnded, new String[]{});
                }
                break;
        }
        return null;
    }

    private static Objeto EvaluateStatements(ArrayList<Statement> statements, Environment env) {
        Objeto result = null;
        for (Statement statement : statements) {
            result = Evaluate(statement, env);
        }
        return result;
    }

    private static boolean IsTruthy(Objeto obj) {
        if (obj == NULL) {
            return false;
        } else if (obj == TRUE) {
            return true;
        } else if (obj == FALSE) {
            return false;
        }
        return true;
    }

    private static Errado NewError(String message, String[] args) {
        hasErrors = true;
        for (Object obj : args) {
            message += " " + obj.toString();
        }
        return new Errado(message);
    }

    private static Logico ToBooleanObject(boolean value) {
        return value ? TRUE : FALSE;
    }
}