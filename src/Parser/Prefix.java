package Parser;

import Lexer.Token;

public class Prefix extends Expression{
    String operator;
    Expression right;
    
    public Prefix(Token token, String operator, Expression right){
        super(token);
        this.operator = operator;
        this.right = right;
    }
    
    public Prefix(Token token, String operator){
        this(token, operator, null);
    }

    public String getOperator() {
        return operator;
    }

    public Expression getRight() {
        return right;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public void setRight(Expression right) {
        this.right = right;
    }
    
    
    
    @Override
    public String toString() {
        return String.format("(%1$s%2$s)" ,operator, right.toString()) ;
    }
}
