package Parser;

import java.util.ArrayList;

import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
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
    private final HashMap<MingolToken, Precedence> PRECEDENCES = new HashMap<MingolToken, Precedence>() {
        {
            put(MingolToken.EQ, Precedence.EQUALS);
            put(MingolToken.NE, Precedence.EQUALS);
            put(MingolToken.LT, Precedence.LESSGREATER);
            put(MingolToken.GT, Precedence.LESSGREATER);
            put(MingolToken.LE, Precedence.LESSGREATER);
            put(MingolToken.GE, Precedence.LESSGREATER);
            put(MingolToken.ADDITION, Precedence.SUM);
            put(MingolToken.SUBSTRACTION, Precedence.SUM);
            put(MingolToken.DIVISION, Precedence.PRODUCT);
            put(MingolToken.MULTIPLICATION, Precedence.PRODUCT);
        }
    };

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

    public void AdvanceToken() {
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
        letStatement.SetName((Identifier) prefixParseFns.get(MingolToken.IDENT).Function());

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
            String mensaje = "No se encontró ninguna función para parsear " + currentToken.getLiteral();
            errors.add(mensaje);
            return null;
        }
        Expression leftExpression = prefixParseFn.Function();
        
        assert peekToken != null:
                "Peek token es null";
        
        while((peekToken.getTokenType() != MingolToken.SEMICOLON) 
                && (precedence.ordinal() < PeekPrecedence().ordinal())){
            IInfixParseFn infixParseFn = infixParseFns.get(peekToken.getTokenType());
            
            if(infixParseFn == null){
                return leftExpression;
            }
            
            AdvanceToken();
            assert leftExpression != null:
                    "left expression es null";
            leftExpression = infixParseFn.Function(leftExpression);
        }
        return leftExpression;
    }
    
    private Precedence CurrentPrecedence(){
        assert currentToken != null:
                "current token es null";
        Precedence precedence = PRECEDENCES.get(currentToken.getTokenType());
        if(precedence == null){
            return Precedence.LOWEST;
        }
        return precedence;
    }
    
    private Precedence PeekPrecedence(){
        assert peekToken != null:
                "Peek token es null";
        Precedence precedence = PRECEDENCES.get(peekToken.getTokenType());
        if(precedence == null){
            return Precedence.LOWEST;
        }
        return precedence;
    }

    private HashMap<MingolToken, IPrefixParseFn> RegisterPrefixFns() {
        //Creación de las funciones
        //Parse identifier
        IPrefixParseFn ParseIdentifier = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null:
                        "Current token es null";
                return new Identifier(currentToken, currentToken.getLiteral());
            }
        };

        //Parse Integer
        IPrefixParseFn ParseInteger = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null:
                        "Current token es null";
                Integral integral = new Integral(currentToken);
                try {
                    integral.setToken(currentToken);
                    integral.setValue(Integer.parseInt(currentToken.getLiteral()));
                } catch (ClassCastException e) {
                    System.err.println("Conversion invalida a entero: " + currentToken.getLiteral());
                    return null;
                }
                return integral;
            }
        };

        //Prefix expressions
        IPrefixParseFn ParsePrefixExpressions = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null:
                        "Current token es null";
                Prefix prefixExpression = new Prefix(currentToken, currentToken.getLiteral());
                AdvanceToken();
                prefixExpression.setRight(ParseExpression(Precedence.PREFIX));
                return prefixExpression;
            }
        };
        
        //Parse boolean
        IPrefixParseFn ParseBoolean = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null:
                        "El current token es null";
                return new Booleano(currentToken, currentToken.getTokenType() == MingolToken.TRUE);
            }
        };
        
        //Parse grouped expression
        IPrefixParseFn ParseGroupedExpression = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                AdvanceToken();
                Expression expression = ParseExpression(Precedence.LOWEST);
                
                if(!ExpectedToken(MingolToken.RPAREN)){
                    return null;
                }
                return expression;
            }
        };

        //Se agregan las funciones
        HashMap<MingolToken, IPrefixParseFn> functions = new HashMap<MingolToken, IPrefixParseFn>();
        functions.put(MingolToken.IDENT, ParseIdentifier);
        functions.put(MingolToken.INTEGER, ParseInteger);
        functions.put(MingolToken.SUBSTRACTION, ParsePrefixExpressions);
        functions.put(MingolToken.NEGATION, ParsePrefixExpressions);
        functions.put(MingolToken.TRUE, ParseBoolean);
        functions.put(MingolToken.FALSE, ParseBoolean);
        functions.put(MingolToken.LPAREN,ParseGroupedExpression);
        return functions;
    }

    private HashMap<MingolToken, IInfixParseFn> RegisterInfixFns() {
        //Funcion
        IInfixParseFn ParseInfixExpression = new IInfixParseFn() {
            @Override
            public Expression Function(Expression expression) {
                assert currentToken != null :
                        "El current token es null";
                Infix infix = new Infix(currentToken, expression, currentToken.getLiteral());
                Precedence precedence = CurrentPrecedence();
                AdvanceToken();
                infix.setRight(ParseExpression(precedence));
                return infix;
            }
        };

        //Se agregan las funciones
        HashMap<MingolToken, IInfixParseFn> functions = new HashMap<MingolToken, IInfixParseFn>();
        functions.put(MingolToken.EQ, ParseInfixExpression);
        functions.put(MingolToken.NE, ParseInfixExpression);
        functions.put(MingolToken.LT, ParseInfixExpression);
        functions.put(MingolToken.GT, ParseInfixExpression);
        functions.put(MingolToken.LE, ParseInfixExpression);
        functions.put(MingolToken.GE, ParseInfixExpression);
        functions.put(MingolToken.ADDITION, ParseInfixExpression);
        functions.put(MingolToken.SUBSTRACTION, ParseInfixExpression);
        functions.put(MingolToken.DIVISION, ParseInfixExpression);
        functions.put(MingolToken.MULTIPLICATION, ParseInfixExpression);
        return functions;
    }
}
