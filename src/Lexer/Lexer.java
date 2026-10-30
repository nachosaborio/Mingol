package Lexer;

import Errores.Mensaje;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

public class Lexer {

    // Límites de las constantes (magnitud sin signo), compartidos con el parser (L4)
    public static final BigInteger MAX_MAGNITUD_ENTERA = BigInteger.valueOf(32768);
    public static final BigDecimal MAX_MAGNITUD_REAL = new BigDecimal("32768.99");
    public static final BigInteger MAX_ENTERO_POSITIVO = BigInteger.valueOf(32767);
    public static final BigDecimal MAX_REAL_POSITIVO = new BigDecimal("32767.99");
    public static final BigDecimal MAX_REAL_NEGATIVO = new BigDecimal("32768.00");

    // Clases de caracteres, compiladas una sola vez
    private static final Pattern LETRA = Pattern.compile("^[a-zA-Z]$");
    private static final Pattern DIGITO = Pattern.compile("^[\\d]$");
    private static final Pattern INICIO_IDENT = Pattern.compile("^[a-zA-Z_]$");
    private static final Pattern PARTE_IDENT = Pattern.compile("^[a-zA-Z0-9_]$");
    private static final Pattern MAYUSCULA = Pattern.compile("[A-Z]");

    private String source;
    private String character;
    private int readPosition;
    private int position;
    // Línea física actual (sube al dejar atrás cada "\n")
    private int linea = 1;
    // Modo comentario: forma de apertura (null fuera de comentario, "#", "CO" o "COMMENT") y su línea
    private String formaComentario = null;
    private int lineaComentario = 0;

    // Mensajes léxicos (1xx) en orden de registro
    private final ArrayList<Mensaje> mensajes = new ArrayList<>();
    // 103 de cada IDENT, para que el parser lo retire en posición de comando (L2)
    private final IdentityHashMap<Token, Mensaje> mayusculas = new IdentityHashMap<>();

    //Constructor
    public Lexer(String source) {
        this.source = source;
        this.character = "";
        this.readPosition = 0;
        this.position = 0;
        // 113: línea física de más de 80 columnas, contando todo (espacios finales, comentarios, vacías) (L3)
        String[] lineas = source.split("\n", -1);
        for (int i = 0; i < lineas.length; i++) {
            if (lineas[i].length() > 80) {
                Error(i + 1, 113, "Línea de más de 80 columnas", String.valueOf(lineas[i].length()));
            }
        }
        ReadCharacter();
    }

    // Mensajes léxicos registrados, en orden (copia)
    public ArrayList<Mensaje> GetMensajes() {
        return new ArrayList<>(mensajes);
    }

    // Verdadero si el lexer registró algún ERROR
    public boolean HayErrores() {
        for (Mensaje m : mensajes) {
            if (m.EsError()) {
                return true;
            }
        }
        return false;
    }

    // Quita el 103 de ese token, si lo tiene (IDENT en posición de comando, L2)
    public void RetirarMayusculas(Token t) {
        Mensaje m = mayusculas.remove(t);
        if (m != null) {
            mensajes.remove(m); // Mensaje no redefine equals: se quita por identidad
        }
    }

    // Registra un error léxico en la línea física donde empezó el lexema
    private Mensaje Error(int linea, int codigo, String texto, String lexema) {
        Mensaje m = Mensaje.Error(linea, codigo, texto, lexema);
        mensajes.add(m);
        return m;
    }

    // Reglas de identificador (REQ-IDENT-01): 103, 104 y 105, en ese orden
    private void ValidarIdentificador(Token token, int lineaLexema) {
        String id = token.getLiteral();
        if (MAYUSCULA.matcher(id).find()) {
            Mensaje m = Error(lineaLexema, 103, "Identificador con mayúsculas", id);
            // Solo el IDENT puede retirarse en posición de comando
            if (token.getTokenType() == MingolToken.IDENT) {
                mayusculas.put(token, m);
            }
        }
        if (id.length() > 20) {
            Error(lineaLexema, 104, "Identificador de más de 20 caracteres", id);
        }
        if (!IsLetter(id.substring(0, 1)) || id.endsWith("_")) {
            Error(lineaLexema, 105, "Identificador que no empieza con letra o que empieza o termina con _", id);
        }
    }

    // Rango de magnitud de un REAL (L4): > 32768.99 es 110; BigDecimal acepta "1." y ".5"
    private void ValidarRangoReal(String literal, int lineaLexema) {
        if (new BigDecimal(literal).compareTo(MAX_MAGNITUD_REAL) > 0) {
            Error(lineaLexema, 110, "Constante real fuera de rango", literal);
        }
    }

    // Verifica si el caracter es letra (ya no incluye "_")
    private boolean IsLetter(String caracter) {
        return LETRA.matcher(caracter).matches();
    }

    // Puede empezar un identificador: letra o "_" (el "_" inicial da 105)
    private boolean EsInicioDeIdentificador(String caracter) {
        return INICIO_IDENT.matcher(caracter).matches();
    }

    // Puede seguir dentro de un identificador: letra, dígito o "_"
    private boolean EsParteDeIdentificador(String caracter) {
        return PARTE_IDENT.matcher(caracter).matches();
    }

