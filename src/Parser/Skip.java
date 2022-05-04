package Parser;

import Lexer.Token;

public class Skip extends Expression{

    public Skip(Token token) {
        super(token);
    }
    
    @Override
    public String toString(){
        return TokenLiteral();
    }
}
