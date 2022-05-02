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

import Evaluador.Cadena;
import Evaluador.Entero;
import Evaluador.Environment;
import Evaluador.Errado;
import Evaluador.Evaluator;
import Evaluador.Logico;
import Evaluador.ObjectType;
import Evaluador.Objeto;
import Parser.Parser;
import Parser.Program;
import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Block;
import Parser.Booleano;
import Parser.Call;
import Parser.Expression;
import Parser.ExpressionStatement;
import Parser.Identifier;
import Parser.If;
import Parser.Infix;
import Parser.Integral;
import Parser.LetStatement;
import Parser.Prefix;
import Parser.Statement;
import Parser.StringLiteral;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

//TODO revisar qué pasa si 3 >
//TODO implementar comentarios
//TODO size = INT

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
        //TestStringEvaluator();
        REPL();
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
    
    private static void TestProgramStatements(Parser parser, Program program, Integer expectedStatementCount) {
        expectedStatementCount = expectedStatementCount != null ? expectedStatementCount : 1;

        if (parser.GetErrors().size() > 0) {
            System.out.println(parser.GetErrors());
        }

        assert parser.GetErrors().size() == 0 : "El parser tiene errores.";
        assert program.statements.size() == expectedStatementCount :
                "La cantidad de statements es diferente a la esperada";
        assert program.statements.get(0) instanceof ExpressionStatement :
                "El primer statement no es un ExpressionStatement";
    }

    private static void TestLiteralExpression(Expression expression, Object expectedValue) {
        if (expectedValue instanceof String) {
            TestIdentifier(expression, expectedValue);
        } else if (expectedValue instanceof Integer) {
            TestInteger(expression, expectedValue);
        } else if (expectedValue instanceof Boolean) {
            TestBoolean(expression, expectedValue);
        } else {
            System.err.println("Tipo de elemento no soportado, se obtuvo: " + expectedValue.getClass().getSimpleName());
        }
    }

    private static void TestIdentifier(Expression expression, Object expectedValue) {
        assert expression instanceof Identifier :
                "La expresion no es un Identifier";
        Identifier identifier = (Identifier) expression;
        assert identifier.getValue().equals(expectedValue) :
                "El valor del identifier no coincide con el valor esperado";
        assert identifier.TokenLiteral().equals(expectedValue) :
                "La literal del identifier no coincide con el valor esperado";
    }

    private static void TestInteger(Expression expression, Object expectedValue) {
        assert expression instanceof Integral :
                "La expresion no es un Integer";
        Integral integral = (Integral) expression;
        assert integral.getValue() == (Integer) expectedValue :
                "El valor del integer no coincide con el valor esperado";
        assert integral.getToken().getLiteral().equals(expectedValue.toString()) :
                "La literal del integer no coincide con el valor esperado";
    }

    private static void TestBoolean(Expression expression, Object expectedValue) {
        assert expression instanceof Booleano :
                "La expresion no es un booleano";

        Booleano booleano = (Booleano) expression;

        assert booleano.getValue() == (Boolean) expectedValue :
                "El valor del booleano no coincide con el valor esperado";

        if ((Boolean) expectedValue == true) {
            assert booleano.TokenLiteral().equals("TRUE") :
                    "La literal del booleano no coincide con el valor esperado";
        } else {
            assert booleano.TokenLiteral().equals("FALSE") :
                    "La literal del booleano no coincide con el valor esperado";
        }
    }

    private static void TestInfixExpression(Expression expression, Object expectedLeft,
            String expectedOperator, Object expectedRight) {
        Infix infix = (Infix) expression;

        assert infix.getLeft() != null :
                "La izquierda es nula";
        TestLiteralExpression(infix.getLeft(), expectedLeft);

        assert infix.getOperator().equals(expectedOperator) :
                "el operador no es igual al operador esperado";
        assert infix.getRight() != null;
    }
    
    private static Objeto EvaluateTests(String source){
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        Environment env = new Environment();
        
        Objeto evaluated = Evaluator.Evaluate(program, env);
        
        assert evaluated != null:
                "La evaluacion fue nula";
        
        return evaluated;
    }
    
    private static void TestIntegerObject(Objeto evaluated, int expected){
        assert evaluated instanceof Entero:
                "No es un entero";
        Entero entero = (Entero) evaluated;
        assert entero.getValue() == expected:
                "No son iguales";
    }
    
    private static void EvaluateBooleanObject(Objeto evaluated, boolean expected){
        assert evaluated instanceof Logico:
                "No es un booleano";
        Logico logico = (Logico) evaluated;
        assert logico.getValue() == expected:
                "No son iguales";
    }
    
    private static void TestNullObject(Objeto evaluated){
        assert evaluated.equals(Evaluator.NULL);
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
    
    private static void TestString(){
        String source = "\"foo\"; \"Platzi es la mejor escuela de CS\";";
        Lexer lexer = new Lexer(source);
        ArrayList<Token> tokens = new ArrayList<Token>();
        
        for(int i = 0; i < 4; i++){
            tokens.add(lexer.NextToken());
        }
        
        Token[] expectedTokens = {
            new Token(MingolToken.STRING, "foo"),
            new Token(MingolToken.SEMICOLON, ";"),
            new Token(MingolToken.STRING, "Platzi es la mejor escuela de CS"),
            new Token(MingolToken.SEMICOLON, ";")
        };
        
        for(int i = 0; i < 4; i++){
            assert tokens.get(i).Tuple().equals(expectedTokens[i].Tuple()):
                    "Los tokens no coinciden";
        }
    }
    
    public static void TestStringLiteralExpression(){
        String source = "\"Hello world!\"";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        
        ExpressionStatement expressionStatement = (ExpressionStatement) program.statements.get(0);
        StringLiteral stringLiteral = (StringLiteral) expressionStatement.getExpression();
        
        assert stringLiteral instanceof StringLiteral:
                "No es una instancia de string literal";
        assert stringLiteral.getValue().equals("Hello world!"):
                "El string no coincide";
    }
    
    public static void TestStringEvaluator(){
        String source = "\"Hello world\"";
        String expected = "Hello world";
        Objeto evaluated = EvaluateTests(source);
        
        assert evaluated instanceof Cadena:
                "No es una cadena";
        Cadena cadena = (Cadena) evaluated;
        assert cadena.getValue().equals(expected);
    }

    private static void TestParser() {
        String source = "STRING x := 5;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer, null, null);
        Program program = parser.ParseProgram();
    }

    private static void TestLetStatement() {
        String source = "INT w := 5;\nSTRING x := 10;\nCHAR y := 20;\nREAL z := 25;";
        Lexer lexer = new Lexer(source);

        Parser parser = new Parser(lexer, null, null);
        Program program = parser.ParseProgram();
        
        assert program.statements.size() == 4:
                "La cantidad de statements es incorrecta";
        HashMap<String, Integer> map = new HashMap<>();
        map.put("w", 5);
        map.put("x", 10);
        map.put("y", 20);
        map.put("z", 25);
        
        for(int i = 0; i < 4; i++){
            String expectedIdentifier = map.keySet().toArray()[i].toString();
            int expectedValue = map.get(expectedIdentifier);
            LetStatement statement = (LetStatement) program.statements.get(i);
            
            assert statement instanceof LetStatement:
                    "No es un let statement";
            
            assert statement.getName() != null:
                    "el name es null";
            TestIdentifier(statement.getName(), expectedIdentifier);
            
            assert statement.getValue() != null:
                    "el value es null";
            TestLiteralExpression(statement.getValue(), expectedValue);
            
        }
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
        System.out.println(program.toString());
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
        System.out.println(program.toString());
    }

    private static void TestIdentifierExpression() {
        String source = "foobar;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();

        TestProgramStatements(parser, program, null);
        ExpressionStatement expressionStatement = (ExpressionStatement) program.statements.get(0);

        assert expressionStatement.getExpression() != null :
                "La expresion es nula";
        TestLiteralExpression(expressionStatement.getExpression(), "foobar");
    }

    private static void TestIntegerExpression() {
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

    private static void TestPrefixExpression() {
        String source = "!5;-15;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        TestProgramStatements(parser, program, 2);

        HashMap<String, Integer> mapa = new HashMap<String, Integer>();
        mapa.put("!", 5);
        mapa.put("-", 15);
        for (int i = 0; i < 2; i++) {
            Object key = mapa.keySet().toArray()[i];
            String expectedOperator = key.toString();
            int expectedValue = mapa.get(key);
            ExpressionStatement statement = (ExpressionStatement) program.statements.get(i);

            assert statement.getExpression() instanceof Prefix :
                    "la expresion no es un prefijo";

            Prefix prefix = (Prefix) statement.getExpression();

            assert prefix.getOperator().equals(expectedOperator) :
                    "el prefijo no es igual al operador esperado";
            assert prefix.getRight() != null :
                    "la derecha es nula";
            TestLiteralExpression(prefix.getRight(), expectedValue);
        }
    }

    private static void TestInfixOperator() {
        String source
                = "5 + 5;"
                + "5 - 5;"
                + "5 * 5;"
                + "5 / 5;"
                + "5 > 5;"
                + "5 < 5;"
                + "5 = 5;"
                + "5 /= 5;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();

        TestProgramStatements(parser, program, 8);
        ArrayList<Triple<Integer, String, Integer>> expectedOperatorsAndValues
                = new ArrayList<Triple<Integer, String, Integer>>();

        expectedOperatorsAndValues.add(new Triple<>(5, "+", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, "-", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, "*", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, "/", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, ">", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, "<", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, "=", 5));
        expectedOperatorsAndValues.add(new Triple<>(5, "/=", 5));

        for (int i = 0; i < 1; i++) {
            int expectedLeft = expectedOperatorsAndValues.get(i).getFirst();
            String expectedOperator = expectedOperatorsAndValues.get(i).getSecond();
            int expectedRight = expectedOperatorsAndValues.get(i).getThird();
            ExpressionStatement statement = (ExpressionStatement) program.statements.get(i);

            assert statement.getExpression() != null :
                    "La expresión es null";

            assert statement.getExpression() instanceof Infix :
                    "la expresion no es un prefijo";
        }
    }

    private static void TestBooleanExpression() {
        String source = "TRUE;FALSE;";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();

        TestProgramStatements(parser, program, 2);
        boolean[] expectedValues = {true, false};

        for (int i = 0; i < 2; i++) {
            ExpressionStatement statement = (ExpressionStatement) program.statements.get(i);
            boolean expectedValue = expectedValues[i];

            assert statement.getExpression() != null :
                    "La expresión es null";
            TestLiteralExpression(statement.getExpression(), expectedValue);

        }
    }

    private static void TestOperatorPrecedence() {
        ArrayList<Triple<String, String, Integer>> testSources
                = new ArrayList<Triple<String, String, Integer>>();
//        testSources.add(new Triple<>("-a * b;", "((-a)*b)", 1));
//        testSources.add(new Triple<>("!-a;", "(!(-a))", 1));
//        testSources.add(new Triple<>("a + b / c", "(a+(b/c))", 1));
//        testSources.add(new Triple<>("3 + 4; -5 * 5;", "(3+4)((-5)*5)", 2));
//        testSources.add(new Triple<>("1 + (2 + 3) + 4;", "((1+(2+3))+4)", 1));
//        testSources.add(new Triple<>("(5 + 5) * 2;", "((5+5)*2)", 1));
//        testSources.add(new Triple<>("-(5 + 5);", "(-(5+5))", 1));
//        testSources.add(new Triple<>("-(5 + 5);", "(-(5+5))", 1));
        testSources.add(new Triple<>("print(a);", "(print((a)))",1));
        testSources.add(new Triple<>("read(b);", "(read((b)))",1));

        for (Triple<String, String, Integer> triple : testSources) {
            Lexer lexer = new Lexer(triple.getFirst());
            Parser parser = new Parser(lexer);
            Program program = parser.ParseProgram();

            TestProgramStatements(parser, program, triple.getThird());
            String value = program.toString();
            assert program.toString().equals(triple.getSecond()) :
                    "El resultado no es el mismo";
        }
    }
    
    private static void TestCallExpresion(){
        String source = "print(a,b)";
        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        
        TestProgramStatements(parser, program, null);
        Call call = (Call)((ExpressionStatement) program.statements.get(0)).getExpression(); 
        
        assert call instanceof Call:
                "call no es un call";
        TestIdentifier(call.getFunction(), "print");
        
        assert call.getArguments().size() == 2:
                "El numero de argumentos es diferente a lo esperado";
        TestLiteralExpression(call.getArguments().get(0), "a");
        TestLiteralExpression(call.getArguments().get(1), "b");
        
    }

    private static void TestIfExpression() {
        String source = "IF (x < y ) THEN\n"
//                + "z\n"
//                + "ELSE\n"
//                + "ae\n"
                + "FI;";

        Lexer lexer = new Lexer(source);
        Parser parser = new Parser(lexer);
        Program program = parser.ParseProgram();
        TestProgramStatements(parser, program, null);

        If ifExpression = (If) ((ExpressionStatement) program.statements.get(0)).getExpression();
        assert ifExpression instanceof If :
                "La expresion no es un if";

        assert ifExpression.getCondition() != null :
                "La expresión es null";
        TestInfixExpression(ifExpression.getCondition(), "x", "<", "y");

        assert ifExpression.getConsequence() != null :
                "La consecuencia es null";
        assert ifExpression.getConsequence() instanceof Block :
                "La consecuencia no es un block";
        assert ifExpression.getConsequence().getStatements().size() == 1:
                "No hay consecuencias";

        ExpressionStatement consequenceStatement = (ExpressionStatement) ifExpression.getConsequence().getStatements().get(0);
        assert consequenceStatement.getExpression() != null :
                "El statement de la consecuencia es null";
        TestIdentifier(consequenceStatement.getExpression(), "z");

        //Alternartiva
        if (ifExpression.getAlternative() != null) {
            ExpressionStatement alternativeStatement = (ExpressionStatement) ifExpression.getAlternative().getStatements().get(0);
            TestIdentifier(alternativeStatement.getExpression(), "ae");
        }
    }
    
    private static void TestIntegerEvaluator(){
        HashMap<String, Integer> tests = new HashMap<String, Integer>();
        tests.put("5", 5);
        tests.put("10", 10);
        tests.put("-5", -5);
        tests.put("-10", -10);
        tests.put("5 + 5", 10);
        tests.put("5 - 10", -5);
        tests.put("2 * 2 * 2 * 2", 16);
        tests.put("2 * 5 - 3", 7);
        tests.put("50 / 2", 25);
        tests.put("2 * (5 - 3)", 4);
        tests.put("(2 + 7) / 3", 3);
        tests.put("50 / 2 * 2 + 10", 60);
        tests.put("5 / 2", 2);
        
        for(Map.Entry<String,Integer> entry : tests.entrySet()){
            Objeto evaluated = EvaluateTests(entry.getKey());
            TestIntegerObject(evaluated, entry.getValue());
        }
    }
    
    private static void TestBooleanEvaluator(){
        var tests = new HashMap<String, Boolean>();
        tests.put("TRUE", true);
        tests.put("FALSE", false);
        tests.put("1 < 2", true);
        tests.put("1 > 2", false); 
        tests.put("1 < 1", false);
        tests.put("1 > 1", false);
        tests.put("1 = 1", true);
        tests.put("1 /= 1", false);
        tests.put("1 /= 2", true);
        tests.put("TRUE = TRUE", true);
        tests.put("FALSE = FALSE", true);
        tests.put("TRUE = FALSE", false);
        tests.put("TRUE /= FALSE", true);
        tests.put("(1 < 2) = TRUE", true);
        tests.put("(1 < 2) = FALSE", false);
        tests.put("(1 > 2) = TRUE", false);
        tests.put("(1 > 2) = FALSE", true);
        
        for(Map.Entry<String, Boolean> entry : tests.entrySet()){
            Objeto evaluated = EvaluateTests(entry.getKey());
            
            EvaluateBooleanObject(evaluated, entry.getValue());
        }
    }
    
    private static void TestBangOperator(){
        var tests = new HashMap<String, Boolean>();
        tests.put("!TRUE", false);
        tests.put("!FALSE", true);
        tests.put("!!TRUE", true);
        tests.put("!!FALSE", false);
        
        for(Map.Entry<String, Boolean> entry : tests.entrySet()){
            Objeto evaluated = EvaluateTests(entry.getKey());
            
            EvaluateBooleanObject(evaluated, entry.getValue());
        }
    }
    
    private static void TestIfElseEvaluator(){
        var tests = new HashMap<String, Integer>();
        tests.put("IF(TRUE) THEN 10 FI", 10);
        tests.put("IF(FALSE) THEN 10 FI", null);
        tests.put("IF(1 < 2) THEN 10 FI", 10);
        tests.put("IF(1 > 2) THEN 10 FI", null); 
        tests.put("IF(1 < 2) THEN 10 ELSE 20 FI", 10);
        tests.put("IF(1 > 2) THEN 10 ELSE 20 FI", 20);
        
        for(Map.Entry<String, Integer> entry : tests.entrySet()){
            String source = entry.getKey();
            Integer expected = entry.getValue();
            Objeto evaluated = EvaluateTests(source);
            
            if(expected != null){
                TestIntegerObject(evaluated, expected);
            }
            else{
                TestNullObject(evaluated);
            }
        }
    }
    
    private static void TestErrorHandling(){
        var tests = new HashMap<String, String>();
        tests.put("5 + TRUE", "Discrepancia de tipos: INTEGERS + BOOLEAN");
        tests.put("5 + TRUE; 9", "Discrepancia de tipos: INTEGERS + BOOLEAN");
        tests.put("-TRUE", "Operador inválido: - BOOLEAN");
        tests.put("TRUE + FALSE", "Operador inválido: BOOLEAN + BOOLEAN");
        tests.put("5; TRUE - FALSE; 10;","Operador inválido: BOOLEAN - BOOLEAN");
        tests.put("IF (10 > 7) THEN TRUE + FALSE FI;", "Operador inválido: BOOLEAN + BOOLEAN");
        tests.put("IF (10 > 1) THEN IF (TRUE) THEN TRUE + FALSE FI; FI;", "Operador inválido: BOOLEAN + BOOLEAN");
        tests.put("foobar", "Identificador no encontrado: foobar");
        
        for(Map.Entry<String, String> entry : tests.entrySet()){
            String source = entry.getKey();
            String expected = entry.getValue();
            Objeto evaluated = EvaluateTests(source);
            
            assert evaluated instanceof Errado:
                    "No es un error";
            Errado error = (Errado) evaluated;
            assert error.getMessage().equals(expected):
                    "El error no es el esperado";
        }
    }
    
    private static void TestAssignmentEvaluator(){
        var tests = new HashMap<String, Integer>();
        tests.put("INT a := 5; a;", 5);
        tests.put("INT a := 5; 5 * a;", 25);
        tests.put("INT a := 5; INT b := a; b;",5);
        tests.put("INT a := 5; INT b := a; INT c := a + b + 5;c;",15);
        
        for(Map.Entry<String, Integer> entry : tests.entrySet()){
            String source = entry.getKey();
            int expected = entry.getValue();
            Objeto evaluated = EvaluateTests(source);
            TestIntegerObject(evaluated, expected);
        }
    }
}