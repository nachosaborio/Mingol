package Parser;

import java.util.ArrayList;

import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;

public class Parser {

    private Lexer lexer;
    private Token currentToken;
    private Token peekToken;
    private ArrayList<String> errors;

    public Parser(Lexer lexer, Token currentToken, Token peekToken) {
        this.lexer = lexer;
        this.errors = new ArrayList<String>();
        AdvanceToken();
        AdvanceToken();
    }
    
    public ArrayList<String> GetErrors(){
        return errors;
    }
    
    public Program ParseProgram(){
        Program program = new Program(new ArrayList<Statement>());

        while(currentToken.getTokenType() != MingolToken.EOL){
            Statement statement = ParseSatement();
            if(statement != null){
                program.statements.add(statement);
            }
            AdvanceToken();
        }

        return program;
    }

    private void AdvanceToken(){
        currentToken = peekToken;
        peekToken = lexer.NextToken();
    }

    private boolean ExpectedToken(MingolToken token){
        if(peekToken.getTokenType() == token){
            AdvanceToken();
            return true;
        }
        ExpectedTokenError(token);
        return false;
    }

    private void ExpectedTokenError(MingolToken token){
        String error = "Se esperaba un token de tipo " + token 
                + " pero se obtuvo un " + peekToken.getTokenType();
        errors.add(error);
    }
    
    private Statement ParseSatement(){
        if(currentToken == null){
            return null;
        }
        else{
            if(currentToken.getTokenType() == MingolToken.TYPEINT){
                return ParseLetStatement();
            }
            else{
                return null;
            }
        }
    }

    private LetStatement ParseLetStatement(){
        if(currentToken == null){
            return null;
        }
        else{
            LetStatement letStatement = new LetStatement(currentToken);

            if(!ExpectedToken(MingolToken.IDENT)){
                return null;
            }
            letStatement.SetName(new Identifier(currentToken, currentToken.getLiteral()));

            if(!ExpectedToken(MingolToken.ASSIGN)){
                return null;
            }

            //TODO terminar cuando sepamos parsear expresiones
            while(currentToken.getTokenType() != MingolToken.SEMICOLON){
                AdvanceToken();
            }
            return letStatement;
        }
    }
}
