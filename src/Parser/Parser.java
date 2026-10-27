package Parser;

import java.util.ArrayList;
// Conjuntos de cierres de la pila de bloques (cambio 6)
import java.util.EnumSet;
// Magnitudes de constantes sin depender de int/double (L4)
import java.math.BigDecimal;
import java.math.BigInteger;

// Mensajes estructurados para el archivo de errores
import Errores.Mensaje;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Interfaces.IInfixParseFn;
import Parser.Interfaces.IPrefixParseFn;
import java.util.HashMap;

public class Parser {

    public boolean hasErrors = false;
    public int line = 1;
    private boolean isEnd = false;
    private boolean isComment = false;
    private boolean errorBegin = false;
    // Corrección BUG 1: se eliminó el campo 'linea' porque nunca se incrementaba
    // (todos los mensajes decían "Linea 1"). Ahora los mensajes usan 'line',
    // que AdvanceToken() incrementa al consumir cada EOL.

    private Lexer lexer;
    private Token currentToken;
    private Token peekToken;
    // Todos los tokens del lexer hasta EOF (inclusive), sin los ILLEGAL (cambio 1)
    private ArrayList<Token> tokens;
    // Índice de currentToken en 'tokens' (-2 antes del primer AdvanceToken)
    private int indiceActual = -2;
    // Si el fuente tiene algún BEGIN / END (para la sección inicial y S6)
    private boolean hayBegin = false;
    private boolean hayEnd = false;
    // Líneas físicas no vacías que lleva el comando actual (S4: máximo 5)
    private int lineasDelComando = 1;
    // ILLEGAL quitados de 'tokens', con el índice del token que los sigue, y el próximo a registrar
    private ArrayList<Token> ilegales;
    private ArrayList<Integer> posicionIlegal;
    private int proximoIlegal = 0;
    // Tarea 5 (DS5): se borró el campo anterior (solo lo leía EsInicioDeComando)
    // Primer token del último comando (posición de comando); lo usa ParseAlgol (L7)
    private Token tokenDeComando;
    // Constante INTEGER/REAL precedida por un '-' unario (L4); se compara por identidad
    private Token constanteNegada;
    // Cambio 6: sección del programa (REQ-STRUCT)
    private enum Seccion { ANTES_DE_BEGIN, VARIABLES, COMANDOS, DESPUES_DE_END }
    private Seccion seccion;
    // Si ya se vio el primer BEGIN / END (solo el primero cambia la sección)
    private boolean vistoBegin = false;
    private boolean vistoEnd = false;
    // Comandos entre el primer BEGIN y el primer END (217; DS1: sin declaraciones ni ALGOL)
    private int comandos = 0;
    // Si el último ParseSatement fue un comando que cuenta para 216/217 (DS1)
    private boolean ultimoCuenta = false;
    // Cambio 6: un nivel de la pila de bloques (palabra IF/FOR y sus cierres)
    private static class NivelDeBloque {
        final MingolToken palabra;
        final EnumSet<MingolToken> cierres;
        NivelDeBloque(MingolToken palabra, EnumSet<MingolToken> cierres) {
            this.palabra = palabra;
            this.cierres = cierres;
        }
    }
    private ArrayList<NivelDeBloque> pilaDeBloques = new ArrayList<NivelDeBloque>();
    // Bloques IF / FOR abiertos (214/215 en 3.6)
    private int ifsAbiertos = 0;
    private int forsAbiertos = 0;
    private ArrayList<String> errors;
    // Mensajes estructurados, uno por cada cadena de 'errors' y en el mismo orden
    private ArrayList<Mensaje> mensajes;
    private HashMap<MingolToken, IPrefixParseFn> prefixParseFns;
    private HashMap<MingolToken, IInfixParseFn> infixParseFns;
    private final HashMap<MingolToken, Precedence> PRECEDENCES = new HashMap<MingolToken, Precedence>() {
        {
            put(MingolToken.EQ, Precedence.EQUALS);
            put(MingolToken.NE, Precedence.EQUALS);
            put(MingolToken.LT, Precedence.LESSGREATER);
            put(MingolToken.GT, Precedence.LESSGREATER);
            put(MingolToken.LE, Precedence.LESSGREATER);
            put(MingolToken.GE, Precedence.LESSGREATER);
            put(MingolToken.ADDITION, Precedence.SUM);
            put(MingolToken.SUBSTRACTION, Precedence.SUM);
            put(MingolToken.DIVISION, Precedence.PRODUCT);
            put(MingolToken.MULTIPLICATION, Precedence.PRODUCT);
            put(MingolToken.LPAREN, Precedence.CALL);
        }
    };

    public Parser(Lexer lexer, Token currentToken, Token peekToken) {
        this.lexer = lexer;
        this.errors = new ArrayList<String>();
        // Lista paralela a 'errors' para el archivo de errores
        this.mensajes = new ArrayList<Mensaje>();
        prefixParseFns = RegisterPrefixFns();
        infixParseFns = RegisterInfixFns();

        // Cambio 1: se cargan todos los tokens antes de analizar
        CargarTokens();
        // Cambio 6 (DS3): sin BEGIN en el fuente, los comandos se analizan normalmente (sin 207)
        seccion = hayBegin ? Seccion.ANTES_DE_BEGIN : Seccion.VARIABLES;

        AdvanceToken();
        AdvanceToken();
    }

    // Lee del lexer hasta EOF. Cada ILLEGAL se quita de la lista: para la
    // sintaxis un carácter ilegal no existe (sin 205/204 en cascada); su 101
    // lo registra AdvanceToken (RegistrarIlegales)
    private void CargarTokens() {
        tokens = new ArrayList<Token>();
        ilegales = new ArrayList<Token>();
        posicionIlegal = new ArrayList<Integer>();
        while (true) {
            Token t = lexer.NextToken();
            if (t.getTokenType() == MingolToken.ILLEGAL) {
                // Se recuerda con la posición del token que lo sigue; el 101 se
                // registra al pasar por ahí, para conservar el orden de GetErrors()
                ilegales.add(t);
                posicionIlegal.add(tokens.size());
                continue;
            }
            if (t.getTokenType() == MingolToken.BEGIN) {
                hayBegin = true;
            } else if (t.getTokenType() == MingolToken.END) {
                hayEnd = true;
            }
            tokens.add(t);
            if (t.getTokenType() == MingolToken.EOF) {
                break;
            }
        }
    }

    // 101 de cada ILLEGAL que quedó antes de la posición actual: misma cadena y
    // mismo Mensaje que ParseIllegal, con la línea del token (cambio 1)
    private void RegistrarIlegales() {
        while (proximoIlegal < ilegales.size() && posicionIlegal.get(proximoIlegal) <= indiceActual) {
            Token t = ilegales.get(proximoIlegal++);
            errors.add("\tLinea "+ t.getLine() +"Error 205: Token ilegal: " + t.getLiteral());
            mensajes.add(Mensaje.Error(t.getLine(), 101, "Carácter ilegal", Lexema(t)));
            hasErrors = true;
        }
    }

    // Token de la lista en la posición i; más allá del final, el EOF; antes del inicio, null
    private Token TokenEn(int i) {
        if (i < 0) {
            return null;
        }
        return tokens.get(Math.min(i, tokens.size() - 1));
    }

    // Cambio 2: avanza dentro de un comando. Si queda en un EOL y la siguiente
    // línea no vacía es continuación (S4), salta los EOL intermedios; si no,
    // el EOL queda como FIN del comando
    private void AvanzarEnComando() {
        AdvanceToken();
        if (currentToken.getTokenType() != MingolToken.EOL) {
            return;
        }
        // Primer token de la siguiente línea no vacía (sin avanzar)
        int i = indiceActual;
        while (TokenEn(i).getTokenType() == MingolToken.EOL) {
            i++;
        }
        if (lineasDelComando < 5 && EsContinuacion(i)) {
            while (currentToken.getTokenType() == MingolToken.EOL) {
                AdvanceToken();
            }
            lineasDelComando++;
        }
    }

