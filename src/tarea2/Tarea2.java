package tarea2;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class Tarea2 {

    public static void main(String[] args) {
//        String path = args[0];
        String path = "prueba.txt";
        TestTarea(path);
        //TestTwoCharacterOperator();
    }

    private static void TestTarea(String archivoOriginal) {
        try {
            boolean isBegin = false;
            boolean isEnd = false;
            boolean isComment = false;
            boolean errorBegin = false;
            int linea = 1;

            BufferedReader reader = new BufferedReader(new FileReader(archivoOriginal));
            //Crea resultado.txt
            FileWriter writer = new FileWriter(archivoOriginal + "-errores.txt");
            String line = reader.readLine();
            String error = "";

            while (line != null) {
                
                String source = line;
                if(line.length() > 80){
                    error += "\tError: las líneas no pueden sobrepasar los 80 caracteres.\n";
                }
                Lexer lexer = new Lexer(source);

loop:           for (int i = 0; i < source.length(); i++) {
                    Token tempToken = lexer.NextToken();
                    //Salta los comentarios
                    if (tempToken.getTokenType() != MingolToken.COMMENT && isComment) {
                        continue loop;
                    }
                    
                    //Revisa si no hay comandos antes del Begin
                    if(!isBegin
                            && tempToken.getTokenType() != MingolToken.BEGIN
                            && tempToken.getTokenType() != MingolToken.EOL 
                            && tempToken.getTokenType() != MingolToken.COMMENT
                            && !isComment 
                            && !errorBegin){
                        error += "\tError: No pueden haber comandos antes del Begin.\n";
                        errorBegin = true;
                    }

                    switch (tempToken.getTokenType()) {
                        case COMMENT:
                            isComment = !isComment;
                            break;
                        case BEGIN:
                            if (!isBegin) {
                                isBegin = true;
                            } else {
                                error += "\tError: no puede haber más de una sentencia Begin en el mismo archivo.\n";
                            }
                            break;
                        case EOL:
                            break loop;
                        case END:
                            break;
                    }
                } // Fin procesamiento de token
                writer.write(linea + "\t" + line + "\n");
                if (error != "") {
                    writer.write(error);
                }
                errorBegin = false;
                error = "";
                linea++;
                line = reader.readLine();
            }//Fin procesamiento de línea
            reader.close();
            writer.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    private static void Test() {
        //ILLEGAL
        //String source = "¡¿@";

        //One character operator
        //String source = "+=";
        //EOF
        String source = "";

        Lexer lexer = new Lexer(source);

        ArrayList<Token> tokens = new ArrayList<>();
        for (int i = 0; i < source.length() + 1; i++) {
            tokens.add(lexer.NextToken());
        }

        for (Token token : tokens) {
            System.out.println(token.Tuple());
        }
    }

    private static void TestAsignacion() {
        String source = "INT numero := 5;";
        Lexer lexer = new Lexer(source);
        ArrayList<Token> tokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tokens.add(lexer.NextToken());
        }
        for (Token token : tokens) {
            System.out.println(token.Tuple());
        }
    }

    private static void TestControlStatement() {
        String source = String.join("\n",
                "IF (5 > 4) THEN",
                "RETURN TRUE",
                "ELSE",
                "RETURN FALSE",
                "FI;");

        Lexer lexer = new Lexer(source);
        ArrayList<Token> tokens = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            tokens.add(lexer.NextToken());
        }
        for (Token token : tokens) {
            System.out.println(token.Tuple());
        }
    }

    private static void TestTwoCharacterOperator() {
        String source = String.join("\n",
                "numero := 5;",
                "5 /= 4;",
                "5 <= 4;",
                "5 >= 4;");

        Lexer lexer = new Lexer(source);
        ArrayList<Token> tokens = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tokens.add(lexer.NextToken());
        }
        for (Token token : tokens) {
            System.out.println(token.Tuple());
        }
    }
}
