package Parser;

import Lexer.Token;

public class ReturnStatement extends Statement{
    private Expression returnValue;
    
    public ReturnStatement(Token token, Expression returnValue){
        super(token);
        this.returnValue = returnValue;
    }
    
    public ReturnStatement(Token token){
        this(token, null);
    }
    
    @Override
    public String Str() {
        return String.format("%1$s %2$s" , TokenLiteral(), returnValue.Str()) ;
    }
}
