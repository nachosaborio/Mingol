package Parser;

import Lexer.Token;

public class Goto  extends Expression {

    Expression identificador;

    public Goto(Token token) {
        super(token);
    }

    public Expression getIdentificador() {
        return identificador;
    }

    public void setIdentificador(Expression identificador) {
        this.identificador = identificador;
    }

    @Override
    public String toString() {
        String out = "GOTO " + identificador.toString();
        return out;
    }
}
