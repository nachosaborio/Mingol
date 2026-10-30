// Arnés de la spec mingol-criticos-fix (bugs A, B y C).
// Clase sin paquete, como RunTestProyecto. Se compila contra build\classes y se ejecuta
// desde una carpeta temporal ($env:TEMP\mingol-criticos): todos los archivos se crean ahí.
//
// Modos:
//   Pendiente 9: se retiraron exploracion, hijo, capturar, preservacion y arreglo (comparaban el
//                    formato viejo del archivo de errores y su captura golden ya no existe).
//   hijopres <archivo> <argumento> -> interno de main (referencia de TestProyecto, DL1).
//   unitarias     -> tarea 3.6: EvaluateIdentifier, RutaA68, BuscarA68g (incluido el rechazo de
//                    .bat/.cmd), EsScriptPorLotes, CarpetaAplicacion y el parser de e2, e3, e7, e8, e9.
//   main          -> tarea 3.6: los 9 casos de main en hijos con entorno controlado (A68G y PATH).
//                    Área 5: los nombres inválidos de (2) dan 115; (2b) prueba la inyección en la
//                    carpeta; el código esperado es el del eco java.exe (1) o 1 por errores (E3).
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale; // Comparaciones sin depender del idioma (tarea 3.6)
import java.util.Map;
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
        // e1 (área 3, sintaxis corregida): declaración sin valor inicial y asignación sin ";" antes de END,
        // para que siga siendo válido (lo usan main (2), (5), (6) y ref-e1)
        programa("e1", false, "BEGIN", "INT x;", "x := 1", "END");
        programa("e2", false, "BEGIN", "INT x := 1;", "IF (x) THEN");
        programa("e3", false, "BEGIN", "INT x := 1;", "IF (x) THEN", "SKIP;", "ELSE");
        // e4 con SKIP (DS1, sintaxis corregida): la instrucción ALGOL ignorada no cuenta como comando;
        // sin el SKIP daría 217 y main dejaría de verlo válido
        programa("e4", false, "BEGIN", "RETURN 5", "SKIP", "END");
        programa("e5", false, "BEGIN", "INT x := 1;", "x = 3");
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
            // Pendiente 9: sin exploracion, capturar, preservacion ni arreglo
            System.err.println("Uso: PruebasCriticos <unitarias|main>");
            System.exit(2);
        }
        switch (args[0]) {
            // Pendiente 9: exploracion, hijo, capturar, preservacion y arreglo caen en default (Modo desconocido)
            case "hijopres":
                // Interno de main (DL1): referencia de TestProyecto en la carpeta actual
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
            case "main":
                // Tarea 3.6: main real en hijos con entorno controlado
                ModoMain();
                break;
            default:
                System.err.println("Modo desconocido: " + args[0]);
                System.exit(2);
        }
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
    // Utilidades del hijo hijopres y de las carpetas temporales (Pendiente 9: sin captura ni generador)
    // ------------------------------------------------------------------

    // Salida completa del hijo hijopres (sin recortar a 64 KB)
    private static final int MAX_META = 16 * 1024 * 1024;

    // %TEMP%\mingol-criticos-golden: ya no hay captura (Pendiente 9); solo se usa su padre (la raíz de %TEMP%)
    private static Path CarpetaGolden() {
        String temp = System.getenv("TEMP");
        Path base = (temp != null && !temp.isBlank()) ? Paths.get(temp) : Paths.get(System.getProperty("java.io.tmpdir"));
        return base.resolve("mingol-criticos-golden");
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

    // Misma java.exe, --enable-preview, -Xmx256m, mismo classpath; waitFor(10 s) y destroyForcibly.
    // Argumentos del hijo, nombre del log y límite de lectura (Pendiente 9: única versión que queda)
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

    // Nombre sin la última extensión (lo que se espera de RutaA68)
    private static String SinUltimaExtension(String ruta) {
        String nombre = Paths.get(ruta).getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }

    // ------------------------------------------------------------------
    // Tarea 3.6: utilidades comunes de los modos unitarias y main (Pendiente 9: sin arreglo)
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
            // Área 5 (E5): el .a68 va en la carpeta actual, sin la carpeta del fuente
            {"mingolito.mingol", "mingolito.a68"}, {"sub.dir\\t11.mingol", "t11.a68"},
            {"C:\\x.y\\z.Mingol", "z.a68"}, {"mi prog.mingol", "mi prog.a68"},
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
        for (String id : new String[]{"e2", "e3", "e7", "e8", "e9"}) {
            // e8 (área 3, sintaxis corregida): "SKIP;" ya no consume el END, así que el 204 de FI se obtiene con END
            String esperado = "Error 204: Se esperaba un token de tipo FI pero se obtuvo un " + (id.equals("e8") ? "END" : "EOF");
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
            // Nombre con el token obtenido (END en e8 desde el área 3)
            Registrar("parser " + id + " (204 de FI con " + (id.equals("e8") ? "END" : "EOF") + ", < 10 s)", d, r);
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
            // Área 5 (E1, REQ-INPUT-01): solo mingolito.mingol es un nombre válido
            boolean valido = nombre.equals("mingolito.mingol");
            if (valido) {
                // Área 5 (E3, E10): código 1 (el del eco java.exe) y .a68 copia byte a byte
                ChequearSalida(r, 1, d, false);
                ChequearEco(r, a68, d);
                if (!Files.exists(a68)) d.add("no existe " + a68);
                else if (Files.mismatch(dificil.resolve(nombre), a68) != -1L) d.add(a68.getFileName() + " no es copia byte a byte del fuente");
                if (Files.exists(dificil.resolve("a68ito.a68"))) d.add("se escribió a68ito.a68");
            } else {
                // Área 5 (E1, E6): 115, código 1, sin eco ni .a68
                ChequearSalida(r, 1, d, true);
                if (r.stderr.contains(ECO_MARCA)) d.add("se invocó el eco con un nombre inválido");
                if (Files.exists(a68)) d.add("existe " + a68 + " con un nombre inválido");
                ChequearErrores115(dificil.resolve(SinUltimaExtension(nombre) + "-errores.txt"), e1, nombre, d);
            }
            SinInyectado(d, dificil, base, repo, Paths.get("").toAbsolutePath());
            // main esperó: el eco ya escribió y no queda vivo ningún proceso con esa ruta
            if (ProcesosVivosCon(a68.toString()) > 0) d.add("queda un proceso vivo con " + a68);
            Registrar("main (2) " + (valido ? "eco con '" : "115 con '") + nombre + "'", d, r);
        }

        // (2b) Área 5: la cobertura de inyección pasa a la carpeta, que llega a a68g en la ruta absoluta
        String[] carpetas = {"mi prog", "a&mkdir INYECTADO&b", "a^b", "p%PATH%(1)"};
        for (int i = 0; i < carpetas.length; i++) {
            Path dir = Files.createDirectories(dificil.resolve(carpetas[i]));
            Escribir(dir.resolve("prog.mingol"), e1, CON_SALTO_FINAL.get("e1"));
            Path a68 = dir.resolve("prog.a68").toAbsolutePath();
            ResultadoHijo r = LanzarMain(ConClases(clases, "prog.mingol"), dir, "main-eco-carpeta-" + i, Entorno(eco, system32));
            List<String> d = new ArrayList<>();
            ChequearSalida(r, 1, d, false);
            ChequearEco(r, a68, d);
            SinInyectado(d, dir, dificil, base, repo, Paths.get("").toAbsolutePath());
            if (ProcesosVivosCon(a68.toString()) > 0) d.add("queda un proceso vivo con " + a68);
            Registrar("main (2b) eco con la carpeta '" + carpetas[i] + "'", d, r);
        }

        // (3) Solo advertencias (e4): se invoca el eco
        {
            Path dir = Files.createDirectories(trabajo.resolve("advertencias"));
            Escribir(dir.resolve("e4.mingol"), PROGRAMAS.get("e4"), CON_SALTO_FINAL.get("e4"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e4.mingol"), dir, "main-advertencias", Entorno(eco, system32));
            List<String> d = new ArrayList<>();
            // Área 5 (E3): el código es el del eco java.exe (1)
            ChequearSalida(r, 1, d, false);
            ChequearEco(r, dir.resolve("e4.a68").toAbsolutePath(), d);
            Registrar("main (3) solo advertencias (e4) invoca el eco", d, r);
        }

        // (4) Con errores (e5): no se invoca nada
        {
            Path dir = Files.createDirectories(trabajo.resolve("errores"));
            Escribir(dir.resolve("e5.mingol"), PROGRAMAS.get("e5"), CON_SALTO_FINAL.get("e5"));
            ResultadoHijo r = LanzarMain(ConClases(clases, "e5.mingol"), dir, "main-errores", Entorno(eco, system32));
            List<String> d = new ArrayList<>();
            // Área 5 (E3): con errores, MINGOL sale con 1
            ChequearSalida(r, 1, d, true);
            // Marca del eco independiente del idioma
            if (r.stderr.contains(ECO_MARCA)) d.add("se invocó el eco con un programa con errores");
            // Área 5 (E6, REQ-EXEC-01): con errores no queda .a68
            if (Files.exists(dir.resolve("e5.a68"))) d.add("existe e5.a68 con un programa con errores");
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

    // Área 5 (E1, E13): -errores.txt = copia numerada y, al final, solo el 115 con el nombre del archivo
    private static void ChequearErrores115(Path errores, String[] lineas, String nombre, List<String> d) throws IOException {
        if (!Files.exists(errores)) {
            d.add("no existe " + errores.getFileName());
            return;
        }
        List<String> esperado = new ArrayList<>();
        for (int i = 0; i < lineas.length; i++) esperado.add(String.format("%05d", i + 1) + " " + lineas[i]);
        esperado.add("        ERROR 115: Nombre de archivo inválido [" + nombre + "].");
        // TestProyecto escribe en la codificación nativa (Cp1252): se lee con ISO-8859-1
        List<String> real = Files.readAllLines(errores, StandardCharsets.ISO_8859_1);
        if (!real.equals(esperado)) d.add(errores.getFileName() + " distinto: " + real + " (se esperaba " + esperado + ")");
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

    // -errores.txt que produce TestProyecto para el mismo fuente (hijo hijopres, por reflexión)
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

    // Une las líneas con \n; sin \n final si así lo indica el programa
    private static void Escribir(Path archivo, String[] lineas, boolean saltoFinal) throws IOException {
        String texto = String.join("\n", lineas) + (saltoFinal ? "\n" : "");
        Files.write(archivo, texto.getBytes(StandardCharsets.US_ASCII));
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
