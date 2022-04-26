package Parser;

import Lexer.Token;

public class Integral extends Expression{
    private Token token;
    private Integer value;
    
    public Integral(Token token, Integer value){
        super(token);
        this.value = value;
    }
    
    public Integral(Token token){
        this(token, null);
    }

    public Token getToken() {
        return token;
    }

    public int getValue() {
        return value;
    }

    public void setToken(Token token) {
        this.token = token;
    }

    public void setValue(Integer value) {
        this.value = value;
    }
    
    
    
    @Override
    public String Str() {
        return value.toString();
    }
}
