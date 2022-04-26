package Parser;

import Parser.Interfaces.ASTNode;
import Lexer.Token;

public class Expression implements ASTNode{

    private Token token;
    
    public Expression(Token token) {
        this.token = token;
    }
    
    @Override
    public String TokenLiteral() {
        return token.getLiteral();
    }

    @Override
    public String Str() {
        return "";
    }
}
