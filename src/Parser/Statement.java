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

    // Línea física del nodo (la de su token); 0 si no tiene token
    public int getLine() {
        return token == null ? 0 : token.getLine();
    }

    @Override
    public String toString() {
        return token.getTokenType().toString();
    }
}
