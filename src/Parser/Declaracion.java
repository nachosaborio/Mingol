package Parser;

import Lexer.Token;
import java.util.ArrayList;

// Nodo nuevo (área 3): declaración "TIPO id1, id2, ...;" sin valor inicial (decisión 3).
// token = el tipo; un LetStatement(tipo, Identifier, null) por identificador.
public class Declaracion extends Statement{
    private ArrayList<LetStatement> variables = new ArrayList<>();

    public Declaracion(Token token){
        super(token);
    }

    // Agrega una variable declarada
    public void AddVariable(LetStatement variable){
        variables.add(variable);
    }

    public ArrayList<LetStatement> getVariables() {
        return variables;
    }

    // Tipo seguido de los identificadores separados por comas
    @Override
    public String toString() {
        String out = "";
        for (LetStatement v : variables) {
            out += (out.isEmpty() ? " " : ", ") + v.getName();
        }
        return TokenLiteral() + out;
    }
}
