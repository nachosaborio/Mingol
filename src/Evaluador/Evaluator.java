package Evaluador;

// Área 4: nodos que valida el evaluador (asignación, FOR, read)
import Parser.Asignacion;
import Parser.Block;
import Parser.Booleano;
import Parser.Decimal;
import Parser.Declaracion;
import Parser.Etiqueta;
import Parser.Expression;
import Parser.ExpressionStatement;
import Parser.For;
import Parser.Goto;
import Parser.Identifier;
import Parser.If;
import Parser.Infix;
import Parser.Integral;
import Parser.Interfaces.ASTNode;
import Parser.LetStatement;
import Parser.Prefix;
import Parser.Print;
import Parser.Program;
import Parser.Read;
import Parser.Secuencia;
import Parser.Statement;
import Parser.StringLiteral;
import Errores.Mensaje;
import java.util.ArrayList;

public class Evaluator {

    public static boolean hasErrors = false;
    // Mensajes estructurados del evaluador para el archivo de errores (único sitio de registro: NewError)
    private static ArrayList<Mensaje> mensajes = new ArrayList<>();
    public static final Logico TRUE = new Logico(true);
    public static final Logico FALSE = new Logico(false);
    public static final Nulo NULL = new Nulo();
    private static String TypeMismatchError = "\tError 301: Discrepancia de tipos:";
    private static String UnknownPrefixOperation = "\tError 302: Operador inválido:";
    private static String UnknownIdentifier = "\tError 303: Identificador no encontrado:";
    private static String RepeatedIdentificator = "\tError 304: Identificador ya declarado:";
    private static String AlreadyBegin = "\tError 305: No puede haber más de una sentencia BEGIN por archivo.";
    // END repetido usa 306 (antes repetía el 305 de BEGIN)
    private static String AlreadyEnded = "\tError 306: No puede haber más de una sentencia END por archivo.";
    private static String NotBegined = "\tError 307: No se encontró una sentencia BEGIN.";
    private static String NotEnded = "\tError 308: No se encontró una sentencia END.";
    // Área 4: textos del Errado para 309, 310 y 311 (se usan en asignación y FOR)
    private static String CharLargo = "\tError 309: Un CHAR es un solo carácter:";
    private static String CicloNoEntero = "\tError 310: La variable de ciclo del FOR no es INT:";
    private static String RangoNoEntero = "\tError 311: Valor de FROM/BY/TO no es constante entera ni variable INT:";

    // Mensajes registrados por el evaluador desde el último Reiniciar()
    public static ArrayList<Mensaje> GetMensajes() {
        return mensajes;
    }

    // Deja el estado estático limpio para analizar otro programa en la misma JVM
    public static void Reiniciar() {
        mensajes.clear();
        hasErrors = false;
    }

