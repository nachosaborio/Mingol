// Arnés de la spec mingol-criticos-fix (bugs A, B y C).
// Clase sin paquete, como RunTestProyecto. Se compila contra build\classes y se ejecuta
// desde una carpeta temporal ($env:TEMP\mingol-criticos): todos los archivos se crean ahí.
//
// Modos:
//   exploracion   -> tarea 1: Property 1 (Bug Condition) sobre c1, e2, e3, e7, e8, e9, main
//                    sin argumentos, la cadena de exec del bug C y el borde de mingolito.
//   hijo <caso>   -> interno: ejecuta un caso aislado (lo lanza el padre con límite de tiempo).
//   capturar [--sobrescribir] -> tarea 2: guarda en %TEMP%\mingol-criticos-golden\ el comportamiento
//                    del código SIN arreglar (c2, c4, c5, c6, e1, e4, e5, e6, casos de R6 y 3.7 y las
//                    semillas 1..200 sin flags), siempre con TestProyecto por reflexión, nunca con main.
//   preservacion  -> tarea 2 y 3.8: Property 4, compara todo contra lo capturado.
//   hijopres <archivo> <argumento> -> interno de capturar/preservacion (un caso en su carpeta).
//   unitarias     -> tarea 3.6: EvaluateIdentifier, RutaA68, BuscarA68g (incluido el rechazo de
//                    .bat/.cmd), EsScriptPorLotes, CarpetaAplicacion y el parser de e2, e3, e7, e8, e9.
//   arreglo       -> tarea 3.6: Fix Checking con generadores (programas defectuosos y nombres, semillas 1..200).
//   main          -> tarea 3.6: los 9 casos de main en hijos con entorno controlado (A68G y PATH).
//   hijoparser <caso> -> interno de unitarias: solo lexer + parser de <caso>.mingol.
//
// Seguridad (R7): Mingol.main con un programa válido solo se llama en el modo main, en un hijo con
// A68G y PATH controlados, y solo si existe EjecutarA68g (código arreglado). Ningún .bat/.cmd se
// ejecuta nunca: se crean de 0 bytes y solo se pasan como ruta.
import Evaluador.Entero; // Unitarias de EvaluateIdentifier
import Evaluador.Environment;
import Evaluador.Errado;
import Evaluador.Evaluator;
import Evaluador.Objeto;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.ExpressionStatement;
import Parser.Identifier; // Unitarias de EvaluateIdentifier
import Parser.Parser;
import Parser.Program;
import Parser.Statement;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset; // Salida nativa de Mingol.main en los hijos
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale; // Comparaciones sin depender del idioma (tarea 3.6)
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class PruebasCriticos {

    // Límite de tiempo por hijo (R8)
    private static final long LIMITE_SEGUNDOS = 10;

    // Resultados del modo actual: OK/FALLA por caso
    private static final List<String> resumen = new ArrayList<>();
    private static int fallas = 0;

    // Programas de prueba de bugfix.md. "true" = el archivo termina en \n; "false" = sin \n final
    private static final Map<String, String[]> PROGRAMAS = new LinkedHashMap<>();
    private static final Map<String, Boolean> CON_SALTO_FINAL = new LinkedHashMap<>();

    static {
        programa("c1", true, "BEGIN", "inicio:", "CHAR y := inicio;", "END");
        programa("c2", true, "BEGIN", "inicio:", "(inicio);", "END");
        programa("c4", true, "BEGIN", "INT x := 1;", "CHAR y := x;", "END");
        programa("c5", true, "BEGIN", "inicio:", "print ( (inicio) );", "END");
        programa("c6", true, "BEGIN", "CHAR y := end;", "END");
        programa("e1", false, "BEGIN", "INT x := 1;", "END");
        programa("e2", false, "BEGIN", "INT x := 1;", "IF (x) THEN");
        programa("e3", false, "BEGIN", "INT x := 1;", "IF (x) THEN", "SKIP;", "ELSE");
        programa("e4", false, "BEGIN", "RETURN 5", "END");
        programa("e5", false, "BEGIN", "INT x := 1;", "x = 3");
        programa("e6", false, "BEGIN", "FOR x FROM 1 BY 1 TO 3 DO");
        programa("e7", true, "BEGIN", "INT x := 1;", "IF (x) THEN", "", "");
        programa("e8", true, "BEGIN", "INT x := 1;", "IF (x) THEN", "SKIP;", "END");
        programa("e9", false, "BEGIN", "INT x := 1;", "IF (x) THEN", "print ( \"a\" );", "ELSE");
    }

    private static void programa(String id, boolean saltoFinal, String... lineas) {
        PROGRAMAS.put(id, lineas);
        CON_SALTO_FINAL.put(id, saltoFinal);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // Modos disponibles (los internos no se listan)
            System.err.println("Uso: PruebasCriticos <exploracion|capturar|preservacion|arreglo|unitarias|main>");
            System.exit(2);
        }
        switch (args[0]) {
            case "exploracion":
                Exploracion();
                break;
            case "hijo":
                Hijo(args.length > 1 ? args[1] : "");
                break;
            case "capturar":
                // Tarea 2: captura del comportamiento base (solo antes del arreglo)
                Capturar(args.length > 1 && args[1].equals("--sobrescribir"));
                break;
            case "preservacion":
                // Tarea 2 (control) y 3.8: compara contra la captura
                Preservacion();
                break;
            case "hijopres":
                // Interno: un caso de captura/preservación en la carpeta actual
                HijoPreservacion(args[1], args[2]);
                break;
            case "unitarias":
                // Tarea 3.6: pruebas unitarias por reflexión
                Unitarias();
                break;
            case "hijoparser":
                // Interno de unitarias: parser aislado con límite de tiempo
                HijoParser(args[1]);
                break;
            case "arreglo":
                // Tarea 3.6: Fix Checking con generadores
                Arreglo();
                break;
            case "main":
                // Tarea 3.6: main real en hijos con entorno controlado
                ModoMain();
                break;
            default:
                System.err.println("Modo desconocido: " + args[0]);
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Modo exploracion (tarea 1)
    // ------------------------------------------------------------------

    private static void Exploracion() throws Exception {
        Path carpeta = Paths.get("").toAbsolutePath();
        System.out.println("Carpeta de trabajo: " + carpeta);
        // Escribe c1…c6 y e1…e9 con extensión .mingol en minúsculas
        EscribirProgramas(carpeta);

        // Bug A
        CasoC1(carpeta);
        // Bug B: cuelgue en ParseBlock
        CasoBloqueAbierto(carpeta, "e2");
        CasoBloqueAbierto(carpeta, "e9");
        // Bug B: IF sin FI silencioso
        CasoIfSinFi(carpeta, "e3", "EOF");
        CasoIfSinFi(carpeta, "e7", "EOF");
        // e8 da EOF: ParseSkip con "SKIP;" consume el END de la línea siguiente (queda para la spec de sintaxis)
        CasoIfSinFi(carpeta, "e8", "EOF");
        // Bug C
        CasoMainSinArgumentos(carpeta);
        CasoCadenaExec("a&mkdir INYECTADO&b.mingol");
        CasoCadenaExec("mi prog.mingol");
        CasoCadenaExec("mingolito.mingol");
        CasoBordeMingolito(carpeta);
        CasoMetodosNuevos();

        // Resumen final
        System.out.println();
        System.out.println("===== RESUMEN exploracion =====");
        for (String r : resumen) {
            System.out.println(r);
        }
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }

    // c1: etiqueta usada como identificador (bug A)
    private static void CasoC1(Path carpeta) throws Exception {
        String id = "c1";
        BorrarSalidas(carpeta, id);
        ResultadoHijo r = LanzarHijo(id, carpeta);
        List<String> diferencias = new ArrayList<>();
        ChequeosComunes(r, carpeta, id, diferencias);
        // env.get("y") debe ser un Errado 303 con el identificador
        String envY = LineaConPrefijo(r.stdout, "ENV_Y|");
        if (envY == null || !envY.startsWith("ENV_Y|Errado|") || !envY.contains("303") || !envY.contains("inicio")) {
            diferencias.add("env.get(\"y\") esperado Errado con 303 e inicio; obtenido: " + envY);
        }
        Registrar(id + " (etiqueta como identificador)", diferencias, r);
    }

    // e2 y e9: ParseBlock llega a EOF (bug B)
    private static void CasoBloqueAbierto(Path carpeta, String id) throws Exception {
        BorrarSalidas(carpeta, id);
        ResultadoHijo r = LanzarHijo(id, carpeta);
        List<String> diferencias = new ArrayList<>();
        if (r.todo().contains("OutOfMemoryError")) {
            diferencias.add("OutOfMemoryError en el hijo");
        }
        ChequeosComunes(r, carpeta, id, diferencias);
        Chequear204Fi(carpeta, id, "EOF", diferencias);
        Registrar(id + " (EOF en ParseBlock)", diferencias, r);
    }

    // e3, e7, e8: IF sin FI (bug B)
    private static void CasoIfSinFi(Path carpeta, String id, String tokenEsperado) throws Exception {
        BorrarSalidas(carpeta, id);
        ResultadoHijo r = LanzarHijo(id, carpeta);
        List<String> diferencias = new ArrayList<>();
        ChequeosComunes(r, carpeta, id, diferencias);
        // GetErrors() del parser (impreso por el hijo antes de evaluar)
        String esperado = "Error 204: Se esperaba un token de tipo FI pero se obtuvo un " + tokenEsperado;
        List<String> errores = LineasConPrefijo(r.stdout, "GETERRORS|");
        boolean hay = false;
        for (String e : errores) {
            if (e.contains(esperado)) hay = true;
        }
        if (!hay) {
            diferencias.add("GetErrors() sin '" + esperado + "'; obtenido: " + errores);
        }
        Chequear204Fi(carpeta, id, tokenEsperado, diferencias);
        Registrar(id + " (IF sin FI, token " + tokenEsperado + ")", diferencias, r);
    }

    // main sin argumentos: seguro también en el código sin arreglar (R7)
    private static void CasoMainSinArgumentos(Path carpeta) throws Exception {
        // Carpeta propia y vacía para comprobar que no se crean archivos
        Path dir = carpeta.resolve("main-sin-args");
        Files.createDirectories(dir);
        VaciarCarpeta(dir);
        ResultadoHijo r = LanzarHijo("main", dir);
        List<String> diferencias = new ArrayList<>();
        if (!r.termino) diferencias.add("no terminó en " + LIMITE_SEGUNDOS + " s");
        if (r.codigo == 0) diferencias.add("código de salida 0 (se esperaba ≠ 0)");
        if (!r.stderr.contains("Uso:")) diferencias.add("stderr sin 'Uso:'");
        if (r.todo().contains("Exception")) diferencias.add("la salida contiene 'Exception': " + PrimeraLineaCon(r.todo(), "Exception"));
        if (r.todo().contains("at ")) diferencias.add("la salida contiene una traza ('at '): " + PrimeraLineaCon(r.todo(), "at "));
        String[] creados = dir.toFile().list();
        if (creados != null && creados.length > 0) diferencias.add("archivos creados: " + Arrays.toString(creados));
        Registrar("main sin argumentos", diferencias, r);
    }

    // Bug C (tarea 3.7): ya no se reconstruye la cadena vieja (CadenaExecOriginal). Se comprueba que
    // existe EjecutarA68g(String) y se arma, SIN ejecutar, la lista de argumentos que pasa a ProcessBuilder
    // (a68g + ruta absoluta de RutaA68). La invocación real con estos nombres se verifica en main, caso 2
    private static void CasoCadenaExec(String nombre) {
        List<String> diferencias = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        try {
            Class.forName("Mingol.Mingol").getDeclaredMethod("EjecutarA68g", String.class);
            tokens.add("a68g.exe");
            tokens.add(Paths.get((String) Llamar("RutaA68", new Class<?>[]{String.class}, nombre)).toAbsolutePath().toString());
        } catch (Throwable t) {
            diferencias.add("sin EjecutarA68g/RutaA68 (main sigue armando una cadena de shell): " + t);
        }
        String cadena = String.join(" | ", tokens);
        if (tokens.size() != 2 && diferencias.isEmpty()) diferencias.add("se esperaban 2 argumentos; hay " + tokens.size());
        String esperadoA68 = Paths.get(nombre).resolveSibling(SinUltimaExtension(nombre) + ".a68").toString();
        String esperadoAbs = Paths.get(esperadoA68).toAbsolutePath().toString();
        for (String t : tokens) {
            if (t.equalsIgnoreCase("cmd") || t.equalsIgnoreCase("/c") || t.toLowerCase().contains("start")) {
                diferencias.add("token de shell: [" + t + "]");
            }
        }
        if (!tokens.contains(esperadoA68) && !tokens.contains(esperadoAbs)) {
            diferencias.add("la ruta del .a68 [" + esperadoA68 + "] no es un único token");
        }
        if (cadena.contains("a68ito.a68")) {
            diferencias.add("se invoca a68ito.a68 en lugar de mingolito.a68");
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) sb.append('[').append(t).append("] ");
        System.out.println("  cadena exec (" + nombre + "): " + cadena);
        System.out.println("  tokens: " + sb.toString().trim());
        Registrar("cadena exec de main para '" + nombre + "'", diferencias, null);
    }

    // Borde: TestProyecto sobre mingolito.mingol (contenido de e5, con errores) debe escribir mingolito.a68
    private static void CasoBordeMingolito(Path carpeta) throws Exception {
        String id = "mingolito";
        Escribir(carpeta.resolve(id + ".mingol"), PROGRAMAS.get("e5"), CON_SALTO_FINAL.get("e5"));
        BorrarSalidas(carpeta, id);
        Files.deleteIfExists(carpeta.resolve("a68ito.a68"));
        ResultadoHijo r = LanzarHijo(id, carpeta);
        List<String> diferencias = new ArrayList<>();
        if (!r.termino) diferencias.add("no terminó en " + LIMITE_SEGUNDOS + " s");
        if (!Files.exists(carpeta.resolve("mingolito.a68"))) diferencias.add("no existe mingolito.a68");
        if (Files.exists(carpeta.resolve("a68ito.a68"))) diferencias.add("se escribió a68ito.a68");
        if (!r.stdout.contains("TestProyecto=false")) diferencias.add("TestProyecto distinto de false (e5 tiene errores)");
        Registrar("borde TestProyecto mingolito.mingol", diferencias, r);
    }

    // Por reflexión: los métodos nuevos del diseño deben existir en Mingol.Mingol (no aborta si faltan)
    private static void CasoMetodosNuevos() throws Exception {
        Set<String> nombres = new HashSet<>();
        for (Method m : Class.forName("Mingol.Mingol").getDeclaredMethods()) {
            nombres.add(m.getName());
        }
        for (String n : new String[]{"RutaA68", "BuscarA68g", "EsScriptPorLotes", "CarpetaAplicacion"}) {
            List<String> diferencias = new ArrayList<>();
            if (!nombres.contains(n)) diferencias.add("no existe Mingol." + n);
            Registrar("método " + n + " en Mingol.Mingol", diferencias, null);
        }
    }

    // ------------------------------------------------------------------
    // Modo hijo: ejecuta un único caso; el padre controla tiempo y memoria
    // ------------------------------------------------------------------

    private static void Hijo(String caso) throws Exception {
        if (caso.equals("main")) {
            // Único uso de main en este arnés: SIN argumentos (R7). La excepción, si la hay, se propaga
            Class.forName("Mingol.Mingol").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            return;
        }
        if (caso.isEmpty()) {
            System.err.println("hijo: falta el caso");
            System.exit(2);
        }
        String archivo = caso + ".mingol";
        boolean huboExcepcion = false;

        // e3, e7, e8: imprime GetErrors() del parser antes de evaluar
        if (caso.equals("e3") || caso.equals("e7") || caso.equals("e8")) {
            Parser parser = new Parser(new Lexer(LeerFuente(archivo)));
            parser.ParseProgram();
            for (String e : parser.GetErrors()) {
                System.out.println("GETERRORS|" + e);
            }
            System.out.println("GETERRORS_TOTAL|" + parser.GetErrors().size());
        }

        // TestProyecto por reflexión, igual que RunTestProyecto
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            Object ok = m.invoke(null, archivo);
            System.out.println("TestProyecto=" + ok);
        } catch (InvocationTargetException e) {
            huboExcepcion = true;
            System.out.println("EXCEPCION|" + e.getCause());
            e.getCause().printStackTrace();
        }

        // c1: evalúa por separado con un env propio para inspeccionar env.get("y")
        if (caso.equals("c1")) {
            Environment env = new Environment();
            try {
                Program program = new Parser(new Lexer(LeerFuente(archivo))).ParseProgram();
                Evaluator.Evaluate(program, env);
            } catch (Throwable t) {
                huboExcepcion = true;
                System.out.println("EXCEPCION_EVAL|" + t);
                t.printStackTrace();
            }
            Object y = env.get("y");
            if (y instanceof Errado) {
                System.out.println("ENV_Y|Errado|" + ((Errado) y).Inspect());
            } else {
                System.out.println("ENV_Y|" + (y == null ? "null" : y.getClass().getSimpleName()) + "|");
            }
        }
        System.exit(huboExcepcion ? 3 : 0);
    }

    // Lee el fuente igual que TestProyecto: cada línea seguida de "\n"
    private static String LeerFuente(String archivo) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                sb.append(linea).append("\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Tarea 2: casos de preservación (Property 4) y generador con semillas fijas
    // ------------------------------------------------------------------

    // Hijos de captura/preservación en paralelo (cada uno en su carpeta y con su log)
    private static final int HILOS = 4;
    // Salida completa del hijo de preservación (sin recortar a 64 KB)
    private static final int MAX_META = 16 * 1024 * 1024;

    // Un caso de preservación: fuente, archivo en disco, argumento que recibe TestProyecto y flags
    private static class CasoPres {
        String id;
        String archivo;
        String argumento;
        String[] lineas;
        boolean saltoFinal;
        boolean generado;
        // Flags de isBugCondition (diseño, Preservation Checking)
        boolean usaEtiquetaComoIdentificador;
        boolean ifSinFi;
        boolean terminaEnBloqueAbierto;

        boolean sinFlags() {
            return !usaEtiquetaComoIdentificador && !ifSinFi && !terminaEnBloqueAbierto;
        }

        byte[] bytes() {
            return (String.join("\n", lineas) + (saltoFinal ? "\n" : "")).getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static CasoPres CasoFijo(String id, String archivo, String argumento, boolean saltoFinal, String... lineas) {
        CasoPres c = new CasoPres();
        c.id = id;
        c.archivo = archivo;
        c.argumento = argumento;
        c.lineas = lineas;
        c.saltoFinal = saltoFinal;
        return c;
    }

    // c2, c4, c5, c6, e1, e4, e5, e6 de bugfix.md, más los casos de R6 (3.1) y de 3.7
    private static List<CasoPres> CasosFijos() {
        List<CasoPres> casos = new ArrayList<>();
        for (String id : new String[]{"c2", "c4", "c5", "c6", "e1", "e4", "e5", "e6"}) {
            casos.add(CasoFijo(id, id + ".mingol", id + ".mingol", CON_SALTO_FINAL.get(id), PROGRAMAS.get(id)));
        }
        // R6, arreglo 1: "Linea N" con el número real en un programa de varias líneas (201 en las líneas 3 y 5)
        casos.add(CasoFijo("r1", "r1.mingol", "r1.mingol", true,
                "BEGIN", "INT a := 1;", "INT b := 2.5;", "", "INT c := \"x\";", "END"));
        // R6, arreglo 2: un EOL por línea (líneas vacías, comentario y blancos redundantes)
        casos.add(CasoFijo("r2", "r2.mingol", "r2.mingol", false,
                "BEGIN", "", "CO nota CO", "  INT a := 1;  ", "", "END"));
        // R6, arreglo 3: 12345678901 registrado como error sin excepción
        casos.add(CasoFijo("r3", "r3.mingol", "r3.mingol", true,
                "BEGIN", "INT x := 12345678901;", "END"));
        // 3.7: t1.mingol -> t1.a68 (programa válido, solo por TestProyecto)
        casos.add(CasoFijo("t1", "t1.mingol", "t1.mingol", false, PROGRAMAS.get("e1")));
        // 3.7: argumento "t1" sin extensión -> lee t1.mingol (normalización copiada de main, sin llamar a main, R7)
        casos.add(CasoFijo("t1sinext", "t1.mingol", "t1", false, PROGRAMAS.get("e1")));
        return casos;
    }

    // Nombres de variables y de etiquetas disjuntos, en minúsculas y no reservados
    private static final String[] VARS = {"a", "b", "c", "x", "y", "total", "suma", "dato"};
    private static final String[] ETIQUETAS = {"inicio", "fin", "salto", "otra"};
    private static final String[] RELACIONALES = {"=", "/=", "<", ">", "<=", ">=", "EQ", "NE", "LT", "GT", "LE", "GE"};

    // Generador de programas (diseño, Preservation Checking). Evita a propósito los caminos que
    // cuelgan o abortan sin marcarlos: cuerpos de IF de una sola línea sin SKIP; ni líneas vacías,
    // "/" seguido siempre de un literal no nulo, ninguna línea empieza con un operador ni con "(".
    private static CasoPres Generar(int semilla) {
        Random r = new Random(semilla);
        CasoPres c = new CasoPres();
        c.id = String.format("g%03d", semilla);
        c.archivo = c.id + ".mingol";
        c.argumento = c.archivo;
        c.generado = true;
        List<String> l = new ArrayList<>();
        List<String> etiquetasDefinidas = new ArrayList<>();

        // Antes de BEGIN: líneas vacías o comentarios
        int previas = r.nextInt(3);
        for (int i = 0; i < previas; i++) {
            if (r.nextBoolean()) l.add(""); else AgregarComentario(r, l);
        }
        if (r.nextInt(20) != 0) l.add(Sangria(r) + "BEGIN");

        // Sección de declaraciones
        int declaraciones = r.nextInt(5);
        for (int i = 0; i < declaraciones; i++) l.add(Sangria(r) + Declaracion(r));

        // Sección de comandos
        int comandos = 1 + r.nextInt(6);
        for (int i = 0; i < comandos; i++) {
            switch (r.nextInt(14)) {
                case 0:
                    l.add(Declaracion(r));
                    break;
                case 1:
                    l.add(Sangria(r) + Elegir(r, VARS) + " := " + Expr(r, 0) + ";");
                    break;
                case 2:
                    l.add(Sangria(r) + "print ( (" + ListaPrint(r) + ") );");
                    break;
                case 3:
                    l.add(Sangria(r) + (r.nextBoolean() ? "print ( \"hola\" );" : "print ( newline );"));
                    break;
                case 4:
                    l.add(Sangria(r) + "GOTO " + Elegir(r, ETIQUETAS) + ";");
                    break;
                case 5: {
                    String e = Elegir(r, ETIQUETAS);
                    etiquetasDefinidas.add(e);
                    l.add(e + ":");
                    break;
                }
                case 6:
                case 7:
                    AgregarIf(r, l, CondicionNormal(r), true);
                    break;
                case 8:
                    AgregarFor(r, l);
                    break;
                case 9:
                    l.add("RETURN " + r.nextInt(10) + (r.nextBoolean() ? ";" : ""));
                    break;
                case 10:
                    l.add("LONG INT " + Elegir(r, VARS) + ";");
                    break;
                case 11:
                    l.add(Sangria(r) + (r.nextBoolean() ? "SKIP" : "SKIP;"));
                    break;
                case 12:
                    AgregarComentario(r, l);
                    break;
                default:
                    l.add("");
                    break;
            }
        }

        // Caminos defectuosos, marcados con su flag (no entran en la captura)
        int defecto = r.nextInt(8);
        if (defecto == 0) {
            // Etiqueta usada como identificador en una asignación o en una condición
            c.usaEtiquetaComoIdentificador = true;
            String e = etiquetasDefinidas.isEmpty() ? Elegir(r, ETIQUETAS) : etiquetasDefinidas.get(0);
            if (etiquetasDefinidas.isEmpty()) l.add(e + ":");
            if (r.nextBoolean()) l.add("CHAR w := " + e + ";"); else AgregarIf(r, l, e, true);
        } else if (defecto == 1) {
            // IF sin FI seguido de otra línea, o con un cuerpo de dos líneas
            c.ifSinFi = true;
            if (r.nextBoolean()) {
                AgregarIf(r, l, CondicionNormal(r), false);
            } else {
                l.add("IF ( " + CondicionNormal(r) + " ) THEN");
                l.add("  print ( \"uno\" );");
                l.add("  print ( \"dos\" );");
                l.add("FI;");
            }
        }
        if (defecto == 2) {
            // El archivo termina con un bloque abierto (después de THEN o de ELSE)
            c.terminaEnBloqueAbierto = true;
            l.add("IF ( " + CondicionNormal(r) + " ) THEN");
            if (r.nextBoolean()) {
                l.add("  print ( \"a\" );");
                l.add("ELSE");
            }
        } else {
            if (r.nextInt(20) != 0) l.add(Sangria(r) + "END");
            int finales = r.nextInt(3);
            for (int i = 0; i < finales; i++) {
                if (r.nextBoolean()) l.add(""); else AgregarComentario(r, l);
            }
        }
        c.lineas = l.toArray(new String[0]);
        c.saltoFinal = r.nextBoolean();
        return c;
    }

    private static String Elegir(Random r, String[] opciones) {
        return opciones[r.nextInt(opciones.length)];
    }

    // Blancos redundantes al inicio (REQ-LINE-06)
    private static String Sangria(Random r) {
        return r.nextInt(4) == 0 ? "  " : "";
    }

    // INT/REAL/CHAR con valor inicial (la forma que acepta el código actual) y algunas variantes con error
    private static String Declaracion(Random r) {
        String v = Elegir(r, VARS);
        switch (r.nextInt(8)) {
            case 0:
                return "INT " + v + " := " + Expr(r, 0) + ";";
            case 1:
                return "INT " + v + ";";
            case 2:
                return "REAL " + v + " := " + r.nextInt(100) + "." + r.nextInt(10) + ";";
            case 3:
                return "CHAR " + v + " := \"" + (char) ('a' + r.nextInt(26)) + "\";";
            case 4:
                return "CHAR " + v + " := " + Elegir(r, VARS) + ";";
            case 5:
                return "INT " + v + " := -" + (1 + r.nextInt(9)) + ";";
            default:
                return "INT " + v + " := " + r.nextInt(100) + ";";
        }
    }

    // Expresión aritmética sin etiquetas; "/" siempre seguido de un literal entre 1 y 9
    private static String Expr(Random r, int profundidad) {
        StringBuilder sb = new StringBuilder(Termino(r, profundidad));
        int ops = r.nextInt(3);
        for (int i = 0; i < ops; i++) {
            String op = Elegir(r, new String[]{"+", "-", "*", "/"});
            sb.append(' ').append(op).append(' ');
            sb.append(op.equals("/") ? String.valueOf(1 + r.nextInt(9)) : Termino(r, profundidad));
        }
        return sb.toString();
    }

    private static String Termino(Random r, int profundidad) {
        switch (r.nextInt(5)) {
            case 0:
            case 1:
                return Elegir(r, VARS);
            case 2:
                return String.valueOf(r.nextInt(100));
            case 3:
                return r.nextInt(100) + "." + r.nextInt(10);
            default:
                return profundidad < 2 ? "( " + Expr(r, profundidad + 1) + " )" : String.valueOf(1 + r.nextInt(9));
        }
    }

    private static String CondicionNormal(Random r) {
        if (r.nextInt(4) == 0) return Expr(r, 1);
        return Expr(r, 1) + " " + Elegir(r, RELACIONALES) + " " + Expr(r, 1);
    }

    // Cuerpo de una sola línea que deja a ParseBlock en el EOL (sin SKIP; que consumiría la línea siguiente)
    private static String CuerpoIf(Random r) {
        switch (r.nextInt(5)) {
            case 0:
                return "  print ( \"si\" );";
            case 1:
                return "  print ( (" + Elegir(r, VARS) + ") );";
            case 2:
                return "  print ( newline );";
            case 3:
                return "  SKIP";
            default:
                return "  GOTO " + Elegir(r, ETIQUETAS) + ";";
        }
    }

    // IF con o sin ELSE; con conFi=false se omite el FI (camino defectuoso, solo con flag)
    private static void AgregarIf(Random r, List<String> l, String condicion, boolean conFi) {
        l.add(Sangria(r) + "IF ( " + condicion + " ) THEN");
        l.add(CuerpoIf(r));
        if (r.nextBoolean()) {
            l.add("ELSE");
            l.add(CuerpoIf(r));
        }
        if (conFi) l.add(r.nextBoolean() ? "FI;" : "FI");
    }

    // FOR con DO al final de la línea o en la línea siguiente, cuerpo de una línea y OD
    private static void AgregarFor(Random r, List<String> l) {
        String limite = r.nextBoolean() ? String.valueOf(1 + r.nextInt(9)) : Elegir(r, VARS);
        String cabecera = "FOR " + Elegir(r, VARS) + " FROM " + (1 + r.nextInt(3)) + " BY 1 TO " + limite;
        if (r.nextBoolean()) {
            l.add(cabecera + " DO");
        } else {
            l.add(cabecera);
            l.add("DO");
        }
        l.add("  print ( \"vuelta\" );");
        l.add(r.nextBoolean() ? "OD;" : "OD");
    }

    // Las tres formas de comentario, en una línea o en varias
    private static void AgregarComentario(Random r, List<String> l) {
        String forma = Elegir(r, new String[]{"CO", "COMMENT", "#"});
        if (r.nextBoolean()) {
            l.add(forma + " nota " + forma);
        } else {
            l.add(forma);
            l.add("texto libre");
            l.add(forma);
        }
    }

    // print de lista: variables y, a veces, una etiqueta (como c5, se acepta hoy)
    private static String ListaPrint(Random r) {
        int n = 1 + r.nextInt(3);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(r.nextInt(6) == 0 ? Elegir(r, ETIQUETAS) : Elegir(r, VARS));
        return String.join(", ", ids);
    }

    // ------------------------------------------------------------------
    // Modo capturar (tarea 2, código SIN arreglar)
    // ------------------------------------------------------------------

    private static Path CarpetaGolden() {
        String temp = System.getenv("TEMP");
        Path base = (temp != null && !temp.isBlank()) ? Paths.get(temp) : Paths.get(System.getProperty("java.io.tmpdir"));
        return base.resolve("mingol-criticos-golden");
    }

    private static void Capturar(boolean sobrescribir) throws Exception {
        Path golden = CarpetaGolden();
        // La captura es la referencia de la tarea 3.8: no se pisa sin pedirlo explícitamente
        if (Files.exists(golden)) {
            if (!sobrescribir) {
                System.err.println("Ya existe la captura " + golden + ". No se regenera (usar --sobrescribir solo antes del arreglo).");
                System.exit(2);
            }
            BorrarArbol(golden);
        }
        Files.createDirectories(golden);
        Path trabajo = Paths.get("").toAbsolutePath().resolve("captura");
        BorrarArbol(trabajo);
        System.out.println("Captura en: " + golden);
        System.out.println("Carpeta de trabajo: " + trabajo);

        // Casos fijos y semillas 1..200; solo los que no tienen flags se capturan
        List<CasoPres> casos = new ArrayList<>(CasosFijos());
        int conEtiqueta = 0, conIfSinFi = 0, conBloque = 0, generadosConFlags = 0;
        for (int s = 1; s <= 200; s++) {
            CasoPres c = Generar(s);
            if (c.sinFlags()) {
                casos.add(c);
            } else {
                generadosConFlags++;
                if (c.usaEtiquetaComoIdentificador) conEtiqueta++;
                if (c.ifSinFi) conIfSinFi++;
                if (c.terminaEnBloqueAbierto) conBloque++;
            }
        }

        Map<String, ResultadoHijo> resultados = CorrerCasos(casos, trabajo, "cap-");

        StringBuilder indice = new StringBuilder();
        Map<String, List<String>> metas = new LinkedHashMap<>();
        int capturados = 0, conEvaluador = 0, validos = 0;
        for (CasoPres c : casos) {
            ResultadoHijo r = resultados.get(c.id);
            Path dir = trabajo.resolve(c.id);
            List<String> diferencias = new ArrayList<>();
            ProblemasDeEjecucion(r, diferencias);
            if (!diferencias.isEmpty()) {
                // Un programa sin flags que cuelga o lanza: el generador lo clasificó mal
                String etiqueta = c.generado ? c.id + " mal clasificado (sin flags pero no termina o lanza)" : c.id + " (caso preservable)";
                Registrar(etiqueta, diferencias, r);
                continue;
            }
            // Guarda fuente, salida del hijo y todos los archivos generados en la carpeta del caso
            Path destino = golden.resolve(c.id);
            Files.createDirectories(destino.resolve("archivos"));
            Files.write(destino.resolve("fuente.bin"), c.bytes());
            List<String> meta = LineasMeta(r.stdout);
            Files.write(destino.resolve("meta.txt"), meta, StandardCharsets.UTF_8);
            for (String nombre : ArchivosDe(dir)) {
                Files.copy(dir.resolve(nombre), destino.resolve("archivos").resolve(nombre));
            }
            indice.append(c.id).append('\t').append(c.archivo).append('\t').append(c.argumento)
                    .append('\t').append(c.generado ? "generado" : "fijo").append('\n');
            metas.put(c.id, meta);
            capturados++;
            if (meta.stream().anyMatch(m -> m.startsWith("INSPECT|"))) conEvaluador++;
            if (meta.contains("TESTPROYECTO|true")) validos++;
            Registrar("captura " + c.id, diferencias, r);
        }
        Files.write(golden.resolve("indice.txt"), indice.toString().getBytes(StandardCharsets.UTF_8));

        // Observaciones del código sin arreglar (tarea 2)
        Observaciones(metas, golden);

        System.out.println();
        System.out.println("Semillas 1..200: " + (200 - generadosConFlags) + " sin flags, " + generadosConFlags
                + " con flags (usaEtiquetaComoIdentificador=" + conEtiqueta + ", ifSinFi=" + conIfSinFi
                + ", terminaEnBloqueAbierto=" + conBloque + "), no capturadas");
        System.out.println("Capturados: " + capturados + " (evaluador corre en " + conEvaluador
                + ", TestProyecto=true en " + validos + ")");
        ImprimirResumen("capturar");
    }

    // Comprueba lo que la tarea 2 pide observar en el código sin arreglar
    private static void Observaciones(Map<String, List<String>> metas, Path golden) throws IOException {
        Observar("e1 válido", metas, "e1", "TESTPROYECTO|true", "GETERRORS_TOTAL|0");
        Observar("e5 con 204 y 203 en la línea 3", metas, "e5", "TESTPROYECTO|false",
                "~GETERRORS|\tLinea 3 Error 204", "~GETERRORS|\tLinea 3 Error 203");
        Observar("e6 termina", metas, "e6");
        Observar("c4 se acepta", metas, "c4", "TESTPROYECTO|true");
        Observar("c5 se acepta", metas, "c5", "TESTPROYECTO|true");
        Observar("e4 advertencia de ALGOL y TestProyecto=true", metas, "e4", "TESTPROYECTO|true", "~GETERRORS|\tLinea 2 ADVERTENCIA");
        // "*" = alguna línea contiene el texto (el Call está en medio de la línea NODOS)
        Observar("c2 (inicio); como Call", metas, "c2", "TESTPROYECTO|true", "*ExpressionStatement:Call");
        Observar("r1 Linea N real (201 en las líneas 3 y 5)", metas, "r1",
                "~GETERRORS|\tLinea 3 Error 201", "~GETERRORS|\tLinea 5 Error 201");
        Observar("r2 un EOL por línea", metas, "r2", "EOLS|6");
        Observar("r3 12345678901 como error controlado", metas, "r3", "TESTPROYECTO|false",
                "~GETERRORS|\tLinea 2 Error 203: conversion invalida a entero: 12345678901");
        Observar("t1.mingol -> t1.a68", metas, "t1", "RUTA|t1.mingol", "TESTPROYECTO|true");
        Observar("t1 sin extensión lee t1.mingol", metas, "t1sinext", "RUTA|t1.mingol", "TESTPROYECTO|true");

        // c6: no aborta y el -errores.txt no muestra ningún mensaje (solo las líneas numeradas)
        List<String> diferencias = new ArrayList<>();
        if (!metas.containsKey("c6")) {
            diferencias.add("c6 no se capturó");
        } else {
            if (!metas.get("c6").contains("TESTPROYECTO|false")) diferencias.add("TestProyecto distinto de false");
            Path errores = golden.resolve("c6").resolve("archivos").resolve("c6-errores.txt");
            List<String> lineas = Files.exists(errores) ? Files.readAllLines(errores, StandardCharsets.UTF_8) : null;
            if (lineas == null || lineas.size() != PROGRAMAS.get("c6").length) diferencias.add("c6-errores.txt con mensajes: " + lineas);
        }
        Registrar("observación c6 sin mensaje visible", diferencias, null);

        // .a68 en la misma carpeta con el nombre esperado (3.7)
        for (String id : new String[]{"t1", "t1sinext"}) {
            List<String> dif = new ArrayList<>();
            Path a68 = golden.resolve(id).resolve("archivos").resolve("t1.a68");
            if (!Files.exists(a68)) dif.add("no se escribió t1.a68");
            else if (!Arrays.equals(Files.readAllBytes(a68), (String.join("\n", PROGRAMAS.get("e1")) + "\n").getBytes(StandardCharsets.US_ASCII)))
                dif.add("t1.a68 no es copia línea por línea del fuente");
            if (!Files.exists(golden.resolve(id).resolve("archivos").resolve("t1-errores.txt"))) dif.add("no se escribió t1-errores.txt");
            Registrar("observación " + id + " archivos t1.a68 y t1-errores.txt", dif, null);
        }
    }

    // Cada esperado es una línea exacta de meta, "~prefijo" (alguna línea empieza con) o "*texto" (alguna contiene)
    private static void Observar(String nombre, Map<String, List<String>> metas, String id, String... esperados) {
        List<String> diferencias = new ArrayList<>();
        List<String> meta = metas.get(id);
        if (meta == null) {
            diferencias.add(id + " no se capturó");
        } else {
            for (String e : esperados) {
                boolean ok;
                if (e.startsWith("~")) {
                    String pref = e.substring(1);
                    ok = meta.stream().anyMatch(m -> m.startsWith(pref));
                } else if (e.startsWith("*")) {
                    // Contiene en cualquier posición
                    String parte = e.substring(1);
                    ok = meta.stream().anyMatch(m -> m.contains(parte));
                } else {
                    ok = meta.contains(e);
                }
                if (!ok) diferencias.add("falta '" + e + "' en la salida de " + id);
            }
        }
        Registrar("observación " + nombre, diferencias, null);
    }

    // ------------------------------------------------------------------
    // Modo preservacion (Property 4)
    // ------------------------------------------------------------------

    private static void Preservacion() throws Exception {
        Path golden = CarpetaGolden();
        Path indice = golden.resolve("indice.txt");
        if (!Files.exists(indice)) {
            System.err.println("No existe la captura " + indice + ": correr antes 'capturar' en el código sin arreglar.");
            System.exit(2);
        }
        Path trabajo = Paths.get("").toAbsolutePath().resolve("preservacion");
        BorrarArbol(trabajo);
        System.out.println("Captura: " + golden);
        System.out.println("Carpeta de trabajo: " + trabajo);

        // Reconstruye los casos desde la captura (fuente exacto guardado)
        List<CasoPres> casos = new ArrayList<>();
        Map<String, byte[]> fuentes = new LinkedHashMap<>();
        for (String linea : Files.readAllLines(indice, StandardCharsets.UTF_8)) {
            if (linea.isBlank()) continue;
            String[] p = linea.split("\t");
            CasoPres c = new CasoPres();
            c.id = p[0];
            c.archivo = p[1];
            c.argumento = p[2];
            c.generado = p[3].equals("generado");
            casos.add(c);
            fuentes.put(c.id, Files.readAllBytes(golden.resolve(c.id).resolve("fuente.bin")));
        }

        Map<String, ResultadoHijo> resultados = CorrerCasos(casos, trabajo, "pres-", fuentes);

        for (CasoPres c : casos) {
            ResultadoHijo r = resultados.get(c.id);
            List<String> diferencias = new ArrayList<>();
            ProblemasDeEjecucion(r, diferencias);
            // El generador debe seguir siendo determinista (control del arnés)
            if (c.generado) {
                int semilla = Integer.parseInt(c.id.substring(1));
                if (!Arrays.equals(Generar(semilla).bytes(), fuentes.get(c.id))) {
                    diferencias.add("el generador ya no produce el mismo fuente para la semilla " + semilla);
                }
            }
            // GetErrors en orden, AST, TestProyecto, Inspect, tokens y ruta: línea por línea
            List<String> esperado = Files.readAllLines(golden.resolve(c.id).resolve("meta.txt"), StandardCharsets.UTF_8);
            List<String> obtenido = LineasMeta(r.stdout);
            CompararMeta(esperado, obtenido, diferencias);
            // -errores.txt y .a68 (y cualquier otro archivo) con el mismo nombre y los mismos bytes
            Path dirEsperado = golden.resolve(c.id).resolve("archivos");
            Path dirObtenido = trabajo.resolve(c.id);
            List<String> nombresEsperados = ArchivosDe(dirEsperado);
            List<String> nombresObtenidos = ArchivosDe(dirObtenido);
            if (!nombresEsperados.equals(nombresObtenidos)) {
                diferencias.add("archivos distintos: esperado " + nombresEsperados + ", obtenido " + nombresObtenidos);
            }
            for (String n : nombresEsperados) {
                if (nombresObtenidos.contains(n)
                        && !Arrays.equals(Files.readAllBytes(dirEsperado.resolve(n)), Files.readAllBytes(dirObtenido.resolve(n)))) {
                    diferencias.add(n + " no coincide byte a byte");
                }
            }
            Registrar("preservación " + c.id, diferencias, r);
        }
        ImprimirResumen("preservacion");
    }

    private static void CompararMeta(List<String> esperado, List<String> obtenido, List<String> diferencias) {
        int n = Math.min(esperado.size(), obtenido.size());
        for (int i = 0; i < n; i++) {
            if (!esperado.get(i).equals(obtenido.get(i))) {
                diferencias.add("salida distinta en la línea " + (i + 1) + ": esperado [" + esperado.get(i) + "], obtenido [" + obtenido.get(i) + "]");
                return;
            }
        }
        if (esperado.size() != obtenido.size()) {
            diferencias.add("cantidad de líneas de salida: esperado " + esperado.size() + ", obtenido " + obtenido.size());
        }
    }

    // ------------------------------------------------------------------
    // Ejecución de casos de preservación en hijos (R8), en paralelo
    // ------------------------------------------------------------------

    private static Map<String, ResultadoHijo> CorrerCasos(List<CasoPres> casos, Path trabajo, String prefijoLog) throws Exception {
        Map<String, byte[]> fuentes = new LinkedHashMap<>();
        for (CasoPres c : casos) fuentes.put(c.id, c.bytes());
        return CorrerCasos(casos, trabajo, prefijoLog, fuentes);
    }

    private static Map<String, ResultadoHijo> CorrerCasos(List<CasoPres> casos, Path trabajo, String prefijoLog,
            Map<String, byte[]> fuentes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        Map<String, Future<ResultadoHijo>> futuros = new LinkedHashMap<>();
        for (CasoPres c : casos) {
            // Cada caso en su propia carpeta: los archivos generados no se mezclan
            Path dir = trabajo.resolve(c.id);
            Files.createDirectories(dir);
            Files.write(dir.resolve(c.archivo), fuentes.get(c.id));
            futuros.put(c.id, pool.submit(() ->
                    LanzarHijo(Arrays.asList("hijopres", c.archivo, c.argumento), dir, prefijoLog + c.id, MAX_META)));
        }
        Map<String, ResultadoHijo> resultados = new LinkedHashMap<>();
        for (Map.Entry<String, Future<ResultadoHijo>> e : futuros.entrySet()) {
            resultados.put(e.getKey(), e.getValue().get());
        }
        pool.shutdown();
        return resultados;
    }

    // No terminó, salió con código ≠ 0 o hubo una excepción/Error en el compilador
    private static void ProblemasDeEjecucion(ResultadoHijo r, List<String> diferencias) {
        if (!r.termino) diferencias.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        else if (r.codigo != 0) diferencias.add("código de salida del hijo " + r.codigo);
        String exc = LineaConPrefijo(r.stdout, "EXCEPCION");
        if (exc != null) diferencias.add(exc);
        String err = PrimeraLineaCon(r.stderr, "Exception");
        if (err == null) err = PrimeraLineaCon(r.stderr, "Error");
        if (err != null) diferencias.add("stderr: " + err.trim());
    }

    // Líneas de la salida del hijo que forman la "huella" del caso
    private static List<String> LineasMeta(String stdout) {
        List<String> meta = new ArrayList<>();
        for (String l : stdout.split("\\R")) {
            if (!l.isEmpty()) meta.add(l);
        }
        return meta;
    }

    // Nombres de los archivos de una carpeta, ordenados
    private static List<String> ArchivosDe(Path dir) {
        List<String> nombres = new ArrayList<>();
        File[] hijos = dir.toFile().listFiles();
        if (hijos != null) {
            for (File f : hijos) if (f.isFile()) nombres.add(f.getName());
        }
        java.util.Collections.sort(nombres);
        return nombres;
    }

    // Borra una carpeta de trabajo del arnés; se niega a borrar fuera de %TEMP%
    private static void BorrarArbol(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Path temp = CarpetaGolden().getParent().toAbsolutePath().normalize();
        Path abs = dir.toAbsolutePath().normalize();
        if (!abs.startsWith(temp) || abs.equals(temp)) {
            throw new IOException("Se evita borrar fuera de %TEMP%: " + abs);
        }
        try (java.util.stream.Stream<Path> s = Files.walk(abs)) {
            List<Path> todos = new ArrayList<>();
            s.forEach(todos::add);
            java.util.Collections.reverse(todos);
            for (Path p : todos) Files.delete(p);
        }
    }

    private static void ImprimirResumen(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) {
            if (r.startsWith("FALLA")) System.out.println(r);
        }
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Modo hijopres: huella completa de un caso, por reflexión con TestProyecto (nunca main, R7)
    // ------------------------------------------------------------------

    private static void HijoPreservacion(String archivo, String argumento) throws Exception {
        // Salida en UTF-8 para que el padre la lea sin depender de la página de códigos
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        boolean huboExcepcion = false;
        String ruta = NormalizarComoMain(argumento);
        out.println("RUTA|" + ruta);
        String fuente = LeerFuente(ruta);

        // Tokens y cantidad de EOL (R6, arreglo 2)
        Lexer lexer = new Lexer(fuente);
        StringBuilder tokens = new StringBuilder();
        int eols = 0;
        for (int i = 0; i < 200000; i++) {
            Token t = lexer.NextToken();
            if (t.getTokenType() == MingolToken.EOL) eols++;
            tokens.append(t.getTokenType()).append('(').append(t.getLiteral()).append(") ");
            if (t.getTokenType() == MingolToken.EOF) break;
        }
        out.println("EOLS|" + eols);
        out.println("TOKENS|" + Escapar(tokens.toString().trim()));

        // Parser por separado: GetErrors() en orden, nodos y AST
        Parser parser = new Parser(new Lexer(fuente));
        Program program = parser.ParseProgram();
        for (String e : parser.GetErrors()) out.println("GETERRORS|" + Escapar(e));
        out.println("GETERRORS_TOTAL|" + parser.GetErrors().size());
        out.println("PARSER_HASERRORS|" + parser.hasErrors);
        out.println("NODOS|" + Nodos(program));
        out.println("AST|" + Escapar(AstSeguro(program)));

        // TestProyecto por reflexión: escribe el -errores.txt y el .a68 en esta carpeta
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            out.println("TESTPROYECTO|" + m.invoke(null, ruta));
        } catch (InvocationTargetException e) {
            huboExcepcion = true;
            out.println("EXCEPCION|" + e.getCause());
            e.getCause().printStackTrace();
        }

        // Inspect() del evaluador cuando corre (TestProyecto lo ejecuta solo sin mensajes del parser).
        // Va después de TestProyecto para no alterar Evaluator.hasErrors (estático) antes de tiempo.
        if (parser.GetErrors().isEmpty()) {
            try {
                Program otro = new Parser(new Lexer(fuente)).ParseProgram();
                Objeto res = Evaluator.Evaluate(otro, new Environment());
                out.println("INSPECT|" + (res == null ? "null" : res.getClass().getSimpleName() + "|" + Escapar(res.Inspect())));
            } catch (Throwable t) {
                huboExcepcion = true;
                out.println("EXCEPCION_EVAL|" + t);
                t.printStackTrace();
            }
        } else {
            out.println("INSPECT_NO_CORRE");
        }
        out.flush();
        System.exit(huboExcepcion ? 3 : 0);
    }

    // Copia de la normalización de extensión de Mingol.main (no se llama a main, R7)
    private static String NormalizarComoMain(String path) {
        String parts[] = path.split("\\.");
        if (parts.length == 1) {
            path += ".mingol";
        }
        return path;
    }

    // Clase de cada sentencia y de su expresión (p. ej. ExpressionStatement:Call)
    private static String Nodos(Program program) {
        StringBuilder sb = new StringBuilder();
        for (Statement s : program.statements) {
            sb.append(s == null ? "null" : s.getClass().getSimpleName());
            if (s instanceof ExpressionStatement) {
                Object e = ((ExpressionStatement) s).getExpression();
                sb.append(':').append(e == null ? "null" : e.getClass().getSimpleName());
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    // toString() del AST; si algún nodo lo hace fallar (p. ej. If sin ELSE), se anota por sentencia.
    // Es del arnés: el compilador nunca llama a toString(), así que no cuenta como excepción del caso.
    private static String AstSeguro(Program program) {
        try {
            return program.toString();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder("<toString falla: " + t.getClass().getSimpleName() + "> ");
            for (Statement s : program.statements) {
                try {
                    sb.append(s.toString());
                } catch (Throwable u) {
                    sb.append('<').append(u.getClass().getSimpleName()).append('>');
                }
                sb.append(" | ");
            }
            return sb.toString();
        }
    }

    // Una sola línea por dato: se escapan contrabarras y saltos
    private static String Escapar(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    // ------------------------------------------------------------------
    // Lanzamiento de hijos (R8)
    // ------------------------------------------------------------------

    private static class ResultadoHijo {
        boolean termino;
        int codigo = -1;
        String stdout = "";
        String stderr = "";
        long milis;

        String todo() {
            return stdout + "\n" + stderr;
        }
    }

    // Misma java.exe, --enable-preview, -Xmx256m, mismo classpath; waitFor(10 s) y destroyForcibly
    private static ResultadoHijo LanzarHijo(String caso, Path directorio) throws Exception {
        // Exploración: mismo comportamiento que antes (modo "hijo <caso>", log con el nombre del caso)
        return LanzarHijo(Arrays.asList("hijo", caso), directorio, caso, 64 * 1024);
    }

    // Versión general: argumentos del hijo, nombre del log y límite de lectura de la salida
    private static ResultadoHijo LanzarHijo(List<String> argsHijo, Path directorio, String nombreLog, int maxBytes) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> comando = new ArrayList<>();
        comando.add(java);
        // Si el padre corre con -ea, los hijos también (tarea 3.9)
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.equals("-ea") || a.equals("-enableassertions")) comando.add("-ea");
        }
        comando.add("--enable-preview");
        comando.add("-Xmx256m");
        comando.add("-cp");
        comando.add(System.getProperty("java.class.path"));
        comando.add("PruebasCriticos");
        comando.addAll(argsHijo);

        // La salida va a archivos fuera de la carpeta del hijo (no cuenta como archivo creado)
        Path logs = Paths.get("").toAbsolutePath().resolve("logs");
        Files.createDirectories(logs);
        File out = logs.resolve(nombreLog + ".out").toFile();
        File err = logs.resolve(nombreLog + ".err").toFile();

        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.directory(directorio.toFile());
        pb.redirectOutput(out);
        pb.redirectError(err);
        long inicio = System.currentTimeMillis();
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
        r.milis = System.currentTimeMillis() - inicio;
        r.stdout = LeerRecortado(out.toPath(), maxBytes);
        r.stderr = LeerRecortado(err.toPath(), 64 * 1024);
        return r;
    }

    // Lee como mucho maxBytes (un OutOfMemoryError puede dejar mucha salida)
    private static String LeerRecortado(Path p, int maxBytes) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        int n = Math.min(b.length, maxBytes);
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Chequeos
    // ------------------------------------------------------------------

    // Termina, sin excepción, archivos completos y TestProyecto=false
    private static void ChequeosComunes(ResultadoHijo r, Path carpeta, String id, List<String> diferencias) throws IOException {
        if (!r.termino) {
            diferencias.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        }
        String excepcion = PrimeraLineaCon(r.todo(), "Exception");
        if (excepcion == null) excepcion = PrimeraLineaCon(r.todo(), "Error:");
        if (excepcion != null && !excepcion.contains("Error 2") && !excepcion.contains("Error 3")) {
            diferencias.add("excepción: " + excepcion.trim());
        }
        String traza = PrimeraLineaCon(r.stderr, "\tat ");
        if (traza != null) diferencias.add("traza: " + traza.trim());
        if (r.termino && r.codigo != 0) diferencias.add("código de salida del hijo " + r.codigo);
        if (!r.stdout.contains("TestProyecto=false")) {
            diferencias.add("TestProyecto distinto de false (" + LineaConPrefijo(r.stdout, "TestProyecto=") + ")");
        }
        ArchivosCompletos(carpeta, id, diferencias);
    }

    // -errores.txt con las n líneas numeradas y .a68 copia del fuente
    private static void ArchivosCompletos(Path carpeta, String id, List<String> diferencias) throws IOException {
        List<String> fuente = Files.readAllLines(carpeta.resolve(id + ".mingol"), StandardCharsets.US_ASCII);
        Path errores = carpeta.resolve(id + "-errores.txt");
        Path a68 = carpeta.resolve(id + ".a68");
        long tamErrores = Files.exists(errores) ? Files.size(errores) : -1;
        long tamA68 = Files.exists(a68) ? Files.size(a68) : -1;
        String tamanos = "(" + id + "-errores.txt " + tamErrores + " bytes, " + id + ".a68 " + tamA68 + " bytes)";
        if (tamErrores < 0) {
            diferencias.add("no existe " + id + "-errores.txt");
        } else {
            // ISO-8859-1 no falla con ningún byte: TestProyecto escribe en la codificación nativa (tildes en los mensajes)
            List<String> lineas = Files.readAllLines(errores, StandardCharsets.ISO_8859_1);
            boolean ok = lineas.size() >= fuente.size();
            for (int i = 0; ok && i < fuente.size(); i++) {
                ok = lineas.get(i).equals(String.format("%05d", i + 1) + "\t" + fuente.get(i));
            }
            if (!ok) diferencias.add(id + "-errores.txt incompleto " + tamanos);
        }
        StringBuilder copia = new StringBuilder();
        for (String l : fuente) copia.append(l).append("\n");
        if (tamA68 < 0) {
            diferencias.add("no existe " + id + ".a68");
        } else if (!new String(Files.readAllBytes(a68), StandardCharsets.US_ASCII).equals(copia.toString())) {
            diferencias.add(id + ".a68 no es copia del fuente " + tamanos);
        }
    }

    // El -errores.txt debe tener el 204 de FI esperado con el token correcto
    private static void Chequear204Fi(Path carpeta, String id, String token, List<String> diferencias) throws IOException {
        String esperado = "Error 204: Se esperaba un token de tipo FI pero se obtuvo un " + token;
        Path errores = carpeta.resolve(id + "-errores.txt");
        String contenido = Files.exists(errores) ? new String(Files.readAllBytes(errores), StandardCharsets.UTF_8) : "";
        if (!contenido.contains(esperado)) {
            diferencias.add(id + "-errores.txt sin '" + esperado + "'");
        }
    }

    // ------------------------------------------------------------------
    // Bug C: reconstrucción de la cadena del código sin arreglar (NO se ejecuta)
    // ------------------------------------------------------------------

    // Copia textual de lo que arma Mingol.main antes del arreglo
    private static String CadenaExecOriginal(String path) {
        return "cmd /c \"start cmd /k" + "C:\\Algol\\a68g.exe " + path.replace("mingol", "a68");
    }

    // Runtime.exec(String) parte la cadena con un StringTokenizer por blancos
    private static List<String> Tokenizar(String cadena) {
        List<String> tokens = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(cadena);
        while (st.hasMoreTokens()) tokens.add(st.nextToken());
        return tokens;
    }

    // Nombre sin la última extensión (lo que se espera de RutaA68)
    private static String SinUltimaExtension(String ruta) {
        String nombre = Paths.get(ruta).getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }

    // ------------------------------------------------------------------
    // Tarea 3.6: utilidades comunes de los modos unitarias, arreglo y main
    // ------------------------------------------------------------------

    // Carpeta base de los modos nuevos: %TEMP%\mingol-criticos (nunca el repositorio)
    private static Path CarpetaBase() {
        return CarpetaGolden().getParent().resolve("mingol-criticos").toAbsolutePath().normalize();
    }

    // Llama por reflexión a un método estático de Mingol.Mingol (también privado) y desenvuelve la excepción real
    private static Object Llamar(String metodo, Class<?>[] tipos, Object... args) throws Exception {
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod(metodo, tipos);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new RuntimeException(c);
        }
    }

    // BuscarA68g(String, Path, String) por reflexión
    private static Path Buscar(String valorA68G, Path carpetaApp, String path) throws Exception {
        return (Path) Llamar("BuscarA68g", new Class<?>[]{String.class, Path.class, String.class}, valorA68G, carpetaApp, path);
    }

    private static boolean ExisteMetodo(String nombre) throws ClassNotFoundException {
        for (Method m : Class.forName("Mingol.Mingol").getDeclaredMethods()) {
            if (m.getName().equals(nombre)) return true;
        }
        return false;
    }

    // Entrada del classpath que contiene Mingol\Mingol.class (build\classes)
    private static Path CarpetaClases() {
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (e.isBlank()) continue;
            Path p = Paths.get(e).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("Mingol").resolve("Mingol.class"))) return p;
        }
        return null;
    }

    // Crea un archivo de 0 bytes (ficticio a68g.exe o script rechazado). Nunca se ejecuta.
    // Si ya existía con contenido, se detiene: no se pisa algo que no creó el arnés
    private static Path Vacio(Path archivo) throws IOException {
        Files.createDirectories(archivo.getParent());
        if (Files.exists(archivo) && Files.size(archivo) != 0) {
            throw new IOException("Ya existe con contenido, no se pisa: " + archivo);
        }
        Files.write(archivo, new byte[0]);
        return archivo;
    }

    // El ficticio o el script debe seguir existiendo con 0 bytes (prueba de que no se tocó)
    private static void SigueVacio(Path archivo, List<String> diferencias) throws IOException {
        if (!Files.exists(archivo)) diferencias.add(archivo + " ya no existe");
        else if (Files.size(archivo) != 0) diferencias.add(archivo + " ya no tiene 0 bytes (" + Files.size(archivo) + ")");
    }

    // Compara un valor y registra OK/FALLA
    private static void Esperar(String caso, Object esperado, Object obtenido, List<String> extra) {
        List<String> d = new ArrayList<>();
        if (!java.util.Objects.equals(esperado, obtenido)) d.add("esperado [" + esperado + "], obtenido [" + obtenido + "]");
        if (extra != null) d.addAll(extra);
        Registrar(caso, d, null);
    }

    // Copia esperada del .a68: cada línea del fuente seguida de \n
    private static String CopiaEsperada(String[] lineas) {
        StringBuilder sb = new StringBuilder();
        for (String l : lineas) sb.append(l).append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Modo unitarias (tarea 3.6; diseño, Unit Tests)
    // ------------------------------------------------------------------

    private static void Unitarias() throws Exception {
        Path base = CarpetaBase();
        Path u = base.resolve("unitarias");
        BorrarArbol(u);
        Files.createDirectories(u);
        System.out.println("Carpeta de trabajo: " + u);
        UnitariasEvaluateIdentifier();
        UnitariasRutaA68();
        UnitariasBuscarA68g(u.resolve("buscar"));
        UnitariasScripts(base.resolve("scripts"), u.resolve("scripts"));
        UnitariasEsScriptPorLotes();
        UnitariasCarpetaAplicacion();
        UnitariasParser(u.resolve("parser"));
        ImprimirResumen("unitarias");
    }

    // EvaluateIdentifier vía Evaluator.Evaluate: lo que no es Objeto da 303 sin lanzar; una variable se resuelve
    @SuppressWarnings("unchecked")
    private static void UnitariasEvaluateIdentifier() {
        String[][] casos = {{"inicio", ""}, {"BEGIN", "BEGIN"}, {"END", "END"}, {"ausente", null}};
        for (String[] c : casos) {
            List<String> d = new ArrayList<>();
            try {
                Environment env = new Environment();
                if (c[1] != null) env.put(c[0], c[1]);
                Objeto res = Evaluator.Evaluate(new Identifier(new Token(MingolToken.IDENT, c[0]), c[0]), env);
                if (!(res instanceof Errado)) d.add("no devolvió un Errado: " + res);
                else if (!res.Inspect().contains("303") || !res.Inspect().contains(c[0])) d.add("Inspect sin 303 o sin el nombre: " + res.Inspect());
            } catch (Throwable t) {
                d.add("lanzó " + t);
            }
            Registrar("EvaluateIdentifier " + c[0] + (c[1] == null ? " (clave ausente)" : " -> \"" + c[1] + "\""), d, null);
        }
        List<String> d = new ArrayList<>();
        try {
            Environment env = new Environment();
            Entero uno = new Entero(1);
            env.put("x", uno);
            Objeto res = Evaluator.Evaluate(new Identifier(new Token(MingolToken.IDENT, "x"), "x"), env);
            if (res != uno) d.add("no devolvió el mismo Entero: " + res);
        } catch (Throwable t) {
            d.add("lanzó " + t);
        }
        Registrar("EvaluateIdentifier x -> new Entero(1) devuelve ese objeto", d, null);
        // Evaluate marca hasErrors (estático); se deja como estaba para no afectar a otros casos
        Evaluator.hasErrors = false;
    }

    // RutaA68: cambia solo la última extensión por .a68, sin distinguir mayúsculas
    private static void UnitariasRutaA68() {
        String[][] casos = {
            {"t1.mingol", "t1.a68"}, {"T10.MINGOL", "T10.a68"}, {"x.Mingol", "x.a68"},
            {"mingolito.mingol", "mingolito.a68"}, {"sub.dir\\t11.mingol", "sub.dir\\t11.a68"},
            {"C:\\x.y\\z.Mingol", "C:\\x.y\\z.a68"}, {"mi prog.mingol", "mi prog.a68"},
            {"a&b.mingol", "a&b.a68"}, {"t12", "t12.a68"}};
        for (String[] c : casos) {
            try {
                Esperar("RutaA68(" + c[0] + ")", c[1], Llamar("RutaA68", new Class<?>[]{String.class}, c[0]), null);
            } catch (Throwable t) {
                Registrar("RutaA68(" + c[0] + ")", List.of("lanzó " + t), null);
            }
        }
    }

    // BuscarA68g con carpetas temporales y ficticios a68g.exe de 0 bytes
    private static void UnitariasBuscarA68g(Path b) throws Exception {
        Path deA68G = Vacio(b.resolve("a68gvar").resolve("a68g.exe"));
        Path app = b.resolve("app");
        Path appExe = Vacio(app.resolve("a68g.exe"));
        Vacio(app.resolve("a68g"));
        Path appSolo = b.resolve("app-solo");
        Path appSoloSinExt = Vacio(appSolo.resolve("a68g"));
        Path appVacia = Files.createDirectories(b.resolve("app-vacia"));
        Path p1 = b.resolve("path1");
        Path p1Exe = Vacio(p1.resolve("a68g.exe"));
        Path p2 = b.resolve("path2");
        Path p2Exe = Vacio(p2.resolve("a68g.exe"));
        Path pVacia = Files.createDirectories(b.resolve("path-vacia"));
        String noExiste = b.resolve("no-existe").resolve("a68g.exe").toString();
        String sep = File.pathSeparator;

        Object[][] casos = {
            {"orden: A68G antes que carpeta y PATH", deA68G, deA68G.toString(), app, p1.toString()},
            {"A68G inexistente se salta -> carpeta", appExe, noExiste, app, p1.toString()},
            {"sin A68G: carpeta antes que PATH", appExe, null, app, p1.toString()},
            {"A68G en blanco se salta -> PATH", p1Exe, "   ", null, p1.toString()},
            {"A68G inválido se salta -> PATH", p1Exe, "C:\\a<b\\a68g.exe", null, p1.toString()},
            {"carpeta: a68g.exe antes que a68g", appExe, null, app, null},
            {"carpeta: solo a68g sin extensión", appSoloSinExt, null, appSolo, null},
            {"PATH: primera entrada que lo tiene", p1Exe, null, appVacia, pVacia + sep + p1 + sep + p2},
            {"PATH: entradas vacías, en blanco, inválidas y con comillas", p2Exe, null, null,
                sep + "   " + sep + "C:\\in<valido" + sep + sep + "\"" + p2 + "\""},
            {"todo ausente -> null", null, noExiste, appVacia, pVacia.toString()},
            {"todo nulo -> null", null, null, null, null},
        };
        for (Object[] c : casos) {
            try {
                Esperar("BuscarA68g " + c[0], c[1], Buscar((String) c[2], (Path) c[3], (String) c[4]), null);
            } catch (Throwable t) {
                Registrar("BuscarA68g " + c[0], List.of("lanzó " + t), null);
            }
        }
    }

    // Rechazo de .bat/.cmd (decisión del usuario): los scripts tienen 0 bytes y solo se pasan como ruta
    private static void UnitariasScripts(Path scripts, Path b) throws Exception {
        Path bat = Vacio(scripts.resolve("a68g.bat"));
        Path cmd = Vacio(scripts.resolve("A68G.CMD"));
        Path appFict = Vacio(b.resolve("app").resolve("a68g.exe"));
        Path pathFict = Vacio(b.resolve("path").resolve("a68g.exe"));
        Path pathBat = Vacio(b.resolve("path-bat").resolve("a68g.bat"));

        Object[][] casos = {
            {"A68G = a68g.bat solo -> null", null, bat.toString(), null, null},
            {"A68G = A68G.CMD -> null", null, cmd.toString(), null, null},
            {"A68G = a68g.bat + ficticio en la carpeta -> el ficticio", appFict, bat.toString(), appFict.getParent(), null},
            {"A68G = a68g.bat + ficticio solo en el PATH -> el del PATH", pathFict, bat.toString(), null, pathFict.getParent().toString()},
            {"PATH con una sola carpeta que tiene a68g.bat -> null", null, null, null, pathBat.getParent().toString()},
        };
        for (Object[] c : casos) {
            List<String> extra = new ArrayList<>();
            Object obtenido = null;
            try {
                obtenido = Buscar((String) c[2], (Path) c[3], (String) c[4]);
            } catch (Throwable t) {
                extra.add("lanzó " + t);
            }
            // Después de cada caso, los scripts siguen con 0 bytes
            SigueVacio(bat, extra);
            SigueVacio(cmd, extra);
            SigueVacio(pathBat, extra);
            Esperar("BuscarA68g " + c[0], c[1], obtenido, extra);
        }
    }

    private static void UnitariasEsScriptPorLotes() {
        String[] verdaderos = {"x.bat", "X.BAT", "x.Cmd", "x.bat.", "x.cmd "};
        String[] falsos = {"a68g.exe", "a68g", "batch.exe", "x.bat.exe", "cmd"};
        for (String n : verdaderos) EsScript(n, true);
        for (String n : falsos) EsScript(n, false);
    }

    private static void EsScript(String nombre, boolean esperado) {
        try {
            Esperar("EsScriptPorLotes(\"" + nombre + "\")", esperado,
                    Llamar("EsScriptPorLotes", new Class<?>[]{String.class}, nombre), null);
        } catch (Throwable t) {
            Registrar("EsScriptPorLotes(\"" + nombre + "\")", List.of("lanzó " + t), null);
        }
    }

    // CarpetaAplicacion desde build\classes devuelve esa carpeta
    private static void UnitariasCarpetaAplicacion() {
        List<String> d = new ArrayList<>();
        try {
            Path esperado = CarpetaClases();
            Path obtenido = (Path) Llamar("CarpetaAplicacion", new Class<?>[]{});
            if (esperado == null) d.add("no se encontró build\\classes en el classpath");
            else if (obtenido == null) d.add("devolvió null");
            else if (!Files.isSameFile(esperado, obtenido)) d.add("esperado [" + esperado + "], obtenido [" + obtenido + "]");
        } catch (Throwable t) {
            d.add("lanzó " + t);
        }
        Registrar("CarpetaAplicacion desde build\\classes", d, null);
    }

    // Parser aislado (R8): 204 de FI con EOF y ParseProgram vuelve en menos de 10 s
    private static void UnitariasParser(Path dir) throws Exception {
        Files.createDirectories(dir);
        String esperado = "Error 204: Se esperaba un token de tipo FI pero se obtuvo un EOF";
        for (String id : new String[]{"e2", "e3", "e7", "e8", "e9"}) {
            Escribir(dir.resolve(id + ".mingol"), PROGRAMAS.get(id), CON_SALTO_FINAL.get(id));
            ResultadoHijo r = LanzarHijo(Arrays.asList("hijoparser", id), dir, "unit-parser-" + id, 64 * 1024);
            List<String> d = new ArrayList<>();
            if (!r.termino) d.add("no terminó en " + LIMITE_SEGUNDOS + " s");
            else if (r.codigo != 0) d.add("código de salida " + r.codigo);
            String exc = PrimeraLineaCon(r.todo(), "Exception");
            if (exc == null) exc = PrimeraLineaCon(r.todo(), "OutOfMemoryError");
            if (exc != null) d.add("excepción: " + exc.trim());
            if (LineasConPrefijo(r.stdout, "GETERRORS|").stream().noneMatch(e -> e.contains(esperado))) {
                d.add("GetErrors() sin '" + esperado + "': " + LineasConPrefijo(r.stdout, "GETERRORS|"));
            }
            String ms = LineaConPrefijo(r.stdout, "PARSE_MS|");
            if (ms == null) d.add("ParseProgram no volvió");
            else if (Long.parseLong(ms.substring("PARSE_MS|".length())) >= LIMITE_SEGUNDOS * 1000) d.add("ParseProgram tardó " + ms);
            Registrar("parser " + id + " (204 de FI con EOF, < 10 s)", d, r);
        }
    }

    // Modo hijoparser: solo lexer + parser; imprime GetErrors() y el tiempo de ParseProgram
    private static void HijoParser(String caso) throws Exception {
        String fuente = LeerFuente(caso + ".mingol");
        long inicio = System.nanoTime();
        Parser parser = new Parser(new Lexer(fuente));
        parser.ParseProgram();
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        for (String e : parser.GetErrors()) System.out.println("GETERRORS|" + e);
        System.out.println("PARSE_MS|" + ms);
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Modo arreglo (tarea 3.6; diseño, Fix Checking y Property-Based Tests)
    // ------------------------------------------------------------------

    // Programa con caminos defectuosos: cuenta los IF sin FI que el parser alcanza
    private static class CasoArreglo extends CasoPres {
        int cantidadIfSinFi;
    }

    private static final String FI_204 = "Error 204: Se esperaba un token de tipo FI";

    private static void Arreglo() throws Exception {
        Path trabajo = CarpetaBase().resolve("arreglo");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        System.out.println("Carpeta de trabajo: " + trabajo);

        // Programas defectuosos, semillas 1..200, cada uno en su hijo (R8)
        List<CasoPres> casos = new ArrayList<>();
        int conEtiqueta = 0, conIfSinFi = 0, conBloque = 0, totalIfSinFi = 0;
        for (int s = 1; s <= 200; s++) {
            CasoArreglo c = GenerarDefectuoso(s);
            casos.add(c);
            if (c.usaEtiquetaComoIdentificador) conEtiqueta++;
            if (c.ifSinFi) conIfSinFi++;
            if (c.terminaEnBloqueAbierto) conBloque++;
            totalIfSinFi += c.cantidadIfSinFi;
        }
        Path programas = trabajo.resolve("programas");
        Map<String, ResultadoHijo> resultados = CorrerCasos(casos, programas, "arr-");
        for (CasoPres cp : casos) {
            CasoArreglo c = (CasoArreglo) cp;
            ResultadoHijo r = resultados.get(c.id);
            List<String> d = new ArrayList<>();
            // Termina en < 10 s, sin excepción
            ProblemasDeEjecucion(r, d);
            // No válido
            if (!LineasMeta(r.stdout).contains("TESTPROYECTO|false")) d.add("TestProyecto distinto de false: " + LineaConPrefijo(r.stdout, "TESTPROYECTO|"));
            // Archivos completos
            ArchivosCompletos(programas.resolve(c.id), c.id, d);
            // Un 204 de FI por cada IF sin FI alcanzado
            long n204 = LineasConPrefijo(r.stdout, "GETERRORS|").stream().filter(e -> e.contains(FI_204)).count();
            if (n204 != c.cantidadIfSinFi) d.add("204 de FI: esperados " + c.cantidadIfSinFi + ", obtenidos " + n204);
            String flags = (c.usaEtiquetaComoIdentificador ? "E" : "-") + (c.ifSinFi ? "I" : "-") + (c.terminaEnBloqueAbierto ? "B" : "-");
            Registrar("arreglo " + c.id + " [" + flags + ", IF sin FI=" + c.cantidadIfSinFi + "]", d, r);
        }
        System.out.println("Semillas 1..200: usaEtiquetaComoIdentificador=" + conEtiqueta + ", ifSinFi=" + conIfSinFi
                + ", terminaEnBloqueAbierto=" + conBloque + ", IF sin FI en total=" + totalIfSinFi);

        // Nombres de archivo difíciles: RutaA68 y el .a68 que escribe TestProyecto
        Nombres(trabajo.resolve("nombres"));
        ImprimirResumen("arreglo");
    }

    // Inserta en un programa base (válido o con errores) uno o más caminos defectuosos
    private static CasoArreglo GenerarDefectuoso(int semilla) {
        // Flujo aleatorio distinto del de preservación
        Random r = new Random(1_000_003L * semilla);
        CasoArreglo c = new CasoArreglo();
        c.id = String.format("f%03d", semilla);
        c.archivo = c.id + ".mingol";
        c.argumento = c.archivo;
        c.generado = true;
        List<String> l = new ArrayList<>();
        List<String> etiquetas = new ArrayList<>();

        if (r.nextInt(3) == 0) l.add("");
        l.add("BEGIN");
        int declaraciones = r.nextInt(3);
        for (int i = 0; i < declaraciones; i++) l.add("INT " + Elegir(r, VARS) + " := " + r.nextInt(100) + ";");
        // A veces la base ya tiene un error (201)
        if (r.nextInt(4) == 0) l.add("INT " + Elegir(r, VARS) + " := 2.5;");

        int comandos = 1 + r.nextInt(6);
        for (int i = 0; i < comandos; i++) {
            switch (r.nextInt(8)) {
                case 0:
                    l.add(LineaSimple(r));
                    break;
                case 1:
                    DefinirEtiqueta(r, l, etiquetas);
                    break;
                case 2:
                    // Etiqueta usada como identificador (bug A)
                    UsarEtiqueta(r, l, etiquetas);
                    c.usaEtiquetaComoIdentificador = true;
                    break;
                case 3:
                    // IF sin FI seguido de otra línea (bug B)
                    IfSinFiIntermedio(r, l);
                    c.ifSinFi = true;
                    c.cantidadIfSinFi++;
                    break;
                case 4:
                    // IF correcto, con FI (no cuenta)
                    AgregarIf(r, l, CondicionNormal(r), true);
                    break;
                case 5:
                    l.add("");
                    break;
                default:
                    l.add("CO nota CO");
                    break;
            }
        }

        // Final del archivo: bloque abierto, IF cortado o END
        switch (r.nextInt(4)) {
            case 0:
                // Cortado justo después de THEN
                l.add("IF ( " + CondicionNormal(r) + " ) THEN");
                c.terminaEnBloqueAbierto = true;
                c.cantidadIfSinFi++;
                break;
            case 1:
                // Cortado después de ELSE
                l.add("IF ( " + CondicionNormal(r) + " ) THEN");
                l.add(CuerpoSeguro(r));
                l.add("ELSE");
                c.terminaEnBloqueAbierto = true;
                c.cantidadIfSinFi++;
                break;
            case 2:
                // Cuerpo completo pero sin FI ni END
                l.add("IF ( " + CondicionNormal(r) + " ) THEN");
                l.add(CuerpoSeguro(r));
                c.ifSinFi = true;
                c.cantidadIfSinFi++;
                break;
            default:
                // Siempre al menos un camino defectuoso
                if (c.sinFlags()) {
                    UsarEtiqueta(r, l, etiquetas);
                    c.usaEtiquetaComoIdentificador = true;
                }
                l.add("END");
                break;
        }
        c.lineas = l.toArray(new String[0]);
        c.saltoFinal = r.nextBoolean();
        return c;
    }

    // Comandos de una línea que el parser procesa línea a línea
    private static String LineaSimple(Random r) {
        switch (r.nextInt(4)) {
            case 0:
                return "print ( \"hola\" );";
            case 1:
                return "print ( newline );";
            case 2:
                return "print ( (" + Elegir(r, VARS) + ") );";
            default:
                return "GOTO " + Elegir(r, ETIQUETAS) + ";";
        }
    }

    // Cuerpo de IF sin SKIP (que consumiría la línea siguiente y cambiaría el conteo)
    private static String CuerpoSeguro(Random r) {
        switch (r.nextInt(3)) {
            case 0:
                return "  print ( \"si\" );";
            case 1:
                return "  print ( (" + Elegir(r, VARS) + ") );";
            default:
                return "  print ( newline );";
        }
    }

    // Define una etiqueta nueva si queda alguna; devuelve la etiqueta definida o una existente
    private static String DefinirEtiqueta(Random r, List<String> l, List<String> etiquetas) {
        List<String> libres = new ArrayList<>();
        for (String e : ETIQUETAS) if (!etiquetas.contains(e)) libres.add(e);
        if (libres.isEmpty()) return etiquetas.get(r.nextInt(etiquetas.size()));
        String e = libres.get(r.nextInt(libres.size()));
        etiquetas.add(e);
        l.add(e + ":");
        return e;
    }

    // Etiqueta (definida antes, al nivel superior) usada en una asignación o en una condición
    private static void UsarEtiqueta(Random r, List<String> l, List<String> etiquetas) {
        String e = (etiquetas.isEmpty() || r.nextInt(3) == 0) ? DefinirEtiqueta(r, l, etiquetas) : etiquetas.get(r.nextInt(etiquetas.size()));
        if (r.nextBoolean()) {
            l.add("CHAR w := " + e + ";");
        } else {
            l.add("IF ( " + e + " ) THEN");
            l.add(CuerpoSeguro(r));
            l.add("FI;");
        }
    }

    // IF sin FI, con o sin ELSE, seguido de una línea separadora para que el siguiente IF se alcance
    private static void IfSinFiIntermedio(Random r, List<String> l) {
        l.add("IF ( " + CondicionNormal(r) + " ) THEN");
        l.add(CuerpoSeguro(r));
        if (r.nextBoolean()) {
            l.add("ELSE");
            l.add(CuerpoSeguro(r));
        }
        l.add("print ( \"sep\" );");
    }

    // Generador de nombres: RutaA68 mantiene carpeta y nombre base y termina en .a68;
    // TestProyecto escribe el .a68 justo en esa ruta (en proceso, por reflexión)
    private static void Nombres(Path raiz) throws Exception {
        String raizTxt = raiz.toString();
        // TestProyecto parte la ruta por el primer punto para el -errores.txt: sin puntos antes de la carpeta de prueba
        if (raizTxt.contains(".")) {
            Registrar("nombres", List.of("la carpeta temporal contiene un punto (" + raizTxt + "); no se prueba"), null);
            return;
        }
        String[] e5 = PROGRAMAS.get("e5");
        for (int s = 1; s <= 200; s++) {
            Random r = new Random(500_009L * s);
            StringBuilder carpeta = new StringBuilder(raizTxt).append(File.separator).append(String.format("s%03d", s));
            int niveles = r.nextInt(3);
            for (int i = 0; i < niveles; i++) carpeta.append(File.separator).append(NombreAlAzar(r, true));
            String nombreBase = (r.nextInt(5) == 0 ? "mingol" : "") + NombreAlAzar(r, false);
            String ruta = carpeta + File.separator + nombreBase + "." + ExtensionAlAzar(r);
            String esperado = carpeta + File.separator + nombreBase + ".a68";
            List<String> d = new ArrayList<>();
            try {
                Object obtenida = Llamar("RutaA68", new Class<?>[]{String.class}, ruta);
                if (!esperado.equals(obtenida)) d.add("RutaA68: esperado [" + esperado + "], obtenido [" + obtenida + "]");
                Path dir = Paths.get(carpeta.toString());
                Files.createDirectories(dir);
                // Contenido con errores (e5): el evaluador no corre
                Escribir(Paths.get(ruta), e5, true);
                Llamar("TestProyecto", new Class<?>[]{String.class}, ruta);
                Path a68 = Paths.get(esperado);
                if (!Files.exists(a68)) d.add("no se escribió " + esperado);
                else if (!new String(Files.readAllBytes(a68), StandardCharsets.US_ASCII).equals(CopiaEsperada(e5))) d.add("el .a68 no es copia del fuente");
                // Ningún otro .a68 en la carpeta (p. ej. a68ito.a68)
                for (String n : ArchivosDe(dir)) {
                    if (n.toLowerCase().endsWith(".a68") && !n.equals(nombreBase + ".a68")) d.add("otro .a68 en la carpeta: " + n);
                }
            } catch (Throwable t) {
                d.add("lanzó " + t);
            }
            Registrar("nombre " + raiz.relativize(Paths.get(ruta)), d, null);
        }
    }

    // Letras, dígitos, _, espacio, &, ^, %, (, ) y, en carpetas, también '.'; empieza y termina con letra
    private static String NombreAlAzar(Random r, boolean carpeta) {
        String letras = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String medio = letras + "0123456789_ &^%()" + (carpeta ? "." : "");
        StringBuilder sb = new StringBuilder();
        sb.append(letras.charAt(r.nextInt(letras.length())));
        int largo = r.nextInt(8);
        for (int i = 0; i < largo; i++) sb.append(medio.charAt(r.nextInt(medio.length())));
        sb.append(letras.charAt(r.nextInt(letras.length())));
        String n = sb.toString();
        // Evita los nombres de dispositivo de Windows (CON, NUL, COM1...), que no son archivos
        String raiz = n.split("\\.")[0].trim().toLowerCase(Locale.ROOT);
        if (raiz.matches("con|prn|aux|nul|com[0-9]|lpt[0-9]")) n = "x" + n;
        return n;
    }

    // "mingol" con cualquier combinación de mayúsculas y minúsculas
    private static String ExtensionAlAzar(Random r) {
        StringBuilder sb = new StringBuilder();
        for (char ch : "mingol".toCharArray()) sb.append(r.nextBoolean() ? Character.toUpperCase(ch) : ch);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Modo main (tarea 3.6; diseño, casos 1 a 9). Siempre en hijos con entorno controlado
    // ------------------------------------------------------------------

    // Parte del mensaje del eco que no depende del idioma del JDK ("Caused by:"/"Causado por:" va delante)
    private static final String ECO_MARCA = "java.lang.ClassNotFoundException: ";

    private static void ModoMain() throws Exception {
        // Guardia de seguridad (R7): sin EjecutarA68g el código no está arreglado y main abriría otra consola
        if (!ExisteMetodo("EjecutarA68g")) {
            System.err.println("ABORTADO: Mingol.Mingol no tiene EjecutarA68g (código sin arreglar). No se ejecuta main.");
            System.exit(2);
        }
        Path base = CarpetaBase();
        Path trabajo = base.resolve("main");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        Path clases = CarpetaClases();
        if (clases == null) {
            System.err.println("ABORTADO: no se encontró build\\classes en el classpath.");
            System.exit(2);
        }
        Path repo = clases.getParent().getParent();
        String eco = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        String raizSistema = System.getenv("SystemRoot") != null ? System.getenv("SystemRoot") : "C:\\Windows";
        String system32 = Paths.get(raizSistema, "System32").toString();
        // Nada fuera de lo controlado puede encontrarse: si hubiera un a68g real al alcance, se aborta
        for (Path p : new Path[]{Paths.get(system32, "a68g.exe"), Paths.get(system32, "a68g"), clases.resolve("a68g.exe"), clases.resolve("a68g")}) {
            if (Files.exists(p)) {
                System.err.println("ABORTADO: existe " + p + "; el entorno controlado no sería seguro.");
                System.exit(2);
            }
        }
        System.out.println("Carpeta de trabajo: " + trabajo);
        System.out.println("Eco (A68G): " + eco);

        // Ficticio de 0 bytes en una carpeta usada como PATH, y scripts rechazados de 0 bytes
        Path ficticio = Vacio(trabajo.resolve("path-ficticio").resolve("a68g.exe"));
        Path bat = Vacio(base.resolve("scripts").resolve("a68g.bat"));
        Path cmd = Vacio(base.resolve("scripts").resolve("A68G.CMD"));
        String[] e1 = PROGRAMAS.get("e1");
        byte[] erroresRef = ErroresDeReferencia(trabajo.resolve("ref-e1"), "e1");

        // (1) Sin argumentos y con ""
        for (String[] args : new String[][]{{}, {""}}) {
            String caso = args.length == 0 ? "sin argumentos" : "con \"\"";
            Path dir = Files.createDirectories(trabajo.resolve(args.length == 0 ? "uso-sin-args" : "uso-vacio"));
            ResultadoHijo r = LanzarMain(ConClases(clases, args), dir, "main-uso-" + args.length, Entorno(null, system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 1, d, true);
            if (!r.stderr.contains("Uso:")) d.add("stderr sin 'Uso:'");
            String[] creados = dir.toFile().list();
            if (creados != null && creados.length > 0) d.add("archivos creados: " + Arrays.toString(creados));
            Registrar("main (1) " + caso, d, r);
        }

        // (2) Eco con nombres difíciles en "dir con & espacio"
        Path dificil = base.resolve("dir con & espacio");
        BorrarArbol(dificil);
        Files.createDirectories(dificil);
        String[] nombres = {"mi prog.mingol", "a&mkdir INYECTADO&b.mingol", "a^b.mingol", "p%PATH%(1).mingol", "mingolito.mingol"};
        for (int i = 0; i < nombres.length; i++) {
            String nombre = nombres[i];
            Escribir(dificil.resolve(nombre), e1, CON_SALTO_FINAL.get("e1"));
            Path a68 = dificil.resolve(SinUltimaExtension(nombre) + ".a68").toAbsolutePath();
            ResultadoHijo r = LanzarMain(ConClases(clases, nombre), dificil, "main-eco-" + i, Entorno(eco, system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 0, d, false);
            ChequearEco(r, a68, d);
            if (!Files.exists(a68)) d.add("no existe " + a68);
            else if (!new String(Files.readAllBytes(a68), StandardCharsets.US_ASCII).equals(CopiaEsperada(e1))) d.add(a68.getFileName() + " no es copia del fuente");
            if (nombre.equals("mingolito.mingol") && Files.exists(dificil.resolve("a68ito.a68"))) d.add("se escribió a68ito.a68");
            SinInyectado(d, dificil, base, repo, Paths.get("").toAbsolutePath());
            // main esperó: el eco ya escribió y no queda vivo ningún proceso con esa ruta
            if (ProcesosVivosCon(a68.toString()) > 0) d.add("queda un proceso vivo con " + a68);
            Registrar("main (2) eco con '" + nombre + "'", d, r);
        }

        // (3) Solo advertencias (e4): se invoca el eco
        {
            Path dir = Files.createDirectories(trabajo.resolve("advertencias"));
            Escribir(dir.resolve("e4.mingol"), PROGRAMAS.get("e4"), CON_SALTO_FINAL.get("e4"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e4.mingol"), dir, "main-advertencias", Entorno(eco, system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 0, d, false);
            ChequearEco(r, dir.resolve("e4.a68").toAbsolutePath(), d);
            Registrar("main (3) solo advertencias (e4) invoca el eco", d, r);
        }

        // (4) Con errores (e5): no se invoca nada
        {
            Path dir = Files.createDirectories(trabajo.resolve("errores"));
            Escribir(dir.resolve("e5.mingol"), PROGRAMAS.get("e5"), CON_SALTO_FINAL.get("e5"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e5.mingol"), dir, "main-errores", Entorno(eco, system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 0, d, true);
            // Marca del eco independiente del idioma
            if (r.stderr.contains(ECO_MARCA)) d.add("se invocó el eco con un programa con errores");
            if (!Files.exists(dir.resolve("e5-errores.txt"))) d.add("no se escribió e5-errores.txt");
            Registrar("main (4) con errores (e5) no invoca nada", d, r);
        }

        // (5) A68G inexistente + ficticio en el PATH: fallo de inicio con la ruta del ficticio
        {
            Path dir = Files.createDirectories(trabajo.resolve("inicio-falla"));
            Escribir(dir.resolve("e1.mingol"), e1, CON_SALTO_FINAL.get("e1"));
            String noExiste = trabajo.resolve("no-existe").resolve("a68g.exe").toString();
            ResultadoHijo r = LanzarMain(ConClases(clases, "e1.mingol"), dir, "main-inicio-falla",
                    Entorno(noExiste, ficticio.getParent().toString()));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 1, d, true);
            if (!r.stderr.contains("No se pudo iniciar a68g (" + ficticio + ")")) d.add("stderr sin 'No se pudo iniciar a68g (" + ficticio + ")'");
            SigueVacio(ficticio, d);
            Registrar("main (5) A68G inexistente + ficticio en el PATH", d, r);
        }

        // (6) No encontrado: sin A68G y PATH = System32
        {
            Path dir = Files.createDirectories(trabajo.resolve("no-encontrado"));
            Escribir(dir.resolve("e1.mingol"), e1, CON_SALTO_FINAL.get("e1"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e1.mingol"), dir, "main-no-encontrado", Entorno(null, system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 1, d, true);
            if (!r.stderr.contains("No se encontró a68g")) d.add("stderr sin 'No se encontró a68g'");
            if (r.stderr.contains(".bat/.cmd")) d.add("menciona .bat/.cmd sin que A68G apunte a uno");
            ErroresIguales(dir.resolve("e1-errores.txt"), erroresRef, d);
            Registrar("main (6) no encontrado (PATH = System32)", d, r);
        }

        // (7) Carpeta del .jar: jar temporal con la herramienta jar del JDK (R3) y el ficticio al lado
        {
            Path app = Files.createDirectories(trabajo.resolve("app con espacio"));
            Path jar = app.resolve("Mingol.jar");
            List<String> d = new ArrayList<>();
            String salidaJar = CrearJar(jar, clases);
            Path ficticioApp = Vacio(app.resolve("a68g.exe"));
            ResultadoHijo r = null;
            if (salidaJar != null) {
                d.add("no se pudo crear el jar: " + salidaJar);
            } else {
                Path dir = Files.createDirectories(trabajo.resolve("jar"));
                Escribir(dir.resolve("e1.mingol"), e1, CON_SALTO_FINAL.get("e1"));
                r = LanzarMain(Arrays.asList("-jar", jar.toString(), "e1.mingol"), dir, "main-jar", Entorno(null, system32));
                ChequearSalida(r, 1, d, true);
                String esperado = "No se pudo iniciar a68g (" + ficticioApp + ")";
                // La letra de unidad puede venir en otra caja desde el CodeSource
                if (!r.stderr.toLowerCase(Locale.ROOT).contains(esperado.toLowerCase(Locale.ROOT))) d.add("stderr sin '" + esperado + "'");
                SigueVacio(ficticioApp, d);
            }
            Registrar("main (7) ficticio junto al .jar en 'app con espacio'", d, r);
        }

        // (8) A68G = .bat (y luego .CMD) sin nada más: no encontrado, con la frase de .bat/.cmd
        for (Path script : new Path[]{bat, cmd}) {
            Path dir = Files.createDirectories(trabajo.resolve("script-" + script.getFileName().toString().replace('.', '-')));
            Escribir(dir.resolve("e1.mingol"), e1, CON_SALTO_FINAL.get("e1"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e1.mingol"), dir, "main-" + script.getFileName(), Entorno(script.toString(), system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 1, d, true);
            if (!r.stderr.contains("No se encontró a68g")) d.add("stderr sin 'No se encontró a68g'");
            if (!r.stderr.contains("A68G apunta a un archivo .bat/.cmd, que no se acepta")) d.add("stderr sin la frase de .bat/.cmd no aceptados");
            SigueVacio(script, d);
            ErroresIguales(dir.resolve("e1-errores.txt"), erroresRef, d);
            Registrar("main (8) A68G = " + script.getFileName() + " solo", d, r);
        }

        // (9) A68G = .bat + ficticio en el PATH: la búsqueda ignora el .bat y llega al PATH
        {
            Path dir = Files.createDirectories(trabajo.resolve("script-y-path"));
            Escribir(dir.resolve("e1.mingol"), e1, CON_SALTO_FINAL.get("e1"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e1.mingol"), dir, "main-bat-path",
                    Entorno(bat.toString(), ficticio.getParent().toString()));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 1, d, true);
            if (!r.stderr.contains("No se pudo iniciar a68g (" + ficticio + ")")) d.add("stderr sin 'No se pudo iniciar a68g (" + ficticio + ")'");
            SigueVacio(bat, d);
            SigueVacio(ficticio, d);
            Registrar("main (9) A68G = a68g.bat + ficticio en el PATH", d, r);
        }

        // Control final: scripts intactos y ninguna carpeta INYECTADO en todo el árbol temporal ni en el repo
        List<String> d = new ArrayList<>();
        SigueVacio(bat, d);
        SigueVacio(cmd, d);
        try (java.util.stream.Stream<Path> s = Files.walk(base)) {
            s.filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("INYECTADO")).forEach(p -> d.add("existe " + p));
        }
        SinInyectado(d, repo);
        Registrar("main control final (scripts con 0 bytes, sin INYECTADO)", d, null);
        ImprimirResumen("main");
    }

    // -cp build\classes Mingol.Mingol <args>: CarpetaAplicacion será build\classes (sin a68g)
    private static List<String> ConClases(Path clases, String... args) {
        List<String> l = new ArrayList<>(Arrays.asList("-cp", clases.toString(), "Mingol.Mingol"));
        l.addAll(Arrays.asList(args));
        return l;
    }

    // Entorno controlado del hijo: A68G (opcional) y PATH
    private static Map<String, String> Entorno(String a68g, String path) {
        Map<String, String> m = new LinkedHashMap<>();
        if (a68g != null) m.put("A68G", a68g);
        m.put("PATH", path);
        return m;
    }

    // Mingol.main en un hijo (R8) con el entorno vaciado y solo lo indispensable + lo controlado
    private static ResultadoHijo LanzarMain(List<String> argsJava, Path dir, String nombreLog, Map<String, String> entorno) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> comando = new ArrayList<>();
        comando.add(java);
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.equals("-ea") || a.equals("-enableassertions")) comando.add("-ea");
        }
        comando.add("--enable-preview");
        comando.add("-Xmx256m");
        comando.addAll(argsJava);
        ProcessBuilder pb = new ProcessBuilder(comando);
        Map<String, String> env = pb.environment();
        String raizSistema = env.get("SystemRoot");
        String temp = env.get("TEMP");
        // Nada heredado: ni el PATH del usuario, ni A68G, ni JAVA_TOOL_OPTIONS
        env.clear();
        if (raizSistema != null) env.put("SystemRoot", raizSistema);
        if (temp != null) {
            env.put("TEMP", temp);
            env.put("TMP", temp);
        }
        env.putAll(entorno);

        Path logs = Paths.get("").toAbsolutePath().resolve("logs");
        Files.createDirectories(logs);
        File out = logs.resolve(nombreLog + ".out").toFile();
        File err = logs.resolve(nombreLog + ".err").toFile();
        pb.directory(dir.toFile());
        pb.redirectOutput(out);
        pb.redirectError(err);
        long inicio = System.currentTimeMillis();
        Process p = pb.start();
        ResultadoHijo r = new ResultadoHijo();
        r.termino = p.waitFor(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
        if (!r.termino) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        } else {
            r.codigo = p.exitValue();
        }
        r.milis = System.currentTimeMillis() - inicio;
        // Mingol escribe con la codificación nativa (p. ej. "encontró"), no en UTF-8
        r.stdout = LeerRecortado(out.toPath(), 64 * 1024, Charset.defaultCharset());
        r.stderr = LeerRecortado(err.toPath(), 64 * 1024, Charset.defaultCharset());
        return r;
    }

    // Lee como mucho maxBytes con la codificación indicada
    private static String LeerRecortado(Path p, int maxBytes, Charset cs) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        return new String(b, 0, Math.min(b.length, maxBytes), cs);
    }

    // Terminó, con el código esperado, sin traza y (si se pide) sin la palabra Exception
    private static void ChequearSalida(ResultadoHijo r, int codigo, List<String> d, boolean sinException) {
        if (!r.termino) d.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        else if (r.codigo != codigo) d.add("código de salida " + r.codigo + " (se esperaba " + codigo + ")");
        String traza = PrimeraLineaCon(r.todo(), "\tat ");
        if (traza != null) d.add("traza: " + traza.trim());
        if (sinException) {
            String exc = PrimeraLineaCon(r.todo(), "Exception");
            if (exc != null) d.add("la salida contiene 'Exception': " + exc.trim());
        }
    }

    // El eco (java.exe) recibió exactamente la ruta absoluta del .a68, una sola vez
    private static void ChequearEco(ResultadoHijo r, Path a68, List<String> d) {
        // Lo que sigue a la marca es el argumento que recibió el eco, tal cual
        List<String> recibidos = new ArrayList<>();
        for (String l : r.stderr.split("\\R")) {
            int i = l.indexOf(ECO_MARCA);
            if (i >= 0) recibidos.add(l.substring(i + ECO_MARCA.length()));
        }
        if (recibidos.size() != 1) {
            d.add("el eco se invocó " + recibidos.size() + " veces; stderr: " + r.stderr.trim());
        } else if (!recibidos.get(0).equals(a68.toString())) {
            d.add("argumento recibido [" + recibidos.get(0) + "], esperado [" + a68 + "]");
        }
    }

    // Ninguna carpeta INYECTADO en las carpetas indicadas
    private static void SinInyectado(List<String> d, Path... carpetas) {
        for (Path c : carpetas) {
            if (Files.exists(c.resolve("INYECTADO"))) d.add("existe la carpeta " + c.resolve("INYECTADO"));
        }
    }

    // Procesos vivos cuya línea de comando contiene el texto (el eco no debe sobrevivir a main)
    private static long ProcesosVivosCon(String texto) {
        return ProcessHandle.allProcesses()
                .filter(ph -> ph.info().commandLine().map(cl -> cl.contains(texto)).orElse(false))
                .count();
    }

    // -errores.txt que produce TestProyecto para el mismo fuente (hijo de preservación, por reflexión)
    private static byte[] ErroresDeReferencia(Path dir, String id) throws Exception {
        Files.createDirectories(dir);
        Escribir(dir.resolve(id + ".mingol"), PROGRAMAS.get(id), CON_SALTO_FINAL.get(id));
        ResultadoHijo r = LanzarHijo(Arrays.asList("hijopres", id + ".mingol", id + ".mingol"), dir, "main-ref-" + id, MAX_META);
        Path errores = dir.resolve(id + "-errores.txt");
        if (!r.termino || !Files.exists(errores)) {
            System.err.println("ABORTADO: no se pudo obtener el -errores.txt de referencia de " + id);
            System.exit(2);
        }
        return Files.readAllBytes(errores);
    }

    private static void ErroresIguales(Path errores, byte[] referencia, List<String> d) throws IOException {
        if (!Files.exists(errores)) d.add("no existe " + errores.getFileName());
        else if (!Arrays.equals(Files.readAllBytes(errores), referencia)) d.add(errores.getFileName() + " distinto del de TestProyecto");
    }

    // jar --create con la herramienta jar del JDK, dentro de %TEMP% (nunca ant jar ni dist/). null si salió bien
    private static String CrearJar(Path jar, Path clases) throws Exception {
        Files.deleteIfExists(jar);
        String herramienta = Paths.get(System.getProperty("java.home"), "bin", "jar.exe").toString();
        ProcessBuilder pb = new ProcessBuilder(herramienta, "--create", "--file", jar.toString(),
                "--main-class", "Mingol.Mingol", "-C", clases.toString(), ".");
        pb.redirectErrorStream(true);
        Path log = Paths.get("").toAbsolutePath().resolve("logs").resolve("jar.log");
        Files.createDirectories(log.getParent());
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        // La herramienta jar no es el compilador: margen algo mayor que el de los hijos
        if (!p.waitFor(LIMITE_SEGUNDOS * 3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "jar no terminó";
        }
        if (p.exitValue() != 0 || !Files.exists(jar)) return "código " + p.exitValue() + ": " + LeerRecortado(log, 4096);
        return null;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static void EscribirProgramas(Path carpeta) throws IOException {
        for (Map.Entry<String, String[]> e : PROGRAMAS.entrySet()) {
            Escribir(carpeta.resolve(e.getKey() + ".mingol"), e.getValue(), CON_SALTO_FINAL.get(e.getKey()));
        }
    }

    // Une las líneas con \n; sin \n final si así lo indica el programa
    private static void Escribir(Path archivo, String[] lineas, boolean saltoFinal) throws IOException {
        String texto = String.join("\n", lineas) + (saltoFinal ? "\n" : "");
        Files.write(archivo, texto.getBytes(StandardCharsets.US_ASCII));
    }

    private static void BorrarSalidas(Path carpeta, String id) throws IOException {
        Files.deleteIfExists(carpeta.resolve(id + "-errores.txt"));
        Files.deleteIfExists(carpeta.resolve(id + ".a68"));
    }

    // Solo se usa con la carpeta propia del caso main, dentro de la carpeta temporal
    private static void VaciarCarpeta(Path dir) throws IOException {
        File[] hijos = dir.toFile().listFiles();
        if (hijos == null) return;
        for (File f : hijos) {
            if (f.isFile()) Files.delete(f.toPath());
        }
    }

    private static String LineaConPrefijo(String texto, String prefijo) {
        for (String l : texto.split("\\R")) {
            if (l.startsWith(prefijo)) return l;
        }
        return null;
    }

    private static List<String> LineasConPrefijo(String texto, String prefijo) {
        List<String> r = new ArrayList<>();
        for (String l : texto.split("\\R")) {
            if (l.startsWith(prefijo)) r.add(l.substring(prefijo.length()));
        }
        return r;
    }

    private static String PrimeraLineaCon(String texto, String parte) {
        for (String l : texto.split("\\R")) {
            if (l.contains(parte)) return l;
        }
        return null;
    }

    // Imprime OK/FALLA con las diferencias y lo agrega al resumen
    private static void Registrar(String caso, List<String> diferencias, ResultadoHijo r) {
        String info = (r == null) ? "" : " [termino=" + r.termino + ", codigo=" + r.codigo + ", " + r.milis + " ms]";
        if (diferencias.isEmpty()) {
            System.out.println("OK    " + caso + info);
            resumen.add("OK    " + caso);
        } else {
            fallas++;
            System.out.println("FALLA " + caso + info);
            for (String d : diferencias) System.out.println("      - " + d);
            resumen.add("FALLA " + caso + ": " + diferencias.get(0));
        }
    }
}