    // S4: la línea que empieza en el token i continúa el comando si no empieza
    // con EOF, un cierre ni una palabra de inicio. Una palabra ALGOL no corta (DS6)
    private boolean EsContinuacion(int i) {
        Token t = TokenEn(i);
        return !EsCierre(t) && !EsPalabraDeInicio(t, TokenEn(i + 1))
                && t.getTokenType() != MingolToken.EOF;
    }

    // Cierres de bloque o de programa
    private boolean EsCierre(Token t) {
        switch (t.getTokenType()) {
            case END:
            case ELSE:
            case FI:
            case OD:
                return true;
            default:
                return false;
        }
    }

    // Palabras de inicio de S4; 'identificador :=' se reconoce con el token siguiente del renglón
    private boolean EsPalabraDeInicio(Token t, Token siguiente) {
        switch (t.getTokenType()) {
            case BEGIN:
            case IF:
            case FOR:
            case GOTO:
            case SKIP:
            case READ:
            case PRINT:
            case TYPEINT:
            case TYPEREAL:
            case TYPECHAR:
            case ETIQUETA:
                return true;
            case IDENT:
                return siguiente != null && siguiente.getTokenType() == MingolToken.ASSIGN;
            default:
                return false;
        }
    }

    // Primer token de la siguiente línea no vacía a partir del índice i (sin avanzar)
    private Token PrimeroDeLineaSiguiente(int i) {
        while (TokenEn(i).getTokenType() == MingolToken.EOL) {
            i++;
        }
        return TokenEn(i);
    }

    // Avanza hasta el EOL (o EOF) de la línea física actual, sin mensajes
    private void SaltarLineaFisica() {
        while (currentToken.getTokenType() != MingolToken.EOL
                && currentToken.getTokenType() != MingolToken.EOF) {
            AdvanceToken();
        }
    }

    // Cambio 3: fin de comando (REQ-LINE-04, S4, S9) con el token que sigue a la
    // gramática del comando. Deja el currentToken en el EOL/EOF del fin o en el
    // cierre de la misma línea (que queda para el bloque)
    private void TerminarComando() {
        MingolToken tipo = currentToken.getTokenType();
        if (tipo == MingolToken.SEMICOLON) {
            Token puntoYComa = currentToken;
            AdvanceToken();
            if (currentToken.getTokenType() != MingolToken.EOL
                    && currentToken.getTokenType() != MingolToken.EOF) {
                // Sobrante después del ';': un 202 con el primero y se salta la línea
                Error202(MingolToken.SEMICOLON, currentToken);
                SaltarLineaFisica();
                return;
            }
            // ';' antes de un cierre: 206 con el cierre en el texto (2.8)
            Token siguiente = PrimeroDeLineaSiguiente(indiceActual);
            if (EsCierre(siguiente)) {
                ErrorSintactico(206, "La línea anterior a " + siguiente.getTokenType()
                        + " no lleva punto y coma", puntoYComa);
            }
            return;
        }
        if (tipo == MingolToken.EOL || tipo == MingolToken.EOF) {
            // Sin ';': bien si sigue un cierre o no hay más tokens; si no, 204 [EOL] (2.9)
            if (tipo == MingolToken.EOL) {
                Token siguiente = PrimeroDeLineaSiguiente(indiceActual);
                if (siguiente.getTokenType() != MingolToken.EOF && !EsCierre(siguiente)) {
                    Error204(MingolToken.SEMICOLON, currentToken);
                }
            }
            return;
        }
        if (EsCierre(currentToken)) {
            // Cierre en la misma línea: el comando termina sin ';' (S9)
            return;
        }
        // Cualquier otro token: 204 SEMICOLON con ese token y resincronización
        Error204(MingolToken.SEMICOLON, currentToken);
        Resincronizar();
    }

    // Cambio 4: avanza sin registrar nada hasta ';', FIN, EOF o un cierre (2.20).
    // Con ';' lo consume y salta el resto de la línea física
    private void Resincronizar() {
        while (currentToken.getTokenType() != MingolToken.SEMICOLON
                && currentToken.getTokenType() != MingolToken.EOL
                && currentToken.getTokenType() != MingolToken.EOF
                && !EsCierre(currentToken)) {
            AvanzarEnComando();
        }
        if (currentToken.getTokenType() == MingolToken.SEMICOLON) {
            AdvanceToken();
            SaltarLineaFisica();
        }
    }

    // Cambio 5: mensaje sintáctico en la línea física del token del lexema (2.21;
    // el EOF lleva n+1 y ArchivoErrores aplica D1). Cadena por defecto: "texto [lexema]"
    private void ErrorSintactico(int codigo, String texto, Token lexema) {
        ErrorSintactico(codigo, texto, texto + " [" + Lexema(lexema) + "]", lexema);
    }

    // Igual, con la cadena de GetErrors() explícita (202 y 204 conservan la de hoy).
    // Mensaje y cadena se registran juntos, en el mismo orden
    private void ErrorSintactico(int codigo, String texto, String cadena, Token lexema) {
        int linea = lexema.getLine();
        errors.add("\tLinea " + linea + " Error " + codigo + ": " + cadena);
        mensajes.add(Mensaje.Error(linea, codigo, texto, Lexema(lexema)));
        hasErrors = true;
    }

    // 202 con el texto y la cadena de hoy (X = la palabra tras la que sobra algo)
    private void Error202(Object palabra, Token sobrante) {
        ErrorSintactico(202, "No puede haber nada en la línea después de " + palabra,
                "no puede haber nada en la línea luego del token " + palabra, sobrante);
    }

    // 204 con el tipo del token obtenido (DS6: p. ej. ALGOL) y la cadena de hoy ("... un Y")
    private void Error204(MingolToken esperado, Token obtenido) {
        ErrorSintactico(204, "Se esperaba un token de tipo " + esperado + " pero se obtuvo " + obtenido.getTokenType(),
                "Se esperaba un token de tipo " + esperado + " pero se obtuvo un " + obtenido.getTokenType(), obtenido);
    }

    // Salta las líneas vacías (EOL seguidos) hasta el primer token de un comando
    private void SaltarLineasVacias() {
        while (currentToken.getTokenType() == MingolToken.EOL) {
            AdvanceToken();
        }
    }

    // Cambio 9: BEGIN, END, ELSE y etiqueta van solos en su línea. Avanza; si hay
    // algo antes del EOL/EOF → 202 con el primer sobrante y se salta la línea física
    private void NadaMasEnLaLinea(MingolToken palabra) {
        AdvanceToken();
        if (currentToken.getTokenType() != MingolToken.EOL
                && currentToken.getTokenType() != MingolToken.EOF) {
            Error202(palabra, currentToken);
            SaltarLineaFisica();
        }
    }

    // Cambio 10 (S9): fin de la cabecera de IF/FOR (THEN, DO). Un ';' da 202 [;] y se
    // descarta; otro sobrante da 202 con el primero, pero NO se salta: es el
    // comienzo del cuerpo. Un solo 202 por cabecera
    private void FinDeCabecera(MingolToken palabra) {
        AdvanceToken();
        boolean reportado = false;
        if (currentToken.getTokenType() == MingolToken.SEMICOLON) {
            Error202(palabra, currentToken);
            reportado = true;
            AdvanceToken();
        }
        if (!reportado && currentToken.getTokenType() != MingolToken.EOL
                && currentToken.getTokenType() != MingolToken.EOF) {
            Error202(palabra, currentToken);
        }
    }

