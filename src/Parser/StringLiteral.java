package Parser;

import Lexer.Token;

public class StringLiteral extends Expression{
    String value;

    public StringLiteral(Token token, String value) {
        super(token);
        this.value = value;
    }

    public String getValue() {
        return value;
    }
    
    @Override
    public String toString(){
        return value;
    }
}
