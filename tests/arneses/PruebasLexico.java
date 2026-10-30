// Arnés de la spec mingol-lexico-fix (área 2: analizador léxico).
// Clase sin paquete, como PruebasArchivoErrores y PruebasCriticos. Se compila contra build\classes y se
// ejecuta desde una carpeta temporal ($env:TEMP\mingol-lexico): todos los archivos se crean ahí.
//
// Modos:
//   exploracion   -> tarea 1: Property 1 (Bug Condition) sobre los casos 1..9 del diseño
//                    (Exploratory Bug Condition Checking). Se espera que FALLE en el código sin arreglar.
//   hijotp <archivo>  -> interno: TestProyecto por reflexión (nunca main) sobre un caso, en su carpeta.
//   hijofor <archivo> -> interno: ParseProgram del FOR con 99999999999 (caso 9), por si aborta.
//   capturar [--sobrescribir] -> tarea 2: guarda en %TEMP%\mingol-lexico-golden el comportamiento del código
//                    sin arreglar (tokens, GetMensajes, GetErrors, hasErrors, TestProyecto, -errores.txt).
//   preservacion  -> tarea 2: Property 2 y 3 contra esa captura (no la regenera).
//   hijocap <archivo> <0|1> -> interno: captura de un caso (1 = con tokens), en su carpeta.
//   unitarias     -> tarea 3.13: un caso por cláusula 2.1–2.19 con sus bordes, L2, L7, comentarios,
//                    API del lexer, FOR (hijo) y TestProyecto sobre c1–c10 (hijo).
//   propiedades   -> tarea 3.13: Property 1 por familia con oráculo propio, Property 2 contra el
//                    tokenizador de referencia y hasErrors = existe ERROR (300 semillas cada una).
//
// Seguridad (R8): TestProyecto y el FOR corren en un hijo con la misma java.exe, --enable-preview,
// -Xmx256m, el mismo classpath (y -ea si el padre lo usa), waitFor(10 s) y destroyForcibly.
import Errores.Mensaje;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Parser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

public class PruebasLexico {

    // Límite de tiempo por hijo (R8)
    private static final long LIMITE_SEGUNDOS = 10;

    // Tope de tokens por fuente: protege contra un lexer que no llegue nunca a EOF
    private static final int MAX_TOKENS = 5000;

    // Sangría exacta de los mensajes en el -errores.txt (área 1)
    private static final String S8 = "        ";