    // Tras un 205/213: deja atrás el token del error y resincroniza sin más
    // mensajes (2.20). Un ';' lo consume directamente Resincronizar
    private void DescartarComando() {
        if (currentToken.getTokenType() != MingolToken.SEMICOLON
                && currentToken.getTokenType() != MingolToken.EOF) {
            AvanzarEnComando();
        }
        Resincronizar();
    }

    // Si el token cierra algún nivel de la pila de bloques (cualquier nivel)
    private boolean CierraAlgunBloque(Token t) {
        for (NivelDeBloque nivel : pilaDeBloques) {
            if (nivel.cierres.contains(t.getTokenType())) {
                return true;
            }
        }
        return false;
    }

    // Cambio 16: bloque de IF/FOR. Apila el nivel y analiza comandos hasta EOF, END
    // o un cierre de cualquier nivel; cuenta los comandos (DS1) y desapila.
    // El cierre no se consume: lo consume quien abrió el bloque
    private Block ParseBlock(MingolToken tipo, MingolToken... cierres) {
        EnumSet<MingolToken> conjunto = EnumSet.noneOf(MingolToken.class);
        for (MingolToken c : cierres) {
            conjunto.add(c);
        }
        Block block = new Block(currentToken, new ArrayList<>());
        pilaDeBloques.add(new NivelDeBloque(tipo, conjunto));
        if (tipo == MingolToken.IF) {
            ifsAbiertos++;
        } else if (tipo == MingolToken.FOR) {
            forsAbiertos++;
        }
        int cantidad = 0;
        while (true) {
            SaltarLineasVacias();
            if (currentToken.getTokenType() == MingolToken.EOF
                    || currentToken.getTokenType() == MingolToken.END
                    || CierraAlgunBloque(currentToken)) {
                break;
            }
            int antes = indiceActual;
            Statement statement = ParseSatement();
            if (statement != null) {
                block.AddStatement(statement);
            }
            if (ultimoCuenta) {
                cantidad++;
            }
            // Garantía de avance, igual que en ParseProgram
            if (indiceActual == antes) {
                AdvanceToken();
            }
        }
        pilaDeBloques.remove(pilaDeBloques.size() - 1);
        if (tipo == MingolToken.IF) {
            ifsAbiertos--;
        } else if (tipo == MingolToken.FOR) {
            forsAbiertos--;
        }
        comandosDelUltimoBloque = cantidad;
        return block;
    }

    // Comandos que tuvo el último ParseBlock(tipo, cierres) (216 en 3.6)
    private int comandosDelUltimoBloque = 0;

    // ---- Métodos de despacho de ParseSatement ----
    // (Tarea 5, DS5: se borró el puente DelegarEnViejo/profundidadVieja, sin referencias)

    // Cambio 11: declaración "TIPO id1, id2, ...;" sin valor inicial (decisión 3).
    // Devuelve siempre el nodo con los identificadores leídos (sin 303/304 en cascada, 2.20).
    // Un solo error de gramática por comando: tras él, Resincronizar. Sin 201 (S2)
    private Statement ParseDeclaracion() {
        Token tipo = currentToken;
        Declaracion declaracion = new Declaracion(tipo);
        // 209: declaración después del primer comando (REQ-STRUCT-03)
        if (seccion == Seccion.COMANDOS) {
            ErrorSintactico(209, "Declaración después del primer comando", tipo);
        }
        AvanzarEnComando();
        // true cuando se espera un identificador (al inicio o tras una coma)
        boolean esperaIdent = true;
        // Última coma leída (lexema del 212 si la lista termina tras ella)
        Token coma = null;
        while (true) {
            MingolToken t = currentToken.getTokenType();
            if (esperaIdent) {
                if (t == MingolToken.IDENT) {
                    // Identificador de la lista: un LetStatement sin valor
                    declaracion.AddVariable(new LetStatement(tipo,
                            new Identifier(currentToken, currentToken.getLiteral()), null));
                    esperaIdent = false;
                    AvanzarEnComando();
                    continue;
                }
                if (t == MingolToken.COMMA) {
                    // Coma al inicio o tras otra coma: 212 con esa coma
                    ErrorSintactico(212, "Coma colgando en la lista", currentToken);
                    Resincronizar();
                    return declaracion;
                }
                if (coma != null && EsFinDeComando(currentToken)) {
                    // Coma seguida del fin del comando: 212 con esa coma
                    ErrorSintactico(212, "Coma colgando en la lista", coma);
                    Resincronizar();
                    return declaracion;
                }
                // Sin identificador donde se esperaba: 204 IDENT con el token obtenido
                Error204(MingolToken.IDENT, currentToken);
                Resincronizar();
                return declaracion;
            }
            if (t == MingolToken.COMMA) {
                coma = currentToken;
                esperaIdent = true;
                AvanzarEnComando();
                continue;
            }
            if (t == MingolToken.IDENT) {
                // Dos identificadores sin coma: 211 con el segundo
                ErrorSintactico(211, "Falta la coma entre dos elementos de la lista", currentToken);
                Resincronizar();
                return declaracion;
            }
            if (t == MingolToken.ASSIGN) {
                // 210 (S2): la variable queda declarada; el valor se recorre solo por
                // los 1xx de L4 y se descarta (no llega al evaluador, DS2)
                ErrorSintactico(210, "Declaración con valor inicial", currentToken);
                AvanzarEnComando();
                ParseSecuencia(ModoSecuencia.EXPRESION);
                Resincronizar();
                return declaracion;
            }
            // Fin de la lista: ';', FIN, EOF, cierre u otro token (204 SEMICOLON)
            TerminarComando();
            return declaracion;
        }
    }

    // ';', FIN (EOL no transparente), EOF o cierre: el comando terminó
    private boolean EsFinDeComando(Token t) {
        MingolToken tipo = t.getTokenType();
        return tipo == MingolToken.SEMICOLON || tipo == MingolToken.EOL
                || tipo == MingolToken.EOF || EsCierre(t);
    }

    // Cambio 12: asignación "variable := expresión" con la expresión como Secuencia (S3)
    private Statement ParseAsignacion() {
        Token tokenVariable = currentToken;
        Identifier variable = new Identifier(tokenVariable, tokenVariable.getLiteral());
        // Se salta el ':=' (ParseSatement ya comprobó que es el token siguiente)
        AvanzarEnComando();
        AvanzarEnComando();
        Secuencia expresion = ParseSecuencia(ModoSecuencia.EXPRESION);
        Asignacion asignacion = new Asignacion(tokenVariable, variable, expresion);
        TerminarComando();
        return asignacion;
    }

    // Modos de ParseSecuencia: expresión de una asignación o condición de un IF
    private enum ModoSecuencia { EXPRESION, CONDICION }

