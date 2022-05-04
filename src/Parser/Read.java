package Parser;

import Lexer.Token;

public class Read extends Expression{
    Expression identificador;

    public Read(Token token) {
        super(token);
    }

    public Expression getIdentificador() {
        return identificador;
    }

    public void setIdentificador(Expression identificador) {
        this.identificador = identificador;
    }
    
    @Override
    public String toString(){
        String out = "read("+ identificador.toString() + ")";
        return out;
    }
}