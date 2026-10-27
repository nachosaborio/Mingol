package Parser;

import Lexer.Token;
import java.util.ArrayList;

// Nodo nuevo (área 3, S3): expresión o condición recorrida como secuencia de tokens, sin gramática.
// Guarda sus identificadores y constantes (elementos) y las palabras ALGOL que aparecen (palabrasAlgol).
// El evaluador no calcula valores con este nodo: solo valida sus identificadores.
public class Secuencia extends Expression{
    // Identifier, Integral, Decimal, StringLiteral o Booleano, en el orden del fuente
    private ArrayList<Expression> elementos = new ArrayList<>();
    // Palabras ALGOL dentro de la expresión o condición (sin advertencia, 2.5)
    private ArrayList<Token> palabrasAlgol = new ArrayList<>();

    // token = primer token de la secuencia (para getLine())
    public Secuencia(Token token) {
        super(token);
    }

    // Agrega un identificador o una constante
    public void AddElemento(Expression elemento){
        elementos.add(elemento);
    }

    // Agrega una palabra ALGOL
    public void AddPalabraAlgol(Token palabra){
        palabrasAlgol.add(palabra);
    }

    public ArrayList<Expression> getElementos() {
        return elementos;
    }

    public ArrayList<Token> getPalabrasAlgol() {
        return palabrasAlgol;
    }

    // Solo los identificadores de la secuencia (para el evaluador y el área 4)
    public ArrayList<Identifier> getIdentificadores() {
        ArrayList<Identifier> ids = new ArrayList<>();
        for (Expression e : elementos) {
            if (e instanceof Identifier) ids.add((Identifier) e);
        }
        return ids;
    }

    // Elementos y palabras ALGOL separados por espacios, en el orden en que se guardaron
    @Override
    public String toString() {
        String out = "";
        for (Expression e : elementos) {
            out += (out.isEmpty() ? "" : " ") + e.toString();
        }
        for (Token t : palabrasAlgol) {
            out += (out.isEmpty() ? "" : " ") + t.getLiteral();
        }
        return out;
    }
}
