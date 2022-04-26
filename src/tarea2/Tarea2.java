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

import Parser.Parser;
import Parser.Program;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Expression;
import Parser.ExpressionStatement;
import Parser.Identifier;
import Parser.Integral;
import Parser.LetStatement;
import Parser.Statement;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class Tarea2 {

    public static void main(String[] args) {
        //  try {
        // String path = args[0];
        // String parts[] = path.split("\\.");
        // if (parts.length == 1) {
        //     path += ".mingol";
        // }
        // //String path = "prueba.mingol";
        // if(TestTarea(path)){
        //     String rutaArchivo = Paths.get("").toAbsolutePath().toString();
        //     rutaArchivo += "//" + path.replace("mingol", "a68");
        //     Runtime runtime = Runtime.getRuntime();
        //     Runtime.getRuntime().exec("cmd /c \"start cmd /k"+ "C:\\Algol\\a68g.exe " + path.replace("mingol", "a68"));
        // }
        // } catch (IOException e) {
        //     System.out.println(e.getMessage());
        // }
        TestIntegerExpression();
    }

    private static void TestProgramStatements(Parser parser, Program program, Integer expectedStatementCount) {
        expectedStatementCount = expectedStatementCount != null ? expectedStatementCount : 1;

        assert parser.GetErrors().size() == 0 : "El parser tiene errores.";
        assert program.statements.size() == expectedStatementCount : 
                "La cantidad de statements es diferente a la esperada";
        assert program.statements.get(0) instanceof ExpressionStatement : 
                "El primer statement no es un ExpressionStatement";
    }
    
    private static void TestLiteralExpression(Expression expression, Object expectedValue){
        if(expectedValue instanceof String){
            TestIdentifier(expression, expectedValue);
        }
        else if(expectedValue instanceof Integer){
            TestInteger(expression, expectedValue);
        }
        else{
            System.err.println("Tipo de elemento no soportado, se obtuvo: " + expectedValue.getClass().getSimpleName());
        }
    }
    
    private static void TestIdentifier(Expression expression, Object expectedValue){
        assert expression instanceof Identifier :
                "La expresion no es un Identifier";
        Identifier identifier = (Identifier) expression;
        assert identifier.getValue().equals(expectedValue) :
                "El valor del identifier no coincide con el valor esperado";
        assert identifier.getToken().getLiteral().equals(expectedValue):
                "La literal del identifier no coincide con el valor esperado";
    }
    
    private static void TestInteger(Expression expression, Object expectedValue){
        assert expression instanceof Integral:
                "La expresion no es un Integer";
        Integral integral = (Integral) expression;
        assert integral.getValue() == (Integer)expectedValue:
                "El valor del integer no coincide con el valor esperado";
        assert integral.getToken().getLiteral().equals(expectedValue.toString()):
                "La literal del integer no coincide con el valor esperado";
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

    private static void TestParser() {
        String source = "STRING x := 5;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer, null, null);
        Program program = parser.ParseProgram();
    }

    private static void TestLetStatement() {
        String source = "INT x := 5;\nINT y := 10;\nINT z := 20;";
        Lexer lexer = new Lexer(source);

        Parser parser = new Parser(lexer, null, null);
        Program program = parser.ParseProgram();
    }

    private static void TestParseErrors() {
        String source = "INT x 5;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer, null, null);
        Program program = parser.ParseProgram();
        System.out.println(parser.GetErrors());
    }

    private static void TestReturnStatement() {
        String source = "return 5;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer, null, null);
        Program program = parser.ParseProgram();
        System.out.println(parser.GetErrors());
    }

    private static void ASTTestLet() {
        Program program = new Program(new ArrayList<Statement>(
                Arrays.asList(
                        new LetStatement(
                                new Token(MingolToken.TYPEINT, "INT"),
                                new Identifier(
                                        new Token(MingolToken.IDENT, "mi_var"),
                                        "mi_var"
                                ),
                                new Identifier(
                                        new Token(MingolToken.IDENT, "otra_var"),
                                        "otra_var"
                                )
                        )
                )
        ));
        System.out.println(program.Str());
    }

    private static void ASTTestReturn() {
        Program program = new Program(new ArrayList<Statement>(
                Arrays.asList(
                        new LetStatement(
                                new Token(MingolToken.RETURN, "return"),
                                new Identifier(
                                        new Token(MingolToken.IDENT, "mi_var"),
                                        "mi_var"
                                )
                        )
                )
        ));
        System.out.println(program.Str());
    }

    private static void TestIdentifierExpression() {
        String source = "foobar;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();

        TestProgramStatements(parser, program, null);
        ExpressionStatement expressionStatement = (ExpressionStatement) program.statements.get(0);
        
        assert expressionStatement.getExpression() != null:
                "La expresion es nula";
        TestLiteralExpression(expressionStatement.getExpression(), "foobar");
    }
    
    private static void TestIntegerExpression(){
        String source = "5;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        
        TestProgramStatements(parser, program, null);
        ExpressionStatement expressionStatement = (ExpressionStatement) program.statements.get(0);
        assert expressionStatement != null :
                "La expresion es nula";
        TestLiteralExpression(expressionStatement.getExpression(), 5);
    }
}
