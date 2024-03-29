package Parser;

import Parser.Interfaces.ASTNode;
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
    public String toString() {
        return token.getTokenType().toString();
    }
}