    public static Objeto Evaluate(ASTNode node, Environment env) {
        // El parser pudo dejar nodos incompletos (null): no hay nada que evaluar
        if (node == null) {
            return null;
        }
        String className = node.getClass().getSimpleName();

        switch (className) {
            case "Program":
                Program program = (Program) node;
                // Área 4: pasada previa que registra todas las etiquetas (GOTO hacia adelante, M9)
                RegistrarEtiquetas(program.statements, env);
                Objeto evaluado = EvaluateProgram(program, env);
                // Tras recorrer todo el programa: 307 y/o 308 solo si de verdad falta BEGIN/END
                Errado faltante = null;
                if(env.get("BEGIN") == null){
                    faltante = NewError(Mensaje.SIN_LINEA, 307, "No se encontró una sentencia BEGIN", "BEGIN",
                            NotBegined, new String[]{});
                }
                if(env.get("END") == null){
                    Errado sinEnd = NewError(Mensaje.SIN_LINEA, 308, "No se encontró una sentencia END", "END",
                            NotEnded, new String[]{});
                    if (faltante == null) {
                        faltante = sinEnd;
                    }
                }
                // Se devuelve el primero de los faltantes; si no hay, lo evaluado
                return faltante != null ? faltante : evaluado;
            case "Statement":
                Statement statement = (Statement) node;
                return EvaluateStatement(statement, env);
            case "ExpressionStatement":
                ExpressionStatement expression = (ExpressionStatement) node;
                // Expresión null del parser (p. ej. IF sin FI): su error ya se registró
                if (expression.getExpression() == null) {
                    return null;
                }
                assert expression.getExpression() != null :
                        "La expresion es nula";
                return Evaluate(expression.getExpression(), env);
            case "Integral":
                Integral integral = (Integral) node;
                assert integral != null :
                        "El entero es nulo";
                return new Entero(integral.getValue());
            case "Decimal":
                Decimal real = (Decimal) node;
                assert real != null :
                        "El real es nulo";
                return new Real(real.getValue());
            case "Booleano":
                Booleano logico = (Booleano) node;
                assert logico != null :
                        "El booleano es nulo";
                return ToBooleanObject(logico.getValue());
            case "Prefix":
                Prefix prefix = (Prefix) node;
                // Operando null del parser: su error ya se registró, sin error nuevo
                if (prefix.getRight() == null) {
                    return null;
                }
                assert prefix.getRight() != null :
                        "La derecha es nula";
                Objeto right = Evaluate(prefix.getRight(), env);
                // Un Errado del operando se propaga sin crear otro; un resultado null no se evalúa
                if (right == null || right instanceof Errado) {
                    return right;
                }
                assert right != null :
                        "Right es nulo";
                return EvaluatePrefixExpression(prefix, prefix.getOperator(), right);
            case "Infix":
                Infix infix = (Infix) node;
                // Se evalúan los dos lados (Evaluate(null) da null) para registrar los errores de ambos
                Objeto left = Evaluate(infix.getLeft(), env);
                Objeto iright = Evaluate(infix.getRight(), env);
                // Un Errado del operando se propaga sin crear un 301 en cascada
                if (left instanceof Errado) {
                    return left;
                }
                if (iright instanceof Errado) {
                    return iright;
                }
                // Operando null (parser o código no pedido): null sin error
                if (left == null || iright == null) {
                    return null;
                }
                assert infix.getLeft() != null :
                        "La izquierda es nula";
                assert infix.getRight() != null :
                        "La derecha es nula";
                assert left != null :
                        "La izquierda es nula";
                assert iright != null :
                        "La derecha es nula";
                return EvaluateInfixExpression(infix, infix.getOperator(), left, iright);
            case "Block":
                Block block = (Block) node;
                return EvaluateStatements(block.getStatements(), env);
            case "If":
                If si = (If) node;
                return EvaluateIfExpression(si, env);
            case "LetStatement":
                LetStatement let = (LetStatement) node;
                assert let != null :
                        "El identificador es nulo";
                Objeto value = Evaluate(let.getValue(), env);
                assert let.getName() != null :
                        "El nombre del identificador es nulo";
                // El parser pudo dejar el valor en null: la variable cuenta igual como declarada
                if (value == null) {
                    value = NULL;
                }
                if (env.get(let.getName().getValue()) == null) {
                    // Se guarda aunque sea un Errado: la variable cuenta como declarada (sin 303 en cascada)
                    env.put(let.getName().getValue(), value);
                    // Área 4 (DM2): el tipo declarado sale del token del LetStatement; una repetida no lo cambia
                    String tipoLet = let.TokenLiteral();
                    if ("INT".equals(tipoLet) || "REAL".equals(tipoLet) || "CHAR".equals(tipoLet)) {
                        env.DeclararTipo(let.getName().getValue(), tipoLet);
                    }
                } else {
                    Errado repetido = NewError(let.getName().getLine(), 304, "Identificador ya declarado",
                            let.getName().getValue(), RepeatedIdentificator,
                            new String[]{let.getName().getValue()});
                    // Se devuelve el primer error de la sentencia (el del valor, si lo hubo)
                    return (value instanceof Errado) ? value : repetido;
                }
                // El error del valor inicial ya no se descarta: se devuelve para que EvaluateProgram lo vea
                if (value instanceof Errado) {
                    return value;
                }
                break;
            case "Declaracion":
                // Área 3: cada variable con el código de LetStatement (NULL, 304); se devuelve el primer Errado
                Declaracion declaracion = (Declaracion) node;
                Errado primeroDeclaracion = null;
                for (LetStatement variable : declaracion.getVariables()) {
                    Objeto resultado = Evaluate(variable, env);
                    if (resultado instanceof Errado && primeroDeclaracion == null) {
                        primeroDeclaracion = (Errado) resultado;
                    }
                }
                return primeroDeclaracion;
            case "Secuencia":
                // Área 3 (S3): solo se validan los identificadores (303 como hoy); no se calculan valores
                Secuencia secuencia = (Secuencia) node;
                Errado primeroSecuencia = null;
                for (Identifier id : secuencia.getIdentificadores()) {
                    Objeto resultado = EvaluateIdentifier(id, env);
                    if (resultado instanceof Errado && primeroSecuencia == null) {
                        primeroSecuencia = (Errado) resultado;
                    }
                }
                return primeroSecuencia;
            case "Asignacion":
                // Área 4 (2.3–2.6): 303 de izquierda y operandos, 301 por operando incompatible, 309; sin calcular
                Asignacion asignacion = (Asignacion) node;
                Errado primeroAsignacion = null;
                String tipoIzq = null;
                Identifier izquierda = asignacion.getVariable();
                if (izquierda != null) {
                    if (!env.EsVariable(izquierda.getValue())) {
                        // Izquierda inexistente o etiqueta: 303 y sin revisión de tipos
                        primeroAsignacion = NewError(izquierda.getLine(), 303, "Identificador no encontrado",
                                izquierda.getValue(), UnknownIdentifier, new String[]{izquierda.getValue()});
                    } else {
                        tipoIzq = env.TipoDe(izquierda.getValue());
                    }
                }
                Secuencia derecha = asignacion.getExpresion();
                if (derecha != null) {
                    // Operandos en orden del fuente; las palabras ALGOL no se miran (M5)
                    for (Expression operando : derecha.getElementos()) {
                        Errado error = null;
                        String tipoOperando = null;
                        boolean conTipo = true;
                        if (operando instanceof Identifier) {
                            String nombre = ((Identifier) operando).getValue();
                            if (!env.EsVariable(nombre)) {
                                // 303 por aparición (M13); no participa en tipos
                                error = NewError(operando.getLine(), 303, "Identificador no encontrado",
                                        nombre, UnknownIdentifier, new String[]{nombre});
                                conTipo = false;
                            } else {
                                tipoOperando = env.TipoDe(nombre);
                                // Variable sin tipo conocido: se salta (DM2)
                                conTipo = tipoOperando != null;
                            }
                        } else if (operando instanceof Integral) {
                            tipoOperando = "INT";
                        } else if (operando instanceof Decimal) {
                            tipoOperando = "REAL";
                        } else if (operando instanceof StringLiteral) {
                            tipoOperando = "CHAR";
                        }
                        // Booleano queda sin tipo: incompatible con todo
                        if (error == null && conTipo && tipoIzq != null) {
                            boolean izqNumerica = "INT".equals(tipoIzq) || "REAL".equals(tipoIzq);
                            boolean opNumerico = "INT".equals(tipoOperando) || "REAL".equals(tipoOperando);
                            boolean compatible = izqNumerica ? opNumerico
                                    : ("CHAR".equals(tipoIzq) && "CHAR".equals(tipoOperando));
                            String lexema = LexemaOperando(operando);
                            if (!compatible) {
                                // 301 sin sufijo de tipos (DM6), uno por operando (M7)
                                error = NewError(operando.getLine(), 301, "Discrepancia de tipos", lexema,
                                        TypeMismatchError, new String[]{lexema});
                            } else if ("CHAR".equals(tipoIzq) && operando instanceof StringLiteral
                                    && ((StringLiteral) operando).getValue().length() != 1) {
                                // 309: texto de longitud distinta de 1 hacia CHAR (M8); nunca junto a un 301
                                error = NewError(operando.getLine(), 309, "Un CHAR es un solo carácter", lexema,
                                        CharLargo, new String[]{lexema});
                            }
                        }
                        if (error != null && primeroAsignacion == null) {
                            primeroAsignacion = error;
                        }
                    }
                }
                return primeroAsignacion;
            case "Identifier":
                Identifier identifier = (Identifier) node;
                return EvaluateIdentifier(identifier, env);
            case "Call":
                break;
            case "StringLiteral":
                StringLiteral str = (StringLiteral) node;
                return new Cadena(str.getValue());
            case "Etiqueta":
                Etiqueta etiqueta = (Etiqueta) node;
                // Área 4: espacio de etiquetas propio; una variable homónima no interviene (M11)
                Object primera = env.PrimeraEtiqueta(etiqueta.getValue());
                if (primera == null) {
                    // Sin pasada previa (REPL, unitarias): se registra aquí, como antes
                    env.RegistrarEtiqueta(etiqueta.getValue(), etiqueta);
                } else if (primera != etiqueta) {
                    // 304 bajo la segunda aparición (DM3, M10)
                    return NewError(etiqueta.getLine(), 304, "Identificador ya declarado", etiqueta.getValue(),
                            RepeatedIdentificator, new String[]{etiqueta.getValue()});
                }
                break;
            case "Goto":
                Goto gouto = (Goto) node;
                // Área 4: solo vale una etiqueta del programa (también posterior); una variable da 303 (M9)
                if(!env.EsEtiqueta(gouto.getIdentificador().TokenLiteral())) {
                    // 303 con la línea del identificador del GOTO
                    return NewError(gouto.getIdentificador().getLine(), 303, "Identificador no encontrado",
                            gouto.getIdentificador().TokenLiteral(), UnknownIdentifier,
                            new String[]{gouto.getIdentificador().toString()});
                }
                break;
            case "Print":
                Print print = (Print) node;
                // Se registran todos los identificadores no definidos y se devuelve el primero
                Errado primeroPrint = null;
                if(print.getIdentificadores().size() >0){
                    for(Expression identificador : print.getIdentificadores()){
                        // Área 4: solo una variable vale; una etiqueta o una marca da 303 (2.11)
                        if(!env.EsVariable(identificador.TokenLiteral())){
                            Errado noDefinido = NewError(identificador.getLine(), 303, "Identificador no encontrado",
                                    identificador.TokenLiteral(), UnknownIdentifier,
                                    new String[]{identificador.TokenLiteral()});
                            if (primeroPrint == null) {
                                primeroPrint = noDefinido;
                            }
                        }
                    }
                }
                if (primeroPrint != null) {
                    return primeroPrint;
                }
                break;
            case "Read":
                Read leer = (Read) node;
                // Área 4 (2.10): read de algo que no es variable da 303; cualquier tipo vale; read ( ) sin mensaje
                Expression leida = leer.getIdentificador();
                if (leida != null && !env.EsVariable(leida.TokenLiteral())) {
                    return NewError(leida.getLine(), 303, "Identificador no encontrado",
                            leida.TokenLiteral(), UnknownIdentifier,
                            new String[]{leida.TokenLiteral()});
                }
                break;
            case "For":
                // Área 4 (2.7–2.9, M12): cabecera 303/310/311 y cuerpo evaluado; devuelve el primer Errado
                For ciclo = (For) node;
                Errado primeroFor = null;
                Expression variableCiclo = ciclo.getCiclo();
                // Campo null (cabecera con 204): se salta sin mensaje (3.10)
                if (variableCiclo != null) {
                    String nombreCiclo = variableCiclo.TokenLiteral();
                    if (!env.EsVariable(nombreCiclo)) {
                        // No es variable (inexistente o etiqueta, DM8): 303
                        primeroFor = NewError(variableCiclo.getLine(), 303, "Identificador no encontrado",
                                nombreCiclo, UnknownIdentifier, new String[]{nombreCiclo});
                    } else {
                        String tipoCiclo = env.TipoDe(nombreCiclo);
                        if ("REAL".equals(tipoCiclo) || "CHAR".equals(tipoCiclo)) {
                            // Variable de ciclo REAL o CHAR: 310
                            primeroFor = NewError(variableCiclo.getLine(), 310,
                                    "La variable de ciclo del FOR no es INT", nombreCiclo,
                                    CicloNoEntero, new String[]{nombreCiclo});
                        }
                    }
                }
                // FROM, BY y TO: un mensaje por límite, sin colapsar (M12)
                for (Expression limite : new Expression[]{ciclo.getMinima(), ciclo.getIncremento(), ciclo.getMaxima()}) {
                    // Integral siempre vale; null (204 o 102) se salta sin mensaje
                    if (!(limite instanceof Identifier)) {
                        continue;
                    }
                    String nombreLimite = ((Identifier) limite).getValue();
                    Errado errorLimite = null;
                    if (!env.EsVariable(nombreLimite)) {
                        errorLimite = NewError(limite.getLine(), 303, "Identificador no encontrado",
                                nombreLimite, UnknownIdentifier, new String[]{nombreLimite});
                    } else {
                        String tipoLimite = env.TipoDe(nombreLimite);
                        if ("REAL".equals(tipoLimite) || "CHAR".equals(tipoLimite)) {
                            errorLimite = NewError(limite.getLine(), 311,
                                    "Valor de FROM/BY/TO no es constante entera ni variable INT", nombreLimite,
                                    RangoNoEntero, new String[]{nombreLimite});
                        }
                    }
                    if (errorLimite != null && primeroFor == null) {
                        primeroFor = errorLimite;
                    }
                }
                // Cuerpo con las mismas reglas; sus etiquetas ya están en la pasada previa (x06, M10)
                Objeto cuerpo = Evaluate(ciclo.getComands(), env);
                if (cuerpo instanceof Errado && primeroFor == null) {
                    primeroFor = (Errado) cuerpo;
                }
                return primeroFor;
        }
        return null;
    }

