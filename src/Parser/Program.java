package Parser;

import Parser.Interfaces.ASTNode;
import java.util.ArrayList;

public class Program implements ASTNode{

    public ArrayList<Statement> statements;
    
    public Program(ArrayList<Statement> statements) {
        this.statements = statements;
    }
    
    @Override
    public String TokenLiteral() {
        if(statements.size() > 0){
            return statements.get(0).TokenLiteral();
        }
        else{
            return "";
        }
    }

    @Override
    public String toString() {
        String strings = "";
        for(Statement statement : statements){
            strings += statement.toString();
        }
        return strings;
    }
}
