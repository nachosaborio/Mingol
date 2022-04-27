package Parser;

import Lexer.Token;

public class Booleano extends Expression{
    Boolean value;
    
    public Booleano(Token token, Boolean value) {
        super(token);
        this.value = value;
    }
    
    public Booleano(Token token){
        this(token, null);
    }

    public Boolean getValue() {
        return value;
    }

    public void setValue(Boolean value) {
        this.value = value;
    }
    
    @Override
    public String toString() {
        return TokenLiteral();
    }
}
