//UNIVERSIDAD ESTATAL A DISTANCIA
//VICERRECTORÍA ACADÉMICA
//ESCUELA DE CIENCIAS EXACTAS Y NATURALES
//CÁTEDRA DE DESARROLLO DE SISTEMAS
//BACHILLERATO EN INGENIERÍA INFORMÁTICA
//Código: 03307
//Compiladores
//Centro Universitario: San Isidro (13)
//Estudiante: Sergio Ignacio Saborío Segura
//Cédula: 1-1717-0701
//PRIMER CUATRIMESTRE, 2022
package Mingol;

import Evaluador.Environment;
import Evaluador.Evaluator;
import Evaluador.Objeto;
import Errores.ArchivoErrores; // Escritura del archivo de errores
import Errores.Mensaje; // Mensajes estructurados de parser y evaluador
import Parser.Parser;
import Parser.Program;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import java.io.BufferedReader;
import java.io.File; // Separador del PATH en BuscarA68g
import java.io.FileNotFoundException; // Motivo, nivel 3 (DQ1)
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException; // CarpetaAplicacion
import java.nio.file.AccessDeniedException; // Motivo, nivel 2 (DQ1)
import java.nio.file.DirectoryNotEmptyException; // Motivo, nivel 2 (DQ1)
import java.nio.file.FileAlreadyExistsException; // Motivo, nivel 2 (DQ1)
import java.nio.file.FileSystemException; // Motivo, nivel 1 (DQ1)
import java.nio.file.Files; // Comprobar candidatos de a68g
import java.nio.file.NoSuchFileException; // Motivo, nivel 2 (DQ1)
import java.nio.file.InvalidPathException; // Rutas inválidas en A68G o el PATH
import java.nio.file.Path; // Necesario para RutaA68
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption; // CopiarA68 con REPLACE_EXISTING (E10)
import java.security.CodeSource; // CarpetaAplicacion
import java.util.ArrayList;
import java.util.List; // Líneas del fuente y mensajes
import java.util.Locale; // Minúsculas independientes del idioma en EsScriptPorLotes
import java.util.Scanner;
// Sin java.util.logging: main ya no registra trazas (cambio 1, DE1)

public class Mingol {

    public static void main(String[] args) {
        //REPL();
            // Sin argumento: uso, sin traza y sin crear archivos
            if (args.length == 0 || args[0].isBlank()) {
                System.err.println("Uso: java -jar MINGOL.jar <Programa>.MINGOL");
                System.exit(1);
            }
            // Único System.exit del flujo normal: 1 o el código literal de a68g (E3, DE1); sin registro de trazas ni corte por puntos
            System.exit(Compilar(args[0]));
    }

    // Preparar y luego a68g; 1 si MINGOL no deja un .a68 listo (E3, DE1)
    private static int Compilar(String argumento) {
        Path a68 = Preparar(argumento);
        return a68 == null ? 1 : EjecutarA68g(a68.toString());
    }

    // Bloque de entrada (a)-(d) y 115 (e); devuelve el .a68 o null. Nunca System.exit ni a68g (DE1)
    // Sin toAbsolutePath/toRealPath sobre el fuente: con a:b.MINGOL lanzaría IOError (DE3)
    private static Path Preparar(String argumento) {
        // Ruta efectiva calculada una vez: el mismo String abre el archivo y sale en los avisos (DQ2, DE4)
        String ruta;
        Path fuente;
        try {
            ruta = RutaEfectiva(argumento);
            fuente = Paths.get(ruta);
        } catch (InvalidPathException ex) {
            // (a) Ruta que Java no acepta: el argumento tal cual y el getMessage() de Java (E19, DQ2)
            System.err.println("No se pudo leer el archivo fuente: " + argumento + " (ruta inválida: " + ex.getMessage() + ")");
            return null;
        }
        // (b) No existe; CON, AUX, COMn, LPTn, NUL.txt caen aquí sin código especial (E7, DQ4)
        if (!Files.exists(fuente)) {
            System.err.println("No se encontró el archivo fuente: " + ruta);
            return null;
        }
        // (c) Existe pero no es un archivo regular: carpeta, NUL. (E18, DQ4)
        if (!Files.isRegularFile(fuente)) {
            System.err.println("No se pudo leer el archivo fuente: " + ruta + " (no es un archivo)");
            return null;
        }
        // (d) La lectura falla: motivo por el helper único, sin traza (E7, DQ1)
        List<String> lineas;
        try {
            lineas = LeerFuente(fuente);
        } catch (IOException ex) {
            System.err.println("No se pudo leer el archivo fuente: " + ruta + " (" + Motivo(ex) + ")");
            return null;
        }
        // (e) Leído: 115 si el nombre o la extensión no valen; el fuente se analiza igual (E1, E4, E13, E15)
        String nombre = fuente.getFileName().toString();
        return Procesar(fuente, lineas, NombreArchivoValido(nombre) ? null : nombre);
    }