    // Cambio 13 (S3): recorre una expresión o condición como secuencia de tokens,
    // sin gramática y sin 203. Empieza en el token actual y lo deja en el que la
    // termina, sin consumirlo: EXPRESION en ';', FIN, EOF o cierre; CONDICION
    // además en THEN o en el ')' que deja la profundidad en 0
    private Secuencia ParseSecuencia(ModoSecuencia modo) {
        Secuencia secuencia = new Secuencia(currentToken);
        int profundidad = 0;
        // Los dos tokens anteriores de la secuencia (regla de 'negada', L4)
        Token previo = null;
        Token antePrevio = null;
        while (!EsFinDeComando(currentToken)) {
            Token t = currentToken;
            MingolToken tipo = t.getTokenType();
            if (modo == ModoSecuencia.CONDICION) {
                if (tipo == MingolToken.THEN) {
                    break;
                }
                if (tipo == MingolToken.LPAREN) {
                    profundidad++;
                } else if (tipo == MingolToken.RPAREN) {
                    if (profundidad == 0) {
                        break;
                    }
                    profundidad--;
                }
            }
            // '-' unario delante de la constante: mismo criterio que el Pratt (L4)
            boolean negada = previo != null && previo.getTokenType() == MingolToken.SUBSTRACTION
                    && (antePrevio == null || !EsOperandoPrevio(antePrevio));
            switch (tipo) {
                case IDENT:
                    secuencia.AddElemento(new Identifier(t, t.getLiteral()));
                    break;
                case INTEGER: {
                    Integral entero = ConstanteEntera(t, negada);
                    if (entero != null) {
                        secuencia.AddElemento(entero);
                    }
                    break;
                }
                case REAL: {
                    Decimal decimal = ConstanteReal(t, negada);
                    if (decimal != null) {
                        secuencia.AddElemento(decimal);
                    }
                    break;
                }
                case STRING:
                    secuencia.AddElemento(new StringLiteral(t, t.getLiteral()));
                    break;
                case ALGOL:
                    if (EsBooleano(t)) {
                        // L7: TRUE/FALSE dentro de una expresión son Booleano
                        secuencia.AddElemento(new Booleano(t, t.getLiteral().equals("TRUE")));
                    } else {
                        // Otra palabra ALGOL: se guarda, sin advertencia (2.5)
                        secuencia.AddPalabraAlgol(t);
                    }
                    break;
                default:
                    // Operadores, paréntesis, comas y el resto se ignoran
                    break;
            }
            antePrevio = previo;
            previo = t;
            AvanzarEnComando();
        }
        return secuencia;
    }

    // TRUE/FALSE (el lexer los da como ALGOL, L7)
    private boolean EsBooleano(Token t) {
        return t.getTokenType() == MingolToken.ALGOL
                && (t.getLiteral().equals("TRUE") || t.getLiteral().equals("FALSE"));
    }

    // Token tras el cual un '-' es binario (no niega la constante siguiente, L4)
    private boolean EsOperandoPrevio(Token t) {
        switch (t.getTokenType()) {
            case IDENT:
            case INTEGER:
            case REAL:
            case STRING:
            case RPAREN:
                return true;
            default:
                return EsBooleano(t);
        }
    }

