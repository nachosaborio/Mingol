package tarea2;

import java.util.HashMap;
import java.util.Map;

public class Token {

    private MingolToken TokenType;
    private String Literal;

    public Token(MingolToken TokenType, String Literal) {
        this.TokenType = TokenType;
        this.Literal = Literal;
    }

    public String Tuple() {
        String result = "Type: " + TokenType + ", Literal: " + Literal;
        return result;
    }

    public static MingolToken LookupTokenType(String literal) {
        //Diccionario con los tokens 
        Map keywords = new HashMap<String, MingolToken>();
        keywords.put("ELSE", MingolToken.ELSE);
        keywords.put("FALSE", MingolToken.FALSE);
        keywords.put("FI", MingolToken.FI);
        keywords.put("IF", MingolToken.IF);
        keywords.put("INT", MingolToken.TYPEINT);
        keywords.put("RETURN", MingolToken.RETURN);
        keywords.put("THEN", MingolToken.THEN);
        keywords.put("TRUE", MingolToken.TRUE);
        //keywords.put(":=", MingolToken.ASSIGN);

        MingolToken elToken = (MingolToken) keywords.get(literal);
        if (elToken != null) {
            return elToken;
        } else {
            return MingolToken.IDENT;
        }
    }
}
