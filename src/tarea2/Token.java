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
        keywords.put("BEGIN", MingolToken.BEGIN);
        keywords.put("BY", MingolToken.BY);
        keywords.put("CHAR", MingolToken.TYPECHAR);
        keywords.put("CO", MingolToken.COMMENT);
        keywords.put("COMMENT", MingolToken.COMMENT);
        keywords.put("DO", MingolToken.DO);
        keywords.put("ELSE", MingolToken.ELSE);
        keywords.put("END", MingolToken.END);
        keywords.put("EQ", MingolToken.EQ);
        keywords.put("FALSE", MingolToken.FALSE);
        keywords.put("FI", MingolToken.FI);
        keywords.put("FOR", MingolToken.FOR);
        keywords.put("FROM", MingolToken.FROM);
        keywords.put("GE", MingolToken.GE);
        keywords.put("GOTO", MingolToken.GOTO);
        keywords.put("GT", MingolToken.GT);
        keywords.put("IF", MingolToken.IF);
        keywords.put("INT", MingolToken.TYPEINT);
        keywords.put("LE", MingolToken.LE);
        keywords.put("LT", MingolToken.LT);
        keywords.put("NE", MingolToken.NE);
        keywords.put("OD", MingolToken.OD);
        keywords.put("REAL", MingolToken.TYPEREAL);
        keywords.put("RETURN", MingolToken.RETURN);
        keywords.put("SKIP", MingolToken.SKIP);
        keywords.put("STRING", MingolToken.TYPESTRING);
        keywords.put("THEN", MingolToken.THEN);
        keywords.put("TO", MingolToken.TO);
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
