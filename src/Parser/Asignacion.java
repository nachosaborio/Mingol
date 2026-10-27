package Parser;

import Lexer.Token;

// Nodo nuevo (área 3): asignación "variable := expresión;". La expresión es una Secuencia sin gramática (S3).
public class Asignacion extends Statement{
    private Identifier variable;
    private Secuencia expresion;

    // token = el de la variable (para getLine())
    public Asignacion(Token token, Identifier variable, Secuencia expresion){
        super(token);
        this.variable = variable;
        this.expresion = expresion;
    }

    public Identifier getVariable() {
        return variable;
    }

    public Secuencia getExpresion() {
        return expresion;
    }

    @Override
    public String toString() {
        return String.format("%1$s := %2$s", variable, expresion);
    }
}
