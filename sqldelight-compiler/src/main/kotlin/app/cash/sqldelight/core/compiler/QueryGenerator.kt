package app.cash.sqldelight.core.compiler

import app.cash.sqldelight.core.compiler.integration.javadocText
import app.cash.sqldelight.core.compiler.model.BindableQuery
import app.cash.sqldelight.core.compiler.model.NamedExecute
import app.cash.sqldelight.core.compiler.model.NamedQuery
import app.cash.sqldelight.core.dialect.api.SqlDelightDialect
import app.cash.sqldelight.core.lang.DRIVER_NAME
import app.cash.sqldelight.core.lang.IntermediateType
import app.cash.sqldelight.core.lang.PREPARED_STATEMENT_TYPE
import app.cash.sqldelight.core.lang.util.childOfType
import app.cash.sqldelight.core.lang.util.findChildrenOfType
import app.cash.sqldelight.core.lang.util.isArrayParameter
import app.cash.sqldelight.core.lang.util.range
import app.cash.sqldelight.core.lang.util.rawSqlText
import app.cash.sqldelight.core.psi.SqlDelightStmtClojureStmtList
import com.alecstrong.sql.psi.core.psi.SqlBinaryEqualityExpr
import com.alecstrong.sql.psi.core.psi.SqlBindExpr
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.alecstrong.sql.psi.core.psi.SqlTypes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.NameAllocator

abstract class QueryGenerator(private val query: BindableQuery, protected val dialect: SqlDelightDialect) {
  /**
   * Creates the block of code that prepares [query] as a prepared statement and binds the
   * arguments to it. This code block does not make any use of class fields, and only populates a
   * single variable [STATEMENT_NAME]
   *
   * val numberIndexes = createArguments(count = number.size)
   * val statement = database.prepareStatement("""
   *     |SELECT *
   *     |FROM player
   *     |WHERE number IN $numberIndexes
   *     """.trimMargin(), SqlPreparedStatement.Type.SELECT, 1 + (number.size - 1))
   * number.forEachIndexed { index, number ->
   *     check(this is SqlCursorSubclass)
   *     statement.bindLong(index + 2, number)
   *     }
   */
  protected fun executeBlock(): CodeBlock {
    val result = CodeBlock.builder()

    if (query is NamedExecute && query.statement is SqlDelightStmtClojureStmtList) {
      query.statement.findChildrenOfType<SqlStmt>().forEachIndexed { index, statement ->
        result.add(executeBlock(statement, query.idForIndex(index)))
      }
    } else {
      result.add(executeBlock(query.statement, query.id))
    }

    return result.build()
  }

