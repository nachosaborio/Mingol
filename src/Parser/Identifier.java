package Parser;

import Lexer.Token;

public class Identifier extends Expression{
    private Token token;
    private String value;

    public Identifier(Token token, String value){
        super(token);
        this.token = token;
        this.value = value;
    }

    public Token getToken() {
        return token;
    }

    public String getValue() {
        return value;
    }
    
    @Override
    public String toString() {
        return value;
    }
}
