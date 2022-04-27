
package Parser;

import Lexer.Token;

public class ExpressionStatement extends Statement{
    private Expression expression;
    
    public ExpressionStatement(Token token, Expression expression){
        super(token);
        this.expression = expression;
    }
    
    public ExpressionStatement(Token token){
        this(token, null);
    }

    public Expression getExpression() {
        return expression;
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }
    
    @Override
    public String toString() {
        return expression.toString();
    }
}
