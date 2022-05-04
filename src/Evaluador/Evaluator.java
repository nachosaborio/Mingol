package Evaluador;

import Parser.Block;
import Parser.Booleano;
import Parser.Decimal;
import Parser.ExpressionStatement;
import Parser.Identifier;
import Parser.If;
import Parser.Infix;
import Parser.Integral;
import Parser.Interfaces.ASTNode;
import Parser.LetStatement;
import Parser.Prefix;
import Parser.Program;
import Parser.Statement;
import Parser.StringLiteral;
import java.util.ArrayList;

public class Evaluator {
    
    public static final Logico TRUE = new Logico(true);
    public static final Logico FALSE = new Logico(false);
    public static final Nulo NULL = new Nulo();
    private static String TypeMismatchError = "Discrepancia de tipos:";
    private static String UnknownPrefixOperation = "Operador inválido:";
    private static String UnknownIdentifier = "Identificador no encontrado:";
    
    public static Objeto Evaluate(ASTNode node, Environment env){
        String className = node.getClass().getSimpleName();
        
        switch(className){
            case "Program":
                Program program = (Program) node;
                return EvaluateProgram(program, env);
            case "ExpressionStatement":
                ExpressionStatement expression = (ExpressionStatement)node;
                
                assert expression.getExpression() != null:
                        "La expresion es nula";
                return Evaluate(expression.getExpression(), env);
            case "Integral":
                Integral integral = (Integral) node;
                assert integral != null:
                        "El entero es nulo";
                return new Entero(integral.getValue());
            case "Decimal":
                Decimal real = (Decimal) node;
                assert real != null:
                        "El real es nulo";
                return new Real(real.getValue());
            case "Booleano":
                Booleano logico = (Booleano) node;
                assert logico != null:
                        "El booleano es nulo";
                return ToBooleanObject(logico.getValue());
            case "Prefix":
                Prefix prefix = (Prefix) node;
                assert prefix.getRight() != null:
                        "La derecha es nula";
                Objeto right = Evaluate(prefix.getRight(),env);
                assert right != null:
                        "Right es nulo";
                return EvaluatePrefixExpression(prefix.getOperator(), right);
            case "Infix":
                Infix infix = (Infix)node;
                assert infix.getLeft() != null:
                        "La izquierda es nula";
                assert infix.getRight() != null:
                        "La derecha es nula";
                Objeto left = Evaluate(infix.getLeft(),env);
                Objeto iright = Evaluate(infix.getRight(),env);
                assert left != null:
                        "La izquierda es nula";
                assert iright != null:
                        "La derecha es nula";
                return EvaluateInfixExpression(infix.getOperator(),left, iright);
            case "Block":
                Block block = (Block) node;
                return EvaluateStatements(block.getStatements(),env);
            case "If":
                If si = (If) node;
                return EvaluateIfExpression(si,env);
            case "LetStatement":
                LetStatement let = (LetStatement)node;
                assert let != null:
                        "El identificador es nulo";
                Objeto value = Evaluate(let.getValue(),env);
                assert let.getName() != null:
                        "El nombre del identificador es nulo";
                env.put(let.getName().getValue(), value);
                break;
            case "Identifier":
                Identifier identifier = (Identifier) node;
                return EvaluateIdentifier(identifier, env);
            case "Call":
                break;
            case "StringLiteral":
                StringLiteral str = (StringLiteral) node;
                return new Cadena(str.getValue());
        }
        return null;
    }
    
    private static Objeto EvaluateProgram(Program program, Environment env){
        Objeto result = null;
        for (Statement statement : program.statements){
             result = Evaluate(statement, env);
            if(result instanceof Errado){
                return result;
            }
        }
        return result;
    }
    
    private static Objeto EvaluateStatements(ArrayList<Statement> statements, Environment env){
        Objeto result = null;
        for(Statement statement : statements){
            result = Evaluate(statement, env);
        }
        return result;
    }
    
    private static Logico ToBooleanObject(boolean value){
        return value ? TRUE : FALSE;
    }
    
    private static Objeto EvaluatePrefixExpression(String operator, Objeto right){
        
        switch(operator){
            case "!":
                return EvaluateBangOperatorExpression(right);
            case "-":
                return EvaluateMinusOperatorExpression(right);
            default:
                return NewError(UnknownPrefixOperation, new String[] {operator, right.Type().toString()});
        }
    }
    
    private static Objeto EvaluateBangOperatorExpression(Objeto right){
        if(right == TRUE){
            return FALSE;
        }
        else if(right == FALSE){
            return TRUE;
        }
        else if(right == NULL){
            return TRUE;
        }
        else{
            return FALSE;
        }
    }
    
    private static Objeto EvaluateMinusOperatorExpression(Objeto right){
        if(!(right instanceof Entero)){
            return NewError(UnknownPrefixOperation, new String[] {"-", right.Type().toString()});
        }
        else{
            Entero entero = (Entero) right;
            return new Entero(-entero.getValue());
        }
    }
    
    private static Objeto EvaluateInfixExpression(String operator, Objeto left, Objeto right){
        if(left.Type() == ObjectType.INTEGERS && right.Type() == ObjectType.INTEGERS){
            return EvaluateIntegerInfixExpression(operator, left, right);
        }
        else if(operator.equals("=") || operator.equals("EQ")){
            return ToBooleanObject(left == right);
        }
        else if(operator.equals("/=") || operator.equals("NE")){
            return ToBooleanObject(left != right);
        }
        else if(left.Type() != right.Type()){
            return NewError(TypeMismatchError, new String[]{left.Type().toString(), operator, right.Type().toString()});
        }
        else{
            return NewError(UnknownPrefixOperation, new String[]{left.Type().toString(), operator, right.Type().toString()});
        }
    }
    
    private static Objeto EvaluateIntegerInfixExpression(String operator, Objeto left, Objeto right){
        int leftValue = ((Entero)left).getValue();
        int rigthValue = ((Entero)right).getValue();
        
        switch(operator){
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
    
    private static Objeto EvaluateIfExpression(If si, Environment env){
        assert si != null:
                "El if es nulo";
        Objeto condition = Evaluate(si.getCondition(), env);
        assert condition != null:
                "La condicion es nula";
        
        if(IsTruthy(condition)){
            assert si.getConsequence() != null:
                    "La consecuencia es null";
            return Evaluate(si.getConsequence(), env);
        }
        else if(si.getAlternative() != null){
            return Evaluate(si.getAlternative(), env);
        }
        else{
            return NULL;
        }
    }
    
    private static Objeto EvaluateIdentifier(Identifier identifier, Environment env){
        Objeto result = (Objeto) env.get(identifier.getValue());
        if(result == null){
            return NewError(UnknownIdentifier, new String[]{identifier.getValue()});
        }
        return result;
    }
    
    private static boolean IsTruthy(Objeto obj){
        if(obj == NULL){
            return false;
        }
        else if(obj == TRUE){
            return true;
        }
        else if(obj == FALSE){
            return false;
        }
        return true;
    }
    
    private static Errado NewError(String message, String[] args){
        for(Object obj : args){
            message += " " + obj.toString();
        }
        return new Errado(message);
    }
}