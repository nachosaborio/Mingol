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
import Evaluador.Errado;
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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException; // CarpetaAplicacion
import java.nio.file.Files; // Comprobar candidatos de a68g
import java.nio.file.InvalidPathException; // Rutas inválidas en A68G o el PATH
import java.nio.file.Path; // Necesario para RutaA68
import java.nio.file.Paths;
import java.security.CodeSource; // CarpetaAplicacion
import java.util.ArrayList;
import java.util.List; // Líneas del fuente y mensajes
import java.util.Locale; // Minúsculas independientes del idioma en EsScriptPorLotes
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Mingol {

    public static void main(String[] args) {
        //REPL();
            // Sin argumento: uso, sin traza y sin crear archivos
            if (args.length == 0 || args[0].isBlank()) {
                System.err.println("Uso: java -jar MINGOL.jar <Programa>.MINGOL");
                System.exit(1);
            }
            try {
                String path = args[0];
                String parts[] = path.split("\\.");
                if (parts.length == 1) {
                    path += ".mingol";
                }
                //String path = "proyecto.mingol";
                if (TestProyecto(path)) {
                    // Se quitaron rutaArchivo y runtime, que no se usaban
                    // a68g se invoca sin shell, con el mismo .a68 que escribió TestProyecto
                    EjecutarA68g(RutaA68(path));
                }
            } catch (IOException ex) {
            Logger.getLogger(Mingol.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static boolean TestProyecto(String archivoOriginal) throws IOException{
        // El fuente se lee completo antes de escribir nada (el lector se cierra aquí)
        List<String> lineas = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(archivoOriginal))) {
            // Misma lectura que antes (readLine), para copiar el mismo número de líneas
            String line = reader.readLine();
            while (line != null) {
                lineas.add(line);
                line = reader.readLine();
            }
        }

        // Fuente con un salto real tras cada línea (arreglo original del EOL)
        StringBuilder source = new StringBuilder();
        for (String line : lineas) {
            source.append(line).append("\n");
        }

        // Nombre del archivo de errores: sin carpetas ni extensión, en la carpeta actual
        String nombreErrores = NombrePrograma(archivoOriginal) + "-errores.txt";

        // El .a68 se calcula en un único lugar, compartido con main; no se escribe sobre el propio fuente
        Path fuente = Paths.get(archivoOriginal);
        Path a68 = Paths.get(RutaA68(archivoOriginal));
        if (!MismoArchivo(fuente, a68)) {
            try (FileWriter archivoAlgol = new FileWriter(a68.toString())) {
                for (String line : lineas) {
                    archivoAlgol.write(line + "\n");
                }
            }
        }

        // Estado del evaluador limpio; parser y evaluador corren siempre (decisión 4)
        Evaluator.Reiniciar();
        Lexer lexer = new Lexer(source.toString());
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        Environment env = new Environment();
        Evaluator.Evaluate(program, env);

        // Mensajes del parser y luego del evaluador, escritos bajo su línea
        List<Mensaje> mensajes = new ArrayList<>(parser.GetMensajes());
        mensajes.addAll(Evaluator.GetMensajes());
        ArchivoErrores.Escribir(nombreErrores, lineas, mensajes);

        // Válido si y solo si ningún mensaje es error (las advertencias se permiten)
        for (Mensaje m : mensajes) {
            if (m.EsError()) {
                return false;
            }
        }
        return true;
    }

    // Nombre del programa: nombre del archivo sin carpetas ni la última extensión
    private static String NombrePrograma(String ruta) {
        String nombre = Paths.get(ruta).getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        return (punto > 0) ? nombre.substring(0, punto) : nombre;
    }

    // Verdadero si el .a68 es el mismo archivo que el fuente (p. ej. un fuente x.a68)
    private static boolean MismoArchivo(Path fuente, Path a68) throws IOException {
        return Files.exists(a68) && Files.isSameFile(fuente, a68);
    }
    
    // Ruta del .a68: misma carpeta, nombre sin la última extensión + ".a68" (sin importar mayúsculas)
    private static String RutaA68(String ruta) {
        Path p = Paths.get(ruta);
        String nombre = p.getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        String base = (punto > 0) ? nombre.substring(0, punto) : nombre;
        return p.resolveSibling(base + ".a68").toString();
    }

    // Ejecuta a68g en esta consola, sin shell: cada argumento va separado y se toma literal
    private static void EjecutarA68g(String rutaA68) {
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
            System.exit(1);
        }
        // Ruta absoluta: el mismo archivo que escribió TestProyecto, y no se confunde con una opción
        String archivo = Paths.get(rutaA68).toAbsolutePath().toString();
        try {
            // Exactamente dos elementos de comando, misma consola, y se espera a que termine
            Process proceso = new ProcessBuilder(a68g.toString(), archivo).inheritIO().start();
            proceso.waitFor();
        } catch (IOException ex) {
            // Mensaje claro sin traza
            System.err.println("No se pudo iniciar a68g (" + a68g + "): " + ex.getMessage());
            System.exit(1);
        } catch (InterruptedException ex) {
            // Se conserva el estado de interrupción
            Thread.currentThread().interrupt();
            System.err.println("Se interrumpió la espera de a68g.");
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