    private static Objeto EvaluateBangOperatorExpression(Objeto right) {
        if (right == TRUE) {
            return FALSE;
        } else if (right == FALSE) {
            return TRUE;
        } else if (right == NULL) {
            return TRUE;
        } else {
            return FALSE;
        }
    }

    // Área 4: lexema de un operando de asignación; "" → STRING (DM4), constantes con su literal sin "-" (DM5)
    private static String LexemaOperando(Expression operando) {
        if (operando instanceof Identifier) {
            return ((Identifier) operando).getValue();
        }
        if (operando instanceof StringLiteral) {
            String texto = ((StringLiteral) operando).getValue();
            return (texto == null || texto.isEmpty()) ? "STRING" : texto;
        }
        return operando.TokenLiteral();
    }

    private static Objeto EvaluateIdentifier(Identifier identifier, Environment env) {
        // Solo las variables (Objeto) se resuelven; etiquetas y marcas BEGIN/END cuentan como no definidas
        Object guardado = env.get(identifier.getValue());
        if (!(guardado instanceof Objeto)) {
            // Nueva firma: 303 con la línea del identificador
            return NewError(identifier.getLine(), 303, "Identificador no encontrado", identifier.getValue(),
                    UnknownIdentifier, new String[]{identifier.getValue()});
        }
        return (Objeto) guardado;
    }

