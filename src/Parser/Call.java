package Parser;

import Lexer.Token;
import java.util.ArrayList;

public class Call extends Expression{
    Expression function;
    ArrayList<Expression> arguments;
    
    public Call(Token token, Expression function, ArrayList<Expression> arguments) {
        super(token);
        this.function = function;
        this.arguments = arguments;
    }
    
    public Call(Token token, Expression function){
        this(token,function, null);
    }

    public Expression getFunction() {
        return function;
    }

    public void setFunction(Expression function) {
        this.function = function;
    }

    public ArrayList<Expression> getArguments() {
        return arguments;
    }

    public void setArguments(ArrayList<Expression> arguments) {
        this.arguments = arguments;
    }
    
    @Override
    public String toString(){
        assert arguments != null:
                "La lista de argumentos es null";
        String argList = "";
        for (Expression expression : arguments){
            argList += ", " + expression.toString();
        };
        String args = function.toString() + "(" + argList + ")";
        return args;
    }
}
