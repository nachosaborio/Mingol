package Parser;

import Lexer.Token;

public class Statement implements ASTNode{
    private Token token;
    
    public Statement(Token token) {
        this.token = token;
    }
    
    @Override
    public String TokenLiteral() {
        return token.getLiteral();
    }

    @Override
    public String Str() {
        return token.getTokenType().toString();
    }
}
