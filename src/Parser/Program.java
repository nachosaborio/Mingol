package Parser;

import java.util.ArrayList;

public class Program implements ASTNode{

    private Statement[] statements;
    
    public Program(Statement[] statements) {
        this.statements = statements;
    }
    
    @Override
    public String TokenLiteral() {
        if(statements.length > 0){
            return statements[0].TokenLiteral();
        }
        else{
            return "";
        }
    }

    @Override
    public String Str() {
        String strings = "";
        for(Statement statement : statements){
            strings += statements.toString();
        }
        return strings;
    }
}