    // Firma y resultado conservados para los arneses: sin 115, el nombre lo valida main (DE2)
    // El .a68 sigue E5/E6/E10 por Procesar; ya no se escribe con FileWriter antes del análisis (DE8)
    // Avisos: lo usan los arneses por reflexión
    @SuppressWarnings("unused")
    private static boolean TestProyecto(String archivoOriginal) throws IOException{
        Path fuente = Paths.get(archivoOriginal);
        // La lectura sigue lanzando IOException como antes
        return Procesar(fuente, LeerFuente(fuente), null) != null;
    }

    // Pasos (1)-(3) de E16: -errores.txt, ¿hay ERROR?, borrado o copia del .a68; devuelve el .a68 o null
    private static Path Procesar(Path fuente, List<String> lineas, String lexema115) {
        List<Mensaje> mensajes = Analizar(lineas);
        // 115 sin línea: ArchivoErrores lo pone en el bloque final antes que 307/308 (E1, E13, E20)
        if (lexema115 != null) {
            mensajes.add(Mensaje.Error(Mensaje.SIN_LINEA, 115, "Nombre de archivo inválido", lexema115));
        }
        // (1) -errores.txt con el nombre tal cual, en la carpeta actual (E2, E14); si falla, aviso sin traza (E16, DE4)
        String nombreErrores = NombrePrograma(fuente.toString()) + "-errores.txt";
        boolean escrito = true;
        try {
            ArchivoErrores.Escribir(nombreErrores, lineas, mensajes);
        } catch (IOException ex) {
            System.err.println("No se pudo escribir el archivo de errores: " + nombreErrores + " (" + Motivo(ex) + ")");
            escrito = false;
        }
        // (2) ¿Algún ERROR? Las advertencias se permiten
        boolean hayError = false;
        for (Mensaje m : mensajes) {
            if (m.EsError()) {
                hayError = true;
            }
        }
        Path a68 = Paths.get(RutaA68(fuente.toString()));
        if (hayError) {
            // Con ERROR se borra el .a68 viejo aunque haya fallado (1) (E6, E17); aviso después del de (1) (DE7)
            String aviso = BorrarA68Viejo(fuente, a68);
            if (aviso != null) {
                System.err.println(aviso);
            }
            return null;
        }
        // Sin ERROR pero sin -errores.txt: se corta antes de copiar (E16)
        if (!escrito) {
            return null;
        }
        // (3) Copia byte a byte (E10); si falla, aviso de E17 y null
        String aviso = CopiarA68(fuente, a68);
        if (aviso != null) {
            System.err.println(aviso);
            return null;
        }
        return a68;
    }

