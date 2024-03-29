package Lexer;

import Evaluador.Caracter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Lexer {

    private String source;
    private String character;
    private int readPosition;
    private int position;

    //Constructor
    public Lexer(String source) {
        this.source = source;
        this.character = "";
        this.readPosition = 0;
        this.position = 0;
        ReadCharacter();
    }

    //Verifica si el siguiente caracter es letra
    private boolean IsLetter(String caracter) {
        Pattern pat = Pattern.compile("^[a-zA-Z_]$");
        Matcher mat = pat.matcher(caracter);
        return mat.matches();
    }

    //Verifica si el siguiente caracter es un número
    private boolean IsNumber(String caracter) {
        Pattern pat = Pattern.compile("^[\\d]$");
        Matcher mat = pat.matcher(caracter);
        return mat.matches();
    }

    //Crea un token de dos caracteres
    private Token MakeTwoCharacterToken(MingolToken type) {
        String prefix = character;
        ReadCharacter();
        String suffix = character;
        return new Token(type, prefix + suffix);
    }

    //Predice el siguiente caracter
    public String PeekCharacter() {
        if (readPosition >= source.length()) {
            return "";
        } else {
            return Character.toString(source.charAt(readPosition));
        }
    }

    //Lee el siguiente caracter
    public void ReadCharacter() {
        if (readPosition >= source.length()) {
            character = "";
        } else {
            character = Character.toString(source.charAt(readPosition));
        }
        position = readPosition;
        readPosition++;
    }

    //Va leyendo una palabra letra por letra
    private String ReadIdentifier() {
        int initialPosition = position;

        while (IsLetter(character)) {
            ReadCharacter();
        }
        return source.substring(initialPosition, position);
    }

    //Va leyendo un número dígito por dígito
    private String ReadNumber() {
        int initialPosition = position;
        while (IsNumber(character)) {
            ReadCharacter();
        }
        return source.substring(initialPosition, position);
    }

    private String ReadString() {
        ReadCharacter();
        int initialPosition = position;

        while (!character.equals("\"") && readPosition <= source.length()) {
            ReadCharacter();
        }
        return source.substring(initialPosition, position);
    }

    //Elimina los espacios en blanco
    private void SkipWhiteSpace() {
        while (character.matches("^[\\s]$")) {
            ReadCharacter();
        }
    }

    //Toma el siguiente token y lo clasifica
    public Token NextToken() {
        Token token;
        SkipWhiteSpace();
        switch (character) {
            case ".":
                token = new Token(MingolToken.DECIMAL, character);
                break;
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
            case "\\":
                if (PeekCharacter().equals("n")) {
                    token = MakeTwoCharacterToken(MingolToken.EOL);
                } else {
                    token = MakeTwoCharacterToken(MingolToken.ILLEGAL);
                }
                break;
            case "*":
                token = new Token(MingolToken.MULTIPLICATION, character);
                break;
            case "":
                token = new Token(MingolToken.EOF, character);
                break;
            case "\n":
                token = new Token(MingolToken.EOL, character);
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
            case "#":
                token = new Token(MingolToken.COMMENT, character);
                break;
            case "!":
                token = new Token(MingolToken.NEGATION, character);
                break;
            case "\"":
                String iliteral = ReadString();
                token = new Token(MingolToken.STRING, iliteral);
                break;
            default:
                if (IsLetter(character)) {
                    String literal = ReadIdentifier();
                    if (character.equals(":")) {
                        ReadCharacter();
                        return new Token(MingolToken.ETIQUETA, literal);
                    }
                    MingolToken tokenType = Token.LookupTokenType(literal);
                    return new Token(tokenType, literal);
                } else if (IsNumber(character)) {
                    String literal = ReadNumber();
                    if (character.equals(".")) {
                        ReadCharacter();
                        literal += "." + ReadNumber();
                        return new Token(MingolToken.REAL, literal);
                    }
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
