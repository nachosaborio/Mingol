package tarea2;

import java.util.ArrayList;

public class Tarea2 {

    public static void main(String[] args) {
        TestTwoCharacterOperator();
    }

    private static void Test() {
        //ILLEGAL
        //String source = "¡¿@";

        //One character operator
        //String source = "+=";
        //EOF
        String source = "¡¿@(),;";

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
        for (int i = 0; i < 16; i++) {
            tokens.add(lexer.NextToken());
        }
        for (Token token : tokens) {
            System.out.println(token.Tuple());
        }
    }
}
