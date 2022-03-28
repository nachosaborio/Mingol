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

package tarea2;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;

public class Tarea2 {

    public static void main(String[] args) {
         try {
        String path = args[0];
        String parts[] = path.split("\\.");
        if (parts.length == 1) {
            path += ".mingol";
        }
        //String path = "prueba.mingol";
        if(TestTarea(path)){
            String rutaArchivo = Paths.get("").toAbsolutePath().toString();
            rutaArchivo += "//" + path.replace("mingol", "a68");
            Runtime runtime = Runtime.getRuntime();
            Runtime.getRuntime().exec("cmd /c \"start cmd /k"+ "C:\\Algol\\a68g.exe " + path.replace("mingol", "a68"));
        }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    private static boolean TestTarea(String archivoOriginal) throws IOException{
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
loop:           for (int i = 0; i < source.length(); i++) {
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
            
            if(!hasErrors){
                return true;
            }
            else{
                return false;
            }
    }
}
