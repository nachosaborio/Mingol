# MINGOL

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Apache Ant](https://img.shields.io/badge/Apache%20Ant-1.10-A81C7D?logo=apacheant&logoColor=white)
![NetBeans](https://img.shields.io/badge/NetBeans-12.6-1B6AC6?logo=apachenetbeanside&logoColor=white)
![ALGOL 68](https://img.shields.io/badge/ALGOL%2068-a68g-5C6BC0)
![Windows](https://img.shields.io/badge/Windows-CMD-0078D6?logo=windows&logoColor=white)

Compilador de **MINGOL**, un subconjunto simplificado de ALGOL 68, escrito en Java. Analiza un programa fuente `.MINGOL` (léxico, sintaxis y semántica), genera un archivo de errores con el fuente numerado y, si el programa no tiene errores, lo ejecuta con el intérprete de ALGOL 68 [`a68g`](https://jmvdveer.home.xs4all.nl/en.algol-68-genie.html).

> Proyecto académico desarrollado para la materia **03307 Compiladores** del Bachillerato en Ingeniería Informática de la **Universidad Estatal a Distancia de Costa Rica (UNED)**.

## Cómo funciona

```
java --enable-preview -jar Mingol.jar <Programa>.MINGOL
```

1. Lee el archivo fuente línea por línea.
2. Hace el análisis léxico, sintáctico y semántico.
3. Genera `<Programa>-errores.txt` en la carpeta desde donde se ejecuta, con cada línea del fuente numerada a 5 dígitos y los mensajes debajo de la línea donde ocurren:

   ```
   00003 x = 3;
           ERROR 204: Se esperaba un token de tipo IDENT pero se obtuvo EQ [=].
   00004 print ( (b) );
           ERROR 303: Identificador no encontrado [b].
   ```

   Los códigos siguen el esquema 1xx léxico, 2xx sintáctico y 3xx semántico. Las instrucciones de ALGOL que MINGOL no soporta generan una advertencia, que no impide la ejecución.
4. Si no hay errores, copia el fuente a `<Programa>.a68` y lo ejecuta con `a68g` en la misma consola.

La extensión `.MINGOL` puede omitirse al invocarlo.

## Requisitos

- JDK 17 (el proyecto se compila con `--enable-preview`).
- Apache Ant 1.10 o NetBeans 12.6 para compilar.
- `a68g` (Algol 68 Genie) para ejecutar los programas válidos. MINGOL lo busca en este orden:
  1. La ruta completa indicada en la variable de entorno `A68G`.
  2. `a68g.exe` en la misma carpeta que el `.jar`.
  3. El `PATH` del sistema.

  Por seguridad no se aceptan archivos `.bat` ni `.cmd`.

## Compilar y ejecutar

Con Ant, desde la raíz del proyecto:

```
ant compile
java --enable-preview -cp build\classes Mingol.Mingol prueba.mingol
```

También se puede abrir como proyecto de NetBeans (`nbproject/`).

> **Nota:** `dist/Mingol.jar` corresponde a la versión original de la entrega y no incluye las correcciones posteriores. Para usarlas, compilar con `ant compile` (o generar un `.jar` nuevo con `ant jar`).

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `src/Lexer` | Analizador léxico y definición de tokens |
| `src/Parser` | Analizador sintáctico y nodos del árbol (AST) |
| `src/Evaluador` | Análisis semántico |
| `src/Errores` | Mensajes estructurados y escritura del archivo de errores |
| `src/Mingol` | Punto de entrada, generación del `.a68` e invocación de `a68g` |
| `tests/arneses` | Arneses de prueba (sin JUnit) |

## Estado

Las correcciones de los errores críticos y del formato del archivo de errores están hechas. Quedan pendientes las validaciones de léxico, sintaxis, semántica y del nombre del archivo de entrada.

## Créditos

Desarrollado por Sergio Ignacio Saborío Segura para el curso 03307 Compiladores, Escuela de Ciencias Exactas y Naturales, UNED Costa Rica (primer cuatrimestre de 2022).