package Parser;

import Lexer.Token;

public class Infix extends Expression{
    Expression left;
    String operator;
    Expression right;

    public Infix(Token token, Expression left, String operator, Expression right) {
        super(token);
        this.left = left;
        this.operator = operator;
        this.right = right;
    }
    
    public Infix(Token token, Expression left, String operator){
        this(token,left,operator, null);
    }

    public Expression getLeft() {
        return left;
    }

    public void setLeft(Expression left) {
        this.left = left;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Expression getRight() {
        return right;
    }

    public void setRight(Expression right) {
        this.right = right;
    }
    
    @Override
    public String Str() {
        return String.format("(%1$s %2$s %3$s)" ,operator, left.toString(), right.toString()) ;
    }
}
