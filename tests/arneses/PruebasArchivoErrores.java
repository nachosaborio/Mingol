// Arnés de la spec mingol-archivo-errores-fix (área 1: archivo de errores).
// Clase sin paquete, como RunTestProyecto y PruebasCriticos. Se compila contra build\classes y se
// ejecuta desde una carpeta temporal ($env:TEMP\mingol-errores): todos los archivos se crean ahí.
//
// Modos:
//   ejemplos      -> tarea 1: Property 1 (Bug Condition) sobre t1..t13, c1, c6 y e8, más el borde
//                    de la causa 4 (-ea con el AST parcial de t7 y e8) y la revisión de las causas 1..8.
//   hijo <id> <argumento> [evaluar] -> interno: un caso aislado (TestProyecto por reflexión, nunca main).
//   hijopres <archivo> <argumento> -> interno de propiedades y main (un caso en su carpeta; DL1).
//   Pendiente 8: se retiraron capturar, preservacion y hijoreglas (su captura golden ya no existe y
//                    comparaban el comportamiento del código sin arreglar).
//   unitarias     -> tarea 4.8 (en proceso): Mensaje, ArchivoErrores, NombrePrograma/RutaA68/MismoArchivo,
//                    líneas de tokens del Lexer, GetMensajes() de e8 y el Evaluator.
//   propiedades   -> tarea 4.8: semillas 1..200 en hijos; formato y orden (Property 1), independencia
//                    (decisión 4) y valor inicial (2.16, 3.11).
//   main          -> tarea 4.8: Mingol.main en hijos con entorno controlado (A68G = eco java.exe,
//                    PATH = C:\Windows\System32): t4, e4 y T10 invocan el eco; t3 y t7 no.
//                    Área 5 (E3): código 1 en los cinco (el eco java.exe sale con 1, t3/t7 por errores).
//   Área 5: ejemplos suma t14 (\r\n sin salto final); el .a68 va en la carpeta actual, es copia byte a
//                    byte (Files.mismatch) y no existe si hay errores (E5, E6, E10).
//
// Seguridad (R8): cada TestProyecto y cada main corre en un hijo con -Xmx256m, waitFor(10 s) y
// destroyForcibly. main solo se lanza en el modo main, si existe Mingol.EjecutarA68g, con el entorno
// vaciado. Ningún .bat/.cmd se crea ni se ejecuta.
import Errores.ArchivoErrores; // Unitarias de la tarea 4.8
import Errores.Mensaje; // Unitarias de la tarea 4.8
import Parser.ExpressionStatement; // Nodos para las unitarias del Evaluator
import Parser.Identifier;
import Parser.Infix;
import Parser.Integral;
import Parser.LetStatement;
import Parser.Prefix;
import java.nio.charset.Charset; // Salida de main en la codificación nativa
import java.util.Comparator; // Orden del oráculo de propiedades
import Evaluador.Environment;
import Evaluador.Errado;
import Evaluador.Evaluator;
import Evaluador.Objeto;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PruebasArchivoErrores {

    // Límite de tiempo por hijo (R8)
    private static final long LIMITE_SEGUNDOS = 10;

    // Resultados del modo actual: OK/FALLA por caso
    private static final List<String> resumen = new ArrayList<>();
    private static int fallas = 0;

    // Sangría exacta de los mensajes (decisión del usuario sobre 2.2)
    private static final String S8 = "        ";

    // Formato de una línea de mensaje (2.3 y 2.11)
    private static final Pattern FORMATO_MENSAJE = Pattern.compile(
            "^ {8}(ERROR \\d{3}: .+ \\[.*\\]\\.|Advertencia: instrucción no es soportada por esta versión \\[.+\\]\\.)$");

    // Área 3 (sintaxis corregida, REQ-ALGOL-02): "x = 3;" y "y = 3;" ya no dan la cascada 204/203
    // común, sino un solo 205 con el primer token del comando; por eso X_IGUAL_3 dejó de ser común
    private static String E205(String lexema) {
        return "ERROR 205: No es una instrucción válida de MINGOL ni de ALGOL [" + lexema + "].";
    }

    // Área 3 (REQ-LINE-04 corregido): la línea anterior a un cierre no lleva ";"
    private static String E206(String cierre) {
        return "ERROR 206: La línea anterior a " + cierre + " no lleva punto y coma [;].";
    }

    // ------------------------------------------------------------------
    // Casos de la sección Examples del diseño
    // ------------------------------------------------------------------

    // Un caso: fuente, ruta en disco, argumento de TestProyecto y salida esperada
    private static class Caso {
        String id;
        String archivo;        // ruta relativa a la carpeta actual
        String argumento;      // lo que recibiría main (se normaliza como main)
        String[] lineas;
        boolean saltoFinal;
        boolean crlf;          // Área 5 (E10): líneas separadas con \r\n en lugar de \n (t14)
        String nombreErrores;  // <archivo sin carpetas ni extensión>-errores.txt, en la carpeta actual
        String rutaA68;        // donde la deja RutaA68
        boolean generado;      // true si sale del generador; sin lectura desde el pendiente 8 (la usaba la captura)
        Map<Integer, List<String>> bajoLinea = new LinkedHashMap<>();
        List<String> finales = new ArrayList<>();

        Caso bajo(int linea, String... mensajes) {
            bajoLinea.computeIfAbsent(linea, k -> new ArrayList<>()).addAll(Arrays.asList(mensajes));
            return this;
        }

        Caso fin(String... mensajes) {
            finales.addAll(Arrays.asList(mensajes));
            return this;
        }

        // Validez esperada: ausencia de ERROR (2.6, 3.6)
        boolean validoEsperado() {
            for (List<String> l : bajoLinea.values()) for (String m : l) if (m.startsWith("ERROR")) return false;
            for (String m : finales) if (m.startsWith("ERROR")) return false;
            return true;
        }

        byte[] bytes() {
            // Área 5 (E10): con crlf el separador y el salto final son \r\n
            String sep = crlf ? "\r\n" : "\n";
            return (String.join(sep, lineas) + (saltoFinal ? sep : "")).getBytes(StandardCharsets.US_ASCII);
        }

        // Archivo esperado completo: copia numerada con un espacio y los mensajes con 8 espacios
        List<String> esperado() {
            List<String> r = new ArrayList<>();
            for (int i = 1; i <= lineas.length; i++) {
                r.add(String.format("%05d", i) + " " + lineas[i - 1]);
                for (String m : bajoLinea.getOrDefault(i, new ArrayList<>())) r.add(S8 + m);
            }
            for (String m : finales) r.add(S8 + m);
            return r;
        }
    }

    private static Caso Nuevo(String id, String archivo, String argumento, String nombreErrores, String rutaA68,
                              boolean saltoFinal, String... lineas) {
        Caso c = new Caso();
        c.id = id;
        c.archivo = archivo;
        c.argumento = argumento;
        c.nombreErrores = nombreErrores;
        c.rutaA68 = rutaA68;
        c.saltoFinal = saltoFinal;
        c.lineas = lineas;
        return c;
    }

    // Caso simple: <id>.mingol en la carpeta actual
    private static Caso Simple(String id, String... lineas) {
        return Nuevo(id, id + ".mingol", id + ".mingol", id + "-errores.txt", id + ".a68", true, lineas);
    }

    // t1..t13 (no hay t8 en bugfix.md ni en el diseño), c1, c6 y e8 con sus esperados (diseño, Examples; D1)
    private static List<Caso> Casos() {
        List<Caso> l = new ArrayList<>();
        // Área 3 (sintaxis corregida): fuentes y esperados de Integration Tests del diseño. Las
        // declaraciones van sin valor inicial (decisión 3), el último comando antes de END/FI va sin ";"
        // (REQ-LINE-04) y "x = 3;" da 205 (REQ-ALGOL-02)
        // Borde t1: fuente sin cambios; 210 en la 3, 205 en la 4 y 206 en la 6 (sintaxis corregida)
        l.add(Simple("t1", "", "BEGIN", "INT a := 1.5;", "x = 3;", "   ", "print ( (zz) );", "END", "")
                .bajo(3, T1_LINEA3)
                .bajo(4, E205("x"))
                .bajo(6, E206("END"), "ERROR 303: Identificador no encontrado [zz]."));
        // t2: declaraciones sin valor inicial y GOTO sin ";" antes de END (sintaxis corregida)
        l.add(Simple("t2", "BEGIN", "INT a;", "INT a;", "print ( (b) );", "GOTO nada", "END")
                .bajo(3, "ERROR 304: Identificador ya declarado [a].")
                .bajo(4, "ERROR 303: Identificador no encontrado [b].")
                .bajo(5, "ERROR 303: Identificador no encontrado [nada]."));
        // t3: declaración sin valor inicial y print sin ";" antes de END (sintaxis corregida)
        l.add(Simple("t3", "BEGIN", "INT a;", "LONG x;", "print ( (b) )", "END")
                .bajo(3, "Advertencia: instrucción no es soportada por esta versión [LONG].")
                .bajo(4, "ERROR 303: Identificador no encontrado [b]."));
        // t4: válido con la sintaxis corregida (invoca el eco en main)
        l.add(Simple("t4", "BEGIN", "INT a;", "print ( (a) )", "END"));
        // t5: con un comando para que el END no dé 217 (sintaxis corregida)
        l.add(Simple("t5", "BEGIN", "BEGIN", "INT a;", "print ( (a) )", "END", "END")
                .bajo(2, "ERROR 305: No puede haber más de una sentencia BEGIN por archivo [BEGIN].")
                .bajo(6, "ERROR 306: No puede haber más de una sentencia END por archivo [END]."));
        // t6: sin BEGIN los comandos se analizan normalmente (DS3): solo 307 y 308
        l.add(Simple("t6", "INT a;", "print ( (a) );")
                .fin("ERROR 307: No se encontró una sentencia BEGIN [BEGIN].",
                     "ERROR 308: No se encontró una sentencia END [END]."));
        // t7: "x = 3;" da 205 y print sin ";" antes de END (sintaxis corregida)
        l.add(Simple("t7", "BEGIN", "INT a;", "x = 3;", "print ( (b) )", "END")
                .bajo(3, E205("x"))
                .bajo(4, "ERROR 303: Identificador no encontrado [b]."));
        // t9: lo que sigue al END da 208 y ya no se analiza (sintaxis corregida, REQ-STRUCT-05)
        l.add(Simple("t9", "BEGIN", "print ( newline )", "END", "INT a;", "INT a;", "print ( (b) );")
                .bajo(4, "ERROR 208: Comando después de END [INT].")
                .bajo(5, "ERROR 208: Comando después de END [INT].")
                .bajo(6, "ERROR 208: Comando después de END [print]."));
        // t10: extensión en mayúsculas; válido con la sintaxis corregida (asignación sin ";" antes de END)
        l.add(Nuevo("t10", "T10.MINGOL", "T10.MINGOL", "T10-errores.txt", "T10.a68", true,
                "BEGIN", "INT a;", "a := 1", "END"));
        // t11: carpeta con punto; el -errores.txt va en la carpeta actual
        // Área 5 (E5): el .a68 también va en la carpeta actual (antes sub.dir\t11.a68)
        l.add(Nuevo("t11", "sub.dir" + File.separator + "t11.mingol", "sub.dir" + File.separator + "t11.mingol",
                "t11-errores.txt", "t11.a68", true,
                "BEGIN", "x = 3;", "END")
                .bajo(2, E205("x")));
        // t12: invocado sin extensión (se normaliza como main a t12.mingol); "y = 3;" da 205 (sintaxis corregida)
        l.add(Nuevo("t12", "t12.mingol", "t12", "t12-errores.txt", "t12.a68", true,
                "BEGIN", "LONG x;", "y = 3;", "END")
                .bajo(2, "Advertencia: instrucción no es soportada por esta versión [LONG].")
                .bajo(3, E205("y")));
        // t13 (DS2, sintaxis corregida): con valor inicial da 210, y queda declarada y el valor no se evalúa
        l.add(Simple("t13", "BEGIN", "CHAR y := zz;", "print ( (y) )", "END")
                .bajo(2, "ERROR 210: Declaración con valor inicial [:=]."));
        // c1: la etiqueta usada como identificador ahora en la condición de un IF (sintaxis corregida,
        // decisión 3: ya no hay valor inicial); sigue cubriendo el ClassCastException de críticos
        l.add(Simple("c1", "BEGIN", "inicio:", "IF ( inicio ) THEN", "SKIP", "FI", "END")
                .bajo(3, "ERROR 303: Identificador no encontrado [inicio]."));
        // c6: "end" en la condición de un IF en lugar del valor inicial (sintaxis corregida)
        l.add(Simple("c6", "BEGIN", "IF ( end ) THEN", "SKIP", "FI", "END")
                .bajo(2, "ERROR 303: Identificador no encontrado [end]."));
        // e8: "SKIP;" ya no consume el END (sintaxis corregida): 206 en la 4, 204 de FI con END en la 5, sin 308
        l.add(Simple("e8", "BEGIN", "INT x;", "IF (x) THEN", "SKIP;", "END")
                .bajo(4, E206("END"))
                .bajo(5, "ERROR 204: Se esperaba un token de tipo FI pero se obtuvo END [END]."));
        return l;
    }

    // Área 5 (E10): t14 válido con \r\n y sin salto final; el .a68 debe ser copia byte a byte
    private static Caso T14() {
        Caso c = Nuevo("t14", "t14.mingol", "t14.mingol", "t14-errores.txt", "t14.a68", false,
                "BEGIN", "INT a;", "print ( (a) )", "END");
        c.crlf = true;
        return c;
    }

    // t1, línea 3 ("INT a := 1.5;"): área 3 (sintaxis corregida, decisión 3): un solo 210 en lugar del 201 + 203
    private static final String[] T1_LINEA3 = {
        "ERROR 210: Declaración con valor inicial [:=]."
    };

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // Modos públicos (los internos no se listan)
            // Pendiente 8: sin capturar ni preservacion
            System.err.println("Uso: PruebasArchivoErrores <ejemplos|unitarias|propiedades|main>");
            System.exit(2);
        }
        switch (args[0]) {
            case "ejemplos":
                Ejemplos();
                break;
            case "hijo":
                // Interno: un caso aislado con límite de tiempo
                Hijo(args.length > 1 ? args[1] : "", args.length > 2 ? args[2] : "",
                        args.length > 3 && args[3].equals("evaluar"));
                break;
            // Pendiente 8: capturar, preservacion e hijoreglas caen en default (Modo desconocido)
            case "hijopres":
                // Interno de propiedades y main (DL1): un caso en la carpeta actual
                HijoPres(args.length > 2 ? args[2] : "");
                break;
            case "unitarias":
                // Tarea 4.8: pruebas unitarias en proceso
                Unitarias();
                break;
            case "propiedades":
                // Tarea 4.8: propiedades con el generador (semillas 1..200), en hijos
                Propiedades();
                break;
            case "main":
                // Tarea 4.8: Mingol.main en hijos con entorno controlado
                ModoMain();
                break;
            default:
                System.err.println("Modo desconocido: " + args[0]);
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------
    // Modo ejemplos (tarea 1)
    // ------------------------------------------------------------------

    private static void Ejemplos() throws Exception {
        Path carpeta = Paths.get("").toAbsolutePath();
        System.out.println("Carpeta de trabajo: " + carpeta);
        // El arnés nunca trabaja dentro del repositorio (R5)
        if (Files.exists(carpeta.resolve("build.xml")) || Files.exists(carpeta.resolve("proyecto.mingol"))) {
            System.err.println("Se evita ejecutar en el repositorio: usar $env:TEMP\\mingol-errores");
            System.exit(2);
        }
        List<Caso> casos = Casos();
        // Área 5 (E10): t14 solo en ejemplos
        casos.add(T14());
        // Escribe todos los fuentes antes de lanzar hijos (causa 7 usa c6 y t4)
        for (Caso c : casos) {
            Path p = carpeta.resolve(c.archivo);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, c.bytes());
        }

        Map<String, ResultadoHijo> resultados = new LinkedHashMap<>();
        for (Caso c : casos) {
            resultados.put(c.id, Ejecutar(carpeta, c));
        }

        // Borde de la causa 4: Evaluator.Evaluate sobre el AST parcial, en un hijo con -ea
        Map<String, String> bordes = new LinkedHashMap<>();
        for (String id : new String[]{"t7", "e8"}) {
            bordes.put(id, BordeEa(carpeta, id));
        }

        // Revisión de las causas 1..8 del diseño
        Causas(carpeta, resultados, bordes);

        System.out.println();
        System.out.println("===== RESUMEN ejemplos =====");
        for (String r : resumen) System.out.println(r);
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }

    // Ejecuta un caso en un hijo y compara el -errores.txt con el esperado
    private static ResultadoHijo Ejecutar(Path carpeta, Caso c) throws Exception {
        Path fuente = carpeta.resolve(c.archivo);
        Path errores = carpeta.resolve(c.nombreErrores);
        Path a68 = carpeta.resolve(c.rutaA68);
        // Salidas previas (incluido el nombre viejo de t11, sub-errores.txt)
        Files.deleteIfExists(errores);
        Files.deleteIfExists(a68);
        if (c.id.equals("t11")) Files.deleteIfExists(carpeta.resolve("sub-errores.txt"));
        byte[] copia = Files.readAllBytes(fuente);

        ResultadoHijo r = LanzarHijo(Arrays.asList("hijo", c.id, c.argumento), carpeta, c.id, false);
        List<String> dif = new ArrayList<>();
        if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        String exc = LineaConPrefijo(r.stdout, "EXCEPCION|");
        if (exc != null) dif.add("excepción no controlada: " + exc);

        // (a) fuente intacto byte a byte
        boolean fuenteIntacto = Files.exists(fuente) && Arrays.equals(copia, Files.readAllBytes(fuente));
        if (!fuenteIntacto) dif.add("el fuente cambió (" + (Files.exists(fuente) ? Files.size(fuente) : -1) + " bytes)");

        // Área 5 (E5, E6, E10): .a68 en la carpeta actual; si es válido, copia byte a byte del fuente
        // (Files.mismatch); con errores NO debe existir. Se borró la comparación línea por línea
        boolean a68Ok = false;
        if (c.validoEsperado()) {
            if (!Files.exists(a68)) {
                dif.add("no existe " + c.rutaA68);
            } else {
                a68Ok = Files.exists(fuente) && Files.mismatch(fuente, a68) == -1L;
                if (!a68Ok) dif.add(c.rutaA68 + " no es copia byte a byte del fuente");
            }
        } else {
            a68Ok = !Files.exists(a68);
            if (!a68Ok) dif.add("existe " + c.rutaA68 + " con un programa con errores");
        }

        // (h) validez = ausencia de ERROR
        String tp = LineaConPrefijo(r.stdout, "TestProyecto=");
        String tpEsperado = "TestProyecto=" + c.validoEsperado();
        if (!tpEsperado.equals(tp)) dif.add("se esperaba " + tpEsperado + "; obtenido " + tp);
        // Consistencia: TestProyecto = NOT (parser.hasErrors OR evaluator.hasErrors)
        String he = LineaConPrefijo(r.stdout, "HASERRORS|");
        if (tp != null && he != null) {
            boolean p = he.contains("parser=true"), e = he.contains("evaluator=true");
            if (tp.equals("TestProyecto=" + (p || e))) dif.add("TestProyecto inconsistente con " + he);
        }

        // (b) nombre del archivo de errores en la carpeta actual
        boolean soloSeparador = false;
        if (!Files.exists(errores)) {
            String[] hay = carpeta.toFile().list((d, n) -> n.endsWith("-errores.txt"));
            dif.add("no existe " + c.nombreErrores + " (en la carpeta hay: " + Arrays.toString(hay) + ")");
        } else {
            List<String> real = Files.readAllLines(errores, StandardCharsets.ISO_8859_1);
            List<String> esperado = c.esperado();
            ValidarFormato(c, real, dif);
            if (!real.equals(esperado)) {
                dif.add("contenido distinto del esperado: " + PrimeraDiferencia(esperado, real));
                // ¿Coincide si se normaliza el tabulador que sigue al número? (t10)
                List<String> normal = new ArrayList<>();
                for (String l : real) normal.add(l.matches("^\\d{5}\t.*") ? l.replaceFirst("\t", " ") : l);
                soloSeparador = normal.equals(esperado);
                if (soloSeparador) dif.add("(normalizando el tabulador tras el número coincide: solo falla el separador)");
            }
        }

        // t10: debe fallar SOLO por el separador; si no, regresión de críticos
        if (c.id.equals("t10")) {
            // Fuente alterado, sin T10.a68 o sin copia exacta: lo había resuelto críticos
            // Área 5: a68Ok ya usa la nueva condición (Files.mismatch, solo si es válido)
            if (!fuenteIntacto || !a68Ok || !Files.exists(errores)) {
                System.out.println("AVISO: t10 con el fuente alterado o sin T10.a68/T10-errores.txt (regresión de mingol-criticos-fix)");
            } else if (!soloSeparador && !dif.isEmpty()) {
                // Solo se avisa si t10 falla: con el arreglo t10 da OK y el aviso confundía
                System.out.println("AVISO: t10 no falla solo por el separador (revisar)");
            }
        }

        Registrar(c.id, dif, r);
        // Huella del hijo para documentar contraejemplos
        for (String l : r.stdout.split("\\R")) {
            if (l.startsWith("GETERRORS|") || l.startsWith("TestProyecto=") || l.startsWith("HASERRORS|")
                    || l.startsWith("INSPECT") || l.startsWith("MENSAJE") || l.startsWith("ENV_Y|")) {
                System.out.println("      | " + l);
            }
        }
        if (Files.exists(errores)) {
            System.out.println("      | archivo " + c.nombreErrores + ":");
            for (String l : Files.readAllLines(errores, StandardCharsets.ISO_8859_1)) {
                System.out.println("      |   " + l.replace("\t", "<T>"));
            }
        }
        return r;
    }

    // Formato, ubicación y orden (Property 1 c, d, e) independientes del esperado exacto
    private static void ValidarFormato(Caso c, List<String> real, List<String> dif) {
        int n = c.lineas.length;
        int linea = 0;
        List<String> errores = new ArrayList<>();
        int ultimaCategoria = -1;
        boolean vistoFinal = false;
        for (int i = 0; i < real.size(); i++) {
            String l = real.get(i);
            String numerada = (linea < n) ? String.format("%05d", linea + 1) + " " + c.lineas[linea] : null;
            if (numerada != null && l.equals(numerada)) {
                linea++;
                ultimaCategoria = -1;
                continue;
            }
            if (!FORMATO_MENSAJE.matcher(l).matches()) {
                errores.add("línea " + (i + 1) + " del archivo sin formato válido: [" + l.replace("\t", "<T>") + "]");
                continue;
            }
            String t = l.trim();
            boolean es307o308 = t.startsWith("ERROR 307") || t.startsWith("ERROR 308");
            // El bloque final (307/308) empieza su propio orden y va al final, sin nada después
            if (es307o308 && !vistoFinal) {
                vistoFinal = true;
                ultimaCategoria = -1;
            }
            if (vistoFinal && !es307o308) errores.add("después de 307/308 hay [" + t + "]");
            if (es307o308 && linea != n) errores.add("307/308 antes de la última línea: [" + t + "]");
            int cat = Categoria(l);
            if (cat < ultimaCategoria) errores.add("categorías desordenadas en [" + t + "]");
            ultimaCategoria = cat;
        }
        if (linea != n) errores.add("copia numerada incompleta o con otro separador: " + linea + " de " + n + " líneas");
        if (!errores.isEmpty()) {
            dif.add("formato: " + errores.get(0) + (errores.size() > 1 ? " (y " + (errores.size() - 1) + " más)" : ""));
        }
    }

    // Orden por categoría: léxico 1xx, sintáctico 2xx, semántico 3xx, advertencia
    private static int Categoria(String l) {
        String t = l.trim();
        if (t.startsWith("Advertencia")) return 4;
        return t.charAt(6) - '0';
    }

    // Borde de la causa 4: hijo con -ea que fuerza Evaluator.Evaluate sobre el AST parcial
    private static String BordeEa(Path carpeta, String id) throws Exception {
        ResultadoHijo r = LanzarHijo(Arrays.asList("hijo", id, id + ".mingol", "evaluar"), carpeta, id + "-ea", true);
        String eval = LineaConPrefijo(r.stdout, "EVAL|");
        List<String> dif = new ArrayList<>();
        if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s");
        if (eval == null) {
            dif.add("el hijo no informó el resultado de Evaluate: " + PrimeraLineaCon(r.todo(), "Exception"));
        } else if (eval.startsWith("EVAL|EXC|")) {
            // Detectado por el nombre de clase de la excepción, no por el texto
            dif.add("Evaluate con -ea sobre el AST parcial lanza " + eval.substring("EVAL|EXC|".length()));
        }
        Registrar("borde causa 4 (-ea, AST parcial de " + id + ")", dif, r);
        return eval == null ? "sin resultado" : eval;
    }

    // Revisión de las causas 1..8 (confirmada / refutada / no observable antes del arreglo)
    private static void Causas(Path carpeta, Map<String, ResultadoHijo> res, Map<String, String> bordes) throws Exception {
        System.out.println();
        System.out.println("===== CAUSAS del diseño =====");
        // 1: mensajes como String sin estructura (no hay GetMensajes en el parser)
        boolean hayMensajes = Existe("Parser.Parser", "GetMensajes");
        Causa(1, !hayMensajes, "Parser.GetMensajes() " + (hayMensajes ? "existe" : "no existe")
                + "; GetErrors() de t3 mezcla advertencia y errores: " + LineasConPrefijo(res.get("t3").stdout, "GETERRORS|"));
        // 2: los nodos no saben su línea
        boolean tokenLinea = Existe("Lexer.Token", "getLine");
        boolean stmtLinea = Existe("Parser.Statement", "getLine");
        Causa(2, !tokenLinea && !stmtLinea, "Token.getLine " + tokenLinea + ", Statement.getLine " + stmtLinea);
        // 3: el evaluador corta en el primer Errado y 308 falso (t2, t5, t9)
        String t2 = Contenido(carpeta, "t2-errores.txt"), t5 = Contenido(carpeta, "t5-errores.txt"),
                t9 = Contenido(carpeta, "t9-errores.txt");
        boolean c3 = t2.contains("Error 308") && !t2.contains("Error 304") && t5.contains("Error 308")
                && t9.contains("Error 304") && !t9.contains("Error 303");
        Causa(3, c3, "t2 con 308 sin 304: " + (t2.contains("Error 308") && !t2.contains("Error 304"))
                + "; t5 con 308: " + t5.contains("Error 308") + "; t9 con 304 y sin 303: " + (t9.contains("Error 304") && !t9.contains("Error 303")));
        // 4: el evaluador no tolera el AST parcial (con -ea)
        boolean c4 = bordes.values().stream().anyMatch(b -> b.startsWith("EVAL|EXC|"));
        Causa(4, c4, "t7: " + bordes.get("t7") + "; e8: " + bordes.get("e8"));
        // 5: cascadas al seguir evaluando: no se puede observar antes del arreglo (el evaluador no sigue)
        // Con todos los casos en OK la causa 5 ya está resuelta (el evaluador sigue sin cascadas)
        System.out.println(fallas == 0
                ? "CAUSA 5: resuelta (todos los casos dan OK: el evaluador sigue sin cascadas)"
                : "CAUSA 5: no observable antes del arreglo (EvaluateProgram corta en el primer Errado)");
        // 6: nombre con split sobre la ruta (t11)
        boolean c6 = Files.exists(carpeta.resolve("sub-errores.txt")) && !Files.exists(carpeta.resolve("t11-errores.txt"));
        Causa(6, c6, "sub-errores.txt existe: " + Files.exists(carpeta.resolve("sub-errores.txt"))
                + "; t11-errores.txt existe: " + Files.exists(carpeta.resolve("t11-errores.txt")));
        // 7: hasErrors estático sin reinicio (en un hijo: c6 con error y luego t4 válido)
        ResultadoHijo r7 = LanzarHijo(Arrays.asList("hijo", "causa7", ""), carpeta, "causa7", false);
        String h7 = LineaConPrefijo(r7.stdout, "CAUSA7|");
        Causa(7, h7 != null && h7.contains("despues_t4=true") && h7.contains("reiniciar=false"), String.valueOf(h7));
        // 8: LetStatement guarda el Errado del valor inicial y lo descarta (c1, c6, t13)
        String y1 = LineaConPrefijo(res.get("c1").stdout, "ENV_Y|");
        String y6 = LineaConPrefijo(res.get("c6").stdout, "ENV_Y|");
        boolean c8 = y1 != null && y1.startsWith("ENV_Y|Errado|") && y6 != null && y6.startsWith("ENV_Y|Errado|")
                && !Contenido(carpeta, "c6-errores.txt").contains("303");
        Causa(8, c8, "c1 " + y1 + "; c6 " + y6 + "; 303 en c6-errores.txt: " + Contenido(carpeta, "c6-errores.txt").contains("303"));
    }

    private static void Causa(int n, boolean confirmada, String evidencia) {
        // Si el bug ya no se reproduce y todos los casos dan OK, la causa está resuelta (informativo)
        String estado = confirmada ? "confirmada" : (fallas == 0 ? "resuelta" : "NO CONFIRMADA (revisar)");
        System.out.println("CAUSA " + n + ": " + estado + " - " + evidencia);
    }

    private static boolean Existe(String clase, String metodo) throws Exception {
        for (Method m : Class.forName(clase).getMethods()) if (m.getName().equals(metodo)) return true;
        return false;
    }

    private static String Contenido(Path carpeta, String nombre) throws IOException {
        Path p = carpeta.resolve(nombre);
        return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1) : "";
    }

    // ------------------------------------------------------------------
    // Tarea 2: generador de programas (diseño, Property-Based Tests)
    // ------------------------------------------------------------------

    // Hijos en paralelo para no exceder tiempos (cada uno sigue con su límite de 10 s, R8)
    private static final int HILOS = 4;
    // La huella completa de un caso cabe de sobra en 1 MB
    private static final int MAX_HUELLA = 1024 * 1024;

    // Nombres en minúsculas, no reservados; variables y etiquetas disjuntas
    private static final String[] VARS_G = {"a", "b", "c", "x", "y", "total"};
    private static final String[] ETIQ_G = {"inicio", "fin", "otra"};

    // Programa de la semilla s (determinista: la misma semilla da los mismos bytes).
    // La mitad de las semillas tiende a programas sin mensajes (repertorio "limpio"); la otra mitad
    // mezcla todo el repertorio del diseño. Pendiente 8: ya no lo usa la preservación, solo propiedades.
    private static Caso Generar(int semilla) {
        // Semilla mezclada: con new Random(s) y s consecutivos pequeños el primer nextBoolean() sale igual
        Random r = new Random(semilla * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
        String id = String.format("g%03d", semilla);
        boolean limpio = r.nextBoolean();
        List<String> l = new ArrayList<>();
        List<String> declaradas = new ArrayList<>();
        List<String> etiquetas = new ArrayList<>();

        // Antes de BEGIN: líneas vacías o con espacios
        int previas = r.nextInt(3);
        for (int i = 0; i < previas; i++) l.add(Blanca(r));
        // BEGIN presente (lo normal), ausente o repetido
        int begins = limpio ? 1 : CuantasMarcas(r);
        for (int i = 0; i < begins; i++) l.add(Sangria(r) + "BEGIN");

        // Área 3 (sintaxis corregida): en los limpios las declaraciones (sin valor inicial) van primero,
        // para que no haya 209 (REQ-STRUCT-03)
        if (limpio) {
            int decl = r.nextInt(4);
            for (int i = 0; i < decl; i++) {
                String v = VARS_G[i];
                declaradas.add(v);
                l.add(Sangria(r) + "INT " + v + ";");
            }
        }
        int lineas = 1 + r.nextInt(15);
        for (int i = 0; i < lineas; i++) {
            if (limpio) LineaLimpia(r, l, declaradas, etiquetas);
            else LineaCualquiera(r, l, etiquetas);
        }
        // Área 3 (REQ-LINE-04 y 217): SKIP final sin ";" antes de END, así los limpios no tienen 206 ni 217
        if (limpio) l.add(Sangria(r) + "SKIP");

        int ends = limpio ? 1 : CuantasMarcas(r);
        for (int i = 0; i < ends; i++) l.add(Sangria(r) + "END");
        int finales = r.nextInt(3);
        for (int i = 0; i < finales; i++) l.add(Blanca(r));

        boolean saltoFinal = r.nextBoolean();
        Caso c = Nuevo(id, id + ".mingol", id + ".mingol", id + "-errores.txt", id + ".a68", saltoFinal,
                l.toArray(new String[0]));
        c.generado = true;
        return c;
    }

    // 1 casi siempre; a veces 0 (ausente) o 2 (repetido)
    private static int CuantasMarcas(Random r) {
        int k = r.nextInt(10);
        return k == 0 ? 0 : (k == 1 ? 2 : 1);
    }

    private static String ElegirG(Random r, String[] opciones) {
        return opciones[r.nextInt(opciones.length)];
    }

    // Blancos redundantes al inicio (REQ-LINE-06)
    private static String Sangria(Random r) {
        return r.nextInt(4) == 0 ? "  " : "";
    }

    // Línea vacía o con solo espacios (se copian tal cual, 3.2)
    private static String Blanca(Random r) {
        return r.nextBoolean() ? "" : "   ";
    }

    // Repertorio que hoy no produce mensajes: variables nuevas, print de declaradas, etiqueta antes
    // de su GOTO, IF con FI y cuerpo de una línea, líneas vacías y con espacios
    private static void LineaLimpia(Random r, List<String> l, List<String> declaradas, List<String> etiquetas) {
        switch (r.nextInt(7)) {
            case 0:
            case 1:
                // Área 3 (sintaxis corregida): las declaraciones ya se generaron antes de los comandos
                // (sin 209); en su lugar, un SKIP con ";" seguido de otro comando
                l.add(Sangria(r) + "SKIP;");
                return;
            case 2:
                if (declaradas.isEmpty()) { l.add(Blanca(r)); return; }
                l.add(Sangria(r) + "print ( (" + declaradas.get(r.nextInt(declaradas.size())) + ") );");
                return;
            case 3: {
                // Etiqueta nueva y, a veces, su GOTO después
                String e = null;
                for (String cand : ETIQ_G) if (!etiquetas.contains(cand)) { e = cand; break; }
                if (e == null) { l.add("GOTO " + etiquetas.get(r.nextInt(etiquetas.size())) + ";"); return; }
                etiquetas.add(e);
                l.add(e + ":");
                if (r.nextBoolean()) l.add(Sangria(r) + "GOTO " + e + ";");
                return;
            }
            case 4:
                if (declaradas.isEmpty()) { l.add(Blanca(r)); return; }
                String v = declaradas.get(r.nextInt(declaradas.size()));
                l.add(Sangria(r) + "IF ( " + v + " ) THEN");
                // Área 3 (REQ-LINE-04): la línea anterior a FI no lleva ";"
                l.add("  print ( (" + v + ") )");
                l.add("FI;");
                return;
            default:
                l.add(Blanca(r));
        }
    }

    // Repertorio completo del diseño: declaraciones (también repetidas), CHAR y := zz;, print de
    // variables definidas o no, GOTO, etiquetas y su uso como identificador, LONG x;, x = 3;,
    // INT a := 1.5;, @, constante fuera de rango, IF con y sin FI, líneas vacías y con espacios
    private static void LineaCualquiera(Random r, List<String> l, List<String> etiquetas) {
        int caso = r.nextInt(16);
        switch (caso) {
            case 0:
            case 1:
                l.add(Sangria(r) + "INT " + ElegirG(r, VARS_G) + " := " + r.nextInt(1000) + ";");
                return;
            case 2:
                l.add("CHAR y := zz;");
                return;
            case 3:
            case 4:
                l.add(Sangria(r) + "print ( (" + (r.nextInt(4) == 0 ? "zz" : ElegirG(r, VARS_G)) + ") );");
                return;
            case 5:
                l.add("GOTO " + ElegirG(r, ETIQ_G) + ";");
                return;
            case 6: {
                String e = ElegirG(r, ETIQ_G);
                etiquetas.add(e);
                l.add(e + ":");
                return;
            }
            case 7: {
                // Etiqueta usada como identificador (en print o como valor inicial)
                String e = etiquetas.isEmpty() ? ElegirG(r, ETIQ_G) : etiquetas.get(r.nextInt(etiquetas.size()));
                l.add(r.nextBoolean() ? "print ( (" + e + ") );" : "CHAR w := " + e + ";");
                return;
            }
            case 8:
                l.add("LONG x;");
                return;
            case 9:
                l.add("x = 3;");
                return;
            case 10:
                l.add("INT a := 1.5;");
                return;
            case 11:
                l.add("@");
                return;
            case 12:
                // Fuera de rango: 12345678901 y 99999 dan solo 108 (comportamiento léxico corregido, 3.10)
                l.add("INT " + ElegirG(r, VARS_G) + " := " + (r.nextBoolean() ? "12345678901" : "99999") + ";");
                return;
            case 13:
            case 14: {
                // IF con FI (case 13) o sin FI (case 14), con ELSE opcional
                boolean sinFi = (caso == 14);
                String v = ElegirG(r, VARS_G);
                l.add("IF ( " + v + " ) THEN");
                l.add("  print ( (" + v + ") );");
                if (r.nextBoolean()) {
                    l.add("ELSE");
                    l.add("  print ( \"no\" );");
                }
                // case 13 cierra con FI; case 14 lo omite (IF sin FI)
                if (!sinFi) l.add("FI;");
                return;
            }
            default:
                l.add(Blanca(r));
        }
    }

    // ------------------------------------------------------------------
    // Correspondencia GetErrors() <-> GetMensajes() (unitarias) y utilidades de la huella
    // (Pendiente 8: sin la detección de la preservación)
    // ------------------------------------------------------------------

    private static final Pattern LINEA_CADENA = Pattern.compile("^\tLinea (\\d+)");
    private static final Pattern ERROR_CADENA = Pattern.compile("Error (\\d{3}): (.*)$");
    private static final Pattern ADVERTENCIA_CADENA = Pattern.compile("ADVERTENCIA: Instruccion (.*) no soportada por esta version$");
    private static final Pattern ESPERABA_CADENA = Pattern.compile("^Se esperaba un token de tipo (\\S+) pero se obtuvo un (\\S+)$");

    // Una cadena de GetErrors() corresponde a su Mensaje: línea, código (tabla 2.10), categoría y lexema/texto
    // Campos del Mensaje: [linea, codigo, categoria, lexema, texto]
    private static String Corresponde(String cadena, String[] m) {
        java.util.regex.Matcher ml = LINEA_CADENA.matcher(cadena);
        if (!ml.find()) return "cadena sin línea: [" + cadena + "]";
        // Línea igual, incluida la n+1 del final del archivo
        if (!ml.group(1).equals(m[0])) return "línea " + ml.group(1) + " en la cadena y " + m[0] + " en el Mensaje: [" + cadena + "]";
        java.util.regex.Matcher ma = ADVERTENCIA_CADENA.matcher(cadena);
        if (ma.find()) {
            // ADVERTENCIA <-> categoría ADVERTENCIA, sin código, con el literal como lexema
            if (!"ADVERTENCIA".equals(m[2]) || !"null".equals(m[1])) return "advertencia con categoría " + m[2] + " y código " + m[1];
            if (!ma.group(1).equals(m[3])) return "advertencia con lexema [" + m[3] + "] y no [" + ma.group(1) + "]";
            return null;
        }
        java.util.regex.Matcher me = ERROR_CADENA.matcher(cadena);
        if (!me.find()) return "cadena sin código: [" + cadena + "]";
        String viejo = me.group(1), resto = me.group(2);
        // Traducción con la tabla 2.10
        String nuevo;
        String lexemaEsperado = null;
        // S1 (área 3, sintaxis corregida): "205" es el 101 solo con "Token ilegal:"; cualquier otro
        // "205" es el nuevo 205 de REQ-ALGOL-02 y cae en la rama nuevo = viejo
        if (viejo.equals("205") && resto.startsWith("Token ilegal:")) {
            nuevo = "101";
            lexemaEsperado = Lexema(resto.replaceFirst("^Token ilegal: ", ""));
        } else if (viejo.equals("203") && resto.startsWith("conversion invalida a ")) {
            nuevo = "102";
            lexemaEsperado = resto.replaceFirst("^conversion invalida a (entero|real): ", "");
        } else if (viejo.equals("203")) {
            nuevo = "203";
            lexemaEsperado = Lexema(resto.replaceFirst("^Token inválido: ", ""));
        } else {
            nuevo = viejo;
        }
        if (!nuevo.equals(m[1])) return "código " + viejo + " debería traducirse a " + nuevo + " y el Mensaje tiene " + m[1];
        String cat = nuevo.charAt(0) == '1' ? "LEXICO" : nuevo.charAt(0) == '2' ? "SINTACTICO" : "SEMANTICO";
        if (!cat.equals(m[2])) return "código " + nuevo + " con categoría " + m[2];
        if (lexemaEsperado != null && !lexemaEsperado.equals(m[3])) {
            return "código " + nuevo + " con lexema [" + m[3] + "] y no [" + lexemaEsperado + "]";
        }
        if (nuevo.equals("204")) {
            // ExpectedTokenError y ExpectedCurrentTokenError: mismo tipo esperado y obtenido
            java.util.regex.Matcher mt = ESPERABA_CADENA.matcher(resto);
            if (!mt.find()) return "204 con texto inesperado: [" + resto + "]";
            String texto = "Se esperaba un token de tipo " + mt.group(1) + " pero se obtuvo " + mt.group(2);
            if (!texto.equals(m[4])) return "204 con texto [" + m[4] + "] y no [" + texto + "]";
            if ((mt.group(2).equals("EOF") || mt.group(2).equals("EOL")) && !mt.group(2).equals(m[3])) {
                return "204 con lexema [" + m[3] + "] y no [" + mt.group(2) + "]";
            }
        } else if (nuevo.equals("201")) {
            String var = resto.replaceFirst(" no es un (INT|REAL|STRING)$", "");
            String tipo = resto.replaceFirst("^.* no es un ", "");
            if (m[4] == null || !m[4].contains("tipo " + tipo + " de la variable " + var)) return "201 con texto [" + m[4] + "]";
        } else if (nuevo.equals("202") && resto.endsWith(" debe ir solo en su línea")) {
            // Pendiente 2 (P1–P4): "<cierre> debe ir solo en su línea", con el cierre como lexema
            if (m[4] == null || !m[4].equals(resto)) return "202 con texto [" + m[4] + "] y no [" + resto + "]";
            String cierre = resto.substring(0, resto.indexOf(' '));
            if (!cierre.equals(m[3])) return "202 con lexema [" + m[3] + "] y no [" + cierre + "]";
        } else if (nuevo.equals("202")) {
            String token = resto.replaceFirst("^.*luego del token ", "");
            if (m[4] == null || !m[4].endsWith("después de " + token)) return "202 con texto [" + m[4] + "]";
        }
        return null;
    }

    // Lexema como lo deja Parser.Lexema: "\n" -> EOL, "" -> nombre del tipo (no vacío)
    private static String Lexema(String literal) {
        if (literal.equals("\n")) return "EOL";
        // Literal vacío: el lexema es el nombre del tipo, que la cadena no trae; no se compara
        if (literal.isEmpty()) return null;
        return literal;
    }

    private static String Valor(List<String> meta, String prefijo) {
        for (String l : meta) if (l.startsWith(prefijo)) return l.substring(prefijo.length());
        return null;
    }

    // Inverso de Escapar/EscaparCampo
    private static String Desescapar(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                sb.append(nx == 'n' ? '\n' : nx == 'r' ? '\r' : nx == 'p' ? '|' : nx);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // Un campo de la huella: además de Escapar, el separador '|' pasa a "\p"
    private static String EscaparCampo(String s) {
        return Escapar(s).replace("|", "\\p");
    }

    // Líneas del fuente como las lee TestProyecto (BufferedReader)
    private static List<String> LineasFuente(byte[] b) {
        List<String> lineas = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.StringReader(new String(b, StandardCharsets.ISO_8859_1)))) {
            String s;
            while ((s = br.readLine()) != null) lineas.add(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return lineas;
    }

    // ------------------------------------------------------------------
    // Ejecución en paralelo y utilidades de propiedades y main (Pendiente 8: sin la captura)
    // ------------------------------------------------------------------

    // Cada caso en su propia carpeta y su propio hijo (R8); HILOS hijos a la vez
    private static Map<String, ResultadoHijo> CorrerCasos(List<Caso> casos, Map<String, byte[]> fuentes,
                                                          Path trabajo, Path logs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        Map<String, Future<ResultadoHijo>> futuros = new LinkedHashMap<>();
        try {
            for (Caso c : casos) {
                Path dir = trabajo.resolve(c.id);
                Path f = dir.resolve(c.archivo);
                Files.createDirectories(f.getParent());
                Files.write(f, fuentes.get(c.id));
                futuros.put(c.id, pool.submit(() -> LanzarHijo(Arrays.asList("hijopres", c.archivo, c.argumento),
                        dir, logs, c.id, false, MAX_HUELLA)));
            }
            Map<String, ResultadoHijo> res = new LinkedHashMap<>();
            for (Map.Entry<String, Future<ResultadoHijo>> e : futuros.entrySet()) res.put(e.getKey(), e.getValue().get());
            return res;
        } finally {
            pool.shutdown();
        }
    }

    // No terminó, salió con código distinto de 0 o hubo una excepción en el compilador
    private static void ProblemasDeEjecucion(ResultadoHijo r, List<String> dif) {
        if (!r.termino) dif.add("no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
        else if (r.codigo != 0) dif.add("código de salida del hijo " + r.codigo);
        String exc = LineaConPrefijo(r.stdout, "EXCEPCION");
        if (exc != null) dif.add(exc);
        String err = PrimeraLineaCon(r.stderr, "Exception");
        if (err == null) err = PrimeraLineaCon(r.stderr, "Error");
        if (err != null) dif.add("stderr: " + err.trim());
    }

    // Líneas no vacías de la huella del hijo
    private static List<String> LineasMeta(String stdout) {
        List<String> meta = new ArrayList<>();
        for (String l : stdout.split("\\R")) if (!l.isEmpty()) meta.add(l);
        return meta;
    }

    // Archivos de una carpeta, recursivo, como rutas relativas ordenadas (t11 deja sub.dir\t11.a68)
    private static List<String> ArchivosRec(Path dir) throws IOException {
        if (!Files.exists(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().collect(Collectors.toList());
        }
    }

    // Carpeta actual, con la misma protección que el modo ejemplos (R5)
    private static Path CarpetaSegura() {
        Path carpeta = Paths.get("").toAbsolutePath();
        System.out.println("Carpeta de trabajo base: " + carpeta);
        if (Files.exists(carpeta.resolve("build.xml")) || Files.exists(carpeta.resolve("proyecto.mingol"))) {
            System.err.println("Se evita ejecutar en el repositorio: usar $env:TEMP\\mingol-errores");
            System.exit(2);
        }
        return carpeta;
    }

    // Solo las FALLA y el total (la salida completa queda en el log de la corrida)
    private static void ImprimirResumen(String modo) {
        System.out.println();
        System.out.println("===== RESUMEN " + modo + " =====");
        for (String r : resumen) if (r.startsWith("FALLA")) System.out.println(r);
        System.out.println("Total: " + resumen.size() + ", FALLA: " + fallas + ", OK: " + (resumen.size() - fallas));
        System.exit(fallas == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Modo hijopres: huella de un caso (TestProyecto por reflexión, nunca main)
    // ------------------------------------------------------------------

    private static void HijoPres(String argumento) throws Exception {
        // Salida en UTF-8 para que el padre la lea sin depender de la página de códigos
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        boolean huboExcepcion = false;
        String ruta = NormalizarComoMain(argumento);
        out.println("RUTA|" + Escapar(ruta));
        String fuente = LeerFuente(ruta);

        // Parser por separado: GetErrors() en orden (y GetMensajes() si existe, para la tarea 3)
        Parser parser = new Parser(new Lexer(fuente));
        parser.ParseProgram();
        for (String e : parser.GetErrors()) out.println("GETERRORS|" + Escapar(e));
        out.println("GETERRORS_TOTAL|" + parser.GetErrors().size());
        ImprimirMensajes(out, "parser", parser, parser.getClass());

        // TestProyecto: escribe el -errores.txt y el .a68 en la carpeta del caso
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            out.println("TESTPROYECTO|" + m.invoke(null, ruta));
        } catch (InvocationTargetException e) {
            huboExcepcion = true;
            out.println("EXCEPCION|" + e.getCause().getClass().getName() + "|" + Escapar(String.valueOf(e.getCause().getMessage())));
            e.getCause().printStackTrace();
        }
        // hasErrors justo después de TestProyecto (antes de volver a evaluar)
        out.println("HASERRORS|parser=" + parser.hasErrors + "|evaluator=" + Evaluator.hasErrors);
        ImprimirMensajes(out, "evaluador", null, Evaluator.class);

        // Inspect() del evaluador solo cuando el parser no tuvo mensajes
        if (parser.GetErrors().isEmpty()) {
            try {
                Environment env = new Environment();
                Objeto res = Evaluator.Evaluate(new Parser(new Lexer(fuente)).ParseProgram(), env);
                out.println("INSPECT|" + (res == null ? "null" : res.getClass().getSimpleName() + "|" + Escapar(res.Inspect())));
                // Tarea 3 (1.16): alguna declaración guardó un Errado como valor inicial (antes y después del arreglo)
                boolean letErrado = false;
                for (Object v : env.values()) if (v instanceof Errado) letErrado = true;
                out.println("LET_ERRADO|" + letErrado);
            } catch (Throwable t) {
                huboExcepcion = true;
                out.println("EXCEPCION_EVAL|" + t.getClass().getName());
            }
        } else {
            out.println("INSPECT_NO_CORRE");
        }
        out.flush();
        System.exit(huboExcepcion ? 3 : 0);
    }

    // ------------------------------------------------------------------
    // Modo hijo: un caso aislado; el padre controla tiempo y memoria
    // ------------------------------------------------------------------

    private static void Hijo(String id, String argumento, boolean evaluar) throws Exception {
        // Salida en UTF-8 para que el padre la lea sin depender de la página de códigos
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        if (id.equals("causa7")) {
            HijoCausa7(out);
            return;
        }
        String ruta = NormalizarComoMain(argumento);
        out.println("RUTA|" + ruta);
        String fuente = LeerFuente(ruta);

        if (evaluar) {
            // Borde de la causa 4: Evaluate directo sobre el AST parcial (el padre lanza este hijo con -ea)
            Program program = new Parser(new Lexer(fuente)).ParseProgram();
            try {
                Objeto res = Evaluator.Evaluate(program, new Environment());
                out.println("EVAL|OK|" + (res == null ? "null" : Escapar(res.Inspect())));
            } catch (Throwable t) {
                out.println("EVAL|EXC|" + t.getClass().getName());
            }
            out.flush();
            System.exit(0);
        }

        boolean huboExcepcion = false;
        // Parser por separado: GetErrors() en orden y GetMensajes() si existe (por reflexión)
        Parser parser = new Parser(new Lexer(fuente));
        parser.ParseProgram();
        for (String e : parser.GetErrors()) out.println("GETERRORS|" + Escapar(e));
        ImprimirMensajes(out, "parser", parser, parser.getClass());

        // TestProyecto por reflexión, igual que RunTestProyecto (sin a68g)
        Method m = Class.forName("Mingol.Mingol").getDeclaredMethod("TestProyecto", String.class);
        m.setAccessible(true);
        try {
            out.println("TestProyecto=" + m.invoke(null, ruta));
        } catch (InvocationTargetException e) {
            huboExcepcion = true;
            out.println("EXCEPCION|" + e.getCause().getClass().getName() + "|" + Escapar(String.valueOf(e.getCause().getMessage())));
            e.getCause().printStackTrace();
        }
        // hasErrors del parser (mismo fuente, determinista) y del evaluador estático tras TestProyecto
        out.println("HASERRORS|parser=" + parser.hasErrors + "|evaluator=" + Evaluator.hasErrors);
        ImprimirMensajes(out, "evaluador", null, Evaluator.class);

        // Inspect() del evaluador con parser sin mensajes; va después para no alterar hasErrors antes de tiempo
        if (parser.GetErrors().isEmpty()) {
            Environment env = new Environment();
            try {
                Program otro = new Parser(new Lexer(fuente)).ParseProgram();
                Objeto res = Evaluator.Evaluate(otro, env);
                out.println("INSPECT|" + (res == null ? "null" : res.getClass().getSimpleName() + "|" + Escapar(res.Inspect())));
            } catch (Throwable t) {
                huboExcepcion = true;
                out.println("EXCEPCION_EVAL|" + t.getClass().getName());
            }
            // Causa 8: qué quedó guardado en env para y (c1, c6, t13)
            Object y = env.get("y");
            if (y instanceof Errado) out.println("ENV_Y|Errado|" + Escapar(((Errado) y).Inspect()));
            else out.println("ENV_Y|" + (y == null ? "null" : y.getClass().getSimpleName()) + "|");
        } else {
            out.println("INSPECT_NO_CORRE");
        }
        out.flush();
        System.exit(huboExcepcion ? 3 : 0);
    }

    // Causa 7: Evaluator.hasErrors no se reinicia entre programas de la misma JVM
    private static void HijoCausa7(PrintStream out) throws Exception {
        Evaluator.Evaluate(new Parser(new Lexer(LeerFuente("c6.mingol"))).ParseProgram(), new Environment());
        boolean trasC6 = Evaluator.hasErrors;
        Evaluator.Evaluate(new Parser(new Lexer(LeerFuente("t4.mingol"))).ParseProgram(), new Environment());
        boolean trasT4 = Evaluator.hasErrors;
        out.println("CAUSA7|despues_c6=" + trasC6 + "|despues_t4=" + trasT4 + "|reiniciar=" + Existe("Evaluador.Evaluator", "Reiniciar"));
        out.flush();
        System.exit(0);
    }

    // MENSAJE|origen|... por reflexión: no existe en el código sin arreglar
    private static void ImprimirMensajes(PrintStream out, String origen, Object instancia, Class<?> clase) {
        try {
            Method gm = clase.getMethod("GetMensajes");
            Object lista = gm.invoke(instancia);
            // Tarea 3: MENSAJE|origen|linea|codigo|categoria|lexema|texto|formateado, cada campo con EscaparCampo
            for (Object msj : (List<?>) lista) {
                StringBuilder sb = new StringBuilder("MENSAJE|" + origen);
                for (String g : new String[]{"getLinea", "getCodigo", "getCategoria", "getLexema", "getTexto", "Formatear"}) {
                    try {
                        sb.append('|').append(EscaparCampo(String.valueOf(msj.getClass().getMethod(g).invoke(msj))));
                    } catch (NoSuchMethodException e) {
                        sb.append("|?");
                    }
                }
                out.println(sb.toString());
            }
        } catch (NoSuchMethodException e) {
            out.println("MENSAJES_NO_EXISTE|" + origen);
        } catch (Exception e) {
            out.println("MENSAJES_FALLA|" + origen + "|" + e.getClass().getName());
        }
    }

    // Copia de la normalización de extensión de Mingol.main (no se llama a main)
    private static String NormalizarComoMain(String path) {
        if (path.split("\\.").length == 1) path += ".mingol";
        return path;
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
    // Tarea 4.8: modo unitarias (en proceso)
    // ------------------------------------------------------------------

    private static void Unitarias() throws Exception {
        Path carpeta = CarpetaSegura();
        Path u = carpeta.resolve("unitarias");
        BorrarArbol(u);
        Files.createDirectories(u);
        UnitariasMensaje();
        UnitariasArchivoErrores(u);
        UnitariasRutas(u);
        UnitariasLexer();
        UnitariasParserE8();
        UnitariasEvaluator();
        ImprimirResumen("unitarias");
    }

    // Compara un valor y registra OK/FALLA
    private static void Igual(String nombre, Object esperado, Object obtenido) {
        List<String> d = new ArrayList<>();
        if (!Objects.equals(esperado, obtenido)) {
            d.add("esperado [" + Escapar(String.valueOf(esperado)) + "], obtenido [" + Escapar(String.valueOf(obtenido)) + "]");
        }
        Registrar(nombre, d, null);
    }

    // Mensaje.Formatear(), categorías y rechazo de códigos fuera de 100–399
    private static void UnitariasMensaje() {
        Igual("Mensaje 1xx", "ERROR 101: Carácter ilegal [@].", Mensaje.Error(3, 101, "Carácter ilegal", "@").Formatear());
        Igual("Mensaje 1xx (102)", "ERROR 102: Constante numérica no convertible a entero [12345678901].",
                Mensaje.Error(2, 102, "Constante numérica no convertible a entero", "12345678901").Formatear());
        Igual("Mensaje 2xx", "ERROR 202: No puede haber nada en la línea después de BEGIN [x].",
                Mensaje.Error(1, 202, "No puede haber nada en la línea después de BEGIN", "x").Formatear());
        Igual("Mensaje 3xx", "ERROR 303: Identificador no encontrado [zz].",
                Mensaje.Error(6, 303, "Identificador no encontrado", "zz").Formatear());
        Igual("Mensaje advertencia", "Advertencia: instrucción no es soportada por esta versión [LONG].",
                Mensaje.Advertencia(3, "LONG").Formatear());
        Igual("Mensaje lexema EOL", "ERROR 204: Se esperaba un token de tipo SEMICOLON pero se obtuvo EOL [EOL].",
                Mensaje.Error(2, 204, "Se esperaba un token de tipo SEMICOLON pero se obtuvo EOL", "EOL").Formatear());
        Igual("Mensaje lexema EOF", "ERROR 204: Se esperaba un token de tipo FI pero se obtuvo EOF [EOF].",
                Mensaje.Error(6, 204, "Se esperaba un token de tipo FI pero se obtuvo EOF", "EOF").Formatear());

        // Categoría derivada del código, EsError y línea tal como se detectó (incluida la n+1)
        Igual("Mensaje categoría 1xx", Mensaje.Categoria.LEXICO, Mensaje.Error(1, 100, "t", "l").getCategoria());
        Igual("Mensaje categoría 2xx", Mensaje.Categoria.SINTACTICO, Mensaje.Error(1, 299, "t", "l").getCategoria());
        Igual("Mensaje categoría 3xx", Mensaje.Categoria.SEMANTICO, Mensaje.Error(1, 399, "t", "l").getCategoria());
        Mensaje adv = Mensaje.Advertencia(4, "RETURN");
        Igual("Mensaje advertencia: categoría, sin código, no es error",
                "ADVERTENCIA|null|false", adv.getCategoria() + "|" + adv.getCodigo() + "|" + adv.EsError());
        Igual("Mensaje error: EsError y línea n+1 conservada", "true|6",
                Mensaje.Error(6, 204, "t", "EOF").EsError() + "|" + Mensaje.Error(6, 204, "t", "EOF").getLinea());

        // Fuera de 100–399: IllegalArgumentException
        for (int cod : new int[]{0, 5, 99, 400, 999, -101}) {
            List<String> d = new ArrayList<>();
            try {
                Mensaje.Error(1, cod, "t", "l");
                d.add("no lanzó IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                // Esperado
            } catch (Throwable t) {
                d.add("lanzó " + t.getClass().getName());
            }
            Registrar("Mensaje rechaza el código " + cod, d, null);
        }
    }

    // Escribe con ArchivoErrores y lee el resultado con ISO-8859-1 (se escribe en Cp1252)
    private static String EscribirYLeer(Path dir, String nombre, List<String> lineas, List<Mensaje> mensajes) throws IOException {
        Path p = dir.resolve(nombre);
        ArchivoErrores.Escribir(p.toString(), lineas, mensajes);
        return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
    }

    private static void UnitariasArchivoErrores(Path u) throws Exception {
        Mensaje m307 = Mensaje.Error(Mensaje.SIN_LINEA, 307, "No se encontró una sentencia BEGIN", "BEGIN");
        Mensaje m308 = Mensaje.Error(Mensaje.SIN_LINEA, 308, "No se encontró una sentencia END", "END");
        String l307 = S8 + "ERROR 307: No se encontró una sentencia BEGIN [BEGIN].\n";
        String l308 = S8 + "ERROR 308: No se encontró una sentencia END [END].\n";

        // Fuente vacío con 307 y 308: solo el bloque final, cada mensaje con su salto
        Igual("ArchivoErrores fuente vacío con 307 y 308", l307 + l308,
                EscribirYLeer(u, "vacio-errores.txt", new ArrayList<>(), Arrays.asList(m307, m308)));

        // Categorías mezcladas en una línea; orden estable dentro de la categoría (204 antes que 201)
        List<Mensaje> mezcla = new ArrayList<>(Arrays.asList(
                Mensaje.Advertencia(2, "LONG"),
                Mensaje.Error(2, 303, "Identificador no encontrado", "b"),
                Mensaje.Error(2, 204, "Se esperaba un token de tipo IDENT pero se obtuvo EQ", "="),
                Mensaje.Error(2, 101, "Carácter ilegal", "@"),
                Mensaje.Error(2, 201, "El valor inicial no corresponde al tipo INT de la variable a", "1.5"),
                Mensaje.Error(1, 305, "No puede haber más de una sentencia BEGIN por archivo", "BEGIN")));
        List<Mensaje> copiaMezcla = new ArrayList<>(mezcla);
        String esperadoMezcla = "00001 BEGIN\n"
                + S8 + "ERROR 305: No puede haber más de una sentencia BEGIN por archivo [BEGIN].\n"
                + "00002 x = 3;\n"
                + S8 + "ERROR 101: Carácter ilegal [@].\n"
                + S8 + "ERROR 204: Se esperaba un token de tipo IDENT pero se obtuvo EQ [=].\n"
                + S8 + "ERROR 201: El valor inicial no corresponde al tipo INT de la variable a [1.5].\n"
                + S8 + "ERROR 303: Identificador no encontrado [b].\n"
                + S8 + "Advertencia: instrucción no es soportada por esta versión [LONG].\n"
                + "00003 END\n";
        Igual("ArchivoErrores categorías mezcladas y orden estable", esperadoMezcla,
                EscribirYLeer(u, "mezcla-errores.txt", Arrays.asList("BEGIN", "x = 3;", "END"), mezcla));
        // Escribe ordenando una copia: la lista recibida no cambia
        Igual("ArchivoErrores no modifica la lista de mensajes", true, copiaMezcla.equals(mezcla));

        // Línea n+1 (y más allá) bajo la línea n, junto con los de n y ordenados por categoría; 308 al final
        List<Mensaje> fuera = Arrays.asList(
                Mensaje.Error(4, 204, "Se esperaba un token de tipo FI pero se obtuvo EOF", "EOF"),
                m308,
                Mensaje.Error(3, 306, "No puede haber más de una sentencia END por archivo", "END"),
                Mensaje.Error(9, 203, "Token que no puede iniciar una expresión", "EOF"));
        String esperadoFuera = "00001 BEGIN\n00002 SKIP;\n00003 END\n"
                + S8 + "ERROR 204: Se esperaba un token de tipo FI pero se obtuvo EOF [EOF].\n"
                + S8 + "ERROR 203: Token que no puede iniciar una expresión [EOF].\n"
                + S8 + "ERROR 306: No puede haber más de una sentencia END por archivo [END].\n"
                + l308;
        Igual("ArchivoErrores línea fuera de rango bajo la última línea (D1)", esperadoFuera,
                EscribirYLeer(u, "fuera-errores.txt", Arrays.asList("BEGIN", "SKIP;", "END"), fuera));

        // Fuente vacío: el fuera de rango va al bloque final, antes de 307 (sintáctico antes que semántico)
        Igual("ArchivoErrores fuera de rango con fuente vacío va al bloque final",
                S8 + "ERROR 204: Se esperaba un token de tipo FI pero se obtuvo EOF [EOF].\n" + l307,
                EscribirYLeer(u, "vacio2-errores.txt", new ArrayList<>(),
                        Arrays.asList(m307, Mensaje.Error(1, 204, "Se esperaba un token de tipo FI pero se obtuvo EOF", "EOF"))));

        // Sin mensajes: copia literal (líneas vacías y con espacios) con un espacio y salto final
        Igual("ArchivoErrores sin mensajes: copia literal con salto final", "00001 BEGIN\n00002 \n00003    \n00004 END\n",
                EscribirYLeer(u, "copia-errores.txt", Arrays.asList("BEGIN", "", "   ", "END"), new ArrayList<>()));
    }

    // NombrePrograma, RutaA68 y MismoArchivo por reflexión (son privados)
    private static void UnitariasRutas(Path u) throws Exception {
        Class<?>[] s = {String.class};
        String[][] nombres = {
            {"t1.mingol", "t1", "t1.a68"},
            {"T10.MINGOL", "T10", "T10.a68"},
            // Área 5 (E5): RutaA68 es relativa a la carpeta actual, sin la carpeta del fuente
            {"sub.dir\\t11.mingol", "t11", "t11.a68"},
            {"C:\\x.y\\z.Mingol", "z", "z.a68"},
            {"t12.mingol", "t12", "t12.a68"},
            {"x.a68", "x", "x.a68"},
            {"X.A68", "X", "X.a68"},
        };
        for (String[] c : nombres) {
            Igual("NombrePrograma(" + c[0] + ")", c[1], Llamar("NombrePrograma", s, c[0]));
            Igual("RutaA68(" + c[0] + ")", c[2], Llamar("RutaA68", s, c[0]));
        }

        // MismoArchivo con archivos reales en la carpeta de unitarias
        Class<?>[] pp = {Path.class, Path.class};
        Path x = u.resolve("x.a68");
        Files.write(x, "BEGIN\nEND\n".getBytes(StandardCharsets.US_ASCII));
        // Área 5 (E5): RutaA68 ya no trae la carpeta; se resuelve contra la carpeta u
        Path rutaX = u.resolve((String) Llamar("RutaA68", s, x.toString()));
        Igual("MismoArchivo(x.a68, RutaA68(x.a68)) existente", true, Llamar("MismoArchivo", pp, x, rutaX));
        // Windows no distingue mayúsculas: X.A68 es el mismo archivo
        Igual("MismoArchivo(X.A68, x.a68)", true, Llamar("MismoArchivo", pp, u.resolve("X.A68"), x));
        Path t1 = u.resolve("t1.mingol");
        Files.write(t1, "BEGIN\nEND\n".getBytes(StandardCharsets.US_ASCII));
        // Área 5 (E5): se resuelve contra la carpeta u (no la carpeta actual)
        Path t1a68 = u.resolve((String) Llamar("RutaA68", s, t1.toString()));
        Igual("MismoArchivo(t1.mingol, t1.a68) sin t1.a68", false, Llamar("MismoArchivo", pp, t1, t1a68));
        Files.write(t1a68, "BEGIN\nEND\n".getBytes(StandardCharsets.US_ASCII));
        Igual("MismoArchivo(t1.mingol, t1.a68) con t1.a68 existente", false, Llamar("MismoArchivo", pp, t1, t1a68));
    }

    // Líneas de los tokens: líneas vacías y con espacios, cadena y comentario de varias líneas, EOL y EOF
    private static void UnitariasLexer() {
        // Actualizado por un comportamiento léxico corregido (3.10): el texto ya no abarca dos líneas (L5);
        // la línea 5 queda vacía para conservar el comentario en la 6 y 7, END en la 8 y EOF en la 9
        String fuente = String.join("\n", "", "BEGIN", "   ", "print ( \"a b\" );", "", "# comentario", "de dos lineas #", "END") + "\n";
        List<Token> toks = Tokens(new Lexer(fuente));
        List<Integer> eol = new ArrayList<>();
        Map<String, Integer> primera = new LinkedHashMap<>();
        for (Token t : toks) {
            if (t.getTokenType() == MingolToken.EOL) eol.add(t.getLine());
            else primera.putIfAbsent(t.getTokenType() + ":" + t.getLiteral(), t.getLine());
        }
        // 3.10: el texto termina en su línea, así que también hay EOL de la 4
        Igual("Lexer EOL con la línea que termina", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8), eol);
        Igual("Lexer BEGIN tras una línea vacía", 2, primera.get("BEGIN:BEGIN"));
        Igual("Lexer texto de una línea en la 4", 4, primera.get("STRING:a b"));
        // 3.10: el comentario lo salta el lexer: entre apertura y cierre solo los EOL de la 6 y la 7
        List<String> enComentario = new ArrayList<>();
        for (Token t : toks) {
            if ((t.getLine() == 6 || t.getLine() == 7) && t.getTokenType() != MingolToken.EOL) {
                enComentario.add(t.getTokenType() + ":" + Escapar(t.getLiteral()) + "@" + t.getLine());
            }
        }
        Igual("Lexer ningún token dentro del comentario salvo EOL", new ArrayList<String>(), enComentario);
        Igual("Lexer END en la 8", 8, primera.get("END:END"));
        Token ultimo = toks.get(toks.size() - 1);
        Igual("Lexer EOF con n+1", "EOF|9", ultimo.getTokenType() + "|" + ultimo.getLine());

        // 3.10: texto sin cerrar (L5): 112 en la 4 y el texto termina en su línea, con EOL de la 4
        // La línea 5 va sin comillas: si tuviera una, abriría otro texto sin cerrar (otro 112)
        String sinCerrar = String.join("\n", "", "BEGIN", "   ", "print ( \"a", "b );", "END") + "\n";
        Lexer ls = new Lexer(sinCerrar);
        List<Token> ts = Tokens(ls);
        List<String> m112 = new ArrayList<>();
        for (Mensaje m : ls.GetMensajes()) if (Integer.valueOf(112).equals(m.getCodigo())) m112.add(String.valueOf(m.getLinea()));
        Igual("Lexer texto sin cerrar: 112 en la 4", Arrays.asList("4"), m112);
        boolean eol4 = false;
        for (Token t : ts) if (t.getTokenType() == MingolToken.EOL && t.getLine() == 4) eol4 = true;
        Igual("Lexer texto sin cerrar: EOL en la 4", true, eol4);
    }

    // Todos los tokens hasta EOF (con tope por seguridad)
    private static List<Token> Tokens(Lexer lx) {
        List<Token> toks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Token t = lx.NextToken();
            toks.add(t);
            if (t.getTokenType() == MingolToken.EOF) break;
        }
        return toks;
    }

    // e8 (área 3, sintaxis corregida): "SKIP;" ya no consume el END, así que en lugar del 204 [EOF]
    // en la línea n+1 salen el 206 [;] en la 4 y el 204 de FI con lexema END en la 5, y nada más
    private static void UnitariasParserE8() {
        Parser p = new Parser(new Lexer("BEGIN\nINT x;\nIF (x) THEN\nSKIP;\nEND\n"));
        p.ParseProgram();
        List<String> d = new ArrayList<>();
        List<String> esperado = Arrays.asList(
                "4:ERROR 206: La línea anterior a END no lleva punto y coma [;].",
                "5:ERROR 204: Se esperaba un token de tipo FI pero se obtuvo END [END].");
        List<String> obtenido = Formateados(p.GetMensajes());
        if (!esperado.equals(obtenido)) d.add("GetMensajes(): esperado " + esperado + ", obtenido " + obtenido);
        for (Mensaje m : p.GetMensajes()) {
            if (m.getCategoria() != Mensaje.Categoria.SINTACTICO) d.add("categoría " + m.getCategoria() + " en " + m.Formatear());
        }
        // La cadena de GetErrors() del 204 conserva el texto de hoy ("... pero se obtuvo un END")
        if (p.GetErrors().stream().noneMatch(e -> e.contains("Error 204: Se esperaba un token de tipo FI pero se obtuvo un END"))) {
            d.add("GetErrors() sin el 204 de FI con END: " + p.GetErrors());
        }
        Registrar("Parser e8: GetMensajes() con 206 [;] en la 4 y 204 FI [END] en la 5 (sin 204 [EOF])", d, null);
        UnitariasCorrespondeS1();
    }

    // S1 (área 3, sintaxis corregida): Corresponde traduce "205" a 101 solo con "Token ilegal:"; el
    // nuevo 205 de REQ-ALGOL-02 corresponde a 205. Se usan las cadenas y Mensajes reales del parser
    private static void UnitariasCorrespondeS1() {
        Parser p = new Parser(new Lexer("BEGIN\nfoo;\n@\nSKIP\nEND\n"));
        p.ParseProgram();
        List<String> d = new ArrayList<>();
        List<Mensaje> msj = p.GetMensajes();
        List<String> cad = p.GetErrors();
        boolean hay101 = false, hay205 = false;
        if (msj.size() != cad.size()) {
            d.add(msj.size() + " Mensajes y " + cad.size() + " cadenas: " + Formateados(msj) + " / " + cad);
        } else {
            for (int i = 0; i < msj.size(); i++) {
                Mensaje m = msj.get(i);
                String[] campos = {String.valueOf(m.getLinea()), String.valueOf(m.getCodigo()), String.valueOf(m.getCategoria()),
                        m.getLexema(), m.getTexto()};
                String r = Corresponde(cad.get(i), campos);
                if (r != null) d.add("correspondencia " + (i + 1) + ": " + r);
                if (Integer.valueOf(101).equals(m.getCodigo())) hay101 = true;
                if (Integer.valueOf(205).equals(m.getCodigo())) {
                    hay205 = true;
                    // El mismo 205 no debe corresponder a un 101
                    campos[1] = "101";
                    campos[2] = "LEXICO";
                    if (Corresponde(cad.get(i), campos) == null) d.add("el 205 [" + m.getLexema() + "] se aceptó como 101");
                }
            }
        }
        if (!hay101) d.add("sin 101 [@]: " + Formateados(msj));
        if (!hay205) d.add("sin 205 [foo]: " + Formateados(msj));
        Registrar("Corresponde (S1): 205 de 'Token ilegal:' -> 101; 205 de REQ-ALGOL-02 -> 205", d, null);
    }

    private static List<String> Formateados(List<Mensaje> l) {
        List<String> r = new ArrayList<>();
        for (Mensaje m : l) r.add(m.getLinea() + ":" + m.Formatear());
        return r;
    }

    // Token con línea (los nodos la exponen con getLine)
    private static Token Tk(MingolToken tipo, String literal, int linea) {
        Token t = new Token(tipo, literal);
        t.setLine(linea);
        return t;
    }

    private static Identifier Id(String nombre, int linea) {
        return new Identifier(Tk(MingolToken.IDENT, nombre, linea), nombre);
    }

    // Mensajes del evaluador como "linea|codigo|lexema"
    private static List<String> MsjEval() {
        List<String> r = new ArrayList<>();
        for (Mensaje m : Evaluator.GetMensajes()) r.add(m.getLinea() + "|" + m.getCodigo() + "|" + m.getLexema());
        return r;
    }

    private static String Res(Objeto o) {
        return o == null ? "null" : o.getClass().getSimpleName() + ":" + o.Inspect();
    }

    private static void UnitariasEvaluator() {
        // Evaluate(null): null, sin mensajes
        Evaluator.Reiniciar();
        Igual("Evaluator Evaluate(null)", "null|[]", Res(Evaluator.Evaluate(null, new Environment())) + "|" + MsjEval());

        // ExpressionStatement con expresión null
        Evaluator.Reiniciar();
        Objeto r = Evaluator.Evaluate(new ExpressionStatement(Tk(MingolToken.IDENT, "x", 2), null), new Environment());
        Igual("Evaluator ExpressionStatement con expresión null", "null|[]|false", Res(r) + "|" + MsjEval() + "|" + Evaluator.hasErrors);

        // Infix con operando null: null sin error
        Evaluator.Reiniciar();
        r = Evaluator.Evaluate(new Infix(Tk(MingolToken.ADDITION, "+", 3),
                new Integral(Tk(MingolToken.INTEGER, "1", 3), 1), "+", null), new Environment());
        Igual("Evaluator Infix con operando null", "null|[]|false", Res(r) + "|" + MsjEval() + "|" + Evaluator.hasErrors);

        // Infix con operando Errado: se propaga el 303, sin 301 en cascada
        Evaluator.Reiniciar();
        r = Evaluator.Evaluate(new Infix(Tk(MingolToken.ADDITION, "+", 4), Id("zz", 4), "+",
                new Integral(Tk(MingolToken.INTEGER, "1", 4), 1)), new Environment());
        Igual("Evaluator Infix con operando Errado (sin 301)", "Errado|true|[4|303|zz]",
                (r == null ? "null" : r.getClass().getSimpleName()) + "|" + (r != null && r.Inspect().contains("Error 303")) + "|" + MsjEval());

        // Infix con los dos operandos Errado: los dos 303, ningún 301
        Evaluator.Reiniciar();
        r = Evaluator.Evaluate(new Infix(Tk(MingolToken.ADDITION, "+", 4), Id("zz", 4), "+", Id("ww", 4)), new Environment());
        Igual("Evaluator Infix con dos operandos Errado", "[4|303|zz, 4|303|ww]", String.valueOf(MsjEval()));

        // Prefix con operando null y con operando Errado
        Evaluator.Reiniciar();
        r = Evaluator.Evaluate(new Prefix(Tk(MingolToken.SUBSTRACTION, "-", 5), "-", null), new Environment());
        Igual("Evaluator Prefix con operando null", "null|[]", Res(r) + "|" + MsjEval());
        Evaluator.Reiniciar();
        r = Evaluator.Evaluate(new Prefix(Tk(MingolToken.SUBSTRACTION, "-", 5), "-", Id("zz", 5)), new Environment());
        Igual("Evaluator Prefix con operando Errado (sin 302)", "Errado|[5|303|zz]",
                (r == null ? "null" : r.getClass().getSimpleName()) + "|" + MsjEval());

        // LetStatement con valor null: guarda NULL, sin mensajes
        Evaluator.Reiniciar();
        Environment env = new Environment();
        r = Evaluator.Evaluate(new LetStatement(Tk(MingolToken.TYPEINT, "INT", 6), Id("a", 6), null), env);
        Igual("Evaluator LetStatement con valor null guarda NULL", "null|true|[]",
                Res(r) + "|" + (env.get("a") == Evaluator.NULL) + "|" + MsjEval());

        // LetStatement con valor Errado: lo devuelve, la variable queda declarada, un solo Mensaje
        Evaluator.Reiniciar();
        env = new Environment();
        r = Evaluator.Evaluate(new LetStatement(Tk(MingolToken.TYPECHAR, "CHAR", 7), Id("y", 7), Id("zz", 7)), env);
        Igual("Evaluator LetStatement con valor Errado", "Errado|true|[7|303|zz]",
                (r == null ? "null" : r.getClass().getSimpleName()) + "|" + (env.get("y") instanceof Errado) + "|" + MsjEval());
        // Usar y después no registra otro mensaje (el Errado guardado no se vuelve a registrar)
        Evaluator.Evaluate(Id("y", 8), env);
        Igual("Evaluator uso posterior de y sin 303 nuevo", "[7|303|zz]", String.valueOf(MsjEval()));
        // Redeclaración: 304 con la línea del nombre
        Evaluator.Evaluate(new LetStatement(Tk(MingolToken.TYPEINT, "INT", 9), Id("y", 9),
                new Integral(Tk(MingolToken.INTEGER, "1", 9), 1)), env);
        Igual("Evaluator redeclaración de y da 304", "[7|303|zz, 9|304|y]", String.valueOf(MsjEval()));

        // Reiniciar(): vacía los mensajes y pone hasErrors en false
        boolean antes = Evaluator.hasErrors && !Evaluator.GetMensajes().isEmpty();
        Evaluator.Reiniciar();
        Igual("Evaluator Reiniciar()", "true|[]|false", antes + "|" + MsjEval() + "|" + Evaluator.hasErrors);

        // EvaluateIdentifier: 303 con la línea del identificador (también una etiqueta guardada como String)
        Evaluator.Reiniciar();
        env = new Environment();
        env.put("inicio", "");
        Evaluator.Evaluate(Id("qq", 11), env);
        Evaluator.Evaluate(Id("inicio", 12), env);
        Igual("Evaluator EvaluateIdentifier con la línea del identificador", "[11|303|qq, 12|303|inicio]", String.valueOf(MsjEval()));
        List<String> d = new ArrayList<>();
        if (!Evaluator.GetMensajes().isEmpty()) {
            Mensaje m = Evaluator.GetMensajes().get(0);
            if (!"Identificador no encontrado".equals(m.getTexto())) d.add("texto [" + m.getTexto() + "]");
            if (m.getCategoria() != Mensaje.Categoria.SEMANTICO) d.add("categoría " + m.getCategoria());
        } else {
            d.add("sin mensajes");
        }
        Registrar("Evaluator EvaluateIdentifier: texto y categoría del 303", d, null);
        Evaluator.Reiniciar();
    }

    // ------------------------------------------------------------------
    // Tarea 4.8: modo propiedades (generador, semillas 1..200, en hijos)
    // ------------------------------------------------------------------

    private static final List<String> CATEGORIAS = Arrays.asList("LEXICO", "SINTACTICO", "SEMANTICO", "ADVERTENCIA");

    private static void Propiedades() throws Exception {
        Path carpeta = CarpetaSegura();
        Path trabajo = carpeta.resolve("propiedades");
        BorrarArbol(trabajo);
        Path logs = carpeta.resolve("logs-propiedades");
        BorrarArbol(logs);
        System.out.println("Carpeta de trabajo: " + trabajo);

        List<Caso> casos = new ArrayList<>();
        Map<String, byte[]> fuentes = new LinkedHashMap<>();
        for (int s = 1; s <= 200; s++) {
            Caso c = Generar(s);
            casos.add(c);
            fuentes.put(c.id, c.bytes());
        }
        Map<String, ResultadoHijo> res = CorrerCasos(casos, fuentes, trabajo, logs);

        int nIndep = 0, nValor = 0, nValidos = 0, nConMensajes = 0;
        for (Caso c : casos) {
            ResultadoHijo r = res.get(c.id);
            List<String> meta = LineasMeta(r.stdout);
            List<String> lineas = LineasFuente(fuentes.get(c.id));
            Path errores = trabajo.resolve(c.id).resolve(c.nombreErrores);
            List<String> real = Files.exists(errores) ? Files.readAllLines(errores, StandardCharsets.ISO_8859_1) : null;

            // Property 1: formato, ubicación, orden y validez
            List<String> dif = new ArrayList<>();
            ProblemasDeEjecucion(r, dif);
            if (real == null) {
                dif.add("no existe " + c.nombreErrores);
            } else {
                Caso v = new Caso();
                v.lineas = lineas.toArray(new String[0]);
                ValidarFormato(v, real, dif);
                // Oráculo: cada Mensaje (parser y evaluador) bajo su línea según la regla del diseño
                List<String[]> msj = new ArrayList<>(Mensajes6(meta, "parser"));
                msj.addAll(Mensajes6(meta, "evaluador"));
                if (!msj.isEmpty()) nConMensajes++;
                List<String> esperado = ArchivoDesdeMensajes(lineas, msj);
                if (!real.equals(esperado)) dif.add("el archivo no ubica los mensajes como el diseño: " + PrimeraDiferencia(esperado, real));
                // Validez = ausencia de ERROR = NOT (parser.hasErrors OR evaluator.hasErrors)
                boolean hayError = real.stream().anyMatch(l -> l.startsWith(S8 + "ERROR "));
                String tp = Valor(meta, "TESTPROYECTO|");
                if (!String.valueOf(!hayError).equals(tp)) dif.add("TestProyecto=" + tp + " con ERROR en el archivo: " + hayError);
                String he = Valor(meta, "HASERRORS|");
                if (he == null) {
                    dif.add("sin HASERRORS");
                } else {
                    boolean hp = he.contains("parser=true"), hv = he.contains("evaluator=true");
                    if (!String.valueOf(!(hp || hv)).equals(tp)) dif.add("TestProyecto=" + tp + " inconsistente con " + he);
                }
                if ("true".equals(tp)) nValidos++;
            }
            // Fuente intacto
            Path fuente = trabajo.resolve(c.id).resolve(c.archivo);
            if (!Files.exists(fuente) || !Arrays.equals(fuentes.get(c.id), Files.readAllBytes(fuente))) dif.add("el fuente cambió");
            Registrar("propiedad formato y orden " + c.id, dif, r);
            if (real == null) continue;

            Map<Integer, List<String>> bajo = MensajesPorLinea(real, lineas);
            boolean[] seguro = LineasConstruidas(lineas);

            // Independencia (decisión 4): print ( (zz) ); construido por el parser da el 303 bajo su línea
            List<String> difI = new ArrayList<>();
            boolean aplicaI = false;
            for (int i = 0; i < lineas.size(); i++) {
                if (!seguro[i] || !lineas.get(i).trim().equals("print ( (zz) );")) continue;
                aplicaI = true;
                if (!bajo.getOrDefault(i + 1, new ArrayList<>()).contains("ERROR 303: Identificador no encontrado [zz].")) {
                    difI.add("falta el 303 [zz] bajo la línea " + (i + 1) + "; hay " + bajo.getOrDefault(i + 1, new ArrayList<>()));
                }
            }
            if (aplicaI) {
                nIndep++;
                Registrar("propiedad independencia " + c.id, difI, r);
            }

            // Valor inicial (2.16, 3.11; área 3 DS2): CHAR y := zz; da el 210 bajo su línea y ningún 303 [zz];
            // print ( (y) ); posterior sin 303
            List<String> difV = new ArrayList<>();
            int linY = -1;
            for (int i = 0; i < lineas.size(); i++) {
                if (seguro[i] && lineas.get(i).trim().equals("CHAR y := zz;")) {
                    linY = i;
                    break;
                }
            }
            if (linY >= 0) {
                nValor++;
                // Área 3 (DS2, sintaxis corregida): la declaración con valor inicial da 210 [:=] y el valor
                // no llega al evaluador, así que ya no hay 303 [zz]; y sigue declarada
                List<String> bajoY = bajo.getOrDefault(linY + 1, new ArrayList<>());
                if (!bajoY.contains("ERROR 210: Declaración con valor inicial [:=].")) {
                    difV.add("falta el 210 [:=] bajo la línea " + (linY + 1) + "; hay " + bajoY);
                }
                if (bajoY.contains("ERROR 303: Identificador no encontrado [zz].")) {
                    difV.add("303 [zz] bajo la línea " + (linY + 1) + " (DS2: el valor no se evalúa)");
                }
                for (int j = linY + 1; j < lineas.size(); j++) {
                    if (!lineas.get(j).trim().equals("print ( (y) );")) continue;
                    if (bajo.getOrDefault(j + 1, new ArrayList<>()).contains("ERROR 303: Identificador no encontrado [y].")) {
                        difV.add("print ( (y) ); de la línea " + (j + 1) + " da 303 [y]");
                    }
                }
                Registrar("propiedad valor inicial " + c.id, difV, r);
            }
        }
        System.out.println();
        System.out.println("Semillas: 200; con mensajes: " + nConMensajes + "; válidas: " + nValidos
                + "; con print ( (zz) ); construido: " + nIndep + "; con CHAR y := zz; construido: " + nValor);
        if (nIndep == 0 || nValor == 0 || nConMensajes == 0) System.out.println("AVISO: alguna propiedad no tuvo casos; revisar el generador");
        ImprimirResumen("propiedades");
    }

    // Mensajes de la huella con sus 6 campos: [linea, codigo, categoria, lexema, texto, formateado]
    private static List<String[]> Mensajes6(List<String> meta, String origen) {
        List<String[]> r = new ArrayList<>();
        String pref = "MENSAJE|" + origen + "|";
        for (String l : meta) {
            if (!l.startsWith(pref)) continue;
            String[] p = l.substring(pref.length()).split("\\|", -1);
            String[] f = new String[6];
            for (int i = 0; i < 6; i++) f[i] = i < p.length ? Desescapar(p[i]) : null;
            r.add(f);
        }
        return r;
    }

    // Oráculo del archivo: SIN_LINEA al final; 1..n bajo su línea; > n bajo n (o al final si n = 0);
    // dentro de cada clave, por categoría y luego por orden de detección (parser antes que evaluador)
    private static List<String> ArchivoDesdeMensajes(List<String> lineas, List<String[]> msj) {
        int n = lineas.size();
        List<int[]> orden = new ArrayList<>();
        for (int i = 0; i < msj.size(); i++) {
            int linea = Integer.parseInt(msj.get(i)[0]);
            int clave = linea < 1 ? Integer.MAX_VALUE : (linea <= n ? linea : (n >= 1 ? n : Integer.MAX_VALUE));
            orden.add(new int[]{clave, CATEGORIAS.indexOf(msj.get(i)[2]), i});
        }
        orden.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]).thenComparingInt(a -> a[2]));
        List<String> r = new ArrayList<>();
        int k = 0;
        for (int i = 1; i <= n; i++) {
            r.add(String.format("%05d", i) + " " + lineas.get(i - 1));
            while (k < orden.size() && orden.get(k)[0] == i) r.add(S8 + msj.get(orden.get(k++)[2])[5]);
        }
        while (k < orden.size()) r.add(S8 + msj.get(orden.get(k++)[2])[5]);
        return r;
    }

    // Mensajes (sin sangría) bajo cada línea numerada; el bloque final queda con la última línea
    private static Map<Integer, List<String>> MensajesPorLinea(List<String> real, List<String> lineas) {
        Map<Integer, List<String>> m = new LinkedHashMap<>();
        int idx = 0;
        for (String l : real) {
            if (idx < lineas.size() && l.equals(String.format("%05d", idx + 1) + " " + lineas.get(idx))) {
                idx++;
                continue;
            }
            m.computeIfAbsent(idx, k -> new ArrayList<>()).add(l.trim());
        }
        return m;
    }

    // Líneas que el parser construye como sentencias de primer nivel: fuera de todo IF del generador
    // y antes de cualquier anidamiento (un IF sin FI deja su bloque en null y arrastra lo que sigue)
    private static boolean[] LineasConstruidas(List<String> lineas) {
        boolean[] r = new boolean[lineas.size()];
        int abiertos = 0;
        boolean perdido = false;
        for (int i = 0; i < lineas.size(); i++) {
            String t = lineas.get(i).trim();
            if (t.startsWith("IF ")) {
                abiertos++;
                if (abiertos > 1) perdido = true;
            } else if (t.equals("FI;") && abiertos > 0) {
                abiertos--;
            }
            r[i] = !perdido && abiertos == 0 && !t.startsWith("IF ") && !t.equals("FI;");
        }
        return r;
    }

    // ------------------------------------------------------------------
    // Tarea 4.8: modo main (Mingol.main en hijos con entorno controlado)
    // ------------------------------------------------------------------

    // Marca del eco independiente del idioma del JDK ("Causado por:" va delante)
    private static final String ECO_MARCA = "java.lang.ClassNotFoundException: ";
    private static final String SYSTEM32 = "C:\\Windows\\System32";

    private static void ModoMain() throws Exception {
        // Guardia (R8): sin EjecutarA68g, main armaría la cadena de shell vieja y abriría otra consola
        try {
            Class.forName("Mingol.Mingol").getDeclaredMethod("EjecutarA68g", String.class);
        } catch (NoSuchMethodException e) {
            System.err.println("ABORTADO: Mingol.Mingol no tiene EjecutarA68g (código sin arreglar). No se ejecuta main.");
            System.exit(2);
        }
        Path carpeta = CarpetaSegura();
        // Todo dentro de %TEMP%\mingol-errores
        Path base = CarpetaGolden().getParent().resolve("mingol-errores");
        if (!carpeta.normalize().startsWith(base)) {
            System.err.println("ABORTADO: el modo main solo corre dentro de " + base);
            System.exit(2);
        }
        Path clases = CarpetaClases();
        if (clases == null) {
            System.err.println("ABORTADO: no se encontró build\\classes en el classpath.");
            System.exit(2);
        }
        // Eco: la java.exe del JDK; nunca un script por lotes
        Path eco = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
        String nEco = eco.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!Files.isRegularFile(eco) || nEco.endsWith(".bat") || nEco.endsWith(".cmd")) {
            System.err.println("ABORTADO: el eco no es un java.exe válido: " + eco);
            System.exit(2);
        }
        // Ningún a68g real al alcance del entorno controlado
        for (Path p : new Path[]{Paths.get(SYSTEM32, "a68g.exe"), Paths.get(SYSTEM32, "a68g"), clases.resolve("a68g.exe"), clases.resolve("a68g")}) {
            if (Files.exists(p)) {
                System.err.println("ABORTADO: existe " + p + "; el entorno controlado no sería seguro.");
                System.exit(2);
            }
        }
        Path trabajo = carpeta.resolve("main");
        BorrarArbol(trabajo);
        Files.createDirectories(trabajo);
        Path logs = carpeta.resolve("logs-main");
        BorrarArbol(logs);
        System.out.println("Carpeta de trabajo: " + trabajo);
        System.out.println("Eco (A68G): " + eco + "; PATH: " + SYSTEM32 + "; clases: " + clases);

        // Casos: t4, e4 y T10 válidos (invocan el eco); t3 y t7 con errores (no lo invocan)
        Map<String, Caso> fijos = new LinkedHashMap<>();
        for (Caso c : Casos()) fijos.put(c.id, c);
        List<Caso> casos = new ArrayList<>();
        casos.add(fijos.get("t4"));
        // e4 con SKIP (DS1, sintaxis corregida): la instrucción ALGOL ignorada no cuenta como comando;
        // sin el SKIP daría 217 y dejaría de invocar el eco
        casos.add(Nuevo("e4", "e4.mingol", "e4.mingol", "e4-errores.txt", "e4.a68", false, "BEGIN", "RETURN 5", "SKIP", "END"));
        casos.add(fijos.get("t10"));
        casos.add(fijos.get("t3"));
        casos.add(fijos.get("t7"));
        Map<String, Boolean> conEco = new LinkedHashMap<>();
        conEco.put("t4", true);
        conEco.put("e4", true);
        conEco.put("t10", true);
        conEco.put("t3", false);
        conEco.put("t7", false);

        for (Caso c : casos) {
            boolean esperaEco = conEco.get(c.id);
            Path dirMain = trabajo.resolve(c.id);
            Path dirRef = trabajo.resolve("ref-" + c.id);
            byte[] bytes = c.bytes();
            for (Path d : new Path[]{dirMain, dirRef}) {
                Files.createDirectories(d);
                Files.write(d.resolve(c.archivo), bytes);
            }
            List<String> d = new ArrayList<>();

            // Referencia: el -errores.txt que produce TestProyecto (hijo por reflexión, sin main)
            ResultadoHijo ref = LanzarHijo(Arrays.asList("hijopres", c.archivo, c.argumento), dirRef, logs, "ref-" + c.id, false, MAX_HUELLA);
            ProblemasDeEjecucion(ref, d);
            Path errRef = dirRef.resolve(c.nombreErrores);
            if (!Files.exists(errRef)) d.add("TestProyecto no escribió " + c.nombreErrores + " de referencia");
            String tpRef = Valor(LineasMeta(ref.stdout), "TESTPROYECTO|");
            if (!String.valueOf(esperaEco).equals(tpRef)) d.add("TestProyecto de referencia=" + tpRef + " (se esperaba " + esperaEco + ")");

            // main real en un hijo con entorno controlado
            ResultadoHijo r = LanzarMain(Arrays.asList("-cp", clases.toString(), "Mingol.Mingol", c.argumento),
                    dirMain, logs, "main-" + c.id, eco.toString());
            if (!r.termino) d.add("main no terminó en " + LIMITE_SEGUNDOS + " s (destroyForcibly)");
            // Área 5 (E3): 1 en los cinco casos (el eco java.exe sale con 1; t3/t7 por los errores)
            else if (r.codigo != 1) d.add("código de salida de main " + r.codigo + " (se esperaba 1)");
            String traza = PrimeraLineaCon(r.todo(), "\tat ");
            if (traza != null) d.add("traza: " + traza.trim());

            Path a68 = dirMain.resolve(c.rutaA68).toAbsolutePath();
            List<String> recibidos = new ArrayList<>();
            for (String l : r.stderr.split("\\R")) {
                int i = l.indexOf(ECO_MARCA);
                if (i >= 0) recibidos.add(l.substring(i + ECO_MARCA.length()));
            }
            if (esperaEco) {
                // El eco recibió exactamente la ruta absoluta del .a68, una sola vez
                if (recibidos.size() != 1) d.add("el eco se invocó " + recibidos.size() + " veces; stderr: " + Escapar(r.stderr.trim()));
                else if (!recibidos.get(0).equals(a68.toString())) d.add("argumento del eco [" + recibidos.get(0) + "], esperado [" + a68 + "]");
            } else {
                if (!recibidos.isEmpty()) d.add("se invocó el eco con un programa con errores: " + recibidos);
                String exc = PrimeraLineaCon(r.todo(), "Exception");
                if (exc != null) d.add("la salida contiene 'Exception': " + exc.trim());
            }

            // -errores.txt igual al de TestProyecto, fuente intacto y .a68 donde lo pone RutaA68
            Path errMain = dirMain.resolve(c.nombreErrores);
            if (!Files.exists(errMain)) d.add("main no escribió " + c.nombreErrores);
            else if (Files.exists(errRef) && !Arrays.equals(Files.readAllBytes(errRef), Files.readAllBytes(errMain))) {
                d.add(c.nombreErrores + " de main distinto del de TestProyecto: [" + Escapar(new String(Files.readAllBytes(errMain), StandardCharsets.ISO_8859_1))
                        + "] / [" + Escapar(new String(Files.readAllBytes(errRef), StandardCharsets.ISO_8859_1)) + "]");
            }
            Path fuente = dirMain.resolve(c.archivo);
            if (!Files.exists(fuente) || !Arrays.equals(bytes, Files.readAllBytes(fuente))) d.add("el fuente cambió");
            // Área 5 (E6, E10): válido → .a68 en la carpeta del caso, copia byte a byte; con errores, sin .a68
            if (esperaEco) {
                if (!Files.exists(a68)) d.add("no existe " + c.rutaA68);
                else if (!Files.exists(fuente) || Files.mismatch(fuente, a68) != -1L) d.add(c.rutaA68 + " no es copia byte a byte del fuente");
            } else if (Files.exists(a68)) {
                d.add("existe " + c.rutaA68 + " con un programa con errores");
            }
            // Ningún .bat/.cmd en la carpeta del caso; el eco no sigue vivo
            for (String n : ArchivosRec(dirMain)) {
                String low = n.toLowerCase(java.util.Locale.ROOT);
                if (low.endsWith(".bat") || low.endsWith(".cmd")) d.add("se creó " + n);
            }
            if (ProcesosVivosCon(a68.toString()) > 0) d.add("queda un proceso vivo con " + a68);
            Registrar("main " + c.id + (esperaEco ? " invoca el eco" : " no invoca el eco")
                    + (c.id.equals("t10") ? " (T10.MINGOL, fuente intacto)" : ""), d, r);
        }
        ImprimirResumen("main");
    }

    // build\classes: la entrada del classpath que contiene Mingol\Mingol.class
    private static Path CarpetaClases() {
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (e.isBlank()) continue;
            Path p = Paths.get(e).toAbsolutePath().normalize();
            if (Files.exists(p.resolve("Mingol").resolve("Mingol.class"))) return p;
        }
        return null;
    }

    // Mingol.main en un hijo (R8) con el entorno vaciado salvo SystemRoot/TEMP/TMP, más A68G y PATH
    private static ResultadoHijo LanzarMain(List<String> argsJava, Path dir, Path logs, String nombreLog, String eco) throws Exception {
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
        env.put("A68G", eco);
        env.put("PATH", SYSTEM32);

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
        // main y el eco escriben con la codificación nativa
        r.stdout = LeerConCodificacion(out.toPath(), Charset.defaultCharset());
        r.stderr = LeerConCodificacion(err.toPath(), Charset.defaultCharset());
        return r;
    }

    private static String LeerConCodificacion(Path p, Charset cs) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        return new String(b, 0, Math.min(b.length, 64 * 1024), cs);
    }

    // Procesos vivos cuya línea de comando contiene el texto (el eco no debe sobrevivir a main)
    private static long ProcesosVivosCon(String texto) {
        return ProcessHandle.allProcesses()
                .filter(ph -> ph.info().commandLine().map(cl -> cl.contains(texto)).orElse(false))
                .count();
    }

    // ------------------------------------------------------------------
    // Lanzamiento de hijos (R8), adaptado de PruebasCriticos
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

    // Misma java.exe, --enable-preview, -Xmx256m, mismo classpath; -ea si el padre lo usa o si se fuerza
    private static ResultadoHijo LanzarHijo(List<String> argsHijo, Path directorio, String nombreLog, boolean forzarEa) throws Exception {
        return LanzarHijo(argsHijo, directorio, nombreLog, forzarEa, 64 * 1024);
    }

    // Versión con límite de lectura propio (propiedades y main leen la huella completa)
    private static ResultadoHijo LanzarHijo(List<String> argsHijo, Path directorio, String nombreLog, boolean forzarEa,
                                            int maxBytes) throws Exception {
        return LanzarHijo(argsHijo, directorio, directorio.resolve("logs"), nombreLog, forzarEa, maxBytes);
    }

    // Versión con carpeta de logs propia: en CorrerCasos los logs no se mezclan con los archivos del caso
    private static ResultadoHijo LanzarHijo(List<String> argsHijo, Path directorio, Path logs, String nombreLog,
                                            boolean forzarEa, int maxBytes) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> comando = new ArrayList<>();
        comando.add(java);
        boolean ea = forzarEa;
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.equals("-ea") || a.equals("-enableassertions")) ea = true;
        }
        if (ea) comando.add("-ea");
        comando.add("--enable-preview");
        comando.add("-Xmx256m");
        comando.add("-cp");
        comando.add(System.getProperty("java.class.path"));
        comando.add("PruebasArchivoErrores");
        comando.addAll(argsHijo);

        // La salida va a archivos en la carpeta de logs (fuera de los nombres que se comparan)
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
        r.stderr = LeerRecortado(err.toPath(), maxBytes);
        return r;
    }

    // Lee como mucho maxBytes (un OutOfMemoryError puede dejar mucha salida)
    private static String LeerRecortado(Path p, int maxBytes) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        return new String(b, 0, Math.min(b.length, maxBytes), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Utilidades (copiadas y adaptadas de PruebasCriticos)
    // ------------------------------------------------------------------

    // %TEMP%\mingol-errores-golden: Pendiente 8, ya no hay captura; solo se usa su padre (la raíz de %TEMP%)
    private static Path CarpetaGolden() {
        String temp = System.getenv("TEMP");
        Path base = (temp != null && !temp.isBlank()) ? Paths.get(temp) : Paths.get(System.getProperty("java.io.tmpdir"));
        return base.resolve("mingol-errores-golden").toAbsolutePath().normalize();
    }

    // Borra una carpeta de trabajo del arnés; se niega a borrar fuera de %TEMP% (propiedades y main)
    @SuppressWarnings("unused")
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

    // Llama por reflexión a un método estático de Mingol.Mingol (también privado), para las tareas 2 a 4.8
    @SuppressWarnings("unused")
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

    // Primera línea distinta entre el esperado y el real
    private static String PrimeraDiferencia(List<String> esperado, List<String> real) {
        int n = Math.max(esperado.size(), real.size());
        for (int i = 0; i < n; i++) {
            String e = i < esperado.size() ? esperado.get(i) : "<fin>";
            String r = i < real.size() ? real.get(i) : "<fin>";
            if (!e.equals(r)) {
                return "línea " + (i + 1) + ": esperado [" + e.replace("\t", "<T>") + "], obtenido [" + r.replace("\t", "<T>") + "]";
            }
        }
        return "(sin diferencias)";
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
