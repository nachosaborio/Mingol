package Parser;

import Lexer.Lexer;

public class Parser {

    private Lexer lexer;
    
    public Parser(Lexer lexer) {
        this.lexer = lexer;
    }
    
    public Program ParseProgram(){
        Program program = new Program(new Statement[0]);
        return program;
    }
}
