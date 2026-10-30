// Arnés de la spec mingol-entrada-ejecucion-fix (área 5: entrada y ejecución).
// Clase sin paquete, como los otros arneses. Se compila contra build\classes y se ejecuta desde
// $env:TEMP\mingol-entrada-arnes: todos los archivos se crean ahí (nunca en el repositorio, R5).
//
// Modos:
//   exploracion       -> tarea 1: Property 1 (Bug Condition, Properties 1–5 del diseño) sobre los 35 casos
//                        de bugfix.md y d01–d16 del diseño. Corre main real en hijos con el eco de A68G y
//                        compara con la columna "Esperado". Se espera que FALLE en el código sin arreglar
//                        (0136e1b) en todos los casos de C y que pase solo el control f03.
//   capturar [--sobrescribir]
//                     -> tarea 2: captura en %TEMP%\mingol-entrada-golden (sobre 0136e1b, antes de tocar src/)
//                        del dominio de preservación (Property 6). No pisa una captura existente sin
//                        --sobrescribir (sale con 2) y se niega a capturar si Mingol ya tiene Preparar.
//   preservacion      -> tarea 2: Property 6, todo igual a la captura (casos, fuentes, TP|, -errores.txt byte a
//                        byte, código, stderr, eco) y al oráculo de preservación (M10, 3.1, 3.2, 3.3, 3.6).
//   hijoref <archivo> -> interno: TestProyecto(String) por reflexión en la carpeta actual; imprime TP|<booleano>.
//   unitarias         -> tarea 3.5: U1–U8 del diseño (funciones puras y E/S por reflexión en proceso; Preparar en
//                        hijos hijoprep y TestProyecto en hijos hijoref, R8). Incluye los generadores de nombres (200)
//                        y de contenidos (50) de Property-Based Tests. No depende de dist\ ni de la captura.
//   main              -> tarea 3.5: M1–M10 del diseño, main real en hijos con el eco (y M4 con un jar temporal
//                        armado con la herramienta jar del JDK desde build\classes). No depende de dist\.
//   hijoprep <argumento>
//                     -> interno: Preparar por reflexión en la carpeta actual; imprime PREPARAR|<ruta o null>.
//
// Eco (DQ6, DE9): A68G = java.exe del JDK y JDK_JAVA_OPTIONS=-javaagent:<eco.jar>. El agente EcoA68g
// (clase de nivel superior de este archivo) solo actúa si sun.java.command es la ruta absoluta de un
// archivo regular .a68: imprime ECO-A68G|<ruta> en stderr y sale con ECO_CODIGO (0 si falta).
// Nunca se ejecuta el a68g real y no se crea ningún .bat/.cmd (R10).
//
// Solo usa API presente antes y después del arreglo: main real en un hijo; TestProyecto y EjecutarA68g por
// reflexión solo como guardias; los métodos nuevos (CopiarA68) se buscan por reflexión y, si faltan, el caso
// da FALLA sin abortar.
// Seguridad (R8): cada hijo corre con la misma java.exe, --enable-preview, -Xmx256m, el mismo classpath
// (y -ea si el padre lo usa), stdin cerrado, waitFor(10 s) y destroyForcibly de los descendientes.
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random; // Área 5 (3.5): generadores con semilla fija
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public class PruebasEntrada {

    // Límite de tiempo por hijo (R8)
    private static final long LIMITE_SEGUNDOS = 10;

    // Sangría exacta de los mensajes en el -errores.txt (área 1)
    private static final String S8 = "        ";

    // Carpeta de trabajo, subcarpeta de la copia de proyecto.mingol y carpetas que no se tocan (R5)
    private static final String CARPETA_TRABAJO = "mingol-entrada-arnes";
    private static final String SUBCARPETA_PROYECTO = "proyecto";
    private static final String CARPETA_DIAG = "mingol-entrada-diag";
    private static final String CARPETA_DIS = "mingol-entrada-dis";

    // Codificación de stderr de los hijos (nativa de Windows) y del -errores.txt (R6)
    private static final Charset CP1252 = Charset.forName("windows-1252");

    // Prefijos del eco y de la nota del launcher que se descartan del stderr (R10)
    private static final String ECO = "ECO-A68G|";
    private static final String NOTA_JDK = "NOTE: Picked up JDK_JAVA_OPTIONS:";

    // Contenidos del diagnóstico (bugfix.md, "Cómo se diagnosticó")
    private static final List<String> VALIDO = L("BEGIN", "INT a;", "a := 1;", "print ( (a) )", "END");
    private static final List<String> ERRONEO = L("BEGIN", "INT a;", "a := zz;", "print ( (a) )", "END");
    private static final List<String> ADVERTENCIA = L("BEGIN", "INT a;", "PRAGMAT x;", "a := 1;", "print ( (a) )", "END");
    private static final String VIEJO_A68 = "contenido viejo de una corrida valida\r\n";

    // Resultados del modo actual, por grupo del resumen
    private static final List<String> resumen = new ArrayList<>();
    private static final Map<String, List<String>> diferenciasPorCaso = new LinkedHashMap<>();
    private static final Map<String, int[]> porGrupo = new LinkedHashMap<>(); // {total, fallas}
    private static final List<String> controlesQueFallan = new ArrayList<>();
    private static final List<String> noReproducidos = new ArrayList<>();
    private static int fallas = 0;

    // Estado de la exploración: carpeta base, eco y repositorio
    private static Path base;
    private static Path ecoJar;
    private static Path repo;

    // ------------------------------------------------------------------
    // Despacho por modo (área 5)
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: PruebasEntrada exploracion | capturar [--sobrescribir] | preservacion | unitarias"
                    + " | main | hijoprep <argumento> | hijoref <archivo>");
            System.exit(2);
        }
        switch (args[0]) {
            // Tarea 1: exploración de la condición del bug
            case "exploracion":
                Exploracion();
                break;
            // Tarea 2 (área 5): captura y preservación, y su hijo
            case "capturar":
                Capturar(args.length > 1 && args[1].equals("--sobrescribir"));
                break;
            case "preservacion":
                Preservacion();
                break;
            case "hijoref":
                if (args.length < 2) {
                    System.err.println("Uso: PruebasEntrada hijoref <archivo>");
                    System.exit(2);
                }
                HijoRef(args[1]);
                break;
            // Tarea 3.5 (área 5): unitarias U1–U8, main M1–M10 y el interno hijoprep
            case "unitarias":
                Unitarias();
                break;
            case "main":
                ModoMain();
                break;
            case "hijoprep":
                if (args.length < 2) {
                    System.err.println("Uso: PruebasEntrada hijoprep <argumento>");
                    System.exit(2);
                }
                HijoPrep(args[1]);
                break;
            default:
                System.err.println("Modo desconocido: " + args[0]);
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Mensajes esperados (tabla final de códigos y "Mensajes de consola" del diseño)
    // ------------------------------------------------------------------

    private static String Err(int codigo, String texto, String lexema) {
        return String.format("ERROR %03d: %s [%s].", codigo, texto, lexema);
    }

    private static String Adv(String lexema) {
        return "Advertencia: instrucción no es soportada por esta versión [" + lexema + "].";
    }

    private static String E111(String lex) { return Err(111, "Texto de más de 30 caracteres", lex); }
    private static String E115(String lex) { return Err(115, "Nombre de archivo inválido", lex); }
    private static String E303(String lex) { return Err(303, "Identificador no encontrado", lex); }
    private static String E307() { return Err(307, "No se encontró una sentencia BEGIN", "BEGIN"); }
    private static String E308() { return Err(308, "No se encontró una sentencia END", "END"); }

    // Bloque de entrada (E19 a–d) con <ruta> según DQ2
    private static String NoEncontrado(String ruta) { return "No se encontró el archivo fuente: " + ruta; }
    private static String NoEsArchivo(String ruta) { return "No se pudo leer el archivo fuente: " + ruta + " (no es un archivo)"; }
    private static String NoLeido(String ruta, String motivo) { return "No se pudo leer el archivo fuente: " + ruta + " (" + motivo + ")"; }
    private static String RutaInvalida(String argumento) {
        String motivo;
        try {
            Paths.get(argumento);
            motivo = "(Paths.get no lanzó en el arnés)";
        } catch (InvalidPathException ex) {
            motivo = ex.getMessage();
        }
        return "No se pudo leer el archivo fuente: " + argumento + " (ruta inválida: " + motivo + ")";
    }
    // Avisos de E16, E17 y E21
    private static String NoErrores(String nombre, String motivo) { return "No se pudo escribir el archivo de errores: " + nombre + " (" + motivo + ")"; }
    private static String NoGenerado(String a68, String motivo) { return "No se pudo generar " + a68 + ": " + motivo; }
    private static String NoGeneradoLargo(String a68, String motivo) {
        return NoGenerado(a68, motivo) + "; puede quedar un " + a68 + " anterior que no corresponde a este fuente";
    }
    private static String NoBorrado(String a68, String motivo) { return "No se pudo borrar " + a68 + " anterior: " + motivo; }

    // -errores.txt completo: copia numerada, mensajes bajo su línea y bloque final
    private static String Errores(List<String> lineas, Map<Integer, List<String>> bajo, String... finales) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lineas.size(); i++) {
            sb.append(String.format("%05d", i)).append(' ').append(lineas.get(i - 1)).append('\n');
            for (String m : bajo.getOrDefault(i, Collections.emptyList())) sb.append(S8).append(m).append('\n');
        }
        for (String m : finales) sb.append(S8).append(m).append('\n');
        return sb.toString();
    }

    private static Map<Integer, List<String>> Bajo(Object... pares) {
        Map<Integer, List<String>> m = new TreeMap<>();
        for (int i = 0; i < pares.length; i += 2) m.computeIfAbsent((Integer) pares[i], k -> new ArrayList<>()).add((String) pares[i + 1]);
        return m;
    }

    private static Map<Integer, List<String>> SinMensajes() { return new TreeMap<>(); }
    private static Map<Integer, List<String>> Bajo303() { return Bajo(3, E303("zz")); }

    // Fuente con \r\n y, si se pide, salto final (formato del diagnóstico)
    private static byte[] Crlf(List<String> lineas, boolean saltoFinal) {
        String s = String.join("\r\n", lineas) + (saltoFinal && !lineas.isEmpty() ? "\r\n" : "");
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] Crlf(List<String> lineas) { return Crlf(lineas, true); }

    // ------------------------------------------------------------------
    // Casos (área 5): preparación, argumento y lo esperado
    // ------------------------------------------------------------------

    // Retiene un recurso del arnés mientras corre el hijo (d10, d11, d13)
    private interface Retener {
        AutoCloseable Abrir(Path dir) throws Exception;
    }

    private static class Caso {
        final String id;
        final String grupo;          // "control", "bugfix" o "diseño"
        Path dirAbsoluto;            // p01: la subcarpeta proyecto; el resto, exploracion\<id>
        final Map<String, byte[]> archivos = new LinkedHashMap<>();
        final List<String> carpetas = new ArrayList<>();
        String cwd = "";
        List<String> argsRecibidos;  // lo que recibe main (null = sin argumento)
        List<String> argsEnviados;   // lo que se pasa a ProcessBuilder (a"b se envía como a\"b)
        boolean jar;
        int ecoCodigo = 0;
        String a68gEnv;              // área 5 (tarea 2): valor de A68G (null = java.exe, el eco)
        Path jarRuta;                // área 5 (3.5, M4): jar temporal (null = dist\Mingol.jar)
        boolean prep;                // área 5 (3.5, U7): el hijo es hijoprep (Preparar), no main
        String preparar;             // U7: lo que debe imprimir hijoprep tras "PREPARAR|"
        String rutaDQ2;              // U7 (b)–(d): <ruta> del aviso = RutaEfectiva(argumento), nunca absoluta (DQ2)
        String dispositivo;          // área 5 (CI #9): ruta efectiva de un dispositivo; su rama (b)/(c) la decide el SO
        String fuenteDiag;           // fuente que se compara con el diagnóstico (bugfix.md)
        Retener retener;
        // Esperado
        int codigo;
        final List<String> stderr = new ArrayList<>();
        String ecoRel;               // .a68 que recibe a68g (relativo a la carpeta del caso) o null
        final Map<String, String> errores = new LinkedHashMap<>();
        final Map<String, String> a68 = new LinkedHashMap<>(); // .a68 -> fuente
        final Set<String> agrega = new LinkedHashSet<>();
        final Set<String> quita = new LinkedHashSet<>();

        Caso(String id, String grupo) {
            this.id = id;
            this.grupo = grupo;
        }

        Caso arg(String a) {
            argsRecibidos = L(a);
            argsEnviados = L(a);
            return this;
        }
    }

    private static final List<Caso> casos = new ArrayList<>();

    private static Caso Nuevo(String id, String grupo) {
        Caso c = new Caso(id, grupo);
        casos.add(c);
        return c;
    }

    // Programa sin ERROR: -errores.txt y .a68 en la carpeta actual, eco con su ruta, código del eco
    private static Caso Valido(String id, String grupo, String fuente, byte[] bytes, List<String> lineas,
            Map<Integer, List<String>> bajo, String cwd, String arg, String nombre) {
        Caso c = Nuevo(id, grupo).arg(arg);
        c.archivos.put(fuente, bytes);
        c.fuenteDiag = fuente;
        c.cwd = cwd;
        String pre = cwd.isEmpty() ? "" : cwd + "\\";
        c.codigo = 0;
        c.errores.put(pre + nombre + "-errores.txt", Errores(lineas, bajo));
        c.a68.put(pre + nombre + ".a68", fuente);
        c.ecoRel = pre + nombre + ".a68";
        c.agrega.add(pre + nombre + "-errores.txt");
        c.agrega.add(pre + nombre + ".a68");
        return c;
    }

    private static Caso Valido(String id, String archivo, String arg, String nombre) {
        return Valido(id, "bugfix", archivo, Crlf(VALIDO), VALIDO, SinMensajes(), "", arg, nombre);
    }

    // Nombre o extensión inválidos: 115 en el bloque final, sin .a68 ni a68g, sale 1 (E1, E4, E13)
    private static Caso Invalido(String id, String grupo, String archivo, byte[] bytes, List<String> lineas,
            Map<Integer, List<String>> bajo, String nombre, String lexema, String... finalesExtra) {
        Caso c = Nuevo(id, grupo).arg(archivo);
        c.archivos.put(archivo, bytes);
        c.fuenteDiag = archivo;
        c.codigo = 1;
        List<String> finales = new ArrayList<>();
        finales.add(E115(lexema));
        finales.addAll(Arrays.asList(finalesExtra));
        c.errores.put(nombre + "-errores.txt", Errores(lineas, bajo, finales.toArray(new String[0])));
        c.agrega.add(nombre + "-errores.txt");
        return c;
    }

    private static Caso Invalido(String id, String archivo, String nombre) {
        return Invalido(id, "bugfix", archivo, Crlf(VALIDO), VALIDO, SinMensajes(), nombre, archivo);
    }

    // Área 5 (CI #9): rama de un nombre de dispositivo según el SO (DQ4), con Paths.get(ruta efectiva) en la
    // carpeta del hijo: no existe -> (b); existe y no es regular -> (c); regular -> FALLA (el SO entregó un archivo)
    private static String RamaDispositivo(Path cwd, String ruta, List<String> dif) {
        Path p = cwd.resolve(Paths.get(ruta));
        if (!Files.exists(p)) return NoEncontrado(ruta);
        if (!Files.isRegularFile(p)) return NoEsArchivo(ruta);
        dif.add("dispositivo " + ruta + ": el SO lo entrega como archivo regular, no cae en (b) ni en (c)");
        return null;
    }

    // Área 5 (CI #9): marca un caso de dispositivo; el mensaje fijo queda solo como referencia de este equipo
    private static Caso Dispositivo(Caso c, String ruta) {
        c.dispositivo = ruta;
        return c;
    }

    // Bloque de entrada fallido (E7, E18, E19): mensaje en stderr, sale 1, nada creado ni tocado
    private static Caso Entrada(String id, String grupo, String arg, String mensaje) {
        Caso c = Nuevo(id, grupo).arg(arg);
        c.codigo = 1;
        c.stderr.add(mensaje);
        return c;
    }

    // Los 35 casos de bugfix.md y d01–d16 del diseño
    private static void ArmarCasos(Map<String, String> motivos) throws IOException {
        // --- Nombres (REQ-INPUT-01) ---
        Valido("n01", "CalcularInflacion.MINGOL", "CalcularInflacion.MINGOL", "CalcularInflacion");
        Valido("n02", "Calcular_Inflacion1.MINGOL", "Calcular_Inflacion1.MINGOL", "Calcular_Inflacion1");
        Invalido("n03", "1Calcular.MINGOL", "1Calcular");
        Invalido("n04", "_Calcular.MINGOL", "_Calcular");
        Invalido("n05", "Calcular_.MINGOL", "Calcular_");
        Invalido("n06", "Calcular Inflacion.MINGOL", "Calcular Inflacion");
        Valido("n07", "Abcdefghijklmnopqrst.MINGOL", "Abcdefghijklmnopqrst.MINGOL", "Abcdefghijklmnopqrst");
        Invalido("n08", "Abcdefghijklmnopqrstu.MINGOL", "Abcdefghijklmnopqrstu");
        Invalido("n09", "Calc-ular.MINGOL", "Calc-ular");
        Invalido("n10", "Calc%1.MINGOL", "Calc%1");
        Invalido("n11", "a.b.MINGOL", "a.b");
        Valido("n12", "PROG.MINGOL", "PROG.MINGOL", "PROG").archivos.put("prog2.MINGOL", Crlf(VALIDO));

        // --- Extensión ---
        Valido("x01", "SinExt.MINGOL", "SinExt", "SinExt");
        Valido("x02", "Minus.mingol", "Minus.mingol", "Minus");
        Valido("x03", "Mixta.MinGol", "Mixta.MinGol", "Mixta");
        Invalido("x04", "Otro.txt", "Otro");
        // x05: la guardia MismoArchivo protege el fuente, sin aviso (E12)
        Invalido("x05", "Otro2.a68", "Otro2");
        Caso x06 = Entrada("x06", "bugfix", "Punto.", NoEncontrado("Punto."));
        x06.archivos.put("Punto.MINGOL", Crlf(VALIDO));
        x06.fuenteDiag = "Punto.MINGOL";
        // x07: nombre vacío, -errores.txt tal cual (E14)
        Invalido("x07", ".MINGOL", "");
        Invalido("x08", "Prog.MINGOL.txt", "Prog.MINGOL");
        Caso x09 = Entrada("x09", "bugfix", "SinExt2", NoEncontrado("SinExt2.MINGOL"));
        x09.archivos.put("SinExt2", Crlf(VALIDO));
        x09.fuenteDiag = "SinExt2";

        // --- Rutas con carpetas (E5, E8) ---
        Valido("r01", "bugfix", "sub\\Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "", "sub\\Prog.MINGOL", "Prog");
        Valido("r02", "bugfix", "car.peta\\Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "", "car.peta\\Prog", "Prog");
        Caso r03 = Valido("r03", "bugfix", "fuente\\Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "trabajo", "..\\fuente\\Prog.MINGOL", "Prog");
        r03.carpetas.add("trabajo");
        Valido("r04", "bugfix", "1mal\\Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "", "1mal\\Prog.MINGOL", "Prog");

        // --- Archivo inexistente y control sin argumento ---
        Entrada("f01", "bugfix", "NoExiste.MINGOL", NoEncontrado("NoExiste.MINGOL"));
        Entrada("f02", "bugfix", "NoExiste", NoEncontrado("NoExiste.MINGOL"));
        Caso f03 = Nuevo("f03", "control");
        f03.codigo = 1;
        f03.stderr.add("Uso: java -jar MINGOL.jar <Programa>.MINGOL");

        // --- Condición de ejecución (REQ-EXEC-01, E6) ---
        Caso e01 = Nuevo("e01", "bugfix").arg("ConErr.MINGOL");
        e01.archivos.put("ConErr.MINGOL", Crlf(ERRONEO));
        e01.fuenteDiag = "ConErr.MINGOL";
        e01.codigo = 1;
        e01.errores.put("ConErr-errores.txt", Errores(ERRONEO, Bajo303()));
        e01.agrega.add("ConErr-errores.txt");
        Caso e02 = Nuevo("e02", "bugfix").arg("Viejo.MINGOL");
        e02.archivos.put("Viejo.MINGOL", Crlf(ERRONEO));
        e02.archivos.put("Viejo.a68", VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        e02.fuenteDiag = "Viejo.MINGOL";
        e02.codigo = 1;
        e02.errores.put("Viejo-errores.txt", Errores(ERRONEO, Bajo303()));
        e02.agrega.add("Viejo-errores.txt");
        e02.quita.add("Viejo.a68");
        Valido("e03", "bugfix", "Adv.MINGOL", Crlf(ADVERTENCIA), ADVERTENCIA, Bajo(3, Adv("PRAGMAT")), "", "Adv.MINGOL", "Adv");
        Invalido("e04", "bugfix", "Bad Name.MINGOL", Crlf(ERRONEO), ERRONEO, Bajo303(), "Bad Name", "Bad Name.MINGOL");

        // --- Jar de 0136e1b (REQ-EXEC-02) ---
        Caso j01 = Valido("j01", "bugfix", "fuente\\Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "trabajo", "..\\fuente\\Prog.MINGOL", "Prog");
        j01.carpetas.add("trabajo");
        j01.jar = true;
        Valido("j02", "Prog.MINGOL", "Prog.MINGOL", "Prog").jar = true;

        // --- Copia de proyecto.mingol (2.12): solo los tres 111 y las dos advertencias, sin .a68 ---
        Caso p01 = Nuevo("p01", "bugfix").arg("proyecto.mingol");
        byte[] proyecto = Files.readAllBytes(repo.resolve("proyecto.mingol"));
        p01.archivos.put("proyecto.mingol", proyecto);
        p01.fuenteDiag = "proyecto\\proyecto.mingol";
        p01.dirAbsoluto = base.resolve(SUBCARPETA_PROYECTO);
        p01.codigo = 1;
        List<String> lp = Files.readAllLines(repo.resolve("proyecto.mingol"), StandardCharsets.ISO_8859_1);
        Map<Integer, List<String>> bp = Bajo(2, Adv("PRAGMAT"), 3, Adv("MODE"));
        for (int n : new int[] {21, 49, 59}) bp.computeIfAbsent(n, k -> new ArrayList<>()).add(E111(TextoEntreComillas(lp.get(n - 1))));
        p01.errores.put("proyecto-errores.txt", Errores(lp, bp));
        p01.agrega.add("proyecto-errores.txt");

        // --- d01: dispositivos con o sin extensión caen en (b) (DQ4) ---
        String[][] d01 = {{"CON.MINGOL", "CON.MINGOL"}, {"CON", "CON.MINGOL"}, {"con.mingol", "con.mingol"},
                {"AUX", "AUX.MINGOL"}, {"COM1", "COM1.MINGOL"}, {"LPT1.MINGOL", "LPT1.MINGOL"}, {"NUL.txt", "NUL.txt"}};
        // Área 5 (CI #9): la rama (b)/(c) de cada dispositivo la decide el SO al correr (RamaDispositivo)
        for (int i = 0; i < d01.length; i++)
            Dispositivo(Entrada("d01" + (char) ('a' + i), "diseño", d01[i][0], NoEncontrado(d01[i][1])), d01[i][1]);
        // --- d02: NUL (se abre NUL.MINGOL) y NUL. (en este equipo b y c; en el runner del CI, b y b) ---
        Dispositivo(Entrada("d02a", "diseño", "NUL", NoEncontrado("NUL.MINGOL")), "NUL.MINGOL");
        Dispositivo(Entrada("d02b", "diseño", "NUL.", NoEsArchivo("NUL.")), "NUL.");
        // --- d03: carpeta carpeta.MINGOL, rama (c) ---
        Entrada("d03", "diseño", "carpeta", NoEsArchivo("carpeta.MINGOL")).carpetas.add("carpeta.MINGOL");
        // --- d04: InvalidPathException, rama (a) con el argumento tal cual ---
        String[] d04 = {"a?b.MINGOL", "a*b", "a<b", "a|b", "a\"b", "sub\\x:y.MINGOL", "Prog "};
        for (int i = 0; i < d04.length; i++) {
            Caso c = Entrada("d04" + (char) ('a' + i), "diseño", d04[i], RutaInvalida(d04[i]));
            // Windows quita una comilla sin escapar: se envía a\"b para que main reciba a"b
            if (d04[i].contains("\"")) c.argsEnviados = L(d04[i].replace("\"", "\\\""));
        }
        // --- d05: unidad inexistente, rama (b) sin toAbsolutePath (DE3) ---
        Entrada("d05", "diseño", "a:b.MINGOL", NoEncontrado("a:b.MINGOL"));
        // --- d06: Punto. con Punto existente (E15) ---
        Caso d06 = Nuevo("d06", "diseño").arg("Punto.");
        d06.archivos.put("Punto", Crlf(VALIDO));
        d06.codigo = 1;
        d06.errores.put("Punto-errores.txt", Errores(VALIDO, SinMensajes(), E115("Punto.")));
        d06.agrega.add("Punto-errores.txt");
        // --- d07: \r\n sin salto final, copia byte a byte (E10) ---
        Valido("d07", "diseño", "Crlf.MINGOL", Crlf(VALIDO, false), VALIDO, SinMensajes(), "", "Crlf.MINGOL", "Crlf");
        // --- d08: carpeta Prog-errores.txt con un programa válido (E16) ---
        Caso d08 = Nuevo("d08", "diseño").arg("Prog.MINGOL");
        d08.archivos.put("Prog.MINGOL", Crlf(VALIDO));
        d08.carpetas.add("Prog-errores.txt");
        d08.codigo = 1;
        d08.stderr.add(NoErrores("Prog-errores.txt", motivos.get("errores")));
        // --- d09: lo mismo con un programa erróneo y un Prog.a68 viejo, que se borra (E17) ---
        Caso d09 = Nuevo("d09", "diseño").arg("Prog.MINGOL");
        d09.archivos.put("Prog.MINGOL", Crlf(ERRONEO));
        d09.archivos.put("Prog.a68", VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        d09.carpetas.add("Prog-errores.txt");
        d09.codigo = 1;
        d09.stderr.add(NoErrores("Prog-errores.txt", motivos.get("errores")));
        d09.quita.add("Prog.a68");
        // --- d10: erróneo con Viejo.a68 abierto por el arnés (E21) ---
        Caso d10 = Nuevo("d10", "diseño").arg("Viejo.MINGOL");
        d10.archivos.put("Viejo.MINGOL", Crlf(ERRONEO));
        d10.archivos.put("Viejo.a68", VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        d10.codigo = 1;
        d10.errores.put("Viejo-errores.txt", Errores(ERRONEO, Bajo303()));
        d10.agrega.add("Viejo-errores.txt");
        d10.stderr.add(NoBorrado("Viejo.a68", motivos.get("borrar")));
        d10.retener = dir -> new RandomAccessFile(dir.resolve("Viejo.a68").toFile(), "r");
        // --- d11: válido con Prog.a68 viejo abierto por el arnés: copia y borrado fallan (E17) ---
        Caso d11 = Nuevo("d11", "diseño").arg("Prog.MINGOL");
        d11.archivos.put("Prog.MINGOL", Crlf(VALIDO));
        d11.archivos.put("Prog.a68", VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        d11.codigo = 1;
        d11.errores.put("Prog-errores.txt", Errores(VALIDO, SinMensajes()));
        d11.agrega.add("Prog-errores.txt");
        d11.stderr.add(NoGeneradoLargo("Prog.a68", motivos.get("copiar")));
        d11.retener = dir -> new RandomAccessFile(dir.resolve("Prog.a68").toFile(), "r");
        // --- d12: Prog.a68 es una carpeta con contenido (DQ3) ---
        Caso d12 = Nuevo("d12", "diseño").arg("Prog.MINGOL");
        d12.archivos.put("Prog.MINGOL", Crlf(VALIDO));
        d12.archivos.put("Prog.a68\\x.txt", "x".getBytes(StandardCharsets.ISO_8859_1));
        d12.codigo = 1;
        d12.errores.put("Prog-errores.txt", Errores(VALIDO, SinMensajes()));
        d12.agrega.add("Prog-errores.txt");
        d12.stderr.add(NoGeneradoLargo("Prog.a68", "La carpeta no está vacía"));
        // --- d13: fuente con un bloqueo exclusivo del arnés, rama (d) ---
        Caso d13 = Nuevo("d13", "diseño").arg("Prog.MINGOL");
        d13.archivos.put("Prog.MINGOL", Crlf(VALIDO));
        d13.codigo = 1;
        d13.stderr.add(NoLeido("Prog.MINGOL", motivos.get("leer")));
        d13.retener = dir -> {
            RandomAccessFile raf = new RandomAccessFile(dir.resolve("Prog.MINGOL").toFile(), "rw");
            FileLock lock = raf.getChannel().lock();
            return () -> { lock.release(); raf.close(); };
        };
        // d14 va aparte (CopiarA68 por reflexión, en proceso)
        // --- d15: 1a.MINGOL vacío: 115, 307, 308 en ese orden (E20) ---
        Invalido("d15", "diseño", "1a.MINGOL", new byte[0], L(), SinMensajes(), "1a", "1a.MINGOL", E307(), E308());
        // --- d16: el código de a68g se propaga literal (E3) ---
        for (int codigo : new int[] {0, 2, 300, -5, 65535}) {
            Caso c = Valido("d16" + (codigo < 0 ? "m" + (-codigo) : "_" + codigo), "diseño", "Prog.MINGOL", Crlf(VALIDO),
                    VALIDO, SinMensajes(), "", "Prog.MINGOL", "Prog");
            c.ecoCodigo = codigo;
            c.codigo = codigo;
        }
    }

    // ------------------------------------------------------------------
    // Tarea 1: exploración (área 5)
    // ------------------------------------------------------------------

    private static void Exploracion() throws Exception {
        base = CarpetaSegura();
        repo = RaizRepositorio();
        Guardias();
        Path trabajo = base.resolve("exploracion");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        ecoJar = ArmarEco();

        // Diagnóstico de la spec (solo lectura, para avisar si un fuente difiere)
        Path diag = CarpetaTemp().resolve(CARPETA_DIAG);
        if (!Files.isDirectory(diag)) System.out.println("AVISO: no existe " + diag + "; los fuentes salen de las constantes");

        // Motivos del sistema de archivos para las mismas situaciones (dependen del idioma de Windows)
        Map<String, String> motivos = Motivos(trabajo.resolve("_motivos"));
        System.out.println("Motivos de este equipo: " + motivos);

        ArmarCasos(motivos);
        for (Caso c : casos) {
            Path dir = c.dirAbsoluto != null ? c.dirAbsoluto : trabajo.resolve(c.id);
            BorrarArbol(dir);
            Files.createDirectories(dir);
            List<String> dif = new ArrayList<>();
            Correr(c, dir, trabajo.resolve("logs"), dif);
            if (c.fuenteDiag != null) AvisoDiagnostico(diag, c, dir);
            Registrar(c.id, c.grupo, dif);
        }
        D14(trabajo.resolve("d14"));

        Causas();
        ImprimirResumen("exploracion");
    }

    // Prepara la carpeta del caso, corre main en un hijo y compara con lo esperado
    private static void Correr(Caso c, Path dir, Path logs, List<String> dif) throws Exception {
        for (String d : c.carpetas) Files.createDirectories(dir.resolve(d));
        for (Map.Entry<String, byte[]> e : c.archivos.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        Set<String> antes = Entradas(dir);
        Map<String, byte[]> originales = new LinkedHashMap<>(c.archivos);

        ResultadoHijo r;
        AutoCloseable retenido = c.retener != null ? c.retener.Abrir(dir) : null;
        try {
            // Área 5 (3.5): U7 corre Preparar en hijoprep; el resto, main real
            r = c.prep ? LanzarInterno("hijoprep", c.id, c.argsEnviados, dir.resolve(c.cwd), logs)
                    : LanzarMain(c, dir.resolve(c.cwd), logs);
        } finally {
            if (retenido != null) retenido.close();
        }

        // Código de salida
        if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        else if (r.codigo != c.codigo) dif.add("código de salida: esperado " + c.codigo + ", obtenido " + r.codigo);

        // stderr: sin la nota del launcher; líneas del eco aparte; sin traza
        List<String> ecos = new ArrayList<>();
        List<String> resto = new ArrayList<>();
        for (String l : r.stderr.split("\\R", -1)) {
            if (l.startsWith(NOTA_JDK)) continue;
            if (l.startsWith(ECO)) ecos.add(l.substring(ECO.length()));
            else resto.add(l);
        }
        while (!resto.isEmpty() && resto.get(resto.size() - 1).isEmpty()) resto.remove(resto.size() - 1);
        boolean traza = false;
        for (String l : resto) if (l.startsWith("\tat ") || l.contains("GRAVE") || l.contains("Exception")) traza = true;
        if (traza) dif.add("stderr con traza: " + Primeras(resto, 3));
        // Área 5 (CI #9): con un dispositivo, la rama esperada se calcula en la carpeta del hijo, como los motivos
        List<String> stderrEsperado = c.stderr;
        if (c.dispositivo != null) {
            String m = RamaDispositivo(dir.resolve(c.cwd), c.dispositivo, dif);
            if (m != null) stderrEsperado = L(m);
        }
        if (!resto.equals(stderrEsperado)) dif.add("stderr: esperado " + stderrEsperado + ", obtenido " + Primeras(resto, 4));
        if (c.prep) {
            // Área 5 (3.5, U7): resultado de Preparar y <ruta> de DQ2
            String sal = r.stdout.replaceAll("\\R+$", "");
            if (!sal.equals("PREPARAR|" + c.preparar)) dif.add("hijoprep: esperado [PREPARAR|" + c.preparar + "], obtenido [" + Escapar(sal) + "]");
            if (c.rutaDQ2 != null) {
                Object ef = Invocar(Metodo("RutaEfectiva", String.class), c.argsRecibidos.get(0));
                if (!c.rutaDQ2.equals(ef)) dif.add("DQ2: RutaEfectiva = [" + ef + "], el aviso usa [" + c.rutaDQ2 + "]");
                if (Paths.get(c.rutaDQ2).isAbsolute()) dif.add("DQ2: <ruta> absoluta " + c.rutaDQ2);
            }
        } else if (!r.stdout.isEmpty()) dif.add("stdout no vacío: " + Escapar(r.stdout.length() > 200 ? r.stdout.substring(0, 200) : r.stdout));

        // Invocaciones del eco (exactamente una, con la ruta absoluta del .a68 de la carpeta actual)
        List<String> ecoEsperado = c.ecoRel == null ? L() : L(dir.resolve(c.ecoRel).toString());
        if (!ecos.equals(ecoEsperado)) dif.add("eco (a68g): esperado " + ecoEsperado + ", obtenido " + ecos);

        // Archivos de la carpeta del caso
        Set<String> esperado = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        esperado.addAll(antes);
        esperado.removeAll(c.quita);
        esperado.addAll(c.agrega);
        Set<String> obtenido = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        obtenido.addAll(Entradas(dir));
        List<String> faltan = new ArrayList<>(), sobran = new ArrayList<>();
        for (String e : esperado) if (!obtenido.contains(e)) faltan.add(e);
        for (String o : obtenido) if (!esperado.contains(o)) sobran.add(o);
        if (!faltan.isEmpty() || !sobran.isEmpty()) dif.add("archivos: faltan " + faltan + ", sobran " + sobran);

        // -errores.txt completo (R6: ISO-8859-1)
        for (Map.Entry<String, String> e : c.errores.entrySet()) CompararErrores(dir.resolve(e.getKey()), e.getKey(), e.getValue(), dif);

        // .a68 copia exacta del fuente (E10)
        for (Map.Entry<String, String> e : c.a68.entrySet()) {
            Path a = dir.resolve(e.getKey());
            Path f = dir.resolve(e.getValue());
            if (!Files.isRegularFile(a)) continue; // ya figura en "archivos"
            long m = Files.mismatch(f, a);
            if (m != -1) dif.add(".a68 " + e.getKey() + ": no es copia exacta (primer byte distinto " + m + "; fuente "
                    + Files.size(f) + " bytes, .a68 " + Files.size(a) + " bytes)");
        }

        // Archivos previos que no deben cambiar (el fuente siempre; E12)
        for (Map.Entry<String, byte[]> e : originales.entrySet()) {
            if (c.quita.contains(e.getKey())) continue;
            Path p = dir.resolve(e.getKey());
            if (Files.isRegularFile(p) && !Arrays.equals(e.getValue(), Files.readAllBytes(p))) {
                dif.add("archivo previo modificado: " + e.getKey() + " (" + e.getValue().length + " -> " + Files.size(p) + " bytes)");
            }
        }
    }

    // Compara el -errores.txt obtenido con el esperado completo
    private static void CompararErrores(Path p, String nombre, String esperado, List<String> dif) throws IOException {
        if (!Files.isRegularFile(p)) {
            dif.add("no se generó " + nombre + " (mensajes esperados: " + Mensajes(esperado) + ")");
            return;
        }
        String obtenido = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        if (!esperado.equals(obtenido)) {
            dif.add(nombre + ": " + PrimeraDiferencia(esperado, obtenido));
            dif.add("mensajes esperados: " + Mensajes(esperado) + ", obtenidos: " + Mensajes(obtenido));
        }
    }

    // d14: CopiarA68 con fuente inexistente y .a68 viejo, por reflexión (en 0136e1b no existe)
    private static void D14(Path dir) throws Exception {
        List<String> dif = new ArrayList<>();
        BorrarArbol(dir);
        Files.createDirectories(dir);
        Path a68 = dir.resolve("Prog.a68");
        Files.write(a68, VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        Method m = null;
        try {
            m = Class.forName("Mingol.Mingol").getDeclaredMethod("CopiarA68", Path.class, Path.class);
        } catch (NoSuchMethodException ex) {
            dif.add("no existe Mingol.CopiarA68(Path, Path)");
        }
        if (m != null) {
            m.setAccessible(true);
            // Área 5 (3.5): oráculo ajustado a DQ7 (aviso largo y viejo intacto)
            ComprobarD14(m, dir, true, dif);
        }
        Registrar("d14", "diseño", dif);
    }

    // d14 (DQ7): CopiarA68 con fuente inexistente. Con .a68 viejo, MismoArchivo lanza NoSuchFileException en las dos
    // guardias: aviso largo y el viejo intacto byte a byte (DE6). Sin viejo: aviso corto y sin .a68 (DE5)
    private static void ComprobarD14(Method m, Path dir, boolean conViejo, List<String> dif) throws Exception {
        BorrarArbol(dir);
        Files.createDirectories(dir);
        Path a68 = dir.resolve("Prog.a68");
        byte[] viejo = VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1);
        if (conViejo) Files.write(a68, viejo);
        Object aviso = Invocar(m, dir.resolve("NoExiste.MINGOL"), a68);
        String esperado = conViejo ? NoGeneradoLargo("Prog.a68", "No existe") : NoGenerado("Prog.a68", "No existe");
        if (!esperado.equals(aviso)) dif.add("aviso: esperado [" + esperado + "], obtenido [" + aviso + "]");
        if (conViejo) {
            if (!Files.isRegularFile(a68) || !Arrays.equals(viejo, Files.readAllBytes(a68))) dif.add("el Prog.a68 viejo no quedó intacto (DQ7, DE6)");
        } else if (Files.exists(a68)) {
            dif.add("quedó un Prog.a68 sin fuente");
        }
    }

    // Avisa si un fuente del caso difiere byte a byte del diagnóstico
    private static void AvisoDiagnostico(Path diag, Caso c, Path dir) throws IOException {
        Path d = c.id.equals("p01") ? diag.resolve("p01").resolve(c.fuenteDiag) : diag.resolve(c.id).resolve(c.fuenteDiag);
        if (!c.grupo.equals("bugfix") || !Files.isRegularFile(d)) return;
        String clave = c.id.equals("p01") ? "proyecto.mingol" : c.fuenteDiag;
        byte[] propio = c.archivos.get(clave);
        if (propio != null && !Arrays.equals(propio, Files.readAllBytes(d))) {
            System.out.println("AVISO: " + c.id + "\\" + clave + " difiere de " + d);
        }
    }

    // Motivos (DQ1) de las mismas situaciones, calculados en este equipo con el oráculo del arnés
    // Avisos: el recurso del try se abre solo para provocar el fallo que se prueba (no se lee)
    @SuppressWarnings("try")
    private static Map<String, String> Motivos(Path dir) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        BorrarArbol(dir);
        Files.createDirectories(dir);
        // d08/d09: FileWriter sobre una carpeta (como ArchivoErrores.Escribir)
        Path carpeta = dir.resolve("X-errores.txt");
        Files.createDirectories(carpeta);
        try (FileWriter w = new FileWriter(carpeta.toString())) {
            m.put("errores", "(FileWriter no lanzó)");
        } catch (IOException ex) {
            m.put("errores", MotivoArnes(ex));
        }
        // d10: deleteIfExists con el archivo abierto
        Path abierto = dir.resolve("Abierto.a68");
        Files.write(abierto, VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        Path fuente = dir.resolve("Fuente.MINGOL");
        Files.write(fuente, Crlf(VALIDO));
        try (RandomAccessFile raf = new RandomAccessFile(abierto.toFile(), "r")) {
            try {
                Files.deleteIfExists(abierto);
                m.put("borrar", "(deleteIfExists no lanzó)");
            } catch (IOException ex) {
                m.put("borrar", MotivoArnes(ex));
            }
            // d11: Files.copy con REPLACE_EXISTING sobre el mismo archivo abierto
            try {
                Files.copy(fuente, abierto, StandardCopyOption.REPLACE_EXISTING);
                m.put("copiar", "(Files.copy no lanzó)");
            } catch (IOException ex) {
                m.put("copiar", MotivoArnes(ex));
            }
        }
        // d13: lectura de un archivo con un bloqueo exclusivo
        Path bloqueado = dir.resolve("Bloqueado.MINGOL");
        Files.write(bloqueado, Crlf(VALIDO));
        try (RandomAccessFile raf = new RandomAccessFile(bloqueado.toFile(), "rw"); FileLock lock = raf.getChannel().lock()) {
            try (BufferedReader br = new BufferedReader(new FileReader(bloqueado.toFile()))) {
                br.readLine();
                m.put("leer", "(readLine no lanzó)");
            } catch (IOException ex) {
                m.put("leer", MotivoArnes(ex));
            }
        }
        BorrarArbol(dir);
        return m;
    }

    // Oráculo del motivo (DQ1), escrito aparte de Mingol.Motivo: cuatro niveles, nunca vacío
    private static String MotivoArnes(IOException ex) {
        if (ex instanceof FileSystemException && ((FileSystemException) ex).getReason() != null
                && !((FileSystemException) ex).getReason().isEmpty()) {
            return ((FileSystemException) ex).getReason();
        }
        if (ex instanceof NoSuchFileException) return "No existe";
        if (ex instanceof AccessDeniedException) return "Acceso denegado";
        if (ex instanceof DirectoryNotEmptyException) return "La carpeta no está vacía";
        if (ex instanceof FileAlreadyExistsException) return "Ya existe";
        String s = ex.getMessage();
        if (ex instanceof FileNotFoundException && s != null && s.endsWith(")")) {
            int a = s.lastIndexOf('(');
            if (a >= 0 && s.length() - 1 - (a + 1) >= 1) return s.substring(a + 1, s.length() - 1);
        }
        if (s != null && !s.isEmpty()) return s;
        String n = ex.getClass().getSimpleName();
        return n.isEmpty() ? ex.getClass().getName() : n;
    }

    // Grupos de "Expected Counterexamples": la causa se confirma si todos sus casos fallan con el síntoma
    private static void Causas() {
        System.out.println();
        System.out.println("===== CAUSAS DEL DISEÑO (Hypothesized Root Cause 1–6) =====");
        Grupo("1. Extensión decidida sobre la ruta entera", "traza", "r02", "x06", "f02", "x09");
        Grupo("2. Ninguna validación del nombre (falta el 115)", "ERROR 115", "n03", "n04", "n05", "n06", "n08", "n09",
                "n10", "n11", "x04", "x05", "x07", "x08");
        Grupo("3. .a68 antes del análisis, junto al fuente y reescrito", ".a68", "e01", "e02", "r01", "r03", "n01");
        Grupo("4. catch (IOException) con Logger", "traza", "f01", "f02");
        Grupo("5. waitFor() sin usar", "código de salida", "d16_2", "d16_300", "d16m5", "d16_65535");
        Grupo("6. NombrePrograma con punto > 0", "faltan [-errores.txt]", "x07");
    }

    private static void Grupo(String nombre, String sintoma, String... ids) {
        List<String> sinSintoma = new ArrayList<>();
        for (String id : ids) {
            List<String> d = diferenciasPorCaso.get(id);
            boolean tiene = false;
            if (d != null) for (String s : d) if (s.contains(sintoma)) tiene = true;
            if (!tiene) sinSintoma.add(id);
        }
        System.out.println(nombre + " [síntoma \"" + sintoma + "\"]: " + (sinSintoma.isEmpty()
                ? "CONFIRMADA (" + String.join(", ", ids) + ")"
                : "NO CONFIRMADA: sin el síntoma " + sinSintoma));
    }

    // ------------------------------------------------------------------
    // Tarea 2 (área 5): captura y preservación (Property 6 del diseño)
    // ------------------------------------------------------------------

    private static final String CARPETA_GOLDEN = "mingol-entrada-golden";
    private static final String NO_A68G = "No se encontró a68g. Se buscó en la variable de entorno A68G, "
            + "en la carpeta de MINGOL y en el PATH. Defina A68G con la ruta completa de a68g.exe.";

    // Caso del dominio de preservación: "ref" = TestProyecto(String) en hijoref; "main" = main real con el eco
    private static class CasoPres {
        final String id;
        final String tipo;
        final String archivo;        // fuente en la carpeta del caso (null = sin fuente)
        final byte[] bytes;
        final String argumento;      // main: argumento (null = sin argumento); ref: archivo
        boolean sinA68g;             // A68G inexistente y PATH sin a68g (3.1)
        boolean proyecto;            // la carpeta es la subcarpeta proyecto (R5)
        // Oráculo de preservación
        String erroresEsperado;      // -errores.txt completo (null = no debe existir)
        String tpEsperado;           // ref: TP|true o TP|false
        int codigoEsperado;
        final List<String> stderrEsperado = new ArrayList<>();
        boolean conEco;              // main: una invocación del eco con la ruta absoluta del .a68

        CasoPres(String id, String tipo, String archivo, byte[] bytes, String argumento) {
            this.id = id;
            this.tipo = tipo;
            this.archivo = archivo;
            this.bytes = bytes;
            this.argumento = argumento;
        }

        // Descripción estable del caso: se guarda en la captura para comprobar "casos iguales"
        String Descriptor() {
            return tipo + "|" + archivo + "|" + argumento + "|" + (sinA68g ? "sin-a68g" : "eco");
        }

        // Nombre del programa con la regla de 0136e1b (sin la última extensión; el punto en posición > 0)
        String Base() {
            if (archivo == null) return null;
            int p = archivo.lastIndexOf('.');
            return p > 0 ? archivo.substring(0, p) : archivo;
        }
    }

    // Fuente con \n y salto final: fuera de C para main (1.9)
    private static byte[] Lf(List<String> lineas) {
        String s = lineas.isEmpty() ? "" : String.join("\n", lineas) + "\n";
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    // Dominio de preservación (isBugCondition falso para main; cualquier fuente para TestProyecto)
    private static List<CasoPres> CasosPreservacion() throws IOException {
        List<CasoPres> l = new ArrayList<>();
        // TestProyecto(String) sin 115 (DE2): nombres inválidos o con otra extensión, cuatro contenidos
        String[] nombres = {"be-sobrante.mingol", "be-begin2.mingol", "mi prog.mingol", "1Calcular.MINGOL",
                "Calcular Inflacion.MINGOL", "a.b.MINGOL", "Otro.txt"};
        for (int i = 0; i < nombres.length; i++) {
            String n = nombres[i];
            String pre = String.format("tp%02d_", i + 1);
            CasoPres v = new CasoPres(pre + "valido", "ref", n, Lf(VALIDO), n);
            v.erroresEsperado = Errores(VALIDO, SinMensajes());
            v.tpEsperado = "TP|true";
            CasoPres e = new CasoPres(pre + "erroneo", "ref", n, Lf(ERRONEO), n);
            e.erroresEsperado = Errores(ERRONEO, Bajo303());
            e.tpEsperado = "TP|false";
            CasoPres a = new CasoPres(pre + "adv", "ref", n, Lf(ADVERTENCIA), n);
            a.erroresEsperado = Errores(ADVERTENCIA, Bajo(3, Adv("PRAGMAT")));
            a.tpEsperado = "TP|true";
            CasoPres z = new CasoPres(pre + "vacio", "ref", n, new byte[0], n);
            z.erroresEsperado = Errores(L(), SinMensajes(), E307(), E308());
            z.tpEsperado = "TP|false";
            l.addAll(Arrays.asList(v, e, a, z));
        }
        // Copia de proyecto.mingol en proyecto\: tres 111 y dos advertencias (área 4)
        byte[] proyecto = Files.readAllBytes(repo.resolve("proyecto.mingol"));
        CasoPres p = new CasoPres("tp_proyecto", "ref", "proyecto.mingol", proyecto, "proyecto.mingol");
        p.proyecto = true;
        List<String> lp = Files.readAllLines(repo.resolve("proyecto.mingol"), StandardCharsets.ISO_8859_1);
        Map<Integer, List<String>> bp = Bajo(2, Adv("PRAGMAT"), 3, Adv("MODE"));
        for (int n : new int[] {21, 49, 59}) bp.computeIfAbsent(n, k -> new ArrayList<>()).add(E111(TextoEntreComillas(lp.get(n - 1))));
        p.erroresEsperado = Errores(lp, bp);
        p.tpEsperado = "TP|false";
        l.add(p);
        for (CasoPres c : l) c.codigoEsperado = 0;

        // main fuera de C (M10): válido con \n y salto final, nombre y extensión válidos, ECO_CODIGO=0
        CasoPres m1 = new CasoPres("m10_valido", "main", "Prog.MINGOL", Lf(VALIDO), "Prog.MINGOL");
        m1.erroresEsperado = Errores(VALIDO, SinMensajes());
        m1.conEco = true;
        // Advertencias (3.2): e03 con \n
        CasoPres m2 = new CasoPres("m10_adv", "main", "Adv.MINGOL", Lf(ADVERTENCIA), "Adv.MINGOL");
        m2.erroresEsperado = Errores(ADVERTENCIA, Bajo(3, Adv("PRAGMAT")));
        m2.conEco = true;
        // Sin extensión (3.6): x01 con \n
        CasoPres m3 = new CasoPres("m10_sinext", "main", "SinExt.MINGOL", Lf(VALIDO), "SinExt");
        m3.erroresEsperado = Errores(VALIDO, SinMensajes());
        m3.conEco = true;
        // Sin argumento (3.3): f03
        CasoPres m4 = new CasoPres("f03_uso", "main", null, null, null);
        m4.codigoEsperado = 1;
        m4.stderrEsperado.add("Uso: java -jar MINGOL.jar <Programa>.MINGOL");
        // Búsqueda de a68g (3.1): A68G inexistente y PATH = System32 sin a68g
        CasoPres m5 = new CasoPres("a68g_ausente", "main", "Prog.MINGOL", Lf(VALIDO), "Prog.MINGOL");
        m5.sinA68g = true;
        m5.erroresEsperado = Errores(VALIDO, SinMensajes());
        m5.codigoEsperado = 1;
        m5.stderrEsperado.add(NO_A68G);
        l.addAll(Arrays.asList(m1, m2, m3, m4, m5));
        return l;
    }

    // Lo observado en un caso: campos de texto y bytes del -errores.txt
    private static class Observacion {
        final Map<String, String> campos = new LinkedHashMap<>();
        byte[] errores;
        final List<String> stderr = new ArrayList<>();
        final List<String> ecos = new ArrayList<>();
    }

    // Prepara la carpeta, corre el hijo (hijoref o main) y registra lo observado
    private static Observacion Observar(CasoPres c, Path dir, Path logs) throws Exception {
        BorrarArbol(dir);
        Files.createDirectories(dir);
        if (c.archivo != null) Files.write(dir.resolve(c.archivo), c.bytes);
        ResultadoHijo r = c.tipo.equals("ref") ? LanzarRef(c.id, c.argumento, dir, logs) : LanzarMainPres(c, dir, logs);
        Observacion o = new Observacion();
        o.campos.put("caso", c.Descriptor());
        o.campos.put("termino", String.valueOf(r.termino));
        o.campos.put("codigo", String.valueOf(r.codigo));
        o.campos.put("stdout", Escapar(r.stdout.replaceAll("\\R+$", "")));
        // stderr sin la nota del launcher (R10); las líneas del eco, aparte y relativas a la carpeta del caso
        for (String s : r.stderr.split("\\R", -1)) {
            if (s.startsWith(NOTA_JDK)) continue;
            if (s.startsWith(ECO)) {
                Path e = Paths.get(s.substring(ECO.length()));
                o.ecos.add(e.isAbsolute() && e.startsWith(dir) ? dir.relativize(e).toString() : "FUERA:" + e);
            } else {
                o.stderr.add(s);
            }
        }
        while (!o.stderr.isEmpty() && o.stderr.get(o.stderr.size() - 1).isEmpty()) o.stderr.remove(o.stderr.size() - 1);
        o.campos.put("stderr", Escapar(String.join("\n", o.stderr)));
        o.campos.put("eco", String.join(";", o.ecos));
        // Archivos de la carpeta; en ref el .a68 no se compara (E5/E6/E10, DE8)
        List<String> entradas = new ArrayList<>();
        for (String e : Entradas(dir)) if (c.tipo.equals("main") || !e.toLowerCase(Locale.ROOT).endsWith(".a68")) entradas.add(e);
        o.campos.put("entradas", String.join(";", entradas));
        // El fuente queda intacto byte a byte
        if (c.archivo != null) {
            Path f = dir.resolve(c.archivo);
            o.campos.put("fuente intacto", String.valueOf(Files.isRegularFile(f) && Arrays.equals(c.bytes, Files.readAllBytes(f))));
        }
        // -errores.txt (bytes) y, en main, el .a68 con Files.mismatch
        if (c.archivo != null) {
            Path err = dir.resolve(c.Base() + "-errores.txt");
            o.errores = Files.isRegularFile(err) ? Files.readAllBytes(err) : null;
            o.campos.put("errores", o.errores != null ? "presente" : "ausente");
            if (c.tipo.equals("main")) {
                Path a68 = dir.resolve(c.Base() + ".a68");
                o.campos.put("a68", Files.isRegularFile(a68) ? String.valueOf(Files.mismatch(dir.resolve(c.archivo), a68)) : "ausente");
            }
        }
        return o;
    }

    // Oráculo de preservación (independiente de la captura): M10, 3.1, 3.2, 3.3, 3.6 y TestProyecto sin 115
    private static void Oraculo(CasoPres c, Observacion o, Path dir, Path logs, List<String> dif) throws Exception {
        if (!o.campos.get("termino").equals("true")) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s");
        if (!o.campos.get("codigo").equals(String.valueOf(c.codigoEsperado))) {
            dif.add("código: esperado " + c.codigoEsperado + ", obtenido " + o.campos.get("codigo"));
        }
        if (!o.stderr.equals(c.stderrEsperado)) dif.add("stderr: esperado " + c.stderrEsperado + ", obtenido " + Primeras(o.stderr, 4));
        String stdout = o.campos.get("stdout");
        if (c.tipo.equals("ref")) {
            if (!stdout.equals(c.tpEsperado)) dif.add("hijoref: esperado " + c.tpEsperado + ", obtenido [" + stdout + "]");
        } else if (!stdout.isEmpty()) {
            dif.add("stdout no vacío: " + stdout);
        }
        if (c.archivo != null && !"true".equals(o.campos.get("fuente intacto"))) dif.add("el fuente cambió");
        // -errores.txt completo, sin 115 (DE2)
        if (c.erroresEsperado != null) {
            if (o.errores == null) {
                dif.add("no se generó " + c.Base() + "-errores.txt");
            } else {
                String obtenido = new String(o.errores, StandardCharsets.ISO_8859_1);
                if (!c.erroresEsperado.equals(obtenido)) dif.add(c.Base() + "-errores.txt: " + PrimeraDiferencia(c.erroresEsperado, obtenido));
                if (obtenido.contains("ERROR 115")) dif.add("TestProyecto/main fuera de C emitió un 115");
            }
        }
        // Archivos esperados
        Set<String> esperadas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (c.archivo != null) {
            esperadas.add(c.archivo);
            esperadas.add(c.Base() + "-errores.txt");
            if (c.tipo.equals("main")) esperadas.add(c.Base() + ".a68");
        }
        Set<String> obtenidas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (!o.campos.get("entradas").isEmpty()) obtenidas.addAll(Arrays.asList(o.campos.get("entradas").split(";")));
        if (!esperadas.equals(obtenidas)) dif.add("archivos: esperado " + esperadas + ", obtenido " + obtenidas);
        if (!c.tipo.equals("main")) return;
        // main: .a68 copia exacta (Files.mismatch == -1) y una invocación del eco con su ruta absoluta
        if (c.archivo != null && !"-1".equals(o.campos.get("a68"))) dif.add(".a68: Files.mismatch = " + o.campos.get("a68"));
        List<String> ecoEsperado = c.conEco ? L(c.Base() + ".a68") : L();
        if (!o.ecos.equals(ecoEsperado)) dif.add("eco: esperado " + ecoEsperado + ", obtenido " + o.ecos);
        // M10: el -errores.txt de main es el mismo que da hijoref para el mismo fuente
        if (c.archivo != null) {
            CasoPres ref = new CasoPres(c.id + "-ref", "ref", c.archivo, c.bytes, c.archivo);
            Observacion or = Observar(ref, dir.resolveSibling(c.id + "-ref"), logs);
            if (!Arrays.equals(o.errores, or.errores)) dif.add("-errores.txt distinto del de hijoref");
        }
    }

    // Carpeta del caso: proyecto\ para la copia de proyecto.mingol, si no <modo>\<id>
    private static Path DirCaso(CasoPres c, Path trabajo) {
        return c.proyecto ? base.resolve(SUBCARPETA_PROYECTO) : trabajo.resolve(c.id);
    }

    // Preparación común de capturar y preservacion: carpeta segura, guardias y eco
    private static Path PrepararPreservacion(String modo) throws Exception {
        base = CarpetaSegura();
        repo = RaizRepositorio();
        Guardias();
        Path trabajo = base.resolve(modo);
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        ecoJar = ArmarEco();
        return trabajo;
    }

    private static Path CarpetaGolden() throws IOException {
        return CarpetaTemp().resolve(CARPETA_GOLDEN);
    }

    // capturar [--sobrescribir]: guarda lo observado sobre 0136e1b (R9)
    private static void Capturar(boolean sobrescribir) throws Exception {
        Path golden = CarpetaGolden();
        // No se pisa una captura existente sin --sobrescribir
        if (Files.exists(golden) && !sobrescribir) {
            System.err.println("Ya existe una captura en " + golden + "; usar --sobrescribir para reemplazarla");
            System.exit(2);
        }
        // La captura se toma sobre el código sin arreglar: si Mingol ya tiene Preparar, se niega (R9)
        for (Method m : Class.forName("Mingol.Mingol").getDeclaredMethods()) {
            if (m.getName().equals("Preparar")) {
                System.err.println("Mingol.Preparar existe: la captura se toma solo sobre 0136e1b (R9)");
                System.exit(2);
            }
        }
        Path trabajo = PrepararPreservacion("capturar");
        BorrarArbol(golden);
        Files.createDirectories(golden);
        List<CasoPres> lista = CasosPreservacion();
        List<String> indice = new ArrayList<>();
        for (CasoPres c : lista) {
            Path dir = DirCaso(c, trabajo);
            Observacion o = Observar(c, dir, trabajo.resolve("logs"));
            List<String> dif = new ArrayList<>();
            Oraculo(c, o, dir, trabajo.resolve("logs"), dif);
            // Captura del caso: fuente, observación y -errores.txt
            Path g = golden.resolve(c.id);
            Files.createDirectories(g);
            if (c.bytes != null) Files.write(g.resolve("fuente.bin"), c.bytes);
            Files.write(g.resolve("observacion.txt"), Lineas(o.campos), StandardCharsets.UTF_8);
            if (o.errores != null) Files.write(g.resolve("errores.bin"), o.errores);
            indice.add(c.id + "=" + c.Descriptor());
            Registrar(c.id, c.tipo, dif);
        }
        Files.write(golden.resolve("casos.txt"), indice, StandardCharsets.UTF_8);
        System.out.println("Captura escrita en " + golden + " (" + lista.size() + " casos)");
        ImprimirResumen("capturar");
    }

    // preservacion: todo igual a la captura y al oráculo
    private static void Preservacion() throws Exception {
        Path golden = CarpetaGolden();
        if (!Files.isRegularFile(golden.resolve("casos.txt"))) {
            System.err.println("No existe la captura " + golden + ": correr antes 'capturar' sobre 0136e1b");
            System.exit(2);
        }
        Path trabajo = PrepararPreservacion("preservacion");
        List<CasoPres> lista = CasosPreservacion();
        // Casos iguales a la captura (mismos ids y descriptores, en el mismo orden)
        List<String> indice = new ArrayList<>();
        for (CasoPres c : lista) indice.add(c.id + "=" + c.Descriptor());
        List<String> capturado = Files.readAllLines(golden.resolve("casos.txt"), StandardCharsets.UTF_8);
        List<String> difCasos = new ArrayList<>();
        if (!indice.equals(capturado)) difCasos.add("casos distintos de la captura: esperado " + Primeras(capturado, 3) + ", obtenido " + Primeras(indice, 3));
        Registrar("casos-captura", "captura", difCasos);
        for (CasoPres c : lista) {
            Path dir = DirCaso(c, trabajo);
            Path g = golden.resolve(c.id);
            List<String> dif = new ArrayList<>();
            // Fuente igual a la captura
            Path fb = g.resolve("fuente.bin");
            if (c.bytes != null && (!Files.isRegularFile(fb) || !Arrays.equals(c.bytes, Files.readAllBytes(fb)))) dif.add("fuente distinto de la captura");
            Observacion o = Observar(c, dir, trabajo.resolve("logs"));
            // Observación campo por campo
            List<String> esperado = Files.isRegularFile(g.resolve("observacion.txt"))
                    ? Files.readAllLines(g.resolve("observacion.txt"), StandardCharsets.UTF_8) : L();
            List<String> obtenido = Lineas(o.campos);
            for (int i = 0; i < Math.max(esperado.size(), obtenido.size()); i++) {
                String a = i < esperado.size() ? esperado.get(i) : "(fin)";
                String b = i < obtenido.size() ? obtenido.get(i) : "(fin)";
                if (!a.equals(b)) dif.add("captura: esperado [" + a + "], obtenido [" + b + "]");
            }
            // -errores.txt byte a byte contra la captura (R6: se muestra con ISO-8859-1)
            Path eb = g.resolve("errores.bin");
            byte[] errCap = Files.isRegularFile(eb) ? Files.readAllBytes(eb) : null;
            if (!Arrays.equals(errCap, o.errores)) {
                dif.add("-errores.txt distinto de la captura: " + (errCap == null || o.errores == null ? "presencia distinta"
                        : PrimeraDiferencia(new String(errCap, StandardCharsets.ISO_8859_1), new String(o.errores, StandardCharsets.ISO_8859_1))));
            }
            Oraculo(c, o, dir, trabajo.resolve("logs"), dif);
            Registrar(c.id, c.tipo, dif);
        }
        ImprimirResumen("preservacion");
    }

    private static List<String> Lineas(Map<String, String> campos) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : campos.entrySet()) l.add(e.getKey() + "=" + e.getValue());
        return l;
    }

    // main real para un caso de preservación, con el mismo lanzador y el mismo eco que la exploración
    private static ResultadoHijo LanzarMainPres(CasoPres c, Path dir, Path logs) throws Exception {
        Caso k = new Caso(c.id, "pres");
        if (c.argumento != null) k.arg(c.argumento);
        k.ecoCodigo = 0;
        if (c.sinA68g) k.a68gEnv = dir.resolve("noexiste").resolve("a68g.exe").toString();
        return LanzarMain(k, dir, logs);
    }

    // hijoref en un hijo (R8): mismo java, -ea si el padre lo usa, entorno sin A68G ni el agente
    private static ResultadoHijo LanzarRef(String id, String archivo, Path cwd, Path logs) throws Exception {
        return LanzarInterno("hijoref", id, L(archivo), cwd, logs);
    }

    // Área 5 (3.5): modo interno (hijoref, hijoprep) en un hijo (R8), sin A68G ni el agente del eco
    private static ResultadoHijo LanzarInterno(String interno, String id, List<String> argumentos, Path cwd, Path logs) throws Exception {
        List<String> comando = new ArrayList<>();
        comando.add(JavaExe().toString());
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
        comando.add("PruebasEntrada");
        comando.add(interno);
        comando.addAll(argumentos);
        Files.createDirectories(logs);
        File out = logs.resolve(id + ".out").toFile();
        File err = logs.resolve(id + ".err").toFile();
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.directory(cwd.toFile());
        Map<String, String> env = pb.environment();
        String systemRoot = System.getenv("SystemRoot");
        String temp = System.getenv("TEMP");
        String tmp = System.getenv("TMP");
        env.clear();
        env.put("SystemRoot", systemRoot);
        if (temp != null) env.put("TEMP", temp);
        if (tmp != null) env.put("TMP", tmp);
        env.put("PATH", Paths.get(systemRoot, "System32").toString());
        pb.redirectOutput(out);
        pb.redirectError(err);
        Process p = pb.start();
        p.getOutputStream().close();
        ResultadoHijo r = new ResultadoHijo();
        r.termino = p.waitFor(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
        if (!r.termino) {
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

    // Interno hijoref: TestProyecto(String) por reflexión en la carpeta actual (nunca main ni a68g)
    private static void HijoRef(String archivo) throws Exception {
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            Object r = m.invoke(null, archivo);
            System.out.println("TP|" + r);
        } catch (InvocationTargetException ex) {
            System.out.println("TP|excepcion:" + ex.getCause());
        }
        System.out.flush();
    }

    // Área 5 (3.5): interno hijoprep, Preparar por reflexión en la carpeta actual; nunca a68g (DE1)
    private static void HijoPrep(String argumento) throws Exception {
        Method m;
        try {
            m = Metodo("Preparar", String.class);
        } catch (NoSuchMethodException ex) {
            System.out.println("PREPARAR|sin-metodo");
            System.exit(2);
            return;
        }
        try {
            Object r = m.invoke(null, argumento);
            System.out.println("PREPARAR|" + r);
        } catch (InvocationTargetException ex) {
            System.out.println("PREPARAR|excepcion:" + ex.getCause());
        }
        System.out.flush();
        System.err.flush();
    }

    // ------------------------------------------------------------------
    // Tarea 3.5 (área 5): utilidades de reflexión
    // ------------------------------------------------------------------

    private static Method Metodo(String nombre, Class<?>... tipos) throws Exception {
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod(nombre, tipos);
        m.setAccessible(true);
        return m;
    }

    // Resultado del método estático o la excepción que lanzó (nunca la propaga)
    private static Object Invocar(Method m, Object... args) throws Exception {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException ex) {
            return ex.getCause();
        }
    }

    // Carpeta nueva y vacía para un caso
    private static Path Sub(Path dir, String nombre) throws IOException {
        Path d = dir.resolve(nombre);
        BorrarArbol(d);
        Files.createDirectories(d);
        return d;
    }

    // Aviso devuelto por BorrarA68Viejo/CopiarA68 (null = sin aviso)
    private static void Aviso(List<String> dif, String esperado, Object obtenido) {
        if (esperado == null ? obtenido != null : !esperado.equals(obtenido)) {
            dif.add("aviso: esperado [" + esperado + "], obtenido [" + obtenido + "]");
        }
    }

    private static void Intacto(List<String> dif, Path p, byte[] bytes, String que) throws IOException {
        if (!Files.isRegularFile(p) || !Arrays.equals(bytes, Files.readAllBytes(p))) dif.add(que + " no quedó intacto");
    }

    private static void Copia(List<String> dif, Path f, Path a) throws IOException {
        if (!Files.isRegularFile(a)) dif.add("no existe " + a.getFileName());
        else if (Files.mismatch(f, a) != -1) dif.add(a.getFileName() + ": Files.mismatch = " + Files.mismatch(f, a));
    }

    // ------------------------------------------------------------------
    // Tarea 3.5 (área 5): unitarias U1–U8 (Unit Tests y Property-Based Tests del diseño)
    // ------------------------------------------------------------------

    private static void Unitarias() throws Exception {
        base = CarpetaSegura();
        repo = RaizRepositorio();
        Guardias(false);
        Path trabajo = base.resolve("unitarias");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        // Motivos del sistema para las mismas situaciones (dependen del idioma de Windows)
        Map<String, String> motivos = Motivos(trabajo.resolve("_motivos"));
        System.out.println("Motivos de este equipo: " + motivos);
        U1();
        U2();
        U3();
        U4();
        U5(trabajo.resolve("u5"), motivos);
        U6(trabajo.resolve("u6"), motivos);
        U7(trabajo.resolve("u7"), trabajo.resolve("logs"), motivos);
        U8(trabajo.resolve("u8"), trabajo.resolve("logs"));
        ImprimirResumen("unitarias");
    }

    // Oráculo de REQ-INPUT-01 escrito aparte, carácter por carácter y sin expresiones regulares (E4, DQ5)
    private static boolean NombreValidoArnes(String n) {
        int p = n.lastIndexOf('.');
        if (p < 0) return false;
        String ext = n.substring(p + 1);
        String m = "mingol";
        if (ext.length() != m.length()) return false;
        for (int i = 0; i < m.length(); i++) {
            char c = ext.charAt(i);
            if (c != m.charAt(i) && c != (char) (m.charAt(i) - 'a' + 'A')) return false;
        }
        String b = n.substring(0, p);
        if (b.isEmpty() || b.length() > 20) return false;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            boolean letra = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            boolean digito = c >= '0' && c <= '9';
            if (i == 0 && !letra) return false;
            if (!letra && !digito && c != '_') return false;
        }
        return b.charAt(b.length() - 1) != '_';
    }

    // U1: NombreArchivoValido con los nombres de los 35 casos, los bordes del diseño y 200 generados
    private static void U1() throws Exception {
        Method m = Metodo("NombreArchivoValido", String.class);
        String[] validos = {"CalcularInflacion.MINGOL", "Calcular_Inflacion1.MINGOL", "Abcdefghijklmnopqrst.MINGOL",
                "PROG.MINGOL", "prog2.MINGOL", "SinExt.MINGOL", "Minus.mingol", "Mixta.MinGol", "Prog.MINGOL",
                "NoExiste.MINGOL", "ConErr.MINGOL", "Viejo.MINGOL", "Adv.MINGOL", "proyecto.mingol", "Punto.MINGOL",
                "a.MINGOL", "a__b.mingol", "A1_b.MinGol", "Crlf.MINGOL", "CON.MINGOL"};
        String[] invalidos = {"1Calcular.MINGOL", "_Calcular.MINGOL", "Calcular_.MINGOL", "Calcular Inflacion.MINGOL",
                "Abcdefghijklmnopqrstu.MINGOL", "Calc-ular.MINGOL", "Calc%1.MINGOL", "a.b.MINGOL", "Otro.txt",
                "Otro2.a68", ".MINGOL", "Prog.MINGOL.txt", "SinExt2", "Bad Name.MINGOL", "a_.MINGOL", "_a.MINGOL",
                "1a.MINGOL", "a-b.MINGOL", "a b.MINGOL", "a%b.MINGOL", "Punto.", "Punto..", "Punto",
                "x.M\u0130NGOL", "x.mingol2", "x", "A\u00f1o.MINGOL", "Caf\u00e9.MINGOL"};
        for (String n : validos) U1Uno(m, n, true);
        for (String n : invalidos) U1Uno(m, n, false);
        // Property-Based Tests (nombres): semilla fija, letras, dígitos, _, -, espacio, ., %; largo 0–25
        Random rnd = new Random(20250505L);
        String limpio = "abcdeXYZQ0123456789_";
        String sucio = "abXY09_-. %";
        String[] otras = {"txt", "a68", "mingo", "mingoll", "MINGOL2", ""};
        List<String> dif = new ArrayList<>();
        int buenos = 0;
        for (int i = 0; i < 200; i++) {
            String alfabeto = rnd.nextBoolean() ? limpio : sucio;
            StringBuilder sb = new StringBuilder();
            int largo = rnd.nextInt(26);
            for (int k = 0; k < largo; k++) sb.append(alfabeto.charAt(rnd.nextInt(alfabeto.length())));
            int tipo = rnd.nextInt(4);
            if (tipo <= 1) {
                // Extensión mingol con mayúsculas al azar
                sb.append('.');
                for (char c : "mingol".toCharArray()) sb.append(rnd.nextBoolean() ? (char) (c - 'a' + 'A') : c);
            } else if (tipo == 2) {
                sb.append('.').append(otras[rnd.nextInt(otras.length)]);
            }
            String n = sb.toString();
            boolean esperado = NombreValidoArnes(n);
            if (esperado) buenos++;
            Object r = Invocar(m, n);
            if (!Boolean.valueOf(esperado).equals(r)) dif.add("[" + n + "]: esperado " + esperado + ", obtenido " + r);
        }
        System.out.println("U1 generados: 200 nombres, " + buenos + " válidos según el oráculo");
        if (buenos == 0 || buenos == 200) dif.add("generador degenerado: " + buenos + " válidos");
        Registrar("U1 200 nombres generados", "U1", dif);
    }

    private static void U1Uno(Method m, String n, boolean esperado) throws Exception {
        List<String> dif = new ArrayList<>();
        Object r = Invocar(m, n);
        if (!Boolean.valueOf(esperado).equals(r)) dif.add("esperado " + esperado + ", obtenido " + r);
        if (NombreValidoArnes(n) != esperado) dif.add("el oráculo del arnés discrepa del esperado");
        Registrar("U1 [" + n + "]", "U1", dif);
    }

    // U2: RutaEfectiva (E8, E15; solo mira el nombre del archivo)
    private static void U2() throws Exception {
        Method m = Metodo("RutaEfectiva", String.class);
        String[][] l = {{"SinExt", "SinExt.MINGOL"}, {"car.peta\\Prog", "car.peta\\Prog.MINGOL"}, {"Punto.", "Punto."},
                {"x.mingol", "x.mingol"}, {".MINGOL", ".MINGOL"}, {"sub/Prog", "sub/Prog.MINGOL"}};
        for (String[] c : l) {
            List<String> dif = new ArrayList<>();
            Object r = Invocar(m, c[0]);
            if (!c[1].equals(r)) dif.add("esperado [" + c[1] + "], obtenido [" + r + "]");
            Registrar("U2 [" + c[0] + "]", "U2", dif);
        }
        List<String> dif = new ArrayList<>();
        Object r = Invocar(m, "a?b");
        if (!(r instanceof InvalidPathException)) dif.add("esperado InvalidPathException, obtenido " + r);
        Registrar("U2 [a?b]", "U2", dif);
    }

    // U3: NombrePrograma y RutaA68 (E5, E14): el .a68 siempre sin carpeta
    private static void U3() throws Exception {
        Method np = Metodo("NombrePrograma", String.class);
        Method ra = Metodo("RutaA68", String.class);
        String[][] l = {{".MINGOL", "", ".a68"}, {"Punto.", "Punto", "Punto.a68"}, {"a.b.MINGOL", "a.b", "a.b.a68"},
                {"sub\\Prog.MINGOL", "Prog", "Prog.a68"}, {"C:\\x.y\\z.Mingol", "z", "z.a68"}, {"PROG.MINGOL", "PROG", "PROG.a68"}};
        for (String[] c : l) {
            List<String> dif = new ArrayList<>();
            Object n = Invocar(np, c[0]);
            Object a = Invocar(ra, c[0]);
            if (!c[1].equals(n)) dif.add("NombrePrograma: esperado [" + c[1] + "], obtenido [" + n + "]");
            if (!c[2].equals(a)) dif.add("RutaA68: esperado [" + c[2] + "], obtenido [" + a + "]");
            if (a instanceof String && Paths.get((String) a).getParent() != null) dif.add("RutaA68 con carpeta: " + a);
            Registrar("U3 [" + c[0] + "]", "U3", dif);
        }
    }

    // U4: Motivo (DQ1), los cuatro niveles; nunca lanza, null ni vacío
    private static void U4() throws Exception {
        Method m = Metodo("Motivo", IOException.class);
        IOException anonima = new IOException() { };
        Object[][] l = {
            {"nivel 1 FileSystemException", new FileSystemException("x.a68", null, "Razón del sistema"), "Razón del sistema"},
            {"nivel 1 gana al 2", new AccessDeniedException("x", null, "Razón"), "Razón"},
            {"nivel 2 NoSuchFileException", new NoSuchFileException("x"), "No existe"},
            {"nivel 2 AccessDeniedException", new AccessDeniedException("x"), "Acceso denegado"},
            {"nivel 2 DirectoryNotEmptyException", new DirectoryNotEmptyException("x"), "La carpeta no está vacía"},
            {"nivel 2 FileAlreadyExistsException", new FileAlreadyExistsException("x"), "Ya existe"},
            {"nivel 2 razón vacía", new NoSuchFileException("x", null, ""), "No existe"},
            {"nivel 3", new FileNotFoundException("Prog-errores.txt (Acceso denegado)"), "Acceso denegado"},
            {"nivel 3 último paréntesis", new FileNotFoundException("a (b) (c)"), "c"},
            {"nivel 3 mensaje null", new FileNotFoundException((String) null), "FileNotFoundException"},
            {"nivel 3 mensaje vacío", new FileNotFoundException(""), "FileNotFoundException"},
            {"nivel 3 sin paréntesis", new FileNotFoundException("ruta sin paréntesis"), "ruta sin paréntesis"},
            {"nivel 3 paréntesis vacío", new FileNotFoundException("ruta ()"), "ruta ()"},
            {"nivel 3 sin cierre", new FileNotFoundException("ruta (abc"), "ruta (abc"},
            {"nivel 3 solo cierre", new FileNotFoundException(")"), ")"},
            {"nivel 4 texto", new IOException("texto"), "texto"},
            {"nivel 4 sin mensaje", new IOException(), "IOException"},
            {"nivel 4 mensaje vacío", new IOException(""), "IOException"},
            {"nivel 4 FileSystemException sin razón", new FileSystemException("x"), "x"},
            {"nivel 4 clase anónima", anonima, anonima.getClass().getName()},
        };
        for (Object[] c : l) {
            List<String> dif = new ArrayList<>();
            Object r = Invocar(m, c[1]);
            if (!(r instanceof String) || ((String) r).isEmpty()) dif.add("no devolvió un texto no vacío: " + r);
            else if (!c[2].equals(r)) dif.add("esperado [" + c[2] + "], obtenido [" + r + "]");
            Registrar("U4 " + c[0], "U4", dif);
        }
    }

    // U5: BorrarA68Viejo (E6, E12, E21; DQ3, DE6)
    // Avisos: el recurso del try se abre solo para provocar el fallo que se prueba (no se lee)
    @SuppressWarnings("try")
    private static void U5(Path dir, Map<String, String> motivos) throws Exception {
        Method m = Metodo("BorrarA68Viejo", Path.class, Path.class);
        byte[] viejo = VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1);
        byte[] valido = Crlf(VALIDO);
        List<String> dif;
        Path d, f, a;

        // No existe: sin aviso
        dif = new ArrayList<>();
        d = Sub(dir, "no-existe");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Aviso(dif, null, Invocar(m, f, a));
        if (Files.exists(a)) dif.add("apareció V.a68");
        Registrar("U5 no existe", "U5", dif);

        // Existe: se borra sin aviso
        dif = new ArrayList<>();
        d = Sub(dir, "existe");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.write(a, viejo);
        Aviso(dif, null, Invocar(m, f, a));
        if (Files.exists(a)) dif.add("V.a68 no se borró");
        Registrar("U5 existe", "U5", dif);

        // MismoArchivo (fuente x.a68, pedido como X.A68): nada se toca, sin aviso (E12)
        dif = new ArrayList<>();
        d = Sub(dir, "mismo");
        f = d.resolve("x.a68");
        Files.write(f, valido);
        Aviso(dif, null, Invocar(m, f, d.resolve("X.A68")));
        Intacto(dif, f, valido, "el fuente x.a68");
        Registrar("U5 MismoArchivo", "U5", dif);

        // Abierto por el arnés: aviso de E21 y el archivo sigue
        dif = new ArrayList<>();
        d = Sub(dir, "abierto");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.write(a, viejo);
        Object r;
        try (RandomAccessFile raf = new RandomAccessFile(a.toFile(), "r")) {
            r = Invocar(m, f, a);
        }
        Aviso(dif, NoBorrado("V.a68", motivos.get("borrar")), r);
        Intacto(dif, a, viejo, "V.a68");
        Registrar("U5 abierto (RandomAccessFile)", "U5", dif);

        // Carpeta vacía: se borra sin aviso (DQ3)
        dif = new ArrayList<>();
        d = Sub(dir, "carpeta-vacia");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.createDirectories(a);
        Aviso(dif, null, Invocar(m, f, a));
        if (Files.exists(a)) dif.add("la carpeta V.a68 no se borró");
        Registrar("U5 carpeta vacía", "U5", dif);

        // Carpeta con contenido: aviso con "La carpeta no está vacía" y la carpeta intacta (DQ3)
        dif = new ArrayList<>();
        d = Sub(dir, "carpeta-llena");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.createDirectories(a);
        Files.write(a.resolve("x.txt"), "x".getBytes(StandardCharsets.ISO_8859_1));
        Aviso(dif, NoBorrado("V.a68", "La carpeta no está vacía"), Invocar(m, f, a));
        Intacto(dif, a.resolve("x.txt"), "x".getBytes(StandardCharsets.ISO_8859_1), "V.a68\\x.txt");
        Registrar("U5 carpeta con contenido", "U5", dif);
    }

    // U6: CopiarA68 (E10, E12, E17; DQ3, DQ7, DE5, DE6) y los 50 contenidos generados
    // Avisos: el recurso del try se abre solo para provocar el fallo que se prueba (no se lee)
    @SuppressWarnings("try")
    private static void U6(Path dir, Map<String, String> motivos) throws Exception {
        Method m = Metodo("CopiarA68", Path.class, Path.class);
        byte[] viejo = VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1);
        byte[] valido = Crlf(VALIDO);
        List<String> dif;
        Path d, f, a;

        // \r\n, sin salto final y bytes 0x80–0xFF: copia exacta
        dif = new ArrayList<>();
        d = Sub(dir, "bytes");
        f = d.resolve("F.MINGOL");
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write("BEGIN\r\n".getBytes(StandardCharsets.ISO_8859_1));
        for (int b = 0x80; b <= 0xFF; b++) bo.write(b);
        bo.write("\r\nEND".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(f, bo.toByteArray());
        a = d.resolve("F.a68");
        Aviso(dif, null, Invocar(m, f, a));
        Copia(dif, f, a);
        Registrar("U6 \\r\\n sin salto final y bytes 0x80-0xFF", "U6", dif);

        // Reemplazo de un .a68 viejo
        dif = new ArrayList<>();
        d = Sub(dir, "reemplazo");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("F.a68");
        Files.write(a, viejo);
        Aviso(dif, null, Invocar(m, f, a));
        Copia(dif, f, a);
        Registrar("U6 reemplazo de un .a68 viejo", "U6", dif);

        // d14 sin .a68 viejo: aviso corto y sin .a68 (DE5)
        dif = new ArrayList<>();
        ComprobarD14(m, dir.resolve("d14-sin-viejo"), false, dif);
        Registrar("U6 d14 fuente inexistente sin .a68 viejo", "U6", dif);

        // d14 con .a68 viejo: aviso largo y el viejo intacto (DQ7, DE6)
        dif = new ArrayList<>();
        ComprobarD14(m, dir.resolve("d14-con-viejo"), true, dif);
        Registrar("U6 d14 fuente inexistente con .a68 viejo (DQ7)", "U6", dif);

        // .a68 abierto por el arnés (d11): aviso largo y el contenido viejo intacto
        dif = new ArrayList<>();
        d = Sub(dir, "abierto");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.write(a, viejo);
        Object r;
        try (RandomAccessFile raf = new RandomAccessFile(a.toFile(), "r")) {
            r = Invocar(m, f, a);
        }
        Aviso(dif, NoGeneradoLargo("V.a68", motivos.get("copiar")), r);
        Intacto(dif, a, viejo, "V.a68");
        Registrar("U6 abierto (RandomAccessFile, d11)", "U6", dif);

        // MismoArchivo: sin aviso y el fuente intacto (E12)
        dif = new ArrayList<>();
        d = Sub(dir, "mismo");
        f = d.resolve("x.a68");
        Files.write(f, valido);
        Aviso(dif, null, Invocar(m, f, d.resolve("X.A68")));
        Intacto(dif, f, valido, "el fuente x.a68");
        Registrar("U6 MismoArchivo", "U6", dif);

        // Carpeta vacía: Files.copy la reemplaza por el archivo (DQ3)
        dif = new ArrayList<>();
        d = Sub(dir, "carpeta-vacia");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.createDirectories(a);
        Aviso(dif, null, Invocar(m, f, a));
        Copia(dif, f, a);
        Registrar("U6 carpeta vacía", "U6", dif);

        // Carpeta con contenido (d12): aviso largo y la carpeta intacta (DQ3)
        dif = new ArrayList<>();
        d = Sub(dir, "carpeta-llena");
        f = d.resolve("F.MINGOL");
        Files.write(f, valido);
        a = d.resolve("V.a68");
        Files.createDirectories(a);
        Files.write(a.resolve("x.txt"), "x".getBytes(StandardCharsets.ISO_8859_1));
        Aviso(dif, NoGeneradoLargo("V.a68", "La carpeta no está vacía"), Invocar(m, f, a));
        if (!Files.isDirectory(a)) dif.add("V.a68 ya no es una carpeta");
        Intacto(dif, a.resolve("x.txt"), "x".getBytes(StandardCharsets.ISO_8859_1), "V.a68\\x.txt");
        Registrar("U6 carpeta con contenido (d12)", "U6", dif);

        // Property-Based Tests (copia): 50 contenidos, \n, \r\n o mezcla, con y sin salto final, bytes 0x20–0xFF
        dif = new ArrayList<>();
        Random rnd = new Random(20250506L);
        d = Sub(dir, "generados");
        f = d.resolve("G.MINGOL");
        a = d.resolve("G.a68");
        for (int i = 0; i < 50; i++) {
            java.io.ByteArrayOutputStream g = new java.io.ByteArrayOutputStream();
            int lineas = rnd.nextInt(7);
            int modo = rnd.nextInt(3);
            boolean salto = rnd.nextBoolean();
            for (int l = 0; l < lineas; l++) {
                int largo = rnd.nextInt(21);
                for (int k = 0; k < largo; k++) g.write(0x20 + rnd.nextInt(0xE0));
                if (l < lineas - 1 || salto) {
                    boolean crlf = modo == 1 || (modo == 2 && rnd.nextBoolean());
                    if (crlf) g.write('\r');
                    g.write('\n');
                }
            }
            Files.write(f, g.toByteArray());
            Object av = Invocar(m, f, a);
            if (av != null) dif.add("contenido " + i + ": aviso [" + av + "]");
            else if (!Files.isRegularFile(a) || Files.mismatch(f, a) != -1) dif.add("contenido " + i + ": no es copia exacta");
        }
        Registrar("U6 50 contenidos generados", "U6", dif);
    }

    // Caso de Preparar en un hijo hijoprep (U7): sale 0 y PREPARAR|<esperado>
    private static Caso Prep(String id, String arg, String preparar) {
        Caso c = new Caso(id, "U7").arg(arg);
        c.prep = true;
        c.preparar = preparar;
        c.codigo = 0;
        return c;
    }

    // U7: Preparar en hijos (ramas a–e de E19, DQ2, DQ4, E14–E17, E20, x05, e04); lista de archivos antes y después
    private static void U7(Path dir, Path logs, Map<String, String> motivos) throws Exception {
        List<Caso> l = new ArrayList<>();
        // (a) InvalidPathException: el argumento tal cual
        String[] a = {"a?b.MINGOL", "a*b", "Prog "};
        for (int i = 0; i < a.length; i++) {
            Caso c = Prep("u7a" + (i + 1), a[i], "null");
            c.stderr.add(RutaInvalida(a[i]));
            l.add(c);
        }
        // (b) No existe; dispositivos sin código especial (DQ4); <ruta> = RutaEfectiva (DQ2)
        String[][] b = {{"NoExiste", "NoExiste.MINGOL"}, {"NoExiste.MINGOL", "NoExiste.MINGOL"}, {"CON", "CON.MINGOL"},
                {"CON.MINGOL", "CON.MINGOL"}, {"AUX", "AUX.MINGOL"}, {"COM1", "COM1.MINGOL"}, {"LPT1.MINGOL", "LPT1.MINGOL"},
                {"NUL.txt", "NUL.txt"}, {"a:b.MINGOL", "a:b.MINGOL"}};
        for (int i = 0; i < b.length; i++) {
            Caso c = Prep("u7b" + (i + 1), b[i][0], "null");
            c.stderr.add(NoEncontrado(b[i][1]));
            c.rutaDQ2 = b[i][1];
            // Área 5 (CI #9): CON, AUX, COM1, LPT1 y NUL.txt son dispositivos; su rama la decide el SO
            if (i >= 2 && i <= 7) Dispositivo(c, b[i][1]);
            l.add(c);
        }
        Caso x09 = Prep("u7b_x09", "SinExt2", "null");
        x09.archivos.put("SinExt2", Crlf(VALIDO));
        x09.stderr.add(NoEncontrado("SinExt2.MINGOL"));
        x09.rutaDQ2 = "SinExt2.MINGOL";
        l.add(x09);
        // (c) No es un archivo regular: carpeta y NUL.
        Caso carpeta = Prep("u7c_carpeta", "carpeta", "null");
        carpeta.carpetas.add("carpeta.MINGOL");
        carpeta.stderr.add(NoEsArchivo("carpeta.MINGOL"));
        carpeta.rutaDQ2 = "carpeta.MINGOL";
        l.add(carpeta);
        Caso nul = Prep("u7c_nul", "NUL.", "null");
        nul.stderr.add(NoEsArchivo("NUL."));
        nul.rutaDQ2 = "NUL.";
        Dispositivo(nul, "NUL."); // área 5 (CI #9): en el runner windows-2022 cae en (b)
        l.add(nul);
        // (d) Lectura que falla: fuente con FileChannel.lock() del arnés (d13)
        Caso d13 = Prep("u7d_d13", "Prog.MINGOL", "null");
        d13.archivos.put("Prog.MINGOL", Crlf(VALIDO));
        d13.stderr.add(NoLeido("Prog.MINGOL", motivos.get("leer")));
        d13.rutaDQ2 = "Prog.MINGOL";
        d13.retener = dd -> {
            RandomAccessFile raf = new RandomAccessFile(dd.resolve("Prog.MINGOL").toFile(), "rw");
            FileLock lock = raf.getChannel().lock();
            return () -> { lock.release(); raf.close(); };
        };
        l.add(d13);
        // E14 (x07): .MINGOL crea -errores.txt con 115
        Caso x07 = Prep("u7e_x07", ".MINGOL", "null");
        x07.archivos.put(".MINGOL", Crlf(VALIDO));
        x07.errores.put("-errores.txt", Errores(VALIDO, SinMensajes(), E115(".MINGOL")));
        x07.agrega.add("-errores.txt");
        l.add(x07);
        // E15 (d06): Punto. abre Punto
        Caso d06 = Prep("u7e_d06", "Punto.", "null");
        d06.archivos.put("Punto", Crlf(VALIDO));
        d06.errores.put("Punto-errores.txt", Errores(VALIDO, SinMensajes(), E115("Punto.")));
        d06.agrega.add("Punto-errores.txt");
        l.add(d06);
        // E16 (d08): carpeta Prog-errores.txt con un programa válido
        Caso d08 = Prep("u7e_d08", "Prog.MINGOL", "null");
        d08.archivos.put("Prog.MINGOL", Crlf(VALIDO));
        d08.carpetas.add("Prog-errores.txt");
        d08.stderr.add(NoErrores("Prog-errores.txt", motivos.get("errores")));
        l.add(d08);
        // E17 (d09): aviso de E16 y el .a68 viejo borrado
        Caso d09 = Prep("u7e_d09", "Prog.MINGOL", "null");
        d09.archivos.put("Prog.MINGOL", Crlf(ERRONEO));
        d09.archivos.put("Prog.a68", VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        d09.carpetas.add("Prog-errores.txt");
        d09.stderr.add(NoErrores("Prog-errores.txt", motivos.get("errores")));
        d09.quita.add("Prog.a68");
        l.add(d09);
        // E20 (d15): 115, 307, 308 en ese orden
        Caso d15 = Prep("u7e_d15", "1a.MINGOL", "null");
        d15.archivos.put("1a.MINGOL", new byte[0]);
        d15.errores.put("1a-errores.txt", Errores(L(), SinMensajes(), E115("1a.MINGOL"), E307(), E308()));
        d15.agrega.add("1a-errores.txt");
        l.add(d15);
        // x05: el fuente Otro2.a68 queda intacto y sin aviso (E12)
        Caso x05 = Prep("u7e_x05", "Otro2.a68", "null");
        x05.archivos.put("Otro2.a68", Crlf(VALIDO));
        x05.errores.put("Otro2-errores.txt", Errores(VALIDO, SinMensajes(), E115("Otro2.a68")));
        x05.agrega.add("Otro2-errores.txt");
        l.add(x05);
        // e04: 303 bajo la 3 y 115 en el bloque final
        Caso e04 = Prep("u7e_e04", "Bad Name.MINGOL", "null");
        e04.archivos.put("Bad Name.MINGOL", Crlf(ERRONEO));
        e04.errores.put("Bad Name-errores.txt", Errores(ERRONEO, Bajo303(), E115("Bad Name.MINGOL")));
        e04.agrega.add("Bad Name-errores.txt");
        l.add(e04);
        // Control (e): válido, devuelve el .a68 relativo a la carpeta actual, copia exacta
        Caso n01 = Prep("u7e_valido", "CalcularInflacion.MINGOL", "CalcularInflacion.a68");
        n01.archivos.put("CalcularInflacion.MINGOL", Crlf(VALIDO));
        n01.errores.put("CalcularInflacion-errores.txt", Errores(VALIDO, SinMensajes()));
        n01.a68.put("CalcularInflacion.a68", "CalcularInflacion.MINGOL");
        n01.agrega.add("CalcularInflacion-errores.txt");
        n01.agrega.add("CalcularInflacion.a68");
        l.add(n01);
        for (Caso c : l) {
            Path d = Sub(dir, c.id);
            List<String> dif = new ArrayList<>();
            Correr(c, d, logs, dif);
            Registrar("U7 " + c.id + " [" + c.argsRecibidos.get(0) + "]", "U7", dif);
        }
    }

    // U8: TestProyecto(String) en hijoref: sin 115 (DE2) y .a68 solo sin ERROR (DE8)
    private static void U8(Path dir, Path logs) throws Exception {
        for (boolean valido : new boolean[] {true, false}) {
            String id = valido ? "valido" : "erroneo";
            Path d = Sub(dir, id);
            Path f = d.resolve("1Calcular.MINGOL");
            Files.write(f, Crlf(valido ? VALIDO : ERRONEO));
            ResultadoHijo r = LanzarRef("u8_" + id, "1Calcular.MINGOL", d, logs);
            List<String> dif = new ArrayList<>();
            if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s");
            String tp = r.stdout.replaceAll("\\R+$", "");
            if (!tp.equals("TP|" + valido)) dif.add("hijoref: esperado [TP|" + valido + "], obtenido [" + Escapar(tp) + "]");
            if (!r.stderr.isBlank()) dif.add("stderr no vacío: " + Escapar(r.stderr));
            CompararErrores(d.resolve("1Calcular-errores.txt"), "1Calcular-errores.txt",
                    Errores(valido ? VALIDO : ERRONEO, valido ? SinMensajes() : Bajo303()), dif);
            Path a68 = d.resolve("1Calcular.a68");
            if (valido) Copia(dif, f, a68);
            else if (Files.exists(a68)) dif.add("dejó 1Calcular.a68 con errores (DE8)");
            Registrar("U8 TestProyecto 1Calcular " + id, "U8", dif);
        }
    }

    // ------------------------------------------------------------------
    // Tarea 3.5 (área 5): main M1–M10 (Integration Tests del diseño)
    // ------------------------------------------------------------------

    private static void ModoMain() throws Exception {
        base = CarpetaSegura();
        repo = RaizRepositorio();
        Guardias(false);
        Path trabajo = base.resolve("main");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        ecoJar = ArmarEco();
        Path logs = trabajo.resolve("logs");
        // M7: motivos de las mismas situaciones calculados en el arnés (idioma de Windows)
        Map<String, String> motivos = Motivos(trabajo.resolve("_motivos"));
        System.out.println("Motivos de este equipo: " + motivos);
        // Mismos casos y oráculos que la exploración; se eligen por id
        ArmarCasos(motivos);
        Map<String, Caso> porId = new LinkedHashMap<>();
        for (Caso c : casos) porId.put(c.id, c);
        List<String[]> sel = new ArrayList<>();

        // M1 E3: código literal del eco; también la lista de Property-Based Tests y ECO_CODIGO=1 (informativo)
        for (String id : new String[] {"d16_0", "d16_2", "d16_300", "d16_65535", "d16m5"}) sel.add(new String[] {"M1", id});
        for (int codigo : new int[] {3, 127, 255, 256, -1, 1}) {
            String id = "m1" + (codigo < 0 ? "m" + (-codigo) : "_" + codigo);
            Caso c = Valido(id, "M1", "Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "", "Prog.MINGOL", "Prog");
            c.ecoCodigo = codigo;
            c.codigo = codigo;
            porId.put(id, c);
            sel.add(new String[] {"M1", id});
        }
        // M2 REQ-EXEC-01; M3 E5
        for (String id : new String[] {"e01", "e02", "e03", "e04"}) sel.add(new String[] {"M2", id});
        for (String id : new String[] {"r01", "r03", "r04"}) sel.add(new String[] {"M3", id});
        // M4: jar temporal desde build\classes (nunca dist\), como r03
        Path jar = trabajo.resolve("_jar").resolve("Mingol.jar");
        Files.createDirectories(jar.getParent());
        String falloJar = CrearJar(jar, repo.resolve("build").resolve("classes"), logs);
        Caso m4 = Valido("m4_jar", "M4", "fuente\\Prog.MINGOL", Crlf(VALIDO), VALIDO, SinMensajes(), "trabajo",
                "..\\fuente\\Prog.MINGOL", "Prog");
        m4.carpetas.add("trabajo");
        m4.jar = true;
        m4.jarRuta = jar;
        porId.put(m4.id, m4);
        sel.add(new String[] {"M4", m4.id});
        // M5 entrada; M6 E12; M7 E16/E17/E21; M8 E15/E20
        for (String id : new String[] {"f01", "f02", "x09", "d04a", "d03"}) sel.add(new String[] {"M5", id});
        sel.add(new String[] {"M6", "x05"});
        for (String id : new String[] {"d08", "d09", "d10", "d11", "d12"}) sel.add(new String[] {"M7", id});
        for (String id : new String[] {"d06", "x06", "d15"}) sel.add(new String[] {"M8", id});
        // M9 meta 2.12: proyecto.mingol con un proyecto.a68 viejo, que se borra; control n01
        Caso p01 = porId.get("p01");
        p01.archivos.put("proyecto.a68", VIEJO_A68.getBytes(StandardCharsets.ISO_8859_1));
        p01.quita.add("proyecto.a68");
        sel.add(new String[] {"M9", "p01"});
        sel.add(new String[] {"M9", "n01"});
        // M10 control de preservación: \n y salto final, ECO_CODIGO=0
        Caso m10 = Valido("m10", "M10", "Prog.MINGOL", Lf(VALIDO), VALIDO, SinMensajes(), "", "Prog.MINGOL", "Prog");
        porId.put(m10.id, m10);
        sel.add(new String[] {"M10", m10.id});

        for (String[] s : sel) {
            Caso c = porId.get(s[1]);
            List<String> dif = new ArrayList<>();
            if (c == m4 && falloJar != null) {
                dif.add("no se pudo armar el jar temporal: " + falloJar);
                Registrar(s[0] + " " + c.id, s[0], dif);
                continue;
            }
            Path dir = c.dirAbsoluto != null ? c.dirAbsoluto : trabajo.resolve(c.id);
            BorrarArbol(dir);
            Files.createDirectories(dir);
            Correr(c, dir, logs, dif);
            if (c == m10) {
                // M10: el -errores.txt de main es el mismo que da hijoref para el mismo fuente
                Path ref = Sub(trabajo, "m10-ref");
                Files.write(ref.resolve("Prog.MINGOL"), Lf(VALIDO));
                ResultadoHijo rr = LanzarRef("m10-ref", "Prog.MINGOL", ref, logs);
                if (!rr.stdout.replaceAll("\\R+$", "").equals("TP|true")) dif.add("hijoref: " + Escapar(rr.stdout));
                Path em = dir.resolve("Prog-errores.txt");
                Path er = ref.resolve("Prog-errores.txt");
                if (!Files.isRegularFile(em) || !Files.isRegularFile(er)
                        || !Arrays.equals(Files.readAllBytes(em), Files.readAllBytes(er))) dif.add("-errores.txt distinto del de hijoref");
            }
            Registrar(s[0] + " " + c.id, s[0], dif);
        }
        ImprimirResumen("main");
    }

    // M4: jar --create con la herramienta jar del JDK (como PruebasCriticos), dentro de %TEMP%; null si salió bien
    private static String CrearJar(Path jar, Path clases, Path logs) throws Exception {
        Files.deleteIfExists(jar);
        String herramienta = Paths.get(System.getProperty("java.home"), "bin", "jar.exe").toString();
        ProcessBuilder pb = new ProcessBuilder(herramienta, "--create", "--file", jar.toString(),
                "--main-class", "Mingol.Mingol", "-C", clases.toString(), ".");
        pb.redirectErrorStream(true);
        Files.createDirectories(logs);
        Path log = logs.resolve("jar.log");
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        if (!p.waitFor(LIMITE_SEGUNDOS * 3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "jar no terminó";
        }
        if (p.exitValue() != 0 || !Files.exists(jar)) return "código " + p.exitValue() + ": " + LeerRecortado(log, 4096);
        return null;
    }

    // ------------------------------------------------------------------
    // Eco y guardias (R10, "Carpeta y guardias" del diseño)
    // ------------------------------------------------------------------

    // eco\eco.jar con el manifiesto Premain-Class y los bytes de EcoA68g.class del classpath
    private static Path ArmarEco() throws IOException {
        Path dir = base.resolve("eco");
        BorrarArbol(dir);
        Files.createDirectories(dir);
        Path jar = dir.resolve("eco.jar");
        String ruta = jar.toString();
        if (ruta.contains(" ") || ruta.contains("\"") || ruta.contains("'")) {
            System.err.println("La ruta de eco.jar tiene blancos o comillas (JDK_JAVA_OPTIONS la partiría): " + ruta);
            System.exit(2);
        }
        byte[] clase;
        try (InputStream in = PruebasEntrada.class.getClassLoader().getResourceAsStream("EcoA68g.class")) {
            if (in == null) throw new IOException("No se encontró EcoA68g.class en el classpath del arnés");
            clase = in.readAllBytes();
        }
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(new Attributes.Name("Premain-Class"), "EcoA68g");
        try (JarOutputStream j = new JarOutputStream(new FileOutputStream(jar.toFile()), mf)) {
            j.putNextEntry(new JarEntry("EcoA68g.class"));
            j.write(clase);
            j.closeEntry();
        }
        return jar;
    }

    // Guardias de exploracion y main: API de 0136e1b presente, ningún a68g real, eco java.exe
    private static void Guardias() throws Exception {
        Guardias(true);
    }

    // Área 5 (3.5): unitarias y main no dependen de dist\Mingol.jar (tarea 5)
    private static void Guardias(boolean exigirDist) throws Exception {
        Class<?> mingol = Class.forName("Mingol.Mingol");
        try {
            mingol.getDeclaredMethod("EjecutarA68g", String.class);
            mingol.getDeclaredMethod("TestProyecto", String.class);
        } catch (NoSuchMethodException ex) {
            System.err.println("Falta Mingol.EjecutarA68g o Mingol.TestProyecto: " + ex.getMessage());
            System.exit(2);
        }
        List<Path> reales = new ArrayList<>();
        Path sys32 = Paths.get(System.getenv("SystemRoot"), "System32");
        Path clases = repo.resolve("build").resolve("classes");
        for (Path d : new Path[] {sys32, clases}) {
            for (String n : new String[] {"a68g", "a68g.exe"}) if (Files.exists(d.resolve(n))) reales.add(d.resolve(n));
        }
        if (!reales.isEmpty()) {
            System.err.println("Hay un a68g real que el hijo podría encontrar: " + reales);
            System.exit(2);
        }
        Path java = JavaExe();
        String n = java.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(java) || !n.equals("java.exe")) {
            System.err.println("El eco debe ser java.exe del JDK: " + java);
            System.exit(2);
        }
        if (exigirDist && !Files.isRegularFile(repo.resolve("dist").resolve("Mingol.jar"))) {
            System.err.println("No existe dist\\Mingol.jar (j01, j02)");
            System.exit(2);
        }
    }

    private static Path JavaExe() {
        return Paths.get(System.getProperty("java.home"), "bin", "java.exe");
    }

    // ------------------------------------------------------------------
    // Lanzamiento de hijos (R8), adaptado de PruebasSemantica
    // ------------------------------------------------------------------

    private static class ResultadoHijo {
        boolean termino;
        int codigo = Integer.MIN_VALUE;
        String stdout = "";
        String stderr = "";
    }

    // main real (o el jar de 0136e1b) con entorno controlado y el eco (DE9)
    private static ResultadoHijo LanzarMain(Caso c, Path cwd, Path logs) throws Exception {
        List<String> comando = new ArrayList<>();
        comando.add(JavaExe().toString());
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.equals("-ea") || a.equals("-enableassertions")) {
                comando.add("-ea");
                break;
            }
        }
        comando.add("--enable-preview");
        comando.add("-Xmx256m");
        if (c.jar) {
            comando.add("-jar");
            // Área 5 (3.5, M4): jar temporal si lo hay; si no, el de dist (j01, j02)
            comando.add((c.jarRuta != null ? c.jarRuta : repo.resolve("dist").resolve("Mingol.jar")).toString());
        } else {
            comando.add("-cp");
            comando.add(System.getProperty("java.class.path"));
            comando.add("Mingol.Mingol");
        }
        if (c.argsEnviados != null) comando.addAll(c.argsEnviados);

        Files.createDirectories(logs);
        File out = logs.resolve(c.id + ".out").toFile();
        File err = logs.resolve(c.id + ".err").toFile();
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.directory(cwd.toFile());
        // Entorno vacío salvo lo indispensable, A68G = java.exe y el agente del eco
        Map<String, String> env = pb.environment();
        String systemRoot = System.getenv("SystemRoot");
        String temp = System.getenv("TEMP");
        String tmp = System.getenv("TMP");
        env.clear();
        env.put("SystemRoot", systemRoot);
        if (temp != null) env.put("TEMP", temp);
        if (tmp != null) env.put("TMP", tmp);
        env.put("PATH", Paths.get(systemRoot, "System32").toString());
        // Área 5 (tarea 2): A68G elegible para el caso de búsqueda sin a68g (3.1)
        env.put("A68G", c.a68gEnv != null ? c.a68gEnv : JavaExe().toString());
        env.put("JDK_JAVA_OPTIONS", "-javaagent:" + ecoJar);
        env.put("ECO_CODIGO", String.valueOf(c.ecoCodigo));
        pb.redirectOutput(out);
        pb.redirectError(err);
        Process p = pb.start();
        // stdin cerrado (R8)
        p.getOutputStream().close();
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

    // Lee como mucho maxBytes en la codificación nativa de los hijos
    private static String LeerRecortado(Path p, int maxBytes) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        return new String(b, 0, Math.min(b.length, maxBytes), CP1252);
    }

    // ------------------------------------------------------------------
    // Utilidades (adaptadas de PruebasSemantica)
    // ------------------------------------------------------------------

    // %TEMP% (o java.io.tmpdir), con la ruta real para no depender de los nombres cortos 8.3
    private static Path CarpetaTemp() throws IOException {
        String temp = System.getenv("TEMP");
        Path b = (temp != null && !temp.isBlank()) ? Paths.get(temp) : Paths.get(System.getProperty("java.io.tmpdir"));
        return b.toRealPath();
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

    // Archivos y carpetas (con "\" final) de una carpeta, relativos a ella
    private static Set<String> Entradas(Path dir) throws IOException {
        Set<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> !p.equals(dir)).forEach(p -> r.add(dir.relativize(p).toString() + (Files.isDirectory(p) ? "\\" : "")));
        }
        return r;
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

    // Mensajes de un -errores.txt como "línea:mensaje" (0 = bloque final sin líneas antes)
    private static List<String> Mensajes(String archivo) {
        List<String> r = new ArrayList<>();
        String linea = "0";
        for (String l : archivo.split("\n", -1)) {
            if (l.length() >= 5 && l.substring(0, 5).matches("\\d{5}")) linea = String.valueOf(Integer.parseInt(l.substring(0, 5)));
            else if (l.startsWith(S8)) r.add(linea + ":" + l.substring(S8.length()));
        }
        return r;
    }

    // Contenido del primer texto entre comillas de la línea
    private static String TextoEntreComillas(String linea) {
        int a = linea.indexOf('"');
        int b = linea.indexOf('"', a + 1);
        return (a < 0 || b < 0) ? "" : linea.substring(a + 1, b);
    }

    private static List<String> L(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    private static String Primeras(List<String> l, int n) {
        return l.size() <= n ? l.toString() : l.subList(0, n) + " (+" + (l.size() - n) + ")";
    }

    // Una sola línea por dato: se escapan contrabarras y saltos
    private static String Escapar(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    // Imprime OK/FALLA con las diferencias y lo agrega al resumen por grupo
    private static void Registrar(String caso, String grupo, List<String> diferencias) {
        boolean control = grupo.equals("control");
        diferenciasPorCaso.put(caso, diferencias);
        int[] g = porGrupo.computeIfAbsent(grupo, k -> new int[2]);
        g[0]++;
        String etiqueta = "[" + grupo + "] ";
        if (diferencias.isEmpty()) {
            System.out.println("OK    " + etiqueta + caso);
            resumen.add("OK    " + caso);
            // Un caso de C que pasa: el bug no se reproduce
            if (!control) noReproducidos.add(caso);
        } else {
            fallas++;
            g[1]++;
            if (control) controlesQueFallan.add(caso);
            System.out.println("FALLA " + etiqueta + caso);
            for (String d : diferencias) System.out.println("      - " + d);
            resumen.add("FALLA " + caso + ": " + diferencias.get(0));
        }
    }

    // Resumen que separa el control, los 34 de C de bugfix.md y d01–d16
    private static void ImprimirResumen(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) if (r.startsWith("FALLA")) System.out.println(r);
        for (Map.Entry<String, int[]> e : porGrupo.entrySet()) {
            int[] g = e.getValue();
            System.out.println("Grupo " + e.getKey() + ": " + g[0] + ", FALLA: " + g[1] + ", OK: " + (g[0] - g[1]));
        }
        if (!controlesQueFallan.isEmpty()) System.out.println("Controles que fallan: " + controlesQueFallan);
        // Área 5 (tarea 2): "bug no reproducido" solo tiene sentido en la exploración
        if (modo.equals("exploracion")) {
            System.out.println("Casos de C que pasan (bug no reproducido): " + (noReproducidos.isEmpty() ? "ninguno" : noReproducidos));
        }
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }
}

// Agente del eco de a68g (DE9, DQ6): se carga con JDK_JAVA_OPTIONS=-javaagent:<eco.jar> en toda JVM del hijo,
// pero solo actúa en la que lanza MINGOL como a68g: comando = ruta absoluta de un archivo regular .a68
class EcoA68g {
    public static void premain(String argumentos) {
        try {
            String comando = System.getProperty("sun.java.command");
            if (comando == null || !comando.toLowerCase(Locale.ROOT).endsWith(".a68")) return;
            Path p = Paths.get(comando);
            if (!p.isAbsolute() || !Files.isRegularFile(p)) return;
            String valor = System.getenv("ECO_CODIGO");
            int codigo = (valor == null || valor.isBlank()) ? 0 : Integer.parseInt(valor.trim());
            System.err.println("ECO-A68G|" + comando);
            System.err.flush();
            System.exit(codigo);
        } catch (RuntimeException ex) {
            // Cualquier problema: el agente no hace nada y la JVM sigue normalmente
        }
    }
}