    // Resultados del modo actual
    private static final List<String> resumen = new ArrayList<>();
    private static int fallas = 0;
    // Casos que no son de control y pasaron (el bug no se reprodujo)
    private static final List<String> noReproducidos = new ArrayList<>();
    // Estado por caso, para revisar las causas del diseño
    private static final Map<String, Boolean> fallo = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // Despacho por modo
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: PruebasLexico exploracion | capturar [--sobrescribir] | preservacion | unitarias | propiedades"
                    + " | hijotp <archivo> | hijofor <archivo> | hijocap <archivo> <0|1>");
            System.exit(2);
        }
        switch (args[0]) {
            case "exploracion":
                Exploracion();
                break;
            case "hijotp":
                HijoTP(args[1]);
                break;
            case "hijofor":
                HijoFor(args[1]);
                break;
            // Tarea 2: captura y preservación
            case "capturar":
                Capturar(args.length > 1 && args[1].equals("--sobrescribir"));
                break;
            case "preservacion":
                Preservacion();
                break;
            case "hijocap":
                HijoCap(args[1], args[2].equals("1"));
                break;
            // Tarea 3.13: pruebas del arreglo
            case "unitarias":
                Unitarias();
                break;
            case "propiedades":
                Propiedades();
                break;
            default:
                System.err.println("Modo desconocido: " + args[0]);
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Casos de la exploración
    // ------------------------------------------------------------------

    // Un caso: fuente y lo que se espera de él (null = no se comprueba)
    private static class Caso {
        String id;
        String grupo;              // caso 1..9 del diseño
        String[] lineas;
        boolean control;           // debe pasar también antes del arreglo (borde válido)
        List<String> tokens;       // secuencia completa sin el EOF: TIPO[literal]@línea
        List<String> contiene;     // tokens que deben aparecer (en cualquier posición)
        List<String> lexicos;      // multiconjunto exacto de 1xx: NNN@línea[lexema]
        List<Integer> prohibidos = new ArrayList<>(); // códigos que no deben aparecer en GetMensajes()
        boolean sinMensajes;       // GetMensajes() del parser vacío
        String advertencia;        // lexema de la advertencia esperada en la línea 1; "" = ninguna
        Boolean testProyecto;      // validez esperada (corre en un hijo)
        List<String[]> bajo = new ArrayList<>();        // {línea, prefijo} bajo esa línea del -errores.txt
        List<String> archivoSin = new ArrayList<>();    // textos que no deben estar en el -errores.txt
        boolean archivoSinMensajes; // -errores.txt sin ninguna línea de mensaje

        String fuente() {
            return String.join("\n", lineas) + "\n";
        }

        boolean necesitaHijo() {
            return testProyecto != null || !bajo.isEmpty() || !archivoSin.isEmpty() || archivoSinMensajes;
        }
    }

    private static Caso C(String grupo, String id, String... lineas) {
        Caso c = new Caso();
        c.grupo = grupo;
        c.id = id;
        c.lineas = lineas;
        return c;
    }

    // Token esperado en el formato del arnés
    private static String T(String tipo, String literal, int linea) {
        return tipo + "[" + Escapar(literal) + "]@" + linea;
    }

    private static String EOL(int linea) {
        return T("EOL", "\n", linea);
    }

    // Mensaje 1xx esperado
    private static String M(int codigo, int linea, String lexema) {
        return codigo + "@" + linea + "[" + lexema + "]";
    }

    private static List<String> L(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    // Tokens de "BEGIN" / "print ( newline );" / "END" a partir de la línea dada
    private static List<String> CuerpoC8(int desde) {
        return CuerpoC8(desde, true);
    }

    // Área 3 (REQ-LINE-04 corregido): la línea anterior a END no lleva ";"; con conPuntoYComa = false
    // los tokens esperados son los de "print ( newline )" sin el SEMICOLON
    private static List<String> CuerpoC8(int desde, boolean conPuntoYComa) {
        List<String> r = L(T("BEGIN", "BEGIN", desde), EOL(desde),
                T("PRINT", "print", desde + 1), T("LPAREN", "(", desde + 1), T("NEWLINE", "newline", desde + 1),
                T("RPAREN", ")", desde + 1));
        if (conPuntoYComa) r.add(T("SEMICOLON", ";", desde + 1));
        r.add(EOL(desde + 1));
        r.add(T("END", "END", desde + 2));
        r.add(EOL(desde + 2));
        return r;
    }

    private static List<Caso> Casos() {
        List<Caso> casos = new ArrayList<>();
        Caso c;

        // 1. Dígitos en identificadores (2.1)
        c = C("1", "1a factor1", "factor1");
        c.tokens = L(T("IDENT", "factor1", 1), EOL(1));
        c.lexicos = L();
        casos.add(c);
        c = C("1", "1b arctan2", "arctan2");
        c.tokens = L(T("ALGOL", "arctan2", 1), EOL(1));
        c.lexicos = L();
        casos.add(c);
        c = C("1", "1c c10", "BEGIN", "INT factor1;", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), T("TYPEINT", "INT", 2), T("IDENT", "factor1", 2),
                T("SEMICOLON", ";", 2), EOL(2), T("END", "END", 3), EOL(3));
        c.lexicos = L();
        casos.add(c);

        // 2. Reglas de identificador (2.2 a 2.5) y L2
        c = C("2", "2a Factor1 (103)", "print ( (Factor1) );");
        c.tokens = L(T("PRINT", "print", 1), T("LPAREN", "(", 1), T("LPAREN", "(", 1), T("IDENT", "Factor1", 1),
                T("RPAREN", ")", 1), T("RPAREN", ")", 1), T("SEMICOLON", ";", 1), EOL(1));
        c.lexicos = L(M(103, 1, "Factor1"));
        casos.add(c);
        c = C("2", "2b 21 caracteres (104)", "abcdefghijklmnopqrstu");
        c.tokens = L(T("IDENT", "abcdefghijklmnopqrstu", 1), EOL(1));
        c.lexicos = L(M(104, 1, "abcdefghijklmnopqrstu"));
        casos.add(c);
        c = C("2", "2c _x (105)", "_x");
        c.tokens = L(T("IDENT", "_x", 1), EOL(1));
        c.lexicos = L(M(105, 1, "_x"));
        casos.add(c);
        c = C("2", "2d x_ (105)", "x_");
        c.tokens = L(T("IDENT", "x_", 1), EOL(1));
        c.lexicos = L(M(105, 1, "x_"));
        casos.add(c);
        c = C("2", "2e 1factor (105)", "1factor");
        c.tokens = L(T("IDENT", "1factor", 1), EOL(1));
        c.lexicos = L(M(105, 1, "1factor"));
        casos.add(c);
        c = C("2", "2f print: (106)", "print:");
        c.tokens = L(T("ETIQUETA", "print", 1), EOL(1));
        c.lexicos = L(M(106, 1, "print"));
        casos.add(c);
        c = C("2", "2g RREAL factor1; sin 103 (L2)", "RREAL factor1;");
        c.tokens = L(T("IDENT", "RREAL", 1), T("IDENT", "factor1", 1), T("SEMICOLON", ";", 1), EOL(1));
        c.lexicos = L();
        casos.add(c);
        c = C("2", "2h Factor1 := 2; (103)", "Factor1 := 2;");
        c.tokens = L(T("IDENT", "Factor1", 1), T("ASSIGN", ":=", 1), T("INTEGER", "2", 1), T("SEMICOLON", ";", 1), EOL(1));
        c.lexicos = L(M(103, 1, "Factor1"));
        casos.add(c);
        c = C("2", "2i Inicio: (103)", "Inicio:");
        c.tokens = L(T("ETIQUETA", "Inicio", 1), EOL(1));
        c.lexicos = L(M(103, 1, "Inicio"));
        casos.add(c);

        // 3. Comentarios (2.6, 2.7)
        c = C("3", "3a c1", "CO abre aqui", "y cierra mal #", "BEGIN", "print ( newline );", "END");
        c.tokens = L(EOL(1), EOL(2), EOL(3), EOL(4), EOL(5));
        c.lexicos = L(M(107, 1, "CO"));
        c.testProyecto = false;
        c.bajo.add(new String[]{"1", "ERROR 107"});
        casos.add(c);
        // Área 3 (REQ-LINE-04 corregido): sin ";" antes de END, para que siga sin mensajes y válido
        c = C("3", "3b c2", "COMMENT dentro va CO suelto COMMENT", "BEGIN", "print ( newline )", "END");
        c.tokens = L(EOL(1));
        c.tokens.addAll(CuerpoC8(2, false));
        c.lexicos = L();
        c.sinMensajes = true;
        c.testProyecto = true;
        c.archivoSinMensajes = true;
        casos.add(c);
        // Área 3 (REQ-LINE-04 corregido): sin ";" antes de END, para que siga sin mensajes y válido
        c = C("3", "3c c3", "# texto con \"comilla #", "BEGIN", "print ( newline )", "END");
        c.tokens = L(EOL(1));
        c.tokens.addAll(CuerpoC8(2, false));
        c.lexicos = L();
        c.sinMensajes = true;
        c.testProyecto = true;
        c.archivoSinMensajes = true;
        casos.add(c);
        c = C("3", "3d c9", "BEGIN", "print ( newline );", "END", "CO sin cerrar");
        c.tokens = CuerpoC8(1);
        c.tokens.add(EOL(4));
        c.lexicos = L(M(107, 4, "CO"));
        c.testProyecto = false;
        c.bajo.add(new String[]{"4", "ERROR 107"});
        casos.add(c);

        // 4. Números (2.8 a 2.10, L4, L8)
        c = C("4", "4a 32768 (108, sin 201)", "INT a := 32768;");
        c.tokens = L(T("TYPEINT", "INT", 1), T("IDENT", "a", 1), T("ASSIGN", ":=", 1), T("INTEGER", "32768", 1),
                T("SEMICOLON", ";", 1), EOL(1));
        c.lexicos = L(M(108, 1, "32768"));
        c.prohibidos = new ArrayList<>(Arrays.asList(102, 201));
        casos.add(c);
        c = C("4", "4b 99999999999 (solo 108)", "INT a := 99999999999;");
        c.lexicos = L(M(108, 1, "99999999999"));
        c.prohibidos = new ArrayList<>(Arrays.asList(102, 201));
        casos.add(c);
        c = C("4", "4c -32768 valido", "INT a := -32768;");
        c.control = true;
        c.tokens = L(T("TYPEINT", "INT", 1), T("IDENT", "a", 1), T("ASSIGN", ":=", 1), T("SUBSTRACTION", "-", 1),
                T("INTEGER", "32768", 1), T("SEMICOLON", ";", 1), EOL(1));
        c.lexicos = L();
        casos.add(c);
        c = C("4", "4d - 32768 valido", "INT a := - 32768;");
        c.control = true;
        c.lexicos = L();
        casos.add(c);
        c = C("4", "4e a-32768 (108)", "INT b := a-32768;");
        c.lexicos = L(M(108, 1, "32768"));
        casos.add(c);
        c = C("4", "4f 1. (109)", "REAL r := 1.;");
        c.contiene = L(T("REAL", "1.", 1));
        c.lexicos = L(M(109, 1, "1."));
        casos.add(c);
        c = C("4", "4g .5 (109)", "REAL r := .5;");
        c.contiene = L(T("REAL", ".5", 1));
        c.lexicos = L(M(109, 1, ".5"));
        casos.add(c);
        c = C("4", "4h 32768.00 (110)", "REAL r := 32768.00;");
        c.lexicos = L(M(110, 1, "32768.00"));
        casos.add(c);
        c = C("4", "4i 32767.999 (110)", "REAL r := 32767.999;");
        c.lexicos = L(M(110, 1, "32767.999"));
        casos.add(c);
        c = C("4", "4j -32768.50 (110)", "REAL r := -32768.50;");
        c.lexicos = L(M(110, 1, "32768.50"));
        casos.add(c);
        c = C("4", "4k -32768.00 valido", "REAL r := -32768.00;");
        c.control = true;
        c.lexicos = L();
        casos.add(c);

        // 5. Textos (2.11, 2.12, L5)
        c = C("5", "5a c5", "BEGIN", "print ( \"abc );", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), T("PRINT", "print", 2), T("LPAREN", "(", 2),
                T("STRING", "abc );", 2), EOL(2), T("END", "END", 3), EOL(3));
        c.lexicos = L(M(112, 2, "\"abc );"));
        c.bajo.add(new String[]{"2", "ERROR 112"});
        c.archivoSin.add("ERROR 308");
        casos.add(c);
        String t31 = "abcdefghijklmnopqrstuvwxyz01234"; // 31 caracteres
        c = C("5", "5b c6", "BEGIN", "print ( \"" + t31 + "\" );", "END");
        c.lexicos = L(M(111, 2, t31));
        c.testProyecto = false;
        c.bajo.add(new String[]{"2", "ERROR 111"});
        casos.add(c);

        // 6. Línea de más de 80 columnas (2.13, L3); se arma con 99 columnas exactas
        String l99 = "print ( \"" + "x".repeat(28) + "\" );";
        l99 = l99 + " ".repeat(99 - l99.length());
        c = C("6", "6a c4", "BEGIN", l99, "END");
        c.lexicos = L(M(113, 2, String.valueOf(l99.length())));
        c.testProyecto = false;
        c.bajo.add(new String[]{"2", "ERROR 113"});
        casos.add(c);

        // 7. Operadores y etiquetas (2.14 a 2.16)
        c = C("7", "7a x:=3", "x:=3");
        c.tokens = L(T("IDENT", "x", 1), T("ASSIGN", ":=", 1), T("INTEGER", "3", 1), EOL(1));
        c.lexicos = L();
        casos.add(c);
        c = C("7", "7b inicio :", "inicio :");
        c.tokens = L(T("ETIQUETA", "inicio", 1), EOL(1));
        c.lexicos = L();
        casos.add(c);
        c = C("7", "7c a < = b (114)", "a < = b");
        c.tokens = L(T("IDENT", "a", 1), T("LE", "<=", 1), T("IDENT", "b", 1), EOL(1));
        c.lexicos = L(M(114, 1, "< ="));
        casos.add(c);
        c = C("7", "7d x : = 1 (114)", "x : = 1");
        c.tokens = L(T("IDENT", "x", 1), T("ASSIGN", ":=", 1), T("INTEGER", "1", 1), EOL(1));
        c.lexicos = L(M(114, 1, ": ="));
        casos.add(c);

        // 8. Tabla de reservadas (2.17, 2.18, L7): ALGOL y advertencia en posición de comando
        String[][] tabla = {
            {"ENTIER", "ENTIER x;"}, {"PRAGMA", "PRAGMA x;"}, {"grep", "grep;"},
            {"lengths", "lengths;"}, {"STRING", "STRING s;"}, {"TRUE", "TRUE;"}
        };
        for (String[] t : tabla) {
            c = C("8", "8 " + t[0], t[1]);
            c.contiene = L(T("ALGOL", t[0], 1));
            c.advertencia = t[0];
            casos.add(c);
        }
        c = C("8", "8 IF ( TRUE ) THEN sin advertencia", "IF ( TRUE ) THEN");
        c.tokens = L(T("IF", "IF", 1), T("LPAREN", "(", 1), T("ALGOL", "TRUE", 1), T("RPAREN", ")", 1),
                T("THEN", "THEN", 1), EOL(1));
        c.advertencia = "";
        casos.add(c);
        return casos;
    }

    // ------------------------------------------------------------------
    // Modo exploracion
    // ------------------------------------------------------------------

    private static void Exploracion() throws Exception {
        Path carpeta = CarpetaSegura();
        Path trabajo = carpeta.resolve("exploracion");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        // Lexer.GetMensajes() no existe en el código sin arreglar: se reporta sin abortar
        List<String> difLexer = new ArrayList<>();
        try {
            Method gm = Lexer.class.getMethod("GetMensajes");
            Object l = gm.invoke(new Lexer("x\n"));
            if (!(l instanceof List)) difLexer.add("Lexer.GetMensajes() no devuelve una lista");
        } catch (NoSuchMethodException e) {
            difLexer.add("Lexer.GetMensajes() no existe");
        }
        Registrar("0 Lexer.GetMensajes()", "0", false, difLexer);

        int n = 0;
        for (Caso c : Casos()) {
            n++;
            List<String> dif = new ArrayList<>();
            String fuente = c.fuente();

            // Tokens de Lexer.NextToken() (en proceso; el lexer actual siempre llega a EOF)
            List<String> tokens = Tokens(fuente, dif);
            if (c.tokens != null && !c.tokens.equals(tokens)) {
                dif.add("tokens: esperado " + c.tokens + ", obtenido " + tokens);
            }
            if (c.contiene != null) {
                for (String t : c.contiene) {
                    if (!tokens.contains(t)) dif.add("falta el token " + t + "; obtenido " + tokens);
                }
            }

            // Mensajes de Parser.GetMensajes()
            List<Mensaje> mensajes = MensajesParser(fuente, dif);
            if (mensajes != null) {
                List<String> lex = new ArrayList<>();
                List<String> adv = new ArrayList<>();
                for (Mensaje m : mensajes) {
                    Integer cod = m.getCodigo();
                    if (cod != null && cod >= 100 && cod < 200) lex.add(M(cod, m.getLinea(), m.getLexema()));
                    if (cod != null && c.prohibidos.contains(cod)) dif.add("aparece el " + cod + ": " + m.Formatear());
                    if (m.getCategoria() == Mensaje.Categoria.ADVERTENCIA) adv.add(m.getLinea() + "[" + m.getLexema() + "]");
                }
                if (c.lexicos != null) {
                    // Multiconjunto exacto: se comparan ordenadas
                    List<String> a = new ArrayList<>(c.lexicos);
                    List<String> b = new ArrayList<>(lex);
                    Collections.sort(a);
                    Collections.sort(b);
                    if (!a.equals(b)) dif.add("1xx: esperado " + a + ", obtenido " + b);
                }
                if (c.sinMensajes && !mensajes.isEmpty()) {
                    List<String> f = new ArrayList<>();
                    for (Mensaje m : mensajes) f.add(m.getLinea() + ":" + m.Formatear());
                    dif.add("se esperaban 0 mensajes, obtenido " + f);
                }
                if (c.advertencia != null) {
                    if (c.advertencia.isEmpty()) {
                        if (!adv.isEmpty()) dif.add("advertencias inesperadas " + adv);
                    } else if (!adv.contains("1[" + c.advertencia + "]")) {
                        dif.add("falta la advertencia de REQ-ALGOL-01 [" + c.advertencia + "] en la línea 1; obtenidas " + adv);
                    }
                }
            }

            // TestProyecto y el -errores.txt en un hijo (R8)
            if (c.necesitaHijo()) {
                Path dir = trabajo.resolve("c" + n);
                Files.createDirectories(dir);
                String archivo = "caso" + n + ".mingol";
                Files.write(dir.resolve(archivo), fuente.getBytes(StandardCharsets.ISO_8859_1));
                ResultadoHijo r = LanzarHijo(L("hijotp", archivo), dir, "caso" + n);
                ProblemasDeEjecucion(r, dif);
                String tp = LineaConPrefijo(r.stdout, "TestProyecto=");
                if (c.testProyecto != null) {
                    String esperado = "TestProyecto=" + c.testProyecto;
                    if (!esperado.equals(tp)) dif.add("esperado " + esperado + ", obtenido " + tp);
                }
                Path errores = dir.resolve("caso" + n + "-errores.txt");
                List<String> arch = Files.exists(errores)
                        ? Arrays.asList(new String(Files.readAllBytes(errores), StandardCharsets.ISO_8859_1).split("\n", -1))
                        : null;
                if (arch == null) {
                    dif.add("no se generó " + errores.getFileName());
                } else {
                    for (String[] b : c.bajo) {
                        List<String> bajo = MensajesBajo(arch, Integer.parseInt(b[0]));
                        boolean hay = false;
                        for (String m : bajo) if (m.startsWith(S8 + b[1])) hay = true;
                        if (!hay) dif.add("bajo la línea " + b[0] + " no está " + b[1] + "; hay " + bajo);
                    }
                    for (String s : c.archivoSin) {
                        for (String l : arch) if (l.contains(s)) dif.add("el -errores.txt contiene [" + l.trim() + "]");
                    }
                    if (c.archivoSinMensajes) {
                        for (String l : arch) if (l.startsWith(S8)) dif.add("el -errores.txt tiene el mensaje [" + l.trim() + "]");
                    }
                }
            }
            Registrar(c.id, c.grupo, c.control, dif);
        }

        // 9. FOR con 99999999999 en un hijo (2.19): sin NumberFormatException y con 108
        CasoFor(trabajo);

        Causas();
        ImprimirResumen("exploracion");
    }

    // Caso 9: el ParseProgram corre en un hijo porque hoy aborta
    private static void CasoFor(Path trabajo) throws Exception {
        List<String> dif = new ArrayList<>();
        Path dir = trabajo.resolve("for");
        Files.createDirectories(dir);
        String fuente = String.join("\n", "BEGIN", "INT i := 0;", "FOR i FROM 99999999999 BY 1 TO 3", "DO", "SKIP", "OD;", "END") + "\n";
        Files.write(dir.resolve("for.mingol"), fuente.getBytes(StandardCharsets.ISO_8859_1));
        ResultadoHijo r = LanzarHijo(L("hijofor", "for.mingol"), dir, "for");
        if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        // La excepción se detecta por el nombre de la clase
        if (r.todo().contains("java.lang.NumberFormatException")) {
            dif.add("NumberFormatException: " + LineaConPrefijo(r.stdout, "EXCEPCION"));
        } else {
            String exc = LineaConPrefijo(r.stdout, "EXCEPCION");
            if (exc != null) dif.add(exc);
        }
        List<String> lex = LineasConPrefijo(r.stdout, "LEX|");
        if (!lex.contains(M(108, 3, "99999999999"))) dif.add("falta el 108 [99999999999] en la línea 3; 1xx obtenidos " + lex);
        Registrar("9 FOR i FROM 99999999999 BY 1 TO 3", "9", false, dif);
    }

    // Revisión de las causas 1..8 del diseño: confirmada si fallan los casos que dependen de ella
    private static void Causas() {
        System.out.println();
        System.out.println("===== CAUSAS DEL DISEÑO =====");
        Causa(1, "clases de caracteres (IsLetter/ReadIdentifier)", "1a factor1", "1b arctan2", "2e 1factor (105)");
        Causa(2, "el lexer no registra errores", "0 Lexer.GetMensajes()", "2a Factor1 (103)", "2b 21 caracteres (104)",
                "6a c4", "5b c6");
        Causa(3, "etiqueta decidida por un solo carácter", "7a x:=3", "7b inicio :", "2f print: (106)");
        Causa(4, "comentarios como tokens", "3a c1", "3b c2", "3c c3", "3d c9");
        Causa(5, "textos y números sin límites", "5a c5", "5b c6", "4f 1. (109)", "4a 32768 (108, sin 201)");
        Causa(6, "operadores de dos caracteres con PeekCharacter()", "7c a < = b (114)", "7d x : = 1 (114)");
        Causa(7, "tabla escrita a mano", "8 ENTIER", "8 PRAGMA", "8 grep", "8 lengths", "8 STRING", "8 TRUE");
        Causa(8, "conversión sin protección en ParseFor", "9 FOR i FROM 99999999999 BY 1 TO 3");
    }

    private static void Causa(int n, String nombre, String... casos) {
        List<String> pasan = new ArrayList<>();
        for (String c : casos) if (!Boolean.TRUE.equals(fallo.get(c))) pasan.add(c);
        String estado = pasan.isEmpty() ? "confirmada" : "NO confirmada del todo (pasan: " + pasan + ")";
        System.out.println("Causa " + n + " (" + nombre + "): " + estado);
    }

    // ------------------------------------------------------------------
    // Tarea 2: lenguaje neutral (fuera de C), generadores con semillas fijas
    // ------------------------------------------------------------------

    // Semillas fijas: la misma semilla da siempre el mismo fuente
    private static final long BASE_NEUTRAL = 1000;
    private static final long BASE_COMENTADO = 5000;
    // Semillas que se capturan con hijo y las que solo se comparan contra el tokenizador de referencia
    private static final int SEMILLAS_CAPTURA = 40;
    private static final int SEMILLAS_COMENTADAS = 15;
    private static final int SEMILLAS_REFERENCIA = 300;

    // Palabras MINGOL exactas del lenguaje neutral y su token (sin CO/COMMENT/STRING/TRUE/FALSE, que están en C)
    private static final Map<String, String> MINGOL_NEUTRAL = new LinkedHashMap<>();
    static {
        String[][] p = {
            {"BEGIN", "BEGIN"}, {"BY", "BY"}, {"CHAR", "TYPECHAR"}, {"DO", "DO"}, {"ELSE", "ELSE"}, {"END", "END"},
            {"EQ", "EQ"}, {"FI", "FI"}, {"FOR", "FOR"}, {"FROM", "FROM"}, {"GE", "GE"}, {"GOTO", "GOTO"},
            {"GT", "GT"}, {"IF", "IF"}, {"INT", "TYPEINT"}, {"LE", "LE"}, {"LT", "LT"}, {"NE", "NE"}, {"OD", "OD"},
            {"REAL", "TYPEREAL"}, {"SKIP", "SKIP"}, {"THEN", "THEN"}, {"TO", "TO"},
            {"print", "PRINT"}, {"read", "READ"}, {"newline", "NEWLINE"}
        };
        for (String[] x : p) MINGOL_NEUTRAL.put(x[0], x[1]);
    }

    // ALGOL exactas del lenguaje neutral (3.3, 3.9)
    private static final List<String> ALGOL_NEUTRAL = Arrays.asList("LONG", "max", "PRAGMAT", "OR", "RETURN");

    // Minúsculas que coinciden con reservadas en mayúscula: siguen como IDENT (3.2)
    private static final List<String> MINUSCULAS_IDENT = Arrays.asList("end", "eq", "begin", "for");

    // El generador nunca produce como identificador aleatorio una minúscula reservada (REQ-ALGOL-03),
    // una palabra MINGOL en minúscula ni la errata "lenghts" de la tabla actual
    private static final Set<String> NO_IDENT = new HashSet<>(Arrays.asList((
            "a68g abend abort abs airy alnum alpha angle arccos arccosh arcsin arcsinh arctan arctan2 arctanh "
            + "argc argv aspect associate back background backspace bessel beta bin blank break channel cholesky "
            + "circle clear clock close cntrl collect collections colour columns cos cosh cpu create curses curt "
            + "debug digit draw elliptic eof eoln erase erf error establish evaluate execve exp factorial fft fill "
            + "fixed flip float flop font fork format gamma garbage gc get getchar getf graph grep halt heap http "
            + "idf in incomplete integral inverse is last lengths line linestyle linewidth ln local lock log lower "
            + "lu make max min mksa modes move name new null num on open out pack page pi point pointer possible "
            + "preemptive print printf punct put putchar putf qr random read rect refresh reidf reset resolution "
            + "return routine scratch seconds set shorths show sin sinh size small sound space sqrt stack stand "
            + "stop style sub sv sweep system tan tanh tcp term text time upper value wait whole width will "
            + "window write xdigit newline lenghts").split(" ")));

    // Caracteres de los textos neutrales (sin comillas ni contrabarra)
    private static final String CHARS_TEXTO =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,;:()+-*/<>=!%'#_";

    private static final String MINUSCULAS = "abcdefghijklmnopqrstuvwxyz";

    // Identificador neutral: minúsculas sin dígitos, 1..max caracteres, "_" solo en el medio, no reservado
    private static String IdNeutral(Random r, int max) {
        while (true) {
            int n = 1 + r.nextInt(max);
            if (r.nextInt(6) == 0) n = max; // borde de longitud
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                boolean medio = i > 0 && i < n - 1;
                if (medio && r.nextInt(6) == 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
                else sb.append(MINUSCULAS.charAt(r.nextInt(26)));
            }
            String s = sb.toString();
            if (!NO_IDENT.contains(s) && !MINGOL_NEUTRAL.containsKey(s)) return s;
        }
    }

    // Identificador de plantilla: a veces una minúscula que coincide con una reservada en mayúscula
    private static String IdPlantilla(Random r) {
        if (r.nextInt(8) == 0) return MINUSCULAS_IDENT.get(r.nextInt(MINUSCULAS_IDENT.size()));
        return IdNeutral(r, r.nextInt(4) == 0 ? 20 : 8);
    }

    // Entero en 0..32767, con los bordes más frecuentes
    private static String EnteroNeutral(Random r) {
        if (r.nextInt(5) == 0) return new String[]{"0", "32767", "1", "9999"}[r.nextInt(4)];
        return String.valueOf(r.nextInt(32768));
    }

    // Real d+.d+ en rango: parte entera 0..32767 y 1 o 2 decimales (máximo 32767.99)
    private static String RealNeutral(Random r) {
        if (r.nextInt(6) == 0) return new String[]{"32767.99", "0.0", "32767.9", "1.5"}[r.nextInt(4)];
        String dec = r.nextBoolean() ? String.valueOf(r.nextInt(10)) : String.format("%02d", r.nextInt(100));
        return EnteroNeutral(r) + "." + dec;
    }

    // Texto cerrado de 0 a 30 caracteres
    private static String TextoNeutral(Random r, int max) {
        int n = r.nextInt(5) == 0 ? max : r.nextInt(max + 1);
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < n; i++) sb.append(CHARS_TEXTO.charAt(r.nextInt(CHARS_TEXTO.length())));
        return sb.append('"').toString();
    }

    private static boolean EsPalabra(String atomo) {
        char c = atomo.charAt(0);
        return Character.isLetter(c) || c == '_';
    }

    // Átomo de la "sopa" de tokens; prev es el átomo anterior de la línea (null al empezar)
    private static String AtomoNeutral(Random r, String prev) {
        String[] ops = {"+", "-", "*", "/", "=", "<", ">", "<=", ">=", "/=", ":=", ";", ",", "(", ")", "!", "\\n"};
        String[] ilegales = {"%", "'", ":"};
        while (true) {
            String a;
            int k = r.nextInt(20);
            if (k < 3) a = MINGOL_NEUTRAL.keySet().toArray(new String[0])[r.nextInt(MINGOL_NEUTRAL.size())];
            else if (k < 7) a = IdNeutral(r, 20);
            else if (k < 8) a = MINUSCULAS_IDENT.get(r.nextInt(MINUSCULAS_IDENT.size()));
            else if (k < 9) a = ALGOL_NEUTRAL.get(r.nextInt(ALGOL_NEUTRAL.size()));
            else if (k < 11) a = EnteroNeutral(r);
            else if (k < 12) a = RealNeutral(r);
            else if (k < 13) a = TextoNeutral(r, 30);
            else if (k < 19) a = ops[r.nextInt(ops.length)];
            else a = ilegales[r.nextInt(ilegales.length)];
            // ":" suelto tras una palabra sería "inicio :" (1.17) o reservada + ":" (1.6): fuera del neutral
            if (a.equals(":") && prev != null && EsPalabra(prev)) continue;
            // "<", ">", "/", ":" seguidos de blancos y "=" son 1.18: fuera del neutral
            if (a.startsWith("=") && prev != null && (prev.equals("<") || prev.equals(">") || prev.equals("/") || prev.equals(":"))) continue;
            return a;
        }
    }

    // Línea de sopa: átomos separados por 1 a 3 espacios, con blancos al inicio y al final, hasta 80 columnas
    private static String LineaSopa(Random r) {
        StringBuilder sb = new StringBuilder(" ".repeat(r.nextInt(4)));
        int n = 1 + r.nextInt(10);
        String prev = null;
        for (int i = 0; i < n; i++) {
            String a = AtomoNeutral(r, prev);
            String sep = prev == null ? "" : " ".repeat(1 + (r.nextInt(5) == 0 ? r.nextInt(3) : 0));
            if (sb.length() + sep.length() + a.length() > 80) break;
            sb.append(sep).append(a);
            prev = a;
        }
        return ConBlancos(r, sb.toString(), 0);
    }

    // Agrega hasta 3 espacios al final sin pasar de 80 columnas; con "relleno" puede llegar justo a 80
    private static String ConBlancos(Random r, String linea, int sangria) {
        String s = " ".repeat(sangria) + linea;
        if (s.length() > 80) s = linea;
        int fin = r.nextInt(10) == 0 ? 80 - s.length() : r.nextInt(4);
        if (s.length() + fin > 80) fin = Math.max(0, 80 - s.length());
        return s + " ".repeat(fin);
    }

    // Línea con forma de comando de MINGOL o ALGOL (sin errores léxicos)
    private static String LineaPlantilla(Random r) {
        while (true) {
            String a = IdPlantilla(r), b = IdPlantilla(r);
            String s;
            switch (r.nextInt(22)) {
                case 0: s = "INT " + a + " := " + EnteroNeutral(r) + ";"; break;
                case 1: s = "REAL " + a + " := " + RealNeutral(r) + ";"; break;
                case 2: s = "CHAR " + a + " := " + TextoNeutral(r, 1) + ";"; break;
                case 3: s = a + " := " + b + " + " + EnteroNeutral(r) + ";"; break;
                case 4: s = a + " := ( " + b + " * " + RealNeutral(r) + " ) - " + a + ";"; break;
                case 5: s = "print ( (" + a + ", " + b + ") );"; break;
                case 6: s = "print ( " + TextoNeutral(r, 30) + " );"; break;
                case 7: s = "print ( newline );"; break;
                case 8: s = "read ( " + a + " );"; break;
                case 9: s = "GOTO " + a + ";"; break;
                case 10: s = a + ":"; break;
                case 11: s = "IF ( " + a + " < " + EnteroNeutral(r) + " ) THEN"; break;
                case 12: s = "IF ( " + a + " /= " + b + " OR " + a + " >= 1 ) THEN"; break;
                case 13: s = "ELSE"; break;
                case 14: s = "FI;"; break;
                case 15: s = "FOR " + a + " FROM 1 BY 1 TO " + EnteroNeutral(r); break;
                case 16: s = "DO"; break;
                case 17: s = "OD;"; break;
                case 18: s = r.nextBoolean() ? "SKIP" : "SKIP;"; break;
                case 19: s = "LONG " + a + ";"; break;
                case 20: s = "RETURN " + EnteroNeutral(r); break;
                default: s = a + " := max ( " + b + " , 2 );"; break;
            }
            if (s.length() <= 80) return ConBlancos(r, s, r.nextInt(5));
        }
    }

    // Fuente neutral de una semilla (líneas sin el salto final)
    private static String[] GenerarNeutral(long semilla) {
        Random r = new Random(semilla);
        List<String> l = new ArrayList<>();
        if (r.nextInt(4) == 0) l.add("");
        boolean begin = r.nextInt(10) < 8;
        if (begin) l.add("BEGIN");
        int n = 2 + r.nextInt(11);
        for (int i = 0; i < n; i++) {
            int k = r.nextInt(100);
            if (k < 8) l.add("");
            else if (k < 13) l.add(" ".repeat(1 + r.nextInt(80)));
            else if (k < 63) l.add(LineaPlantilla(r));
            else l.add(LineaSopa(r));
        }
        if (begin && r.nextInt(10) < 8) l.add("END");
        if (r.nextInt(4) == 0) l.add("");
        return l.toArray(new String[0]);
    }

    // Palabra del contenido de un comentario: sin comillas, sin "#", sin CO/COMMENT, sin contrabarra
    // y sin dígitos pegados delante de letras (hoy "1CO" se leería como 1 + CO y cerraría el comentario)
    private static String PalabraComentario(Random r) {
        String letras = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        while (true) {
            String s;
            int k = r.nextInt(10);
            if (k < 5) {
                StringBuilder sb = new StringBuilder();
                int n = 1 + r.nextInt(8);
                for (int i = 0; i < n; i++) sb.append(letras.charAt(r.nextInt(letras.length())));
                if (r.nextInt(4) == 0) sb.append(r.nextInt(100)); // dígitos al final, como Factor1
                s = sb.toString();
            } else if (k < 6) {
                s = "_" + MINUSCULAS.charAt(r.nextInt(26));
            } else if (k < 8) {
                s = new String[]{"1.", "12", "3.25", "99999", ".5"}[r.nextInt(5)];
            } else {
                s = new String[]{";", ",", "(", ")", "%", "'", ":", "=", "+", "-", "*", "<", ">", "!", ":="}[r.nextInt(15)];
            }
            String letrasDe = s.replaceAll("[0-9]+$", "");
            if (letrasDe.equals("CO") || letrasDe.equals("COMMENT")) continue;
            return s;
        }
    }

    // Comentario bien cerrado de una sola forma (c7), de 1 a 3 líneas, cada una de hasta 80 columnas
    private static List<String> Comentario(Random r) {
        String forma = new String[]{"CO", "COMMENT", "#"}[r.nextInt(3)];
        int lineas = 1 + r.nextInt(3);
        List<String> res = new ArrayList<>();
        for (int i = 0; i < lineas; i++) {
            StringBuilder sb = new StringBuilder();
            if (i == 0) sb.append(forma);
            int palabras = 1 + r.nextInt(6);
            for (int j = 0; j < palabras; j++) {
                String p = PalabraComentario(r);
                // Se reserva lugar para el cierre en la última línea
                if (sb.length() + 1 + p.length() + forma.length() + 1 > 80) break;
                if (sb.length() > 0) sb.append(' ');
                sb.append(p);
            }
            if (i == lineas - 1) sb.append(' ').append(forma);
            res.add(sb.toString());
        }
        return res;
    }

    // Programa válido de hoy (c8 o t4) con 1 a 3 comentarios bien cerrados entre líneas
    private static String[] GenerarComentado(long semilla) {
        Random r = new Random(semilla);
        List<List<String>> bloques = new ArrayList<>();
        List<String> base = r.nextBoolean()
                ? L("BEGIN", "print ( newline );", "END")
                : L("BEGIN", "INT a := 1;", "print ( (a) );", "END");
        for (String b : base) bloques.add(L(b));
        int n = 1 + r.nextInt(3);
        for (int i = 0; i < n; i++) bloques.add(r.nextInt(bloques.size() + 1), Comentario(r));
        List<String> l = new ArrayList<>();
        for (List<String> b : bloques) l.addAll(b);
        return l.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Tokenizador de referencia del lenguaje neutral (reproduce el lexer actual; lo reutiliza "propiedades")
    // ------------------------------------------------------------------

    // Secuencia TIPO[literal]@línea sin el EOF, para fuentes del lenguaje neutral (sin comentarios)
    private static List<String> Referencia(String f) {
        List<String> r = new ArrayList<>();
        int i = 0, linea = 1, n = f.length();
        while (i < n) {
            char c = f.charAt(i);
            if (c == ' ') { i++; continue; }
            if (c == '\n') { r.add(T("EOL", "\n", linea)); linea++; i++; continue; }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetter(f.charAt(j)) || f.charAt(j) == '_')) j++;
                String w = f.substring(i, j);
                // Hoy el ":" pegado hace etiqueta antes de mirar la tabla
                if (j < n && f.charAt(j) == ':') { r.add(T("ETIQUETA", w, linea)); i = j + 1; continue; }
                String tipo = MINGOL_NEUTRAL.containsKey(w) ? MINGOL_NEUTRAL.get(w) : ALGOL_NEUTRAL.contains(w) ? "ALGOL" : "IDENT";
                r.add(T(tipo, w, linea));
                i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && Character.isDigit(f.charAt(j))) j++;
                if (j < n && f.charAt(j) == '.') {
                    int k = j + 1;
                    while (k < n && Character.isDigit(f.charAt(k))) k++;
                    r.add(T("REAL", f.substring(i, k), linea));
                    i = k;
                } else {
                    r.add(T("INTEGER", f.substring(i, j), linea));
                    i = j;
                }
                continue;
            }
            if (c == '"') {
                int j = f.indexOf('"', i + 1);
                r.add(T("STRING", f.substring(i + 1, j), linea));
                i = j + 1;
                continue;
            }
            char sig = i + 1 < n ? f.charAt(i + 1) : '\0';
            String tipo, lit = String.valueOf(c);
            switch (c) {
                case ':': tipo = sig == '=' ? "ASSIGN" : "ILLEGAL"; break;
                case '/': tipo = sig == '=' ? "NE" : "DIVISION"; break;
                case '<': tipo = sig == '=' ? "LE" : "LT"; break;
                case '>': tipo = sig == '=' ? "GE" : "GT"; break;
                case '\\': tipo = sig == 'n' ? "EOL" : "ILLEGAL"; lit = "\\" + sig; break;
                case '=': tipo = "EQ"; break;
                case '+': tipo = "ADDITION"; break;
                case '-': tipo = "SUBSTRACTION"; break;
                case '*': tipo = "MULTIPLICATION"; break;
                case '(': tipo = "LPAREN"; break;
                case ')': tipo = "RPAREN"; break;
                case ',': tipo = "COMMA"; break;
                case ';': tipo = "SEMICOLON"; break;
                case '!': tipo = "NEGATION"; break;
                case '.': tipo = "DECIMAL"; break;
                default: tipo = "ILLEGAL"; break;
            }
            // Operadores de dos caracteres pegados
            if (sig == '=' && (c == ':' || c == '/' || c == '<' || c == '>')) lit = c + "=";
            r.add(T(tipo, lit, linea));
            i += lit.length();
        }
        return r;
    }

    // Primera posición distinta entre dos listas, para el reporte
    private static String PrimeraDiferencia(List<String> esperado, List<String> obtenido) {
        int n = Math.min(esperado.size(), obtenido.size());
        for (int i = 0; i < n; i++) {
            if (!esperado.get(i).equals(obtenido.get(i))) {
                return "pos " + i + ": esperado " + esperado.get(i) + ", obtenido " + obtenido.get(i);
            }
        }
        return "largo: esperado " + esperado.size() + ", obtenido " + obtenido.size();
    }

    // Property 2 contra el lexer actual: el tokenizador de referencia da la misma secuencia (tipo, literal, línea)
    private static void ComprobarReferencia(List<CasoCap> casos) {
        List<String> dif = new ArrayList<>();
        int malos = 0, total = 0;
        List<String[]> fuentes = new ArrayList<>();
        for (int s = 0; s < SEMILLAS_REFERENCIA; s++) {
            fuentes.add(new String[]{"semilla " + (BASE_NEUTRAL + s), Unir(GenerarNeutral(BASE_NEUTRAL + s))});
        }
        for (CasoCap c : casos) {
            if (!c.comentarios) fuentes.add(new String[]{c.id, new String(c.fuente, StandardCharsets.ISO_8859_1)});
        }
        for (String[] f : fuentes) {
            total++;
            List<String> d = new ArrayList<>();
            List<String> lx = Tokens(f[1], d);
            List<String> ref = Referencia(f[1]);
            if (!d.isEmpty() || !lx.equals(ref)) {
                malos++;
                if (dif.size() < 5) dif.add(f[0] + ": " + d + " " + PrimeraDiferencia(ref, lx) + "; fuente " + Escapar(f[1]));
            }
        }
        if (malos > 0) dif.add(malos + " de " + total + " fuentes difieren");
        Registrar("referencia = lexer en " + total + " fuentes neutrales", "P2", true, dif);
    }

    private static String Unir(String[] lineas) {
        return String.join("\n", lineas) + "\n";
    }

    // ------------------------------------------------------------------
    // Tarea 2: casos de captura
    // ------------------------------------------------------------------

    // Caso de captura: archivo relativo a su carpeta, fuente en bytes y si tiene comentarios
    private static class CasoCap {
        String id;
        String archivo;
        byte[] fuente;
        boolean comentarios;
    }

    private static CasoCap Cap(String id, String archivo, boolean comentarios, String... lineas) {
        CasoCap c = new CasoCap();
        c.id = id;
        c.archivo = archivo;
        c.comentarios = comentarios;
        c.fuente = Unir(lineas).getBytes(StandardCharsets.ISO_8859_1);
        return c;
    }

    // Semillas neutrales, variantes comentadas, c7, c8 y los ejemplos del área 1 (fuentes copiadas de
    // PruebasArchivoErrores.Casos(), sin modificar ese archivo; todos con salto final)
    private static List<CasoCap> CasosCaptura() {
        List<CasoCap> l = new ArrayList<>();
        for (int s = 0; s < SEMILLAS_CAPTURA; s++) {
            String id = String.format("n%02d", s);
            l.add(Cap(id, id + ".mingol", false, GenerarNeutral(BASE_NEUTRAL + s)));
        }
        for (int s = 0; s < SEMILLAS_COMENTADAS; s++) {
            String id = String.format("k%02d", s);
            l.add(Cap(id, id + ".mingol", true, GenerarComentado(BASE_COMENTADO + s)));
        }
        l.add(Cap("c7", "c7.mingol", true, "CO Factor1 _x 1. CO", "BEGIN", "print ( newline );", "END"));
        l.add(Cap("c8", "c8.mingol", false, "BEGIN", "print ( newline );", "END"));
        l.add(Cap("ae-t1", "t1.mingol", false, "", "BEGIN", "INT a := 1.5;", "x = 3;", "   ", "print ( (zz) );", "END", ""));
        l.add(Cap("ae-t2", "t2.mingol", false, "BEGIN", "INT a := 1;", "INT a := 2;", "print ( (b) );", "GOTO nada;", "END"));
        l.add(Cap("ae-t3", "t3.mingol", false, "BEGIN", "INT a := 1;", "LONG x;", "print ( (b) );", "END"));
        l.add(Cap("ae-t4", "t4.mingol", false, "BEGIN", "INT a := 1;", "print ( (a) );", "END"));
        l.add(Cap("ae-t5", "t5.mingol", false, "BEGIN", "BEGIN", "INT a := 1;", "END", "END"));
        l.add(Cap("ae-t6", "t6.mingol", false, "INT a := 1;", "print ( (a) );"));
        l.add(Cap("ae-t7", "t7.mingol", false, "BEGIN", "INT a := 1;", "x = 3;", "print ( (b) );", "END"));
        l.add(Cap("ae-t9", "t9.mingol", false, "BEGIN", "END", "INT a := 1;", "INT a := 2;", "print ( (b) );"));
        l.add(Cap("ae-t10", "T10.MINGOL", false, "BEGIN", "INT a := 1;", "END"));
        l.add(Cap("ae-t11", "sub.dir" + File.separator + "t11.mingol", false, "BEGIN", "x = 3;", "END"));
        // t12 se invoca sin extensión; main lo normaliza a t12.mingol, que es lo que recibe TestProyecto
        l.add(Cap("ae-t12", "t12.mingol", false, "BEGIN", "LONG x;", "y = 3;", "END"));
        l.add(Cap("ae-t13", "t13.mingol", false, "BEGIN", "CHAR y := zz;", "print ( (y) );", "INT y := 1;", "END"));
        l.add(Cap("ae-c1", "c1.mingol", false, "BEGIN", "inicio:", "CHAR y := inicio;", "END"));
        l.add(Cap("ae-c6", "c6.mingol", false, "BEGIN", "CHAR y := end;", "END"));
        l.add(Cap("ae-e8", "e8.mingol", false, "BEGIN", "INT x := 1;", "IF (x) THEN", "SKIP;", "END"));
        return l;
    }

    // Nombre del -errores.txt como lo calcula TestProyecto: sin carpetas ni la última extensión
    private static String NombreErrores(String archivo) {
        String nombre = Paths.get(archivo).getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return ((punto > 0) ? nombre.substring(0, punto) : nombre) + "-errores.txt";
    }

    // Corre la captura de un caso en un hijo (R8) dentro de dir; devuelve {captura, -errores.txt o null}
    private static Object[] CorrerCaptura(CasoCap c, Path dir, List<String> dif) throws Exception {
        BorrarArbol(dir);
        Files.createDirectories(dir);
        Path fuente = dir.resolve(c.archivo);
        Files.createDirectories(fuente.getParent());
        Files.write(fuente, c.fuente);
        ResultadoHijo r = LanzarHijo(L("hijocap", c.archivo, c.comentarios ? "0" : "1"), dir, c.id);
        ProblemasDeEjecucion(r, dif);
        Path cap = dir.resolve(ARCHIVO_CAPTURA);
        String captura = Files.exists(cap) ? new String(Files.readAllBytes(cap), StandardCharsets.UTF_8) : null;
        if (captura == null) dif.add("el hijo no escribió " + ARCHIVO_CAPTURA);
        Path err = dir.resolve(NombreErrores(c.archivo));
        byte[] errores = Files.exists(err) ? Files.readAllBytes(err) : null;
        return new Object[]{captura, errores};
    }

    private static final String ARCHIVO_CAPTURA = "captura-arnes.txt";

    // Carpeta de la captura (fuera de la carpeta de trabajo, en %TEMP%)
    private static Path CarpetaGolden() throws IOException {
        return CarpetaTemp().resolve("mingol-lexico-golden");
    }

    // ------------------------------------------------------------------
    // Modo capturar [--sobrescribir]
    // ------------------------------------------------------------------

    private static void Capturar(boolean sobrescribir) throws Exception {
        Path carpeta = CarpetaSegura();
        Path golden = CarpetaGolden();
        boolean existe = false;
        if (Files.exists(golden)) {
            try (Stream<Path> s = Files.list(golden)) {
                existe = s.findAny().isPresent();
            }
        }
        // No se pisa una captura existente sin --sobrescribir
        if (existe && !sobrescribir) {
            System.err.println("Ya existe una captura en " + golden + "; usar --sobrescribir para reemplazarla");
            System.exit(2);
        }
        BorrarArbol(golden);
        Files.createDirectories(golden);
        Path trabajo = carpeta.resolve("capturar");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        List<CasoCap> casos = CasosCaptura();
        ComprobarReferencia(casos);
        Observaciones();

        StringBuilder indice = new StringBuilder();
        Map<String, String> capturas = new HashMap<>();
        for (CasoCap c : casos) {
            List<String> dif = new ArrayList<>();
            Object[] res = CorrerCaptura(c, trabajo.resolve(c.id), dif);
            String captura = (String) res[0];
            byte[] errores = (byte[]) res[1];
            if (errores == null) dif.add("no se generó " + NombreErrores(c.archivo));
            Path g = golden.resolve(c.id);
            Files.createDirectories(g);
            Files.write(g.resolve("fuente.bin"), c.fuente);
            Files.write(g.resolve("meta.txt"), ("archivo=" + c.archivo + "\ncomentarios=" + c.comentarios + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            if (captura != null) Files.write(g.resolve("captura.txt"), captura.getBytes(StandardCharsets.UTF_8));
            if (errores != null) Files.write(g.resolve("errores.bin"), errores);
            if (captura != null) capturas.put(c.id, captura);
            indice.append(c.id).append('\n');
            Registrar("captura " + c.id + (c.comentarios ? " (con comentarios)" : ""), "captura", true, dif);
        }
        ObservacionesCapturadas(capturas);
        // El índice se escribe al final: marca la captura como completa
        Files.write(golden.resolve("indice.txt"), indice.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Captura en " + golden + ": " + casos.size() + " casos");
        ImprimirResumen("capturar");
    }

    // Observaciones del código sin arreglar (tarea 2), en proceso con fuentes chicas
    private static void Observaciones() {
        List<String> dif = new ArrayList<>();
        List<String> t = Tokens("end eq begin for\n", dif);
        List<String> esperado = L(T("IDENT", "end", 1), T("IDENT", "eq", 1), T("IDENT", "begin", 1), T("IDENT", "for", 1), EOL(1));
        if (!t.equals(esperado)) dif.add("esperado " + esperado + ", obtenido " + t);
        Registrar("observación end/eq/begin/for como IDENT", "obs", true, dif);

        dif = new ArrayList<>();
        t = Tokens("LONG max PRAGMAT OR RETURN\n", dif);
        esperado = L(T("ALGOL", "LONG", 1), T("ALGOL", "max", 1), T("ALGOL", "PRAGMAT", 1), T("ALGOL", "OR", 1),
                T("ALGOL", "RETURN", 1), EOL(1));
        if (!t.equals(esperado)) dif.add("esperado " + esperado + ", obtenido " + t);
        Registrar("observación LONG/max/PRAGMAT/OR/RETURN como ALGOL", "obs", true, dif);

        dif = new ArrayList<>();
        List<Mensaje> m = MensajesParser("BEGIN\n%\nEND\n", dif);
        boolean hay = false;
        if (m != null) for (Mensaje x : m) if (Integer.valueOf(101).equals(x.getCodigo()) && "%".equals(x.getLexema()) && x.getLinea() == 2) hay = true;
        if (!hay) dif.add("no aparece el 101 [%] en la línea 2; mensajes " + Formatear(m));
        Registrar("observación % con 101", "obs", true, dif);
    }

    // c8 válido sin mensajes y c7 sin mensajes, leídos de la captura recién hecha
    private static void ObservacionesCapturadas(Map<String, String> capturas) {
        for (String id : new String[]{"c8", "c7"}) {
            List<String> dif = new ArrayList<>();
            String cap = capturas.get(id);
            if (cap == null) {
                dif.add("sin captura");
            } else {
                for (String l : cap.split("\n")) if (l.startsWith("MSG|") || l.startsWith("PARSER|")) dif.add(l);
                if (!cap.contains("TP|true")) dif.add("TestProyecto distinto de true: " + LineaConPrefijo(cap, "TP|"));
                if (!cap.contains("HAS|false")) dif.add("hasErrors distinto de false: " + LineaConPrefijo(cap, "HAS|"));
            }
            Registrar("observación " + id + " válido y sin mensajes", "obs", true, dif);
        }
    }

    private static List<String> Formatear(List<Mensaje> m) {
        List<String> r = new ArrayList<>();
        if (m != null) for (Mensaje x : m) r.add(x.getLinea() + ":" + x.Formatear());
        return r;
    }

    // ------------------------------------------------------------------
    // Modo preservacion (Preservation Checking del diseño)
    // ------------------------------------------------------------------

    private static void Preservacion() throws Exception {
        Path carpeta = CarpetaSegura();
        Path golden = CarpetaGolden();
        Path indice = golden.resolve("indice.txt");
        if (!Files.exists(indice)) {
            System.err.println("No hay captura completa en " + golden + ": correr antes 'capturar' sobre el código sin arreglar");
            System.exit(2);
        }
        Path trabajo = carpeta.resolve("preservacion");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        // El conjunto de casos y sus fuentes deben coincidir con la captura (generadores deterministas)
        List<CasoCap> actuales = CasosCaptura();
        List<String> ids = new ArrayList<>();
        for (String l : Files.readAllLines(indice, StandardCharsets.UTF_8)) if (!l.isBlank()) ids.add(l.trim());
        List<String> difIds = new ArrayList<>();
        List<String> idsActuales = new ArrayList<>();
        for (CasoCap c : actuales) {
            idsActuales.add(c.id);
            Path f = golden.resolve(c.id).resolve("fuente.bin");
            if (Files.exists(f) && !Arrays.equals(Files.readAllBytes(f), c.fuente)) difIds.add(c.id + ": el generador ya no da el fuente capturado");
        }
        if (!ids.equals(idsActuales)) difIds.add("casos capturados " + ids + " distintos de los actuales " + idsActuales);
        Registrar("casos y fuentes iguales a la captura", "P3", true, difIds);

        // Property 2 sin captura: referencia = lexer
        ComprobarReferencia(actuales);

        for (String id : ids) {
            List<String> dif = new ArrayList<>();
            Path g = golden.resolve(id);
            Map<String, String> meta = new HashMap<>();
            for (String l : Files.readAllLines(g.resolve("meta.txt"), StandardCharsets.UTF_8)) {
                int k = l.indexOf('=');
                if (k > 0) meta.put(l.substring(0, k), l.substring(k + 1));
            }
            CasoCap c = new CasoCap();
            c.id = id;
            c.archivo = meta.get("archivo");
            c.comentarios = Boolean.parseBoolean(meta.get("comentarios"));
            c.fuente = Files.readAllBytes(g.resolve("fuente.bin"));
            Path capG = g.resolve("captura.txt");
            String esperado = Files.exists(capG) ? new String(Files.readAllBytes(capG), StandardCharsets.UTF_8) : null;

            Object[] res = CorrerCaptura(c, trabajo.resolve(id), dif);
            String obtenido = (String) res[0];
            if (esperado == null) dif.add("la captura no tiene captura.txt");
            if (esperado != null && obtenido != null) {
                // área 4 (DM10, M4): solo tokens (si el caso los tiene), los 1xx en orden y las excepciones del
                // parser; ya no se comparan ERR|, HAS|, TP|, los 2xx/3xx/advertencias ni el -errores.txt
                List<String> a = Excepciones101(id, SoloLexico(esperado), dif), b = SoloLexico(obtenido);
                if (!a.equals(b)) dif.add("tokens/1xx: " + PrimeraDiferencia(a, b));
            }
            Registrar("preservación " + id + (c.comentarios ? " (mensajes y validez)" : ""), "P3", true, dif);
        }
        ImprimirResumen("preservacion");
    }

    // área 4: excepciones de la captura desactualizada (R9, sin regenerarla). La captura, previa al área 2,
    // registraba el 101 con el contador de línea del parser y lo perdía en ParseAlgol tras RETURN (n10);
    // RegistrarIlegales (área 3) lo da en la línea física real. Solo el 101 de estos cuatro casos:
    // {caso, lexema, línea capturada (0 = la captura no lo tiene), línea real}
    private static final String[][] EXCEPCIONES_101 = {
        {"n10", "'", "0", "5"},
        {"n18", "%", "5", "4"},
        {"n21", "'", "8", "6"},
        {"n33", "'", "9", "8"},
    };

    // área 4: aplica EXCEPCIONES_101 a la lista esperada; si la captura no tiene el 101 indicado, falla
    private static List<String> Excepciones101(String id, List<String> esperado, List<String> dif) {
        List<String> r = new ArrayList<>(esperado);
        for (String[] e : EXCEPCIONES_101) {
            if (!e[0].equals(id)) continue;
            String nuevo = "MSG|" + e[3] + "|LEXICO|101|" + e[1] + "|ERROR 101: Carácter ilegal [" + e[1] + "].";
            if (!e[2].equals("0")) {
                String viejo = "MSG|" + e[2] + "|LEXICO|101|" + e[1] + "|ERROR 101: Carácter ilegal [" + e[1] + "].";
                if (!r.remove(viejo)) { dif.add("excepción 101 de " + id + ": la captura no tiene " + viejo); continue; }
            } else if (r.contains(nuevo)) {
                dif.add("excepción 101 de " + id + ": la captura ya tiene " + nuevo); continue;
            }
            // se inserta antes del primer MSG con línea mayor (los 1xx van en orden de línea)
            int pos = r.size();
            for (int i = 0; i < r.size(); i++) {
                String l = r.get(i);
                String lin = l.startsWith("MSG|") ? l.split("\\|", 3)[1] : "";
                if (lin.matches("\\d+") && Integer.parseInt(lin) > Integer.parseInt(e[3])) { pos = i; break; }
            }
            r.add(pos, nuevo);
        }
        return r;
    }

    // área 4 (DM10): líneas TOK|/TOKDIF|, MSG| con código 100-199 (en orden) y PARSER|EXC de una captura
    private static List<String> SoloLexico(String captura) {
        List<String> r = new ArrayList<>();
        for (String l : captura.split("\n")) {
            if (l.startsWith("TOK|") || l.startsWith("TOKDIF|") || l.startsWith("PARSER|EXC")) {
                r.add(l);
            } else if (l.startsWith("MSG|")) {
                // MSG|línea|categoría|código|lexema|texto
                String[] c = l.split("\\|", 6);
                if (c.length >= 4 && c[3].matches("1\\d\\d")) r.add(l);
            }
        }
        return r;
    }

    // Hijo de captura: tokens (si se pide), GetMensajes, GetErrors, hasErrors y TestProyecto por reflexión.
    // Solo usa API presente antes y después del arreglo (Lexer.NextToken, Parser.GetMensajes/GetErrors/hasErrors)
    private static void HijoCap(String archivo, boolean conTokens) throws Exception {
        StringBuilder sb = new StringBuilder();
        String fuente = LeerFuente(archivo);
        if (conTokens) {
            List<String> dif = new ArrayList<>();
            for (String t : Tokens(fuente, dif)) sb.append("TOK|").append(t).append('\n');
            for (String d : dif) sb.append("TOKDIF|").append(Escapar(d)).append('\n');
        }
        try {
            Parser p = new Parser(new Lexer(fuente));
            p.ParseProgram();
            for (Mensaje m : p.GetMensajes()) {
                sb.append("MSG|").append(m.getLinea()).append('|').append(m.getCategoria()).append('|')
                        .append(m.getCodigo() == null ? "-" : String.valueOf(m.getCodigo())).append('|')
                        .append(Escapar(String.valueOf(m.getLexema()))).append('|')
                        .append(Escapar(m.Formatear())).append('\n');
            }
            for (String e : p.GetErrors()) sb.append("ERR|").append(Escapar(String.valueOf(e))).append('\n');
            sb.append("HAS|").append(p.hasErrors).append('\n');
        } catch (Throwable t) {
            sb.append("PARSER|EXC|").append(t.getClass().getName()).append('|').append(Escapar(String.valueOf(t.getMessage()))).append('\n');
        }
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            sb.append("TP|").append(m.invoke(null, archivo)).append('\n');
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            sb.append("TP|EXC|").append(c.getClass().getName()).append('|').append(Escapar(String.valueOf(c.getMessage()))).append('\n');
        }
        Files.write(Paths.get(ARCHIVO_CAPTURA), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Análisis en proceso
    // ------------------------------------------------------------------

    // Secuencia de tokens sin el EOF: TIPO[literal]@línea
    private static List<String> Tokens(String fuente, List<String> dif) {
        List<String> r = new ArrayList<>();
        try {
            Lexer lx = new Lexer(fuente);
            for (int i = 0; i < MAX_TOKENS; i++) {
                Token t = lx.NextToken();
                if (t.getTokenType() == MingolToken.EOF) return r;
                r.add(T(t.getTokenType().name(), t.getLiteral(), t.getLine()));
            }
            dif.add("el lexer no llegó a EOF en " + MAX_TOKENS + " tokens");
        } catch (Throwable e) {
            dif.add("excepción en el lexer: " + e.getClass().getName() + ": " + e.getMessage());
        }
        return r;
    }

    // Parser.GetMensajes() tras ParseProgram(); null si hubo una excepción
    private static List<Mensaje> MensajesParser(String fuente, List<String> dif) {
        try {
            Parser p = new Parser(new Lexer(fuente));
            p.ParseProgram();
            return new ArrayList<>(p.GetMensajes());
        } catch (Throwable e) {
            dif.add("excepción en el parser: " + e.getClass().getName() + ": " + e.getMessage());
            return null;
        }
    }

    // Líneas de mensaje (con 8 espacios) que siguen a la línea numerada n del -errores.txt
    private static List<String> MensajesBajo(List<String> arch, int n) {
        List<String> r = new ArrayList<>();
        String prefijo = String.format("%05d ", n);
        for (int i = 0; i < arch.size(); i++) {
            if (arch.get(i).startsWith(prefijo)) {
                for (int j = i + 1; j < arch.size() && arch.get(j).startsWith(S8); j++) r.add(arch.get(j));
                break;
            }
        }
        return r;
    }

    // ------------------------------------------------------------------
    // Hijos
    // ------------------------------------------------------------------

    // TestProyecto por reflexión, como RunTestProyecto (sin a68g)
    private static void HijoTP(String archivo) throws Exception {
        // Avisos: es la salida estándar del hijo; cerrarla la cerraría para todo el proceso
        @SuppressWarnings("resource")
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        int codigo = 0;
        try {
            out.println("TestProyecto=" + m.invoke(null, archivo));
        } catch (InvocationTargetException e) {
            codigo = 3;
            out.println("EXCEPCION|" + e.getCause().getClass().getName() + "|" + Escapar(String.valueOf(e.getCause().getMessage())));
            e.getCause().printStackTrace();
        }
        out.flush();
        System.exit(codigo);
    }

    // ParseProgram del caso 9: imprime los 1xx o la excepción
    private static void HijoFor(String archivo) throws Exception {
        // Avisos: es la salida estándar del hijo; cerrarla la cerraría para todo el proceso
        @SuppressWarnings("resource")
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        int codigo = 0;
        try {
            Parser p = new Parser(new Lexer(LeerFuente(archivo)));
            p.ParseProgram();
            for (Mensaje msj : p.GetMensajes()) {
                Integer cod = msj.getCodigo();
                if (cod != null && cod >= 100 && cod < 200) out.println("LEX|" + M(cod, msj.getLinea(), msj.getLexema()));
            }
        } catch (Throwable t) {
            codigo = 3;
            out.println("EXCEPCION|" + t.getClass().getName() + "|" + Escapar(String.valueOf(t.getMessage())));
        }
        out.flush();
        System.exit(codigo);
    }

    // Lee el fuente igual que TestProyecto: cada línea seguida de "\n"
    private static String LeerFuente(String archivo) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = reader.readLine()) != null) sb.append(linea).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Lanzamiento de hijos (R8), adaptado de PruebasArchivoErrores
    // ------------------------------------------------------------------

    private static class ResultadoHijo {
        boolean termino;
        int codigo = -1;
        String stdout = "";
        String stderr = "";

        String todo() {
            return stdout + "\n" + stderr;
        }
    }

    // Misma java.exe, --enable-preview, -Xmx256m, mismo classpath y -ea si el padre lo usa
    private static ResultadoHijo LanzarHijo(List<String> argsHijo, Path directorio, String nombreLog) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> comando = new ArrayList<>();
        comando.add(java);
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.equals("-ea") || a.equals("-enableassertions")) {
                comando.add("-ea");
                break;
            }
        }
        comando.add("--enable-preview");
        comando.add("-Xmx256m");
        comando.add("-cp");
        comando.add(System.getProperty("java.class.path"));
        comando.add("PruebasLexico");
        comando.addAll(argsHijo);

        // La salida va a una subcarpeta de logs, fuera de los archivos que se comparan
        Path logs = directorio.resolve("logs");
        Files.createDirectories(logs);
        File out = logs.resolve(nombreLog + ".out").toFile();
        File err = logs.resolve(nombreLog + ".err").toFile();

        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.directory(directorio.toFile());
        pb.redirectOutput(out);
        pb.redirectError(err);
        Process p = pb.start();
        ResultadoHijo r = new ResultadoHijo();
        r.termino = p.waitFor(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
        if (!r.termino) {
            // Se pasó del límite: se mata el hijo y sus descendientes
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        } else {
            r.codigo = p.exitValue();
        }
        r.stdout = LeerRecortado(out.toPath(), 64 * 1024);
        r.stderr = LeerRecortado(err.toPath(), 64 * 1024);
        return r;
    }

    // Lee como mucho maxBytes (un OutOfMemoryError puede dejar mucha salida)
    private static String LeerRecortado(Path p, int maxBytes) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        return new String(b, 0, Math.min(b.length, maxBytes), StandardCharsets.UTF_8);
    }

    // No terminó, salió con código distinto de 0 o hubo una excepción
    private static void ProblemasDeEjecucion(ResultadoHijo r, List<String> dif) {
        if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        else if (r.codigo != 0) dif.add("código de salida del hijo " + r.codigo);
        String exc = LineaConPrefijo(r.stdout, "EXCEPCION");
        if (exc != null) dif.add(exc);
        String err = PrimeraLineaCon(r.stderr, "Exception");
        if (err == null) err = PrimeraLineaCon(r.stderr, "Error");
        if (err != null) dif.add("stderr: " + err.trim());
    }

    // ------------------------------------------------------------------
    // Utilidades (copiadas y adaptadas de PruebasArchivoErrores)
    // ------------------------------------------------------------------

    // %TEMP% (o java.io.tmpdir), con la ruta real para no depender de los nombres cortos 8.3
    private static Path CarpetaTemp() throws IOException {
        String temp = System.getenv("TEMP");
        Path base = (temp != null && !temp.isBlank()) ? Paths.get(temp) : Paths.get(System.getProperty("java.io.tmpdir"));
        return base.toRealPath();
    }

    // Carpeta actual; se niega a correr en el repositorio (R5)
    private static Path CarpetaSegura() {
        Path carpeta = Paths.get("").toAbsolutePath();
        System.out.println("Carpeta de trabajo base: " + carpeta);
        if (Files.exists(carpeta.resolve("build.xml")) || Files.exists(carpeta.resolve("proyecto.mingol"))) {
            System.err.println("Se evita ejecutar en el repositorio: usar $env:TEMP\\mingol-lexico");
            System.exit(2);
        }
        return carpeta;
    }

    // Borra una carpeta de trabajo del arnés; se niega a borrar fuera de %TEMP%
    private static void BorrarArbol(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Path temp = CarpetaTemp();
        Path abs = dir.toRealPath();
        if (!abs.startsWith(temp) || abs.equals(temp)) {
            throw new IOException("Se evita borrar fuera de %TEMP%: " + abs);
        }
        try (Stream<Path> s = Files.walk(abs)) {
            List<Path> todos = new ArrayList<>();
            s.forEach(todos::add);
            Collections.reverse(todos);
            for (Path p : todos) Files.delete(p);
        }
    }

    private static String LineaConPrefijo(String texto, String prefijo) {
        for (String l : texto.split("\\R")) if (l.startsWith(prefijo)) return l;
        return null;
    }

    private static List<String> LineasConPrefijo(String texto, String prefijo) {
        List<String> r = new ArrayList<>();
        for (String l : texto.split("\\R")) if (l.startsWith(prefijo)) r.add(l.substring(prefijo.length()));
        return r;
    }

    private static String PrimeraLineaCon(String texto, String parte) {
        for (String l : texto.split("\\R")) if (l.contains(parte)) return l;
        return null;
    }

    // Una sola línea por dato: se escapan contrabarras y saltos
    private static String Escapar(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    // Imprime OK/FALLA con las diferencias y lo agrega al resumen
    private static void Registrar(String caso, String grupo, boolean control, List<String> diferencias) {
        String etiqueta = "[caso " + grupo + (control ? ", control" : "") + "] ";
        fallo.put(caso, !diferencias.isEmpty());
        if (diferencias.isEmpty()) {
            System.out.println("OK    " + etiqueta + caso);
            resumen.add("OK    " + caso);
            // Un caso que no es de control y pasa: el bug no se reproduce
            if (!control) noReproducidos.add(caso);
        } else {
            fallas++;
            System.out.println("FALLA " + etiqueta + caso);
            for (String d : diferencias) System.out.println("      - " + d);
            resumen.add("FALLA " + caso + ": " + diferencias.get(0));
        }
    }

    // Solo las FALLA, los no reproducidos y el total
    private static void ImprimirResumen(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) if (r.startsWith("FALLA")) System.out.println(r);
        if (!noReproducidos.isEmpty()) System.out.println("Pasan sin ser de control (bug no reproducido): " + noReproducidos);
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }

    // ==================================================================
    // Tarea 3.13: utilidades comunes de unitarias y propiedades
    // ==================================================================

    // Textos de los mensajes léxicos (copia propia del arnés, para comprobar el mensaje exacto)
    private static final Map<Integer, String> TEXTOS = new HashMap<>();
    static {
        TEXTOS.put(103, "Identificador con mayúsculas");
        TEXTOS.put(104, "Identificador de más de 20 caracteres");
        TEXTOS.put(105, "Identificador que no empieza con letra o que empieza o termina con _");
        TEXTOS.put(106, "Palabra reservada de MINGOL o ALGOL usada como etiqueta");
        TEXTOS.put(107, "Comentario sin cerrar al final del archivo");
        TEXTOS.put(108, "Constante entera fuera de rango");
        TEXTOS.put(109, "Constante real sin dígito antes o después del punto");
        TEXTOS.put(110, "Constante real fuera de rango");
        TEXTOS.put(111, "Texto de más de 30 caracteres");
        TEXTOS.put(112, "Texto sin comilla de cierre");
        TEXTOS.put(113, "Línea de más de 80 columnas");
        TEXTOS.put(114, "Operador de dos caracteres con blanco intermedio");
    }

    // Mensaje formateado como Mensaje.Formatear(), sin línea
    private static String F(int codigo, String lexema) {
        return String.format("ERROR %03d: %s [%s].", codigo, TEXTOS.get(codigo), lexema);
    }

    // Mensaje esperado con su línea: "línea|ERROR NNN: texto [lexema]."
    private static String E(int codigo, int linea, String lexema) {
        return linea + "|" + F(codigo, lexema);
    }

    // Mensajes en el formato de E(...)
    private static List<String> Formateados(List<Mensaje> m) {
        List<String> r = new ArrayList<>();
        if (m != null) for (Mensaje x : m) r.add(x.getLinea() + "|" + x.Formatear());
        return r;
    }

    // Solo los 1xx de una lista de mensajes, en el formato de E(...)
    private static List<String> Lexicos(List<Mensaje> m) {
        List<String> r = new ArrayList<>();
        if (m != null) {
            for (Mensaje x : m) {
                Integer c = x.getCodigo();
                if (c != null && c >= 100 && c < 200) r.add(x.getLinea() + "|" + x.Formatear());
            }
        }
        return r;
    }

    // Tokens y mensajes del mismo lexer, leído hasta EOF
    private static class Lectura {
        Lexer lexer;
        List<Token> crudos = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        List<String> mensajes = new ArrayList<>();
    }

    private static Lectura Leer(String fuente, List<String> dif) {
        Lectura r = new Lectura();
        try {
            r.lexer = new Lexer(fuente);
            for (int i = 0; i < MAX_TOKENS; i++) {
                Token t = r.lexer.NextToken();
                if (t.getTokenType() == MingolToken.EOF) {
                    r.mensajes = Formateados(r.lexer.GetMensajes());
                    return r;
                }
                r.crudos.add(t);
                r.tokens.add(T(t.getTokenType().name(), t.getLiteral(), t.getLine()));
            }
            dif.add("el lexer no llegó a EOF en " + MAX_TOKENS + " tokens");
        } catch (Throwable e) {
            dif.add("excepción en el lexer: " + e.getClass().getName() + ": " + e.getMessage());
        }
        return r;
    }

    // Resultado del parser: mensajes, hasErrors y el programa
    private static class Analisis {
        List<Mensaje> mensajes;
        boolean hasErrors;
        Object programa;
    }

    // ParseProgram en un hilo con límite de tiempo: un cuelgue se reporta como falla y no corta el arnés
    private static Analisis Analizar(String fuente, List<String> dif) {
        FutureTask<Analisis> tarea = new FutureTask<>(() -> {
            Parser p = new Parser(new Lexer(fuente));
            Analisis a = new Analisis();
            a.programa = p.ParseProgram();
            a.mensajes = new ArrayList<>(p.GetMensajes());
            a.hasErrors = p.hasErrors;
            return a;
        });
        Thread hilo = new Thread(tarea, "analisis");
        hilo.setDaemon(true);
        hilo.start();
        try {
            return tarea.get(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            dif.add("el parser no terminó en " + LIMITE_SEGUNDOS + " s");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            dif.add("excepción en el parser: " + c.getClass().getName() + ": " + c.getMessage());
        } catch (InterruptedException e) {
            dif.add("interrumpido");
        }
        return null;
    }

    // 201 de tipo sobre una expresión con "-" (Prefix/Infix no es Integral/Decimal): ya existía y es del
    // área 3; no es la cascada por rango, cuyo lexema era el nombre de la variable (valor null)
    private static boolean EsTipoDeExpresion(Mensaje m) {
        return Integer.valueOf(201).equals(m.getCodigo()) && "-".equals(m.getLexema());
    }

    // Advertencias como "línea[lexema]"
    private static List<String> Advertencias(List<Mensaje> m) {
        List<String> r = new ArrayList<>();
        if (m != null) {
            for (Mensaje x : m) if (x.getCategoria() == Mensaje.Categoria.ADVERTENCIA) r.add(x.getLinea() + "[" + x.getLexema() + "]");
        }
        return r;
    }

    // Multiconjunto: se comparan ordenadas
    private static boolean MismoMulticonjunto(List<String> a, List<String> b) {
        List<String> x = new ArrayList<>(a), y = new ArrayList<>(b);
        Collections.sort(x);
        Collections.sort(y);
        return x.equals(y);
    }

    // Busca en el AST (por reflexión, solo clases del paquete Parser) un nodo de la clase dada
    private static boolean ContieneNodo(Object o, Class<?> clase, Map<Object, Boolean> vistos, int prof) throws IllegalAccessException {
        if (o == null || prof > 60) return false;
        if (clase.isInstance(o)) return true;
        if (vistos.put(o, Boolean.TRUE) != null) return false;
        if (o instanceof Iterable) {
            for (Object x : (Iterable<?>) o) if (ContieneNodo(x, clase, vistos, prof + 1)) return true;
            return false;
        }
        if (o.getClass().getPackage() == null || !o.getClass().getPackage().getName().equals("Parser")) return false;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                if (ContieneNodo(f.get(o), clase, vistos, prof + 1)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Tabla de reservadas del oráculo (REQ-ALGOL-03 + PRAGMAT, decisión 2), independiente de Token.java
    // ------------------------------------------------------------------

    private static final String ALGOL_MAYUSCULAS =
            "ABS AND ARG BEGIN BIN BITS BOOL BYTES CHANNEL CHAR COMMENT COMPL COMPLEX CONJ DET DIVAB DO DOUBLE "
            + "DOWN DYAD ELEM ELSIF END ENTIER EQ FALSE FILE FLEX FORMAT GE GT IM INT INV LE LENG LIBRARY LONG LT "
            + "LWB MINUSAB MOD MODAB MODE NE NIL NORM NOT ODD OP OR OVER OVERAB PIPE PLUSAB PLUSTO PR PRAGMA PRELUDE "
            + "PRIO PROC RE REAL REF REPEAT REPR ROUND ROW RETURN SEMA SHL SHORT SHORTEN SHR SIGN SKIP SOUND STANDARD "
            + "STRING STRUCT TIMESAB TRACE TRUE TRANSPUT UP UPB VOID WHILE XOR PRAGMAT";

    private static final String ALGOL_MINUSCULAS =
            "a68g abend abort abs airy alnum alpha angle arccos arccosh arcsin arcsinh arctan arctan2 arctanh "
            + "argc argv aspect associate back background backspace bessel beta bin blank break channel cholesky "
            + "circle clear clock close cntrl collect collections colour columns cos cosh cpu create curses curt "
            + "debug digit draw elliptic eof eoln erase erf error establish evaluate execve exp factorial fft fill "
            + "fixed flip float flop font fork format gamma garbage gc get getchar getf graph grep halt heap http "
            + "idf in incomplete integral inverse is last lengths line linestyle linewidth ln local lock log lower "
            + "lu make max min mksa modes move name new null num on open out pack page pi point pointer possible "
            + "preemptive print printf punct put putchar putf qr random read rect refresh reidf reset resolution "
            + "return routine scratch seconds set shorths show sin sinh size small sound space sqrt stack stand "
            + "stop style sub sv sweep system tan tanh tcp term text time upper value wait whole width will "
            + "window write xdigit newline";

    private static final Set<String> RESERVADAS_ALGOL = new HashSet<>();
    // Reservadas que el generador puede usar como palabra (sin CO/COMMENT, que abren comentario)
    private static final List<String> RESERVADAS_GENERABLES = new ArrayList<>();
    static {
        RESERVADAS_ALGOL.addAll(Arrays.asList(ALGOL_MAYUSCULAS.split(" ")));
        RESERVADAS_ALGOL.addAll(Arrays.asList(ALGOL_MINUSCULAS.split(" ")));
        Set<String> todas = new HashSet<>(RESERVADAS_ALGOL);
        todas.addAll(MINGOL_NEUTRAL.keySet());
        todas.remove("CO");
        todas.remove("COMMENT");
        RESERVADAS_GENERABLES.addAll(todas);
        Collections.sort(RESERVADAS_GENERABLES); // orden fijo: las semillas dan siempre lo mismo
    }

    // Tipo de token que el oráculo espera para una palabra completa (MINGOL primero, luego ALGOL)
    private static String TipoPalabra(String w) {
        if (MINGOL_NEUTRAL.containsKey(w)) return MINGOL_NEUTRAL.get(w);
        if (w.equals("CO") || w.equals("COMMENT")) return "COMMENT";
        if (RESERVADAS_ALGOL.contains(w)) return "ALGOL";
        return "IDENT";
    }

    private static boolean EsLetraAscii(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean EsParteAscii(char c) {
        return EsLetraAscii(c) || (c >= '0' && c <= '9') || c == '_';
    }

    // 103, 104 y 105 de un identificador no reservado, en ese orden
    private static List<String> OraculoIdent(String w, int linea) {
        List<String> r = new ArrayList<>();
        if (w.matches(".*[A-Z].*")) r.add(E(103, linea, w));
        if (w.length() > 20) r.add(E(104, linea, w));
        if (!EsLetraAscii(w.charAt(0)) || w.endsWith("_")) r.add(E(105, linea, w));
        return r;
    }

    // ==================================================================
    // Modo unitarias
    // ==================================================================

    // Caso unitario en proceso: lo que no es null se comprueba
    private static class CasoU {
        String id;
        String fuente;
        List<String> tokens;       // secuencia exacta sin EOF
        List<String> contiene;     // tokens que deben aparecer
        List<String> lexer;        // Lexer.GetMensajes() exacto y en orden
        List<String> parser1xx;    // 1xx de Parser.GetMensajes(), multiconjunto exacto
        List<String> advertencias; // advertencias de Parser.GetMensajes(), multiconjunto exacto
        List<Integer> prohibidos = new ArrayList<>(); // códigos que no deben aparecer en Parser.GetMensajes()
        Boolean booleano;          // el AST debe (o no) tener un nodo Booleano
    }

    private static CasoU U(String id, String... lineas) {
        CasoU c = new CasoU();
        c.id = id;
        c.fuente = Unir(lineas);
        return c;
    }

    private static List<CasoU> CasosUnitarios() {
        List<CasoU> l = new ArrayList<>();
        CasoU c;

        // 2.1 Dígitos dentro del identificador
        c = U("2.1 factor1", "factor1");
        c.tokens = L(T("IDENT", "factor1", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.1 arctan2", "arctan2");
        c.tokens = L(T("ALGOL", "arctan2", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.1 a_1_b2", "a_1_b2");
        c.tokens = L(T("IDENT", "a_1_b2", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.1 c10", "BEGIN", "INT factor1;", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), T("TYPEINT", "INT", 2), T("IDENT", "factor1", 2),
                T("SEMICOLON", ";", 2), EOL(2), T("END", "END", 3), EOL(3));
        c.lexer = L();
        c.parser1xx = L();
        l.add(c);

        // 2.2 Mayúsculas y posición de comando (L2)
        c = U("2.2 print ( (Factor1) ); con 103", "print ( (Factor1) );");
        c.lexer = L(E(103, 1, "Factor1"));
        c.parser1xx = L(E(103, 1, "Factor1"));
        l.add(c);
        c = U("2.2 MAX_ANOS := 1; con 103", "MAX_ANOS := 1;");
        c.tokens = L(T("IDENT", "MAX_ANOS", 1), T("ASSIGN", ":=", 1), T("INTEGER", "1", 1), T("SEMICOLON", ";", 1), EOL(1));
        c.lexer = L(E(103, 1, "MAX_ANOS"));
        c.parser1xx = L(E(103, 1, "MAX_ANOS"));
        l.add(c);
        c = U("2.2 x := MAX_ANOS; con 103", "x := MAX_ANOS;");
        c.parser1xx = L(E(103, 1, "MAX_ANOS"));
        l.add(c);
        c = U("L2 RREAL factor1; sin 103", "RREAL factor1;");
        c.lexer = L(E(103, 1, "RREAL"));
        c.parser1xx = L();
        l.add(c);
        c = U("L2 READ ( x ); sin 103", "READ ( x );");
        c.lexer = L(E(103, 1, "READ"));
        c.parser1xx = L();
        l.add(c);
        c = U("L2 Print ( \"x\" ); sin 103", "Print ( \"x\" );");
        c.lexer = L(E(103, 1, "Print"));
        c.parser1xx = L();
        l.add(c);
        c = U("L2 Factor1 := 2; con 103", "Factor1 := 2;");
        c.parser1xx = L(E(103, 1, "Factor1"));
        l.add(c);
        c = U("L2 Inicio: con 103", "Inicio:");
        c.tokens = L(T("ETIQUETA", "Inicio", 1), EOL(1));
        c.lexer = L(E(103, 1, "Inicio"));
        c.parser1xx = L(E(103, 1, "Inicio"));
        l.add(c);
        c = U("L2 REAL FACTOR1, factor2; con 103", "REAL FACTOR1, factor2;");
        c.lexer = L(E(103, 1, "FACTOR1"));
        c.parser1xx = L(E(103, 1, "FACTOR1"));
        l.add(c);
        c = U("L2 tras EOL: RREAL x; / Factor1 := 2;", "BEGIN", "RREAL x;", "Factor1 := 2;", "END");
        c.lexer = L(E(103, 2, "RREAL"), E(103, 3, "Factor1"));
        c.parser1xx = L(E(103, 3, "Factor1"));
        l.add(c);

        // 2.3 Longitud: 20 válido, 21 con 104
        c = U("2.3 20 caracteres", "abcdefghijklmnopqrst");
        c.tokens = L(T("IDENT", "abcdefghijklmnopqrst", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.3 21 caracteres (104)", "abcdefghijklmnopqrstu");
        c.tokens = L(T("IDENT", "abcdefghijklmnopqrstu", 1), EOL(1));
        c.lexer = L(E(104, 1, "abcdefghijklmnopqrstu"));
        l.add(c);
        c = U("2.3 21 con mayúscula (103, 104)", "Abcdefghijklmnopqrstu");
        c.lexer = L(E(103, 1, "Abcdefghijklmnopqrstu"), E(104, 1, "Abcdefghijklmnopqrstu"));
        l.add(c);

        // 2.4 "_" al inicio o al final y número pegado a letras
        for (String w : new String[]{"_x", "no_se_usa_", "_no_se_usa", "_", "1factor", "12_"}) {
            c = U("2.4 " + w + " (105)", w);
            c.tokens = L(T("IDENT", w, 1), EOL(1));
            c.lexer = L(E(105, 1, w));
            l.add(c);
        }
        c = U("2.4 1Factor_ (103, 105)", "1Factor_");
        c.tokens = L(T("IDENT", "1Factor_", 1), EOL(1));
        c.lexer = L(E(103, 1, "1Factor_"), E(105, 1, "1Factor_"));
        l.add(c);
        c = U("2.4 x_1 válido", "x_1");
        c.lexer = L();
        l.add(c);

        // 2.5 Reservada como etiqueta
        c = U("2.5 print: (106)", "print:");
        c.tokens = L(T("ETIQUETA", "print", 1), EOL(1));
        c.lexer = L(E(106, 1, "print"));
        l.add(c);
        c = U("2.5 LONG : (106)", "LONG :");
        c.tokens = L(T("ETIQUETA", "LONG", 1), EOL(1));
        c.lexer = L(E(106, 1, "LONG"));
        l.add(c);
        c = U("2.5 BEGIN: (106)", "BEGIN:");
        c.tokens = L(T("ETIQUETA", "BEGIN", 1), EOL(1));
        c.lexer = L(E(106, 1, "BEGIN"));
        l.add(c);
        c = U("2.5 end: válido", "end:");
        c.tokens = L(T("ETIQUETA", "end", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.5 CO: abre comentario", "CO: x CO");
        c.tokens = L(EOL(1));
        c.lexer = L();
        l.add(c);

        // 2.6 Comentarios: misma forma, sin tokenizar el contenido, un EOL por línea
        c = U("2.6 c2", "COMMENT dentro va CO suelto COMMENT", "BEGIN", "print ( newline );", "END");
        c.tokens = L(EOL(1));
        c.tokens.addAll(CuerpoC8(2));
        c.lexer = L();
        c.parser1xx = L();
        l.add(c);
        c = U("2.6 c3", "# texto con \"comilla #", "BEGIN", "print ( newline );", "END");
        c.tokens = L(EOL(1));
        c.tokens.addAll(CuerpoC8(2));
        c.lexer = L();
        l.add(c);
        c = U("2.6 c7", "CO Factor1 _x 1. CO", "BEGIN", "print ( newline );", "END");
        c.tokens = L(EOL(1));
        c.tokens.addAll(CuerpoC8(2));
        c.lexer = L();
        c.parser1xx = L();
        l.add(c);
        c = U("2.6 un EOL por línea", "CO a", "b", "c CO x");
        c.tokens = L(EOL(1), EOL(2), T("IDENT", "x", 3), EOL(3));
        c.lexer = L();
        l.add(c);
        c = U("2.6 COBOL/XCO/CO1 no cierran", "CO COBOL XCO CO1 co", "CO");
        c.tokens = L(EOL(1), EOL(2));
        c.lexer = L();
        l.add(c);
        c = U("2.6 cierre seguido de código", "# hola # x := 1;");
        c.tokens = L(T("IDENT", "x", 1), T("ASSIGN", ":=", 1), T("INTEGER", "1", 1), T("SEMICOLON", ";", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.6 otras formas dentro de #", "# CO COMMENT \" Factor1 #");
        c.tokens = L(EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.6 CO y # dentro de COMMENT", "COMMENT x CO # y COMMENT z");
        c.tokens = L(T("IDENT", "z", 1), EOL(1));
        c.lexer = L();
        l.add(c);

        // 2.7 Comentario sin cerrar
        c = U("2.7 c1", "CO abre aqui", "y cierra mal #", "BEGIN", "print ( newline );", "END");
        c.tokens = L(EOL(1), EOL(2), EOL(3), EOL(4), EOL(5));
        c.lexer = L(E(107, 1, "CO"));
        l.add(c);
        c = U("2.7 c9", "BEGIN", "print ( newline );", "END", "CO sin cerrar");
        c.tokens = CuerpoC8(1);
        c.tokens.add(EOL(4));
        c.lexer = L(E(107, 4, "CO"));
        c.parser1xx = L(E(107, 4, "CO"));
        l.add(c);
        c = U("2.7 # sin cerrar", "# abierto");
        c.tokens = L(EOL(1));
        c.lexer = L(E(107, 1, "#"));
        l.add(c);
        c = U("2.7 COMMENT sin cerrar en la línea 2", "x", "COMMENT", "y");
        c.tokens = L(T("IDENT", "x", 1), EOL(1), EOL(2), EOL(3));
        c.lexer = L(E(107, 2, "COMMENT"));
        l.add(c);
        // Pendiente 1 (C1): CO: / COMMENT: abren comentario, sin 106; el ":" es texto del comentario
        c = U("2.7 C1 CO: cerrado", "BEGIN", "CO: texto CO", "SKIP", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), EOL(2), T("SKIP", "SKIP", 3), EOL(3), T("END", "END", 4), EOL(4));
        c.lexer = L();
        c.prohibidos = new ArrayList<>(Arrays.asList(106));
        l.add(c);
        // Pendiente 1 (C1): sin cierre, SKIP y END quedan dentro del comentario
        c = U("2.7 C1 CO: sin cerrar", "BEGIN", "CO: texto", "SKIP", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), EOL(2), EOL(3), EOL(4));
        c.lexer = L(E(107, 2, "CO"));
        c.prohibidos = new ArrayList<>(Arrays.asList(106));
        l.add(c);
        // Pendiente 1 (C1): igual con COMMENT:
        c = U("2.7 C1 COMMENT: cerrado", "BEGIN", "COMMENT: texto COMMENT", "SKIP", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), EOL(2), T("SKIP", "SKIP", 3), EOL(3), T("END", "END", 4), EOL(4));
        c.lexer = L();
        c.prohibidos = new ArrayList<>(Arrays.asList(106));
        l.add(c);
        c = U("2.7 C1 COMMENT: sin cerrar", "BEGIN", "COMMENT: texto", "SKIP", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), EOL(2), EOL(3), EOL(4));
        c.lexer = L(E(107, 2, "COMMENT"));
        c.prohibidos = new ArrayList<>(Arrays.asList(106));
        l.add(c);

        // 2.8 Enteros (L4)
        Object[][] enteros = {
            {"32767", L(), L()},
            {"32768", L(), L(E(108, 1, "32768"))},
            {"-32768", L(), L()},
            {"- 32768", L(), L()},
            {"32769", L(E(108, 1, "32769")), L(E(108, 1, "32769"))},
            {"-32769", L(E(108, 1, "32769")), L(E(108, 1, "32769"))},
            {"a-32768", L(), L(E(108, 1, "32768"))},
            {"a - 32768", L(), L(E(108, 1, "32768"))},
            {"99999999999", L(E(108, 1, "99999999999")), L(E(108, 1, "99999999999"))},
        };
        for (Object[] e : enteros) {
            c = U("2.8 INT b := " + e[0] + ";", "INT b := " + e[0] + ";");
            c.lexer = Lista(e[1]);
            c.parser1xx = Lista(e[2]);
            c.prohibidos = new ArrayList<>(Arrays.asList(102, 201));
            l.add(c);
        }

        // 2.9 Real sin dígito antes o después del punto (L8)
        Object[][] mal = {
            {"1.", L(E(109, 1, "1."))},
            {"10.", L(E(109, 1, "10."))},
            {".5", L(E(109, 1, ".5"))},
            {"99999.", L(E(109, 1, "99999."), E(110, 1, "99999."))},
        };
        for (Object[] e : mal) {
            c = U("2.9 REAL r := " + e[0] + ";", "REAL r := " + e[0] + ";");
            c.contiene = L(T("REAL", (String) e[0], 1));
            c.lexer = Lista(e[1]);
            c.parser1xx = Lista(e[1]);
            c.prohibidos = new ArrayList<>(Arrays.asList(102, 201));
            l.add(c);
        }
        c = U("2.9 1..5 da dos 109", "1..5");
        c.tokens = L(T("REAL", "1.", 1), T("REAL", ".5", 1), EOL(1));
        c.lexer = L(E(109, 1, "1."), E(109, 1, ".5"));
        l.add(c);
        c = U("2.9 . suelto sigue DECIMAL", ".");
        c.tokens = L(T("DECIMAL", ".", 1), EOL(1));
        c.lexer = L();
        l.add(c);

        // 2.10 Reales (L4)
        Object[][] reales = {
            {"32767.99", L(), L()},
            {"32767.999", L(), L(E(110, 1, "32767.999"))},
            {"-32768.00", L(), L()},
            {"-32768.50", L(), L(E(110, 1, "32768.50"))},
            {"32768.00", L(), L(E(110, 1, "32768.00"))},
            {"32768.99", L(), L(E(110, 1, "32768.99"))},
            {"-32768.99", L(), L(E(110, 1, "32768.99"))},
            {"32769.0", L(E(110, 1, "32769.0")), L(E(110, 1, "32769.0"))},
        };
        for (Object[] e : reales) {
            c = U("2.10 REAL r := " + e[0] + ";", "REAL r := " + e[0] + ";");
            c.lexer = Lista(e[1]);
            c.parser1xx = Lista(e[2]);
            c.prohibidos = new ArrayList<>(Arrays.asList(102, 201));
            l.add(c);
        }

        // 2.11 Textos: 30 válido, 31 con 111
        String t30 = "abcdefghijklmnopqrstuvwxyz0123";
        String t31 = t30 + "4";
        c = U("2.11 texto de 30", "print ( \"" + t30 + "\" );");
        c.contiene = L(T("STRING", t30, 1));
        c.lexer = L();
        l.add(c);
        c = U("2.11 texto de 31 (111)", "print ( \"" + t31 + "\" );");
        c.contiene = L(T("STRING", t31, 1));
        c.lexer = L(E(111, 1, t31));
        l.add(c);

        // 2.12 Texto sin cierre (L5)
        c = U("2.12 c5", "BEGIN", "print ( \"abc );", "END");
        c.tokens = L(T("BEGIN", "BEGIN", 1), EOL(1), T("PRINT", "print", 2), T("LPAREN", "(", 2),
                T("STRING", "abc );", 2), EOL(2), T("END", "END", 3), EOL(3));
        c.lexer = L(E(112, 2, "\"abc );"));
        l.add(c);
        c = U("2.12 espacios finales fuera del lexema", "print ( \"abc   ", "x");
        c.tokens = L(T("PRINT", "print", 1), T("LPAREN", "(", 1), T("STRING", "abc   ", 1), EOL(1), T("IDENT", "x", 2), EOL(2));
        c.lexer = L(E(112, 1, "\"abc"));
        l.add(c);
        c = U("2.12 comilla sola", "\"", "x");
        c.tokens = L(T("STRING", "", 1), EOL(1), T("IDENT", "x", 2), EOL(2));
        c.lexer = L(E(112, 1, "\""));
        l.add(c);
        c = U("2.12 sin cierre de 35 (solo 112)", "\"" + t31 + "abcd");
        c.lexer = L(E(112, 1, "\"" + t31 + "abcd"));
        l.add(c);

        // 2.13 Línea de más de 80 columnas (L3): espacios finales, comentario y línea vacía
        c = U("2.13 80 columnas", "x" + " ".repeat(79));
        c.lexer = L();
        l.add(c);
        c = U("2.13 81 con espacios finales", "x" + " ".repeat(80));
        c.tokens = L(T("IDENT", "x", 1), EOL(1));
        c.lexer = L(E(113, 1, "81"));
        l.add(c);
        String p81 = "print ( newline );";
        c = U("2.13 81 tras un comando", p81 + " ".repeat(81 - p81.length()));
        c.lexer = L(E(113, 1, "81"));
        l.add(c);
        c = U("2.13 81 en un comentario", "CO " + "a".repeat(75) + " CO");
        c.tokens = L(EOL(1));
        c.lexer = L(E(113, 1, "81"));
        l.add(c);
        c = U("2.13 81 en una línea vacía", "x", " ".repeat(81), "y");
        c.tokens = L(T("IDENT", "x", 1), EOL(1), EOL(2), T("IDENT", "y", 3), EOL(3));
        c.lexer = L(E(113, 2, "81"));
        l.add(c);
        String l99 = "print ( \"" + "x".repeat(28) + "\" );";
        l99 = l99 + " ".repeat(99 - l99.length());
        c = U("2.13 c4", "BEGIN", l99, "END");
        c.lexer = L(E(113, 2, "99"));
        c.parser1xx = L(E(113, 2, "99"));
        l.add(c);

        // 2.14 y 2.15 Asignación pegada y etiqueta con blancos
        c = U("2.14 x:=3", "x:=3");
        c.tokens = L(T("IDENT", "x", 1), T("ASSIGN", ":=", 1), T("INTEGER", "3", 1), EOL(1));
        c.lexer = L();
        l.add(c);
        c = U("2.15 inicio :", "inicio :");
        c.tokens = L(T("ETIQUETA", "inicio", 1), EOL(1));
        c.lexer = L();
        c.parser1xx = L();
        l.add(c);
        c = U("2.15 inicio   :", "inicio   :");
        c.tokens = L(T("ETIQUETA", "inicio", 1), EOL(1));
        c.lexer = L();
        l.add(c);

        // 2.16 Operadores de dos caracteres con blanco
        Object[][] ops = {
            {"a < = b", "LE", "<=", "< ="}, {"a >  = b", "GE", ">=", ">  ="}, {"a / = b", "NE", "/=", "/ ="},
            {"x : = 1", "ASSIGN", ":=", ": ="}, {"inicio : = 1", "ASSIGN", ":=", ": ="},
            {"a <= b", "LE", "<=", null}, {"a < b", "LT", "<", null}, {"a / b", "DIVISION", "/", null},
        };
        for (Object[] o : ops) {
            c = U("2.16 " + o[0], (String) o[0]);
            String der = ((String) o[0]).endsWith("1") ? "1" : "b";
            String izq = ((String) o[0]).split(" ")[0];
            c.tokens = L(T("IDENT", izq, 1), T((String) o[1], (String) o[2], 1),
                    T(der.equals("1") ? "INTEGER" : "IDENT", der, 1), EOL(1));
            c.lexer = o[3] == null ? L() : L(E(114, 1, (String) o[3]));
            l.add(c);
        }
        c = U("2.16 1 : b sigue ILLEGAL", "1 : b");
        c.tokens = L(T("INTEGER", "1", 1), T("ILLEGAL", ":", 1), T("IDENT", "b", 1), EOL(1));
        c.lexer = L();
        l.add(c);

        // 2.18 y L7: STRING/TRUE/FALSE son ALGOL; advertencia solo en posición de comando
        for (String w : new String[]{"STRING s;", "TRUE;", "FALSE;", "ENTIER x;", "PRAGMA x;", "grep;", "lengths;"}) {
            String p = w.split("[ ;]")[0];
            c = U("2.18 " + w + " con advertencia", w);
            c.contiene = L(T("ALGOL", p, 1));
            c.advertencias = L("1[" + p + "]");
            l.add(c);
        }
        c = U("L7 IF ( TRUE ) THEN sin advertencia y con Booleano", "IF ( TRUE ) THEN", "SKIP", "FI;");
        c.contiene = L(T("ALGOL", "TRUE", 1));
        c.advertencias = L();
        c.booleano = true;
        l.add(c);
        // "INT b := FALSE;" da 210 (área 3, sintaxis corregida, decisión 3; antes el 201 de tipo): solo sin advertencia
        c = U("L7 INT b := FALSE; sin advertencia", "INT b := FALSE;");
        c.advertencias = L();
        l.add(c);
        c = U("L7 IF ( FALSE ) THEN sin advertencia y con Booleano", "IF ( FALSE ) THEN", "SKIP", "FI;");
        c.advertencias = L();
        c.booleano = true;
        l.add(c);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static List<String> Lista(Object o) {
        return (List<String>) o;
    }

    private static void Unitarias() throws Exception {
        Path carpeta = CarpetaSegura();
        Path trabajo = carpeta.resolve("unitarias");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        Class<?> booleano = Class.forName("Parser.Booleano");

        for (CasoU c : CasosUnitarios()) {
            List<String> dif = new ArrayList<>();
            Lectura lx = Leer(c.fuente, dif);
            if (c.tokens != null && !c.tokens.equals(lx.tokens)) dif.add("tokens: " + PrimeraDiferencia(c.tokens, lx.tokens) + "; obtenidos " + lx.tokens);
            if (c.contiene != null) for (String t : c.contiene) if (!lx.tokens.contains(t)) dif.add("falta el token " + t + "; obtenidos " + lx.tokens);
            if (c.lexer != null && !c.lexer.equals(lx.mensajes)) dif.add("Lexer.GetMensajes(): esperado " + c.lexer + ", obtenido " + lx.mensajes);
            if (c.parser1xx != null || c.advertencias != null || !c.prohibidos.isEmpty() || c.booleano != null) {
                Analisis a = Analizar(c.fuente, dif);
                if (a != null) {
                    List<String> lex = Lexicos(a.mensajes);
                    if (c.parser1xx != null && !MismoMulticonjunto(c.parser1xx, lex)) dif.add("1xx del parser: esperado " + c.parser1xx + ", obtenido " + lex);
                    List<String> adv = Advertencias(a.mensajes);
                    if (c.advertencias != null && !MismoMulticonjunto(c.advertencias, adv)) dif.add("advertencias: esperado " + c.advertencias + ", obtenido " + adv);
                    for (Mensaje m : a.mensajes) {
                        if (m.getCodigo() != null && c.prohibidos.contains(m.getCodigo()) && !EsTipoDeExpresion(m)) dif.add("aparece " + m.Formatear());
                    }
                    if (c.booleano != null) {
                        boolean hay = ContieneNodo(a.programa, booleano, new IdentityHashMap<>(), 0);
                        if (hay != c.booleano) dif.add("Booleano en el AST: esperado " + c.booleano + ", obtenido " + hay);
                    }
                }
            }
            Registrar(c.id, "U", true, dif);
        }

        UnitariaTabla();
        UnitariasApiLexer();
        UnitariasFor(trabajo);
        UnitariasTestProyecto(trabajo);
        ImprimirResumen("unitarias");
    }

    // 2.17: tabla de reservadas contra REQ-ALGOL-03 (oráculo propio) y las entradas quitadas
    private static void UnitariaTabla() {
        List<String> dif = new ArrayList<>();
        for (String w : RESERVADAS_ALGOL) {
            String obtenido = Token.LookupTokenType(w).name();
            if (!obtenido.equals(TipoPalabra(w))) dif.add(w + ": esperado " + TipoPalabra(w) + ", obtenido " + obtenido);
            if (!Token.EsReservada(w)) dif.add(w + ": EsReservada=false");
        }
        for (String w : new String[]{"ENTRIER", "lenghts", "", "size=INT", "Print", "READ", "end"}) {
            if (Token.LookupTokenType(w) != MingolToken.IDENT) dif.add("[" + w + "] debería ser IDENT, es " + Token.LookupTokenType(w));
            if (Token.EsReservada(w)) dif.add("[" + w + "]: EsReservada=true");
        }
        Registrar("2.17 tabla de reservadas = REQ-ALGOL-03 + PRAGMAT (" + RESERVADAS_ALGOL.size() + " palabras)", "U", true, dif);
    }

    // API del lexer: GetMensajes en orden y como copia, RetirarMayusculas y HayErrores
    private static void UnitariasApiLexer() {
        // Orden de registro: 113 (constructor) y luego los del lexema en orden de aparición
        List<String> dif = new ArrayList<>();
        String largo = "Abcdefghijklmnopqrstu";
        String linea = "Factor1 " + largo + " _y";
        linea = linea + " ".repeat(85 - linea.length());
        Lectura lx = Leer(linea + "\n", dif);
        List<String> esperado = L(E(113, 1, "85"), E(103, 1, "Factor1"), E(103, 1, largo), E(104, 1, largo), E(105, 1, "_y"));
        if (!esperado.equals(lx.mensajes)) dif.add("esperado " + esperado + ", obtenido " + lx.mensajes);
        if (lx.lexer != null) {
            lx.lexer.GetMensajes().clear();
            if (lx.lexer.GetMensajes().size() != 5) dif.add("GetMensajes() no devuelve una copia");
        }
        Registrar("API GetMensajes() en orden y como copia", "U", true, dif);

        // RetirarMayusculas y HayErrores
        dif = new ArrayList<>();
        lx = Leer("Factor1 x\n", dif);
        // Tokens: Factor1, x y el EOL
        if (lx.lexer != null && lx.crudos.size() == 3) {
            Lexer lexer = lx.lexer;
            Token conMayus = lx.crudos.get(0), sinMayus = lx.crudos.get(1);
            if (!lexer.HayErrores()) dif.add("HayErrores()=false con un 103");
            lexer.RetirarMayusculas(sinMayus);
            if (lexer.GetMensajes().size() != 1) dif.add("retirar un token sin 103 cambió los mensajes: " + Formateados(lexer.GetMensajes()));
            lexer.RetirarMayusculas(new Token(MingolToken.IDENT, "Factor1"));
            if (lexer.GetMensajes().size() != 1) dif.add("retirar un token ajeno con el mismo literal quitó el 103");
            lexer.RetirarMayusculas(conMayus);
            if (!lexer.GetMensajes().isEmpty()) dif.add("el 103 no se retiró: " + Formateados(lexer.GetMensajes()));
            if (lexer.HayErrores()) dif.add("HayErrores()=true sin mensajes");
            lexer.RetirarMayusculas(conMayus);
            if (!lexer.GetMensajes().isEmpty()) dif.add("retirar dos veces cambió los mensajes");
        } else {
            dif.add("tokens inesperados " + lx.tokens);
        }
        Lectura limpio = Leer("x\n", dif);
        if (limpio.lexer != null && limpio.lexer.HayErrores()) dif.add("HayErrores()=true en un fuente sin errores");
        // El 103 de una etiqueta no se retira (solo los IDENT)
        Lectura et = Leer("Inicio:\n", dif);
        if (et.lexer != null && !et.crudos.isEmpty()) {
            et.lexer.RetirarMayusculas(et.crudos.get(0));
            if (et.lexer.GetMensajes().size() != 1) dif.add("se retiró el 103 de una ETIQUETA");
        }
        Registrar("API RetirarMayusculas y HayErrores", "U", true, dif);
    }

    // 2.19: FOR con constantes fuera de rango, en un hijo (R8)
    private static void UnitariasFor(Path trabajo) throws Exception {
        String[][] casos = {
            {"FROM 99999999999", "FOR i FROM 99999999999 BY 1 TO 3", "108@3[99999999999]"},
            {"BY 32768", "FOR i FROM 1 BY 32768 TO 3", "108@3[32768]"},
            {"TO 32769", "FOR i FROM 1 BY 1 TO 32769", "108@3[32769]"},
            {"en rango", "FOR i FROM 1 BY 1 TO 32767", null},
        };
        int n = 0;
        for (String[] c : casos) {
            n++;
            List<String> dif = new ArrayList<>();
            Path dir = trabajo.resolve("for" + n);
            Files.createDirectories(dir);
            // Igual que el caso 9 de la exploración ("INT i;" sin valor es del área 3)
            String fuente = Unir(new String[]{"BEGIN", "INT i := 0;", c[1], "DO", "SKIP", "OD;", "END"});
            Files.write(dir.resolve("for.mingol"), fuente.getBytes(StandardCharsets.ISO_8859_1));
            ResultadoHijo r = LanzarHijo(L("hijofor", "for.mingol"), dir, "for");
            ProblemasDeEjecucion(r, dif);
            if (r.todo().contains("java.lang.NumberFormatException")) dif.add("NumberFormatException");
            List<String> lex = LineasConPrefijo(r.stdout, "LEX|");
            List<String> esperado = c[2] == null ? L() : L(c[2]);
            if (!MismoMulticonjunto(esperado, lex)) dif.add("1xx: esperado " + esperado + ", obtenido " + lex);
            Registrar("2.19 FOR " + c[0], "U", true, dif);
        }
    }

    // TestProyecto sobre c1–c14 (c11–c14: pendiente 1, C1): archivo de errores esperado (ISO-8859-1, R6) y validez, en un hijo (R8)
    private static class CasoTP {
        String id;
        String[] lineas;
        Map<Integer, List<String>> bajo = new HashMap<>(); // 1xx (o todos, si exacto) bajo cada línea, en orden
        List<String> finales = new ArrayList<>();           // bloque final (solo si exacto)
        boolean exacto;                                     // el archivo completo debe coincidir
        List<String> sin = new ArrayList<>();               // textos que no deben aparecer
        Boolean valido;                                     // null: depende del área 3, no se comprueba
    }

    private static CasoTP TP(String id, boolean exacto, Boolean valido, String... lineas) {
        CasoTP c = new CasoTP();
        c.id = id;
        c.exacto = exacto;
        c.valido = valido;
        c.lineas = lineas;
        return c;
    }

    private static List<CasoTP> CasosTP() {
        List<CasoTP> l = new ArrayList<>();
        CasoTP c;
        c = TP("c1", true, false, "CO abre aqui", "y cierra mal #", "BEGIN", "print ( newline );", "END");
        c.bajo.put(1, L(F(107, "CO")));
        // BEGIN y END quedaron dentro del comentario: 307 y 308 reales, en el bloque final
        c.finales = L("ERROR 307: No se encontró una sentencia BEGIN [BEGIN].", "ERROR 308: No se encontró una sentencia END [END].");
        l.add(c);
        // Área 3 (REQ-LINE-04 corregido): c2, c3, c4, c6, c7, c8 y c9 sin ";" en la línea anterior a END
        // (si no, el nuevo 206 los hace inválidos o agrega un mensaje al archivo exacto)
        l.add(TP("c2", true, true, "COMMENT dentro va CO suelto COMMENT", "BEGIN", "print ( newline )", "END"));
        l.add(TP("c3", true, true, "# texto con \"comilla #", "BEGIN", "print ( newline )", "END"));
        // c4: el relleno conserva las 99 columnas
        String l99 = "print ( \"" + "x".repeat(28) + "\" )";
        l99 = l99 + " ".repeat(99 - l99.length());
        c = TP("c4", true, false, "BEGIN", l99, "END");
        c.bajo.put(2, L(F(113, "99")));
        l.add(c);
        // c5: después del 112 sigue lo sintáctico de la línea (área 3); se exige el 112 primero y sin 308
        c = TP("c5", false, false, "BEGIN", "print ( \"abc );", "END");
        c.bajo.put(2, L(F(112, "\"abc );")));
        c.sin.add("ERROR 308");
        l.add(c);
        String t31 = "abcdefghijklmnopqrstuvwxyz01234";
        c = TP("c6", true, false, "BEGIN", "print ( \"" + t31 + "\" )", "END");
        c.bajo.put(2, L(F(111, t31)));
        l.add(c);
        l.add(TP("c7", true, true, "CO Factor1 _x 1. CO", "BEGIN", "print ( newline )", "END"));
        l.add(TP("c8", true, true, "BEGIN", "print ( newline )", "END"));
        c = TP("c9", true, false, "BEGIN", "print ( newline )", "END", "CO sin cerrar");
        c.bajo.put(4, L(F(107, "CO")));
        l.add(c);
        // c10: sin 1xx; desde el área 3 (sintaxis corregida) da 206 [;] y 217 [END] por no tener
        // comandos (no se comprueba la validez)
        l.add(TP("c10", false, null, "BEGIN", "INT factor1;", "END"));
        // Pendiente 1 (C1): CO: / COMMENT: abren comentario, sin 106 (archivo exacto, sin mensajes)
        l.add(TP("c11", true, true, "BEGIN", "CO: texto CO", "SKIP", "END"));
        l.add(TP("c13", true, true, "BEGIN", "COMMENT: texto COMMENT", "SKIP", "END"));
        // Pendiente 1 (C1): sin cierre, 107 bajo la apertura; el 308 (EOF en la línea 5) va bajo la
        // última línea por D1, así que se espera en bajo(4) y no en finales; ausencia explícita del 106
        c = TP("c12", true, false, "BEGIN", "CO: texto", "SKIP", "END");
        c.bajo.put(2, L(F(107, "CO")));
        c.bajo.put(4, L("ERROR 308: No se encontró una sentencia END [END]."));
        c.sin.add("ERROR 106");
        l.add(c);
        c = TP("c14", true, false, "BEGIN", "COMMENT: texto", "SKIP", "END");
        c.bajo.put(2, L(F(107, "COMMENT")));
        c.bajo.put(4, L("ERROR 308: No se encontró una sentencia END [END]."));
        c.sin.add("ERROR 106");
        l.add(c);
        return l;
    }

    private static void UnitariasTestProyecto(Path trabajo) throws Exception {
        for (CasoTP c : CasosTP()) {
            List<String> dif = new ArrayList<>();
            Path dir = trabajo.resolve("tp-" + c.id);
            Files.createDirectories(dir);
            String archivo = c.id + ".mingol";
            Files.write(dir.resolve(archivo), Unir(c.lineas).getBytes(StandardCharsets.ISO_8859_1));
            ResultadoHijo r = LanzarHijo(L("hijotp", archivo), dir, c.id);
            ProblemasDeEjecucion(r, dif);
            String tp = LineaConPrefijo(r.stdout, "TestProyecto=");
            if (c.valido != null && !("TestProyecto=" + c.valido).equals(tp)) dif.add("esperado TestProyecto=" + c.valido + ", obtenido " + tp);
            Path errores = dir.resolve(c.id + "-errores.txt");
            if (!Files.exists(errores)) {
                dif.add("no se generó " + errores.getFileName());
            } else {
                String texto = new String(Files.readAllBytes(errores), StandardCharsets.ISO_8859_1);
                List<String> arch = Arrays.asList(texto.split("\n", -1));
                if (c.exacto) {
                    // Archivo completo esperado
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= c.lineas.length; i++) {
                        sb.append(String.format("%05d", i)).append(' ').append(c.lineas[i - 1]).append('\n');
                        for (String m : c.bajo.getOrDefault(i, L())) sb.append(S8).append(m).append('\n');
                    }
                    for (String m : c.finales) sb.append(S8).append(m).append('\n');
                    List<String> esp = Arrays.asList(sb.toString().split("\n", -1));
                    if (!esp.equals(arch)) dif.add("-errores.txt: " + PrimeraDiferencia(esp, arch));
                } else {
                    // Copia numerada exacta, 1xx exactos y primeros bajo su línea, y textos prohibidos
                    List<String> numeradas = new ArrayList<>();
                    for (String a : arch) if (!a.startsWith(S8) && !a.isEmpty()) numeradas.add(a);
                    List<String> espNum = new ArrayList<>();
                    for (int i = 1; i <= c.lineas.length; i++) espNum.add(String.format("%05d", i) + " " + c.lineas[i - 1]);
                    if (!espNum.equals(numeradas)) dif.add("copia numerada: " + PrimeraDiferencia(espNum, numeradas));
                    List<String> lexEsp = new ArrayList<>(), lexObt = new ArrayList<>();
                    for (int i = 1; i <= c.lineas.length; i++) {
                        for (String m : c.bajo.getOrDefault(i, L())) lexEsp.add(i + "|" + m);
                        List<String> bajo = MensajesBajo(arch, i);
                        boolean otro = false;
                        for (String m : bajo) {
                            boolean lexico = m.startsWith(S8 + "ERROR 1");
                            if (lexico) {
                                lexObt.add(i + "|" + m.substring(S8.length()));
                                if (otro) dif.add("bajo la línea " + i + " un 1xx va después de otro mensaje: " + bajo);
                            } else {
                                otro = true;
                            }
                        }
                    }
                    if (!lexEsp.equals(lexObt)) dif.add("1xx del archivo: esperado " + lexEsp + ", obtenido " + lexObt);
                }
                // Pendiente 1 (C1): los textos prohibidos se revisan en los dos modos (antes, solo en el no exacto)
                for (String s : c.sin) for (String a : arch) if (a.contains(s)) dif.add("el -errores.txt contiene [" + a.trim() + "]");
            }
            Registrar("TestProyecto " + c.id, "U", true, dif);
        }
    }

    // ==================================================================
    // Modo propiedades (Property 1 por familia, Property 2 y consistencia de hasErrors)
    // ==================================================================

    private static final int SEMILLAS_PROPIEDAD = 300;
    // Bases de semilla por familia (fijas)
    private static final long BASE_IDENT = 100_000, BASE_NUM = 200_000, BASE_TEXTO = 300_000,
            BASE_COMENT = 400_000, BASE_OPER = 500_000, BASE_P2 = 600_000, BASE_CONS = 700_000;

    // Fuente generado con lo que el oráculo espera (null = no se comprueba)
    private static class Generado {
        String[] lineas;
        List<String> tokens;
        List<String> lexer;
        List<String> parser1xx;
    }

    private static final String LETRAS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // --- Identificadores: [A-Za-z0-9_] de 1 a 25, prefijo letra, "_" o dígito, con ":" pegado o con blancos ---
    private static Generado GenIdent(Random r) {
        String w;
        while (true) {
            if (r.nextInt(6) == 0) {
                w = RESERVADAS_GENERABLES.get(r.nextInt(RESERVADAS_GENERABLES.size()));
            } else {
                int n = r.nextInt(3) == 0 ? 18 + r.nextInt(5) : 1 + r.nextInt(25);
                StringBuilder sb = new StringBuilder();
                int k = r.nextInt(10);
                sb.append(k < 6 ? LETRAS.charAt(r.nextInt(52)) : k < 8 ? '_' : (char) ('0' + r.nextInt(10)));
                for (int i = 1; i < n; i++) {
                    int q = r.nextInt(10);
                    if (q < 5) sb.append(MINUSCULAS.charAt(r.nextInt(26)));
                    else if (q < 7) sb.append((char) ('A' + r.nextInt(26)));
                    else if (q < 9) sb.append((char) ('0' + r.nextInt(10)));
                    else sb.append('_');
                }
                w = sb.toString();
                // Solo dígitos sería un INTEGER: no es de esta familia
                if (w.matches("[0-9]+")) w = w + "a";
            }
            if (!w.equals("CO") && !w.equals("COMMENT")) break;
        }
        int suf = r.nextInt(5);
        String sufijo = suf == 1 ? ":" : suf == 2 ? " ".repeat(1 + r.nextInt(3)) + ":" : suf == 3 ? " := 1" : suf == 4 ? " x" : "";
        Generado g = new Generado();
        g.lineas = new String[]{w + sufijo};
        g.tokens = new ArrayList<>();
        g.lexer = new ArrayList<>();
        boolean etiqueta = suf == 1 || suf == 2;
        if (Character.isDigit(w.charAt(0))) {
            // Número pegado a letras: IDENT completo con 105; el ":" que sigue no hace etiqueta
            g.tokens.add(T("IDENT", w, 1));
            g.lexer.addAll(OraculoIdent(w, 1));
            if (etiqueta) g.tokens.add(T("ILLEGAL", ":", 1));
        } else if (etiqueta) {
            g.tokens.add(T("ETIQUETA", w, 1));
            if (!TipoPalabra(w).equals("IDENT")) g.lexer.add(E(106, 1, w));
            else g.lexer.addAll(OraculoIdent(w, 1));
        } else {
            String tipo = TipoPalabra(w);
            g.tokens.add(T(tipo, w, 1));
            if (tipo.equals("IDENT")) g.lexer.addAll(OraculoIdent(w, 1));
        }
        if (suf == 3) {
            g.tokens.add(T("ASSIGN", ":=", 1));
            g.tokens.add(T("INTEGER", "1", 1));
        } else if (suf == 4) {
            g.tokens.add(T("IDENT", "x", 1));
        }
        g.tokens.add(EOL(1));
        return g;
    }

    // --- Números: magnitudes alrededor de los límites, con o sin "-" unario o binario (L4, L8) ---
    private static final BigInteger O_MAX_ENTERO = BigInteger.valueOf(32768);
    private static final BigDecimal O_MAX_REAL = new BigDecimal("32768.99");
    private static final BigDecimal O_MAX_REAL_POS = new BigDecimal("32767.99");
    private static final BigDecimal O_MAX_REAL_NEG = new BigDecimal("32768.00");

    private static String ParteEntera(Random r) {
        int k = r.nextInt(8);
        if (k < 3) return new String[]{"32767", "32768", "32769", "0", "1"}[r.nextInt(5)];
        if (k < 6) return String.valueOf(r.nextInt(40000));
        // Muy grande: hasta 15 dígitos
        StringBuilder sb = new StringBuilder(String.valueOf(1 + r.nextInt(9)));
        int n = 4 + r.nextInt(11);
        for (int i = 0; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private static String Decimales(Random r) {
        if (r.nextInt(3) == 0) return new String[]{"99", "00", "999", "0", "50", "991", "01"}[r.nextInt(7)];
        int n = 1 + r.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private static Generado GenNumero(Random r) {
        int forma = r.nextInt(4);
        String lit;
        boolean real = forma != 0;
        if (forma == 0) lit = ParteEntera(r);
        else if (forma == 1) lit = ParteEntera(r) + "." + Decimales(r);
        else if (forma == 2) lit = ParteEntera(r) + ".";
        else lit = "." + Decimales(r);
        String signo = new String[]{"", "-", "- ", "b-", "b - "}[r.nextInt(5)];
        boolean negada = signo.equals("-") || signo.equals("- ");
        Generado g = new Generado();
        // Declaración del tipo de la constante, para no provocar el 201 de tipo (que no es de esta familia)
        String tipo = real ? "REAL" : "INT", var = real ? "r" : "a";
        g.lineas = new String[]{tipo + " " + var + " := " + signo + lit + ";"};
        g.tokens = L(T(real ? "TYPEREAL" : "TYPEINT", tipo, 1), T("IDENT", var, 1), T("ASSIGN", ":=", 1));
        if (signo.startsWith("b")) g.tokens.add(T("IDENT", "b", 1));
        if (!signo.isEmpty()) g.tokens.add(T("SUBSTRACTION", "-", 1));
        g.tokens.add(T(real ? "REAL" : "INTEGER", lit, 1));
        g.tokens.add(T("SEMICOLON", ";", 1));
        g.tokens.add(EOL(1));
        g.lexer = new ArrayList<>();
        g.parser1xx = new ArrayList<>();
        if (!real) {
            int cmp = new BigInteger(lit).compareTo(O_MAX_ENTERO);
            if (cmp > 0) g.lexer.add(E(108, 1, lit));
            g.parser1xx.addAll(g.lexer);
            if (cmp == 0 && !negada) g.parser1xx.add(E(108, 1, lit));
        } else {
            if (lit.startsWith(".") || lit.endsWith(".")) g.lexer.add(E(109, 1, lit));
            BigDecimal m = new BigDecimal(lit);
            if (m.compareTo(O_MAX_REAL) > 0) g.lexer.add(E(110, 1, lit));
            g.parser1xx.addAll(g.lexer);
            if (m.compareTo(O_MAX_REAL) <= 0 && m.compareTo(negada ? O_MAX_REAL_NEG : O_MAX_REAL_POS) > 0) g.parser1xx.add(E(110, 1, lit));
        }
        return g;
    }

    // --- Textos y líneas: 0 a 40 caracteres, cerrados o no, líneas de 60 a 100 columnas ---
    private static Generado GenTexto(Random r) {
        int n = r.nextInt(5) == 0 ? 30 + r.nextInt(2) : r.nextInt(41);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(CHARS_TEXTO.charAt(r.nextInt(CHARS_TEXTO.length())));
        String contenido = sb.toString();
        boolean cerrado = r.nextInt(3) != 0;
        String base = "print ( \"" + contenido + (cerrado ? "\" );" : "");
        int objetivo = r.nextInt(4) == 0 ? 80 + r.nextInt(2) : 60 + r.nextInt(41);
        String l1 = base + " ".repeat(Math.max(0, objetivo - base.length()));
        List<String> lineas = L(l1);
        boolean vacia = r.nextInt(3) == 0;
        if (vacia) lineas.add(" ".repeat(60 + r.nextInt(41)));
        lineas.add("x");
        Generado g = new Generado();
        g.lineas = lineas.toArray(new String[0]);
        g.lexer = new ArrayList<>();
        for (int i = 0; i < g.lineas.length; i++) {
            if (g.lineas[i].length() > 80) g.lexer.add(E(113, i + 1, String.valueOf(g.lineas[i].length())));
        }
        g.tokens = L(T("PRINT", "print", 1), T("LPAREN", "(", 1));
        if (cerrado) {
            if (contenido.length() > 30) g.lexer.add(E(111, 1, contenido));
            g.tokens.add(T("STRING", contenido, 1));
            g.tokens.add(T("RPAREN", ")", 1));
            g.tokens.add(T("SEMICOLON", ";", 1));
        } else {
            // El texto llega hasta el final de la línea física, espacios incluidos (L5)
            String hastaFin = l1.substring(9);
            g.lexer.add(E(112, 1, "\"" + hastaFin.stripTrailing()));
            g.tokens.add(T("STRING", hastaFin, 1));
        }
        g.tokens.add(EOL(1));
        int lx = 2;
        if (vacia) g.tokens.add(EOL(lx++));
        g.tokens.add(T("IDENT", "x", lx));
        g.tokens.add(EOL(lx));
        return g;
    }

    // --- Comentarios: forma aleatoria, otras formas, comillas, COBOL, varias líneas, cerrado o no ---
    private static final String[] POOL_COMUN = {"texto", "Factor1", "_x", "1.", ".5", "99999", ":=", "< =", "print:",
        "\"", "\"abc", "abc\"", "%", "'", "x:", "32768.999", "a_", "LONG", "end"};

    private static String[] PoolForma(String forma) {
        switch (forma) {
            case "CO": return new String[]{"COMMENT", "#", "COBOL", "XCO", "CO1", "co", "C"};
            case "COMMENT": return new String[]{"CO", "#", "COMMENTS", "xCOMMENT", "COMMENT_", "CO1"};
            default: return new String[]{"CO", "COMMENT", "COBOL"};
        }
    }

    // Oráculo propio: posición justo después del cierre (misma forma) o -1 si no cierra
    private static int FinDeComentario(String f, int desde, String forma) {
        if (forma.equals("#")) {
            int k = f.indexOf('#', desde);
            return k < 0 ? -1 : k + 1;
        }
        int i = desde;
        while (i < f.length()) {
            if (EsParteAscii(f.charAt(i))) {
                int j = i;
                while (j < f.length() && EsParteAscii(f.charAt(j))) j++;
                if (f.substring(i, j).equals(forma)) return j;
                i = j;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static Generado GenComentario(Random r) {
        while (true) {
            String forma = new String[]{"CO", "COMMENT", "#"}[r.nextInt(3)];
            String[] pool = PoolForma(forma);
            List<String> lineas = new ArrayList<>();
            boolean previa = r.nextInt(3) == 0;
            if (previa) lineas.add("a");
            int apertura = lineas.size(); // índice de la línea de apertura
            int n = 1 + r.nextInt(3);
            boolean cerrado = r.nextInt(4) != 0;
            int sufijo = r.nextInt(3);
            int columnaFin = -1; // columna justo después del cierre en la última línea del comentario
            for (int i = 0; i < n; i++) {
                StringBuilder sb = new StringBuilder(i == 0 ? forma : "");
                int palabras = r.nextInt(6);
                for (int j = 0; j < palabras; j++) {
                    String p = r.nextInt(3) == 0 ? pool[r.nextInt(pool.length)] : POOL_COMUN[r.nextInt(POOL_COMUN.length)];
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(p);
                }
                if (i == n - 1 && cerrado) {
                    sb.append(' ').append(forma);
                    columnaFin = sb.length();
                    sb.append(new String[]{"", " x := 1;", " print ( newline );"}[sufijo]);
                }
                // A veces la línea pasa de 80 columnas con espacios finales (113 también en comentarios)
                if (r.nextInt(6) == 0) sb.append(" ".repeat(Math.max(0, 81 + r.nextInt(20) - sb.length())));
                lineas.add(sb.toString());
            }
            boolean posterior = r.nextBoolean();
            if (posterior) lineas.add("y");
            String fuente = Unir(lineas.toArray(new String[0]));
            int offApertura = 0;
            for (int i = 0; i < apertura; i++) offApertura += lineas.get(i).length() + 1;
            int offUltima = 0;
            for (int i = 0; i < apertura + n - 1; i++) offUltima += lineas.get(i).length() + 1;
            int cierre = FinDeComentario(fuente, offApertura + forma.length(), forma);
            int esperado = cerrado ? offUltima + columnaFin : -1;
            // El contenido cerró antes de lo previsto: se descarta y se genera otro con el mismo Random
            if (cierre != esperado) continue;

            Generado g = new Generado();
            g.lineas = lineas.toArray(new String[0]);
            g.lexer = new ArrayList<>();
            for (int i = 0; i < g.lineas.length; i++) {
                if (g.lineas[i].length() > 80) g.lexer.add(E(113, i + 1, String.valueOf(g.lineas[i].length())));
            }
            if (!cerrado) g.lexer.add(E(107, apertura + 1, forma));
            g.tokens = new ArrayList<>();
            if (previa) {
                g.tokens.add(T("IDENT", "a", 1));
                g.tokens.add(EOL(1));
            }
            for (int i = 0; i < n; i++) {
                int ln = apertura + i + 1;
                if (i == n - 1 && cerrado) {
                    if (sufijo == 1) g.tokens.addAll(L(T("IDENT", "x", ln), T("ASSIGN", ":=", ln), T("INTEGER", "1", ln), T("SEMICOLON", ";", ln)));
                    if (sufijo == 2) g.tokens.addAll(L(T("PRINT", "print", ln), T("LPAREN", "(", ln), T("NEWLINE", "newline", ln),
                            T("RPAREN", ")", ln), T("SEMICOLON", ";", ln)));
                }
                g.tokens.add(EOL(ln));
            }
            if (posterior) {
                int ln = apertura + n + 1;
                // Sin cierre, la línea "y" está dentro del comentario: solo su EOL
                if (cerrado) g.tokens.add(T("IDENT", "y", ln));
                g.tokens.add(EOL(ln));
            }
            return g;
        }
    }

    // --- Operadores: <, >, /, : con 0 a 3 espacios antes de "=" (o sin "=") ---
    private static Generado GenOperador(Random r) {
        char op = "<>/:".charAt(r.nextInt(4));
        int esp = r.nextInt(4);
        boolean igual = r.nextInt(4) != 0;
        boolean izqNum = r.nextInt(3) == 0;
        String izq = izqNum ? "1" : "a";
        String sp1 = r.nextBoolean() ? " " : "";
        Generado g = new Generado();
        g.lineas = new String[]{izq + sp1 + op + " ".repeat(esp) + (igual ? "=" : "") + " b"};
        g.tokens = new ArrayList<>();
        g.lexer = new ArrayList<>();
        boolean etiqueta = op == ':' && !igual && !izqNum;
        g.tokens.add(izqNum ? T("INTEGER", "1", 1) : T(etiqueta ? "ETIQUETA" : "IDENT", "a", 1));
        if (igual) {
            String tipo = op == '<' ? "LE" : op == '>' ? "GE" : op == '/' ? "NE" : "ASSIGN";
            g.tokens.add(T(tipo, op + "=", 1));
            if (esp > 0) g.lexer.add(E(114, 1, op + " ".repeat(esp) + "="));
        } else if (op == '<') {
            g.tokens.add(T("LT", "<", 1));
        } else if (op == '>') {
            g.tokens.add(T("GT", ">", 1));
        } else if (op == '/') {
            g.tokens.add(T("DIVISION", "/", 1));
        } else if (izqNum) {
            g.tokens.add(T("ILLEGAL", ":", 1)); // ":" suelto sigue con 101 en el parser (3.4)
        }
        g.tokens.add(T("IDENT", "b", 1));
        g.tokens.add(EOL(1));
        return g;
    }

    private interface Generador {
        Generado Generar(Random r);
    }

    // Property 1 de una familia: tokens, Lexer.GetMensajes() exacto y, si aplica, 1xx del parser sin 102/201
    private static void Familia(String nombre, long base, Generador gen) {
        List<String> dif = new ArrayList<>();
        int malos = 0;
        for (int s = 0; s < SEMILLAS_PROPIEDAD; s++) {
            long semilla = base + s;
            Generado g = gen.Generar(new Random(semilla));
            String fuente = Unir(g.lineas);
            List<String> d = new ArrayList<>();
            Lectura lx = Leer(fuente, d);
            if (!g.tokens.equals(lx.tokens)) d.add("tokens: " + PrimeraDiferencia(g.tokens, lx.tokens));
            if (!g.lexer.equals(lx.mensajes)) d.add("Lexer.GetMensajes(): esperado " + g.lexer + ", obtenido " + lx.mensajes);
            if (g.parser1xx != null) {
                Analisis a = Analizar(fuente, d);
                if (a != null) {
                    List<String> lex = Lexicos(a.mensajes);
                    if (!MismoMulticonjunto(g.parser1xx, lex)) d.add("1xx del parser: esperado " + g.parser1xx + ", obtenido " + lex);
                    for (Mensaje m : a.mensajes) {
                        boolean cascada = Integer.valueOf(201).equals(m.getCodigo()) && !EsTipoDeExpresion(m);
                        if (Integer.valueOf(102).equals(m.getCodigo()) || cascada) d.add("aparece " + m.Formatear());
                    }
                }
            }
            if (!d.isEmpty()) {
                malos++;
                if (dif.size() < 5) dif.add("semilla " + semilla + ", fuente [" + Escapar(fuente) + "]: " + d);
            }
        }
        if (malos > 0) dif.add(malos + " de " + SEMILLAS_PROPIEDAD + " semillas fallan");
        Registrar("P1 " + nombre + " (" + SEMILLAS_PROPIEDAD + " semillas)", "P1", true, dif);
    }

    // Property 2: lenguaje neutral contra el tokenizador de referencia, sin mensajes léxicos nuevos
    private static void PropiedadPreservacion() {
        List<String> dif = new ArrayList<>();
        int malos = 0;
        for (int s = 0; s < SEMILLAS_PROPIEDAD; s++) {
            long semilla = BASE_P2 + s;
            String fuente = Unir(GenerarNeutral(semilla));
            List<String> d = new ArrayList<>();
            Lectura lx = Leer(fuente, d);
            List<String> ref = Referencia(fuente);
            if (!ref.equals(lx.tokens)) d.add("tokens: " + PrimeraDiferencia(ref, lx.tokens));
            if (!lx.mensajes.isEmpty()) d.add("mensajes léxicos nuevos " + lx.mensajes);
            if (!d.isEmpty()) {
                malos++;
                if (dif.size() < 5) dif.add("semilla " + semilla + ", fuente [" + Escapar(fuente) + "]: " + d);
            }
        }
        if (malos > 0) dif.add(malos + " de " + SEMILLAS_PROPIEDAD + " semillas fallan");
        Registrar("P2 tokens neutrales = referencia (" + SEMILLAS_PROPIEDAD + " semillas)", "P2", true, dif);
    }

    // Consistencia: parser.hasErrors = existe un ERROR en GetMensajes(), sobre fuentes neutrales y de las familias
    private static void PropiedadConsistencia() {
        Generador[] familias = {PruebasLexico::GenIdent, PruebasLexico::GenNumero, PruebasLexico::GenTexto,
            PruebasLexico::GenComentario, PruebasLexico::GenOperador};
        List<String> dif = new ArrayList<>();
        int malos = 0;
        for (int s = 0; s < SEMILLAS_PROPIEDAD; s++) {
            long semilla = BASE_CONS + s;
            Random r = new Random(semilla);
            List<String> lineas = new ArrayList<>();
            if (s % 3 == 0) {
                lineas.addAll(Arrays.asList(GenerarNeutral(semilla)));
            } else {
                // Programa con BEGIN/END y de 1 a 4 fragmentos de las familias
                lineas.add("BEGIN");
                int n = 1 + r.nextInt(4);
                for (int i = 0; i < n; i++) lineas.addAll(Arrays.asList(familias[r.nextInt(familias.length)].Generar(r).lineas));
                lineas.add("END");
            }
            String fuente = Unir(lineas.toArray(new String[0]));
            List<String> d = new ArrayList<>();
            Analisis a = Analizar(fuente, d);
            if (a != null) {
                boolean hayError = false;
                for (Mensaje m : a.mensajes) if (m.EsError()) hayError = true;
                if (a.hasErrors != hayError) d.add("hasErrors=" + a.hasErrors + " pero existe ERROR=" + hayError + "; mensajes " + Formateados(a.mensajes));
            }
            if (!d.isEmpty()) {
                malos++;
                if (dif.size() < 5) dif.add("semilla " + semilla + ", fuente [" + Escapar(fuente) + "]: " + d);
            }
        }
        if (malos > 0) dif.add(malos + " de " + SEMILLAS_PROPIEDAD + " semillas fallan");
        Registrar("hasErrors = existe ERROR en GetMensajes() (" + SEMILLAS_PROPIEDAD + " semillas)", "P3", true, dif);
    }

    private static void Propiedades() throws Exception {
        CarpetaSegura();
        long inicio = System.currentTimeMillis();
        Familia("identificadores", BASE_IDENT, PruebasLexico::GenIdent);
        Familia("números", BASE_NUM, PruebasLexico::GenNumero);
        Familia("textos y líneas", BASE_TEXTO, PruebasLexico::GenTexto);
        Familia("comentarios", BASE_COMENT, PruebasLexico::GenComentario);
        Familia("operadores", BASE_OPER, PruebasLexico::GenOperador);
        PropiedadPreservacion();
        PropiedadConsistencia();
        System.out.println("Tiempo: " + (System.currentTimeMillis() - inicio) + " ms");
        ImprimirResumen("propiedades");
    }
}