    //Verifica si el siguiente caracter es un número
    private boolean IsNumber(String caracter) {
        return DIGITO.matcher(caracter).matches();
    }

    // Lookahead sin consumir: true si tras espacios viene ":" y después (con o sin espacios) no viene "="
    private boolean SigueEtiqueta() {
        int j = position;
        while (j < source.length() && source.charAt(j) == ' ') {
            j++;
        }
        if (j >= source.length() || source.charAt(j) != ':') {
            return false;
        }
        j++;
        while (j < source.length() && source.charAt(j) == ' ') {
            j++;
        }
        // ":=" o ": =" es asignación, no etiqueta
        return j >= source.length() || source.charAt(j) != '=';
    }

    //Crea un token de dos caracteres
    private Token MakeTwoCharacterToken(MingolToken type) {
        String prefix = character;
        ReadCharacter();
        String suffix = character;
        return new Token(type, prefix + suffix);
    }

    // Operador de dos caracteres (<=, >=, /=, :=), pegado o con espacios intermedios (2.16).
    // Devuelve null si tras los espacios no viene "=" (no consume nada en ese caso).
    // Con espacios: avanza hasta dejar el "=" como carácter actual (el ReadCharacter final lo consume),
    // registra 114 con el texto tal como se escribió y emite el literal canónico.
    private Token OperadorDeDosCaracteres(MingolToken type) {
        if (PeekCharacter().equals("=")) {
            return MakeTwoCharacterToken(type);
        }
        // Lookahead solo de espacios: nunca cruza un "\n"
        int j = readPosition;
        while (j < source.length() && source.charAt(j) == ' ') {
            j++;
        }
        if (j >= source.length() || source.charAt(j) != '=') {
            return null;
        }
        int lineaOperador = linea;
        String canonico = character + "=";
        String escrito = source.substring(position, j + 1);
        while (position < j) {
            ReadCharacter();
        }
        Error(lineaOperador, 114, "Operador de dos caracteres con blanco intermedio", escrito);
        return new Token(type, canonico);
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
        // El carácter actual es el que se deja atrás: si es salto de línea, empieza otra línea
        if ("\n".equals(character)) {
            linea++;
        }
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

        // Incluye dígitos y "_": "factor1" es un solo lexema
        while (EsParteDeIdentificador(character)) {
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

    // Lee el contenido del texto hasta la comilla, el "\n" o el final, sin consumir ninguno de los tres (L5)
    private String ReadString() {
        ReadCharacter();
        int initialPosition = position;

        // El texto no cruza líneas: se detiene también en "\n" y en el final ("")
        while (!character.equals("\"") && !character.equals("\n") && !character.equals("")) {
            ReadCharacter();
        }
        return source.substring(initialPosition, position);
    }

    //Elimina los espacios en blanco
    private void SkipWhiteSpace() {
        // \s incluía el salto de línea y lo consumía antes del switch, así que case "\n" nunca emitía EOL; ahora se excluye
        while (character.matches("^[\\s&&[^\\n]]$")) {
            ReadCharacter();
        }
    }

    // Avanza dentro del comentario hasta el "\n" (sin consumirlo, el modo sigue),
    // el cierre de la misma forma (lo consume y sale del modo) o el final (107)
    private void SaltarComentario() {
        while (true) {
            if (character.equals("")) {
                // Sin cierre al final del archivo: 107 en la línea de apertura con la forma como lexema
                Error(lineaComentario, 107, "Comentario sin cerrar al final del archivo", formaComentario);
                formaComentario = null;
                return;
            }
            if (character.equals("\n")) {
                // El EOL de esta línea lo emite SiguienteToken
                return;
            }
            if (formaComentario.equals("#")) {
                boolean cierra = character.equals("#");
                ReadCharacter();
                if (cierra) {
                    formaComentario = null;
                    return;
                }
            } else if (EsParteDeIdentificador(character)) {
                // Corrida completa: "COBOL" o "XCO" no cierran un CO
                String corrida = ReadIdentifier();
                if (corrida.equals(formaComentario)) {
                    formaComentario = null;
                    return;
                }
            } else {
                // Cualquier otro carácter (comillas, "#" en CO/COMMENT, etc.) se ignora
                ReadCharacter();
            }
        }
    }

    // Envoltorio: salta comentarios y anota en el token la línea física donde empieza
    public Token NextToken() {
        while (true) {
            // Dentro de un comentario solo salen EOL (uno por línea física) y EOF
            if (formaComentario != null) {
                SaltarComentario();
            }
            SkipWhiteSpace();
            int lineaToken = linea;
            Token t = SiguienteToken();
            if (t == null) {
                // Se abrió un comentario: no hay token, se sigue con el bucle
                continue;
            }
            t.setLine(lineaToken);
            return t;
        }
    }

    //Toma el siguiente token y lo clasifica
    private Token SiguienteToken() {
        Token token;
        SkipWhiteSpace();
        switch (character) {
            case ".":
                if (IsNumber(PeekCharacter())) {
                    // ".5": REAL sin dígito antes del punto (L8), 109 y se emite REAL
                    int lineaPunto = linea;
                    ReadCharacter();
                    String decimal = "." + ReadNumber();
                    Error(lineaPunto, 109, "Constante real sin dígito antes o después del punto", decimal);
                    ValidarRangoReal(decimal, lineaPunto);
                    return new Token(MingolToken.REAL, decimal);
                }
                // "." suelto: DECIMAL como antes
                token = new Token(MingolToken.DECIMAL, character);
                break;
            case ":":
                // ":=" o ": =" (114); ":" suelto sigue ILLEGAL (3.4)
                token = OperadorDeDosCaracteres(MingolToken.ASSIGN);
                if (token == null) {
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
                // "/=" o "/ =" (114); si no, DIVISION como antes
                token = OperadorDeDosCaracteres(MingolToken.NE);
                if (token == null) {
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
                // ">=" o "> =" (114); si no, GT como antes
                token = OperadorDeDosCaracteres(MingolToken.GE);
                if (token == null) {
                    token = new Token(MingolToken.GT, character);
                }
                break;
            case "<":
                // "<=" o "< =" (114); si no, LT como antes
                token = OperadorDeDosCaracteres(MingolToken.LE);
                if (token == null) {
                    token = new Token(MingolToken.LT, character);
                }
                break;
            case "#":
                // Abre un comentario con forma "#": no se emite token
                formaComentario = "#";
                lineaComentario = linea;
                ReadCharacter();
                return null;
            case "!":
                token = new Token(MingolToken.NEGATION, character);
                break;
            case "\"":
                // Línea física de la comilla de apertura, para el 111/112
                int lineaTexto = linea;
                String iliteral = ReadString();
                token = new Token(MingolToken.STRING, iliteral);
                if (!character.equals("\"")) {
                    // Sin cierre en la línea (L5): 112 con '"' + contenido sin espacios finales, sin 111;
                    // se devuelve sin consumir el "\n" (sale como EOL en la llamada siguiente)
                    Error(lineaTexto, 112, "Texto sin comilla de cierre", "\"" + iliteral.stripTrailing());
                    return token;
                }
                // Cerrado: 111 si el contenido supera 30 caracteres; el ReadCharacter final consume la comilla
                if (iliteral.length() > 30) {
                    Error(lineaTexto, 111, "Texto de más de 30 caracteres", iliteral);
                }
                break;
            default:
                if (EsInicioDeIdentificador(character)) {
                    // Línea física al empezar el lexema
                    int lineaLexema = linea;
                    String literal = ReadIdentifier();
                    // CO/COMMENT exactos abren comentario antes de mirar la etiqueta: no se emite token
                    if (literal.equals("CO") || literal.equals("COMMENT")) {
                        formaComentario = literal;
                        lineaComentario = lineaLexema;
                        return null;
                    }
                    if (SigueEtiqueta()) {
                        // Etiqueta: se consumen los espacios y el ":"
                        while (!character.equals(":")) {
                            ReadCharacter();
                        }
                        ReadCharacter();
                        Token etiqueta = new Token(MingolToken.ETIQUETA, literal);
                        if (Token.EsReservada(literal)) {
                            Error(lineaLexema, 106, "Palabra reservada de MINGOL o ALGOL usada como etiqueta", literal);
                        } else {
                            ValidarIdentificador(etiqueta, lineaLexema);
                        }
                        return etiqueta;
                    }
                    MingolToken tokenType = Token.LookupTokenType(literal);
                    Token palabra = new Token(tokenType, literal);
                    // Las reservadas exactas no se validan como identificador
                    if (tokenType == MingolToken.IDENT) {
                        ValidarIdentificador(palabra, lineaLexema);
                    }
                    return palabra;
                } else if (IsNumber(character)) {
                    // Línea física y posición al empezar el lexema
                    int lineaNumero = linea;
                    int inicioNumero = position;
                    String literal = ReadNumber();
                    if (EsInicioDeIdentificador(character)) {
                        // "1factor": se lee la corrida completa como IDENT; ValidarIdentificador da 105 (y 103/104)
                        ReadIdentifier();
                        Token pegado = new Token(MingolToken.IDENT, source.substring(inicioNumero, position));
                        ValidarIdentificador(pegado, lineaNumero);
                        return pegado;
                    }
                    if (character.equals(".")) {
                        ReadCharacter();
                        if (IsNumber(character)) {
                            // "1.5": REAL bien formado
                            literal += "." + ReadNumber();
                        } else {
                            // "1.": punto consumido sin dígito detrás, 109 y se emite REAL [1.]
                            literal += ".";
                            Error(lineaNumero, 109, "Constante real sin dígito antes o después del punto", literal);
                        }
                        ValidarRangoReal(literal, lineaNumero);
                        return new Token(MingolToken.REAL, literal);
                    }
                    // Solo magnitud (L4): el 32768 sin "-" unario lo resuelve el parser
                    if (new BigInteger(literal).compareTo(MAX_MAGNITUD_ENTERA) > 0) {
                        Error(lineaNumero, 108, "Constante entera fuera de rango", literal);
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
