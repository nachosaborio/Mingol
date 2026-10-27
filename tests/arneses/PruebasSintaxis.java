// Arnés de la spec mingol-sintaxis-fix (área 3: analizador sintáctico).
// Clase sin paquete, como los otros arneses. Se compila contra build\classes y se ejecuta desde
// $env:TEMP\mingol-sintaxis-arnes: todos los archivos se crean ahí (nunca en el repositorio, R5).
//
// Modos:
//   exploracion       -> tarea 1: Property 1 (Bug Condition) sobre s01–s72, los casos obligatorios
//                        (S4 (a), S4 (b), S9, DS1, DS2, DS3, DS6) y una copia de proyecto.mingol.
//                        Compara el -errores.txt completo con la tabla Examples del diseño.
//                        Se espera que FALLE en el código sin arreglar (24f9001), salvo los controles.
//   hijotp <archivo>  -> interno: TestProyecto por reflexión (nunca main) y resumen del AST.
//   capturar [--sobrescribir] -> tarea 2: guarda en %TEMP%\mingol-sintaxis-golden el comportamiento del
//                        código sin arreglar (GetMensajes, GetErrors, hasErrors, evaluador, TestProyecto,
//                        -errores.txt) de los controles, 200 semillas del dominio de preservación y ejemplos
//                        fijos; comprueba además el oráculo de preservación contra lo capturado.
//   preservacion      -> tarea 2: Property 2 contra esa captura (no la regenera) y contra el oráculo.
//   hijocap <archivo> -> interno: captura de un caso, en su carpeta.
//   unitarias         -> tarea 3.9: un caso por cláusula 2.1–2.23 (mensaje exacto en el -errores.txt y
//                        cadena de GetErrors()), casos obligatorios, bordes de fin de comando, L4, AST,
//                        evaluador y la copia de proyecto.mingol.
//   propiedades       -> tarea 3.9: 300 semillas por familia (válidos, una sola falta, dominio de
//                        preservación contra el oráculo de la tarea 2) y validez con TestProyecto en
//                        las primeras 50 de cada familia.
//   hijolote <lista>  -> interno: analiza en un hijo (R8) un lote de fuentes y deja un .dump por fuente.
//
// Solo usa API presente antes y después del arreglo; los nodos nuevos (Declaracion, Asignacion,
// Secuencia) se miran por reflexión, para que el arnés compile también contra 24f9001.
// Seguridad (R8): TestProyecto corre en un hijo con la misma java.exe, --enable-preview, -Xmx256m,
// el mismo classpath (y -ea si el padre lo usa), waitFor(10 s) y destroyForcibly.
import Errores.Mensaje;
import Evaluador.Environment;
import Evaluador.Evaluator;
import Lexer.Lexer;
import Lexer.Token;
import Parser.Block;
import Parser.For;
import Parser.Identifier;
import Parser.If;
import Parser.Interfaces.ASTNode;
import Parser.Parser;
import Parser.Program;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class PruebasSintaxis {

    // Límite de tiempo por hijo (R8)
    private static final long LIMITE_SEGUNDOS = 10;

    // Sangría exacta de los mensajes en el -errores.txt (área 1)
    private static final String S8 = "        ";

    // Nombre de la carpeta de trabajo y de la subcarpeta de la copia de proyecto.mingol
    private static final String CARPETA_TRABAJO = "mingol-sintaxis-arnes";
    private static final String SUBCARPETA_PROYECTO = "proyecto";

    // Resultados del modo actual
    private static final List<String> resumen = new ArrayList<>();
    private static final List<String> controlesQueFallan = new ArrayList<>();
    private static final List<String> noReproducidos = new ArrayList<>();
    private static final Map<String, Boolean> fallo = new LinkedHashMap<>();
    private static int fallas = 0;
    private static int controles = 0;
    private static int casosC = 0;

    // ------------------------------------------------------------------
    // Despacho por modo
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: PruebasSintaxis exploracion | capturar [--sobrescribir] | preservacion | unitarias"
                    + " | propiedades | hijotp <archivo> | hijocap <archivo> | hijolote <lista>");
            System.exit(2);
        }
        switch (args[0]) {
            case "exploracion":
                Exploracion();
                break;
            case "hijotp":
                HijoTP(args[1]);
                break;
            // Tarea 2: captura y preservación
            case "capturar":
                Capturar(args.length > 1 && args[1].equals("--sobrescribir"));
                break;
            case "preservacion":
                Preservacion();
                break;
            case "hijocap":
                HijoCap(args[1]);
                break;
            // Tarea 3.9: unitarias, propiedades y el hijo de lote que usan las dos
            case "unitarias":
                Unitarias();
                break;
            case "propiedades":
                Propiedades();
                break;
            case "hijolote":
                HijoLote(args[1]);
                break;
            default:
                System.err.println("Modo desconocido: " + args[0]);
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Mensajes esperados (tabla final de códigos del diseño)
    // ------------------------------------------------------------------

    // "ERROR NNN: texto [lexema]."
    private static String Err(int codigo, String texto, String lexema) {
        return String.format("ERROR %03d: %s [%s].", codigo, texto, lexema);
    }

    // Advertencia de REQ-ALGOL-01
    private static String Adv(String lexema) {
        return "Advertencia: instrucción no es soportada por esta versión [" + lexema + "].";
    }

    private static String E103(String lex) { return Err(103, "Identificador con mayúsculas", lex); }
    private static String E108(String lex) { return Err(108, "Constante entera fuera de rango", lex); }
    private static String E202(String despues, String lex) { return Err(202, "No puede haber nada en la línea después de " + despues, lex); }
    private static String E204(String esperado, String obtenido, String lex) {
        return Err(204, "Se esperaba un token de tipo " + esperado + " pero se obtuvo " + obtenido, lex);
    }
    private static String E204Eol() { return E204("SEMICOLON", "EOL", "EOL"); }
    private static String E205(String lex) { return Err(205, "No es una instrucción válida de MINGOL ni de ALGOL", lex); }
    private static String E206(String cierre) { return Err(206, "La línea anterior a " + cierre + " no lleva punto y coma", ";"); }
    private static String E207(String lex) { return Err(207, "Comando antes de BEGIN", lex); }
    private static String E208(String lex) { return Err(208, "Comando después de END", lex); }
    private static String E209(String lex) { return Err(209, "Declaración después del primer comando", lex); }
    private static String E210() { return Err(210, "Declaración con valor inicial", ":="); }
    private static String E211(String lex) { return Err(211, "Falta la coma entre dos elementos de la lista", lex); }
    private static String E212() { return Err(212, "Coma colgando en la lista", ","); }
    private static String E213(String lex) { return Err(213, "Palabra de MINGOL fuera de lugar", lex); }
    private static String E214() { return Err(214, "IF anidado dentro de otro IF", "IF"); }
    private static String E215() { return Err(215, "FOR anidado dentro de otro FOR", "FOR"); }
    private static String E216(String cierre) { return Err(216, "Bloque sin comandos antes de " + cierre, cierre); }
    private static String E217() { return Err(217, "Falta la sección de comandos", "END"); }
    private static String E303(String lex) { return Err(303, "Identificador no encontrado", lex); }
    private static String E305() { return Err(305, "No puede haber más de una sentencia BEGIN por archivo", "BEGIN"); }
    private static String E306() { return Err(306, "No puede haber más de una sentencia END por archivo", "END"); }
    private static String E307() { return Err(307, "No se encontró una sentencia BEGIN", "BEGIN"); }
    private static String E308() { return Err(308, "No se encontró una sentencia END", "END"); }

    // ------------------------------------------------------------------
    // Casos de la exploración
    // ------------------------------------------------------------------

    // Un caso: fuente, mensajes esperados bajo cada línea, bloque final y resumen del AST esperado
    private static class Caso {
        String id;
        String grupo;
        boolean control;                                   // debe pasar ya en 24f9001
        String[] lineas;
        boolean diagnostico;                               // fuente igual al de %TEMP%\mingol-sintaxis
        final TreeMap<Integer, List<String>> bajo = new TreeMap<>();
        final List<String> finales = new ArrayList<>();    // 307/308 (SIN_LINEA)
        final Map<String, String> ast = new LinkedHashMap<>();

        // Mensajes esperados bajo la línea n, en el orden del archivo
        Caso m(int n, String... mensajes) {
            bajo.computeIfAbsent(n, k -> new ArrayList<>()).addAll(Arrays.asList(mensajes));
            return this;
        }

        Caso fin(String... mensajes) {
            finales.addAll(Arrays.asList(mensajes));
            return this;
        }

        // Dato del AST que debe imprimir el hijo (clave=valor)
        Caso ast(String clave, String valor) {
            ast.put(clave, valor);
            return this;
        }

        Caso control() {
            control = true;
            return this;
        }

        // Tarea 3.9: cadenas de GetErrors() que deben aparecer tal cual
        final List<String> cadenas = new ArrayList<>();
        // Tarea 3.9: caso relajado (no fija el archivo completo): mensajes "línea:mensaje" que deben
        // estar y códigos que no deben aparecer
        boolean relajado;
        final List<String> contiene = new ArrayList<>();
        final List<String> sinCodigos = new ArrayList<>();

        Caso cad(String... c) {
            cadenas.addAll(Arrays.asList(c));
            return this;
        }

        Caso relajado(String[] debe, String... codigosProhibidos) {
            relajado = true;
            contiene.addAll(Arrays.asList(debe));
            sinCodigos.addAll(Arrays.asList(codigosProhibidos));
            return this;
        }

        // -errores.txt esperado completo
        String archivoEsperado() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lineas.length; i++) {
                sb.append(String.format("%05d", i)).append(' ').append(lineas[i - 1]).append('\n');
                for (String msj : bajo.getOrDefault(i, Collections.emptyList())) sb.append(S8).append(msj).append('\n');
            }
            for (String msj : finales) sb.append(S8).append(msj).append('\n');
            return sb.toString();
        }

        // Válido = ningún ERROR (las advertencias se permiten)
        boolean valido() {
            for (List<String> l : bajo.values()) for (String s : l) if (s.startsWith("ERROR")) return false;
            for (String s : finales) if (s.startsWith("ERROR")) return false;
            return true;
        }
    }

    // Caso del diagnóstico (s01–s72): mismo contenido que %TEMP%\mingol-sintaxis\sNN.mingol
    private static Caso S(String grupo, String id, String... lineas) {
        Caso c = new Caso();
        c.grupo = grupo;
        c.id = id;
        c.lineas = lineas;
        c.diagnostico = true;
        return c;
    }

    // Caso obligatorio con el contenido tal cual
    private static Caso O(String id, String... lineas) {
        Caso c = S("obligatorio", id, lineas);
        c.diagnostico = false;
        return c;
    }

    // Caso obligatorio envuelto en BEGIN / INT a, ano, n; / ... / SKIP / END
    private static Caso Envuelto(String id, String... cuerpo) {
        List<String> l = new ArrayList<>();
        l.add("BEGIN");
        l.add("INT a, ano, n;");
        l.addAll(Arrays.asList(cuerpo));
        l.add("SKIP");
        l.add("END");
        return O(id, l.toArray(new String[0]));
    }

    private static List<Caso> Casos() {
        List<Caso> c = new ArrayList<>();
        // Grupos (diseño, Exploratory Test Cases): 1 declaraciones, 2 bloques, 3 regla del ;,
        // 4 desconocidos, 5 estructura, 6 sobrantes, 7 ALGOL en expresiones, 8 formas, 9 anidamiento
        c.add(S("1", "s01", "BEGIN", "INT a;", "REAL b, c;", "CHAR d;", "print ( newline )", "END")
                .ast("cuenta.Declaracion", "3").ast("cuenta.LetStatement", "4"));
        c.add(S("1", "s02", "BEGIN", "INT a := 42;", "print ( newline )", "END").m(2, E210()));
        c.add(S("1", "s03", "BEGIN", "INT a;", "a := 2", "END")
                .ast("cuenta.Asignacion", "1").ast("cuenta.Secuencia", "1"));
        c.add(S("2", "s04", "BEGIN", "INT i;", "FOR i FROM 1 BY 1 TO 3", "DO", "print ( newline );", "print ( newline )", "OD", "END")
                .ast("For.comandos", "2"));
        c.add(S("2", "s05", "BEGIN", "INT a;", "IF ( a > 0 ) THEN", "print ( newline );", "print ( newline )", "ELSE",
                "print ( newline )", "FI", "END")
                .ast("If.consecuencia", "2").ast("If.alternativa", "1"));
        c.add(S("3", "s06", "BEGIN", "print ( newline );", "END").m(2, E206("END")));
        c.add(S("3", "s07", "BEGIN", "print ( newline )", "print ( newline )", "END").m(2, E204Eol()));
        c.add(S("2", "s08", "BEGIN", "INT a;", "a := ( a *", "2 );", "print ( newline )", "END"));
        c.add(S("4", "s09", "BEGIN", "RREAL factor1;", "print ( newline )", "END").m(2, E205("RREAL")));
        c.add(S("4", "s10", "BEGIN", "READ ( x );", "print ( newline )", "END").m(2, E205("READ")));
        c.add(S("4", "s11", "BEGIN", "foo;", "print ( newline )", "END").m(2, E205("foo")));
        c.add(S("5", "s12", "print ( newline );", "BEGIN", "print ( newline )", "END", "print ( newline );")
                .m(1, E207("print")).m(5, E208("print")));
        c.add(S("5", "s13", "BEGIN", "print ( newline );", "INT a;", "print ( newline )", "END").m(3, E209("INT")));
        c.add(S("6", "s14", "BEGIN", "inicio: print ( newline );", "print ( newline )", "END").m(2, E202("ETIQUETA", "print")));
        c.add(S("7", "s15", "BEGIN", "INT a;", "IF ( a = 1 OR a = 2 ) THEN", "print ( newline )", "FI", "END")
                .ast("Secuencia.algol", "OR"));
        c.add(S("3", "s16", "BEGIN", "SKIP;", "END").m(2, E206("END")));
        c.add(S("ctl", "s17", "BEGIN", "SKIP", "END").control());
        c.add(S("3", "s18", "BEGIN", "SKIP;", "print ( newline )", "END"));
        c.add(S("8", "s19", "BEGIN", "INT a, b;", "read ( a );", "read a;", "read ( a, b );", "read ( );", "print ( newline )", "END")
                .m(4, E204("LPAREN", "IDENT", "a")).m(5, E204("RPAREN", "COMMA", ",")).m(6, E204("IDENT", "RPAREN", ")")));
        c.add(S("otros", "s20", "BEGIN", "INT a, b;", "print ( (a, b) );", "print ( (a) );", "print ( newline )", "END"));
        c.add(S("8", "s21", "BEGIN", "INT a, b;", "print ( (a,) );", "print ( (,a) );", "print ( a );", "print ( newline )", "END")
                .m(3, E212()).m(4, E212()).m(5, E204("LPAREN", "IDENT", "a")));
        c.add(S("8", "s22", "BEGIN", "print ( \"hola\" );", "print \"hola\";", "print ( newline )", "END")
                .m(3, E204("LPAREN", "STRING", "hola")));
        c.add(S("otros", "s23", "BEGIN", "GOTO fin;", "fin:", "print ( newline )", "END").m(2, E303("fin")));
        c.add(S("8", "s24", "BEGIN", "GOTO;", "GOTO fin extra;", "fin:", "print ( newline )", "END")
                .m(2, E204("IDENT", "SEMICOLON", ";")).m(3, E204("SEMICOLON", "IDENT", "extra"), E303("fin")));
        c.add(S("1", "s25", "BEGIN", "INT b := -32768;", "print ( newline )", "END").m(2, E210()));
        c.add(S("otros", "s26", "BEGIN;", "print ( newline )", "END;").m(1, E202("BEGIN", ";")).m(3, E202("END", ";")));
        c.add(S("6", "s27", "BEGIN", "INT a;", "IF ( a > 0 ) THEN;", "print ( newline )", "ELSE;", "print ( newline )", "FI", "END")
                .m(3, E202("THEN", ";")).m(5, E202("ELSE", ";")));
        c.add(S("6", "s28", "BEGIN", "INT i;", "FOR i FROM 1 BY 1 TO 3", "DO;", "print ( newline )", "OD", "END")
                .m(4, E202("DO", ";")));
        c.add(S("3", "s29", "BEGIN", "INT a;", "IF ( a > 0 ) THEN", "print ( newline );", "ELSE", "print ( newline );", "FI;", "END")
                .m(4, E206("ELSE")).m(6, E206("FI")).m(7, E206("END")));
        c.add(S("2", "s30", "BEGIN", "INT a;", "IF ( a > 0 )", "THEN", "print ( newline )", "FI", "END"));
        c.add(S("9", "s31", "BEGIN", "INT a, i;", "IF ( a > 0 ) THEN", "IF ( a > 1 ) THEN", "print ( newline )", "FI", "FI;",
                "FOR i FROM 1 BY 1 TO 3", "DO", "FOR i FROM 1 BY 1 TO 3", "DO", "print ( newline )", "OD", "OD", "END")
                .m(4, E214()).m(10, E215()));
        c.add(S("9", "s32", "BEGIN", "INT a;", "IF ( a > 0 ) THEN", "print ( newline )", "ELSE", "FI", "END").m(6, E216("FI")));
        c.add(S("1", "s33", "BEGIN", "INT a b;", "INT , c;", "INT d,;", "print ( newline )", "END")
                .m(2, E211("b")).m(3, E212()).m(4, E212()));
        c.add(S("4", "s34", "BEGIN", "xx yy zz;", "print ( newline );", "print ( newline );", "print ( newline )", "END")
                .m(2, E205("xx")));
        c.add(S("3", "s35", "BEGIN", "INT a;", "IF ( a > 0 ) THEN", "print ( newline )", "FI", "print ( newline )", "END")
                .m(5, E204Eol()));
        c.add(S("1", "s36", "BEGIN", "INT a;", "a := 1;", "print ( newline )", "END"));
        c.add(S("7", "s37", "BEGIN", "INT a;", "a := max + 1;", "a := ABS a;", "print ( newline )", "END"));
        c.add(S("2", "s38", "BEGIN", "INT i;", "FOR i FROM 1 BY 1 TO 3", "DO", "print ( newline )", "OD;", "print ( newline )", "END"));
        c.add(S("otros", "s39", "BEGIN", "print ( newline );", "SKIP;", "print ( newline )", "END"));
        c.add(S("otros", "s40", "BEGIN", "PRAGMAT x PRAGMAT", "print ( newline )", "END").m(2, Adv("PRAGMAT")));
        c.add(S("ctl", "s41", "BEGIN", "inicio:", "GOTO inicio", "END").control());
        c.add(S("6", "s42", "BEGIN", "INT a;", "IF ( a > 0 ) THEN print ( newline ) FI;", "print ( newline )", "END")
                .m(3, E202("THEN", "print")));
        c.add(S("2", "s43", "BEGIN", "print", "( newline );", "print ( newline )", "END"));
        c.add(S("5", "s44", "BEGIN", "print ( newline );", "END", "print ( newline )").m(2, E206("END")).m(4, E208("print")));
        c.add(S("7", "s45", "BEGIN", "CHAR r;", "IF ( r = \"S\" OR r = \"s\" ) THEN", "print ( newline )", "FI", "END"));
        c.add(S("3", "s46", "BEGIN", "IF ( 1 > 0 ) THEN", "print ( newline )", "ELSE", "print ( newline )", "FI", "END"));
        c.add(S("6", "s47", "BEGIN", "print ( newline ); print ( newline );", "print ( newline )", "END")
                .m(2, E202("SEMICOLON", "print")));
        c.add(S("otros", "s48", "", "# comentario #", "BEGIN", "", "CO otro CO", "print ( newline )", "", "END", "", "# fin #"));
        c.add(S("2", "s49", "BEGIN", "FOR i FROM 1", "BY 1 TO 3", "DO", "print ( newline )", "OD", "END"));
        c.add(S("1", "s50", "BEGIN", "REAL b,", "c;", "print ( newline )", "END"));
        c.add(S("ctl", "s51", "BEGIN", "IF ( 1 > 0 ) THEN", "SKIP", "FI", "END").control());
        c.add(S("3", "s52", "BEGIN", "SKIP", "print ( newline )", "END").m(2, E204Eol()));
        c.add(S("otros", "s53", "BEGIN", "LONG x;", "print ( newline )", "END").m(2, Adv("LONG")));
        c.add(S("otros", "s54", "BEGIN", "IF ( TRUE ) THEN", "print ( newline )", "FI", "END"));
        c.add(S("3", "s55", "BEGIN", "read ( a )", "END"));
        c.add(S("otros", "s56", "BEGIN", "GOTO fin", "fin:", "print ( newline )", "END").m(2, E204Eol(), E303("fin")));
        c.add(S("6", "s57", "BEGIN", "fin: ;", "print ( newline )", "END").m(2, E202("ETIQUETA", ";")));
        c.add(S("3", "s58", "BEGIN", "print ( \"hola\" )", "END"));
        c.add(S("3", "s59", "BEGIN", "print ( (a, b) )", "END").m(2, E303("a"), E303("b")));
        c.add(S("otros", "s60", "BEGIN", "IF ( 1 > 0 ) THEN", "print ( newline )", "FI;", "print ( newline )", "END"));
        c.add(S("4", "s61", "BEGIN", "print ( newline )", "FI", "END").m(3, E213("FI")));
        c.add(S("otros", "s62", "BEGIN", "print ( newline );", "BEGIN", "print ( newline )", "END", "END")
                .m(3, E305()).m(6, E306()));
        c.add(S("3", "s63", "BEGIN", "IF ( 1 > 0 ) THEN", "print ( newline )", "FI", "END"));
        c.add(S("3", "s64", "BEGIN", "print ( newline );", "print ( newline )", "END"));
        c.add(S("4", "s65", "BEGIN", "for i;", "print ( newline )", "END").m(2, E205("for")));
        c.add(S("ctl", "s66", "BEGIN", "IF ( 1 > 0 ) THEN", "GOTO fin", "FI;", "fin:", "SKIP", "END").m(3, E303("fin")).control());
        c.add(S("5", "s67", "BEGIN", "END").m(2, E217()));
        // DS8: REQ-LINE-04 al pie de la letra: 206 [;] bajo la declaración y 217 bajo el END
        c.add(S("1", "s68", "BEGIN", "INT a;", "END").m(2, E206("END")).m(3, E217()));
        c.add(S("2", "s69", "BEGIN", "x := ( x *", "Factor2 );", "print ( newline )", "END").m(3, E103("Factor2")));
        c.add(S("ctl", "s70", "BEGIN", "print ( newline );", "PRAGMAT a PRAGMAT", "END").m(3, Adv("PRAGMAT")).control());
        c.add(S("9", "s71", "BEGIN", "IF ( 1 > 0 ) THEN", "FI", "END").m(3, E216("FI")));
        // DS9: REQ-LINE-04 al pie de la letra: 206 [;] bajo el print y 213 bajo el OD suelto
        c.add(S("4", "s72", "BEGIN", "print ( newline );", "OD", "END").m(2, E206("OD")).m(3, E213("OD")));

        // Casos obligatorios (diseño, Examples). Envoltorio BEGIN / INT a, ano, n; / ... / SKIP / END.
        // S4 (a): el FI/OD va con ";" porque lo sigue el SKIP del envoltorio (sin ";" daría el 204 [EOL] de 2.9);
        // así el caso exige "ningún 204" y, además, ningún otro mensaje.
        c.add(Envuelto("s4a_if", "IF ( a > 0 ) THEN", "print ( newline )", "FI;"));
        c.add(Envuelto("s4a_for", "FOR ano FROM 1 BY 1 TO n DO", "print ( newline )", "OD;"));
        // S4 (b), sin envoltorio: 204 [EOL] bajo la 2 y 305 bajo la 3, en ese orden
        c.add(O("s4b", "BEGIN", "print ( newline )", "BEGIN", "print ( newline )", "END").m(2, E204Eol()).m(3, E305()));
        // S9: un solo 202 con el primer token sobrante; el FI/OD de la misma línea cierra sin más mensajes
        c.add(Envuelto("s9_if", "IF ( a > 0 ) THEN print ( newline ) FI;").m(3, E202("THEN", "print")));
        c.add(Envuelto("s9_for", "FOR ano FROM 1 BY 1 TO n DO print ( newline ) OD;").m(3, E202("DO", "print")));
        // DS1, sin envoltorio: la instrucción ALGOL ignorada no cuenta como comando
        c.add(O("ds1", "BEGIN", "RETURN 5", "END").m(2, Adv("RETURN")).m(3, E217()));
        // DS2 (a): sin la línea INT a, ano, n; del envoltorio, que daría un 304 [a] ajeno al caso
        c.add(O("ds2_a", "BEGIN", "INT a := 32768;", "SKIP", "END").m(2, E108("32768"), E210()));
        // DS2 (b): print sin ";" es el último comando antes de END (sin el SKIP del envoltorio)
        c.add(O("ds2_b", "BEGIN", "INT a, ano, n;", "CHAR y := zz;", "print ( (y) )", "END").m(3, E210()));
        // DS3, sin BEGIN ni END: solo 307 y 308 (y el 205 propio de x = 3;), ningún 207
        c.add(O("ds3", "INT a;", "print ( (a) );").fin(E307(), E308()));
        c.add(O("ds3_x", "INT a;", "x = 3;", "print ( (a) );").m(2, E205("x")).fin(E307(), E308()));
        // DS6: LONG al comienzo de la línea siguiente continúa el comando; el texto usa el tipo del
        // token obtenido (ALGOL), como el 204 de hoy (decisión del usuario)
        c.add(Envuelto("ds6", "print ( newline )", "LONG x;")
                .m(4, E204("SEMICOLON", "ALGOL", "LONG")));
        return c;
    }

    // ------------------------------------------------------------------
    // Exploración (tarea 1)
    // ------------------------------------------------------------------

    private static void Exploracion() throws Exception {
        Path base = CarpetaSegura();
        Path trabajo = base.resolve("exploracion");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        // Diagnóstico de la spec (solo lectura, para avisar si un fuente difiere)
        Path diag = CarpetaTemp().resolve("mingol-sintaxis");

        for (Caso c : Casos()) {
            List<String> dif = new ArrayList<>();
            Path dir = trabajo.resolve(c.id);
            Files.createDirectories(dir);
            String archivo = c.id + ".mingol";
            // Mismo formato que los fuentes del diagnóstico: CRLF y salto final
            byte[] bytes = (String.join("\r\n", c.lineas) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            Files.write(dir.resolve(archivo), bytes);
            if (c.diagnostico && Files.exists(diag.resolve(archivo))
                    && !Arrays.equals(bytes, Files.readAllBytes(diag.resolve(archivo)))) {
                System.out.println("AVISO: " + archivo + " difiere de " + diag.resolve(archivo));
            }

            ResultadoHijo r = LanzarHijo(L("hijotp", archivo), dir, c.id);
            ProblemasDeEjecucion(r, dif);

            // Validez
            String tp = LineaConPrefijo(r.stdout, "TestProyecto=");
            String esperadoTP = "TestProyecto=" + c.valido();
            if (!esperadoTP.equals(tp)) dif.add("esperado " + esperadoTP + ", obtenido " + tp);

            // -errores.txt completo (R6: ISO-8859-1)
            Path errores = dir.resolve(c.id + "-errores.txt");
            if (!Files.exists(errores)) {
                dif.add("no se generó " + errores.getFileName());
            } else {
                String obtenido = new String(Files.readAllBytes(errores), StandardCharsets.ISO_8859_1);
                String esperado = c.archivoEsperado();
                if (!esperado.equals(obtenido)) {
                    dif.add("-errores.txt: " + PrimeraDiferencia(esperado, obtenido));
                    dif.add("mensajes esperados: " + Mensajes(esperado));
                    dif.add("mensajes obtenidos: " + Mensajes(obtenido));
                }
            }

            // Resumen del AST impreso por el hijo
            ComprobarAst(c, r, dif);
            Registrar(c.id, c.grupo, c.control, dif);
        }

        Proyecto(base);
        Causas();
        ImprimirResumen("exploracion");
    }

    // Datos del AST: cada clave esperada debe estar entre los valores impresos por el hijo
    private static void ComprobarAst(Caso c, ResultadoHijo r, List<String> dif) {
        if (c.ast.isEmpty()) return;
        List<String> ast = LineasConPrefijo(r.stdout, "AST|");
        for (Map.Entry<String, String> e : c.ast.entrySet()) {
            List<String> valores = new ArrayList<>();
            for (String a : ast) if (a.startsWith(e.getKey() + "=")) valores.add(a.substring(e.getKey().length() + 1));
            if (!valores.contains(e.getValue())) {
                dif.add("AST " + e.getKey() + ": esperado " + e.getValue() + ", obtenido " + (valores.isEmpty() ? "(ninguno)" : valores));
            }
        }
    }

    // Copia de proyecto.mingol en la subcarpeta proyecto: sin 2xx, 111 bajo 21/49/59, advertencias bajo 2 y 3
    private static void Proyecto(Path base) throws Exception {
        List<String> dif = new ArrayList<>();
        Path original = RaizRepositorio().resolve("proyecto.mingol");
        Path dir = base.resolve(SUBCARPETA_PROYECTO);
        BorrarArbol(dir);
        Files.createDirectories(dir);
        // Solo se lee el de la raíz; Mingol corre sobre la copia (R5)
        Files.copy(original, dir.resolve("proyecto.mingol"));
        ResultadoHijo r = LanzarHijo(L("hijotp", "proyecto.mingol"), dir, "proyecto");
        ProblemasDeEjecucion(r, dif);
        Path errores = dir.resolve("proyecto-errores.txt");
        if (!Files.exists(errores)) {
            dif.add("no se generó proyecto-errores.txt");
        } else {
            List<String> arch = Arrays.asList(new String(Files.readAllBytes(errores), StandardCharsets.ISO_8859_1).split("\n", -1));
            List<String> sint = new ArrayList<>();
            List<String> lex = new ArrayList<>();
            List<String> adv = new ArrayList<>();
            List<String> sem = new ArrayList<>();
            int linea = 0;
            for (String l : arch) {
                if (l.matches("\\d{5} .*") || l.matches("\\d{5}")) {
                    linea = Integer.parseInt(l.substring(0, 5));
                    continue;
                }
                if (!l.startsWith(S8)) continue;
                String m = l.substring(S8.length());
                String e = linea + ":" + m;
                if (m.startsWith("ERROR 2")) sint.add(e);
                else if (m.startsWith("ERROR 1")) lex.add(e);
                else if (m.startsWith("ERROR 3")) sem.add(e);
                else if (m.startsWith("Advertencia")) adv.add(linea + "");
            }
            if (!sint.isEmpty()) dif.add(sint.size() + " mensajes de sintaxis (se esperaban 0): " + sint);
            List<String> lexLineas = new ArrayList<>();
            for (String e : lex) {
                if (!e.contains("ERROR 111")) dif.add("1xx inesperado: " + e);
                lexLineas.add(e.substring(0, e.indexOf(':')));
            }
            if (!lexLineas.equals(L("21", "49", "59"))) dif.add("1xx en las líneas " + lexLineas + " (se esperaban 111 en 21, 49 y 59)");
            if (!adv.equals(L("2", "3"))) dif.add("advertencias en las líneas " + adv + " (se esperaban 2 y 3)");
            // Los 3xx son del área 4: se informan sin fijarlos
            System.out.println("INFO  proyecto: 3xx (área 4, no se fijan): " + sem);
        }
        Registrar("proyecto", "10", false, dif);
    }

    // Revisión de las causas 1..9 del diseño: confirmada si fallan todos los casos que dependen de ella
    private static void Causas() {
        System.out.println();
        System.out.println("===== CAUSAS DEL DISEÑO =====");
        Causa(1, "no existe el concepto de comando", "s04", "s05", "s07", "s08", "s47", "s49");
        Causa(2, "ParseLetStatement mezcla declaración, inicialización y asignación", "s01", "s02", "s03", "s25");
        Causa(3, "expresiones con gramática (Pratt y ParseAlgol)", "s08", "s15", "s37");
        Causa(4, "regla del ; repartida y al revés", "s06", "s16", "s29", "s35", "s52");
        Causa(5, "sin estado de estructura ni pila de bloques", "s12", "s13", "s31", "s67");
        Causa(6, "el caso por defecto es una expresión", "s09", "s11", "s61");
        Causa(7, "checkSemicolon/ExpectedToken avanzan un token (y usan line/peekToken)", "s14", "s27", "s28", "s69");
        Causa(8, "posición de comando deducida del token anterior", "s69");
        Causa(9, "formas de read/print/GOTO incompletas", "s19", "s21", "s24");
    }

    private static void Causa(int n, String nombre, String... casos) {
        List<String> pasan = new ArrayList<>();
        for (String c : casos) if (!Boolean.TRUE.equals(fallo.get(c))) pasan.add(c);
        System.out.println("Causa " + n + " (" + nombre + "): " + (pasan.isEmpty()
                ? "CONFIRMADA (fallan " + Arrays.toString(casos) + ")"
                : "NO CONFIRMADA: pasan " + pasan));
    }

    // ------------------------------------------------------------------
    // Dominio de preservación (tarea 2): generador y oráculo
    // ------------------------------------------------------------------

    // Semillas fijas 1..200 del generador de preservación
    private static final int SEMILLAS_PRESERVACION = 200;

    // Semilla en generación "limpia" (sin errores); la fija GenerarPreservacion, un programa por vez
    private static boolean genLimpio = false;

    private static String E304(String lex) { return Err(304, "Identificador ya declarado", lex); }

    // Programa del dominio de preservación (isBugCondition falso) con los mensajes que predice el oráculo
    private static class Programa {
        final List<String> lineas = new ArrayList<>();
        final List<String> parser = new ArrayList<>();     // "línea|mensaje" del lexer y del parser
        final List<String> evaluador = new ArrayList<>();  // "línea|mensaje" del evaluador (0 = 307/308)

        // Agrega una línea física; el lexer da 113 a toda línea de más de 80 columnas (L3)
        int Linea(String texto) {
            lineas.add(texto);
            int n = lineas.size();
            if (texto.length() > 80) P(n, Err(113, "Línea de más de 80 columnas", String.valueOf(texto.length())));
            return n;
        }

        void P(int n, String m) { parser.add(n + "|" + m); }

        void E(int n, String m) { evaluador.add(n + "|" + m); }

        // hasErrors: algún ERROR del lexer o del parser (las advertencias no cuentan)
        boolean HasErrors() {
            for (String s : parser) if (s.contains("|ERROR")) return true;
            return false;
        }

        // Validez de TestProyecto: ningún ERROR (el evaluador solo registra errores)
        boolean Valido() {
            return !HasErrors() && evaluador.isEmpty();
        }
    }

    // Identificadores para print, read y condiciones; nunca coinciden con una etiqueta
    // (la etiqueta aceptada como variable en print es un bug del área 4)
    private static final String[] IDS = {"a", "b", "total", "v1", "x_y"};
    private static final String[] ETIQUETAS = {"e1", "e2", "fin", "inicio"};
    private static final String[] TEXTOS = {"hola", "texto corto", "Resultado: ", "a b c", "x"};
    // Instrucciones ALGOL en posición de comando: {línea, lexema de la advertencia}
    private static final String[][] INSTRUCCIONES_ALGOL = {{"LONG x;", "LONG"}, {"PRAGMAT x PRAGMAT", "PRAGMAT"},
            {"max ( 1 )", "max"}, {"TRUE", "TRUE"}, {"FALSE", "FALSE"}, {"ABS x;", "ABS"}, {"sqrt ( 4 );", "sqrt"},
            {"SHORT y", "SHORT"}};

    // Tipos de comando del dominio de preservación (Scope del diseño)
    private enum K { PRINT_NL, PRINT_TXT, PRINT_LARGO, PRINT_LISTA, READ, GOTO, ETIQUETA, ALGOL, IF, SKIP }

    // Generador del dominio de preservación: BEGIN/END (con o sin sobrante, repetidos o faltantes),
    // comentarios y líneas vacías, print/read/GOTO con ";", print/read sin ";" seguidos de otro comando,
    // etiquetas solas, SKIP o GOTO sin ";" como último antes de END, IF de una línea con un SKIP/GOTO y
    // FI sin ELSE, instrucciones ALGOL, líneas de caracteres ilegales y lo que solo produce 1xx.
    // Sin declaraciones, asignaciones ni nada de la condición C.
    private static Programa GenerarPreservacion(int semilla) {
        // Semilla mezclada (splitmix64): con semillas consecutivas, el primer nextInt de Random sale casi igual
        long z = semilla * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        Random r = new Random(z ^ (z >>> 31));
        Programa p = new Programa();
        Set<String> definidas = new HashSet<>();
        // Una de cada cuatro semillas es "limpia": sin errores (solo advertencias), para cubrir programas válidos
        genLimpio = r.nextInt(4) == 0;
        int modoBegin = genLimpio ? 9 : r.nextInt(10);   // 0 falta (307), 1 "BEGIN;" (202), 2 repetido (305), resto normal
        int modoEnd = genLimpio ? 9 : r.nextInt(10);     // 0 falta (308), 1 "END;" (202), 2 repetido (306), resto normal
        for (int i = r.nextInt(3); i > 0; i--) Transparente(p, r);
        if (modoBegin != 0) {
            int n = p.Linea(modoBegin == 1 ? "BEGIN;" : "BEGIN");
            if (modoBegin == 1) p.P(n, E202("BEGIN", ";"));
        }
        if (modoBegin == 2) {
            if (r.nextBoolean()) Transparente(p, r);
            p.E(p.Linea("BEGIN"), E305());
        }

        // Al menos un comando MINGOL (sin él, BEGIN/END sería 1.18); el último depende de si hay END
        int cantidad = 1 + r.nextInt(7);
        K[] medios = genLimpio ? new K[]{K.PRINT_NL, K.PRINT_TXT, K.READ, K.GOTO, K.ETIQUETA, K.ALGOL, K.IF}
                : new K[]{K.PRINT_NL, K.PRINT_TXT, K.PRINT_LARGO, K.PRINT_LISTA, K.READ, K.GOTO, K.ETIQUETA, K.ALGOL, K.IF};
        K[] ultimosConEnd = {K.SKIP, K.GOTO, K.ETIQUETA, K.SKIP};
        K[] ultimosSinEnd = genLimpio ? new K[]{K.PRINT_NL, K.PRINT_TXT, K.READ, K.GOTO, K.ETIQUETA}
                : new K[]{K.PRINT_NL, K.PRINT_TXT, K.PRINT_LISTA, K.READ, K.GOTO, K.ETIQUETA};
        K[] tipos = new K[cantidad];
        for (int i = 0; i < cantidad - 1; i++) tipos[i] = medios[r.nextInt(medios.length)];
        tipos[cantidad - 1] = modoEnd == 0 ? ultimosSinEnd[r.nextInt(ultimosSinEnd.length)]
                : ultimosConEnd[r.nextInt(ultimosConEnd.length)];
        // El último siempre es un comando MINGOL (una instrucción ALGOL sola daría 217 por DS1)
        for (int i = 0; i < cantidad; i++) {
            boolean ultimo = i == cantidad - 1;
            // print/read sin ";" solo si lo sigue una palabra de inicio (no un cierre ni ALGOL: S4, DS6).
            // Nunca la lista de print: hoy el 204 descarta el nodo y sus 303 no salen (ver tarea 2)
            boolean sinPuntoYComa = !genLimpio && !ultimo && tipos[i] != K.PRINT_LISTA && tipos[i + 1] != K.ALGOL
                    && r.nextInt(4) == 0;
            Comando(p, r, tipos[i], ultimo && modoEnd != 0, sinPuntoYComa, definidas);
            if (!ultimo && r.nextInt(4) == 0) Transparente(p, r);
        }

        if (modoEnd != 0) {
            if (r.nextInt(4) == 0) Transparente(p, r);
            int n = p.Linea(modoEnd == 1 ? "END;" : "END");
            if (modoEnd == 1) p.P(n, E202("END", ";"));
            if (modoEnd == 2) {
                // Entre los dos END solo líneas transparentes (un comando ahí sería 1.16)
                for (int i = r.nextInt(2); i > 0; i--) Transparente(p, r);
                p.E(p.Linea("END"), E306());
            }
        }
        for (int i = r.nextInt(3); i > 0; i--) Transparente(p, r);
        // Comentario sin cerrar al final: 107 bajo la línea de apertura con la forma como lexema
        if (!genLimpio && r.nextInt(8) == 0) {
            boolean co = r.nextBoolean();
            p.P(p.Linea(co ? "CO sin cerrar" : "# sin cerrar"), Err(107, "Comentario sin cerrar al final del archivo", co ? "CO" : "#"));
            p.Linea("SKIP;");
        }
        if (modoBegin == 0) p.E(0, E307());
        if (modoEnd == 0) p.E(0, E308());
        return p;
    }

    // Línea que la sintaxis no ve: vacía, espacios, comentario (de una o tres líneas) o solo caracteres ilegales
    private static void Transparente(Programa p, Random r) {
        // En las semillas limpias, sin 113 ni 101
        switch (r.nextInt(genLimpio ? 6 : 9)) {
            case 0: p.Linea(""); break;
            case 1: p.Linea("   "); break;
            case 2: p.Linea("# comentario #"); break;
            case 3: p.Linea("CO otro CO"); break;
            case 4: p.Linea("COMMENT nota COMMENT"); break;
            case 5:
                p.Linea("CO");
                p.Linea("texto libre del comentario");
                p.Linea("CO");
                break;
            case 6: p.Linea(" ".repeat(85)); break;                  // 113 [85]
            case 7: p.Linea("# " + "x".repeat(84) + " #"); break;     // 113 [88]
            default: {
                // Solo caracteres ilegales: un 101 por cada uno
                String[] ilegales = r.nextBoolean() ? new String[]{"%"} : new String[]{"$", "%"};
                int n = p.Linea(String.join(" ", ilegales));
                for (String c : ilegales) p.P(n, Err(101, "Carácter ilegal", c));
            }
        }
    }

    // Identificador de print/read/condición; a veces con 103 (mayúsculas) o 104 (más de 20)
    private static String IdComun(Random r) {
        if (genLimpio) return IDS[r.nextInt(IDS.length)];
        int k = r.nextInt(10);
        if (k == 0) return "Abc";
        if (k == 1) return "abcdefghijklmnopqrstuvwxyz";
        return IDS[r.nextInt(IDS.length)];
    }

    // 1xx del lexer para un identificador fuera de la posición de comando
    private static void Lexicos(Programa p, int n, String id) {
        if (!id.equals(id.toLowerCase())) p.P(n, E103(id));
        if (id.length() > 20) p.P(n, Err(104, "Identificador de más de 20 caracteres", id));
    }

    // Un comando del dominio; antesDeEnd = último comando antes de END (va sin ";")
    private static void Comando(Programa p, Random r, K tipo, boolean antesDeEnd, boolean sinPuntoYComa, Set<String> definidas) {
        String fin = sinPuntoYComa ? "" : ";";
        // De vez en cuando, espacios finales hasta pasar las 80 columnas (113)
        String relleno = !genLimpio && r.nextInt(15) == 0 ? " ".repeat(70) : "";
        int n;
        switch (tipo) {
            case PRINT_NL:
                n = p.Linea("print ( newline )" + fin + relleno);
                break;
            case PRINT_TXT:
                n = p.Linea("print ( \"" + TEXTOS[r.nextInt(TEXTOS.length)] + "\" )" + fin + relleno);
                break;
            case PRINT_LARGO: {
                String t = "y".repeat(31 + r.nextInt(9));
                n = p.Linea("print ( \"" + t + "\" )" + fin + relleno);
                p.P(n, Err(111, "Texto de más de 30 caracteres", t));
                break;
            }
            case PRINT_LISTA: {
                List<String> ids = new ArrayList<>();
                for (int k = 1 + r.nextInt(3); k > 0; k--) ids.add(IdComun(r));
                n = p.Linea("print ( (" + String.join(", ", ids) + ") )" + fin + relleno);
                // Sin variables declaradas, cada identificador da 303
                for (String id : ids) {
                    Lexicos(p, n, id);
                    p.E(n, E303(id));
                }
                break;
            }
            case READ: {
                String v = IdComun(r);
                n = p.Linea("read ( " + v + " )" + fin + relleno);
                // read no se evalúa: solo los 1xx
                Lexicos(p, n, v);
                break;
            }
            case GOTO: {
                if (genLimpio) {
                    // Limpia: solo a una etiqueta ya definida; si no hay, se define una (también vale como último)
                    List<String> def = new ArrayList<>(definidas);
                    Collections.sort(def);
                    if (def.isEmpty()) {
                        Comando(p, r, K.ETIQUETA, antesDeEnd, false, definidas);
                    } else {
                        p.Linea("GOTO " + def.get(r.nextInt(def.size())) + (antesDeEnd ? "" : ";"));
                    }
                    return;
                }
                String e = r.nextInt(8) == 0 ? "Fin" : ETIQUETAS[r.nextInt(ETIQUETAS.length)];
                n = p.Linea("GOTO " + e + (antesDeEnd ? "" : ";") + relleno);
                Lexicos(p, n, e);
                // 303 si la etiqueta no se definió antes (también hacia adelante, área 4)
                if (!definidas.contains(e)) p.E(n, E303(e));
                return;
            }
            case ETIQUETA: {
                if (genLimpio) {
                    // Limpia: una etiqueta sin definir; si ya están todas, una instrucción ALGOL (sin 304)
                    List<String> libres = new ArrayList<>(Arrays.asList(ETIQUETAS));
                    libres.removeAll(definidas);
                    if (libres.isEmpty()) {
                        Comando(p, r, K.ALGOL, antesDeEnd, false, definidas);
                        return;
                    }
                    String e = libres.get(r.nextInt(libres.size()));
                    definidas.add(e);
                    p.Linea(e + (r.nextInt(4) == 0 ? " :" : ":"));
                    return;
                }
                String e = ETIQUETAS[r.nextInt(ETIQUETAS.length)];
                n = p.Linea(e + (r.nextInt(4) == 0 ? " :" : ":") + relleno);
                if (!definidas.add(e)) p.E(n, E304(e));
                return;
            }
            case ALGOL: {
                String[] a = INSTRUCCIONES_ALGOL[r.nextInt(INSTRUCCIONES_ALGOL.length)];
                p.P(p.Linea(a[0]), Adv(a[1]));
                return;
            }
            case SKIP:
                p.Linea("SKIP" + relleno);
                return;
            case IF: {
                int c = r.nextInt(genLimpio ? 3 : 6);
                boolean verdadera = c <= 1;
                String cond;
                List<String> ids303 = new ArrayList<>();
                switch (c) {
                    case 0: cond = "1 > 0"; break;
                    case 1: cond = "TRUE"; break;
                    case 2: cond = "FALSE"; break;
                    case 3: {
                        String v = IdComun(r);
                        cond = v + " > 0";
                        ids303.add(v);
                        break;
                    }
                    case 4: {
                        // Una etiqueta en la condición da 303 (no es variable)
                        String e = ETIQUETAS[r.nextInt(ETIQUETAS.length)];
                        cond = e;
                        ids303.add(e);
                        break;
                    }
                    default:
                        cond = "Cnt > 1";
                        ids303.add("Cnt");
                }
                n = p.Linea("IF ( " + cond + " ) THEN");
                for (String v : ids303) {
                    Lexicos(p, n, v);
                    p.E(n, E303(v));
                }
                // Cuerpo: un SKIP o un GOTO sin ";". Con una condición que no es verdadera, el GOTO va solo a
                // una etiqueta ya definida (sin mensajes evalúe o no la rama: DS4)
                List<String> def = new ArrayList<>(definidas);
                Collections.sort(def);
                boolean conGoto = r.nextInt(3) != 0;
                if (conGoto && verdadera && !genLimpio) {
                    String e = ETIQUETAS[r.nextInt(ETIQUETAS.length)];
                    int m = p.Linea("GOTO " + e);
                    if (!definidas.contains(e)) p.E(m, E303(e));
                } else if (conGoto && !def.isEmpty()) {
                    p.Linea("GOTO " + def.get(r.nextInt(def.size())));
                } else {
                    p.Linea("SKIP");
                }
                p.Linea("FI;");
                return;
            }
            default:
                throw new IllegalStateException(String.valueOf(tipo));
        }
        // print/read sin ";" seguidos de otro comando: 204 [EOL] bajo su línea
        if (sinPuntoYComa) p.P(n, E204Eol());
    }

    // ------------------------------------------------------------------
    // Casos de la captura
    // ------------------------------------------------------------------

    private static class CasoCap {
        String id;
        String archivo;
        String[] lineas;
        Programa oraculo;          // mensajes predichos (null en los controles)
        String archivoEsperado;    // controles: -errores.txt de la tabla Examples

        // Mismo formato que la exploración: CRLF y salto final
        byte[] Fuente() {
            return (String.join("\r\n", lineas) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    // Caso fijo con oráculo: las líneas pasan por Programa.Linea (113 automático)
    private static CasoCap Fijo(String id, String archivo, String... lineas) {
        CasoCap c = new CasoCap();
        c.id = id;
        c.archivo = archivo;
        c.oraculo = new Programa();
        for (String l : lineas) c.oraculo.Linea(l);
        c.lineas = lineas;
        return c;
    }

    private static List<CasoCap> CasosCaptura() {
        List<CasoCap> l = new ArrayList<>();
        // 1. Controles del diagnóstico (s17, s41, s51, s66, s70), con su -errores.txt de la tabla Examples
        for (Caso c : Casos()) {
            if (!c.control) continue;
            CasoCap k = new CasoCap();
            k.id = c.id;
            k.archivo = c.id + ".mingol";
            k.lineas = c.lineas;
            k.archivoEsperado = c.archivoEsperado();
            l.add(k);
        }
        // 2. Semillas 1..200 del dominio de preservación
        for (int s = 1; s <= SEMILLAS_PRESERVACION; s++) {
            Programa p = GenerarPreservacion(s);
            CasoCap k = new CasoCap();
            k.id = String.format("g%03d", s);
            k.archivo = k.id + ".mingol";
            k.lineas = p.lineas.toArray(new String[0]);
            k.oraculo = p;
            l.add(k);
        }
        // 3. Ejemplos de las áreas 1 y 2 fuera de C. Ajustes para que no caigan en C: t3 sin su declaración
        //    y con SKIP final (print con ";" antes de END sería 1.10); t12 con SKIP en lugar de "y = 3;"
        //    (1.14) para no quedar sin comandos (1.18); c2/c3/c7/c8 con "print ( newline );" seguido de SKIP;
        //    el 101 con un SKIP entre BEGIN y END.
        CasoCap c = Fijo("ae-t3", "t3.mingol", "BEGIN", "LONG x;", "print ( (b) );", "SKIP", "END");
        c.oraculo.P(2, Adv("LONG"));
        c.oraculo.E(3, E303("b"));
        l.add(c);
        c = Fijo("ae-t12", "t12.mingol", "BEGIN", "LONG x;", "SKIP", "END");
        c.oraculo.P(2, Adv("LONG"));
        l.add(c);
        l.add(Fijo("lx-c2", "c2.mingol", "COMMENT dentro va CO suelto COMMENT", "BEGIN", "print ( newline );", "SKIP", "END"));
        l.add(Fijo("lx-c3", "c3.mingol", "# texto con \"comilla #", "BEGIN", "print ( newline );", "SKIP", "END"));
        l.add(Fijo("lx-c7", "c7.mingol", "CO Factor1 _x 1. CO", "BEGIN", "print ( newline );", "SKIP", "END"));
        l.add(Fijo("lx-c8", "c8.mingol", "BEGIN", "print ( newline );", "SKIP", "END"));
        c = Fijo("ilegal", "ilegal.mingol", "BEGIN", "%", "SKIP", "END");
        c.oraculo.P(2, Err(101, "Carácter ilegal", "%"));
        l.add(c);
        // 4. BEGIN/END repetidos y faltantes (202, 305, 306, 307, 308)
        c = Fijo("be-sobrante", "be-sobrante.mingol", "BEGIN;", "print ( newline );", "SKIP", "END;");
        c.oraculo.P(1, E202("BEGIN", ";"));
        c.oraculo.P(4, E202("END", ";"));
        l.add(c);
        c = Fijo("be-begin2", "be-begin2.mingol", "BEGIN", "BEGIN", "SKIP", "END");
        c.oraculo.E(2, E305());
        l.add(c);
        c = Fijo("be-end2", "be-end2.mingol", "BEGIN", "SKIP", "END", "END");
        c.oraculo.E(4, E306());
        l.add(c);
        c = Fijo("be-ambos2", "be-ambos2.mingol", "BEGIN", "BEGIN", "print ( newline );", "SKIP", "END", "", "END");
        c.oraculo.E(2, E305());
        c.oraculo.E(7, E306());
        l.add(c);
        // Sin BEGIN (DS3): solo 307; el END no debe dar 217
        c = Fijo("be-sinbegin", "be-sinbegin.mingol", "print ( newline );", "SKIP", "END");
        c.oraculo.E(0, E307());
        l.add(c);
        c = Fijo("be-sinend", "be-sinend.mingol", "BEGIN", "print ( newline );");
        c.oraculo.E(0, E308());
        l.add(c);
        c = Fijo("be-ninguno", "be-ninguno.mingol", "print ( newline );");
        c.oraculo.E(0, E307());
        c.oraculo.E(0, E308());
        l.add(c);
        return l;
    }

    // Oráculo de preservación contra una captura: mensajes del parser y del evaluador (multiconjuntos por
    // línea), hasErrors y TestProyecto
    private static void ComprobarOraculo(Programa o, String captura, List<String> dif) {
        List<String> parser = new ArrayList<>();
        List<String> evaluador = new ArrayList<>();
        String has = null, tp = null;
        for (String l : captura.split("\n")) {
            String[] c = l.split("\\|", 6);
            if (c[0].equals("MSG") && c.length == 6) parser.add(c[1] + "|" + c[5]);
            else if (c[0].equals("EVAL") && c.length == 6) evaluador.add(c[1] + "|" + c[5]);
            else if (c[0].equals("HAS")) has = l.substring(4);
            else if (c[0].equals("TP")) tp = l.substring(3);
            else if (c[0].equals("PARSER")) dif.add("oráculo: excepción " + l);
        }
        CompararMulticonjunto("lexer/parser", o.parser, parser, dif);
        CompararMulticonjunto("evaluador", o.evaluador, evaluador, dif);
        if (!String.valueOf(o.HasErrors()).equals(has)) dif.add("oráculo: hasErrors esperado " + o.HasErrors() + ", obtenido " + has);
        if (!String.valueOf(o.Valido()).equals(tp)) dif.add("oráculo: TestProyecto esperado " + o.Valido() + ", obtenido " + tp);
    }

    private static void CompararMulticonjunto(String que, List<String> esperado, List<String> obtenido, List<String> dif) {
        List<String> faltan = new ArrayList<>(esperado);
        List<String> sobran = new ArrayList<>();
        for (String s : obtenido) if (!faltan.remove(s)) sobran.add(s);
        if (!faltan.isEmpty()) dif.add("oráculo (" + que + "): faltan " + faltan);
        if (!sobran.isEmpty()) dif.add("oráculo (" + que + "): sobran " + sobran);
    }

    // Nombre del -errores.txt como lo calcula TestProyecto: sin carpetas ni la última extensión
    private static String NombreErrores(String archivo) {
        String nombre = Paths.get(archivo).getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return ((punto > 0) ? nombre.substring(0, punto) : nombre) + "-errores.txt";
    }

    private static final String ARCHIVO_CAPTURA = "captura-arnes.txt";

    // Carpeta de la captura (fuera de la carpeta de trabajo, en %TEMP%); la del léxico no se toca (R9)
    private static Path CarpetaGolden() throws IOException {
        return CarpetaTemp().resolve("mingol-sintaxis-golden");
    }

    // Corre la captura de un caso en un hijo (R8) dentro de dir; devuelve {captura, -errores.txt o null}
    private static Object[] CorrerCaptura(CasoCap c, byte[] fuente, Path dir, List<String> dif) throws Exception {
        BorrarArbol(dir);
        Files.createDirectories(dir);
        Files.write(dir.resolve(c.archivo), fuente);
        ResultadoHijo r = LanzarHijo(L("hijocap", c.archivo), dir, c.id);
        ProblemasDeEjecucion(r, dif);
        Path cap = dir.resolve(ARCHIVO_CAPTURA);
        String captura = Files.exists(cap) ? new String(Files.readAllBytes(cap), StandardCharsets.UTF_8) : null;
        if (captura == null) dif.add("el hijo no escribió " + ARCHIVO_CAPTURA);
        Path err = dir.resolve(NombreErrores(c.archivo));
        byte[] errores = Files.exists(err) ? Files.readAllBytes(err) : null;
        if (errores == null) dif.add("no se generó " + NombreErrores(c.archivo));
        return new Object[]{captura, errores};
    }

    // Comprobaciones que no dependen de la captura: oráculo (semillas y fijos) o -errores.txt de Examples
    private static void ComprobarReferencia(CasoCap c, String captura, byte[] errores, List<String> dif) {
        if (c.oraculo != null && captura != null) ComprobarOraculo(c.oraculo, captura, dif);
        if (c.archivoEsperado != null && errores != null) {
            String obtenido = new String(errores, StandardCharsets.ISO_8859_1);
            if (!c.archivoEsperado.equals(obtenido)) dif.add("control: -errores.txt " + PrimeraDiferencia(c.archivoEsperado, obtenido));
        }
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
        Cobertura(casos);
        StringBuilder indice = new StringBuilder();
        for (CasoCap c : casos) {
            List<String> dif = new ArrayList<>();
            byte[] fuente = c.Fuente();
            Object[] res = CorrerCaptura(c, fuente, trabajo.resolve(c.id), dif);
            String captura = (String) res[0];
            byte[] errores = (byte[]) res[1];
            Path g = golden.resolve(c.id);
            Files.createDirectories(g);
            Files.write(g.resolve("fuente.bin"), fuente);
            Files.write(g.resolve("meta.txt"), ("archivo=" + c.archivo + "\n").getBytes(StandardCharsets.UTF_8));
            if (captura != null) Files.write(g.resolve("captura.txt"), captura.getBytes(StandardCharsets.UTF_8));
            if (errores != null) Files.write(g.resolve("errores.bin"), errores);
            ComprobarReferencia(c, captura, errores, dif);
            indice.append(c.id).append('\n');
            Registrar("captura " + c.id, "captura", true, dif);
        }
        // El índice se escribe al final: marca la captura como completa
        Files.write(golden.resolve("indice.txt"), indice.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Captura en " + golden + ": " + casos.size() + " casos");
        ResumenSimple("capturar");
    }

    // Cuántas semillas producen cada código o advertencia (cobertura del dominio)
    private static void Cobertura(List<CasoCap> casos) {
        Map<String, Integer> cuenta = new TreeMap<>();
        int validos = 0, semillas = 0;
        for (CasoCap c : casos) {
            if (!c.id.startsWith("g")) continue;
            semillas++;
            if (c.oraculo.Valido()) validos++;
            Set<String> codigos = new HashSet<>();
            List<String> todos = new ArrayList<>(c.oraculo.parser);
            todos.addAll(c.oraculo.evaluador);
            for (String s : todos) {
                String m = s.substring(s.indexOf('|') + 1);
                codigos.add(m.startsWith("ERROR") ? m.substring(6, 9) : "adv");
            }
            for (String k : codigos) cuenta.merge(k, 1, Integer::sum);
        }
        System.out.println("Cobertura del generador (" + semillas + " semillas, " + validos + " válidas): " + cuenta);
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
        Map<String, CasoCap> porId = new HashMap<>();
        for (CasoCap c : actuales) {
            idsActuales.add(c.id);
            porId.put(c.id, c);
            Path f = golden.resolve(c.id).resolve("fuente.bin");
            if (Files.exists(f) && !Arrays.equals(Files.readAllBytes(f), c.Fuente())) difIds.add(c.id + ": el generador ya no da el fuente capturado");
        }
        if (!ids.equals(idsActuales)) difIds.add("casos capturados distintos de los actuales (" + ids.size() + " / " + idsActuales.size() + ")");
        Registrar("casos y fuentes iguales a la captura", "P2", true, difIds);

        for (String id : ids) {
            List<String> dif = new ArrayList<>();
            Path g = golden.resolve(id);
            String archivo = null;
            for (String l : Files.readAllLines(g.resolve("meta.txt"), StandardCharsets.UTF_8)) {
                if (l.startsWith("archivo=")) archivo = l.substring("archivo=".length());
            }
            CasoCap c = porId.get(id);
            if (c == null) {
                // Caso capturado que ya no existe: se corre igual con su fuente y archivo
                c = new CasoCap();
                c.id = id;
                c.archivo = archivo;
            }
            byte[] fuente = Files.readAllBytes(g.resolve("fuente.bin"));
            Path capG = g.resolve("captura.txt");
            Path errG = g.resolve("errores.bin");
            String esperado = Files.exists(capG) ? new String(Files.readAllBytes(capG), StandardCharsets.UTF_8) : null;
            byte[] erroresEsp = Files.exists(errG) ? Files.readAllBytes(errG) : null;

            Object[] res = CorrerCaptura(c, fuente, trabajo.resolve(id), dif);
            String obtenido = (String) res[0];
            byte[] erroresObt = (byte[]) res[1];
            if (esperado == null) dif.add("la captura no tiene captura.txt");
            if (esperado != null && obtenido != null) {
                // GetMensajes, GetErrors, hasErrors, evaluador y TestProyecto iguales
                List<String> a = Arrays.asList(esperado.split("\n", -1)), b = Arrays.asList(obtenido.split("\n", -1));
                if (!a.equals(b)) dif.add("captura: " + PrimeraDiferencia(String.join("\n", a), String.join("\n", b)));
            }
            // -errores.txt byte a byte (leído con ISO-8859-1 para el reporte, R6)
            if (!Arrays.equals(erroresEsp, erroresObt)) {
                String ea = erroresEsp == null ? "(ausente)" : new String(erroresEsp, StandardCharsets.ISO_8859_1);
                String eb = erroresObt == null ? "(ausente)" : new String(erroresObt, StandardCharsets.ISO_8859_1);
                dif.add("-errores.txt: " + PrimeraDiferencia(ea, eb));
            }
            // El oráculo (el mismo que usará CI) también debe seguir valiendo
            ComprobarReferencia(c, obtenido, erroresObt, dif);
            Registrar("preservación " + id, "P2", true, dif);
        }
        ResumenSimple("preservacion");
    }

    // Hijo de captura: GetMensajes, GetErrors, hasErrors, mensajes del evaluador y TestProyecto por
    // reflexión (que escribe el -errores.txt). Solo usa API presente antes y después del arreglo.
    private static void HijoCap(String archivo) throws Exception {
        StringBuilder sb = new StringBuilder();
        String fuente = LeerFuente(archivo);
        try {
            Evaluator.Reiniciar();
            Parser p = new Parser(new Lexer(fuente));
            Program prog = p.ParseProgram();
            for (Mensaje m : p.GetMensajes()) sb.append("MSG|").append(FormatoMensaje(m)).append('\n');
            for (String e : p.GetErrors()) sb.append("ERR|").append(Escapar(String.valueOf(e))).append('\n');
            sb.append("HAS|").append(p.hasErrors).append('\n');
            Evaluator.Evaluate(prog, new Environment());
            for (Mensaje m : Evaluator.GetMensajes()) sb.append("EVAL|").append(FormatoMensaje(m)).append('\n');
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

    // línea|categoría|código|lexema|mensaje formateado
    private static String FormatoMensaje(Mensaje m) {
        return m.getLinea() + "|" + m.getCategoria() + "|" + (m.getCodigo() == null ? "-" : String.valueOf(m.getCodigo()))
                + "|" + Escapar(String.valueOf(m.getLexema())) + "|" + Escapar(m.Formatear());
    }

    // Resumen de capturar/preservacion: total y fallas
    private static void ResumenSimple(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) if (r.startsWith("FALLA")) System.out.println(r);
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Tarea 3.9: lotes en hijos (R8) y comprobaciones comunes
    // ------------------------------------------------------------------

    // Fuentes por hijo; cada hijo con su límite de 10 s (R8)
    private static final int LOTE = 20;

    // Resultado de un fuente analizado en un hijo de lote
    private static class Dump {
        int lx = -1;                                     // mensajes del lexer al comienzo de GetMensajes()
        final List<String[]> m = new ArrayList<>();      // GetMensajes(): línea, categoría, código, lexema, texto, formateado
        final List<String> r = new ArrayList<>();        // GetErrors()
        String h;                                        // hasErrors
        final List<String[]> v = new ArrayList<>();      // mensajes del evaluador
        final List<String> ast = new ArrayList<>();      // resumen del AST (cuenta.X=n y datos)
        String tp;                                       // TestProyecto (si se pidió)
        final List<String> exc = new ArrayList<>();      // excepciones
    }

    // línea|categoría|código|lexema|texto|formateado
    private static String FormatoMensajeT(Mensaje m) {
        return m.getLinea() + "|" + m.getCategoria() + "|" + (m.getCodigo() == null ? "-" : String.valueOf(m.getCodigo()))
                + "|" + Escapar(String.valueOf(m.getLexema())) + "|" + Escapar(String.valueOf(m.getTexto()))
                + "|" + Escapar(m.Formatear());
    }

    // Hijo de lote: por cada "archivo|tp" de la lista, parser, evaluador, AST y (si tp=1) TestProyecto;
    // escribe archivo.dump al terminar cada fuente (si se cuelga, el padre sabe en cuál)
    private static void HijoLote(String lista) throws Exception {
        Method tp = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        tp.setAccessible(true);
        for (String l : Files.readAllLines(Paths.get(lista), StandardCharsets.UTF_8)) {
            if (l.isBlank()) continue;
            String[] p = l.split("\\|");
            String archivo = p[0];
            StringBuilder sb = new StringBuilder();
            try {
                String fuente = LeerFuente(archivo);
                Evaluator.Reiniciar();
                Lexer lx = new Lexer(fuente);
                Parser ps = new Parser(lx);
                Program prog = ps.ParseProgram();
                ArrayList<Mensaje> todos = ps.GetMensajes();
                sb.append("LX|").append(lx.GetMensajes().size()).append('\n');
                for (Mensaje m : todos) sb.append("M|").append(FormatoMensajeT(m)).append('\n');
                for (String e : ps.GetErrors()) sb.append("R|").append(Escapar(String.valueOf(e))).append('\n');
                sb.append("H|").append(ps.hasErrors).append('\n');
                Evaluator.Evaluate(prog, new Environment());
                for (Mensaje m : Evaluator.GetMensajes()) sb.append("V|").append(FormatoMensajeT(m)).append('\n');
                Map<String, Integer> conteo = new TreeMap<>();
                List<String> datos = new ArrayList<>();
                Recorrer(prog, conteo, datos, new IdentityHashMap<>(), 0);
                for (Map.Entry<String, Integer> e : conteo.entrySet()) sb.append("A|cuenta.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
                for (String d : datos) sb.append("A|").append(Escapar(d)).append('\n');
            } catch (Throwable t) {
                sb.append("X|").append(t.getClass().getName()).append('|').append(Escapar(String.valueOf(t.getMessage()))).append('\n');
            }
            if (p.length > 1 && p[1].equals("1")) {
                try {
                    sb.append("T|").append(tp.invoke(null, archivo)).append('\n');
                } catch (InvocationTargetException e) {
                    Throwable c = e.getCause();
                    sb.append("X|TestProyecto|").append(c.getClass().getName()).append('|').append(Escapar(String.valueOf(c.getMessage()))).append('\n');
                }
            }
            Files.write(Paths.get(archivo + ".dump"), sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.exit(0);
    }

    private static Dump LeerDump(Path p) throws IOException {
        Dump d = new Dump();
        for (String l : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
            int i = l.indexOf('|');
            if (i < 0) continue;
            String resto = l.substring(i + 1);
            switch (l.substring(0, i)) {
                case "LX": d.lx = Integer.parseInt(resto.trim()); break;
                case "M": d.m.add(resto.split("\\|", 6)); break;
                case "R": d.r.add(resto); break;
                case "H": d.h = resto; break;
                case "V": d.v.add(resto.split("\\|", 6)); break;
                case "A": d.ast.add(resto); break;
                case "T": d.tp = resto; break;
                case "X": d.exc.add(resto); break;
                default: break;
            }
        }
        return d;
    }

    // Corre los fuentes de dir en lotes de LOTE por hijo; los que no dejan .dump van a problemas
    private static Map<String, Dump> CorrerLotes(Path dir, List<String> archivos, Set<String> conTP, String nombre,
                                                 Map<String, String> problemas) throws Exception {
        Map<String, Dump> res = new HashMap<>();
        for (int i = 0; i < archivos.size(); i += LOTE) {
            List<String> sub = archivos.subList(i, Math.min(archivos.size(), i + LOTE));
            StringBuilder sb = new StringBuilder();
            for (String a : sub) sb.append(a).append('|').append(conTP.contains(a) ? "1" : "0").append('\n');
            String lote = "lote-" + nombre + "-" + (i / LOTE);
            Files.write(dir.resolve(lote + ".txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
            ResultadoHijo r = LanzarHijo(L("hijolote", lote + ".txt"), dir, lote);
            List<String> dif = new ArrayList<>();
            ProblemasDeEjecucion(r, dif);
            for (String a : sub) {
                Path dp = dir.resolve(a + ".dump");
                if (Files.exists(dp)) res.put(a, LeerDump(dp));
                else problemas.put(a, "sin resultado del hijo " + lote + (dif.isEmpty() ? "" : ": " + dif));
            }
            // Un hijo que no terminó bien invalida todo su lote
            if (!dif.isEmpty()) for (String a : sub) problemas.putIfAbsent(a, "hijo " + lote + ": " + dif);
        }
        return res;
    }

    // Cadena de GetErrors() que corresponde a un mensaje del parser (tabla final de códigos;
    // 101, 202, 204 y advertencias con la cadena de hoy). null = 102 (se compara el prefijo)
    private static String CadenaEsperada(String[] m) {
        String linea = m[0], cat = m[1], cod = m[2], lex = m[3], texto = m[4];
        if (cat.equals("ADVERTENCIA")) return "\tLinea " + linea + " ADVERTENCIA: Instruccion " + lex + " no soportada por esta version";
        switch (cod) {
            case "101": return "\tLinea " + linea + "Error 205: Token ilegal: " + lex;
            case "108": return "\tLinea " + linea + " Error 108: Constante entera fuera de rango: " + lex;
            case "110": return "\tLinea " + linea + " Error 110: Constante real fuera de rango: " + lex;
            case "102": return null;
            case "202": {
                String pre = "No puede haber nada en la línea después de ";
                String x = texto.startsWith(pre) ? texto.substring(pre.length()) : "?" + texto;
                return "\tLinea " + linea + " Error 202: no puede haber nada en la línea luego del token " + x;
            }
            case "204": return "\tLinea " + linea + " Error 204: " + texto.replace(" pero se obtuvo ", " pero se obtuvo un ");
            default:
                if (cod.startsWith("2")) return "\tLinea " + linea + " Error " + cod + ": " + texto + " [" + lex + "]";
                return "(mensaje " + cod + " inesperado del parser)";
        }
    }

    private static String Visible(String s) {
        return s == null ? "null" : s.replace("\t", "\\t");
    }

    // Para todo fuente: sin excepciones, sin 201/203, hasErrors = algún ERROR del lexer o del parser,
    // y una cadena de GetErrors() por mensaje del parser, en el mismo orden y con la misma línea
    private static void ComprobarComun(Dump d, List<String> dif) {
        for (String e : d.exc) dif.add("excepción: " + e);
        if (d.lx < 0) {
            dif.add("el hijo no informó los mensajes");
            return;
        }
        boolean hayError = false;
        for (String[] m : d.m) {
            if (m.length < 6) {
                dif.add("mensaje mal formado: " + Arrays.toString(m));
                continue;
            }
            if (m[2].equals("201") || m[2].equals("203")) dif.add("código retirado: " + m[0] + ":" + m[5]);
            if (!m[1].equals("ADVERTENCIA")) hayError = true;
        }
        if (!String.valueOf(hayError).equals(d.h)) {
            dif.add("hasErrors=" + d.h + " pero " + (hayError ? "hay" : "no hay") + " errores del lexer o del parser");
        }
        List<String[]> delParser = d.m.subList(Math.min(d.lx, d.m.size()), d.m.size());
        if (delParser.size() != d.r.size()) {
            dif.add("GetErrors() tiene " + d.r.size() + " cadenas para " + delParser.size() + " mensajes del parser");
        }
        for (int i = 0; i < Math.min(delParser.size(), d.r.size()); i++) {
            String[] m = delParser.get(i);
            if (m.length < 6) continue;
            String esp = CadenaEsperada(m);
            String obt = d.r.get(i);
            boolean ok = esp == null ? obt.startsWith("\tLinea " + m[0] + " Error 203: conversion invalida") : esp.equals(obt);
            if (!ok) dif.add("cadena " + (i + 1) + " de GetErrors(): esperada [" + Visible(esp) + "], obtenida [" + Visible(obt) + "]");
        }
    }

    // Validez: TestProyecto = ningún ERROR en el -errores.txt
    private static void ComprobarValidez(Dump d, Path dir, String archivo, List<String> dif) throws IOException {
        Path err = dir.resolve(NombreErrores(archivo));
        if (!Files.exists(err)) {
            dif.add("no se generó " + err.getFileName());
            return;
        }
        boolean hayError = false;
        for (String l : new String(Files.readAllBytes(err), StandardCharsets.ISO_8859_1).split("\n")) {
            if (l.startsWith(S8 + "ERROR")) hayError = true;
        }
        if (!String.valueOf(!hayError).equals(d.tp)) {
            dif.add("TestProyecto=" + d.tp + " pero el -errores.txt " + (hayError ? "tiene" : "no tiene") + " errores");
        }
    }

    // Fuente con CRLF y salto final (mismo formato que la exploración)
    private static byte[] Bytes(List<String> lineas) {
        return (String.join("\r\n", lineas) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------
    // Modo unitarias (diseño, Unit Tests)
    // ------------------------------------------------------------------

    // Caso unitario: fuente tal cual, sin envoltorio
    private static Caso U(String grupo, String id, String... lineas) {
        Caso c = O(id, lineas);
        c.grupo = grupo;
        return c;
    }

    private static String E110(String lex) { return Err(110, "Constante real fuera de rango", lex); }

    // Casos obligatorios de la tarea 1 que se repiten en unitarias
    private static final List<String> OBLIGATORIOS = Arrays.asList("s4a_if", "s4a_for", "s4b", "s9_if", "s9_for",
            "ds1", "ds2_a", "ds2_b", "ds3", "ds3_x", "ds6", "s68", "s72");

    private static List<Caso> CasosUnitarios() {
        List<Caso> c = new ArrayList<>();
        // 2.1 declaraciones válidas, en una o varias líneas; cada identificador con su tipo y sin valor
        c.add(U("2.1", "u2_1a", "BEGIN", "INT a;", "REAL b, c;", "CHAR d;", "print ( (a, b, c, d) )", "END")
                .ast("cuenta.Declaracion", "3").ast("cuenta.LetStatement", "4")
                .ast("Declaracion", "INT:a:sinvalor").ast("Declaracion", "REAL:b,c:sinvalor").ast("Declaracion", "CHAR:d:sinvalor"));
        c.add(U("2.1", "u2_1b", "BEGIN", "REAL b,", "c;", "INT x;", "print ( (b, c, x) )", "END")
                .ast("Declaracion", "REAL:b,c:sinvalor").ast("Declaracion", "INT:x:sinvalor"));
        // 2.2 valor inicial: un 210 por declaración, sin 201/203; la variable queda declarada (DS2)
        c.add(U("2.2", "u2_2", "BEGIN", "INT a := 42;", "INT b := -32768;", "REAL r := 1.5;", "CHAR y := zz;",
                "print ( (a, b, r, y) )", "END")
                .m(2, E210()).m(3, E210()).m(4, E210()).m(5, E210())
                .ast("Declaracion", "INT:a:sinvalor").ast("Declaracion", "CHAR:y:sinvalor")
                .cad("\tLinea 2 Error 210: Declaración con valor inicial [:=]"));
        // 2.3 comas: 211 con el segundo identificador, 212 con la coma
        c.add(U("2.3", "u2_3", "BEGIN", "INT a b;", "INT , c;", "INT d,;", "INT e, , f;", "INT g,", "SKIP", "END")
                .m(2, E211("b")).m(3, E212()).m(4, E212()).m(5, E212()).m(6, E212())
                .ast("Declaracion", "INT:a:sinvalor").ast("Declaracion", "INT:e:sinvalor"));
        // 2.4 asignación en una y en cinco líneas físicas, como secuencia sin gramática
        c.add(U("2.4", "u2_4a", "BEGIN", "INT a, b;", "a := ( a *", "b ) + 2;", "b := a;", "print ( (a, b) )", "END")
                .ast("cuenta.Asignacion", "2").ast("Asignacion", "a:Identifier:a,Identifier:b,Integral:2")
                .ast("Asignacion", "b:Identifier:a"));
        c.add(U("2.4", "u2_4b", "BEGIN", "INT a;", "a := 1", "+ 2", "+ 3", "+ 4", "+ 5;", "print ( (a) )", "END")
                .ast("Asignacion", "a:Integral:1,Integral:2,Integral:3,Integral:4,Integral:5"));
        // 2.5 palabras ALGOL dentro de expresiones y condiciones: sin advertencia
        c.add(U("2.5", "u2_5", "BEGIN", "INT a, b;", "a := max ( b, 1 ) + ABS b;", "IF ( a = 1 OR a = 2 ) THEN",
                "print ( newline )", "FI", "END")
                .ast("Secuencia.algol", "max").ast("Secuencia.algol", "ABS").ast("Secuencia.algol", "OR"));
        // 2.6 cuerpos completos de FOR e IF (con ELSE)
        c.add(U("2.6", "u2_6", "BEGIN", "INT a, i;", "FOR i FROM 1 BY 1 TO 3 DO", "print ( newline );", "a := i;",
                "read ( a )", "OD;", "IF ( a > 0 ) THEN", "print ( newline );", "print ( (a) )", "ELSE", "SKIP;",
                "print ( newline )", "FI", "END")
                .ast("For.comandos", "3").ast("If.consecuencia", "2").ast("If.alternativa", "2"));
        // 2.7 cabeceras multilínea
        c.add(U("2.7", "u2_7", "BEGIN", "INT a, i;", "IF ( a >", "0 )", "THEN", "SKIP", "FI;", "FOR i FROM 1", "BY 1",
                "TO 3", "DO", "SKIP", "OD", "END")
                .ast("If.consecuencia", "1").ast("For.comandos", "1"));
        // 2.8 ';' en la última línea antes de ELSE, FI, OD y END: 206 con el cierre en el texto
        c.add(U("2.8", "u2_8", "BEGIN", "INT a, i;", "IF ( a > 0 ) THEN", "print ( newline );", "ELSE",
                "print ( newline );", "FI;", "FOR i FROM 1 BY 1 TO 2 DO", "SKIP;", "OD;", "print ( newline );", "END")
                .m(4, E206("ELSE")).m(6, E206("FI")).m(9, E206("OD")).m(11, E206("END"))
                .cad("\tLinea 4 Error 206: La línea anterior a ELSE no lleva punto y coma [;]"));
        // 2.9 sin ';' antes de un comando (print, FI, OD, SKIP, GOTO, asignación de dos líneas): 204 [EOL]
        c.add(U("2.9", "u2_9", "BEGIN", "INT a, i;", "print ( newline )", "IF ( a > 0 ) THEN", "SKIP", "FI",
                "FOR i FROM 1 BY 1 TO 2 DO", "SKIP", "OD", "fin:", "SKIP", "GOTO fin", "a := 1", "+ 2", "read ( a )", "END")
                .m(3, E204Eol()).m(6, E204Eol()).m(9, E204Eol()).m(11, E204Eol()).m(12, E204Eol()).m(14, E204Eol())
                .cad("\tLinea 3 Error 204: Se esperaba un token de tipo SEMICOLON pero se obtuvo un EOL"));
        // 2.10 sobrantes: un 202 con el primer token sobrante
        c.add(U("2.10", "u2_10a", "BEGIN xx", "INT a;", "IF ( a > 0 ) THEN;", "SKIP", "ELSE;", "SKIP", "FI;",
                "FOR a FROM 1 BY 1 TO 2 DO;", "SKIP", "OD;", "inicio: print ( newline );", "fin: ;",
                "print ( newline ); print ( newline );", "SKIP; xx", "END;")
                .m(1, E202("BEGIN", "xx")).m(3, E202("THEN", ";")).m(5, E202("ELSE", ";")).m(8, E202("DO", ";"))
                .m(11, E202("ETIQUETA", "print")).m(12, E202("ETIQUETA", ";")).m(13, E202("SEMICOLON", "print"))
                .m(14, E202("SEMICOLON", "xx")).m(15, E202("END", ";"))
                .cad("\tLinea 1 Error 202: no puede haber nada en la línea luego del token BEGIN"));
        // 2.10 y tabla de códigos: algo después de FI/OD (sin su ';') en la misma línea → 202 FI/OD
        c.add(U("2.10", "u2_10b", "BEGIN", "INT a;", "IF ( a > 0 ) THEN", "SKIP", "FI xx", "FOR a FROM 1 BY 1 TO 2 DO",
                "SKIP", "OD xx", "SKIP", "END")
                .m(5, E202("FI", "xx")).m(8, E202("OD", "xx")));
        // 2.11 comandos desconocidos: solo 205, sin 103 (L2) ni 303
        c.add(U("2.11", "u2_11", "BEGIN", "RREAL factor1;", "READ ( x );", "foo;", "xx yy zz;", "for i;", "print ( newline )", "END")
                .m(2, E205("RREAL")).m(3, E205("READ")).m(4, E205("foo")).m(5, E205("xx")).m(6, E205("for"))
                .cad("\tLinea 2 Error 205: No es una instrucción válida de MINGOL ni de ALGOL [RREAL]"));
        // 2.12 palabras de MINGOL fuera de lugar: 213
        c.add(U("2.12", "u2_12a", "BEGIN", "print ( newline );", "THEN SKIP;", "DO;", "FROM 1;", "BY 2;", "TO 3;", "SKIP", "END")
                .m(3, E213("THEN")).m(4, E213("DO")).m(5, E213("FROM")).m(6, E213("BY")).m(7, E213("TO")));
        c.add(U("2.12", "u2_12b", "BEGIN", "print ( newline )", "ELSE", "SKIP", "OD", "SKIP", "END")
                .m(3, E213("ELSE")).m(5, E213("OD")));
        // 2.13 comandos antes de BEGIN y después de END (también ALGOL): 207/208, sin advertencia
        c.add(U("2.13", "u2_13a", "# nota #", "LONG x;", "print ( newline );", "BEGIN", "SKIP", "END", "PRAGMAT y PRAGMAT", "SKIP")
                .m(2, E207("LONG")).m(3, E207("print")).m(7, E208("PRAGMAT")).m(8, E208("SKIP")));
        // 2.13 sin END: solo el 308 de hoy
        c.add(U("2.13", "u2_13b", "BEGIN", "print ( newline )").fin(E308()));
        // 2.14 declaración tardía: 209; ALGOL no cierra la sección, una etiqueta sí (S5)
        c.add(U("2.14", "u2_14", "BEGIN", "LONG x;", "INT a;", "inicio:", "INT b;", "SKIP;", "REAL c;", "print ( newline )", "END")
                .m(2, Adv("LONG")).m(5, E209("INT")).m(7, E209("REAL")));
        // 2.15 sin comandos entre BEGIN y END: 217
        c.add(U("2.15", "u2_15a", "BEGIN", "# solo un comentario #", "", "END").m(4, E217()));
        c.add(U("2.15", "u2_15b", "BEGIN", "INT a", "END").m(3, E217()));
        // 2.16 SKIP: con ';' antes de un comando es válido; los demás casos según 2.8/2.9/2.10
        c.add(U("2.16", "u2_16", "BEGIN", "SKIP;", "print ( newline );", "SKIP", "print ( newline );", "SKIP xx;",
                "SKIP; xx", "SKIP;", "END")
                .m(4, E204Eol()).m(6, E204("SEMICOLON", "IDENT", "xx")).m(7, E202("SEMICOLON", "xx")).m(8, E206("END")));
        // 2.16 e8: SKIP; antes de END da 206 y el END se reconoce (sin 308)
        c.add(U("2.16", "u2_16e8", "BEGIN", "INT x;", "IF (x) THEN", "SKIP;", "END")
                .m(4, E206("END")).m(5, E204("FI", "END", "END"))
                .cad("\tLinea 5 Error 204: Se esperaba un token de tipo FI pero se obtuvo un END"));
        // 2.17 read
        c.add(U("2.17", "u2_17", "BEGIN", "INT a, b;", "read ( a );", "read a;", "read ( a, b );", "read ( );", "read ( a )", "END")
                .m(4, E204("LPAREN", "IDENT", "a")).m(5, E204("RPAREN", "COMMA", ",")).m(6, E204("IDENT", "RPAREN", ")")));
        // 2.18 print: las tres formas válidas y un mensaje por forma inválida
        c.add(U("2.18", "u2_18", "BEGIN", "INT a, b;", "print ( (a, b) );", "print ( (a) );", "print ( \"texto\" );",
                "print ( newline );", "print ( a );", "print \"hola\";", "print ( (a,) );", "print ( (,a) );",
                "print ( (a b) );", "print ( ( ) );", "print ( newline )", "END")
                .m(7, E204("LPAREN", "IDENT", "a")).m(8, E204("LPAREN", "STRING", "hola")).m(9, E212()).m(10, E212())
                .m(11, E211("b")).m(12, E204("IDENT", "RPAREN", ")")));
        // 2.19 GOTO
        c.add(U("2.19", "u2_19", "BEGIN", "fin:", "GOTO;", "GOTO fin extra;", "GOTO fin;", "GOTO fin", "END")
                .m(3, E204("IDENT", "SEMICOLON", ";")).m(4, E204("SEMICOLON", "IDENT", "extra")));
        // 2.20 sin cascadas: cada error seguido de líneas válidas; el ilegal solo da 101
        c.add(U("2.20", "u2_20", "BEGIN", "INT a;", "print ( (a b) );", "read ( a );", "foo bar baz;", "read ( a );",
                "GOTO;", "print ( % newline );", "a := ( a +", "2 );", "print ( (a) )", "END")
                .m(3, E211("b")).m(5, E205("foo")).m(7, E204("IDENT", "SEMICOLON", ";")).m(8, Err(101, "Carácter ilegal", "%"))
                .cad("\tLinea 8" + "Error 205: Token ilegal: %"));
        // 2.21 línea del token del lexema; [EOL] bajo la última línea del comando; [EOF] con D1
        c.add(U("2.21", "u2_21a", "BEGIN", "INT a, b;", "read (", "a, b );", "a := 1", "+ 2", "print ( newline )", "END")
                .m(4, E204("RPAREN", "COMMA", ",")).m(6, E204Eol()));
        c.add(U("2.21", "u2_21b", "BEGIN", "IF ( 1 > 0 ) THEN", "SKIP").m(3, E204("FI", "EOF", "EOF")).fin(E308())
                .cad("\tLinea 4 Error 204: Se esperaba un token de tipo FI pero se obtuvo un EOF"));
        // 2.22 posición de comando
        c.add(U("2.22", "u2_22a", "BEGIN", "x := ( x *", "Factor2 );", "print ( newline )", "END").m(3, E103("Factor2")));
        c.add(U("2.22", "u2_22b", "BEGIN", "RREAL x;", "READ ( x );", "print ( newline )", "END")
                .m(2, E205("RREAL")).m(3, E205("READ")));
        // S9 con Print: el diseño solo fija que no hay 103 (y el 202 de S9); el resto se informa
        c.add(U("2.22", "u2_22c", "BEGIN", "INT a;", "IF ( a > 0 ) THEN Print ( newline ) FI;", "SKIP", "END")
                .relajado(new String[]{"3:" + E202("THEN", "Print")}, "103"));
        // 2.23 bloques vacíos y anidamiento
        c.add(U("2.23", "u2_23", "BEGIN", "INT a, i;", "IF ( a > 0 ) THEN", "ELSE", "SKIP", "FI;",
                "FOR i FROM 1 BY 1 TO 2 DO", "OD;", "IF ( a > 0 ) THEN", "SKIP", "ELSE", "FI;",
                "IF ( a > 0 ) THEN", "IF ( a > 1 ) THEN", "SKIP", "FI", "FI;",
                "FOR i FROM 1 BY 1 TO 2 DO", "FOR i FROM 1 BY 1 TO 2 DO", "SKIP", "OD", "OD", "END")
                .m(4, E216("ELSE")).m(8, E216("OD")).m(12, E216("FI")).m(14, E214()).m(19, E215()));

        // Bordes del fin de comando
        c.add(U("bordes", "ub_mismalinea", "BEGIN", "INT a;", "IF ( a > 0 ) THEN", "print ( newline ) FI;",
                "FOR a FROM 1 BY 1 TO 2 DO", "SKIP OD;", "SKIP", "END")
                .ast("If.consecuencia", "1").ast("For.comandos", "1"));
        c.add(U("bordes", "ub_vacias", "BEGIN", "INT a;", "a := 1", "", "# nota #", "+ 2;", "IF ( a >", "CO c CO", "0 ) THEN",
                "SKIP", "FI", "END")
                .ast("Asignacion", "a:Integral:1,Integral:2"));
        c.add(U("bordes", "ub_fi_end", "BEGIN", "IF ( 1 > 0 ) THEN", "SKIP", "FI;", "END").m(4, E206("END")));
        c.add(U("bordes", "ub_od_cmd", "BEGIN", "FOR n FROM 1 BY 1 TO 2 DO", "SKIP", "OD", "SKIP", "END").m(4, E204Eol()));

        // L4 dentro de secuencias y en FOR
        c.add(U("L4", "ul4", "BEGIN", "INT a, b, i;", "a := 32768;", "a := -32768;", "a := ( -32768 );", "a := b-32768;",
                "a := 32767.999;", "a := -32768.00;", "FOR i FROM 32768 BY 1 TO 3 DO", "SKIP", "OD;", "a := -32769;",
                "print ( (a) )", "END")
                .m(3, E108("32768")).m(6, E108("32768")).m(7, E110("32767.999")).m(9, E108("32768")).m(12, E108("32769")));

        // AST: Declaracion, Asignacion con Secuencia (Booleano, ALGOL), If con dos ramas, For completo
        c.add(U("AST", "uast", "BEGIN", "INT a, b;", "CHAR c;", "a := b + TRUE * max ( 1 ) OR ABS 2.5;",
                "IF ( a > 0 ) THEN", "SKIP", "ELSE", "print ( newline );", "SKIP", "FI;",
                "FOR a FROM 1 BY 1 TO 3 DO", "SKIP;", "read ( a );", "print ( (a) )", "OD", "END")
                .ast("cuenta.Declaracion", "2").ast("cuenta.LetStatement", "3")
                .ast("Declaracion", "INT:a,b:sinvalor").ast("Declaracion", "CHAR:c:sinvalor")
                .ast("cuenta.Asignacion", "1").ast("cuenta.Secuencia", "2").ast("cuenta.If", "1").ast("cuenta.For", "1")
                .ast("Asignacion", "a:Identifier:b,Booleano:TRUE,Integral:1,Decimal:2.5")
                .ast("Secuencia.algol", "max").ast("Secuencia.algol", "OR").ast("Secuencia.algol", "ABS")
                .ast("If.consecuencia", "1").ast("If.alternativa", "2").ast("For.comandos", "3"));

        // Evaluador: 304, 303 de la condición y de la rama ELSE, sin 301/302 con REAL, asignación muda (DS4)
        c.add(U("evaluador", "uev1", "BEGIN", "INT a;", "INT a;", "REAL r, r;", "SKIP", "END")
                .m(3, E304("a")).m(4, E304("r")));
        c.add(U("evaluador", "uev2", "BEGIN", "REAL r;", "IF ( r > 1.5 OR zz = 2 ) THEN", "SKIP", "ELSE", "GOTO nada",
                "FI;", "a := zz + 1.5 * 3", "END")
                .m(3, E303("zz")).m(6, E303("nada")));

        // Obligatorios (tarea 1), con su -errores.txt de la tabla Examples
        for (Caso o : Casos()) if (OBLIGATORIOS.contains(o.id)) c.add(o);
        return c;
    }

    private static void ComprobarAstLista(Caso c, List<String> ast, List<String> dif) {
        for (Map.Entry<String, String> e : c.ast.entrySet()) {
            List<String> valores = new ArrayList<>();
            for (String a : ast) if (a.startsWith(e.getKey() + "=")) valores.add(a.substring(e.getKey().length() + 1));
            if (!valores.contains(e.getValue())) {
                dif.add("AST " + e.getKey() + ": esperado " + e.getValue() + ", obtenido " + (valores.isEmpty() ? "(ninguno)" : valores));
            }
        }
    }

    private static void Unitarias() throws Exception {
        Path base = CarpetaSegura();
        Path trabajo = base.resolve("unitarias");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        List<Caso> casos = CasosUnitarios();
        List<String> archivos = new ArrayList<>();
        for (Caso c : casos) {
            String a = c.id + ".mingol";
            Files.write(trabajo.resolve(a), Bytes(Arrays.asList(c.lineas)));
            archivos.add(a);
        }
        Map<String, String> problemas = new HashMap<>();
        Map<String, Dump> dumps = CorrerLotes(trabajo, archivos, new HashSet<>(archivos), "u", problemas);
        for (Caso c : casos) {
            List<String> dif = new ArrayList<>();
            String a = c.id + ".mingol";
            if (problemas.containsKey(a)) dif.add(problemas.get(a));
            Dump d = dumps.get(a);
            if (d != null) {
                ComprobarComun(d, dif);
                Path err = trabajo.resolve(c.id + "-errores.txt");
                String obtenido = Files.exists(err) ? new String(Files.readAllBytes(err), StandardCharsets.ISO_8859_1) : null;
                if (obtenido == null) {
                    dif.add("no se generó " + err.getFileName());
                } else if (c.relajado) {
                    // Solo lo que fija el diseño; el resto se informa
                    List<String> msgs = Mensajes(obtenido);
                    for (String s : c.contiene) if (!msgs.contains(s)) dif.add("falta " + s);
                    for (String s : msgs) {
                        for (String cod : c.sinCodigos) if (s.contains("ERROR " + cod + ":")) dif.add("no debería aparecer " + s);
                    }
                    System.out.println("INFO  " + c.id + ": mensajes obtenidos " + msgs);
                } else {
                    String esperado = c.archivoEsperado();
                    if (!esperado.equals(obtenido)) {
                        dif.add("-errores.txt: " + PrimeraDiferencia(esperado, obtenido));
                        dif.add("mensajes esperados: " + Mensajes(esperado));
                        dif.add("mensajes obtenidos: " + Mensajes(obtenido));
                    }
                    if (!String.valueOf(c.valido()).equals(d.tp)) dif.add("TestProyecto esperado " + c.valido() + ", obtenido " + d.tp);
                }
                if (d.tp != null) ComprobarValidez(d, trabajo, a, dif);
                for (String s : c.cadenas) {
                    if (!d.r.contains(s)) dif.add("falta la cadena de GetErrors() [" + Visible(s) + "]; obtenidas " + Visible(String.valueOf(d.r)));
                }
                ComprobarAstLista(c, d.ast, dif);
            }
            Registrar(c.id, c.grupo, true, dif);
        }
        // Meta (2.24): copia de proyecto.mingol en la subcarpeta proyecto, TestProyecto en un hijo
        Proyecto(base);
        ResumenSimple("unitarias");
    }

    // ------------------------------------------------------------------
    // Modo propiedades (diseño, Property-Based Tests)
    // ------------------------------------------------------------------

    private static final int SEMILLAS_PROPIEDADES = 300;
    // Muestra de validez con TestProyecto por familia
    private static final int SEMILLAS_VALIDEZ = 50;

    // Variables y etiquetas del generador de válidos (minúsculas, no reservadas, disjuntas)
    private static final String[] VARS_V = {"a", "b", "c2", "total", "suma", "x_y", "n", "r"};
    private static final String[] ETIQUETAS_V = {"inicio", "otra", "paso", "salida"};

    // Línea generada con lo que necesitan las mutaciones
    private static class LG {
        String t;                // texto
        String k;                // T (transparente), BEGIN, END, DECL, CONT, SIMPLE, LABEL, ALGOL, IFH, FORH, ELSE, FI, OD, X
        boolean inicio;          // primera línea de un comando MINGOL o declaración (palabra de inicio, S4)
        String cmd;              // última línea de un comando terminable: decl, asig, read, print_nl, print_txt, print_list, goto, skip, fi, od
        String grupo;            // tipo del comando al que pertenece la línea
        boolean pyc;             // la última línea termina con ';'
        int bloque = -1;         // bloque cuyo cuerpo la contiene (-1 = nivel superior)
        int cierra = -1;         // bloque que cierra (ELSE, FI, OD)
        String cab;              // THEN o DO al final de la línea (fin de cabecera)
        String arg;              // variable, texto o etiqueta (para romper read/print/GOTO)
        String adv;              // lexema de la advertencia (ALGOL)
        boolean trasPrimer;      // ya empezó el primer comando MINGOL

        LG(String t, String k) {
            this.t = t;
            this.k = k;
        }

        LG Copia() {
            LG x = new LG(t, k);
            x.inicio = inicio; x.cmd = cmd; x.grupo = grupo; x.pyc = pyc; x.bloque = bloque; x.cierra = cierra;
            x.cab = cab; x.arg = arg; x.adv = adv; x.trasPrimer = trasPrimer;
            return x;
        }
    }

    // Programa válido con el AST esperado
    private static class ProgV {
        final List<LG> l = new ArrayList<>();
        final Map<String, Integer> cuenta = new TreeMap<>();
        final List<String> datos = new ArrayList<>();
        Random r;
        int bloque = -1;
        int bloques = 0;
        boolean huboComando;
        final List<String> declaradas = new ArrayList<>();
        final List<String> etiquetas = new ArrayList<>();

        void Cuenta(String clase) { cuenta.merge(clase, 1, Integer::sum); }

        List<String> Lineas() {
            List<String> r = new ArrayList<>();
            for (LG x : l) r.add(x.t);
            return r;
        }
    }

    // Semilla mezclada (splitmix64), como en el generador de preservación
    private static Random Mezclar(long semilla) {
        long z = semilla * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    // Línea transparente para la sintaxis: vacía, espacios o comentario (de una o tres líneas)
    private static void Transp(ProgV g) {
        String[] una = {"", "   ", "# nota #", "CO otro CO", "COMMENT x COMMENT"};
        if (g.r.nextInt(6) == 0) {
            for (String s : new String[]{"CO", "texto libre", "CO"}) {
                LG x = new LG(s, "T");
                x.bloque = g.bloque;
                g.l.add(x);
            }
            return;
        }
        LG x = new LG(una[g.r.nextInt(una.length)], "T");
        x.bloque = g.bloque;
        g.l.add(x);
    }

    // Reparte las partes de un comando en 1 a 5 líneas físicas; ninguna línea de continuación empieza con
    // una palabra de inicio ni con un cierre (las partes lo garantizan). Entre líneas, a veces vacías o comentarios
    private static void EmitirPartes(ProgV g, List<String> partes, String k, String cmd, boolean pyc, String cab, String arg) {
        int n = partes.size();
        int lineas = 1;
        if (n > 1 && g.r.nextInt(3) == 0) lineas = 2 + g.r.nextInt(Math.min(4, n - 1));
        lineas = Math.min(lineas, Math.min(5, n));
        List<Integer> pos = new ArrayList<>();
        for (int i = 1; i < n; i++) pos.add(i);
        Collections.shuffle(pos, g.r);
        List<Integer> cortes = new ArrayList<>(pos.subList(0, lineas - 1));
        Collections.sort(cortes);
        int desde = 0;
        for (int li = 0; li < lineas; li++) {
            int hasta = li < lineas - 1 ? cortes.get(li) : n;
            String texto = String.join(" ", partes.subList(desde, hasta));
            boolean ultima = li == lineas - 1;
            LG x = new LG(ultima && pyc ? texto + ";" : texto, li == 0 ? k : "CONT");
            x.inicio = li == 0 && !k.equals("ALGOL");
            x.grupo = cmd != null ? cmd : k;
            if (ultima) {
                x.cmd = cmd;
                x.pyc = pyc && cmd != null;
                x.cab = cab;
            }
            x.arg = arg;
            x.bloque = g.bloque;
            x.trasPrimer = g.huboComando;
            g.l.add(x);
            if (!ultima && g.r.nextInt(5) == 0) Transp(g);
            desde = hasta;
        }
    }

    private static String VarO(ProgV g, String sino) {
        return g.declaradas.isEmpty() ? sino : g.declaradas.get(g.r.nextInt(g.declaradas.size()));
    }

    // Término de una expresión; anidado = admite un paréntesis (solo uno por expresión)
    private static void Termino(ProgV g, List<String> p, boolean[] anidable) {
        int k = g.r.nextInt(9);
        switch (k) {
            case 3:
                if (g.r.nextInt(4) == 0) p.add("-");
                p.add(String.valueOf(g.r.nextInt(1000)));
                return;
            case 4:
                p.add(g.r.nextInt(100) + "." + g.r.nextInt(10));
                return;
            case 5:
                p.add(g.r.nextBoolean() ? "TRUE" : "FALSE");
                return;
            case 6:
                p.addAll(Arrays.asList("max", "(", VarO(g, "7") + ",", "1", ")"));
                return;
            case 7:
                p.add("ABS");
                p.add(VarO(g, "3"));
                return;
            case 8:
                if (anidable[0]) {
                    anidable[0] = false;
                    p.add("(");
                    p.add(VarO(g, "2"));
                    p.add(new String[]{"+", "-", "*", "/"}[g.r.nextInt(4)]);
                    p.add(String.valueOf(g.r.nextInt(100)));
                    p.add(")");
                    return;
                }
                p.add(VarO(g, "5"));
                return;
            default:
                p.add(VarO(g, String.valueOf(g.r.nextInt(50))));
        }
    }

    private static List<String> Expresion(ProgV g) {
        List<String> p = new ArrayList<>();
        boolean[] anidable = {true};
        int terminos = 1 + g.r.nextInt(3);
        for (int i = 0; i < terminos; i++) {
            if (i > 0) p.add(g.r.nextInt(8) == 0 ? "OR" : new String[]{"+", "-", "*", "/"}[g.r.nextInt(4)]);
            Termino(g, p, anidable);
        }
        return p;
    }

    private static List<String> Condicion(ProgV g) {
        List<String> p = new ArrayList<>();
        boolean[] anidable = {true};
        String[] rel = {">", "<", "=", "/=", "<=", ">="};
        int comparaciones = g.r.nextInt(3) == 0 ? 2 : 1;
        for (int i = 0; i < comparaciones; i++) {
            if (i > 0) p.add("OR");
            Termino(g, p, anidable);
            p.add(rel[g.r.nextInt(rel.length)]);
            Termino(g, p, anidable);
        }
        return p;
    }

    private enum KV { ASIG, READ, PNL, PTXT, PLIST, GOTO, SKIP, LABEL, ALGOL, IF, FOR }

    private static final KV[] SIMPLES_V = {KV.ASIG, KV.READ, KV.PNL, KV.PTXT, KV.PLIST, KV.GOTO, KV.SKIP};

    // Un comando; pyc = no es el último de su secuencia (REQ-LINE-04); top = nivel superior
    private static void EmitirComando(ProgV g, KV tipo, boolean pyc, boolean top) {
        Random r = g.r;
        // Sustituciones cuando falta lo que el comando necesita
        if ((tipo == KV.ASIG || tipo == KV.READ) && g.declaradas.isEmpty()) tipo = KV.PNL;
        if (tipo == KV.PLIST && g.declaradas.isEmpty()) tipo = KV.PTXT;
        if (tipo == KV.GOTO && g.etiquetas.isEmpty()) tipo = top ? KV.LABEL : KV.SKIP;
        if (tipo == KV.LABEL && g.etiquetas.size() == ETIQUETAS_V.length) tipo = KV.SKIP;
        if (!top && (tipo == KV.LABEL || tipo == KV.ALGOL || tipo == KV.IF || tipo == KV.FOR)) tipo = KV.SKIP;
        if (tipo != KV.ALGOL) g.huboComando = true;
        switch (tipo) {
            case ASIG: {
                String v = VarO(g, "a");
                List<String> p = new ArrayList<>();
                p.add(v + " :=");
                p.addAll(Expresion(g));
                EmitirPartes(g, p, "SIMPLE", "asig", pyc, null, v);
                g.Cuenta("Asignacion");
                g.Cuenta("Secuencia");
                return;
            }
            case READ: {
                String v = VarO(g, "a");
                EmitirPartes(g, L("read ( " + v + " )"), "SIMPLE", "read", pyc, null, v);
                g.Cuenta("Read");
                return;
            }
            case PNL:
                EmitirPartes(g, L("print ( newline )"), "SIMPLE", "print_nl", pyc, null, null);
                g.Cuenta("Print");
                return;
            case PTXT: {
                String t = TEXTOS[r.nextInt(TEXTOS.length)];
                EmitirPartes(g, L("print ( \"" + t + "\" )"), "SIMPLE", "print_txt", pyc, null, t);
                g.Cuenta("Print");
                return;
            }
            case PLIST: {
                List<String> ids = new ArrayList<>(g.declaradas);
                Collections.shuffle(ids, r);
                ids = ids.subList(0, 1 + r.nextInt(Math.min(3, ids.size())));
                EmitirPartes(g, L("print ( (" + String.join(", ", ids) + ") )"), "SIMPLE", "print_list", pyc, null, ids.get(0));
                g.Cuenta("Print");
                return;
            }
            case GOTO: {
                String e = g.etiquetas.get(r.nextInt(g.etiquetas.size()));
                EmitirPartes(g, L("GOTO " + e), "SIMPLE", "goto", pyc, null, e);
                g.Cuenta("Goto");
                return;
            }
            case SKIP:
                EmitirPartes(g, L("SKIP"), "SIMPLE", "skip", pyc, null, null);
                g.Cuenta("Skip");
                return;
            case LABEL: {
                List<String> libres = new ArrayList<>(Arrays.asList(ETIQUETAS_V));
                libres.removeAll(g.etiquetas);
                String e = libres.get(r.nextInt(libres.size()));
                g.etiquetas.add(e);
                LG x = new LG(e + (r.nextInt(4) == 0 ? " :" : ":"), "LABEL");
                x.inicio = true;
                x.grupo = "label";
                x.bloque = g.bloque;
                x.trasPrimer = true;
                g.l.add(x);
                g.Cuenta("Etiqueta");
                return;
            }
            case ALGOL: {
                String[] a = INSTRUCCIONES_ALGOL[r.nextInt(INSTRUCCIONES_ALGOL.length)];
                LG x = new LG(a[0], "ALGOL");
                x.grupo = "algol";
                x.adv = a[1];
                x.bloque = g.bloque;
                x.trasPrimer = g.huboComando;
                g.l.add(x);
                return;
            }
            case IF: {
                List<String> p = new ArrayList<>(L("IF", "("));
                p.addAll(Condicion(g));
                p.add(")");
                p.add("THEN");
                EmitirPartes(g, p, "IFH", null, false, "THEN", null);
                int previo = g.bloque;
                int bloqueThen = ++g.bloques;
                g.bloque = bloqueThen;
                int n1 = 1 + r.nextInt(3);
                for (int j = 0; j < n1; j++) {
                    EmitirComando(g, SIMPLES_V[r.nextInt(SIMPLES_V.length)], j < n1 - 1, false);
                    if (r.nextInt(5) == 0) Transp(g);
                }
                g.bloque = previo;
                int ultimo = bloqueThen;
                int n2 = -1;
                if (r.nextBoolean()) {
                    LG el = new LG("ELSE", "ELSE");
                    el.cierra = bloqueThen;
                    el.grupo = "if";
                    el.bloque = previo;
                    el.trasPrimer = true;
                    g.l.add(el);
                    int bloqueElse = ++g.bloques;
                    g.bloque = bloqueElse;
                    n2 = 1 + r.nextInt(3);
                    for (int j = 0; j < n2; j++) {
                        EmitirComando(g, SIMPLES_V[r.nextInt(SIMPLES_V.length)], j < n2 - 1, false);
                        if (r.nextInt(5) == 0) Transp(g);
                    }
                    g.bloque = previo;
                    ultimo = bloqueElse;
                }
                LG fi = new LG(pyc ? "FI;" : "FI", "FI");
                fi.cmd = "fi";
                fi.grupo = "fi";
                fi.pyc = pyc;
                fi.cierra = ultimo;
                fi.bloque = previo;
                fi.trasPrimer = true;
                g.l.add(fi);
                g.Cuenta("If");
                g.Cuenta("Secuencia");
                g.datos.add("If.consecuencia=" + n1);
                g.datos.add("If.alternativa=" + n2);
                return;
            }
            case FOR: {
                List<String> p = L("FOR", VarO(g, "n"), "FROM", VarO(g, "1"), "BY", String.valueOf(1 + r.nextInt(3)),
                        "TO", r.nextBoolean() ? VarO(g, "9") : String.valueOf(1 + r.nextInt(9)), "DO");
                EmitirPartes(g, p, "FORH", null, false, "DO", null);
                int previo = g.bloque;
                int bloqueFor = ++g.bloques;
                g.bloque = bloqueFor;
                int n1 = 1 + r.nextInt(3);
                for (int j = 0; j < n1; j++) {
                    EmitirComando(g, SIMPLES_V[r.nextInt(SIMPLES_V.length)], j < n1 - 1, false);
                    if (r.nextInt(5) == 0) Transp(g);
                }
                g.bloque = previo;
                LG od = new LG(pyc ? "OD;" : "OD", "OD");
                od.cmd = "od";
                od.grupo = "od";
                od.pyc = pyc;
                od.cierra = bloqueFor;
                od.bloque = previo;
                od.trasPrimer = true;
                g.l.add(od);
                g.Cuenta("For");
                g.datos.add("For.comandos=" + n1);
                return;
            }
            default:
                throw new IllegalStateException(String.valueOf(tipo));
        }
    }

    // Programa válido: BEGIN, declaraciones (a veces partidas tras una coma), comandos con ';' según
    // REQ-LINE-04, IF/FOR no anidados con 1 a 3 comandos (ELSE opcional), cortes de línea que no empiezan con
    // palabra de inicio (hasta 5 líneas), líneas vacías y comentarios intercalados, END y líneas finales
    private static ProgV GenerarValido(int semilla) {
        ProgV g = new ProgV();
        g.r = Mezclar(semilla * 7919L + 13);
        Random r = g.r;
        for (int i = r.nextInt(3); i > 0; i--) Transp(g);
        LG b = new LG("BEGIN", "BEGIN");
        b.inicio = true;
        g.l.add(b);
        List<String> pool = new ArrayList<>(Arrays.asList(VARS_V));
        Collections.shuffle(pool, r);
        List<List<String>> decls = new ArrayList<>();
        for (int i = r.nextInt(4); i > 0 && !pool.isEmpty(); i--) {
            List<String> d = new ArrayList<>();
            for (int k = 1 + r.nextInt(3); k > 0 && !pool.isEmpty(); k--) d.add(pool.remove(0));
            decls.add(d);
        }
        int nCmd = 1 + r.nextInt(6);
        KV[] todos = KV.values();
        KV[] tipos = new KV[nCmd];
        boolean algunoMingol = false;
        for (int i = 0; i < nCmd; i++) {
            tipos[i] = todos[r.nextInt(todos.length)];
            if (tipos[i] != KV.ALGOL) algunoMingol = true;
        }
        // Al menos un comando MINGOL (si no, 217)
        if (!algunoMingol) tipos[0] = KV.SKIP;
        int total = decls.size() + nCmd;
        int idx = 0;
        String[] tiposDato = {"INT", "REAL", "CHAR"};
        for (List<String> d : decls) {
            idx++;
            List<String> p = new ArrayList<>();
            for (int k = 0; k < d.size(); k++) {
                String parte = (k == 0 ? tiposDato[r.nextInt(3)] + " " : "") + d.get(k) + (k < d.size() - 1 ? "," : "");
                p.add(parte);
            }
            EmitirPartes(g, p, "DECL", "decl", idx < total, null, null);
            g.declaradas.addAll(d);
            g.Cuenta("Declaracion");
            for (int k = 0; k < d.size(); k++) g.Cuenta("LetStatement");
            if (r.nextInt(4) == 0) Transp(g);
        }
        for (int i = 0; i < nCmd; i++) {
            idx++;
            EmitirComando(g, tipos[i], idx < total, true);
            if (r.nextInt(4) == 0) Transp(g);
        }
        LG e = new LG("END", "END");
        e.inicio = true;
        g.l.add(e);
        for (int i = r.nextInt(3); i > 0; i--) Transp(g);
        return g;
    }

    // Programa con una sola falta y los 2xx que debe dar (línea por identidad de LG)
    private static class Mutado {
        String clase;
        List<LG> l;
        final List<Object[]> esperados = new ArrayList<>();   // {LG, mensaje}

        List<String> Esperados() {
            List<String> r = new ArrayList<>();
            for (Object[] e : esperados) r.add((IndiceDe(l, (LG) e[0]) + 1) + "|" + e[1]);
            return r;
        }
    }

    private static int IndiceDe(List<LG> l, LG x) {
        for (int i = 0; i < l.size(); i++) if (l.get(i) == x) return i;
        return -1;
    }

    // Siguiente línea que la sintaxis ve (no transparente)
    private static LG Siguiente(List<LG> l, int i) {
        for (int j = i + 1; j < l.size(); j++) if (!l.get(j).k.equals("T")) return l.get(j);
        return null;
    }

    private static List<LG> Copiar(List<LG> l) {
        List<LG> c = new ArrayList<>();
        for (LG x : l) c.add(x.Copia());
        return c;
    }

    private static LG Nueva(String t) {
        return new LG(t, "X");
    }

    // Aplica la mutación de la clase dada si hay dónde; null si no es aplicable
    private static Mutado Mutacion(int clase, ProgV p, Random r) {
        Mutado m = new Mutado();
        m.l = Copiar(p.l);
        List<LG> l = m.l;
        List<Integer> cand = new ArrayList<>();
        switch (clase) {
            case 0: {
                // Agregar ';' a la última línea antes de un cierre → 206 con el cierre
                m.clase = "agregar ; antes de un cierre";
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i), s = Siguiente(l, i);
                    if (x.cmd != null && !x.pyc && s != null && Arrays.asList("ELSE", "FI", "OD", "END").contains(s.k)) cand.add(i);
                }
                if (cand.isEmpty()) return null;
                int i = cand.get(r.nextInt(cand.size()));
                l.get(i).t += ";";
                m.esperados.add(new Object[]{l.get(i), E206(Siguiente(l, i).k)});
                return m;
            }
            case 1: {
                // Quitar el ';' de un comando seguido de otro → 204 [EOL]
                m.clase = "quitar ;";
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i), s = Siguiente(l, i);
                    if (x.cmd != null && x.pyc && s != null && s.inicio) cand.add(i);
                }
                if (cand.isEmpty()) return null;
                LG x = l.get(cand.get(r.nextInt(cand.size())));
                x.t = x.t.substring(0, x.t.length() - 1);
                m.esperados.add(new Object[]{x, E204Eol()});
                return m;
            }
            case 2: {
                // Agregar un token después del fin del comando o de BEGIN/END/etiqueta/ELSE/THEN/DO → 202
                m.clase = "agregar token tras el fin";
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i);
                    if ((x.cmd != null && x.pyc) || x.k.equals("BEGIN") || x.k.equals("END") || x.k.equals("LABEL")
                            || x.k.equals("ELSE") || x.cab != null) cand.add(i);
                }
                LG x = l.get(cand.get(r.nextInt(cand.size())));
                if (x.cab != null) {
                    x.t += " ;";
                    m.esperados.add(new Object[]{x, E202(x.cab, ";")});
                } else {
                    x.t += " xx";
                    String despues = x.k.equals("LABEL") ? "ETIQUETA" : (x.k.equals("BEGIN") || x.k.equals("END") || x.k.equals("ELSE")) ? x.k : "SEMICOLON";
                    m.esperados.add(new Object[]{x, E202(despues, "xx")});
                }
                return m;
            }
            case 3: {
                // Insertar un comando desconocido después de un comando con ';' → 205
                m.clase = "comando desconocido";
                for (int i = 0; i < l.size(); i++) if (l.get(i).cmd != null && l.get(i).pyc) cand.add(i);
                if (cand.isEmpty()) return null;
                String[][] v = {{"foo;", "foo"}, {"RREAL x;", "RREAL"}, {"READ ( x );", "READ"}, {"for i;", "for"}, {"xx yy zz;", "xx"}};
                String[] e = v[r.nextInt(v.length)];
                LG n = Nueva(e[0]);
                l.add(cand.get(r.nextInt(cand.size())) + 1, n);
                m.esperados.add(new Object[]{n, E205(e[1])});
                return m;
            }
            case 4: {
                // Un comando antes de BEGIN (207) o después de END (208), también ALGOL
                if (r.nextBoolean()) {
                    m.clase = "comando antes de BEGIN";
                    String[] e = r.nextBoolean() ? new String[]{"print ( newline );", "print"} : new String[]{"LONG x;", "LONG"};
                    LG n = Nueva(e[0]);
                    for (int i = 0; i < l.size(); i++) {
                        if (l.get(i).k.equals("BEGIN")) {
                            l.add(i, n);
                            break;
                        }
                    }
                    m.esperados.add(new Object[]{n, E207(e[1])});
                } else {
                    m.clase = "comando después de END";
                    String[] e = r.nextBoolean() ? new String[]{"print ( newline )", "print"} : new String[]{"PRAGMAT y PRAGMAT", "PRAGMAT"};
                    LG n = Nueva(e[0]);
                    for (int i = 0; i < l.size(); i++) {
                        if (l.get(i).k.equals("END")) {
                            l.add(i + 1, n);
                            break;
                        }
                    }
                    m.esperados.add(new Object[]{n, E208(e[1])});
                }
                return m;
            }
            case 5: {
                // Declaración después del primer comando → 209
                m.clase = "declaración tardía";
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i);
                    if (x.cmd != null && x.pyc && x.trasPrimer && !x.cmd.equals("decl")) cand.add(i);
                }
                if (cand.isEmpty()) return null;
                String tipo = new String[]{"INT", "REAL", "CHAR"}[r.nextInt(3)];
                LG n = Nueva(tipo + " zz;");
                l.add(cand.get(r.nextInt(cand.size())) + 1, n);
                m.esperados.add(new Object[]{n, E209(tipo)});
                return m;
            }
            case 6: {
                // Valor inicial en una declaración → 210
                m.clase = "valor inicial";
                // Una línea de continuación con un solo identificador no sirve: "n := 5;" empieza con
                // "identificador :=", que es palabra de inicio (S4), y ya no continúa la declaración
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i);
                    if ("decl".equals(x.cmd) && (x.inicio || x.t.contains(","))) cand.add(i);
                }
                if (cand.isEmpty()) return null;
                LG x = l.get(cand.get(r.nextInt(cand.size())));
                x.t = x.t.substring(0, x.t.length() - 1) + " := 5;";
                m.esperados.add(new Object[]{x, E210()});
                return m;
            }
            case 7: {
                // Quitar (211) o duplicar (212) una coma de una lista de declaración o de print
                java.util.regex.Pattern pc = java.util.regex.Pattern.compile(", ([a-z][a-z0-9_]*)");
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i);
                    if (("decl".equals(x.grupo) || "print_list".equals(x.grupo)) && pc.matcher(x.t).find()) cand.add(i);
                }
                if (cand.isEmpty()) return null;
                LG x = l.get(cand.get(r.nextInt(cand.size())));
                java.util.regex.Matcher mt = pc.matcher(x.t);
                mt.find();
                if (r.nextBoolean()) {
                    m.clase = "quitar una coma";
                    x.t = x.t.substring(0, mt.start()) + " " + mt.group(1) + x.t.substring(mt.end());
                    m.esperados.add(new Object[]{x, E211(mt.group(1))});
                } else {
                    m.clase = "duplicar una coma";
                    x.t = x.t.substring(0, mt.start()) + ", , " + mt.group(1) + x.t.substring(mt.end());
                    m.esperados.add(new Object[]{x, E212()});
                }
                return m;
            }
            case 8: {
                // FI, OD o ELSE suelto antes de END → 213
                String w = new String[]{"FI", "OD", "ELSE"}[r.nextInt(3)];
                m.clase = w + " suelto";
                LG n = Nueva(w);
                for (int i = 0; i < l.size(); i++) {
                    if (l.get(i).k.equals("END")) {
                        l.add(i, n);
                        break;
                    }
                }
                m.esperados.add(new Object[]{n, E213(w)});
                return m;
            }
            case 9: {
                // Anidar un IF en un IF (214) o un FOR en un FOR (215), como primer comando del cuerpo
                m.clase = "anidar";
                for (int i = 0; i < l.size(); i++) if (l.get(i).cab != null) cand.add(i);
                if (cand.isEmpty()) return null;
                int i = cand.get(r.nextInt(cand.size()));
                boolean esIf = l.get(i).cab.equals("THEN");
                LG n = Nueva(esIf ? "IF ( 1 > 0 ) THEN" : "FOR n FROM 1 BY 1 TO 2 DO");
                l.add(i + 1, n);
                l.add(i + 2, Nueva("SKIP"));
                l.add(i + 3, Nueva(esIf ? "FI;" : "OD;"));
                m.esperados.add(new Object[]{n, esIf ? E214() : E215()});
                return m;
            }
            case 10: {
                // Vaciar un bloque → 216 con su cierre
                m.clase = "vaciar un bloque";
                List<Integer> bloques = new ArrayList<>();
                for (LG x : l) if (x.bloque >= 0 && !bloques.contains(x.bloque)) bloques.add(x.bloque);
                if (bloques.isEmpty()) return null;
                int b = bloques.get(r.nextInt(bloques.size()));
                l.removeIf(x -> x.bloque == b);
                for (LG x : l) if (x.cierra == b) m.esperados.add(new Object[]{x, E216(x.k)});
                return m;
            }
            case 11: {
                // Quitar todos los comandos → 217 (y 206 bajo la última declaración, DS8)
                m.clase = "sin comandos";
                l.removeIf(x -> !(x.k.equals("BEGIN") || x.k.equals("END") || "decl".equals(x.grupo)
                        || (x.k.equals("T") && x.bloque < 0)));
                LG ultimaDecl = null;
                for (LG x : l) if ("decl".equals(x.cmd)) ultimaDecl = x;
                if (ultimaDecl != null) m.esperados.add(new Object[]{ultimaDecl, E206("END")});
                for (LG x : l) if (x.k.equals("END")) m.esperados.add(new Object[]{x, E217()});
                return m;
            }
            case 12: {
                // Romper read, print o GOTO → un solo 204
                m.clase = "romper read/print/GOTO";
                for (int i = 0; i < l.size(); i++) {
                    LG x = l.get(i);
                    if (x.inicio && x.cmd != null && Arrays.asList("read", "print_nl", "print_txt", "print_list", "goto").contains(x.cmd)) cand.add(i);
                }
                if (cand.isEmpty()) return null;
                LG x = l.get(cand.get(r.nextInt(cand.size())));
                String fin = x.pyc ? ";" : "";
                String base;
                String esperado;
                int v = r.nextInt(3);
                switch (x.cmd) {
                    case "read":
                        if (v == 0) { base = "read " + x.arg; esperado = E204("LPAREN", "IDENT", x.arg); }
                        else if (v == 1) { base = "read ( " + x.arg + ", " + x.arg + " )"; esperado = E204("RPAREN", "COMMA", ","); }
                        else { base = "read ( )"; esperado = E204("IDENT", "RPAREN", ")"); }
                        break;
                    case "print_nl":
                        base = "print newline";
                        esperado = E204("LPAREN", "NEWLINE", "newline");
                        break;
                    case "print_txt":
                        base = "print \"" + x.arg + "\"";
                        esperado = E204("LPAREN", "STRING", x.arg);
                        break;
                    case "print_list":
                        if (v == 0) { base = "print ( ( ) )"; esperado = E204("IDENT", "RPAREN", ")"); }
                        else { base = "print ( " + x.arg + " )"; esperado = E204("LPAREN", "IDENT", x.arg); }
                        break;
                    default:
                        if (v == 0) {
                            base = "GOTO";
                            esperado = x.pyc ? E204("IDENT", "SEMICOLON", ";") : E204("IDENT", "EOL", "EOL");
                        } else {
                            base = "GOTO " + x.arg + " extra";
                            esperado = E204("SEMICOLON", "IDENT", "extra");
                        }
                }
                x.t = base + fin;
                m.esperados.add(new Object[]{x, esperado});
                return m;
            }
            default:
                throw new IllegalStateException(String.valueOf(clase));
        }
    }

    private static final int CLASES_MUTACION = 13;

    private static Mutado Mutar(int semilla) {
        ProgV p = GenerarValido(semilla);
        Random r = Mezclar(semilla * 104729L + 77);
        int inicio = r.nextInt(CLASES_MUTACION);
        for (int k = 0; k < CLASES_MUTACION; k++) {
            Mutado m = Mutacion((inicio + k) % CLASES_MUTACION, p, r);
            if (m != null) return m;
        }
        throw new IllegalStateException("ninguna mutación aplicable a la semilla " + semilla);
    }

    // Mensajes de una categoría como "línea|mensaje"
    private static List<String> DeCategoria(List<String[]> ms, String... categorias) {
        List<String> r = new ArrayList<>();
        List<String> cs = Arrays.asList(categorias);
        for (String[] m : ms) if (m.length == 6 && cs.contains(m[1])) r.add(m[0] + "|" + m[5]);
        return r;
    }

    private static List<String> Todos(List<String[]> ms) {
        List<String> r = new ArrayList<>();
        for (String[] m : ms) if (m.length == 6) r.add(m[0] + "|" + m[5]);
        return r;
    }

    // Una línea de generador de más de 80 columnas daría un 113 ajeno a la propiedad
    private static void Columnas(List<String> lineas, List<String> dif) {
        for (int i = 0; i < lineas.size(); i++) {
            if (lineas.get(i).length() > 80) dif.add("generador: la línea " + (i + 1) + " tiene más de 80 columnas");
        }
    }

    private static int fallasProp = 0;

    private static void RegistrarProp(String familia, int semilla, String detalle, List<String> lineas, List<String> dif,
                                      Map<String, int[]> cuentas) {
        int[] c = cuentas.computeIfAbsent(familia, k -> new int[2]);
        c[0]++;
        if (dif.isEmpty()) return;
        c[1]++;
        fallasProp++;
        System.out.println("FALLA [" + familia + "] semilla " + semilla + (detalle == null ? "" : " (" + detalle + ")"));
        for (String d : dif) System.out.println("      - " + d);
        System.out.println("      fuente:");
        for (int i = 0; i < lineas.size(); i++) System.out.println(String.format("      %05d %s", i + 1, lineas.get(i)));
    }

    private static void Propiedades() throws Exception {
        Path base = CarpetaSegura();
        Path trabajo = base.resolve("propiedades");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        Map<String, int[]> cuentas = new LinkedHashMap<>();
        String[] claves = {"Declaracion", "LetStatement", "Asignacion", "Secuencia", "If", "For", "Print", "Read", "Goto", "Etiqueta", "Skip"};

        // Fuentes de las tres familias; validez (TestProyecto) en las primeras 50 de cada una
        Map<String, ProgV> validos = new LinkedHashMap<>();
        Map<String, Mutado> mutados = new LinkedHashMap<>();
        Map<String, Programa> preservados = new LinkedHashMap<>();
        Map<String, Integer> semillaDe = new HashMap<>();
        Map<String, List<String>> lineasDe = new HashMap<>();
        List<String> archivos = new ArrayList<>();
        Set<String> conTP = new HashSet<>();
        for (int s = 1; s <= SEMILLAS_PROPIEDADES; s++) {
            String av = String.format("v%03d.mingol", s), am = String.format("m%03d.mingol", s), ap = String.format("p%03d.mingol", s);
            ProgV v = GenerarValido(s);
            Mutado m = Mutar(s);
            Programa p = GenerarPreservacion(s);
            validos.put(av, v);
            mutados.put(am, m);
            preservados.put(ap, p);
            List<String> lm = new ArrayList<>();
            for (LG x : m.l) lm.add(x.t);
            lineasDe.put(av, v.Lineas());
            lineasDe.put(am, lm);
            lineasDe.put(ap, p.lineas);
            for (String a : new String[]{av, am, ap}) {
                semillaDe.put(a, s);
                Files.write(trabajo.resolve(a), Bytes(lineasDe.get(a)));
                archivos.add(a);
                if (s <= SEMILLAS_VALIDEZ) conTP.add(a);
            }
        }
        Map<String, String> problemas = new HashMap<>();
        Map<String, Dump> dumps = CorrerLotes(trabajo, archivos, conTP, "p", problemas);

        for (String a : archivos) {
            List<String> dif = new ArrayList<>();
            if (problemas.containsKey(a)) dif.add(problemas.get(a));
            Dump d = dumps.get(a);
            String familia;
            String detalle = null;
            // El generador de preservación produce líneas de más de 80 columnas a propósito (113 en su oráculo)
            if (!a.startsWith("p")) Columnas(lineasDe.get(a), dif);
            if (d != null) {
                ComprobarComun(d, dif);
                if (d.tp != null) ComprobarValidez(d, trabajo, a, dif);
            }
            if (a.startsWith("v")) {
                // Programas válidos: ningún 1xx/2xx, solo las advertencias esperadas, nada del evaluador,
                // AST con la cantidad esperada de nodos por tipo y TestProyecto válido
                familia = "validos";
                ProgV v = validos.get(a);
                if (d != null) {
                    List<String> lexSint = DeCategoria(d.m, "LEXICO", "SINTACTICO");
                    if (!lexSint.isEmpty()) dif.add("mensajes 1xx/2xx en un programa válido: " + lexSint);
                    List<String> advEsp = new ArrayList<>();
                    for (int i = 0; i < v.l.size(); i++) if (v.l.get(i).adv != null) advEsp.add((i + 1) + "|" + Adv(v.l.get(i).adv));
                    CompararMulticonjunto("advertencias", advEsp, DeCategoria(d.m, "ADVERTENCIA"), dif);
                    if (!d.v.isEmpty()) dif.add("mensajes del evaluador en un programa válido: " + Todos(d.v));
                    if (!"false".equals(d.h)) dif.add("hasErrors=" + d.h);
                    Map<String, String> cuentaObt = new HashMap<>();
                    List<String> datosObt = new ArrayList<>();
                    for (String x : d.ast) {
                        if (x.startsWith("cuenta.")) cuentaObt.put(x.substring(7, x.indexOf('=')), x.substring(x.indexOf('=') + 1));
                        else if (x.startsWith("If.") || x.startsWith("For.")) datosObt.add(x);
                    }
                    for (String k : claves) {
                        String esp = String.valueOf(v.cuenta.getOrDefault(k, 0));
                        String obt = cuentaObt.getOrDefault(k, "0");
                        if (!esp.equals(obt)) dif.add("AST: " + k + " esperado " + esp + ", obtenido " + obt);
                    }
                    CompararMulticonjunto("AST (bloques)", v.datos, datosObt, dif);
                    if (d.tp != null && !"true".equals(d.tp)) dif.add("TestProyecto=" + d.tp + " en un programa válido");
                }
            } else if (a.startsWith("m")) {
                // Una sola falta: exactamente el 2xx esperado (código, línea, lexema), ningún otro 2xx ni 1xx
                familia = "mutacion";
                Mutado m = mutados.get(a);
                detalle = m.clase;
                if (d != null) {
                    CompararMulticonjunto("2xx", m.Esperados(), DeCategoria(d.m, "SINTACTICO"), dif);
                    List<String> lex = DeCategoria(d.m, "LEXICO");
                    if (!lex.isEmpty()) dif.add("1xx inesperados: " + lex);
                    if (d.tp != null && !"false".equals(d.tp)) dif.add("TestProyecto=" + d.tp + " con un error de sintaxis");
                }
            } else {
                // Dominio de preservación contra el oráculo de la tarea 2 (Property 2 en CI)
                familia = "preservacion";
                Programa p = preservados.get(a);
                if (d != null) {
                    CompararMulticonjunto("lexer/parser", p.parser, Todos(d.m), dif);
                    CompararMulticonjunto("evaluador", p.evaluador, Todos(d.v), dif);
                    if (!String.valueOf(p.HasErrors()).equals(d.h)) dif.add("oráculo: hasErrors esperado " + p.HasErrors() + ", obtenido " + d.h);
                    if (d.tp != null && !String.valueOf(p.Valido()).equals(d.tp)) dif.add("oráculo: TestProyecto esperado " + p.Valido() + ", obtenido " + d.tp);
                }
            }
            RegistrarProp(familia, semillaDe.get(a), detalle, lineasDe.get(a), dif, cuentas);
        }

        // Clases de mutación cubiertas
        Map<String, Integer> clases = new TreeMap<>();
        for (Mutado m : mutados.values()) clases.merge(m.clase, 1, Integer::sum);
        System.out.println("Clases de mutación: " + clases);
        System.out.println();
        System.out.println("===== RESUMEN propiedades =====");
        int total = 0;
        for (Map.Entry<String, int[]> e : cuentas.entrySet()) {
            total += e.getValue()[0];
            System.out.println("Familia " + e.getKey() + ": " + e.getValue()[0] + " semillas, FALLA: " + e.getValue()[1]
                    + ", OK: " + (e.getValue()[0] - e.getValue()[1]));
        }
        System.out.println("Validez con TestProyecto: " + conTP.size() + " fuentes (" + SEMILLAS_VALIDEZ + " por familia)");
        System.out.println("Total: " + total + ", FALLA: " + fallasProp + ", OK: " + (total - fallasProp));
        System.exit(fallasProp == 0 ? 0 : 1);
    }

    // Elementos de una Secuencia como "Clase:literal,..." (por reflexión)
    private static String Elementos(Object secuencia) throws IllegalAccessException {
        Object el = CampoPorNombre(secuencia, "elementos");
        List<String> r = new ArrayList<>();
        if (el instanceof Collection) {
            for (Object e : (Collection<?>) el) {
                r.add(e.getClass().getSimpleName() + ":" + (e instanceof ASTNode ? ((ASTNode) e).TokenLiteral() : "?"));
            }
        }
        return String.join(",", r);
    }

    // ------------------------------------------------------------------
    // Hijo
    // ------------------------------------------------------------------

    // TestProyecto por reflexión (sin a68g) y, aparte, un resumen del AST del mismo fuente
    private static void HijoTP(String archivo) throws Exception {
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
        // Resumen del AST: conteo de nodos por clase y datos de For, If y Secuencia
        try {
            Program prog = new Parser(new Lexer(LeerFuente(archivo))).ParseProgram();
            Map<String, Integer> conteo = new TreeMap<>();
            List<String> datos = new ArrayList<>();
            Recorrer(prog, conteo, datos, new IdentityHashMap<>(), 0);
            for (Map.Entry<String, Integer> e : conteo.entrySet()) out.println("AST|cuenta." + e.getKey() + "=" + e.getValue());
            for (String d : datos) out.println("AST|" + d);
        } catch (Throwable t) {
            codigo = 3;
            out.println("EXCEPCION|AST|" + t.getClass().getName() + "|" + Escapar(String.valueOf(t.getMessage())));
        }
        out.flush();
        System.exit(codigo);
    }

    // Recorre por reflexión los nodos del paquete Parser (también los que todavía no existen)
    private static void Recorrer(Object o, Map<String, Integer> conteo, List<String> datos,
                                 IdentityHashMap<Object, Boolean> vistos, int prof) throws IllegalAccessException {
        if (o == null || prof > 200 || vistos.containsKey(o)) return;
        if (o instanceof Collection) {
            vistos.put(o, true);
            for (Object x : (Collection<?>) o) Recorrer(x, conteo, datos, vistos, prof + 1);
            return;
        }
        Class<?> clase = o.getClass();
        if (clase.getPackage() == null || !"Parser".equals(clase.getPackage().getName())) return;
        vistos.put(o, true);
        conteo.merge(clase.getSimpleName(), 1, Integer::sum);
        if (o instanceof For) {
            Block b = ((For) o).getComands();
            datos.add("For.comandos=" + (b == null || b.getStatements() == null ? -1 : b.getStatements().size()));
        }
        if (o instanceof If) {
            Block cons = ((If) o).getConsequence();
            Block alt = ((If) o).getAlternative();
            datos.add("If.consecuencia=" + (cons == null || cons.getStatements() == null ? -1 : cons.getStatements().size()));
            datos.add("If.alternativa=" + (alt == null || alt.getStatements() == null ? -1 : alt.getStatements().size()));
        }
        if (clase.getSimpleName().equals("Secuencia")) {
            // palabrasAlgol: lista de Token (campo del nodo nuevo, por reflexión)
            Object pa = CampoPorNombre(o, "palabrasAlgol");
            if (pa instanceof Collection) {
                for (Object t : (Collection<?>) pa) if (t instanceof Token) datos.add("Secuencia.algol=" + ((Token) t).getLiteral());
            }
            // Tarea 3.9: elementos de la secuencia (clase:literal)
            datos.add("Secuencia.elementos=" + Elementos(o));
        }
        // Tarea 3.9: tipo, identificadores y si alguno tiene valor (DS2: nunca)
        if (clase.getSimpleName().equals("Declaracion")) {
            Object vars = CampoPorNombre(o, "variables");
            List<String> nombres = new ArrayList<>();
            boolean conValor = false;
            if (vars instanceof Collection) {
                for (Object v : (Collection<?>) vars) {
                    Object nombre = CampoPorNombre(v, "name");
                    if (nombre instanceof Identifier) nombres.add(((Identifier) nombre).getValue());
                    if (CampoPorNombre(v, "value") != null) conValor = true;
                }
            }
            datos.add("Declaracion=" + ((ASTNode) o).TokenLiteral() + ":" + String.join(",", nombres) + ":"
                    + (conValor ? "convalor" : "sinvalor"));
        }
        // Tarea 3.9: variable y elementos de la expresión de una asignación
        if (clase.getSimpleName().equals("Asignacion")) {
            Object var = CampoPorNombre(o, "variable");
            Object exp = CampoPorNombre(o, "expresion");
            datos.add("Asignacion=" + (var instanceof Identifier ? ((Identifier) var).getValue() : "?") + ":"
                    + (exp == null ? "null" : Elementos(exp)));
        }
        for (Class<?> k = clase; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Recorrer(f.get(o), conteo, datos, vistos, prof + 1);
            }
        }
    }

    // Valor de un campo por nombre (en la clase o sus superclases); null si no existe
    private static Object CampoPorNombre(Object o, String nombre) throws IllegalAccessException {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals(nombre)) {
                    f.setAccessible(true);
                    return f.get(o);
                }
            }
        }
        return null;
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
    // Lanzamiento de hijos (R8), copiado y adaptado de PruebasLexico
    // ------------------------------------------------------------------

    private static class ResultadoHijo {
        boolean termino;
        int codigo = -1;
        String stdout = "";
        String stderr = "";
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
        comando.add("PruebasSintaxis");
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
        for (String exc : LineasConPrefijo(r.stdout, "EXCEPCION|")) dif.add("EXCEPCION|" + exc);
        String err = PrimeraLineaCon(r.stderr, "Exception");
        if (err == null) err = PrimeraLineaCon(r.stderr, "Error");
        if (err != null) dif.add("stderr: " + err.trim());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    // %TEMP% (o java.io.tmpdir), con la ruta real para no depender de los nombres cortos 8.3
    private static Path CarpetaTemp() throws IOException {
        String temp = System.getenv("TEMP");
        Path base = (temp != null && !temp.isBlank()) ? Paths.get(temp) : Paths.get(System.getProperty("java.io.tmpdir"));
        return base.toRealPath();
    }

    // Carpeta actual con guardias: nunca el repositorio ni el diagnóstico, y ninguna copia de
    // proyecto.mingol fuera de la subcarpeta proyecto (R5)
    private static Path CarpetaSegura() throws IOException {
        Path carpeta = Paths.get("").toAbsolutePath().toRealPath();
        System.out.println("Carpeta de trabajo base: " + carpeta);
        if (Files.exists(carpeta.resolve("build.xml")) || carpeta.equals(CarpetaTemp().resolve("mingol-sintaxis"))) {
            System.err.println("Se evita ejecutar en el repositorio o en el diagnóstico: usar $env:TEMP\\" + CARPETA_TRABAJO);
            System.exit(2);
        }
        if (!carpeta.startsWith(CarpetaTemp())) {
            System.err.println("La carpeta de trabajo debe estar dentro de %TEMP% (usar $env:TEMP\\" + CARPETA_TRABAJO + ")");
            System.exit(2);
        }
        Path permitida = carpeta.resolve(SUBCARPETA_PROYECTO);
        List<Path> copias = new ArrayList<>();
        try (Stream<Path> s = Files.walk(carpeta)) {
            s.filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("proyecto.mingol"))
                    .filter(p -> !permitida.equals(p.getParent()))
                    .forEach(copias::add);
        }
        if (!copias.isEmpty()) {
            System.err.println("Hay copias de proyecto.mingol fuera de la subcarpeta " + SUBCARPETA_PROYECTO + ": " + copias);
            System.exit(2);
        }
        return carpeta;
    }

    // Raíz del repositorio: la carpeta que contiene build\classes en el classpath
    private static Path RaizRepositorio() throws IOException {
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Paths.get(e).toAbsolutePath().normalize();
            if (p.getNameCount() >= 2 && p.getFileName().toString().equals("classes")
                    && p.getParent().getFileName().toString().equals("build")) {
                Path raiz = p.getParent().getParent();
                if (Files.exists(raiz.resolve("proyecto.mingol"))) return raiz;
            }
        }
        throw new IOException("No se encontró la raíz del repositorio (build\\classes) en el classpath");
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

    // Primera línea distinta entre el archivo esperado y el obtenido
    private static String PrimeraDiferencia(String esperado, String obtenido) {
        String[] a = esperado.split("\n", -1);
        String[] b = obtenido.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "(fin)";
            String y = i < b.length ? b[i] : "(fin)";
            if (!x.equals(y)) return "línea " + (i + 1) + " del archivo: esperado [" + x + "], obtenido [" + y + "]";
        }
        return "(sin diferencia visible)";
    }

    // Mensajes de un -errores.txt como "línea:mensaje" (fin = bloque final)
    private static List<String> Mensajes(String archivo) {
        List<String> r = new ArrayList<>();
        String linea = "0";
        for (String l : archivo.split("\n", -1)) {
            if (l.length() >= 5 && l.substring(0, 5).matches("\\d{5}")) linea = String.valueOf(Integer.parseInt(l.substring(0, 5)));
            else if (l.startsWith(S8)) r.add(linea + ":" + l.substring(S8.length()));
        }
        return r;
    }

    private static List<String> L(String... s) {
        return new ArrayList<>(Arrays.asList(s));
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
        String etiqueta = "[" + grupo + (control ? ", control" : "") + "] ";
        fallo.put(caso, !diferencias.isEmpty());
        if (control) controles++;
        else casosC++;
        if (diferencias.isEmpty()) {
            System.out.println("OK    " + etiqueta + caso);
            resumen.add("OK    " + caso);
            // Un caso de C que pasa: el bug no se reproduce
            if (!control) noReproducidos.add(caso);
        } else {
            fallas++;
            if (control) controlesQueFallan.add(caso);
            System.out.println("FALLA " + etiqueta + caso);
            for (String d : diferencias) System.out.println("      - " + d);
            resumen.add("FALLA " + caso + ": " + diferencias.get(0));
        }
    }

    // Resumen que separa controles y casos de C
    private static void ImprimirResumen(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) if (r.startsWith("FALLA")) System.out.println(r);
        System.out.println("Controles: " + controles + ", OK: " + (controles - controlesQueFallan.size())
                + ", FALLA: " + controlesQueFallan.size() + (controlesQueFallan.isEmpty() ? "" : " " + controlesQueFallan));
        int fallanC = fallas - controlesQueFallan.size();
        System.out.println("Casos de C: " + casosC + ", FALLA: " + fallanC + ", OK: " + noReproducidos.size()
                + (noReproducidos.isEmpty() ? "" : " (bug no reproducido: " + noReproducidos + ")"));
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }
}
