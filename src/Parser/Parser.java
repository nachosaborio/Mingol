package Parser;

import java.util.ArrayList;

import Lexer.Lexer;
import Lexer.MingolToken;
import Lexer.Token;
import Parser.Interfaces.IInfixParseFn;
import Parser.Interfaces.IPrefixParseFn;
import java.util.HashMap;

public class Parser {

    public boolean hasErrors = false;
    private boolean isEnd = false;
    private boolean isComment = false;
    private boolean errorBegin = false;
    private int linea = 1;

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
            put(MingolToken.LPAREN, Precedence.CALL);
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

        while (currentToken.getTokenType() != MingolToken.EOF) {
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
        String error = "\tError 204: Se esperaba un token de tipo " + token
                + " pero se obtuvo un " + peekToken.getTokenType();
        errors.add(error);
        hasErrors = true;
    }

    private Statement ParseSatement() {
        assert currentToken != null :
                "El current token es null";
        switch (currentToken.getTokenType()) {
            case TYPEINT:
            case TYPESTRING:
            case TYPECHAR:
            case TYPEREAL:
                return ParseLetStatement();
            case BEGIN:
                checkSemicolon(MingolToken.BEGIN);
                return new Statement(new Token(MingolToken.BEGIN, "BEGIN"));
            case END:
                checkSemicolon(MingolToken.BEGIN);
                return new Statement(new Token(MingolToken.END, "END"));
            case RETURN:
                return ParseReturnStatement();
            default:
                return ParseExpressionStatement();
        }
    }

    private void checkSemicolon(MingolToken mingol) {
        if (peekToken.getTokenType() != MingolToken.EOL
                && peekToken.getTokenType() != MingolToken.EOF) {
            errors.add("\tError 202: no puede haber nada en la línea luego del token " + mingol);
            hasErrors = true;
        }
        AdvanceToken();
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

        AdvanceToken();
        letStatement.SetValue(ParseExpression(Precedence.LOWEST));

        assert peekToken != null :
                "Peek token es null";

        if (peekToken.getTokenType().equals(MingolToken.SEMICOLON)) {
            AdvanceToken();
        }

        return letStatement;
    }

    private ReturnStatement ParseReturnStatement() {
        assert currentToken != null :
                "El current token es null";
        ReturnStatement returnStatement = new ReturnStatement(currentToken);
        AdvanceToken();
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
            String mensaje = "\tError 203: Token inválido: " + currentToken.getLiteral();
            errors.add(mensaje);
            hasErrors = true;
            return null;
        }
        Expression leftExpression = prefixParseFn.Function();

        assert peekToken != null :
                "Peek token es null";

        while ((peekToken.getTokenType() != MingolToken.SEMICOLON)
                && (precedence.ordinal() < PeekPrecedence().ordinal())) {
            IInfixParseFn infixParseFn = infixParseFns.get(peekToken.getTokenType());

            if (infixParseFn == null) {
                return leftExpression;
            }

            AdvanceToken();
            assert leftExpression != null :
                    "left expression es null";
            leftExpression = infixParseFn.Function(leftExpression);
        }
        return leftExpression;
    }

    private Block ParseBlock() {
        assert currentToken != null :
                "current token es null";
        Block block = new Block(currentToken, new ArrayList<>());

        AdvanceToken();

        while (currentToken.getTokenType() != MingolToken.FI
                && currentToken.getTokenType() != MingolToken.ELSE
                && currentToken.getTokenType() != MingolToken.OD
                && currentToken.getTokenType() != MingolToken.EOL) {
            Statement statement = ParseSatement();

            if (statement != null) {
                block.AddStatement(statement);
            }
            AdvanceToken();
        }
        return block;
    }

    private ArrayList<Expression> ParseCallArguments() {
        ArrayList<Expression> arguments = new ArrayList<>();
        assert peekToken != null :
                "El peek token es null";

        if (peekToken.getTokenType().equals(MingolToken.RPAREN)) {
            AdvanceToken();
            return arguments;
        }

        AdvanceToken();
        Expression expression = ParseExpression(Precedence.LOWEST);

        if (expression != null) {
            arguments.add(expression);
        }

        while (peekToken.getTokenType() == MingolToken.COMMA) {
            AdvanceToken();
            AdvanceToken();
            expression = ParseExpression(Precedence.LOWEST);
            if (expression != null) {
                arguments.add(expression);
            }
        }

        if (!ExpectedToken(MingolToken.RPAREN)) {
            return null;
        }
        return arguments;
    }

    private Precedence CurrentPrecedence() {
        assert currentToken != null :
                "current token es null";
        Precedence precedence = PRECEDENCES.get(currentToken.getTokenType());
        if (precedence == null) {
            return Precedence.LOWEST;
        }
        return precedence;
    }

    private Precedence PeekPrecedence() {
        assert peekToken != null :
                "Peek token es null";
        Precedence precedence = PRECEDENCES.get(peekToken.getTokenType());
        if (precedence == null) {
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
                assert currentToken != null :
                        "Current token es null";
                return new Identifier(currentToken, currentToken.getLiteral());
            }
        };

        //Parse Integer
        IPrefixParseFn ParseInteger = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
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

        IPrefixParseFn ParseReal = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "Current token es null";
                Decimal decimal = new Decimal(currentToken);
                try {
                    decimal.setValue(Double.parseDouble(currentToken.getLiteral()));
                } catch (ClassCastException e) {
                    System.err.println("Conversion invalida a entero: " + currentToken.getLiteral());
                    return null;
                }
                return decimal;
            }
        };

        //Prefix expressions
        IPrefixParseFn ParsePrefixExpressions = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
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
                assert currentToken != null :
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

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }
                return expression;
            }
        };

        //Parse if
        IPrefixParseFn ParseIf = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "current token es null";
                If ifExpression = new If(currentToken);
                if (!ExpectedToken(MingolToken.LPAREN)) {
                    return null;
                }

                AdvanceToken();
                ifExpression.setCondition(ParseExpression(Precedence.LOWEST));

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }

                if (!ExpectedToken(MingolToken.THEN)) {
                    return null;
                }

                checkSemicolon(MingolToken.THEN);
                ifExpression.setConsequence(ParseBlock());

                if (currentToken.getTokenType().equals(MingolToken.ELSE)) {
                    checkSemicolon(MingolToken.ELSE);
                    ifExpression.setAlternative(ParseBlock());
                }
