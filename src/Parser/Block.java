package Parser;

import Lexer.Token;
import java.util.ArrayList;

public class Block extends Statement{
    ArrayList<Statement> statements;

    public Block(Token token, ArrayList<Statement> statements) {
        super(token);
        this.statements = statements;
    }

    public ArrayList<Statement> getStatements() {
        return statements;
    }

    public void setStatements(ArrayList<Statement> statements) {
        this.statements = statements;
    }
    
    public void AddStatement(Statement statement){
        statements.add(statement);
    }
    
    @Override
    public String toString(){
        String out = "";
        
        for(Statement statement : statements){
            out += statement.toString();
        }
        
        return out;
    }
}