  private fun executeBlock(
    statement: PsiElement,
    id: Int
  ): CodeBlock {
    val result = CodeBlock.builder()

    val positionToArgument = mutableListOf<Triple<Int, BindableQuery.Argument, SqlBindExpr?>>()
    val seenArgs = mutableSetOf<BindableQuery.Argument>()
    val duplicateTypes = mutableSetOf<IntermediateType>()
    query.arguments.forEach { argument ->
      if (argument.bindArgs.isNotEmpty()) {
        argument.bindArgs
          .filter { PsiTreeUtil.isAncestor(statement, it, true) }
          .forEach { bindArg ->
            if (!seenArgs.add(argument)) {
              duplicateTypes.add(argument.type)
            }
            positionToArgument.add(Triple(bindArg.node.textRange.startOffset, argument, bindArg))
          }
      } else {
        positionToArgument.add(Triple(0, argument, null))
      }
    }

    val bindStatements = CodeBlock.builder()
    val replacements = mutableListOf<Pair<IntRange, String>>()
    val argumentCounts = mutableListOf<String>()

    var needsFreshStatement = false

    val seenArrayArguments = mutableSetOf<BindableQuery.Argument>()

    val argumentNameAllocator = NameAllocator().apply {
      query.arguments.forEach { newName(it.type.name) }
    }

    // A list of [SqlBindExpr] in order of appearance in the query.
    val orderedBindArgs = positionToArgument.sortedBy { it.first }

    // The number of non-array bindArg's we've encountered so far.
    var nonArrayBindArgsCount = 0

    // A list of arrays we've encountered so far.
    val precedingArrays = mutableListOf<String>()

    val extractedVariables = mutableMapOf<IntermediateType, String>()
    // extract the variable for duplicate types, so we don't encode twice
    for (type in duplicateTypes) {
      if (type.bindArg?.isArrayParameter() == true) continue
      val encodedJavaType = type.encodedJavaType() ?: continue
      val variableName = argumentNameAllocator.newName(type.name)
      extractedVariables[type] = variableName
      bindStatements.addStatement("val %N = $encodedJavaType", variableName)
    }
    // For each argument in the sql
    orderedBindArgs.forEach { (_, argument, bindArg) ->
      val type = argument.type
      // Need to replace the single argument with a group of indexed arguments, calculated at
      // runtime from the list parameter:
      // val idIndexes = id.mapIndexed { index, _ -> "?${1 + previousArray.size + index}" }.joinToString(prefix = "(", postfix = ")")
      val offset = (precedingArrays.map { "$it.size" } + "${nonArrayBindArgsCount + 1}")
        .joinToString(separator = " + ")
      if (bindArg?.isArrayParameter() == true) {
        needsFreshStatement = true

        if (seenArrayArguments.add(argument)) {
          result.addStatement(
            """
            |val ${type.name}Indexes = createArguments(count = ${type.name}.size)
          """.trimMargin()
          )
        }

        // Replace the single bind argument with the array of bind arguments:
        // WHERE id IN ${idIndexes}
        replacements.add(bindArg.range to "\$${type.name}Indexes")

        // Perform the necessary binds:
        // id.forEachIndex { index, parameter ->
        //   statement.bindLong(1 + previousArray.size + index, parameter)
        // }
        val indexCalculator = "index + $offset"
        val elementName = argumentNameAllocator.newName(type.name)
        bindStatements.addStatement(
          """
          |${type.name}.forEachIndexed { index, $elementName ->
          |%L}
        """.trimMargin(),
          type.copy(name = elementName).preparedStatementBinder(indexCalculator)
        )

        precedingArrays.add(type.name)
        argumentCounts.add("${type.name}.size")
      } else {
        nonArrayBindArgsCount += 1
        if (type.javaType.isNullable) {
          val parent = bindArg?.parent
          if (parent is SqlBinaryEqualityExpr) {
            needsFreshStatement = true

            var symbol = parent.childOfType(SqlTypes.EQ) ?: parent.childOfType(SqlTypes.EQ2)
            val nullableEquality: String
            if (symbol != null) {
              nullableEquality = "${symbol.leftWhitspace()}IS${symbol.rightWhitespace()}"
            } else {
              symbol = parent.childOfType(SqlTypes.NEQ) ?: parent.childOfType(SqlTypes.NEQ2)!!
              nullableEquality = "${symbol.leftWhitspace()}IS NOT${symbol.rightWhitespace()}"
            }

            val block = CodeBlock.of("if (${type.name} == null) \"$nullableEquality\" else \"${symbol.text}\"")
            replacements.add(symbol.range to "\${ $block }")
          }
        }
        // Binds each parameter to the statement:
        // statement.bindLong(1, id)
        bindStatements.add(type.preparedStatementBinder(offset, extractedVariables[type]))

        // Replace the named argument with a non named/indexed argument.
        // This allows us to use the same algorithm for non Sqlite dialects
        // :name becomes ?
        if (bindArg != null) {
          replacements.add(bindArg.range to "?")
        }
      }
    }

    // Adds the actual SqlPreparedStatement:
    // statement = database.prepareStatement("SELECT * FROM test")
    val executeMethod = if (query is NamedQuery) {
      "return $DRIVER_NAME.executeQuery"
    } else {
      "$DRIVER_NAME.execute"
    }
    if (nonArrayBindArgsCount != 0) {
      argumentCounts.add(0, nonArrayBindArgsCount.toString())
    }
    val arguments = mutableListOf<Any>(
      statement.rawSqlText(replacements),
      argumentCounts.ifEmpty { listOf(0) }.joinToString(" + ")
    )
    val binder: String

    if (argumentCounts.isEmpty()) {
      binder = ""
    } else {
      val binderLambda = CodeBlock.builder()
        .addStatement(" {")
        .indent()

      if (PREPARED_STATEMENT_TYPE != dialect.preparedStatementType) {
        binderLambda.addStatement("check(this is %T)", dialect.preparedStatementType)
      }

      binderLambda.add(bindStatements.build())
        .unindent()
        .add("}")
      arguments.add(binderLambda.build())
      binder = "%L"
    }
    result.add(
      "$executeMethod(" +
        "${if (needsFreshStatement) "null" else "$id"}," +
        " %P," +
        " %L" +
        ")$binder\n",
      *arguments.toTypedArray()
    )

    return result.build()
  }

  private fun PsiElement.leftWhitspace(): String {
    return if (prevSibling is PsiWhiteSpace) "" else " "
  }

  private fun PsiElement.rightWhitespace(): String {
    return if (nextSibling is PsiWhiteSpace) "" else " "
  }

  protected fun addJavadoc(builder: FunSpec.Builder) {
    if (query.javadoc != null) javadocText(query.javadoc)?.let { builder.addKdoc(it) }
  }
}
