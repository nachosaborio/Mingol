package Evaluador;

import Parser.ExpressionStatement;
import Parser.Integral;
import Parser.Interfaces.ASTNode;
import Parser.Program;
import Parser.Statement;
import java.util.ArrayList;

public class Evaluator {
    
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
}
