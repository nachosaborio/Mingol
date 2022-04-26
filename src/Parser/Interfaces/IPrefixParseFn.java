package Parser.Interfaces;

import Lexer.Token;
import Parser.Expression;

public abstract class IPrefixParseFn {
    
    abstract public void setCurrentToken(Token currentToken);
    abstract public Expression Function();
}
