package Parser;

import Lexer.Token;

public class Decimal extends Expression{
    private Double value;

    public Decimal(Token token, Double value) {
        super(token);
        this.value = value;
    }

    public Decimal(Token token) {
        this(token, null);
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }
    
    @Override
    public String toString() {
        return value.toString();
    }
}