    // Lee el fuente con la misma lectura de siempre (FileReader nativo + readLine), extraída de TestProyecto (decisión 6, DE10)
    private static List<String> LeerFuente(Path fuente) throws IOException {
        List<String> lineas = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(fuente.toFile()))) {
            String line = reader.readLine();
            while (line != null) {
                lineas.add(line);
                line = reader.readLine();
            }
        }
        return lineas;
    }

    // Lexer, parser y evaluador sobre las líneas unidas con "\n"; extraído de TestProyecto sin cambios (3.5)
    private static List<Mensaje> Analizar(List<String> lineas) {
        // Fuente con un salto real tras cada línea (arreglo original del EOL)
        StringBuilder source = new StringBuilder();
        for (String line : lineas) {
            source.append(line).append("\n");
        }
        // Estado del evaluador limpio; parser y evaluador corren siempre (decisión 4)
        Evaluator.Reiniciar();
        Lexer lexer = new Lexer(source.toString());
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        Environment env = new Environment();
        Evaluator.Evaluate(program, env);
        // Mensajes del parser y luego del evaluador; lista nueva y modificable (Procesar agrega el 115)
        List<Mensaje> mensajes = new ArrayList<>(parser.GetMensajes());
        mensajes.addAll(Evaluator.GetMensajes());
        return mensajes;
    }

    // Con ERROR se borra el .a68 viejo; devuelve el aviso o null (E6, E21)
    private static String BorrarA68Viejo(Path fuente, Path a68) {
        try {
            // Es el propio fuente: no se toca y sin aviso (E12); va antes de cualquier borrado (DQ3)
            if (MismoArchivo(fuente, a68)) {
                return null;
            }
            // Una carpeta vacía se borra; con contenido, DirectoryNotEmptyException (DQ3)
            Files.deleteIfExists(a68);
            return null;
        } catch (IOException ex) {
            // E21; también si MismoArchivo lanza: no se borra nada (DE6)
            return "No se pudo borrar " + a68.getFileName() + " anterior: " + Motivo(ex);
        }
    }

    // Copia byte a byte del fuente al .a68; devuelve el aviso o null (E10, E17)
    private static String CopiarA68(Path fuente, Path a68) {
        try {
            // Nunca sobre el propio fuente (E12); va antes de copiar (DQ3); si MismoArchivo lanza, no se copia (DE6)
            if (MismoArchivo(fuente, a68)) {
                return null;
            }
            // Una carpeta vacía se reemplaza; con contenido, DirectoryNotEmptyException (DQ3)
            Files.copy(fuente, a68, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException ex) {
            String aviso = "No se pudo generar " + a68.getFileName() + ": " + Motivo(ex);
            try {
                // E17: no dejar un .a68 que no corresponde a este fuente; guardia E12 otra vez (DQ3); "no había nada" cuenta como borrado (DE5)
                if (!MismoArchivo(fuente, a68)) {
                    Files.deleteIfExists(a68);
                }
                return aviso;
            } catch (IOException ex2) {
                return aviso + "; puede quedar un " + a68.getFileName() + " anterior que no corresponde a este fuente";
            }
        }
    }

    // Nombre del programa: nombre del archivo sin carpetas ni la última extensión
    private static String NombrePrograma(String ruta) {
        String nombre = Paths.get(ruta).getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        // punto >= 0: ".MINGOL" da "" y "Punto." da "Punto" (E14)
        return (punto >= 0) ? nombre.substring(0, punto) : nombre;
    }

    // Ruta efectiva: agrega ".MINGOL" solo si el nombre del archivo no tiene punto (E8, E15, 1.11)
    // Mira solo el nombre, no las carpetas (r02); propaga InvalidPathException
    private static String RutaEfectiva(String argumento) {
        Path n = Paths.get(argumento).getFileName();
        if (n != null && n.toString().indexOf('.') < 0) {
            return argumento + ".MINGOL";
        }
        return argumento;
    }

    // Nombre y extensión según REQ-INPUT-01, sin distinguir mayúsculas (E4, E13); letras solo ASCII (DQ5)
    private static boolean NombreArchivoValido(String nombre) {
        // Extensión = tras el último punto (E8); sin punto no hay extensión .mingol
        int punto = nombre.lastIndexOf('.');
        if (punto < 0) {
            return false;
        }
        String base = nombre.substring(0, punto);
        String ext = nombre.substring(punto + 1);
        // Clase explícita en vez de equalsIgnoreCase, que aceptaría "MİNGOL" (U+0130) (E4)
        return ext.matches("[Mm][Ii][Nn][Gg][Oo][Ll]")
                && base.matches("[A-Za-z]([A-Za-z0-9_]{0,18}[A-Za-z0-9])?");
    }

    // Motivo de un fallo de E/S para los avisos: cascada de 4 niveles, nunca lanza ni da vacío (DQ1)
    private static String Motivo(IOException ex) {
        // Nivel 1: texto del sistema, si la excepción lo trae
        if (ex instanceof FileSystemException fse) {
            String razon = fse.getReason();
            if (razon != null && !razon.isEmpty()) {
                return razon;
            }
        }
        // Nivel 2: texto fijo por tipo
        if (ex instanceof NoSuchFileException) {
            return "No existe";
        }
        if (ex instanceof AccessDeniedException) {
            return "Acceso denegado";
        }
        if (ex instanceof DirectoryNotEmptyException) {
            return "La carpeta no está vacía";
        }
        if (ex instanceof FileAlreadyExistsException) {
            return "Ya existe";
        }
        String mensaje = ex.getMessage();
        // Nivel 3: FileNotFoundException "<ruta> (<texto>)"; si la forma no cuadra, nivel 4
        if (ex instanceof FileNotFoundException && mensaje != null && mensaje.endsWith(")")) {
            int abre = mensaje.lastIndexOf('(');
            if (abre >= 0 && abre < mensaje.length() - 2) {
                return mensaje.substring(abre + 1, mensaje.length() - 1);
            }
        }
        // Nivel 4: getMessage(); si es null o vacío, el nombre simple de la clase
        if (mensaje != null && !mensaje.isEmpty()) {
            return mensaje;
        }
        String clase = ex.getClass().getSimpleName();
        // Una clase anónima no tiene nombre simple: el nombre completo mantiene el texto no vacío
        return clase.isEmpty() ? ex.getClass().getName() : clase;
    }

    // Verdadero si el .a68 es el mismo archivo que el fuente (p. ej. un fuente x.a68)
    private static boolean MismoArchivo(Path fuente, Path a68) throws IOException {
        return Files.exists(a68) && Files.isSameFile(fuente, a68);
    }
    
    // Ruta del .a68: en la carpeta actual, sin resolveSibling; nombre del programa + ".a68" (E5)
    private static String RutaA68(String ruta) {
        return NombrePrograma(ruta) + ".a68";
    }

    // Ejecuta a68g en esta consola, sin shell: cada argumento va separado y se toma literal
    // Devuelve el código de salida: el literal de a68g o 1 si no se pudo ejecutar (E3, cambio 16)
    private static int EjecutarA68g(String rutaA68) {
        // A68G se lee una sola vez, para la búsqueda y para el mensaje
        String valorA68G = System.getenv("A68G");
        Path a68g = BuscarA68g(valorA68G, CarpetaAplicacion(), System.getenv("PATH"));
        if (a68g == null) {
            String mensaje = "No se encontró a68g. Se buscó en la variable de entorno A68G, "
                    + "en la carpeta de MINGOL y en el PATH. Defina A68G con la ruta completa de a68g.exe.";
            // Si A68G apuntaba a un script por lotes, se explica por qué se ignoró
            if (valorA68G != null && EsScriptPorLotes(valorA68G)) {
                mensaje += " A68G apunta a un archivo .bat/.cmd, que no se acepta: debe ser el ejecutable a68g.exe.";
            }
            System.err.println(mensaje);
            // Mismo mensaje; el código lo decide main (E3, DE1)
            return 1;
        }
        // Ruta absoluta: el mismo archivo que escribió TestProyecto, y no se confunde con una opción
        String archivo = Paths.get(rutaA68).toAbsolutePath().toString();
        try {
            // Exactamente dos elementos de comando, misma consola, y se espera a que termine
            Process proceso = new ProcessBuilder(a68g.toString(), archivo).inheritIO().start();
            // Código literal de a68g, sin normalizar (E3)
            return proceso.waitFor();
        } catch (IOException ex) {
            // Mensaje claro sin traza; 1 en vez de System.exit (E3, DE1)
            System.err.println("No se pudo iniciar a68g (" + a68g + "): " + ex.getMessage());
            return 1;
        } catch (InterruptedException ex) {
            // Se conserva el estado de interrupción; fallo de MINGOL → 1 (E3)
            Thread.currentThread().interrupt();
            System.err.println("Se interrumpió la espera de a68g.");
            return 1;
        }
    }

    // Busca a68g: A68G, luego la carpeta de la aplicación, luego el PATH. Puro: no lee el entorno
    private static Path BuscarA68g(String valorA68G, Path carpetaApp, String path) {
        // 1. Variable A68G (un valor inválido, inexistente o .bat/.cmd se salta)
        if (valorA68G != null && !valorA68G.isBlank()) {
            try {
                Path candidato = Paths.get(valorA68G);
                if (Aceptable(candidato)) {
                    return candidato;
                }
            } catch (InvalidPathException ex) {
                // Ruta inválida: se sigue buscando
            }
        }
        // 2. Carpeta de la aplicación: a68g.exe antes que a68g
        if (carpetaApp != null) {
            for (String nombre : new String[]{"a68g.exe", "a68g"}) {
                try {
                    Path candidato = carpetaApp.resolve(nombre);
                    if (Aceptable(candidato)) {
                        return candidato;
                    }
                } catch (InvalidPathException ex) {
                    // Se sigue con el siguiente candidato
                }
            }
        }
        // 3. Cada entrada no vacía del PATH, sin comillas alrededor
        if (path != null) {
            for (String entrada : path.split(File.pathSeparator)) {
                String dir = entrada.trim();
                if (dir.length() >= 2 && dir.startsWith("\"") && dir.endsWith("\"")) {
                    dir = dir.substring(1, dir.length() - 1).trim();
                }
                if (dir.isEmpty()) {
                    continue;
                }
                for (String nombre : new String[]{"a68g.exe", "a68g"}) {
                    try {
                        Path candidato = Paths.get(dir).resolve(nombre);
                        if (Aceptable(candidato)) {
                            return candidato;
                        }
                    } catch (InvalidPathException ex) {
                        // Entrada inválida del PATH: se salta
                    }
                }
            }
        }
        // 4. Ningún candidato aceptable
        return null;
    }

    // Candidato válido: archivo regular que no es un script por lotes
    private static boolean Aceptable(Path candidato) {
        Path nombre = candidato.getFileName();
        if (nombre == null || EsScriptPorLotes(nombre.toString())) {
            return false;
        }
        try {
            return Files.isRegularFile(candidato);
        } catch (SecurityException ex) {
            // Sin permiso para comprobarlo: no se acepta
            return false;
        }
    }

    // .bat y .cmd se rechazan: Windows los ejecuta con cmd.exe, que reinterpretaría los argumentos
    private static boolean EsScriptPorLotes(String nombre) {
        // Windows ignora puntos y espacios finales del nombre (x.bat. es x.bat)
        String n = nombre.replaceAll("[. ]+$", "").toLowerCase(Locale.ROOT);
        return n.endsWith(".bat") || n.endsWith(".cmd");
    }

    // Carpeta del .jar (o de las clases, si se ejecuta desde build\classes); null si no se puede saber
    private static Path CarpetaAplicacion() {
        try {
            CodeSource cs = Mingol.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            // toURI decodifica %20 y otros escapes
            Path p = Paths.get(cs.getLocation().toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (URISyntaxException | IllegalArgumentException | SecurityException
                | java.nio.file.FileSystemNotFoundException ex) {
            return null;
        }
    }

    // Avisos: se conserva por la decisión 6 (no se llama; el Scanner es System.in y no se cierra)
    @SuppressWarnings({"unused", "resource"})
    private static void REPL(){
        var scanned = new ArrayList<String>();
        
        Scanner scanner = new Scanner(System.in);
        //String line = "";
        String source = "";
        
        while(!source.equals("END")){
            System.out.print(">>");
            source = scanner.nextLine();
            //line = scanner.nextLine();
            //source += "\n" + line;
            scanned.add(source);
            
            Lexer lexer = new Lexer(String.join("", scanned));
            Parser parser = new Parser(lexer);
            Program program = parser.ParseProgram();
            Environment env = new Environment();
            
            if(source.equals("END")){
                continue;
            }
            if(parser.GetErrors().size() > 0){
                PrintParseErrors(parser.GetErrors());
                scanned.remove(scanned.size()-1);
                continue;
            }
            
            Objeto evaluated = Evaluator.Evaluate(program,env);
            
            if(evaluated != null){
                System.out.println(evaluated.Inspect());
            }
            //System.out.println(program.toString());
        }
    }
    
    private static void PrintParseErrors(ArrayList<String> errors){
        for(String error : errors){
            System.out.println(error);
        }
    }

    // Avisos: se conserva por la decisión 6 (no se llama; el switch solo mira algunos tokens)
    @SuppressWarnings({"unused", "incomplete-switch"})
    private static boolean TestTarea(String archivoOriginal) throws IOException {
        boolean hasErrors = false;
        boolean isBegin = false;
        boolean isEnd = false;
        boolean isComment = false;
        boolean errorBegin = false;
        int linea = 1;
        ArrayList<Token> tokens = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(archivoOriginal));
        //Crea resultado.txt
        String[] nombreArchivo = archivoOriginal.split("\\.");
        FileWriter writer = new FileWriter(nombreArchivo[0] + "-errores.txt");
        FileWriter archivoAlgol = new FileWriter(archivoOriginal.replace("mingol", "a68"));
        String line = reader.readLine();
        String error = "";

        while (line != null) {

            String source = line;
            if (line.length() > 80) {
                error += "\tError: las líneas no pueden sobrepasar los 80 caracteres.\n";
                hasErrors = true;
            }
            Lexer lexer = new Lexer(source);

            //Revisa token por token
            loop:
            for (int i = 0; i < source.length(); i++) {
                Token tempToken = lexer.NextToken();
                //Salta los comentarios
                if (tempToken.getTokenType() != MingolToken.COMMENT && isComment) {
                    continue;
                }

                //Revisa si no hay comandos antes del Begin
                if (!isBegin
                        && tempToken.getTokenType() != MingolToken.BEGIN
                        && tempToken.getTokenType() != MingolToken.EOL
                        && tempToken.getTokenType() != MingolToken.COMMENT
                        && !isComment
                        && !errorBegin) {
                    error += "\tError: No pueden haber comandos antes del Begin.\n";
                    hasErrors = true;
                    errorBegin = true;
                }

                //Hace cosas según el token que encuentre
                switch (tempToken.getTokenType()) {
                    //Si encuentra un comentario, lo activa o desactiva según sea el caso
                    case COMMENT:
                        isComment = !isComment;
                        break;
                    case BEGIN:
                        if (!isBegin) {
                            isBegin = true;
                        } else {
                            error += "\tError: no puede haber más de una sentencia Begin en el mismo archivo.\n";
                            hasErrors = true;
                        }
                        break;
                    case SEMICOLON:
                        switch (tokens.get(tokens.size() - 1).getTokenType()) {
                            case BEGIN:
                                error += "\tError: no puede haber un punto y coma luego de la sentencia Begin.\n";
                                hasErrors = true;
                                break;
                            case END:
                                error += "\tError: no puede haber un punto y coma luego de la sentencia End.\n";
                                hasErrors = true;
                                break;
                            case ELSE:
                                error += "\tError: no puede haber un punto y coma luego de la sentencia Else.\n";
                                hasErrors = true;
                                break;
                            case THEN:
                                error += "\tError: no puede haber un punto y coma luego de la sentencia Then.\n";
                                hasErrors = true;
                                break;
                            case DO:
                                error += "\tError: no puede haber un punto y coma luego de la sentencia Do.\n";
                                hasErrors = true;
                                break;
                        }
                        break;
                    case FI:
                        if (tokens.get(tokens.size() - 1).getTokenType() == MingolToken.SEMICOLON) {
                            error += "\tError: no puede haber un punto y coma en la línea anterior a la sentencia Fi.\n";
                            hasErrors = true;
                        }
                        break;
                    case OD:
                        if (tokens.get(tokens.size() - 1).getTokenType() == MingolToken.SEMICOLON) {
                            error += "\tError: no puede haber un punto y coma en la línea anterior a la sentencia Od.\n";
                            hasErrors = true;
                        }
                        break;
                    case END:
                        if (!isEnd) {
                            isEnd = true;
                        } else {
                            error += "\tError: no puede haber más de una sentencia End en el mismo archivo.\n";
                            hasErrors = true;
                        }
                        if (tokens.get(tokens.size() - 1).getTokenType() == MingolToken.SEMICOLON) {
                            error += "\tError: no puede haber un punto y coma en la línea anterior a la sentencia End.\n";
                            hasErrors = true;
                        }
                        break;
                    case EOL:
                        break loop;
                    default:
                        break;
                }
                tokens.add(tempToken);
            } // Fin procesamiento de tokens
            //Agrega los números de línea al archivo de errores
            String numeroDeLinea = String.format("%05d", linea);
            writer.write(numeroDeLinea + "\t" + line + "\n");
            //Agrega los errores al archivo
            if (error != "") {
                writer.write(error);
            }
            //Reinicia los controles
            errorBegin = false;
            error = "";
            linea++;
            archivoAlgol.write(line + "\n");
            line = reader.readLine();
        }//Fin procesamiento de líneas
        if (!isBegin) {
            writer.write("\tError: el archivo no incluye el comando BEGIN.\n");
        }
        if (!isEnd) {
            writer.write("\tError: el archivo no incluye el comando END.\n");
        }
        reader.close();
        writer.close();
        archivoAlgol.close();

        if (!hasErrors) {
            return true;
        } else {
            return false;
        }
    }
}