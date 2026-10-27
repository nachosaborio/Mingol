// Arnés de la spec mingol-semantica-fix (área 4: analizador semántico).
// Clase sin paquete, como los otros arneses. Se compila contra build\classes y se ejecuta desde
// $env:TEMP\mingol-semantica-arnes: todos los archivos se crean ahí (nunca en el repositorio, R5).
//
// Modos:
//   exploracion       -> tarea 1: Property 1 (Bug Condition) sobre m01–m30, x01–x08 y una copia de
//                        proyecto.mingol. Compara el -errores.txt completo con la columna "Esperado tras
//                        el arreglo" de Examples del diseño. Se espera que FALLE en el código sin arreglar
//                        (674583b), salvo los controles; m30 se informa aparte.
//   hijotp <archivo>  -> interno: TestProyecto por reflexión (nunca main).
//   capturar [--sobrescribir] -> tarea 2: captura en %TEMP%\mingol-semantica-golden (sobre 674583b, antes
//                        de tocar src/) de controles, semillas 1..200 del dominio de preservación y fijos.
//   preservacion      -> tarea 2: Property 5, todo igual a la captura y al oráculo de preservación.
//   hijocap <archivo> -> interno: parser, evaluador y TestProyecto de un fuente para la captura.
//   unitarias         -> tarea 3.8: casos de 2.1–2.12 y bordes con su -errores.txt completo, Environment y
//                        borrados M1/M2/M14 por reflexión, Evaluate fuera de un Program y la meta proyecto.
//   propiedades       -> tarea 3.8: 300 semillas por familia (válidos, 12 mutaciones, dominio de preservación
//                        contra el oráculo de la tarea 2) y validez con TestProyecto en las primeras 50.
//                        No depende de la captura.
//   hijolote <lista>  -> interno: analiza un lote de fuentes en un hijo (R8) y deja un .dump por fuente.
//
// Solo usa API presente antes y después del arreglo.
// Seguridad (R8): TestProyecto corre en un hijo con la misma java.exe, --enable-preview, -Xmx256m,
// el mismo classpath (y -ea si el padre lo usa), waitFor(10 s) y destroyForcibly.
import Errores.Mensaje;
import Evaluador.Entero;
import Evaluador.Environment;
import Evaluador.Errado;
import Evaluador.Evaluator;
import Evaluador.Objeto;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Etiqueta;
import Parser.Identifier;
import Parser.Parser;
import Parser.Program;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class PruebasSemantica {

    // Límite de tiempo por hijo (R8)
    private static final long LIMITE_SEGUNDOS = 10;

    // Sangría exacta de los mensajes en el -errores.txt (área 1)
    private static final String S8 = "        ";

    // Carpeta de trabajo, subcarpeta de la copia de proyecto.mingol y carpetas que no se tocan
    private static final String CARPETA_TRABAJO = "mingol-semantica-arnes";
    private static final String SUBCARPETA_PROYECTO = "proyecto";
    private static final String CARPETA_DIAG = "mingol-semantica-diag";
    private static final String CARPETA_DIS = "mingol-semantica-dis";

    // Resultados del modo actual
    private static final List<String> resumen = new ArrayList<>();
    private static final List<String> controlesQueFallan = new ArrayList<>();
    private static final List<String> noReproducidos = new ArrayList<>();
    private static final Map<String, Boolean> fallo = new LinkedHashMap<>();
    private static int fallas = 0;
    private static int controles = 0;
    private static int casosC = 0;
    // m30: en C pero pasa por casualidad; se informa aparte (no cuenta como falla ni como C)
    private static String resultadoM30 = "(sin correr)";

    // ------------------------------------------------------------------
    // Despacho por modo
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: PruebasSemantica exploracion | capturar [--sobrescribir] | preservacion | unitarias"
                    + " | propiedades | hijotp <archivo> | hijocap <archivo> | hijolote <lista>");
            System.exit(2);
        }
        switch (args[0]) {
            // Tarea 1: exploración y su hijo
            case "exploracion":
                Exploracion();
                break;
            case "hijotp":
                HijoTP(args[1]);
                break;
            // Tarea 2: captura y preservación, y su hijo
            case "capturar":
                Capturar(args.length > 1 && args[1].equals("--sobrescribir"));
                break;
            case "preservacion":
                Preservacion();
                break;
            case "hijocap":
                HijoCap(args[1]);
                break;
            // Tarea 3.8: unitarias, propiedades y el hijo de lotes
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
    // Mensajes esperados (tabla final de códigos 3xx del diseño)
    // ------------------------------------------------------------------

    // "ERROR NNN: texto [lexema]."
    private static String Err(int codigo, String texto, String lexema) {
        return String.format("ERROR %03d: %s [%s].", codigo, texto, lexema);
    }

    // Advertencia de REQ-ALGOL-01
    private static String Adv(String lexema) {
        return "Advertencia: instrucción no es soportada por esta versión [" + lexema + "].";
    }

    private static String E111(String lex) { return Err(111, "Texto de más de 30 caracteres", lex); }
    private static String E209(String lex) { return Err(209, "Declaración después del primer comando", lex); }
    private static String E210() { return Err(210, "Declaración con valor inicial", ":="); }
    private static String E301(String lex) { return Err(301, "Discrepancia de tipos", lex); }
    private static String E303(String lex) { return Err(303, "Identificador no encontrado", lex); }
    private static String E304(String lex) { return Err(304, "Identificador ya declarado", lex); }
    private static String E309(String lex) { return Err(309, "Un CHAR es un solo carácter", lex); }
    private static String E310(String lex) { return Err(310, "La variable de ciclo del FOR no es INT", lex); }
    private static String E311(String lex) { return Err(311, "Valor de FROM/BY/TO no es constante entera ni variable INT", lex); }

    // ------------------------------------------------------------------
    // Casos de la exploración
    // ------------------------------------------------------------------

    // Un caso: fuente y mensajes esperados bajo cada línea
    private static class Caso {
        String id;
        String grupo;
        boolean control;                                   // fuera de C: debe pasar ya en 674583b
        boolean casualidad;                                // m30: en C, pero pasa ya
        boolean diagnostico;                               // fuente de %TEMP%\mingol-semantica-diag
        String[] lineas;
        final TreeMap<Integer, List<String>> bajo = new TreeMap<>();

        // Mensajes esperados bajo la línea n, en el orden del archivo
        Caso m(int n, String... mensajes) {
            bajo.computeIfAbsent(n, k -> new ArrayList<>()).addAll(Arrays.asList(mensajes));
            return this;
        }

        Caso control() {
            control = true;
            return this;
        }

        Caso casualidad() {
            casualidad = true;
            return this;
        }

        // -errores.txt esperado completo
        String archivoEsperado() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lineas.length; i++) {
                sb.append(String.format("%05d", i)).append(' ').append(lineas[i - 1]).append('\n');
                for (String msj : bajo.getOrDefault(i, Collections.emptyList())) sb.append(S8).append(msj).append('\n');
            }
            return sb.toString();
        }

        // Válido = ningún ERROR (las advertencias se permiten)
        boolean valido() {
            for (List<String> l : bajo.values()) for (String s : l) if (s.startsWith("ERROR")) return false;
            return true;
        }
    }

    // Caso del diagnóstico (m01–m30): las líneas van entre BEGIN y END
    private static Caso M(String grupo, String id, String... cuerpo) {
        Caso c = Nuevo(grupo, id, cuerpo);
        c.diagnostico = true;
        return c;
    }

    // Caso extra del diseño (x01–x08)
    private static Caso X(String grupo, String id, String... cuerpo) {
        return Nuevo(grupo, id, cuerpo);
    }

    private static Caso Nuevo(String grupo, String id, String... cuerpo) {
        Caso c = new Caso();
        c.grupo = grupo;
        c.id = id;
        String[] l = new String[cuerpo.length + 2];
        l[0] = "BEGIN";
        System.arraycopy(cuerpo, 0, l, 1, cuerpo.length);
        l[l.length - 1] = "END";
        c.lineas = l;
        return c;
    }

    // Grupos = Test Cases 1–5 del diseño (6 = proyecto); "ctl" = controles fuera de C.
    // Fuentes de m01–m30 reconstruidos desde la tabla de bugfix.md (%TEMP%\mingol-semantica-diag no existe
    // en este equipo); coinciden con los números de línea de la tabla.
    private static List<Caso> Casos() {
        List<Caso> c = new ArrayList<>();
        // 1. GOTO y etiquetas
        c.add(M("1", "m01", "GOTO fin;", "fin:", "SKIP"));
        c.add(M("ctl", "m02", "inicio:", "print ( newline );", "GOTO inicio").control());
        c.add(M("ctl", "m03", "GOTO nada").control().m(2, E303("nada")));
        c.add(M("1", "m04", "INT a;", "GOTO a").m(3, E303("a")));
        c.add(M("1", "m05", "INT i;", "FOR i FROM 1 BY 1 TO 3", "DO", "adentro:", "print ( newline )", "OD;", "GOTO adentro"));
        c.add(M("1", "m30", "INT i;", "FOR i FROM 1 BY 1 TO 3", "DO", "GOTO afuera", "OD;", "afuera:", "SKIP").casualidad());
        // 2 y 3. Asignación
        c.add(M("2", "m06", "a := 1").m(2, E303("a")));
        c.add(M("2", "m07", "INT a;", "a := b + 1").m(3, E303("b")));
        c.add(M("ctl", "m08", "INT a;", "a := max + ABS a").control());
        c.add(M("3", "m09", "INT a;", "CHAR c;", "a := c;", "c := a").m(4, E301("c")).m(5, E301("a")));
        c.add(M("ctl", "m10", "INT a;", "REAL r;", "r := a * 2;", "a := r / 2.5;", "a := 1.5;", "r := 3").control());
        c.add(M("3", "m11", "INT a;", "CHAR c;", "c := \"x\";", "c := 1;", "a := \"x\";", "a := TRUE;", "c := 1.5")
                .m(5, E301("1")).m(6, E301("x")).m(7, E301("TRUE")).m(8, E301("1.5")));
        c.add(M("3", "m12", "CHAR c;", "c := \"hola\";", "c := \"\";", "c := \"x\" ;", "c := c")
                .m(3, E309("hola")).m(4, E309("STRING")));
        c.add(M("2", "m13", "fin:", "fin := 1;", "print ( (fin) );", "read ( fin )")
                .m(3, E303("fin")).m(4, E303("fin")).m(5, E303("fin")));
        c.add(M("ctl", "m14", "INT a;", "IF ( zz > 0 ) THEN", "SKIP", "FI;", "IF ( a > max ) THEN", "SKIP", "FI;",
                "IF ( TRUE ) THEN", "print ( (uu) )", "ELSE", "print ( (vv) )", "FI").control()
                .m(3, E303("zz")).m(10, E303("uu")).m(12, E303("vv")));
        c.add(M("2", "m15", "INT a;", "IF ( a > 0 ) THEN", "qq := 1", "ELSE", "read ( ww )", "FI")
                .m(4, E303("qq")).m(6, E303("ww")));
        // 4. FOR
        c.add(M("4", "m16", "FOR i FROM 1 BY 1 TO 3", "DO", "SKIP", "OD").m(2, E303("i")));
        c.add(M("4", "m17", "REAL r;", "CHAR c;", "INT i;", "FOR r FROM 1 BY 1 TO 3", "DO", "SKIP", "OD;",
                "FOR i FROM zz BY r TO c", "DO", "SKIP", "OD")
                .m(5, E310("r")).m(9, E303("zz"), E311("r"), E311("c")));
        c.add(M("4", "m18", "INT i;", "FOR i FROM 1 BY 1 TO 3", "DO", "print ( (zz) );", "qq := 2;", "read ( ww )", "OD")
                .m(5, E303("zz")).m(6, E303("qq")).m(7, E303("ww")));
        c.add(M("4", "m19", "etq:", "FOR etq FROM 1 BY 1 TO etq", "DO", "SKIP", "OD").m(3, E303("etq"), E303("etq")));
        // 5. read
        c.add(M("5", "m20", "read ( zz )").m(2, E303("zz")));
        // Declaraciones y etiquetas repetidas
        c.add(M("ctl", "m21", "INT a;", "REAL a;", "CHAR b, b;", "INT c, d, c;", "SKIP").control()
                .m(3, E304("a")).m(4, E304("b")).m(5, E304("c")));
        c.add(M("ctl", "m22", "fin:", "print ( newline );", "fin:", "SKIP").control().m(4, E304("fin")));
        c.add(M("1", "m23", "INT a;", "a:", "print ( newline )"));
        c.add(M("2", "m24", "INT a;", "a := b + b;", "IF ( zz > zz ) THEN", "SKIP", "FI")
                .m(3, E303("b"), E303("b")).m(4, E303("zz"), E303("zz")));
        c.add(M("ctl", "m25", "INT a;", "print ( (a, zz) )").control().m(3, E303("zz")));
        c.add(M("2", "m26", "INT a;", "fin:", "IF ( fin > 0 ) THEN", "SKIP", "FI;", "a := fin + 1")
                .m(4, E303("fin")).m(7, E303("fin")));
        c.add(M("ctl", "m27", "INT a;", "REAL r;", "CHAR c;", "IF ( c = 1 ) THEN", "SKIP", "FI;",
                "IF ( a = \"x\" ) THEN", "SKIP", "FI;", "print ( (a, r, c) )").control());
        c.add(M("3", "m28", "INT a;", "CHAR c;", "a := -5;", "a := ( a * 2 ) + c").m(5, E301("c")));
        c.add(M("ctl", "m29", "INT a := 5;", "print ( (a) );", "INT z;", "print ( (z) )").control()
                .m(2, E210()).m(4, E209("INT")));
        // Extras del diseño (Examples)
        c.add(X("3", "x01", "CHAR c;", "c := \"hola\";", "c := \"\";", "c := \"x\"")
                .m(3, E309("hola")).m(4, E309("STRING")));
        c.add(X("4", "x02", "INT i, b;", "CHAR a, c;", "FOR i FROM a BY b TO c", "DO", "SKIP", "OD")
                .m(4, E311("a"), E311("c")));
        c.add(X("3", "x03", "INT a;", "CHAR c;", "a := c + \"x\" + TRUE").m(4, E301("c"), E301("x"), E301("TRUE")));
        c.add(X("1", "x04", "INT fin;", "fin:", "fin := 1;", "GOTO fin"));
        c.add(X("1", "x05", "INT i;", "GOTO adentro;", "FOR i FROM 1 BY 1 TO 2", "DO", "adentro:", "SKIP", "OD"));
        c.add(X("1", "x06", "INT i;", "fin:", "print ( newline );", "FOR i FROM 1 BY 1 TO 2", "DO", "fin:", "SKIP", "OD")
                .m(7, E304("fin")));
        c.add(X("3", "x07", "CHAR c;", "c := -5").m(3, E301("5")));
        c.add(X("3", "x08", "INT a;", "a := \"\"").m(3, E301("STRING")));
        return c;
    }

    // ------------------------------------------------------------------
    // Modo exploracion (tarea 1)
    // ------------------------------------------------------------------

    private static void Exploracion() throws Exception {
        Path base = CarpetaSegura();
        Path trabajo = base.resolve("exploracion");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        // Diagnóstico de la spec (solo lectura, para avisar si un fuente difiere)
        Path diag = CarpetaTemp().resolve(CARPETA_DIAG);
        if (!Files.isDirectory(diag)) {
            System.out.println("AVISO: no existe " + diag + "; m01–m30 se reconstruyeron desde la tabla de bugfix.md");
        }

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
            CompararArchivo(dir.resolve(c.id + "-errores.txt"), c.archivoEsperado(), dif);

            if (c.casualidad) {
                // m30 se informa aparte
                // Tras el arreglo pasa por la razón correcta: el cuerpo del FOR se evalúa y la etiqueta
                // posterior ya está registrada por la pasada previa (en 674583b pasaba por casualidad)
                resultadoM30 = dif.isEmpty() ? "OK (cuerpo del FOR evaluado; etiqueta posterior registrada por la pasada previa)" : "FALLA: " + dif;
                System.out.println("INFO  [" + c.grupo + ", m30] " + c.id + ": " + resultadoM30);
                fallo.put(c.id, !dif.isEmpty());
                continue;
            }
            Registrar(c.id, c.grupo, c.control, dif);
        }

        Proyecto(base);
        Causas();
        ImprimirResumen("exploracion");
    }

    // Compara el -errores.txt obtenido con el esperado completo
    private static void CompararArchivo(Path errores, String esperado, List<String> dif) throws IOException {
        if (!Files.exists(errores)) {
            dif.add("no se generó " + errores.getFileName());
            return;
        }
        String obtenido = new String(Files.readAllBytes(errores), StandardCharsets.ISO_8859_1);
        if (!esperado.equals(obtenido)) {
            dif.add("-errores.txt: " + PrimeraDiferencia(esperado, obtenido));
            dif.add("mensajes esperados: " + Mensajes(esperado));
            dif.add("mensajes obtenidos: " + Mensajes(obtenido));
        }
    }

    // Copia de proyecto.mingol en la subcarpeta proyecto: 2 advertencias y 3 × 111, ningún 3xx (2.13)
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

        // Archivo esperado: copia numerada, advertencias bajo 2 y 3, 111 bajo 21, 49 y 59
        List<String> fuente = Files.readAllLines(dir.resolve("proyecto.mingol"), StandardCharsets.ISO_8859_1);
        Map<Integer, String> bajo = new TreeMap<>();
        bajo.put(2, Adv("PRAGMAT"));
        bajo.put(3, Adv("MODE"));
        for (int n : new int[] {21, 49, 59}) bajo.put(n, E111(TextoEntreComillas(fuente.get(n - 1))));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= fuente.size(); i++) {
            sb.append(String.format("%05d", i)).append(' ').append(fuente.get(i - 1)).append('\n');
            if (bajo.containsKey(i)) sb.append(S8).append(bajo.get(i)).append('\n');
        }

        // Validez: false por los 111
        String tp = LineaConPrefijo(r.stdout, "TestProyecto=");
        if (!"TestProyecto=false".equals(tp)) dif.add("esperado TestProyecto=false, obtenido " + tp);
        CompararArchivo(dir.resolve("proyecto-errores.txt"), sb.toString(), dif);
        Registrar("proyecto", "6", false, dif);
    }

    // Contenido del primer texto entre comillas de la línea
    private static String TextoEntreComillas(String linea) {
        int a = linea.indexOf('"');
        int b = linea.indexOf('"', a + 1);
        return (a < 0 || b < 0) ? "" : linea.substring(a + 1, b);
    }

    // Test Cases 1–6 del diseño: el grupo confirma sus causas si fallan todos sus casos
    private static void Causas() {
        System.out.println();
        System.out.println("===== CAUSAS DEL DISEÑO (Test Cases 1–6) =====");
        Grupo("1. GOTO y etiquetas", "causas 1, 2 y 5", "m01", "m04", "m05", "m23", "x04", "x05", "x06");
        Grupo("2. Asignación: definidas", "causa 3", "m06", "m07", "m13", "m15", "m24", "m26");
        Grupo("3. Asignación: tipos y CHAR", "causas 3 y 5", "m09", "m11", "m12", "m28", "x01", "x03", "x07", "x08");
        Grupo("4. FOR", "causa 2", "m16", "m17", "m18", "m19", "x02");
        Grupo("5. read y print", "causas 4 y 5", "m13", "m15", "m20");
        Grupo("6. proyecto.mingol", "causa 1", "proyecto");
        System.out.println("m30 (en C, informado aparte): " + resultadoM30);
    }

    private static void Grupo(String nombre, String causas, String... casos) {
        List<String> pasan = new ArrayList<>();
        for (String c : casos) if (!Boolean.TRUE.equals(fallo.get(c))) pasan.add(c);
        System.out.println(nombre + " (" + causas + "): " + (pasan.isEmpty()
                ? "CONFIRMADA (fallan " + Arrays.toString(casos) + ")"
                : "NO CONFIRMADA: pasan " + pasan));
    }

    // ------------------------------------------------------------------
    // Tarea 2: generador del dominio de preservación (isBugCondition falso) y su oráculo
    // ------------------------------------------------------------------

    private static final int SEMILLAS_PRESERVACION = 200;

    // Mensajes de áreas anteriores que predice el oráculo
    private static String E101(String lex) { return Err(101, "Carácter ilegal", lex); }
    private static String E103(String lex) { return Err(103, "Identificador con mayúsculas", lex); }
    private static String E104(String lex) { return Err(104, "Identificador de más de 20 caracteres", lex); }
    private static String E107(String lex) { return Err(107, "Comentario sin cerrar al final del archivo", lex); }
    private static String E113(int columnas) { return Err(113, "Línea de más de 80 columnas", String.valueOf(columnas)); }
    private static String E202(String despues, String lex) { return Err(202, "No puede haber nada en la línea después de " + despues, lex); }
    private static String E205(String lex) { return Err(205, "No es una instrucción válida de MINGOL ni de ALGOL", lex); }
    private static String E305() { return Err(305, "No puede haber más de una sentencia BEGIN por archivo", "BEGIN"); }
    private static String E306() { return Err(306, "No puede haber más de una sentencia END por archivo", "END"); }
    private static String E307() { return Err(307, "No se encontró una sentencia BEGIN", "BEGIN"); }
    private static String E308() { return Err(308, "No se encontró una sentencia END", "END"); }

    // Programa generado con los mensajes que predice el oráculo
    private static class Programa {
        final List<String> lineas = new ArrayList<>();
        final List<String> parser = new ArrayList<>();     // "línea|mensaje" del lexer y del parser
        final List<String> evaluador = new ArrayList<>();  // "línea|mensaje" del evaluador (0 = 307/308)

        // Agrega una línea física; el lexer da 113 a toda línea de más de 80 columnas (L3)
        int Linea(String texto) {
            lineas.add(texto);
            int n = lineas.size();
            if (texto.length() > 80) P(n, E113(texto.length()));
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

    // Nombres disjuntos: variables, etiquetas y no definidos nunca coinciden (sin M11 en este dominio)
    private static final String[] VARS = {"a", "b", "n", "total", "v1", "x_y", "suma", "letra"};
    private static final String[] TIPOS = {"INT", "REAL", "CHAR"};
    private static final String[] ETIQUETAS = {"e1", "e2", "fin", "inicio", "otra"};
    private static final String[] NO_DEFINIDOS = {"zz", "qq", "ww", "nada"};
    private static final String[] TEXTOS = {"hola", "texto corto", "Resultado: ", "a b c", "x"};
    // Palabras ALGOL dentro de una expresión numérica (M5: no se miran)
    private static final String[] ALGOL_EXPR = {"max", "ABS", "pi", "ROUND"};
    // Instrucciones ALGOL en posición de comando: {línea, lexema de la advertencia}
    private static final String[][] INSTRUCCIONES_ALGOL = {{"LONG x;", "LONG"}, {"PRAGMAT x PRAGMAT", "PRAGMAT"},
            {"max ( 1 )", "max"}, {"TRUE", "TRUE"}, {"FALSE", "FALSE"}, {"ABS x;", "ABS"}, {"sqrt ( 4 );", "sqrt"},
            {"SHORT y", "SHORT"}};

    // Comandos del dominio (Scope del diseño)
    private enum K { PRINT_NL, PRINT_TXT, PRINT_LARGO, PRINT_LISTA, READ, ASIG, GOTO, ETIQUETA, ALGOL, IF, FOR, SKIP, DECL_TARDIA }

    // Estado del generador: tipos de las variables declaradas (el de la primera declaración, 3.1),
    // etiquetas ya vistas en el nivel superior y si la sección de variables ya se cerró (S5)
    private static class Gen {
        Programa p = new Programa();
        Random r;
        boolean limpio;
        final Map<String, String> tipos = new HashMap<>();
        final List<String> declaradas = new ArrayList<>();   // en orden, para sortear de forma determinista
        final List<String> etiquetas = new ArrayList<>();
        boolean seccionCerrada = false;

        // Variable declarada al azar con uno de los tipos pedidos (null si no hay)
        String Var(String... tiposPedidos) {
            List<String> l = new ArrayList<>();
            for (String v : declaradas) if (Arrays.asList(tiposPedidos).contains(tipos.get(v))) l.add(v);
            return l.isEmpty() ? null : l.get(r.nextInt(l.size()));
        }

        // Declara (o da 304 si ya estaba); el tipo queda el de la primera
        void Declarar(int n, String tipo, String v) {
            if (tipos.containsKey(v)) {
                p.E(n, E304(v));
            } else {
                tipos.put(v, tipo);
                declaradas.add(v);
            }
        }
    }

    // Generador del dominio de preservación: declaraciones tipadas (también repetidas, 209, 210), print de
    // variables, no definidas, textos y newline; GOTO hacia atrás a etiquetas fuera de un FOR o a nombres que
    // no existen; etiquetas sueltas o repetidas en el nivel superior; IF con cualquier condición y ramas sin
    // elementos de C; asignaciones, read y FOR correctos; instrucciones ALGOL; BEGIN/END repetidos o
    // faltantes; y lo que solo da 1xx/2xx.
    private static Programa GenerarPreservacion(int semilla) {
        // Semilla mezclada (splitmix64), como en PruebasSintaxis
        long z = semilla * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        Gen g = new Gen();
        g.r = new Random(z ^ (z >>> 31));
        Random r = g.r;
        Programa p = g.p;
        // Una de cada cuatro semillas es "limpia": sin errores (solo advertencias)
        g.limpio = r.nextInt(4) == 0;
        int modoBegin = g.limpio ? 9 : r.nextInt(10);   // 0 falta (307), 1 "BEGIN;" (202), 2 repetido (305)
        int modoEnd = g.limpio ? 9 : r.nextInt(10);     // 0 falta (308), 1 "END;" (202), 2 repetido (306)

        for (int i = r.nextInt(3); i > 0; i--) Transparente(g, false);
        if (modoBegin != 0) {
            int n = p.Linea(modoBegin == 1 ? "BEGIN;" : "BEGIN");
            if (modoBegin == 1) p.P(n, E202("BEGIN", ";"));
        }
        if (modoBegin == 2) p.E(p.Linea("BEGIN"), E305());

        // Sección de variables: 0 a 4 declaraciones; entre ellas solo líneas sin 1xx (S5)
        for (int d = r.nextInt(5); d > 0; d--) {
            Declaracion(g);
            if (r.nextInt(4) == 0) Transparente(g, false);
        }

        // Sección de comandos: al menos un comando MINGOL; el último nunca es ALGOL ni declaración (DS1)
        int cantidad = 1 + r.nextInt(7);
        K[] medios = g.limpio
                ? new K[]{K.PRINT_NL, K.PRINT_TXT, K.PRINT_LISTA, K.READ, K.ASIG, K.ASIG, K.GOTO, K.ETIQUETA, K.ALGOL, K.IF, K.FOR, K.FOR}
                : new K[]{K.PRINT_NL, K.PRINT_TXT, K.PRINT_LARGO, K.PRINT_LISTA, K.READ, K.ASIG, K.ASIG, K.GOTO, K.ETIQUETA,
                        K.ALGOL, K.IF, K.FOR, K.FOR, K.DECL_TARDIA};
        K[] ultimos = {K.PRINT_NL, K.PRINT_TXT, K.PRINT_LISTA, K.READ, K.ASIG, K.GOTO, K.ETIQUETA, K.IF, K.FOR, K.SKIP, K.SKIP};
        for (int i = 0; i < cantidad; i++) {
            boolean ultimo = i == cantidad - 1;
            K tipo = ultimo ? ultimos[r.nextInt(ultimos.length)] : medios[r.nextInt(medios.length)];
            // El último antes de END no lleva ";" (REQ-LINE-04); sin END, sí
            Comando(g, tipo, ultimo && modoEnd != 0 ? "" : ";");
            if (!ultimo && r.nextInt(4) == 0) Transparente(g, true);
        }

        if (modoEnd != 0) {
            if (r.nextInt(4) == 0) Transparente(g, false);
            int n = p.Linea(modoEnd == 1 ? "END;" : "END");
            if (modoEnd == 1) p.P(n, E202("END", ";"));
            if (modoEnd == 2) {
                for (int i = r.nextInt(2); i > 0; i--) Transparente(g, false);
                p.E(p.Linea("END"), E306());
            }
        }
        for (int i = r.nextInt(3); i > 0; i--) Transparente(g, false);
        // Comentario sin cerrar al final: 107 bajo la línea de apertura
        if (!g.limpio && r.nextInt(8) == 0) {
            boolean co = r.nextBoolean();
            p.P(p.Linea(co ? "CO sin cerrar" : "# sin cerrar"), E107(co ? "CO" : "#"));
            p.Linea("SKIP;");
        }
        if (modoBegin == 0) p.E(0, E307());
        if (modoEnd == 0) p.E(0, E308());
        return p;
    }

    // Línea que la sintaxis no ve; con conLexicos (solo en la sección de comandos) también 113 o 101
    private static void Transparente(Gen g, boolean conLexicos) {
        Programa p = g.p;
        switch (g.r.nextInt(conLexicos && !g.limpio ? 9 : 6)) {
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
            default: p.P(p.Linea("%"), E101("%"));                    // solo un carácter ilegal
        }
    }

    // Declaración "TIPO v1, v2, ...;" en la sección de variables; a veces con valor inicial (210)
    private static void Declaracion(Gen g) {
        Random r = g.r;
        String tipo = TIPOS[r.nextInt(TIPOS.length)];
        if (!g.limpio && r.nextInt(6) == 0) {
            // 210: una sola variable nueva con valor; queda declarada con su tipo (DS2, 3.6)
            List<String> libres = new ArrayList<>();
            for (String v : VARS) if (!g.tipos.containsKey(v)) libres.add(v);
            if (!libres.isEmpty()) {
                String v = libres.get(r.nextInt(libres.size()));
                int n = g.p.Linea(tipo + " " + v + " := " + (1 + r.nextInt(50)) + ";");
                g.p.P(n, E210());
                g.Declarar(n, tipo, v);
                return;
            }
        }
        List<String> nombres = new ArrayList<>();
        for (int k = 1 + r.nextInt(3); k > 0; k--) {
            String v = VARS[r.nextInt(VARS.length)];
            // Limpia: sin repetidas
            if (g.limpio && (g.tipos.containsKey(v) || nombres.contains(v))) continue;
            nombres.add(v);
        }
        if (nombres.isEmpty()) {
            // Limpia sin nombres libres: una línea en blanco en su lugar
            g.p.Linea("");
            return;
        }
        int n = g.p.Linea(tipo + " " + String.join(", ", nombres) + ";");
        for (String v : nombres) g.Declarar(n, tipo, v);
    }

    // Nombre no definido (nunca variable ni etiqueta); en las sucias, a veces con 103 o 104
    private static String NoDefinido(Gen g) {
        int k = g.r.nextInt(10);
        if (k == 0) return "Abc";
        if (k == 1) return "abcdefghijklmnopqrstuvwxyz";
        return NO_DEFINIDOS[g.r.nextInt(NO_DEFINIDOS.length)];
    }

    // 1xx del lexer para un identificador fuera de la posición de comando
    private static void Lexicos(Programa p, int n, String id) {
        if (!id.equals(id.toLowerCase())) p.P(n, E103(id));
        if (id.length() > 20) p.P(n, E104(id));
    }

    // Un comando del nivel superior; fin = ";" o "" (antes de END)
    private static void Comando(Gen g, K tipo, String fin) {
        Programa p = g.p;
        Random r = g.r;
        switch (tipo) {
            case PRINT_LARGO: {
                String t = "y".repeat(31 + r.nextInt(9));
                p.P(p.Linea("print ( \"" + t + "\" )" + fin), E111(t));
                break;
            }
            case GOTO: {
                // Sucia: a veces a un nombre que no existe (303 antes y después, 3.4)
                if (!g.limpio && (g.etiquetas.isEmpty() || r.nextInt(2) == 0)) {
                    String e = NoDefinido(g);
                    int n = p.Linea("GOTO " + e + fin);
                    Lexicos(p, n, e);
                    p.E(n, E303(e));
                } else if (!g.etiquetas.isEmpty()) {
                    // Hacia atrás a una etiqueta del nivel superior ya vista
                    p.Linea("GOTO " + g.etiquetas.get(r.nextInt(g.etiquetas.size())) + fin);
                } else {
                    Comando(g, K.ETIQUETA, fin);
                    return;
                }
                break;
            }
            case ETIQUETA: {
                String e;
                if (g.limpio) {
                    List<String> libres = new ArrayList<>(Arrays.asList(ETIQUETAS));
                    libres.removeAll(g.etiquetas);
                    if (libres.isEmpty()) {
                        Comando(g, K.SKIP, fin);
                        return;
                    }
                    e = libres.get(r.nextInt(libres.size()));
                } else {
                    e = ETIQUETAS[r.nextInt(ETIQUETAS.length)];
                }
                // La etiqueta nunca lleva ";" (REQ-LABEL-01)
                int n = p.Linea(e + (r.nextInt(4) == 0 ? " :" : ":"));
                // Repetida fuera de un FOR: 304 bajo la segunda (antes y después)
                if (g.etiquetas.contains(e)) p.E(n, E304(e));
                else g.etiquetas.add(e);
                break;
            }
            case ALGOL: {
                // No cierra la sección de variables (S5)
                String[] a = INSTRUCCIONES_ALGOL[r.nextInt(INSTRUCCIONES_ALGOL.length)];
                p.P(p.Linea(a[0]), Adv(a[1]));
                return;
            }
            case DECL_TARDIA: {
                // 209 solo si ya se cerró la sección; si no, una declaración común
                if (!g.seccionCerrada) {
                    Declaracion(g);
                    return;
                }
                String t = TIPOS[r.nextInt(TIPOS.length)];
                String v = VARS[r.nextInt(VARS.length)];
                int n = p.Linea(t + " " + v + ";");
                p.P(n, E209(t));
                g.Declarar(n, t, v);
                return;
            }
            case IF:
                If(g, fin);
                break;
            case FOR:
                For(g, fin);
                break;
            default:
                Simple(g, tipo, fin, false);
        }
        g.seccionCerrada = true;
    }

    // Comando de una línea (también dentro de un IF o un FOR); enFor = sin nada que hoy no se mira
    private static void Simple(Gen g, K tipo, String fin, boolean enFor) {
        Programa p = g.p;
        Random r = g.r;
        g.seccionCerrada = true;
        switch (tipo) {
            case PRINT_NL:
                p.Linea("print ( newline )" + fin);
                return;
            case PRINT_TXT:
                p.Linea("print ( \"" + TEXTOS[r.nextInt(TEXTOS.length)] + "\" )" + fin);
                return;
            case SKIP:
                p.Linea("SKIP" + fin);
                return;
            case PRINT_LISTA: {
                // Variables declaradas y, fuera de un FOR y en las sucias, no definidas (un 303 por cada una)
                List<String> ids = new ArrayList<>();
                boolean conNoDefinidos = !enFor && !g.limpio;
                for (int k = 1 + r.nextInt(3); k > 0; k--) {
                    String v = g.Var("INT", "REAL", "CHAR");
                    if (v == null || (conNoDefinidos && r.nextInt(3) == 0)) v = conNoDefinidos ? NoDefinido(g) : null;
                    if (v != null) ids.add(v);
                }
                if (ids.isEmpty()) {
                    p.Linea("print ( newline )" + fin);
                    return;
                }
                int n = p.Linea("print ( (" + String.join(", ", ids) + ") )" + fin);
                for (String id : ids) {
                    if (g.tipos.containsKey(id)) continue;
                    Lexicos(p, n, id);
                    p.E(n, E303(id));
                }
                return;
            }
            case READ: {
                // Solo variables declaradas (cualquier tipo): fuera de C
                String v = g.Var("INT", "REAL", "CHAR");
                p.Linea(v == null ? "print ( newline )" + fin : "read ( " + v + " )" + fin);
                return;
            }
            case ASIG:
                Asignacion(g, fin);
                return;
            case GOTO: {
                // Dentro de un IF o un FOR: solo hacia atrás a una etiqueta ya vista; en un IF sucio, también a lo
                // que no existe (303 antes y después)
                if (!enFor && !g.limpio && (g.etiquetas.isEmpty() || r.nextInt(2) == 0)) {
                    String e = NoDefinido(g);
                    int n = p.Linea("GOTO " + e + fin);
                    Lexicos(p, n, e);
                    p.E(n, E303(e));
                } else if (!g.etiquetas.isEmpty()) {
                    p.Linea("GOTO " + g.etiquetas.get(r.nextInt(g.etiquetas.size())) + fin);
                } else {
                    p.Linea("SKIP" + fin);
                }
                return;
            }
            default:
                throw new IllegalStateException(String.valueOf(tipo));
        }
    }

    // Asignación correcta (REQ-CMD-02): izquierda declarada, operandos declarados y compatibles, textos de un
    // carácter hacia CHAR; con palabras ALGOL y "-" unario en las numéricas. Si no hay variables, newline.
    private static void Asignacion(Gen g, String fin) {
        Random r = g.r;
        String izq = g.Var("INT", "REAL", "CHAR");
        if (izq == null) {
            g.p.Linea("print ( newline )" + fin);
            return;
        }
        List<String> ops = new ArrayList<>();
        if (g.tipos.get(izq).equals("CHAR")) {
            for (int k = 1 + r.nextInt(2); k > 0; k--) {
                String v = g.Var("CHAR");
                ops.add(r.nextBoolean() ? v : "\"" + "xS".charAt(r.nextInt(2)) + "\"");
            }
            g.p.Linea(izq + " := " + String.join(" + ", ops) + fin);
            return;
        }
        String[] operadores = {" + ", " - ", " * ", " / "};
        StringBuilder sb = new StringBuilder(izq + " := ");
        for (int k = 1 + r.nextInt(3); k > 0; k--) {
            String op;
            switch (r.nextInt(5)) {
                case 0: op = g.Var("INT", "REAL"); break;
                case 1: op = String.valueOf(r.nextInt(100)); break;
                case 2: op = (1 + r.nextInt(9)) + "." + r.nextInt(10); break;
                case 3: op = ALGOL_EXPR[r.nextInt(ALGOL_EXPR.length)]; break;
                default: op = "-" + (1 + r.nextInt(9));
            }
            if (op == null) op = "2";
            if (sb.length() > izq.length() + 4) sb.append(operadores[r.nextInt(operadores.length)]);
            sb.append(op);
        }
        String expr = sb.toString();
        // A veces entre paréntesis (la expresión no se valida, S3)
        if (r.nextInt(4) == 0) expr = izq + " := ( " + expr.substring(izq.length() + 4) + " )";
        g.p.Linea(expr + fin);
    }

    // IF con cualquier condición (3.2) y ramas de uno o dos comandos simples
    private static void If(Gen g, String fin) {
        Programa p = g.p;
        Random r = g.r;
        String cond;
        List<String> ids303 = new ArrayList<>();
        int c = r.nextInt(g.limpio ? 6 : 10);
        String v = g.Var("INT", "REAL");
        String ch = g.Var("CHAR");
        switch (c) {
            case 0: cond = "1 > 0"; break;
            case 1: cond = "TRUE"; break;
            case 2: cond = "max > 1"; break;
            case 3: cond = v != null ? v + " > 0" : "FALSE"; break;
            case 4: cond = ch != null ? ch + " = \"S\" OR " + ch + " = \"s\"" : "0 < 1"; break;
            case 5: cond = v != null ? "( " + v + " * 2 ) >= ABS " + v : "TRUE"; break;
            case 6: {
                String u = NoDefinido(g);
                cond = u + " > 0";
                ids303.add(u);
                break;
            }
            case 7: {
                // Una etiqueta en la condición da 303 antes y después (3.2)
                String e = ETIQUETAS[r.nextInt(ETIQUETAS.length)];
                cond = e;
                ids303.add(e);
                break;
            }
            case 8: cond = "zz > zz"; ids303.add("zz"); ids303.add("zz"); break;
            default: {
                String u = NoDefinido(g);
                cond = (v != null ? v : "1") + " > " + u;
                ids303.add(u);
            }
        }
        int n = p.Linea("IF ( " + cond + " ) THEN");
        for (String id : ids303) {
            Lexicos(p, n, id);
            p.E(n, E303(id));
        }
        Rama(g, false);
        if (r.nextInt(3) == 0) {
            p.Linea("ELSE");
            Rama(g, false);
        }
        p.Linea("FI" + fin);
    }

    // FOR correcto: variable INT declarada, límites constantes enteras o variables INT; cuerpo sin errores
    // ni etiquetas. Sin variable INT, un print ( newline ).
    private static void For(Gen g, String fin) {
        Random r = g.r;
        String i = g.Var("INT");
        if (i == null) {
            g.p.Linea("print ( newline )" + fin);
            return;
        }
        String[] lim = new String[3];
        for (int k = 0; k < 3; k++) {
            String v = r.nextInt(3) == 0 ? g.Var("INT") : null;
            lim[k] = v != null ? v : String.valueOf(k == 1 ? 1 + r.nextInt(3) : r.nextInt(20));
        }
        g.p.Linea("FOR " + i + " FROM " + lim[0] + " BY " + lim[1] + " TO " + lim[2]);
        g.p.Linea("DO");
        Rama(g, true);
        g.p.Linea("OD" + fin);
    }

    // Uno o dos comandos simples; el último sin ";" (antes de ELSE/FI/OD)
    private static void Rama(Gen g, boolean enFor) {
        K[] tipos = {K.PRINT_NL, K.PRINT_TXT, K.PRINT_LISTA, K.READ, K.ASIG, K.ASIG, K.GOTO, K.SKIP};
        int k = 1 + g.r.nextInt(2);
        for (int j = 0; j < k; j++) Simple(g, tipos[g.r.nextInt(tipos.length)], j == k - 1 ? "" : ";", enFor);
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
        // 1. Controles del diagnóstico, con su -errores.txt de la tabla Examples
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
        // 3. Fijos de áreas anteriores fuera de C (mismo fuente que en PruebasArchivoErrores y PruebasSintaxis)
        CasoCap c = Fijo("ae-t2", "t2.mingol", "BEGIN", "INT a;", "INT a;", "print ( (b) );", "GOTO nada", "END");
        c.oraculo.E(3, E304("a"));
        c.oraculo.E(4, E303("b"));
        c.oraculo.E(5, E303("nada"));
        l.add(c);
        c = Fijo("ae-t3", "t3.mingol", "BEGIN", "INT a;", "LONG x;", "print ( (b) )", "END");
        c.oraculo.P(3, Adv("LONG"));
        c.oraculo.E(4, E303("b"));
        l.add(c);
        c = Fijo("ae-t5", "t5.mingol", "BEGIN", "BEGIN", "INT a;", "print ( (a) )", "END", "END");
        c.oraculo.E(2, E305());
        c.oraculo.E(6, E306());
        l.add(c);
        c = Fijo("ae-t7", "t7.mingol", "BEGIN", "INT a;", "x = 3;", "print ( (b) )", "END");
        c.oraculo.P(3, E205("x"));
        c.oraculo.E(4, E303("b"));
        l.add(c);
        c = Fijo("ae-t13", "t13.mingol", "BEGIN", "CHAR y := zz;", "print ( (y) )", "END");
        c.oraculo.P(2, E210());
        l.add(c);
        c = Fijo("ae-c1", "c1.mingol", "BEGIN", "inicio:", "IF ( inicio ) THEN", "SKIP", "FI", "END");
        c.oraculo.E(3, E303("inicio"));
        l.add(c);
        c = Fijo("ae-c6", "c6.mingol", "BEGIN", "IF ( end ) THEN", "SKIP", "FI", "END");
        c.oraculo.E(2, E303("end"));
        l.add(c);
        l.add(Fijo("sx-s20", "s20.mingol", "BEGIN", "INT a, b;", "print ( (a, b) );", "print ( (a) );", "print ( newline )", "END"));
        l.add(Fijo("sx-s45", "s45.mingol", "BEGIN", "CHAR r;", "IF ( r = \"S\" OR r = \"s\" ) THEN", "print ( newline )", "FI", "END"));
        c = Fijo("sx-s59", "s59.mingol", "BEGIN", "print ( (a, b) )", "END");
        c.oraculo.E(2, E303("a"));
        c.oraculo.E(2, E303("b"));
        l.add(c);
        // 4. Asignación, read y FOR correctos (Test Case 4): hoy no se miran y después no dan mensajes
        l.add(Fijo("ok-tc4", "tc4.mingol", "BEGIN", "INT a;", "REAL r;", "CHAR c;", "r := a * 2 + 1.5;", "c := \"x\";",
                "read ( a );", "FOR a FROM 1 BY 1 TO 3", "DO", "print ( (a, r) )", "OD", "END"));
        l.add(Fijo("ok-asig", "asig.mingol", "BEGIN", "INT a, b;", "REAL r;", "CHAR c, d;", "a := -5;", "b := ( a * 2 ) + max;",
                "r := a / 2.5 - ABS b;", "a := r;", "c := d;", "d := c + \"S\"", "END"));
        l.add(Fijo("ok-read", "read.mingol", "BEGIN", "INT a;", "REAL r;", "CHAR c;", "read ( a );", "read ( r );", "read ( c )", "END"));
        l.add(Fijo("ok-for", "for.mingol", "BEGIN", "INT i, d, h, p;", "REAL r;", "d := 1;", "h := 10;", "p := 2;",
                "FOR i FROM d BY p TO h", "DO", "r := r + i;", "print ( (i, r) )", "OD;", "FOR i FROM 1 BY 1 TO h", "DO",
                "read ( r )", "OD", "END"));
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

    // Carpeta de la captura del área 4; las de sintaxis y léxico no se tocan (R9)
    private static Path CarpetaGolden() throws IOException {
        return CarpetaTemp().resolve("mingol-semantica-golden");
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

    // Cuántas semillas producen cada código o advertencia, y cuántas tienen cada comando (cobertura del dominio)
    private static void Cobertura(List<CasoCap> casos) {
        Map<String, Integer> cuenta = new TreeMap<>();
        Map<String, Integer> comandos = new TreeMap<>();
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
            Set<String> vistos = new HashSet<>();
            for (String l : c.lineas) {
                String t = l.trim();
                if (t.startsWith("FOR ")) vistos.add("FOR");
                else if (t.startsWith("IF ")) vistos.add("IF");
                else if (t.startsWith("read ")) vistos.add("read");
                else if (t.contains(" := ") && !t.startsWith("INT") && !t.startsWith("REAL") && !t.startsWith("CHAR")) vistos.add(":=");
                else if (t.startsWith("GOTO ")) vistos.add("GOTO");
            }
            for (String k : vistos) comandos.merge(k, 1, Integer::sum);
        }
        System.out.println("Cobertura del generador (" + semillas + " semillas, " + validos + " válidas): " + cuenta);
        System.out.println("Semillas con cada comando: " + comandos);
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
        Registrar("casos y fuentes iguales a la captura", "P5", true, difIds);

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
            if (esperado != null && obtenido != null && !esperado.equals(obtenido)) {
                // GetMensajes, GetErrors, hasErrors, evaluador y TestProyecto iguales
                dif.add("captura: " + PrimeraDiferencia(esperado, obtenido));
            }
            // -errores.txt byte a byte (leído con ISO-8859-1 para el reporte, R6)
            if (!Arrays.equals(erroresEsp, erroresObt)) {
                String ea = erroresEsp == null ? "(ausente)" : new String(erroresEsp, StandardCharsets.ISO_8859_1);
                String eb = erroresObt == null ? "(ausente)" : new String(erroresObt, StandardCharsets.ISO_8859_1);
                dif.add("-errores.txt: " + PrimeraDiferencia(ea, eb));
            }
            // El oráculo (el mismo que usará CI) también debe seguir valiendo
            ComprobarReferencia(c, obtenido, erroresObt, dif);
            Registrar("preservación " + id, "P5", true, dif);
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

    // Lee el fuente igual que TestProyecto: cada línea seguida de "\n"
    private static String LeerFuente(String archivo) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = reader.readLine()) != null) sb.append(linea).append("\n");
        }
        return sb.toString();
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
    // Tarea 3.8: hijo de lotes (R8) y su lectura
    // ------------------------------------------------------------------

    // Fuentes por hijo: 25 parseos (y a lo sumo 25 TestProyecto) entran holgados en los 10 s del límite
    private static final int LOTE = 25;

    // Resultado de un fuente en un hijo de lote
    private static class Dump {
        final List<String> m = new ArrayList<>();      // "línea|mensaje" del lexer y del parser
        final List<String> v = new ArrayList<>();      // "línea|mensaje" del evaluador, en orden
        final List<String> exc = new ArrayList<>();
        String h;                                      // hasErrors
        String tp;                                     // TestProyecto (null si no se pidió)
    }

    // Hijo de lote: por cada "archivo|tp" de la lista, parser, evaluador y (si tp=1) TestProyecto, que escribe
    // el -errores.txt en la carpeta actual; deja archivo.dump al terminar cada fuente
    private static void HijoLote(String lista) throws Exception {
        Method tp = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        tp.setAccessible(true);
        for (String l : Files.readAllLines(Paths.get(lista), StandardCharsets.UTF_8)) {
            if (l.isBlank()) continue;
            String[] p = l.split("\\|");
            String archivo = p[0];
            StringBuilder sb = new StringBuilder();
            try {
                Evaluator.Reiniciar();
                Parser ps = new Parser(new Lexer(LeerFuente(archivo)));
                Program prog = ps.ParseProgram();
                for (Mensaje m : ps.GetMensajes()) sb.append("M|").append(m.getLinea()).append('|').append(Escapar(m.Formatear())).append('\n');
                sb.append("H|").append(ps.hasErrors).append('\n');
                Evaluator.Evaluate(prog, new Environment());
                for (Mensaje m : Evaluator.GetMensajes()) sb.append("V|").append(m.getLinea()).append('|').append(Escapar(m.Formatear())).append('\n');
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
                case "M": d.m.add(resto); break;
                case "V": d.v.add(resto); break;
                case "H": d.h = resto; break;
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

    // Fuente con CRLF y salto final, como en la exploración
    private static byte[] Bytes(List<String> lineas) {
        return (String.join("\r\n", lineas) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------
    // Modo unitarias (diseño, Unit Tests)
    // ------------------------------------------------------------------

    private static String E108(String lex) { return Err(108, "Constante entera fuera de rango", lex); }
    private static String E204(String tipo, String obtenido, String lex) {
        return Err(204, "Se esperaba un token de tipo " + tipo + " pero se obtuvo " + obtenido, lex);
    }

    // Casos con -errores.txt completo: los de Examples (m01–m30, x01–x08) y bordes propios
    private static List<Caso> CasosUnitarios() {
        List<Caso> c = new ArrayList<>(Casos());
        // 2.1: etiqueta en una rama de IF, con GOTO hacia adelante (línea 3) y hacia atrás (ELSE y final): válido
        c.add(X("u", "u-if-etiq", "INT a;", "GOTO fin;", "IF ( a > 0 ) THEN", "fin:", "SKIP", "ELSE", "GOTO fin", "FI;", "GOTO fin"));
        // 2.5: mezclas INT/REAL, palabras ALGOL y "-" unario sin 301
        c.add(X("u", "u-mezcla", "INT a;", "REAL r;", "r := -a + max * 2.5;", "a := ( r - 1 ) / ABS a"));
        // 2.8: límites constantes y variables INT válidos
        c.add(X("u", "u-for-ok", "INT i, n;", "n := 3;", "FOR i FROM 1 BY n TO n", "DO", "SKIP", "OD"));
        // 2.10: read de cualquier tipo
        c.add(X("u", "u-read-tipos", "REAL r;", "CHAR c;", "read ( r );", "read ( c )"));
        // 2.11: print de una variable homónima de una etiqueta (cuenta la variable, DM8)
        c.add(X("u", "u-print-hom", "INT fin;", "fin:", "print ( (fin) );", "GOTO fin"));
        // 3.10: FOR con cabecera rota (204): sin 3xx de los campos null y con el cuerpo evaluado
        c.add(X("u", "u-for-204", "INT i;", "FOR FROM 1 BY 1 TO 3", "DO", "print ( (zz) )", "OD")
                .m(3, E204("IDENT", "FROM", "FROM")).m(5, E303("zz")));
        // 3.10: read ( ) (204) sin 303
        c.add(X("u", "u-read-204", "INT a;", "read ( )").m(3, E204("IDENT", "RPAREN", ")")));
        // 3.10: constante fuera de rango (108) sin 301
        c.add(X("u", "u-108", "INT a;", "a := 40000").m(3, E108("40000")));
        // DS7: print sin ";" seguido de otro comando: 204 [EOL] y el 303 del nodo que no se descarta
        c.add(X("u", "u-ds7", "INT a;", "print ( (a, zz) )", "SKIP").m(3, E204("SEMICOLON", "EOL", "EOL"), E303("zz")));
        return c;
    }

    private static void Unitarias() throws Exception {
        Path base = CarpetaSegura();
        Path trabajo = base.resolve("unitarias");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        // 1. Casos con archivo: todos en lotes, con TestProyecto
        List<Caso> casos = CasosUnitarios();
        List<String> archivos = new ArrayList<>();
        Map<String, Caso> porArchivo = new LinkedHashMap<>();
        for (Caso c : casos) {
            String a = c.id + ".mingol";
            Files.write(trabajo.resolve(a), Bytes(Arrays.asList(c.lineas)));
            archivos.add(a);
            porArchivo.put(a, c);
        }
        Map<String, String> problemas = new HashMap<>();
        Map<String, Dump> dumps = CorrerLotes(trabajo, archivos, new HashSet<>(archivos), "u", problemas);
        for (Map.Entry<String, Caso> e : porArchivo.entrySet()) {
            Caso c = e.getValue();
            List<String> dif = new ArrayList<>();
            if (problemas.containsKey(e.getKey())) dif.add(problemas.get(e.getKey()));
            Dump d = dumps.get(e.getKey());
            if (d != null) {
                for (String x : d.exc) dif.add("excepción: " + x);
                if (!String.valueOf(c.valido()).equals(d.tp)) dif.add("TestProyecto esperado " + c.valido() + ", obtenido " + d.tp);
            }
            CompararArchivo(trabajo.resolve(c.id + "-errores.txt"), c.archivoEsperado(), dif);
            Registrar(c.id, "archivo", true, dif);
        }

        // 2. Sin archivo: Environment, Evaluate fuera de un Program y borrados M1/M2/M14
        UnitariaEnvironment();
        UnitariaFueraDePrograma();
        UnitariaBorrados();

        // 3. Meta: proyecto.mingol sin 2xx ni 3xx
        Proyecto(base);
        ResumenSimple("unitarias");
    }

    // Llama por reflexión a un método de Environment (API nueva: el arnés compila también contra 674583b)
    private static Object Llamar(Environment env, String metodo, Class<?>[] tipos, Object... args) throws Exception {
        return Environment.class.getMethod(metodo, tipos).invoke(env, args);
    }

    // EsVariable, DeclararTipo/TipoDe, RegistrarEtiqueta, PrimeraEtiqueta y EsEtiqueta (DM1)
    @SuppressWarnings("unchecked")
    private static void UnitariaEnvironment() {
        List<String> dif = new ArrayList<>();
        try {
            Class<?>[] s = {String.class};
            Environment env = new Environment();
            env.put("x", new Entero(1));
            env.put("err", new Errado("m"));
            env.put("t", "");
            env.put("BEGIN", "BEGIN");
            // Un Objeto y un Errado cuentan; "", "BEGIN" y lo ausente no
            Esperar(dif, "EsVariable(x)", true, Llamar(env, "EsVariable", s, "x"));
            Esperar(dif, "EsVariable(err)", true, Llamar(env, "EsVariable", s, "err"));
            Esperar(dif, "EsVariable(t)", false, Llamar(env, "EsVariable", s, "t"));
            Esperar(dif, "EsVariable(BEGIN)", false, Llamar(env, "EsVariable", s, "BEGIN"));
            Esperar(dif, "EsVariable(nada)", false, Llamar(env, "EsVariable", s, "nada"));
            // TipoDe: null sin declaración
            Esperar(dif, "TipoDe(x) sin declarar", null, Llamar(env, "TipoDe", s, "x"));
            Llamar(env, "DeclararTipo", new Class<?>[]{String.class, String.class}, "x", "INT");
            Esperar(dif, "TipoDe(x)", "INT", Llamar(env, "TipoDe", s, "x"));
            // RegistrarEtiqueta: true la primera vez, false después, sin pisar
            Object n1 = new Object(), n2 = new Object();
            Class<?>[] so = {String.class, Object.class};
            Esperar(dif, "RegistrarEtiqueta(fin) 1", true, Llamar(env, "RegistrarEtiqueta", so, "fin", n1));
            Esperar(dif, "RegistrarEtiqueta(fin) 2", false, Llamar(env, "RegistrarEtiqueta", so, "fin", n2));
            if (Llamar(env, "PrimeraEtiqueta", s, "fin") != n1) dif.add("PrimeraEtiqueta(fin) no es el primer nodo");
            Esperar(dif, "PrimeraEtiqueta(otra)", null, Llamar(env, "PrimeraEtiqueta", s, "otra"));
            Esperar(dif, "EsEtiqueta(fin)", true, Llamar(env, "EsEtiqueta", s, "fin"));
            Esperar(dif, "EsEtiqueta(x)", false, Llamar(env, "EsEtiqueta", s, "x"));
            // Espacios separados: la etiqueta no entra al mapa de variables
            Esperar(dif, "get(fin)", null, env.get("fin"));
            Esperar(dif, "EsVariable(fin)", false, Llamar(env, "EsVariable", s, "fin"));
        } catch (Throwable t) {
            dif.add("excepción: " + (t instanceof InvocationTargetException ? t.getCause() : t));
        }
        Registrar("environment", "reflexión", true, dif);
    }

    private static void Esperar(List<String> dif, String que, Object esperado, Object obtenido) {
        if (!java.util.Objects.equals(esperado, obtenido)) dif.add(que + ": esperado " + esperado + ", obtenido " + obtenido);
    }

    // Token con línea, como en PruebasArchivoErrores
    private static Token Tk(String literal, int linea) {
        Token t = new Token(MingolToken.IDENT, literal);
        t.setLine(linea);
        return t;
    }

    // Fuera de un Program (REPL, DM3): dos Etiqueta "fin" sobre el mismo Environment → nada y luego 304;
    // Evaluate(Identifier) sobre env.put(nombre, "") sigue dando 303
    @SuppressWarnings("unchecked")
    private static void UnitariaFueraDePrograma() {
        List<String> dif = new ArrayList<>();
        try {
            Evaluator.Reiniciar();
            Environment env = new Environment();
            Objeto r1 = Evaluator.Evaluate(new Etiqueta(Tk("fin", 2), "fin"), env);
            if (r1 instanceof Errado || !Evaluator.GetMensajes().isEmpty()) dif.add("primera etiqueta: " + Formateados(Evaluator.GetMensajes()));
            Objeto r2 = Evaluator.Evaluate(new Etiqueta(Tk("fin", 5), "fin"), env);
            if (!(r2 instanceof Errado)) dif.add("segunda etiqueta: no devolvió Errado");
            Esperar(dif, "mensajes tras la segunda", L("5|" + E304("fin")), Formateados(Evaluator.GetMensajes()));

            Evaluator.Reiniciar();
            Environment env2 = new Environment();
            env2.put("inicio", "");
            Objeto r3 = Evaluator.Evaluate(new Identifier(Tk("inicio", 3), "inicio"), env2);
            if (!(r3 instanceof Errado)) dif.add("Identifier sobre put(inicio, \"\"): no devolvió Errado");
            Esperar(dif, "Identifier sobre put(inicio, \"\")", L("3|" + E303("inicio")), Formateados(Evaluator.GetMensajes()));
        } catch (Throwable t) {
            dif.add("excepción: " + t);
        }
        Registrar("fuera-de-program", "DM3", true, dif);
    }

    private static List<String> Formateados(List<Mensaje> ms) {
        List<String> r = new ArrayList<>();
        for (Mensaje m : ms) r.add(m.getLinea() + "|" + m.Formatear());
        return r;
    }

    // M1/M2/M14 (2.14): lo borrado ya no existe y lo que se conserva sigue (M3)
    private static void UnitariaBorrados() {
        List<String> dif = new ArrayList<>();
        Set<String> campos = new HashSet<>();
        for (java.lang.reflect.Field f : Parser.class.getDeclaredFields()) campos.add(f.getName());
        Set<String> metodosP = new HashSet<>();
        for (Method m : Parser.class.getDeclaredMethods()) metodosP.add(m.getName());
        Set<String> metodosE = new HashSet<>();
        for (Method m : Evaluator.class.getDeclaredMethods()) metodosE.add(m.getName());
        for (String c : new String[]{"isEnd", "isComment", "errorBegin"}) if (campos.contains(c)) dif.add("sigue el campo Parser." + c);
        for (String m : new String[]{"JumpEOL", "ExpectedCurrentTokenError"}) if (metodosP.contains(m)) dif.add("sigue Parser." + m);
        if (metodosE.contains("IsTruthy")) dif.add("sigue Evaluator.IsTruthy");
        for (String m : new String[]{"Lexema", "ExpectedToken", "ExpectedTokenError", "ParseExpression", "ParseReturnStatement"}) {
            if (!metodosP.contains(m)) dif.add("falta Parser." + m + " (M3)");
        }
        Registrar("borrados-m1-m2-m14", "reflexión", true, dif);
    }

    // ------------------------------------------------------------------
    // Modo propiedades (diseño, Property-Based Tests)
    // ------------------------------------------------------------------

    private static final int SEMILLAS_PROPIEDADES = 300;
    private static final int SEMILLAS_VALIDEZ = 50;
    private static final int CLASES_MUTACION = 12;

    // Línea física del generador de válidos con lo que el oráculo espera bajo ella
    private static class LG {
        final String t;                                // texto sin el terminador
        String f;                                      // texto final (con ";" si corresponde)
        String adv;                                    // lexema de la advertencia (instrucción ALGOL)
        final List<String> ev = new ArrayList<>();     // mensajes del evaluador, en orden de registro
        final List<String> px = new ArrayList<>();     // mensajes del parser propios de la mutación (209)
        LG(String t) { this.t = t; this.f = t; }
    }

    // Tipos de comando: simple (lleva ";" salvo al final de su bloque), crudo (etiqueta, ALGOL o declaración
    // tardía: sin terminador y nunca último), IF y FOR
    private static final int C_SIMPLE = 0, C_CRUDO = 1, C_IF = 2, C_FOR = 3;

    private static class Cmd {
        int tipo;
        LG linea;                                      // la línea simple o la cabecera del IF/FOR
        final List<Cmd> rama1 = new ArrayList<>();     // THEN o cuerpo del FOR
        List<Cmd> rama2;                               // ELSE (null si no hay)
    }

    private static Cmd Simple(String t, String... ev) {
        Cmd c = new Cmd();
        c.tipo = C_SIMPLE;
        c.linea = new LG(t);
        c.linea.ev.addAll(Arrays.asList(ev));
        return c;
    }

    private static Cmd Crudo(String t) {
        Cmd c = Simple(t);
        c.tipo = C_CRUDO;
        return c;
    }

    // Programa de las familias "validos" y "mutacion"
    private static class PV {
        Random r;
        final Map<String, String> tipos = new LinkedHashMap<>();
        final List<LG> decl = new ArrayList<>();
        final List<Cmd> top = new ArrayList<>();
        final List<String> etiquetas = new ArrayList<>();          // nombres de todas las etiquetas
        final Map<String, Cmd> contenedor = new HashMap<>();       // etiqueta → comando del nivel superior que la tiene
        String clase = "valido";
        List<LG> lineas;                                           // tras Renderizar

        // Variables declaradas de los tipos pedidos (en orden de declaración, determinista)
        List<String> Vars(String... t) {
            List<String> l = new ArrayList<>();
            for (Map.Entry<String, String> e : tipos.entrySet()) if (Arrays.asList(t).contains(e.getValue())) l.add(e.getKey());
            return l;
        }

        // Elemento al azar (null si la lista está vacía); genérico para nombres y bloques
        <T> T Una(List<T> l) { return l.isEmpty() ? null : l.get(r.nextInt(l.size())); }

        // Etiquetas que no son variables (para usarlas donde se espera una variable)
        List<String> EtiquetasNoVar() {
            List<String> l = new ArrayList<>();
            for (String e : etiquetas) if (!tipos.containsKey(e)) l.add(e);
            return l;
        }

        // Ni variable ni (a veces) etiqueta: un no definido o una etiqueta que no es variable
        String Indefinido() {
            List<String> ne = EtiquetasNoVar();
            if (!ne.isEmpty() && r.nextInt(3) == 0) return Una(ne);
            return NO_DEFINIDOS[r.nextInt(NO_DEFINIDOS.length)];
        }

        // Bloques donde puede ir un comando simple: nivel superior y ramas de IF (y cuerpos de FOR si conFor)
        List<List<Cmd>> Bloques(boolean conFor) {
            List<List<Cmd>> b = new ArrayList<>();
            b.add(top);
            for (Cmd c : top) {
                if (c.tipo == C_IF) {
                    b.add(c.rama1);
                    if (c.rama2 != null) b.add(c.rama2);
                } else if (c.tipo == C_FOR && conFor) {
                    b.add(c.rama1);
                }
            }
            return b;
        }

        // Comando del nivel superior que contiene al bloque b (null si b es el nivel superior)
        Cmd Dueno(List<Cmd> b) {
            for (Cmd c : top) if (c.rama1 == b || c.rama2 == b) return c;
            return null;
        }

        void Insertar(List<Cmd> b, Cmd c) { b.add(r.nextInt(b.size() + 1), c); }
    }

    private static long Mezclar(long semilla) {
        long z = semilla * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // Lexema de un texto en un 301/309 (DM4)
    private static String LexTexto(String contenido) { return contenido.isEmpty() ? "STRING" : contenido; }

    // Operandos compatibles con el tipo de la izquierda (REQ-CMD-02), sin mensajes
    private static List<String> Compatibles(PV g, String tipoIzq, int n) {
        List<String> ops = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (tipoIzq.equals("CHAR")) {
                String v = g.Una(g.Vars("CHAR"));
                ops.add(v != null && g.r.nextBoolean() ? v : "\"" + "xS?".charAt(g.r.nextInt(3)) + "\"");
            } else {
                String op;
                switch (g.r.nextInt(5)) {
                    case 0: op = g.Una(g.Vars("INT", "REAL")); break;
                    case 1: op = String.valueOf(g.r.nextInt(100)); break;
                    case 2: op = (1 + g.r.nextInt(9)) + "." + g.r.nextInt(10); break;
                    case 3: op = ALGOL_EXPR[g.r.nextInt(ALGOL_EXPR.length)]; break;
                    default: op = "-" + (1 + g.r.nextInt(9));
                }
                ops.add(op == null ? "2" : op);
            }
        }
        return ops;
    }

    // "izq := op1 + op2 ..."; las numéricas con operadores variados y a veces entre paréntesis (S3)
    private static String Expr(PV g, String izq, String tipoIzq, List<String> ops) {
        String[] operadores = tipoIzq.equals("CHAR") ? new String[]{" + "} : new String[]{" + ", " - ", " * ", " / "};
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < ops.size(); k++) {
            if (k > 0) sb.append(operadores[g.r.nextInt(operadores.length)]);
            sb.append(ops.get(k));
        }
        String e = sb.toString();
        if (!tipoIzq.equals("CHAR") && g.r.nextInt(4) == 0) e = "( " + e + " )";
        return izq + " := " + e;
    }

    // Condición válida: variables declaradas, constantes y palabras ALGOL (3.2)
    private static String Condicion(PV g) {
        String v = g.Una(g.Vars("INT", "REAL"));
        String ch = g.Una(g.Vars("CHAR"));
        switch (g.r.nextInt(6)) {
            case 0: return "1 > 0";
            case 1: return "TRUE";
            case 2: return v != null ? "max > " + v : "max > 1";
            case 3: return v != null ? v + " > 0" : "FALSE";
            case 4: return ch != null ? ch + " = \"S\" OR " + ch + " = \"s\"" : "0 < 1";
            default: return v != null ? "( " + v + " * 2 ) >= ABS " + v : "TRUE";
        }
    }

    // Comando simple válido (en cualquier bloque)
    private static Cmd SimpleValido(PV g) {
        Random r = g.r;
        switch (r.nextInt(9)) {
            case 0: return Simple("print ( newline )");
            case 1: return Simple("print ( \"" + TEXTOS[r.nextInt(TEXTOS.length)] + "\" )");
            case 2: {
                List<String> ids = new ArrayList<>();
                for (int k = 1 + r.nextInt(3); k > 0; k--) ids.add(g.Una(g.Vars("INT", "REAL", "CHAR")));
                return Simple("print ( (" + String.join(", ", ids) + ") )");
            }
            case 3: return Simple("read ( " + g.Una(g.Vars("INT", "REAL", "CHAR")) + " )");
            case 4:
            case 5: {
                String izq = g.Una(g.Vars("INT", "REAL", "CHAR"));
                String t = g.tipos.get(izq);
                return Simple(Expr(g, izq, t, Compatibles(g, t, 1 + r.nextInt(3))));
            }
            case 6:
                // GOTO hacia atrás o hacia adelante, también al cuerpo de un FOR o desde él (todas ya decididas)
                return g.etiquetas.isEmpty() ? Simple("SKIP") : Simple("GOTO " + g.Una(g.etiquetas));
            case 7: return Simple("SKIP");
            default: return Simple("print ( newline )");
        }
    }

    // FOR válido: variable INT, límites constantes enteras o variables INT; cuerpo de comandos simples
    private static Cmd ForValido(PV g, String ciclo, String[] limites) {
        Cmd c = new Cmd();
        c.tipo = C_FOR;
        String[] lim = limites;
        if (lim == null) {
            lim = new String[3];
            for (int k = 0; k < 3; k++) {
                String v = g.r.nextInt(3) == 0 ? g.Una(g.Vars("INT")) : null;
                lim[k] = v != null ? v : String.valueOf(k == 1 ? 1 + g.r.nextInt(3) : g.r.nextInt(20));
            }
        }
        c.linea = new LG("FOR " + (ciclo != null ? ciclo : g.Una(g.Vars("INT"))) + " FROM " + lim[0] + " BY " + lim[1] + " TO " + lim[2]);
        for (int k = 1 + g.r.nextInt(2); k > 0; k--) c.rama1.add(SimpleValido(g));
        return c;
    }

    private static Cmd IfValido(PV g) {
        Cmd c = new Cmd();
        c.tipo = C_IF;
        c.linea = new LG("IF ( " + Condicion(g) + " ) THEN");
        for (int k = 1 + g.r.nextInt(2); k > 0; k--) c.rama1.add(SimpleValido(g));
        if (g.r.nextInt(3) == 0) {
            c.rama2 = new ArrayList<>();
            for (int k = 1 + g.r.nextInt(2); k > 0; k--) c.rama2.add(SimpleValido(g));
        }
        return c;
    }

    // Etiqueta en un bloque al azar (nivel superior, rama de IF o cuerpo de FOR)
    private static void ColocarEtiqueta(PV g, String e, List<Cmd> bloque) {
        Cmd c = Crudo(e + (g.r.nextInt(4) == 0 ? " :" : ":"));
        g.Insertar(bloque, c);
        Cmd dueno = g.Dueno(bloque);
        g.contenedor.put(e, dueno != null ? dueno : c);
    }

    // Programa válido (familia "validos"): declaraciones tipadas (la primera INT), etiquetas en el nivel
    // superior, en ramas de IF y en cuerpos de FOR, GOTO hacia atrás y hacia adelante, asignaciones
    // compatibles con ALGOL y "-" unario, read, print, FOR correcto y a veces un homónimo (M11)
    private static PV GenerarValido(int semilla) {
        PV g = new PV();
        g.r = new Random(Mezclar(semilla) ^ 0x5EEDL);
        Random r = g.r;
        List<String> libres = new ArrayList<>(Arrays.asList(VARS));
        for (int d = 1 + r.nextInt(3); d > 0 && !libres.isEmpty(); d--) {
            String tipo = g.decl.isEmpty() ? "INT" : TIPOS[r.nextInt(TIPOS.length)];
            List<String> ns = new ArrayList<>();
            for (int k = 1 + r.nextInt(3); k > 0 && !libres.isEmpty(); k--) {
                String v = libres.remove(r.nextInt(libres.size()));
                ns.add(v);
                g.tipos.put(v, tipo);
            }
            g.decl.add(new LG(tipo + " " + String.join(", ", ns) + ";"));
        }
        // Etiquetas decididas de antemano (los GOTO pueden ir hacia adelante)
        List<String> le = new ArrayList<>(Arrays.asList(ETIQUETAS));
        for (int k = r.nextInt(4); k > 0; k--) g.etiquetas.add(le.remove(r.nextInt(le.size())));
        if (g.tipos.size() >= 2 && r.nextInt(4) == 0) g.etiquetas.add(g.Una(new ArrayList<>(g.tipos.keySet())));
        for (int i = 2 + r.nextInt(6); i > 0; i--) {
            int k = r.nextInt(10);
            if (k == 5) g.top.add(IfValido(g));
            else if (k == 6 || k == 7) g.top.add(ForValido(g, null, null));
            else if (k == 8) {
                String[] a = INSTRUCCIONES_ALGOL[r.nextInt(INSTRUCCIONES_ALGOL.length)];
                Cmd c = Crudo(a[0]);
                c.linea.adv = a[1];
                g.top.add(c);
            } else g.top.add(SimpleValido(g));
        }
        for (String e : g.etiquetas) {
            List<List<Cmd>> b = g.Bloques(true);
            ColocarEtiqueta(g, e, b.get(r.nextInt(b.size())));
        }
        return g;
    }

    // ---- Mutaciones (diseño: "Una sola situación"); cada una devuelve el comando con sus mensajes ----

    // 1. GOTO a una variable (que no es etiqueta) → 303
    private static Cmd MutGotoVar(PV g) {
        List<String> l = new ArrayList<>(g.tipos.keySet());
        l.removeAll(g.etiquetas);
        String v = g.Una(l);
        return Simple("GOTO " + v, E303(v));
    }

    // 2. Izquierda no declarada (o etiqueta) → 303 y ningún 301, aunque la derecha sea incompatible
    private static Cmd MutIzquierda(PV g) {
        String u = g.Indefinido();
        List<String> ops = new ArrayList<>();
        for (int k = 1 + g.r.nextInt(3); k > 0; k--) {
            switch (g.r.nextInt(5)) {
                case 0: ops.add(g.Una(new ArrayList<>(g.tipos.keySet()))); break;
                case 1: ops.add("\"hola\""); break;
                case 2: ops.add("TRUE"); break;
                case 3: ops.add("1.5"); break;
                default: ops.add("\"\"");
            }
        }
        return Simple(u + " := " + String.join(" + ", ops), E303(u));
    }

    // 3. k apariciones de un no declarado (o etiqueta) en la derecha → k × 303
    private static Cmd MutDerecha(PV g) {
        String izq = g.Una(new ArrayList<>(g.tipos.keySet()));
        String t = g.tipos.get(izq);
        String u = g.Indefinido();
        List<String> ops = Compatibles(g, t, g.r.nextInt(3));
        int k = 1 + g.r.nextInt(3);
        for (int j = 0; j < k; j++) ops.add(g.r.nextInt(ops.size() + 1), u);
        Cmd c = Simple(Expr(g, izq, t, ops));
        for (int j = 0; j < k; j++) c.linea.ev.add(E303(u));
        return c;
    }

    // 4. k operandos de tipo incompatible (variables, constantes, TRUE/FALSE) → k × 301, en orden del fuente
    private static Cmd MutTipos(PV g) {
        String izq = g.Una(new ArrayList<>(g.tipos.keySet()));
        String t = g.tipos.get(izq);
        boolean ch = t.equals("CHAR");
        List<String[]> ops = new ArrayList<>();
        for (String s : Compatibles(g, t, g.r.nextInt(3))) ops.add(new String[]{s, null});
        int k = 1 + g.r.nextInt(3);
        for (int j = 0; j < k; j++) {
            String[] o;
            int x = g.r.nextInt(5);
            if (ch) {
                String v = g.Una(g.Vars("INT", "REAL"));
                int n = 1 + g.r.nextInt(9);
                if (x == 0 && v != null) o = new String[]{v, E301(v)};
                else if (x == 1) o = new String[]{"-" + n, E301(String.valueOf(n))};      // DM5
                else if (x == 2) o = new String[]{n + ".5", E301(n + ".5")};
                else if (x == 3) o = new String[]{"FALSE", E301("FALSE")};
                else o = new String[]{String.valueOf(n), E301(String.valueOf(n))};
            } else {
                String v = g.Una(g.Vars("CHAR"));
                if (x == 0 && v != null) o = new String[]{v, E301(v)};
                else if (x == 1) o = new String[]{"\"x\"", E301("x")};
                else if (x == 2) o = new String[]{"\"hola\"", E301("hola")};                // sin 309 (M8)
                else if (x == 3) o = new String[]{"\"\"", E301("STRING")};                  // DM4
                else o = new String[]{"TRUE", E301("TRUE")};
            }
            ops.add(g.r.nextInt(ops.size() + 1), o);
        }
        List<String> fuente = new ArrayList<>();
        for (String[] o : ops) fuente.add(o[0]);
        Cmd c = Simple(Expr(g, izq, t, fuente));
        for (String[] o : ops) if (o[1] != null) c.linea.ev.add(o[1]);
        return c;
    }

    // 5. Texto de longitud distinta de 1 hacia CHAR → 309; el mismo hacia INT/REAL → 301 sin 309
    private static Cmd MutCharLargo(PV g) {
        String texto = g.r.nextBoolean() ? "" : (g.r.nextBoolean() ? "hola" : "ab");
        String c = g.Una(g.Vars("CHAR"));
        if (c != null && g.r.nextBoolean()) {
            List<String> ops = Compatibles(g, "CHAR", g.r.nextInt(2));
            ops.add(g.r.nextInt(ops.size() + 1), "\"" + texto + "\"");
            return Simple(Expr(g, c, "CHAR", ops), E309(LexTexto(texto)));
        }
        String a = g.Una(g.Vars("INT", "REAL"));
        if (a == null) {
            // Solo hay CHAR: la variante hacia CHAR
            return Simple(c + " := \"" + texto + "\"", E309(LexTexto(texto)));
        }
        return Simple(a + " := \"" + texto + "\"", E301(LexTexto(texto)));
    }

    // Una de las clases 1–5 o un print de un no definido (para el cuerpo del FOR, clase 8)
    private static Cmd MutSimple(PV g) {
        switch (g.r.nextInt(6)) {
            case 0: return MutGotoVar(g);
            case 1: return MutIzquierda(g);
            case 2: return MutDerecha(g);
            case 3: return MutTipos(g);
            case 4: return MutCharLargo(g);
            default: {
                String u = g.Indefinido();
                return Simple("print ( (" + u + ") )", E303(u));
            }
        }
    }

    // Etiqueta nueva que no es variable, colocada en el nivel superior (clases 10 y 11 sin etiquetas)
    private static String NuevaEtiqueta(PV g) {
        for (String e : ETIQUETAS) {
            if (!g.etiquetas.contains(e) && !g.tipos.containsKey(e)) {
                g.etiquetas.add(e);
                ColocarEtiqueta(g, e, g.top);
                return e;
            }
        }
        throw new IllegalStateException("sin etiquetas libres");
    }

    // Programa válido de la misma semilla con una mutación de la clase ((semilla - 1) % 12) + 1
    private static PV Mutar(int semilla) {
        PV g = GenerarValido(semilla);
        g.r = new Random(Mezclar(semilla) ^ 0x3A7L);
        Random r = g.r;
        int clase = (semilla - 1) % CLASES_MUTACION + 1;
        g.clase = String.format("%02d", clase);
        switch (clase) {
            case 1: g.Insertar(g.Una(g.Bloques(false)), MutGotoVar(g)); break;
            case 2: g.Insertar(g.Una(g.Bloques(false)), MutIzquierda(g)); break;
            case 3: g.Insertar(g.Una(g.Bloques(false)), MutDerecha(g)); break;
            case 4: g.Insertar(g.Una(g.Bloques(false)), MutTipos(g)); break;
            case 5: g.Insertar(g.Una(g.Bloques(false)), MutCharLargo(g)); break;
            case 6: {
                // Variable de ciclo no declarada o etiqueta → 303; REAL/CHAR → 310
                String v = g.Una(g.Vars("REAL", "CHAR"));
                Cmd f;
                if (v != null && r.nextBoolean()) {
                    f = ForValido(g, v, new String[]{"1", "1", "3"});
                    f.linea.ev.add(E310(v));
                } else {
                    String u = g.Indefinido();
                    f = ForValido(g, u, new String[]{"1", "1", "3"});
                    f.linea.ev.add(E303(u));
                }
                g.Insertar(g.top, f);
                break;
            }
            case 7: {
                // k límites REAL/CHAR → k × 311; no declarados → 303 cada uno; en orden FROM, BY, TO
                String[] lim = new String[3];
                List<String> ev = new ArrayList<>();
                int malo = r.nextInt(3);
                for (int k = 0; k < 3; k++) {
                    if (k == malo || r.nextInt(3) == 0) {
                        String v = g.Una(g.Vars("REAL", "CHAR"));
                        if (v != null && r.nextBoolean()) {
                            lim[k] = v;
                            ev.add(E311(v));
                        } else {
                            lim[k] = g.Indefinido();
                            ev.add(E303(lim[k]));
                        }
                    } else {
                        String v = r.nextBoolean() ? g.Una(g.Vars("INT")) : null;
                        lim[k] = v != null ? v : String.valueOf(1 + r.nextInt(9));
                    }
                }
                Cmd f = ForValido(g, null, lim);
                f.linea.ev.addAll(ev);
                g.Insertar(g.top, f);
                break;
            }
            case 8: {
                // Un error de las clases 1–5 o un print de un no definido dentro del cuerpo del FOR
                List<Cmd> fors = new ArrayList<>();
                for (Cmd c : g.top) if (c.tipo == C_FOR) fors.add(c);
                Cmd f;
                if (fors.isEmpty()) {
                    f = ForValido(g, null, null);
                    g.Insertar(g.top, f);
                } else {
                    f = fors.get(r.nextInt(fors.size()));
                }
                g.Insertar(f.rama1, MutSimple(g));
                break;
            }
            case 9: {
                // read de un no declarado o de una etiqueta → 303
                String u = g.Indefinido();
                g.Insertar(g.Una(g.Bloques(false)), Simple("read ( " + u + " )", E303(u)));
                break;
            }
            case 10: {
                // print de una etiqueta (que no es variable) → 303, también junto a variables
                List<String> ne = g.EtiquetasNoVar();
                String e = ne.isEmpty() ? NuevaEtiqueta(g) : g.Una(ne);
                List<String> ids = new ArrayList<>();
                for (int k = r.nextInt(3); k > 0; k--) ids.add(g.Una(new ArrayList<>(g.tipos.keySet())));
                ids.add(r.nextInt(ids.size() + 1), e);
                g.Insertar(g.Una(g.Bloques(false)), Simple("print ( (" + String.join(", ", ids) + ") )", E303(e)));
                break;
            }
            case 11: {
                // Etiqueta repetida (también dentro del FOR) → 304 bajo la segunda, que va después de la primera
                String e = g.etiquetas.isEmpty() ? NuevaEtiqueta(g) : g.Una(g.etiquetas);
                g.top.add(Simple("SKIP"));
                int t = g.top.indexOf(g.contenedor.get(e));
                Cmd dup = Crudo(e + ":");
                dup.linea.ev.add(E304(e));
                List<Cmd> fors = new ArrayList<>();
                for (int k = t + 1; k < g.top.size(); k++) if (g.top.get(k).tipo == C_FOR) fors.add(g.top.get(k));
                if (!fors.isEmpty() && r.nextBoolean()) {
                    g.Insertar(fors.get(r.nextInt(fors.size())).rama1, dup);
                } else {
                    // Antes de un elemento posterior a la primera (nunca al final)
                    g.top.add(t + 1 + r.nextInt(g.top.size() - 1 - t), dup);
                }
                break;
            }
            default: {
                // 12. Uso antes de una declaración tardía → 209 (del parser) y 303 (DM7)
                String tipo = TIPOS[r.nextInt(TIPOS.length)];
                Cmd d = Crudo(tipo + " tardia;");
                d.linea.px.add(E209(tipo));
                int j = 1 + r.nextInt(g.top.size());
                g.top.add(j, d);
                Cmd uso = r.nextBoolean() ? Simple("print ( (tardia) )", E303("tardia")) : Simple("read ( tardia )", E303("tardia"));
                g.top.add(r.nextInt(j + 1), uso);
            }
        }
        return g;
    }

    // Ningún bloque vacío ni terminado en un comando crudo (REQ-LINE-04, DS1)
    private static void Normalizar(List<Cmd> b) {
        for (Cmd c : b) {
            if (c.tipo == C_IF || c.tipo == C_FOR) {
                Normalizar(c.rama1);
                if (c.rama2 != null) Normalizar(c.rama2);
            }
        }
        if (b.isEmpty() || b.get(b.size() - 1).tipo == C_CRUDO) b.add(Simple("SKIP"));
    }

    // Líneas finales: BEGIN, declaraciones, comandos y END; el último de cada bloque sin ";"
    private static void Renderizar(PV g) {
        Normalizar(g.top);
        List<LG> out = new ArrayList<>();
        out.add(new LG("BEGIN"));
        out.addAll(g.decl);
        RenderBloque(g.top, out);
        out.add(new LG("END"));
        g.lineas = out;
    }

    private static void RenderBloque(List<Cmd> b, List<LG> out) {
        for (int k = 0; k < b.size(); k++) {
            Cmd c = b.get(k);
            String fin = k == b.size() - 1 ? "" : ";";
            switch (c.tipo) {
                case C_SIMPLE:
                    c.linea.f = c.linea.t + fin;
                    out.add(c.linea);
                    break;
                case C_CRUDO:
                    out.add(c.linea);
                    break;
                case C_IF:
                    out.add(c.linea);
                    RenderBloque(c.rama1, out);
                    if (c.rama2 != null) {
                        out.add(new LG("ELSE"));
                        RenderBloque(c.rama2, out);
                    }
                    out.add(new LG("FI" + fin));
                    break;
                default:
                    out.add(c.linea);
                    out.add(new LG("DO"));
                    RenderBloque(c.rama1, out);
                    out.add(new LG("OD" + fin));
            }
        }
    }

    // Esperados del oráculo: mensajes del parser (advertencias y 209) y del evaluador (en orden), "línea|mensaje"
    private static List<String> EsperadoParser(PV g) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < g.lineas.size(); i++) {
            LG l = g.lineas.get(i);
            if (l.adv != null) r.add((i + 1) + "|" + Adv(l.adv));
            for (String m : l.px) r.add((i + 1) + "|" + m);
        }
        return r;
    }

    private static List<String> EsperadoEvaluador(PV g) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < g.lineas.size(); i++) for (String m : g.lineas.get(i).ev) r.add((i + 1) + "|" + m);
        return r;
    }

    private static List<String> Textos(PV g) {
        List<String> r = new ArrayList<>();
        for (LG l : g.lineas) r.add(l.f);
        return r;
    }

    private static final Map<String, int[]> cuentasProp = new LinkedHashMap<>();
    private static int fallasProp = 0;

    // Registra una semilla de una familia; si falla imprime la semilla, la clase y el fuente numerado
    private static void RegistrarProp(String familia, int semilla, String clase, List<String> lineas, List<String> dif) {
        int[] c = cuentasProp.computeIfAbsent(familia, k -> new int[2]);
        c[0]++;
        if (dif.isEmpty()) return;
        c[1]++;
        fallasProp++;
        System.out.println("FALLA [" + familia + "] semilla " + semilla + (clase != null ? " (clase " + clase + ")" : ""));
        for (String d : dif) System.out.println("      - " + d);
        for (int i = 0; i < lineas.size(); i++) System.out.println(String.format("      %05d %s", i + 1, lineas.get(i)));
    }

    private static void Propiedades() throws Exception {
        Path base = CarpetaSegura();
        Path trabajo = base.resolve("propiedades");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);

        Map<String, PV> validos = new LinkedHashMap<>();
        Map<String, PV> mutados = new LinkedHashMap<>();
        Map<String, Programa> preservados = new LinkedHashMap<>();
        Map<String, Integer> semillaDe = new HashMap<>();
        Map<String, List<String>> lineasDe = new HashMap<>();
        List<String> archivos = new ArrayList<>();
        Set<String> conTP = new HashSet<>();
        for (int s = 1; s <= SEMILLAS_PROPIEDADES; s++) {
            String av = String.format("v%03d.mingol", s), am = String.format("m%03d.mingol", s), ap = String.format("p%03d.mingol", s);
            PV v = GenerarValido(s);
            Renderizar(v);
            PV m = Mutar(s);
            Renderizar(m);
            Programa p = GenerarPreservacion(s);
            validos.put(av, v);
            mutados.put(am, m);
            preservados.put(ap, p);
            lineasDe.put(av, Textos(v));
            lineasDe.put(am, Textos(m));
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
            String familia, clase = null;
            if (d != null) {
                for (String x : d.exc) dif.add("excepción: " + x);
                // Validez: TestProyecto = ningún ERROR en el -errores.txt
                if (d.tp != null) {
                    Path err = trabajo.resolve(NombreErrores(a));
                    if (!Files.exists(err)) dif.add("no se generó " + err.getFileName());
                    else {
                        boolean conError = new String(Files.readAllBytes(err), StandardCharsets.ISO_8859_1).contains("\n" + S8 + "ERROR");
                        if (!String.valueOf(!conError).equals(d.tp)) dif.add("TestProyecto=" + d.tp + " pero el archivo " + (conError ? "tiene" : "no tiene") + " ERROR");
                    }
                }
            }
            if (a.startsWith("v") || a.startsWith("m")) {
                PV g = a.startsWith("v") ? validos.get(a) : mutados.get(a);
                familia = a.startsWith("v") ? "validos" : "mutacion";
                if (a.startsWith("m")) clase = g.clase;
                for (String l : lineasDe.get(a)) if (l.length() > 80) dif.add("el generador dio una línea de más de 80 columnas");
                if (d != null) {
                    // Parser: advertencias del programa sin mutar más lo propio de la mutación (12: el 209)
                    CompararMulticonjunto("lexer/parser", EsperadoParser(g), d.m, dif);
                    // Evaluador: conteo exacto (código, línea, lexema) y en orden del fuente
                    List<String> ev = EsperadoEvaluador(g);
                    if (!ev.equals(d.v)) dif.add("evaluador: esperado " + ev + ", obtenido " + d.v);
                    boolean valido = familia.equals("validos");
                    // hasErrors solo con el 209 de la clase 12 (las demás mutaciones son semánticas)
                    boolean hasEsp = g.clase.equals("12");
                    if (!String.valueOf(hasEsp).equals(d.h)) dif.add("hasErrors esperado " + hasEsp + ", obtenido " + d.h);
                    if (d.tp != null && !String.valueOf(valido).equals(d.tp)) dif.add("TestProyecto esperado " + valido + ", obtenido " + d.tp);
                    if (!valido && ev.isEmpty()) dif.add("la mutación no produjo ningún 3xx esperado (generador)");
                }
            } else {
                // Dominio de preservación contra el oráculo de la tarea 2 (Property 5 en CI)
                familia = "preservacion";
                Programa p = preservados.get(a);
                if (d != null) {
                    CompararMulticonjunto("lexer/parser", p.parser, d.m, dif);
                    CompararMulticonjunto("evaluador", p.evaluador, d.v, dif);
                    if (!String.valueOf(p.HasErrors()).equals(d.h)) dif.add("oráculo: hasErrors esperado " + p.HasErrors() + ", obtenido " + d.h);
                    if (d.tp != null && !String.valueOf(p.Valido()).equals(d.tp)) dif.add("oráculo: TestProyecto esperado " + p.Valido() + ", obtenido " + d.tp);
                }
            }
            RegistrarProp(familia, semillaDe.get(a), clase, lineasDe.get(a), dif);
        }

        // Cobertura: clases de mutación, códigos esperados y elementos de los válidos
        Map<String, Integer> clases = new TreeMap<>();
        Map<String, Integer> codigos = new TreeMap<>();
        for (PV m : mutados.values()) {
            clases.merge(m.clase, 1, Integer::sum);
            for (String e : EsperadoEvaluador(m)) codigos.merge(e.substring(e.indexOf('|') + 7, e.indexOf('|') + 10), 1, Integer::sum);
        }
        Map<String, Integer> elementos = new TreeMap<>();
        for (PV v : validos.values()) {
            Set<String> vistos = new HashSet<>();
            for (LG l : v.lineas) {
                String t = l.f.trim();
                if (t.startsWith("FOR ")) vistos.add("FOR");
                else if (t.startsWith("IF ")) vistos.add("IF");
                else if (t.startsWith("GOTO ")) vistos.add("GOTO");
                else if (t.endsWith(":")) vistos.add("etiqueta");
                else if (l.adv != null) vistos.add("ALGOL");
                else if (t.contains(" := ")) vistos.add(":=");
            }
            Set<String> nombresEtiq = new HashSet<>(v.etiquetas);
            nombresEtiq.retainAll(v.tipos.keySet());
            if (!nombresEtiq.isEmpty()) vistos.add("homónimo");
            for (String k : vistos) elementos.merge(k, 1, Integer::sum);
        }
        System.out.println("Clases de mutación: " + clases);
        System.out.println("Mensajes 3xx esperados en las mutaciones: " + codigos);
        System.out.println("Válidos con cada elemento: " + elementos);
        System.out.println();
        System.out.println("===== RESUMEN propiedades =====");
        int total = 0;
        for (Map.Entry<String, int[]> e : cuentasProp.entrySet()) {
            total += e.getValue()[0];
            System.out.println("Familia " + e.getKey() + ": " + e.getValue()[0] + " semillas, FALLA: " + e.getValue()[1]
                    + ", OK: " + (e.getValue()[0] - e.getValue()[1]));
        }
        System.out.println("Validez con TestProyecto: " + conTP.size() + " fuentes (" + SEMILLAS_VALIDEZ + " por familia)");
        System.out.println("Total: " + total + ", FALLA: " + fallasProp + ", OK: " + (total - fallasProp));
        System.exit(fallasProp == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Hijo: TestProyecto por reflexión (sin a68g)
    // ------------------------------------------------------------------

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
        out.flush();
        System.exit(codigo);
    }

    // ------------------------------------------------------------------
    // Lanzamiento de hijos (R8), copiado y adaptado de PruebasSintaxis
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
        comando.add("PruebasSemantica");
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

    // Carpeta actual con guardias: nunca el repositorio, el diagnóstico ni el prototipo del diseño,
    // siempre dentro de %TEMP% y ninguna copia de proyecto.mingol fuera de la subcarpeta proyecto (R5)
    private static Path CarpetaSegura() throws IOException {
        Path carpeta = Paths.get("").toAbsolutePath().toRealPath();
        Path temp = CarpetaTemp();
        System.out.println("Carpeta de trabajo base: " + carpeta);
        if (Files.exists(carpeta.resolve("build.xml")) || carpeta.startsWith(RaizRepositorio())
                || carpeta.startsWith(temp.resolve(CARPETA_DIAG)) || carpeta.startsWith(temp.resolve(CARPETA_DIS))) {
            System.err.println("Se evita ejecutar en el repositorio, el diagnóstico o " + CARPETA_DIS
                    + ": usar $env:TEMP\\" + CARPETA_TRABAJO);
            System.exit(2);
        }
        if (!carpeta.startsWith(temp) || carpeta.equals(temp)) {
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
                if (Files.exists(raiz.resolve("proyecto.mingol"))) return raiz.toRealPath();
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

    // Mensajes de un -errores.txt como "línea:mensaje"
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

    // Resumen que separa controles, casos de C y m30
    private static void ImprimirResumen(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) if (r.startsWith("FALLA")) System.out.println(r);
        System.out.println("Controles: " + controles + ", OK: " + (controles - controlesQueFallan.size())
                + ", FALLA: " + controlesQueFallan.size() + (controlesQueFallan.isEmpty() ? "" : " " + controlesQueFallan));
        int fallanC = fallas - controlesQueFallan.size();
        System.out.println("Casos de C: " + casosC + ", FALLA: " + fallanC + ", OK: " + noReproducidos.size()
                + (noReproducidos.isEmpty() ? "" : " (bug no reproducido: " + noReproducidos + ")"));
        System.out.println("m30: " + resultadoM30);
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }
}
