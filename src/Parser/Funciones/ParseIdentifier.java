package Parser.Funciones;

import Lexer.Token;
import Parser.Expression;
import Parser.Identifier;
import Parser.Interfaces.IPrefixParseFn;

public class ParseIdentifier extends IPrefixParseFn{
    private Token currentToken;

    public void setCurrentToken(Token currentToken) {
        this.currentToken = currentToken;
    }
    
    @Override
    public Expression Function() {
        assert currentToken != null;
        return new Identifier(currentToken, currentToken.getLiteral());
    }
}
