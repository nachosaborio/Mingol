package tarea2;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Lexer {

    private String source;
    private String character;
    private int readPosition;
    private int position;

    public Lexer(String source) {
        this.source = source;
        this.character = "";
        this.readPosition = 0;
        this.position = 0;
        ReadCharacter();
    }

    private boolean IsLetter(String caracter) {
        Pattern pat = Pattern.compile("^[a-zA-Z_]$");
        Matcher mat = pat.matcher(caracter);
        return mat.matches();
    }

    private boolean IsNumber(String caracter) {
        Pattern pat = Pattern.compile("^[\\d]$");
        Matcher mat = pat.matcher(caracter);
        return mat.matches();
    }

    private Token MakeTwoCharacterToken(MingolToken type) {
        String prefix = character;
        ReadCharacter();
        String suffix = character;
        return new Token(type, prefix + suffix);
    }

    public String PeekCharacter() {
        if (readPosition >= source.length()) {
            return "";
        } else {
            return Character.toString(source.charAt(readPosition));
        }
    }

    public void ReadCharacter() {
        if (readPosition >= source.length()) {
            character = "";
        } else {
            character = Character.toString(source.charAt(readPosition));
        }
        position = readPosition;
        readPosition++;
    }

    private String ReadIdentifier() {
        int initialPosition = position;

        while (IsLetter(character)) {
            ReadCharacter();
        }
        return source.substring(initialPosition, position);
    }

    private String ReadNumber() {
        int initialPosition = position;
        while (IsNumber(character)) {
            ReadCharacter();
        }
        return source.substring(initialPosition, position);
    }

    private void SkipWhiteSpace() {
        while (character.matches("^[\\s]$")) {
            ReadCharacter();
        }
    }

    public Token NextToken() {
        Token token;
        SkipWhiteSpace();
        switch (character) {
            case ":":
                if (PeekCharacter().equals("=")) {
                    token = MakeTwoCharacterToken(MingolToken.ASSIGN);
                } else {
                    token = new Token(MingolToken.ILLEGAL, character);
                }
                break;
            case "=":
                token = new Token(MingolToken.EQ, character);
                break;
            case "+":
                token = new Token(MingolToken.ADDITION, character);
                break;
            case "-":
                token = new Token(MingolToken.SUBSTRACTION, character);
                break;
            case "/":
                if (PeekCharacter().equals("=")) {
                    token = MakeTwoCharacterToken(MingolToken.NE);
                } else {
                    token = new Token(MingolToken.DIVISION, character);
                }
                break;
            case "*":
                token = new Token(MingolToken.MULTIPLICATION, character);
                break;
            case "":
                token = new Token(MingolToken.EOL, "\\n");
                break;
            case "(":
                token = new Token(MingolToken.LPAREN, character);
                break;
            case ")":
                token = new Token(MingolToken.RPAREN, character);
                break;
            case ",":
                token = new Token(MingolToken.COMMA, character);
                break;
            case ";":
                token = new Token(MingolToken.SEMICOLON, character);
                break;
            case ">":
                if (PeekCharacter().equals("=")) {
                    token = MakeTwoCharacterToken(MingolToken.GE);
                } else {
                    token = new Token(MingolToken.GT, character);
                }
                break;
            case "<":
                if (PeekCharacter().equals("=")) {
                    token = MakeTwoCharacterToken(MingolToken.LE);
                } else {
                    token = new Token(MingolToken.LT, character);
                }
                break;
            default:
                if (IsLetter(character)) {
                    String literal = ReadIdentifier();
                    MingolToken tokenType = Token.LookupTokenType(literal);
                    return new Token(tokenType, literal);
                } else if (IsNumber(character)) {
                    String literal = ReadNumber();
                    return new Token(MingolToken.INTEGER, literal);
                } else {
                    token = new Token(MingolToken.ILLEGAL, character);
                }
                break;
        }
        ReadCharacter();
        return token;
    }
}
