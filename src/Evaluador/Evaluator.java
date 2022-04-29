package Evaluador;

import Lexer.MingolToken;
import Parser.Block;
import Parser.Booleano;
import Parser.ExpressionStatement;
import Parser.If;
import Parser.Infix;
import Parser.Integral;
import Parser.Interfaces.ASTNode;
import Parser.Prefix;
import Parser.Program;
import Parser.Statement;
import java.util.ArrayList;

public class Evaluator {
    
    public static final Logico TRUE = new Logico(true);
    public static final Logico FALSE = new Logico(false);
    public static final Nulo NULL = new Nulo();
    
    public static Objeto Evaluate(ASTNode node){
        String className = node.getClass().getSimpleName();
        
        switch(className){
            case "Program":
                Program program = (Program) node;
                return EvaluateStatements(program.statements);
            case "ExpressionStatement":
                ExpressionStatement expression = (ExpressionStatement)node;
                
                assert expression.getExpression() != null:
                        "La expresion es nula";
                return Evaluate(expression.getExpression());
            case "Integral":
                Integral integral = (Integral) node;
                assert integral != null:
                        "El entero es nulo";
                return new Entero(integral.getValue());
            case "Booleano":
                Booleano logico = (Booleano) node;
                assert logico != null:
                        "El booleano es nulo";
                return ToBooleanObject(logico.getValue());
            case "Prefix":
                Prefix prefix = (Prefix) node;
                assert prefix.getRight() != null:
                        "La derecha es nula";
                Objeto right = Evaluate(prefix.getRight());
                assert right != null:
                        "Right es nulo";
                return EvaluatePrefixExpression(prefix.getOperator(), right);
            case "Infix":
                Infix infix = (Infix)node;
                assert infix.getLeft() != null:
                        "La izquierda es nula";
                assert infix.getRight() != null:
                        "La derecha es nula";
                Objeto left = Evaluate(infix.getLeft());
                Objeto iright = Evaluate(infix.getRight());
                assert left != null:
                        "La izquierda es nula";
                assert iright != null:
                        "La derecha es nula";
                return EvaluateInfixExpression(infix.getOperator(),left, iright);
            case "Block":
                Block block = (Block) node;
                return EvaluateStatements(block.getStatements());
            case "If":
                If si = (If) node;
                return EvaluateIfExpression(si);
            default:
                return null;
        }
    }
    
    private static Objeto EvaluateStatements(ArrayList<Statement> statements){
        Objeto result = null;
        for(Statement statement : statements){
            result = Evaluate(statement);
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
                return NULL;
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
            return NULL;
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
        else{
            return NULL;
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
                return NULL;
        }
    }
    
    private static Objeto EvaluateIfExpression(If si){
        assert si != null:
                "El if es nulo";
        Objeto condition = Evaluate(si.getCondition());
        assert condition != null:
                "La condicion es nula";
        
        if(IsTruthy(condition)){
            assert si.getConsequence() != null:
                    "La consecuencia es null";
            return Evaluate(si.getConsequence());
        }
        else if(si.getAlternative() != null){
            return Evaluate(si.getAlternative());
        }
        else{
            return NULL;
        }
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
}