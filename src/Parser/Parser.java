package Parser;

import java.util.ArrayList;

import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Funciones.ParseIdentifier;
import Parser.Funciones.ParseInteger;
import Parser.Interfaces.IInfixParseFn;
import Parser.Interfaces.IPrefixParseFn;
import java.util.HashMap;
import java.util.Map;

public class Parser {

    private Lexer lexer;
    private Token currentToken;
    private Token peekToken;
    private ArrayList<String> errors;
    private HashMap<MingolToken, IPrefixParseFn> prefixParseFns;
    private HashMap<MingolToken, IInfixParseFn> infixParseFns;

    //PrefixParseFn = Callable[[], Optional[Expression]]
    //InfixParseFn = Callable[[Expression], Optional[Expression]]
    //PrefixParseFns = Dict[TokenType, PrefixParseFn]
    //InfixParseFns = Dict[TokenType, InfixParseFn]
    public Parser(Lexer lexer, Token currentToken, Token peekToken) {
        this.lexer = lexer;
        this.errors = new ArrayList<String>();
        prefixParseFns = RegisterPrefixFns();
        infixParseFns = RegisterInfixFns();

        AdvanceToken();
        AdvanceToken();
    }

    public Parser(Lexer lexer) {
        this(lexer, null, null);
    }

    public ArrayList<String> GetErrors() {
        return errors;
    }

    public Program ParseProgram() {
        Program program = new Program(new ArrayList<Statement>());

        while (currentToken.getTokenType() != MingolToken.EOL) {
            Statement statement = ParseSatement();
            if (statement != null) {
                program.statements.add(statement);
            }
            AdvanceToken();
        }

        return program;
    }

    private void AdvanceToken() {
        currentToken = peekToken;
        peekToken = lexer.NextToken();
    }

    private boolean ExpectedToken(MingolToken token) {
        if (peekToken.getTokenType() == token) {
            AdvanceToken();
            return true;
        }
        ExpectedTokenError(token);
        return false;
    }

    private void ExpectedTokenError(MingolToken token) {
        String error = "Se esperaba un token de tipo " + token
                + " pero se obtuvo un " + peekToken.getTokenType();
        errors.add(error);
    }

    private Statement ParseSatement() {
        assert currentToken != null :
                "El current token es null";
        if (currentToken.getTokenType() == MingolToken.TYPEINT) {
            return ParseLetStatement();
        } else if (currentToken.getTokenType() == MingolToken.RETURN) {
            return ParseReturnStatement();
        } else {
            return ParseExpressionStatement();
        }
    }

    private LetStatement ParseLetStatement() {
        assert currentToken != null :
                "El current token es null";
        LetStatement letStatement = new LetStatement(currentToken);

        if (!ExpectedToken(MingolToken.IDENT)) {
            return null;
        }
        letStatement.SetName((Identifier) new ParseIdentifier().Function());

        if (!ExpectedToken(MingolToken.ASSIGN)) {
            return null;
        }

        //TODO terminar cuando sepamos parsear expresiones
        while (currentToken.getTokenType() != MingolToken.SEMICOLON) {
            AdvanceToken();
        }
        return letStatement;
    }

    private ReturnStatement ParseReturnStatement() {
        assert currentToken != null :
                "El current token es null";
        ReturnStatement returnStatement = new ReturnStatement(currentToken);
        AdvanceToken();
        //TODO terminar cuando sepamos parsear expresiones
        while (currentToken.getTokenType() != MingolToken.SEMICOLON) {
            AdvanceToken();
        }
        return returnStatement;
    }

    private ExpressionStatement ParseExpressionStatement() {
        assert currentToken != null :
                "El current token es null";
        ExpressionStatement expressionStatement = new ExpressionStatement(currentToken);
        expressionStatement.setExpression(ParseExpression(Precedence.LOWEST));

        assert peekToken != null :
                "El peek token es null";

        if (peekToken.getTokenType() == MingolToken.SEMICOLON) {
            AdvanceToken();
        }

        return expressionStatement;
    }

    private Expression ParseExpression(Precedence precedence) {
        assert currentToken != null :
                "El current token es null";
        IPrefixParseFn prefixParseFn = prefixParseFns.get(currentToken.getTokenType());
        if (prefixParseFn == null) {
            return null;
        }
        prefixParseFn.setCurrentToken(currentToken);
        Expression leftExpression = prefixParseFn.Function();
        return leftExpression;
    }

    private HashMap<MingolToken, IPrefixParseFn> RegisterPrefixFns() {
        HashMap<MingolToken, IPrefixParseFn> functions = new HashMap<MingolToken, IPrefixParseFn>();
        functions.put(MingolToken.IDENT, new ParseIdentifier());
        functions.put(MingolToken.INTEGER, new ParseInteger()); //Agregar error de parseo
        return functions;
    }

    private HashMap<MingolToken, IInfixParseFn> RegisterInfixFns() {
        return new HashMap();
    }
}
