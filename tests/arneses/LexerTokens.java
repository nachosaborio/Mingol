import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Arnés de verificación del BUG 2 (tarea 3.2 de critical-compiler-bugs).
 *
 * a) Muestra la secuencia de tipos de token para fuentes con saltos reales.
 * b) Preservación 3.5: para cada archivo, construye el fuente como TestProyecto()
 *    (readLine() + separador) con contrabarra+n y con "\n" real, lo tokeniza con el
 *    lexer actual y compara las secuencias de tipos de token.
 *
 * Se ejecuta desde la raíz del repositorio (las rutas son relativas a ella).
 */
public class LexerTokens {

    private static final int MAX_TOKENS = 1_000_000;

    private static List<MingolToken> tipos(String source) {
        Lexer lexer = new Lexer(source);
        List<MingolToken> out = new ArrayList<>();
        for (int i = 0; i < MAX_TOKENS; i++) {
            Token t = lexer.NextToken();
            out.add(t.getTokenType());
            if (t.getTokenType() == MingolToken.EOF) {
                return out;
            }
        }
        throw new IllegalStateException("No se alcanzó EOF tras " + MAX_TOKENS + " tokens");
    }

    private static String unir(List<MingolToken> l) {
        StringBuilder sb = new StringBuilder();
        for (MingolToken t : l) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static List<String> leerLineas(File f) throws IOException {
        // Igual que TestProyecto(): FileReader + BufferedReader.readLine()
        List<String> lineas = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            while (line != null) {
                lineas.add(line);
                line = reader.readLine();
            }
        }
        return lineas;
    }

    private static long contarEOL(List<MingolToken> l) {
        return l.stream().filter(t -> t == MingolToken.EOL).count();
    }

    /** Devuelve true si es IGUAL y el número de EOL coincide con el de líneas. */
    private static boolean comparar(String nombre, List<String> lineas) {
        StringBuilder viejo = new StringBuilder();
        StringBuilder nuevo = new StringBuilder();
        for (String line : lineas) {
            viejo.append(line).append("\\n"); // separador anterior: contrabarra + n
            nuevo.append(line).append("\n");  // separador nuevo: salto real
        }
        List<MingolToken> a = tipos(viejo.toString());
        List<MingolToken> b = tipos(nuevo.toString());
        boolean igual = a.equals(b);
        long eolA = contarEOL(a);
        long eolB = contarEOL(b);
        boolean conteoOk = eolA == lineas.size() && eolB == lineas.size();

        System.out.printf("%-64s %-8s EOL(\\n)=%d EOL(real)=%d lineas=%d tokens=%d%s%n",
                nombre, igual ? "IGUAL" : "DISTINTO", eolA, eolB, lineas.size(), b.size(),
                conteoOk ? "" : "  <-- conteo EOL != lineas");

        if (!igual) {
            int n = Math.min(a.size(), b.size());
            int i = 0;
            while (i < n && a.get(i) == b.get(i)) i++;
            System.out.printf("    primera diferencia en token #%d: contrabarra+n=%s real=%s%n",
                    i, i < a.size() ? a.get(i) : "<fin>", i < b.size() ? b.get(i) : "<fin>");
        }
        return igual && conteoOk;
    }

    private static void agregarMingol(File dir, List<File> destino) {
        File[] fs = dir.listFiles((d, name) -> name.endsWith(".mingol"));
        if (fs == null) return;
        Arrays.sort(fs);
        destino.addAll(Arrays.asList(fs));
    }

    public static void main(String[] args) throws IOException {
        System.out.println("=== a) Secuencias de tipos de token (saltos reales) ===");
        String[][] casos = {
            {"BEGIN\\nEND\\n", "BEGIN\nEND\n"},
            {"BEGIN\\n\\nEND\\n", "BEGIN\n\nEND\n"},
            {"BEGIN\\n \\t \\nEND\\n", "BEGIN\n \t \nEND\n"},
        };
        for (String[] c : casos) {
            System.out.printf("%-26s -> %s%n", "\"" + c[0] + "\"", unir(tipos(c[1])));
        }

        System.out.println();
        System.out.println("=== b) Preservacion 3.5: contrabarra+n vs salto real ===");
        List<File> archivos = new ArrayList<>();
        archivos.add(new File("proyecto.mingol"));
        archivos.add(new File("prueba.mingol"));
        agregarMingol(new File("tests/evidencia-crit"), archivos);
        agregarMingol(new File("tests/evidencia-crit/preservacion"), archivos);

        boolean global = true;
        int total = 0;
        for (File f : archivos) {
            if (!f.isFile()) {
                System.out.println(f.getPath() + "  NO ENCONTRADO");
                global = false;
                continue;
            }
            global &= comparar(f.getPath().replace('\\', '/'), leerLineas(f));
            total++;
        }

        // Fuente sintético: líneas vacías y líneas de solo espacios/tabuladores
        List<String> sintetico = Arrays.asList(
                "BEGIN",
                "",
                "   ",
                "\t",
                " \t \t ",
                "",
                "    INT x := 1;",
                "\t\tPRINT(x);",
                "",
                "END");
        global &= comparar("<sintetico: vacias + espacios/tabs>", sintetico);
        total++;

        System.out.println();
        System.out.println("RESULTADO GLOBAL: " + (global ? "OK" : "FALLO") + " (" + total + " fuentes)");
    }
}