    // Cambio 14: IF ( condición ) THEN / bloque / [ELSE / bloque] / FI.
    // Devuelve siempre el nodo (conserva la estructura: sin 213 en el FI)
    private Statement ParseIfComando() {
        Token tokenIf = currentToken;
        // S7: IF dentro de otro IF → 214, y se analiza igual
        if (ifsAbiertos > 0) {
            ErrorSintactico(214, "IF anidado dentro de otro IF", tokenIf);
        }
        If nodo = new If(tokenIf);
        // Cabecera multilínea: el primer faltante da un 204 y no hay más mensajes
        boolean faltante = false;
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.LPAREN) {
            Error204(MingolToken.LPAREN, currentToken);
            faltante = true;
        } else {
            AvanzarEnComando();
            // Condición como secuencia, sin gramática (S3)
            nodo.setCondition(ParseSecuencia(ModoSecuencia.CONDICION));
            if (currentToken.getTokenType() != MingolToken.RPAREN) {
                Error204(MingolToken.RPAREN, currentToken);
                faltante = true;
            } else {
                AvanzarEnComando();
                if (currentToken.getTokenType() != MingolToken.THEN) {
                    Error204(MingolToken.THEN, currentToken);
                    faltante = true;
                }
            }
        }
        if (faltante) {
            SaltarCabecera(MingolToken.THEN);
        }
        // S9: lo que sigue a THEN en la misma línea es el comienzo del cuerpo
        if (currentToken.getTokenType() == MingolToken.THEN) {
            FinDeCabecera(MingolToken.THEN);
        }
        Block consecuencia = ParseBlock(MingolToken.IF, MingolToken.ELSE, MingolToken.FI);
        nodo.setConsequence(consecuencia);
        if (currentToken.getTokenType() == MingolToken.ELSE) {
            // 216: THEN sin comandos antes del ELSE (DS1: ALGOL no cuenta)
            if (comandosDelUltimoBloque == 0) {
                ErrorSintactico(216, "Bloque sin comandos antes de ELSE", currentToken);
            }
            NadaMasEnLaLinea(MingolToken.ELSE);
            nodo.setAlternative(ParseBlock(MingolToken.IF, MingolToken.FI));
        }
        CerrarBloque(MingolToken.FI);
        return new ExpressionStatement(tokenIf, nodo);
    }

    // Cambio 15: FOR ident FROM v BY v TO v DO / bloque / OD, con v = INTEGER o IDENT.
    // Devuelve siempre el nodo (conserva la estructura: sin 213 en el OD)
    private Statement ParseForComando() {
        Token tokenFor = currentToken;
        // S7: FOR dentro de otro FOR → 215, y se analiza igual
        if (forsAbiertos > 0) {
            ErrorSintactico(215, "FOR anidado dentro de otro FOR", tokenFor);
        }
        For nodo = new For(tokenFor);
        // Cabecera multilínea: el primer faltante da un 204 y no hay más mensajes
        boolean faltante = false;
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.IDENT) {
            Error204(MingolToken.IDENT, currentToken);
            faltante = true;
        } else {
            nodo.setCiclo(new Identifier(currentToken, currentToken.getLiteral()));
            AvanzarEnComando();
        }
        // FROM, BY y TO, cada uno con su valor
        MingolToken[] palabras = { MingolToken.FROM, MingolToken.BY, MingolToken.TO };
        for (int k = 0; k < palabras.length && !faltante; k++) {
            if (currentToken.getTokenType() != palabras[k]) {
                Error204(palabras[k], currentToken);
                faltante = true;
                break;
            }
            AvanzarEnComando();
            Expression valor;
            if (currentToken.getTokenType() == MingolToken.INTEGER) {
                // Rango L4 sin '-' unario; null solo si la conversión falla (102)
                valor = ConstanteEntera(currentToken, false);
            } else if (currentToken.getTokenType() == MingolToken.IDENT) {
                valor = new Identifier(currentToken, currentToken.getLiteral());
            } else {
                Error204(MingolToken.INTEGER, currentToken);
                faltante = true;
                break;
            }
            if (k == 0) {
                nodo.setMinima(valor);
            } else if (k == 1) {
                nodo.setIncremento(valor);
            } else {
                nodo.setMaxima(valor);
            }
            AvanzarEnComando();
        }
        if (!faltante && currentToken.getTokenType() != MingolToken.DO) {
            Error204(MingolToken.DO, currentToken);
            faltante = true;
        }
        if (faltante) {
            SaltarCabecera(MingolToken.DO);
        }
        // S9 simétrico: lo que sigue a DO en la misma línea es el comienzo del cuerpo
        if (currentToken.getTokenType() == MingolToken.DO) {
            FinDeCabecera(MingolToken.DO);
        }
        nodo.setComands(ParseBlock(MingolToken.FOR, MingolToken.OD));
        CerrarBloque(MingolToken.OD);
        return new ExpressionStatement(tokenFor, nodo);
    }

    // Tras el 204 de una cabecera: avanza sin mensajes hasta THEN/DO, ';', FIN o EOF.
    // Un ';' se descarta (sin 205 en cascada en el cuerpo)
    private void SaltarCabecera(MingolToken fin) {
        while (currentToken.getTokenType() != fin
                && currentToken.getTokenType() != MingolToken.SEMICOLON
                && currentToken.getTokenType() != MingolToken.EOL
                && currentToken.getTokenType() != MingolToken.EOF) {
            AvanzarEnComando();
        }
        if (currentToken.getTokenType() == MingolToken.SEMICOLON) {
            AdvanceToken();
        }
    }

    // Cierre de IF/FOR (FI u OD): 216 si el último bloque quedó vacío, se consume y
    // TerminarComando (salvo DS10: sobrante sin ';' en la misma línea → 202);
    // si el bloque terminó en otra cosa, 204 con ese token sin
    // consumirlo (con EOF, la cadena de hoy "... pero se obtuvo un EOF")
    private void CerrarBloque(MingolToken cierre) {
        if (currentToken.getTokenType() == cierre) {
            if (comandosDelUltimoBloque == 0) {
                ErrorSintactico(216, "Bloque sin comandos antes de " + cierre, currentToken);
            }
            Token tokenCierre = currentToken;
            AvanzarEnComando();
            // DS10: algo distinto de ';' tras FI/OD en su misma línea → un 202 con
            // el primer sobrante y se salta la línea física (sin cascada)
            MingolToken tipo = currentToken.getTokenType();
            if (tipo != MingolToken.SEMICOLON && tipo != MingolToken.EOL && tipo != MingolToken.EOF
                    && currentToken.getLine() == tokenCierre.getLine()) {
                Error202(cierre, currentToken);
                SaltarLineaFisica();
                return;
            }
            TerminarComando();
            return;
        }
        Error204(cierre, currentToken);
    }

    // Cambio 17: read ( IDENT ). El primer token inesperado da un 204 y no hay nodo
    // (error de gramática); los de terminación no descartan el nodo (DS7)
    private Statement ParseReadComando() {
        Token tokenRead = currentToken;
        Read read = new Read(tokenRead);
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.LPAREN) {
            return ErrorDeGramatica(MingolToken.LPAREN);
        }
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.IDENT) {
            return ErrorDeGramatica(MingolToken.IDENT);
        }
        read.setIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.RPAREN) {
            return ErrorDeGramatica(MingolToken.RPAREN);
        }
        AvanzarEnComando();
        TerminarComando();
        return new ExpressionStatement(tokenRead, read);
    }

    // Cambio 17: print ( (lista) ), print ( newline ) o print ( "texto" ).
    // Errores de gramática (204, 211, 212) sin nodo; de terminación, con nodo (DS7)
    private Statement ParsePrintComando() {
        Token tokenPrint = currentToken;
        Print print = new Print(tokenPrint);
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.LPAREN) {
            return ErrorDeGramatica(MingolToken.LPAREN);
        }
        AvanzarEnComando();
        switch (currentToken.getTokenType()) {
            case LPAREN:
                // Lista de variables con las reglas de la declaración (211, 212)
                if (!ParseListaDePrint(print)) {
                    return null;
                }
                break;
            case NEWLINE:
                // Nodo newline con su propio token
                print.setNewline(new Expression(currentToken));
                break;
            case STRING:
                // Texto literal (el del token STRING)
                print.setTexto(currentToken.getLiteral());
                break;
            default:
                // Ni lista, ni newline, ni texto: 204 LPAREN con el token (s21, s22)
                return ErrorDeGramatica(MingolToken.LPAREN);
        }
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.RPAREN) {
            return ErrorDeGramatica(MingolToken.RPAREN);
        }
        AvanzarEnComando();
        TerminarComando();
        return new ExpressionStatement(tokenPrint, print);
    }

    // Lista de print desde su '(' hasta su ')' (deja el currentToken en el ')').
    // 212 con coma inicial, doble o final; 211 sin coma; '( )' → 204 IDENT [)];
    // otro token tras un identificador → 204 RPAREN. Con error: Resincronizar y false
    private boolean ParseListaDePrint(Print print) {
        AvanzarEnComando();
        // true cuando se espera un identificador (al inicio o tras una coma)
        boolean esperaIdent = true;
        // Última coma leída (lexema del 212 si la lista termina tras ella)
        Token coma = null;
        while (true) {
            MingolToken t = currentToken.getTokenType();
            if (esperaIdent) {
                if (t == MingolToken.IDENT) {
                    print.AddIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
                    esperaIdent = false;
                    AvanzarEnComando();
                    continue;
                }
                if (t == MingolToken.COMMA) {
                    // Coma al inicio o tras otra coma
                    ErrorSintactico(212, "Coma colgando en la lista", currentToken);
                    Resincronizar();
                    return false;
                }
                if (coma != null && (t == MingolToken.RPAREN || EsFinDeComando(currentToken))) {
                    // Coma seguida del cierre de la lista o del fin del comando
                    ErrorSintactico(212, "Coma colgando en la lista", coma);
                    Resincronizar();
                    return false;
                }
                // Sin identificador donde se esperaba (incluye '( )')
                ErrorDeGramatica(MingolToken.IDENT);
                return false;
            }
            if (t == MingolToken.COMMA) {
                coma = currentToken;
                esperaIdent = true;
                AvanzarEnComando();
                continue;
            }
            if (t == MingolToken.IDENT) {
                // Dos identificadores sin coma: 211 con el segundo
                ErrorSintactico(211, "Falta la coma entre dos elementos de la lista", currentToken);
                Resincronizar();
                return false;
            }
            if (t == MingolToken.RPAREN) {
                return true;
            }
            // Otro token tras un identificador: falta el ')' de la lista
            ErrorDeGramatica(MingolToken.RPAREN);
            return false;
        }
    }

    // Error de gramática de read/print/GOTO: 204 con el token obtenido,
    // Resincronizar y sin nodo (devuelve null)
    private Statement ErrorDeGramatica(MingolToken esperado) {
        Error204(esperado, currentToken);
        Resincronizar();
        return null;
    }

    // Cambio 17: GOTO IDENT. Sin identificador → 204 IDENT sin nodo; un error de
    // terminación conserva el Goto (s24: 303 del destino)
    private Statement ParseGotoComando() {
        Token tokenGoto = currentToken;
        Goto gouto = new Goto(tokenGoto);
        AvanzarEnComando();
        if (currentToken.getTokenType() != MingolToken.IDENT) {
            return ErrorDeGramatica(MingolToken.IDENT);
        }
        gouto.setIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
        AvanzarEnComando();
        TerminarComando();
        return new ExpressionStatement(tokenGoto, gouto);
    }

    // Cambio 17 (REQ-CMD-07): SKIP y TerminarComando, sin avance de más
    // (SKIP; antes de END da 206 y el END queda reconocido: sin 308 falso)
    private Statement ParseSkipComando() {
        Token tokenSkip = currentToken;
        AvanzarEnComando();
        TerminarComando();
        return new ExpressionStatement(tokenSkip, new Skip(tokenSkip));
    }

    // Cambio 17 (REQ-LABEL-01): nodo Etiqueta como hoy; nada más en la línea (202)
    private Statement ParseEtiquetaComando() {
        Token tokenEtiqueta = currentToken;
        Etiqueta etiqueta = new Etiqueta(tokenEtiqueta, tokenEtiqueta.getLiteral());
        NadaMasEnLaLinea(MingolToken.ETIQUETA);
        return new ExpressionStatement(tokenEtiqueta, etiqueta);
    }

    // Cambio 17 (REQ-ALGOL-01): instrucción ALGOL en posición de comando (también
    // TRUE/FALSE, L7). Misma advertencia y cadena que ParseAlgol, en la línea del
    // token; se ignora el resto de la línea física
    private Statement ParseAlgolComando() {
        Token tokenAlgol = currentToken;
        errors.add("\tLinea "+ tokenAlgol.getLine() +" ADVERTENCIA: Instruccion " + tokenAlgol.getLiteral() + " no soportada por esta version");
        mensajes.add(Mensaje.Advertencia(tokenAlgol.getLine(), tokenAlgol.getLiteral()));
        SaltarLineaFisica();
        return new ExpressionStatement(tokenAlgol, new Expression(tokenAlgol));
    }

    public Parser(Lexer lexer) {
        this(lexer, null, null);
    }

    public Program ParseProgram() {
        Program program = new Program(new ArrayList<Statement>());

        // Cambio 7: un comando por llamada; las líneas vacías se saltan
        while (true) {
            SaltarLineasVacias();
            if (currentToken.getTokenType() == MingolToken.EOF) {
                break;
            }
            int antes = indiceActual;
            Statement statement = ParseSatement();
            if (statement != null) {
                program.statements.add(statement);
            }
            // Garantía de avance: nunca se repite el mismo token (sin cuelgues)
            if (indiceActual == antes) {
                AdvanceToken();
            }
        }

        // Los errores léxicos (1xx) también invalidan el programa
        if (lexer.HayErrores()) {
            hasErrors = true;
        }
        return program;
    }

    public void AdvanceToken() {
        if(currentToken != null && currentToken.getTokenType().equals(MingolToken.EOL)){
            line++;
        }
        // Único lugar donde avanza el flujo (ya no guarda el token anterior, tarea 5)
        // Cambio 1: los tokens salen de la lista cargada (tras el EOF se repite el EOF)
        indiceActual++;
        // 101 de los caracteres ilegales que se acaban de pasar
        RegistrarIlegales();
        currentToken = TokenEn(indiceActual);
        peekToken = TokenEn(indiceActual + 1);
    }

    // Tarea 5 (DS5): se borró EsInicioDeComando; la posición de comando es tokenDeComando

    // Tarea 5 (DS5): se borró checkSemicolon; el 202 lo dan NadaMasEnLaLinea/FinDeCabecera

    private Precedence CurrentPrecedence() {
        assert currentToken != null :
                "current token es null";
        Precedence precedence = PRECEDENCES.get(currentToken.getTokenType());
        if (precedence == null) {
            return Precedence.LOWEST;
        }
        return precedence;
    }

    private void ExpectedTokenError(MingolToken token) {
        // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
        String error = "\tLinea "+ line +" Error 204: Se esperaba un token de tipo " + token
                + " pero se obtuvo un " + peekToken.getTokenType();
        errors.add(error);
        // Mensaje 204 con el token siguiente (el obtenido) como lexema
        mensajes.add(Mensaje.Error(line, 204, "Se esperaba un token de tipo " + token
                + " pero se obtuvo " + peekToken.getTokenType(), Lexema(peekToken)));
        hasErrors = true;
    }

    // Constante entera con el rango de L4 (la usan ParseInteger, ParseSecuencia y ParseForComando).
    // Magnitud > 32768: el lexer ya dio el 108. 32768 sin '-' unario: 108 aquí.
    // Nunca devuelve null por rango (sin 201 en cascada); solo por una conversión fallida (102).
    private Integral ConstanteEntera(Token t, boolean negada) {
        Integral integral = new Integral(t);
        try {
            BigInteger m = new BigInteger(t.getLiteral());
            if (m.compareTo(Lexer.MAX_MAGNITUD_ENTERA) == 0 && !negada) {
                // Mensaje 108 (léxico) en la línea física de la constante
                errors.add("\tLinea "+ t.getLine() +" Error 108: Constante entera fuera de rango: " + t.getLiteral());
                mensajes.add(Mensaje.Error(t.getLine(), 108, "Constante entera fuera de rango", t.getLiteral()));
                hasErrors = true;
            }
            // Valor acotado a int para no desbordar
            integral.setValue(m.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue());
        } catch (NumberFormatException e) {
            // Se conserva el 102 controlado para una conversión que no sea de rango (3.8)
            errors.add("\tLinea "+ line +" Error 203: conversion invalida a entero: " + t.getLiteral());
            // Mensaje 102 (léxico): constante no convertible a entero
            mensajes.add(Mensaje.Error(line, 102, "Constante numérica no convertible a entero", t.getLiteral()));
            hasErrors = true;
            return null;
        }
        return integral;
    }

    // Constante real con el rango de L4 (la usan el prefijo ParseReal y ParseSecuencia).
    // Mismo cuerpo que tenía ParseReal: 110 en la línea de la constante; 102 controlado
    private Decimal ConstanteReal(Token t, boolean negada) {
        Decimal decimal = new Decimal(t);
        try {
            // Rango con '-' unario (L4); > 32768.99 ya lo reportó el lexer (no se duplica)
            BigDecimal m = new BigDecimal(t.getLiteral());
            if (m.compareTo(Lexer.MAX_MAGNITUD_REAL) <= 0
                    && m.compareTo(negada ? Lexer.MAX_REAL_NEGATIVO : Lexer.MAX_REAL_POSITIVO) > 0) {
                // Mensaje 110 (léxico) en la línea física de la constante
                errors.add("\tLinea "+ t.getLine() +" Error 110: Constante real fuera de rango: " + t.getLiteral());
                mensajes.add(Mensaje.Error(t.getLine(), 110, "Constante real fuera de rango", t.getLiteral()));
                hasErrors = true;
            }
            decimal.setValue(Double.parseDouble(t.getLiteral()));
        } catch (NumberFormatException e) {
            // parseDouble lanza NumberFormatException (no ClassCastException); antes no se capturaba
            // y abortaba el compilador. Ahora se registra como error controlado (antes decia "entero").
            errors.add("\tLinea "+ line +" Error 203: conversion invalida a real: " + t.getLiteral());
            // Mensaje 102 (léxico): constante no convertible a real
            mensajes.add(Mensaje.Error(line, 102, "Constante numérica no convertible a real", t.getLiteral()));
            hasErrors = true;
            return null;
        }
        return decimal;
    }

    // Igual que ExpectedTokenError, pero informa el token actual (el que ocupa el lugar del esperado)
    private void ExpectedCurrentTokenError(MingolToken token) {
        String error = "\tLinea "+ line +" Error 204: Se esperaba un token de tipo " + token
                + " pero se obtuvo un " + currentToken.getTokenType();
        errors.add(error);
        // Mensaje 204 con el token actual como lexema (EOF al final del archivo, con línea n+1)
        mensajes.add(Mensaje.Error(line, 204, "Se esperaba un token de tipo " + token
                + " pero se obtuvo " + currentToken.getTokenType(), Lexema(currentToken)));
        hasErrors = true;
    }

    // Lexema para el corchete: nunca un salto de línea ni vacío
    private String Lexema(Token t) {
        if ("\n".equals(t.getLiteral())) {
            return "EOL";
        }
        if (t.getLiteral() == null || t.getLiteral().isEmpty()) {
            return t.getTokenType().toString();
        }
        return t.getLiteral();
    }

    private boolean ExpectedToken(MingolToken token) {
        if (peekToken.getTokenType() == token) {
            AdvanceToken();
            return true;
        }
        ExpectedTokenError(token);
        return false;
    }

    public ArrayList<String> GetErrors() {
        return errors;
    }

    // Lista nueva: primero los mensajes del lexer y después los del parser
    // (los del parser siguen en el mismo orden que GetErrors())
    public ArrayList<Mensaje> GetMensajes() {
        ArrayList<Mensaje> todos = new ArrayList<>(lexer.GetMensajes());
        todos.addAll(mensajes);
        return todos;
    }

    private void JumpEOL() {
        while(currentToken.getTokenType().equals(MingolToken.EOL)){
            AdvanceToken();
        }
    }

    // Tarea 5 (DS5): se borró ParseBlock() sin parámetros; queda ParseBlock(tipo, cierres)

    private ArrayList<Expression> ParseCallArguments() {
        ArrayList<Expression> arguments = new ArrayList<>();
        assert peekToken != null :
                "El peek token es null";

        if (peekToken.getTokenType().equals(MingolToken.RPAREN)) {
            AdvanceToken();
            return arguments;
        }

        AdvanceToken();
        Expression expression = ParseExpression(Precedence.LOWEST);

        if (expression != null) {
            arguments.add(expression);
        }

        while (peekToken.getTokenType() == MingolToken.COMMA) {
            AdvanceToken();
            AdvanceToken();
            expression = ParseExpression(Precedence.LOWEST);
            if (expression != null) {
                arguments.add(expression);
            }
        }

        if (!ExpectedToken(MingolToken.RPAREN)) {
            return null;
        }
        return arguments;
    }

    private Expression ParseExpression(Precedence precedence) {
        assert currentToken != null :
                "El current token es null";
        IPrefixParseFn prefixParseFn = prefixParseFns.get(currentToken.getTokenType());
        if (prefixParseFn == null) {
            // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
            String mensaje = "\tLinea "+ line +" Error 203: Token inválido: " + currentToken.getLiteral();
            errors.add(mensaje);
            // Mensaje 203: token que no puede iniciar una expresión
            mensajes.add(Mensaje.Error(line, 203, "Token que no puede iniciar una expresión", Lexema(currentToken)));
            hasErrors = true;
            return null;
        }
        Expression leftExpression = prefixParseFn.Function();
        // El prefijo ya registró su error: no se siguen aplicando infijos sobre null
        if (leftExpression == null) {
            return null;
        }

        assert peekToken != null :
                "Peek token es null";

        while ((peekToken.getTokenType() != MingolToken.SEMICOLON)
                && (precedence.ordinal() < PeekPrecedence().ordinal())) {
            IInfixParseFn infixParseFn = infixParseFns.get(peekToken.getTokenType());

            if (infixParseFn == null) {
                return leftExpression;
            }

            AdvanceToken();
            assert leftExpression != null :
                    "left expression es null";
            leftExpression = infixParseFn.Function(leftExpression);
        }
        return leftExpression;
    }

    // Tarea 5 (DS5): se borraron ParseExpressionStatement y ParseLetStatement (sin referencias);
    // los reemplazan ParseDeclaracion, ParseAsignacion y los Parse*Comando

    private ReturnStatement ParseReturnStatement() {
        assert currentToken != null :
                "El current token es null";
        ReturnStatement returnStatement = new ReturnStatement(currentToken);
        AdvanceToken();
        while (currentToken.getTokenType() != MingolToken.SEMICOLON) {
            AdvanceToken();
        }
        return returnStatement;
    }

    private Statement ParseSatement() {
        assert currentToken != null :
                "El current token es null";
        // Cambio 8: analiza UN comando, que empieza en el token actual
        SaltarLineasVacias();
        // Si cuenta para 216/217 se decide antes de despachar: los comandos de un
        // bloque interno (IF/FOR) no deben pisar el valor de este (DS1)
        boolean cuenta = CuentaComoComando();
        Statement resultado = ParseComando();
        ultimoCuenta = cuenta;
        return resultado;
    }

    // DS1: cuentan todos menos BEGIN/END, declaraciones, instrucciones ALGOL
    // ignoradas y las líneas fuera de BEGIN...END (207/208)
    private boolean CuentaComoComando() {
        MingolToken tipo = currentToken.getTokenType();
        if (tipo == MingolToken.EOF || tipo == MingolToken.BEGIN || tipo == MingolToken.END) {
            return false;
        }
        if (seccion == Seccion.ANTES_DE_BEGIN || seccion == Seccion.DESPUES_DE_END) {
            return false;
        }
        return tipo != MingolToken.TYPEINT && tipo != MingolToken.TYPEREAL
                && tipo != MingolToken.TYPECHAR && tipo != MingolToken.ALGOL;
    }

    // Cuerpo de ParseSatement: un comando según su primer token
    private Statement ParseComando() {
        if (currentToken.getTokenType() == MingolToken.EOF) {
            return null;
        }
        // Posición de comando: solo el primer token del comando (2.22)
        tokenDeComando = currentToken;
        // Comando nuevo: una línea física no vacía (límite de 5 de AvanzarEnComando)
        lineasDelComando = 1;
        // L2: un IDENT que no va seguido de ':=' es un comando, no un identificador: sin 103
        if (currentToken.getTokenType() == MingolToken.IDENT
                && peekToken.getTokenType() != MingolToken.ASSIGN) {
            lexer.RetirarMayusculas(currentToken);
        }
        MingolToken tipo = currentToken.getTokenType();

        // S6: fuera de BEGIN...END, un 207/208 por línea (también ALGOL) y se salta la línea
        if (tipo != MingolToken.BEGIN && tipo != MingolToken.END
                && (seccion == Seccion.ANTES_DE_BEGIN || seccion == Seccion.DESPUES_DE_END)) {
            if (seccion == Seccion.ANTES_DE_BEGIN) {
                ErrorSintactico(207, "Comando antes de BEGIN", currentToken);
            } else {
                ErrorSintactico(208, "Comando después de END", currentToken);
            }
            SaltarLineaFisica();
            return null;
        }

        switch (tipo) {
            case BEGIN: {
                // El primer BEGIN abre la sección de variables; el repetido lo informa el evaluador (305)
                Token tokenBegin = currentToken;
                if (!vistoBegin) {
                    vistoBegin = true;
                    seccion = Seccion.VARIABLES;
                }
                NadaMasEnLaLinea(MingolToken.BEGIN);
                return new Statement(tokenBegin);
            }
            case END: {
                // El primer END cierra el programa; 217 si no hubo comandos desde BEGIN (DS1, DS3)
                Token tokenEnd = currentToken;
                if (!vistoEnd) {
                    vistoEnd = true;
                    if (vistoBegin && comandos == 0) {
                        ErrorSintactico(217, "Falta la sección de comandos", tokenEnd);
                    }
                    seccion = Seccion.DESPUES_DE_END;
                }
                NadaMasEnLaLinea(MingolToken.END);
                return new Statement(tokenEnd);
            }
            case TYPEINT:
            case TYPEREAL:
            case TYPECHAR:
                // Declaración: no cierra la sección de variables ni cuenta como comando (S5, DS1)
                return ParseDeclaracion();
            case ALGOL:
                // Instrucción ALGOL ignorada: no cierra la sección ni cuenta (S5, DS1)
                return ParseAlgolComando();
            case IDENT:
                if (peekToken.getTokenType() == MingolToken.ASSIGN) {
                    ComandoMingol();
                    return ParseAsignacion();
                }
                // IDENT sin ':=': no es una instrucción (S8)
                return ComandoInvalido();
            case IF:
                ComandoMingol();
                return ParseIfComando();
            case FOR:
                ComandoMingol();
                return ParseForComando();
            case READ:
                ComandoMingol();
                return ParseReadComando();
            case PRINT:
                ComandoMingol();
                return ParsePrintComando();
            case GOTO:
                ComandoMingol();
                return ParseGotoComando();
            case SKIP:
                ComandoMingol();
                return ParseSkipComando();
            case ETIQUETA:
                ComandoMingol();
                return ParseEtiquetaComando();
            case FI:
            case OD:
            case ELSE:
            case THEN:
            case DO:
            case FROM:
            case BY:
            case TO:
                // Palabra de MINGOL fuera de lugar (sin bloque abierto que la cierre): 213
                ContarComando();
                ErrorSintactico(213, "Palabra de MINGOL fuera de lugar", currentToken);
                DescartarComando();
                return null;
            case RETURN:
                // Inalcanzable (el lexer da ALGOL); se conserva (decisión 6)
                ContarComando();
                return ParseReturnStatement();
            default:
                return ComandoInvalido();
        }
    }

    // S5 y DS1: un comando MINGOL reconocido cierra la sección de variables y cuenta
    private void ComandoMingol() {
        if (seccion == Seccion.VARIABLES) {
            seccion = Seccion.COMANDOS;
        }
        ContarComando();
    }

    // DS1: cuenta para 216 (bloque) y 217 (entre el primer BEGIN y el primer END)
    private void ContarComando() {
        if (!vistoEnd) {
            comandos++;
        }
    }

    // S8: 205 con el primer token del comando y resincronización (cuenta, no cierra la sección)
    private Statement ComandoInvalido() {
        ContarComando();
        ErrorSintactico(205, "No es una instrucción válida de MINGOL ni de ALGOL", currentToken);
        DescartarComando();
        return null;
    }

    private Precedence PeekPrecedence() {
        assert peekToken != null :
                "Peek token es null";
        Precedence precedence = PRECEDENCES.get(peekToken.getTokenType());
        if (precedence == null) {
            return Precedence.LOWEST;
        }
        return precedence;
    }

    private HashMap<MingolToken, IPrefixParseFn> RegisterPrefixFns() {
        //Creación de las funciones
        //Parse identifier
        IPrefixParseFn ParseIdentifier = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                return new Identifier(currentToken, currentToken.getLiteral());
            }
        };

        IPrefixParseFn ParseEtiqueta = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                return new Etiqueta(currentToken, currentToken.getLiteral());
            }
        };

        //Parse Integer
        IPrefixParseFn ParseInteger = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                // Rango con '-' unario (L4): negada si es la constante que guardó ParsePrefixExpressions
                return ConstanteEntera(currentToken, currentToken == constanteNegada);
            }
        };

        IPrefixParseFn ParseReal = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                // El rango (L4) y el 102 pasan a ConstanteReal, que comparte con ParseSecuencia
                return ConstanteReal(currentToken, currentToken == constanteNegada);
            }
        };

        //Prefix expressions
        IPrefixParseFn ParsePrefixExpressions = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                Prefix prefixExpression = new Prefix(currentToken, currentToken.getLiteral());
                // '-' unario delante de una constante: se recuerda para el rango (L4)
                if (currentToken.getTokenType() == MingolToken.SUBSTRACTION
                        && (peekToken.getTokenType() == MingolToken.INTEGER
                            || peekToken.getTokenType() == MingolToken.REAL)) {
                    constanteNegada = peekToken;
                }
                AdvanceToken();
                prefixExpression.setRight(ParseExpression(Precedence.PREFIX));
                return prefixExpression;
            }
        };

        //Parse boolean
        IPrefixParseFn ParseBoolean = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "El current token es null";
                return new Booleano(currentToken, currentToken.getTokenType() == MingolToken.TRUE);
            }
        };

        //Parse grouped expression
        IPrefixParseFn ParseGroupedExpression = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                AdvanceToken();
                Expression expression = ParseExpression(Precedence.LOWEST);

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }
                return expression;
            }
        };

        // Tarea 5 (DS5): se borraron los prefijos ParseIf y ParseFor (sin referencias vivas);
        // los reemplazan ParseIfComando y ParseForComando

        IPrefixParseFn ParseStringLiteral = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "El current token es null";
                return new StringLiteral(currentToken, currentToken.getLiteral());
            }
        };

        // Tarea 5 (DS5): se borraron los prefijos ParseRead, ParsePrint y ParseSkip;
        // los reemplazan ParseReadComando, ParsePrintComando y ParseSkipComando

        IPrefixParseFn ParseComments = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Token thisToken = currentToken;
                do {
                    AdvanceToken();
                } while (currentToken.getTokenType() != MingolToken.EOF
                        && currentToken.getTokenType() != MingolToken.COMMENT);
                return new Expression(thisToken);
            }
        };

        IPrefixParseFn ParseAlgol = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                // L7: TRUE/FALSE fuera de posición de comando siguen siendo Booleano, sin advertencia
                String literal = currentToken.getLiteral();
                if ((literal.equals("TRUE") || literal.equals("FALSE"))
                        && currentToken != tokenDeComando) {
                    return new Booleano(currentToken, literal.equals("TRUE"));
                }
                // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
                errors.add("\tLinea "+ line +" ADVERTENCIA: Instruccion " + currentToken.getLiteral() + " no soportada por esta version");
                // Advertencia ALGOL con el literal de la instrucción como lexema
                mensajes.add(Mensaje.Advertencia(line, currentToken.getLiteral()));
                Token thisToken = currentToken;
                while (currentToken.getTokenType() != MingolToken.EOL
                        && currentToken.getTokenType() != MingolToken.EOF) {
                    AdvanceToken();
                }
                return new Expression(thisToken);
            }
        };

        // Tarea 5 (DS5): se borró el prefijo ParseGoto; lo reemplaza ParseGotoComando

        IPrefixParseFn ParseIllegal = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                // Corrección BUG 1: usa 'line' (contador real) en lugar de 'linea'.
                errors.add("\tLinea "+ line +"Error 205: Token ilegal: " + currentToken.getLiteral());
                // Mensaje 101 (léxico): carácter ilegal
                mensajes.add(Mensaje.Error(line, 101, "Carácter ilegal", Lexema(currentToken)));
                hasErrors = true;
                return null;
            }
        };
        
        // Tarea 5 (DS5): se borró el prefijo ParseEOL; los EOL los manejan SaltarLineasVacias/AvanzarEnComando

        //Se agregan las funciones (tarea 5: sin los prefijos de comando borrados)
        HashMap<MingolToken, IPrefixParseFn> functions = new HashMap<MingolToken, IPrefixParseFn>();
        functions.put(MingolToken.IDENT, ParseIdentifier);
        functions.put(MingolToken.ETIQUETA, ParseEtiqueta);
        functions.put(MingolToken.INTEGER, ParseInteger);
        functions.put(MingolToken.REAL, ParseReal);
        functions.put(MingolToken.SUBSTRACTION, ParsePrefixExpressions);
        functions.put(MingolToken.NEGATION, ParsePrefixExpressions);
        functions.put(MingolToken.TRUE, ParseBoolean);
        functions.put(MingolToken.FALSE, ParseBoolean);
        functions.put(MingolToken.LPAREN, ParseGroupedExpression);
        functions.put(MingolToken.STRING, ParseStringLiteral);
        functions.put(MingolToken.ILLEGAL, ParseIllegal);
        functions.put(MingolToken.ALGOL, ParseAlgol);
        functions.put(MingolToken.COMMENT, ParseComments);
        return functions;
    }

    private HashMap<MingolToken, IInfixParseFn> RegisterInfixFns() {
        //Funcion
        IInfixParseFn ParseInfixExpression = new IInfixParseFn() {
            @Override
            public Expression Function(Expression expression) {
                assert currentToken != null :
                        "El current token es null";
                Infix infix = new Infix(currentToken, expression, currentToken.getLiteral());
                Precedence precedence = CurrentPrecedence();
                AdvanceToken();
                infix.setRight(ParseExpression(precedence));
                return infix;
            }
        };

        IInfixParseFn ParseCall = new IInfixParseFn() {
            @Override
            public Expression Function(Expression expression) {
                assert currentToken != null :
                        "El current token es null";
                Call call = new Call(currentToken, expression);

                call.setArguments(ParseCallArguments());

                return call;
            }
        };

        //Se agregan las funciones (tarea 5: sin los prefijos de comando borrados)
        HashMap<MingolToken, IInfixParseFn> functions = new HashMap<MingolToken, IInfixParseFn>();
        functions.put(MingolToken.EQ, ParseInfixExpression);
        functions.put(MingolToken.NE, ParseInfixExpression);
        functions.put(MingolToken.LT, ParseInfixExpression);
        functions.put(MingolToken.GT, ParseInfixExpression);
        functions.put(MingolToken.LE, ParseInfixExpression);
        functions.put(MingolToken.GE, ParseInfixExpression);
        functions.put(MingolToken.ADDITION, ParseInfixExpression);
        functions.put(MingolToken.SUBSTRACTION, ParseInfixExpression);
        functions.put(MingolToken.DIVISION, ParseInfixExpression);
        functions.put(MingolToken.MULTIPLICATION, ParseInfixExpression);
        functions.put(MingolToken.LPAREN, ParseCall);
        return functions;
    }
}