    private static Objeto EvaluateIfExpression(If si, Environment env) {
        assert si != null :
                "El if es nulo";
        // Área 3 (DS4): sin valores no hay rama que elegir; se evalúan la condición (conserva su 303),
        // la consecuencia y la alternativa, y se devuelve el primer Errado o null (Evaluate(null) da null)
        Objeto condition = Evaluate(si.getCondition(), env);
        Objeto consecuencia = Evaluate(si.getConsequence(), env);
        Objeto alternativa = Evaluate(si.getAlternative(), env);
        if (condition instanceof Errado) {
            return condition;
        }
        if (consecuencia instanceof Errado) {
            return consecuencia;
        }
        if (alternativa instanceof Errado) {
            return alternativa;
        }
        return null;
    }

    // Se recibe el nodo Infix para dar la línea a los 301/302
    private static Objeto EvaluateInfixExpression(Infix nodo, String operator, Objeto left, Objeto right) {
        if (left.Type() == ObjectType.INTEGERS && right.Type() == ObjectType.INTEGERS) {
            return EvaluateIntegerInfixExpression(nodo, operator, left, right);
        } else if (operator.equals("=") || operator.equals("EQ")) {
            return ToBooleanObject(left == right);
        } else if (operator.equals("/=") || operator.equals("NE")) {
            return ToBooleanObject(left != right);
        } else if (left.Type() != right.Type()) {
            String[] args = new String[]{left.Type().toString(), operator, right.Type().toString()};
            return NewError(nodo.getLine(), 301, "Discrepancia de tipos: " + String.join(" ", args), operator,
                    TypeMismatchError, args);
        } else {
            String[] args = new String[]{left.Type().toString(), operator, right.Type().toString()};
            return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), operator,
                    UnknownPrefixOperation, args);
        }
    }

    // Se recibe el nodo Infix para dar la línea al 302
    private static Objeto EvaluateIntegerInfixExpression(Infix nodo, String operator, Objeto left, Objeto right) {
        int leftValue = ((Entero) left).getValue();
        int rigthValue = ((Entero) right).getValue();

        switch (operator) {
            case "+":
                return new Entero(leftValue + rigthValue);
            case "-":
                return new Entero(leftValue - rigthValue);
            case "*":
                return new Entero(leftValue * rigthValue);
            case "/":
                return new Entero(leftValue / rigthValue);
            case "LT":
            case "<":
                return ToBooleanObject(leftValue < rigthValue);
            case "GT":
            case ">":
                return ToBooleanObject(leftValue > rigthValue);
            case "EQ":
            case "=":
                return ToBooleanObject(leftValue == rigthValue);
            case "NE":
            case "/=":
                return ToBooleanObject(leftValue != rigthValue);
            case "GE":
            case ">=":
                return ToBooleanObject(leftValue >= rigthValue);
            case "LE":
            case "<=":
                return ToBooleanObject(leftValue <= rigthValue);
            default:
                String[] args = new String[]{left.Type().toString(), operator, right.Type().toString()};
                return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), operator,
                        UnknownPrefixOperation, args);
        }
    }

    // Se recibe el nodo Prefix para dar la línea al 302
    private static Objeto EvaluateMinusOperatorExpression(Prefix nodo, Objeto right) {
        if (!(right instanceof Entero)) {
            String[] args = new String[]{"-", right.Type().toString()};
            return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), "-",
                    UnknownPrefixOperation, args);
        } else {
            Entero entero = (Entero) right;
            return new Entero(-entero.getValue());
        }
    }

    // Se recibe el nodo Prefix para dar la línea al 302
    private static Objeto EvaluatePrefixExpression(Prefix nodo, String operator, Objeto right) {

        switch (operator) {
            case "!":
                return EvaluateBangOperatorExpression(right);
            case "-":
                return EvaluateMinusOperatorExpression(nodo, right);
            default:
                String[] args = new String[]{operator, right.Type().toString()};
                return NewError(nodo.getLine(), 302, "Operador inválido: " + String.join(" ", args), operator,
                        UnknownPrefixOperation, args);
        }
    }

    private static Objeto EvaluateProgram(Program program, Environment env) {
        Objeto result = null;
        Objeto primerError = null;
        // Ya no corta en el primer Errado: recorre todo (BEGIN/END llegan a env) y devuelve el primero
        for (Statement statement : program.statements) {
            result = Evaluate(statement, env);
            if (result instanceof Errado && primerError == null) {
                primerError = result;
            }
        }
        return primerError != null ? primerError : result;
    }

    // Área 4: pasada previa sin mensajes; registra cada etiqueta (también en ramas y cuerpos) en orden del fuente
    private static void RegistrarEtiquetas(ArrayList<Statement> statements, Environment env) {
        if (statements == null) {
            return;
        }
        for (Statement statement : statements) {
            // Un Block dentro de la lista también se recorre
            if (statement instanceof Block) {
                RegistrarEtiquetas((Block) statement, env);
                continue;
            }
            if (!(statement instanceof ExpressionStatement)) {
                continue;
            }
            Expression expresion = ((ExpressionStatement) statement).getExpression();
            if (expresion instanceof Etiqueta) {
                Etiqueta etiqueta = (Etiqueta) expresion;
                env.RegistrarEtiqueta(etiqueta.getValue(), etiqueta);
            } else if (expresion instanceof If) {
                If si = (If) expresion;
                RegistrarEtiquetas(si.getConsequence(), env);
                RegistrarEtiquetas(si.getAlternative(), env);
            } else if (expresion instanceof For) {
                RegistrarEtiquetas(((For) expresion).getComands(), env);
            }
        }
    }

    // Área 4: recursión sobre un bloque (rama de IF o cuerpo de FOR); tolera null
    private static void RegistrarEtiquetas(Block bloque, Environment env) {
        if (bloque != null) {
            RegistrarEtiquetas(bloque.getStatements(), env);
        }
    }

    private static Objeto EvaluateStatement(Statement statement, Environment env) {
        switch (statement.TokenLiteral()) {
            case "BEGIN":
                if (env.get("BEGIN") == null) {
                    env.put("BEGIN", "BEGIN");
                } else {
                    // 305 con la línea del BEGIN repetido
                    return NewError(statement.getLine(), 305, "No puede haber más de una sentencia BEGIN por archivo",
                            "BEGIN", AlreadyBegin, new String[]{});
                }
                break;
            case "END":
                if (env.get("END") == null) {
                    env.put("END", "END");
                } else {
                    // 306 con la línea del END repetido
                    return NewError(statement.getLine(), 306, "No puede haber más de una sentencia END por archivo",
                            "END", AlreadyEnded, new String[]{});
                }
                break;
        }
        return null;
    }

    private static Objeto EvaluateStatements(ArrayList<Statement> statements, Environment env) {
        Objeto result = null;
        for (Statement statement : statements) {
            result = Evaluate(statement, env);
        }
        return result;
    }

    // M14: se borró IsTruthy (sin uso desde S3)

    // Arma el mismo Errado que antes (mismo Inspect()) y registra su Mensaje; único sitio de registro
    private static Errado NewError(int linea, int codigo, String texto, String lexema, String message, String[] args) {
        hasErrors = true;
        mensajes.add(Mensaje.Error(linea, codigo, texto, lexema));
        for (Object obj : args) {
            message += " " + obj.toString();
        }
        return new Errado(message);
    }

    private static Logico ToBooleanObject(boolean value) {
        return value ? TRUE : FALSE;
    }
}