//                if (! !ExpectedToken(MingolToken.FI)) {
//                    return null;
//                }

                return ifExpression;
            }
        };

        //Parse for
        IPrefixParseFn ParseFor = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "current token es null";
                For forExpression = new For(currentToken);

                //FOR
                if (!ExpectedToken(MingolToken.IDENT)) {
                    return null;
                }
                forExpression.setCiclo(new Identifier(currentToken, currentToken.getLiteral()));

                //FROM
                if (!ExpectedToken(MingolToken.FROM)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)
                        && !peekToken.getTokenType().equals(MingolToken.INTEGER)) {
                    ExpectedToken(MingolToken.INTEGER);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.IDENT)) {
                    forExpression.setMinima(new Identifier(currentToken, currentToken.getLiteral()));
                } else {
                    forExpression.setMinima(new Integral(currentToken, Integer.parseInt(currentToken.getLiteral())));
                }

                //BY
                if (!ExpectedToken(MingolToken.BY)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)
                        && !peekToken.getTokenType().equals(MingolToken.INTEGER)) {
                    ExpectedToken(MingolToken.INTEGER);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.IDENT)) {
                    forExpression.setIncremento(new Identifier(currentToken, currentToken.getLiteral()));
                } else {
                    forExpression.setIncremento(new Integral(currentToken, Integer.parseInt(currentToken.getLiteral())));
                }

                //TO
                if (!ExpectedToken(MingolToken.TO)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)
                        && !peekToken.getTokenType().equals(MingolToken.INTEGER)) {
                    ExpectedToken(MingolToken.INTEGER);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.IDENT)) {
                    forExpression.setMaxima(new Identifier(currentToken, currentToken.getLiteral()));
                } else {
                    forExpression.setMaxima(new Integral(currentToken, Integer.parseInt(currentToken.getLiteral())));
                }

                //DO
                if (!ExpectedToken(MingolToken.DO)) {
                    return null;
                }
                forExpression.setComands(ParseBlock());

                return forExpression;
            }
        };

        IPrefixParseFn ParseStringLiteral = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                assert currentToken != null :
                        "El current token es null";
                return new StringLiteral(currentToken, currentToken.getLiteral());
            }
        };

        IPrefixParseFn ParseRead = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Read read = new Read(currentToken);

                if (!ExpectedToken(MingolToken.LPAREN)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.IDENT)) {
                    ExpectedToken(MingolToken.IDENT);
                    return null;
                }
                AdvanceToken();
                read.setIdentificador(new Identifier(currentToken, currentToken.getLiteral()));

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }
                if (!ExpectedToken(MingolToken.SEMICOLON)) {
                    return null;
                }

                return read;
            }
        };

        IPrefixParseFn ParsePrint = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Print print = new Print(currentToken);

                if (!ExpectedToken(MingolToken.LPAREN)) {
                    return null;
                }

                if (!peekToken.getTokenType().equals(MingolToken.LPAREN)
                        && !peekToken.getTokenType().equals(MingolToken.NEWLINE)
                        && !peekToken.getTokenType().equals(MingolToken.STRING)) {
                    return null;
                }

                switch (peekToken.getTokenType()) {
                    case LPAREN:
                        AdvanceToken();
                        if (!ExpectedToken(MingolToken.IDENT)) {
                            return null;
                        }
                        print.AddIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
                        while (!peekToken.getTokenType().equals(MingolToken.RPAREN)) {
                            if (!ExpectedToken(MingolToken.COMMA)) {
                                return null;
                            }
                            if (!ExpectedToken(MingolToken.IDENT)) {
                                return null;
                            }
                            print.AddIdentificador(new Identifier(currentToken, currentToken.getLiteral()));
                        }
                        AdvanceToken();
                        break;
                    case NEWLINE:
                        print.setNewline(new Expression(currentToken));
                        AdvanceToken();
                        break;
                    case STRING:
                        print.setTexto(currentToken.getLiteral());
                        AdvanceToken();
                        break;
                }

                if (!ExpectedToken(MingolToken.RPAREN)) {
                    return null;
                }

                if (!ExpectedToken(MingolToken.SEMICOLON)) {
                    return null;
                }

                return print;
            }
        };

        IPrefixParseFn ParseSkip = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Skip skip = new Skip(currentToken);

                if (!peekToken.getTokenType().equals(MingolToken.SEMICOLON)
                        && !peekToken.getTokenType().equals(MingolToken.EOL)) {
                    ExpectedToken(MingolToken.SEMICOLON);
                    return null;
                }
                AdvanceToken();
                if (currentToken.getTokenType().equals(MingolToken.EOL)) {
                    if (!peekToken.getTokenType().equals(MingolToken.FI)
                            && peekToken.getTokenType().equals(MingolToken.OD)
                            && peekToken.getTokenType().equals(MingolToken.END)) {
                        ExpectedToken(MingolToken.SEMICOLON);
                        return null;
                    }
                    return skip;
                }
                else{
                    AdvanceToken();
                    if(!currentToken.getTokenType().equals(MingolToken.EOL)){
                        ExpectedToken(MingolToken.SEMICOLON);
                        return null;
                    }
                    else{
                        AdvanceToken();
                        return skip;
                    }
                }
            }
        };

        IPrefixParseFn ParseComments = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                Token thisToken = currentToken;
                do {
                    AdvanceToken();
                } while (currentToken.getTokenType() != MingolToken.EOF
                        && currentToken.getTokenType() != MingolToken.COMMENT);
                return new Expression(thisToken);
            }
        };

        IPrefixParseFn ParseAlgol = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                errors.add("\tADVERTENCIA: Instruccion " + currentToken.getLiteral() + " no soportada por esta version");
                Token thisToken = currentToken;
                while (currentToken.getTokenType() != MingolToken.EOL
                        && currentToken.getTokenType() != MingolToken.EOF) {
                    AdvanceToken();
                }
                return new Expression(thisToken);
            }
        };

        IPrefixParseFn ParseIllegal = new IPrefixParseFn() {
            @Override
            public Expression Function() {
                errors.add("Error 201: Token ilegal: " + currentToken.getLiteral());
                hasErrors = true;
                return null;
            }
        };

        //Se agregan las funciones
        HashMap<MingolToken, IPrefixParseFn> functions = new HashMap<MingolToken, IPrefixParseFn>();
        functions.put(MingolToken.IDENT, ParseIdentifier);
        functions.put(MingolToken.INTEGER, ParseInteger);
        functions.put(MingolToken.REAL, ParseReal);
        functions.put(MingolToken.SUBSTRACTION, ParsePrefixExpressions);
        functions.put(MingolToken.NEGATION, ParsePrefixExpressions);
        functions.put(MingolToken.TRUE, ParseBoolean);
        functions.put(MingolToken.FALSE, ParseBoolean);
        functions.put(MingolToken.LPAREN, ParseGroupedExpression);
        functions.put(MingolToken.IF, ParseIf);
        functions.put(MingolToken.STRING, ParseStringLiteral);
        functions.put(MingolToken.FOR, ParseFor);
        functions.put(MingolToken.READ, ParseRead);
        functions.put(MingolToken.PRINT, ParsePrint);
        functions.put(MingolToken.SKIP, ParseSkip);
        functions.put(MingolToken.ILLEGAL, ParseIllegal);
        functions.put(MingolToken.ALGOL, ParseAlgol);
        functions.put(MingolToken.COMMENT, ParseComments);
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

        IInfixParseFn ParseCall = new IInfixParseFn() {
            @Override
            public Expression Function(Expression expression) {
                assert currentToken != null :
                        "El current token es null";
                Call call = new Call(currentToken, expression);

                call.setArguments(ParseCallArguments());

                return call;
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
        functions.put(MingolToken.LPAREN, ParseCall);
        return functions;
    }
}
